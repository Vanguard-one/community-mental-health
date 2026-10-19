package com.cmh.psycare.ai.rag;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量化（Embedding）配置。全部来自 {@code psycare.embedding.*}，
 * <b>代码里不出现任何供应商的默认地址、路径或维度</b>。
 *
 * <h3>为什么向量化与对话要分成两套配置</h3>
 * <p>这两件事在本项目里<b>必然由两家供应商提供</b>：DeepSeek 没有 embeddings 端点
 * （已核实，见计划文档「事实 1」）。所以 {@code psycare.llm.*} 与 {@code psycare.embedding.*}
 * 不是「同一家的两个接口」，而是两条独立的上下游关系 —— 合并成一套配置，就等于埋下
 * 「换向量模型时顺手把对话模型的 base-url 也改了」这种低级事故的可能。
 *
 * <h3>关于与 {@link com.cmh.psycare.ai.LlmProperties} 的方法重复</h3>
 * <p>{@link #resolvedApiKey()} / {@link #maskedKey()} / {@link #resolvedBaseUrl()} /
 * {@link #resolvedModelsPath()} 四段逻辑与 {@code LlmProperties} 几乎逐字相同。这是
 * <b>有意保留的重复</b>，不是疏漏：
 * <ul>
 *   <li>抽公共基类（如 {@code OpenAiCompatProperties}）要改动
 *       {@code LlmProperties} —— 那是 Phase 0–2.5 已经在线上跑、被反复验证过的关键路径。
 *       为一个纯形态优化去动它，收益约 35 行、风险是整条对话链路；不划算。</li>
 *   <li>这几段逻辑虽短，但每一段都对应一个<b>已经踩过的坑</b>（尾随空格导致 401、
 *       尾部斜杠拼出双斜杠、models 路径要退两段）。抽出去以后，注释里的「为什么」会与
 *       调用方脱节，下一个改的人不容易看见。</li>
 *   <li>「三次重复才抽象」在这里刚好卡在第二次。第三家供应商出现时再抽，是更稳的时点。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "psycare.embedding")
public class EmbeddingProperties {

    /** 仅供标识与文档用途；协议适配由实现类的条件装配决定 */
    private String provider = "openai-compat";

    /** 形如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，**含版本段** */
    private String baseUrl;

    /** 形如 {@code /embeddings}，**不含版本段**。与 OpenAI 兼容协议的约定一致 */
    private String path = "/embeddings";

    /** 模型列表路径；留空则由 {@link #path} 推导（{@code /embeddings} → {@code /models}） */
    private String modelsPath;

    /**
     * API Key。**直接写在 {@code application.yml} 的 {@code psycare.embedding.api-key}**。
     *
     * <p>与 {@code psycare.llm.api-key} 同一套理由（见 {@code LlmProperties#apiKey}）：
     * 本地开发「clone 下来就能跑」优先于「密钥不进文件」；生产可用环境变量
     * {@code PSYCARE_EMBEDDING_API_KEY} 覆盖，无需在 yml 里写占位符接收。
     *
     * <p>{@code @ToString.Exclude} 是必须的：{@code @Data} 生成的 {@code toString()}
     * 会把密钥拼进去，而配置类被随手 {@code log.info} 出来是极常见的调试动作。
     */
    @ToString.Exclude
    private String apiKey;

    /**
     * 向量模型名。**必须以服务端 {@code /models} 返回的为准**，不要靠记忆写。
     *
     * <p>本项目已实测（2026-10-08）：{@code qwen3.7-text-embedding-flash} 与
     * {@code qwen3.7-text-embedding} 在模型列表中，维度可选 1024 / 768 / 512 / 256。
     */
    private String model;

    /**
     * 向量维度。**必须与 Milvus collection 的 {@code dense_vector} dim 严格一致**。
     *
     * <p>⚠️ 维度不是「越小越省」的调参项：一旦变更，<b>所有已入库向量都失去可比性</b>
     * （不同维度的向量在同一空间里做近邻，结果接近随机），必须整体重新生成。
     * 所以它要一次定死，并且与 {@code model} 一起被钉在 {@code kb_document} 行上
     * （{@code embed_model} / {@code dim} 两列），让 rebuild 脚本能判断哪些文档需要重跑。
     */
    private Integer dim = 1024;

    /**
     * 单次请求的最大条数。
     *
     * <p><b>实测上限是 25</b>，不是文档里常写的 10：2026-10-08 用本配置的模型实测，
     * n=25 返回 25 条（0.37s），n=32 直接 400
     * （{@code InvalidParameter: batch size is invalid, it should not be larger than 25}）。
     * 不同模型的这个上限不一样（{@code text-embedding-v4} 是 10），
     * 所以它是配置项而不是常量 —— 换模型时必须重新实测。
     */
    private Integer batchSize = 25;

    /** 单次请求超时（秒）。语义与 LLM 侧不同：向量化是**批量离线段**，没有首帧概念 */
    private Integer timeoutSeconds = 30;

    /**
     * 单个批次的失败重试次数（指数退避）。
     *
     * <p>整批重试耗尽后**降级为逐条调用**，用于把「一条脏数据毒死整批」隔离出来：
     * 单条最终仍失败只记 FAILED，不阻断其余条目（计划 5.4 阶段四）。
     */
    private Integer maxRetries = 3;

    /** 退避基数（毫秒），第 n 次重试等待 {@code base * 2^n} */
    private Long retryBackoffMs = 500L;

    /** 启动时做一次轻量探活（只调模型列表接口，**不产生 token 费用**） */
    private boolean startupProbe = true;

    /**
     * 去掉两端空白后的 Key。
     *
     * <p>必须 trim：yml 里的值被引号包住时（{@code api-key: "sk-xxx "}）尾随空格会被完整保留，
     * 拼进 {@code Authorization: Bearer <key>} 后服务端判为无效凭证返回 401，
     * 而报错文案只会说「鉴权失败」—— 肉眼永远看不出差一个空格。
     */
    public String resolvedApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    /** 是否已配置（非空白） */
    public boolean hasKey() {
        return !resolvedApiKey().isEmpty();
    }

    /** 是否还是没被替换掉的占位符（把「忘改配置」与「密钥真被吊销」区分开） */
    public boolean isPlaceholderKey() {
        String k = resolvedApiKey().toLowerCase();
        if (k.isEmpty()) {
            return false;
        }
        return k.startsWith("sk-xxxx")
                || k.equals("sk-xxx")
                || k.contains("your-key")
                || k.contains("your_key")
                || k.contains("yourkey")
                || k.contains("changeme")
                || k.contains("placeholder")
                || k.contains("替换成你的")
                || k.startsWith("<");
    }

    /** 脱敏后的 Key，**只供日志使用**：{@code sk-ws-***Z3Qg} */
    public String maskedKey() {
        String k = resolvedApiKey();
        if (k.isEmpty()) {
            return "(空)";
        }
        if (k.length() <= 12) {
            return k.charAt(0) + "***";
        }
        return k.substring(0, 6) + "***" + k.substring(k.length() - 4);
    }

    /** 去掉尾部斜杠的 base-url（否则与 path 拼出 {@code //embeddings}，部分网关直接 404） */
    public String resolvedBaseUrl() {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * 推导模型列表路径：去掉 {@link #path} 末尾<b>两段</b>（资源段 + 动作段）再接 {@code /models}。
     *
     * <p>{@code models} 与 {@code embeddings} 是<b>同级</b>资源，都在版本段之下：
     * <pre>
     *   /v1/embeddings       → /v1/models
     *   /embeddings          → /models      （base-url 已含 /v1 的常见情形）
     * </pre>
     * 只砍一段会推出 {@code /embeddings/models} → 404，而报错看着像整条地址配错。
     */
    public String resolvedModelsPath() {
        if (modelsPath != null && !modelsPath.isBlank()) {
            return modelsPath.trim();
        }
        String p = path == null ? "" : path.trim();
        int i = p.lastIndexOf('/');
        int j = i > 0 ? p.lastIndexOf('/', i - 1) : -1;
        String prefix = j > 0 ? p.substring(0, j) : "";
        return prefix + "/models";
    }
}
