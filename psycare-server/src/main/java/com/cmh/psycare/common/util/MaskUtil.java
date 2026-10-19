package com.cmh.psycare.common.util;

/**
 * 展示层脱敏工具。
 * <p>
 * 本项目对「姓名 / 手机号」的约定是**存原文、展示时脱敏**
 * （见 sys_user 表注释：姓名「展示脱敏」、手机号「存储加密」）。
 * 脱敏放展示层而不是存储层，是为了保留医生在必要场景下的可追溯性。
 * <p>
 * 注意：脱敏只做「降低识别度」，不构成匿名化。真正的去标识化能力
 * 需要存储层加密 + 访问审计配合。
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /**
     * 姓名脱敏：保留姓氏，其余用 * 代替。
     * 「张三」→「张*」，「欧阳娜娜」→「欧***」。
     * 单个字符与空值原样/兜底返回，避免出现空字符串。
     */
    public static String name(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.trim();
        if (s.length() == 1) {
            return s;
        }
        return s.charAt(0) + "*".repeat(Math.min(s.length() - 1, 3));
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位。
     * 「13800000001」→「138****0001」。长度不足 7 位时整体打码。
     */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.trim();
        if (s.length() < 7) {
            return "*".repeat(s.length());
        }
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }
}
