package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 量表题目选项
 */
@Data
@TableName("scale_option")
public class ScaleOption {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    /** 选项文本 */
    private String content;

    /** 选项分值 */
    private Integer score;

    /** 排序 */
    private Integer sort;
}
