package com.cmh.psycare.mapper;

import com.cmh.psycare.dto.AssessmentHistoryVO;
import com.cmh.psycare.dto.AssessmentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 测评做题记录查询（跨 assessment / scale / scale_item / scale_option 的读模型）。
 * <p>
 * 单表 CRUD 走 MyBatis-Plus 的 AssessmentMapper / AssessmentAnswerMapper，
 * 这里只放需要多表 join 的展示查询。
 */
@Mapper
public interface AssessmentRecordMapper {

    /**
     * 一次测评的逐题明细（一行 = 一道题）。
     * <p>
     * 用 LEFT JOIN：即便 assessment_answer 没有明细（早期数据只写了
     * assessment.answer_json），也仍会返回一行卷头，不会让调用方拿到空集合
     * 而误判「测评不存在」。此时 itemNo 等明细字段为 null。
     */
    List<AssessmentRow> selectAnswerRows(@Param("assessmentId") Long assessmentId);

    /** 某居民的历史测评（按时间倒序），不含逐题明细 */
    List<AssessmentHistoryVO> selectHistory(@Param("residentId") Long residentId);
}
