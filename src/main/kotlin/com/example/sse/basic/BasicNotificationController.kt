package com.example.sse.basic

import com.example.sse.Notification
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/basic/notifications")
class BasicNotificationController(
	private val notificationService: BasicNotificationService,
) {

	// 구독: 응답을 끊지 않고 text/event-stream으로 이벤트를 계속 보낸다
	@GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
	fun subscribe(): SseEmitter {
		return notificationService.subscribe()
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
