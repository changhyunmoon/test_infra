# Blue/Green Deployment

이 문서는 GitHub Actions, Docker Hub, EC2, Docker Compose, Nginx를 이용한 블루/그린 배포 구조를 설명한다.

## 목표

배포 중에도 사용자가 접근하는 진입 포트 `8080`을 유지하면서 새 버전 컨테이너를 별도 포트에 먼저 띄우고, 헬스체크가 성공한 뒤 Nginx 설정만 교체한다.

```text
사용자 요청
  -> EC2:8080
  -> Nginx
  -> api-server-blue:8081 또는 api-server-green:8082
```

## 관련 파일

| 파일 | 역할 |
| --- | --- |
| `.github/workflows/cicd-worflow.yml` | CI/CD 파이프라인 정의 |
| `Dockerfile` | Spring Boot 애플리케이션 이미지 빌드 |
| `deployment/switch-blue-green.sh` | EC2에서 실제 blue/green 전환 수행 |
| `deployment/docker-compose/docker-compose.blue.yml` | blue 컨테이너 실행 정의 |
| `deployment/docker-compose/docker-compose.green.yml` | green 컨테이너 실행 정의 |
| `deployment/nginx/nginx-blue.conf` | Nginx가 blue 슬롯으로 프록시하도록 설정 |
| `deployment/nginx/nginx-green.conf` | Nginx가 green 슬롯으로 프록시하도록 설정 |
| `src/main/java/.../HealthController.java` | `/api/health`, `/health` 헬스체크 제공 |

## GitHub Actions 흐름

`.github/workflows/cicd-worflow.yml`은 `main` 브랜치 push 또는 수동 실행으로 동작한다.

단, k6 관련 파일만 바뀐 경우에는 API 서버 배포를 실행하지 않도록 제외한다.

```yaml
paths-ignore:
  - 'k6/**'
  - '.github/workflows/k6-deploy.yml'
```

### 1. Build job

빌드 작업은 다음 순서로 진행된다.

1. 저장소 checkout
2. JDK 17 설정
3. GitHub Secret `APPLICATION_PROD`를 base64 decode하여 `application-prod.yml` 생성
4. Docker Buildx 설정
5. Docker Hub 로그인
6. Docker 이미지 빌드 및 push

이미지는 두 태그로 push된다.

```text
{DOCKERHUB_USERNAME}/api-server:latest
{DOCKERHUB_USERNAME}/api-server:{github.sha}
```

배포에서는 `github.sha` 태그를 사용한다. 이 방식은 어떤 커밋이 배포됐는지 추적하기 쉽고, `latest` 태그 변경으로 인한 혼선을 줄인다.

### 2. Deploy job

배포 작업은 다음 순서로 진행된다.

1. 저장소 checkout
2. `deployment/.env` 생성
3. `deployment/**` 파일을 EC2의 `/home/ubuntu/deploy`로 복사
4. EC2에서 `switch-blue-green.sh` 실행

생성되는 `.env` 값은 Docker Compose가 사용할 이미지 정보를 담는다.

```text
DOCKERHUB_USERNAME=...
DOCKER_IMAGE_NAME=api-server
DOCKER_IMAGE_TAG={github.sha}
```

## EC2 배포 스크립트 흐름

`deployment/switch-blue-green.sh`는 EC2에서 실행된다.

### 1. 환경 로딩

스크립트는 `/home/ubuntu/deploy`를 기준 디렉터리로 사용한다.

```bash
BASE_DIR="/home/ubuntu/deploy"
COMPOSE_DIR="$BASE_DIR/docker-compose"
NGINX_DIR="$BASE_DIR/nginx"
NETWORK_NAME="api-server-network"
ENTRY_PORT=8080
```

`.env` 파일을 읽어 Docker 이미지 이름과 태그를 가져온다.

### 2. Docker network 보장

API 서버, Prometheus, Loki, Grafana, Promtail이 서로 컨테이너 이름으로 통신할 수 있도록 외부 네트워크 `api-server-network`를 생성한다.

```bash
docker network create "$NETWORK_NAME"
```

이미 있으면 재사용한다.

### 3. 모니터링 컨테이너 기동

Prometheus, Grafana, Loki, Promtail 중 하나라도 실행 중이 아니면 `docker-compose.monitoring.yml`로 모니터링 스택을 올린다.

또한 Promtail은 설정 변경 반영을 위해 강제로 재생성하도록 구성했다.

```bash
docker compose -f "$MONITORING_COMPOSE_FILE" up -d --force-recreate --no-deps promtail
```

