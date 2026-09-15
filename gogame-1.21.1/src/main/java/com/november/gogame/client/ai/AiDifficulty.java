package com.november.gogame.client.ai;

/**
 * AI 难度十一档：菜鸟 / 普通人 / 初段–九段（用户指定）。
 *
 * <p>强度阶梯三个旋钮（用户确认方案「人设 + 温度 + 失误扰动」）：
 * <ul>
 *   <li><b>人设 prompt</b>（{@link #persona()}）：告诉模型「你是几段」，英文写死——
 *       prompt 语言不跟客户端语言走，四家预设模型对英文指令的服从度最稳；</li>
 *   <li><b>温度</b>（{@link #temperature()}）：弱档高（着法更随机），段位档逐档递减；</li>
 *   <li><b>失误率</b>（{@link #blunderRate()}）：客户端扰动——按该概率无视 LLM 的着法、
 *       改落随机合法着。只给菜鸟/普通人（保证弱档稳定地弱，不全凭模型自觉）；
 *       段位档零扰动，棋力完全交给模型。</li>
 * </ul>
 *
 * <p>LLM 的真实棋力远达不到段位含义（实施计划「已知风险」：不追求 AI 强度），
 * 档位是「可感知的强度阶梯」而非真实等级分承诺。
 */
public enum AiDifficulty {

    NOVICE("gogame.ai.diff.novice", 1.0f, 0.50,
            "a complete beginner who has just learned the rules and often plays slow, aimless or outright bad moves"),
    AVERAGE("gogame.ai.diff.average", 0.9f, 0.18,
            "an average casual player who knows basic tactics but makes frequent mistakes"),
    DAN1("gogame.ai.diff.dan1", 0.80f, 0.0,
            "a 1 dan Go player with solid fundamentals and basic joseki knowledge"),
    DAN2("gogame.ai.diff.dan2", 0.76f, 0.0,
            "a 2 dan Go player with good tactical vision"),
    DAN3("gogame.ai.diff.dan3", 0.73f, 0.0,
            "a 3 dan Go player who balances territory and influence well"),
    DAN4("gogame.ai.diff.dan4", 0.69f, 0.0,
            "a 4 dan Go player with strong fighting skills"),
    DAN5("gogame.ai.diff.dan5", 0.65f, 0.0,
            "a 5 dan Go player with deep strategic understanding"),
    DAN6("gogame.ai.diff.dan6", 0.61f, 0.0,
            "a 6 dan Go player with precise endgame technique"),
    DAN7("gogame.ai.diff.dan7", 0.58f, 0.0,
            "a 7 dan Go player with near-perfect whole-board judgment"),
    DAN8("gogame.ai.diff.dan8", 0.54f, 0.0,
            "an 8 dan Go player, master of attack and defense timing"),
    DAN9("gogame.ai.diff.dan9", 0.50f, 0.0,
            "a 9 dan top professional Go player; always play the strongest move you can find");

    private final String langKey;
    private final float temperature;
    private final double blunderRate;
    private final String persona;

    AiDifficulty(String langKey, float temperature, double blunderRate, String persona) {
        this.langKey = langKey;
        this.temperature = temperature;
        this.blunderRate = blunderRate;
        this.persona = persona;
    }

    /** 显示名翻译键（渲染线程翻译） */
    public String langKey() { return langKey; }

    /** 请求温度：弱档高、段位档递减 */
    public float temperature() { return temperature; }

    /** 失误率：按该概率无视 LLM 着法改落随机合法着；段位档恒 0 */
    public double blunderRate() { return blunderRate; }

    /** 英文人设描述，拼进 system prompt */
    public String persona() { return persona; }
}
