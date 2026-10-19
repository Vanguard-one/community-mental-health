package com.cmh.psycare.engine;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 危机词本地检测（AI 问诊与测评文本双端共用）。
 *
 * <p>关键原则：危机识别<b>必须用本地规则</b>，不能依赖大模型自觉。
 * 词表可后续迁移到数据库 / 配置中心，由管理端维护。
 *
 * <h3>⚠️ 输入侧与输出侧必须用两套判据，不能共用一个 {@link #hit}</h3>
 * <p>两侧的目标是<b>相反</b>的，混用会造成一个必然发生的严重误报：
 *
 * <table border="1">
 *   <caption>两侧判据对比</caption>
 *   <tr><th></th><th>检测对象</th><th>判据</th><th>口径</th></tr>
 *   <tr>
 *     <td>{@link #hit}</td><td>居民自己说的话</td><td>危机词出现</td><td><b>宁宽</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@link #harmful}</td><td>模型生成的回复</td><td>危机词与操作性词同窗出现</td><td><b>宁窄</b></td>
 *   </tr>
 * </table>
 *
 * <p><b>输入侧宁宽</b>：多提示一次热线，代价是一句多余的话；漏掉一次，代价可能是一条命。
 * 所以只要出现危机词就短路，不问语境、不看人称。
 *
 * <p><b>输出侧宁窄</b>：system prompt 的 L2 第 3 条<b>明确要求</b>模型在风险语境下
 * 建议拨打 12356，L4 第 4 阶段还要求它主动询问是否有伤害自己的念头。
 * 也就是说「模型说出自杀/热线」正是<b>期望行为</b>——用 {@link #hit} 判输出，
 * 会把每一段合规回复都替换成固定文案，用户看到的是：聊得好好的，AI 突然浑身通红地
 * 说「已为你转接人工」。这不是安全，这是故障。
 *
 * <p>输出侧真正要拦的是<b>可操作的自伤指引与教唆</b>（方法、步骤、剂量），
 * 因此要求危机词与操作性词在同一个短窗口内共现 —— 只说「不要一个人扛着，
 * 可以打 12356」不会命中，而「吃多少片能……」会命中。
 */
@Component
public class CrisisDetector {

    /** 危机关键词（自杀/自伤相关）。词表应定期扩充。 */
    private static final List<String> CRISIS_WORDS = List.of(
            "自杀", "想死", "不想活", "活不下去", "轻生", "自残", "自我了断",
            "结束生命", "一了百了", "跳楼", "割腕", "没有活下去的意义",
            "活着没意思", "不想活了", "告别这个世界", "自杀倾向"
    );

    /**
     * 输出侧的操作性词：与危机词组合起来才构成「提供指引」。
     *
     * <p>刻意<b>不收</b>「怎么」「如何」「为什么」这类疑问副词 ——
     * 它们会与「你怎么会有这种念头」这类共情问句共现，是高频误报源。
     * 只收能构成「教方法」的名词与量化词。
     */
    private static final List<String> HARM_METHOD_WORDS = List.of(
            "方法", "方式", "步骤", "教程", "指南", "操作", "流程",
            "剂量", "致死量", "几片", "几颗", "多少片", "多少颗", "吃多少", "吞多少", "多少药");

    /** 危机词前后各看多少个字符找操作性词 */
    private static final int HARM_WINDOW = 10;

    /**
     * 居民输入侧检测：文本是否命中危机词。
     *
     * <p>口径宽是有意的，见类注释。命中后调用方应走
     * {@code AiConsultService.crisisStream}：不调模型、直接给固定文案并建 CRISIS 工单。
     */
    public boolean hit(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return CRISIS_WORDS.stream().anyMatch(text::contains);
    }

    /**
     * 模型输出侧检测：回复是否在提供可操作的自伤指引或教唆。
     *
     * <p>判据是「危机词 + 操作性词在 {@value #HARM_WINDOW} 字符窗口内共现」。
     * 命中后应整段替换为 {@link #safetyNotice()}。
     *
     * <p>这是<b>护栏</b>而不是主检测：一个按 L2 约束好的模型几乎不会走到这里。
     * 它存在的意义是「模型被越狱 / 提示词注入成功」时的最后一道兜底。
     */
    public boolean harmful(String aiOutput) {
        if (aiOutput == null || aiOutput.isBlank()) {
            return false;
        }
        for (String word : CRISIS_WORDS) {
            int from = 0;
            int idx;
            while ((idx = aiOutput.indexOf(word, from)) >= 0) {
                int start = Math.max(0, idx - HARM_WINDOW);
                int end = Math.min(aiOutput.length(), idx + word.length() + HARM_WINDOW);
                String window = aiOutput.substring(start, end);
                if (HARM_METHOD_WORDS.stream().anyMatch(window::contains)) {
                    return true;
                }
                from = idx + word.length();
            }
        }
        return false;
    }

    /**
     * 输入侧命中危机时给居民的固定回应（不交给模型生成）。
     *
     * <p>之所以敢承诺「已为你转接人工」：这条路径会真的建一张 CRISIS 工单，
     * 有医生在工单列表里接手。文案与事实一致才有意义。
     */
    public String crisisResponse() {
        return "我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n"
                + "如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n"
                + "全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n"
                + "同时我已为你转接人工，稍后会有专业人员与你联系。";
    }

    /**
     * 输出侧命中时的固定文案。
     *
     * <p>与 {@link #crisisResponse()} 只差一句：<b>不承诺转接人工</b>。
     * 输出侧命中意味着「模型自己说错了话」，此时居民的输入未必构成危机
     * （例如他只是在问一个知识性问题），因此<b>不该</b>为他建危机工单 ——
     * 凭模型的一次失言就给人贴上危机标签，是另一种伤害。
     * 既然没有工单在流转，就不能说「已为你转接」：一个不会兑现的承诺，
     * 在心理场景里比不承诺更伤人。
     *
     * <p>给的人工出口是「我的消息」（居民可自主挑医生），这与真实能力一致。
     */
    public String safetyNotice() {
        return "我注意到你可能正经历非常艰难的时刻。请一定照顾好自己。\n"
                + "如果你此刻有伤害自己的念头，请立即拨打心理援助热线：\n"
                + "全国 24 小时心理援助热线 12356；北京心理危机研究与干预中心 010-82951332。\n"
                + "也可以随时在「我的消息」里找医生聊一聊。";
    }
}
