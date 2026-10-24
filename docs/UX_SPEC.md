# GHOST visual design system and screen contract

## Design intention

A calm, high-trust emergency utility, **not** a neon cyberpunk console. Crisp Material 3, legible typography, sparse layouts, clear status states and one unmistakable SOS action. The interface needs to look credible during a live demo and remain usable for tired or stressed people.

## Visual tokens (starting design choices; check contrast)

- Primary deep navy `#14273D` (navigation, headers)
- Accent teal `#087F8C` (connected / normal primary actions)
- Emergency red `#C52A36` (SOS and true urgent visual states only)
- Warning amber `#B76C09` (pending, not yet delivered)
- Surface warm-white `#FAFBFC`; card white `#FFFFFF`; text `#182431`; muted `#586574`
- Spacing grid 4/8/12/16/24/32 dp; min recommended tap target **48dp**; cards 16dp radius; restrained elevation
- Fonts: native readable sans with Material 3 typography scale; body >=14sp with support for system font scaling; avoid all caps on paragraphs
- Respect light/dark mode and system contrast; do not use color alone to express a message state.

## Nav structure

Four bottom destinations: **Home**, **Requests**, **Mission**, **Network**. Prominent primary `SEND SOS` CTA on Home, always visible and accessible. Standard peers show Mission as a transparent “Coordinator not active on this device” overview, not a fake agent.

### Home

- Hero status: `OFFLINE MESH ACTIVE · 2 PEERS` OR `NO NEARBY PEERS` OR `INTERNET GATEWAY AVAILABLE`.
- Primary emergency action: wide red `SEND SOS` surface; explanatory consent before sending any GPS/landmark.
- Three state chips: relay queue count, SOS pending count, last successful sync.
- “Recent events” with provenance: **queued**, **relay received**, **delivered to recipient**, **read**. Use text+icon labels.
- Battery/radio state visible but not alarmist.

### SOS form

- Select category: medical / trapped / water / food / shelter / other.
- Number of people optional; description; optional coarse area/landmark; explicit checkbox that public SOS may be visible to relay peers.
- Preflight confirmation: exact content, audience, location granularity; publish only on explicit Send.
- Show message ID, expiry, and honest delivery timeline afterward.

### Requests board

- Cards: need, approximate location, age, reported sender trust, current verified state, current/possible volunteer offers.
- Filters category, new/acknowledged/help offered/closed. Clear empty, loading, stale and error views.
- Volunteer can publish offer, withdraw it, accept/decline a specific proposal, without editing other people's offer records.

### Mission Control

- Only enabled for coordinator with model ready.
- Top: `LOCAL MODEL READY (OFFLINE)` or `MODEL LOADING` / `RULES ONLY` with no misrepresentation.
- Main panel: “What the agent observed → which tool it selected → what tool returned → what it proposed → waiting for approval”.
- Explain each match with freshness: reported resources, rough area, last update age. `Approve and send` and `Reject` distinct.
- Event trace is timeline, not raw tokens; details expansion can show sanitized input/output and event IDs for judges.
- On decline, show previous suggestion crossed as `DECLINED by offer owner` and next alternative as `NEW PROPOSAL`.

### Network

- Peers with stable short IDs, last seen, trust status, radio transport, connection status.
- Queued packets and relay log (A → B → C), receipt level; no fake visual maps or global topology inferred from hearsay.
- Permission health and button to retry discovery; foreground relaying status.

## Accessibility and smoothness checklist

- Support font scaling, screen readers/content descriptions, reduced motion, touch target sizes and logical focus order.
- Avoid endless shimmer and animations during unstable connectivity; simple 150-250ms transitions are enough if motion is enabled.
- No disk/network inference on main UI thread. Observe Room through ViewModels/StateFlow and render using `collectAsStateWithLifecycle`.
- Stable keys in lazy lists; keep list updates focused, debounce repeated Radio/Room events; use `@Preview` fixture states.
- Include previews for 4 states per screen: loaded, empty, loading/error, disconnected/stale.
- Verify real-device usability on a small phone and in dark mode. Screenshots at fixed configuration for consistent judges' demo.

## Design acceptance tests

No truncated SOS text at 200% font scale; offline indication always visible; queued/relay/delivered statuses visually and textually distinct; mandatory consent cannot be skipped; no accidental network sends from screen composition or re-render; user can leave/return to a form without losing typed text; buttons disabled while a duplicate action is processing.
