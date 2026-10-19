package com.cmh.psycare.ai;

/**
 * 引导问诊的三个阶段。
 *
 * <h3>为什么需要阶段机</h3>
 * <p>没有流程约束时，模型会退化成「百科全书式问答」：用户问什么就答什么，
 * 永远不会主动把话题推进到「影响」「风险」「就医建议」这些真正重要的维度 ——
 * 而这些恰恰是初筛要采集的信息。阶段机把「该问什么」从模型的自由发挥
 * 变成服务端可控的状态推进。
 *
 * <h3>为什么从五段压到三段</h3>
 * <p>原设计是「建立联结 → 主诉澄清 → 功能影响 → 风险筛查 → 小结转介」，
 * 每段至少一轮，走到转介要约 5 轮问答。对社区居民（尤其是第一次接触心理服务的人）
 * 来说这太长：初筛的目标是<b>尽快给出一条可执行的去向</b>，
 * 而不是把信息采集得尽可能完整。压缩后最多 3 个提问就进入建议转介，
 * 用户拿得到医生推荐，信息缺口由后续就医时补充。
 *
 * <p>被砍掉的两个阶段不是被否定，而是<b>并入</b>：
 * <ul>
 *   <li>「功能影响」的四项（睡眠/食欲/工作学习/人际）合并进「主诉澄清」的追问要点，
 *       仍在 {@link StageRules} 的字段词表里，也仍会写进提示词；</li>
 *   <li>「风险筛查」不再单独占一轮 —— 它改为安全边界的一部分（见
 *       {@link PromptAssets#SAFETY_BOUNDARY} 第 3 条）：一旦出现相关表述立即处置，
 *       不出现就不专门追问。理由：危机识别的第一道防线是本地词表短路
 *       （{@code CrisisDetector#hit}），靠「问一句」并不能提高召回，
 *       却会让每个用户都被问一次「你有没有想过伤害自己」，代价与收益不成比例。</li>
 * </ul>
 *
 * <h3>为什么用枚举 ordinal 表示先后顺序</h3>
 * <p>阶段是<b>有序</b>的，{@link #ordinal()} 天然表达顺序，
 * 判断「是否已越过某阶段」不必再维护一张顺序表。代价是不能随意调整枚举顺序 ——
 * 这一点由 {@link StageRules} 的自检守住。
 *
 * <p>⚠️ {@link #ENDED} 不是流程阶段，只是「会话已关闭」的终止态，
 * 因此它排在最后但不计入 {@link #TOTAL_STEPS}。
 */
public enum InterviewStage {

    /** 1 · 共情、确认来意、说明角色边界 */
    OPENING("建立联结", "共情、确认来意，问一个最关键的问题"),

    /** 2 · 表现 / 起始时间 / 频率 / 强度 + 睡眠、食欲、工作学习、人际的影响 */
    CHIEF_COMPLAINT("主诉澄清", "围绕困扰追问一到两个关键点，把程度与对生活的影响问清楚"),

    /** 3 · 复述理解 + 建议 + 转介（含「要不要我为你推荐医生」的询问） */
    SUMMARY_REFERRAL("建议转介", "复述理解到的情况、给出建议，并询问是否需要为你推荐医生"),

    /** 会话关闭引发的终止态，不属于三个流程阶段 */
    ENDED("已结束", "会话已结束");

    private final String label;
    private final String goal;

    InterviewStage(String label, String goal) {
        this.label = label;
        this.goal = goal;
    }

    /** 中文阶段名，用于前端指示条与提示词 */
    public String getLabel() {
        return label;
    }

    /** 本阶段的目标，写进提示词告诉模型「这一轮该问什么」 */
    public String getGoal() {
        return goal;
    }

    /**
     * 展示用的阶段序号（1–3）。
     *
     * <p>{@link #ENDED} 返回 {@link #TOTAL_STEPS} + 1，表示「已越过全部阶段」；
     * 前端拿到它时指示条整条点亮，不会出现越界下标。
     */
    public int getStep() {
        return ordinal() + 1;
    }

    /** 流程阶段总数，前端指示条按它算比例 */
    public static final int TOTAL_STEPS = 3;

    /** 当前阶段是否已经越过 / 等于给定阶段 —— 用 ordinal 表达顺序 */
    public boolean isAtLeast(InterviewStage other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * 容错解析。Redis 里存的是枚举名，历史数据或手工改动可能写入未知值 ——
     * 解析失败时退回 OPENING 而不是抛异常：丢一次阶段状态顶多让引导重来一轮，
     * 而抛异常会让整个会话直接不可用。
     *
     * <p>⚠️ 老数据里可能残留已删除的 {@code FUNCTION_IMPACT} / {@code RISK_SCREENING}：
     * 它们会被解析成 OPENING。这是刻意的保守方向 —— 重新走一遍引导，
     * 比把用户直接扔进「建议转介」更安全（后者会在还没采集任何信息时就给出推荐）。
     */
    public static InterviewStage of(String name) {
        if (name == null || name.isBlank()) {
            return OPENING;
        }
        for (InterviewStage s : values()) {
            if (s.name().equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        return OPENING;
    }
}
