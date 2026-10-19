package com.cmh.psycare.controller;

import com.cmh.psycare.ai.rag.DocumentParser;
import com.cmh.psycare.ai.rag.MilvusVectorStore;
import com.cmh.psycare.ai.rag.RetrievalPipeline;
import com.cmh.psycare.ai.rag.RetrievalProperties;
import com.cmh.psycare.ai.rag.RetrievedChunk;
import com.cmh.psycare.ai.rag.TextSplitter;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.KbChunkVO;
import com.cmh.psycare.dto.KnowledgeIndexReportVO;
import com.cmh.psycare.dto.KnowledgeOverviewVO;
import com.cmh.psycare.dto.KnowledgePreviewVO;
import com.cmh.psycare.dto.KnowledgeRequest;
import com.cmh.psycare.dto.RetrievalProbeRequest;
import com.cmh.psycare.entity.KbDocument;
import com.cmh.psycare.service.KnowledgeIndexService;
import com.cmh.psycare.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理端接口。
 *
 * <h3>为什么每个方法第一行都是 {@code requireRole("ADMIN")}</h3>
 * <p>本项目只做了<b>认证</b>（JwtAuthFilter 解析 token），没有做授权。
 * 前端把菜单藏起来只是「不给入口」，不是一个安全边界 —— 医生拿自己的 token
 * 直接 POST 这些接口，就能重建索引、删文档。索引是全局资源，改动会影响所有居民
 * 拿到的回答，所以这条断言必须写在后端（硬约定 #18）。
 *
 * <h3>为什么没有「drop collection」这类接口</h3>
 * <p>Phase 3 验收第 3 条要验「Milvus 被删后能重建」，但那件事应该由人在命令行做
 * （`drop` 是破坏性且不可逆的）。把它做成一个按钮，收益是省一次命令行操作，
 * 代价是某天有人误点，全库向量没了。重建本身（{@code /rebuild}）是安全的，因为它
 * 只依赖 MySQL，所以它给接口。
 */
