package com.example.sse.replay

import com.example.sse.Notification
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/replay/notifications")
class ReplayNotificationController(
	private val notificationService: ReplayNotificationService,
) {

	// 구독: 응답을 끊지 않고 text/event-stream으로 이벤트를 계속 보낸다
	// [replay] 재연결 시 EventSource가 보내는 Last-Event-ID 헤더를 받는다 (최초 접속이면 없음)
	@GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun subscribe(
		@RequestHeader("Last-Event-ID", required = false) lastEventId: Long?,
	): SseEmitter {
		return notificationService.subscribe(lastEventId)
	}

	// 발행: 일반 HTTP 요청으로 알림을 만들면 모든 구독자에게 전달된다
	@PostMapping
	fun publish(@RequestBody request: PublishRequest): Notification {
		return notificationService.publish(request.message)
	}

	// 확인용: 현재 emitters 수. 끊긴 연결이 정리되는 과정을 관찰한다
	@GetMapping("/subscribers")
	fun subscriberCount(): Int = notificationService.subscriberCount()

	data class PublishRequest(val message: String)
}
