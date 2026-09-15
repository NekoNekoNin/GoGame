package com.november.gogame.client.ai;

/**
 * 一次 AI 请求从发出到结束的全部状态 —— HTTP 线程写，客户端主线程读（两条线程唯一的接触面）。
 *
 * <p>抄本体附属 mcphone-deepseek 的 {@code Turn} 模式并简化：围棋一手是<b>非流式</b>请求
 * （回复就几十字符，一次取回整份 JSON），没有逐字增长的正文，故一个 volatile String 足够，
 * 不需要 StringBuffer。HTTP 线程<b>不</b>碰棋盘、不碰界面、不碰 I18n——错误只记翻译键，
 * 等主线程要显示时再翻译（I18n 属于客户端语言管理器，别的线程上问它会撞资源包重载）。
 *
 * <p>结局只写一次：{@link #complete}/{@link #fail} 都只在 CONNECTING 时生效，收尾之后的
 * 噪声异常改不了这次请求的结果。
 */
public final class AiTurn {

    public enum State {
        /** 请求发出去了，还没收到回应 */
        CONNECTING,
        /** 拿到了完整回复正文 */
        DONE,
        /** 网络/HTTP/解析失败 */
        ERROR
    }

    private volatile State state = State.CONNECTING;
    private volatile String content = "";
    private volatile String errorKey = "";

    // ——— 写的一侧：只有 HTTP 线程调（包级可见）———

    void complete(String c) {
        if (state != State.CONNECTING) return;
        content = c == null ? "" : c;
        state = State.DONE;
    }

    void fail(String key) {
        if (state != State.CONNECTING) return;
        errorKey = key == null ? "" : key;
        state = State.ERROR;
    }

    // ——— 读的一侧：只有客户端主线程调 ———

    public State state() { return state; }

    public boolean isRunning() { return state == State.CONNECTING; }

    /** DONE 时的完整回复正文；其余状态为空串 */
    public String content() { return content; }

    /** ERROR 时的错误翻译键（无参数：缓存 toast 通道只传键，细节进日志不进界面） */
    public String errorKey() { return errorKey; }
}
