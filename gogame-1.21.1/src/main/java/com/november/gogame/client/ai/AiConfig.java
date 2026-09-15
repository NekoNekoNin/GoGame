package com.november.gogame.client.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.gogame.GoGameMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

/**
 * 这台机器上的 AI 对弈设置 —— API Key、服务地址、模型、提供方、难度档。
 *
 * <p>抄本体附属 mcphone-deepseek 的 {@code DeepSeekConfig} 模式（实施计划 Phase 4 点名）：
 * 不用 NeoForge 的 ModConfigSpec——那套是给「启动前在文本编辑器里改」设计的，而这里每一项
 * 都要能在手机屏幕上当场改完当场生效。代价是模组列表的「配置」按钮点进去是空的，取舍清醒。
 *
 * <p><b>按服务方各记一套</b>（用户决策）：{@link Slot} 为每个 {@link AiProvider} 各存一份
 * Key/地址/模型，切服务方是「整组切换」——切回来仍在，无需为每家重粘一次 Key。难度档是
 * 全局玩法设置，不分服务方。
 *
 * <p><b>Key 是明文</b>，与范本同一立场：模组没有系统钥匙串可用，自己「加密」只是让人误以为
 * 安全。做的三件实事照旧——界面只显示末四位、日志一个字不写、除所选服务地址外不发给任何地方。
 *
 * <p><b>落盘</b>：先写 .tmp 再整体挪过去（原子移动不支持时退普通覆盖），最坏丢最后一次改动，
 * 不把已有设置写成半个文件。读坏/读不到一律回默认值，不让界面去防。
 */
public final class AiConfig {

