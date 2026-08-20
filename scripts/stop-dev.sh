#!/usr/bin/env bash
#
# Ends every development process this project starts, and nothing else.
#
# Selection is an allow-list, never a deny-list. `pkill -f node` reads as
# thorough and is not: on a developer machine the same runtime serves the
# editor's language servers, its extension host and any agent tooling, none of
# which belong to this project. Ending them looks like the editor crashed.
#
# A process is a candidate only if it holds one of this project's ports, or if
# its command line names one of this project's dev commands. Descendants are
# included, because `npm start` runs the dev server as a child and ending the
# parent alone leaves the child holding the port.
#
# Containers are out of scope by default: they are not terminal processes, and
# Ctrl+C never reached them either. Pass --containers to stop the Compose stack
# as well.
#
# Usage:
#   ./scripts/stop-dev.sh --dry-run      # report only, end nothing
#   ./scripts/stop-dev.sh
#   ./scripts/stop-dev.sh --containers

set -uo pipefail

DRY_RUN=0
INCLUDE_CONTAINERS=0
for argument in "$@"; do
    case "$argument" in
        --dry-run|-n) DRY_RUN=1 ;;
        --containers) INCLUDE_CONTAINERS=1 ;;
        -h|--help) sed -n '3,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $argument" >&2; exit 2 ;;
    esac
done

# Ports published from the host by the two applications. Container ports are not
# here: `docker compose down` releases those, not a signal to a process.
PORTS=(8080 4420)

# Command-line patterns for a process started by this project.
PATTERNS='quarkus:dev|ng\.js.? serve|@angular/cli/bin|@angular/build|card-service|banco-santo-andre'

# Never signalled, whatever matches. A terminal whose working directory is inside
# the repository matches a path pattern exactly as a dev server does, and a shell
# is never the thing holding a port.
PROTECTED='bash|sh|zsh|fish|tmux|screen|code|Code Helper|node_repl|sshd|ssh'

port_holders() {
    local port pids=""
    for port in "${PORTS[@]}"; do
        if command -v lsof > /dev/null 2>&1; then
            pids="$pids $(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)"
        elif command -v ss > /dev/null 2>&1; then
            # ss prints pid=NNNN inside its process column
            pids="$pids $(ss -ltnp 2>/dev/null | grep -oE "pid=[0-9]+" | cut -d= -f2 || true)"
        elif command -v fuser > /dev/null 2>&1; then
            pids="$pids $(fuser -n tcp "$port" 2>/dev/null || true)"
        fi
    done
    echo "$pids"
}

pattern_matches() {
    # -f matches the full command line; the project patterns are specific enough
    # that a plain `pgrep node` is never needed.
    pgrep -f "$PATTERNS" 2>/dev/null || true
}

descendants_of() {
    # Breadth-first over the process tree. pgrep -P lists direct children.
    local queue=("$1") seen="" current children
    while [ ${#queue[@]} -gt 0 ]; do
        current="${queue[0]}"
        queue=("${queue[@]:1}")
        case " $seen " in *" $current "*) continue ;; esac
        seen="$seen $current"
        children="$(pgrep -P "$current" 2>/dev/null || true)"
        for child in $children; do
            queue+=("$child")
        done
    done
    echo "$seen"
}

self=$$
parent_self="$(ps -o ppid= -p $$ 2>/dev/null | tr -d ' ')"

candidates="$(port_holders) $(pattern_matches)"
targets=""
for seed in $candidates; do
    [ -n "$seed" ] || continue
    for pid in $(descendants_of "$seed"); do
        case " $targets " in *" $pid "*) continue ;; esac
        targets="$targets $pid"
    done
done

stopped=0
for pid in $targets; do
    [ -n "$pid" ] || continue
    [ "$pid" = "$self" ] && continue
    [ "$pid" = "$parent_self" ] && continue

    command_name="$(ps -o comm= -p "$pid" 2>/dev/null || true)"
    [ -n "$command_name" ] || continue
    base_name="$(basename "$command_name")"

    if echo "$base_name" | grep -qE "^(${PROTECTED})$"; then
        printf '  protected  %-7s %s\n' "$pid" "$base_name"
        continue
    fi

    full_command="$(ps -o args= -p "$pid" 2>/dev/null | cut -c1-90 || true)"
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '  would stop %-7s %s\n' "$pid" "$full_command"
        continue
    fi

    # SIGTERM first: a dev server that shuts down cleanly releases its port and
    # flushes its output. SIGKILL only for what ignores it.
    if kill "$pid" 2>/dev/null; then
        printf '  stopped    %-7s %s\n' "$pid" "$full_command"
        stopped=$((stopped + 1))
    else
        printf '  gone       %-7s %s\n' "$pid" "$base_name"
    fi
done

if [ "$DRY_RUN" -eq 0 ] && [ "$stopped" -gt 0 ]; then
    sleep 2
    for pid in $targets; do
        if kill -0 "$pid" 2>/dev/null; then
            base_name="$(basename "$(ps -o comm= -p "$pid" 2>/dev/null || echo unknown)")"
            if ! echo "$base_name" | grep -qE "^(${PROTECTED})$"; then
                printf '  SIGKILL    %-7s %s\n' "$pid" "$base_name"
                kill -9 "$pid" 2>/dev/null || true
            fi
        fi
    done
fi

if [ -z "$targets" ]; then
    echo 'Nothing to stop: no project process is running.'
fi

if [ "$INCLUDE_CONTAINERS" -eq 1 ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
        echo '  would run  docker compose down'
    else
        echo 'Stopping the Compose stack...'
        docker compose down
    fi
fi

# The check that proves it, rather than the claim that it worked.
for port in "${PORTS[@]}"; do
    holder=""
    if command -v lsof > /dev/null 2>&1; then
        holder="$(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)"
    fi
    if [ -n "$holder" ]; then
        echo "  port ${port} is STILL held by ${holder}"
    else
        echo "  port ${port} is free"
    fi
done
