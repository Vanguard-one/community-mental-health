package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AppointmentCreateRequest;
import com.cmh.psycare.dto.AppointmentVO;
import com.cmh.psycare.dto.ScheduleDayVO;
import com.cmh.psycare.entity.Appointment;
import com.cmh.psycare.entity.DoctorSchedule;
import com.cmh.psycare.mapper.AppointmentMapper;
import com.cmh.psycare.mapper.DoctorScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 预约下单 / 我的预约 / 取消。
 *
 * <h3>下单的四道闸门，顺序不能换</h3>
 * <ol>
 *   <li><b>时段存在且未开始</b> —— 快速失败，给用户一句能看懂的话；</li>
 *   <li><b>我没约过这个时段</b> —— 拦住连点两次、网络重发这类最常见的情况；</li>
 *   <li><b>CAS 扣号</b> {@code UPDATE ... WHERE booked_count < capacity} ——
 *       判断与扣减在同一条语句里，影响 0 行即已满；</li>
 *   <li><b>INSERT，撞唯一索引则整体回滚</b> —— 第 2 步与第 4 步之间仍有毫秒级窗口，
 *       这一层是数据库给的硬保证，同时会把第 3 步已经 +1 的计数一起还原。</li>
 * </ol>
 *
 * <p>第 2 步与第 3 步<strong>不能合并</strong>：第 2 步在「用户手抖点两次」时给出的是
 * 「你已预约过该时段」，而第 3 步失败给出的是「已约满」—— 两者对用户意味着
 * 完全不同的下一步动作，混在一起只会让人以为号被别人抢走了。
 *
 * <h3>为什么整个方法可以挂 @Transactional</h3>
 * 事务里只有数据库操作（时段表、预约单、站内信），没有任何外部调用 ——
 * 硬约定 #76 禁止的是「外部调用进事务」，那会让事务持有时间从毫秒变成秒级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    /** 站内信类型：预约相关。与通知页的类型标签、筛选分组一一对应 */
    private static final String TYPE_APPOINTMENT = "APPOINTMENT";

    private final AppointmentMapper appointmentMapper;
    private final DoctorScheduleMapper scheduleMapper;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------
    // 下单
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public AppointmentVO create(AppointmentCreateRequest req) {
        Long residentId = SecurityUtil.getUserId();

        // ① 时段必须存在且还没开始
        DoctorSchedule slot = scheduleMapper.selectById(req.getScheduleId());
        if (slot == null) {
            throw new BizException("该时段已不存在，请刷新后重新选择");
        }
        LocalDateTime startAt = LocalDateTime.of(slot.getWorkDate(), slot.getStartTime());
        if (!startAt.isAfter(LocalDateTime.now())) {
            throw new BizException("该时段已过，请选择其他时间");
        }

        // ② 同一时段不许有两张有效单
        Long dup = appointmentMapper.selectCount(new LambdaQueryWrapper<Appointment>()
                .eq(Appointment::getScheduleId, slot.getId())
                .eq(Appointment::getResidentId, residentId)
                .eq(Appointment::getStatus, Appointment.STATUS_BOOKED));
        if (dup != null && dup > 0) {
            throw new BizException("你已预约过该时段，请勿重复提交");
        }

        // ③ 原子占号
        if (scheduleMapper.tryBook(slot.getId()) == 0) {
            throw new BizException("该时段已约满，请换个时间");
        }

        LocalDateTime now = LocalDateTime.now();
        Appointment a = new Appointment();
        a.setAppointmentNo(nextNo(slot.getWorkDate()));
        a.setResidentId(residentId);
        a.setDoctorId(slot.getDoctorId());
        a.setScheduleId(slot.getId());
        a.setVisitDate(slot.getWorkDate());
        a.setStartTime(slot.getStartTime());
        a.setEndTime(slot.getEndTime());
        a.setStatus(Appointment.STATUS_BOOKED);
        a.setPatientName(req.getPatientName().trim());
        a.setPatientPhone(req.getPatientPhone().trim());
        a.setChiefComplaint(req.getChiefComplaint() == null || req.getChiefComplaint().isBlank()
                ? null : req.getChiefComplaint().trim());
        a.setConsentVersion(req.getConsentVersion());
        a.setConsentAt(now);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        try {
            appointmentMapper.insert(a);
        } catch (DuplicateKeyException e) {
            // 走到这里说明第 ② 步之后、INSERT 之前被同一个人的另一个请求插了单。
            // 抛出 BizException 让事务整体回滚 —— 第 ③ 步的 +1 会一并还原，
            // 不会出现「单子没建但号被占掉」。
            log.warn("预约并发重入 scheduleId={} residentId={}", slot.getId(), residentId);
            throw new BizException("你已预约过该时段，请勿重复提交");
        }

        AppointmentVO vo = appointmentMapper.selectVoById(a.getId());
        pushCreated(vo);
        return vo;
    }

    // ------------------------------------------------------------------
    // 查询与取消
    // ------------------------------------------------------------------

    /** 我的预约。排序在 SQL 里（就诊日期倒序），过期与否由 VO 的 {@code statusKey} 判定 */
    public List<AppointmentVO> my() {
        return appointmentMapper.selectByResident(SecurityUtil.getUserId());
    }

    /**
     * 取消预约。
     *
     * <p>顺序：先取回单子（为了拿到 {@code scheduleId} 好退号），再用一条
     * 「带归属 + 带状态条件」的 UPDATE 真正改状态。真正的幂等保证在 UPDATE 的
     * 影响行数上 —— 重复取消时第二次影响 0 行，不会重复退号。
     *
     * <p>开始时间已过就不允许自助取消了：那时候医生已经把这段时间留出来了，
     * 居民点一下「取消」并不能改变什么，只会让双方的信息不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, String reason) {
        Long residentId = SecurityUtil.getUserId();
        Appointment a = appointmentMapper.selectById(id);
        // 别人的单子和不存在的单子给同一句话：不泄露「这个 id 存在」
        if (a == null || !residentId.equals(a.getResidentId())) {
            throw new BizException("预约不存在");
        }
        LocalDateTime startAt = LocalDateTime.of(a.getVisitDate(), a.getStartTime());
        if (!startAt.isAfter(LocalDateTime.now())) {
            throw new BizException("就诊时段已开始，无法自助取消，请直接联系医生");
        }

        LocalDateTime now = LocalDateTime.now();
        String text = reason == null || reason.isBlank() ? null : reason.trim();
        if (appointmentMapper.cancelIfBooked(id, residentId, text, now) == 0) {
            throw new BizException("该预约已取消，无需重复操作");
        }
        scheduleMapper.release(a.getScheduleId());

        String summary = a.getPatientName() + " · " + whenText(a);
        notificationService.push(a.getDoctorId(), "预约已取消", summary,
                "患者：" + a.getPatientName() + "（" + maskPhone(a.getPatientPhone()) + "）\n"
                        + "时间：" + whenText(a) + "\n"
                        + (text == null ? "患者取消了这次预约，该号源已重新放出。" : "取消原因：" + text),
                TYPE_APPOINTMENT);
    }

    // ------------------------------------------------------------------
    // 站内信
    // ------------------------------------------------------------------

    private void pushCreated(AppointmentVO vo) {
        String when = vo.getWhenText();
        String doctor = vo.getDoctorDisplayName();
        String dept = vo.getDepartment() == null ? "" : vo.getDepartment();
        String title = vo.getDoctorTitle() == null ? "" : vo.getDoctorTitle();

        // 给居民：摘要形态「{医生名} · {MM-dd 周X} {HH:mm-HH:mm}」，列表页单行显示
        StringBuilder residentBody = new StringBuilder();
        residentBody.append("医生：").append(doctor);
        if (!dept.isBlank() || !title.isBlank()) {
            residentBody.append("（").append(dept).append(dept.isBlank() || title.isBlank() ? "" : " · ")
                    .append(title).append("）");
        }
        residentBody.append("\n时间：").append(when)
                .append("\n预约单号：").append(vo.getAppointmentNo())
                .append("\n就诊人：").append(vo.getPatientName())
                .append("\n\n请提前 5 分钟准备好，进入「我的消息」即可与医生交流。")
                .append("\n如需取消，请在「我的预约」中操作（就诊开始前可自助取消）。");
        notificationService.push(vo.getResidentId(), "预约成功",
                doctor + " · " + when, residentBody.toString(), TYPE_APPOINTMENT);

        // 给医生：摘要形态「{患者名} · {MM-dd 周X} {HH:mm-HH:mm}」
        DoctorSchedule slot = scheduleMapper.selectById(vo.getScheduleId());
        int booked = slot == null || slot.getBookedCount() == null ? 1 : slot.getBookedCount();
        int capacity = slot == null || slot.getCapacity() == null ? 0 : slot.getCapacity();
        StringBuilder doctorBody = new StringBuilder();
        doctorBody.append("患者：").append(vo.getPatientName())
                .append("（").append(maskPhone(vo.getPatientPhone())).append("）")
                .append("\n时间：").append(when)
                .append("\n主诉：").append(vo.getChiefComplaint() == null ? "未填写" : vo.getChiefComplaint())
                .append("\n该时段已约：").append(booked).append("/").append(capacity).append(" 人");
        notificationService.push(vo.getDoctorId(), "新预约",
                vo.getPatientName() + " · " + when, doctorBody.toString(), TYPE_APPOINTMENT);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 单号：A + 就诊日期 + 6 位随机数。
     *
     * <p>不用自增 id 拼单号：下单时 id 还不存在，要么插入后再 UPDATE 一次（多一条写），
     * 要么让单号在响应里迟到。随机数有 {@code uk_no} 兜底，撞了就是一次
     * 「系统异常，请稍后重试」，概率约十万分之一（日均千单时）。
     */
    private String nextNo(LocalDate visitDate) {
        return "A" + visitDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String whenText(Appointment a) {
        String shortDate = String.format("%02d-%02d",
                a.getVisitDate().getMonthValue(), a.getVisitDate().getDayOfMonth());
        String time = String.format("%02d:%02d-%02d:%02d",
                a.getStartTime().getHour(), a.getStartTime().getMinute(),
                a.getEndTime().getHour(), a.getEndTime().getMinute());
        return shortDate + " " + ScheduleDayVO.weekdayOf(a.getVisitDate()) + " " + time;
    }

    /** 手机号打码：站内信不是保险箱，摘要把完整号码露出来没有必要 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone == null ? "" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
