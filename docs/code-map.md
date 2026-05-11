# Code Map

이 문서는 구현한 주요 파일이 어떤 역할을 하는지 빠르게 찾기 위한 코드 맵이다.

## GitHub Actions

| 파일 | 역할 |
| --- | --- |
| `.github/workflows/cicd-worflow.yml` | API 서버 CI/CD. Docker 이미지 빌드, Docker Hub push, EC2 배포 |
| `.github/workflows/k6-deploy.yml` | k6 테스트 스크립트를 별도 서버로 복사 |

## Deployment

| 파일 | 역할 |
| --- | --- |
| `deployment/switch-blue-green.sh` | EC2에서 blue/green 배포 전환 수행 |
| `deployment/docker-compose/docker-compose.blue.yml` | blue 슬롯 컨테이너 정의 |
| `deployment/docker-compose/docker-compose.green.yml` | green 슬롯 컨테이너 정의 |
| `deployment/nginx/nginx-blue.conf` | Nginx를 blue 슬롯으로 연결 |
| `deployment/nginx/nginx-green.conf` | Nginx를 green 슬롯으로 연결 |

## Monitoring Infrastructure

| 파일 | 역할 |
| --- | --- |
| `deployment/docker-compose/docker-compose.monitoring.yml` | Prometheus, Grafana, Loki, Promtail 실행 |
| `deployment/docker-compose/prometheus.yml` | blue/green API 서버 메트릭 scrape 설정 |
| `deployment/docker-compose/loki-config.yml` | Loki 저장소, retention, schema 설정 |
| `deployment/docker-compose/promtail-config.yml` | Docker 로그 파일 수집 및 Loki 전송 설정 |

## Application Common Infrastructure

| 파일 | 역할 |
| --- | --- |
| `HealthController.java` | `/api/health`, `/health` 헬스체크 endpoint |
| `DataSourceProxyBeanPostProcessor.java` | DataSource를 datasource-proxy로 감싸 SQL 실행 이벤트 감지 |
| `SqlLoggingListener.java` | SQL 실행 로그와 쿼리 실행 시간 메트릭 기록 |
| `DbQueryStatsContext.java` | 요청 단위 DB 쿼리 수와 DB 총 시간 누적 |
| `MdcLoggingFilter.java` | 요청별 MDC 설정, requestId 응답 헤더 추가, DB summary 기록 |
| `DbMetricsRecorder.java` | Micrometer Timer/DistributionSummary 메트릭 기록 |

## Monitoring Test API

| 파일 | 역할 |
| --- | --- |
| `TestController.java` | DB 모니터링 검증용 API 제공 |
| `TestService.java` | test row 생성과 count 조회 트랜잭션 처리 |
| `TestEntity.java` | `test` 테이블 JPA entity |
| `TestRepository.java` | Spring Data JPA repository |
| `TestCreateRequest.java` | POST 요청 body |
| `TestResponse.java` | 생성 결과 response |
| `TestTableInitializer.java` | 애플리케이션 시작 시 `test` 테이블 생성 |

## Resources

| 파일 | 역할 |
| --- | --- |
| `src/main/resources/application.yml` | 애플리케이션 이름과 기본 profile |
| `src/main/resources/logback-spring.xml` | MDC 기반 콘솔 로그 패턴, SQL/DB summary logger 설정 |

## k6

| 파일 | 역할 |
| --- | --- |
| `k6/monitoring-test.js` | DB 모니터링 검증용 부하 테스트 |

## Dockerfile

`Dockerfile`은 멀티 스테이지 빌드를 사용한다.

| Stage | 역할 |
| --- | --- |
| `builder` | Gradle로 bootJar 생성, layered jar 분리 |
| `runtime` | JRE 기반 실행 이미지 구성 |

런타임 이미지는 `spring` 사용자를 생성해 non-root로 애플리케이션을 실행한다.

```dockerfile
USER spring:spring
```

애플리케이션은 classpath 방식으로 실행된다.

```dockerfile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=$SERVER_PORT -cp /app:/app/lib/* com.team6.project3th.Project3thApplication"]
```

