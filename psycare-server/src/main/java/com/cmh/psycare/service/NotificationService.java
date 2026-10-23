package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.entity.Notification;
import com.cmh.psycare.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信通知服务
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /**
     * 推送一条带摘要的通知。
     *
     * @param summary 列表页单行摘要；传 null 时前端回落到截 {@code content}
     */
    public void push(Long userId, String title, String summary, String content, String type) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setSummary(summary);
        n.setContent(content);
        n.setType(type);
        n.setReadFlag(0);
        n.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(n);
    }

    /**
     * 旧签名的 4 参版本：摘要留空。
     *
     * <p>保留它是为了让既有的工单预警、咨询消息等调用方<strong>一行都不用改</strong> ——
     * 那几类通知的正文本来就短，列表里截前 40 字完全够用，没必要为它们补摘要。
     */
    public void push(Long userId, String title, String content, String type) {
        push(userId, title, null, content, type);
    }

    public List<Notification> my(Long userId) {
        return notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .orderByDesc(Notification::getCreatedAt)
                        .last("limit 50"));
    }

    /**
     * 未读条数 —— 侧栏「通知」角标的数据源。
     *
     * <p>单独一条 COUNT，而不是让前端从 {@link #my} 的列表里数：
     * 列表接口带 {@code limit 50} 和正文，用来驱动一个 15 秒一次的轮询太重；
     * 更关键的是**超过 50 条未读时前端会数错**（列表只给了 50 条），
     * 而角标本来就该是「真实的待办量」。
     */
    public long unreadCount(Long userId) {
        Long n = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getReadFlag, 0));
        return n == null ? 0L : n;
    }

    /**
     * 标记已读。
     *
     * <p>{@code user_id} 条件写在同一条 UPDATE 里，而不是先查出通知再判断归属 ——
     * 「先查后改」在并发下会多出一个窗口，也多一次往返。
     * 影响 0 行说明「不是我的」或「本来就是已读」，两种情况对调用方是同一件事。
     *
     * @return true 表示本次真的从「未读」改成了「已读」
     */
    public boolean markRead(Long userId, Long id) {
        int n = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1));
        return n > 0;
    }
}
