---
name: neoforge-1-21-1-moddev
description: Develop, debug, review, or port Minecraft 1.21.1 NeoForge mods and MCphone addons. Use for Java 21, ModDevGradle, payload networking, client screens, registries, and mod metadata on this exact version; select the Forge skill for Minecraft 1.20.1.
metadata:
  minecraft: "1.21.1"
  loader: "NeoForge 21.1.x"
---

# NeoForge 1.21.1 mod development

## Confirm the target

Read the target project's `gradle.properties`, `build.gradle`, `settings.gradle`, metadata template, and applicable project instructions. Keep the existing wrapper, loader pin, mappings, mod ID, and runtime dependency ranges unless the task calls for changing them. Minecraft 1.21.1 compiles for Java 21; the JVM running Gradle is a separate choice governed by the wrapper. Parchment supplements Mojang names; Yarn examples are not interchangeable.

Use the exact [1.21.1 official documentation](https://docs.neoforged.net/docs/1.21.1/gettingstarted/) and the project's resolved source/JAR to check signatures. Examples for 1.21.4, 1.21.11, or 26.x need separate verification. In particular, do not add the later `assets/<modid>/items/` item-definition system to a 1.21.1 project.

## Work in the project's architecture

- Use `net.neoforged.*` imports, `META-INF/neoforge.mods.toml`, and the existing mod-constructor injection pattern. Register lifecycle/registry listeners on the mod bus and gameplay listeners on `NeoForge.EVENT_BUS` as appropriate for the event.
- Keep rendering, screens, input, and `Minecraft.getInstance()` behind a physical-client entry point. `Level.isClientSide` checks logical side and cannot protect client classes from dedicated-server class loading. A separate `@Mod(value = MODID, dist = Dist.CLIENT)` entry point is supported for this version.
- Register content through the project's deferred registers. Resource identifiers use `ResourceLocation.fromNamespaceAndPath`. Check the pinned API before copying registration examples.
- For a protocol change, read [networking and version notes](references/version-notes.md). Use `CustomPacketPayload.Type`, `StreamCodec`, `RegisterPayloadHandlersEvent`, and `PayloadRegistrar`; do not substitute Forge `SimpleChannel`.
- Keep game-state mutation on the correct game thread. Default payload handlers run on the main thread. HTTP workers should hand immutable results or controlled state carriers back to the client thread.
- Use `GuiGraphics` and the 1.21.1 `Screen` signatures. Drawing coordinates and input hit boxes must use the same GUI-scaled coordinate system. Preserve clipping and pose-stack balance. A standalone screen does not require an inventory menu.

## MCphone addons

Inspect the actual dependency JAR when its version differs from the local MCphone checkout. Implement `IPhoneApp` in client code, register it through `META-INF/services/com.november.mcphone.api.client.app.IPhoneApp`, and use the addon's own namespace. Follow the existing `IPhonePage` / `PhoneCanvas` layer for phone pages; use the existing full-screen flow when the project already has one.

Keep MCphone as an external dependency. Inspect the built JAR before release to ensure the dependency was not bundled. Declare client-only requirements with the metadata side appropriate to the implementation; do not infer that server gameplay arbitration requires phone rendering classes.

## Build, verify, and diagnose

Use the target directory's wrapper. Run a suitable build/test baseline before claiming it is healthy. An offline attempt is useful when dependencies are already cached; otherwise resolve the specific missing dependency through the normal build process. Do not delete caches or run `clean` as a routine first step.

For Gradle text filtering/expansion, explicitly set UTF-8 when templates contain non-ASCII text. A successful JAR build does not establish that its TOML parses or the mod loads. Inspect generated metadata and runtime logs when investigating discovery failures.

Run the tests that exercise the changed behavior. Use existing rule-engine tests for pure logic; validate client loading for SPI/UI changes and dedicated-server loading for side separation. Run game tests only when the project actually registers them. Launch the game when runtime validation is needed, and distinguish what was tested automatically from what still needs user interaction. Do not issue AI API calls with a user's key just to establish a build baseline.

Report the changed behavior, validation, and remaining limits. Keep existing license and publishing conventions; a development skill does not authorize release publication or an unrelated platform migration.
