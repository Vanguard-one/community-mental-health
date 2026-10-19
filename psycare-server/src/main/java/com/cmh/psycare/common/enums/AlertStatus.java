package com.cmh.psycare.common.enums;

/**
 * 预警工单状态机
 */
public enum AlertStatus {

    /** 待确认 */
    PENDING,
    /** 核实为误报 */
    REJECTED,
    /** 已确认待处理 */
    CONFIRMED,
    /** 处置中（已分派问诊/转介） */
    PROCESSING,
    /** 已回访 */
    FOLLOWED,
    /** 已关闭 */
    CLOSED
}
