#!/usr/bin/env bash
# ============================================================
# launch.sh — lance imomswms (backend) + storepilot (frontend)
# pour les sessions de debug.
#
# Usage :
#   ./launch.sh             lance les deux serveurs
#   ./launch.sh --back      backend seul   (http://localhost:8040)
#   ./launch.sh --front     frontend seul  (http://localhost:3000)
#   ./launch.sh --help      affiche cette aide
#
# Les logs sont prefixés [imomswms] / [storepilot] à l'écran et
# conservés dans des fichiers /tmp (chemins affichés à l'arrêt).
# Ctrl+C arrête proprement les deux serveurs (et leurs enfants).
# Si un port (8040/3000) est déjà occupé, les processus qui l'occupent
# sont automatiquement arrêtés avant le lancement.
#
# Variables d'env optionnelles :
#   BROWSER=none ./launch.sh    pour ne pas ouvrir le navigateur
# ============================================================

set -u

# Le script vit dans imomswms : les chemins sont déduits de son emplacement
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR"
FRONTEND_DIR="$SCRIPT_DIR/../storepilot"

BACKEND_PORT=8040
FRONTEND_PORT=3000
BACKEND_TIMEOUT=180   # secondes max d'attente du démarrage backend

ONLY_BACKEND=0
ONLY_FRONTEND=0

case "${1:-}" in
  --back|--backend)   ONLY_FRONTEND=1 ;;
  --front|--frontend) ONLY_BACKEND=1 ;;
  -h|--help)
    sed -n '2,20p' "$0"
    exit 0
    ;;
  "") ;;
  *)
    echo "Option inconnue: $1 (voir --help)"
    exit 1
    ;;
esac

BACKEND_LOG=$(mktemp /tmp/imomswms-debug.XXXXXX)
FRONTEND_LOG=$(mktemp /tmp/storepilot-debug.XXXXXX)

BACKEND_PID=""
FRONTEND_PID=""
TAIL_BACK_PID=""
TAIL_FRONT_PID=""

# Tue un process et tous ses descendants (npm -> node, mvnw -> java...)
kill_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null); do
    kill_tree "$child"
  done
  kill "$pid" 2>/dev/null
}

cleanup() {
  local code=${1:-0}
  trap - INT TERM
  echo
  echo "[debug] Arrêt des serveurs..."
  [ -n "$FRONTEND_PID" ] && kill_tree "$FRONTEND_PID"
  [ -n "$BACKEND_PID" ] && kill_tree "$BACKEND_PID"
  sleep 2
  [ -n "$FRONTEND_PID" ] && kill -9 "$FRONTEND_PID" 2>/dev/null
  [ -n "$BACKEND_PID" ] && kill -9 "$BACKEND_PID" 2>/dev/null
  [ -n "$TAIL_BACK_PID" ] && kill "$TAIL_BACK_PID" 2>/dev/null
  [ -n "$TAIL_FRONT_PID" ] && kill "$TAIL_FRONT_PID" 2>/dev/null
  # Dans une pipeline `tail | awk`, $! capture awk : le tail est tué par pattern
  # (le chemin du log est unique ; on évite les regex sur "tail -n +1")
  pkill -f "$BACKEND_LOG" 2>/dev/null
  pkill -f "$FRONTEND_LOG" 2>/dev/null
  echo "[debug] Logs conservés :"
  echo "  backend  : $BACKEND_LOG"
  echo "  frontend : $FRONTEND_LOG"
  exit "$code"
}
trap 'cleanup 0' INT TERM HUP

port_busy() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$1/"
  fi
}

# Tue les processus qui écoutent sur un port, puis attend que le
# port soit libéré (SIGTERM, puis SIGKILL si besoin).
kill_port() {
  local port=$1 pids pid waited
  if command -v lsof >/dev/null 2>&1; then
    pids=$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null)
  elif command -v fuser >/dev/null 2>&1; then
    pids=$(fuser "$port/tcp" 2>/dev/null)
  else
    pids=""
  fi
  [ -z "$pids" ] && return 0
  echo "[debug] Port $port occupé (PID $(echo $pids | tr '\n' ' ')): arrêt des processus précédents..."
  for pid in $pids; do
    kill_tree "$pid"
  done
  waited=0
  while port_busy "$port" && [ "$waited" -lt 10 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if port_busy "$port"; then
    echo "[debug] Port $port toujours occupé : kill -9..."
    for pid in $pids; do
      kill -9 "$pid" 2>/dev/null
    done
    waited=0
    while port_busy "$port" && [ "$waited" -lt 5 ]; do
      sleep 1
      waited=$((waited + 1))
    done
  fi
  if port_busy "$port"; then
    echo "[debug] ERREUR: impossible de libérer le port $port"
    return 1
  fi
  echo "[debug] Port $port libéré."
  return 0
}

