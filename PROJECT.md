# GHOST

Goal: ask for help during outages. A phone saves an SOS, nearby phones relay it,
and an online gateway uploads it. The server matches requests with helpers/supplies;
a web operator approves proposals, resource owners accept/decline, and signed
responses return through the mesh. Without internet, cloud coordination waits.

Stack: one Kotlin/Compose app with Room/Nearby, one Fastify server with
PostgreSQL/OpenAI, and a React/Vite dashboard. API and worker share one service.

Built: SOS forms/drafts and signed reports in Room, local-only saves, foreground
Nearby discovery/pairing, basic multi-hop SOS relay (four transfers), bounded retries and signed neighbor
receipts. Signing, cloud-key pinning, consent and server authentication also work.
Radio behavior and handset key persistence still need physical testing.

Next: durable per-neighbor relay delivery tracking, gateway sync → dashboard
and resource matching → approval, owner decisions and outage recovery.

Keep public/cloud consent separate, preserve signed bytes, reject forged/duplicate
events, and keep cloud/provider secrets server-side. Queued, received and confirmed are distinct.
Chat, local AI and LoRa wait.
