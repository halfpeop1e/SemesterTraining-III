#!/usr/bin/env zsh
set -euo pipefail

# ── 参数解析 ──
LAB=false
TRAIN_ID='LB'
BUILD=false
WAIT_SECONDS=90

while [[ $# -gt 0 ]]; do
    case "$1" in
        --lab|-Lab) LAB=true; shift ;;
        --train-id|-TrainId) TRAIN_ID="$2"; shift 2 ;;
        --build|-Build) BUILD=true; shift ;;
        --wait|-WaitSeconds) WAIT_SECONDS="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ── 辅助函数 ──
get_compose_setting() {
    local name="$1" default="$2"
    local value=""
    if [[ -n "${(P)name+x}" ]]; then
        value="${(P)name}"
    fi
    if [[ -z "$value" && -f .env ]]; then
        value=$(grep -E "^\s*${name}\s*=" .env | tail -1 | sed -E "s/^\s*${name}\s*=\s*//; s/^[\"']//; s/[\"']$//; s/\s*$//")
    fi
    echo "${value:-$default}"
}

check_docker_ready() {
    docker info &>/dev/null
}

# ── 检查 Docker ──
if ! command -v docker &>/dev/null; then
    echo "Docker not found. Please install Docker Desktop and try again." >&2
    exit 1
fi

if ! check_docker_ready; then
    echo "Docker is not running. Please start Docker Desktop and try again." >&2
    exit 1
fi

# ── 端口检测 ──
find_free_port() {
    local start_port="$1" port="$start_port"
    while [[ $port -lt 65535 ]]; do
        if ! lsof -iTCP:"$port" -sTCP:LISTEN &>/dev/null; then
            echo "$port"
            return 0
        fi
        ((port++))
    done
    echo "No free port found starting from $start_port." >&2
    return 1
}

CONFIGURED_BACKEND_PORT=$(get_compose_setting 'BACKEND_PORT' '8080')
CONFIGURED_CC_PORT=$(get_compose_setting 'CONTROL_CENTER_PORT' '5173')
CONFIGURED_ONBOARD_PORT=$(get_compose_setting 'ONBOARD_PORT' '5174')

BACKEND_PORT=$(find_free_port "$CONFIGURED_BACKEND_PORT")
CC_PORT="$CONFIGURED_CC_PORT"
ONBOARD_PORT="$CONFIGURED_ONBOARD_PORT"

if [[ "$BACKEND_PORT" != "$CONFIGURED_BACKEND_PORT" ]]; then
    echo -e "\033[33mPort $CONFIGURED_BACKEND_PORT is in use. Backend will use port $BACKEND_PORT instead.\033[0m"
fi
if [[ "$CC_PORT" -eq "$BACKEND_PORT" ]]; then
    CC_PORT=$(find_free_port $((CC_PORT + 1)))
fi
if [[ "$CC_PORT" != "$CONFIGURED_CC_PORT" ]]; then
    echo -e "\033[33mPort $CONFIGURED_CC_PORT is in use. Control center will use port $CC_PORT instead.\033[0m"
fi
if [[ "$ONBOARD_PORT" -eq "$BACKEND_PORT" || "$ONBOARD_PORT" -eq "$CC_PORT" ]]; then
    base=$((ONBOARD_PORT > CC_PORT ? ONBOARD_PORT : CC_PORT))
    ONBOARD_PORT=$(find_free_port $((base + 1)))
fi
if [[ "$ONBOARD_PORT" != "$CONFIGURED_ONBOARD_PORT" ]]; then
    echo -e "\033[33mPort $CONFIGURED_ONBOARD_PORT is in use. Onboard system will use port $ONBOARD_PORT instead.\033[0m"
fi

# ── 导出环境变量 ──
export BACKEND_PORT
export CONTROL_CENTER_PORT="$CC_PORT"
export ONBOARD_PORT
export HIL_TRAIN_ID="$TRAIN_ID"

if $LAB; then
    export HIL_ENABLED='true'
    export HIL_SIGNAL_SCREEN_ENABLED='true'
    export HIL_NETWORK_SCREEN_ENABLED='true'
    export HIL_VISION_ENABLED='true'
    export PLC_AUTO_START='false'
    export PLC_OUTPUT_ENABLED='true'
    export PLC_OUTPUT_FRAME_FORMAT='capture-variant-28'
    echo -e "\033[36mLaboratory mode: train ID=$TRAIN_ID; screens, Vision, and PLC 28-byte output are enabled.\033[0m"
    echo -e "\033[33mPLC is not auto-connected. Create and bring the same train ID online, then click 704 Connect in the onboard page.\033[0m"
else
    export HIL_ENABLED='false'
    export HIL_SIGNAL_SCREEN_ENABLED='false'
    export HIL_NETWORK_SCREEN_ENABLED='false'
    export HIL_VISION_ENABLED='false'
    export PLC_AUTO_START='false'
    export PLC_OUTPUT_ENABLED='false'
    export PLC_OUTPUT_FRAME_FORMAT='capture-variant-28'
    echo -e "\033[32mLocal simulation mode: no laboratory device will be connected or written.\033[0m"
fi

# ── Docker Compose 启动 ──
if $BUILD; then
    docker compose up -d --build
else
    docker compose up -d
fi

if [[ $? -ne 0 ]]; then
    echo "Docker Compose startup failed. Run 'docker compose logs' to inspect it." >&2
    exit 1
fi

# ── 健康检查等待 ──
HEALTH_URL="http://localhost:${BACKEND_PORT}/api/health"
DEADLINE=$(( $(date +%s) + WAIT_SECONDS ))

while [[ $(date +%s) -lt $DEADLINE ]]; do
    if curl -sf --connect-timeout 3 "$HEALTH_URL" | grep -q '"success":true'; then
        break
    fi
    sleep 2
done

if ! curl -sf --connect-timeout 3 "$HEALTH_URL" | grep -q '"success":true'; then
    docker compose ps
    echo "Backend health check did not pass within ${WAIT_SECONDS} seconds. Run 'docker compose logs backend'." >&2
    exit 1
fi

echo ''
echo -e "\033[32mStarted:\033[0m"
echo "  Control center: http://localhost:${CC_PORT}"
echo "  Onboard system: http://localhost:${ONBOARD_PORT}"
echo "  Backend health: http://localhost:${BACKEND_PORT}/api/health"
if $LAB; then
    echo "  HIL status:     http://localhost:${BACKEND_PORT}/api/hil/status"
fi
