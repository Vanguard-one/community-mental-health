package com.cmh.psycare.dto;

import lombok.Data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 一天的号源（居民端日期条 + 医生端配置页共用）。
 *
 * <p><b>没有号源的日期也会返回</b>，{@code slots} 为空列表。
 * 这一点是刻意的：日期条要把未来 7 天连续画出来，缺了某天就会出现
 * 「10-24 跳到 10-26」这种断档，用户会以为是加载失败。
 * 空数组 = 「这天没排班」，由前端画成置灰日期；字段缺失 = 「不知道」，前端无法区分。
 *
 * <p>{@code weekday} 由服务端算：前端 {@code new Date('2026-10-25')} 会按 UTC 解析，
 * 东八区用户看到的可能是前一天，星期几跟着错一天。这种错只在部分时段出现，
 * 测试时极难复现。
 */
@Data
public class ScheduleDayVO {

    private LocalDate date;

    private List<ScheduleSlotVO> slots = new ArrayList<>();

    /** "2026-10-25" */
    public String getDateText() {
        return date == null ? "" : date.toString();
    }

    /** "10-25"：日期条上的主标签 */
    public String getShortText() {
        return date == null ? "" : String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }

    /** "周六" */
    public String getWeekday() {
        return weekdayOf(date);
    }

    public static String weekdayOf(LocalDate date) {
        if (date == null) {
            return "";
        }
        DayOfWeek dow = date.getDayOfWeek();
        switch (dow) {
            case MONDAY:
                return "周一";
            case TUESDAY:
                return "周二";
            case WEDNESDAY:
                return "周三";
            case THURSDAY:
                return "周四";
            case FRIDAY:
                return "周五";
            case SATURDAY:
                return "周六";
            default:
                return "周日";
        }
    }
}
