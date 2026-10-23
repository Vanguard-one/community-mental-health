package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.entity.Notification;
import com.cmh.psycare.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内信接口
 */
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/my")
    public Result<List<Notification>> my() {
        return Result.ok(notificationService.my(SecurityUtil.getUserId()));
    }

    /**
     * 未读条数：导航「通知」角标的数据源（三端共用）。
     *
     * <p>与 {@code /chat/unread} 并列但不合并：那边统计的是「有未读消息的**会话数**」
     * （一个会话里 5 条也只算 1），这边是「未读通知的**条数**」。
     * 合并成一个数字之后，「点开一条通知该减多少」就再也说不清了；
     * 而侧栏本来就是两个菜单项、两个角标，各对应自己的口径。
     *
     * <p>没有 {@code requireRole}：通知是按 {@code user_id} 查的，
     * 居民 / 医生 / 管理员都会收到自己的站内信，任何一个登录者都只可能查到自己那一份。
     */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(notificationService.unreadCount(SecurityUtil.getUserId()));
    }

    /**
     * 标记单条已读（通知列表「点开即已读」）。
     *
     * <p>用 POST 而不是 PUT：这里没有「整体替换资源」的语义，只有一个动作。
     * 已经是已读时返回成功（幂等），不区分「本来就读过」和「刚刚改成读过」——
     * 前端把它当「尽力而为」的调用，失败了也不该阻断弹层的打开。
     *
     * <p>归属校验在 SQL 里（{@code WHERE id = ? AND user_id = ?}），
     * 所以别人的通知 id 传进来只会影响 0 行，不会改到任何人。
     */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(SecurityUtil.getUserId(), id);
        return Result.ok();
    }
}
