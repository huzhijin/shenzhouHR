#!/usr/bin/env bash
set -Eeuo pipefail
export LC_ALL=C
export COPYFILE_DISABLE=1

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="${1:-$REPO_ROOT/release}"
RELEASE_NAME="${RELEASE_NAME:-shenzhouhr-release-$(date -u +%Y%m%d%H%M%S)}"
ALLOW_DIRTY_RELEASE="${ALLOW_DIRTY_RELEASE:-false}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v bash >/dev/null || die 'bash is required'
command -v sha256sum >/dev/null || command -v shasum >/dev/null || die 'sha256sum or shasum is required'
command -v install >/dev/null || die 'install is required to stage customer documentation safely'
[[ "$RELEASE_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
  || die "Unsafe release name: $RELEASE_NAME"
case "$ALLOW_DIRTY_RELEASE" in
  true|false) ;;
  *) die 'ALLOW_DIRTY_RELEASE must be true or false' ;;
esac

if [[ ! -x "$REPO_ROOT/backend/mvnw" ]]; then
  die "Maven wrapper not found: $REPO_ROOT/backend/mvnw"
fi
command -v node >/dev/null || die 'Node.js is required to build the frontend'
command -v npm >/dev/null || die 'npm is required to build the frontend'
command -v python3 >/dev/null || die 'Python 3 is required to run deployment release tests'

JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
[[ -n "$JAVA_BIN" ]] || die 'Java 21 is required'
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown}"
JAR_BIN="${JAR_BIN:-$(dirname -- "$JAVA_BIN")/jar}"
[[ -x "$JAR_BIN" ]] || die "A full JDK 21 is required; jar tool not found: $JAR_BIN"

MIGRATION_SOURCE="$REPO_ROOT/backend/src/main/resources/db/migration"
MIGRATION_FILES=()
MIGRATION_VERSIONS=()
MIGRATION_SORT_INPUT=()
for candidate in "$MIGRATION_SOURCE"/V*__*.sql; do
  [[ -f "$candidate" ]] || continue
  filename="$(basename -- "$candidate")"
  version="${filename#V}"
  version="${version%%__*}"
  [[ "$version" =~ ^[0-9]+$ ]] || die "Invalid Flyway migration filename: $filename"
  MIGRATION_SORT_INPUT+=("$version"$'\t'"$candidate")
done
[[ "${#MIGRATION_SORT_INPUT[@]}" -gt 0 ]] || die 'No Flyway migrations were found'
while IFS=$'\t' read -r version file; do
  [[ -n "$version" && -n "$file" ]] || continue
  MIGRATION_VERSIONS+=("$version")
  MIGRATION_FILES+=("$file")
done < <(
  printf '%s\n' "${MIGRATION_SORT_INPUT[@]}" | sort -n -k1,1
)

expected=1
MIGRATION_VERSION_LIST=""
for version in "${MIGRATION_VERSIONS[@]}"; do
  [[ "$version" -eq "$expected" ]] \
    || die "Flyway migration gap or duplicate: expected V$expected, found V$version"
  if [[ -n "$MIGRATION_VERSION_LIST" ]]; then
    MIGRATION_VERSION_LIST+=","
  fi
  MIGRATION_VERSION_LIST+="V$version"
  expected=$((expected + 1))
done
MIGRATION_MAX="${MIGRATION_VERSIONS[${#MIGRATION_VERSIONS[@]} - 1]}"
MIGRATION_RANGE="V1..V$MIGRATION_MAX"

SOURCE_COMMIT="$(git -C "$REPO_ROOT" rev-parse --verify HEAD 2>/dev/null || true)"
SOURCE_TREE_STATE="clean"
if [[ -z "$SOURCE_COMMIT" ]]; then
  SOURCE_COMMIT="unknown"
  SOURCE_TREE_STATE="unknown"
elif [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all -- \
    README.md api backend frontend deploy docs scripts 2>/dev/null)" ]]; then
  SOURCE_TREE_STATE="modified"
