package com.cmh.psycare.ai.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量库（RAG 的索引层）。
 *
 * <h3>职责边界</h3>
 * <p>这个类只做「<b>schema / 索引 / 写入 / 检索</b>」四件事，<b>不做向量化</b> ——
 * 向量由调用方（{@code KnowledgeIndexService}）通过 {@link EmbeddingClient} 算好传进来。
 * 分开的理由是错误归因：向量化失败是「上游厂商的问题」，Milvus 失败是「索引层的问题」，
 * 两者的处置（重试策略、要不要降级）完全不同，混在一个类里必然写成一个大 try-catch。
 *
 * <h3>懒连接与「不可用即降级」</h3>
 * <p>连接是<b>第一次真正用到时才建</b>，且任何失败都抛 {@link MilvusUnavailableException}。
 * 这样做的直接好处是：本地只想跑对话、不想开 Milvus 时，应用照常启动、聊天照常用
 * （计划 5.6：RAG 是增强不是依赖）。
 *
 * <h3>两个不可回退的版本约束（已核实）</h3>
 * <ul>
 *   <li>Milvus 3.0 <b>不支持降级到 2.6</b>：3.0 写过数据后换镜像回滚，数据可能读不出来；</li>
 *   <li>不要开 <b>Storage V3 / Loon</b>：它改变序列化格式，一旦启用就<b>永久</b>回不去 2.6。</li>
 * </ul>
 *
 * <h3>为什么所有标量字段都非空（用 0 表示「无」）</h3>
 * <p>Milvus 的 nullable 标量字段要到较新版本才完整支持，而「父块 id / 页码」这类字段
 * 在业务上完全可以用一个哨兵值表达。用一个 0 换来「不必依赖服务端 nullable 行为」，
 * 是划算的 —— 这类版本相关的坑一次都不该踩。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusVectorStore {

    /* ---------- 字段名（写死成常量：schema、写入、检索三处必须完全一致） ---------- */
    public static final String F_CHUNK_ID = "chunk_id";
    public static final String F_DOC_ID = "doc_id";
    public static final String F_PARENT_ID = "parent_id";
    public static final String F_TEXT = "text";
    public static final String F_DENSE = "dense_vector";
    public static final String F_SPARSE = "sparse_vector";
    public static final String F_DOC_TITLE = "doc_title";
    public static final String F_SOURCE_TYPE = "source_type";
    public static final String F_PUBLISH_YEAR = "publish_year";
    public static final String F_PAGE_FROM = "page_from";
    public static final String F_PAGE_TO = "page_to";
    public static final String F_TOKEN_COUNT = "token_count";

    /** 检索要带回的字段（不含向量本身 —— 回表取正文，不需要把向量传回来） */
    private static final List<String> OUT_FIELDS = List.of(
            F_CHUNK_ID, F_DOC_ID, F_PARENT_ID, F_DOC_TITLE, F_SOURCE_TYPE, F_PUBLISH_YEAR);

    /** 布尔型不足，用 0 表达「无父块 / 无页码」 */
    private static final long NONE = 0L;

    private final MilvusProperties props;
    private final EmbeddingProperties embedding;

    private volatile MilvusClientV2 client;
    private volatile boolean prepared;
    private final Object lock = new Object();

    /* ==================== 对外状态 ==================== */

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** 集合的向量维度。**唯一来源是 embedding 配置**，这里只是为了少写一层取值 */
    public int dim() {
        Integer d = embedding.getDim();
        return d == null ? 1024 : d;
    }

    public String collectionName() {
        return props.getCollection();
    }

    public String databaseName() {
        return props.getDatabase();
    }

    /**
     * 连接可用性探测。**不抛异常**，只回布尔值 —— 供接口层的「索引服务状态」展示用。
     */
    public boolean ping() {
        if (!props.isEnabled()) {
            return false;
        }
        try {
            MilvusClientV2 c = acquire();
            c.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(props.getCollection()).build());
            return true;
        } catch (Exception e) {
            log.warn("[Milvus] 探活失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 集合中的向量行数。
     *
     * <p>**不抛异常**：它只服务管理端的概览数字。Milvus 不可用时返回 -1（前端显示「—」），
     * 比让整个概览接口 500 要合理 —— 概览页恰恰是用户排查「Milvus 是不是挂了」的地方，
     * 它自己不能因为 Milvus 挂了就打不开。
     */
    public long countAll() {
        if (!props.isEnabled()) {
            return -1L;
        }
        try {
            var resp = acquire().getCollectionStats(
                    GetCollectionStatsReq.builder().collectionName(props.getCollection()).build());
            Long n = resp.getNumOfEntities();
            return n == null ? -1L : n;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 供探针/前端展示的一行状态描述 */
    public String describeState() {        if (!props.isEnabled()) {
            return "已禁用（psycare.milvus.enabled=false）";
        }
        try {
            MilvusClientV2 c = acquire();
            var resp = c.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(props.getCollection()).build());
            Integer d = null;
            if (resp.getCollectionSchema() != null) {
                CreateCollectionReq.FieldSchema f = resp.getCollectionSchema().getField(F_DENSE);
                d = f == null ? null : f.getDimension();
            }
            return "%s / %s / %d 维 / 服务端 %s".formatted(
                    props.getDatabase(), props.getCollection(),
                    d == null ? dim() : d, c.getServerVersion());
        } catch (Exception e) {
            return "不可用：" + e.getMessage();
        }
    }

    /**
     * 丢弃当前连接状态，下一次调用重新建连。
     *
     * <p>用在两处：① 启动探活失败后，第一次真实写入时重试建连；
     * ② 「重建索引」被用户显式触发时 —— 用户按下按钮本身就意味着他可能刚把 Milvus 起起来。
     */
    public void invalidate() {
        synchronized (lock) {
            prepared = false;
        }
    }

    /* ==================== 写入 ==================== */

    /**
     * 批量 upsert 子块。
     *
     * <p>用 {@code upsert} 而不是 {@code insert}：重建索引是「先删后插」，
     * 但如果上一次删除在半途失败、或同一批被重试，insert 会因主键冲突整批失败，
     * 而 upsert 天然幂等。重建流程本身也因此可以安全重入。
     *
     * @return 实际写入条数
     * @throws MilvusUnavailableException 不可用时（调用方决定是否降级）
     */
    public int upsertChunks(List<MilvusChunkRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        MilvusClientV2 c = acquire();
        try {
            List<JsonObject> data = new ArrayList<>(rows.size());
            for (MilvusChunkRow r : rows) {
                if (r.dense() == null || r.dense().length != dim()) {
                    throw new IllegalArgumentException(
                            "向量维度与配置不一致：chunk_id=" + r.chunkId()
                                    + " 实际=" + (r.dense() == null ? "null" : r.dense().length)
                                    + " 期望=" + dim());
                }
                data.add(toJson(r));
            }
            var resp = c.upsert(UpsertReq.builder()
                    .collectionName(props.getCollection())
                    .data(data)
                    .build());
            if (props.isFlushAfterWrite()) {
                c.flush(FlushReq.builder().collectionNames(List.of(props.getCollection())).build());
            }
            return (int) resp.getUpsertCnt();
        } catch (MilvusUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new MilvusUnavailableException("Milvus 写入失败：" + e.getMessage(), e);
        }
    }

    /** 删除某文档在 Milvus 中的全部子块（重建索引的第一步） */
    public void deleteByDoc(long docId) {
        MilvusClientV2 c = acquire();
        try {
            // filter 是 Milvus 的表达式语句，不是 SQL —— 这里是数值拼接，无注入面；
            // 仍然断言一次，避免有人日后把 String 参数传进来。
            if (docId <= 0) {
                throw new IllegalArgumentException("docId 非法：" + docId);
            }
            c.delete(DeleteReq.builder()
                    .collectionName(props.getCollection())
                    .filter(F_DOC_ID + " == " + docId)
                    .build());
        } catch (MilvusUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new MilvusUnavailableException("Milvus 删除失败：" + e.getMessage(), e);
        }
    }

    /** 整个集合 drop（Phase 3 验收第 3 条：验证「Milvus 可重建」要用它） */
    public void dropCollection() {
        MilvusClientV2 c = acquire();
        try {
            c.dropCollection(DropCollectionReq.builder()
                    .collectionName(props.getCollection()).build());
            invalidate();
        } catch (Exception e) {
            throw new MilvusUnavailableException("Milvus 删除集合失败：" + e.getMessage(), e);
        }
    }

    /** 某文档在 Milvus 中的子块 id 列表（校验脚本与「索引是否完整」用） */
    public List<Long> listChunkIdsByDoc(long docId) {
        MilvusClientV2 c = acquire();
        try {
            var resp = c.query(QueryReq.builder()
                    .collectionName(props.getCollection())
                    .filter(F_DOC_ID + " == " + docId)
                    .outputFields(List.of(F_CHUNK_ID))
                    .limit(16384L)
                    .build());
            List<Long> ids = new ArrayList<>();
            for (var qr : resp.getQueryResults()) {
                Object v = qr.getEntity() == null ? null : qr.getEntity().get(F_CHUNK_ID);
                if (v instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
            ids.sort(Long::compareTo);
            return ids;
        } catch (Exception e) {
            throw new MilvusUnavailableException("Milvus 查询失败：" + e.getMessage(), e);
        }
    }

    /* ==================== 检索（Phase 4 用，此处备好接口） ==================== */

    /**
     * 混合检索：稠密（HNSW/COSINE） + 稀疏（BM25）→ RRF 融合。
     *
     * <p>稀疏那一路故意传<b>原始查询文本</b>而不是向量：BM25 的稀疏向量由服务端的
     * BM25 Function 按字段 analyzer 现场生成。这也是为什么 {@code text} 字段必须
     * 显式配中文分词器（默认 standard 分词按空格切，中文整句会变成一个 token，
     * BM25 退化成整句精确匹配，召回 0 且完全不报错）。
     *
     * @param dense      查询向量（由 {@link EmbeddingClient} 算出）
     * @param queryText  原始查询文本（喂给 BM25）
     * @param expr       标量过滤表达式（如 {@code source_type != "SYNTHETIC"}），可为 null
     */
    public List<MilvusHit> hybridSearch(float[] dense, String queryText, String expr) {
        MilvusClientV2 c = acquire();
        try {
            List<BaseVector> denseVecs = List.of(new FloatVec(dense));
            List<BaseVector> textVecs = List.of(new EmbeddedText(queryText));

            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectorFieldName(F_DENSE)
                    .vectors(denseVecs)
                    .metricType(IndexParam.MetricType.COSINE)
                    .params("{\"ef\": 128}")
                    .topK(props.getDenseTopK())
                    .build();

            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(F_SPARSE)
                    .vectors(textVecs)
                    .metricType(IndexParam.MetricType.BM25)
                    .params("{\"drop_ratio_search\": 0.2}")
                    .topK(props.getSparseTopK())
                    .build();

            if (expr != null && !expr.isBlank()) {
                denseReq.setFilter(expr);
                sparseReq.setFilter(expr);
            }

            SearchResp resp = c.hybridSearch(HybridSearchReq.builder()
                    .collectionName(props.getCollection())
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new RRFRanker(props.getRrfK()))
                    .topK(props.getFinalTopK())
                    .outFields(OUT_FIELDS)
                    .consistencyLevel(ConsistencyLevel.BOUNDED)
                    .build());

            List<MilvusHit> hits = new ArrayList<>();
            for (var group : resp.getSearchResults()) {
                for (SearchResp.SearchResult r : group) {
                    Map<String, Object> e = r.getEntity() == null ? Map.of() : r.getEntity();
                    hits.add(new MilvusHit(
                            num(e.get(F_CHUNK_ID), 0L),
                            num(e.get(F_DOC_ID), 0L),
                            nullIfZero(num(e.get(F_PARENT_ID), NONE)),
                            str(e.get(F_DOC_TITLE)),
                            str(e.get(F_SOURCE_TYPE)),
                            (int) num(e.get(F_PUBLISH_YEAR), NONE),
                            r.getScore() == null ? 0f : r.getScore(),
                            null));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new MilvusUnavailableException("Milvus 检索失败：" + e.getMessage(), e);
        }
    }

    /* ==================== 内部：连接与建表 ==================== */

    private MilvusClientV2 acquire() {
        if (!props.isEnabled()) {
            throw new MilvusUnavailableException("Milvus 已禁用（psycare.milvus.enabled=false）");
        }
        if (prepared) {
            return client;
        }
        synchronized (lock) {
            if (!prepared) {
                prepare();
            }
            return client;
        }
    }

    private void prepare() {
        MilvusClientV2 c = null;
        try {
            ConnectConfig.ConnectConfigBuilder b = ConnectConfig.builder()
                    .uri(props.resolvedUri())
                    .connectTimeoutMs(props.getConnectTimeoutMs())
                    .rpcDeadlineMs(props.getRpcDeadlineMs());
            if (props.hasToken()) {
                b.token(props.resolvedToken());
            }
            c = new MilvusClientV2(b.build());

            ensureDatabase(c);
            try {
                c.useDatabase(props.getDatabase());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new MilvusUnavailableException("切换到 database 时被中断：" + props.getDatabase(), ie);
            }

            boolean exists = Boolean.TRUE.equals(c.hasCollection(
                    HasCollectionReq.builder().collectionName(props.getCollection()).build()));

            if (!exists) {
                createCollection(c);
                log.info("[Milvus] 已创建集合 {}/{}（dim={}，含 jieba 分词 + BM25 Function）",
                        props.getDatabase(), props.getCollection(), dim());
            } else {
                verifyDim(c);
            }

            c.loadCollection(LoadCollectionReq.builder()
                    .collectionName(props.getCollection()).build());

            this.client = c;
            this.prepared = true;
            log.info("[Milvus] 就绪：{} / {} / dim={} / token={}",
                    props.resolvedUri(), props.getCollection(), dim(), props.maskedToken());
        } catch (MilvusUnavailableException e) {
            closeQuietly(c);
            throw e;
        } catch (Exception e) {
            closeQuietly(c);
            throw new MilvusUnavailableException(
                    "Milvus 连接失败（" + props.resolvedUri() + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 独立 database 的幂等创建。
     *
     * <p>不用「连接时直接指定 dbName」：那样在库还不存在时，连接本身就失败，
     * 而错误信息只是一句模糊的鉴权/连接失败。先连默认库、再建库、再切换，
     * 每一步的失败原因都可读。
     */
    private void ensureDatabase(MilvusClientV2 c) {
        String db = props.getDatabase();
        if (db == null || db.isBlank() || "default".equals(db)) {
            return;
        }
        List<String> names = c.listDatabases().getDatabaseNames();
        if (names != null && names.contains(db)) {
            return;
        }
        c.createDatabase(CreateDatabaseReq.builder().databaseName(db).build());
        log.info("[Milvus] 已创建独立 database：{}（与实例上其他人的集合隔离）", db);
    }

    private void createCollection(MilvusClientV2 c) {
        // 用静态工厂而非 c.createSchema()：实例方法在 3.0.5 已标 deprecated
        CreateCollectionReq.CollectionSchema schema = MilvusClientV2.CreateSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName(F_CHUNK_ID).dataType(DataType.Int64)
                .isPrimaryKey(true).autoID(false)
                .description("= MySQL kb_chunk.id（双库 id 一致是回表查询的前提）")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_DOC_ID).dataType(DataType.Int64)
                .description("所属文档；过滤与 deleteByDoc 用")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_PARENT_ID).dataType(DataType.Int64)
                .description("父块 id（0 = 无父）；回表取正文的钥匙")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_TEXT).dataType(DataType.VarChar).maxLength(props.getTextMaxBytes())
                .enableAnalyzer(true)
                // 中文分词必须显式配：默认 standard 分词按空格/标点切，
                // 中文没有空格 → 整句变成一个 token → BM25 退化为整句精确匹配，召回 0 且不报错。
                .analyzerParams(Map.of("tokenizer", "jieba", "filter", List.of("cnalphanumonly")))
                .description("子块正文；BM25 的分析源字段")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_DENSE).dataType(DataType.FloatVector).dimension(dim())
                .description("稠密向量（通义 embedding 输出）")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_SPARSE).dataType(DataType.SparseFloatVector)
                .description("稀疏向量：由 BM25 Function 自动生成，**写入时不提供**")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_DOC_TITLE).dataType(DataType.VarChar).maxLength(256)
                .description("引用卡片展示").build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_SOURCE_TYPE).dataType(DataType.VarChar).maxLength(32)
                .description("SYNTHETIC / AUTHORITATIVE / UPLOAD").build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_PUBLISH_YEAR).dataType(DataType.Int32)
                .description("发布年份（0 = 未知）").build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_PAGE_FROM).dataType(DataType.Int32)
                .description("起始页（0 = 未知）").build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_PAGE_TO).dataType(DataType.Int32)
                .description("结束页（0 = 未知）").build());

        schema.addField(AddFieldReq.builder()
                .fieldName(F_TOKEN_COUNT).dataType(DataType.Int32)
                .description("估算 token 数（预算控制）").build());

        // BM25 Function：把 text 自动转成 sparse_vector。
        // 写入侧<b>不提供</b> sparse_vector，由服务端生成 —— 手工算 BM25 权重既没必要也易错。
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("bm25_text_to_sparse")
                .inputFieldNames(List.of(F_TEXT))
                .outputFieldNames(List.of(F_SPARSE))
                .build());

        c.createCollection(CreateCollectionReq.builder()
                .collectionName(props.getCollection())
                .description("PsyCare 知识库子块索引（事实源在 MySQL kb_chunk）")
                .collectionSchema(schema)
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .indexParams(List.of(
                        // 稠密：HNSW + COSINE（免手工归一化）
                        IndexParam.builder()
                                .fieldName(F_DENSE)
                                .indexType(IndexParam.IndexType.HNSW)
                                .metricType(IndexParam.MetricType.COSINE)
                                .extraParams(Map.of("M", 16, "efConstruction", 200))
                                .build(),
                        // 稀疏：BM25 倒排。⚠️ 索引与查询的 metricType 必须一致
                        IndexParam.builder()
                                .fieldName(F_SPARSE)
                                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                .metricType(IndexParam.MetricType.BM25)
                                .extraParams(Map.of("inverted_index_algo", "DAAT_MAXSCORE"))
                                .build(),
                        // 标量索引：加速 doc_id / source_type 过滤
                        IndexParam.builder()
                                .fieldName(F_DOC_ID)
                                .indexType(IndexParam.IndexType.INVERTED)
                                .build(),
                        IndexParam.builder()
                                .fieldName(F_SOURCE_TYPE)
                                .indexType(IndexParam.IndexType.INVERTED)
                                .build()))
                .build());
    }

    /**
     * 集合已存在时校验维度。
     *
     * <p>这是本次唯一能挡住「换了向量模型但没重建索引」的地方 ——
     * 那种情况下写入会失败（还算好的），但如果新模型维度恰好相同，
     * 就会得到一库<b>空间不可比</b>的向量，检索结果接近随机且毫无报错。
     * 所以这里对不上就直接拒绝服务，而不是打个 WARN 继续跑。
     */
    private void verifyDim(MilvusClientV2 c) {
        var resp = c.describeCollection(DescribeCollectionReq.builder()
                .collectionName(props.getCollection()).build());
        if (resp.getCollectionSchema() == null) {
            return;
        }
        CreateCollectionReq.FieldSchema f = resp.getCollectionSchema().getField(F_DENSE);
        if (f == null || f.getDimension() == null) {
            return;
        }
        if (!f.getDimension().equals(dim())) {
            throw new MilvusUnavailableException(
                    "集合 " + props.getCollection() + " 的维度是 " + f.getDimension()
                            + "，而当前 embedding.dim = " + dim()
                            + "。换过向量模型必须整体重建索引（旧向量与新向量不在同一个空间里）；"
                            + "确认后先 drop 该集合再重启。");
        }
    }

    private JsonObject toJson(MilvusChunkRow r) {
        JsonObject o = new JsonObject();
        o.addProperty(F_CHUNK_ID, r.chunkId());
        o.addProperty(F_DOC_ID, r.docId());
        o.addProperty(F_PARENT_ID, r.parentId() == null ? NONE : r.parentId());
        o.addProperty(F_TEXT, truncateByBytes(r.text(), props.getTextMaxBytes()));
        o.addProperty(F_DOC_TITLE, truncateByBytes(r.docTitle() == null ? "" : r.docTitle(), 256));
        o.addProperty(F_SOURCE_TYPE, r.sourceType() == null ? "UPLOAD" : r.sourceType());
        o.addProperty(F_PUBLISH_YEAR, r.publishYear() == null ? 0 : r.publishYear());
        o.addProperty(F_PAGE_FROM, r.pageFrom() == null ? 0 : r.pageFrom());
        o.addProperty(F_PAGE_TO, r.pageTo() == null ? 0 : r.pageTo());
        o.addProperty(F_TOKEN_COUNT, r.tokenCount() == null ? 0 : r.tokenCount());

        JsonArray vec = new JsonArray();
        for (float v : r.dense()) {
            vec.add(v);
        }
        o.add(F_DENSE, vec);
        // sparse_vector 故意不写：由 BM25 Function 生成
        return o;
    }

    /**
     * 按 UTF-8 字节数截断（Milvus 的 VarChar maxLength 是<b>字节</b>）。
     *
     * <p>不能按字符切：中文一个字 3 字节，500 字就是 1500 字节，
     * 而按字符数判断长度的人会以为 4096 能装 4096 个字。截断时也不能切出半个字符 ——
     * 所以按字符逐个累加字节数，而不是 substring。
     */
    private static String truncateByBytes(String s, int maxBytes) {
        if (s == null) {
            return "";
        }
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int bytes = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int b = String.valueOf(ch).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + b > maxBytes - 3) {   // 留 3 字节给省略号
                sb.append("...");
                break;
            }
            bytes += b;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static long num(Object o, long dft) {
        return o instanceof Number n ? n.longValue() : dft;
    }

    private static Long nullIfZero(long v) {
        return v == NONE ? null : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void closeQuietly(MilvusClientV2 c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // 关闭失败没有可采取的动作，且此时通常已经在错误路径上
        }
    }

    /**
     * 供日志与排错：把一组命中结果压成一行 {@code 块id@文档id:融合分}。
     * 单独的「命中了几条」看不出问题，压成一行之后「为什么是这几条」才可核对。
     */
    public static String brief(List<MilvusHit> hits) {
        return hits.stream()
                .map(h -> h.chunkId() + "@" + h.docId() + ":" + String.format("%.4f", h.score()))
                .collect(Collectors.joining(", "));
    }
}
