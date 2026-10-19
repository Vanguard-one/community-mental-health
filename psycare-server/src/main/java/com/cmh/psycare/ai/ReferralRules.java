package com.cmh.psycare.ai;

import java.util.List;

/**
 * 转介询问 / 应允的判定规则。**纯函数，不依赖 Spring、不访问数据库、不调模型。**
 *
 * <h3>为什么这是一段规则，而不是让模型自己判断</h3>
 * <p>「AI 问要不要推荐医生 → 用户答应 → 进入推荐流程」看起来像是模型能自行处理的事，
 * 但真正需要服务端裁决的是**下一步动作**：是否结束会话、是否跳转到推荐页。
 * 这类动作不能由模型的措辞决定 —— 同一个意思它可以有一百种说法，
 * 前端若靠关键词去猜「这句到底问没问」，就会重演「正文里出现 12356 就当危机」那类误判
 * （见 {@code ChatStore} 的历史注释）。
 *
 * <h3>判据是「一问一答」，不是单看用户说了什么</h3>
 * <p>只看用户输入的话，「好的」「嗯」「可以」这些词在任何一轮都可能是对普通问题的回应；
 * 只看 AI 输出的话，用户又可能只是随口附和。所以判定必须<b>同时</b>满足：
 * <ol>
 *   <li>AI 上一轮**问过**推荐（{@link #askedForReferral}）；并且</li>
 *   <li>用户本轮给了**肯定**答复（{@link #YES_WORDS}，且不含否定词）。</li>
 * </ol>
 * <p>唯一的例外是用户主动要医生（{@link #wantsDoctor}）：那时不必等 AI 先问 ——
 * 对方已经明确表达诉求，再让他多答一轮「需要我推荐吗」是纯粹的官僚。
 *
 * <h3>否定的优先级最高</h3>
 * <p>{@code "先不用了"} 里含「用」也含「不」；{@code "不要" } 里含「要」。
 * 中文的否定与肯定共享字形，所以 {@link #NO_WORDS} 必须先判，
 * 一旦命中直接否决，不再看后面任何肯定词。这个方向是**保守**的：
 * 该推荐而没推荐，用户还能继续聊；不该推荐却跳走了，他会莫名其妙地被推出对话。
 */
public final class ReferralRules {

    private ReferralRules() {
    }

    /**
     * AI 询问句的锚点。
     *
     * <p>与 {@link PromptAssets#INTERVIEW_FLOW} 第 3 阶段里要求模型使用的那句话**逐字一致**。
     * 改提示词必须同步改这里 —— 否则应允判定会失效，表现为「用户答了『好』却什么都没发生」，
     * 而这不会报任何错。
     *
     * <p>它只是<b>强</b>信号之一，不是唯一判据：模型未必每次都一字不差地照抄，
     * 所以 {@link #askedForReferral} 还留了一条宽松判据。
     */
    public static final String ASK_MARK = "需要我为你推荐合适的医生吗";

    /**
     * 用户主动要医生的表达。命中即视为「已经应允」，不要求 AI 先问。
     *
     * <p>刻意只收带「医生」或「推荐」的完整说法，不收「帮帮我」「怎么办」这类泛指 ——
     * 它们在任何一轮都会出现，把它们当转介意向会在对话刚开始就把人推去推荐页。
     */
    private static final List<String> WANT_DOCTOR = List.of(
            "推荐医生", "推荐个医生", "推荐一下医生", "医生推荐", "给我推荐", "帮我推荐",
            "介绍医生", "介绍个医生", "介绍一位医生", "找医生", "找个医生", "看医生",
            "要看医生", "想咨询医生", "咨询医生", "对接医生", "联系医生", "转介",
            "需要医生", "想找医生");

    /**
     * 肯定表达。
     *
     * <p>不收单字以外的口语变体（如「成」）—— 覆盖不全只是让用户多说一句，
     * 覆盖过宽会把无关附应判成应允，两个方向的代价不对称。
     */
    private static final List<String> YES_WORDS = List.of(
            "好", "可以", "行", "要", "需要", "是的", "对", "嗯", "麻烦", "谢谢",
            "拜托", "来吧", "推荐", "ok", "OK");

    /**
     * 否定表达，**优先于** {@link #YES_WORDS}。
     *
     * <p>中文的否定与肯定共享字形，这是这个词表存在的唯一理由：
     * 「不用」含「用」、「不要」含「要」、「不好」含「好」、「不行」含「行」、
     * 「不对」含「对」。不先判否定，一句「先不用了」会被读成同意。
     */
    private static final List<String> NO_WORDS = List.of(
            "不用", "不需要", "先不", "暂时不", "不用了", "不要", "不必", "算了", "先这样",
            "以后再说", "再看看", "先聊", "不推荐", "没这个打算",
            "不好", "不行", "不对", "不想", "没空", "不方便", "不愿意");

    /**
     * AI 上一轮是否问过「要不要推荐医生」。
     *
     * <p>两条判据取或：
     * <ol>
     *   <li>逐字命中 {@link #ASK_MARK} —— 模型的正常表现；</li>
     *   <li>宽松判据：同时出现「推荐」和「医生」，并且是个疑问句。
     *       模型可能改写成「要不要我帮你推荐几位医生？」「需要我给你推荐医生吗」，
     *       只认逐字会让这些同样正确的问法失去作用。</li>
     * </ol>
     *
     * <p>宽松判据里的「疑问句」检查（{@code 吗 / ？}）不是可选项：没有它，
     * 「我建议你去医院找医生看看，医生可能会推荐……」这种陈述句也会命中。
     */
    public static boolean askedForReferral(String lastAiText) {
        if (lastAiText == null || lastAiText.isBlank()) {
            return false;
        }
        if (lastAiText.contains(ASK_MARK)) {
            return true;
        }
        boolean hasIntent = lastAiText.contains("推荐") && lastAiText.contains("医生");
        boolean isQuestion = lastAiText.contains("吗") || lastAiText.contains("？")
                || lastAiText.contains("?");
        return hasIntent && isQuestion;
    }

    /** 用户是否在主动要医生（不依赖 AI 是否问过） */
    public static boolean wantsDoctor(String userInput) {
        return userInput != null && !userInput.isBlank() && containsAny(userInput, WANT_DOCTOR);
    }

    /**
     * 本轮用户输入是否构成「同意为你推荐医生」。
     *
     * @param lastAiText AI 上一轮的回复原文；会话刚开始（还没有 AI 回复）时为 null
     * @param userInput  本轮用户输入
     */
    public static boolean accepted(String lastAiText, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String input = userInput.trim();
        // 否定优先，见类注释
        if (containsAny(input, NO_WORDS)) {
            return false;
        }
        // 用户自己开口要医生 —— 这是最强信号，不必等 AI 先问
        if (wantsDoctor(input)) {
            return true;
        }
        // 一问一答：AI 问过 + 用户答了肯定
        return askedForReferral(lastAiText) && containsAny(input, YES_WORDS);
    }

    private static boolean containsAny(String text, List<String> words) {
        String lower = text.toLowerCase();
        for (String w : words) {
            if (lower.contains(w.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
