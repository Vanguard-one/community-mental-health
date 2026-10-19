package com.cmh.psycare.dto;

import lombok.Data;

/**
 * 未读汇总 —— 两端导航红点的**唯一**数据源。
 *
 * <h3>为什么要有这个独立接口，而不是让前端自己数</h3>
 *
 * <p>在此之前，医生端侧栏的角标是前端拿会话列表 {@code filter(s => s.awaitingMyReply).length}
 * 算出来的。居民端要显示同一件事时，如果也照抄一遍，就会变成两个前端各写一份统计 ——
 * 而居民端列表的数据源（{@code /chat/doctors}）与医生端（{@code /chat/sessions}）
 * 本来就不是同一个接口，两份统计迟早会漂移成两套口径。
 *
 * <p>所以把「数多少个」收到服务端：一次 COUNT 查询、两端同一个返回结构，
 * 前端只负责渲染数字。<b>两个端在红点上的表现因此不可能不一致</b>——
 * 它们读的是同一个字段。
 *
 * <p>角标显示 {@link #sessionCount} 而不是 {@link #messageCount}，
 * 沿用医生端既有语义（数的是"有未读的会话数"，与"几段对话等着我"对齐）。
 * 条数在会话列表里按行显示，两个数字各有用途，所以都返回。
 *
 * <p>口径与 {@code ChatMapper.xml} 里的列表查询严格一致：
 * {@code consult_type = 'DOCTOR'} + {@code status = 'OPEN'} +
 * {@code sender <> 我的角色} + {@code read_at IS NULL}。
 * 其中 {@code status = 'OPEN'} 不是可选项：红点点亮却点不进去（列表里没有那一行）
 * 是最糟的提示，限定 OPEN 才能保证"亮着的一定能处理"。
 */
@Data
public class ChatUnreadVO {

    /** 有未读消息的会话数 —— 导航角标显示的数字 */
    private Integer sessionCount;

    /** 未读消息总条数 —— 会话列表文案用 */
    private Integer messageCount;
}
