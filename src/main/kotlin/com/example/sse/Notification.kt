package com.example.sse

import java.time.OffsetDateTime

data class Notification(
	val id: Long,
	val message: String,
	val createdAt: OffsetDateTime,
)
