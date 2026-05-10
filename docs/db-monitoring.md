# 부하테스트용 DB 모니터링 문서

## 목적

이 문서는 k6 부하테스트 중 API별 DB 사용량을 관찰하기 위해 구현한 내용을 정리한다.

목표는 API별로 다음 질문에 답할 수 있게 만드는 것이다.

- 요청 1건당 SQL 쿼리가 몇 번 실행되는가?
- SQL 실행시간은 얼마나 걸리는가?
- API 응답시간 중 DB 시간이 어느 정도를 차지하는가?
- 특정 k6 테스트 실행에서 발생한 SQL 로그를 어떻게 찾는가?

## 모니터링 구성

현재 모니터링 구성은 다음과 같다.

- k6: 별도 EC2에서 부하 생성
- Prometheus: 애플리케이션 메트릭 수집
- Loki: 애플리케이션 로그와 SQL 로그 저장
- Promtail: 로그 수집 및 Loki 전송
- Grafana: 메트릭 대시보드와 로그 조회
- Spring Boot Actuator + Micrometer: 애플리케이션 메트릭 노출
- datasource-proxy: JDBC 쿼리 실행 감지

## 요청 추적 구조

k6는 모든 요청에 테스트 실행 식별자를 헤더로 전달한다.

권장 헤더:

```http
X-Test-Run-Id: load-20260510-001
X-Scenario: read-orders
X-Request-Id: optional-client-request-id
```

k6 예시:

```javascript
import http from 'k6/http';

const TEST_RUN_ID = __ENV.TEST_RUN_ID || 'local-load-test';

export default function () {
  http.get('https://example.com/api/orders', {
    headers: {
      'X-Test-Run-Id': TEST_RUN_ID,
      'X-Scenario': 'read-orders',
    },
  });
}
```

애플리케이션은 요청이 들어오면 다음 값을 MDC에 저장한다.

- `testRunId`
- `scenario`
- `requestId`
- `method`
- `uri`

이 값들은 Logback 패턴에 포함되어 모든 애플리케이션 로그에 자동으로 출력된다.

관련 파일:

- `src/main/java/com/team6/project3th/common/logging/MdcLoggingFilter.java`
- `src/main/resources/logback-spring.xml`

## SQL 실행시간 로그

`datasource-proxy`를 사용해 Spring Boot가 사용하는 `DataSource`를 감싼다.

이를 통해 애플리케이션에서 실행되는 JDBC 쿼리를 감지하고, 쿼리 실행 후 SQL 실행시간을 로그로 남긴다.

관련 파일:

- `src/main/java/com/team6/project3th/common/config/DataSourceProxyBeanPostProcessor.java`
- `src/main/java/com/team6/project3th/common/logging/SqlLoggingListener.java`

SQL 실행 시 다음과 같은 로그가 남는다.

```text
event=db_query datasource=mysqlDataSource elapsedMs=18 success=true querySize=1 sql="select ..."
```

Logback 패턴에 MDC 값이 포함되어 있으므로 실제 로그에는 다음 값들도 함께 찍힌다.

```text
testRunId=... scenario=... requestId=... method=... uri=...
```

따라서 Loki에서 특정 부하테스트 실행 ID나 특정 API 기준으로 SQL 로그를 찾을 수 있다.

SQL 로그 레벨은 별도로 제어할 수 있다.

```xml
<logger name="SQL_LOG" level="INFO" additivity="true"/>
```

SQL 로그를 끄고 싶으면 `level`을 `OFF`로 변경한다.

## 요청별 DB 요약 로그

SQL 실행 로그는 쿼리 단위 로그다.

하지만 부하테스트에서는 요청 1건이 DB를 얼마나 사용했는지도 중요하다.

이를 위해 요청 처리 중 실행된 DB 쿼리 수와 총 DB 실행시간을 `ThreadLocal`에 누적한다.

관련 파일:

- `src/main/java/com/team6/project3th/common/logging/DbQueryStatsContext.java`
- `src/main/java/com/team6/project3th/common/logging/MdcLoggingFilter.java`

요청이 끝나면 다음과 같은 요약 로그를 남긴다.

```text
event=http_request_db_summary status=200 httpElapsedMs=91 dbQueryCount=2 dbTotalMs=30
```

각 값의 의미는 다음과 같다.

- `httpElapsedMs`: 서버 내부에서 요청을 처리하는 데 걸린 전체 시간
- `dbQueryCount`: 해당 요청 중 실행된 JDBC 쿼리 수
- `dbTotalMs`: 해당 요청 중 JDBC 실행에 사용된 총 시간

요약 로그는 Loki에서 API별 DB 비용을 빠르게 확인할 때 유용하다.

요청별 DB 요약 로그도 별도로 레벨을 제어할 수 있다.

```xml
<logger name="HTTP_DB_SUMMARY" level="INFO" additivity="true"/>
```

## Prometheus 메트릭

DB 관련 지표는 Micrometer custom metric으로 Prometheus에 노출한다.

관련 파일:

