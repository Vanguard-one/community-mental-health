package com.cmh.psycare.common.enums;

/**
 * 预警类型
 */
public enum AlertType {

    /** 单次测评超过阈值 */
    THRESHOLD,
    /** 连续测评呈恶化趋势 */
    TREND,
    /** 危机项（如 PHQ-9 第9题自杀意念）阳性，最高优先级 */
    CRISIS
}
