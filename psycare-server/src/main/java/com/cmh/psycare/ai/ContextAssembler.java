package com.cmh.psycare.ai;

import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.dto.RecommendResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文组装器：把「常量 + 会话档案 + 资料 + 历史 + 当前输入」按<b>稳定性递减</b>的顺序拼成消息列表。
 *
 * <h3>顺序不是随便排的 —— 它决定了缓存命中率</h3>
 * <pre>
 * L1 人格      ┐
 * L2 安全边界   │ 永久不变 → 前缀字节稳定 → DeepSeek 自动上下文缓存命中
 * L3 输出约束   │
 * L4 流程      ┘
 * L5 档案与摘要  会话级，变化少
 * L6 检索资料    每轮变（Phase 2 只有「无资料」分支）
 * L7 对话历史    每轮变
 * L8 当前输入    最后
 * </pre>
 * <p>把任何易变信息（时间戳、轮次、会话 id）放到 L1–L4，缓存就 100% 不命中，
 * 而且不报错、只在账单上体现 —— 所以这里 L1–L4 <b>只读 {@link PromptAssets} 的常量</b>，
 * 一个变量都不拼。
 *
 * <h3>裁剪依据从「条数」改成「token 预算」</h3>
 * <p>按条数裁（改造前的 {@code max-history * 2}）在中文场景下不可控：
 * 一条 3000 字的倾诉与一句「嗯」占同样的配额，于是「10 轮」有时是 200 字、
 * 有时是 3 万字，直接顶爆上下文。改成按 token 预算从最近往前累加，
 * 并给单条超长消息加上限。
 */
@Component
@RequiredArgsConstructor
public class ContextAssembler {

    /** 非 CJK 字符按 4 字符 ≈ 1 token 折算 */
    private static final int CHARS_PER_TOKEN = 4;
    /** 每条消息的结构开销（role 字段、分隔符等） */
    private static final int MESSAGE_OVERHEAD = 8;

    /** 字段名 → 中文标签，用于 L5「已了解到的信息」 */
    private static final Map<String, String> FIELD_LABELS = Map.of(
            StageRules.F_DURATION, "困扰起始时间",
            StageRules.F_FREQUENCY, "出现频率",
            StageRules.F_SEVERITY, "严重程度",
            StageRules.F_SLEEP, "睡眠影响",
            StageRules.F_APPETITE, "食欲影响",
            StageRules.F_WORK, "工作学习影响",
            StageRules.F_SOCIAL, "人际关系影响");

    private final LlmProperties props;

    /**
     * 组装一次请求的消息列表。
     *
     * @return 第一条必定是 L1 常量，最后一条必定是当前用户输入
     */
    public List<ChatMessage> assemble(Context ctx) {
        List<ChatMessage> messages = new ArrayList<>(10);

        // ---- L1–L4：常量，严禁拼接任何变量 ----
        messages.add(ChatMessage.system(PromptAssets.IDENTITY));
        messages.add(ChatMessage.system(PromptAssets.SAFETY_BOUNDARY));
        messages.add(ChatMessage.system(PromptAssets.OUTPUT_RULES));
        messages.add(ChatMessage.system(PromptAssets.INTERVIEW_FLOW));

        // ---- L5：会话级档案（测评上下文 + 阶段 + 已采集信息 + 摘要）----
        messages.add(ChatMessage.system(profileBlock(ctx)));

        // ---- L5b：可转介的医生名单。只在拿到推荐结果时注入 ----
        // 位置放在常量块之后：它每轮不变（同一会话内推荐稳定），又不会挤进 L1–L4
        // 那个必须字节级稳定的前缀里 —— 放前面会让 DeepSeek 的自动上下文缓存 100% 失效。
        if (ctx.doctors() != null && !ctx.doctors().isEmpty()) {
            messages.add(ChatMessage.system(doctorBlock(ctx.doctors())));
        }

        // ---- L6：资料。Phase 2 没有检索，固定走「无资料」分支，
        //      显式告知模型「没查到」比留空更安全（留空它会凭记忆编指南名）----
        if (ctx.retrieved() == null || ctx.retrieved().isEmpty()) {
            messages.add(ChatMessage.system(PromptAssets.NO_KNOWLEDGE_HINT));
        } else {
            messages.add(ChatMessage.system(ragBlock(ctx.retrieved())));
        }

        // ---- L7：历史，按 token 预算从最近往前累加 ----
        messages.addAll(trimToBudget(ctx.history(), budget()));

        // ---- L8：当前输入 ----
        messages.add(ChatMessage.user(ctx.userInput()));
        return messages;
    }

    /** 历史部分的 token 预算 */
    public int budget() {
        Integer v = props.getMaxContextTokens();
        return v == null || v <= 0 ? 6000 : v;
    }

