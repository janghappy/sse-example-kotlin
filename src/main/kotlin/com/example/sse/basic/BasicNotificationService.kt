package com.example.sse.basic

import com.example.sse.Notification
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

// 기본 예제: 연결된 구독자에게 실시간으로 보내기만 한다 (끊긴 사이의 알림은 재전송하지 않음)
@Service
class BasicNotificationService {

	private val emitters = CopyOnWriteArrayList<SseEmitter>()
	private val sequence = AtomicLong()

	fun subscribe(): SseEmitter {
		// 타임아웃이 지나면 연결이 끊기고, 브라우저 EventSource가 자동으로 재연결한다
		val emitter = SseEmitter(TIMEOUT_MILLIS)
		emitters.add(emitter)
		emitter.onCompletion { emitters.remove(emitter) }
		emitter.onTimeout { emitter.complete() }
		emitter.onError { emitters.remove(emitter) }

		// 연결 직후 이벤트를 하나 보내야 응답 헤더가 바로 나간다
		send(emitter, SseEmitter.event().name("connect").data("connected"))
		return emitter
	}

	fun publish(message: String): Notification {
		val notification = Notification(
			id = sequence.incrementAndGet(),
			message = message,
			createdAt = OffsetDateTime.now(),
		)
		emitters.forEach { send(it, toEvent(notification)) }
		return notification
	}

	fun subscriberCount(): Int = emitters.size

	// 이벤트 조립: 전송은 하지 않는다
	private fun toEvent(notification: Notification): SseEmitter.SseEventBuilder =
		SseEmitter.event()
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
	}
}
