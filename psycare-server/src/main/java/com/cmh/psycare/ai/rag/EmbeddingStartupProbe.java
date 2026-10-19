package com.cmh.psycare.ai.rag;

import com.cmh.psycare.ai.LlmProbeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化的启动探活。存在的理由与 {@code LlmStartupProbe} 相同：
 * 把「Key 没配 / 模型名退役 / 地域配错」暴露在<b>启动日志</b>里，
 * 而不是留给索引任务跑到一半才发现。
 *
 * <h3>为什么失败只 WARN 不 ERROR（与 LLM 侧相反）</h3>
 * <p>向量化只服务<b>离线的知识库索引</b>，没有用户在线等它 —— 索引建不起来，
 * 表现是「检索为空、AI 说不知道」，而不是「页面打不开」。而 LLM 侧一旦不可用，
 * 每一位正在问诊的居民都会立刻收到降级话术，那是用户可见的故障。
 * 严重程度不同，日志级别就应该不同；两者都喊 ERROR 会让日志失去分层。
 *
 * <h3>模型不在列表里不算失败</h3>
 * <p>已实测：本机某次探活时 {@code /models} <b>没有</b>返回
 * {@code text-embedding-v4}，但直接调用它是成功的 —— 该端点不保证返回全量模型。
 * 所以「不在列表」只提示、不判定失败，与 {@code LlmStartupProbe} 的处理一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingStartupProbe implements ApplicationRunner {

    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isStartupProbe()) {
            log.info("[EMB 启动探活] 已关闭（psycare.embedding.startup-probe=false）");
            return;
        }

        LlmProbeResult result = embeddingClient.probe();
        if (result.ok()) {
            List<String> models = result.models();
            log.info("[EMB 启动探活] ✅ 通过：base={} model={} dim={} key={}",
                    props.getBaseUrl(), props.getModel(), props.getDim(), props.maskedKey());
            if (!models.isEmpty() && !result.containsModel(props.getModel())) {
                log.warn("[EMB 启动探活] ⚠️ 配置的 model={} 不在服务端列表（共 {} 个）中。"
                                + "该列表不保证返回全量，可忽略；若索引时报模型无效，改成列表中的名字：{}",
                        props.getModel(), models.size(), models);
            }
            return;
        }

        String hint = switch (result.code()) {
            case LLM_KEY_INVALID -> "把 psycare.embedding.api-key 换成真实密钥后重启"
                    + "（注意：通义的中国区 Key 只能打 dashscope.aliyuncs.com，新加坡 Key 只能打 dashscope-intl，"
                    + "配错地域的表现同样是 401，文案看不出来）。";
            case LLM_MODEL_NOT_FOUND -> "改成服务端 /models 返回的向量模型名。";
            case LLM_UPSTREAM_ERROR -> "检查网络与代理：本机 HTTP_PROXY 会拦截出网，需确认能直连 dashscope.aliyuncs.com。";
            default -> "检查 psycare.embedding.base-url / path / models-path 配置。";
        };
        log.warn("[EMB 启动探活] ⚠️ 未通过 code={}：{} → {}",
                result.code(), result.message(), hint);
        log.warn("[EMB 启动探活] 影响范围：知识库索引与检索不可用；AI 对话、测评、医患聊天均不受影响。");
    }
}
