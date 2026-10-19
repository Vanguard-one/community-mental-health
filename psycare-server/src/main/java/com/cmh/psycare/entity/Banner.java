package com.cmh.psycare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 首页轮播 banner（面向全体访客的运营物料）。
 *
 * <h3>为什么不复用 {@link Notification}</h3>
 * <p>{@code notification} 是按 {@code user_id} 一对一推送、带 {@code read_flag}
 * 已读标记的站内信；banner 面向<b>全体访客</b>，还需要图片、跳转链接、排序、
 * 启停和时间窗口。两者只有「有个标题」这一处重合 —— 硬合并会得到一张
 * 「一半列永远为 NULL」的表，而且两边的查询条件会互相污染。所以独立建表
 * （见 {@code sql/v9_banner_ddl.sql} 的完整论证）。{@code notification} 表
 * <b>保留不动</b>，{@code /notifications} 页仍在用它，只是首页右下角不再读它。
 *
 * <h3>关于 startAt / endAt 可为 null</h3>
 * <p>两者是<b>开放区间</b>：{@code startAt=null} 表示立即生效，
 * {@code endAt=null} 表示永不过期，两者都为 null 就是「永久展示」（默认状态）。
 * 这样管理端的「展示时间」是纯可选项 —— 若给 {@code endAt} 设 NOT NULL +
 * 默认「当前时间」，新建的 banner 会因为结束时间早于当前时刻而<b>立刻不可见</b>，
 * 表现为「保存成功了但首页没有」，极难排查。
 *
 * <h3>逻辑删除</h3>
 * <p>{@code isDeleted} 由全局配置接管（{@code logic-delete-field: isDeleted}），
 * 所以 {@code deleteById} 实际执行 {@code UPDATE ... SET is_deleted=1}，
 * 而不是物理删除 —— 与 {@link KbDocument} 一致。
 */
@Data
@TableName("banner")
public class Banner {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题：管理端列表展示，同时作为图片的 alt 文案（图片加载失败时用户看到的就是它） */
    private String title;

    /**
     * 图片地址。本地上传后由后端返回相对路径 {@code /api/uploads/banner/xxx.png}，
     * 前端直接拼接请求 baseURL 使用。
     *
     * <p>存相对路径而不是绝对 URL，是为了「换域名不用改库」：本地开发的
     * {@code http://localhost:5173} 与生产的域名不同，绝对 URL 落库后迁移必然全废。
     */
    private String imageUrl;

    /**
     * 点击跳转链接。支持站内相对路径（{@code /scale-list}）或完整 URL；
     * <b>为空表示不可点击</b>（纯展示图），前端据此决定是否包一层可点元素。
     */
    private String linkUrl;

    /** 排序，值小的在前（升序）。管理端「上移/下移」交换相邻两行的 sort 值 */
    private Integer sort;

    /** 1 启用 / 0 停用。停用后首页不展示，但数据保留（可再启用） */
    private Integer status;

    /** 展示开始时间；null = 立即生效 */
    private LocalDateTime startAt;

    /** 展示结束时间；null = 永不过期 */
    private LocalDateTime endAt;

    private Integer isDeleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
