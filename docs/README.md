# Project3th Infrastructure Documentation

이 디렉터리는 Project3th에서 구현한 배포, 모니터링, DB 관측성, 부하 테스트 코드를 설명한다.

## 문서 목록

| 문서 | 설명 |
| --- | --- |
| [blue-green-deployment.md](./blue-green-deployment.md) | GitHub Actions, Docker Hub, EC2, Nginx를 이용한 블루/그린 무중단 배포 구조 |
| [monitoring-stack.md](./monitoring-stack.md) | Prometheus, Grafana, Loki, Promtail 모니터링 스택 구축과 운영 방법 |
| [db-monitoring.md](./db-monitoring.md) | DB 쿼리 로그와 요청별 DB 메트릭을 수집하는 구현 내용 |
| [db-monitoring-design.md](./db-monitoring-design.md) | DB 관측성 설계 의도와 데이터 흐름 |
| [k6-monitoring-test.md](./k6-monitoring-test.md) | k6 부하 테스트 스크립트와 모니터링 검증 방법 |
| [code-map.md](./code-map.md) | 구현 파일별 역할 정리 |

## 전체 구성 요약

```text
GitHub Actions
  -> Docker image build/push
  -> deployment files copy to EC2
  -> EC2 switch-blue-green.sh
  -> blue/green container switch
  -> Nginx upstream switch

Application
  -> health check endpoint
  -> DB query logging
  -> Micrometer metrics
  -> Prometheus scrape
  -> Grafana dashboard/query

Docker logs
  -> Promtail
  -> Loki
  -> Grafana Explore
```

## 주요 포트

| 대상 | 포트 | 설명 |
| --- | --- | --- |
| Nginx entrypoint | `8080` | 외부 요청 진입 포트 |
| Blue API container | `8081 -> 8080` | blue 배포 슬롯 |
| Green API container | `8082 -> 8080` | green 배포 슬롯 |
| Prometheus | `9090` | 메트릭 수집/조회 |
| Grafana | `3000` | 대시보드와 Loki Explore |
| Loki | `3100` | 로그 저장 API |

