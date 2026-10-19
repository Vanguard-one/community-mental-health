package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 打开会话请求。
 * <p>
 * 只有「跟谁聊」这一个入参：会话的另一方由后端按当前登录角色补全
 * （医生自己就是 doctor_id，居民自己就是 resident_id），
 * 前端传不了也改不了。
 */
@Data
public class ChatOpenRequest {

    /** 医生端传 residentId，居民端传 doctorId */
    @NotNull(message = "缺少聊天对象")
    private Long targetId;
}
