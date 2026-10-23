package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息通知（站内信）
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    /**
     * 列表页单行摘要（超出省略）。
     *
     * <p>为什么不给前端 {@code content} 让它自己截：预约类通知的摘要形态是
     * 「{医生名} · {MM-dd 周X} {HH:mm}-{HH:mm}」，而正文里医生名和时段是分散在两行的，
     * 前端只拿得到一段连续文本，截出来是半截话，且每行长度不一 ——
     * 列表高度立刻参差，这正是通知页「乱」的来源。
     */
    private String summary;

    /** 类型：ALERT / CONSULT / APPOINTMENT / SYSTEM */
    private String type;

    /** 是否已读：1 是 0 否 */
    private Integer readFlag;

    private LocalDateTime createdAt;
}
