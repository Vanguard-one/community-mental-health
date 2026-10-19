package com.cmh.psycare.ai.rag;

import java.util.List;

/**
 * 一次批量向量化的结果。
 *
 * <h3>为什么用下标回原位，而不是「过滤掉失败项」</h3>
 * <p>调用方（{@code KnowledgeIndexService}）拿到的是一批<b>分块正文</b>，
 * 它需要把每条向量写回对应的 {@code kb_chunk.id}。如果失败项被直接剔除，
 * 返回列表的下标就与输入不再对齐，调用方只能靠「内容比对」去猜哪条缺了 ——
 * 而分块正文里有不少是**完全相同的重复句**（如「遵医嘱用药」这类模板句），
 * 猜错的后果是把 A 块的向量挂到 B 块上，且检索时毫无异常。
 *
 * <p>所以：{@link #vectors()} 与输入<b>等长、同序</b>，失败位置为 {@code null}，
 * 失败下标单独记在 {@link #failedIndexes()} 里。
 *
 * @param vectors       与输入等长同序的向量列表；失败位置为 {@code null}
 * @param failedIndexes 永久失败（重试耗尽 + 逐条降级后仍失败）的下标，升序
 * @param promptTokens  本次累计消耗的输入 token（多个批次之和），供成本记账
 */
public record EmbeddingBatch(List<float[]> vectors, List<Integer> failedIndexes, long promptTokens) {

    public static EmbeddingBatch empty() {
        return new EmbeddingBatch(List.of(), List.of(), 0L);
    }

    public boolean hasFailure() {
        return !failedIndexes.isEmpty();
    }

    public int size() {
        return vectors.size();
    }
}