    /**
     * L5 档案块。所有项都可能缺，缺了就不写那一行 —— 不要留下「总分 null」这种半截句子
     * （历史上有过这个 bug：总分与等级必须同时存在才注入，否则污染 system prompt）。
     */
    private String profileBlock(Context ctx) {
        StringBuilder sb = new StringBuilder("【本次对话背景】\n");

        if (ctx.riskLevel() != null && ctx.totalScore() != null) {
            sb.append("- 该居民刚完成 ").append(ctx.scaleName() == null ? "心理测评" : ctx.scaleName())
                    .append("，总分 ").append(ctx.totalScore())
                    .append("，系统评估风险等级为 ").append(ctx.riskLevel())
                    .append("（").append(ctx.riskLevel().getDesc()).append("）。\n");
            sb.append("  请在倾听时重点关注与该等级相符的情绪状态，但不要向用户透露系统评级细节，避免标签化。\n");
        } else {
            sb.append("- 本次对话不是从测评结果发起的，没有测评背景信息。\n");
        }

        InterviewStage stage = ctx.stage() == null ? InterviewStage.OPENING : ctx.stage();
        sb.append("- 当前问诊阶段：").append(stage.getLabel())
                .append("（第 ").append(Math.min(stage.getStep(), InterviewStage.TOTAL_STEPS))
                .append(" / ").append(InterviewStage.TOTAL_STEPS).append(" 阶段）。")
                .append("本阶段目标：").append(stage.getGoal()).append("。\n");

        Set<String> fields = ctx.knownFields();
        if (fields != null && !fields.isEmpty()) {
            List<String> labels = new ArrayList<>();
            // 按 FIELD_LABELS 的稳定顺序输出，避免 Set 顺序波动影响前缀
            FIELD_LABELS.forEach((k, v) -> {
                if (fields.contains(k)) {
                    labels.add(v);
                }
            });
            if (!labels.isEmpty()) {
                sb.append("- 已经了解到的信息：").append(String.join("、", labels))
                        .append("。不要重复追问这些内容。\n");
            }
        }

        if (ctx.summary() != null && !ctx.summary().isBlank()) {
            sb.append("- 之前对话的摘要：").append(ctx.summary().trim()).append('\n');
        }
        return sb.toString();
    }

    /**
     * L5b 医生名单块：让模型能在对话正文里直接推荐医生。
     *
     * <h4>为什么把名单喂进上下文，而不是让模型自己调接口查</h4>
     * <p>改造前「推荐医生」只存在于右侧栏（前端另调 {@code /recommend/doctors}），
     * 对话正文里模型手里<b>没有任何医生数据</b>，于是它只能说「建议你去看看医生」——
     * 用户拿不到姓名与科室，也得不到「为什么是他」。
     * 推荐结果本来就已经算好了（{@code RecommendService}），把它作为上下文注入是
     * 零额外调用、零新基础设施的做法；真正的工具调用（模型自行决定何时查、查什么）
     * 留给 Phase 5。
     *
     * <h4>两条硬约束</h4>
     * <ol>
     *   <li><b>只给对外属性</b>：姓名 / 科室 / 职称 / 擅长方向。推荐引擎内部那条
     *       「风险等级A→科室[全科]」的理由<b>刻意不注入</b> —— 它含系统风险等级标签，
     *       而 L2 第 6 条明确禁止向居民透露内部标签。让模型基于「科室 + 擅长方向 +
     *       对方刚说过的话」自己组织推荐理由，反而更像人话，也天然不会泄露标签。</li>
     *   <li><b>明确阶段门禁</b>：名单从第一轮起就在上下文里，若不写清使用时机，
     *       模型很可能开口就把几位医生全抖出来，五阶段引导直接作废。</li>
     * </ol>
     */
    private String doctorBlock(List<RecommendResult> doctors) {
        StringBuilder sb = new StringBuilder("【可转介的医生（本平台真实在册，不是示例数据）】\n");
        for (RecommendResult d : doctors) {
            sb.append("- ").append(d.getDoctorName())
                    .append("（").append(d.getDepartment()).append(" · ").append(d.getTitle()).append("）");
            if (d.getExpertiseTags() != null && !d.getExpertiseTags().isBlank()) {
                sb.append("擅长：").append(d.getExpertiseTags().trim());
            }
            sb.append('\n');
        }
        sb.append("""
                使用规则：
                1. 只在「小结转介」阶段、或对方主动问「该看什么医生 / 找谁看」时，才介绍其中 1 到 2 位；
                   其他阶段不要提这份名单，先按当前阶段的目标把情况问清楚。
                2. 介绍时用你自己的话说明「为什么推荐他」——结合对方的困扰与该医生的科室、擅长方向来谈，
                   不要照抄上面括号里的内容，也不要罗列评分之类的内部指标。
                3. 这些是真实在册人员：不要编造姓名、科室或职称，也不要承诺出诊时间或号源。
                4. 对方若表示想直接联系，告诉他可以在「我的消息」里选中该医生发起咨询。
                """);
        return sb.toString();
    }

