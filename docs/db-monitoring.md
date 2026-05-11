# DB Monitoring

이 문서는 DB 정보를 모니터링하기 위해 애플리케이션에 구현한 로그, 메트릭, 요청 추적 코드를 설명한다.

## 목표

API 요청 단위로 다음 정보를 확인할 수 있게 한다.

| 정보 | 용도 |
| --- | --- |
| SQL 실행 로그 | 어떤 SQL이 실행됐는지 확인 |
| 쿼리 1회 실행 시간 | 느린 쿼리 탐지 |
| 요청당 DB 총 시간 | API 응답 지연 중 DB 영향도 확인 |
| 요청당 DB 쿼리 수 | N+1 문제나 과도한 쿼리 탐지 |
| testRunId/scenario/requestId | 부하 테스트와 운영 로그 추적 |

## 관련 파일

| 파일 | 역할 |
| --- | --- |
| `DataSourceProxyBeanPostProcessor.java` | Spring DataSource를 datasource-proxy로 감싸 SQL 실행 감지 |
| `SqlLoggingListener.java` | SQL 실행 후 로그와 쿼리 단위 메트릭 기록 |
| `DbQueryStatsContext.java` | 요청 단위 DB 쿼리 수와 총 DB 시간 누적 |
| `MdcLoggingFilter.java` | 요청별 MDC 설정, 요청 종료 시 DB summary 기록 |
| `DbMetricsRecorder.java` | Micrometer 메트릭 기록 |
| `logback-spring.xml` | MDC 값을 포함한 로그 패턴 정의 |
| `build.gradle` | Actuator, Prometheus registry, datasource-proxy 의존성 |

## 의존성

DB 모니터링은 다음 라이브러리를 사용한다.

```gradle
implementation "org.springframework.boot:spring-boot-starter-actuator"
runtimeOnly "io.micrometer:micrometer-registry-prometheus"
implementation 'net.ttddyy:datasource-proxy:1.11.0'
```

| 라이브러리 | 역할 |
| --- | --- |
| Spring Boot Actuator | `/actuator/prometheus` 계열 endpoint 제공 |
| Micrometer Prometheus registry | Micrometer 메트릭을 Prometheus 형식으로 노출 |
| datasource-proxy | JDBC DataSource를 감싸 SQL 실행 이벤트 감지 |

## 요청 추적: MDC

`MdcLoggingFilter`는 모든 HTTP 요청마다 다음 값을 MDC에 저장한다.

| MDC key | 값 |
| --- | --- |
| `testRunId` | 요청 헤더 `X-Test-Run-Id`, 없으면 `-` |
| `scenario` | 요청 헤더 `X-Scenario`, 없으면 `-` |
| `requestId` | 요청 헤더 `X-Request-Id`, 없으면 UUID 생성 |
| `method` | HTTP method |
| `uri` | request URI |

이 값들은 `logback-spring.xml`의 콘솔 로그 패턴에 포함된다.

```xml
testRunId=%X{testRunId:-}
scenario=%X{scenario:-}
requestId=%X{requestId:-}
method=%X{method:-}
uri=%X{uri:-}
```

그래서 애플리케이션 로그, SQL 로그, 요청 요약 로그가 같은 `requestId`와 `testRunId`로 연결된다.

## SQL 실행 감지

`DataSourceProxyBeanPostProcessor`는 Spring이 초기화한 `dataSource` bean을 프록시로 감싼다.

```java
return ProxyDataSourceBuilder
        .create(dataSource)
        .name("mysqlDataSource")
        .listener(sqlLoggingListenerProvider.getObject())
        .countQuery()
        .build();
```

이후 JPA나 JDBC가 DB 쿼리를 실행하면 `SqlLoggingListener.afterQuery()`가 호출된다.

## SQL 로그

`SqlLoggingListener`는 쿼리 실행 후 다음 로그를 남긴다.

```text
event=db_query datasource=... elapsedMs=... success=... querySize=... sql="..."
```

로거 이름은 `SQL_LOG`다.

