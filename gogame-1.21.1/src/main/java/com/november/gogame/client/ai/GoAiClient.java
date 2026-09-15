package com.november.gogame.client.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.gogame.GoGameMod;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 往 LLM 发一次<b>非流式</b>请求，把整份回复灌进 {@link AiTurn}。
 *
 * <p>抄本体附属 mcphone-deepseek 的 {@code DeepSeekClient} 骨架（实施计划 Phase 4 点名），
 * 两处不同：
 * <ul>
 *   <li><b>非流式</b>（{@code stream=false}）：围棋一手的回复就几十字符，没有逐字吐的必要，
 *       一次取回整份 JSON 更稳（兼容服务的 SSE 实现五花八门）；</li>
 *   <li><b>整请求 60s 超时</b>：聊天可以等几分钟，对局不行——超时即失败走兜底着，棋局不卡死。</li>
 * </ul>
 *
 * <p><b>线程</b>：请求与读响应体都在这里的守护线程池上（cached、会长——读体那步是阻塞的，
 * 池子固定大小会饿死 HttpClient 自己的协议任务），Minecraft 渲染线程一次都不会被挡住；
 * 玩家关游戏不该被一个没读完的回复卡在退出界面上，故守护线程。
 *
 * <p><b>这个类不碰棋盘、不碰界面</b>：只认识 AiTurn。着法怎么校验、怎么落，是主线程
 * {@link LocalAiGame#tick()} 的事。错误分类到翻译键（细节进日志）：几种错误的解法完全不同
 * （连不上看网络/地址、超时可重试、证书多半是代理或系统时间），都写成「网络错误」等于什么都没说。
 */
public final class GoAiClient {

    private GoAiClient() {}

    private static final Gson GSON = new Gson();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    /** 整请求超时：一手棋的回复不该等过一分钟 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 一次读取响应体的上限（成功/错误两条路径共用一次 readNBytes）。着法本身很短，但推理模型
     * 会在 body 里回传 reasoning_content，与 MAX_TOKENS 联动：4096 token 中文推理 ≈ 18KB UTF-8，
     * 再叠 JSON 转义与 content 字段，32KB 会在边界被静默截断（截断→解析失败→误判空回复） */
    private static final int MAX_REPLY_BODY = 128 * 1024;

    /** 回复上限：着法本身几十字符，但推理模型（deepseek-v4-pro 之类）把思考 token 也计入
     * max_tokens——闸太低额度全被推理吃光、content 剩空串（finish_reason=length），每手都被
     * 误判「回复里没有内容」。上限只是天花板：非推理模型实际只用几十字符，花费不会因此变大。
     * 调这个值时同步调 MAX_REPLY_BODY（推理内容也进 body，估算见其注释） */
    private static final int MAX_TOKENS = 4096;

    /** AI 回复体解析不出 content 的错误键。{@link LocalAiGame#tick()} 对此键做静默处理，需同步维护 */
    public static final String ERROR_KEY_EMPTY = "gogame.ai.error.empty";

    /** 一次读取 {@code /models} 响应体的上限：模型清单可能很长（有的服务上百个模型），给到 256KB */
    private static final int MAX_MODELS_BODY = 256 * 1024;
    /** 清单最多收多少个模型：再多界面也翻不动，且畸形响应可能塞爆内存 */
    private static final int MAX_MODELS = 200;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gogame-ai-http");
        t.setDaemon(true);
        return t;
    });

    /** 懒建：建一个 HttpClient 会起选择器线程，玩家没开 AI 对局就不该有 */
    private static volatile HttpClient http;

    private static HttpClient http() {
        HttpClient c = http;
        if (c != null) return c;

        synchronized (GoAiClient.class) {
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .executor(POOL)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return http;
        }
    }

    /**
     * 问一手棋。立刻返回，结果随后落进 AiTurn。
     *
     * @param system      人设 + 输出格式约束（{@link GoPromptBuilder#system}）
     * @param user        棋盘文本 + 局面说明（{@link GoPromptBuilder#user}，重试时含反馈）
     * @param temperature 难度档温度
     */
    public static AiTurn send(String system, String user, float temperature) {
        AiTurn turn = new AiTurn();
        AiConfig cfg = AiConfig.get();

        if (!cfg.hasApiKey()) {
            turn.fail("gogame.ai.error.no_key");
            return turn;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/chat/completions"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body(system, user, temperature, cfg), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception badUrl) {
            // 玩家在设置里填了个不成形的地址。URI.create 抛的 IllegalArgumentException
            // 跑不进下面的 exceptionally，这里必须自己兜住
            GoGameMod.LOGGER.warn("[GoGame] AI 地址不成形: {}", cfg.baseUrl());
            turn.fail("gogame.ai.error.bad_url");
            return turn;
        }

        // thenAcceptAsync(..., POOL) 而不是 thenAccept：后者在完成 future 的那条线程上跑
        // （实测落 ForkJoinPool.commonPool），读响应体那步是阻塞的，占公共池线程会拖累
        // 不相干的并行流。指名交给自己的 cached 池，阻塞几条都不要紧。
        http().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> consume(turn, response), POOL)
                .exceptionally(err -> {
                    turn.fail(networkKey(err));
                    GoGameMod.LOGGER.warn("[GoGame] AI 请求失败: {}", shortMessage(err));
                    return null;
                });

        return turn;
    }

    /**
     * 拉取当前地址+Key 可用的模型清单（OpenAI 兼容 {@code GET /models}）。立刻返回，结果随后落进 {@link ModelListTurn}。
     *
     * <p>与一手棋请求同一套基础设施（守护线程池 / 超时 / 错误分类到翻译键）；HTTP 线程不碰界面，
     * 主线程读 {@link ModelListTurn} 的 volatile 状态刷新列表。没填 Key/地址直接就地失败，不发请求。
     */
    public static ModelListTurn fetchModels() {
        ModelListTurn turn = new ModelListTurn();
        AiConfig cfg = AiConfig.get();

        if (!cfg.hasApiKey()) {
            turn.fail("gogame.ai.error.no_key");
            return turn;
        }
        if (cfg.baseUrl().isBlank()) {
            turn.fail("gogame.ai.error.bad_url");
            return turn;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl() + "/models"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .GET()
                    .build();
        } catch (Exception badUrl) {
            GoGameMod.LOGGER.warn("[GoGame] AI 地址不成形(models): {}", cfg.baseUrl());
            turn.fail("gogame.ai.error.bad_url");
            return turn;
        }

        http().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAcceptAsync(response -> consumeModels(turn, response), POOL)
                .exceptionally(err -> {
                    turn.fail(networkKey(err));
                    GoGameMod.LOGGER.warn("[GoGame] 拉取模型清单失败: {}", shortMessage(err));
                    return null;
                });

        return turn;
    }

    //  ——— 请求体 ———

    private static String body(String system, String user, float temperature, AiConfig cfg) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.addProperty("stream", false);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", MAX_TOKENS);

        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user);
        messages.add(usr);
        body.add("messages", messages);

        return GSON.toJson(body);
    }

    //  ——— 响应 ———

    private static void consume(AiTurn turn, HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            int status = response.statusCode();
            byte[] raw = in.readNBytes(MAX_REPLY_BODY);
            if (raw.length == MAX_REPLY_BODY) {
                GoGameMod.LOGGER.warn("[GoGame] AI 响应体达到上限 {} 字节，可能被截断", MAX_REPLY_BODY);
            }
            String text = new String(raw, StandardCharsets.UTF_8);

            if (status / 100 != 2) {
                failByStatus(turn, status, text);
                return;
            }

            String content = contentOf(text);
            if (content == null) {
                GoGameMod.LOGGER.warn("[GoGame] AI 回复里没有 choices[0].message.content: {}", truncate(text));
                turn.fail(ERROR_KEY_EMPTY);
                return;
            }
            turn.complete(content);

        } catch (Exception e) {
            turn.fail(networkKey(e));
            GoGameMod.LOGGER.warn("[GoGame] AI 请求失败: {}", shortMessage(e));
        }
    }

    /** 取 {@code choices[0].message.content}；结构不对/空白返回 null */
    private static String contentOf(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();

            // 有的兼容服务不改状态码，把错误塞进 200 的响应体里
            if (o.has("error")) return null;

            JsonElement choices = o.get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) return null;
            JsonElement first = choices.getAsJsonArray().get(0);
            if (!first.isJsonObject()) return null;
            JsonObject message = first.getAsJsonObject().getAsJsonObject("message");
            if (message == null) return null;

            JsonElement content = message.get("content");
            if (content == null || content.isJsonNull() || !content.isJsonPrimitive()) return null;
            String s = content.getAsString();
            return s.isBlank() ? null : s;
        } catch (Exception malformed) {
            return null;
        }
    }

    /** 读 {@code /models} 响应：非 2xx 归状态码错误键，2xx 解析 {@code data[].id}（空清单也走 DONE） */
    private static void consumeModels(ModelListTurn turn, HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            int status = response.statusCode();
            String text = new String(in.readNBytes(MAX_MODELS_BODY), StandardCharsets.UTF_8);

            if (status / 100 != 2) {
                GoGameMod.LOGGER.warn("[GoGame] 拉取模型清单失败：HTTP {} {}", status, messageOf(errorNode(text)));
                turn.fail(statusKey(status));
                return;
            }
            turn.complete(modelIds(text));

        } catch (Exception e) {
            turn.fail(networkKey(e));
            GoGameMod.LOGGER.warn("[GoGame] 拉取模型清单失败: {}", shortMessage(e));
        }
    }

    /** 取 {@code data[].id}（OpenAI 兼容清单格式）；结构不对/解析失败返回空表，最多 {@link #MAX_MODELS} 个 */
    private static List<String> modelIds(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) return List.of();
            JsonElement data = e.getAsJsonObject().get("data");
            if (data == null || !data.isJsonArray()) return List.of();

            List<String> ids = new ArrayList<>();
            for (JsonElement el : data.getAsJsonArray()) {
                if (ids.size() >= MAX_MODELS) break;
                if (!el.isJsonObject()) continue;
                JsonElement id = el.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive()) {
                    String s = id.getAsString();
                    if (!s.isBlank()) ids.add(s.strip());
                }
            }
            return ids;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    //  ——— 出错时说人话（键无参数：toast 通道只传键，细节进日志） ———

    private static void failByStatus(AiTurn turn, int status, String body) {
        GoGameMod.LOGGER.warn("[GoGame] AI 请求失败：HTTP {} {}", status, messageOf(errorNode(body)));
        turn.fail(statusKey(status));
    }

    /** HTTP 状态码归成玩家看得懂的错误翻译键（一手棋与拉模型清单两条路径共用） */
    private static String statusKey(int status) {
        return switch (status) {
            case 400, 422 -> "gogame.ai.error.bad_request";
            case 401 -> "gogame.ai.error.auth";
            case 402 -> "gogame.ai.error.balance";
            case 404 -> "gogame.ai.error.not_found";
            case 429 -> "gogame.ai.error.rate_limit";
            case 500, 502, 503, 504 -> "gogame.ai.error.server_busy";
            default -> "gogame.ai.error.http";
        };
    }

    /** 响应体里的 {"error":{"message":...}}，取不出来返回 null */
    private static JsonElement errorNode(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                return e.getAsJsonObject().get("error");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String messageOf(JsonElement error) {
        if (error != null && error.isJsonObject()) {
            JsonElement m = error.getAsJsonObject().get("message");
            if (m != null && m.isJsonPrimitive()) return truncate(m.getAsString());
        }
        if (error != null && error.isJsonPrimitive()) return truncate(error.getAsString());
        return "";
    }

    private static String truncate(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 160 ? one : one.substring(0, 160) + "…";
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** 把异常归成玩家看得懂的几类（解法各不相同，见类注释） */
    private static String networkKey(Throwable t) {
        Throwable e = unwrap(t);

        if (e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.SocketTimeoutException) {
            return "gogame.ai.error.timeout";
        }
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.nio.channels.UnresolvedAddressException) {
            return "gogame.ai.error.dns";
        }
        if (e instanceof java.net.ConnectException) {
            return "gogame.ai.error.connect";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "gogame.ai.error.tls";
        }
        return "gogame.ai.error.network";
    }

    private static String shortMessage(Throwable t) {
        Throwable e = unwrap(t);
        String m = e.getMessage();
        return truncate(m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
    }
}
