package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问诊消息（图文会话，含 AI 会话消息，sender 区分来源）
 */
@Data
@TableName("consult_message")
public class ConsultMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 发送者：RESIDENT / DOCTOR / AI */
    private String sender;

    /** 发送者 userId（AI 为 null） */
    private Long senderId;

    private String content;

    /** 是否命中危机词（AI/居民侧检测）：1 是 0 否 */
    private Integer crisisFlag;

    /**
     * AI 回复的收尾方式，取值 DONE / INTERRUPTED / CRISIS / ERROR。
     *
     * <p>非 AI 消息（居民/医生发的）为 null —— 这个字段描述的是「模型这条回复完不完整」，
     * 对用户发言没有意义，不要为了「统一」给它填 DONE。
     *
     * <p>为什么必须与落库时机一起改：落库从 doOnComplete 改成 doFinally 后，
     * 客户端中途断开也会写入已生成的部分，不留标记就分不清「这条本来就短」
     * 与「这条被截断了」。
     */
    private String finishReason;

    /**
     * 接收方读取该消息的时间；{@code null} = 未读。
     *
     * <p>语义要点：消息只属于一个发送者，所以这一列天然表示「<b>对端</b>读它的时刻」，
     * 一句话就能表达双向已读，不需要 resident_read_at / doctor_read_at 两列
     * （那是会话级已读位的写法，代价是同一秒内到达的消息分不清先后）。
     *
     * <p>三种 sender 共用它，但未读统计只走 {@code consult_type = 'DOCTOR'} 的会话
     * （见 {@code ChatMapper.xml}）—— AI 问诊没有红点需求。
     *
     * <p>前端据此做两件事：自己发出的消息显示「已读 / 未读」；
     * 收到的消息里 {@code readAt} 为空即计入未读角标。
     */
    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
