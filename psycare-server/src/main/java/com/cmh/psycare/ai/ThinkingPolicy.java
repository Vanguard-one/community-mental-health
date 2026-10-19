package com.cmh.psycare.ai;

import com.cmh.psycare.common.enums.RiskLevel;

import java.util.List;

/**
 * 本轮要不要让模型「思考」。
 *
 * <h3>为什么这一个布尔值值得单开一个类</h3>
 * <p>它是本系统首帧延迟的唯一有效杠杆。真库 {@code ai_usage_log} 81 条记录回归：
 * <pre>
 *   首帧 ≈ 532 ms + 6.2 ms × 思考token     corr = 0.968
 *   corr(首帧, prompt_tokens) = 0.192
 *   corr(首帧, cached_tokens) = 0.016
 * </pre>
 * 而 532 ms 的固定项里，光公网往返就占掉了 450~800 ms（实测复用连接的最小请求），
 * <b>压缩提示词、提高缓存命中都没有用</b>；把思考关掉，首帧从 2.0~2.7 s 掉到 0.6~0.9 s。
 *
 * <h3>判据为什么是「风险等级 + 引导阶段」这两个</h3>
 * <p>它们有一个共同的关键性质：<b>在调用模型之前就已经知道，不需要任何额外请求</b>。
 * 风险等级来自会话关联的测评（本轮本来就要查那一行），阶段来自 Redis 里的
 * {@link StageState}（在 {@code chatStream} 的第一行就读完了）。所以这个决策是
 * 零成本的确定性分流，而不是「先问一次模型该不该思考」那种自相矛盾的做法。
 *
 * <h3>为什么不全局关掉</h3>
 * <p>危机语境实测（AI 已问「有没有想过伤害自己」，用户答「晚上会想睡过去不醒，但我妈还在」）：
 * <ul>
 *   <li>开思考（首帧 14.6 s）：分层追问 + 给出 120/110/12356 热线 +「今晚别独处、危险物品交人保管」</li>
 *   <li>关思考（首帧 1.0 s）：共情到位，但<b>一条热线、一句安全兜底都没有</b></li>
 * </ul>
 * 安全出口的产出恰好落在思考过程里。所以策略是「默认关、风险条件开回来」，
 * 而不是一刀切 —— 见 {@link LlmProperties#getThinking()}。
 *
 * <h3>无测评背景时不会误判</h3>
 * <p>{@code riskLevel} 传 {@code null} 表示「这次对话不是从测评发起的」，
 * 此处按「不构成风险条件」处理，<b>不会</b>把 null 当成 {@link RiskLevel#A} 或当成高危。
 * 与 {@code ContextAssembler} 里「没有测评就不写风险那一行」是同一条原则：
 * 缺失的数据不能被当成一个具体取值。
 */
public final class ThinkingPolicy {

    private ThinkingPolicy() {
        // 纯策略类，无状态、无依赖，不需要实例
    }

    /**
     * 一次决策的结果。
     *
     * <p>带上 {@code reason} 是为了让日志能直接回答「这一轮为什么快 / 为什么慢」。
     * 没有它，验收时只能看到首帧时长的数字，猜不出是哪条规则命中了。
     *
     * @param enabled 是否开启思考
     * @param reason  人类可读的判据说明，直接进日志
     */
    public record Decision(boolean enabled, String reason) {
    }

    /**
     * 决定本轮是否开启思考。
     *
     * @param props     配置（模式与两个例外清单）
     * @param stage     当前引导阶段；可为 null
     * @param riskLevel 会话关联测评的风险等级；**为 null 表示没有测评背景**
     */
    public static Decision decide(LlmProperties props, InterviewStage stage, RiskLevel riskLevel) {
        String mode = props.resolvedThinking();

        // 两个显式模式优先，且不看任何条件 —— 「我就要全开/全关」不该被例外清单推翻
        if ("enabled".equals(mode)) {
            return new Decision(true, "配置 thinking=enabled（全部开启，改造前行为）");
        }
        if ("disabled".equals(mode)) {
            return new Decision(false, "配置 thinking=disabled（全部关闭，最速）");
        }

        // ---- auto：默认关，命中风险条件才开 ----
        if (riskLevel != null && contains(props.getThinkingRiskLevels(), riskLevel.name())) {
            return new Decision(true, "风险等级 " + riskLevel.name()
                    + "（" + riskLevel.getDesc() + "）命中例外清单");
        }
        if (stage != null && contains(props.getThinkingStages(), stage.name())) {
            return new Decision(true, "引导阶段 " + stage.name()
                    + "（" + stage.getLabel() + "）命中例外清单");
        }
        return new Decision(false, "auto 且未命中任何风险条件（阶段="
                + (stage == null ? "无" : stage.name())
                + "，风险等级=" + (riskLevel == null ? "无测评背景" : riskLevel.name()) + "）");
    }

    /**
     * 清单命中判断。
     *
     * <p>大小写不敏感、两侧去空白：yml 里写成 {@code c,d} 或 {@code C, D} 都能用。
     * 配置值是手写的，为一个空格让规则静默失效是最不值得的失败方式。
     */
    private static boolean contains(List<String> list, String value) {
        if (list == null || list.isEmpty() || value == null) {
            return false;
        }
        for (String item : list) {
            if (item != null && item.trim().equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }
}
