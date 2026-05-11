# DB Monitoring Design

이 문서는 DB 관측성 기능을 어떤 의도로 설계했는지 설명한다.

## 문제 정의

일반적인 HTTP 요청 로그만으로는 다음 질문에 답하기 어렵다.

1. 특정 API가 느릴 때 DB가 원인인지 알 수 있는가?
2. 요청 하나에서 SQL이 몇 번 실행됐는가?
3. N+1처럼 쿼리 수가 비정상적으로 증가하는 API를 찾을 수 있는가?
4. 부하 테스트 중 특정 시나리오의 로그와 메트릭만 분리해서 볼 수 있는가?

이를 해결하기 위해 HTTP 요청, SQL 실행, 메트릭, 로그를 같은 식별 정보로 연결했다.

## 설계 흐름

```text
HTTP request
  -> MdcLoggingFilter
       - testRunId, scenario, requestId, method, uri 저장
  -> Controller / Service / Repository
  -> datasource-proxy
       - SQL 실행 이벤트 감지
  -> SqlLoggingListener
       - SQL 로그 기록
       - 쿼리 1회 실행 시간 메트릭 기록
       - ThreadLocal에 요청별 DB 통계 누적
  -> MdcLoggingFilter finally
       - 요청별 DB summary 로그 기록
       - 요청별 DB 총 시간/쿼리 수 메트릭 기록
       - MDC, ThreadLocal 정리
```

## MDC를 사용한 이유

로그는 여러 클래스에서 발생한다. SQL 로그는 `SqlLoggingListener`에서 찍히고, 요청 요약 로그는 `MdcLoggingFilter`에서 찍힌다.

매번 requestId, uri, testRunId를 파라미터로 넘기면 코드가 지저분해지고 계층 간 결합이 생긴다. MDC를 사용하면 같은 요청 처리 스레드 안에서 찍히는 로그에 공통 식별자를 자동으로 붙일 수 있다.

## ThreadLocal을 사용한 이유

요청별 DB 쿼리 수와 DB 총 시간은 SQL 실행 시점마다 누적되어야 하고, 요청 종료 시점에 한 번 summary로 기록되어야 한다.

Spring MVC는 일반적으로 요청 하나를 하나의 스레드에서 처리하므로, ThreadLocal을 사용하면 현재 요청에 속한 DB 통계를 간단하게 저장할 수 있다.

주의할 점은 반드시 요청 종료 후 `clear()`를 호출해야 한다는 것이다.

```java
DbQueryStatsContext.clear();
MDC.clear();
```

## datasource-proxy를 사용한 이유

JPA Repository 코드마다 시간을 측정하면 누락 가능성이 높고, 비즈니스 코드에 모니터링 코드가 섞인다.

`datasource-proxy`를 사용하면 DataSource 수준에서 모든 JDBC 쿼리를 감지할 수 있다. 따라서 JPA, JdbcTemplate 등 상위 기술과 무관하게 DB 쿼리 실행을 공통으로 관측할 수 있다.

## 메트릭과 로그를 둘 다 남기는 이유

메트릭은 추세와 이상 징후를 보기 좋다.

예를 들어 API별 p95 DB 시간이 증가하거나 요청당 쿼리 수가 늘어나는 상황을 Grafana 대시보드로 확인할 수 있다.

로그는 원인을 추적하기 좋다.

특정 `testRunId`, `requestId`, `uri`로 SQL 로그를 좁히면 어떤 SQL이 실제로 실행됐는지 확인할 수 있다.

즉, 메트릭은 "어디가 이상한가"를 찾고, 로그는 "왜 이상한가"를 추적하는 용도다.

## testRunId와 scenario

k6 부하 테스트나 수동 검증 요청에서 다음 헤더를 넣는다.

```text
X-Test-Run-Id: monitoring-1
X-Scenario: monitoring-jpa-test
```

애플리케이션은 이 값을 MDC에 저장하고 모든 로그에 포함한다.

이렇게 하면 Grafana Loki에서 특정 테스트 실행만 필터링할 수 있다.

```logql
{app="api-server"} |= "testRunId=monitoring-1"
```

## 성공 기준

DB 관측성 구현이 정상이라면 다음이 가능해야 한다.

1. `/api/actuator/prometheus`에서 `app_db_query_duration_seconds` 메트릭을 볼 수 있다.
2. `/api/actuator/prometheus`에서 `app_http_request_db_time_seconds` 메트릭을 볼 수 있다.
3. `/api/actuator/prometheus`에서 `app_http_request_db_query_count` 메트릭을 볼 수 있다.
4. Docker logs 또는 Loki에서 `event=db_query` 로그를 볼 수 있다.
5. Docker logs 또는 Loki에서 `event=http_request_db_summary` 로그를 볼 수 있다.
6. `X-Test-Run-Id` 헤더를 보낸 요청은 같은 `testRunId`로 필터링할 수 있다.

