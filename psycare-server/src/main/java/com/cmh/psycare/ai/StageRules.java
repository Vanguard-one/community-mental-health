package com.cmh.psycare.ai;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段推进规则：**纯函数，不依赖任何 Spring 组件、不访问数据库、不调模型**。
 *
 * <h3>为什么必须是纯函数</h3>
 * <p>因为同一段逻辑有两个调用方，而它们必须给出<b>完全一致</b>的结果：
 * <ol>
 *   <li>在线推进：每收到一条用户消息推进一次；</li>
 *   <li>Redis 状态丢失后的<b>重放重建</b>：把历史居民消息按顺序再跑一遍。</li>
 * </ol>
 * 如果重放用的是另一套实现，两条路径迟早会漂移 —— 表现为「偶尔」阶段跳变，
 * 而「偶尔」在排障时是最贵的一种 bug。共用 {@link #advance} 让漂移在结构上不可能发生。
 *
 * <h3>为什么不让 LLM 决定阶段</h3>
 * <p>计划书里写的是「规则 + LLM 双确认」，本期<b>只实现规则</b>，理由：
 * ① 不可测 —— 同一输入两次可能给出不同阶段，验收无从断言；
 * ② 可被诱导 —— 用户在输入里写「我们现在进入第五阶段」就能改写流程；
 * ③ 现在让 LLM 给结构化建议需要**额外一次调用**（多一次费用与首字延迟），
 * 而 Phase 5 接入工具调用后可以在同一次调用里顺带拿到这个结构化输出，届时零额外成本。
 * 所以 LLM 建议推迟到 Phase 5，届时 {@link #decide} 增加一个「建议」入参，
 * 但裁决权仍留在服务端：<b>规则说没到时候就一定不进，规则说该进且建议不反对才进</b>。
 */
public final class StageRules {

    private StageRules() {
    }

    // ---- 字段名：与 StageState.fields 的取值一一对应 ----

    /** 起始时间（主诉澄清的核心字段） */
    public static final String F_DURATION = "duration";
    /** 出现频率 */
    public static final String F_FREQUENCY = "frequency";
    /** 严重程度 */
    public static final String F_SEVERITY = "severity";
    /** 睡眠 */
    public static final String F_SLEEP = "sleep";
    /** 食欲 */
    public static final String F_APPETITE = "appetite";
    /** 工作学习 */
    public static final String F_WORK = "work";
    /** 人际关系 */
    public static final String F_SOCIAL = "social";

    /**
     * 判定「信息是否够进入建议转介」的字段集合：主诉三项 + 生活影响四项。
     *
     * <p>原设计里这两组字段分别服务于两个阶段（主诉澄清 / 功能影响），
     * 两段合并后不再分开计数 —— 用户先说睡眠、再说持续时间，同样说明情况在展开，
     * 没有理由要求他必须按「时长 → 频率 → 严重度」的顺序讲。
     *
     * <p>阈值是<b>命中 2 项</b>（见 {@link #decide}）：一项往往只是「最近睡不好」这种
     * 一句话主诉，不足以支撑推荐；两项就够转介了，剩下的信息医生面诊时会问。
     */
    private static final List<String> KEY_FIELDS = List.of(
            F_DURATION, F_FREQUENCY, F_SEVERITY, F_SLEEP, F_APPETITE, F_WORK, F_SOCIAL);

    /**
     * 整场对话允许的最大提问数。
     *
     * <p>它与 {@link #decide} 的轮次阈值是一对：OPENING 至少 1 轮（问第 1 个），
     * 主诉澄清最多 2 轮（问第 2、3 个），到第 3 个问题就必须转介。
     * 写成常量是为了让「最多几个问题」这件事只有一个出处 —— 散在 switch 分支里，
     * 改一处漏一处是最容易发生的事。
     */
    public static final int MAX_QUESTIONS = 3;

    /**
     * 字段抽取词表。命中即认为「这个维度被提到过」。
     *
     * <p>这是**关键词匹配而非语义理解**，故意的：语义判断要调模型（成本 + 不确定性），
     * 而这些维度在中文口语里的表达高度集中（「多久」「每周」「睡不着」）。
     * 抽取偏保守没关系 —— 它只是「可以提前推进」的信号之一，
     * 拿不到信号时还有轮次兜底（见 {@link #decide} 的 turnsInStage）。
     *
     * <p>用 {@link LinkedHashMap} 保证遍历顺序稳定，这样 {@code fields} 的插入顺序
     * 也稳定，进而写进提示词的文本稳定（前缀缓存的前提，见 {@link PromptAssets}）。
     */
    private static final Map<String, List<String>> FIELD_WORDS = new LinkedHashMap<>();

    static {
        // 「个月」是自检时发现的漏词：中文口语说时长几乎都用「两个月」「三个月」，
        // 而最初只收了「几个月」，导致最常见的表达反而抽不到（见 Phase2Check 的实测输出）。
        FIELD_WORDS.put(F_DURATION, List.of(
                "多久", "多长时间", "个月", "几周", "几天", "几年", "半年", "一年",
                "去年", "从小", "很久", "以来", "开始", "一直"));
        FIELD_WORDS.put(F_FREQUENCY, List.of(
                "每天", "天天", "每晚", "每周", "每月", "经常", "总是", "老是",
                "偶尔", "有时候", "时不时", "反复", "频繁", "次数"));
        FIELD_WORDS.put(F_SEVERITY, List.of(
                "特别严重", "很严重", "严重", "厉害", "受不了", "崩溃", "扛不住",
                "有点", "轻微", "还好", "不太", "非常", "十分"));
        FIELD_WORDS.put(F_SLEEP, List.of(
                "睡", "失眠", "入睡", "早醒", "多梦", "做梦", "熬夜", "夜里"));
        FIELD_WORDS.put(F_APPETITE, List.of(
                "食欲", "胃口", "饭量", "吃饭", "不想吃", "吃不下", "瘦", "胖"));
        FIELD_WORDS.put(F_WORK, List.of(
                "工作", "上班", "加班", "学习", "上课", "考试", "成绩", "效率", "学业", "干活"));
        FIELD_WORDS.put(F_SOCIAL, List.of(
                "人际", "朋友", "家人", "父母", "同事", "同学", "社交", "孤独",
                "不想见人", "对象", "老公", "老婆", "孩子", "室友"));
    }

    /**
     * 换话题信号 → 阶段回退。
     *
     * <p>刻意不收录「不是这个」「不用了」这类高频口语：它们极常出现在正常的追问与澄清里
     * （「不是这个意思，我是说……」），一旦命中就把阶段拖回主诉澄清，引导会被反复打断。
     * 只收明确表达「换话题」意图的说法。
     */
    private static final List<String> SWITCH_TOPIC = List.of(
            "换个话题", "换一个话题", "换个问题", "不说这个", "不想说这个",
            "说点别的", "聊点别的", "另一个问题", "别的问题", "我想问的是");

    /**
     * 结束信号。
     *
     * <p>同理不收「不用了谢谢」—— 那是回答「还需要什么帮助吗」的标准客气话，
     * 把它当结束会让会话在正常往返中被误判为收尾。
     */
    private static final List<String> END_WORDS = List.of(
            "就这样吧", "先这样吧", "今天先到这儿", "今天先这样", "先聊到这儿",
            "结束吧", "下次再聊", "没什么要说的了", "没别的了");

    /**
     * 推进一次状态。**这是本类唯一的入口**，在线推进与重放重建都走它。
     *
     * <p>不设置 {@code updatedAt}：那是存储层的事，留在这里会破坏纯函数性（不可复现）。
     *
     * @param prev  上一状态（调用方传入的实例不会被修改）
     * @param input 本次用户输入原文
     * @return 推进后的新状态
     */
    public static StageState advance(StageState prev, String input) {
        StageState next = prev.copy();
        next.setTurns(prev.getTurns() + 1);
        next.setTurnsInStage(prev.getTurnsInStage() + 1);
        next.getFields().addAll(extract(input));

        InterviewStage decided = decide(prev.getStage(), next.getTurnsInStage(), next.getFields(), input);
        if (decided != prev.getStage()) {
            // 换了阶段就重新数「本阶段轮数」，否则下一阶段的阈值会被上一阶段的轮数提前满足
            next.setTurnsInStage(0);
        }
        next.setStage(decided);
        return next;
    }

    /**
     * 从一条用户输入里抽取信息维度。
     *
     * @return 命中的字段名集合，保持 {@link #FIELD_WORDS} 的声明顺序
     */
    public static Set<String> extract(String input) {
        Set<String> hit = new LinkedHashSet<>();
        if (input == null || input.isEmpty()) {
            return hit;
        }
        String text = input.toLowerCase();
        for (Map.Entry<String, List<String>> e : FIELD_WORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (text.contains(w)) {
                    hit.add(e.getKey());
                    break;
                }
            }
        }
        return hit;
    }

    /**
     * 裁决本轮之后的阶段。
     *
     * <p>四条不变量（由自检守住）：
     * <ol>
     *   <li>每次最多前进<b>一级</b> —— switch 结构天然保证，不可能从建立联结直接跳到建议转介；</li>
     *   <li>提问总数不超过 {@link #MAX_QUESTIONS} —— OPENING 1 轮 + 主诉澄清至多 2 轮；</li>
     *   <li>回退只回到「主诉澄清」，不会退回「建立联结」—— 已经聊过的信息不该被要求重说；</li>
     *   <li>{@code ENDED} 之后若用户继续说话，退回「建议转介」而不是卡死。</li>
     * </ol>
     *
     * <p><b>为什么主诉澄清的阈值是「关键字段 ≥ 2 或 2 轮」而不是原来的「3 项或 3 轮」</b>：
     * 那一版是按五阶段设计的（后面还有功能影响、风险筛查各占一轮，走完约 5 轮），
     * 而现在的目标是「最多问 3 个问题就给出去向」。同时满足条件时优先看字段 ——
     * 用户已经把情况说清楚了就不必硬凑轮数，这是「更早给建议」的唯一实现方式。
     *
     * @param current     当前阶段
     * @param turnsInStage 在当前阶段内的用户发言数（进入该阶段时从 0 开始）
     * @param fields      已采集字段（含本轮）
     * @param input       本轮用户输入
     */
    public static InterviewStage decide(InterviewStage current, int turnsInStage,
                                        Set<String> fields, String input) {
        if (current == null) {
            return InterviewStage.OPENING;
        }
        String text = input == null ? "" : input;

        if (current == InterviewStage.ENDED) {
            // 说了结束又继续聊 → 回到建议转介，别把会话锁死
            return containsAny(text, END_WORDS) ? InterviewStage.ENDED : InterviewStage.SUMMARY_REFERRAL;
        }
        if (containsAny(text, END_WORDS)) {
            return InterviewStage.ENDED;
        }
        // 换话题回退。只有到了「建议转介」才谈得上回退 —— 在主诉澄清里说「换个话题」，
        // 本来就还在主诉澄清，什么都不必做（这条分支写出来是多余的）。
        if (containsAny(text, SWITCH_TOPIC) && current.isAtLeast(InterviewStage.SUMMARY_REFERRAL)) {
            return InterviewStage.CHIEF_COMPLAINT;
        }

        int key = countOf(fields, KEY_FIELDS);

        return switch (current) {
            // 第 1 轮必须留在「建立联结」：模型第一次开口要先把共情与角色边界说完，
            // 一上来就追问细节会显得不近人情。所以这里只看轮次，不看用户是否已经描述了困扰。
            //
            // ⚠️ 阈值是 2 而不是 1。推进发生在**用户发言之后、组装提示词之前**，
            // 所以「用户第 N 条之后的阶段」= 模型第 N 次回复所处的阶段：
            // 阈值 1 会让模型的第一次回复直接落在「主诉澄清」，OPENING 永远轮不到一次回复，
            // 三个阶段里有一段是空转 —— 那正是这一段注释要防的事。
            case OPENING -> turnsInStage >= 2 ? InterviewStage.CHIEF_COMPLAINT : InterviewStage.OPENING;
            // 关键字段够 3 项（用户一次把困扰说得很全）就少问一轮；否则最多再追问一轮。
            // 两种走向合起来就是「2 到 3 个关键问题之后给出医生推荐」。
            case CHIEF_COMPLAINT -> (key >= 3 || turnsInStage >= 2)
                    ? InterviewStage.SUMMARY_REFERRAL : InterviewStage.CHIEF_COMPLAINT;
            case SUMMARY_REFERRAL, ENDED -> current;
        };
    }

    private static int countOf(Set<String> fields, List<String> names) {
        if (fields == null || fields.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String name : names) {
            if (fields.contains(name)) {
                n++;
            }
        }
        return n;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