fi
if [[ "$SOURCE_TREE_STATE" != "clean" && "$ALLOW_DIRTY_RELEASE" != "true" ]]; then
  die 'Refusing to build a customer release without a clean, committed source tree'
fi

while IFS= read -r -d '' shell_script; do
  bash -n "$shell_script"
done < <(find "$REPO_ROOT/deploy/baota" -type f -name '*.sh' -print0 | sort -z)
cd "$REPO_ROOT"
python3 -m unittest discover -v scripts/release/tests

cd "$REPO_ROOT/frontend"
npm ci
npm run check

cd "$REPO_ROOT/backend"
./mvnw clean package

JAR_PATH=""
while IFS= read -r candidate; do
  case "$candidate" in
    *.original) continue ;;
    *) JAR_PATH="$candidate"; break ;;
  esac
done < <(find "$REPO_ROOT/backend/target" -maxdepth 1 -type f -name '*.jar' | sort)
[[ -n "$JAR_PATH" ]] || die 'Backend fat jar was not produced'
"$JAR_BIN" tf "$JAR_PATH" | grep '^BOOT-INF/classes/' >/dev/null \
  || die "Backend output is not a Spring Boot fat jar: $JAR_PATH"
"$JAR_BIN" tf "$JAR_PATH" | grep '^BOOT-INF/lib/spring-boot-flyway-.*\.jar$' >/dev/null \
  || die "Backend output is missing Spring Boot Flyway auto-configuration: $JAR_PATH"
[[ -d "$REPO_ROOT/frontend/dist/prod" ]] || die 'Frontend production output was not produced'
[[ -f "$REPO_ROOT/frontend/dist/prod/index.html" \
    && ! -L "$REPO_ROOT/frontend/dist/prod/index.html" ]] \
  || die 'Frontend production output is missing a safe index.html'
[[ -z "$(find "$REPO_ROOT/frontend/dist/prod" -type l -print -quit)" ]] \
  || die 'Frontend production output contains a symbolic link'

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
RELEASE_ROOT="$OUTPUT_DIR/$RELEASE_NAME"
ARCHIVE_PATH="$OUTPUT_DIR/$RELEASE_NAME.tar.gz"
ARCHIVE_CHECKSUM_PATH="$ARCHIVE_PATH.sha256"
[[ ! -e "$RELEASE_ROOT" ]] || die "Release directory already exists: $RELEASE_ROOT"
[[ ! -e "$ARCHIVE_PATH" ]] || die "Release archive already exists: $ARCHIVE_PATH"
[[ ! -e "$ARCHIVE_CHECKSUM_PATH" ]] || die "Release checksum already exists: $ARCHIVE_CHECKSUM_PATH"

mkdir -p "$RELEASE_ROOT/backend" "$RELEASE_ROOT/web" "$RELEASE_ROOT/db/migration" \
  "$RELEASE_ROOT/deploy/baota" "$RELEASE_ROOT/docs/deployment"
cp "$JAR_PATH" "$RELEASE_ROOT/backend/shenzhou-hr.jar"
cp -R "$REPO_ROOT/frontend/dist/prod/." "$RELEASE_ROOT/web/"
for migration in "${MIGRATION_FILES[@]}"; do
  cp "$migration" "$RELEASE_ROOT/db/migration/"
done
cp -R "$SCRIPT_DIR/." "$RELEASE_ROOT/deploy/baota/"
rm -rf -- "$RELEASE_ROOT/deploy/baota/systemd"
rm -f -- "$RELEASE_ROOT/deploy/baota/nginx/shenzhouhr-site.conf"
cp "$SCRIPT_DIR/install.sh" "$RELEASE_ROOT/install.sh"
cp "$SCRIPT_DIR/upgrade.sh" "$RELEASE_ROOT/upgrade.sh"
cp "$SCRIPT_DIR/README.md" "$RELEASE_ROOT/DEPLOYMENT-NOTES.md"
install -m 0644 \
  "$REPO_ROOT/docs/deployment/baota-deployment-guide.md" \
  "$RELEASE_ROOT/docs/deployment/baota-deployment-guide.md"
