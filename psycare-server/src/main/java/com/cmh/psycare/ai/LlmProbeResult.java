package com.cmh.psycare.ai;

import java.util.List;

/**
 * 启动探活结果。
 *
 * <p>探活<b>故意只调模型列表接口，不发对话请求</b>：
 * 对话请求会真实计费，而「Key 是否有效 / 模型名是否存在」这两件事
 * 靠一个 GET 就能判定。用最贵的接口去验最便宜的属性是反的。
 */
public record LlmProbeResult(boolean ok, LlmErrorCode code, String message, List<String> models) {

    public static LlmProbeResult ok(List<String> models) {
        return new LlmProbeResult(true, null, "ok", models == null ? List.of() : models);
    }

    public static LlmProbeResult fail(LlmErrorCode code, String message) {
        return new LlmProbeResult(false, code, message, List.of());
    }

    /** 配置的模型名是否出现在服务端返回的列表里。别名（如 deepseek-v4-flash → deepseek-flash）会返回 false */
    public boolean containsModel(String model) {
        return model != null && models.contains(model);
    }
}
