package com.cmh.psycare.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话列表项（医生端与居民端共用）。
 * <p>
 * 两端视角不同，字段按当前登录角色选择性填充：
 * 医生看到的是「居民是谁」，居民看到的是「医生是谁」——
 * 由 Service 层判定当前角色后决定把哪一侧的姓名写进 {@code peerName}，
 * 前端不必做角色分支。
 */
@Data
public class ChatSessionVO {

    /** consult_session.id */
    private Long id;

    private Long residentId;

    private Long doctorId;

    /*
     * 下面四个字段是 SQL 填充的**原文姓名/登录名**，仅用于 Service 层组装
     * peerName（脱敏后下发）。加 @JsonIgnore 是防呆：万一哪天 Service 忘了
     * 脱敏或改了字段名，未脱敏的姓名也不会被序列化出去，而是直接消失。
     */
    @JsonIgnore
    private String residentName;

    @JsonIgnore
    private String doctorName;

    @JsonIgnore
    private String residentUsername;

    @JsonIgnore
    private String doctorUsername;

    /** 医生职称，居民视角作为对端补充信息展示（非敏感，可下发） */
    private String doctorTitle;

    /*
     * 医生执业资料原文（科室 / 擅长 / 评分）。
     *
     * 与上面四个姓名列同一约定：SQL 出原文、@JsonIgnore 挡住，下发的只有
     * Service 按视角组装好的 peer* 字段。这样「医生视角不该看到自己的执业信息」
     * 这件事由一个 setter 决定，而不是靠前端自觉不渲染。
     */
    @JsonIgnore
    private String doctorDepartment;

    @JsonIgnore
    private String doctorExpertise;

    @JsonIgnore
    private Double doctorRating;

    /**
     * 对话对端的展示名（已脱敏）。
     * <p>
     * 医生视角 = 居民姓名；居民视角 = 医生姓名（缺姓名时退回职称/用户名）。
     */
    private String peerName;

    /** 对端补充信息：医生视角为空；居民视角为医生职称，如「主治医师」 */
    private String peerDesc;

    /**
     * 对端科室：医生视角为空；居民视角为医生所在科室。
     *
     * <p>聊天页顶部要交代「我在和谁说话」，光有姓名+职称不够 ——
     * 患者需要知道这位医生是哪一科的。三个 peer* 字段一起构成聊天的标题栏，
     * 因此跟着 {@link #peerName} 走同一个视角分支，不单独判断。
     */
    private String peerDepartment;

    /** 对端专长标签（逗号分隔）：医生视角为空；居民视角为医生擅长方向 */
    private String peerExpertise;

    /** 对端评分：医生视角为空；居民视角为医生历史评分 */
    private Double peerRating;

    /** OPEN / CLOSED */
    private String status;

    private LocalDateTime createdAt;

    /** 最近一条消息内容（超长截断），无消息为 null */
    private String lastMessage;

    private LocalDateTime lastMessageAt;

    private Integer messageCount;

    /** 最近一条消息的发送者：RESIDENT / DOCTOR / AI；无消息为 null */
    private String lastSender;

    /**
     * 对端发来的、我尚未读的消息条数（0 = 无未读）。
     *
     * <p>由 SQL 按当前视角算好（{@code m.sender <> 我的角色 AND m.read_at IS NULL}），
     * 前端不做角色分支 —— 与 peer* 系列同一约定。
     *
     * <p><b>它是红点与「未读」视觉的唯一判据</b>：导航角标数是
     * 「{@code unreadCount > 0} 的会话数」，列表行的加粗与角标也看它。
     * {@link #awaitingMyReply} 退为「待回复」文案的判据，不再驱动红点。
     */
    private Integer unreadCount;

    /**
     * 最近一条消息是不是对端发的 —— 即「欠对方一条回复」。
     *
     * <p><b>与 {@link #unreadCount} 是两件事，不要合并：</b>
     * 未读回答「我看过没有」，待回复回答「我回了没有」。读过而没回仍然是待回复
     * （而且正该继续提醒），所以两者各自独立成立，任何一方都不能推导出另一方。
     * UI 上也不混用：<b>红点 / 加粗归未读，文字标签「待回复」归本字段</b>——
     * 这样「点开看过了」能让红点灭掉，而「还没回」的提醒不会跟着一起消失。
     *
     * <p>两端共用一个字段，由 Service 按当前角色算好，前端不做角色分支。
     */
    private Boolean awaitingMyReply;
}
