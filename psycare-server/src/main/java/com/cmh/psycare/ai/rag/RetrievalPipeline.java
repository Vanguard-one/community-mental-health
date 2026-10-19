package com.cmh.psycare.ai.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.entity.KbChunk;
import com.cmh.psycare.entity.KbDocument;
import com.cmh.psycare.mapper.KbChunkMapper;
import com.cmh.psycare.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 在线检索管线：把「用户这一句话」变成「可以注入提示词的几段资料」。
 *
 * <h3>完整步骤（计划 §Phase 4）</h3>
 * <pre>
 * ① 查询改写（默认关）    多轮指代 → 自足问句
 * ② 查询向量化            整条 query，不切片
 * ③ 混合召回（Milvus）    dense Top20 ∥ sparse Top20  →  RRF 融合 → Top5
 * ④ 阈值过滤              只用 denseScore 比阈值；稀疏召回的无分条目保留
 * ⑤ 父块回表（MySQL）      子块负责被搜到，父块负责被读懂
 * ⑥ 预算裁剪              maxInjected 条 + maxContextTokens token 双重上限
 * </pre>
 *
 * <h3>三条不可违背的边界</h3>
 * <ol>
 *   <li><b>本类绝不抛异常</b>。RAG 是增强不是依赖（计划 5.6）：Milvus 挂了、embedding
 *       超时、Redis 连不上，一律返回空列表，让对话退化成「无资料」继续跑。
 *       把异常抛上去 = 让「索引层的问题」变成「用户说不了话」。</li>
 *   <li><b>不做任何写操作</b>，只读。检索路径上出现事务是设计事故。</li>
 *   <li><b>向量化与 Milvus 调用都在事务外</b>（硬约定 #76）——本类无 {@code @Transactional}，
 *       调用方（{@code AiConsultService}）也不得把它包进事务。</li>
 * </ol>
 *
 * <h3>缓存为什么缓「向量」而不是缓「结果」</h3>
 * <p>缓结果要存整个 {@code RetrievedChunk} 列表（正文很占地方），且语料一改就全失效。
 * 缓向量只存 1024 个 float（约 8 KB 的 JSON），命中后<b>仍走 Milvus 检索</b> ——
 * 语料更新立刻可见，永远不会返回过期的正文。省掉的正是最贵、最慢的那一步
 * （一次外部 HTTP），而 Milvus 检索是本地 RPC，本来就快。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalPipeline {

    /** 缓存 key 前缀，与 StageStore 的 {@code ai:stage:*} 同属 {@code ai:} 命名空间 */
    private static final String CACHE_PREFIX = "ai:cache:emb:";

    private final RetrievalProperties props;
    private final MilvusVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final StringRedisTemplate redis;

    /**
     * 执行一次检索。
     *
     * @param queryText 已改写的用户问句（调用方负责改写；本类不调 LLM）
     * @return 按融合分降序的命中；任何环节失败都返回空列表
     */
    public List<RetrievedChunk> retrieve(String queryText) {
        return retrieve(queryText, null);
    }

    /**
     * 执行一次检索，并可用 {@code minDenseScoreOverride} 临时覆盖配置里的阈值。
     *
     * <h3>为什么要这个重载，而不是在评测端点里改配置对象</h3>
     * <p>阈值网格扫描（0.3 / 0.4 / 0.5 / 0.6 / 0.7）要求同一个进程内跑多次不同阈值。
     * 若在端点里 {@code props.setMinDenseScore(x)}，就是改<b>全局单例的字段</b>：
     * 扫描期间任何一个真实用户发起的对话都会读到那个临时值，而且扫描结束忘了恢复
     * 就把线上阈值永久改掉了。把覆盖值作为<b>方法参数</b>一路传下去，天然线程安全、
     * 也不会留下任何残留状态。
     *
     * @param minDenseScoreOverride 为 null 时用配置值；评测端点才传非 null
     */
    public List<RetrievedChunk> retrieve(String queryText, Float minDenseScoreOverride) {
        if (!props.isEnabled() || !vectorStore.isEnabled()) {
            return List.of();
        }
        String q = queryText == null ? "" : queryText.trim();
        if (q.isEmpty()) {
            return List.of();
        }

        // 生效阈值：覆盖优先，否则取配置
        float min = minDenseScoreOverride != null
                ? minDenseScoreOverride : props.getMinDenseScore();

        try {
            // ---- ② 查询向量化（先查缓存） ----
            float[] dense = denseVector(q);
            if (dense == null) {
                // 向量化彻底失败：不能拿 null 去检索。退化为「无资料」。
                return List.of();
            }

            // ---- ③ 混合召回 + RRF 融合（Milvus 内部完成） ----
            List<MilvusHit> hits = vectorStore.hybridSearch(dense, q, null);
            if (hits == null || hits.isEmpty()) {
                log.debug("[RAG] 混合检索无命中 query={}", brief(q));
                return List.of();
            }

            // ---- ④ 阈值过滤 ----
            List<MilvusHit> passed = applyThreshold(hits, min);
            if (passed.isEmpty()) {
                log.info("[RAG] 全部命中低于阈值 {}（共 {} 条），按「无资料」处理 query={}",
                        min, hits.size(), brief(q));
                return List.of();
            }

            // ---- ⑤ 父块回表 ----
            List<RetrievedChunk> chunks = hydrate(passed);

            // ---- ⑥ 预算裁剪 ----
            return trim(chunks);

        } catch (MilvusUnavailableException e) {
            // 索引层不可用是可预期状态（没起 Milvus / 集合被删），不该刷 error 日志
            log.warn("[RAG] 检索降级（Milvus 不可用）：{}", e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            log.warn("[RAG] 检索降级（未预期异常）：{}", e.toString());
            return List.of();
        }
    }

    /**
     * 阈值过滤。
     *
     * <h3>为什么「denseScore == null 时保留」</h3>
     * <p>null 意味着这条是<b>稀疏（BM25）路单独召回</b>的 —— 也就是向量路没看上、
     * 但关键词精确命中了。这类命中恰恰是混合检索存在的理由：
     * 「PHQ-9 第 9 题」「舍曲林」这种查询，向量路给的分可能很低甚至不召回，
     * 而用户要的正是一条精确匹配。把它当 0 分丢掉，等于花钱做了混合检索却只用了一半。
     */
    private List<MilvusHit> applyThreshold(List<MilvusHit> hits, float min) {
        List<MilvusHit> out = new ArrayList<>(hits.size());
        for (MilvusHit h : hits) {
            Float ds = h.denseScore();
            if (ds == null || ds >= min) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * 回表取正文：子块 → 父块。这一步只能在 MySQL 做（Milvus 里没有父块）。
     *
     * <p>一次批量 {@code IN} 查询取全部父块，不是 N 次单查 —— 命中 5 条时
     * 5 次往返在 P95 上是看得见的差异。
     */
    private List<RetrievedChunk> hydrate(List<MilvusHit> hits) {
        // 一次性把命中的子块与它们的父块都查出来
        Set<Long> chunkIds = new HashSet<>();
        Set<Long> parentIds = new HashSet<>();
        for (MilvusHit h : hits) {
            chunkIds.add(h.chunkId());
            if (h.parentId() != null) {
                parentIds.add(h.parentId());
            }
        }

        Map<Long, KbChunk> parents = parentIds.isEmpty()
                ? Map.of() : loadChunks(parentIds);
        Set<Long> missingParent = new HashSet<>(parentIds);
        missingParent.removeAll(parents.keySet());

        // 父块查不到（被删了 / 索引残留）时退回子块自己的正文，而不是直接丢弃 ——
        // 「有一段能用的资料」比「什么都没有」好，但要用子块正文的前提是它确实存在。
        Map<Long, KbChunk> children = loadChunks(chunkIds);

        // 文档级元数据（标题 / source_type 用于角标），一次批量取
        Map<Long, KbDocument> docs = loadDocuments(hits);

        List<RetrievedChunk> out = new ArrayList<>(hits.size());
        for (MilvusHit h : hits) {
            KbChunk body = null;
            Long usedParentId = null;
            if (h.parentId() != null) {
                body = parents.get(h.parentId());
                if (body != null) {
                    usedParentId = h.parentId();
                }
            }
            if (body == null) {
                body = children.get(h.chunkId());
                usedParentId = null;
            }
            if (body == null || body.getContent() == null || body.getContent().isBlank()) {
                // MySQL 侧完全没有这条数据 —— 索引残留（幽灵块）。丢弃并记一笔。
                log.warn("[RAG] 命中 chunkId={} 在 MySQL 无正文，疑似索引残留，已丢弃", h.chunkId());
                continue;
            }

            KbDocument doc = docs.get(h.docId());
            String title = doc != null ? doc.getTitle() : h.docTitle();
            boolean synthetic = "SYNTHETIC".equalsIgnoreCase(
                    doc != null ? doc.getSourceType() : h.sourceType());

            out.add(new RetrievedChunk(
                    h.chunkId(), h.docId(), usedParentId,
                    title, body.getSection(), body.getContent(),
                    h.score(), h.denseScore(), synthetic));
        }
        return out;
    }

    private Map<Long, KbChunk> loadChunks(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<KbChunk> rows = chunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>().in(KbChunk::getId, ids));
        Map<Long, KbChunk> map = new HashMap<>(rows.size());
        for (KbChunk c : rows) {
            map.put(c.getId(), c);
        }
        return map;
    }

    private Map<Long, KbDocument> loadDocuments(List<MilvusHit> hits) {
        Set<Long> ids = new HashSet<>();
        for (MilvusHit h : hits) {
            ids.add(h.docId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        // 只取未删除的：文档已软删时它不该再被引用（Milvus 行可能还没清干净）
        List<KbDocument> rows = documentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().in(KbDocument::getId, ids));
        Map<Long, KbDocument> map = new HashMap<>(rows.size());
        for (KbDocument d : rows) {
            map.put(d.getId(), d);
        }
        return map;
    }

    /**
     * 预算裁剪：条数上限 + token 预算，两道都过。
     *
     * <p>超预算时<b>整条丢弃</b>而不是截断正文 —— 半截段落比没有更容易误导模型
     * （一句话被拦腰切断后，剩下的部分常常正好读成相反的意思）。
     */
    private List<RetrievedChunk> trim(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        int maxN = Math.max(1, props.getMaxInjected());
        int budget = props.getMaxContextTokens();

        Map<Long, RetrievedChunk> picked = new LinkedHashMap<>();
        int used = 0;
        int n = 0;
        for (RetrievedChunk c : chunks) {          // 已按融合分降序
            if (n >= maxN) {
                break;
            }
            int cost = com.cmh.psycare.ai.ContextAssembler.estimateTokens(c.toPromptLine());
            if (used + cost > budget && n > 0) {
                break;
            }
            picked.put(c.chunkId(), c);
            used += cost;
            n++;
        }
        return new ArrayList<>(picked.values());
    }

    /* ==================== 查询向量化与缓存 ==================== */

    /**
     * 取查询向量：先查 Redis，未命中再调外部接口并回填。
     *
     * <p>缓存失败一律降级为「直接调」，不能因为缓存不可用就让检索不可用 ——
     * 缓存在这里纯粹是省钱省延迟的手段。
     */
    private float[] denseVector(String q) {
        String key = cacheKey(q);

        float[] cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        float[] v;
        try {
            v = embeddingClient.embed(q);
        } catch (RuntimeException e) {
            log.warn("[RAG] 查询向量化失败，本轮无资料：{}", e.getMessage());
            return null;
        }
        if (v == null || v.length == 0) {
            return null;
        }

        writeCache(key, v);
        return v;
    }

    private String cacheKey(String q) {
        // 归一化：去空白差异，避免「同一句话多个空格」占用多个 key
        String norm = q.replaceAll("\\s+", " ").trim();
        return CACHE_PREFIX + sha256(norm);
    }

    private float[] readCache(String key) {
        if (!props.isCacheEnabled()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            return parseVector(raw);
        } catch (RuntimeException e) {
            // Redis 不可用是已知环境坑（R-11），缓存失效不影响正确性
            log.debug("[RAG] 读缓存失败（忽略）：{}", e.toString());
            return null;
        }
    }

    private void writeCache(String key, float[] v) {
        if (!props.isCacheEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key, formatVector(v),
                    props.getCacheTtlSeconds(), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.debug("[RAG] 写缓存失败（忽略）：{}", e.toString());
        }
    }

    /**
     * 向量序列化：逗号分隔的浮点串，不是 JSON 数组。
     *
     * <p>理由：JSON 里每个数字都要带方括号与引号，1024 维字符串会长 30% 左右；
     * 这里只做等长解析，格式简单反而更不容易出错（比 Gson 的反射路径也更快）。
     * 用 Float.toString 保留精度，解析失败即视为未命中。
     */
    static String formatVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    static float[] parseVector(String raw) {
        String[] parts = raw.split(",");
        float[] v = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                v[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 在任何 JVM 上都存在；走到这里说明环境异常，用 hashCode 兜底也比崩强
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 日志用的短摘要：不打印全文，避免把用户隐私写进日志 */
    private static String brief(String q) {
        return q.length() <= 20 ? q : q.substring(0, 20) + "…";
    }

    /** 供管理端 / 探针展示当前检索配置 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("vectorStoreEnabled", vectorStore.isEnabled());
        m.put("minDenseScore", props.getMinDenseScore());
        m.put("maxInjected", props.getMaxInjected());
        m.put("maxContextTokens", props.getMaxContextTokens());
        m.put("cacheEnabled", props.isCacheEnabled());
        m.put("rewriteEnabled", props.isRewriteEnabled());
        return Collections.unmodifiableMap(m);
    }
}