- `src/main/java/com/team6/project3th/common/metrics/DbMetricsRecorder.java`
- `src/main/java/com/team6/project3th/common/logging/SqlLoggingListener.java`
- `src/main/java/com/team6/project3th/common/logging/MdcLoggingFilter.java`

수집하는 메트릭은 다음과 같다.

| 메트릭 | 설명 |
| --- | --- |
| `app_db_query_duration_seconds` | JDBC 쿼리 1회 실행시간 |
| `app_http_request_db_time_seconds` | HTTP 요청 1건당 DB 총 실행시간 |
| `app_http_request_db_query_count` | HTTP 요청 1건당 DB 쿼리 수 |

사용하는 label은 다음과 같다.

| Label | 설명 |
| --- | --- |
| `method` | HTTP method |
| `uri` | 요청 URI |
| `success` | 쿼리 성공 여부. `app_db_query_duration_seconds`에만 사용 |

Prometheus label에는 SQL 원문, userId, orderId, token 같은 동적 값이나 민감정보를 넣지 않는다.

이런 값은 cardinality를 급격히 늘려 Prometheus 성능 문제를 만들 수 있다.

SQL 원문은 Prometheus가 아니라 Loki 로그에서 확인한다.

## 운영 프로필 설정

운영 환경 설정은 다음 파일에 있다.

```text
src/main/resources/application-prod.yml
```

중요 설정:

```yaml
server:
  servlet:
    context-path: /api

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

운영 환경에서는 모든 API 앞에 `/api`가 붙는다.

따라서 Prometheus scrape 경로는 다음과 같다.

```text
/api/actuator/prometheus
```

## Grafana에서 사용할 PromQL 예시

API별 요청당 DB 총 시간 p95:

```promql
histogram_quantile(
  0.95,
  sum(rate(app_http_request_db_time_seconds_bucket[5m])) by (le, method, uri)
)
```

API별 요청당 평균 쿼리 수:

```promql
sum(rate(app_http_request_db_query_count_sum[5m])) by (method, uri)
/
sum(rate(app_http_request_db_query_count_count[5m])) by (method, uri)
```

API별 SQL 실행시간 p95:

```promql
histogram_quantile(
  0.95,
  sum(rate(app_db_query_duration_seconds_bucket[5m])) by (le, method, uri)
)
```

API별 SQL 실행 횟수:

```promql
sum(rate(app_db_query_duration_seconds_count[1m])) by (method, uri)
```

API 응답시간 중 DB 시간이 차지하는 비율:

```promql
(
  sum(rate(app_http_request_db_time_seconds_sum[5m])) by (method, uri)
  /
  sum(rate(app_http_request_db_time_seconds_count[5m])) by (method, uri)
)
/
(
  sum(rate(http_server_requests_seconds_sum[5m])) by (method, uri)
  /
  sum(rate(http_server_requests_seconds_count[5m])) by (method, uri)
)
* 100
```

## Loki에서 사용할 LogQL 예시

요청별 DB 요약 로그 조회:

```logql
{app="api-server"} |= "event=http_request_db_summary"
```

SQL 실행 로그 조회:

```logql
{app="api-server"} |= "event=db_query"
```

특정 k6 테스트 실행 로그 조회:

```logql
{app="api-server"} |= "testRunId=load-20260510-001"
```

특정 API 로그 조회:

```logql
{app="api-server"} |= "uri=/api/orders"
```

## 배포 후 검증 체크리스트

1. 애플리케이션을 `prod` 프로필로 배포한다.
2. DB를 사용하는 API를 호출한다.
3. k6 또는 curl 요청에 `X-Test-Run-Id` 헤더를 포함한다.
4. 애플리케이션 로그에서 `event=db_query`가 찍히는지 확인한다.
5. 애플리케이션 로그에서 `event=http_request_db_summary`가 찍히는지 확인한다.
6. `/api/actuator/prometheus`에 접속한다.
7. 다음 메트릭이 노출되는지 확인한다.

```text
app_db_query_duration_seconds_bucket
app_http_request_db_time_seconds_bucket
app_http_request_db_query_count_bucket
```

8. Prometheus target이 정상 scrape 상태인지 확인한다.
9. Grafana에서 Prometheus 메트릭과 Loki 로그가 모두 조회되는지 확인한다.

## 주의사항

- 현재 구현은 일반적인 Spring MVC 동기 요청 처리에 가장 잘 맞는다.
- `ThreadLocal` 기반 요청별 DB 집계는 `@Async`, 별도 스레드, 이벤트 리스너, WebFlux 환경에서는 자동으로 이어지지 않는다.
- SQL 원문은 Loki 로그에만 남기고 Prometheus label에는 넣지 않는다.
- Prometheus label은 cardinality가 낮은 값만 사용한다.
- 현재 `uri`는 `request.getRequestURI()` 값을 사용한다.
- `/orders/1`, `/orders/2`처럼 동적 path가 많아지면 Prometheus cardinality 문제가 생길 수 있다.
- 필요하면 Spring MVC의 best matching pattern을 사용해 `/orders/{id}` 형태로 metric label을 바꾸는 개선이 필요하다.
