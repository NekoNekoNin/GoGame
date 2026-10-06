# GoGame development context

## Project and scope

`gogame-1.21.1/` is the active GoGame addon project: Minecraft 1.21.1, NeoForge, and the MCphone client-side app SPI. Its initial Phase 0–5 implementation is recorded as complete. Read `docs/PROJECT_LOG.md`, `docs/PITFALLS.md`, and `docs/TECH_STACK.md` as needed, and confirm historical claims against current code and fresh validation.

`mcphone/` and `mcphone-deepseek/` are separate, ignored reference checkouts. Their current source can be newer than GoGame's dependency in `gogame-1.21.1/libs/`. For addon API compatibility, the actual dependency JAR is authoritative. Read the reference project's own applicable instructions before changing that project.

The user requested development skills for both NeoForge 1.21.1 and Forge 1.20.1. A Forge skill is available for work on that target; GoGame currently has no Forge implementation. Choose the platform required by the actual task, and do not report a port complete merely because its skill is installed.

## Development skills

Load the relevant version's skill before version-specific code work. These project copies also serve as the source for personal Codex skill installation:

- NeoForge 1.21.1: `docs/development-skills/neoforge-1-21-1-moddev/SKILL.md`
- Forge 1.20.1: `docs/development-skills/forge-1-20-1-moddev/SKILL.md`

The skills require no additional MCP server. Prefer exact-version official documentation and the project's resolved source/JAR for signatures. Keep the two loaders' metadata, network APIs, resource paths, and Java targets distinct.

## GoGame invariants and validation

- Keep PVP server arbitration and client-only UI/AI loading separate. PVE reuses the same pure Java rules and existing client-state/UI flow.
- The supported board is 19×19, with the existing Chinese area-scoring and komi conventions. Preserve established gameplay unless a requested change affects them.
- Keep MCphone external to the addon JAR. The version and compile dependency are selected in `gogame-1.21.1/gradle.properties` and `libs/`.
- Use the wrapper from `gogame-1.21.1/` for builds and existing unit tests. Java output is 21; the wrapper's runtime JVM is separate. Prefer a process-local JVM choice over changing machine-wide settings.
- Preserve UTF-8 for metadata template expansion. Existing uncommitted changes at handoff add `filteringCharset = 'UTF-8'` and document the corresponding failure; do not discard them.
- Build and automated tests establish different facts from client/server runtime validation. Report which was performed and do not contact a user's paid AI endpoint merely to test the build.
- Update `docs/PROJECT_LOG.md` for substantive changes and `docs/PITFALLS.md` when a new failure and its resolution are worth retaining.