@Slf4j
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeIndexService indexService;
    private final MilvusVectorStore vectorStore;
    /** 检索试跑用。只读依赖 —— 本控制器不会通过它改任何状态 */
    private final RetrievalPipeline retrievalPipeline;
    /** 取当前阈值用于回显（真正生效值见响应体的 minDenseScore） */
    private final RetrievalProperties retrievalProperties;

    /* ==================== 查询 ==================== */

    @GetMapping("/overview")
    public Result<KnowledgeOverviewVO> overview() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(knowledgeService.overview());
    }

    @GetMapping("/documents")
    public Result<PageResult<KbDocument>> documents(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String status) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(knowledgeService.pageDocuments(page, size, keyword, status));
    }

    @GetMapping("/documents/{id}")
    public Result<KbDocument> document(@PathVariable Long id) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(knowledgeService.detail(id));
    }

    @GetMapping("/documents/{id}/chunks")
    public Result<List<KbChunkVO>> chunks(@PathVariable Long id,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(defaultValue = "50") int limit) {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(knowledgeService.listChunks(id, type, limit));
    }

    /* ==================== 切分预览 ==================== */

    /**
     * 只切分、不入库、不调模型（**零成本**）。
     * 调参时应该先在这里看边界，满意了再建档 —— 否则每次试参数都要真的向量化一遍。
     */
    @PostMapping("/preview")
    public Result<KnowledgePreviewVO> preview(@RequestBody KnowledgeRequest req) {
        SecurityUtil.requireRole("ADMIN");
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        TextSplitter.Options opts = toOptions(req);
        String raw = req.getContent();
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "内容不能为空");
        }
        // 预览也走 parse：这样「上传 JSONL 看到的是分块后的效果」与「导入后实际入库的效果」
        // 是同一个流程，不会出现「预览挺好、导进去不一样」。
        List<DocumentParser.ParsedDocument> docs = DocumentParser.parse(req.getFileName(), raw).documents();
        if (docs.isEmpty()) {
            throw new BizException(400, "解析结果为空，请检查内容与文件名");
        }
        List<TextSplitter.Draft> drafts = docs.size() == 1
                ? TextSplitter.split(docs.get(0).text(), opts)
                // 多篇（JSONL）时按篇分别切分再合并展示，并在章节里带上标题以免混淆
                : docs.stream().flatMap(d -> TextSplitter.split(d.text(), opts).stream()).toList();

        KnowledgePreviewVO vo = new KnowledgePreviewVO();
        vo.setNormalizedChars(TextSplitter.normalize(raw).length());
        vo.setParentCount((int) drafts.stream().filter(d -> TextSplitter.TYPE_PARENT.equals(d.type())).count());
        vo.setChildCount((int) drafts.stream().filter(d -> TextSplitter.TYPE_CHILD.equals(d.type())).count());
        vo.setTotalChars(drafts.stream().filter(d -> TextSplitter.TYPE_CHILD.equals(d.type()))
                .mapToInt(TextSplitter.Draft::charCount).sum());
        vo.setTotalTokens(drafts.stream().filter(d -> TextSplitter.TYPE_CHILD.equals(d.type()))
                .mapToInt(TextSplitter.Draft::tokenCount).sum());
        vo.setSamples(drafts.stream().limit(12).map(d -> {
            KnowledgePreviewVO.ChunkSample s = new KnowledgePreviewVO.ChunkSample();
            s.setType(d.type());
            s.setOrdinal(d.ordinal());
            s.setParentOrdinal(d.parentOrdinal());
            s.setSection(d.section());
            s.setCharCount(d.charCount());
            s.setTokenCount(d.tokenCount());
            String c = d.content();
            s.setPreview(c.length() <= 120 ? c : c.substring(0, 120) + "…");
            return s;
        }).toList());
        return Result.ok(vo);
    }

    /* ==================== 写入 ==================== */

    /** 建档 + 建索引（一篇） */
    @PostMapping("/documents")
    public Result<KnowledgeIndexReportVO> create(@RequestBody KnowledgeRequest req) {
        SecurityUtil.requireRole("ADMIN");
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        TextSplitter.Options opts = toOptions(req);
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(400, "请填写文档标题");
        }
        List<DocumentParser.ParsedDocument> docs = DocumentParser.parse(req.getFileName(), req.getContent()).documents();
        if (docs.isEmpty()) {
            throw new BizException(400, "解析结果为空，请检查内容与文件名");
        }
        if (docs.size() > 1) {
            throw new BizException(400, "该文件会解析出 " + docs.size()
                    + " 篇文档，请改用「批量导入」接口（每篇按语料里的 doc_title 命名）");
        }
        return Result.ok(toVO(indexService.createAndIndex(
                req.getTitle(), req.getSourceType(), req.getPublishYear(),
                req.getFileName(), docs.get(0).text(), opts)));
    }

    /**
     * 批量导入（JSONL 合成语料）。
     *
     * <p>逐篇上报结果、逐篇独立 —— 一篇失败不影响其余，因为语料里几百条里出现
     * 一两条格式问题时，正确的做法是「把其余的导进去，并告诉人哪几条有问题」。
     */
    @PostMapping("/documents/import")
    public Result<KnowledgeIndexReportVO> importCorpus(@RequestBody KnowledgeRequest req) {
        SecurityUtil.requireRole("ADMIN");
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        TextSplitter.Options opts = toOptions(req);
        DocumentParser.ParseResult pr = DocumentParser.parse(req.getFileName(), req.getContent());
        if (pr.documents().isEmpty()) {
            throw new BizException(400, "没有解析出任何文档："
                    + (pr.warnings().isEmpty() ? "内容为空" : String.join("；", pr.warnings())));
        }
        List<KnowledgeIndexService.IndexReport> reports = indexService.importParsed(pr.documents(), opts);

        KnowledgeIndexReportVO root = new KnowledgeIndexReportVO();
        int ok = (int) reports.stream().filter(r -> "READY".equals(r.status())).count();
        root.setStatus(ok == reports.size() ? "READY" : "FAILED");
        root.setChildCount(reports.stream().mapToInt(KnowledgeIndexService.IndexReport::childCount).sum());
        root.setParentCount(reports.stream().mapToInt(KnowledgeIndexService.IndexReport::parentCount).sum());
        root.setMilvusRows(reports.stream().mapToInt(KnowledgeIndexService.IndexReport::milvusRows).sum());
        root.setDetails(reports.stream().map(this::toVO).toList());
        StringBuilder msg = new StringBuilder("成功 " + ok + " / " + reports.size() + " 篇");
        if (pr.skippedLines() > 0) {
            msg.append("；跳过 ").append(pr.skippedLines()).append(" 行非法数据");
        }
        if (!pr.warnings().isEmpty()) {
            msg.append("；").append(String.join("；", pr.warnings()));
        }
        root.setMessage(msg.toString());
        return Result.ok(root);
    }

    /** 重建索引（也用于 Milvus 被删后的恢复：只依赖 MySQL 的分块） */
    @PostMapping("/documents/{id}/rebuild")
    public Result<KnowledgeIndexReportVO> rebuild(@PathVariable Long id,
                                                  @RequestBody(required = false) KnowledgeRequest req) {
        SecurityUtil.requireRole("ADMIN");
        // 让下一次建连重新尝试：用户点「重建」往往正是因为他刚把 Milvus 起起来
        vectorStore.invalidate();
        return Result.ok(toVO(indexService.rebuild(id, toOptions(req))));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SecurityUtil.requireRole("ADMIN");
        indexService.delete(id);
        return Result.ok();
    }

    /* ==================== 诊断 ==================== */

    /**
     * Milvus 连通性探测（不写数据、不花 token）。
     *
     * <p>单独给接口而不是靠「概览」顺带：概览在 Milvus 挂时会返回 {@code -1}，
     * 但不会说<b>为什么</b>挂。下过命令的人都知道，「连不上」和「连上了但集合维度不对」
     * 是两种完全不同的处理。
     */
    @PostMapping("/milvus/probe")
    public Result<Map<String, Object>> probeMilvus() {
        SecurityUtil.requireRole("ADMIN");
        vectorStore.invalidate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", vectorStore.isEnabled());
        m.put("state", vectorStore.describeState());
        m.put("ok", vectorStore.ping());
        m.put("rows", vectorStore.countAll());
        m.put("dim", vectorStore.dim());
        return Result.ok(m);
    }

    /**
     * 检索试跑：走一遍真实检索管线，把「会被注入提示词的资料」原样吐出来。
     *
     * <h3>为什么需要这个端点</h3>
     * <p>它是两件事的共用底座：
     * <ol>
     *   <li><b>阈值校准</b>（{@code eval_retrieval.py}）—— 扫 0.3/0.4/0.5/0.6/0.7 看
     *       Recall@5 与拒答率怎么变，把阈值定在有分离点的那一档，而不是凭感觉给 0.5；</li>
     *   <li><b>人工排查</b> —— 用户问「为什么 AI 没答上来」时，第一步就是拿那句话打这个接口：
     *       返回空 → 检索层没召回到（阈值/索引/语料问题）；返回非空但 AI 没答 → 问题在提示词或模型，
     *       与检索无关。这一个接口就能把责任面切开。</li>
     * </ol>
     *
     * <h3>为什么也走 requireRole("ADMIN")</h3>
     * <p>它会消耗 embedding 额度（Redis 未命中时），且能反查语料内容。
     * 与「管理端接口一律断言 ADMIN」保持一致（硬约定 #18）。
     *
     * <h3>为什么不落库、不记审计</h3>
     * <p>它只读；压测与扫描会产生几百次调用，写审计表会把它变成写入路径，
     * 反而拖慢校准。日志里已有 {@code [RAG]} 维度的 Warn/Info 可追。
     */
    @PostMapping("/retrieve")
    public Result<Map<String, Object>> retrieve(@RequestBody RetrievalProbeRequest req) {
        SecurityUtil.requireRole("ADMIN");
        String q = req == null ? null : req.getQuery();
        if (q == null || q.isBlank()) {
            throw new BizException(400, "query 不能为空");
        }
        Float override = req.getMinDenseScore();
        if (override != null && (override < -1f || override > 1f)) {
            // 余弦相似度理论范围 [-1,1]；越界说明调用方算错了，直接挡掉比让它静默失效好
            throw new BizException(400, "minDenseScore 应在 [-1, 1] 区间内");
        }
        boolean withContent = req.getWithContent() == null || req.getWithContent();

        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> hits = retrievalPipeline.retrieve(q, override);
        long cost = System.currentTimeMillis() - t0;

        float effective = override != null ? override : retrievalProperties.getMinDenseScore();

        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (RetrievedChunk c : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", c.chunkId());
            m.put("docId", c.docId());
            m.put("parentId", c.parentId());
            m.put("docTitle", c.docTitle());
            m.put("section", c.section());
            // 融合分与稠密分都给出：评测看 denseScore（阈值比的是它），
            // 但融合分能解释「为什么这条排在前面」，排障时很有用
            m.put("score", c.score());
            m.put("denseScore", c.denseScore());
            m.put("synthetic", c.synthetic());
            m.put("contentLength", c.content() == null ? 0 : c.content().length());
            if (withContent) {
                m.put("content", c.content());
            }
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("minDenseScore", effective);
        out.put("hitCount", items.size());
        out.put("costMs", cost);
        out.put("hits", items);
        return Result.ok(out);
    }

    /**
     * 当前检索配置快照（不含索引层参数）。
     *
     * <p>与 {@link #probeMilvus()} 分工：那个回答「Milvus 通不通、里面有多少行」，
     * 这个回答「检索时会怎么筛、注多少」。排障时两个都要看，但关心的层次不同。
     */
    @GetMapping("/retrieval/config")
    public Result<Map<String, Object>> retrievalConfig() {
        SecurityUtil.requireRole("ADMIN");
        return Result.ok(retrievalPipeline.describe());
    }

    /* ==================== 工具 ==================== */

    private TextSplitter.Options toOptions(KnowledgeRequest req) {
        TextSplitter.Options dft = TextSplitter.Options.defaults();
        if (req == null) {
            return dft;
        }
        int child = req.getChildSize() == null ? dft.childSize() : req.getChildSize();
        int parent = req.getParentSize() == null ? dft.parentSize() : req.getParentSize();
        int overlap = req.getOverlap() == null ? dft.childOverlap() : req.getOverlap();
        try {
            return new TextSplitter.Options(child, overlap, parent, Math.max(60, child / 3));
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "切分参数不合法：" + e.getMessage());
        }
    }

    private KnowledgeIndexReportVO toVO(KnowledgeIndexService.IndexReport r) {
        KnowledgeIndexReportVO v = new KnowledgeIndexReportVO();
        v.setDocId(r.docId());
        v.setStatus(r.status());
        v.setParentCount(r.parentCount());
        v.setChildCount(r.childCount());
        v.setMilvusRows(r.milvusRows());
        v.setFailedVectors(r.failedVectors());
        v.setMilvusOk(r.milvusOk());
        v.setMessage(r.message());
        return v;
    }
}
