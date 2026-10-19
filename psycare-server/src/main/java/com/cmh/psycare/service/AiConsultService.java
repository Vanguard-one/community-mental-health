package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.ai.AiChatClient;
import com.cmh.psycare.ai.ChatMessage;
import com.cmh.psycare.ai.ContextAssembler;
import com.cmh.psycare.ai.InterviewStage;
import com.cmh.psycare.ai.LlmChunk;
import com.cmh.psycare.ai.LlmErrorCode;
import com.cmh.psycare.ai.LlmErrorMapper;
import com.cmh.psycare.ai.LlmException;
import com.cmh.psycare.ai.LlmProperties;
import com.cmh.psycare.ai.LlmUsage;
import com.cmh.psycare.ai.ReferralRules;
import com.cmh.psycare.ai.StageRules;
import com.cmh.psycare.ai.StageState;
import com.cmh.psycare.ai.StageStore;
import com.cmh.psycare.ai.ThinkingPolicy;
import com.cmh.psycare.ai.TranscriptSummarizer;
import com.cmh.psycare.ai.rag.RetrievalPipeline;
import com.cmh.psycare.ai.rag.RetrievedChunk;
import com.cmh.psycare.common.enums.AlertStatus;
import com.cmh.psycare.common.enums.AlertType;
import com.cmh.psycare.common.enums.ConsultType;
import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AiMessageVO;
import com.cmh.psycare.dto.AiSessionDetailVO;
import com.cmh.psycare.dto.AiSessionVO;
import com.cmh.psycare.dto.RecommendResult;
import com.cmh.psycare.engine.CrisisDetector;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.entity.ConsultMessage;
import com.cmh.psycare.entity.ConsultSession;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.mapper.AlertRecordMapper;
import com.cmh.psycare.mapper.AssessmentMapper;
import com.cmh.psycare.mapper.ConsultMessageMapper;
import com.cmh.psycare.mapper.ConsultSessionMapper;
import com.cmh.psycare.mapper.ScaleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 问诊服务：会话管理 + 流式对话 + 危机词本地拦截 + 危机工单触发。
 *
 * <h3>安全设计（答辩红线）</h3>
 * <ol>
 *   <li>用户输入先做本地危机词检测，命中则<b>不走模型</b>，直接返回固定危机文案并触发 CRISIS 工单；</li>
 *   <li>会话上下文（量表/总分/风险等级）注入 system prompt；</li>
 *   <li>AI 回复也做危机词检测，命中则替换为危机文案（双保险）。</li>
 * </ol>
 *
 * <h3>会话归属校验（{@link #requireMember}）</h3>
 * <p>AI 会话 id 是自增的，可直接枚举。所有读写入口必须先过归属校验，
 * 否则任何居民改一下 {@code sessionId} 就能读到、并写进别人的问诊记录 ——
 * 这是全系统最私密的一类数据。管理员也不放行：AI 问诊是居民的私密倾诉，
 * 管理端没有业务理由读取（管理端菜单也不给入口）。
 *
 * <h3>落库时机：doFinally，不是 doOnComplete</h3>
 * <p>流式回复的落库写在 {@code doFinally}。客户端中途关页面/断网时
 * {@code doOnComplete} 根本不触发，已生成的内容会整段丢失 —— 而用户其实
 * 已经在屏幕上读到了一部分，重进会话却什么都没有。改为 {@code doFinally} 后
 * <b>只落已生成的部分</b>，并用 {@code finish_reason} 区分「完整」与「被截断」。
 *
 * <h3>落库内容以「用户实际看到的事件」为准</h3>
 * <p>累计缓冲区不接在上游 {@code LlmChunk} 上，而是接在<b>下发给前端的事件</b>上。
 * 差别在于：走事件时，降级话术、空回复兜底、危机替换文案都会进入缓冲区，
 * 于是「库里存的」与「用户看到的」天然一致；接在上游则只存模型原文，
 * 用户看到的兜底话术在库里查不到，事后审计会读到一段从未发生过的对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConsultService {

    /** 回复收尾方式：正常流式完成 */
    public static final String FINISH_DONE = "DONE";
    /** 回复收尾方式：客户端断开 / 取消 / 整体超时截断，落库内容为已生成的部分 */
    public static final String FINISH_INTERRUPTED = "INTERRUPTED";
    /**
     * 回复收尾方式：内容被替换为固定危机/安全文案（两条路径都会用到）。
     *
     * <p>输入侧短路（{@link #crisisStream}）与输出侧安全护栏（{@link #tailEvents}）
     * 都落这个值 —— 它们对「用户的下一句话该不该由人工接手」是同一类信号。
     * 区分二者看 {@code crisis} 事件的 {@code reason}，不看这个字段。
     */
    public static final String FINISH_CRISIS = "CRISIS";
    /** 回复收尾方式：上游 LLM 抛错或未产出内容 */
    public static final String FINISH_ERROR = "ERROR";

    /**
     * 降级话术。<b>必须带人工出口</b>。
     *
     * <p>只写「服务不可用」在心理健康场景是不合格的：用户可能正处于危机中，
     * 一句冷冰冰的报错会直接终结求助行为。所以任何降级路径都必须给出
     * 「还能找谁」——这里是「我的消息」与全国心理援助热线。
     */
    public static final String FALLBACK_TEXT =
            "抱歉，智能助手暂时无法连接。你可以稍后重试，"
                    + "也可以直接在「我的消息」里联系医生。"
                    + "如果情况比较紧急，请拨打全国心理援助热线 12356。";

    /**
     * 上游正常结束却一个字都没产出时的兜底。
     *
     * <p>与 {@link #FALLBACK_TEXT} 分开，是因为成因不同、给用户的信息也不同：
     * 这条的成因是「模型没能组织出回答」（推理模型的 {@code max_tokens}
     * 被思考过程吃光时必然如此，实测 258 帧全是 reasoning_content、正文为空、
     * 而 {@code finish_reason} 仍是 {@code length}）。说「服务不可用」是误报，
     * 用户换个说法再问一次通常就好。
     */
    public static final String EMPTY_REPLY_TEXT =
            "抱歉，我这次没能组织好回答。你可以换个说法再问一次，"
                    + "也可以直接在「我的消息」里联系医生。"
                    + "如果情况比较紧急，请拨打全国心理援助热线 12356。";

    // ---- SSE 事件名（前后端契约，见 SSE事件协议.md）----
    private static final String EV_META = "meta";
    private static final String EV_TOKEN = "token";
    private static final String EV_USAGE = "usage";
    private static final String EV_OVERRIDE = "override";
    private static final String EV_ERROR = "error";
    private static final String EV_CRISIS = "crisis";
    /**
     * {@code recommend} 事件：本轮用户<b>同意</b>接受医生推荐，前端应播「AI 正在分析」动画，
     * 动画结束后结束会话并跳转到推荐结果页。
     *
     * <p>为什么必须是服务端下发而不是前端自己判断：
     * 「AI 问过没有」这件事只有服务端能确认（它要回看上一轮 AI 回复），
     * 前端只看用户输入的话，任何一轮里的一句「好的」都会被当成应允。
     * 这与 {@code crisis} 事件的取舍相同 —— 判定权归服务端，前端只做渲染。
     */
    private static final String EV_RECOMMEND = "recommend";
    /**
     * {@code citations} 事件：本轮回复所依据的知识库资料（Phase 4）。
     *
     * <h4>为什么由服务端下发</h4>
     * <p>「用到哪几条资料」只有检索层知道。前端反推不出来 —— 它没有向量、没打过 Milvus，
     * 而且资料是在服务端被阈值过滤与预算裁剪之后的最终形态。
     *
     * <h4>为什么在 {@code token} 之前发</h4>
     * <p>引用卡片是「这条回复的依据」，用户开始读正文时就该看到它。
     * 放到 {@code token} 之后（比如跟 {@code usage} 一起）会出现
     * 「回复读完了，忽然从下面顶出一块引用框」，观感上像后补的、不可信。
     * 而它在服务端是<b>正文生成之前就确定</b>的 —— 检索发生在组装提示词时，
     * 比第一个 token 早得多，天然就该先发。
     *
     * <h4>为什么没有资料时<b>不发</b>空事件</h4>
     * <p>发一个 {@code {"items":[]}} 只会让前端多走一遍渲染分支且不画任何东西。
     * 「没有资料」这件事的载体就是「事件不出现」，前端据此什么也不渲染。
     */
    private static final String EV_CITATIONS = "citations";
    private static final String EV_DONE = "done";
    private static final String DONE_PAYLOAD = "[DONE]";

    /** 转介时注入上下文的医生条数：够选就好，多了会把模型带偏成「念名单」 */
    private static final int RECOMMEND_TOP_N = 3;

    /**
     * 引用卡片摘要的字数上限。
     *
     * <p>给 60 字：够让用户认出「这是我上传的那篇讲 XX 的文档」，
     * 又不会让卡片喧宾夺主。引用卡是<b>证据</b>不是内容 —— 想看全文的人
     * 应该去知识库页面，而不是在聊天气泡下面读完整段落。
     */
    private static final int CITATION_SNIPPET_CHARS = 60;

    private static final String ROLE_AI = "AI";
    private static final String ROLE_RESIDENT = "RESIDENT";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";

    /**
     * 历史会话列表最多返回条数。
     *
     * <p>产品要求「只保留最近五次」：历史会话是用来「接着上次聊」的，
     * 不是归档检索。列 30 条会让抽屉变成需要滚动查找的长列表，
     * 而真正会被点开的从来只有最近几次。
     */
    private static final int SESSION_LIST_LIMIT = 5;
    /** 会话明细最多返回消息条数（从最近往前取） */
    private static final int DETAIL_LIMIT = 200;

    /**
     * 允许判定「应允推荐」的最小轮数。
     *
     * <p>用户第一句就说「我想找医生」时不该直接把会话结束掉跳走 ——
     * 他还没说任何情况，AI 也没来得及回应。至少聊过两轮，推荐才有依据。
     */
    private static final int REFERRAL_MIN_TURNS = 2;

    private final ConsultSessionMapper sessionMapper;
    private final ConsultMessageMapper messageMapper;
    private final AssessmentMapper assessmentMapper;
    private final ScaleMapper scaleMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final AiChatClient aiChatClient;
    private final ContextAssembler contextAssembler;
    private final StageStore stageStore;
    private final TranscriptSummarizer summarizer;
    private final CrisisDetector crisisDetector;
    private final RecommendService recommendService;
    private final LlmErrorMapper errorMapper;
    private final LlmProperties llmProps;
    private final AiUsageRecorder usageRecorder;

    /**
     * RAG 检索管线（Phase 4）。
     *
     * <p>它在<b>对话路径上但是非核心依赖</b>：{@link RetrievalPipeline} 自身保证不抛异常、
     * 失败返回空列表，所以这里不需要 try/catch，也不该因为「它可能失败」而放弃接入。
     */
    private final RetrievalPipeline retrievalPipeline;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 开启一次 AI 问诊会话（可关联测评记录 / 预警工单）。
     *
     * <p>关联的 {@code assessmentId} / {@code alertId} 必须属于当前用户：
     * 这两个 id 都会被读出来喂进 system prompt（量表名、总分、风险等级、工单上下文），
     * 不校验的话，传别人的 assessmentId 就能把别人的测评结果套进自己的对话里 ——
     * 既是越权读取，也会让模型基于错误上下文作答，而错误答案看起来完全正常。
     */
    public ConsultSession openSession(Long assessmentId, Long alertId) {
        Long me = SecurityUtil.getUserId();
        if (assessmentId != null) {
            Assessment a = assessmentMapper.selectById(assessmentId);
            if (a == null || !me.equals(a.getResidentId())) {
                throw new BizException(400, "测评记录不存在或不属于当前用户");
            }
        }
        if (alertId != null) {
            AlertRecord r = alertRecordMapper.selectById(alertId);
            if (r == null || !me.equals(r.getResidentId())) {
                throw new BizException(400, "预警工单不存在或不属于当前用户");
            }
        }
        /*
         * 一个居民只有**一条进行中**的 AI 会话：已有就复用，不再新建。
         *
         * 产品侧已取消「历史会话」入口（抽屉、会话列表、切换都不再存在），
         * 聊天框展示的是这个居民**一段连续的对话**。若这里仍旧每次都 insert，
         * 用户每进一次页面就多一条空会话 —— 而界面上永远只显示最近那条，
         * 其余全成了库里的垃圾数据。
         *
         * 只挑 status=OPEN 的：历史遗留数据里可能有已结束（CLOSED）的会话，
         * 复用它会得到一个「只能看不能发」的会话（发消息时服务端会按状态拒绝），
         * 用户会觉得「刚进来就坏了」。全结束了就老老实实新建一条。
         */
        ConsultSession existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<ConsultSession>()
                        .eq(ConsultSession::getResidentId, me)
                        .eq(ConsultSession::getConsultType, ConsultType.AI)
                        .eq(ConsultSession::getStatus, STATUS_OPEN)
                        .orderByDesc(ConsultSession::getId)
                        .last("LIMIT 1"));
        if (existing != null) {
            // 复用时把新的上下文补上：从测评结果页发起问诊，用户期望 AI 读的是**这次**的测评。
            // 只 set 非空字段 —— 传 null 表示「不改变」，不是「清空」。
            LambdaUpdateWrapper<ConsultSession> upd = new LambdaUpdateWrapper<>();
            upd.eq(ConsultSession::getId, existing.getId());
            boolean dirty = false;
            if (assessmentId != null) {
                upd.set(ConsultSession::getAssessmentId, assessmentId);
                dirty = true;
            }
            if (alertId != null) {
                upd.set(ConsultSession::getAlertId, alertId);
                dirty = true;
            }
            if (dirty) {
                sessionMapper.update(null, upd);
                existing.setAssessmentId(assessmentId != null ? assessmentId : existing.getAssessmentId());
                existing.setAlertId(alertId != null ? alertId : existing.getAlertId());
            }
            log.info("[AI 问诊] 复用进行中的会话 session={} resident={}", existing.getId(), me);
            return existing;
        }

        ConsultSession session = new ConsultSession();
        session.setResidentId(me);
        session.setConsultType(ConsultType.AI);
        session.setStatus(STATUS_OPEN);
        session.setAssessmentId(assessmentId);
        session.setAlertId(alertId);
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 流式对话：返回 SSE 事件流。
     *
     * <p>危机输入直接短路（不消耗模型调用）；正常输入走 LLM 流式，
     * 回复在 {@code doFinally} 落库，见类注释「落库时机」。
     *
     * <p>方法体前半段（归属校验、落库居民消息）是<b>同步执行</b>的，
     * 异常会在返回 Flux 之前抛出，因此能被 {@code GlobalExceptionHandler}
     * 转成 HTTP 200 + {@code body.code} 的 JSON（前端已识别非事件流响应）。
     * 一旦开始下发事件，响应头已提交，此后所有错误只能走 {@code event:error}。
     *
     * @throws BizException 401 未登录 / 404 会话不存在 / 403 非本人会话 / 400 会话已结束
     */
    public Flux<ServerSentEvent<String>> chatStream(Long sessionId, String content) {
        ConsultSession session = requireMember(sessionId);
        if (!STATUS_OPEN.equals(session.getStatus())) {
            throw new BizException(400, "会话已结束，无法继续对话");
        }
        Long residentId = session.getResidentId();
        // 一次问答的追踪号：日志里把「上游失败 → 重试 → 降级 → 落库」串成一条线。
        // 不要把 traceId 放进 prompt —— 那会破坏前缀缓存（见 LlmProperties 的 maxTokens 注释及 §5.1）。
        UUID traceId = UUID.randomUUID();

        // 引导问诊阶段：先读（此刻本轮用户消息尚未落库）→ 推进 → 写回。
        // 顺序不能换，见 StageStore 类注释：反过来会让「Redis 丢失时」多推进一次。
        StageState stage = StageRules.advance(stageStore.load(sessionId), content);
        stageStore.save(sessionId, stage);

        if (crisisDetector.hit(content)) {
            return crisisStream(session, residentId, content, traceId, stage);
        }

        // 「要不要我为你推荐医生」的应允判定。
        // 放在这里而不是 tailEvents 里的理由：它只取决于「上一轮 AI 回复 + 本轮用户输入」，
        // 与模型这一轮怎么答无关 —— 用户答应之后模型仍可能再啰嗦两句，
        // 但「是否跳转到推荐页」这件事不该被模型的措辞左右。
        boolean referralAccepted = acceptReferral(sessionId, stage, content);

        return llmStream(session, residentId, content, traceId, stage, referralAccepted);
    }

    /**
     * 本轮用户是否答应了「为你推荐医生」。
     *
     * <p>三重门槛，全部通过才算：
     * <ol>
     *   <li>轮数 ≥ {@link #REFERRAL_MIN_TURNS} —— 用户第一句就说「我想找医生」时
     *       不该立刻把会话结束掉跳走，他还没说任何情况；</li>
     *   <li>要么已在「建议转介」阶段，要么用户主动要医生 —— 其余情况连查都不查
     *       （省一次「取上一条 AI 回复」的查询）；</li>
     *   <li>{@link ReferralRules#accepted} —— AI 确实问过，且用户给了肯定答复。</li>
     * </ol>
     *
     * <p>判定失败是**最常见**的结果，它不代表出错：绝大部分轮次用户只是在回答
     * 「多久了」「睡不着」这类问题。这里不写 warn 日志，只有命中才记一条 info，
     * 否则日志会被无效判定刷满。
     */
    private boolean acceptReferral(Long sessionId, StageState stage, String content) {
        if (stage == null || stage.getTurns() < REFERRAL_MIN_TURNS) {
            return false;
        }
        boolean inReferral = stage.getStage() == InterviewStage.SUMMARY_REFERRAL;
        if (!inReferral && !ReferralRules.wantsDoctor(content)) {
            return false;
        }
        boolean accepted = ReferralRules.accepted(lastAiText(sessionId), content);
        if (accepted) {
            log.info("[AI 问诊] 用户同意接受医生推荐，本轮结束后进入推荐流程：session={} stage={} turns={}",
                    sessionId, stage.getStage(), stage.getTurns());
        }
        return accepted;
    }

    /**
     * 该会话最后一条 AI 回复原文。
     *
     * <p>用途只有一个：判断「AI 上一轮有没有问过『需要我为你推荐合适的医生吗』」。
     * 没有它，用户的「好的」与随口附和就分不开 —— 而这正是要不要结束会话、
     * 跳去推荐页的判据。
     *
     * <p>只取 1 条、按 {@code id} 倒序：同一秒落库的两条消息 {@code created_at} 相同，
     * 按时间排序不稳定（与 {@link #loadHistory} 同一条理由）。
     */
    private String lastAiText(Long sessionId) {
        List<ConsultMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .eq(ConsultMessage::getSender, ROLE_AI)
                        .orderByDesc(ConsultMessage::getId)
                        .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0).getContent();
    }

    /**
     * 结束会话。
     *
     * <p>过去这里查不到会话就静默返回（假装成功），现在改为抛 404 ——
     * 「关掉一个不存在的会话」与「关成功了」对调用方是两件事，
     * 静默成功会让前端把不存在的会话当作已关闭。
     */
    public void closeSession(Long sessionId) {
        ConsultSession session = requireMember(sessionId);
        session.setStatus(STATUS_CLOSED);
        session.setClosedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        // Redis 里的阶段状态随之清理。这不是为了让状态「消失」——
        // 历史消息还在，重放随时能重建出同样的状态；清掉只是不留无用的 TTL 键。
        stageStore.clear(sessionId);
    }

    /**
     * 清空会话的聊天记录，但**保留上下文记忆**。
     *
     * <h3>「清空记录」与「清空记忆」是两件事</h3>
     * <p>进入模型上下文的历史有两条独立来源（见 {@link #buildMessages}）：
     * <ol>
     *   <li>{@code consult_session.summary} —— L4 前情摘要，即「记忆」；</li>
     *   <li>{@code loadHistory()} 每轮现查 {@code consult_message} —— 即「聊天记录」。</li>
     * </ol>
     * 本方法只删第 2 条，<b>不碰 summary 列</b>。于是用户看到屏幕清空了，
     * 但 AI 依然记得之前聊过的内容 —— 这正是这个功能要的效果。
     *
     * <h3>为什么真的删表，而不是加一个「已隐藏」标记</h3>
     * <p>若是「隐藏」，{@code loadHistory()} 与 {@link #sessionDetail} 都得再学一遍
     * 「怎么过滤掉隐藏行」，而 {@code triggerSummaryIfNeeded} 统计消息数时也要排除它们 ——
     * 但凡漏掉一处，清空的记录就会以某种形式重新出现在界面上或重新进入上下文。
     * 直接删行则让所有既有查询天然正确，无需任何改动。
     *
     * <h3>为什么不用逻辑删除</h3>
     * <p>{@code consult_message} 没有 {@code is_deleted} 列，且本表的语义是
     * 「用户自己要删的内容」—— 留着才是问题。心理倾诉场景下，用户按下清空
     * 就该是真的清掉。
     *
     * <h3>阶段不重置</h3>
     * <p>刻意不清 Redis 里的 {@code stageStore}：阶段属于「记忆」的组成部分，
     * 用户已经讲过的内容不该因为清屏就倒退回开场，否则 AI 会重新问一遍问过的事，
     * 与保留下来的摘要自相矛盾。
     *
     * @return 实际删除的消息条数（供前端提示「已清空 N 条」）
     */
    public int clearMessages(Long sessionId) {
        // 与 closeSession / sessionDetail 同级敏感：换个 id 就能删别人的倾诉记录，
        // 归属校验必须放在服务层这里，不能靠 Controller 记得传 residentId。
        requireMember(sessionId);
        int deleted = messageMapper.delete(new LambdaQueryWrapper<ConsultMessage>()
                .eq(ConsultMessage::getSessionId, sessionId));
        log.info("[AI 问诊] 已清空聊天记录 session={} 删除={}条（摘要保留）", sessionId, deleted);
        return deleted;
    }

    /**
     * 当前用户的历史 AI 会话（倒序）。
     *
     * <p>只查当前登录用户自己的会话：{@code resident_id} 直接取自登录态，
     * 不从请求参数取 —— 也就没有「传别人的 id 读别人的会话」这种可能。
     */
    public List<AiSessionVO> listSessions() {
        Long me = SecurityUtil.getUserId();
        List<AiSessionVO> list = sessionMapper.selectAiSessionList(me, SESSION_LIST_LIMIT);
        return list == null ? List.of() : list;
    }

    /**
     * 某个会话的消息明细 + 当前阶段。
     *
     * <p>过 {@link #requireMember}：这是「换个 sessionId 就能读别人倾诉」的入口，
     * 与 {@code chatStream} 同级敏感。
     */
    public AiSessionDetailVO sessionDetail(Long sessionId) {
        ConsultSession session = requireMember(sessionId);
        List<ConsultMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .orderByDesc(ConsultMessage::getId)
                        .last("limit " + DETAIL_LIMIT));
        List<AiMessageVO> messages = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            ConsultMessage m = rows.get(i);
            messages.add(new AiMessageVO(m.getId(), m.getSender(), m.getContent(),
                    m.getCrisisFlag(), m.getFinishReason(), m.getCreatedAt()));
        }
        InterviewStage stage = stageStore.load(sessionId).getStage();
        return new AiSessionDetailVO(session.getId(), session.getStatus(),
                stage.name(), stage.getLabel(), stage.getStep(), session.getSummary(), messages);
    }

    /**
     * 会话归属校验：AI 会话只属于创建它的居民。
     *
     * <p>AI 会话的 {@code doctor_id} 恒为 NULL，没有「医生成员」的概念，
     * 因此判定只有一条：当前登录用户必须是 {@code session.resident_id}。
     * 与 {@code ChatService.requireMember} 的差别正在于此 —— 那边是双向
     * （医生或居民），这边是单向。管理员同样不放行。
     *
     * <p>放在服务层而不是 Controller：将来新增历史会话、删除会话等入口时，
     * 只要走这个私有方法就自动带上校验，不会漏。
     *
     * @return 校验通过的会话实体（调用方无需再查一次）
     * @throws BizException 401 未登录 / 404 会话不存在 / 403 非本人会话
     */
    private ConsultSession requireMember(Long sessionId) {
        ConsultSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(404, "会话不存在");
        }
        if (!SecurityUtil.getUserId().equals(session.getResidentId())) {
            throw new BizException(403, "无权访问该会话");
        }
        return session;
    }

    // =================================================================
    // 危机短路：不消耗模型调用
    // =================================================================

    /**
     * 命中危机词的应答路径。
     *
     * <p>三个特点，都与正常路径刻意不同：
     * <ol>
     *   <li><b>不调模型</b>。危机识别的正确性由本地词表保证，交给模型「自觉」是设计错误；
     *       顺带也省掉一次调用与首字延迟 —— 危机场景下等待是最不能接受的。</li>
     *   <li><b>不写 ai_usage_log</b>。没有发生任何调用，也就没有 token 费用；
     *       写一行零用量记录只会污染成本报表，让「调用量」与「对话量」对不上。</li>
     *   <li><b>事件流仍然完整</b>（meta → token → done）。前端只有一条渲染路径，
     *       不必为危机场景单开分支 —— 特殊分支越多，越容易漏掉新加的事件类型。</li>
     * </ol>
     */
    private Flux<ServerSentEvent<String>> crisisStream(ConsultSession session, Long residentId,
                                                       String content, UUID traceId, StageState stage) {
        Long userMsgId = saveMessage(session.getId(), ROLE_RESIDENT, residentId, content, 1, null);
        triggerCrisisAlert(residentId, session);
        String crisisText = crisisDetector.crisisResponse();
        saveMessage(session.getId(), ROLE_AI, null, crisisText, 1, FINISH_CRISIS);
        log.info("[AI 问诊] 命中危机词，短路返回（不调模型）session={} trace={}", session.getId(), traceId);

        return Flux.just(
                meta(session, userMsgId, traceId, stage),
                crisisEvent("INPUT"),
                sse(EV_TOKEN, crisisText),
                sse(EV_DONE, DONE_PAYLOAD));
    }

    // =================================================================
    // 正常路径：LLM 流式 + 四道防线
    // =================================================================

    private Flux<ServerSentEvent<String>> llmStream(ConsultSession session, Long residentId,
                                                     String content, UUID traceId, StageState stage,
                                                     boolean referralAccepted) {
        Long sessionId = session.getId();
        // 先落库再组装：万一组装失败，用户刚说的话也不会丢（他要重新打一遍）
        Long userMsgId = saveMessage(sessionId, ROLE_RESIDENT, residentId, content, 0, null);

        /*
         * 关联测评**只查这一次**。它有两个用处：喂给 ContextAssembler 做 L5 档案块、
         * 以及给 ThinkingPolicy 判定要不要开思考。此前这个查询藏在 buildMessages 内部，
         * 加了第二个用途后若不提上来，同一轮就会对 assessment 表打两次。
         *
         * 没有测评背景时是 null（而不是默认成 A 级）：null 的语义是「不知道」，
         * 在提示词里表现为不写风险那一行，在思考判定里表现为「不构成风险条件」。
         * 把它当成一个具体取值，两处都会变成伪造信息。
         */
        Assessment assessment = session.getAssessmentId() != null
                ? assessmentMapper.selectById(session.getAssessmentId()) : null;

        /*
         * 检索**只做一次**，结果同时喂给两处：提示词的 L6 资料块、以及 citations 事件。
         *
         * 为什么不能各调一次 retrieve()：Redis 向量缓存只省掉「向量化」那一步外部 HTTP，
         * 后面的 Milvus 混合检索（dense + sparse 两路 + RRF）每次都会实打实跑一遍。
         * 各调一次 = 每轮对话白打一次 Milvus。而且两次结果在极端情况下可能不一致
         * （期间语料被重建），那就会出现「卡片上写的资料」与「模型实际读到的资料」对不上 ——
         * 引用一旦与依据不符，比不显示引用更糟。
         */
        List<RetrievedChunk> retrieved = retrieveRaw(content);
        List<ChatMessage> messages = buildMessages(session, content, stage, userMsgId,
                assessment, retrieved);

        /*
         * 本轮要不要开思考。判据只有「风险等级 + 引导阶段」两项 ——
         * 两者此刻都已在本方法手里（assessment 上面刚查过，stage 是入参），
         * 所以这是一次零成本的确定性分流，不是额外请求。
         * 实测：关思考首帧 0.6~0.9s，开思考 2.0~2.7s；而危机语境的回复
         * 恰好依赖思考过程产出热线与安全兜底 —— 详见 ThinkingPolicy 的类注释。
         */
        ThinkingPolicy.Decision thinking = ThinkingPolicy.decide(llmProps, stage == null ? null : stage.getStage(),
                assessment == null ? null : assessment.getRiskLevel());
        // 每轮都记一行：验收时能直接把「慢的那几轮」对上「为什么开了思考」，
        // 不用回头去猜哪条规则命中了。
        log.info("[AI 问诊] 思考模式={} 依据={} session={} trace={}",
                thinking.enabled() ? "开启" : "关闭", thinking.reason(), sessionId, traceId);

        // 用户实际看到的正文（由 SSE token 事件的 data 拼成，见类注释）
        StringBuilder seen = new StringBuilder();
        // 已下发的「正文帧」计数：重试闸门。已经流到浏览器的字撤不回来，
        // 重订阅会把同一段话再说一遍，所以只有一帧都没发出去时才允许重试。
        AtomicInteger emitted = new AtomicInteger(0);
        AtomicReference<LlmUsage> usageRef = new AtomicReference<>(LlmUsage.EMPTY);
        AtomicReference<LlmException> errRef = new AtomicReference<>();
        AtomicReference<String> upstreamFinish = new AtomicReference<>();
        AtomicInteger reasoningChars = new AtomicInteger(0);
        AtomicLong firstTokenAt = new AtomicLong(0L);
        AtomicBoolean truncated = new AtomicBoolean(false);
        // 服务端改写后的最终正文（当前唯一来源是输出侧安全护栏）。
        // 必须在流内判定并下发，因为 doFinally 时响应已结束，发不出事件；
        // 而落库用的是这个值，才能保证「库里存的 == 用户看到的」。
        AtomicReference<String> overrideRef = new AtomicReference<>();
        AtomicBoolean persisted = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();

        Flux<LlmChunk> upstream = streamWithRetry(messages, emitted, 0, sessionId, traceId,
                        thinking.enabled())
                .doOnNext(c -> {
                    if (c.hasUsage()) {
                        usageRef.set(c.usage());
                    }
                    if (c.finishReason() != null) {
                        upstreamFinish.set(c.finishReason());
                    }
                    if (c.hasReasoning()) {
                        reasoningChars.addAndGet(c.reasoning().length());
                    }
                });

        Flux<ServerSentEvent<String>> body = upstream
                .concatMap(c -> Flux.fromIterable(chunkEvents(c)))
                .concatWith(Flux.defer(() -> Flux.fromIterable(tailEvents(
                        upstreamFinish, seen, overrideRef, truncated, errRef, sessionId, traceId,
                        referralAccepted))))
                .onErrorResume(e -> degrade(e, errRef, sessionId, traceId));

        return Flux.concat(Flux.fromIterable(headEvents(session, userMsgId, traceId, stage, retrieved)), body)
                .doOnNext(ev -> {
                    if (EV_TOKEN.equals(ev.event())) {
                        String text = ev.data() == null ? "" : ev.data();
                        if (!text.isEmpty() && firstTokenAt.get() == 0L) {
                            firstTokenAt.set(System.currentTimeMillis() - startedAt);
                        }
                        seen.append(text);
                    }
                })
                .doFinally(signal -> {
                    if (!persisted.compareAndSet(false, true)) {
                        return;
                    }
                    finishTurn(session, residentId, traceId, signal, seen, overrideRef.get(),
                            errRef.get(), truncated.get(), usageRef.get(), reasoningChars.get(),
                            firstTokenAt.get(), startedAt);
                });
    }

    /**
     * 一次上游调用，失败按分类重试。
     *
     * <p>重试闸门是「已下发正文帧数为 0」：流式响应与普通请求最大的区别是
     * <b>部分结果已经交付给用户</b>，不可回滚。若在吐了半句话之后再重订阅，
     * 用户会看到同一段话被说两遍。
     *
     * <p>400 走特殊策略：压缩历史后重试。DeepSeek 的 400 有两种语义
     * （模型名失效 / 上下文超长），归类在 {@link LlmErrorMapper} 完成，
     * 到这里只剩最省事的判断 —— 还有历史可压就压一半再试，
     * 压不动了就直接失败（重试同一个请求必定同样失败）。
     *
     * @param thinkingEnabled 本轮是否开启思考；由 {@link ThinkingPolicy} 在调用方定好，
     *                        这里只负责<b>原样透传</b>。重试必须沿用同一个值 ——
     *                        若重试时改用另一种模式，「失败原因」与「成功那次的行为」
     *                        就分不清了，而思考开关正是最可能影响成败的变量之一。
     */
    private Flux<LlmChunk> streamWithRetry(List<ChatMessage> messages, AtomicInteger emitted,
                                           int attempt, Long sessionId, UUID traceId,
                                           boolean thinkingEnabled) {
        return aiChatClient.chatStream(messages, thinkingEnabled)
                .doOnNext(c -> {
                    if (c.hasContent()) {
                        emitted.incrementAndGet();
                    }
                })
                .onErrorResume(e -> {
                    LlmException le = errorMapper.map(e);
                    int maxRetries = llmProps.getMaxRetries() == null ? 0 : llmProps.getMaxRetries();
                    boolean retryable = le.isRetryable() && attempt < maxRetries && emitted.get() == 0;
                    if (!retryable) {
                        return Flux.error(le);
                    }
                    List<ChatMessage> next = le.getCode() == LlmErrorCode.LLM_BAD_REQUEST
                            ? shrink(messages) : messages;
                    if (next.size() >= messages.size()) {
                        log.warn("[AI 问诊] {} 但历史已无可压缩，放弃重试 session={} trace={} 原因={}",
                                le.getCode(), sessionId, traceId, le.getMessage());
                        return Flux.error(le);
                    }
                    long base = llmProps.getRetryBackoffMs() == null ? 500L : llmProps.getRetryBackoffMs();
                    long delay = Math.max(0L, base) * (1L << attempt);
                    log.warn("[AI 问诊] 上游失败将重试：第 {} 次 code={} 退避={}ms session={} trace={} 原因={}",
                            attempt + 1, le.getCode(), delay, sessionId, traceId, le.getMessage());
                    return Mono.delay(Duration.ofMillis(delay))
                            .thenMany(Flux.defer(() -> streamWithRetry(next, emitted, attempt + 1,
                                    sessionId, traceId, thinkingEnabled)));
                });
    }

    /**
     * 压缩上下文：保留 system + 最近一半历史 + 当前问题。
     *
     * <p>system 的位置不动 —— 提示词前缀的字节级稳定是 DeepSeek 自动上下文缓存
     * 能否命中的唯一条件（见 5.1）。压缩只能从中间的历史下手。
     *
     * @return 压缩后的消息列表；已无可压时原样返回（调用方据此放弃重试）
     */
    private List<ChatMessage> shrink(List<ChatMessage> messages) {
        if (messages.size() <= 3) {
            return messages;
        }
        int middle = messages.size() - 2;
        int keep = Math.max(2, middle / 2);
        if (keep >= middle) {
            return messages;
        }
        List<ChatMessage> out = new ArrayList<>(keep + 2);
        out.add(messages.get(0));
        out.addAll(messages.subList(messages.size() - 1 - keep, messages.size()));
        return out;
    }

    /**
     * 正常完成后的收尾事件：截断判定 → 输出侧危机替换 → 空回复兜底 → 推荐信号 → done。
     *
     * <p><b>截断判定</b>：{@code take(Duration)} 到点会以「正常完成」收尾，信号类型上看不出
     * 「被截断」，只能用「有没有收到过上游的 finish_reason」判定 —— 没有就是被整体超时砍断的。
     * 不判这一条，截断的回复会被记成 {@code DONE}，截断率这个指标就永远算不出来。
     *
     * <p><b>为什么危机替换在这里做，而不是落库时</b>：替换后的正文必须让用户看到，
     * 而落库发生在 {@code doFinally} —— 那时响应已经结束，任何事件都发不出去。
     * 在这里判定并下发 {@code override}，前端用它覆盖气泡，落库也用同一个值，
     * 「用户读到的」与「库里存的」由构造保证一致。
     *
     * <p><b>为什么这里能读到完整的 {@code seen}</b>：{@code tailEvents} 由 {@code Flux.defer}
     * 在 {@code concatWith} 的切换点求值，而切换点一定在上游全部 {@code onNext} 都已下发之后
     * （响应式规范要求 {@code onComplete} 晚于所有 {@code onNext}，`concatMap` 的缓冲也不例外）。
     * 所以「累计 → 判定 → 下发 override」这条链不需要额外同步。
     *
     * <p><b>为什么 {@code recommend} 也在这里发</b>：它必须晚于全部 {@code token}。
     * 前端收到它就会在「本轮回复结束」时弹分析动画，若提前下发，
     * 动画会与还在往外流的字叠在一起。
     *
     * @param upstreamFinish   上游最后给出的 finish_reason；为 null 说明是被整体超时截断的
     * @param seen             用户此刻已看到的正文
     * @param referralAccepted 本轮用户是否答应了接受医生推荐（见 {@link #acceptReferral}）
     */
    private List<ServerSentEvent<String>> tailEvents(AtomicReference<String> upstreamFinish,
                                                     StringBuilder seen,
                                                     AtomicReference<String> overrideRef,
                                                     AtomicBoolean truncated,
                                                     AtomicReference<LlmException> errRef,
                                                     Long sessionId, UUID traceId,
                                                     boolean referralAccepted) {
        List<ServerSentEvent<String>> tail = new ArrayList<>(5);
        String text = seen.toString();

        if (upstreamFinish.get() == null) {
            truncated.set(true);
            log.warn("[AI 问诊] 上游未给出 finish_reason，判定为整体超时截断：session={} trace={} 已生成={}字",
                    sessionId, traceId, text.length());
        }

        if (text.isEmpty()) {
            errRef.compareAndSet(null, new LlmException(LlmErrorCode.LLM_NO_CONTENT,
                    "上游正常结束但未产出正文（finish_reason=" + upstreamFinish.get()
                            + "，常见成因是 max_tokens 被思考过程耗尽）"));
            tail.add(sse(EV_TOKEN, EMPTY_REPLY_TEXT));
        } else if (crisisDetector.harmful(text)) {
            // ⚠️ 判据必须是 harmful 而不是 hit。
            // hit 是「出现危机词」，而 L2 第 3 条**要求**模型在风险语境下建议拨打 12356、
            // L4 第 4 阶段还要求它主动询问是否有伤害自己的念头 —— 用 hit 判输出，
            // 每一段合规回复都会被替换掉，用户看到「聊得好好的，AI 突然说已为你转接人工」。
            // harmful 要求「危机词 + 方法/剂量类操作性词在同一短窗口内出现」，
            // 因此只拦真正的自伤指引与教唆，放行合规的关怀与热线建议。
            String safe = crisisDetector.safetyNotice();
            overrideRef.set(safe);
            log.warn("[AI 问诊] 模型回复含可操作的自伤指引，替换为固定安全文案：session={} trace={} 原文={}字",
                    sessionId, traceId, text.length());
            tail.add(crisisEvent("OUTPUT"));
            tail.add(sse(EV_OVERRIDE, safe));
        }

        // 推荐信号。两种情况下**不发**：
        // ① 走了输出侧安全护栏（overrideRef 非空）—— 模型刚说了危险内容，
        //    此时顺势把人推进推荐流程，等于用一次正常流程掩盖一次安全事件；
        // ② 本轮回复为空（上面已兜底成 EMPTY_REPLY_TEXT）—— 用户没读到任何有效回应，
        //    直接跳走会让人莫名其妙。
        if (referralAccepted && overrideRef.get() == null && !text.isEmpty()) {
            tail.add(recommendEvent(sessionId));
            log.info("[AI 问诊] 已下发推荐信号 session={} trace={}", sessionId, traceId);
        }

        tail.add(sse(EV_DONE, DONE_PAYLOAD));
        return tail;
    }

    /**
     * 正文之前的事件：{@code meta} 必发，{@code citations} 有资料才发。
     *
     * <p>与 {@link #tailEvents} 对称：这是「流开始前就已经确定」的信息，
     * 那是「要等正文流完才知道」的信息。分开的好处是新增事件时不用判断
     * 「该放前面还是后面」—— 看信息何时确定即可。
     *
     * @param retrieved 本轮检索结果（可能为空；为空则不发 citations）
     */
    private List<ServerSentEvent<String>> headEvents(ConsultSession session, Long userMsgId,
                                                     UUID traceId, StageState stage,
                                                     List<RetrievedChunk> retrieved) {
        List<ServerSentEvent<String>> head = new ArrayList<>(2);
        head.add(meta(session, userMsgId, traceId, stage));
        if (retrieved != null && !retrieved.isEmpty()) {
            head.add(citationsEvent(retrieved));
        }
        return head;
    }

    /**
     * {@code citations} 事件：把本轮注入提示词的资料整理成前端可渲染的引用列表。
     *
     * <h4>只下发「够渲染卡片」的字段，不下发正文</h4>
     * <p>每条只给 {@code docTitle} / {@code section} / {@code chunkId} / {@code score}
     * 与一小段 {@code snippet}。完整的父块正文（可达 1400 字）已经进过提示词了，
     * 再往 SSE 里塞一遍纯属重复传输；而且引用卡片的用途是「让用户知道
     * 『AI 是读了什么才这么说的』」，一条摘要足够，给全文反而会让气泡被资料淹没。
     *
     * <h4>为什么 {@code score} 与 {@code denseScore} 都发但前端只用 first</h4>
     * <p>前端展示的是「资料名 + 章节」，相似度只作为一个可选的调试字段。
     * 两个分数都发是因为它们的语义不同（融合分排序用、稠密分过滤用），
     * 排障时看到「这条是稀疏路召回的（denseScore=null）」能立刻解释
     * 「为什么它的分看起来很不像」。
     *
     * <h4>{@code synthetic} 必须下发（风险 R5）</h4>
     * <p>合成语料是「模型关于医疗知识的记忆的再表述」，<b>可能包含错误</b>。
     * 前端必须给它打「合成示例数据」角标 —— 用户有权知道这段内容不是权威来源。
     * 上线前由 rebuild 流程整体替换成真实语料，届时该角标自然不会出现。
     */
    private ServerSentEvent<String> citationsEvent(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> items = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.chunkId());
            m.put("docId", c.docId());
            m.put("docTitle", c.docTitle());
            m.put("section", c.section());
            m.put("snippet", snippet(c.content()));
            m.put("score", c.score());
            m.put("denseScore", c.denseScore());
            m.put("synthetic", c.synthetic());
            items.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        return sse(EV_CITATIONS, toJson(payload));
    }

    /**
     * 引用卡片的摘要片段。
     *
     * <p>截断长度按<b>字符</b>而非 token：这里只用于展示，不必与模型的计量单位一致；
     * 且中文场景下「字数」比 token 数更贴近用户感知的长度。
     * 去掉首尾空白与换行折叠，避免卡片里出现一大片空行。
     */
    private static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= CITATION_SNIPPET_CHARS
                ? flat : flat.substring(0, CITATION_SNIPPET_CHARS) + "…";
    }

    /**
     * 降级：把上游异常转成「一条错误事件 + 一段能读的话」。
     *
     * <p>错误事件的 data 只放归类后的中文文案与错误码，<b>不放上游响应体</b> ——
     * 上游 body 里带 request_id 与内部细节，对用户无意义，回显到浏览器只是信息泄露面。
     * 技术细节进日志与 {@code ai_usage_log.error_detail}。
     */
    private Flux<ServerSentEvent<String>> degrade(Throwable e, AtomicReference<LlmException> errRef,
                                                  Long sessionId, UUID traceId) {
        LlmException le = errorMapper.map(e);
        errRef.compareAndSet(null, le);
        log.warn("[AI 问诊] 降级 session={} trace={} code={} 状态={} 原因={}",
                sessionId, traceId, le.getCode(), le.getUpstreamStatus(), le.getMessage());

        return Flux.just(
                sse(EV_ERROR, errorJson(le)),
                sse(EV_TOKEN, FALLBACK_TEXT),
                sse(EV_DONE, DONE_PAYLOAD));
    }

    /**
     * 收尾：落库 + 记用量。
     *
     * <p>用量<b>无论成败都记</b>：失败的调用同样消耗 token（思考过程也计费），
     * 只统计成功请求，成本报表会系统性偏低 —— 而上游一挂就疯狂重试正是账单暴涨的典型场景。
     */
    private void finishTurn(ConsultSession session, Long residentId, UUID traceId, SignalType signal,
                            StringBuilder seen, String override, LlmException err, boolean truncated,
                            LlmUsage usage, int reasoningChars, long firstTokenAt, long startedAt) {
        Long sessionId = session.getId();
        // 落库内容 = 用户最终看到的内容：有 override 就是改写后的正文
        String reply = override != null ? override : seen.toString();
        boolean crisisReplaced = override != null;
        int totalMs = (int) (System.currentTimeMillis() - startedAt);
        Integer firstMs = firstTokenAt == 0L ? null : (int) firstTokenAt;

        if (reply.isEmpty()) {
            log.warn("[AI 问诊] 未产出任何正文：session={} trace={} signal={} code={}",
                    sessionId, traceId, signal, err == null ? null : err.getCode());
        } else {
            saveMessage(sessionId, ROLE_AI, null, reply, crisisReplaced ? 1 : 0,
                    crisisReplaced ? FINISH_CRISIS : resolveFinish(signal, err, truncated));
        }

        usageRecorder.record(sessionId, residentId, usage, firstMs, totalMs, err);

        log.info("[AI 问诊] 结束 session={} trace={} signal={} 正文={}字 思考={}字 首帧={}ms 总={}ms code={}{}",
                sessionId, traceId, signal, reply.length(), reasoningChars, firstMs, totalMs,
                err == null ? "OK" : err.getCode(), crisisReplaced ? " 已危机替换" : "");

        triggerSummaryIfNeeded(session);
    }

    /**
     * 判断是否需要做会话摘要压缩。
     *
     * <p>放在落库之后统计消息数，所以这个数字包含本轮刚落的两条消息。
     * <b>整段包在 try 里</b>：摘要是「锦上添花」的后台任务，
     * 它不该让一次已经成功返回给用户的问答在日志里留下异常 ——
     * 更不该因为一次 count 查询失败就让 doFinally 把异常抛进响应式链路。
     */
    private void triggerSummaryIfNeeded(ConsultSession session) {
        try {
            Long total = messageMapper.selectCount(new LambdaQueryWrapper<ConsultMessage>()
                    .eq(ConsultMessage::getSessionId, session.getId()));
            summarizer.maybeCompress(session, total == null ? 0 : total.intValue());
        } catch (Exception e) {
            log.warn("[AI 问诊] 摘要触发判断失败（不影响对话）session={} 原因={}",
                    session.getId(), e.getMessage());
        }
    }

    /** 终止信号 + 是否被截断 + 是否有错误 → 落库的 finish_reason */
    private String resolveFinish(SignalType signal, LlmException err, boolean truncated) {
        if (err != null) {
            return FINISH_ERROR;
        }
        if (truncated || signal == SignalType.CANCEL) {
            return FINISH_INTERRUPTED;
        }
        if (signal == SignalType.ON_COMPLETE) {
            return FINISH_DONE;
        }
        return FINISH_ERROR;
    }

    // =================================================================
    // SSE 事件构造
    // =================================================================

    /** 一帧 LlmChunk → 0~2 个 SSE 事件 */
    private List<ServerSentEvent<String>> chunkEvents(LlmChunk chunk) {
        List<ServerSentEvent<String>> out = new ArrayList<>(2);
        // reasoning 不透出。心理问诊场景里，让居民看到「模型正在分析他是不是抑郁」
        // 既冒犯也不安全；但它已被计数并入日志，用于解释首帧延迟与空回复的成因。
        if (chunk.hasContent()) {
            out.add(sse(EV_TOKEN, chunk.content()));
        }
        if (chunk.hasUsage()) {
            out.add(sse(EV_USAGE, usageJson(chunk.usage())));
        }
        return out;
    }

    /**
     * {@code meta} 事件：本轮标识 + 当前引导阶段。
     *
     * <h4>为什么阶段信息塞在 meta 里，而不是新开一个 {@code stage} 事件</h4>
     * <p>阶段是在<b>请求开始前</b>就定下来的（见 {@link #chatStream}：先推进阶段，
     * 再组装提示词），所以它天然属于「本轮元信息」，与 sessionId / model 同类。
     * 每新增一种事件类型，前后端就多一处必须同步的契约；能不加就不加。
     * 只有「流进行到一半才出现」的信息才值得独立事件（比如 {@code override}）。
     */
    private ServerSentEvent<String> meta(ConsultSession session, Long userMsgId, UUID traceId,
                                         StageState stage) {
        InterviewStage st = stage == null || stage.getStage() == null
                ? InterviewStage.OPENING : stage.getStage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", session.getId());
        m.put("userMessageId", userMsgId);
        m.put("traceId", traceId.toString());
        m.put("model", llmProps.getModel());
        m.put("stage", st.name());
        m.put("stageLabel", st.getLabel());
        m.put("stageStep", st.getStep());
        m.put("stageTotal", InterviewStage.TOTAL_STEPS);
        m.put("stageGoal", st.getGoal());
        return sse(EV_META, toJson(m));
    }

    /**
     * {@code crisis} 事件：本轮回复是危机文案，前端应把它渲染成危机态。
     *
     * <h4>为什么必须由服务端显式下发，而不是让前端从正文里猜</h4>
     * <p>前端原先的做法是「正文里出现 {@code 12356} 就算危机」。而 L2 第 3 条
     * 恰恰<b>要求</b>模型建议拨打 12356 —— 于是模型每一次合规的热线提示都会命中
     * 那个子串判断：气泡变红、右侧弹出「系统已为你转接人工」。
     * 拿一个业务上<b>鼓励出现</b>的内容当危机标志，等于把「正常」判成了「异常」。
     *
     * <p>危机与否只有服务端知道（是否走了短路、是否发生了替换），就该由服务端说，
     * 前端不该反推。<b>reason 也一并给出</b>：
     * <ul>
     *   <li>{@code INPUT} —— 居民自己表达了危机。会建 CRISIS 工单，
     *       所以文案可以承诺「已为你转接人工」；</li>
     *   <li>{@code OUTPUT} —— 模型输出被护栏拦下。不建工单，
     *       前端就不该承诺转接（一个不会兑现的承诺比不承诺更伤人）。</li>
     * </ul>
     */
    private ServerSentEvent<String> crisisEvent(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        return sse(EV_CRISIS, toJson(m));
    }

    /**
     * {@code recommend} 事件：用户已同意接受医生推荐，前端可以进入「分析 → 跳转」流程。
     *
     * <h4>为什么由服务端发，而不是前端自己判断</h4>
     * <p>前端手上只有「用户说了什么」，而应允是一个<b>一问一答</b>的判定：
     * 必须先确认 AI 上一轮问过「需要我为你推荐合适的医生吗」，用户那句「好的」
     * 才算应允。少了这一半，任何一轮里的一句「好的」都会被误读成要去看医生。
     *
     * <p>这与 {@code crisis} 事件的取舍完全一致 —— 判定权归服务端，前端只负责渲染。
     * 而且跳转意味着<b>结束会话</b>，这种不可逆的动作更不该由前端的启发式规则触发。
     *
     * <p>data 里带上 sessionId：前端拿到它就能直接跳去推荐页（推荐页要用它取会话关联的测评），
     * 不必再依赖 store 里的当前会话 —— 此刻会话马上就要被关闭了。
     */
    private ServerSentEvent<String> recommendEvent(Long sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        return sse(EV_RECOMMEND, toJson(m));
    }

    private String errorJson(LlmException le) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", le.getCode() == null ? LlmErrorCode.LLM_UNKNOWN.name() : le.getCode().name());
        m.put("msg", le.getCode() == null ? "模型服务异常" : le.getCode().getUserMsg());
        m.put("retryable", le.isRetryable());
        return toJson(m);
    }

    private String usageJson(LlmUsage usage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptTokens", usage.promptTokens());
        m.put("completionTokens", usage.completionTokens());
        m.put("cachedTokens", usage.cachedTokens());
        m.put("reasoningTokens", usage.reasoningTokens());
        m.put("totalTokens", usage.totalTokens());
        return toJson(m);
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            // 序列化自己的 Map 若失败，说明写了不可序列化的值；返回最小可用 JSON 而不是抛错，
            // 否则一个 meta 事件就能让整轮对话挂掉
            log.error("[AI 问诊] SSE 事件序列化失败：{}", e.getMessage());
            return "{}";
        }
    }

    // =================================================================
    // 上下文组装与落库
    // =================================================================

    /**
     * 组装一次请求的消息列表。分层顺序（常量 → 档案 → 资料 → 历史 → 当前）见
     * {@link ContextAssembler} —— 那个顺序是 DeepSeek 前缀缓存能否命中的前提。
     *
     * @param assessment 会话关联的测评，可为 null（本次对话不是从测评发起的）。
     *                   <b>由调用方传入而不是在这里现查</b>：同一个对象这轮还要用于
     *                   {@link ThinkingPolicy} 的风险等级判定，两处各查一次就是每轮
     *                   多打一次 {@code assessment} 表。
     * @param retrieved  本轮检索结果。<b>由调用方传入而不是在这里现查</b>：
     *                   同一批结果还要用于 citations 事件，见 {@code llmStream} 里的说明。
     */
    private List<ChatMessage> buildMessages(ConsultSession session, String currentContent,
                                            StageState stage, Long currentUserMsgId,
                                            Assessment assessment,
                                            List<RetrievedChunk> retrieved) {
        return contextAssembler.assemble(new ContextAssembler.Context(
                assessment == null ? null : scaleNameOf(assessment.getScaleId()),
                assessment == null ? null : assessment.getTotalScore(),
                // 没有测评背景时**传 null 而不是 RiskLevel.A**：
                // A 的语义是「无风险」，注进去等于凭空多出一句「系统评估风险等级为 A」，
                // 而这次对话根本没有测评数据支撑 —— 那是伪造上下文
                assessment == null ? null : assessment.getRiskLevel(),
                stage == null ? InterviewStage.OPENING : stage.getStage(),
                stage == null ? Set.of() : stage.getFields(),
                session.getSummary(),
                // 把已算好的医生推荐注入 L5b，模型才能在正文里直接介绍医生
                recommendForContext(session.getId()),
                loadHistory(session.getId(), currentUserMsgId),
                toPromptLines(retrieved),
                currentContent));
    }

    /**
     * L6 检索资料 → 提示词行（Phase 4）。
     *
     * <h3>为什么用「本轮用户输入」而不是整段历史去检索</h3>
     * <p>检索的语义是「回答<b>这一句</b>需要什么资料」。把历史拼进去会让向量偏向
     * 前面聊过的内容，越聊越偏离当前问题（长对话里尤其明显）。
     * 多轮指代（「它有什么副作用」）由查询改写解决，不靠拼历史。
     * —— 这也是 {@code retrieveRaw} 的调用点只在 {@code llmStream} 里的原因：
     * 它拿到的就是 {@code content}（本轮输入），不是拼过的历史。
     *
     * <h3>为什么这里不再做 try/catch</h3>
     * <p>{@link RetrievalPipeline#retrieve} 的契约是「失败返回空列表、绝不抛异常」。
     * 在这里再包一层会让「管线真的坏了」被静默吞掉 —— 而管线内部已经按其
     * 自身的语义分了 Warn 级日志，谁坏了查谁。
     */
    private List<String> toPromptLines(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            lines.add(c.toPromptLine());
        }
        log.info("[RAG] 注入 L6 资料 {} 条（命中 chunkId={}）",
                lines.size(), chunks.stream().map(RetrievedChunk::chunkId).toList());
        return lines;
    }

    /**
     * 本轮检索到的资料（含正文），供 L6 提示词与 SSE {@code citations} 事件共用。
     *
     * <p><b>每轮只调一次</b>，调用点在 {@link #llmStream}。不要在这里缓存或用成员变量存 ——
     * 本类是无状态单例，成员变量会让并发会话互相串数据（居民的 A 问题引用到 B 问题的资料）。
     */
    private List<RetrievedChunk> retrieveRaw(String currentContent) {
        return retrievalPipeline.retrieve(currentContent);
    }

    /**
     * 可转介的医生名单（供 L5b 注入）。
     *
     * <p><b>整体 try/catch</b>：推荐要读测评、量表与医生表，任何一步出问题都只是
     * 「这一轮没有转介名单」，不该让正在倾诉的人收到报错 ——
     * 与阶段状态同一原则：非核心依赖失败必须降级，不能把 AI 问诊整体拖垮。
     *
     * <p>顺带也兜住 {@code SecurityUtil}：它读的是登录态 ThreadLocal，
     * 而这里运行在请求线程上（{@code chatStream} 的同步调用链内）；
     * 万一将来被挪进响应式线程，取不到登录态时也只是降级而不是 500。
     */
    private List<RecommendResult> recommendForContext(Long sessionId) {
        try {
            // 用「只算不记」的入口：注进 prompt 的是素材，不是一次推荐事件。
            // 若走 recommendForCurrentResident，每轮都会给 3 位医生各写一条
            // recommendation_log，几轮就把「推荐了什么、有没有被接受」的统计口径稀释掉。
            List<RecommendResult> list =
                    recommendService.recommendForContext(sessionId, RECOMMEND_TOP_N);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("[AI 问诊] 医生推荐不可用，本轮不注入转介名单 session={} 原因={}",
                    sessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 历史消息（时间正序，<b>不含本轮</b>）。
     *
     * <p>三处与改造前不同：
     * <ol>
     *   <li>排序用 {@code id} 而不是 {@code created_at}：同一秒内落的两条消息时间戳相同，
     *       按时间排序结果不稳定，会出现「AI 的回复排在用户提问之前」这种错乱；</li>
     *   <li><b>必须排除本轮已落库的那条</b>（{@code excludeMsgId}）。调用方是先落库
     *       （保证用户输入不丢）再组装上下文，所以本轮消息此刻已经在表里，
     *       不排除就会在 prompt 里出现两次：历史里一次 + L8 当前输入一次。
     *       模型看到用户把同一句话说了两遍，会倾向于「回应那句重复」或合并作答 ——
     *       而且白付一份 token。{@code Context.history} 的注释一直写的是「不含本轮」，
     *       这里之前与注释不符；</li>
     *   <li><b>不在这里裁剪</b>，只按 {@code max-history} 取个上限。真正的裁剪交给
     *       {@link ContextAssembler} 按 token 预算做 —— 条数在中文场景下并不能代表长度。</li>
     * </ol>
     */
    private List<ChatMessage> loadHistory(Long sessionId, Long excludeMsgId) {
        int pairs = llmProps.getMaxHistory() == null ? 10 : llmProps.getMaxHistory();
        LambdaQueryWrapper<ConsultMessage> q = new LambdaQueryWrapper<ConsultMessage>()
                .eq(ConsultMessage::getSessionId, sessionId)
                .orderByDesc(ConsultMessage::getId)
                .last("limit " + (pairs * 2));
        if (excludeMsgId != null) {
            q.ne(ConsultMessage::getId, excludeMsgId);
        }
        List<ConsultMessage> rows = messageMapper.selectList(q);
        List<ChatMessage> out = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            ConsultMessage m = rows.get(i);
            out.add(new ChatMessage(ROLE_AI.equals(m.getSender()) ? "assistant" : "user", m.getContent()));
        }
        return out;
    }

    /**
     * 量表名（如「PHQ-9 抑郁筛查量表」）。
     *
     * <p>此前这里恒传 {@code null}，system prompt 里永远写「该居民刚完成 心理测评」——
     * 模型因此不知道做的是抑郁还是焦虑筛查，追问方向会跑偏。
     * 查不到（scaleId 为空或量表被删）时返回 null，由 ContextAssembler 退化为「心理测评」。
     */
    private String scaleNameOf(Long scaleId) {
        if (scaleId == null) {
            return null;
        }
        Scale scale = scaleMapper.selectById(scaleId);
        return scale == null ? null : scale.getName();
    }

    private void triggerCrisisAlert(Long residentId, ConsultSession session) {
        AlertRecord record = new AlertRecord();
        record.setResidentId(residentId);
        record.setAssessmentId(session.getAssessmentId());
        record.setAlertType(AlertType.CRISIS);
        record.setAlertLevel(RiskLevel.D);
        record.setStatus(AlertStatus.PENDING);
        record.setHandleNote("AI 问诊命中危机词，需人工立即介入");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.insert(record);
    }

    /**
     * 落一条消息。
     *
     * @param finishReason 仅 AI 回复有值（DONE/INTERRUPTED/CRISIS/ERROR）；
     *                     居民发言传 null —— 该字段描述「模型这条回复完不完整」，
     *                     对用户发言没有意义
     * @return 新消息 id（回填自 insert），供 meta 事件使用
     */
    private Long saveMessage(Long sessionId, String sender, Long senderId, String content,
                             int crisisFlag, String finishReason) {
        ConsultMessage msg = new ConsultMessage();
        msg.setSessionId(sessionId);
        msg.setSender(sender);
        msg.setSenderId(senderId);
        msg.setContent(content);
        msg.setCrisisFlag(crisisFlag);
        msg.setFinishReason(finishReason);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        return msg.getId();
    }
}
