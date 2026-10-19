package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 测评单题作答明细
 */
@Data
@TableName("assessment_answer")
public class AssessmentAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assessmentId;

    private Long itemId;

    private Integer itemNo;

    /** 本题得分（反向计分后） */
    private Integer score;

    /** 选中的 option_id */
    private Long optionId;
}