Promtail은 설정 파일을 자동으로 다시 읽지 않기 때문에, 이 처리가 없으면 `promtail-config.yml`을 수정해도 서버에서 계속 이전 설정으로 동작할 수 있다.

### 4. 배포 대상 색상 결정

현재 `api-server-blue`가 실행 중이면 다음 배포 대상은 green이다.

```bash
IS_BLUE=$(docker ps --filter "name=api-server-blue" --filter "status=running" -q)
```

반대로 blue가 실행 중이 아니면 다음 배포 대상은 blue다.

| 현재 실행 중 | 새 배포 대상 | 새 포트 | 이전 대상 |
| --- | --- | --- | --- |
| blue | green | `8082` | blue |
| green 또는 없음 | blue | `8081` | green |

### 5. 새 이미지 pull 및 컨테이너 실행

대상 색상에 맞는 Compose 파일로 이미지를 pull하고 컨테이너를 실행한다.

```bash
docker compose -f "$COMPOSE_DIR/docker-compose.${TARGET_COLOR}.yml" pull
docker compose -f "$COMPOSE_DIR/docker-compose.${TARGET_COLOR}.yml" up -d
```

### 6. 헬스체크

새 컨테이너의 `/api/health`를 직접 호출한다.

```bash
curl http://127.0.0.1:$TARGET_PORT/api/health
```

최대 15회, 5초 간격으로 확인한다. 실패하면 새 컨테이너 로그를 출력하고 배포를 중단한다.

### 7. Nginx 전환

헬스체크가 성공하면 Nginx 설정 파일을 교체한다.

```bash
sudo cp "$NGINX_DIR/nginx-${TARGET_COLOR}.conf" /etc/nginx/sites-available/default
sudo nginx -t
sudo systemctl reload nginx
```

이 단계가 실제 트래픽 전환이다. 애플리케이션 컨테이너를 직접 바꾸는 것이 아니라 Nginx upstream만 바꾼다.

### 8. 진입 포트 헬스체크

Nginx 전환 후 외부 진입 포트 기준으로 `/health`를 확인한다.

```bash
curl http://127.0.0.1:8080/health
```

실패하면 이전 Nginx 설정으로 되돌리고 새 컨테이너를 중지한다.

### 9. 이전 컨테이너 정리

전환이 끝나면 이전 색상 컨테이너를 중지하고 제거한다.

```bash
docker compose -f "$COMPOSE_DIR/docker-compose.${OLD_COLOR}.yml" stop
docker compose -f "$COMPOSE_DIR/docker-compose.${OLD_COLOR}.yml" rm -f
```

## Docker Compose 슬롯

blue와 green은 같은 이미지를 사용하지만 컨테이너 이름과 호스트 포트가 다르다.

| 슬롯 | 컨테이너 이름 | 포트 매핑 |
| --- | --- | --- |
| blue | `api-server-blue` | `8081:8080` |
| green | `api-server-green` | `8082:8080` |

두 컨테이너 모두 `SPRING_PROFILES_ACTIVE=prod`로 실행된다.

Docker logging driver는 `json-file`을 사용한다.

```yaml
logging:
  driver: "json-file"
  options:
    max-size: "10m"
    max-file: "5"
```

이 설정은 Promtail이 Docker 로그 파일을 읽어 Loki로 전달하는 기반이다.

## Nginx 설정

`nginx-blue.conf`는 `127.0.0.1:8081`로 프록시한다.

```nginx
upstream blue {
    server 127.0.0.1:8081;
}
```

`nginx-green.conf`는 `127.0.0.1:8082`로 프록시한다.

```nginx
upstream green {
    server 127.0.0.1:8082;
}
```

공통적으로 `/api/` 요청은 현재 활성 슬롯으로 전달하고, `/health`는 활성 슬롯의 `/api/health`로 전달한다.

## 롤백 방식

이 구조의 롤백은 Nginx 설정을 이전 색상으로 되돌리는 방식이다.

배포 중 진입 포트 헬스체크가 실패하면 스크립트가 이전 설정을 복구한다.

```bash
sudo cp "$NGINX_DIR/nginx-${OLD_COLOR}.conf" /etc/nginx/sites-available/default
sudo nginx -t && sudo systemctl reload nginx
```

## 운영 확인 명령

```bash
docker ps
curl -i http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8081/api/health
curl -i http://127.0.0.1:8082/api/health
docker logs api-server-blue --tail 100
docker logs api-server-green --tail 100
```

