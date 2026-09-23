# 학습 기록 — 단계별 재분해 (legacy)

이 디렉터리의 문서 11편은 **이 시스템을 교육용으로 다시 쪼개 설명한 기록**이다.
2026-09-23 에 `docs/` 루트에서 이리로 옮겼다.

## 정본이 아니다

이 저장소의 정본은 `docs/00-inventory.md` ~ `docs/04-results.md` 와 `docs/adr/` 다.
**둘이 어긋나면 정본과 코드가 맞다.** 이 문서들은 쓰인 시점의 설명으로 두고 고치지 않는다 —
언제 무엇을 이해하고 있었는지가 그대로 남는 편이 낫다.

## 그런데 왜 지우지 않았나

세 가지 이유가 있다.

1. **정본이 이 문서들을 인용한다.** `docs/00-inventory.md` 의 히스토리 표,
   `docs/adr/ADR-006`·`ADR-007` 이 여기를 근거로 든다.
2. **step0~step6 은 새 기능이 아니다.** 이미 완성돼 있던 코드를 교육용으로 다시 쪼갠
   리베이스 체인이고, 커밋이 전부 2026-08-30 하루에 찍혔다. 그 사실 자체가 히스토리를
   읽을 때 필요한 정보다(`docs/00-inventory.md` 부록 C).
3. **step7~step11 은 실제 구현 단계였다.** 관측 계측(step7), 운영 기반(step8),
   인증·개인화(step9), 외부 호출 안전화(step11)는 그때 실제로 만들어진 것이고,
   그 결정의 맥락이 여기에만 있다.

## 목록

| 문서 | 내용 |
|---|---|
| `step0-naive.md` | 방어 없는 순수 비즈니스 로직 |
| `step1-validation.md` | 입력 검증과 예외 계층 |
| `step2-atomic-balance.md` | 원자적 잔액 갱신과 원장 |
| `step3-idempotency.md` | 멱등키로 중복 요청 막기 |
| `step4-state-machine.md` | 시도 번호 기반 상태 전이 CAS |
| `step5-recovery.md` | heartbeat 회수와 재시도·최종 환불 |
| `step6-resilience.md` | 인프라 장애 내성과 감사 |
| `step7-observability.md` | 관측 — 대사 지표·방어 카운터·상태 스냅샷·장애 주입 |
| `step8-ops.md` | 운영 기반 — Flyway·Docker·CI |
| `step9-auth.md` | 조직에서 개인 사용자로, 구글 로그인과 허용 목록 |
| `step11-external.md` | 외부 호출 안전화 — 타임아웃·절대 상한·backoff·드레인·상관 ID |

step10(배포)은 2026-09-20 에 보류됐다. `docs/roadmap.md` 참조.
