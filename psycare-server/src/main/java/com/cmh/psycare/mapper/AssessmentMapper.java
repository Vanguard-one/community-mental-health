package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.Assessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AssessmentMapper extends BaseMapper<Assessment> {

    /** 查某居民在时间窗口内的测评（用于趋势预警），按时间倒序 */
    @Select("SELECT * FROM assessment WHERE resident_id = #{residentId} " +
            "AND created_at >= #{since} ORDER BY created_at DESC")
    List<Assessment> listRecent(@Param("residentId") Long residentId,
                                @Param("since") LocalDateTime since);

    /**
     * 各量表已产生的测评次数（管理端筛查表列表用）。
     *
     * <p>一次 {@code GROUP BY} 取全表，而不是对每个量表循环 {@code selectCount} ——
     * 量表数量虽然只有 4 个，但"列表页里写循环 count"是个会随数据长大而恶化的写法，
     * 一旦将来量表分类变多、或这页改成带分页，它就是 N+1 的起点。
     *
     * <p>返回 Map 的 key 取列别名（{@code scaleId} / {@code cnt}），
     * 值是 JDBC 原始的 {@code Long}（MySQL 的 {@code COUNT(*)} 返回 BIGINT）。
     * 调用方必须按 {@code Number} 取值再转 —— 直接强转 {@code Long} 在
     * 换了驱动或走代理层（返回 BigInteger）时会 ClassCastException。
     */
    @Select("SELECT scale_id AS scaleId, COUNT(*) AS cnt FROM assessment GROUP BY scale_id")
    List<Map<String, Object>> countByScale();
}
