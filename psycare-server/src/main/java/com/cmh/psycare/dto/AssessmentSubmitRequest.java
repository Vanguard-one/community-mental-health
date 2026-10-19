package com.cmh.psycare.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测评提交请求：一次性提交整套作答
 */
@Data
public class AssessmentSubmitRequest {

    @NotNull(message = "量表ID不能为空")
    private Long scaleId;

    @NotEmpty(message = "作答不能为空")
    private List<AnswerItem> answers;

    @Data
    public static class AnswerItem {
        @NotNull(message = "题目ID不能为空")
        private Long itemId;
        @NotNull(message = "选项ID不能为空")
        private Long optionId;
    }
}
