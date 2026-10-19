package com.cmh.psycare.ai.rag;

import com.cmh.psycare.ai.LlmErrorCode;
import com.cmh.psycare.ai.LlmErrorMapper;
import com.cmh.psycare.ai.LlmException;
import com.cmh.psycare.ai.LlmProbeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的向量化客户端（本项目用于阿里云百炼 / 通义）。
 *
 * <h3>为什么用 WebClient 的阻塞式 {@code block()}，而不是做成响应式</h3>
 * <p>调用方是<b>离线索引管道</b>：没有用户在线等，也没有 SSE 要往外推，
 * 整个流程本来就是「一批一批算完再入库」。强行响应式只会让「切片 → 重试 →
 * 逐条降级」这段天然串行的控制流变成一层套一层的 {@code flatMap}，
 * 可读性换不来任何吞吐收益（真正的瓶颈是上游 QPS 与文档规模）。
 *
 * <h3>四个必须显式处理的点（都不是猜的）</h3>
 * <ol>
 *   <li><b>响应顺序不能靠 {@code index} 字段回填</b> —— 兼容协议里每条带 {@code index}，
 *       看似可以据此回填。但<b>实测阿里云百炼的 /embeddings 对 25 条批量里每个元素都返回
 *       {@code "index": 0}</b>：字段有、类型对、值恒定。照它回填，25 条向量会全部落到下标 0，
 *       而现象只是一句与内容无关的「第 1 条向量缺失」。
 *       与此同时<b>数组顺序是对的</b>（已交叉验证：批量[0] 与单独调用第 1 条 cos=1.000000、
 *       批量[24] 与单独调用第 25 条 cos=1.000000）。故规则定为：
 *       <b>{@code index} 构成合法排列时才用它，否则退回数组顺序</b>（见 {@link #isIndexPermutation}）。
 *       读错位的后果是把 A 块的向量挂到 B 块上，检索时没有任何报错，只有「答非所问」这一个现象。</li>
 *   <li><b>条数必须校验</b> —— 少返回一条就说明上游做了肉眼看不见的截断，
 *       此时若照单全收，后面的向量会整体前移一位（同上的错位问题）。
 *       宁可整批失败重来。</li>
 *   <li><b>维度必须校验</b> —— 上游可能忽略 {@code dimensions} 参数而返回默认维度。
 *       不校验就会得到「config 写 1024、库里实际是 1536」的静默不一致，
 *       而写入 Milvus 时才会因为 dim 不匹配报出来（那时已浪费一次全量调用）。</li>
 *   <li><b>「整批坏」与「单条坏」要分开</b> —— 401 是配置问题，逐条重试同样全挂，
 *       只会把一次失败放大成 N 次（并可能触发限流）；而 400 往往是某一条内容
 *       过长或含非法字符，整批陪葬不合理。见 {@link #isFatal}。</li>
 * </ol>
 *
 * <h3>与 LLM 侧的差异（有意不共用）</h3>
 * <p>没有首帧超时：向量化是一次性响应，不存在「吐到一半卡住」与「迟迟不吐第一个字」
 * 的区分。只保留整体超时。
 */
@Slf4j
@Component
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;
    private final LlmErrorMapper errorMapper;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatEmbeddingClient(EmbeddingProperties props, LlmErrorMapper errorMapper) {
        this.props = props;
        this.errorMapper = errorMapper;
        String key = props.resolvedApiKey();
        int bufferBytes = responseBufferBytes(props);
        this.webClient = WebClient.builder()
                .baseUrl(props.resolvedBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // ⚠️ 必须调大：WebClient 默认 maxInMemorySize = 256 KB，
                // 而**批量向量响应天然超过它** —— 实测 batchSize=25、dim=1024 时
                // 响应体 484 KB（content-length 实测值），超限后 Spring 抛
                // DataBufferLimitException，且 retrieve() 会把它包成
                // WebClientResponseException(200)，经 LlmErrorMapper 映射后只剩
                // 一句「上游 200：」——**看着像上游故障，实际是本地缓冲上限**。
                //
                // 这个坑只测单条时永远发现不了：单条响应约 19 KB，离 256 KB 还差一个量级。
                // 本类第一次真跑（2026-10-08 验收程序）就是在批量那一步炸的。
                .codecs(c -> c.defaultCodecs().maxInMemorySize(bufferBytes))
                .build();
        log.info("[EMB] 客户端初始化：base={} path={} model={} dim={} batchSize={} 超时={}s 重试={} 响应缓冲={}KB key={}",
                props.getBaseUrl(), props.getPath(), props.getModel(), props.getDim(),
                props.getBatchSize(), props.getTimeoutSeconds(), props.getMaxRetries(),
                bufferBytes / 1024, props.maskedKey());
    }

    /**
     * 按配置推算响应体缓冲上限。
     *
     * <p>实测基准：{@code batchSize=25} × {@code dim=1024} → 响应体 484 KB，
     * 即每个浮点数序列化后约 19 字节（含符号、小数点与逗号）。按 24 字节/维度留余量，
     * 另加 1 MB 元数据余量（{@code data} 数组外壳、{@code usage} 等）。
     *
     * <p>下限 4 MB：防止有人把 batch-size 配成 1 时缓冲过小。
     * 上限 64 MB：防止 dim 被误配成极大值时，一个响应就吃掉几百 MB 堆。
     * 命中上限说明配置本身有问题，应该去改配置而不是继续调大这个数。
     */
    private static int responseBufferBytes(EmbeddingProperties props) {
        int batch = Math.max(1, props.getBatchSize() == null ? 25 : props.getBatchSize());
        int dim = props.getDim() == null || props.getDim() <= 0 ? 1024 : props.getDim();
        long need = (long) batch * dim * 24 + 1024L * 1024;
        long clamped = Math.max(4L * 1024 * 1024, Math.min(64L * 1024 * 1024, need));
        return (int) clamped;
    }

    @Override
    public float[] embed(String text) {
        // 单条走「批大小 1」的完整链路（含重试），保证两条路径的失败语义一致。
        // 不复用 embedBatch：那条路径会因单条失败而返回 null 占据位，语义模糊。
        return callWithRetry(List.of(text)).vectors().get(0);
    }

    @Override
    public EmbeddingBatch embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return EmbeddingBatch.empty();
        }
        int total = texts.size();
        int batchSize = Math.max(1, props.getBatchSize() == null ? 25 : props.getBatchSize());

        // 预填 null：保证与输入等长同序，失败位置以 null 占位（见 EmbeddingBatch 的注释）
        List<float[]> vectors = new ArrayList<>(Collections.<float[]>nCopies(total, null));
        List<Integer> failed = new ArrayList<>();
        long promptTokens = 0L;

        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<String> slice = texts.subList(start, end);
            try {
                Call call = callWithRetry(slice);
                for (int i = 0; i < slice.size(); i++) {
                    vectors.set(start + i, call.vectors().get(i));
                }
                promptTokens += call.promptTokens();
                continue;
            } catch (LlmException e) {
                // 只对「疑似单条毒数据」的 400 降级；其余（401/429/5xx/网络）逐条重试毫无意义
                if (isFatal(e)) {
                    throw e;
                }
                log.warn("[EMB] 批 [{}, {}) 重试 {} 次后仍失败，降级为逐条调用定位：{}",
                        start, end, props.getMaxRetries(), e.getMessage());
            }

            for (int i = 0; i < slice.size(); i++) {
                int idx = start + i;
                try {
                    Call one = callWithRetry(List.of(slice.get(i)));
                    vectors.set(idx, one.vectors().get(0));
                    promptTokens += one.promptTokens();
                } catch (LlmException e) {
                    // 单条永久失败：记下标、跳过，不阻断整篇文档的索引
                    failed.add(idx);
                    log.error("[EMB] 第 {} 条永久失败（已跳过，不阻断其余条目）：{}", idx, e.getMessage());
                }
            }
        }
        if (!failed.isEmpty()) {
            log.warn("[EMB] 本批 {}/{} 条向量化失败，下标={}", failed.size(), total, failed);
        }
        return new EmbeddingBatch(vectors, failed, promptTokens);
    }

    @Override
    public LlmProbeResult probe() {
        if (props.isPlaceholderKey()) {
            return LlmProbeResult.fail(LlmErrorCode.LLM_KEY_INVALID,
                    "psycare.embedding.api-key 仍是占位符（" + props.maskedKey() + "），请替换为真实密钥。"
                            + "知识库索引将不可用；AI 对话不受影响（它走 psycare.llm.*）。");
        }
        if (!props.hasKey()) {
            return LlmProbeResult.fail(LlmErrorCode.LLM_KEY_INVALID,
                    "未配置 API Key：psycare.embedding.api-key 为空"
                            + "（本地默认从 DASHSCOPE_API_KEY 环境变量取值的覆盖方式见部署说明）。"
                            + "知识库索引将不可用；AI 对话不受影响。");
        }
        String modelsPath = props.resolvedModelsPath();
        String probeUrl = props.resolvedBaseUrl() + modelsPath;
        try {
            String body = webClient.get()
                    .uri(modelsPath)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
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

    // ------------------------------------------------------------------ 内部

    /**
     * 这个错误是否属于「无论重试与否、无论整批还是逐条，都不可能有结果」。
     *
     * <p>判据只放行一种降级场景：{@code LLM_BAD_REQUEST}（400）——
     * 最常见成因是某一条内容超长或含非法字符，剔掉它其余还能成功。
     * 其余一律视为致命：
     * <ul>
     *   <li>{@code LLM_KEY_INVALID} / {@code LLM_MODEL_NOT_FOUND}：配置问题，逐条调 N 次全挂；</li>
     *   <li>{@code LLM_TIMEOUT} / {@code LLM_UPSTREAM_ERROR}：网络与上游故障，逐条调只会放大；</li>
     *   <li>{@code LLM_RATE_LIMITED}：已经限流了，再放大成 N 次请求是最糟的反应。</li>
     * </ul>
     */
    private boolean isFatal(LlmException e) {
        return e.getCode() != LlmErrorCode.LLM_BAD_REQUEST;
    }

    /** 带指数退避的重试。不可重试的错误立即抛出，不浪费时间 */
    private Call callWithRetry(List<String> slice) {
        int attempts = Math.max(0, props.getMaxRetries() == null ? 0 : props.getMaxRetries()) + 1;
        LlmException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                return callOnce(slice);
            } catch (LlmException e) {
                last = e;
                if (!e.isRetryable()) {
                    throw e;
                }
                if (attempt < attempts - 1) {
                    long wait = backoffMs(attempt);
                    log.warn("[EMB] 第 {}/{} 次调用失败（{}），{}ms 后重试：{}",
                            attempt + 1, attempts, e.getCode(), wait, e.getMessage());
                    sleep(wait);
                }
            }
        }
        throw last;
    }

    private long backoffMs(int attempt) {
        long base = props.getRetryBackoffMs() == null ? 500L : props.getRetryBackoffMs();
        return base * (1L << attempt);   // base * 2^n
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();   // 保留中断标志，别吞掉
            throw new LlmException(LlmErrorCode.LLM_UNKNOWN, "重试等待被中断");
        }
    }

    /** 单次 HTTP 调用 */
    private Call callOnce(List<String> slice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModel());
        payload.put("input", slice);
        if (props.getDim() != null && props.getDim() > 0) {
            // 显式传维度：不传就是「取服务端默认」，而默认值换模型会变，
            // 会造成「配置写 1024、库里实际 1536」的静默不一致
            payload.put("dimensions", props.getDim());
        }
        // 不写这个参数时部分网关返回 base64 编码的浮点数组，解析路径完全不同
        payload.put("encoding_format", "float");

        String body;
        try {
            body = webClient.post()
                    .uri(props.getPath())
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds() == null ? 30 : props.getTimeoutSeconds()))
                    .block();
        } catch (Exception e) {
            throw mapUpstreamError(e);
        }
        return parse(body, slice.size());
    }

    /**
     * 上游异常归类。
     *
     * <p>比 {@link LlmErrorMapper#map} 多一层判断：<b>成功状态码下的失败</b>要单独说清。
     * 响应体读取失败时（超缓冲上限、编码错），Spring 的 {@code retrieve()} 会把它
     * 包成 {@code WebClientResponseException}，而<b>状态码字段仍旧是 200</b>。
     * 直接交给通用归类器，得到的是「上游 200：」—— 一句把人往「上游故障」方向引的文案，
     * 而真实成因几乎总在本地。这里把它翻出来，省掉一轮无谓的上游排查。
     */
    private LlmException mapUpstreamError(Exception e) {
        if (e instanceof WebClientResponseException w && w.getStatusCode().is2xxSuccessful()) {
            return new LlmException(LlmErrorCode.LLM_UNKNOWN,
                    "HTTP " + w.getStatusCode().value() + " 成功但响应体处理失败"
                            + "（最常见成因：响应体超过 WebClient 缓冲上限，见构造器里的 maxInMemorySize），根因："
                            + rootCause(e));
        }
        return errorMapper.map(e);
    }

    /** 穿透到最内层成因，只取类名 + 消息 —— 排查需要的是「DataBufferLimitException: Exceeded limit ...」这一句 */
    private String rootCause(Throwable e) {
        Throwable cur = e;
        int guard = 0;
        while (cur.getCause() != null && guard++ < 10) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /**
     * 解析响应。
     *
     * <p>按 {@code index} 回填而非按数组顺序 —— 兼容协议未承诺顺序等于入参顺序。
     * 缺 {@code index} 字段时退化用数组位置（部分网关不返回该字段），
     * 但条数与维度这两道校验仍然生效，所以退化路径不会静默错位到无法察觉的程度。
     */
    private Call parse(String body, int expected) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new LlmException(LlmErrorCode.LLM_UNKNOWN,
                    "向量响应不是合法 JSON：" + e.getMessage() + "；原始响应 " + brief(body));
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new LlmException(LlmErrorCode.LLM_UNKNOWN,
                    "向量条数不符：期望 " + expected + "，实得 "
                            + (data.isArray() ? data.size() : 0) + "；原始响应 " + brief(body));
        }

        List<float[]> vectors = new ArrayList<>(Collections.<float[]>nCopies(expected, null));
        boolean trustIndex = isIndexPermutation(data, expected);
        int position = 0;
        for (JsonNode item : data) {
            int idx = trustIndex ? item.get("index").asInt() : position;
            position++;
            if (idx < 0 || idx >= expected) {
                throw new LlmException(LlmErrorCode.LLM_UNKNOWN,
                        "向量 index 越界：" + idx + "（期望 0.." + (expected - 1) + "）");
            }
            JsonNode emb = item.path("embedding");
            if (!emb.isArray() || emb.isEmpty()) {
                // 这条错误也是「静默失败」的经典形态：HTTP 200 但 embedding 为空
                throw new LlmException(LlmErrorCode.LLM_UNKNOWN,
                        "第 " + idx + " 条向量为空（HTTP 成功但无向量）；原始响应 " + brief(body));
            }
            int dim = emb.size();
            if (props.getDim() != null && props.getDim() > 0 && dim != props.getDim()) {
                throw new LlmException(LlmErrorCode.LLM_UNKNOWN,
                        "第 " + idx + " 条向量维度不符：期望 " + props.getDim() + "，实得 " + dim
                                + "。⚠️ 维度变更会让所有已入库向量失去可比性，必须整体重建索引。");
            }
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) {
                v[j] = (float) emb.get(j).asDouble();
            }
            vectors.set(idx, v);
        }
        for (int i = 0; i < expected; i++) {
            if (vectors.get(i) == null) {
                throw new LlmException(LlmErrorCode.LLM_UNKNOWN, "第 " + i + " 条向量缺失（index 未覆盖全部条目）");
            }
        }
        // 用量字段在兼容协议里是 usage.prompt_tokens；缺失时记 0 而不是报错（记账精度次于主流程）
        long tokens = root.path("usage").path("prompt_tokens").asLong(0L);
        return new Call(vectors, tokens);
    }

    /**
     * 响应里的 {@code index} 是否构成 {@code 0..expected-1} 的一个排列（即是否可信）。
     *
     * <p><b>不能无条件相信 {@code index}。</b>实测阿里云百炼的 OpenAI 兼容 {@code /embeddings}：
     * 25 条批量里每个元素都返回 {@code "index": 0} —— 字段存在、类型正确、值恒定。
     * 若照它回填，25 条向量会全部写到下标 0，其余位置为空，报出来的错是一条与内容
     * 毫无关系的「第 1 条向量缺失」；而这次调用在 HTTP 层完全正常（200、25 条、维度 1024）。
     *
     * <p>所以规则是：<b>只有 {@code index} 构成合法排列时才用它</b>
     * （既覆盖「严格按 index 回填」的供应商，也不会被「index 恒为 0」的供应商带错），
     * 否则退回数组顺序 —— 后者已用余弦相似度交叉验证过是正确的。
     */
    private boolean isIndexPermutation(JsonNode data, int expected) {
        boolean[] seen = new boolean[expected];
        for (JsonNode item : data) {
            JsonNode idx = item.get("index");
            if (idx == null || !idx.isNumber()) {
                return false;
            }
            int v = idx.asInt();
            if (v < 0 || v >= expected || seen[v]) {
                return false;
            }
            seen[v] = true;
        }
        return true;
    }

    private String brief(String body) {
        if (body == null) {
            return "(空响应体)";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }

    /** 一次成功调用的产物 */
    private record Call(List<float[]> vectors, long promptTokens) {
    }
}
