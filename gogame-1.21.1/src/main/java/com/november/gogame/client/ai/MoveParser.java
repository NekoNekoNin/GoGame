package com.november.gogame.client.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.gogame.common.rules.Move;
import com.november.gogame.common.rules.Stone;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 LLM 的回复解析成一手棋。
 *
 * <p>prompt 里虽然写了「只回 JSON」，模型并不总听话，故三层宽容（解析失败返回 null，
 * 由 {@link LocalAiGame} 带反馈重试或随机兜底）：
 * <ol>
 *   <li>{@code {"move":"Q16"}} / {@code {"move":"pass"}}——首选，prompt 要求的格式；</li>
 *   <li>文本里第一个独立出现的 GTP 坐标（如「I play Q16 because…」里的 Q16）；</li>
 *   <li>文本里出现独立的 pass 一词（如「I pass.」）。</li>
 * </ol>
 *
 * <p>坐标解析复用 {@link Move#fromGtp}（列跳过 I、行 1–19 自下而上、越界即抛），
 * 本类只负责「从自由文本里把那个 token 找出来」。<b>AI 不允许认输</b>：即便模型
 * 回了 {@code resign}（prompt 并未授权）也当作解析失败，交回 {@link LocalAiGame} 重试/兜底。
 * 纯静态无状态，可脱机单测。
 */
public final class MoveParser {

    private MoveParser() {}

    /**
     * GTP 坐标（文本层）：列字母 A–H / J–T（跳过 I）+ 行 1–19，须独立成词。
     *
     * <p><b>只认大写</b>：大小写不敏感会把英文散文里的两字词（at/be/hi/do…）误认成坐标，
     * 而 JSON 层的小写坐标（{@code {"move":"q16"}}）已由 {@link Move#fromGtp} 自己容忍，
     * 不需要文本层兼顾。
     */
    private static final Pattern GTP = Pattern.compile("\\b([A-HJ-T])(1[0-9]|[1-9])\\b");

    /** pass 关键词（第三层）：带词边界，免得 surpass/compass/passive 这类普通词误触虚着 */
    private static final Pattern PASS = Pattern.compile("\\bpass\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 解析回复。
     *
     * @param content LLM 回复全文
     * @param color   AI 执色（解析出的着法挂这个色）
     * @return 一手棋；三层都解析不出返回 {@code null}
     */
    public static Move parse(String content, Stone color) {
        if (content == null || content.isBlank()) return null;

        String jsonMove = jsonMoveOf(content);
        if (jsonMove != null) {
            Move m = fromToken(jsonMove, color);
            if (m != null) return m;
        }

        Matcher m = GTP.matcher(content);
        if (m.find()) {
            Move move = fromToken(m.group(), color);
            if (move != null) return move;
        }

        if (PASS.matcher(content).find()) return Move.pass(color);
        return null;
    }

    /** 找回复里第一个 {@code {...}} 片段取 {@code move} 字段；不是 JSON / 没该键返回 null */
    private static String jsonMoveOf(String content) {
        int from = content.indexOf('{');
        int to = content.lastIndexOf('}');
        if (from < 0 || to <= from) return null;

        try {
            JsonElement e = JsonParser.parseString(content.substring(from, to + 1));
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("move")) return null;
            JsonElement mv = o.get("move");
            return (mv == null || mv.isJsonNull() || !mv.isJsonPrimitive()) ? null : mv.getAsString();
        } catch (Exception malformed) {
            return null;
        }
    }

    private static Move fromToken(String token, Stone color) {
        try {
            Move m = Move.fromGtp(color, token);
            // AI 不认输：resign 视作解析失败（返回 null），交给 LocalAiGame 重试/随机兜底
            return m.isResign() ? null : m;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
