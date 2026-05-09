#!/bin/bash

# 에러 발생 시 즉시 중단
set -e

# 1. 환경 설정 및 .env 로드
BASE_DIR="/home/ubuntu/deploy"
COMPOSE_DIR="$BASE_DIR/docker-compose"
NGINX_DIR="$BASE_DIR/nginx"
NETWORK_NAME="api-server-network"

cd "$BASE_DIR"

if [ -f .env ]; then
    set -a
    source .env
    set +a
else
    echo "❌ .env 파일이 없습니다. 배포를 중단합니다."
    exit 1
fi

# 로그 함수
log() {
    echo -e "[$(date +"%Y-%m-%d %T")] $1"
}

log "🚀 --- 무중단 배포 프로세스 시작 (Tag: $DOCKER_IMAGE_TAG) ---"

if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    log "Docker network '$NETWORK_NAME' 없음. 새로 생성합니다."
    docker network create "$NETWORK_NAME"
fi

# Prometheus / Grafana 최초 1회 실행
MONITORING_COMPOSE_FILE="$COMPOSE_DIR/docker-compose.monitoring.yml"

is_container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]
}

if ! is_container_running prometheus || \
   ! is_container_running grafana || \
   ! is_container_running loki || \
   ! is_container_running promtail; then
    log "📊 모니터링 컨테이너 중 실행되지 않은 항목이 있습니다. Prometheus/Grafana/Loki/Promtail을 시작합니다."
    docker compose -f "$MONITORING_COMPOSE_FILE" up -d
else
    log "📊 Prometheus/Grafana/Loki/Promtail이 이미 실행 중입니다. 모니터링 컨테이너 실행을 건너뜁니다."
fi

# 2. Blue/Green 상태 결정
# api-server-blue 컨테이너가 실행 중인지 확인
IS_BLUE=$(docker ps --filter "name=api-server-blue" --filter "status=running" -q)

if [ -z "$IS_BLUE" ]; then
    TARGET_COLOR="blue"
    TARGET_PORT=8081
    OLD_COLOR="green"
else
    TARGET_COLOR="green"
    TARGET_PORT=8082
    OLD_COLOR="blue"
fi

log "🚢 배포 타겟: $TARGET_COLOR (Port: $TARGET_PORT)"

# 3. 새 버전 이미지 Pull
log "1. $TARGET_COLOR 이미지 Pull..."
docker compose -f "$COMPOSE_DIR/docker-compose.${TARGET_COLOR}.yml" pull

# 4. 새 컨테이너 실행
log "2. $TARGET_COLOR 컨테이너 실행..."
docker compose -f "$COMPOSE_DIR/docker-compose.${TARGET_COLOR}.yml" up -d

# 5. 헬스체크
MAX_RETRIES=15
for i in $(seq 1 $MAX_RETRIES); do
    log "3. $TARGET_COLOR 헬스체크 중... ($i/$MAX_RETRIES)"
    sleep 5

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:$TARGET_PORT/api/health || true)

    if [ "$HTTP_STATUS" -eq 200 ]; then
        log "✅ 헬스체크 성공!"
        break
    fi

    if [ $i -eq $MAX_RETRIES ]; then
        log "❌ 헬스체크 실패. 새 컨테이너를 중지하고 배포를 취소합니다."
        docker compose -f "$COMPOSE_DIR/docker-compose.${TARGET_COLOR}.yml" stop
        exit 1
    fi
done

# 6. Nginx 설정 전환
log "4. Nginx 설정 교체 및 Reload..."
# 이전에 만든 nginx-blue.conf 또는 nginx-green.conf를 적용
sudo cp "$NGINX_DIR/nginx-${TARGET_COLOR}.conf" /etc/nginx/sites-available/default

# Nginx 문법 검사 후 리로드
if sudo nginx -t; then
    if sudo systemctl is-active --quiet nginx; then
        sudo systemctl reload nginx
    else
        sudo systemctl start nginx
    fi
    log "✅ Nginx 트래픽 전환 완료!"
else
    log "❌ Nginx 설정 오류 발생. 배포를 중단합니다."
    exit 1
fi

# 7. 이전 컨테이너 정리
log "5. 이전 컨테이너($OLD_COLOR) 정리..."
docker compose -f "$COMPOSE_DIR/docker-compose.${OLD_COLOR}.yml" stop || true
docker compose -f "$COMPOSE_DIR/docker-compose.${OLD_COLOR}.yml" rm -f || true

# 8. 디스크 공간 확보
log "6. 불필요한 이미지 및 빌드 캐시 정리..."
docker image prune -af
docker builder prune -f

log "🎊 배포 완료!"
log "--- 현재 실행 중 컨테이너 ---"
docker ps
