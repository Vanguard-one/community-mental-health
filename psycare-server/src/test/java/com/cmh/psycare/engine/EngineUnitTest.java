package com.cmh.psycare.engine;

import com.cmh.psycare.common.enums.RiskLevel;
import com.cmh.psycare.entity.Scale;
import com.cmh.psycare.entity.ScaleItem;
import com.cmh.psycare.entity.ScaleOption;
import com.cmh.psycare.entity.ScoringRule;
import com.cmh.psycare.mapper.ScoringRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 引擎单元测试：验证计分分级与危机检测这两个"红线"逻辑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineUnitTest {

    @Mock
    private ScoringRuleMapper scoringRuleMapper;

    @InjectMocks
    private ScoringEngine scoringEngine;

    private final CrisisDetector crisisDetector = new CrisisDetector();

    private Scale phq9;
    private List<ScaleItem> items;
    private List<ScaleOption> allOptions;

    @BeforeEach
    void setUp() {
        phq9 = new Scale();
        phq9.setId(1L);
        phq9.setCode("PHQ9");

        // 9 题，第 9 题为危机项
        items = new ArrayList<>();
        allOptions = new ArrayList<>();
        for (int no = 1; no <= 9; no++) {
            ScaleItem item = new ScaleItem();
            item.setId(100L + no);
            item.setScaleId(1L);
            item.setItemNo(no);
            item.setCrisisFlag(no == 9 ? 1 : 0);
            item.setReverseScore(0);
            items.add(item);
            // 每题 4 个选项 0-3 分
            for (int s = 0; s <= 3; s++) {
                ScaleOption opt = new ScaleOption();
                opt.setId((100L + no) * 10 + s);
                opt.setItemId(item.getId());
                opt.setScore(s);
                opt.setSort(s);
                allOptions.add(opt);
            }
        }

        // PHQ-9 切分规则
        when(scoringRuleMapper.selectList(any())).thenReturn(List.of(
                rule(0, 4, "无", "A", 1),
                rule(5, 9, "轻度", "A", 2),
                rule(10, 14, "中度", "B", 3),
                rule(15, 19, "中重度", "C", 4),
                rule(20, 27, "重度", "D", 5)));
    }

    private ScoringRule rule(int min, int max, String raw, String risk, int sort) {
        ScoringRule r = new ScoringRule();
        r.setScaleId(1L);
        r.setMinScore(min);
        r.setMaxScore(max);
        r.setRawLevel(raw);
        r.setRiskLevel(risk);
        r.setSort(sort);
        return r;
    }

    /** 全部选某分值 */
    private Map<Long, Long> allScore(int score, Integer crisisItemScore) {
        Map<Long, Long> submitted = new HashMap<>();
        for (ScaleItem item : items) {
            int s = (item.getItemNo() == 9 && crisisItemScore != null) ? crisisItemScore : score;
            submitted.put(item.getId(), item.getId() * 10 + s);
        }
        return submitted;
    }

    @Test
    void 全选0分_应为无风险A() {
        ScoreResult r = scoringEngine.score(phq9, items, allOptions, allScore(0, null));
        assertEquals(0, r.totalScore());
        assertEquals("无", r.rawLevel());
        assertEquals(RiskLevel.A, r.riskLevel());
        assertFalse(r.crisis());
    }

    @Test
    void 总分12分_应为中度B() {
        // 12 题各选 1 分凑不出 12，这里用 9 题每题约 1.33，直接构造：前 3 题各 3 分，其余 0
        Map<Long, Long> submitted = new HashMap<>();
        for (ScaleItem item : items) {
            int s = item.getItemNo() <= 4 ? 3 : 0; // 4 题 * 3 = 12
            submitted.put(item.getId(), item.getId() * 10 + s);
        }
        ScoreResult r = scoringEngine.score(phq9, items, allOptions, submitted);
        assertEquals(12, r.totalScore());
        assertEquals("中度", r.rawLevel());
        assertEquals(RiskLevel.B, r.riskLevel());
    }

    @Test
    void 危机项得分大于0_无论总分多少都强制D() {
        // 其它题全 0，第 9 题选 1 分
        ScoreResult r = scoringEngine.score(phq9, items, allOptions, allScore(0, 1));
        assertEquals(1, r.totalScore());
        assertTrue(r.crisis());
        assertEquals(RiskLevel.D, r.riskLevel(), "危机项阳性必须强制 D");
    }

    @Test
    void 危机词检测_命中与未命中() {
        assertTrue(crisisDetector.hit("我最近不想活了"));
        assertTrue(crisisDetector.hit("有过自杀的念头"));
        assertFalse(crisisDetector.hit("今天心情还不错"));
        assertFalse(crisisDetector.hit(null));
        assertTrue(crisisDetector.crisisResponse().contains("热线"));
    }
}
