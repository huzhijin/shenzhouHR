#!/usr/bin/env bash
set -Eeuo pipefail

# MySQL 8.4.10 passes this warning-suppression option on every Apple link.
# The ld64 bundled with macOS 12 / Apple Clang 14 predates that option. Keep
# the verified upstream source tree byte-identical and drop only the unsupported
# no-op suppression flag before invoking the absolute system linker.
filtered_arguments=()
for argument in "$@"; do
  case "$argument" in
    -no_warn_duplicate_libraries) ;;
    *) filtered_arguments+=("$argument") ;;
  esac
done

exec /usr/bin/ld "${filtered_arguments[@]}"
