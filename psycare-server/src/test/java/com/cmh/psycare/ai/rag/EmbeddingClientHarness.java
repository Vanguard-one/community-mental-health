package com.cmh.psycare.ai.rag;

import com.cmh.psycare.ai.LlmErrorMapper;
import com.cmh.psycare.ai.LlmException;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link OpenAiCompatEmbeddingClient} 的**手动验收程序**（真调通义，会产生少量费用）。
 *
 * <h3>为什么需要它（这个项目被坑过一次）</h3>
 * <p>Phase 1 时 {@code models} 路径的推导逻辑只被 Python 探针验过（而探针把路径写死了），
 * Java 侧那段推导<b>从没真跑过</b>，于是启动探活第一次执行就 404。教训是：
 * <b>「能编译」与「能跑」之间隔着一整类静默失败</b>，推导/解析/缓冲这类逻辑必须用自己的产物跑一次。
 *
 * <p>本类第一次真跑（2026-10-08）立刻抓到两个只有真跑才会暴露的缺陷：
 * <ol>
 *   <li><b>WebClient 默认 256 KB 响应缓冲装不下批量向量</b>（25×1024 维实测 484 KB）；
 *       单条响应约 19 KB，所以「只测单条」永远发现不了。</li>
 *   <li><b>百炼兼容接口对批量里的每个元素都返回 {@code "index": 0}</b>，
 *       照 {@code index} 回填会把所有向量写到下标 0。</li>
 * </ol>
 * 两者都表现为「HTTP 200 但结果不对」，编译器与类型检查一概看不见。
 *
 * <h3>为什么不写成 {@code @Test}</h3>
 * <p>它<b>会真实调用付费接口</b>。放进 {@code mvn test} 意味着每次构建都在花钱、
 * 且离线/无 Key 时构建必红。所以只提供 {@code main}：
 * <b>它随每次 {@code mvn test-compile} 一起编译</b>（接口一改就会编译失败，不会悄悄烂掉），
 * 但只有人手敲下面这行才会执行。
 *
 * <h3>怎么跑</h3>
 * <pre>
 * cd psycare-server
 * # 1) 取依赖 classpath（离线即可）
 * mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * # 2) 运行（Windows 用 ; 分隔，Linux/macOS 用 :）
 * java -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
 *      com.cmh.psycare.ai.rag.EmbeddingClientHarness [application.yml 路径]
 * </pre>
 * 默认读 {@code src/main/resources/application.yml} —— <b>故意用应用真正会加载的那份配置</b>，
 * 这样「配置能不能绑上」也一并验了。
 */
@SuppressWarnings("unchecked")
public class EmbeddingClientHarness {

    public static void main(String[] args) throws Exception {
        Path yml = Paths.get(args.length > 0 ? args[0] : "src/main/resources/application.yml");
        if (!Files.exists(yml)) {
            System.err.println("找不到配置文件：" + yml.toAbsolutePath());
            System.exit(2);
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yml)) {
            root = new Yaml().load(in);
        }
        Map<String, Object> emb = (Map<String, Object>) ((Map<String, Object>) root.get("psycare")).get("embedding");

        EmbeddingProperties props = new EmbeddingProperties();
        props.setProvider((String) emb.get("provider"));
        props.setBaseUrl((String) emb.get("base-url"));
        props.setPath((String) emb.get("path"));
        props.setApiKey((String) emb.get("api-key"));
        props.setModel((String) emb.get("model"));
        props.setDim((Integer) emb.get("dim"));
        props.setBatchSize((Integer) emb.get("batch-size"));
        props.setTimeoutSeconds((Integer) emb.get("timeout-seconds"));
        props.setMaxRetries(1);
        props.setRetryBackoffMs(200L);

        int failed = 0;
        System.out.println("=== [0] 配置绑定（来源：" + yml + "）===");
        System.out.println("  baseUrl            = " + props.resolvedBaseUrl());
        System.out.println("  path               = " + props.getPath());
        System.out.println("  resolvedModelsPath = " + props.resolvedModelsPath());
        System.out.println("  探活完整 URL        = " + props.resolvedBaseUrl() + props.resolvedModelsPath());
        System.out.println("  model / dim / batch = " + props.getModel() + " / " + props.getDim()
                + " / " + props.getBatchSize());
        System.out.println("  maskedKey          = " + props.maskedKey()
                + "  (hasKey=" + props.hasKey() + ", placeholder=" + props.isPlaceholderKey() + ")");
        System.out.println("  响应缓冲上限        = " + responseBufferBytesOf(props) / 1024 + " KB");

