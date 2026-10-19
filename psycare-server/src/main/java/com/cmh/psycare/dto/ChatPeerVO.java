package com.cmh.psycare.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 居民视角的「可聊天医生」列表项。
 *
 * <p>与 {@link ResidentInfoVO}（医生看居民）互为镜像：居民端「我的消息」页
 * 需要先看到有哪些医生可以聊，再决定点谁。数据源是 {@code sys_user + doctor}，
 * 只取已通过资质审核且账号启用者 —— 未审核/驳回的医生不该出现在居民的可选列表里。
 *
 * <h3>为什么姓名不脱敏</h3>
 * 与 {@code ChatService} 的约定一致：医生是服务提供者，居民有权知道给自己
 * 看病的是谁。且推荐页（/recommend/doctors）本来就展示医生真实姓名，
 * 两处若不一致反而自相矛盾。反方向的「医生看居民」才需要脱敏。
 */
@Data
public class ChatPeerVO {

    /** 医生对应的 sys_user.id —— 发起会话时作为 targetId 传回 */
    private Long doctorId;

    /** 医生姓名；未填时前端退回显示 doctorUsername */
    private String doctorName;

    private String doctorUsername;

    /** 科室，如「精神科」 */
    private String department;

    /** 职称，如「主任医师」 */
    private String title;

    /** 专长标签，逗号分隔 */
    private String expertiseTags;

    private Double rating;

    /** 当前接诊量 */
    private Integer currentLoad;

    /** 与该医生的进行中会话 id；从未聊过则为 null */
    private Long sessionId;

    /** 最近一条消息预览（超长已截断）；没有会话或无消息为 null */
    private String lastMessage;

    private LocalDateTime lastMessageAt;

    private Integer messageCount;

    /**
     * 该医生发来的、我尚未读的消息条数（0 = 无未读）。
     *
     * <p>与 {@code ChatSessionVO.unreadCount} 是同一件事的居民视角版本：
     * 两端列表页数据源不同（医生端走 /chat/sessions，居民端走 /chat/doctors），
     * 但未读的判定条件必须一模一样 —— 一句话：
     * <b>对端发的、read_at 为空的条数</b>。落在两处 SQL 里，口径由本注释锁定。
     *
     * <p>它同时驱动居民端「我的消息」列表的未读角标与导航红点，
     * 所以不得在这里做任何"只算最近一条"之类的简化。
     */
    private Integer unreadCount;
}
