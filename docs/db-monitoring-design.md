# DB 모니터링 구현 구조 공유 문서

## 1. 왜 이 기능을 구현했는가

k6 부하테스트를 하면 기본적으로 확인할 수 있는 것은 API를 외부에서 호출했을 때의 응답시간이다.

하지만 API 응답시간만 보면 병목이 어디에서 발생했는지 알기 어렵다.

예를 들어 `GET /orders` 응답이 800ms까지 증가했을 때, 원인은 여러 가지일 수 있다.

- 애플리케이션 CPU가 부족한 경우
- JVM GC가 자주 발생하는 경우
- DB 쿼리가 느린 경우
- 요청 1건당 쿼리가 너무 많이 나가는 경우
- DB 커넥션 풀이 부족한 경우

그래서 이번 구현의 목적은 API 부하테스트 중 다음 정보를 함께 수집하는 것이다.

- 특정 k6 테스트 실행에서 발생한 로그인지 구분
- API 요청 1건당 DB 쿼리 수 확인
- API 요청 1건당 DB 총 실행시간 확인
- SQL 쿼리 1회 실행시간 확인
- Prometheus/Grafana에서 API별 DB 지표 확인
- Loki에서 실제 SQL 로그 추적

정리하면, k6는 외부 사용자 관점의 성능을 보고, 애플리케이션 내부에서는 DB 관련 지표를 수집해서 둘을 연결하는 구조다.

## 2. 전체 구조

전체 흐름은 다음과 같다.

```text
k6
  |
  | X-Test-Run-Id, X-Scenario 헤더 포함
  v
Spring Boot API
  |
  | MdcLoggingFilter
  | - testRunId, scenario, requestId, method, uri를 MDC에 저장
  |
  v
Controller / Service / Repository
  |
  | datasource-proxy
  | - JDBC 쿼리 실행 감지
  | - SQL 실행시간 측정
  |
  v
SqlLoggingListener
  |
  | - SQL 실행 로그 출력
  | - 요청별 queryCount/dbTotalMs 누적
  | - query duration metric 기록
  |
  v
MdcLoggingFilter finally
  |
  | - 요청 종료 시 DB summary 로그 출력
  | - 요청별 DB metric 기록
  | - ThreadLocal/MDC 정리
  |
  +--> Loki: 로그 저장
  |
  +--> Prometheus: /api/actuator/prometheus scrape
  |
  v
Grafana
```

각 도구의 역할은 다음과 같다.

| 구성 요소 | 역할 |
| --- | --- |
| k6 | 부하 생성, 테스트 실행 ID 전달 |
| MDC | 로그 한 줄마다 요청 식별 정보 자동 포함 |
| datasource-proxy | JDBC 쿼리 실행 감지 |
| Loki | SQL 원문과 요청 summary 로그 저장 |
| Prometheus | DB 시간, 쿼리 수 같은 수치 지표 저장 |
| Grafana | API 성능, DB 영향도, 로그를 시각화 |

## 3. k6 헤더를 사용하는 이유

부하테스트는 여러 번 실행된다.

단순히 로그에 `GET /orders`만 남기면 이 로그가 어떤 테스트에서 발생했는지 구분하기 어렵다.

그래서 k6에서 모든 요청에 다음 헤더를 넣는다.

```http
X-Test-Run-Id: load-20260510-001
X-Scenario: read-orders
```

애플리케이션은 이 값을 MDC에 저장한다.

그 결과 애플리케이션 로그, SQL 로그, 요청별 DB summary 로그가 모두 같은 `testRunId`를 갖게 된다.

Loki에서는 다음처럼 특정 테스트 실행 로그만 볼 수 있다.

```logql
{app="api-server"} |= "testRunId=load-20260510-001"
```

## 4. MdcLoggingFilter

파일:

```text
src/main/java/com/team6/project3th/common/logging/MdcLoggingFilter.java
```

역할:

- HTTP 요청이 들어올 때 가장 앞단에서 실행된다.
- 요청 헤더에서 `X-Test-Run-Id`, `X-Scenario`, `X-Request-Id`를 읽는다.
- `method`, `uri`와 함께 MDC에 저장한다.
- 요청이 끝나면 DB summary 로그를 남긴다.
- 요청별 DB metric을 Micrometer에 기록한다.
- 마지막에 MDC와 ThreadLocal을 정리한다.

핵심 흐름:

```java
MDC.put("testRunId", testRunId);
MDC.put("scenario", scenario);
MDC.put("requestId", requestId);
MDC.put("method", request.getMethod());
MDC.put("uri", request.getRequestURI());
```

이렇게 해두면 이후 같은 요청 처리 중 찍히는 로그에는 별도 코드를 작성하지 않아도 MDC 값이 자동으로 붙는다.

요청 종료 시에는 다음 작업을 한다.

