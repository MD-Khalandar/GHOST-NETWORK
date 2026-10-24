# SOS phase screen contract

Build the SOS phase of GHOST now. Ordinary conversations, generic internet queries and local-model helpers remain later work. Use Compose Material 3 and honest local/mesh/cloud states.

## Navigation

Four destinations: **Home**, **Requests**, **Mission**, **Network**. SOS compose opens from Home/Requests. Mission shows the single cloud coordinator's activity; it is not a model running on the handset.

| Screen | Required behavior |
| --- | --- |
| Home | Prominent Send SOS; local queue, nearby peers and cloud reachability shown separately |
| SOS form | Medical/trapped/water/food/shelter/other; description, optional people count and coarse location. Preview exact content/audience, public sharing and separate cloud-coordination consent |
| Requests / details | Locally known reports, source/age, pending cloud sync, cloud acknowledgements, proposal and actual recipient state |
| Offers | Owner publish/update/withdraw, cloud consent, accept/decline exact proposal version; acceptance stays pending until cloud confirmation |
| Mission | Compact read-only coordination status, proposal/version and approved outcome. Keep explanations behind an explicit disclosure; detailed run/tool history and operator approval belong in the web dashboard |
| Network | Permission/radio state, observed peers/relay path, queue/expiry, gateway sharing toggle, last cloud sync and retry |

A handset's local role toggle cannot grant approval authority. For the demo the operator uses the web dashboard on a computer or C's browser. Phone owners accept/decline their own offers in the app.

## Cloud web dashboard

An authenticated browser UI in `dashboard/`, using the server API and shared cloud records. Build incoming SOS visibility before advanced agent views.

| View | Required behavior |
| --- | --- |
| Incoming SOS | Persisted incoming requests with category, coarse area, reported/received time and state; bounded filters/pagination and visible last refresh |
| Request detail | Original reported content, source/freshness, cloud status, proposed assistance and actual receipt evidence |
| Helpers and supplies | Owner-provided helper name or short identity, help types, resource quantity/unit where known, availability/expiry and last update; no inferred verification badges |
| Agent activity | Actual job/model/tool history, draft/version, uncertainty and waiting-for-approval state |
| Operator actions | Approve/reject exact proposal version through authenticated role-checked API; stale data causes revalidation rather than automatic approval |

Start with one incoming requests board and a request detail view containing offers, approval and actual agent history. Use polling and clear loading/empty/error/stale states. A report queued on an offline phone is not an incoming cloud record. Dashboard controls cannot silently edit owner supplies, confirm receipt or close someone else's SOS. Keep keyboard navigation, accessible labels, readable contrast and narrow-screen layouts usable. Do not require the dashboard to create or send a phone's SOS.

## States and consent

- No gateway/cloud means “Waiting for internet gateway” or “Cloud unavailable.” No phone-local AI fallback is implied.
- Distinguish saved/queued, neighbor send, relay observed, cloud accepted, awaiting approval, proposal sent, phone receipt and allocation confirmed. HTTP success is not help offered or delivered.
- Public mesh sharing and cloud processing are separate choices. Explain that declining cloud consent leaves the report available for local/manual help only. Precise location requires explicit opt-in.
- Until E2E encryption is implemented/tested, disclose relay visibility; do not claim signatures make content private.
- Show current reported source/age and uncertainty; a central coordinator still lacks unsynchronized reports.
- Preserve drafts on error/navigation, prevent duplicate sends, and expose empty/loading/stale/failed/permission-denied/service-unavailable states.
- Dashboard trace shows actual model-selected tool, validated result, draft and human approval. Phone Mission summarizes status and proposal version from the same records. Mark fixtures simulated; do not display cloud activity from a fake as live.
- If approval or acceptance becomes stale while disconnected, require revalidation rather than showing success.

## Visual and implementation baseline

Retain Material 3 tokens: navy `#14273D`, teal `#087F8C`, SOS red `#C52A36`, pending amber `#B76C09`, light surface `#FAFBFC`. Verify semantic light/dark contrast.

Use 4/8dp spacing, 48dp touch targets, screen-reader labels/focus, 200% font support, reduced motion and keyboard/system-bar clearance. Screens observe repository Flow through ViewModels; keep I/O/model execution out of composition. Supply loaded/empty/loading-error/disconnected previews.

Verify U01–U06 in [TEST_MATRIX.md](TEST_MATRIX.md) on physical phones. Previews do not prove accessibility or delivery.
