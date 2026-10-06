# NeoForge 1.21.1 API and source notes

## Network work

The official [payload guide](https://docs.neoforged.net/docs/1.21.1/networking/payload/) establishes the registration event, codec/type pairing, handlers, and send helpers. Registration belongs on the mod bus. Register the appropriate direction with `playToServer` / `playToClient`; use a bidirectional registration only when that is the actual protocol.

`PayloadRegistrar` defaults to main-thread processing. When choosing `executesOn(HandlerThread.NETWORK)`, retain the returned registrar and schedule game-state work through the payload context. Validate C2S requests using the sender obtained from the connection; never trust a payload's claimed player, color, room membership, or result.

Keep paired encoders and decoders in the same protocol order. Bound text lengths, collection sizes, coordinates, and enum values at the network boundary. Preserve the negotiated network version; changing the wire format requires reviewing compatibility. For board snapshots, use the established room identity and move sequence to reject stale state.

The documented direction limits are at most 1 MiB clientbound and less than 32 KiB serverbound. Read [stream codecs](https://docs.neoforged.net/docs/1.21.1/networking/streamcodecs/) when serializing registry-aware values. Do not assume `ByteBuf`, `FriendlyByteBuf`, and `RegistryFriendlyByteBuf` codec signatures are interchangeable.

## Version-sensitive areas

| Area | Minecraft 1.21.1 NeoForge |
|---|---|
| Java output target | 21 |
| Metadata | `META-INF/neoforge.mods.toml` |
| Mod API namespace | `net.neoforged.*` |
| Resource ID construction | `ResourceLocation.fromNamespaceAndPath(...)` |
| Network | `CustomPacketPayload` + `StreamCodec` + `PayloadRegistrar` |
| Resource models | `assets/<modid>/models/item/`; inspect existing resources before adding assets |
| Item instance data | Data components; do not port old item NBT calls mechanically |
| Server data paths | Singular `recipe`, `loot_table`, `tags/block`, `tags/item` |

Verify changed signatures against the dependency actually selected by Gradle. Do not upgrade dependencies merely to make an example compile.

## Other primary references

- [Getting started](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [Side separation](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)
- [Events](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [Screens](https://docs.neoforged.net/docs/1.21.1/gui/screens/)
- [NeoForge 21.0 changes and migration context](https://neoforged.net/news/21.0release/)

## Provenance

Prepared on 2026-09-30 after searching third-party skills and cross-checking these official versioned sources. The third-party seed was [guguzea/MC-AI-Coding-Assistant-Tool: NeoForge 1.21.1 mc-networking](https://github.com/guguzea/MC-AI-Coding-Assistant-Tool/tree/main/neoforge/1.21.1/.agents/skills/mc-networking), downloaded with Codex's skill installer. Its networking guidance was adapted into a complete, independently named development skill. The upstream license is included in `../LICENSE`.

The original skill's custom frontmatter, repository-relative knowledge paths, and `search_neoforge_docs` MCP references were replaced with Codex-compatible metadata and direct official sources. No third-party MCP server is required. The nearby `minecraft-modding` skill in Jahrome907/minecraft-agent-skills was considered but its newer-version examples were not installed for this target.

SHA-256 of the downloaded upstream `SKILL.md`: `6BE686FE020CFABCA2CFFDFDB857BBAD5335B9856F96471D83B73188FB350EBB`.