```java
DbQueryStatsContext.DbQueryStats stats = DbQueryStatsContext.snapshot();

dbMetricsRecorder.recordRequestDbTime(method, uri, stats.getTotalElapsedMs());
dbMetricsRecorder.recordRequestDbQueryCount(method, uri, stats.getQueryCount());

log.info(
        "event=http_request_db_summary status={} httpElapsedMs={} dbQueryCount={} dbTotalMs={}",
        response.getStatus(),
        httpElapsedMs,
        stats.getQueryCount(),
        stats.getTotalElapsedMs()
);
```

이 코드로 요청 1건당 DB 사용량을 요약해서 남긴다.

주의할 점:

```java
DbQueryStatsContext.clear();
MDC.clear();
```

이 정리 작업은 반드시 필요하다.

Spring MVC 요청 처리 스레드는 재사용되기 때문에 MDC나 ThreadLocal을 정리하지 않으면 다음 요청 로그에 이전 요청 정보가 섞일 수 있다.

## 5. logback-spring.xml

파일:

```text
src/main/resources/logback-spring.xml
```

역할:

- 콘솔 로그 패턴에 MDC 값을 포함한다.
- SQL 로그와 요청별 DB summary 로그의 logger level을 따로 제어한다.

핵심 패턴:

```xml
testRunId=%X{testRunId:-}
scenario=%X{scenario:-}
requestId=%X{requestId:-}
method=%X{method:-}
uri=%X{uri:-}
```

이 패턴 때문에 일반 애플리케이션 로그, SQL 로그, summary 로그 모두에 요청 식별 정보가 붙는다.

SQL 로그와 summary 로그는 별도 logger 이름을 사용한다.

```xml
<logger name="SQL_LOG" level="INFO" additivity="true"/>
<logger name="HTTP_DB_SUMMARY" level="INFO" additivity="true"/>
```

이렇게 분리한 이유는 운영 중 필요에 따라 SQL 로그만 끄거나, summary 로그만 유지할 수 있게 하기 위해서다.

예를 들어 SQL 원문 로그가 너무 많으면 다음처럼 끌 수 있다.

```xml
<logger name="SQL_LOG" level="OFF" additivity="true"/>
```

## 6. DataSourceProxyBeanPostProcessor

파일:

```text
src/main/java/com/team6/project3th/common/config/DataSourceProxyBeanPostProcessor.java
```

역할:

- Spring Boot가 자동으로 생성한 `DataSource`를 찾아서 `datasource-proxy`로 감싼다.
- 기존 MySQL/HikariCP 설정은 유지하면서 JDBC 실행만 가로챈다.

핵심 코드:

```java
return ProxyDataSourceBuilder
        .create(dataSource)
        .name("mysqlDataSource")
        .listener(sqlLoggingListener)
        .countQuery()
        .build();
```

이 방식을 사용한 이유:

- `DataSource`를 직접 새로 만들면 HikariCP나 Spring Boot 자동 설정이 누락될 수 있다.
- 이미 만들어진 `dataSource` bean을 감싸면 기존 설정을 유지할 수 있다.
- DB 연결 설정은 `application-prod.yml`에 두고, 모니터링 코드는 JDBC 호출만 관찰한다.

## 7. SqlLoggingListener

파일:

```text
src/main/java/com/team6/project3th/common/logging/SqlLoggingListener.java
```

역할:

- JDBC 쿼리가 실행된 후 호출된다.
- 쿼리 실행시간을 가져온다.
- 요청별 DB 통계에 실행시간을 누적한다.
- SQL 실행 로그를 남긴다.
- Prometheus용 query duration metric을 기록한다.

핵심 흐름:

```java
long elapsedMs = executionInfo.getElapsedTime();

DbQueryStatsContext.addQuery(elapsedMs);

dbMetricsRecorder.recordQueryDuration(
        method,
        uri,
        elapsedMs,
        executionInfo.isSuccess()
);
```

이 코드로 쿼리 1회 실행시간을 요청별 집계와 Prometheus metric에 동시에 반영한다.

SQL 로그는 다음 형태로 남는다.

```text
event=db_query datasource=mysqlDataSource elapsedMs=18 success=true querySize=1 sql="select ..."
```

SQL 원문을 Prometheus label로 넣지 않고 로그로만 남긴 이유:

- SQL 원문은 값의 종류가 많고 길다.
- Prometheus label에 넣으면 cardinality가 커져 성능 문제가 생길 수 있다.
- SQL 원문은 Loki에서 검색하는 것이 더 적합하다.

## 8. DbQueryStatsContext

파일:

```text
src/main/java/com/team6/project3th/common/logging/DbQueryStatsContext.java
```

역할:

- 요청 1건 동안 실행된 DB 쿼리 수와 DB 총 시간을 저장한다.
- `SqlLoggingListener`에서 쿼리 실행마다 값을 누적한다.
- `MdcLoggingFilter`에서 요청 종료 시 snapshot을 읽어 summary 로그와 metric에 사용한다.

핵심 구조:

```java
private static final ThreadLocal<DbQueryStats> CONTEXT =
        ThreadLocal.withInitial(DbQueryStats::new);
```

