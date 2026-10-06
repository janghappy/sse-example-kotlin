# sse-example

Spring Boot / Kotlin으로 SSE(Server-Sent Events)를 가볍게 실습하는 프로젝트입니다. 알림을 발행(POST)하면 구독 중인 모든 클라이언트에 실시간으로 전달됩니다.

같은 알림 기능을 두 단계로 나눠 구현했습니다.

| 예제 | 패키지 | 내용 |
|---|---|---|
| **basic** | `basic/` | SSE 기본기: 구독(emitter 등록), 발행(`emitter.send`), 타임아웃, heartbeat. 끊긴 사이 알림은 재전송하지 않음 |
| **replay** | `replay/` | basic + 이벤트 `id`, history 보관, `Last-Event-ID`로 재연결 시 놓친 알림 재전송. 추가된 부분은 `[replay]` 주석으로 표시 |

## 기술 스택

| 구분 | 버전 |
|---|---|
| Kotlin | 2.3.21 |
| Java (toolchain) | 17 |
| Spring Boot | 4.1.1 (`spring-boot-starter-webmvc`, 추가 의존성 없음) |

## 실행

```bash
./gradlew bootRun
```

- 브라우저 클라이언트: `http://localhost:8081` (basic/replay 선택, EventSource로 구독 + 발행 폼, 구독자 수 5초 주기 조회)

`{type}`은 `basic` 또는 `replay`입니다. 두 예제는 구독자 목록과 알림 순번을 각자 따로 관리합니다.

| API | 설명 |
|---|---|
| `GET /{type}/notifications/subscribe` | 구독 (`text/event-stream`). replay는 `Last-Event-ID` 헤더를 받음 |
| `POST /{type}/notifications` `{"message": "..."}` | 알림 발행 |
| `GET /{type}/notifications/subscribers` | 현재 구독자 수(emitters 크기). 끊긴 연결 정리 관찰용 |

```bash
curl -N localhost:8081/basic/notifications/subscribe                # 터미널 1: 구독
curl -X POST localhost:8081/basic/notifications \
  -H 'Content-Type: application/json' -d '{"message":"hello"}'      # 터미널 2: 발행

curl -N localhost:8081/replay/notifications/subscribe -H 'Last-Event-ID: 1'   # replay: id 1 이후부터 재전송
```

## 프로젝트 구조

```
src/main/
├── kotlin/com/example/sse/
│   ├── SseApplication.kt                     # @EnableScheduling (heartbeat)
│   ├── Notification.kt                       # 공용 알림 모델
│   ├── basic/
│   │   ├── BasicNotificationController.kt    # /basic/notifications
│   │   └── BasicNotificationService.kt       # emitters, send, heartbeat
│   └── replay/
│       ├── ReplayNotificationController.kt   # /replay/notifications, Last-Event-ID 헤더
│       └── ReplayNotificationService.kt      # basic + id, history, 재전송
└── resources/
    ├── application.yaml                      # 포트 8081
    └── static/index.html                     # EventSource 클라이언트
```

## SSE란

HTTP 응답을 끝내지 않고 열어 둔 채, 서버가 텍스트 이벤트를 계속 이어서 보내는 방식입니다.

| | SSE | WebSocket |
|---|---|---|
| 방향 | 서버 → 클라이언트 (단방향) | 양방향 |
| 프로토콜 | 일반 HTTP (`Content-Type: text/event-stream`) | HTTP에서 업그레이드한 별도 프로토콜 |
| 데이터 | 텍스트(UTF-8) | 텍스트, 바이너리 |
| 재연결 | 브라우저 `EventSource`가 자동 재연결 + `Last-Event-ID` | 직접 구현 |
| 클라이언트 → 서버 | 일반 HTTP 요청(POST 등)으로 별도 전송 | 같은 연결로 전송 |

알림, 진행률, 피드 갱신처럼 **서버가 일방적으로 밀어주는** 경우에는 SSE가 더 단순합니다.

## 이벤트 형식

실제 응답 본문입니다(curl로 확인). 이벤트는 빈 줄로 구분됩니다.

```
# basic: id 없음
event:connect
data:connected

event:notification
data:{"id":1,"message":"basic-1","createdAt":"2026-10-22T13:08:18.034262898+09:00"}

# replay: id 포함
id:1
event:notification
data:{"id":1,"message":"replay-1","createdAt":"2026-10-22T13:08:19.978898075+09:00"}
```

