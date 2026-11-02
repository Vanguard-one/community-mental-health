package com.cmh.psycare.dto;

import lombok.Data;

import java.time.LocalTime;

/**
 * 一个可约时段（居民端选时间、医生端配置页共用一个 VO）。
 *
 * <p>为什么不拆成「居民端 VO + 医生端 VO」：两端看到的字段完全一样
 * （时间、上限、已约、剩余），差别只在客户端要不要显示 {@code bookedCount}。
 * 拆开会让 {@code DoctorSchedule} 两次映射逻辑并存，而它们迟早会漂移。
 *
 * <p>{@code startText / endText / timeText} 是给前端直接渲染的字符串。
 * 不把 {@code LocalTime} 直接丢给前端，是因为 Jackson 默认会输出 {@code "09:00:00"}，
 * 于是每个用到它的地方都得写一次 {@code slice(0, 5)} —— 这种「格式化」一旦散落各处，
 * 迟早有人漏掉一处，界面上就出现一行带秒的时间。格式化的归属放在服务端。
 */
@Data
public class ScheduleSlotVO {

    /** doctor_schedule.id */
    private Long id;

    private LocalTime startTime;

    private LocalTime endTime;

    /** 人数上限 */
    private int capacity;

    /** 已约人数 */
    private int bookedCount;

    /** "09:00" */
    public String getStartText() {
        return startTime == null ? "" : String.format("%02d:%02d", startTime.getHour(), startTime.getMinute());
    }

    /** "09:30" */
    public String getEndText() {
        return endTime == null ? "" : String.format("%02d:%02d", endTime.getHour(), endTime.getMinute());
    }

    /** "09:00-09:30"：列表与摘要里出现频次最高的形态 */
    public String getTimeText() {
        return getStartText() + "-" + getEndText();
    }

    /**
     * 剩余可约数。
     *
     * <p>用 {@code Math.max(0, ...)} 兜一次底：理论上 bookedCount 不会超过 capacity
     * （上限不许改到小于已约数），但库是会被手工改的，负数剩余显示成「剩 -1」比显示 0 更难解释。
     */
    public int getRemaining() {
        return Math.max(0, capacity - bookedCount);
    }

    /** 是否已约满 */
    public boolean isFull() {
        return getRemaining() <= 0;
    }
}
