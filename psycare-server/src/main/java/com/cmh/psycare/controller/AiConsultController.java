package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.dto.AiChatRequest;
import com.cmh.psycare.dto.AiSessionDetailVO;
import com.cmh.psycare.dto.AiSessionVO;
import com.cmh.psycare.entity.ConsultSession;
import com.cmh.psycare.service.AiConsultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * AI 问诊接口（流式）。
 *
 * <h3>SSE 事件协议</h3>
 * <p>{@code /chat} 返回 {@code Flux<ServerSentEvent<String>>}，事件类型见
 * {@code SSE事件协议.md}：{@code meta} / {@code token} / {@code usage} /
 * {@code override} / {@code error} / {@code done}。
 * 前端必须按 {@code event:} 分支处理 —— 改造前只认 {@code data:}，
 * 会把 {@code error} 事件里的 JSON 当成正文拼进气泡。
 *
 * <p>Spring MVC 6.1 的 {@code ReactiveTypeHandler$SseEmitterSubscriber} 原生支持
 * {@code ServerSentEvent} 返回值（已核实字节码中引用该类 9 次），
 * 不需要手写 {@code SseEmitter}。
 */
@RestController
@RequestMapping("/ai-consult")
@RequiredArgsConstructor
public class AiConsultController {

    private final AiConsultService aiConsultService;

    /** 开启 AI 问诊会话（可关联测评记录/预警工单） */
    @PostMapping("/open")
    public Result<Map<String, Long>> open(@RequestBody(required = false) Map<String, Long> body) {
        Long assessmentId = body != null ? body.get("assessmentId") : null;
        Long alertId = body != null ? body.get("alertId") : null;
        ConsultSession session = aiConsultService.openSession(assessmentId, alertId);
        return Result.ok(Map.of("sessionId", session.getId()));
    }

    /** 流式对话（SSE）：前端用 fetch 读取事件流 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody AiChatRequest req) {
        return aiConsultService.chatStream(req.getSessionId(), req.getContent());
    }

    /** 结束会话 */
    @PostMapping("/{sessionId}/close")
    public Result<Map<String, Boolean>> close(@PathVariable Long sessionId) {
        aiConsultService.closeSession(sessionId);
        return Result.ok(Map.of("ok", true));
    }

    /**
     * 清空会话的聊天记录（保留上下文摘要）。
     *
     * <p>用 POST 而不是 DELETE：它不是幂等的资源删除 —— 返回体要带
     * 「这次删掉了多少条」，而且重复调用时后续几次都是删 0 条。
     * 更重要的是归属校验（服务层 {@code requireMember}）对两种方法一视同仁，
     * 换 Method 并不能多一层保护，没必要为了语义好看引入 DELETE +
     * 一个可能为空的响应体。
     *
     * <p>返回删除条数：前端据此提示「已清空 N 条」或「当前没有记录可清」，
     * 比一个笼统的 ok 更能说明到底发生了什么。
     */
    @PostMapping("/{sessionId}/clear-messages")
    public Result<Map<String, Integer>> clearMessages(@PathVariable Long sessionId) {
        return Result.ok(Map.of("deleted", aiConsultService.clearMessages(sessionId)));
    }

    /**
     * 当前用户的历史 AI 会话列表。
     *
     * <p>居民 id 取自登录态（服务层），请求里没有任何可用来指定「查谁」的参数 ——
     * 这类「列表接口」最容易漏校验，而漏了就是全量泄露他人的倾诉记录。
     */
    @GetMapping("/sessions")
    public Result<List<AiSessionVO>> sessions() {
        return Result.ok(aiConsultService.listSessions());
    }

    /** 某个会话的消息明细（含当前引导阶段）。服务层会做归属校验，越权返回 403 */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<AiSessionDetailVO> sessionMessages(@PathVariable Long sessionId) {
        return Result.ok(aiConsultService.sessionDetail(sessionId));
    }
}
