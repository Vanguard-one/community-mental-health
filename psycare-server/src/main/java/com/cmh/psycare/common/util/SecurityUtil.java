package com.cmh.psycare.common.util;

import com.cmh.psycare.common.exception.BizException;

/**
 * 获取当前登录用户的工具（从过滤器写入的 ThreadLocal 读取）
 */
public class SecurityUtil {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return u.getUserId();
    }

    public static String getRole() {
        LoginUser u = HOLDER.get();
        return u == null ? null : u.getRole();
    }

    /**
     * 角色断言：当前登录用户必须命中其中之一，否则抛异常。
     *
     * 说明：本项目只做了「认证」（JwtAuthFilter 解析 token），没有做「授权」，
     * 因此需要按接口自行断言角色，否则居民账号也能读到管理端统计与全域工单。
     *
     * @throws BizException 未登录 401 / 角色不符 403
     */
    public static void requireRole(String... roles) {
        String role = getRole();
        if (role == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        for (String r : roles) {
            if (r.equals(role)) {
                return;
            }
        }
        throw new BizException(403, "无权访问该资源");
    }

    public static void clear() {
        HOLDER.remove();
    }
}
