import { del, get, post } from './request'
import type {
  KbChunkItem,
  KbDocument,
  KnowledgeIndexReport,
  KnowledgeOverview,
  KnowledgePreview,
  KnowledgeRequest,
  PageResult,
} from '@/types'

/**
 * 知识库（RAG 索引层）管理接口。**全部仅管理员可用** ——
 * 后端每个方法都独立断言 `ADMIN`，前端不出现入口只是「不给入口」，不是安全边界。
 */

/** 概览：两侧数字 + Milvus 状态（Milvus 挂了这个接口也能正常返回） */
export function getKnowledgeOverview(): Promise<KnowledgeOverview> {
  return get<KnowledgeOverview>('/knowledge/overview')
}

/** 文档分页 */
export function listKbDocuments(params: {
  page?: number
  size?: number
  keyword?: string
  status?: string
}): Promise<PageResult<KbDocument>> {
  return get<PageResult<KbDocument>>('/knowledge/documents', { ...params })
}

/** 文档详情 */
export function getKbDocument(id: number): Promise<KbDocument> {
  return get<KbDocument>(`/knowledge/documents/${id}`)
}

/** 分块列表；`type` 传 PARENT / CHILD 可只看一层 */
export function listKbChunks(id: number, type?: string, limit = 50): Promise<KbChunkItem[]> {
  return get<KbChunkItem[]>(`/knowledge/documents/${id}/chunks`, { type, limit })
}

/**
 * 切分预览：只切分，不向量化、不入库，**零成本**。
 * 调参时先看这里 —— 不然每试一组参数都要真的调一次模型。
 */
export function previewKnowledge(req: KnowledgeRequest): Promise<KnowledgePreview> {
  return post<KnowledgePreview>('/knowledge/preview', req)
}

/** 建档 + 建索引（单篇；文件若解析出多篇会被后端拒绝，要求走批量导入） */
export function createKbDocument(req: KnowledgeRequest): Promise<KnowledgeIndexReport> {
  return post<KnowledgeIndexReport>('/knowledge/documents', req)
}

/** 批量导入（JSONL 合成语料，按 doc_title 分组，逐篇独立成败） */
export function importKbCorpus(req: KnowledgeRequest): Promise<KnowledgeIndexReport> {
  return post<KnowledgeIndexReport>('/knowledge/documents/import', req)
}

/**
 * 重建索引。
 *
 * 两种场景共用：① 换了切分参数重切；② Milvus 的集合被删了要恢复。
 * 后者能成立，是因为重建**只依赖 MySQL 的分块**（父块拼回原文）。
 */
export function rebuildKbDocument(id: number, req?: KnowledgeRequest): Promise<KnowledgeIndexReport> {
  return post<KnowledgeIndexReport>(`/knowledge/documents/${id}/rebuild`, req ?? {})
}

/** 删除文档：软删文档行 + 物理删分块 + 删 Milvus 行 */
export function deleteKbDocument(id: number): Promise<void> {
  return del<void>(`/knowledge/documents/${id}`)
}

/** Milvus 连通性探测结果 */
export interface MilvusProbeResult {
  enabled: boolean
  /** 一行可读状态：`库 / 集合 / 维度 / 服务端版本`，或不可用原因 */
  state: string
  ok: boolean
  /** 集合中的向量行数；-1 = 取不到 */
  rows: number
  dim: number
}

/** Milvus 连通性探测（不写数据、不花 token）；返回 ok / state / rows / dim */
export function probeMilvus(): Promise<MilvusProbeResult> {
  return post<MilvusProbeResult>('/knowledge/milvus/probe')
}
