package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.dto.AdminScaleVO;
import com.cmh.psycare.entity.Assessment;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.entity.ScaleItem;
import com.cmh.psycare.entity.ScaleOption;
import com.cmh.psycare.mapper.AssessmentMapper;
import com.cmh.psycare.mapper.ScaleItemMapper;
import com.cmh.psycare.mapper.ScaleMapper;
import com.cmh.psycare.mapper.ScaleOptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 量表服务：居民端"量表 + 题目 + 选项"组装，以及管理端的筛查表治理。
 *
 * <h3>为什么读侧与管理侧写在同一个类</h3>
 * <p>两者共享同一条领域约束 —— <b>「这张表现在还能不能被用来做题」</b>。
 * 读侧靠 {@code status=1} 过滤，管理侧靠改 {@code status} 决定它出不出现，
 * 拆成两个类会让这条约束横跨两处、迟早走岔。量表的写操作也只有"改状态 / 删除"
 * 两种，没有独立的状态机，不值得再拆一层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaleService {

    /** 与 {@code scale.status} 的取值约定，与 BannerService 保持同一套语义 */
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final ScaleMapper scaleMapper;
    private final ScaleItemMapper scaleItemMapper;
    private final ScaleOptionMapper scaleOptionMapper;
    private final AssessmentMapper assessmentMapper;

    /* ==================== 居民端读侧 ==================== */

    public List<Scale> listEnabled() {
        List<Scale> scales = scaleMapper.selectList(
                new LambdaQueryWrapper<Scale>().eq(Scale::getStatus, 1));
        if (scales.isEmpty()) {
            return scales;
        }
        // 聚合题数：量表数量与题量都极小（个位数），一次 in 查询足够，不做 N+1
        Map<Long, Long> countByScale = scaleItemMapper.selectList(
                        new LambdaQueryWrapper<ScaleItem>()
                                .in(ScaleItem::getScaleId, scales.stream().map(Scale::getId).toList()))
                .stream()
                .collect(Collectors.groupingBy(ScaleItem::getScaleId, Collectors.counting()));
        scales.forEach(s -> s.setItemCount(countByScale.getOrDefault(s.getId(), 0L).intValue()));
        return scales;
    }

    /** 组装问卷页所需的完整结构 */
    public Map<String, Object> detail(Long scaleId) {
        Scale scale = scaleMapper.selectById(scaleId);
        if (scale == null) {
            throw new BizException(404, "量表不存在");
        }
        List<ScaleItem> items = scaleItemMapper.selectList(
                        new LambdaQueryWrapper<ScaleItem>().eq(ScaleItem::getScaleId, scaleId))
                .stream().sorted(Comparator.comparing(ScaleItem::getItemNo)).toList();
        List<ScaleOption> options = scaleOptionMapper.selectList(
                new LambdaQueryWrapper<ScaleOption>()
                        .in(ScaleOption::getItemId, items.stream().map(ScaleItem::getId).collect(Collectors.toList())));
        Map<Long, List<ScaleOption>> optionByItem = options.stream()
                .collect(Collectors.groupingBy(ScaleOption::getItemId));

        List<Map<String, Object>> itemList = items.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            m.put("itemNo", item.getItemNo());
            m.put("content", item.getContent());
            List<ScaleOption> opts = optionByItem.getOrDefault(item.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(ScaleOption::getSort)).toList();
            m.put("options", opts);
            return m;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("scale", scale);
        result.put("items", itemList);
        return result;
    }

    /* ==================== 管理端：筛查表治理 ==================== */

    /**
     * 管理端全量列表：<b>不</b>按 status 过滤，只隐藏已删除的。
     *
     * <p>与居民端 {@link #listEnabled()} 的差别是语义性的，不是参数性的：
     * 居民端要"过滤掉不能做的"，管理端要"一张都不漏"——禁用之后管理员
     * 必须还能看见它、并且能再启用回来。用一个 {@code ?all=true} 开关
     * 会让"读错分支 → 居民端露出已禁用量表"这种 bug 有机会发生，
     * 所以两条路径各自独立（与 BannerController 的处理一致）。
     *
     * <p>已软删的行由 MyBatis-Plus 逻辑删除插件自动过滤，不会回到列表里。
     */
    public List<AdminScaleVO> listForAdmin() {
        List<Scale> scales = scaleMapper.selectList(
                new LambdaQueryWrapper<Scale>().orderByAsc(Scale::getId));
        if (scales.isEmpty()) {
            return List.of();
        }
        List<Long> ids = scales.stream().map(Scale::getId).toList();

        Map<Long, Integer> itemCount = scaleItemMapper.selectList(
                        new LambdaQueryWrapper<ScaleItem>().in(ScaleItem::getScaleId, ids))
                .stream()
                .collect(Collectors.groupingBy(ScaleItem::getScaleId, Collectors.summingInt(x -> 1)));

        Map<Long, Integer> assessmentCount = new HashMap<>();
        for (Map<String, Object> row : assessmentMapper.countByScale()) {
            Object scaleId = row.get("scaleId");
            Object cnt = row.get("cnt");
            // 按 Number 取值再转：MySQL 的 COUNT(*) 是 BIGINT，
            // 换驱动/走代理时可能拿到 BigInteger，直接强转 Long 会炸
            if (scaleId instanceof Number idVal && cnt instanceof Number cntVal) {
                assessmentCount.put(idVal.longValue(), cntVal.intValue());
            }
        }

        return scales.stream()
                .map(s -> AdminScaleVO.builder()
                        .id(s.getId())
                        .code(s.getCode())
                        .name(s.getName())
                        .dimension(s.getDimension())
                        .maxScore(s.getMaxScore())
                        .description(s.getDescription())
                        .status(s.getStatus())
                        .itemCount(itemCount.getOrDefault(s.getId(), 0))
                        .assessmentCount(assessmentCount.getOrDefault(s.getId(), 0))
                        .createdAt(s.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * 启用 / 禁用。传**目标值**而不是切换，这是有意的：
     * <p>切换语义（toggle）在两种真实场景下会出错 ——
     * ① 用户双击，两次请求都把当前值取反，结果翻回原状态，页面显示"已禁用"但实际已启用；
     * ② 两个管理员同时操作同一行，互相覆盖。
     * 传目标值后接口天然幂等：连点两次"禁用"结果仍是禁用。
     *
     * @param status 1 启用 / 0 禁用
     */
    @Transactional(rollbackFor = Exception.class)
    public Scale setStatus(Long id, Integer status) {
        if (status == null || (status != STATUS_ENABLED && status != STATUS_DISABLED)) {
            throw new BizException(400, "状态值只能是 1（启用）或 0（禁用）");
        }
        Scale scale = require(id);
        if (Objects.equals(scale.getStatus(), status)) {
            return scale;   // 幂等：已经是目标状态，不写库也不报错
        }
        Scale patch = new Scale();
        patch.setId(id);
        patch.setStatus(status);
        scaleMapper.updateById(patch);
        scale.setStatus(status);
        log.info("[Scale] {} 筛查表 id={} code={}",
                status == STATUS_ENABLED ? "启用" : "禁用", id, scale.getCode());
        return scale;
    }

    /**
     * 删除筛查表（逻辑删除）。
     *
     * <h3>为什么"删除"不是 DELETE</h3>
     * <p>量表被 {@code assessment.scale_id}、{@code assessment_answer.item_id} 引用，
     * 物理删除会让历史测评的卷名与逐题明细失去归属。所以这里只打软删标记：
     * 居民端立刻不可见（{@code /scale/list} 查不出来）、不能再提交
     * （{@code AssessmentService.submit} 校验 status）、管理端列表也不再展示，
     * 而历史记录照常可看 —— {@code AssessmentRecordMapper.xml} 里手写的
     * {@code JOIN scale} 不受逻辑删除插件影响，仍能取到卷名。
     *
     * <h3>为什么额外把 status 置 0</h3>
     * <p>软删标记只对走 {@code BaseMapper} 的查询生效。报表、原生 SQL、
     * 或将来某个忘了加条件的查询仍可能扫到这一行 —— 顺手置成"停用"，
     * 等于给所有没走逻辑删除的读路径也上了同一把锁，代价只有一次 UPDATE。
     *
     * <h3>题目与选项为什么不删</h3>
     * <p>{@code scale_item / scale_option} 同样被 {@code assessment_answer} 引用，
     * 且 {@code selectAnswerRows} 要 JOIN 它们来还原题干与作答文案。
     * 删掉它们会让历史记录变成一串 id。几十行数据不值得冒这个险，保留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        Scale scale = require(id);
        Long assessmentCount = assessmentMapper.selectCount(
                new LambdaQueryWrapper<Assessment>().eq(Assessment::getScaleId, id));

        Scale patch = new Scale();
        patch.setId(id);
        patch.setStatus(STATUS_DISABLED);
        scaleMapper.updateById(patch);
        // 全局配置 logic-delete-field: isDeleted → 实际执行 UPDATE scale SET is_deleted=1
        scaleMapper.deleteById(id);

        log.warn("[Scale] 删除（软删）筛查表 id={} code={}，已有测评 {} 条 —— 历史记录与题目选项均保留",
                id, scale.getCode(), assessmentCount);
    }

    /* ==================== 工具 ==================== */

    private Scale require(Long id) {
        Scale scale = id == null ? null : scaleMapper.selectById(id);
        if (scale == null) {
            throw new BizException(404, "筛查表不存在或已删除");
        }
        return scale;
    }
}
