package com.cmh.psycare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmh.psycare.dto.AppointmentVO;
import com.cmh.psycare.entity.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约单 Mapper。
 *
 * <p>列表与详情要带医生姓名与科室，只能 join —— 医生的姓名在 {@code sys_user}、
 * 科室/职称在 {@code doctor}，预约单里只存 {@code doctor_id}。
 * 因此有两个 {@code @Select} 直接返回 VO，而不是在 Service 里 N+1 次查名字。
 *
 * <p>join {@code sys_user} 时把 {@code u.is_deleted = 0} 写在 <b>ON</b> 里而不是 WHERE：
 * 写在 WHERE 会把「医生账号已注销」的预约单一起过滤掉，居民的历史记录直接消失；
 * 写在 ON 里则只是「查不到医生名」，单子还在。硬约定 #13 说的是「自定义 SQL 别忘了
 * 逻辑删除条件」，这里条件没忘，只是位置必须放对。
 */
@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {

    /** 预约单 + 医生信息的公共投影。用常量拼接，避免两处 SQL 漂移 */
    String SELECT_VO = "SELECT a.id, a.appointment_no, a.resident_id, a.doctor_id, a.schedule_id, "
            + "a.visit_date, a.start_time, a.end_time, a.status, "
            + "a.patient_name, a.patient_phone, a.chief_complaint, a.created_at, "
            + "d.department, d.title AS doctor_title, "
            + "u.real_name AS doctor_name, u.username AS doctor_username "
            + "FROM appointment a "
            + "LEFT JOIN doctor d ON d.id = a.doctor_id "
            + "LEFT JOIN sys_user u ON u.id = a.doctor_id AND u.is_deleted = 0 ";

    /** 我的预约：按就诊日期倒序，同日按开始时间倒序（最近的在最上面） */
    @Select(SELECT_VO + "WHERE a.resident_id = #{residentId} "
            + "ORDER BY a.visit_date DESC, a.start_time DESC")
    List<AppointmentVO> selectByResident(@Param("residentId") Long residentId);

    /** 单条详情（下单成功后回显用） */
    @Select(SELECT_VO + "WHERE a.id = #{id}")
    AppointmentVO selectVoById(@Param("id") Long id);

    /**
     * 医生端某时段的预约名单。
     *
     * <p>只返回 BOOKED：已取消的人不该出现在「今天要见谁」的名单里。
     * 按创建时间正序 —— 谁先约的排在前面，与号序直觉一致。
     */
    @Select(SELECT_VO + "WHERE a.schedule_id = #{scheduleId} AND a.status = 'BOOKED' "
            + "ORDER BY a.created_at ASC")
    List<AppointmentVO> selectBySchedule(@Param("scheduleId") Long scheduleId);

    /**
     * 取消：单条语句同时完成「归属校验 + 状态校验 + 状态变更」。
     *
     * <p>调用方<strong>不要</strong>先 select 判断再 update —— 那样两次取消并发时
     * 两个请求都会通过判断，各自 {@code release} 一次，号源被退了两次。
     * 影响行数才是唯一可信的结果：0 行 = 单子不存在 / 不是我的 / 已经取消过。
     *
     * @return 1 取消成功；0 表示没取消（用它给用户一个准确的提示）
     */
    @Update("UPDATE appointment SET status = 'CANCELLED', cancel_reason = #{reason}, "
            + "cancelled_at = #{now}, updated_at = #{now} "
            + "WHERE id = #{id} AND resident_id = #{residentId} AND status = 'BOOKED'")
    int cancelIfBooked(@Param("id") Long id,
                       @Param("residentId") Long residentId,
                       @Param("reason") String reason,
                       @Param("now") LocalDateTime now);
}
