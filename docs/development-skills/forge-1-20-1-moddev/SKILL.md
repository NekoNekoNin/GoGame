---
name: forge-1-20-1-moddev
description: Develop, debug, review, or port Minecraft 1.20.1 Forge mods and MCphone addons. Use for Java 17, ForgeGradle 6, SimpleChannel networking, client screens, registries, NBT, capabilities, and mods.toml on this exact version; select the NeoForge skill for Minecraft 1.21.1.
metadata:
  minecraft: "1.20.1"
  loader: "Forge 47.x"
---

# Forge 1.20.1 mod development

## Confirm the target

Read the target project's `gradle.properties`, `build.gradle`, `settings.gradle`, `META-INF/mods.toml`, and applicable project instructions. Forge 1.20.1 uses Java 17 output, ForgeGradle 6, and `net.minecraftforge.*` APIs. Use its own Gradle wrapper and dependencies; do not apply the NeoForge project's Gradle/JDK/metadata settings to it.

Use the exact [1.20.1 official documentation](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/) and the resolved source/JAR. Forge patch releases can affect mod-constructor injection. Preserve the project's proven entry point rather than replacing it with a tutorial's static context accessor or an unverified injected constructor. Treat Parchment as supplementary Mojang mapping information; do not copy Yarn names.

## Work in the project's architecture

- Register content with the project's `DeferredRegister` / `RegistryObject` pattern. Mod lifecycle and registry events use the mod bus; gameplay events use `MinecraftForge.EVENT_BUS`. Check which bus an event belongs to before wiring it.
- A physical-client gate prevents loading rendering and screen classes on a dedicated server. `Level.isClientSide` distinguishes logical sides, including the integrated server, and cannot by itself prevent client-class linkage. Prefer the project's established client initialization or guarded event subscribers. Do not add `@OnlyIn` to fix ordinary side separation.
- For network work, read [networking and version notes](references/version-notes.md). Use the existing `SimpleChannel`, `FriendlyByteBuf`, and `NetworkEvent.Context`. Do not introduce NeoForge's `PayloadRegistrar` or `StreamCodec` API into Forge 1.20.1.
- Keep world and game-state mutations on the appropriate game thread. HTTP callbacks must not access Minecraft rendering, UI state, or the world directly.
- Use the 1.20.1 `Screen` / `GuiGraphics` method signatures. Preserve GUI-scale-aware input, clipping, pose-stack balance, and screen lifecycle. A standalone game board can use its own packets rather than inventing an inventory menu.
- Item stack persistence uses this version's NBT APIs; entity/player data may use Forge capabilities. Do not mechanically substitute 1.21 data-component or NeoForge attachment code. Preserve existing save identifiers and migration behavior.

## Ports and MCphone addons

First identify the requested port's scope and the dependency artifact available for Forge 1.20.1. A NeoForge addon JAR and the NeoForge MCphone JAR cannot be reused as Forge runtime dependencies. Inspect the selected Forge MCphone API rather than assuming that the newer local checkout or another loader has the same methods.

Keep pure Java game rules separate from loader glue when practical, but Java 21 language/library features in shared code require adaptation for Java 17. Match the existing project organization; do not introduce an entire multiloader framework merely to port a small addon.

Register MCphone apps using its supported client-side `IPhoneApp` SPI and the addon's own namespace. Keep MCphone external to the addon JAR and preserve side separation. A skill covering Forge does not mean the current GoGame project already has a Forge implementation.

## Build, verify, and diagnose

Use the Forge target's wrapper and appropriate JVM/toolchain. Run a meaningful build/test baseline before claiming success. When a machine-wide Gradle setting forces an incompatible JDK, prefer a process/task-local override and explain it; do not rewrite global Java settings. An offline attempt can establish what the current cache supports.

Declare UTF-8 for non-ASCII resource filtering. Java properties files and UTF-8 TOML templates have different decoding rules; putting Chinese text into Gradle properties can corrupt it before `ProcessResources` runs. Prefer the project's established literal TOML description or explicit decoding.

Test the changed behavior using existing tests. Use dedicated-server loading to validate side separation, client loading for SPI/UI changes, and a two-client session when a protocol or authority change needs runtime coverage. Run game tests only when they exist. Separate a successful build from verified runtime behavior. Do not invoke paid AI APIs simply for build validation.

Keep version numbers, licenses, runtime ranges, save compatibility, and release conventions intact unless the task authorizes changing them. Report the verified behavior and any untested runtime steps; publication is a separate action.
