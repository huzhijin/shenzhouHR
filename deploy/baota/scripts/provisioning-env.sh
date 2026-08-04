#!/usr/bin/env bash

# Shared validation and upgrade helpers for the account-provisioning recovery
# secret. This file is sourced by the Baota install/upgrade verification tools.
# Secret values are deliberately kept out of diagnostics.
# Disable caller-requested xtrace before any secret can be read or generated.
set +x

PROVISIONING_ENV_ERROR=""
PROVISIONING_ENV_VALUE=""
PROVISIONING_ENV_VALUE_PRESENT=0
PROVISIONING_RESOLVED_VALUE=""
PROVISIONING_APP_VALUE_MISSING=0
PROVISIONING_MIGRATOR_VALUE_MISSING=0
PROVISIONING_GENERATED_PEPPER=""

provisioning_env_fail() {
  PROVISIONING_ENV_ERROR="$1"
  return 1
}

provisioning_validate_pepper() {
  local value="$1"
  local standard canonical
  [[ "$value" =~ ^[A-Za-z0-9_-]{43}$ ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_PEPPER must be a 43-character base64url value' \
    || return 1
  command -v openssl >/dev/null 2>&1 \
    || provisioning_env_fail 'openssl is required to validate SHENZHOUHR_PROVISIONING_PEPPER' \
    || return 1
  standard="$(printf '%s' "$value" | tr '_-' '/+')="
  canonical="$({
    printf '%s' "$standard" \
      | openssl base64 -d -A 2>/dev/null \
      | openssl base64 -A 2>/dev/null \
      | tr '+/' '-_' \
      | tr -d '=\r\n'
  } 2>/dev/null)" \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_PEPPER is not valid base64url' \
    || return 1
  [[ "$canonical" == "$value" ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_PEPPER must canonically encode exactly 32 bytes' \
    || return 1
}

provisioning_generate_pepper() {
  local generated
  command -v openssl >/dev/null 2>&1 \
    || provisioning_env_fail 'openssl is required to generate SHENZHOUHR_PROVISIONING_PEPPER' \
    || return 1
  generated="$(openssl rand -base64 32 2>/dev/null | tr '+/' '-_' | tr -d '=\r\n')" \
    || provisioning_env_fail 'could not generate SHENZHOUHR_PROVISIONING_PEPPER' \
    || return 1
  provisioning_validate_pepper "$generated" || return 1
  PROVISIONING_GENERATED_PEPPER="$generated"
}

provisioning_validate_key_id() {
  [[ "$1" =~ ^[A-Za-z0-9._:-]{1,64}$ ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_KEY_ID must use 1-64 safe identifier characters' \
    || return 1
}

provisioning_validate_recovery_window() {
  local value="$1"
  local days hours minutes seconds total
  [[ "$value" =~ ^P([0-9]+D)?(T([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$ ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW must be a positive ISO-8601 duration using whole seconds' \
    || return 1
  days="${BASH_REMATCH[1]%D}"
  hours="${BASH_REMATCH[3]%H}"
  minutes="${BASH_REMATCH[4]%M}"
  seconds="${BASH_REMATCH[5]%S}"
  [[ -n "$days$hours$minutes$seconds" ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW must be greater than zero' \
    || return 1
  if [[ "$value" == *T && -z "$hours$minutes$seconds" ]]; then
    provisioning_env_fail 'SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW contains an empty time component'
    return 1
  fi
  total="$(awk \
    -v d="${days:-0}" \
    -v h="${hours:-0}" \
    -v m="${minutes:-0}" \
    -v s="${seconds:-0}" \
    'BEGIN { total = d * 86400 + h * 3600 + m * 60 + s; if (total > 0 && total <= 86400) { printf "%.0f", total } else { exit 1 } }' \
  )" || {
    provisioning_env_fail 'SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW must be greater than zero and no more than 24 hours'
    return 1
  }
  [[ "$total" -ge 1 && "$total" -le 86400 ]] \
    || provisioning_env_fail 'SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW must be greater than zero and no more than 24 hours' \
    || return 1
}

provisioning_read_env_value() {
  local file="$1"
  local key="$2"
  local line count
  count=0
  PROVISIONING_ENV_VALUE=""
  PROVISIONING_ENV_VALUE_PRESENT=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == "$key="* ]] || continue
    count=$((count + 1))
    PROVISIONING_ENV_VALUE="${line#*=}"
  done < "$file"
  if ((count > 1)); then
    provisioning_env_fail "environment file contains duplicate variable name: $key"
    return 1
  fi
  if ((count == 1)); then
    PROVISIONING_ENV_VALUE_PRESENT=1
  fi
}

provisioning_resolve_pair_value() {
  local app_file="$1"
  local migrator_file="$2"
  local key="$3"
  local default_value="$4"
  local validator="$5"
  local app_value migrator_value app_present migrator_present resolved

  provisioning_read_env_value "$app_file" "$key" || return 1
  app_value="$PROVISIONING_ENV_VALUE"
  app_present="$PROVISIONING_ENV_VALUE_PRESENT"
  provisioning_read_env_value "$migrator_file" "$key" || return 1
  migrator_value="$PROVISIONING_ENV_VALUE"
  migrator_present="$PROVISIONING_ENV_VALUE_PRESENT"

  if ((app_present == 1 && migrator_present == 1)); then
    [[ "$app_value" == "$migrator_value" ]] \
      || provisioning_env_fail "$key differs between app and migrator environment files" \
      || return 1
    resolved="$app_value"
  elif ((app_present == 1)); then
    resolved="$app_value"
  elif ((migrator_present == 1)); then
    resolved="$migrator_value"
  elif [[ "$default_value" == '__GENERATE_32_BYTE_BASE64URL__' ]]; then
    provisioning_generate_pepper || return 1
    resolved="$PROVISIONING_GENERATED_PEPPER"
  else
    resolved="$default_value"
  fi

  "$validator" "$resolved" || return 1
  PROVISIONING_RESOLVED_VALUE="$resolved"
  PROVISIONING_APP_VALUE_MISSING=$((1 - app_present))
  PROVISIONING_MIGRATOR_VALUE_MISSING=$((1 - migrator_present))
}

provisioning_prepare_env_update() {
  local source_file="$1"
  local additions="$2"
  local output_variable="$3"
  local temp_file
  temp_file="$(mktemp "${source_file}.provisioning.XXXXXX")" \
    || provisioning_env_fail 'could not prepare environment file update' \
    || return 1
  if ! cp -p -- "$source_file" "$temp_file"; then
    rm -f -- "$temp_file"
    provisioning_env_fail 'could not preserve environment file metadata'
    return 1
  fi
  if ! printf '\n%s' "$additions" >> "$temp_file"; then
    rm -f -- "$temp_file"
    provisioning_env_fail 'could not prepare environment file values'
    return 1
  fi
  printf -v "$output_variable" '%s' "$temp_file"
}

provisioning_sync_env_pair() {
  local app_file="$1"
  local migrator_file="$2"
  local app_additions migrator_additions value
  local app_temp migrator_temp app_backup migrator_backup
  local app_changed migrator_changed

  PROVISIONING_ENV_ERROR=""
  [[ "$app_file" != "$migrator_file" ]] \
    || provisioning_env_fail 'app and migrator environment files must be different' \
    || return 1
  [[ -f "$app_file" && ! -L "$app_file" && -f "$migrator_file" && ! -L "$migrator_file" ]] \
    || provisioning_env_fail 'app and migrator environment files must be regular files, not symbolic links' \
    || return 1

  app_additions=""
  migrator_additions=""

  provisioning_resolve_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_PEPPER \
    __GENERATE_32_BYTE_BASE64URL__ \
    provisioning_validate_pepper || return 1
  value="$PROVISIONING_RESOLVED_VALUE"
  ((PROVISIONING_APP_VALUE_MISSING == 0)) \
    || app_additions="${app_additions}SHENZHOUHR_PROVISIONING_PEPPER=${value}"$'\n'
  ((PROVISIONING_MIGRATOR_VALUE_MISSING == 0)) \
    || migrator_additions="${migrator_additions}SHENZHOUHR_PROVISIONING_PEPPER=${value}"$'\n'

  provisioning_resolve_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_KEY_ID v1 \
    provisioning_validate_key_id || return 1
  value="$PROVISIONING_RESOLVED_VALUE"
  ((PROVISIONING_APP_VALUE_MISSING == 0)) \
    || app_additions="${app_additions}SHENZHOUHR_PROVISIONING_KEY_ID=${value}"$'\n'
  ((PROVISIONING_MIGRATOR_VALUE_MISSING == 0)) \
    || migrator_additions="${migrator_additions}SHENZHOUHR_PROVISIONING_KEY_ID=${value}"$'\n'

  provisioning_resolve_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW PT1H \
    provisioning_validate_recovery_window || return 1
  value="$PROVISIONING_RESOLVED_VALUE"
  ((PROVISIONING_APP_VALUE_MISSING == 0)) \
    || app_additions="${app_additions}SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=${value}"$'\n'
  ((PROVISIONING_MIGRATOR_VALUE_MISSING == 0)) \
    || migrator_additions="${migrator_additions}SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=${value}"$'\n'

  [[ -n "$app_additions$migrator_additions" ]] || return 0

  app_temp=""
  migrator_temp=""
  app_backup=""
  migrator_backup=""
  app_changed=0
  migrator_changed=0

  if [[ -n "$app_additions" ]]; then
    provisioning_prepare_env_update "$app_file" "$app_additions" app_temp || return 1
    app_backup="$(mktemp "${app_file}.provisioning-backup.XXXXXX")" || {
      rm -f -- "$app_temp"
      provisioning_env_fail 'could not prepare environment file rollback'
      return 1
    }
    cp -p -- "$app_file" "$app_backup" || {
      rm -f -- "$app_temp" "$app_backup"
      provisioning_env_fail 'could not prepare environment file rollback'
      return 1
    }
  fi
  if [[ -n "$migrator_additions" ]]; then
    provisioning_prepare_env_update "$migrator_file" "$migrator_additions" migrator_temp || {
      rm -f -- "$app_temp" "$app_backup"
      return 1
    }
    migrator_backup="$(mktemp "${migrator_file}.provisioning-backup.XXXXXX")" || {
      rm -f -- "$app_temp" "$app_backup" "$migrator_temp"
      provisioning_env_fail 'could not prepare environment file rollback'
      return 1
    }
    cp -p -- "$migrator_file" "$migrator_backup" || {
      rm -f -- "$app_temp" "$app_backup" "$migrator_temp" "$migrator_backup"
      provisioning_env_fail 'could not prepare environment file rollback'
      return 1
    }
  fi

  if [[ -n "$app_temp" ]]; then
    if ! mv -- "$app_temp" "$app_file"; then
      rm -f -- "$app_temp" "$app_backup" "$migrator_temp" "$migrator_backup"
      provisioning_env_fail 'could not replace app environment file'
      return 1
    fi
    app_changed=1
  fi
  if [[ -n "$migrator_temp" ]]; then
    if ! mv -- "$migrator_temp" "$migrator_file"; then
      if ((app_changed == 1)); then
        mv -- "$app_backup" "$app_file" || true
        app_backup=""
      fi
      rm -f -- "$app_temp" "$app_backup" "$migrator_temp" "$migrator_backup"
      provisioning_env_fail 'could not replace migrator environment file; app environment rollback was attempted'
      return 1
    fi
    migrator_changed=1
  fi

  rm -f -- "$app_temp" "$migrator_temp" "$app_backup" "$migrator_backup"
  ((app_changed == 1 || migrator_changed == 1)) || return 0
  provisioning_validate_env_pair "$app_file" "$migrator_file"
}

provisioning_require_pair_value() {
  local app_file="$1"
  local migrator_file="$2"
  local key="$3"
  local validator="$4"
  local app_value migrator_value

  provisioning_read_env_value "$app_file" "$key" || return 1
  ((PROVISIONING_ENV_VALUE_PRESENT == 1)) \
    || provisioning_env_fail "app environment file is missing variable name: $key" \
    || return 1
  app_value="$PROVISIONING_ENV_VALUE"
  provisioning_read_env_value "$migrator_file" "$key" || return 1
  ((PROVISIONING_ENV_VALUE_PRESENT == 1)) \
    || provisioning_env_fail "migrator environment file is missing variable name: $key" \
    || return 1
  migrator_value="$PROVISIONING_ENV_VALUE"
  [[ "$app_value" == "$migrator_value" ]] \
    || provisioning_env_fail "$key differs between app and migrator environment files" \
    || return 1
  "$validator" "$app_value"
}

provisioning_validate_env_pair() {
  local app_file="$1"
  local migrator_file="$2"
  PROVISIONING_ENV_ERROR=""
  [[ "$app_file" != "$migrator_file" ]] \
    || provisioning_env_fail 'app and migrator environment files must be different' \
    || return 1
  [[ -f "$app_file" && ! -L "$app_file" && -f "$migrator_file" && ! -L "$migrator_file" ]] \
    || provisioning_env_fail 'app and migrator environment files must be regular files, not symbolic links' \
    || return 1
  provisioning_require_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_PEPPER provisioning_validate_pepper || return 1
  provisioning_require_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_KEY_ID provisioning_validate_key_id || return 1
  provisioning_require_pair_value \
    "$app_file" "$migrator_file" \
    SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW provisioning_validate_recovery_window
}
