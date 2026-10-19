package com.cmh.psycare.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的流式客户端。
 *
 * <h3>为什么是「一个泛化类」而不是「每个供应商一个类」</h3>
 * <p>原 {@code DeepSeekClient} 里没有一行 DeepSeek 专有逻辑 ——
 * 请求体结构、{@code choices[0].delta.content} 的解析路径、{@code data: [DONE]} 的收尾，
 * 全是 OpenAI 兼容协议本身。所以新增 {@code QwenClient} / {@code GlmClient} 只会得到
 * 几个 99% 相同的类。<b>把差异收进配置（base-url / path / model / effort），把共性收进一个类</b>，
 * 换供应商就是改 yml。
 *
 * <h3>代码里不再出现 "/v1"</h3>
 * <p>此前 {@code .uri("/v1/chat/completions")} 是硬编码的。一旦 base-url 自带版本段
 * （{@code .../compatible-mode/v1}、{@code .../v1}），就会双拼版本段报 404，
 * 而报错只说「路径不存在」。现在 {@code base-url} 到版本段为止、{@code path} 从版本段之后开始，
 * 由 {@link LlmProperties} 统一约定。
 *
 * <h3>两道时间闸（缺一不可）</h3>
 * <pre>
 *   .timeout(firstTokenSeconds)  // 元素间超时：首帧来自订阅起算，之后按帧间隔算
 *   .take(timeoutSeconds)        // 整体上限：到点直接截断
 * </pre>
 * <p>「15 秒不吐第一个字」与「吐到一半卡住」是两种故障：
 * 前者要立刻降级（用户等待成本最低），后者要断开（继续等已经无意义）。
 * 只设整体超时，会把「首帧卡死」也拖到 60 秒才反应 —— 这正是改造前体验最差的地方。
 *
 * <p>注意 Reactor 的 {@code timeout(Duration)} 语义是<b>元素间</b>超时（含首个元素），
 * 不存在 {@code timeout(首信号, 后续信号)} 的双参数重载（那是 RxJava 的 API）。
 *
 * <p>{@code take(Duration)} 到点会以<b>正常完成</b>收尾而非报错，所以「被截断」这件事
 * 无法从信号类型看出来，只能靠「有没有收到过 finish_reason」判定 —— 该判定在
 * {@code AiConsultService} 里完成。
 *
 * <h3>第三个闸：思考开关（逐次请求，由调用方决定）</h3>
 * <p>首帧延迟的实测构成是「532 ms 固定 + 6.2 ms × 思考token」（corr = 0.968），
 * 固定项已无压缩空间，唯一有量级的杠杆是把思考关掉。开关按<b>方法参数</b>传入，
 * 不做成客户端状态 —— 见 {@code AiChatClient#chatStream} 的说明。
 * 具体策略（什么条件下关、什么条件下开回来）在 {@link ThinkingPolicy}。
 */
@Slf4j
@Component
public class OpenAiCompatClient implements AiChatClient {

    private static final String DONE = "[DONE]";

    private final LlmProperties props;
    private final LlmErrorMapper errorMapper;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatClient(LlmProperties props, LlmErrorMapper errorMapper) {
        this.props = props;
        this.errorMapper = errorMapper;
        // 用 trim 后的值：yml 里被引号包住的密钥可能带尾随空格，拼进 Bearer 头就是 401
        String key = props.resolvedApiKey();
        this.webClient = WebClient.builder()
                .baseUrl(props.resolvedBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // key 只打脱敏后的前 6 位 + 后 4 位：既能在多密钥时确认加载了哪一个，又不把凭证写进日志
        log.info("[LLM] 客户端初始化：base={} path={} model={} effort={} thinking={} maxTokens={} 首帧超时={}s 整体超时={}s key={}",
                props.getBaseUrl(), props.getPath(), props.getModel(),
                props.getEffort() == null ? "(服务端默认)" : props.getEffort(),
                props.resolvedThinking(),
                props.getMaxTokens(), props.getFirstTokenSeconds(), props.getTimeoutSeconds(),
                props.maskedKey());
    }

    @Override
    public Flux<LlmChunk> chatStream(List<ChatMessage> messages, boolean thinkingEnabled) {
        return webClient.post()
                .uri(props.getPath())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(buildPayload(messages, true, thinkingEnabled))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(props.getFirstTokenSeconds()))
                .take(Duration.ofSeconds(props.getTimeoutSeconds()))
                .takeWhile(data -> !DONE.equals(data.trim()))
                .mapNotNull(this::parseChunk)
                .onErrorMap(errorMapper::map);
    }

    /**
     * 非流式补全（见 {@link AiChatClient#complete}）。目前唯一调用方是会话摘要压缩。
     *
     * <p>超时用整体上限 {@code timeout-seconds} 而不是首帧超时：非流式没有「首帧」这个概念，
     * 响应是一次性回来的。
     *
     * <p><b>固定关思考</b>（第三个参数传 false）：摘要只是压缩复述已有对话，
     * 推理在这里买不到任何东西，而它是一次额外的、要花钱的请求。
     */
    @Override
    public Mono<String> complete(List<ChatMessage> messages) {
        return webClient.post()
                .uri(props.getPath())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(buildPayload(messages, false, false))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .map(this::extractContent)
                .onErrorMap(errorMapper::map);
    }

    /**
     * 请求体。流式与非流式<b>共用一份</b>，只有 {@code stream} 标志不同。
     *
     * <p>两处各写一遍必然出现「流式这边改了参数、非流式忘了改」的漂移，
     * 而这种漂移只在特定调用路径上出现，极难发现。
     *
     * <h3>思考开关与 effort 的关系（实测，非推断）</h3>
     * <p>两个参数都与思考有关，实测四种组合的结果：
     * <pre>
     *   thinking=enabled  + effort=low  思考  995 字，首帧 3782 ms   ← 开这条路实际发的
     *   thinking=enabled （不带 effort） 思考 1749 字，首帧 5704 ms
     *   thinking=disabled + effort=low  思考    0 字，首帧  813 ms
     *   thinking=disabled（不带 effort） 思考    0 字，首帧  847 ms   ← 关这条路实际发的
     * </pre>
     * 两条结论：
     * <ol>
     *   <li>{@code enabled} 与 {@code effort} <b>能正确叠加</b>：effort 在思考模式内部
     *       继续起作用（思考量近乎减半）。所以开思考时两个都发是对的。</li>
     *   <li>{@code disabled} 会压过 {@code effort}（不是反过来）。但<b>关思考时仍然不发
     *       {@code effort}</b>：那点差别在测得的误差范围内，而我们不该让「关掉思考」这件事
     *       依赖一条文档没写、只靠实验观察到的优先级规则。</li>
     * </ol>
     *
     * <p>另外，开思考时<b>显式</b>发 {@code thinking:{"type":"enabled"}}，不吃服务端默认值。
     * 量过：默认值一旦被上游改成 {@code disabled}，我们这条「保安全」的路径会
     * <b>静默退化成最速路径</b> —— 表现是「危机轮次的安全兜底忽然没了」，
     * 而日志、指标、响应码全都正常，没有任何东西提示你变过。
     *
     * <p>早前 {@code effort} 这个键名与官方文档的 {@code reasoning_effort} 不一致，
     * 曾担心被静默忽略；对照实验已排除该疑虑（{@code effort=low} 与
     * {@code reasoning_effort=low} 表现同量级），故保持原键名不动，避免无谓的配置变更。
     */
    private Map<String, Object> buildPayload(List<ChatMessage> messages, boolean stream,
                                             boolean thinkingEnabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModel());
        payload.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        payload.put("stream", stream);
        // 不给 max_tokens 就等于把「思考+正文」的总预算交给服务端默认；
        // 显式给足，避免推理模型的思考把正文挤没（见 LlmProperties#maxTokens）
        if (props.getMaxTokens() != null && props.getMaxTokens() > 0) {
            payload.put("max_tokens", props.getMaxTokens());
        }
        if (thinkingEnabled) {
            payload.put("thinking", Map.of("type", "enabled"));
            if (props.getEffort() != null && !props.getEffort().isBlank()) {
                payload.put("effort", props.getEffort());
            }
        } else {
            payload.put("thinking", Map.of("type", "disabled"));
        }
        // temperature 故意只在显式配置时才发：未核实的采样参数不写进默认路径
        if (props.getTemperature() != null) {
            payload.put("temperature", props.getTemperature());
        }
        return payload;
    }

    /**
     * 取非流式响应里的正文：{@code choices[0].message.content}。
     *
     * <p>取不到返回空串而不是抛错 —— 调用方（摘要压缩）对空结果的处理是
     * 「本轮不写摘要」，与「重试一次仍失败」的结果相同，少一层异常路径。
     */
    private String extractContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (Exception e) {
            log.warn("[LLM] 非流式响应解析失败：{}", e.getMessage());
            return "";
        }
    }

    /**
     * 启动探活：只调模型列表，不产生 token 费用。
     *
     * <p>Key 有效性与模型名有效性都能在这里判定，把「AI 不回话」这类问题
     * 从「用户发消息时」提前到「应用启动时」暴露。
     */
    @Override
    public LlmProbeResult probe() {
        // 先分辨「没配」与「配了但没换占位符」：两者都是 401，但下一步动作不同
        if (props.isPlaceholderKey()) {
            return LlmProbeResult.fail(LlmErrorCode.LLM_KEY_INVALID,
                    "psycare.llm.api-key 仍是占位符（" + props.maskedKey() + "），请替换为真实密钥。"
                            + "AI 对话将全部走降级话术。");
        }
        if (!props.hasKey()) {
            return LlmProbeResult.fail(LlmErrorCode.LLM_KEY_INVALID,
                    "未配置 API Key：psycare.llm.api-key 为空，请在 application.yml 里填入真实密钥。"
                            + "AI 对话将全部走降级话术。");
        }
        String modelsPath = props.resolvedModelsPath();
        // 打印完整 URL 而不是只有 path：404 时能一眼看出是「路径推导错了」还是「base-url 配错了」，
        // 只打 /chat/models 的话，两种成因长得一模一样
        String probeUrl = props.resolvedBaseUrl() + modelsPath;
        try {
            String body = webClient.get()
                    .uri(modelsPath)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            List<String> ids = new ArrayList<>();
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            for (JsonNode m : root.path("data")) {
                if (m.path("id").isTextual()) {
                    ids.add(m.get("id").asText());
                }
            }
            return LlmProbeResult.ok(ids);
        } catch (Exception e) {
            LlmException le = errorMapper.map(e);
            return LlmProbeResult.fail(le.getCode(), le.getMessage() + "（探活 URL " + probeUrl + "）");
        }
    }

    /**
     * 解析一帧 SSE 负载。
     *
     * <p>返回 null 表示这一帧没有业务信息（心跳、role 帧、无法解析的片段），
     * 由 {@code mapNotNull} 过滤 —— 不要抛异常，一帧脏数据不该打断整段回复。
     *
     * <p>注意 {@code usage} 只在最后一帧出现，且该帧的 {@code choices} 通常为空数组，
     * 所以三个字段要独立判断，不能「先取 choices[0]，没有就整帧丢弃」——
     * 那样会把 token 用量整段丢掉，成本报表直接归零。
     */
    private LlmChunk parseChunk(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choice = node.path("choices").path(0);
            JsonNode delta = choice.path("delta");

            String content = delta.path("content").isTextual() ? delta.get("content").asText() : null;
            String reasoning = delta.path("reasoning_content").isTextual()
                    ? delta.get("reasoning_content").asText() : null;
            String finish = choice.path("finish_reason").isTextual()
                    ? choice.get("finish_reason").asText() : null;
            LlmUsage usage = node.path("usage").isObject() ? LlmUsage.from(node.get("usage")) : null;

            if (content == null && reasoning == null && finish == null && usage == null) {
                return null;
            }
            return new LlmChunk(content, reasoning, finish, usage);
        } catch (Exception e) {
            log.debug("[LLM] 跳过无法解析的 SSE 帧：{}", data);
            return null;
        }
    }
}
