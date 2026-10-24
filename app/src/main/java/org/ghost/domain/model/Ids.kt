package org.ghost.domain.model

import kotlinx.serialization.Serializable

/** Stable public identity; never substitute a Nearby endpoint handle here. */
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

@Serializable
@JvmInline
value class ApprovalId(val value: String)

@Serializable
@JvmInline
value class AllocationId(val value: String)
