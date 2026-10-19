package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmh.psycare.common.result.PageResult;
import com.cmh.psycare.common.util.MaskUtil;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.AssessmentHistoryVO;
import com.cmh.psycare.dto.ResidentInfoVO;
import com.cmh.psycare.mapper.ResidentInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.List;

/**
 * 医生端「用户信息」：居民档案 + 测评概览 + 会话入口。
 */
@Service
@RequiredArgsConstructor
public class ResidentInfoService {

    private final ResidentInfoMapper residentInfoMapper;
    private final AssessmentService assessmentService;

    /**
     * 居民分页列表。
     * <p>
     * 会话 id 按**当前登录医生**取：同一位居民可以被不同医生分别接诊，
     * 每个医生各自持有一条会话，互不串台。
     */
    public PageResult<ResidentInfoVO> page(long current, long size, String keyword) {
        Long doctorId = SecurityUtil.getUserId();
        Page<ResidentInfoVO> page = new Page<>(current, size);
        IPage<ResidentInfoVO> result = residentInfoMapper.selectResidentPage(page, doctorId, keyword);
        result.getRecords().forEach(this::mask);
        return PageResult.of(result);
    }

    /** 某位居民的历次测评（卷名 + 分数 + 风险 + 时间），即「所属评测问卷」 */
    public List<AssessmentHistoryVO> assessments(Long residentId) {
        return assessmentService.historyWithScale(residentId);
    }

    /**
     * 展示层脱敏 + 派生字段。
     * <p>
     * 脱敏放在这里而不是 SQL：脱敏规则只应有一份（{@link MaskUtil}），
     * 写进 SQL 表达式就等于在数据库里再抄一遍，两边迟早漂移。
     */
    private void mask(ResidentInfoVO vo) {
        vo.setRealName(MaskUtil.name(vo.getRealName()));
        vo.setPhone(MaskUtil.phone(vo.getPhone()));
        // birth_year 存的是年份而非生日（弱化识别），所以年龄只能算到年
        vo.setAge(vo.getBirthYear() == null ? null : Year.now().getValue() - vo.getBirthYear());
    }
}
