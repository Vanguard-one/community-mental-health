package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.ScheduleDayVO;
import com.cmh.psycare.dto.ScheduleSaveRequest;
import com.cmh.psycare.dto.ScheduleSlotVO;
import com.cmh.psycare.dto.WeeklyScheduleRequest;
import com.cmh.psycare.dto.WeeklyScheduleResultVO;
import com.cmh.psycare.entity.DoctorSchedule;
import com.cmh.psycare.mapper.DoctorScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出诊号源：居民端按医生看号、医生端配号。
 *
 * <h3>号源模型</h3>
 * 一行 {@code doctor_schedule} = 某医生某天某时段，{@code capacity} 是上限、
 * {@code booked_count} 是已约。**不预生成** —— 一个容量 3 的时段只有一行，
 * 成交一次 +1，取消一次 -1，扣减由 {@code DoctorScheduleMapper#tryBook/release} 保证。
 *
 * <h3>时段不可变</h3>
 * {@code work_date / start_time / end_time} 建好之后不允许修改，只能删了重建；
 * 而「已有预约的时段不允许删」。这条约束是预约单能安全冗余那三列的前提
 * （见 {@code Appointment} 类注释）。唯一允许改的是 {@code capacity}。
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** 一次最多看多少天。前端只会要 7 或 14，上限挡住「传 3650 天」把库拖死 */
    private static final int MAX_DAYS = 30;

    /**
     * 一次最多铺几周。4 周 = 28 天，仍在 {@link #MAX_DAYS} 之内 ——
     * 两个上限必须同量级，否则「批量铺了 28 天」而「配置页只能看 30 天」还不算矛盾，
     * 但一旦批量超过 30 天，最后几天就再也回不到页面上（没有入口能改）。
     */
    private static final int MAX_WEEKS = 4;

    /** 默认铺 2 周：正好覆盖配置页那 14 天的日期条，生成的号源全部可见可改 */
    private static final int DEFAULT_WEEKS = 2;

    private final DoctorScheduleMapper scheduleMapper;

    /**
     * 居民端：某医生未来 N 天的号源。
     *
     * <p>没有号源的日期也会返回（{@code slots} 为空），日期条才能连续画出来；
     * 当天已经开始的时段会被过滤掉 —— 让用户选一个已经过去的时段，
     * 到提交时才报「该时段已过」是纯粹的浪费。
     */
    public List<ScheduleDayVO> doctorSchedule(Long doctorId, int days) {
        int n = clampDays(days);
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(n - 1L);
        List<DoctorSchedule> rows = scheduleMapper.selectList(
                new LambdaQueryWrapper<DoctorSchedule>()
                        .eq(DoctorSchedule::getDoctorId, doctorId)
                        .between(DoctorSchedule::getWorkDate, from, to)
                        .orderByAsc(DoctorSchedule::getWorkDate)
                        .orderByAsc(DoctorSchedule::getStartTime));
        return buildDays(from, n, rows, true);
    }

    /**
     * 医生端：我自己的号源。
     *
     * <p>与居民端有两处不同：① 只查自己（{@code doctorId} 取 JWT，不接受入参，
     * 否则任何医生都能看别人的号源）；② <b>不过滤当天已开始的时段</b> ——
     * 医生需要看到「上午那 3 个已经过去的时段约了几个人」，否则下午打开页面
     * 会以为上午的号源凭空消失了。
     */
    public List<ScheduleDayVO> mine(LocalDate from, int days) {
        Long doctorId = SecurityUtil.getUserId();
        int n = clampDays(days);
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = start.plusDays(n - 1L);
        List<DoctorSchedule> rows = scheduleMapper.selectList(
                new LambdaQueryWrapper<DoctorSchedule>()
                        .eq(DoctorSchedule::getDoctorId, doctorId)
                        .between(DoctorSchedule::getWorkDate, start, end)
                        .orderByAsc(DoctorSchedule::getWorkDate)
                        .orderByAsc(DoctorSchedule::getStartTime));
        return buildDays(start, n, rows, false);
    }

    /**
     * 保存某天的整组时段：存在则改上限，不存在则插入。<b>不删除本次未提交的旧时段</b>
     * （里面可能已经有人约了；要删走 {@link #deleteSlot}）。
     *
     * <p>逐个校验三件事：开始必须早于结束（V1 不支持跨天）、时段之间不能重叠、
     * 上限不能改到小于已约数。第三条是最容易漏的 —— 改小之后
     * 就会出现 {@code booked_count > capacity}，界面显示「剩 -1」，
     * 而且此后这个时段永远不会再有号（{@code tryBook} 的 WHERE 永远不成立）。
     */
    public List<ScheduleSlotVO> saveBatch(ScheduleSaveRequest req) {
        Long doctorId = SecurityUtil.getUserId();
        LocalDate date = req.getWorkDate();
        if (date.isBefore(LocalDate.now())) {
            throw new BizException("不能配置过去的日期");
        }

        List<ScheduleSaveRequest.Slot> slots = validateAndSort(req.getSlots());

        LocalDateTime now = LocalDateTime.now();
        List<ScheduleSlotVO> out = new ArrayList<>(slots.size());
        for (ScheduleSaveRequest.Slot s : slots) {
            LocalTime start = parseTime(s.getStartTime());
            LocalTime end = parseTime(s.getEndTime());
            DoctorSchedule exist = scheduleMapper.selectOne(
                    new LambdaQueryWrapper<DoctorSchedule>()
                            .eq(DoctorSchedule::getDoctorId, doctorId)
                            .eq(DoctorSchedule::getWorkDate, date)
                            .eq(DoctorSchedule::getStartTime, start));
            if (exist == null) {
                DoctorSchedule fresh = new DoctorSchedule();
                fresh.setDoctorId(doctorId);
                fresh.setWorkDate(date);
                fresh.setStartTime(start);
                fresh.setEndTime(end);
                fresh.setCapacity(s.getCapacity());
                fresh.setBookedCount(0);
                fresh.setCreatedAt(now);
                fresh.setUpdatedAt(now);
                scheduleMapper.insert(fresh);
                out.add(toSlotVO(fresh));
                continue;
            }
            int booked = exist.getBookedCount() == null ? 0 : exist.getBookedCount();
            if (s.getCapacity() < booked) {
                throw new BizException("时段 " + toSlotVO(exist).getTimeText() + " 已有 "
                        + booked + " 人预约，上限不能小于 " + booked);
            }
            // 只 set capacity 与 updated_at：不 new 一个实体丢给 updateById，
            // 因为那样会把内存里的 booked_count（可能是几秒前的旧值）写回去
            scheduleMapper.update(null, new LambdaUpdateWrapper<DoctorSchedule>()
                    .eq(DoctorSchedule::getId, exist.getId())
                    .set(DoctorSchedule::getCapacity, s.getCapacity())
                    .set(DoctorSchedule::getUpdatedAt, now));
            exist.setCapacity(s.getCapacity());
            out.add(toSlotVO(exist));
        }
        return out;
    }

    /**
     * 删除一个时段。只有一位都没约过才允许删。
     *
     * <p>「已有预约就删掉、单子不管」是最省事的做法，但那些居民会在就诊当天
     * 打开应用发现预约凭空消失，而医生端看不到任何异常。拒绝删除并告诉医生
     * 「已有 N 人预约」才是唯一不会伤害用户的做法。
     */
    public void deleteSlot(Long id) {
        Long doctorId = SecurityUtil.getUserId();
        DoctorSchedule s = scheduleMapper.selectById(id);
        if (s == null) {
            throw new BizException("该时段不存在或已被删除");
        }
        if (!doctorId.equals(s.getDoctorId())) {
            // 越权的唯一防线在这里：不能只靠「前端列表里没有别人的时段」
            throw new BizException(403, "无权修改他人的号源");
        }
        int booked = s.getBookedCount() == null ? 0 : s.getBookedCount();
        if (booked > 0) {
            throw new BizException("该时段已有 " + booked + " 人预约，不能删除；"
                    + "可把人数上限改为 " + booked + " 停止放号");
        }
        scheduleMapper.deleteById(id);
    }

    /**
     * 按周批量铺号源：把同一组时段模板铺到若干个周几上，连续若干个星期。
     *
     * <p>典型调用是「周一到周五、上午 3 段 + 下午 2 段、铺 2 周」，一次写入 50 行。
     * 这里不新建任何模型 —— {@code doctor_schedule} 仍然一行一个具体日期，
     * 只是把「填 10 遍同样的表格」这件事交给服务端做。
     *
     * <h3>与单日接口的三处不同</h3>
     * <ol>
     *   <li><b>过去的日期静默收敛</b>，不报错。医生昨天的页面今天才点，
     *       期望的结果是「铺未来」，而不是一条他无法处理的错误。</li>
     *   <li><b>今天已经开始的时段直接跳过</b>，不建空号。上午 10 点点「生成」时，
     *       今天 09:00 那段的 start 已经过去，建出来只会显示成「剩 3」却永远约不上
     *       （居民端 {@code doctorSchedule} 会把它过滤掉，医生端却看得见）。</li>
     *   <li><b>单段失败只跳过、不整批回滚</b>。某天某段已有 5 人预约、而模板上限是 3，
     *       单日接口会抛错让医生去改，批量接口跳过它继续铺剩下的 —— 否则一条历史数据
     *       就能让「生成两周号源」永远失败，而且报错指向的那一天他可能根本不关心。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public WeeklyScheduleResultVO weekly(WeeklyScheduleRequest req) {
        Long doctorId = SecurityUtil.getUserId();
        LocalDate today = LocalDate.now();
        LocalDate from = req.getFromDate() == null ? today : req.getFromDate();
        if (from.isBefore(today)) {
            from = today;
        }
        int weeks = req.getWeekCount() == null ? DEFAULT_WEEKS : req.getWeekCount();
        weeks = Math.max(1, Math.min(weeks, MAX_WEEKS));

        Set<Integer> weekdays = resolveWeekdays(req.getWeekdays());
        boolean overwrite = Boolean.TRUE.equals(req.getOverwriteCapacity());
        // 模板只校验一次：28 天用的是同一份模板，逐日重复做重叠检测是纯粹的浪费
        List<ScheduleSaveRequest.Slot> slots = validateAndSort(req.getSlots());

        LocalDate end = from.plusWeeks(weeks).minusDays(1);

        // 区间内已有的行一次读出来。逐条 selectOne 最多会是 240 次往返；
        // 键用 "日期|开始时间"，与 uk_doctor_date_start 的列组合同构。
        Map<String, DoctorSchedule> index = new HashMap<>();
        List<DoctorSchedule> existing = scheduleMapper.selectList(
                new LambdaQueryWrapper<DoctorSchedule>()
                        .eq(DoctorSchedule::getDoctorId, doctorId)
                        .between(DoctorSchedule::getWorkDate, from, end));
        for (DoctorSchedule s : existing) {
            index.put(slotKey(s.getWorkDate(), s.getStartTime()), s);
        }

        WeeklyScheduleResultVO out = new WeeklyScheduleResultVO();
        LocalDateTime now = LocalDateTime.now();

        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1)) {
            if (!weekdays.contains(d.getDayOfWeek().getValue())) {
                continue;
            }
            int touched = 0;
            for (ScheduleSaveRequest.Slot s : slots) {
                LocalTime start = parseTime(s.getStartTime());
                LocalTime stop = parseTime(s.getEndTime());

                if (LocalDateTime.of(d, start).isBefore(now)) {
                    out.setSkipped(out.getSkipped() + 1);
                    continue;
                }

                DoctorSchedule exist = index.get(slotKey(d, start));
                if (exist == null) {
                    DoctorSchedule fresh = new DoctorSchedule();
                    fresh.setDoctorId(doctorId);
                    fresh.setWorkDate(d);
                    fresh.setStartTime(start);
                    fresh.setEndTime(stop);
                    fresh.setCapacity(s.getCapacity());
                    fresh.setBookedCount(0);
                    fresh.setCreatedAt(now);
                    fresh.setUpdatedAt(now);
                    scheduleMapper.insert(fresh);
                    // 放进索引：模板里不会有重复的开始时间，但这么写能让后面的
                    // 「同一天同一时段被两个线程同时铺」在下一次循环里被看见
                    index.put(slotKey(d, start), fresh);
                    out.setCreated(out.getCreated() + 1);
                    touched++;
                    continue;
                }

                int booked = exist.getBookedCount() == null ? 0 : exist.getBookedCount();
                if (s.getCapacity() < booked) {
                    // 已有预约的时段不能被改成「上限小于已约数」——那会让它永远不再放号
                    out.setSkipped(out.getSkipped() + 1);
                    continue;
                }
                if (!overwrite || s.getCapacity().equals(exist.getCapacity())) {
                    out.setSkipped(out.getSkipped() + 1);
                    continue;
                }
                // 只 set 两列：不 new 实体丢给 updateById，
                // 否则内存里的 booked_count 会把别人刚 +1 的计数覆盖掉
                scheduleMapper.update(null, new LambdaUpdateWrapper<DoctorSchedule>()
                        .eq(DoctorSchedule::getId, exist.getId())
                        .set(DoctorSchedule::getCapacity, s.getCapacity())
                        .set(DoctorSchedule::getUpdatedAt, now));
                exist.setCapacity(s.getCapacity());
                out.setUpdated(out.getUpdated() + 1);
                touched++;
            }
            if (touched > 0) {
                out.getDates().add(d.toString());
            }
        }
        out.setDayCount(out.getDates().size());
        return out;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 排班索引的键：与 uk_doctor_date_start 的 (work_date, start_time) 同构 */
    private String slotKey(LocalDate date, LocalTime start) {
        return date.toString() + '|' + start.toString();
    }

    /**
     * 周几集合。为空时默认周一到周五 —— 社区门诊的现实排班就是工作日，
     * 让医生先取消勾选「周六日」比先勾选「周一~周五」省四下点击。
     */
    private Set<Integer> resolveWeekdays(List<Integer> input) {
        Set<Integer> out = new LinkedHashSet<>();
        if (input == null || input.isEmpty()) {
            for (DayOfWeek d = DayOfWeek.MONDAY; d.getValue() <= DayOfWeek.FRIDAY.getValue(); d = d.plus(1)) {
                out.add(d.getValue());
            }
            return out;
        }
        for (Integer v : input) {
            if (v == null || v < 1 || v > 7) {
                throw new BizException("周几取值为 1~7（1=周一）");
            }
            out.add(v);
        }
        return out;
    }

    /**
     * 模板校验：开始早于结束、彼此不重叠，并按开始时间排好序。
     *
     * <p>单日与批量两个入口共用这一份。分开写的话，迟早有一边放宽了规则
     * （比如批量忘了查重叠），于是同一份模板在两个入口表现不一致。
     */
    private List<ScheduleSaveRequest.Slot> validateAndSort(List<ScheduleSaveRequest.Slot> input) {
        List<ScheduleSaveRequest.Slot> slots = new ArrayList<>(input);
        // 先按开始时间排好，重叠检测只需与已有的每一段比一次
        slots.sort(Comparator.comparing((ScheduleSaveRequest.Slot s) -> parseTime(s.getStartTime())));

        List<LocalTime[]> ranges = new ArrayList<>(slots.size());
        for (ScheduleSaveRequest.Slot s : slots) {
            LocalTime start = parseTime(s.getStartTime());
            LocalTime end = parseTime(s.getEndTime());
            if (!start.isBefore(end)) {
                throw new BizException("时段 " + s.getStartTime() + "-" + s.getEndTime()
                        + " 的开始时间必须早于结束时间（暂不支持跨天排班）");
            }
            for (LocalTime[] prev : ranges) {
                if (start.isBefore(prev[1]) && prev[0].isBefore(end)) {
                    throw new BizException("时段 " + s.getStartTime() + "-" + s.getEndTime()
                            + " 与已排的时段重叠");
                }
            }
            ranges.add(new LocalTime[]{start, end});
        }
        return slots;
    }

    /**
     * 把行数据摊成「连续 N 天、每天若干时段」。
     *
     * @param skipStarted 是否过滤「当天已经开始的时段」。居民端为 true —— 选了也约不上；
     *                    医生端为 false —— 需要看到已过去的时段约了几人。
     */
    private List<ScheduleDayVO> buildDays(LocalDate from, int days,
                                          List<DoctorSchedule> rows, boolean skipStarted) {
        Map<LocalDate, List<ScheduleSlotVO>> byDate = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (DoctorSchedule r : rows) {
            if (skipStarted && LocalDateTime.of(r.getWorkDate(), r.getStartTime()).isBefore(now)) {
                continue;
            }
            byDate.computeIfAbsent(r.getWorkDate(), k -> new ArrayList<>()).add(toSlotVO(r));
        }
        List<ScheduleDayVO> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            ScheduleDayVO day = new ScheduleDayVO();
            day.setDate(d);
            day.getSlots().addAll(byDate.getOrDefault(d, List.of()));
            out.add(day);
        }
        return out;
    }

    private ScheduleSlotVO toSlotVO(DoctorSchedule s) {
        ScheduleSlotVO vo = new ScheduleSlotVO();
        vo.setId(s.getId());
        vo.setStartTime(s.getStartTime());
        vo.setEndTime(s.getEndTime());
        vo.setCapacity(s.getCapacity() == null ? 0 : s.getCapacity());
        vo.setBookedCount(s.getBookedCount() == null ? 0 : s.getBookedCount());
        return vo;
    }

    /** 接受 "09:00" 与 "09:00:00" 两种写法；其余格式给出可读的错误而不是 500 */
    private LocalTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            throw new BizException("时间不能为空");
        }
        try {
            return LocalTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new BizException("时间格式不正确：" + text + "（应为 HH:mm）");
        }
    }

    private int clampDays(int days) {
        if (days < 1) {
            return 1;
        }
        return Math.min(days, MAX_DAYS);
    }
}
