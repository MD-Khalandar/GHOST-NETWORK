package org.ghost.simulation

import java.util.Collections
import java.util.UUID

/** In-memory pending fixture only: neither signed nor restart-safe. */
data class FakePublication<T>(val eventId: String, val payload: T, val queuedAtMs: Long)

internal fun fixtureEventId(kind: String, owner: String, id: String): String =
    UUID.nameUUIDFromBytes("ghost.cp01/$kind/$owner/$id".toByteArray(Charsets.UTF_8)).toString()

internal fun <T> Iterable<T>.snapshot(): List<T> = Collections.unmodifiableList(toList())
