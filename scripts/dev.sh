#!/usr/bin/env bash
set -Eeuo pipefail

# Linux/WSL launcher. Runtime files stay outside the Git working tree.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
read -r repo_key _ < <(printf '%s' "$ROOT" | cksum)
STATE_DIR="${TMPDIR:-/tmp}/logisticsconnect-${UID}-${repo_key}"
LOG_DIR="$STATE_DIR/logs"
BOOT_ID="$(cat /proc/sys/kernel/random/boot_id)"
PID_NAMESPACE="$(readlink /proc/self/ns/pid)"
START_TIMEOUT="${LOGISTICS_START_TIMEOUT:-45}"
services=(ingestion-service hub-service transit-service delay-stage-service)
declare -A ports=(
  [ingestion-service]=7050 [hub-service]=7051
  [transit-service]=7053 [delay-stage-service]=7052
)
started=()
action="${1:-help}"

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "Required command not found: $1"; }

[[ "$START_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || fail 'LOGISTICS_START_TIMEOUT must be a positive integer.'
mkdir -p -m 700 "$STATE_DIR"
[[ -O "$STATE_DIR" && ! -L "$STATE_DIR" ]] || fail "Unsafe runtime directory: $STATE_DIR"
mkdir -p "$LOG_DIR"

# Procfs can expose host PIDs inside a PID namespace. Track both IDs, the
# process start time, the boot ID and the launcher's PID namespace.
signature() {
  local raw
  local -a fields
  [[ "$1" =~ ^[1-9][0-9]*$ && -r "/proc/$1/stat" ]] || return 1
  IFS= read -r raw 2>/dev/null < "/proc/$1/stat" || return 1
  read -r -a fields <<< "${raw##*) }"
  [[ "${fields[0]:-Z}" != Z && -n "${fields[19]:-}" ]] || return 1
  printf '%s' "${fields[19]}"
}

tracked_pid() {
  local pid proc_pid saved_start saved_boot saved_namespace current
  [[ -f "$STATE_DIR/$1.pid" ]] || return 1
  read -r pid proc_pid saved_start saved_boot saved_namespace < "$STATE_DIR/$1.pid" || return 1
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$pid" -gt 1 && "$proc_pid" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$saved_boot" == "$BOOT_ID" && "$saved_namespace" == "$PID_NAMESPACE" ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  current="$(signature "$proc_pid")" || return 1
  [[ "$current" == "$saved_start" ]] || return 1
  printf '%s' "$pid"
}

tcp_open() {
  timeout 1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/"$1"' _ "$1" >/dev/null 2>&1
}

healthy() {
  [[ "$(curl -fsS --connect-timeout 1 --max-time 2 "http://127.0.0.1:${ports[$1]}/health" 2>/dev/null)" == OK ]]
}

stop_one() {
  local service="$1" pid deadline
  if pid="$(tracked_pid "$service")"; then
    printf 'Stopping %s (PID %s)\n' "$service" "$pid"
    kill -TERM "$pid" 2>/dev/null || true
    deadline=$((SECONDS + 10))
    while tracked_pid "$service" >/dev/null && ((SECONDS < deadline)); do sleep 0.2; done
    if pid="$(tracked_pid "$service")"; then
      kill -KILL "$pid" 2>/dev/null || return 1
    fi
  fi
  rm -f -- "$STATE_DIR/$service.pid"
}

stop_services() {
  local i
  for ((i=${#services[@]}-1; i>=0; i--)); do stop_one "${services[$i]}"; done
}

cleanup() {
  local result=$? i
  trap - EXIT INT TERM
  if ((result != 0 && ${#started[@]} > 0)); then
    printf 'Startup failed; stopping services started by this invocation.\n' >&2
    for ((i=${#started[@]}-1; i>=0; i--)); do stop_one "${started[$i]}" || true; done
  fi
  exit "$result"
}

wait_ready() {
  local service="$1" pid="$2" deadline=$((SECONDS + START_TIMEOUT))
  while ((SECONDS < deadline)); do
    if tracked_pid "$service" >/dev/null; then
      if healthy "$service"; then return 0; fi
    elif [[ -s "$STATE_DIR/$service.pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  printf 'Startup failed for %s. Recent log output:\n' "$service" >&2
  tail -n 25 "$LOG_DIR/$service.log" >&2 || true
  return 1
}

build_modules() {
  local service
  need mvn
  for service in "${services[@]}"; do
    if [[ "$action" == test ]]; then
      mvn -f "$ROOT/$service/pom.xml" clean verify
    else
      mvn -f "$ROOT/$service/pom.xml" -DskipTests package
    fi
  done
}

up() {
  local service jar pid deadline
  for service in java curl timeout nohup setsid; do need "$service"; done
  for service in "${services[@]}"; do
    [[ -f "$ROOT/$service/pom.xml" ]] || fail "Missing module: $service"
    if ! tracked_pid "$service" >/dev/null && tcp_open "${ports[$service]}"; then
      fail "Port ${ports[$service]} is occupied by an untracked process. Stop it manually before make up."
    fi
  done
  for service in "${services[@]}"; do
    jar="$ROOT/$service/target/$service.jar"
    if [[ ! -f "$jar" ]]; then
      need mvn
      printf 'Building missing JAR: %s\n' "$service"
      mvn -f "$ROOT/$service/pom.xml" -DskipTests package
      [[ -f "$jar" ]] || fail "Build did not produce $jar"
    fi
  done
  if tcp_open 61616; then
    printf 'Reusing broker listener on localhost:61616.\n'
  else
    need docker
    docker compose -f "$ROOT/common/docker-compose.yml" up -d
    deadline=$((SECONDS + START_TIMEOUT))
    until tcp_open 61616; do
      ((SECONDS < deadline)) || fail 'Broker did not open port 61616 before the timeout.'
      sleep 0.2
    done
  fi
  for service in "${services[@]}"; do
    if pid="$(tracked_pid "$service")"; then
      printf 'Already running: %s (PID %s)\n' "$service" "$pid"
    else
      rm -f -- "$STATE_DIR/$service.pid"
      jar="$ROOT/$service/target/$service.jar"
      printf '\n--- Starting %s ---\n' "$service" >> "$LOG_DIR/$service.log"
      # Record this child before exec; close the inherited lock FD and give
      # Java its own session so Ctrl+C in a log viewer cannot stop the JARs.
      (
        IFS= read -r raw < /proc/self/stat
        proc_pid="${raw%% *}"
        read -r -a fields <<< "${raw##*) }"
        printf '%s %s %s %s %s\n' "$BASHPID" "$proc_pid" "${fields[19]}" "$BOOT_ID" "$PID_NAMESPACE" > "$STATE_DIR/$service.pid"
        exec nohup setsid java -jar "$jar"
      ) </dev/null >> "$LOG_DIR/$service.log" 2>&1 9>&- &
      pid=$!
      started+=("$service")
      printf 'Starting %s (PID %s)\n' "$service" "$pid"
    fi
    wait_ready "$service" "$pid" || fail "$service did not become healthy."
  done
  printf 'All four services passed HTTP health checks.\nLogs: %s\n' "$LOG_DIR"
  printf 'Transit returns 503 until a valid delay event arrives.\n'
}

case "$action" in
  up|stop|restart|build|test)
    need flock
    exec 9>"$STATE_DIR/launcher.lock"
    flock -n 9 || fail 'Another launcher operation is in progress.'
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    ;;
esac

case "$action" in
  up) up ;;
  stop) stop_services; printf 'Tracked JAR processes stopped. Shared broker left running.\n' ;;
  restart) stop_services; up ;;
  build|test) build_modules ;;
  status)
    need curl
    for service in "${services[@]}"; do
      if pid="$(tracked_pid "$service")"; then
        if healthy "$service"; then state=healthy; else state=unhealthy; fi
        printf '%-22s port=%s pid=%s %s\n' "$service" "${ports[$service]}" "$pid" "$state"
      else
        printf '%-22s port=%s stopped or untracked\n' "$service" "${ports[$service]}"
      fi
    done
    printf 'Logs: %s\n' "$LOG_DIR"
    ;;
  logs)
    shopt -s nullglob
    files=("$LOG_DIR/"*.log)
    ((${#files[@]} > 0)) || fail 'No logs yet. Run make up first.'
    printf 'Following logs. Ctrl+C exits this viewer; services keep running.\n'
    exec tail -n 50 -F "${files[@]}"
    ;;
  help) printf 'Use make help, or bash scripts/dev.sh {up|stop|restart|status|logs|build|test}\n' ;;
  *) fail "Unknown action: $action" ;;
esac