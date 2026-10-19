package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 量表题目
 */
@Data
@TableName("scale_item")
public class ScaleItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long scaleId;

    /** 题号（1 起） */
    private Integer itemNo;

    /** 题干 */
    private String content;

    /** 是否危机项（如 PHQ-9 第9题）：1 是 0 否 */
    private Integer crisisFlag;

    /** 是否反向计分：1 是 0 否 */
    private Integer reverseScore;
}
