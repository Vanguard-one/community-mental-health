package com.cmh.psycare.ai.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus（RAG 索引层）配置。全部来自 {@code psycare.milvus.*}。
 *
 * <h3>为什么这里<b>没有</b> dim</h3>
 * <p>向量维度只有<b>一个</b>权威来源：{@link EmbeddingProperties#getDim()} —— 因为维度是
 * <b>模型的性质</b>，不是库的性质。在这里再写一份，就等于允许出现
 * 「embedding 返回 1024 维、collection 建的是 768 维」这种配置，而它的表现是
 * 写入直接报错（还算好的），或旧数据静默失去可比性（更坏）。
 * 配置项要能写错的地方，就不给配置项。
 *
 * <h3>为什么要 database 而不是共用 default</h3>
 * <p>本机这台 Milvus <b>不是本项目独占</b>的（已核实：{@code default} 库下有用户自己的
 * {@code biz} 集合，另有 {@code rag_demo.docs} / {@code rag_tutorial.docs} 两个残留集合，
 * 后者还持续刷段加载 WARN）。把我们的集合混在 {@code default} 里，任何时候
 * 一次「清一下库」就会误伤；反过来，别人的一次误操作也会打掉我们的索引。
 * 独立 database 是零成本的隔离（Milvus 支持 {@code createDatabase}，且 v2 SDK 原生支持）。
 *
 * <h3>为什么 {@code token} 可空</h3>
 * <p>本机 Milvus 是单容器、未开鉴权（{@code COMMON_SECURITY_AUTHORIZATIONENABLED} 未设），
 * 留空即「不发送 Authorization 头」。生产若开鉴权，用
 * {@code <user>:<password>} 形式写入或走 {@code PSYCARE_MILVUS_TOKEN} 环境变量覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "psycare.milvus")
public class MilvusProperties {

    /** 总体开关。关掉后所有索引操作直接返回「不可用」，不建连接 —— 方便本地只想跑对话的场景 */
    private boolean enabled = true;

    /** 形如 {@code http://localhost:19530}。SDK 接受 http/https 形式（内部走 gRPC） */
    private String uri = "http://localhost:19530";

    /** 鉴权 token，形如 {@code root:Milvus}；留空表示该实例未开鉴权 */
    private String token;

    /** 独立 database（见类注释）。启动时若不存在会自动创建，幂等 */
    private String database = "psycare";

    /** 集合名。**不带库前缀** —— 库由 {@link #database} 指定 */
    private String collection = "psycare_kb_chunk";

    /** 建连超时（毫秒）。给短值：Milvus 没起时不该让接口挂几十秒 */
    private Integer connectTimeoutMs = 5000;

    /** 单次 RPC 截止时间（毫秒） */
    private Integer rpcDeadlineMs = 10000;

    /** 稠密那一路的召回条数 */
    private Integer denseTopK = 20;

    /** 稀疏（BM25）那一路的召回条数 */
    private Integer sparseTopK = 20;

    /** RRF 融合后交给上层的条数（Phase 4 再过阈值与重排） */
    private Integer finalTopK = 5;

    /**
     * RRF 的 k 参数。服务端 K=60 是论文默认值（Cormack et al. 2009），
     * 它决定「排名靠前」能拉开多大差距：k 越大，各名次的贡献越平均。
     * 没有充分理由不要改 —— 改了就得重新跑 Phase 4 的阈值校准。
     */
    private Integer rrfK = 60;

    /**
     * {@code text} 字段的最大长度（**字节**，Milvus VarChar 按字节计）。
     * 子块 300–500 汉字 = 900–1500 字节，4096 留了足够余量；
     * 写入前仍会按此值截断，避免一条超长文本让整批 insert 失败。
     */
    private Integer textMaxBytes = 4096;

    /** 写入前是否 flush。刚写完立刻检索时必开，否则因一致性级别看不到数据（表现为「写进去了却搜不到」） */
    private boolean flushAfterWrite = false;

    /** 启动探活（只做连接 + describe，不写数据）。失败只 WARN，不影响在线对话 */
    private boolean startupProbe = true;

    /** 去掉尾部斜杠的 uri */
    public String resolvedUri() {
        String u = uri == null ? "" : uri.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** 去掉两端空白的 token */
    public String resolvedToken() {
        return token == null ? "" : token.trim();
    }

    public boolean hasToken() {
        return !resolvedToken().isEmpty();
    }

    /** 脱敏后的 token，**只供日志使用** */
    public String maskedToken() {
        String t = resolvedToken();
        if (t.isEmpty()) {
            return "(未配置)";
        }
        int i = t.indexOf(':');
        if (i > 0) {
            return t.substring(0, i) + ":***";
        }
        return t.length() <= 6 ? "***" : t.substring(0, 3) + "***";
    }
}
