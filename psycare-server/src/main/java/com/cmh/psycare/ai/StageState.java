package com.cmh.psycare.ai;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一次 AI 问诊会话的阶段状态。存 Redis（key {@code ai:stage:{sessionId}}），会过期、会丢失。
 *
 * <h3>设计要点：状态必须可重建</h3>
 * <p>Redis 重启、TTL 到期、人工清库都会让状态消失。如果状态是「不可重建的进度条」，
 * 用户就会突然发现 AI 又回到「你好，我是……」的第一轮 —— 所以
 * {@link StageStore} 支持按历史消息<b>重放</b>出等价状态，而重放与在线推进
 * 共用 {@link StageRules#advance} 同一个函数（见该类注释）。
 *
 * <p>正因如此，这个类里刻意<b>不存</b>任何无法从历史消息推导出来的字段。
 * 比如「用户上一句是否满意」这类瞬时判断就不能放在这里。
 */
@Data
public class StageState {

    /** 当前阶段 */
    private InterviewStage stage = InterviewStage.OPENING;

    /** 本会话内用户发言总数（重放时等于居民消息条数） */
    private int turns;

    /**
     * 在<b>当前阶段内</b>的发言数，进入新阶段时清零。
     *
     * <p>推进阈值必须用它而不是总轮数：总轮数是单调递增的，若用总轮数做阈值，
     * 越往后每个阶段的阈值越容易被满足，阶段会一路冲到「小结转介」，
     * 五阶段引导直接退化成一次性的问答。
     */
    private int turnsInStage;

    /**
     * 已采集到的信息维度（{@link StageRules} 的字段名）。
     *
     * <p>用 {@link LinkedHashSet} 而不是 HashSet：写进提示词时的顺序影响
     * prompt 前缀的字节一致性，抽取顺序（消息时间顺序）天然稳定。
     */
    private Set<String> fields = new LinkedHashSet<>();

    private LocalDateTime updatedAt;

    /** 浅拷贝，供 {@link StageRules#advance} 做不可变推进 */
    public StageState copy() {
        StageState s = new StageState();
        s.setStage(stage);
        s.setTurns(turns);
        s.setTurnsInStage(turnsInStage);
        s.setFields(new LinkedHashSet<>(fields));
        s.setUpdatedAt(updatedAt);
        return s;
    }

    /** 已采集维度的个数 */
    public int fieldCount() {
        return fields == null ? 0 : fields.size();
    }
}
