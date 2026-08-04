#!/usr/bin/env bash
set -Eeuo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="${1:-$REPO_ROOT/release}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v bash >/dev/null || die 'bash is required'
command -v sha256sum >/dev/null || command -v shasum >/dev/null || die 'sha256sum or shasum is required'

if [[ ! -x "$REPO_ROOT/backend/mvnw" ]]; then
  die "Maven wrapper not found: $REPO_ROOT/backend/mvnw"
fi
command -v node >/dev/null || die 'Node.js is required to build the frontend'
command -v npm >/dev/null || die 'npm is required to build the frontend'

JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
[[ -n "$JAVA_BIN" ]] || die 'Java 21 is required'
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown}"

cd "$REPO_ROOT/frontend"
npm ci
npm run build

cd "$REPO_ROOT/backend"
./mvnw -DskipTests package

JAR_PATH=""
while IFS= read -r candidate; do
  case "$candidate" in
    *.original) continue ;;
    *) JAR_PATH="$candidate"; break ;;
  esac
done < <(find "$REPO_ROOT/backend/target" -maxdepth 1 -type f -name '*.jar' | sort)
[[ -n "$JAR_PATH" ]] || die 'Backend fat jar was not produced'
[[ -d "$REPO_ROOT/frontend/dist/prod" ]] || die 'Frontend production output was not produced'

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
RELEASE_NAME="shenzhouhr-release-$(date -u +%Y%m%d%H%M%S)"
RELEASE_ROOT="$OUTPUT_DIR/$RELEASE_NAME"
ARCHIVE_PATH="$OUTPUT_DIR/$RELEASE_NAME.tar.gz"
[[ ! -e "$RELEASE_ROOT" ]] || die "Release directory already exists: $RELEASE_ROOT"
[[ ! -e "$ARCHIVE_PATH" ]] || die "Release archive already exists: $ARCHIVE_PATH"

mkdir -p "$RELEASE_ROOT/backend" "$RELEASE_ROOT/web" "$RELEASE_ROOT/db/migration" \
  "$RELEASE_ROOT/deploy/baota"
cp "$JAR_PATH" "$RELEASE_ROOT/backend/shenzhou-hr.jar"
cp -R "$REPO_ROOT/frontend/dist/prod/." "$RELEASE_ROOT/web/"
cp "$REPO_ROOT/backend/src/main/resources/db/migration/"*.sql "$RELEASE_ROOT/db/migration/"
cp -R "$SCRIPT_DIR/." "$RELEASE_ROOT/deploy/baota/"
cp "$SCRIPT_DIR/install.sh" "$RELEASE_ROOT/install.sh"
cp "$SCRIPT_DIR/upgrade.sh" "$RELEASE_ROOT/upgrade.sh"
chmod +x "$RELEASE_ROOT/install.sh" "$RELEASE_ROOT/upgrade.sh" \
  "$RELEASE_ROOT/deploy/baota/"*.sh \
  "$RELEASE_ROOT/deploy/baota/mysql/"*.sh "$RELEASE_ROOT/deploy/baota/scripts/"*.sh

{
  printf 'release_name=%s\n' "$RELEASE_NAME"
  printf 'built_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'backend_jar=%s\n' "$(basename "$JAR_PATH")"
  printf 'frontend_dir=frontend/dist/prod\n'
  printf 'migration_files=V1..V11\n'
  printf 'java_major=%s\n' "$JAVA_MAJOR"
} > "$RELEASE_ROOT/BUILD-MANIFEST.txt"

cd "$RELEASE_ROOT"
if command -v sha256sum >/dev/null; then
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
else
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
fi

tar -C "$OUTPUT_DIR" -czf "$ARCHIVE_PATH" "$RELEASE_NAME"
printf '\nRelease ready:\n%s\n' "$ARCHIVE_PATH"
printf 'Upload the archive, extract it, then run: sudo bash install.sh\n'
