package com.cmh.psycare.ai;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 配置。全部来自 {@code psycare.llm.*}，**代码里不出现任何供应商的默认地址或路径**。
 *
 * <h3>为什么 base-url 与 path 必须拆成两个配置项</h3>
 * <p>此前 {@code DeepSeekClient} 把 {@code .uri("/v1/chat/completions")} 写死在代码里，
 * base-url 配的是 {@code https://api.deepseek.com}。看似能用，但换成任何
 * base-url 自带版本段的供应商（SiliconFlow 的 {@code .../v1}、DashScope 的
 * {@code .../compatible-mode/v1}）就会拼成 {@code /v1/compatible-mode/v1/chat/completions} ——
 * 报 404，而错误信息里只有「路径不存在」，看不出是双拼了版本段。
 *
 * <p>现在的约定：<b>base-url 到版本段为止，path 从版本段之后开始</b>。
 * 与 OpenAI 官方 SDK 的 {@code base_url} 语义一致，切换供应商只改 yml。
 */
@Data
@Component
@ConfigurationProperties(prefix = "psycare.llm")
public class LlmProperties {

    /** 仅供标识与文档用途；实际的协议适配由实现类的条件装配决定 */
    private String provider = "openai-compat";

    /** 形如 {@code https://api.deepseek.com/v1}，**含版本段** */
    private String baseUrl;

    /** 形如 {@code /chat/completions}，**不含版本段** */
    private String path = "/chat/completions";

    /** 模型列表路径；留空则由 {@link #path} 推导（{@code /v1/chat/completions} → {@code /v1/models}） */
    private String modelsPath;

    /**
     * API Key。**直接写在 {@code application.yml} 的 {@code psycare.llm.api-key}**。
     *
     * <h4>为什么不做 {@code ${LLM_API_KEY:}} 占位符</h4>
     * <p>占位符方案要求每台机器都先配好环境变量，忘配时的表现是「应用启动正常、日志干净、
     * 页面能开、接口全是 200」——只有真正发了消息才看到 AI 不回话，排查成本远高于收益。
     * 本地开发「clone 下来就能跑」比「密钥不进文件」更值钱。
     *
     * <h4>可覆盖性没有丢</h4>
     * <p>Spring Boot 的宽松绑定（relaxed binding）让<b>环境变量 {@code PSYCARE_LLM_API_KEY}</b>
     * 与 <b>JVM 参数 {@code -DPSYCARE_LLM_API_KEY=xxx}</b> 的优先级都高于 yml，
     * 且不需要在 yml 里写 ${} 占位符来「接收」。所以生产环境依旧可以做到
     * 「密钥不进镜像、只由部署平台注入」，而本地不受任何影响。
     *
     * <h4>为什么必须 {@code @ToString.Exclude}</h4>
     * <p>{@code @Data} 生成的 {@code toString()} 会把所有字段拼进去。只要有人随手写一句
     * {@code log.info("LLM 配置：{}", props)}（调试时极常见），明文密钥就落到日志文件里，
     * 而日志通常会被采集、归档、甚至贴到工单上。这里直接在源头掐断。
     */
    @ToString.Exclude
    private String apiKey;

    private String model;

    /**
     * 思考档位（{@code low} / {@code high} / {@code max}）。留空则用服务端默认。
     *
     * <p>DeepSeek-V4 系列是推理模型：正文之前会先输出 {@code reasoning_content}，
     * **这段思考会占用 {@code max_tokens} 预算，也直接决定首字延迟**。
     * 实测同一问题上 low 比默认少约 26% 思考量、首字快约 0.3s；
     * 本项目是陪伴式轻问诊，优先选低延迟。
     *
     * <p>⚠️ 与 {@link #thinking} 的关系：{@code effort} 只决定「思考多深」，
     * 决定不了「要不要思考」—— 即使给到 {@code low}，实测每轮仍会产生数百字思考。
     * 要真正砍掉首帧延迟，必须用 {@code thinking} 把思考整个关掉（见下）。
     * <b>关思考时本参数不再发送</b>（避免两个开关同时在请求体里，语义谁压谁不确定）。
     */
    private String effort;

    /**
     * 思考模式。取值 {@code enabled} / {@code disabled} / {@code auto}。
     *
     * <h3>为什么必须有这个开关 —— 实测数据</h3>
     * <p>真库 {@code ai_usage_log} 81 条记录回归出的关系：
     * <pre>
     *   首帧 ≈ 532 ms + 6.2 ms × 思考token      corr = 0.968
     *   corr(首帧, prompt_tokens)  = 0.192      ← 提示词长短不是瓶颈
     *   corr(首帧, cached_tokens)  = 0.016      ← 前缀缓存命中也不是
     * </pre>
     * 即：<b>首帧延迟几乎只由「模型想多久」决定</b>。532 ms 里包含公网往返与服务端排队，
     * 实测复用连接下的最小往返就有 453~808 ms，已无压缩空间。
     *
     * <p>同题同提示词对照实验（各项 2 次）：
     * <pre>
     *   effort=low              首帧 2657 / 2081 ms，思考 431 / 349 字
     *   reasoning_effort=none   首帧  615 /  827 ms，思考 0 字
     *   thinking.type=disabled  首帧  737 /  931 ms，思考 0 字
     * </pre>
     * 关思考后首帧稳定在 <b>0.6~0.9 s</b>，快 2.5~5 倍。
     *
     * <h3>那为什么不直接全关</h3>
     * <p>危机语境实测（AI 已问「有没有想过伤害自己」，用户答「晚上会想睡过去不醒」）：
     * 开思考的回复给出了 120/110/12356 热线与「今晚别独处」这类安全兜底；
     * <b>关思考的回复共情到位，但一条热线、一句安全兜底都没有</b>。
     * 安全出口的产出恰好落在思考过程里 —— 这是本产品绝不能丢的东西。
     *
     * <p>所以默认走 {@code auto}：<b>默认关掉（换速度），命中风险条件时自动开回来（保安全）</b>。
     * 判据见 {@link ThinkingPolicy}。想退回全速可把 {@link #thinkingRiskLevels} 与
     * {@link #thinkingStages} 置空；想退回改造前的行为把它设成 {@code enabled}。
     *
     * <p>取值不合法时按 {@code auto} 处理（在 {@link #resolvedThinking()} 里归一），
     * 而不是抛异常 —— 一个配置拼错不该让整个 AI 功能起不来。
     */
    private String thinking = "auto";

    /**
     * {@code auto} 模式下，会话关联测评达到这些风险等级时<b>开启</b>思考。
     *
     * <p>默认只到 {@code C}（高风险）与 {@code D}（危机）：这两档在处置上本就要求
     * 人工介入，多花几秒换更谨慎的措辞是划算的。A/B 档走关闭路径。
     */
    private List<String> thinkingRiskLevels = List.of("C", "D");

    /**
     * {@code auto} 模式下，处于这些引导阶段时<b>开启</b>思考。
     *
     * <p>默认只有 {@code SUMMARY_REFERRAL}（建议转介）：这一阶段要复述全局、
     * 给建议、并用模型自己的话解释「为什么推荐这位医生」，是整段对话里唯一
     * 需要综合判断的环节。前两个阶段（建立联结 / 主诉澄清）都是「接住情绪 + 追问一句」，
     * 思考的边际收益最低而延迟体感最明显。
     */
    private List<String> thinkingStages = List.of("SUMMARY_REFERRAL");

    /** 归一后的思考模式；非法取值一律当 {@code auto} */
    public String resolvedThinking() {
        if (thinking == null) {
            return "auto";
        }
        String v = thinking.trim().toLowerCase();
        return switch (v) {
            case "enabled", "disabled", "auto" -> v;
            default -> "auto";
        };
    }

    /**
     * 单次回复的 token 上限，**同时是「思考 + 正文」的共享预算**。
     *
     * <p>⚠️ 这是推理模型最容易踩的坑：设成 256 时实测 258 帧全是 reasoning_content、
     * 正文字数为 0，而 {@code finish_reason} 是 {@code length}（看不出是空回复）。
     * 前端表现是「AI 不回话」。故默认给足 8192；上限本身不产生费用，只拦失控生成长度。
     */
    private Integer maxTokens = 8192;

    /** 整体上限（秒）：到点截断，避免一次问诊挂死连接 */
    private Integer timeoutSeconds = 60;

    /**
     * 首帧超时（秒）。语义见 {@code OpenAiCompatClient} 的注释：
     * 「15 秒不吐第一个字」与「吐到一半卡住」是两种故障，前者立刻降级。
     */
    private Integer firstTokenSeconds = 15;

    /**
     * 采样温度。**默认 null，即不发送该参数、用服务端默认值。**
     *
     * <p>不预设数值是有意的：本项目面向心理问诊，回答的一致性与安全性比「有创意」重要，
     * 但这个结论需要用评测集验证（同一问题多次回答的稳定性），
     * 在没有基线之前写死一个数字，等于把未经验证的偏好固化进配置。
     */
    private Double temperature;

    /**
     * 送入模型的最近上下文轮数。
     *
     * <p>与 {@link #maxContextTokens} 是<b>双重保护</b>关系：token 预算管「总长度」，
     * 这个管「最多几轮」。估算可能低估（中英混排、表情符号），加一层轮数上限成本为零。
     */
    private Integer maxHistory = 10;

    /**
     * 历史上下文的 token 预算（不含 L1–L4 常量与 L5 档案）。
     *
     * <p>取代原先「按条数裁剪」的做法：中文场景下条数与长度完全不成比例，
     * 一条 3000 字的倾诉和一句「嗯」占同样的配额。详见 {@code ContextAssembler}。
     */
    private Integer maxContextTokens = 6000;

    /** 单条消息的字符上限，超过则截断后送出（防一条超长输入顶掉整个窗口） */
    private Integer singleMessageMaxChars = 1000;

    /** 会话消息总数达到该值后开始触发摘要压缩（异步，不阻塞回复） */
    private Integer summaryTriggerMessages = 24;

    /** 摘要压缩步长：之后每新增这么多条消息再压一次，避免每轮都调一次模型 */
    private Integer summaryStepMessages = 8;

    /** 重试次数上限（默认 1 次，与计划 5.8 一致） */
    private Integer maxRetries = 1;

    /** 退避基数（毫秒），第 n 次重试等待 {@code base * 2^n} */
    private Long retryBackoffMs = 500L;

    /** 启动时做一次轻量探活（只调模型列表接口，**不消耗 token**） */
    private boolean startupProbe = true;

    /**
     * 去掉两端空白后的 Key。
     *
     * <p>必须 trim：yml 里的值只要被引号包起来（{@code api-key: "sk-xxx "}），
     * 尾随空格就会被完整保留，拼进 {@code Authorization: Bearer <key>} 后服务端判为无效凭证，
     * 返回 401。而报错文案只会说「鉴权失败」，肉眼永远看不出差一个空格 ——
     * 本机复制粘贴密钥时踩过一次就够。
     */
    public String resolvedApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    /** 是否已配置（非空白）。注意：占位符也算「已配置」，由 {@link #isPlaceholderKey()} 单独判定 */
    public boolean hasKey() {
        return !resolvedApiKey().isEmpty();
    }

    /**
     * 是否还是没被替换掉的占位符。
     *
     * <p>把「忘了把 sk-xxxx 换成真密钥」和「密钥真的被吊销了」区分开：
     * 两者都会得到 401，但下一步动作完全不同（前者改配置，后者去控制台重置）。
     * 不区分的话，一条「鉴权失败」会让人先去怀疑密钥，白跑一圈。
     */
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

    /**
     * 脱敏后的 Key，**只供日志使用**：{@code sk-347***dd66}。
     *
     * <p>保留前 6 位与后 4 位是有用的：手上同时有多个密钥（测试/生产）时，
     * 一眼就能确认应用实际加载的是哪一个，而日志里不会出现可用的完整凭证。
     */
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

    /**
     * 去掉尾部斜杠的 base-url。
     *
     * <p>{@code https://api.deepseek.com/v1/}（多打一个斜杠）本身不算错，
     * 但与 {@code path} 拼接后会得到 {@code //chat/completions}。
     * 有些网关会把它当成不同路径直接 404，且报错与「路径写错」长得一模一样。
     */
    public String resolvedBaseUrl() {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * 推导模型列表路径。
     *
     * <p>规则：<b>去掉 {@link #path} 末尾的两段（资源段 + 动作段）</b>，剩下的前缀接 {@code /models}。
     * <pre>
     *   /v1/chat/completions                 → /v1/models
     *   /chat/completions                    → /models        （base-url 已含 /v1 的常见情形）
     *   /compatible-mode/v1/chat/completions → /compatible-mode/v1/models
     * </pre>
     *
     * <p>⚠️ 必须去<b>两</b>段，不是一段。原先写成 {@code substring(0, lastIndexOf('/')) + "/models"}，
     * 即只砍掉动作段 {@code /completions}，于是 {@code /chat/completions} 推出 {@code /chat/models}
     * —— 服务端返回 <b>404</b>，而探活文案只会照着 hint 说「检查 base-url / path / models-path」，
     * 看起来像整条地址都配错了。真实原因是：{@code models} 与 {@code chat} 是<b>同级</b>资源，
     * 都在版本段之下，{@code /chat} 这一段（资源段）要一并去掉。
     *
     * <p>缺陷能潜伏到现在的原因：Java 侧探活走这个推导，而模型连通性是早期用 Python 探针
     * （{@code .workbuddy/scripts/llm_probe.py}）<b>直接把 {@code /v1/models} 写死</b>验的 ——
     * 两条路径没有交叉验证，于是「Java 启动探活」第一次真跑就炸。教训：推导逻辑必须用自己的产物验一次。
     */
    public String resolvedModelsPath() {
        if (modelsPath != null && !modelsPath.isBlank()) {
            return modelsPath.trim();
        }
        String p = path == null ? "" : path.trim();
        int i = p.lastIndexOf('/');                       // 动作段（/completions）前的斜杠
        int j = i > 0 ? p.lastIndexOf('/', i - 1) : -1;   // 资源段（/chat）前的斜杠
        String prefix = j > 0 ? p.substring(0, j) : "";
        return prefix + "/models";
    }
}
