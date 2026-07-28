#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-mysql8410-cxx] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly MYSQL8410_REAL_CXX="/usr/bin/clang++"
readonly MYSQL8410_PROTOBUF_INCLUDE="${MYSQL8410_BUNDLED_PROTOBUF_INCLUDE:-}"
readonly MYSQL8410_ABSEIL_INCLUDE="${MYSQL8410_BUNDLED_ABSEIL_INCLUDE:-}"

[[ -x "$MYSQL8410_REAL_CXX" ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: Apple clang++ is unavailable.\n' >&2
  exit 1
}
[[ "$MYSQL8410_PROTOBUF_INCLUDE" = /* ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Protobuf include path is unavailable.\n' >&2
  exit 1
}
[[ "$MYSQL8410_PROTOBUF_INCLUDE" =~ ^/[A-Za-z0-9._/-]+$ ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Protobuf include path is unsafe.\n' >&2
  exit 1
}
[[ -f "${MYSQL8410_PROTOBUF_INCLUDE}/google/protobuf/any.pb.h" ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Protobuf headers are incomplete.\n' >&2
  exit 1
}
[[ "$MYSQL8410_ABSEIL_INCLUDE" = /* ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Abseil include path is unavailable.\n' >&2
  exit 1
}
[[ "$MYSQL8410_ABSEIL_INCLUDE" =~ ^/[A-Za-z0-9._/-]+$ ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Abseil include path is unsafe.\n' >&2
  exit 1
}
[[ -f "${MYSQL8410_ABSEIL_INCLUDE}/absl/base/config.h" ]] || {
  printf '[shenzhouhr-mysql8410-cxx] ERROR: bundled Abseil headers are incomplete.\n' >&2
  exit 1
}

mysql8410_filtered_arguments=()
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "-isystem" && $# -ge 2 \
      && "$2" == "$MYSQL8410_PROTOBUF_INCLUDE" ]]; then
    shift 2
    continue
  fi
  if [[ "$1" == "-isystem${MYSQL8410_PROTOBUF_INCLUDE}" ]]; then
    shift
    continue
  fi
  if [[ "$1" == "-isystem" && $# -ge 2 \
      && "$2" == "$MYSQL8410_ABSEIL_INCLUDE" ]]; then
    shift 2
    continue
  fi
  if [[ "$1" == "-isystem${MYSQL8410_ABSEIL_INCLUDE}" ]]; then
    shift
    continue
  fi
  mysql8410_filtered_arguments+=("$1")
  shift
done

# MySQL's bundled Protobuf and Abseil CMake targets mark their own headers as
# -isystem. On this host Clang then searches /usr/local/include first and mixes
# Homebrew ABI-versioned headers into the official bundled sources. Remove only
# those two exact -isystem occurrences and prepend the same directories as
# normal include directories. No other compiler argument or path is changed.
exec "$MYSQL8410_REAL_CXX" "-I${MYSQL8410_PROTOBUF_INCLUDE}" \
  "-I${MYSQL8410_ABSEIL_INCLUDE}" \
  "${mysql8410_filtered_arguments[@]}"
