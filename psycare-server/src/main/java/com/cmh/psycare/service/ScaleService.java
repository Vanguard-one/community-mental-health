package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.entity.ScaleItem;
import com.cmh.psycare.entity.ScaleOption;
import com.cmh.psycare.mapper.ScaleItemMapper;
import com.cmh.psycare.mapper.ScaleMapper;
import com.cmh.psycare.mapper.ScaleOptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 量表服务：为问卷页组装"量表 + 题目 + 选项"结构
 */
@Service
@RequiredArgsConstructor
public class ScaleService {

    private final ScaleMapper scaleMapper;
    private final ScaleItemMapper scaleItemMapper;
    private final ScaleOptionMapper scaleOptionMapper;

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
}
