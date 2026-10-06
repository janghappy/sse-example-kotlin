# SSE와 REST API 비교

SSE도 일반 HTTP `GET` 요청으로 시작합니다. 차이는 **응답을 언제 끝내느냐**입니다. 이 프로젝트에서도 발행은 REST(`POST /basic/notifications`), 구독은 SSE(`GET /basic/notifications/subscribe`)로 함께 씁니다(replay도 동일).

- [요청-응답 흐름](#요청-응답-흐름)
- [REST로 실시간을 흉내 내는 방법](#rest로-실시간을-흉내-내는-방법)
- [비교 표](#비교-표)
- [Spring 코드 비교](#spring-코드-비교)
- [언제 무엇을 쓰나](#언제-무엇을-쓰나)

---

## 요청-응답 흐름

```
REST          클라이언트 ── 요청 ──▶ 서버
                         ◀── 응답 ──      (연결 종료)

SSE           클라이언트 ── 요청 ──▶ 서버
                         ◀── 헤더 ──      (응답 시작, 끝내지 않음)
                         ◀── 이벤트 ──    (서버가 원할 때마다)
                         ◀── 이벤트 ──
                         ...              (타임아웃/종료 시 끝, EventSource가 재연결)
```

- **REST**: 클라이언트가 물어봐야 서버가 답합니다. 서버에 새 데이터가 생겨도 클라이언트가 다시 요청하기 전에는 전달할 방법이 없습니다.
- **SSE**: 한 번 연결해 두면, 서버가 데이터가 생긴 시점에 바로 보냅니다.

---

## REST로 실시간을 흉내 내는 방법

REST만으로 알림을 받으려면 클라이언트가 계속 물어봐야 합니다.

| 방식 | 동작 | 문제 |
|---|---|---|
| **Short polling** | N초마다 `GET /notifications?after={마지막 id}` 요청 | 새 데이터가 없어도 요청이 계속 나감(빈 응답). 주기만큼 지연. 주기를 줄이면 요청 수 증가 |
| **Long polling** | 요청을 받은 서버가 새 데이터가 생길 때까지 응답을 미뤘다가 응답, 클라이언트는 응답을 받자마자 다시 요청 | 지연은 줄지만 이벤트 하나마다 요청-응답이 한 번씩 반복됨. 재요청 사이에 생긴 이벤트 처리를 직접 구현 |
| **SSE** | 연결 하나로 이벤트를 계속 받음 | 서버가 연결을 유지해야 함 |

SSE는 long polling에서 "응답을 끝내지 않고 계속 이어 보내는" 형태로, 재연결과 놓친 이벤트 처리(`Last-Event-ID`)까지 표준으로 정해져 있습니다.

---

## 비교 표

| | REST API | SSE |
|---|---|---|
| 통신 방향 | 클라이언트가 요청해야 응답 (pull) | 서버가 보내고 싶을 때 전송 (push) |
| 연결 | 요청마다 응답 후 종료 | 하나의 응답을 길게 유지 |
| 응답 헤더 | 보통 `Content-Type: application/json` | `Content-Type: text/event-stream`, `Transfer-Encoding: chunked` (curl로 확인) |
| 응답 본문 | 하나의 완결된 문서(JSON 등) | 빈 줄로 구분된 이벤트(`id`, `event`, `data`)가 계속 이어짐 |
| HTTP 메서드 | GET, POST, PUT, DELETE 등 | GET만 (`EventSource`는 GET만 지원) |
| 상태 코드 | 요청마다 결과를 상태 코드로 표현 | 연결 시작 시 한 번(200). 이후 이벤트별 상태 코드는 없음 |
| 서버 상태 | stateless: 어느 서버가 받아도 같음 | 연결(emitter)을 서버 메모리에 보관. 서버가 여러 대면 이벤트 전파 필요 |
| 확장 | 로드밸런서로 수평 확장이 쉬움 | 연결이 특정 서버에 고정됨. Redis Pub/Sub 등으로 서버 간 전파 |
| 캐싱 | GET은 HTTP 캐시 활용 가능 | 캐시 대상이 아님 |
| 실패 처리 | 클라이언트가 재시도 | `EventSource`가 자동 재연결 + `Last-Event-ID`로 이어 받기 |
| 연결 수 제한 | 요청이 끝나면 연결 반환 | HTTP/1.1에서는 브라우저가 도메인당 동시 연결 수(보통 6개)를 제한하므로, 탭을 여러 개 열면 막힐 수 있음. HTTP/2에서는 하나의 연결로 다중화 |
| 클라이언트 → 서버 | 같은 API로 처리 | 불가. 별도의 REST 요청으로 보냄 (이 프로젝트의 `POST /{type}/notifications`) |

---

## Spring 코드 비교

같은 "알림"을 REST와 SSE로 제공할 때의 차이입니다. REST 쪽은 비교용 예시이고 프로젝트에는 구현하지 않았습니다.

```kotlin
// REST (polling용 예시): 지금까지의 알림을 한 번 응답하고 끝
@GetMapping
fun getNotifications(@RequestParam after: Long): List<Notification> {
	return notificationService.findAfter(after)
}

// SSE (BasicNotificationController): emitter를 반환하면 응답이 열린 채로 유지되고, 이후 send()할 때마다 이벤트 전송
@GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun subscribe(): SseEmitter {
	return notificationService.subscribe()
}
```

- REST는 메서드가 반환하는 순간 응답이 완성됩니다.
- SSE는 `SseEmitter`를 반환해도 응답이 끝나지 않습니다. Spring MVC가 비동기 요청으로 전환해 연결을 열어 두고, 다른 스레드(발행 요청, `@Scheduled` heartbeat)에서 `emitter.send()`를 호출해 이벤트를 이어 씁니다.
- 둘 다 `spring-boot-starter-webmvc`만으로 구현할 수 있고, 같은 `@RestController` 안에 공존합니다.

---

## 언제 무엇을 쓰나

| 상황 | 선택 |
|---|---|
| 조회, 생성, 수정, 삭제 등 일반적인 요청 | REST |
| 데이터가 드물게 바뀌고 몇십 초 지연이 괜찮음 | REST + short polling |
| 서버에서 생긴 변화를 바로 받아야 함 (알림, 진행률, 피드 갱신) | SSE |
| 클라이언트도 실시간으로 계속 보내야 함 (채팅, 게임) | WebSocket |

SSE는 REST를 대체하는 것이 아니라, REST로 표현하기 어려운 **서버 → 클라이언트 푸시**를 보완합니다.
