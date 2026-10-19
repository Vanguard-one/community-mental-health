package com.cmh.psycare.common.enums;

import lombok.Getter;

/**
 * 心理风险等级
 */
@Getter
public enum RiskLevel {

    A("低风险"),
    B("中风险"),
    C("高风险"),
    D("危机");

    private final String desc;

    RiskLevel(String desc) {
        this.desc = desc;
    }
}
