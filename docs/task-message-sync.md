# 질문·답변 도착 정보 동기화

## API 계약

`GET /api/v1/agents/me/task-messages?cursor={nextCursor}&limit=50`

- Agent Bearer 토큰이 필요하며 사용자 로그인 세션으로 대체할 수 없다.
- `limit`은 기본 50, 허용 범위 1~100이다. cursor 생략 시 처음부터 조회한다.
- 본문은 없으며 성공은 항상 200이다. 결과가 없어도 유효한 `nextCursor`를 반환한다.
- 오류는 공통 응답의 400 `VALIDATION_FAILED`, 401 `AUTHENTICATION_REQUIRED`,
  403 `ACCESS_DENIED`, 405 `METHOD_NOT_ALLOWED`, 500 `INTERNAL_SERVER_ERROR`를 사용한다.
- `items`에는 `eventId`(원본 TaskEvent.id의 문자열), `taskId`, `sequence`(문자열),
  `eventType`(QUESTION/ANSWER), `createdAt`(UTC)만 포함한다.
- 별도 수신함과 `notificationId`는 없다. 작성자·본문·질문 참조는 공통 상세 조회 담당 범위다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "eventId": "502",
        "taskId": 100,
        "sequence": "13",
        "eventType": "ANSWER",
        "createdAt": "2026-11-03T05:30:00Z"
      }
    ],
    "nextCursor": "서버가 발급한 불투명한 문자열",
    "hasNext": false
  },
  "code": null,
  "message": null
}
```

## 수신 대상과 부작용

현재 Agent의 팀에 속한 Task에 참여하고, 작성자가 그 Task의 상대 참여자인 경우에만 반환한다.
본인 작성, 자기 배정 Task의 자기 질문, 비참여 Agent 작성, 다른 팀 이벤트는 제외한다.
동일한 질문의 여러 답변은 각각 별도 이벤트로 반환한다.

조회는 `task_events`와 `tasks`만 사용한다. outbox 레코드가 없거나 FAILED/PUBLISHED여도
원본은 복구할 수 있다. Task가 종료된 뒤에도 현재 조회 권한이 유지되면 기존 이벤트는 반환한다.
조회가 Task 상태·version·시작/종료 시각·이벤트·outbox를 변경하지 않는다.

## 늦은 커밋을 빠뜨리지 않는 cursor

마지막 eventId만 사용하지 않는다. 각 이벤트의 `sync_xid`에 PostgreSQL 최상위 삽입
트랜잭션 ID를 보관하고, cursor에는 이전 완료 스냅샷과 현재 페이지 창의 스냅샷을 담는다.
이는 저장 순서와 커밋 순서가 달라도 누락을 막기 위한 내부 정보이며 API 필드로 노출하지 않는다.

1. 새 조회 창을 시작할 때 `pg_current_snapshot()`을 한 번 확보한다.
2. 현재 창에서 보이고 이전 완료 창에서는 보이지 않았던 이벤트만 조회한다.
3. 같은 창의 모든 페이지는 고정된 대상 집합을 사용한다. 페이지 위치는 `(taskId, sequence)`다.
4. 마지막 페이지에서 현재 창을 완료 위치로 넘긴다. 당시 미커밋 트랜잭션은 스냅샷에 남아 있다.
5. 다음 창은 이전에 미커밋이었다가 나중에 커밋된 이벤트도 다시 찾는다. 그 eventId가 작아도 제외하지 않는다.

예: 이벤트 501을 저장한 트랜잭션이 진행 중이고 502가 먼저 커밋되면 첫 창은 502를 반환한다.
501의 트랜잭션이 나중에 커밋되면 다음 창에서 501을 반환한다. 전체 Task 간 생성 시각 순서는
보장하지 않으며, 같은 Task의 이벤트 순서는 `sequence`로 유지한다.

하나의 HTTP 요청은 REPEATABLE READ 읽기 전용 트랜잭션으로 처리한다. 여러 페이지를 받는 동안
새로 커밋된 이벤트는 다음 조회 창에 포함된다. 이는 실제 DB 스냅샷을 세션 밖에 유지하는 방식이
아니므로 브로커가 오랫동안 연결을 끊어도 열린 DB 트랜잭션을 남기지 않는다.

cursor는 HMAC-SHA256으로 서명하고 Agent·사용자·팀에 바인딩한다. 다른 Agent의 정상 cursor는
403, 변조·손상된 cursor는 400이다. 브로커별 cursor 컬럼이나 도착 목록은 중앙에 저장하지 않는다.
`task_message_sync_settings`는 재시작·다중 인스턴스에서 공유하는 서명키 한 건만 저장한다.
운영 중 키를 임의 변경하지 않는다. DB를 복제해 테스트할 때도 이 키를 외부에 공개하지 않는다.

## POST 담당자와의 저장 계약

현재 브랜치에 작성자·공통 순서 필드가 없으므로 V7에서 다음 최소 필드를 추가한다.

| 필드 | 저장 주체 | 설명 |
| --- | --- | --- |
| `author_agent_id` | POST | 인증된 작성 Agent ID. QUESTION/ANSWER는 필수 |
| `sequence` | DB 트리거 | Task 행 잠금 아래 Task별 다음 순번 자동 부여 |
| `sync_xid` | DB 트리거 | `pg_current_xact_id()`로 실제 삽입 트랜잭션 ID 자동 부여 |

`TaskEvent.message(taskId, type, authorAgentId, content)`는 검증 후 저장할 때 쓰는 팩터리다.
이 팩터리는 POST의 참여자·Task 상태·requestKey·질문 참조 검증을 대신하지 않는다.
자기 질문 답변 거부, 질문당 복수 답변, outbox 수신자 지정과 원자적 저장도 POST 담당 범위다.
질문 참조 컬럼과 requestKey의 저장 구조는 이번 GET 구현에서 추가하지 않는다.

DB 트리거가 Task 행을 잠그므로 같은 Task의 등록 순번은 커밋 순서와 일치하고,
서로 다른 Task 사이에는 전역 쓰기 잠금을 걸지 않는다. 기존 비메시지 이벤트에도 순번이 부여된다.
잠금은 FOR NO KEY UPDATE를 사용해 기존 파일 저장의 외래키 KEY SHARE 잠금과 충돌하지 않게 한다.
JPA의 `sequence`는 읽기 전용이므로 INSERT 직후 값을 응답해야 하는 POST는 flush 후 refresh하거나
SQL RETURNING을 사용한다. 공통 상세 조회는 이 `sequence` 기준으로 정렬하도록 담당자와 맞춘다.
기존 이벤트 작성자는 추정해 채우지 않으며, 과거 이력의 sequence는 `(created_at, id)`로 초기화한다.

## 브로커 연동 계약

- 접속·재접속, WebSocket 알림 수신, 느슨한 안전망 주기마다 같은 GET API를 호출한다.
- 안전망 초기 제안은 60초 ±10초이며 브로커 설정값이다. 이번 서버 변경은 타이머를 실행하지 않는다.
- 도착 정보와 nextCursor를 한 로컬 트랜잭션으로 저장한다. 실패하면 이전 cursor로 재시도한다.
- eventId로 중복 저장을 제거한다. hasNext가 true이면 즉시 다음 페이지를 조회한다.
- Pull 실행 중 발생한 신호는 모아서 현재 조회 완료 후 추가 조회한다.
- 로컬 보관과 Agent 읽음·답변·작업 재개는 별도 상태다. 원본 본문은 get_task_result로 확인한다.

이번 변경은 GET 동기화 API와 저장 메타데이터만 제공한다. 질문·답변 POST, Q&A용 outbox 저장·발행,
WebSocket 알림 형식 적용 및 로컬 브로커의 저장·타이머·MCP 연결은 별도 연동 작업이다.

## 검증

`TaskMessageSyncIntegrationTest`는 실제 PostgreSQL과 HTTP 요청으로 다음을 확인한다.

- Agent/팀/참여자/작성자 범위, 본인·자기 배정·비참여 이벤트 제외
- 복수 답변과 종료 Task 복구, outbox 없음·PENDING·PUBLISHED·FAILED 독립성
- 기본·분할·재조회·빈 결과와 같은 시각 이벤트의 고정된 순서
- 큰 ID 선커밋/작은 ID 후커밋, 페이지 중 후커밋, 빈 조회 뒤 커밋, 롤백
- 잘못된 입력·인증·팀 탈퇴·다른 Agent cursor·변조·405 처리
- GET 호출 전후 Task·이벤트·outbox 미변경 및 새 codec 인스턴스의 기존 cursor 검증

```powershell
.\gradlew.bat test --tests com.devlink.server.TaskMessageSyncIntegrationTest
```

PostgreSQL 스냅샷 함수 참고: https://www.postgresql.org/docs/16/functions-info.html#FUNCTIONS-PG-SNAPSHOT

### 최종 실행 결과 (2026-11-03)

- 전체 35개 테스트 클래스, 598개 테스트 통과. 실패·오류·건너뜀 0건.
- 그중 신규 동기화 통합 테스트 23개 통과. V6 기존 데이터의 V7 이관도 포함한다.
- Windows의 `Bad address: listen` 오류를 피해 테스트 JVM에만
  `-Djava.net.preferIPv4Stack=true`를 적용해 전체 테스트를 실행했다. 운영 설정은 변경하지 않았다.
- 기존 작업 완료의 동시 요청 검증에서 FK 잠금 충돌을 발견해 순번 부여 잠금을
  FOR NO KEY UPDATE로 수정한 뒤 전체 회귀 검증을 통과했다.
- `git diff --check` 통과. Git 커밋은 생성하지 않았다.
