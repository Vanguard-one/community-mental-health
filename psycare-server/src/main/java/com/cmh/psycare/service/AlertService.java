package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmh.psycare.common.enums.AlertStatus;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AlertHandleRequest;
import com.cmh.psycare.dto.StatusCount;
import com.cmh.psycare.entity.AlertRecord;
import com.cmh.psycare.entity.AlertStatusLog;
import com.cmh.psycare.mapper.AlertRecordMapper;
import com.cmh.psycare.mapper.AlertStatusLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预警工单服务：列表 + 状态机驱动的处置。
 *
 * 状态机：
 *   PENDING → CONFIRMED / REJECTED
 *   CONFIRMED → PROCESSING
 *   PROCESSING → FOLLOWED
 *   FOLLOWED  → CLOSED
 * 每次流转写入 alert_status_log，保证全程可追溯。
 *
 * 另有一条**只有管理员**能走的恢复边：REJECTED / CLOSED → PENDING（见 {@link #reopen}），
 * 它不进上面的流转表，医生端在处置台里也看不到这个入口。
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRecordMapper alertRecordMapper;
    private final AlertStatusLogMapper alertStatusLogMapper;

    /** 合法状态流转表 */
    private static final Map<AlertStatus, Set<AlertStatus>> TRANSITIONS = Map.of(
            AlertStatus.PENDING, Set.of(AlertStatus.CONFIRMED, AlertStatus.REJECTED),
            AlertStatus.CONFIRMED, Set.of(AlertStatus.PROCESSING),
            AlertStatus.PROCESSING, Set.of(AlertStatus.FOLLOWED),
            AlertStatus.FOLLOWED, Set.of(AlertStatus.CLOSED)
    );

    /**
     * 工单分页。
     *
     * @param status    状态过滤。支持逗号分隔多值（如 {@code "CONFIRMED,PROCESSING"}），
     *                  便于前端用「处理中」这类跨状态分组做筛选；空则不过滤
     * @param alertType 预警类型过滤（THRESHOLD / TREND / CRISIS），空则不过滤
     */
    public PageResult<AlertRecord> page(long current, long size, String status, String alertType) {
        Page<AlertRecord> page = new Page<>(current, size);
        List<String> statusList = splitCsv(status);
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<AlertRecord>()
                .in(!statusList.isEmpty(), AlertRecord::getStatus, statusList)
                .eq(StringUtils.hasText(alertType), AlertRecord::getAlertType, alertType)
                .orderByDesc(AlertRecord::getCreatedAt);
        return PageResult.of(alertRecordMapper.selectPage(page, wrapper));
    }

    /** 单个工单详情：供分栏处置台深链直达与刷新还原使用 */
    public AlertRecord detail(Long alertId) {
        AlertRecord record = alertRecordMapper.selectById(alertId);
        if (record == null) {
            throw new BizException(404, "工单不存在");
        }
        return record;
    }

    /**
     * 各状态工单数（去标识化聚合，仅计数）。
     * 固定返回全部 6 个状态且个数为 0 的也返回，前端无需再补零。
     */
    public List<StatusCount> statusCount() {
        List<StatusCount> list = new ArrayList<>(AlertStatus.values().length);
        for (AlertStatus s : AlertStatus.values()) {
            Long count = alertRecordMapper.selectCount(
                    new LambdaQueryWrapper<AlertRecord>().eq(AlertRecord::getStatus, s));
            list.add(new StatusCount(s.name(), count == null ? 0L : count));
        }
        return list;
    }

    /** "A,B" → ["A","B"]；空白项与空串一律丢弃 */
    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 处置工单：校验状态机 → 更新 → 记录流转日志 */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long alertId, AlertHandleRequest req) {
        AlertRecord record = alertRecordMapper.selectById(alertId);
        if (record == null) {
            throw new BizException(404, "工单不存在");
        }
        AlertStatus from = record.getStatus();
        AlertStatus to;
        try {
            to = AlertStatus.valueOf(req.getToStatus());
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "非法的目标状态: " + req.getToStatus());
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new BizException(400, "不允许从 " + from + " 流转到 " + to);
        }

        record.setStatus(to);
        record.setHandlerId(SecurityUtil.getUserId());
        record.setHandleNote(req.getNote());
        record.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.updateById(record);

        AlertStatusLog log = new AlertStatusLog();
        log.setAlertId(alertId);
        log.setFromStatus(from.name());
        log.setToStatus(to.name());
        log.setOperatorId(SecurityUtil.getUserId());
        log.setRemark(req.getNote());
        log.setCreatedAt(LocalDateTime.now());
        alertStatusLogMapper.insert(log);
    }

    public List<AlertStatusLog> logs(Long alertId) {
        return alertStatusLogMapper.selectList(
                new LambdaQueryWrapper<AlertStatusLog>()
                        .eq(AlertStatusLog::getAlertId, alertId)
                        .orderByAsc(AlertStatusLog::getCreatedAt));
    }

    /** 可重新开启的终结态：只有这两种「流程终止」才有恢复的意义 */
    private static final Set<AlertStatus> REOPENABLE =
            Set.of(AlertStatus.REJECTED, AlertStatus.CLOSED);

    /**
     * 重新开启工单（仅管理员）：把已驳回 / 已关闭的工单退回「待确认」，让医生重新核实一遍。
     *
     * <p>与 {@link #handle} 的三点区别：
     * <ol>
     *   <li><b>不走 TRANSITIONS</b>。从终结态回到 PENDING 是管理员的越权恢复动作，
     *       显式建模比往流转表里加一条边更安全 —— 后者会让医生端也顺手用它。</li>
     *   <li><b>处置人与备注一并清空</b>。退回待确认意味着「尚未有人处置」；
     *       若保留 handlerId，面板会显示「待确认 + 处理人 #2」这种自相矛盾的状态。</li>
     *   <li><b>原备注不会丢</b> —— 流转日志里那条终结记录仍在，可追溯谁在什么时候驳回了它。</li>
     * </ol>
     *
     * @param note 管理员填写的重开启原因，写入流转日志；为空时用默认文案
     */
    @Transactional(rollbackFor = Exception.class)
    public void reopen(Long alertId, String note) {
        AlertRecord record = alertRecordMapper.selectById(alertId);
        if (record == null) {
            throw new BizException(404, "工单不存在");
        }
        AlertStatus from = record.getStatus();
        if (!REOPENABLE.contains(from)) {
            throw new BizException(400, "只有已驳回或已关闭的工单可以重新开启，当前状态：" + from);
        }

        alertRecordMapper.update(null, new LambdaUpdateWrapper<AlertRecord>()
                .eq(AlertRecord::getId, alertId)
                .set(AlertRecord::getStatus, AlertStatus.PENDING)
                // updateById 默认忽略 null 字段，要清空必须用 set 显式写 NULL
                .set(AlertRecord::getHandlerId, null)
                .set(AlertRecord::getHandleNote, null)
                .set(AlertRecord::getUpdatedAt, LocalDateTime.now()));

        AlertStatusLog log = new AlertStatusLog();
        log.setAlertId(alertId);
        log.setFromStatus(from.name());
        log.setToStatus(AlertStatus.PENDING.name());
        log.setOperatorId(SecurityUtil.getUserId());
        log.setRemark(StringUtils.hasText(note) ? note : "管理员重新开启，退回待确认");
        log.setCreatedAt(LocalDateTime.now());
        alertStatusLogMapper.insert(log);
    }
}