`ThreadLocal`을 사용한 이유:

- Spring MVC의 일반적인 동기 요청은 하나의 요청이 같은 요청 처리 스레드에서 진행된다.
- 요청별 상태를 메서드 파라미터로 계속 넘기지 않아도 된다.
- SQL listener와 HTTP filter가 같은 요청 스레드에서 같은 통계 값을 공유할 수 있다.

주의할 점:

- `@Async`, 별도 스레드, 이벤트 리스너, WebFlux에서는 ThreadLocal 값이 자동으로 전파되지 않는다.
- 이 프로젝트는 `spring-boot-starter-webmvc` 기반이므로 일반 동기 요청 기준으로는 적합하다.

## 9. DbMetricsRecorder

파일:

```text
src/main/java/com/team6/project3th/common/metrics/DbMetricsRecorder.java
```

역할:

- Micrometer `MeterRegistry`를 사용해 Prometheus로 노출할 custom metric을 기록한다.
- SQL 실행시간, 요청당 DB 총 시간, 요청당 쿼리 수를 기록한다.

기록하는 메트릭:

| 메트릭 | 설명 |
| --- | --- |
| `app_db_query_duration_seconds` | SQL 쿼리 1회 실행시간 |
| `app_http_request_db_time_seconds` | 요청 1건당 DB 총 실행시간 |
| `app_http_request_db_query_count` | 요청 1건당 DB 쿼리 수 |

각 메트릭은 Grafana에서 API별 DB 영향도를 보는 데 사용된다.

예를 들어 `app_http_request_db_time_seconds`를 보면 API 요청 1건이 DB에서 얼마나 시간을 쓰는지 알 수 있다.

`app_http_request_db_query_count`를 보면 N+1 문제처럼 요청당 쿼리 수가 비정상적으로 많은 API를 찾을 수 있다.

## 10. application-prod.yml

파일:

```text
src/main/resources/application-prod.yml
```

역할:

- 운영 환경에서 MySQL 연결 정보를 설정한다.
- 운영 환경에서 모든 API 앞에 `/api` context path를 붙인다.
- Prometheus가 Actuator metric을 scrape할 수 있게 endpoint를 노출한다.

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

운영 환경에서는 Prometheus scrape 경로가 다음과 같다.

```text
/api/actuator/prometheus
```

## 11. Prometheus와 Loki를 나눈 이유

Prometheus와 Loki는 역할이 다르다.

Prometheus에는 숫자로 집계할 수 있는 낮은 cardinality 지표를 보낸다.

예:

- API별 DB 시간 p95
- API별 요청당 쿼리 수
- SQL 실행시간 p95
- 쿼리 실행 횟수

Loki에는 상세 로그를 보낸다.

예:

- SQL 원문
- 특정 testRunId의 SQL 로그
- 요청별 DB summary 로그
- 특정 API에서 발생한 SQL 로그

즉, Prometheus는 추세와 대시보드용이고, Loki는 원인 추적과 상세 분석용이다.

## 12. Grafana에서 기대하는 분석 흐름

부하테스트 중 API latency가 증가하면 다음 순서로 본다.

1. k6 p95/p99 latency가 증가했는지 확인한다.
2. Spring `http_server_requests_seconds`에서 같은 API의 서버 처리 시간이 증가했는지 확인한다.
3. `app_http_request_db_time_seconds`로 요청당 DB 시간이 증가했는지 확인한다.
4. `app_http_request_db_query_count`로 요청당 쿼리 수가 증가했는지 확인한다.
5. `app_db_query_duration_seconds`로 쿼리 1회 실행시간이 증가했는지 확인한다.
6. HikariCP pending connection이 증가했는지 확인한다.
7. Loki에서 해당 `testRunId`와 `uri`로 SQL 로그를 조회한다.

이 흐름을 통해 단순히 “API가 느리다”가 아니라 다음처럼 구체적으로 판단할 수 있다.

- 쿼리 수가 많아서 느린지
- 쿼리 하나가 느려서 느린지
- DB 커넥션 풀이 부족한지
- DB가 아니라 JVM/CPU/GC 문제인지

## 13. 현재 구현의 한계와 개선 포인트

현재 `uri`는 `request.getRequestURI()` 값을 사용한다.

따라서 다음과 같은 요청은 Prometheus label에서 서로 다른 URI로 기록될 수 있다.

```text
/api/orders/1
/api/orders/2
/api/orders/3
```

동적 path가 많아지면 Prometheus cardinality가 커질 수 있다.

향후 개선한다면 Spring MVC의 best matching pattern을 사용해 다음처럼 기록하는 것이 좋다.

```text
/api/orders/{id}
```

또한 현재 요청별 DB 집계는 `ThreadLocal` 기반이다.

따라서 비동기 처리나 별도 스레드에서 실행되는 DB 쿼리는 같은 요청 통계에 자동으로 포함되지 않을 수 있다.

현재 구조는 Spring MVC 동기 요청을 기준으로 설계되어 있다.
