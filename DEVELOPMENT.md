# Trading Engine 개발 가이드

## 실행 흐름

```text
Market provider
  -> market-gateway
  -> Redis Streams / latest-value cache
  -> trading-worker
  -> strategy evaluation
  -> final order processor
  -> virtual fill / portfolio / official ledger
  -> PostgreSQL + service events
```

- 봇별 평가는 한 번에 하나씩 완료합니다.
- 새 시세 때문에 실행 중 계산을 계속 취소하지 않습니다.
- 주문 직전 결과 유효성을 확인하고 무효할 때만 폐기 후 재평가합니다.
- 모든 분기는 주문 후보만 만들며, 하나의 최종 주문 처리 단계에서 중복·충돌을 해결합니다.
- S3 Parquet은 시작 시 필요한 과거 데이터 warm-up 등에 사용하고 실시간 hot path로 사용하지 않습니다.

## 데이터 접근

- 주 변경 스키마: `trading`, `bot`의 실행 영역
- 읽기: 잠긴 `strategy`, 검증된 `market_data`
- Command 기본은 JPA, 복합 조회는 jOOQ
- DBML·Flyway는 루트 저장소가 통합 관리합니다.

## 테스트와 Docker

- PostgreSQL·Redis가 필요한 테스트는 Testcontainers로 실행합니다. 로컬에서 Docker가 없으면
  `disabledWithoutDocker = true`에 따라 건너뛰고 `./gradlew check`는 통과합니다.
- Docker 사용 가능 여부는 모든 `Test` 작업의 입력입니다. Docker 없이 실행한 결과는 빌드 캐시에
  저장하지 않고, Docker를 켠 뒤의 실행에서 UP-TO-DATE로 재사용하지도 않습니다.
- CI는 `./gradlew check -PrequireContainerTests=true`로 실행합니다. 이때 테스트를 하나라도
  건너뛴 `Test` 작업은 실패합니다. 로컬에서 같은 옵션으로 CI 조건을 재현할 수 있습니다.

## Git Flow

- 기본 브랜치: `develop`
- 작업 브랜치: `feature/*`, `fix/*`, `docs/*`, `chore/*`
- 정식 릴리스 준비: `release/*`
- `main`: v1.0.0부터 검증된 릴리스만
- 릴리스 이후 긴급 수정: `hotfix/*`, 이후 `develop`에도 반영

