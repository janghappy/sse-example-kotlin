# Last-Event-ID 재연결과 놓친 알림 재전송

연결이 끊겼다가 재연결할 때, 그 사이에 발행된 알림을 다시 받는 방법을 정리합니다. 코드는 `replay` 패키지(`/replay/notifications`)이며, 재전송이 없는 `basic`과 비교하면 `[replay]` 주석 부분만 추가됐습니다.

- [누가 무엇을 하나](#누가-무엇을-하나)
- [동작 흐름](#동작-흐름)
- [Last-Event-ID는 이벤트 순번이다](#last-event-id는-이벤트-순번이다)
- [최초 접속은 어디부터 받나](#최초-접속은-어디부터-받나)
- [서버는 얼마나 보관하나](#서버는-얼마나-보관하나)
- [한계](#한계)
- [실무 보완](#실무-보완)
- [WebSocket은 어떻게 하나](#websocket은-어떻게-하나)
- [일반적인 패턴: 재연결 시 REST로 재동기화](#일반적인-패턴-재연결-시-rest로-재동기화)

---

## 누가 무엇을 하나

SSE 표준(브라우저 `EventSource`)이 정한 것은 **클라이언트 쪽 동작**뿐입니다. 놓친 이벤트를 보관하고 골라 보내는 것은 **애플리케이션이 직접 구현**해야 합니다.

| 역할 | 담당 | 이 프로젝트 |
|---|---|---|
| 마지막으로 받은 `id` 기억 | 브라우저 `EventSource` (표준) | 코드 없음 |
| 끊기면 자동 재연결 + `Last-Event-ID` 헤더 전송 | 브라우저 `EventSource` (표준) | 코드 없음 |
| 이벤트에 `id` 붙이기 | 서버 (직접 구현) | `sequence`로 전역 순번 부여, `toEvent`에서 `.id(...)` (basic은 `id` 필드 없음) |
| 지난 이벤트 보관 | 서버 (직접 구현) | `history` 리스트, 최근 100개 |
| 헤더를 읽고 이후 이벤트 재전송 | 서버 (직접 구현) | `subscribe(lastEventId)` |

Spring의 `SseEmitter`는 이벤트를 **보내는 도구**일 뿐, 재전송 버퍼나 보관 개수 설정을 제공하지 않습니다. 아래의 100개도 Spring 기본값이 아니라 이 프로젝트에서 정한 상수입니다.

---

## 동작 흐름

```
① 연결 중 수신        id:1, id:2 수신 → EventSource가 lastEventId = "2" 기억
② 연결 끊김          (네트워크 단절, 또는 서버 타임아웃 60초)
③ 끊긴 사이 발행      id:3, id:4 → 서버 history에는 저장, 이 클라이언트에는 전달 못 함
④ 자동 재연결        EventSource가 몇 초 뒤 GET /replay/notifications/subscribe + 헤더 Last-Event-ID: 2
⑤ 서버 재전송        history.filter { it.id > 2 } → id:3, id:4 전송
⑥ 이후             새 emitter로 실시간 수신 계속
```

```kotlin
// ReplayNotificationController.kt
@GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun subscribe(
	@RequestHeader("Last-Event-ID", required = false) lastEventId: Long?,
): SseEmitter

// ReplayNotificationService.kt
fun publish(message: String): Notification {
	...
	history.add(notification)                                  // 보관
	if (history.size > HISTORY_SIZE) history.removeFirst()     // 100개 초과 시 가장 오래된 것 삭제
	emitters.forEach { send(it, toEvent(notification)) }       // 현재 연결된 구독자에게 전송
}

fun subscribe(lastEventId: Long?): SseEmitter {
	...
	if (lastEventId != null) {
		history.filter { it.id > lastEventId }                 // 놓친 것만 골라
			.forEach { send(emitter, toEvent(it)) }            // 새 연결로 재전송
	}
}
```

- 재연결 간격은 브라우저 기본값(보통 수 초)입니다. 서버가 이벤트에 `retry:` 필드(ms)를 보내면 바꿀 수 있습니다(이 프로젝트에서는 미설정).
- curl로 `Last-Event-ID: 1`을 붙여 구독하면 `id:2`부터 재전송되는 것을 확인했습니다.

---

## Last-Event-ID는 이벤트 순번이다

`Last-Event-ID`는 클라이언트 식별자가 아니라 **"이 이벤트까지 받았다"는 위치**입니다.

- `publish`가 알림마다 `sequence.incrementAndGet()`으로 전역 순번(1, 2, 3...)을 붙이고, 이것이 `id:` 필드로 나갑니다.
- 같은 시점에 연결돼 있던 클라이언트들은 모두 같은 값을 갖습니다.
- 서버는 클라이언트를 구분하지 않습니다. 익명의 emitter 목록과 전체 history만 보관하고, 진행 위치는 **클라이언트가 들고 있다가** 재연결 시 알려 줍니다.

```
알림 id: 1  2  3  4  5
클라이언트 A ──●──●──●──●──●   계속 연결 → 재연결 시 Last-Event-ID: 5
클라이언트 B ──●──●╳        ●   2 이후 끊김 → 재연결 시 Last-Event-ID: 2 → 3, 4 재전송
클라이언트 C         ●──●   4 직전 최초 접속 → 헤더 없음 → 4부터
```

Kafka와 비교하면 다음과 같습니다.

| | Kafka | SSE (`Last-Event-ID`) |
|---|---|---|
| 누가 | 컨슈머 그룹 ID | 없음 |
| 어디까지 | offset | `Last-Event-ID` |
| 위치 저장 | 브로커에 커밋. 컨슈머 재시작에도 유지 | 브라우저 `EventSource` 메모리. 새로고침하면 사라짐 |
| 시작 위치 선택 | `auto.offset.reset` (`earliest` / `latest`) | 항상 latest (선택 수단 없음) |
| 보관 범위 | retention 기간, 디스크 | 서버가 직접 구현 (이 프로젝트: 메모리, 최근 100개) |

---

## 최초 접속은 어디부터 받나

`EventSource`는 **재연결할 때만** `Last-Event-ID`를 보냅니다. 최초 접속은 접속 이후 발행된 알림부터 받습니다(Kafka의 `latest`).

| 상황 | `Last-Event-ID` 헤더 | 받는 이벤트 |
|---|---|---|
| 최초 접속 | 없음 (`null`) | 재전송 없이 이후 발행분만 |
| 끊긴 뒤 자동 재연결 | 마지막으로 받은 `id` | 그 이후분 재전송 + 이후 발행분 |
| 페이지 새로고침 | 없음 (새 `EventSource`) | 최초 접속과 같음. 그사이 알림은 유실 |

- `connect` 이벤트와 heartbeat 주석에는 `id`가 없으므로, 마지막 id를 바꾸지 않습니다.
- 최초 접속에도 과거 알림을 주려면 쿼리 파라미터(예: `?lastEventId=0`)로 시작 위치를 받는 식으로 직접 구현해야 합니다.

---

## 서버는 얼마나 보관하나

```kotlin
private val history = CopyOnWriteArrayList<Notification>()   // 서버 메모리

companion object {
	private const val HISTORY_SIZE = 100                      // 직접 정한 상수 (Spring 기본값 아님)
}
```

| 항목 | 현재 구현 |
|---|---|
| 보관 위치 | 서버 JVM 메모리 (`history` 리스트 하나) |
| 보관 단위 | 전체 알림 공용. 클라이언트별로 따로 두지 않음 |
| 보관량 | 최근 **100개** (개수 기준, 시간 기준 아님) |
| 오래된 것 | 101번째가 들어오면 가장 오래된 것부터 삭제 |
| 설정 | 코드 상수. 바꾸려면 코드 수정 (`application.yaml`로 빼려면 직접 프로퍼티로 만들어야 함) |

---

## 한계

| 상황 | 결과 |
|---|---|
| 끊긴 사이 알림이 100개 이하 | 전부 재전송 |
| 끊긴 사이 알림이 100개 초과 | 오래된 것은 이미 삭제되어 **조용히 유실**. 클라이언트는 유실 사실을 모름 |
| 알림이 드물게 발생 | 몇 시간 전 알림도 남아 있음 (시간 제한이 없으므로) |
| 알림이 초당 수십 건 | 몇 초 분량만 남아 짧은 끊김도 못 메움 |
| 서버 재시작 | history와 `sequence`가 모두 초기화. id가 1부터 다시 시작해 클라이언트가 가진 id와 어긋남 |
| 서버 여러 대 | 서버마다 history가 따로라, 다른 서버로 재연결되면 재전송 불가 |
| 구독과 발행이 동시에 실행 | `subscribe`가 emitter를 먼저 등록한 뒤 history를 재전송하므로, 그 사이 발행된 알림이 두 번 갈 수 있음. 클라이언트가 `id`로 중복 제거 (at-least-once) |

---

## 실무 보완

- **보관 기준**: 개수와 시간을 함께 사용 (예: 최근 5분 또는 1,000개).
- **공유 저장소**: 메모리 대신 Redis Stream, DB 등에 보관하면 서버 재시작/여러 대에서도 유지되고, id도 저장소가 발급해 어긋나지 않습니다. 사실상 Kafka의 retention과 같은 구조가 됩니다.
- **유실 감지**: 요청한 `Last-Event-ID`가 보관 범위보다 오래됐으면 "전체 다시 불러오기" 이벤트를 보내고, 클라이언트는 REST로 전체 목록을 다시 조회합니다.
- **사용자별 알림**: "내 주문 상태"처럼 받는 사람이 다르면, 로그인 사용자 ID로 emitter를 구분하고 사용자별 읽음 위치를 서버(DB)에 저장합니다.

---

## WebSocket은 어떻게 하나

```
클라이언트 ── GET /ws + Upgrade: websocket ──▶ 서버
          ◀── 101 Switching Protocols ──        (여기서 HTTP가 끝나고 WebSocket 프로토콜로 전환)
          ◀═══════ 프레임 (양방향) ═══════▶      (어느 쪽이든 아무 때나 메시지 전송)
          ◀── close 프레임 ──▶                  (종료)
```

WebSocket 프로토콜이 보장하는 것은 "메시지를 순서대로 주고받는다"까지입니다. 이벤트 id, 재연결, 놓친 메시지 재전송이라는 개념이 **프로토콜에 없습니다.**

| 역할 | SSE | WebSocket |
|---|---|---|
| 이벤트에 id 부여 | 표준 필드(`id:`). 값은 서버가 정함 | 없음. 메시지 본문에 직접 포함 |
| 마지막 id 기억 | 브라우저 `EventSource`가 자동 | 클라이언트 코드가 직접 |
| 끊김 감지, 자동 재연결 | `EventSource`가 자동 | 직접 구현 (`onclose`에서 재접속, backoff) |
| 재연결 시 위치 전달 | `Last-Event-ID` 헤더 자동 전송 | 직접 정의 (쿼리 파라미터, 연결 후 첫 메시지 등) |
| 지난 메시지 보관 | 직접 구현 | 직접 구현 |
| 위치 이후 재전송 | 직접 구현 | 직접 구현 |
| 연결 유지 확인 | 직접 구현 (주석 이벤트) | 프로토콜에 ping/pong 프레임 있음. 전송 주기는 서버/라이브러리 설정 |

상위 프로토콜이나 라이브러리를 쓰면 일부를 맡길 수 있습니다.

| 방식 | 재연결 | 놓친 메시지 재전송 |
|---|---|---|
| 순수 WebSocket | 없음 | 없음 |
| STOMP over WebSocket (Spring) | 클라이언트 라이브러리가 지원 | 기본은 없음. 외부 브로커의 영속 큐를 쓰면 브로커가 보관 |
| graphql-transport-ws | `graphql-ws` 클라이언트가 지원 | 없음. 재연결 시 Subscription을 새로 시작 |
| Socket.IO (Node) | 자동 | Connection state recovery: 일정 시간(기본 2분) 안의 재연결에 한해 재전송 |

---

## 일반적인 패턴: 재연결 시 REST로 재동기화

실무에서는 WebSocket이든 SSE든, 연결 위에서 놓친 메시지를 재전송하는 기능을 만들기보다 **실시간 통로와 놓친 데이터 조회를 분리**하는 경우가 일반적입니다.

```
클라이언트                         서버
   │── WebSocket/SSE 재연결 ─────▶│   실시간 통로 다시 열기
   │── GET /messages?after=42 ──▶│   놓친 데이터 조회 (별도 REST API)
   │◀── [43, 44, 45] ────────────│   DB에서 조회해 응답
   │◀══ 46, 47 ... (실시간) ═════│   이후 실시간 수신
```

| 역할 | 담당 |
|---|---|
| 실시간 변화 전달 | WebSocket / SSE |
| 끊긴 사이 데이터 조회 | REST API (기존 목록 조회 API를 재사용하는 경우가 많음) |
| 어디까지 받았는지 기억 | 클라이언트 (마지막 id 또는 시각) |
| 데이터 보관 | DB (원래 저장하던 곳) |

- 실시간 통로는 "지금 일어나는 변화를 빠르게 알리는" 역할만 맡습니다. 놓친 데이터는 원래 DB에 저장돼 있으므로 다시 조회하면 됩니다.
- REST를 많이 쓰는 이유: 목록 조회 API가 대개 이미 있고, 페이징, 인증, 캐시를 그대로 쓸 수 있으며, 실시간 쪽은 전달만 맡아 단순해집니다.
- REST 대신 같은 WebSocket 연결로 `{"type": "sync", "after": 42}` 같은 요청 메시지를 보내 조회하기도 합니다. 어느 쪽이든 프로토콜 기능이 아니라 **애플리케이션이 정한 규칙**입니다.
- 재연결과 동시에 실시간으로 온 메시지와 조회 결과가 겹칠 수 있으므로, 클라이언트가 id로 중복을 제거합니다.
- 최초 접속도 같은 흐름입니다. 화면을 열 때 REST로 목록을 불러오고, 이후 변화는 실시간으로 받습니다. 재연결은 이것을 한 번 더 하는 것입니다.

데이터 성격에 따라 재동기화 방식이 달라집니다.

| 데이터 | 끊긴 사이 데이터 | 일반적인 처리 |
|---|---|---|
| 시세, 위치, 접속 상태, 입력 중 표시 | 최신 값만 의미 있음 | 재전송 없음. 재연결하면 현재 값부터 |
| 게임 상태 | 최신 상태가 중요 | 재연결 시 전체 상태 스냅샷 전송 |
| 채팅, 알림 | 빠지면 안 됨 | DB에 저장돼 있으므로 재연결 시 REST로 "마지막 id 이후" 조회 |
| 금융 시세 피드(호가 등) | 순서와 누락이 중요 | 메시지 순번으로 누락을 감지하고, 누락 시 스냅샷부터 다시 받음 |

이 관점에서 보면 이 프로젝트의 메모리 history + `Last-Event-ID` 재전송은 **짧은 끊김을 가볍게 메우는 보조 수단**입니다. 알림을 DB에 저장하는 실무 구성이라면, 새로고침이나 긴 끊김은 REST 재조회로 처리합니다.
