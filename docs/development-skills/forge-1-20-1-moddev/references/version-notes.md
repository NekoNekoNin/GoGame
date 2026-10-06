# Forge 1.20.1 API and source notes

## Network work

Follow the official [SimpleImpl guide](https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/) and the project's channel registration. Both endpoints must agree on protocol acceptance rules, packet IDs, and encoder/decoder order. Make registration deterministic and keep packet directions explicit where the selected Forge API supports them.

The encoder receives a packet and a `FriendlyByteBuf`; the decoder produces a packet from the buffer. A no-argument constructor is not a requirement when using a decoder factory or buffer constructor. Do not copy examples whose registered method names do not match the packet implementation.

Handlers accepting `Supplier<NetworkEvent.Context>` should obtain the context, enqueue game-state work through `enqueueWork`, and mark the packet handled with `setPacketHandled(true)`. For C2S messages, obtain the sender from the context and validate authorization on the server. For S2C messages, isolate client handler class loading and update the client's current session only.

Use `sendToServer` from the client and the channel's `send` / `PacketDistributor` path for server sends, following the installed Forge patch's signatures. Do not replace this with NeoForge static `PacketDistributor.sendToPlayer` calls.

Bound lengths and collection sizes before allocating. Validate coordinates and membership. If a packet accesses a world location, check the chunk is already loaded rather than allowing a client-supplied location to force chunk generation. Preserve result authority on the server.

## Version-sensitive areas

| Area | Minecraft 1.20.1 Forge |
|---|---|
| Java output target | 17 |
| Build plugin | ForgeGradle 6; preserve wrapper compatibility |
| Metadata | `META-INF/mods.toml` |
| Mod API namespace | `net.minecraftforge.*` |
| Resource ID construction | Existing `new ResourceLocation(namespace, path)` / parse pattern supported by this version |
| Network | `SimpleChannel`, `FriendlyByteBuf`, `NetworkEvent.Context` |
| Item stack data | NBT APIs of Minecraft 1.20.1 |
| Player/entity extension data | Forge capabilities where appropriate |
| Server data paths | Plural `recipes`, `loot_tables`, `tags/blocks`, `tags/items` |
| Item models | `assets/<modid>/models/item/`; no later item-definition directory |

Do not widen Forge patch compatibility based on compilation against only the newest pin. When changing the minimum supported Forge version, verify against that minimum or clearly state the validation gap.

## Other primary references

- [Getting started](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/)
- [Side separation](https://docs.minecraftforge.net/en/1.20.1/concepts/sides/)
- [Events](https://docs.minecraftforge.net/en/1.20.1/concepts/events/)
- [Screens](https://docs.minecraftforge.net/en/1.20.1/gui/screens/)
- [Capabilities](https://docs.minecraftforge.net/en/1.20.1/datastorage/capabilities/)

## Provenance

Prepared on 2026-09-30 after searching third-party skills and cross-checking these official versioned sources. The third-party seed was [guguzea/MC-AI-Coding-Assistant-Tool: Forge 1.20.1 mc-networking](https://github.com/guguzea/MC-AI-Coding-Assistant-Tool/tree/main/forge/1.20.1/.agents/skills/mc-networking), downloaded with Codex's skill installer. Its networking guidance was adapted into a complete, independently named development skill. The upstream license is included in `../LICENSE`.

The original skill's custom frontmatter, repository-relative knowledge paths, and `search_forge_docs` MCP references were replaced with Codex-compatible metadata and direct official sources. The packet-constructor requirement and incomplete example-method names were corrected against the official encoder/decoder contract. No third-party MCP server is required.

SHA-256 of the downloaded upstream `SKILL.md`: `3D091DEBFC596F4AB1FFFDD74907C2BE9920082BE5D94D92AD1FEB8DF3686241`.
