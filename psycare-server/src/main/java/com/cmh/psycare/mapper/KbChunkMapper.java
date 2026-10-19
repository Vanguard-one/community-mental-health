package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 按文档物理删除全部分块（重建索引的第一步）。
     *
     * <h3>为什么这里<b>故意</b>不加 {@code is_deleted = 0}</h3>
     * <p>项目约定「自定义 MyBatis SQL 必须手写 {@code is_deleted = 0}」，是因为
     * 自定义 SQL 不走 {@code @TableLogic}，漏写会让逻辑删除失效。这里正相反：
     * 这条语句的目的就是<b>把该文档的分块彻底清掉</b>，包括历史上被软删的行 ——
     * 加上 {@code is_deleted = 0} 反而会留下一批永远不会被读到的垃圾行，
     * 并且每次重建都多积一批。
     *
     * <p>换句话说，分块表压根不该有逻辑删除语义（DDL 注释：「分块不可变，
     * 内容变了就是一次重建」）。{@code is_deleted} 列保留只是为了与全库约定一致。
     */
    @Delete("DELETE FROM kb_chunk WHERE doc_id = #{docId}")
    int hardDeleteByDoc(@Param("docId") Long docId);
}
