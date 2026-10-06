# SseEmitter 동작과 연결 관리

`basic` 예제(`BasicNotificationService`)를 기준으로, `SseEmitter`가 HTTP 응답과 어떻게 연결되고 끊긴 연결이 어떻게 정리되는지 정리합니다.

- [emitter와 emitters](#emitter와-emitters)
- [return emitter가 하는 일](#return-emitter가-하는-일)
- [반환 전에 send하면: 버퍼링](#반환-전에-send하면-버퍼링)
- [connect 이벤트를 보내는 이유](#connect-이벤트를-보내는-이유)
- [조용히 끊긴 연결 정리](#조용히-끊긴-연결-정리)

---

## emitter와 emitters

| | 정체 | 역할 |
|---|---|---|
| `emitter` | 클라이언트 **한 명**의 열린 HTTP 응답 | 그 클라이언트에게 보내는 통로. `send()`하면 이 클라이언트에게만 감 |
| `emitters` | 현재 열려 있는 emitter **목록** | 발행할 때 "지금 누구에게 보내야 하나"를 찾는 명부 |

클라이언트, HTTP 연결, emitter는 1:1:1입니다.

```
브라우저 탭 1 (EventSource) ═══ HTTP 연결 1 ═══ emitter A ─┐
브라우저 탭 2 (EventSource) ═══ HTTP 연결 2 ═══ emitter B ─┼─ emitters
curl                        ═══ HTTP 연결 3 ═══ emitter C ─┘
```

- 같은 사용자라도 탭이 두 개면 emitter도 두 개입니다. 서버는 둘이 같은 사람인지 모릅니다.
- 재연결은 이전 연결을 이어 쓰는 것이 아니라 새 HTTP 요청이므로 새 emitter가 만들어집니다. 이전 emitter는 [아래 장치](#조용히-끊긴-연결-정리)로 정리됩니다.

`emitters`가 필요한 이유는 구독과 발행이 **서로 다른 요청**이기 때문입니다.

```
[구독 요청 A]  GET /subscribe → emitter A 생성 → emitters에 등록 → 반환 (요청 스레드 반납, 연결은 유지)
[구독 요청 B]  GET /subscribe → emitter B 생성 → emitters에 등록 → 반환
...
[발행 요청]    POST /notifications → emitters 순회 → A.send(), B.send()
```

발행 요청을 처리할 때 구독 요청은 이미 반환된 뒤이므로, emitter를 어딘가에 보관해 둬야 나중에 꺼내 쓸 수 있습니다.

---

## return emitter가 하는 일

```kotlin
@GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun subscribe(): SseEmitter {
	return notificationService.subscribe()   // 이 반환값을 Spring MVC가 받는다
}
```

반환 타입이 `SseEmitter`이면 Spring MVC는 일반 응답처럼 처리하지 않습니다.

1. 응답을 닫지 않은 채 비동기 모드로 전환합니다.
2. 요청 처리 스레드는 반납합니다. 연결은 열려 있지만 스레드를 붙잡지 않습니다.
3. emitter를 이 요청의 응답 스트림에 연결합니다. 이후 어느 스레드(발행 요청, `@Scheduled` heartbeat)에서든 `emitter.send()`하면 이 클라이언트에게 이벤트가 나갑니다.

emitter를 반환하지 않으면 응답은 그 자리에서 끝나고, emitter는 어떤 응답에도 연결되지 않아 `send`해도 아무에게도 도착하지 않습니다. **클라이언트가 emitter를 받는 것이 아닙니다.** emitter는 서버 메모리에만 있고, 클라이언트는 HTTP 응답 스트림을 받습니다.

---

## 반환 전에 send하면: 버퍼링

`subscribe` 안에서는 `return`보다 먼저 `send(connect)`를 호출합니다. 이 시점에는 emitter가 아직 응답에 연결되지 않았습니다.

```
[subscribe() 실행 중]
 ① SseEmitter 생성, emitters에 등록
 ② send(connect) 호출    → 응답과 연결 전이므로 emitter 내부 버퍼에 보관 (네트워크 전송 없음)
 ③ return emitter

[컨트롤러 반환 직후, Spring MVC]
 ④ 비동기 모드 전환, emitter를 응답 스트림에 연결
 ⑤ 버퍼에 있던 connect 이벤트를 응답에 씀 → 헤더 + connect 이벤트가 클라이언트에 도착

[이후]
 ⑥ publish/heartbeat의 send()는 이미 연결된 상태라 호출 즉시 응답에 씀
```

| `send` 호출 시점 | emitter 상태 | 동작 |
|---|---|---|
| 반환 전 (`subscribe` 안의 connect, replay의 재전송) | 응답과 연결 전 | 버퍼에 보관했다가 ⑤에서 순서대로 전송 |
| 반환 후 (`publish`, `heartbeat`) | 응답과 연결됨 | 즉시 전송 |

`send`는 "이 emitter로 보낸다"는 뜻이고, 실제 네트워크 전송 시점은 emitter가 응답에 연결된 이후입니다. 그 차이는 컨트롤러가 반환하는 짧은 순간이라, 클라이언트 입장에서는 연결하자마자 `connect`를 받는 것으로 보입니다.

---

## connect 이벤트를 보내는 이유

emitter를 응답에 연결하는 것만으로는 응답 헤더가 나가지 않고, **첫 이벤트를 쓸 때 헤더도 함께 나갑니다.**

`connect` 전송을 뺀 복사본으로 확인했습니다(프로젝트 코드는 그대로).

```bash
$ timeout 2 curl -sN -i localhost:18084/basic/notifications/subscribe
(2초 동안 출력 없음: 상태 줄과 헤더도 오지 않음)
```

- `EventSource`는 응답 헤더를 받아야 `onopen`이 호출됩니다. `connect`가 없으면 첫 알림이 발행될 때까지 클라이언트는 연결 중 상태로 남습니다.
- 그래서 연결 직후 이벤트를 하나 보내 연결 성공을 바로 알립니다.

---

## 조용히 끊긴 연결 정리

**서버는 연결이 끊긴 사실을 스스로 알 수 없습니다.** 클라이언트가 사라져도(네트워크 단절, 탭 종료, 노트북 덮개 닫힘 등) 그 연결에 뭔가를 써 봐야 실패로 알게 됩니다. 정리하지 않으면 `emitters`에 끊긴 emitter가 계속 쌓입니다.

현재 코드의 정리 장치는 네 가지입니다.

| 장치 | 코드 | 동작 시점 |
|---|---|---|
| ① 전송 실패 시 제거 | `send`의 `catch (IOException)` → `emitters.remove` | 끊긴 연결에 쓰다가 실패할 때 |
| ② heartbeat | 15초마다 모든 emitter에 `:heartbeat` 전송 | 알림이 없어도 주기적으로 써 보게 해서 ①을 일으킴 |
| ③ 에러 콜백 | `onError { emitters.remove(emitter) }` | 전송 실패 직후 Spring/Tomcat이 끊긴 클라이언트를 통지할 때 (아래 추적 참고) |
| ④ 타임아웃 | `SseEmitter(60초)` → `onTimeout { complete() }` → `onCompletion { remove }` | 연결 후 60초가 지나면 무조건 |

- **heartbeat**: 알림이 한참 없어도 주기적으로 전송을 시도해, 끊긴 연결을 드러냅니다.
- **타임아웃**: 감지가 안 되더라도 일정 시간 뒤 강제로 끝내는 상한선입니다. 클라이언트 `EventSource`는 자동으로 재연결하므로 정상 클라이언트는 새 emitter로 이어집니다.

### 측정

curl로 구독한 뒤 종료하고, 확인용 API `GET /basic/notifications/subscribers`(emitters 크기)를 5초 간격으로 조회했습니다. 브라우저 클라이언트(`index.html`)도 같은 API를 5초마다 조회해 화면에 표시합니다.

```bash
timeout 1 curl -sN localhost:8081/basic/notifications/subscribe > /dev/null   # 구독 후 1초 뒤 종료
curl localhost:8081/basic/notifications/subscribers                           # 5초 간격으로 반복 조회
```

```
끊은 직후 ~ 30초   basic=1   ← 첫 heartbeat 전송은 OS 송신 버퍼에 들어가 성공
       ~ 35초   basic=0   ← 두 번째 heartbeat 전송이 실패 → 제거
```

- 끊긴 직후 첫 전송은 실패하지 않을 수 있습니다. 데이터가 OS 송신 버퍼까지만 들어가면 성공으로 처리되고, 상대가 없다는 응답(RST)을 받은 뒤의 전송부터 실패합니다.
- 따라서 끊긴 연결은 **heartbeat 2회(약 30초)** 안에, 늦어도 **타임아웃(60초)**에 정리됩니다.

### 감지 시점 추적

복사본에 로그를 넣어(프로젝트 코드는 그대로) curl 구독을 끊은 뒤 어느 스레드의 어느 코드에서 정리되는지 확인했습니다.

```
13:31:45.302 [http-nio-exec-2] subscribe 3f8b978a
13:31:47     (curl 종료)
13:31:59.442 [scheduling-1]    heartbeat → send ok             ← 첫 전송은 성공 (OS 송신 버퍼에 들어감)
13:32:14.442 [scheduling-1]    heartbeat → send IOException    ← AsyncRequestNotUsableException: ... Broken pipe
                               └ catch에서 emitters.remove     ← 실제로 구독자 수가 줄어드는 시점
13:32:14.443 [http-nio-exec-3] onError                         ← "Servlet container error notification for disconnected client"
13:32:14.452 [http-nio-exec-3] onCompletion
```

| 순서 | 스레드 | 코드 |
|---|---|---|
| ① | heartbeat (`scheduling-1`) | `emitter.send(event)`가 `IOException`(Broken pipe)을 던짐 |
| ② | 같은 스레드 | `catch (e: IOException) { emitters.remove(emitter) }` → **여기서 제거** |
| ③ | Tomcat 요청 스레드 | 끊긴 클라이언트 통지로 `onError { emitters.remove }` 실행 (이미 제거되어 사실상 아무것도 하지 않음) |
| ④ | 같은 스레드 | `onCompletion { emitters.remove }` 실행, emitter 정리 완료 |

정리 경로는 세 개(catch, `onError`, `onCompletion`)지만 모두 **같은 전송 실패**에서 시작합니다.

### 정상 종료와 비정상 종료

curl을 `kill -9`로 강제 종료해도 위와 똑같이 두 번째 heartbeat에서 `Broken pipe` → `onError` → `onCompletion` 순으로 정리됐습니다.

- 브라우저 탭을 닫든 프로세스가 강제 종료되든, 클라이언트 OS가 소켓을 닫으며 FIN을 보냅니다.
- 서블릿(Tomcat)의 SSE 응답은 서버가 쓰기만 하고 그 연결을 읽지 않으므로, FIN이 와도 확인하는 코드가 없습니다. 다음에 써 볼 때 실패로 알게 됩니다.
- FIN도 RST도 오지 않는 단절(랜선 분리, Wi-Fi 끊김, 절전)에서는 TCP 재전송이 포기될 때까지(보통 수 분) 전송이 계속 성공할 수 있어 heartbeat로 빨리 감지하지 못할 수 있습니다. 이때는 **타임아웃(60초)**이 정리합니다(네트워크 차단이 필요해 측정하지 않음).

### 브라우저 닫기를 즉시 감지할 수 있나

Spring MVC + `SseEmitter` 구조에서는 즉시 감지할 수 없고, 다음 전송 때 알게 됩니다.

| 방법 | 내용 | 한계 |
|---|---|---|
| heartbeat 주기 단축 | 감지가 그만큼 빨라짐 | 전송량 증가, 여전히 즉시는 아님 |
| 클라이언트가 해제 요청 | `pagehide`에서 `navigator.sendBeacon('/unsubscribe')` 등 | 최선 노력. 강제 종료, 네트워크 단절 시에는 오지 않음. emitter 식별 id 필요 |
| WebFlux(Netty) | 연결을 계속 읽으므로 일반적으로 FIN이 오면 응답 스트림 구독이 취소됨 | 네트워크 단절(FIN 없음)은 마찬가지로 감지 불가 |

어떤 방식이든 신호 없이 사라진 클라이언트는 heartbeat와 타임아웃으로 정리해야 합니다.

### 값 정하기

| 값 | 짧게 잡으면 | 길게 잡으면 |
|---|---|---|
| heartbeat 주기 | 정리가 빨라지는 대신 전송량 증가 | 프록시/로드밸런서가 유휴 연결을 먼저 끊을 수 있음 |
| 타임아웃 | 재연결이 잦아짐 | 감지 못 한 연결이 오래 남음 |

타임아웃을 무제한으로 두고 heartbeat도 없으면, 알림이 발행되지 않는 동안 끊긴 emitter가 무한정 쌓일 수 있습니다. 그래서 둘을 함께 둡니다.
