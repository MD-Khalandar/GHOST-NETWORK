package org.ghost.domain.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class NodeId(val value: String)

@Serializable
@JvmInline
value class PacketId(val value: String)

@Serializable
@JvmInline
value class EventId(val value: String)

@Serializable
@JvmInline
value class RequestId(val value: String)

@Serializable
@JvmInline
value class OfferId(val value: String)

@Serializable
@JvmInline
value class ProposalId(val value: String)
