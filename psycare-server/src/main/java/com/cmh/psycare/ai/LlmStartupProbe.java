package com.cmh.psycare.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动探活：把「Key 没配 / 模型名退役」这类问题暴露在<b>启动日志</b>里。
 *
 * <h3>为什么这是必要的一步</h3>
 * <p>历史教训：早期写法是 {@code api-key: ${LLM_API_KEY:sk-xxxx}} —— 环境变量 + 占位符默认值。
 * 忘设环境变量时，占位符 {@code sk-xxxx} 会被当成真密钥发出去，每一次调用必然失败，
 * 但应用能正常启动、页面能正常打开、测试能全部通过 —— 直到某个用户在聊天框里发了第一句话，
 * 才看到「AI 不回话」。这也是 {@code api-key} 现在直接写在 yml 里的原因：
 * 「配置是否生效」必须在启动那一刻就可见，而不是留给第一位用户去发现。
 *
 * <h3>为什么用 WARN/ERROR 而不是让启动失败</h3>
 * <p>AI 只是本系统的一个模块。Key 缺失时，测评、预警、医患聊天都还应该能用。
 * 让整个应用起不来，是把「一个模块降级」放大成「全系统不可用」。
 * 所以这里只把结论喊清楚，不抛异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmStartupProbe implements ApplicationRunner {

    private final AiChatClient aiChatClient;
    private final LlmProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isStartupProbe()) {
            log.info("[LLM 启动探活] 已关闭（psycare.llm.startup-probe=false）");
            return;
        }

        LlmProbeResult result = aiChatClient.probe();
        if (result.ok()) {
            List<String> models = result.models();
            log.info("[LLM 启动探活] ✅ 通过：base={} model={} key={} 服务端可用模型={}",
                    props.getBaseUrl(), props.getModel(), props.maskedKey(), models);
            if (!models.isEmpty() && !result.containsModel(props.getModel())) {
                log.warn("[LLM 启动探活] ⚠️ 配置的 model={} 不在服务端列表 {} 中。"
                                + "别名（如 deepseek-v4-flash → deepseek-flash）可忽略；"
                                + "若调用返回 LLM_MODEL_NOT_FOUND，请改成列表中的名字。",
                        props.getModel(), models);
            }
            return;
        }

        String hint = switch (result.code()) {
            case LLM_KEY_INVALID -> "打开 psycare-server/src/main/resources/application.yml，把 psycare.llm.api-key 换成真实密钥（别留 sk-xxxx 占位符）后重启。";
            case LLM_MODEL_NOT_FOUND -> "改成模型列表中的有效名称。注意旧别名 deepseek-chat / deepseek-reasoner 已于 2026-07-24 退役。";
            case LLM_UPSTREAM_ERROR -> "检查网络与代理：本机 HTTP_PROXY 会拦截出网，WebClient 不走代理则需确认能直连 api.deepseek.com。";
            case LLM_TIMEOUT -> "上游 15 秒未响应，通常是网络问题。";
            default -> "检查 psycare.llm.base-url / path / models-path 配置。";
        };
        log.error("[LLM 启动探活] ❌ 失败 code={}：{} → {}",
                result.code(), result.message(), hint);
        log.error("[LLM 启动探活] AI 对话将全部降级为兜底话术（含人工出口），其余模块不受影响。");
    }
}
