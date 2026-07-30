# Idea2Strategy Trading Engine

서버에서 실시간 시장 데이터를 받아 잠긴 전략을 평가하고 가상 주문·체결·포지션·공식 원장을 처리하는 Java/Spring Boot 저장소입니다.

## 책임

- `market-gateway`: 시장 데이터 공급자 연결, 정규화·검증, Redis Streams와 최신값 캐시 발행
- `trading-worker`: 봇별 순차 평가, 후보 통합, 예산·위험 한도 검사, 가상 주문과 체결, 포지션·원장 기록
- 서버에 저장된 잠긴 전략 버전만 실행
- 브라우저나 사용자 기기 상태와 무관한 지속 실행

예정 구조는 Gradle 멀티프로젝트입니다.

```text
apps/
  market-gateway/
  trading-worker/
modules/
  trading-domain/
  trading-application/
  strategy-runtime/
  market-data-adapter/
  trading-persistence/
  trading-messaging/
  trading-common/
```

구현 전에는 루트 조정 저장소의 제품 규칙·계약·DBML과 [DEVELOPMENT.md](DEVELOPMENT.md)를 먼저 확인합니다.