# --- Libération des ports ---------------------------------------

if [ "$ONLY_BACKEND" -eq 0 ]; then
  kill_port "$BACKEND_PORT" || exit 1
fi
if [ "$ONLY_FRONTEND" -eq 0 ]; then
  kill_port "$FRONTEND_PORT" || exit 1
fi

# --- Backend : imomswms (Quarkus dev) ---------------------------

if [ "$ONLY_BACKEND" -eq 0 ]; then
  echo "[debug] Lancement d'imomswms (Quarkus dev, port $BACKEND_PORT)..."
  cd "$BACKEND_DIR" || exit 1
  [ -x ./mvnw ] || chmod +x ./mvnw
  ./mvnw quarkus:dev >"$BACKEND_LOG" 2>&1 &
  BACKEND_PID=$!

  echo "[debug] Attente du démarrage du backend (max ${BACKEND_TIMEOUT}s)..."
  up=0
  i=0
  while [ "$i" -lt "$BACKEND_TIMEOUT" ]; do
    if curl -s -o /dev/null --max-time 2 "http://localhost:$BACKEND_PORT/"; then
      up=1
      break
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "[debug] ERREUR: le backend est mort pendant le démarrage."
      tail -40 "$BACKEND_LOG"
      cleanup 1
    fi
    sleep 1
    i=$((i + 1))
  done
  if [ "$up" -ne 1 ]; then
    echo "[debug] ERREUR: backend injoignable après ${BACKEND_TIMEOUT}s."
    tail -40 "$BACKEND_LOG"
    cleanup 1
  fi
  echo "[debug] Backend prêt : http://localhost:$BACKEND_PORT (Dev UI: /q/dev, H2 console: /h2-console)"
fi

# --- Frontend : storepilot (React) ------------------------------

if [ "$ONLY_FRONTEND" -eq 0 ]; then
  echo "[debug] Lancement de storepilot (React, port $FRONTEND_PORT)..."
  cd "$FRONTEND_DIR" || exit 1
  if [ ! -d node_modules ]; then
    echo "[debug] node_modules absent : npm ci en cours..."
    npm ci >"$FRONTEND_LOG" 2>&1 || { echo "[debug] ERREUR: npm ci a échoué"; cleanup 1; }
  fi
  PORT=$FRONTEND_PORT npm start >"$FRONTEND_LOG" 2>&1 &
  FRONTEND_PID=$!
fi

# --- Bannière + logs en continu ---------------------------------

ESC=$'\033'
echo
echo "=============================================================="
[ "$ONLY_BACKEND" -eq 0 ] && echo "  imomswms   (backend)  : http://localhost:$BACKEND_PORT"
[ "$ONLY_FRONTEND" -eq 0 ] && echo "  storepilot (frontend) : http://localhost:$FRONTEND_PORT"
echo "  Ctrl+C pour tout arrêter"
echo "=============================================================="
echo

if [ -n "$BACKEND_PID" ]; then
  # awk + fflush : line-buffered (sed bufferiserait par blocs de 4Ko)
  tail -n +1 -f "$BACKEND_LOG" | awk -v p="${ESC}[36m[imomswms]${ESC}[0m " '{ print p $0; fflush() }' &
  TAIL_BACK_PID=$!
fi
if [ -n "$FRONTEND_PID" ]; then
  tail -n +1 -f "$FRONTEND_LOG" | awk -v p="${ESC}[32m[storepilot]${ESC}[0m " '{ print p $0; fflush() }' &
  TAIL_FRONT_PID=$!
fi

# --- Surveillance : si un serveur meurt, on arrête tout ---------

while true; do
  sleep 2
  if [ -n "$BACKEND_PID" ] && ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo
    echo "[debug] imomswms s'est arrêté. Log : $BACKEND_LOG"
    cleanup 0
  fi
  if [ -n "$FRONTEND_PID" ] && ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo
    echo "[debug] storepilot s'est arrêté. Log : $FRONTEND_LOG"
    cleanup 0
  fi
done
