# Monitoring Stack

이 문서는 Prometheus, Grafana, Loki, Promtail 기반 모니터링 스택 구축 내용을 설명한다.

## 목표

애플리케이션 운영 상태를 두 방향에서 관찰한다.

| 관측 대상 | 도구 | 설명 |
| --- | --- | --- |
| 메트릭 | Prometheus | Actuator Prometheus endpoint를 주기적으로 scrape |
| 로그 | Promtail + Loki | Docker json-file 로그를 수집해 Loki에 저장 |
| 시각화/조회 | Grafana | Prometheus 메트릭과 Loki 로그 조회 |

## 관련 파일

| 파일 | 역할 |
| --- | --- |
| `deployment/docker-compose/docker-compose.monitoring.yml` | 모니터링 컨테이너 실행 정의 |
| `deployment/docker-compose/prometheus.yml` | Prometheus scrape target 정의 |
| `deployment/docker-compose/loki-config.yml` | Loki 저장소, schema, retention 설정 |
| `deployment/docker-compose/promtail-config.yml` | Docker 로그 수집 설정 |
| `src/main/resources/logback-spring.xml` | 애플리케이션 콘솔 로그 패턴과 로거 설정 |
| `src/main/resources/application.yml` | 애플리케이션 이름과 profile 기본값 |

## Docker Compose 구성

`docker-compose.monitoring.yml`은 네 개의 컨테이너를 실행한다.

| 서비스 | 이미지 | 포트 | 역할 |
| --- | --- | --- | --- |
| prometheus | `prom/prometheus:v2.53.0` | `9090` | 메트릭 수집 |
| loki | `grafana/loki:3.1.0` | `3100` | 로그 저장 |
| promtail | `grafana/promtail:3.1.0` | 내부 통신 | Docker 로그 수집 |
| grafana | `grafana/grafana:11.1.0` | `3000` | 대시보드/로그 조회 |

모두 `api-server-network`에 연결된다.

```yaml
networks:
  api-server-network:
    external: true
```

## Prometheus

Prometheus는 blue와 green 컨테이너를 모두 scrape 대상으로 가진다.

```yaml
scrape_configs:
  - job_name: 'api-server-blue'
    metrics_path: '/api/actuator/prometheus'
    static_configs:
      - targets: ['api-server-blue:8080']

  - job_name: 'api-server-green'
    metrics_path: '/api/actuator/prometheus'
    static_configs:
      - targets: ['api-server-green:8080']
```

blue/green 배포 중 어느 슬롯이 활성 상태인지와 무관하게, 실행 중인 컨테이너의 Actuator Prometheus endpoint를 수집할 수 있다.

확인 명령:

```bash
curl http://localhost:9090/targets
```

애플리케이션 메트릭 endpoint:

```text
/api/actuator/prometheus
```

## Loki

Loki는 로그 저장소 역할을 한다.

주요 설정:

```yaml
auth_enabled: false
```

단일 서버 환경이므로 인증을 끄고 내부 네트워크에서 사용한다.

```yaml
storage:
  filesystem:
    chunks_directory: /loki/chunks
    rules_directory: /loki/rules
```

로그 데이터는 Docker volume `loki-data`에 저장된다.

```yaml
retention_period: 1d
retention_enabled: true
```

보관 기간은 1일이다. 부하 테스트와 모니터링 검증 목적의 로그를 짧게 보관하도록 설정했다.

## Promtail

Promtail은 Docker 로그 파일을 읽어 Loki로 전달한다.

현재 설정은 static config 방식이다.

```yaml
scrape_configs:
  - job_name: api-server
    static_configs:
      - targets:
          - localhost
        labels:
          app: api-server
          job: api-server
          container_name: api-server-green
          __path__: /var/lib/docker/containers/*/*-json.log

    pipeline_stages:
      - docker: {}
```

중요한 라벨은 다음과 같다.

| 라벨 | 설명 |
| --- | --- |
| `app=api-server` | Grafana/Loki에서 API 서버 로그를 조회하기 위한 기본 라벨 |
| `job=api-server` | 수집 작업 구분 |
| `container_name=api-server-green` | 컨테이너 이름 표시용 라벨 |

`pipeline_stages`의 `docker` stage는 Docker json-file 로그에서 실제 로그 메시지를 파싱한다.

```yaml
pipeline_stages:
  - docker: {}
```

## Grafana

Grafana는 다음 두 데이터소스를 연결해서 사용한다.

| 데이터소스 | URL |
| --- | --- |
| Prometheus | `http://prometheus:9090` |
| Loki | `http://loki:3100` |

Grafana 접속 정보는 Compose 환경 변수로 설정되어 있다.

```yaml
GF_SECURITY_ADMIN_USER=admin
GF_SECURITY_ADMIN_PASSWORD=admin
```

## 로그 조회 예시

전체 API 서버 로그:

```logql
{app="api-server"}
```

요청별 DB summary 로그:

```logql
{app="api-server"} |= "event=http_request_db_summary"
```

특정 테스트 실행 ID:

```logql
{app="api-server"} |= "testRunId=monitoring-1"
```

특정 API URI:

```logql
{app="api-server"} |= "uri=/api/monitoring/tests"
```

SQL 로그:

```logql
{app="api-server"} |= "event=db_query"
```

## 장애 복구: 로그가 안 보일 때

### 1. 애플리케이션 로그 확인

```bash
docker logs api-server-green --tail 50
```

여기에 로그가 보이면 애플리케이션 로그 출력은 정상이다.

### 2. Promtail 로그 확인

```bash
docker logs promtail --tail 100
```

다음 에러가 있으면 Loki가 라벨 없는 스트림을 거절한 것이다.

```text
at least one label pair is required per stream
```

이 경우 `promtail-config.yml`에 `app`, `job` 같은 라벨이 실제로 적용되어 있는지 확인해야 한다.

### 3. Promtail 컨테이너 내부 설정 확인

```bash
docker exec promtail cat /etc/promtail/config.yml
```

컨테이너 안 설정에 다음 값이 있어야 한다.

```yaml
app: api-server
job: api-server
```

### 4. Promtail 재생성

Promtail은 설정 파일 변경을 자동 반영하지 않는다.

```bash
cd /home/ubuntu/deploy
docker compose -f docker-compose/docker-compose.monitoring.yml up -d --force-recreate --no-deps promtail
```

### 5. Loki 라벨 확인

```bash
curl -s http://localhost:3100/loki/api/v1/label/app/values
```

정상 응답:

```json
["api-server"]
```

## 현재 설정의 주의점

현재 Promtail 설정은 `container_name: api-server-green` 라벨이 고정되어 있다. 로그 수집 자체는 `__path__: /var/lib/docker/containers/*/*-json.log` 때문에 가능하지만, blue 슬롯으로 전환되면 라벨의 컨테이너 이름은 실제 컨테이너와 다를 수 있다.

정확한 컨테이너 라벨이 필요하면 Docker service discovery 방식으로 되돌리거나, blue/green 둘 다 별도 static target으로 정의하는 방식이 더 적합하다.

