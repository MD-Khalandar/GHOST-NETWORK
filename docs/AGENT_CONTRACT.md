# Local Agent Contract v1 — offline, bounded, auditable

## Purpose

An offline AI coordinator reasons about **reported** SOS needs and resource offers under intermittent mesh connectivity. Its job is to propose possible assistance workflows, not autonomously dispatch people or claim rescue. The agent runs **only on coordinator Phone C**, using an actual pre-provisioned local model when supported.

## Execution model

```text
signed incoming event -> verified Room insert -> projection updated
  -> deterministic TriggerDetector -> agentRun persisted
  -> small bounded local snapshot -> LiteRT-LM.generateAction()
  -> parse JSON to AgentDecision (untrusted)
  -> SchemaValidator + ToolPolicy + authorization
  -> Kotlin tool executes -> observation written to agentRun
  -> next model action (<=4 steps) OR final proposed-match card
  -> human approves outgoing message
  -> signed publish() -> receipt tracking
```

Do NOT claim native SDK function calling: if the chosen LiteRT-LM text runtime only generates text, use a structured-output **format contract** and Kotlin validation to implement the agent loop. Test whether the model reliably complies. When invalid, retry at most once with an error explanation, then report `FAILED_VALIDATION` and make a deterministic recommendation if appropriate.

## Allowed actions and who can authorize them

| Tool | Operation | Side effect | Model may invoke |
|---|---|---|---|
| `read_sos_board` | Returns bounded current SOS reports | Read only | Yes |
| `find_offers` | Returns fresh offers matching resource and area | Read only | Yes |
| `check_offer_status` | Returns owner-signed status, age and availability | Read only | Yes |
| `get_delivery_status` | Returns observed signed receipts | Read only | Yes |
| `propose_match` | Creates a local *draft* proposal | Draft only | Yes, after Kotlin validation |
| `request_clarification` | Creates draft clarification request | Draft only | Yes, operator must send |
| `send_help_offer` | Publishes message onto mesh | Network side effect | **Human action only** |
| `mark_resolved` | Changes request status | User consequence | **Requester/authorized human only** |
| `reserve_resource` | Commits supplies | User consequence | **Owner confirmation only** |

All tools use typed Kotlin data; return `ToolResult` with `ok/error`, `provenance`, `last_seen`, and `uncertainty`. Tool methods **never** interpret free-form LLM code, shell, SQL or arbitrary network URLs.

## Structured model output

An example single decision returned as model text:

```json
{
  "schema_version": 1,
  "action": "find_offers",
  "arguments": {"resource_type":"WATER", "coarse_area":"Sector 4"},
  "reason": "The request needs water and no offer has been evaluated."
}
```

Allowed `action` enum: `read_sos_board`, `find_offers`, `check_offer_status`, `get_delivery_status`, `propose_match`, `request_clarification`, `finish`. Unknown actions, arbitrary `target_node` IDs not in the known snapshot, oversize strings or malicious role instructions are rejected. `reason` is explanatory text, *not* authority.

## Run state machine

`NEW -> RUNNING -> WAITING_FOR_HUMAN -> WAITING_FOR_REMOTE -> REPLAN_PENDING -> RUNNING -> COMPLETE`.

Alternative terminal states: `CANCELLED`, `MODEL_UNAVAILABLE`, `FAILED_VALIDATION`, `TIMED_OUT`, `NO_FEASIBLE_MATCH`. Persist runs and pending proposals across app restart.

Run trigger: verified `SOS_CREATE`, `OFFER_PUBLISH`, `MATCH_DECLINED`, or owner-signed significant offer update. Debounce duplicate events by `(entity ID, projection version)`; store deterministic idempotency keys so replayed declines don't generate duplicate outbound proposals. Use a single-thread coordinator work queue. Maximum four model decisions per run, finite prompt length, finite tool results, cancellation.

## Resource matching policy

The LLM can interpret vague text and choose queries; **deterministic Kotlin code** enforces category compatibility, owner signature, stale/expired offer removal, location granularity, remaining quantity where supplied, request status, and duplicate proposal checks. If quantity absent or location unknown, the agent must ask for clarification or describe uncertainty, not assert the match will work. Never calculate ungrounded safe routes or medical triage grades.

## Adversarial inputs

SOS, volunteer notes and bulletin text are untrusted. If any field says “ignore your prior instructions,” retain it only as quoted *reported content*, not instructions. Scrub raw identifiers, precise coordinates and phone numbers from prompts unless essential and explicitly consented to. Test instruction injection, false official claims, duplicate events, oversized text, stale offer, revoked offer, missing human approval, unsupported model and model crash.

## Hardware / model / runtime realities

- Candidate model: `Qwen3-0.6B.litertlm` for first compatibility probe. Compare with `gemma-4-E2B-it.litertlm` only on a device with enough headroom. Hardware varies; no model is guaranteed to follow structured tool decisions reliably.
- Pre-download model outside the APK, verify file integrity, and run all demo inference **after disconnecting from internet**. Do not call cloud APIs silently.
- LiteRT-LM engine initialization is blocking; do it on a single dedicated background worker, keep engine/conversation owned by that worker and close safely.
- Track device model name, model hash/version, latency, parse failure rate, correct tool-choice rate, crash/memory footprint. A local model output is not proof a tool was invoked; show the validated tool execution trace.
- If model unavailable, show **Rule-Based Assistance Only**. Never call it an offline LLM, autonomous agent or successful model tool call.

## Success criterion for an agentic demo

1. Phone C processes SOS from A through B with all internet disabled.
2. **Actual local model output** selects `find_offers`; Kotlin searches persisted offers.
3. Agent generates a candidate match and waits for coordinator confirmation.
4. Phone B's offer owner declines. Receipt/event arrives on C.
5. Coordinator model **actually reruns** and chooses a different valid offer; the UI shows old and new proposal versions.
6. User approves; a signed result returns to requester A through B; show end-recipient receipt, not just relay acknowledgement.
7. Demonstrate failures: no offers, stale offer, or model unavailable; no misleading success badge.