    /**
     * L6 资料块（Phase 4 接入检索后才会走到）。
     *
     * <h4>结尾那句提示为什么必须是双向的</h4>
     * <p>RAG 最常见的失败不是检索不到，而是<strong>检索到了不相关内容后强行拿来回答</strong>，
     * 所以原稿只写了「不相关就说明没有资料」。但只有这一半会带来另一个失败：
     * 资料<b>确实相关</b>时，模型仍在犹豫要不要用 —— 实测表现为「引用了资料却仍回答
     * 『我这边帮不上忙』」，因为上下文里全是「可能无关」「不要强行引用」的措辞。
     * 因此改成先明确「相关的要照着答」，再讲不相关时怎么办。
     */
    private String ragBlock(List<String> retrieved) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        int i = 1;
        for (String chunk : retrieved) {
            sb.append('[').append(i++).append("] ").append(chunk).append('\n');
        }
        sb.append("""
                使用要求：
                1. 先判断这些资料是否覆盖了对方的问题。**若覆盖**，就以上面的资料为依据如实作答，
                   把它当作你已经掌握的知识来讲，不要再说「我这边帮不上忙」「这不属于我的范围」。
                2. **若没有覆盖**，直接说明你暂时没有这方面的确切资料，不要编造，也不要强行引用。
                3. 资料来源已标在每条的《》中（上方的参考资料卡片也会展示给对方）。""");
        return sb.toString();
    }

    /**
     * 按 token 预算裁剪历史：从最近往前累加，超预算即停。
     *
     * <p>两个细节：
     * <ol>
     *   <li>裁剪后若开头是 assistant，则继续往前丢 —— 以「AI 的自言自语」开头的历史
     *       会让模型误以为上一轮它已经在回应某件没被记录的事；</li>
     *   <li>若预算小到一条都放不下、但历史非空，则<b>保底保留最近 1 轮</b>。
     *       宁可轻微超预算，也不要让多轮对话失去连续性（那等于每轮都重新开始）。</li>
     * </ol>
     */
    private List<ChatMessage> trimToBudget(List<ChatMessage> history, int budget) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Deque<ChatMessage> kept = new ArrayDeque<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = clampSingle(history.get(i));
            int cost = estimateTokens(m.content());
            if (used + cost > budget && !kept.isEmpty()) {
                break;
            }
            used += cost;
            kept.addFirst(m);
        }
        while (!kept.isEmpty() && "assistant".equals(kept.peekFirst().role())) {
            kept.pollFirst();
        }
        if (kept.isEmpty()) {
            int from = Math.max(0, history.size() - 2);
            for (ChatMessage m : history.subList(from, history.size())) {
                kept.addLast(clampSingle(m));
            }
            while (!kept.isEmpty() && "assistant".equals(kept.peekFirst().role())) {
                kept.pollFirst();
            }
        }
        return new ArrayList<>(kept);
    }

    /** 单条超长则截断：一条 3000 字的输入能顶掉整个窗口 */
    private ChatMessage clampSingle(ChatMessage m) {
        int max = props.getSingleMessageMaxChars() == null || props.getSingleMessageMaxChars() <= 0
                ? 1000 : props.getSingleMessageMaxChars();
        String c = m.content() == null ? "" : m.content();
        if (c.length() <= max) {
            return m;
        }
        return new ChatMessage(m.role(), c.substring(0, max) + "……（内容过长已截断）");
    }

    /**
     * token 估算：中文按 1 字 ≈ 1 token 保守估（DeepSeek 中文实际约 0.6–0.7）。
     *
     * <p>刻意不引入 jtokkit / tiktoken：多一个依赖、多一次编码开销，
     * 而估算偏保守 10–20% 对成本影响可以忽略。<b>等实测发现预算经常估偏再换</b>。
     *
     * <p>CJK 判断包含汉字、中文标点与全角字符 —— 全角标点同样按 1 token 计，
     * 宁可高估：低估会让请求超出上下文上限，那是 400 失败，代价比多留点余量大得多。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return MESSAGE_OVERHEAD;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F)
                    || (c >= 0xFF00 && c <= 0xFFEF)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other / CHARS_PER_TOKEN) + MESSAGE_OVERHEAD;
    }

    /**
     * 一次组装的输入。
     *
     * @param scaleName    量表名，可为 null
     * @param totalScore   总分，可为 null
     * @param riskLevel    风险等级，可为 null
     * @param stage        当前阶段
     * @param knownFields  已采集的信息维度
     * @param summary      历史摘要（{@code consult_session.summary}），可为 null
     * @param doctors      可转介的医生（来自 {@code RecommendService}），空则不注入 L5b
     * @param history      历史消息（时间正序，不含本轮）
     * @param retrieved    L6 检索资料，Phase 2 恒为空
     * @param userInput    本轮用户输入
     */
    public record Context(
            String scaleName,
            Integer totalScore,
            RiskLevel riskLevel,
            InterviewStage stage,
            Set<String> knownFields,
            String summary,
            List<RecommendResult> doctors,
            List<ChatMessage> history,
            List<String> retrieved,
            String userInput) {
    }
}