```java
private static final Logger log = LoggerFactory.getLogger("SQL_LOG");
```

`normalizeSql()`은 SQL의 줄바꿈과 여러 공백을 하나의 공백으로 정리해 로그를 한 줄로 만든다.

## 요청별 DB summary 로그

`MdcLoggingFilter`는 요청이 끝날 때 `DbQueryStatsContext`에서 누적 값을 꺼내 summary 로그를 남긴다.

```text
event=http_request_db_summary status=200 httpElapsedMs=91 dbQueryCount=2 dbTotalMs=30
```

로거 이름은 `HTTP_DB_SUMMARY`다.

이 로그는 API 요청 하나의 전체 처리 시간, DB 쿼리 수, DB 총 실행 시간을 함께 보여준다.

## ThreadLocal 통계 저장

`DbQueryStatsContext`는 `ThreadLocal`에 요청 단위 DB 통계를 저장한다.

```java
private static final ThreadLocal<DbQueryStats> CONTEXT =
        ThreadLocal.withInitial(DbQueryStats::new);
```

쿼리 하나가 끝날 때마다 다음 값이 증가한다.

```java
stats.queryCount++;
stats.totalElapsedMs += elapsedMs;
```

요청 종료 후에는 반드시 정리한다.

```java
DbQueryStatsContext.clear();
MDC.clear();
```

Spring MVC 요청 처리 스레드는 재사용되므로, 정리하지 않으면 다음 요청 로그에 이전 요청의 MDC나 DB 통계가 섞일 수 있다.

## Micrometer 메트릭

`DbMetricsRecorder`는 세 가지 메트릭을 기록한다.

| 메트릭 이름 | Prometheus 노출 예 | 설명 |
| --- | --- | --- |
| `app_db_query_duration` | `app_db_query_duration_seconds` | SQL 쿼리 1회 실행 시간 |
| `app_http_request_db_time` | `app_http_request_db_time_seconds` | HTTP 요청 1건당 DB 총 시간 |
| `app_http_request_db_query_count` | `app_http_request_db_query_count` | HTTP 요청 1건당 DB 쿼리 수 |

쿼리 1회 실행 시간에는 `success` 태그가 포함된다.

```java
.tag("success", String.valueOf(success))
```

공통 태그:

| 태그 | 설명 |
| --- | --- |
| `method` | HTTP method |
| `uri` | request URI |
| `success` | 쿼리 성공 여부, `app_db_query_duration`에만 사용 |

## Prometheus 쿼리 예시

API별 요청당 DB 시간 p95:

```promql
histogram_quantile(
  0.95,
  sum(rate(app_http_request_db_time_seconds_bucket[5m])) by (le, method, uri)
)
```

API별 요청당 평균 DB 쿼리 수:

```promql
sum(rate(app_http_request_db_query_count_sum[5m])) by (method, uri)
/
sum(rate(app_http_request_db_query_count_count[5m])) by (method, uri)
```

쿼리 1회 실행 시간 p95:

```promql
histogram_quantile(
  0.95,
  sum(rate(app_db_query_duration_seconds_bucket[5m])) by (le, method, uri)
)
```

API별 쿼리 실행 횟수:

```promql
sum(rate(app_db_query_duration_seconds_count[1m])) by (method, uri)
```

## Loki 쿼리 예시

요청별 DB summary:

```logql
{app="api-server"} |= "event=http_request_db_summary"
```

SQL 로그:

```logql
{app="api-server"} |= "event=db_query"
```

특정 테스트 실행:

```logql
{app="api-server"} |= "testRunId=monitoring-1"
```

특정 API:

```logql
{app="api-server"} |= "uri=/api/monitoring/tests"
```

## 테스트 요청

`X-Test-Run-Id`를 넣으면 로그에서 해당 테스트 실행을 추적할 수 있다.

```bash
curl -H "X-Test-Run-Id: monitoring-1" \
     -H "X-Scenario: manual-check" \
     http://localhost:8080/api/health
```

헤더가 없으면 `testRunId=-`로 기록된다.

