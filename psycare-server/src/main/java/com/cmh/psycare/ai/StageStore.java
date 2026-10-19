package com.cmh.psycare.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.entity.ConsultMessage;
import com.cmh.psycare.mapper.ConsultMessageMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阶段状态的存取：Redis 为主，历史重放兜底。
 *
 * <pre>
 * key  ai:stage:{sessionId}
 * ttl  2 小时（每次写入滑动续期）
 * </pre>
 *
 * <h3>为什么一定要有「重放」这条路径</h3>
 * <p>Redis 是会丢东西的：重启、TTL 到期、内存淘汰、人工清库。若阶段状态是
 * 唯一副本，用户会毫无预兆地看到 AI 又回到「你好，我是……」的第一轮。
 * 重放只需要历史居民消息（本来就在 MySQL 里），成本是几十行代码。
 *
 * <h3>⚠️ 调用顺序：必须先 load 再落库本轮消息</h3>
 * <p>重放会把<b>已落库的</b>居民消息全部跑一遍。如果本轮消息已经写进库，
 * 重放结果就自带了本轮推进，调用方再 {@code advance} 一次就成了推进两次 ——
 * 阶段会比正常路径跑得快，而且只在「Redis 恰好丢了」的时候出现，极难复现。
 * 所以约定：<b>先 {@code load}，再落库本轮消息，最后 {@code advance}</b>。
 *
 * <h3>Redis 故障时的行为</h3>
 * <p>读写失败都只告警，不抛异常。AI 问诊是心理场景，一个「进度条存不上」
 * 不该让正在倾诉的人收到报错 —— 退化成「每轮重放重建」仍然正确，只是多一次查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageStore {

    private static final String KEY_PREFIX = "ai:stage:";
    private static final Duration TTL = Duration.ofHours(2);
    private static final String SENDER_RESIDENT = "RESIDENT";

    /**
     * 重放时最多回看多少条居民消息。
     *
     * <p>取「最近 N 条」而不是「最早 N 条」：阶段推进只依赖最近的信息与累计字段，
     * 而超长会话（几百轮）从头重放既慢又没必要。
     */
    private static final int REPLAY_LIMIT = 200;

    /**
     * Redis 值的专用序列化器。
     *
     * <h4>⚠️ 不能用 {@code new ObjectMapper()}</h4>
     * <p>必须显式注册 {@link JavaTimeModule}：{@link StageState#getUpdatedAt()} 是
     * {@code LocalDateTime}，而裸 {@code ObjectMapper} 不认 JSR-310 类型，序列化会抛
     * <pre>
     * Java 8 date/time type `java.time.LocalDateTime` not supported by default:
     * add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
     * </pre>
     * 后果很隐蔽：{@link #save} 把异常吞掉只打 WARN，于是<b>阶段状态永远写不进 Redis</b>，
     * 会话每一轮都退化成全量重放（日志里每轮一条「按历史消息重放重建」，回看条数=0 起、
     * 逐轮 +1）。功能看起来是对的 —— 重放本来就等价 —— 所以能一直不被发现，
     * 代价是每次请求多一次全表扫描式的历史回溯，而且 Redis 形同虚设。
     *
     * <p>不用 {@code findAndRegisterModules()}：它按 classpath 扫描自动装配，
     * 行为随依赖变化而变。Redis 里存的是一个内部固定格式，需要的是「确定的写法」。
     *
     * <p>关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}：将来给 {@link StageState} 加字段时，
     * 旧值仍能反序列化（否则会退化成 warn + 重放，同样的静默路径）。
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StringRedisTemplate redis;
    private final ConsultMessageMapper messageMapper;

    /**
     * 读取当前阶段状态。**不含本轮** —— 本轮由调用方 {@link StageRules#advance} 一次。
     *
     * <p>Redis 无值或解析失败时自动按历史消息重放重建（幂等）。
     */
    public StageState load(Long sessionId) {
        try {
            String raw = redis.opsForValue().get(key(sessionId));
            if (raw != null && !raw.isBlank()) {
                return JSON.readValue(raw, StageState.class);
            }
        } catch (Exception e) {
            // 包含「Redis 连不上」与「JSON 结构变了」两种情况，都不该中断对话
            log.warn("[AI 阶段] Redis 读取失败，改由历史消息重放重建 session={} 原因={}",
                    sessionId, e.getMessage());
        }
        return replay(sessionId);
    }

    /** 保存并滑动续期 */
    public void save(Long sessionId, StageState state) {
        state.setUpdatedAt(LocalDateTime.now());
        try {
            redis.opsForValue().set(key(sessionId), JSON.writeValueAsString(state), TTL);
        } catch (Exception e) {
            log.warn("[AI 阶段] Redis 写入失败（本轮引导不受影响，下一轮会自动重放重建）session={} 原因={}",
                    sessionId, e.getMessage());
        }
    }

    /** 会话关闭时清掉，避免残留到 TTL 到期 */
    public void clear(Long sessionId) {
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("[AI 阶段] Redis 清理失败 session={} 原因={}", sessionId, e.getMessage());
        }
    }

    /**
     * 按历史居民消息重放，重建出与在线推进等价的阶段状态。
     *
     * <p>重放「等价」是结构保证的：这里用的 {@link StageRules#advance} 与在线路径是同一个函数。
     */
    StageState replay(Long sessionId) {
        List<ConsultMessage> recent = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .eq(ConsultMessage::getSender, SENDER_RESIDENT)
                        .orderByDesc(ConsultMessage::getId)
                        .last("limit " + REPLAY_LIMIT));
        List<ConsultMessage> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);   // 倒序取最近 N 条，再翻回时间正序

        StageState state = new StageState();
        for (ConsultMessage m : ordered) {
            state = StageRules.advance(state, m.getContent());
        }
        log.info("[AI 阶段] 按历史消息重放重建 session={} 回看={}条 → stage={} turns={} 字段={}",
                sessionId, ordered.size(), state.getStage(), state.getTurns(), state.getFields());
        return state;
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
