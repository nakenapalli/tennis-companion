#!/usr/bin/env bash
#
# dev.sh — start/stop the tennis-companion APP processes only:
#   • backend  — ./gradlew bootRun        (:8080)
#   • frontend — npm run dev  (frontend/)  (:3000)
#
# Infra (Postgres :5432 + Redis :6379) is intentionally NOT managed here — keep those containers
# running for your whole session and start/stop the heavier app processes as needed:
#       docker compose up -d postgres redis     # once per session (you run this, not dev.sh)
#       ./dev.sh up                              # spin up backend + frontend
#       ./dev.sh down                            # stop them (infra keeps running)
#
# `up` only *checks* infra is reachable and bails with a hint if it isn't. Per-process pids + logs
# live in .dev/ (gitignored). Each process is started in its own session so `down` can kill the whole
# tree (the gradle/JVM and next/turbopack children), not just the launcher.
#
# Usage: ./dev.sh {up|down|restart|status|logs [backend|frontend]}

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV_DIR="$ROOT/.dev"
mkdir -p "$DEV_DIR"

BACKEND_PORT=8080
FRONTEND_PORT=3000
PG_PORT=5432
REDIS_PORT=6379

# --- helpers ---------------------------------------------------------------

pidfile() { echo "$DEV_DIR/$1.pid"; }
logfile() { echo "$DEV_DIR/$1.log"; }

# Is the tracked process for $1 still alive?
proc_alive() {
  local pf; pf="$(pidfile "$1")"
  [ -f "$pf" ] || return 1
  local pid; pid="$(cat "$pf" 2>/dev/null)"
  [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null
}

# Can we open a TCP connection to host:port? (1s cap so a black-holed port can't hang us.)
port_open() { timeout 1 bash -c ">/dev/tcp/$1/$2" 2>/dev/null; }

# Refuse to start the app if the infra it depends on isn't up — but don't try to manage it.
check_infra() {
  local ok=1
  port_open localhost "$PG_PORT"    || { echo "  ✗ Postgres not reachable on :$PG_PORT"; ok=0; }
  port_open localhost "$REDIS_PORT" || { echo "  ✗ Redis not reachable on :$REDIS_PORT"; ok=0; }
  if [ "$ok" -ne 1 ]; then
    echo
    echo "  Infra isn't up. dev.sh doesn't manage it — start it once for your session with:"
    echo "      docker compose up -d postgres redis"
    return 1
  fi
}

# start_proc <name> <command-string>  — launches in a new session, records the pid.
start_proc() {
  local name="$1" cmd="$2" pf log
  pf="$(pidfile "$name")"; log="$(logfile "$name")"
  if proc_alive "$name"; then
    echo "  • $name already running (pid $(cat "$pf"))"
    return 0
  fi
  : > "$log"
  # Fully detach so the app outlives this script: setsid → new session/process group (pid == pgid, so
  # `down` can signal the whole tree); nohup → ignore SIGHUP when the launching shell exits; </dev/null →
  # don't let `next dev` watch stdin (it quits on stdin close, which the JVM ignores). exec → the tracked
  # pid IS the session leader.
  nohup setsid bash -c "$cmd" </dev/null >>"$log" 2>&1 &
  echo $! > "$pf"
  echo "  • started $name (pid $!) → ${log#"$ROOT"/}"
}

# stop_proc <name> — TERM the whole process group, escalate to KILL if it lingers.
stop_proc() {
  local name="$1" pf pid
  pf="$(pidfile "$name")"
  if [ ! -f "$pf" ]; then echo "  • $name not running"; return 0; fi
  pid="$(cat "$pf" 2>/dev/null)"
  if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM -- -"$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL -- -"$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null
      echo "  • force-killed $name (pid $pid)"
    else
      echo "  • stopped $name (pid $pid)"
    fi
  else
    echo "  • $name was not running (stale pid ${pid:-?})"
  fi
  rm -f "$pf"
}

# Poll an HTTP endpoint until it answers (best-effort readiness; skipped if curl is absent).
wait_http() {
  local url="$1" label="$2" tries="${3:-90}" i
  command -v curl >/dev/null 2>&1 || return 0
  for ((i = 0; i < tries; i++)); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then echo "  • $label ready"; return 0; fi
    sleep 1
  done
  echo "  • $label not ready yet — still starting, watch: ./dev.sh logs"
  return 1
}

# --- commands --------------------------------------------------------------

cmd_up() {
  echo "Checking infra…"
  check_infra || exit 1
  [ -d "$ROOT/frontend/node_modules" ] || echo "  ⚠ frontend/node_modules missing — run: (cd frontend && npm install)"
  if ! proc_alive backend && port_open localhost "$BACKEND_PORT"; then
    echo "  ⚠ something is already listening on :$BACKEND_PORT — backend may fail to bind"
  fi
  echo "Starting app…"
  start_proc backend  "cd '$ROOT' && exec ./gradlew bootRun"
  start_proc frontend "cd '$ROOT/frontend' && exec npm run dev"
  echo
  wait_http "http://localhost:$BACKEND_PORT/api/health" "backend  (http://localhost:$BACKEND_PORT)" 120
  wait_http "http://localhost:$FRONTEND_PORT"           "frontend (http://localhost:$FRONTEND_PORT)" 60
  echo
  echo "Logs:  ./dev.sh logs [backend|frontend]    Stop:  ./dev.sh down"
}

cmd_down() {
  echo "Stopping app (infra left running)…"
  stop_proc frontend
  stop_proc backend
}

cmd_status() {
  local n pf
  for n in backend frontend; do
    pf="$(pidfile "$n")"
    if proc_alive "$n"; then echo "  ● $n  running (pid $(cat "$pf"))"; else echo "  ○ $n  stopped"; fi
  done
  port_open localhost "$PG_PORT"    && echo "  ● postgres reachable  :$PG_PORT"    || echo "  ○ postgres unreachable :$PG_PORT"
  port_open localhost "$REDIS_PORT" && echo "  ● redis    reachable  :$REDIS_PORT" || echo "  ○ redis    unreachable :$REDIS_PORT"
}

cmd_logs() {
  local which="${1:-}"
  case "$which" in
    backend)  exec tail -n 100 -F "$(logfile backend)" ;;
    frontend) exec tail -n 100 -F "$(logfile frontend)" ;;
    "")       exec tail -n 50 -F "$(logfile backend)" "$(logfile frontend)" ;;
    *) echo "logs: expected 'backend', 'frontend', or nothing (both)"; exit 1 ;;
  esac
}

case "${1:-}" in
  up | start)   cmd_up ;;
  down | stop)  cmd_down ;;
  restart)      cmd_down; echo; cmd_up ;;
  status | st)  cmd_status ;;
  logs)         shift; cmd_logs "${1:-}" ;;
  *) echo "Usage: ./dev.sh {up|down|restart|status|logs [backend|frontend]}"; exit 1 ;;
esac
