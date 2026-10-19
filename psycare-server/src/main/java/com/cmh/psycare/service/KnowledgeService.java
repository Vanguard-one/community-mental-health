package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmh.psycare.ai.rag.EmbeddingProperties;
import com.cmh.psycare.ai.rag.MilvusVectorStore;
import com.cmh.psycare.ai.rag.TextSplitter;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.dto.KbChunkVO;
import com.cmh.psycare.dto.KnowledgeOverviewVO;
import com.cmh.psycare.entity.KbChunk;
import com.cmh.psycare.entity.KbDocument;
import com.cmh.psycare.mapper.KbChunkMapper;
import com.cmh.psycare.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库的<b>读</b>侧查询（列表 / 概览 / 分块）。
 *
 * <p>与 {@link KnowledgeIndexService} 分开，是因为两者的失败语义完全不同：
 * 索引会失败、需要降级、需要状态机；查询要么成功要么报错，没有任何中间态。
 * 混在一起会让「查询」也要去理解 Milvus 的降级规则。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final int PREVIEW_LEN = 120;

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final MilvusVectorStore vectorStore;
    private final EmbeddingProperties embeddingProps;

    public PageResult<KbDocument> pageDocuments(long current, long size, String keyword, String status) {
        long c = current <= 0 ? 1 : current;
        long s = size <= 0 ? 10 : Math.min(size, 100);
        LambdaQueryWrapper<KbDocument> q = new LambdaQueryWrapper<KbDocument>()
                .orderByDesc(KbDocument::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.like(KbDocument::getTitle, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            q.eq(KbDocument::getStatus, status.trim().toUpperCase());
        }
        return PageResult.of(documentMapper.selectPage(new Page<>(c, s), q));
    }

    public KbDocument detail(long docId) {
        KbDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(404, "文档不存在或已删除");
        }
        return doc;
    }

    /**
     * 某文档的分块列表（可按 PARENT / CHILD 过滤）。
     *
     * <p>按 {@code ordinal} 升序 —— 与重建时拼原文的顺序一致，
     * 这样「界面看到的顺序」与「重建还原出的原文顺序」永远相同。
     */
    public List<KbChunkVO> listChunks(long docId, String chunkType, int limit) {
        detail(docId);
        int lim = limit <= 0 ? 50 : Math.min(limit, 200);
        LambdaQueryWrapper<KbChunk> q = new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocId, docId)
                .orderByAsc(KbChunk::getOrdinal)
                .last("LIMIT " + lim);
        if (chunkType != null && !chunkType.isBlank()) {
            q.eq(KbChunk::getChunkType, chunkType.trim().toUpperCase());
        }
        return chunkMapper.selectList(q).stream().map(this::toVO).toList();
    }

    private KbChunkVO toVO(KbChunk c) {
        KbChunkVO v = new KbChunkVO();
        v.setId(c.getId());
        v.setParentId(c.getParentId());
        v.setChunkType(c.getChunkType());
        v.setOrdinal(c.getOrdinal());
        v.setSection(c.getSection());
        v.setCharCount(c.getCharCount());
        v.setTokenCount(c.getTokenCount());
        v.setContentHash(c.getContentHash());
        String content = c.getContent() == null ? "" : c.getContent();
        v.setPreview(content.length() <= PREVIEW_LEN ? content : content.substring(0, PREVIEW_LEN) + "…");
        return v;
    }

    /** 概览：MySQL 与 Milvus 两侧的数字<b>分别给</b>（见 {@link KnowledgeOverviewVO} 的说明） */
    public KnowledgeOverviewVO overview() {
        KnowledgeOverviewVO vo = new KnowledgeOverviewVO();
        vo.setDocCount(nz(documentMapper.selectCount(null)));
        vo.setReadyCount(nz(documentMapper.selectCount(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getStatus, "READY"))));
        vo.setFailedCount(nz(documentMapper.selectCount(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getStatus, "FAILED"))));
        vo.setIndexingCount(nz(documentMapper.selectCount(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getStatus, "INDEXING"))));
        vo.setChildChunkCount(nz(chunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getChunkType, TextSplitter.TYPE_CHILD))));

        vo.setMilvusEnabled(vectorStore.isEnabled());
        vo.setMilvusState(vectorStore.describeState());
        vo.setMilvusRows(vectorStore.countAll());
        vo.setMilvusOk(vo.getMilvusRows() >= 0);

        vo.setEmbedModel(embeddingProps.getModel());
        vo.setDim(embeddingProps.getDim());
        return vo;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
