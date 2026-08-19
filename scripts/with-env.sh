#!/usr/bin/env bash
# Runs a command with .env loaded into the environment.
#
# The macOS and Linux counterpart of with-env.ps1, with the same contract: the
# datasource is environment driven, so pointing the service at the canonical
# database (ADR-004) is a matter of which variables are set. The values are read
# from .env — which git ignores — and exported for one child process, so the
# credential never reaches a tracked file, a shell history or a transcript.
#
# Variable names are reported; values never are.
#
#   ./scripts/with-env.sh mvn -pl card-service quarkus:dev
#   ENV_FILE=.env.staging ./scripts/with-env.sh psql

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${ENV_FILE:-.env}"
case "$env_file" in
    # The second pattern is a Windows drive path, for anyone running this under
    # Git Bash or WSL rather than on macOS.
    /*|[A-Za-z]:/*) env_path="$env_file" ;;
    *)  env_path="$repo_root/$env_file" ;;
esac

if [ ! -f "$env_path" ]; then
    echo "No $env_file found. Copy .env.example to .env and fill it in; git ignores .env." >&2
    exit 1
fi

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 <command> [args...]" >&2
    exit 2
fi

loaded=""
first_line=1
while IFS= read -r line || [ -n "$line" ]; do
    # Tolerates CRLF, because this file is edited on Windows as often as not.
    line="${line%$'\r'}"
    if [ "$first_line" -eq 1 ]; then
        # A UTF-8 BOM, which Notepad and PowerShell's Out-File both write. Left
        # in place it makes the first line unparseable, and the error then points
        # at the wrong thing entirely.
        line="${line#$'\xef\xbb\xbf'}"
        first_line=0
    fi
    case "$line" in
        ''|'#'*) continue ;;
    esac

    name="${line%%=*}"
    # Only the first '=' separates. A JDBC URL carries more in its query string
    # and splitting on all of them would truncate it.
    value="${line#*=}"
    if [ "$name" = "$line" ]; then
        echo "Malformed line in $env_file: expected NAME=value" >&2
        exit 1
    fi

    # Trim surrounding whitespace and one layer of matching quotes.
    name="$(printf '%s' "$name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    value="$(printf '%s' "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$value" in
        \"*\") value="${value%\"}"; value="${value#\"}" ;;
        \'*\') value="${value%\'}"; value="${value#\'}" ;;
    esac

    case "$value" in
        *HOST*|*DATABASE*|USER|PASSWORD)
            echo "$name still holds a placeholder from .env.example. Fill in $env_file first." >&2
            exit 1
            ;;
    esac

    export "$name=$value"
    loaded="${loaded:+$loaded, }$name"
done < "$env_path"

# Names only. Printing a value here is how a credential ends up in a transcript.
echo "loaded from $env_file: $loaded"

exec "$@"
