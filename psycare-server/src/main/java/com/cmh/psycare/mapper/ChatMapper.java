package com.cmh.psycare.mapper;

import com.cmh.psycare.dto.ChatPeerVO;
import com.cmh.psycare.dto.ChatSessionVO;
import com.cmh.psycare.dto.ChatUnreadVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 医患聊天会话读模型。
 * <p>
 * 复用既有的 consult_session / consult_message 两张表，靠 consult_type = 'DOCTOR'
 * 与 AI 问诊（consult_type = 'AI'）隔离 —— 零建表、零迁移。
 * <p>
 * 为什么需要 XML：「最近一条消息」和「消息条数」是 consult_message 的聚合，
 * wrapper 只能查主表，拼不出来。
 */
@Mapper
public interface ChatMapper {

    /**
     * 当前用户的会话列表。
     *
     * @param role     当前登录角色，DOCTOR 查自己接诊的，其余按居民查自己的
     * @param userId   当前登录用户 id
     * @param mySender 当前登录角色对应的 <b>sender 取值</b>（DOCTOR / RESIDENT），
     *                 用于统计「对端发来的未读」。与 {@code role} 当前同值，但**刻意分开传**：
     *                 一个决定「查哪一侧的会话」，一个决定「哪些消息算对端发的」，
     *                 把两件事挤进一个参数，日后 sender 词表一变就会静默算错。
     */
    List<ChatSessionVO> selectSessionList(@Param("role") String role,
                                          @Param("userId") Long userId,
                                          @Param("mySender") String mySender);

    /**
     * 单个会话（含双方姓名与最近消息），用于归属校验与详情返回。
     *
     * @param mySender 见 {@link #selectSessionList} —— 详情也要回 {@code unreadCount}，
     *                 否则同一个 VO 在两条路径下一个是数字一个是 null，
     *                 前端类型就没法诚实地声明它
     */
    ChatSessionVO selectSessionDetail(@Param("sessionId") Long sessionId,
                                      @Param("mySender") String mySender);

    /**
     * 居民视角的可聊天医生列表（含与该居民的会话与最近消息）。
     *
     * <p>与 {@link ResidentInfoMapper#selectResidentPage} 互为镜像：那边是医生选居民，
     * 这边是居民选医生。只返回已通过资质审核且账号启用的医生。
     *
     * @param residentId 当前登录居民 id，用于关联「我和这个医生是否已有会话」
     */
    List<ChatPeerVO> selectDoctorList(@Param("residentId") Long residentId);

    /**
     * 「医生咨询」页的医生目录：<b>全部</b>已通过资质审核且账号启用的医生。
     *
     * <p>与 {@link #selectDoctorList} 的唯一差别是<b>不要求有过消息往来</b>。
     * 两个接口都不能省：
     * <ul>
     *   <li>{@code selectDoctorList} —— 「我的消息」页，列的是<b>聊过的人</b>，
     *       没聊过的不该占位；</li>
     *   <li>本方法 —— 「医生咨询」页，列的是<b>可聊的人</b>，
     *       新居民必须从这里发起第一句话（否则他永远找不到入口）。</li>
     * </ul>
     *
     * <p>仍保留 {@code sessionId} 关联（且同样要求该会话已有消息）：
     * 列表上要能显示「继续聊天 / 开始咨询」两种状态，而这个 id 就是判据。
     * 关联条件里的 {@code EXISTS} 不能省 —— 否则会把 {@code openSession}
     * 建出的空会话当成「聊过」，用户点进去发现是空的。
     *
     * <p>排序把「已有对话的医生」排在前面：居民回到这个页面，多半是想接着上次聊。
     *
     * @param residentId 当前登录居民 id
     */
    List<ChatPeerVO> selectDoctorCatalog(@Param("residentId") Long residentId);

    /**
     * 未读汇总（有未读的会话数 + 未读消息条数）。
     *
     * <p>两端导航红点共用这一个查询 —— 不在前端各写一份统计，是为了让
     * 「医生端与居民端在红点上表现一致」这件事有个结构性保证，而不是靠两边自觉。
     *
     * @param role     当前登录角色，决定按医生侧还是居民侧过滤会话
     * @param userId   当前登录用户 id
     * @param mySender 见 {@link #selectSessionList}
     */
    ChatUnreadVO selectUnreadSummary(@Param("role") String role,
                                     @Param("userId") Long userId,
                                     @Param("mySender") String mySender);
}
