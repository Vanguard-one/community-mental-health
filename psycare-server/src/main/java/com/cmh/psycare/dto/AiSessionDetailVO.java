package com.cmh.psycare.dto;

import java.util.List;

/**
 * 会话明细：消息 + 当前引导阶段 + 摘要。
 *
 * <p>放进明细接口是必要的：前端切到历史会话时，阶段指示条要立刻显示正确状态，
 * 否则它会一直停在「建立联结」，直到用户再发一条消息才纠正过来 ——
 * 那种「一进来就显示错」的界面会让人以为历史上下文丢了。
 */
public record AiSessionDetailVO(
        Long sessionId,
        String status,
        String stage,
        String stageLabel,
        int stageStep,
        /** 早远对话的摘要（可能为 null：会话太短还没触发压缩） */
        String summary,
        List<AiMessageVO> messages) {
}