        LlmErrorMapper mapper = new LlmErrorMapper();
        EmbeddingClient client = new OpenAiCompatEmbeddingClient(props, mapper);

        System.out.println("\n=== [1] 单条向量（dim 必须等于配置值）===");
        float[] one = client.embed("舍曲林常见不良反应包括恶心与失眠");
        System.out.println("  dim=" + one.length + " 前3值=" + one[0] + ", " + one[1] + ", " + one[2]);
        if (props.getDim() != null && one.length != props.getDim()) {
            System.out.println("  [!!!] 维度与配置不符");
            failed++;
        }

        System.out.println("\n=== [2] 批量 60 条（跨批切片 25+25+10，覆盖缓冲上限）===");
        List<String> texts = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            texts.add("第 " + i + " 条测试文本，用于验证切片与顺序回填。");
        }
        EmbeddingBatch batch = client.embedBatch(texts);
        System.out.println("  size=" + batch.size() + " failedIndexes=" + batch.failedIndexes()
                + " promptTokens=" + batch.promptTokens());
        if (batch.size() != 60 || batch.hasFailure()
                || !batch.vectors().stream().allMatch(Objects::nonNull)) {
            System.out.println("  [!!!] 批量结果不完整");
            failed++;
        }

        System.out.println("\n=== [3] 顺序回填（首尾两条必须与单独调用高度一致）===");
        // ⚠️ 判据只能是**相似度阈值**，不能是「逐位相等」：
        // 实测同一文本在不同调用上下文下会得到 cos≈0.9978 的两个版本（服务端并非逐位可复现）。
        // 参考基线：不同文本 cos≈0.864。所以 0.99 这条线既能抓住错位，又不会误报。
        double head = cos(batch.vectors().get(0), client.embed("第 1 条测试文本，用于验证切片与顺序回填。"));
        double tail = cos(batch.vectors().get(59), client.embed("第 60 条测试文本，用于验证切片与顺序回填。"));
        double cross = cos(batch.vectors().get(0), batch.vectors().get(59));
        System.out.printf("  cos(批内[0],  单独同文本) = %.6f%n", head);
        System.out.printf("  cos(批内[59], 单独同文本) = %.6f%n", tail);
        System.out.printf("  cos(批内[0],  批内[59])   = %.6f  （对照，应明显更低）%n", cross);
        if (head < 0.99 || tail < 0.99) {
            System.out.println("  [!!!] 首/尾与单独调用不一致 —— 疑似下标错位");
            failed++;
        }

        System.out.println("\n=== [4] 错 Key 的错误归类 ===");
        try {
            badKeyClient(props, mapper).embed("x");
            System.out.println("  [!!!] 错 Key 竟然成功");
            failed++;
        } catch (LlmException e) {
            System.out.println("  code=" + e.getCode() + "（期望 LLM_KEY_INVALID）");
            if (e.getCode() != com.cmh.psycare.ai.LlmErrorCode.LLM_KEY_INVALID) {
                failed++;
            }
        }

        System.out.println("\n=== [5] 启动探活路径 ===");
        var pr = client.probe();
        System.out.println("  ok=" + pr.ok() + " 模型数=" + pr.models().size()
                + " containsModel(" + props.getModel() + ")=" + pr.containsModel(props.getModel()));
        if (!pr.ok()) {
            failed++;
        }

        System.out.println("\n" + (failed == 0 ? "全部通过" : "存在 " + failed + " 项不符合预期"));
        System.exit(failed == 0 ? 0 : 1);
    }

    private static EmbeddingClient badKeyClient(EmbeddingProperties base, LlmErrorMapper mapper) {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setBaseUrl(base.getBaseUrl());
        p.setPath(base.getPath());
        p.setApiKey("sk-invalid-key-for-probe");
        p.setModel(base.getModel());
        p.setDim(base.getDim());
        p.setBatchSize(base.getBatchSize());
        p.setTimeoutSeconds(15);
        p.setMaxRetries(0);
        p.setRetryBackoffMs(50L);
        return new OpenAiCompatEmbeddingClient(p, mapper);
    }

    /** 复算缓冲上限，用于打印（与客户端构造器同一套算式） */
    private static int responseBufferBytesOf(EmbeddingProperties p) {
        int batch = Math.max(1, p.getBatchSize() == null ? 25 : p.getBatchSize());
        int dim = p.getDim() == null || p.getDim() <= 0 ? 1024 : p.getDim();
        long need = (long) batch * dim * 24 + 1024L * 1024;
        return (int) Math.max(4L * 1024 * 1024, Math.min(64L * 1024 * 1024, need));
    }

    private static double cos(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
