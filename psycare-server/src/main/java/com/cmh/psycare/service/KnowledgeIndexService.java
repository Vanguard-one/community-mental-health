package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.ai.rag.DocumentParser;
import com.cmh.psycare.ai.rag.EmbeddingBatch;
import com.cmh.psycare.ai.rag.EmbeddingClient;
import com.cmh.psycare.ai.rag.EmbeddingProperties;
import com.cmh.psycare.ai.rag.MilvusChunkRow;
import com.cmh.psycare.ai.rag.MilvusUnavailableException;
import com.cmh.psycare.ai.rag.MilvusVectorStore;
import com.cmh.psycare.ai.rag.TextSplitter;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.entity.KbChunk;
import com.cmh.psycare.entity.KbDocument;
import com.cmh.psycare.mapper.KbChunkMapper;
import com.cmh.psycare.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库索引编排：把「一篇文档」变成「MySQL 事实 + Milvus 索引」。
 *
 * <h3>写入顺序：先 MySQL 后 Milvus（计划 5.4 阶段五）</h3>
 * <p>这不是随手定的顺序。事实源必须在索引层之前落盘 —— 反过来的话，
 * Milvus 写成功而 MySQL 失败，就会得到一个<b>搜得到但查不到原文</b>的幽灵块
 * （检索命中 → 回表取父块 → 查不到 → 用户看到一条没有内容的引用）。
 * 而 MySQL 成功、Milvus 失败只是「暂时搜不到」，重跑即可，是可恢复的。
 *
 * <h3>为什么外部调用不放在事务里</h3>
 * <p>向量化是 HTTP 调用（秒级），Milvus 写入是 RPC。把它们放进 {@code @Transactional}
 * 会让数据库连接被一个网络请求占住，最坏情况是连接池被几个索引任务耗光。
 * 所以这里的形状是 <b>「事务内只做两次批量写」+「事务外做两次外部调用」</b>，
 * 用 {@link TransactionTemplate} 显式划出事务边界（而不是靠 {@code @Transactional}
 * 注解 —— 注解对本类内部方法的调用不生效，这是个只会静默失效的坑）。
 *
 * <h3>重建的原文从哪来</h3>
 * <p>按 DDL 的设计，分块是<b>唯一的事实</b>，没有单独的「原文」列。
 * 重建时按 {@code ordinal} 升序把所有 PARENT 块拼回来即可 —— 父块本来就是
 * 「按段落聚合、不丢内容」的产物。这样做的额外好处是：<b>改切分参数后重建，
 * 走的是完全同一条代码路径</b>，不需要为「原文」再维护一份存储和一套同步逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexService {

    private static final String ST_PENDING = "PENDING";
    private static final String ST_INDEXING = "INDEXING";
    private static final String ST_READY = "READY";
    private static final String ST_FAILED = "FAILED";

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProps;
    private final MilvusVectorStore vectorStore;
    private final TransactionTemplate txTemplate;

    /**
     * 一次索引的结果。
     *
     * @param milvusOk        向量索引是否建成。**false 不等于失败** —— 见 {@link #markFailure} 的说明
     * @param failedVectors   向量化永久失败（毒数据）的条数；这些子块「入库了但搜不到」
     */
    public record IndexReport(long docId, String status, int parentCount, int childCount,
                              int milvusRows, int failedVectors, boolean milvusOk, String message) {
    }

    /* ==================== 建档 ==================== */

    /**
     * 新建文档并建索引。
     *
     * <p>同名文档冲突在这里<b>主动检查</b>而不是等唯一索引报 SQL 异常：
     * {@code uk_alive_title} 是函数索引（只约束未删除行），违反它抛出的
     * {@code DuplicateKeyException} 到最后只会变成一句「系统异常」。
     */
    public IndexReport createAndIndex(String title, String sourceType, Integer publishYear,
                                      String sourcePath, String text, TextSplitter.Options opts) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            throw new BizException(400, "文档标题不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new BizException(400, "文档内容不能为空");
        }
        Long same = documentMapper.selectCount(new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getTitle, t));
        if (same != null && same > 0) {
            throw new BizException(400, "已存在同名文档《" + t + "》——请改名，或先删除原文档再重建");
        }

        KbDocument doc = new KbDocument();
        doc.setTitle(t);
        doc.setSourceType(normalizeSourceType(sourceType));
        doc.setPublishYear(publishYear);
        doc.setSourcePath(sourcePath);
        doc.setStatus(ST_PENDING);
        doc.setChunkCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        return indexText(doc, text, opts);
    }

    /** 批量导入（JSONL 一次产出多篇；**每篇失败不影响其余**，逐篇上报结果） */
    public List<IndexReport> importParsed(List<DocumentParser.ParsedDocument> docs, TextSplitter.Options opts) {
        List<IndexReport> reports = new ArrayList<>();
        for (DocumentParser.ParsedDocument d : docs) {
            try {
                IndexReport r = createAndIndex(
                        d.title(), d.sourceType(), d.publishYear(), d.sourcePath(), d.text(), opts);
                reports.add(r);
                log.info("[KB] 已导入《{}》id={} status={} chunks={}",
                        d.title(), r.docId(), r.status(), r.childCount());
            } catch (Exception e) {
                log.warn("[KB] 导入《{}》失败：{}", d.title(), e.getMessage());
                reports.add(new IndexReport(-1, ST_FAILED, 0, 0, 0, 0, false,
                        "《" + d.title() + "》" + e.getMessage()));
            }
        }
        return reports;
    }

    /* ==================== 重建 ==================== */

    /**
     * 重建某文档的索引。
     *
     * <p>两个场景共用它：① 换了切分参数想重切；② Milvus 的 collection 被删了
     * （Phase 3 验收第 3 条明确要验这个）。两者对代码的要求是一样的 ——
     * <b>只依赖 MySQL，不依赖 Milvus 现有状态</b>，所以先把 Milvus 该文档的行删干净，
     * 再从 MySQL 的父块拼回原文重跑。
     */
    public IndexReport rebuild(long docId, TextSplitter.Options opts) {
        KbDocument doc = requireDoc(docId);
        String text = reconstructText(docId);
        if (text.isBlank()) {
            throw new BizException(400, "该文档没有可用的原文分块，无法重建；请删除后重新上传");
        }
        if (vectorStore.isEnabled()) {
            try {
                vectorStore.deleteByDoc(docId);
            } catch (MilvusUnavailableException e) {
                // 删不掉不阻断：下面的 upsert 是幂等的（主键 = chunk_id），
                // 重复写入会覆盖而不是产生脏行。
                log.warn("[KB] 重建前清理 Milvus 旧行失败（不阻断，upsert 幂等）：{}", e.getMessage());
            }
        }
        return indexText(doc, text, opts);
    }

    /** 删除文档：软删文档行 + **物理**删分块 + 删 Milvus 行 */
    public void delete(long docId) {
        requireDoc(docId);
        txTemplate.execute(s -> {
            // 分块用物理删：DDL 注释写明「分块不可变，内容变了就是一次重建」，
            // 留在库里只会让每次重建都多积一批 is_deleted=1 的行，且没有任何查询会用到它们。
            chunkMapper.hardDeleteByDoc(docId);
            documentMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                    .eq(KbDocument::getId, docId)
                    .set(KbDocument::getIsDeleted, 1)
                    .set(KbDocument::getDeletedAt, LocalDateTime.now())
                    .set(KbDocument::getUpdatedAt, LocalDateTime.now()));
            return null;
        });
        if (vectorStore.isEnabled()) {
            try {
                vectorStore.deleteByDoc(docId);
            } catch (MilvusUnavailableException e) {
                // 只告警：Milvus 里留下的孤儿行不影响正确性（回表查不到文档即丢弃），
                // 而且下次全量重建会清掉。
                log.warn("[KB] 删除 Milvus 行失败（已忽略）：{}", e.getMessage());
            }
        }
    }

    /* ==================== 核心：切分 → 双写 ==================== */

    private IndexReport indexText(KbDocument doc, String text, TextSplitter.Options opts) {
        long docId = doc.getId();
        markStatus(docId, ST_INDEXING, null, null, null);

        List<TextSplitter.Draft> drafts;
        try {
            drafts = TextSplitter.split(text, opts);
        } catch (RuntimeException e) {
            return markFailure(doc, "切分失败：" + e.getMessage());
        }
        List<TextSplitter.Draft> parents = drafts.stream()
                .filter(d -> TextSplitter.TYPE_PARENT.equals(d.type())).toList();
        List<TextSplitter.Draft> children = drafts.stream()
                .filter(d -> TextSplitter.TYPE_CHILD.equals(d.type())).toList();
        if (children.isEmpty()) {
            return markFailure(doc, "切分结果为空（内容可能全是空白字符）");
        }

        // ---------- 1. MySQL：先删后插（一张表两批 insert，事务保护） ----------
        List<KbChunk> savedChildren;
        try {
            savedChildren = txTemplate.execute(status -> persist(docId, parents, children));
        } catch (RuntimeException e) {
            log.error("[KB] 分块落库失败 docId={}", docId, e);
            return markFailure(doc, "分块落库失败：" + e.getMessage());
        }
        if (savedChildren == null || savedChildren.isEmpty()) {
            return markFailure(doc, "分块落库结果为空");
        }

        // ---------- 2. 向量化（外部 HTTP，事务外） ----------
        List<String> texts = savedChildren.stream().map(KbChunk::getContent).toList();
        EmbeddingBatch batch;
        try {
            batch = embeddingClient.embedBatch(texts);
        } catch (RuntimeException e) {
            // 走到这里说明整批 + 逐条降级都失败，属于「与单条内容无关」的原因
            //（Key 失效 / 网络不通），不是毒数据。
            log.error("[KB] 向量化失败 docId={} 条数={}", docId, texts.size(), e);
            return markFailure(doc, "向量化失败：" + e.getMessage());
        }

        // ---------- 3. Milvus：写入（外部 RPC，事务外；失败不毁掉 MySQL 侧成果） ----------
        int milvusRows = 0;
        boolean milvusOk = false;
        String milvusMsg = null;
        if (!vectorStore.isEnabled()) {
            milvusMsg = "Milvus 已禁用（psycare.milvus.enabled=false），仅入库未建向量索引";
            log.warn("[KB] {} docId={}", milvusMsg, docId);
        } else {
            List<MilvusChunkRow> rows = new ArrayList<>();
            for (int i = 0; i < savedChildren.size(); i++) {
                float[] vec = batch.vectors().get(i);
                if (vec == null) {
                    continue;   // 毒数据：该子块不入 Milvus，明确计入 failedVectors
                }
                KbChunk c = savedChildren.get(i);
                rows.add(new MilvusChunkRow(
                        c.getId(), docId, c.getParentId(), c.getContent(),
                        doc.getTitle(), doc.getSourceType(), doc.getPublishYear(),
                        c.getPageFrom(), c.getPageTo(), c.getTokenCount(), vec));
            }
            try {
                milvusRows = vectorStore.upsertChunks(rows);
                milvusOk = true;
            } catch (MilvusUnavailableException e) {
                milvusMsg = "Milvus 写入失败：" + e.getMessage();
                log.warn("[KB] {} docId={}（MySQL 侧分块已保留，可重跑重建）", milvusMsg, docId);
            }
        }

        // ---------- 4. 收尾状态 ----------
        int failed = batch.failedIndexes() == null ? 0 : batch.failedIndexes().size();
        // Milvus 禁用时 status 仍记 READY：事实源已经可用了，这是「配置层面没开索引」，
        // 不是「这次索引做坏了」。两者混成一个 FAILED，会让人每次都要去看 error_msg 才知道该不该管。
        boolean hardFail = vectorStore.isEnabled() && !milvusOk;
        String status = hardFail ? ST_FAILED : ST_READY;
        String note = hardFail ? milvusMsg
                : (milvusMsg == null ? null : milvusMsg + (failed > 0 ? "；另有 " + failed + " 条向量化失败" : ""));

        markStatus(docId, status, note, children.size(), null);
        if (failed > 0) {
            log.warn("[KB] docId={} 有 {} 条子块向量化失败（已入库但搜不到）", docId, failed);
        }
        IndexReport report = new IndexReport(docId, status, parents.size(), children.size(),
                milvusRows, failed, milvusOk, note);
        log.info("[KB] 索引完成 docId={} status={} 父块={} 子块={} 向量行={} 失败={}",
                docId, status, parents.size(), children.size(), milvusRows, failed);
        return report;
    }

    /** 事务内：清旧分块 → 插父块（记 ordinal→id）→ 插子块（回填 parentId） */
    private List<KbChunk> persist(long docId, List<TextSplitter.Draft> parents, List<TextSplitter.Draft> children) {
        chunkMapper.hardDeleteByDoc(docId);

        LocalDateTime now = LocalDateTime.now();
        Map<Integer, Long> parentIdByOrdinal = new HashMap<>();
        for (TextSplitter.Draft d : parents) {
            KbChunk c = toEntity(docId, d, null, now);
            chunkMapper.insert(c);
            parentIdByOrdinal.put(d.ordinal(), c.getId());
        }

        List<KbChunk> kids = new ArrayList<>(children.size());
        for (TextSplitter.Draft d : children) {
            Long pid = parentIdByOrdinal.get(d.parentOrdinal());
            KbChunk c = toEntity(docId, d, pid, now);
            chunkMapper.insert(c);
            kids.add(c);
        }
        return kids;
    }

    private KbChunk toEntity(long docId, TextSplitter.Draft d, Long parentId, LocalDateTime now) {
        KbChunk c = new KbChunk();
        c.setDocId(docId);
        c.setParentId(parentId);
        c.setChunkType(d.type());
        c.setOrdinal(d.ordinal());
        c.setSection(truncate(d.section(), 256));
        c.setContent(d.content());
        c.setCharCount(d.charCount());
        c.setTokenCount(d.tokenCount());
        c.setContentHash(md5(d.content()));
        c.setCreatedAt(now);
        return c;
    }

    /* ==================== 预览 ==================== */

    /** 只切分不入库（管理端「切分预览」用；也是调参时最省事的入口） */
    public List<TextSplitter.Draft> preview(String text, TextSplitter.Options opts) {
        if (text == null || text.isBlank()) {
            throw new BizException(400, "内容不能为空");
        }
        return TextSplitter.split(text, opts);
    }

    /* ==================== 内部工具 ==================== */

    private KbDocument requireDoc(long docId) {
        KbDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(404, "文档不存在或已删除");
        }
        return doc;
    }

    /**
     * 从 PARENT 块按 {@code ordinal} 升序拼回原文。
     *
     * <p>必须按 {@code ordinal} 而不是 {@code id}：重建是「先删后插」，
     * 自增 id 在删除后不复用，第二次重建时 id 的顺序就不再等于原文顺序。
     * DDL 里为 ordinal 单独写了注释，说的就是这件事。
     */
    private String reconstructText(long docId) {
        List<KbChunk> parents = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocId, docId)
                .eq(KbChunk::getChunkType, TextSplitter.TYPE_PARENT)
                .orderByAsc(KbChunk::getOrdinal));
        StringBuilder sb = new StringBuilder();
        for (KbChunk c : parents) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(c.getContent());
        }
        return sb.toString();
    }

    private IndexReport markFailure(KbDocument doc, String msg) {
        markStatus(doc.getId(), ST_FAILED, truncate(msg, 512), null, null);
        log.warn("[KB] 索引失败 docId={}：{}", doc.getId(), msg);
        return new IndexReport(doc.getId(), ST_FAILED, 0, 0, 0, 0, false, msg);
    }

    /**
     * 状态与统计的一次性更新。
     *
     * <p>用 {@link LambdaUpdateWrapper} 而不是 {@code updateById}：后者默认忽略 null 字段
     * （MyBatis-Plus 的 field-strategy），于是「索引成功后清掉上次的 error_msg」这一步
     * 会静默失效 —— 界面表现为「文档已就绪，但错误原因那一栏还挂着上次的报错」。
     */
    private void markStatus(long docId, String status, String errorMsg, Integer chunkCount, String embedModel) {
        LambdaUpdateWrapper<KbDocument> w = new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, docId)
                .set(KbDocument::getStatus, status)
                .set(KbDocument::getUpdatedAt, LocalDateTime.now())
                // 显式写 null / 写值，两个方向都覆盖到
                .set(KbDocument::getErrorMsg, errorMsg);
        if (chunkCount != null) {
            w.set(KbDocument::getChunkCount, chunkCount);
        }
        if (ST_READY.equals(status)) {
            w.set(KbDocument::getEmbedModel, embeddingProps.getModel());
            w.set(KbDocument::getDim, embeddingProps.getDim());
        }
        documentMapper.update(null, w);
    }

    private static String normalizeSourceType(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        return switch (v) {
            case "AUTHORITATIVE", "UPLOAD", "SYNTHETIC" -> v;
            default -> "UPLOAD";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** 正文 MD5（32 位小写），供 MySQL ↔ Milvus 校验与重复导入去重 */
    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 是 JDK 必带算法，走不到这里；真走到了也不该让索引失败
            return null;
        }
    }
}