chmod +x "$RELEASE_ROOT/install.sh" "$RELEASE_ROOT/upgrade.sh" \
  "$RELEASE_ROOT/deploy/baota/"*.sh \
  "$RELEASE_ROOT/deploy/baota/mysql/"*.sh "$RELEASE_ROOT/deploy/baota/scripts/"*.sh

{
  printf 'release_name=%s\n' "$RELEASE_NAME"
  printf 'built_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'source_commit=%s\n' "$SOURCE_COMMIT"
  printf 'source_tree_state=%s\n' "$SOURCE_TREE_STATE"
  printf 'backend_jar=backend/shenzhou-hr.jar\n'
  printf 'frontend_dir=web\n'
  printf 'migration_files=%s\n' "$MIGRATION_RANGE"
  printf 'migration_count=%s\n' "${#MIGRATION_FILES[@]}"
  printf 'migration_versions=%s\n' "$MIGRATION_VERSION_LIST"
  printf 'java_major=%s\n' "$JAVA_MAJOR"
  printf 'mysql_expected=%s\n' '8.0.45'
  printf 'baota_min_version=%s\n' '11.8'
  printf 'customer_domain=%s\n' '192.168.160.226'
  printf 'backend_address_default=%s\n' '0.0.0.0'
  printf 'backend_port_default=%s\n' '8080'
  printf 'site_port_default=%s\n' '23272'
  printf 'baota_proxy_name=%s\n' 'kaoqin-api'
  printf 'baota_proxy_path=%s\n' '/api/'
  printf 'baota_proxy_target=%s\n' 'http://127.0.0.1:8080/api'
  printf 'process_manager_default=%s\n' 'baota'
  printf 'session_cookie_secure_default=%s\n' 'false'
  printf 'deployment_guide=%s\n' 'docs/deployment/baota-deployment-guide.md'
  printf 'initial_business_data=%s\n' 'not_included_import_approved_files_separately'
  printf 'initial_company_catalog=%s\n' 'deploy/baota/config/initial-companies.tsv'
  printf 'initial_company_codes=%s\n' 'SZSZ,SZJN,SZSC,SZXY'
  printf 'initial_company_count=%s\n' '4'
  printf 'initial_company_scope_count=%s\n' '4'
  printf 'initial_admin_assignment_count=%s\n' '8'
  printf 'deployment_notes=%s\n' 'DEPLOYMENT-NOTES.md'
  printf 'archive_owner=%s\n' 'root:root'
} > "$RELEASE_ROOT/BUILD-MANIFEST.txt"

cd "$RELEASE_ROOT"
if command -v sha256sum >/dev/null; then
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
else
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
fi

if tar --version 2>/dev/null | grep -qi 'bsdtar'; then
  tar --uid 0 --gid 0 --uname root --gname root \
    -C "$OUTPUT_DIR" -czf "$ARCHIVE_PATH" "$RELEASE_NAME"
else
  tar --owner=0 --group=0 --numeric-owner \
    -C "$OUTPUT_DIR" -czf "$ARCHIVE_PATH" "$RELEASE_NAME"
fi
(
  cd "$OUTPUT_DIR"
  if command -v sha256sum >/dev/null; then
    sha256sum "$RELEASE_NAME.tar.gz" > "$RELEASE_NAME.tar.gz.sha256"
  else
    shasum -a 256 "$RELEASE_NAME.tar.gz" > "$RELEASE_NAME.tar.gz.sha256"
  fi
)
printf '\nRelease ready:\n%s\n' "$ARCHIVE_PATH"
printf 'Archive checksum:\n%s\n' "$ARCHIVE_CHECKSUM_PATH"
printf 'Upload the archive, extract it, then follow: docs/deployment/baota-deployment-guide.md\n'
