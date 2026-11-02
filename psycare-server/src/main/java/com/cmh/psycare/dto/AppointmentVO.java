package com.cmh.psycare.dto;

import com.cmh.psycare.entity.Appointment;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约单展示对象（我的预约列表、下单成功回显、医生端名单共用）。
 *
 * <h3>为什么「已过期」是算出来的，不是库里的一列</h3>
 * 落库的状态只有 {@code BOOKED} / {@code CANCELLED}。若再加一个 {@code EXPIRED}，
 * 就必须有一个定时任务在每一分钟把到点的单子刷一遍 —— 全项目目前一个
 * {@code @Scheduled} 都没有，为这件事引入调度器（还要考虑重启期间漏刷）
 * 纯属自找麻烦。而「过期」本质是 {@code visit_date + start_time} 与当前时刻的比较，
 * 每次读的时候算一遍就是最新答案，永远不会「忘了刷」。
 *
 * <h3>为什么状态文案放在服务端</h3>
 * 我的预约按「待就诊 / 已过期 / 已取消」分组，通知列表也要给预约类消息标状态。
 * 两处各写一遍判断，就会出现「列表说已过期、详情说待就诊」这种前后矛盾。
 * {@link #getStatusKey()} 是唯一判定口径，前端只做映射不做判断。
 */
@Data
public class AppointmentVO {

    private Long id;

    private String appointmentNo;

    private Long residentId;

    private Long doctorId;

    private Long scheduleId;

    private LocalDate visitDate;

    private LocalTime startTime;

    private LocalTime endTime;

    /** 库里的原始状态：BOOKED / CANCELLED */
    private String status;

    private String patientName;

    private String patientPhone;

    private String chiefComplaint;

    private LocalDateTime createdAt;

    // ---- join 来的医生信息（sys_user.real_name / doctor.department / doctor.title）----

    private String doctorName;

    private String doctorUsername;

    private String doctorTitle;

    private String department;

    /** "2026-10-25" */
    public String getDateText() {
        return visitDate == null ? "" : visitDate.toString();
    }

    /** "10-25" */
    public String getShortDate() {
        return visitDate == null ? ""
                : String.format("%02d-%02d", visitDate.getMonthValue(), visitDate.getDayOfMonth());
    }

    /** "周六" */
    public String getWeekday() {
        return ScheduleDayVO.weekdayOf(visitDate);
    }

    /** "09:00-09:30" */
    public String getTimeText() {
        if (startTime == null || endTime == null) {
            return "";
        }
        return String.format("%02d:%02d-%02d:%02d",
                startTime.getHour(), startTime.getMinute(),
                endTime.getHour(), endTime.getMinute());
    }

    /** "10-25 周六 09:00-09:30"：通知摘要与列表副标题的通用形态 */
    public String getWhenText() {
        return getShortDate() + " " + getWeekday() + " " + getTimeText();
    }

    /** 开始时刻是否已经过去（仅对 BOOKED 有意义） */
    public boolean isExpired() {
        if (!Appointment.STATUS_BOOKED.equals(status) || visitDate == null || startTime == null) {
            return false;
        }
        return LocalDateTime.of(visitDate, startTime).isBefore(LocalDateTime.now());
    }

    /** 能否自助取消：还没开始、且没被取消过 */
    public boolean isCancellable() {
        return Appointment.STATUS_BOOKED.equals(status) && !isExpired();
    }

    /**
     * 分组键：BOOKED（待就诊） / EXPIRED（已过期） / CANCELLED（已取消）。
     *
     * <p>注意它<strong>不是</strong>库里的字段值 —— BOOKED 的单子过了开始时间就报 EXPIRED，
     * 但库里仍然是 BOOKED（还能被医生看到「这人没来」）。
     */
    public String getStatusKey() {
        if (Appointment.STATUS_CANCELLED.equals(status)) {
            return Appointment.STATUS_CANCELLED;
        }
        return isExpired() ? "EXPIRED" : Appointment.STATUS_BOOKED;
    }

    /** 中文状态，与 {@link #getStatusKey()} 一一对应 */
    public String getStatusText() {
        switch (getStatusKey()) {
            case Appointment.STATUS_CANCELLED:
                return "已取消";
            case "EXPIRED":
                return "已过期";
            default:
                return "待就诊";
        }
    }

    /** 医生名缺失时的兜底（账号注销 / 数据不全）：不显示空白，显示账号名 */
    public String getDoctorDisplayName() {
        if (doctorName != null && !doctorName.isBlank()) {
            return doctorName;
        }
        return doctorUsername == null || doctorUsername.isBlank() ? "医生" : doctorUsername;
    }
}
