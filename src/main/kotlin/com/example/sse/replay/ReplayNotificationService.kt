package com.example.sse.replay

import com.example.sse.Notification
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// 재전송 예제: BasicNotificationService에 [replay] 표시 부분을 더해, 재연결 시 놓친 알림을 다시 보낸다
@Service
class ReplayNotificationService {

	private val emitters = CopyOnWriteArrayList<SseEmitter>()
	private val sequence = AtomicLong()

	// [replay] 최근 알림 보관: 실시간 발송에는 쓰지 않고, 재연결 시 재전송용으로만 읽는다
	private val history = CopyOnWriteArrayList<Notification>()

	// [replay] lastEventId: 클라이언트가 마지막으로 받은 이벤트 id (재연결 시 Last-Event-ID 헤더)
	fun subscribe(lastEventId: Long?): SseEmitter {
		// 타임아웃이 지나면 연결이 끊기고, 브라우저 EventSource가 자동으로 재연결한다
		val emitter = SseEmitter(TIMEOUT_MILLIS)
		emitters.add(emitter)
		emitter.onCompletion { emitters.remove(emitter) }
		emitter.onTimeout { emitter.complete() }
		emitter.onError { emitters.remove(emitter) }

		// 연결 직후 이벤트를 하나 보내야 응답 헤더가 바로 나간다
		send(emitter, SseEmitter.event().name("connect").data("connected"))

		// [replay] 재연결이면 마지막으로 받은 id 이후의 알림을 먼저 보낸다
		if (lastEventId != null) {
			history.filter { it.id > lastEventId }
				.forEach { send(emitter, toEvent(it)) }
		}
		return emitter
	}

	fun publish(message: String): Notification {
		val notification = Notification(
			id = sequence.incrementAndGet(),
			message = message,
			createdAt = OffsetDateTime.now(),
		)

		// [replay] 보관 후 오래된 것부터 삭제
		history.add(notification)
		if (history.size > HISTORY_SIZE) history.removeFirst()

		emitters.forEach { send(it, toEvent(notification)) }
		return notification
	}

	fun subscriberCount(): Int = emitters.size

	// 프록시/로드밸런서가 유휴 연결을 끊지 않도록 주기적으로 주석(comment)을 보낸다
	@Scheduled(fixedRate = HEARTBEAT_MILLIS)
	fun heartbeat() {
		emitters.forEach { send(it, SseEmitter.event().comment("heartbeat")) }
	}

	// 이벤트 조립: 전송은 하지 않는다
	private fun toEvent(notification: Notification): SseEmitter.SseEventBuilder =
		SseEmitter.event()
			.id(notification.id.toString())   // [replay] 브라우저가 기억했다가 재연결 시 Last-Event-ID로 보낸다
			.name("notification")
			.data(notification, MediaType.APPLICATION_JSON)

	// 실제 전송: 실패하면 끊긴 연결로 보고 목록에서 제거한다
	private fun send(emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
		try {
			emitter.send(event)
		} catch (e: IOException) {
			emitters.remove(emitter)
		}
	}

	companion object {
		private const val TIMEOUT_MILLIS = 60_000L
		private const val HEARTBEAT_MILLIS = 15_000L
		private const val HISTORY_SIZE = 100   // [replay] Spring 기본값이 아닌 직접 정한 보관 개수
	}
}
