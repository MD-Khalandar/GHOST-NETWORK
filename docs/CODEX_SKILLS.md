# Codex skills for GHOST

Updated 24 October 2026. Current scope: SOS over Android mesh with one cloud coordinator. Follow the 10 checkpoints and assigned team-prompt ownership; chat and local LLM helpers are deferred.

## Project skills

Six skills live in `.agents/skills/`:

| Skill | Purpose |
|---|---|
| `ghost-offline-data` | Room, repositories, durable queues, Flow and restart consistency |
| `ghost-ui-quality` | SOS/Mission UI, accessibility, consent and honest states |
| `ghost-mesh-reliability` | Nearby transport, routing, retries and receipt evidence |
| `ghost-agent-safety` | Cloud decisions/tools, consent, human authority and idempotency |
| `ghost-demo-qa` | Integration checks and physical-phone acceptance evidence |
| `ui-ux-pro-max` | Visual design research and Compose guidance |

The five GHOST skills cover distinct project contracts. UI/UX Pro Max complements emergency UX checks; use Compose guidance rather than web framework examples.

## Google Android plugin

Installed with the requested commands:

```sh
codex plugin marketplace add android/skills
codex plugin add android-skills@android-skills
```

Installed version: `1.0.17`. Three bundled skills remain enabled:

- `litert-lm`: local text model integration, deferred for this phase.
- `edge-to-edge`: system bars and keyboard insets.
- `jetpack-compose-performance`: observed Compose performance issues.

The other 30 bundled workflows are disabled using `[[skills.config]]` entries in `~/.codex/config.toml`. They remain packaged in the plugin so it stays intact. The configuration backup is `~/.codex/config.toml.before-android-skill-trim`. These settings apply to this local Codex installation. After upgrading the plugin, review the overrides and newly added skills because paths reference the installed version.

Removed three duplicate Google skill directories and their obsolete `upstream-lock.json` from the repository. The plugin owns those upstream files and licenses. Recovery copies are in `/tmp/ghost-duplicate-skills-20261024`; temporary storage is not permanent archival storage.

Removed six unrequested UI/UX installer companion skills: `banner-design`, `brand`, `design`, `design-system`, `slides`, and `ui-styling`.

UI/UX Pro Max was installed with `npx --yes ui-ux-pro-max-cli init --ai codex`. Restart Codex to load the new skill set and enablement settings. See [Codex skill configuration](https://learn.chatgpt.com/docs/build-skills).

The installation record below predates the architecture correction. Installing skills does not establish physical mesh, inference or accessibility evidence. Read `AGENTS.md` and the current project contracts before applying upstream examples.

Verification: six project skill entrypoints exist; all 33 Android plugin entrypoints exist, with exactly 30 disabled by config and three retained. `codex plugin list --json` confirmed version 1.0.17 installed and enabled. Skill selection in a restarted IDE session remains unverified. App builds were not run for this skill-only configuration change.
