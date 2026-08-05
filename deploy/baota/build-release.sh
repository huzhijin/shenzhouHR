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

copy_markdown_tree() {
  local source_root="$1"
  local destination_root="$2"
  local source_file relative_file destination_file copied
  [[ -d "$source_root" && ! -L "$source_root" ]] \
    || die "Markdown source must be a real directory: $source_root"
  copied=0
  while IFS= read -r -d '' source_file; do
    [[ -f "$source_file" && ! -L "$source_file" ]] \
      || die "Markdown source must be a regular file: $source_file"
    relative_file="${source_file#"$source_root"/}"
    case "$relative_file" in
      ""|/*|../*|*/../*|*/..) die "Unsafe Markdown path: $source_file" ;;
    esac
    destination_file="$destination_root/$relative_file"
    mkdir -p "$(dirname -- "$destination_file")"
    install -m 0644 "$source_file" "$destination_file"
    copied=$((copied + 1))
  done < <(find "$source_root" -type f -name '*.md' -print0 | sort -z)
  ((copied > 0)) || die "No Markdown documents found under: $source_root"
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

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
RELEASE_ROOT="$OUTPUT_DIR/$RELEASE_NAME"
ARCHIVE_PATH="$OUTPUT_DIR/$RELEASE_NAME.tar.gz"
ARCHIVE_CHECKSUM_PATH="$ARCHIVE_PATH.sha256"
[[ ! -e "$RELEASE_ROOT" ]] || die "Release directory already exists: $RELEASE_ROOT"
[[ ! -e "$ARCHIVE_PATH" ]] || die "Release archive already exists: $ARCHIVE_PATH"
[[ ! -e "$ARCHIVE_CHECKSUM_PATH" ]] || die "Release checksum already exists: $ARCHIVE_CHECKSUM_PATH"

mkdir -p "$RELEASE_ROOT/backend" "$RELEASE_ROOT/web" "$RELEASE_ROOT/db/migration" \
  "$RELEASE_ROOT/deploy/baota" "$RELEASE_ROOT/docs/user-guide" \
  "$RELEASE_ROOT/docs/contracts" "$RELEASE_ROOT/docs/reporting"
cp "$JAR_PATH" "$RELEASE_ROOT/backend/shenzhou-hr.jar"
cp -R "$REPO_ROOT/frontend/dist/prod/." "$RELEASE_ROOT/web/"
for migration in "${MIGRATION_FILES[@]}"; do
  cp "$migration" "$RELEASE_ROOT/db/migration/"
done
cp -R "$SCRIPT_DIR/." "$RELEASE_ROOT/deploy/baota/"
copy_markdown_tree "$REPO_ROOT/docs/user-guide" "$RELEASE_ROOT/docs/user-guide"
copy_markdown_tree "$REPO_ROOT/docs/contracts" "$RELEASE_ROOT/docs/contracts"
copy_markdown_tree "$REPO_ROOT/docs/reporting" "$RELEASE_ROOT/docs/reporting"
cp "$SCRIPT_DIR/install.sh" "$RELEASE_ROOT/install.sh"
cp "$SCRIPT_DIR/upgrade.sh" "$RELEASE_ROOT/upgrade.sh"
cp "$SCRIPT_DIR/README.md" "$RELEASE_ROOT/DEPLOYMENT-NOTES.md"
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
  printf 'backend_port_default=%s\n' '18080'
  printf 'initial_business_data=%s\n' 'not_included_import_approved_files_separately'
  printf 'customer_guide=%s\n' 'docs/user-guide/README.md'
  printf 'deployment_readiness=%s\n' \
    'docs/contracts/2026-08-06-customer-deployment-readiness.md'
  printf 'reporting_confirmation=%s\n' \
    'docs/contracts/2026-08-06-reporting-business-confirmation.md'
  printf 'reporting_code_audit=%s\n' \
    'docs/contracts/2026-08-06-reporting-code-audit.md'
} > "$RELEASE_ROOT/BUILD-MANIFEST.txt"

cd "$RELEASE_ROOT"
if command -v sha256sum >/dev/null; then
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
else
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
fi

tar -C "$OUTPUT_DIR" -czf "$ARCHIVE_PATH" "$RELEASE_NAME"
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
printf 'Upload the archive, extract it, then run: sudo bash install.sh\n'
