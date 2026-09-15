package com.november.gogame.client.ai;

import java.util.List;

/**
 * 一次「拉取模型清单」请求（{@code GET /models}）从发出到结束的全部状态 ——
 * HTTP 线程写，客户端主线程读，与 {@link AiTurn} 同一套线程隔离约定。
 *
 * <p>为什么另立一个类而不复用 {@link AiTurn}：AiTurn 装的是「一手棋的回复正文」（单个 String），
 * 这里装的是「一串模型 id」（List）。两者语义不同，硬塞进一个类会让读的一侧到处判类型。
 *
 * <p>结局只写一次：{@link #complete}/{@link #fail} 都只在 {@link State#LOADING} 时生效，
 * 收尾之后的噪声异常改不了这次请求的结果（同 AiTurn）。
 */
public final class ModelListTurn {

    public enum State {
        /** 请求发出去了，还没收到回应 */
        LOADING,
        /** 拿到了模型清单（可能为空——空清单也走 DONE，由界面提示「点刷新重试」） */
        DONE,
        /** 网络/HTTP/解析失败 */
        ERROR
    }

    private volatile State state = State.LOADING;
    private volatile List<String> models = List.of();
    private volatile String errorKey = "";

    // ——— 写的一侧：只有 HTTP 线程调（包级可见）———

    void complete(List<String> m) {
        if (state != State.LOADING) return;
        models = m == null ? List.of() : List.copyOf(m);
        state = State.DONE;
    }

    void fail(String key) {
        if (state != State.LOADING) return;
        errorKey = key == null ? "" : key;
        state = State.ERROR;
    }

    // ——— 读的一侧：只有客户端主线程调 ———

    public State state() { return state; }

    public boolean isRunning() { return state == State.LOADING; }

    /** DONE 时的模型 id 清单（不可变）；其余状态为空表 */
    public List<String> models() { return models; }

    /** ERROR 时的错误翻译键（无参数：细节进日志不进界面） */
    public String errorKey() { return errorKey; }
}