    // 用 FMLPaths.CONFIGDIR 而非相对路径 "config/…"：后者依赖 JVM 工作目录=游戏目录，
    // 自定义 --gameDir / 部分第三方启动器下会落到非预期位置（设置持久化漂移）
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("gogame/config.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 每个服务方各自记住的一套 Key/地址/模型（用户决策：按服务方各记一套）。切服务方即整组
     * 切换、互不覆盖——不用再为每家重粘一次 Key。用枚举名作 JSON key，Gson 直接读写。
     */
    private Map<AiProvider, Slot> slots = new EnumMap<>(AiProvider.class);
    private AiProvider provider = AiProvider.DEEPSEEK;
    private AiDifficulty difficulty = AiDifficulty.AVERAGE;

    // 旧版把这三项平铺在顶层；保留字段只为读入老 config.json 时迁移进 slots，迁移后置 null。
    // Gson 默认不写 null → 下次 save 它们自然从文件消失，完成一次性迁移。
    private String apiKey;
    private String baseUrl;
    private String model;

    private static AiConfig instance;

    public static AiConfig get() {
        if (instance == null) {
            AiConfig loaded = read();
            instance = loaded == null ? new AiConfig() : loaded.sanitized();
        }
        return instance;
    }

    /**
     * 手改过的 json 里什么都可能有（含拼错的枚举名）。把值拉回合法区间，别让界面去防；
     * 顺带完成「旧版平铺 Key/地址/模型 → 按服务方分槽」的一次性迁移。
     */
    private AiConfig sanitized() {
        if (provider == null) provider = AiProvider.DEEPSEEK;
        if (difficulty == null) difficulty = AiDifficulty.AVERAGE;
        if (slots == null) slots = new EnumMap<>(AiProvider.class);
        slots.keySet().removeIf(k -> k == null);   // 手改塞了坏枚举名 → 丢弃该项，不连累整份配置

        // 迁移：老版本只存一套平铺值，且 switchProvider 会重置地址/模型，故平铺值只属于「最后所选的那家」。
        // 灌进当时 provider 的槽即可；其余四家迁移后为空槽，首次切入时由 slot(p) 懒建为各自预设默认。
        if (apiKey != null || baseUrl != null || model != null) {
            Slot s = slot(provider);
            if (apiKey != null && s.apiKey.isEmpty()) s.apiKey = apiKey.strip();
            if (baseUrl != null && !baseUrl.isBlank() && s.baseUrl.isEmpty()) s.baseUrl = baseUrl.strip();
            if (model != null && !model.isBlank() && s.model.isEmpty()) s.model = model.strip();
            apiKey = null;
            baseUrl = null;
            model = null;
        }

        // 逐槽把值拉回合法区间（空地址/模型回该服务方默认；null 槽兜底重建）
        for (Map.Entry<AiProvider, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            if (s == null) e.setValue(new Slot(e.getKey()));
            else s.normalize(e.getKey());
        }

        // Gson 按声明类型 Map 反序列化出的是 LinkedHashMap；重建为 EnumMap 让遍历/落盘都经枚举声明序，
        // 人手改 config.json 时槽的排列稳定好读（O1）。null key 已在上面 removeIf 掉，putAll 不会 NPE。
        EnumMap<AiProvider, Slot> ordered = new EnumMap<>(AiProvider.class);
        ordered.putAll(slots);
        slots = ordered;
        return this;
    }

    private static AiConfig read() {
        if (!Files.isRegularFile(FILE)) return null;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, AiConfig.class);
        } catch (Exception e) {
            GoGameMod.LOGGER.warn("[GoGame] 读不了 {}，当作没有", FILE, e);
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            GoGameMod.LOGGER.error("[GoGame] 写不了 {}", FILE, e);
        }
    }

    // ------------------------------------------------------------------
    // 访问器（setter 一律即时落盘：手机里改完就该生效）
    // ------------------------------------------------------------------

    /** 当前服务方的槽（不存在则懒建为其预设默认）——所有 Key/地址/模型访问都委托到它 */
    private Slot current() { return slot(provider); }

    private Slot slot(AiProvider p) { return slots.computeIfAbsent(p, Slot::new); }

    public String apiKey() { return current().apiKey; }

    public boolean hasApiKey() { return !apiKey().isBlank(); }

    public void setApiKey(String s) {
        current().apiKey = s == null ? "" : s.strip();
        save();
    }

    /**
     * 给界面看的 Key：只留末四位。不显示前缀、不按真实长度补点——固定六个点够玩家
     * 确认「我填过了」，不告诉旁边看屏幕的人这串东西有多长（范本同一立场）。
     */
    public String maskedApiKey() {
        String k = apiKey().strip();
        if (k.isEmpty()) return "";
        return k.length() <= 4 ? "••••" : "••••••" + k.substring(k.length() - 4);
    }

    public AiProvider provider() { return provider; }

    /**
     * 切服务方：整组切到目标服务方<b>各自记住</b>的 Key/地址/模型（从没配过则回其预设默认）。
     * 不再重置——这是「按服务方各记一套」的核心：切回来还在，无需重粘 Key。
     */
    public void switchProvider(AiProvider p) {
        if (p == null || p == provider) return;
        provider = p;
        slot(p);   // 确保目标槽存在（懒建为预设默认），访问器随后读到的就是这一套
        save();
    }

    /** 末尾斜杠去掉，拼路径时才不会出现 https://x//chat/completions */
    public String baseUrl() {
        String b = current().baseUrl.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public void setBaseUrl(String s) {
        current().baseUrl = (s == null || s.isBlank()) ? provider.defaultBaseUrl() : s.strip();
        save();
    }

    public String model() { return current().model; }

    public void setModel(String s) {
        if (s != null && !s.isBlank()) {
            current().model = s.strip();
            save();
        }
    }

    public AiDifficulty difficulty() { return difficulty; }

    public void setDifficulty(AiDifficulty d) {
        if (d == null || d == difficulty) return;
        difficulty = d;
        save();
    }

    /**
     * 一个服务方各自记住的一套 Key/地址/模型。字段包级可见，由外层 {@link AiConfig} 直接读写。
     * 无参构造给 Gson 反序列化用；带 {@link AiProvider} 的构造把地址/模型初始化为该服务方预设默认。
     */
    private static final class Slot {
        String apiKey = "";
        String baseUrl = "";
        String model = "";

        Slot() {}

        Slot(AiProvider p) {
            this.baseUrl = p.defaultBaseUrl();
            this.model = p.defaultModel();
        }

        /** 把值拉回合法区间：空地址/模型回该服务方默认（CUSTOM 默认本就是空串，须玩家自填） */
        void normalize(AiProvider p) {
            if (apiKey == null) apiKey = "";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = p.defaultBaseUrl();
            if (model == null || model.isBlank()) model = p.defaultModel();
        }
    }
}
