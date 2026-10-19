package com.cmh.psycare.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * banner 新增 / 编辑请求体。
 *
 * <h3>为什么新增与编辑共用一个类</h3>
 * <p>字段完全一致（编辑时 id 走路径参数），差别只在「哪些必填」。
 * 与 {@link KnowledgeRequest} 同款处理：共用一个类，必填校验在 Service 里显式做。
 *
 * <h3>为什么时间用 LocalDateTime 而不是 String</h3>
 * <p>Jackson 全局配置了 {@code date-format: yyyy-MM-dd HH:mm:ss} 与
 * {@code time-zone: Asia/Shanghai}（见 application.yml），前端 antd 的
 * {@code a-date-picker show-time} 默认就是这个格式，两端无需任何手工转换。
 */
@Data
public class BannerRequest {

    /** 标题（必填） */
    private String title;

    /** 图片地址（必填）。上传接口返回的相对路径原样回传 */
    private String imageUrl;

    /** 跳转链接；可空 = 不可点击 */
    private String linkUrl;

    /** 排序值；可空则取「当前最大值 + 1」自动排到末尾（新增时） */
    private Integer sort;

    /** 1 启用 / 0 停用；可空默认 1 */
    private Integer status;

    /** 展示开始时间；可空 = 立即生效 */
    private LocalDateTime startAt;

    /** 展示结束时间；可空 = 永不过期 */
    private LocalDateTime endAt;
}