| 필드 | 의미 |
|---|---|
| `id` | 이벤트 ID. 브라우저가 기억했다가 재연결 시 `Last-Event-ID` 헤더로 보냄 (replay만 사용) |
| `event` | 이벤트 이름. 클라이언트에서 `addEventListener('notification', ...)`로 구분 |
| `data` | 본문. basic의 JSON 안 `id`는 단순 데이터일 뿐 SSE `id` 필드가 아님 |
| `:` 로 시작하는 줄 | 주석. 클라이언트는 무시하며 heartbeat 용도로 사용 |

## 구현 포인트

### basic

- **`SseEmitter`**: 컨트롤러가 반환하면 Spring MVC가 응답을 열어 두고(비동기 요청), 이후 `emitter.send()`할 때마다 이벤트가 전송됩니다. 구독자별 emitter를 목록에 보관하고 발행 시 모두에게 보냅니다.
- **`toEvent`와 `send`**: `toEvent`는 `Notification`을 이벤트(`event`, `data`)로 조립만 하고, 실제 전송은 `send`의 `emitter.send()`가 합니다. 전송이 실패하면 끊긴 연결로 보고 목록에서 제거합니다.
- **연결 직후 `connect` 이벤트 전송**: 아무것도 보내지 않으면 응답 헤더조차 나가지 않아, 클라이언트는 연결 성공 여부를 알 수 없습니다.
- **타임아웃(60초)**: `SseEmitter(timeout)`이 지나면 연결이 끝납니다. 브라우저 `EventSource`는 자동으로 재연결하므로, 무한 연결 대신 주기적으로 재연결하게 둡니다.
- **heartbeat(15초)**: 주석(`:heartbeat`)을 주기적으로 보냅니다.
  - 프록시/로드밸런서가 유휴 연결을 끊지 않게 합니다.
  - **끊긴 연결 정리**: 서버는 클라이언트가 끊어도 전송이 실패할 때까지 알지 못합니다. 끊긴 직후 첫 전송은 OS 송신 버퍼에 들어가 성공할 수 있어, curl 구독을 끊은 뒤 구독자 수는 첫 heartbeat 후에도 1이었고 **두 번째 heartbeat에서 0**으로 정리됐습니다(약 30초).
- **스레드**: SSE 연결 하나가 요청 스레드를 붙잡지는 않지만(비동기 처리), 연결 자체는 계속 유지됩니다. 구독자가 많으면 연결 수와 emitter 목록이 늘어나므로, 실무에서는 서버 여러 대 간 전파(Redis Pub/Sub 등)도 고려해야 합니다.

### replay (basic에 추가된 것)

- **이벤트 `id`**: `toEvent`에서 `.id(...)`를 붙입니다. 브라우저 `EventSource`가 마지막 id를 기억하고, 재연결 시 `Last-Event-ID` 헤더로 보냅니다.
- **history**: 최근 알림 100개를 메모리에 보관합니다. 실시간 발송에는 쓰지 않고, 재연결 시 재전송용으로만 읽는 아카이브입니다.
- **재전송**: 구독 요청에 `Last-Event-ID`가 있으면 그 이후 알림을 먼저 보냅니다. `Last-Event-ID: 1`로 구독하면 `id:2`부터 받는 것을 확인했습니다. 같은 헤더를 basic에 보내면 무시됩니다.

## 문서

- [SseEmitter 동작과 연결 관리](docs/sse-emitter.md): emitter와 emitters, return emitter의 의미, 반환 전 send 버퍼링, connect 이벤트가 필요한 이유, 끊긴 연결 정리(heartbeat, 타임아웃)와 감지 시점 추적
- [Last-Event-ID 재연결과 놓친 알림 재전송](docs/sse-reconnect.md): 표준과 직접 구현의 경계, 이벤트 순번(Kafka offset 비교), 최초 접속 위치, 보관량(100개)과 한계, 실무 보완, WebSocket 비교, 재연결 시 REST 재동기화 패턴
- [SSE와 REST API 비교](docs/sse-vs-rest.md): 요청-응답 흐름, polling/long polling, 비교 표, Spring 코드 비교, 선택 기준
