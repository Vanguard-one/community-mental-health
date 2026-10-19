package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.entity.Assessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AssessmentMapper extends BaseMapper<Assessment> {

    /** 查某居民在时间窗口内的测评（用于趋势预警），按时间倒序 */
    @Select("SELECT * FROM assessment WHERE resident_id = #{residentId} " +
            "AND created_at >= #{since} ORDER BY created_at DESC")
    List<Assessment> listRecent(@Param("residentId") Long residentId,
                                @Param("since") LocalDateTime since);
}
