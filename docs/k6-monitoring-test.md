# k6 Monitoring Test

이 문서는 DB 모니터링 검증을 위해 작성한 k6 부하 테스트 스크립트를 설명한다.

## 관련 파일

| 파일 | 역할 |
| --- | --- |
| `k6/monitoring-test.js` | API 부하 테스트와 모니터링 검증 요청 |
| `.github/workflows/k6-deploy.yml` | k6 스크립트를 별도 EC2 서버로 복사 |
| `src/main/java/.../monitoring/*` | 테스트용 DB API |

## k6 배포 workflow

`.github/workflows/k6-deploy.yml`은 `k6/**` 또는 workflow 파일이 변경됐을 때 실행된다.

```yaml
paths:
  - 'k6/**'
  - '.github/workflows/k6-deploy.yml'
```

API 서버 배포 workflow와 분리되어 있으므로, 부하 테스트 스크립트만 수정해도 API 서버 이미지를 다시 빌드하지 않는다.

스크립트는 k6 서버의 `/home/ubuntu/k6`로 복사된다.

```yaml
source: "k6/*"
target: "/home/ubuntu/k6"
strip_components: 1
```

## 테스트 대상 API

`monitoring-test.js`는 두 API를 호출한다.

| API | Method | 역할 |
| --- | --- | --- |
| `/api/monitoring/tests` | POST | test 테이블에 row 생성 |
| `/api/monitoring/tests/count` | GET | test 테이블 row 수 조회 |

두 API 모두 DB를 사용하므로 SQL 로그와 DB 메트릭 검증에 적합하다.

## 테스트 시나리오

현재 설정은 constant-vus 방식이다.

```javascript
scenarios: {
  monitoring_smoke: {
    executor: 'constant-vus',
    vus: Number(__ENV.VUS || 5),
    duration: __ENV.DURATION || '1m',
  },
}
```

환경 변수로 VU 수와 테스트 시간을 조절할 수 있다.

```bash
VUS=10 DURATION=3m k6 run monitoring-test.js
```

## 요청 헤더

모든 요청에 테스트 추적용 헤더를 넣는다.

```javascript
const headers = {
  'Content-Type': 'application/json',
  'X-Test-Run-Id': TEST_RUN_ID,
  'X-Scenario': 'monitoring-jpa-test',
};
```

이 값은 애플리케이션 로그의 MDC에 들어가며 Loki에서 필터링할 수 있다.

```logql
{app="api-server"} |= "testRunId=monitoring-1"
```

## 커스텀 메트릭

k6 스크립트는 API별 응답 시간을 별도 Trend로 기록한다.

```javascript
const createDuration = new Trend('monitoring_create_duration');
const countDuration = new Trend('monitoring_count_duration');
const apiSuccess = new Rate('monitoring_api_success');
```

| k6 메트릭 | 설명 |
| --- | --- |
| `monitoring_create_duration` | POST 생성 API 응답 시간 |
| `monitoring_count_duration` | GET count API 응답 시간 |
| `monitoring_api_success` | API 성공률 |

## Threshold

테스트 실패 기준은 다음과 같다.

```javascript
thresholds: {
  http_req_failed: ['rate<0.05'],
  http_req_duration: ['p(95)<1000'],
  monitoring_create_duration: ['p(95)<1000'],
  monitoring_count_duration: ['p(95)<500'],
}
```

| 기준 | 의미 |
| --- | --- |
| `http_req_failed rate<0.05` | 전체 요청 실패율 5% 미만 |
| `http_req_duration p95<1000` | 전체 요청 p95 1초 미만 |
| `monitoring_create_duration p95<1000` | 생성 API p95 1초 미만 |
| `monitoring_count_duration p95<500` | count API p95 0.5초 미만 |

## 실행 후 확인할 것

### Docker logs

```bash
docker logs api-server-green --tail 100
```

다음 로그가 보여야 한다.

```text
event=http_request_db_summary
event=db_query
```

### Loki

```logql
{app="api-server"} |= "testRunId=monitoring-1"
```

### Prometheus

```promql
sum(rate(app_http_request_db_query_count_sum[5m])) by (method, uri)
/
sum(rate(app_http_request_db_query_count_count[5m])) by (method, uri)
```

요청당 쿼리 수가 API별로 확인되어야 한다.

## 주의점

현재 `BASE_URL`은 스크립트에 고정되어 있다.

```javascript
const BASE_URL = 'http://3.37.158.87:8080/api';
```

환경이 바뀔 수 있다면 `__ENV.BASE_URL` 기반으로 바꾸는 것이 좋다.

