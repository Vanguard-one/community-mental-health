package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cmh.psycare.common.enums.ConsultType;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.common.util.MaskUtil;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.ChatPeerVO;
import com.cmh.psycare.dto.ChatSessionVO;
import com.cmh.psycare.dto.ChatUnreadVO;
import com.cmh.psycare.dto.DepartmentVO;
import com.cmh.psycare.entity.ConsultMessage;
import com.cmh.psycare.entity.ConsultSession;
import com.cmh.psycare.entity.SysUser;
import com.cmh.psycare.mapper.ChatMapper;
import com.cmh.psycare.mapper.ConsultMessageMapper;
import com.cmh.psycare.mapper.ConsultSessionMapper;
import com.cmh.psycare.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医患一对一图文聊天。
 * <p>
 * 复用 consult_session / consult_message（{@code consult_type = DOCTOR}），
 * 与 AI 问诊同表不同渠道 —— 这两张表的注释本来就写着「电话/医生图文/AI 三种渠道统一」，
 * 新建一套表反而会造成职责重叠。
 *
 * <h3>四条硬约束</h3>
 * <ol>
 *   <li><b>发送者身份只从 JWT 推导</b>，绝不接受前端传入的 sender/senderId，
 *       否则任何居民都能伪造署名医生的消息。</li>
 *   <li><b>每个读写入口都校验会话归属</b>（{@link #requireMember}）——
 *       会话 id 是自增的，不校验就能遍历别人的聊天记录。</li>
 *   <li><b>姓名脱敏按视角分裂</b>：医生看居民要脱敏（患者隐私），
 *       居民看医生不脱敏（居民有权知道给自己看病的是谁）。</li>
 *   <li><b>两个列表只列「有过消息」的人</b>：医生端 {@link #listSessions()} 不出空会话，
 *       居民端 {@link #listDoctors()} 不出没聊过的医生。判定条件见下方 {@code <h3>}。</li>
 * </ol>
 *
 * <h3>「有过消息」的判定条件</h3>
 *
 * <p>只有一句：<b>该会话下至少存在 1 条 {@code consult_message} 行</b>
 * （SQL 层用 {@code EXISTS} 半连接实现，见 {@code ChatMapper.xml}）。
 * 三个「不」划定它的边界：
 *
 * <ul>
 *   <li><b>不看发送方向</b> —— 任一方发过就算。若要求「双向都发过」，
 *       刚发出消息、对方还没回的那条对话会在两边的列表里同时消失，
 *       与自己刚做的事直接矛盾。</li>
 *   <li><b>不看条数、不看会话状态</b> —— 1 条与 100 条等价；
 *       已 CLOSED 会话的历史消息仍算「曾经往来过」。</li>
 *   <li><b>不看已读</b> —— 「有没有消息往来」与「我点开过没有」是两件事：
 *       前者决定这个人要不要出现在列表里，后者只决定那一行亮不亮红点。
 *       所以准入门槛不看 {@code read_at}，未读为 0 的会话照常列出
 *       （只是不亮角标，见 {@link ChatUnreadVO}）。</li>
 * </ul>
 *
 * <p>反向的「不过滤」只有一处：{@link #openSession} 仍会为没聊过的医生/居民
 * 建出空会话。会话必须先存在才能发第一条消息，这条链路不受影响 ——
 * 空会话只是不显示在列表里，不是被删掉；对方一旦发来第一条消息，
 * 它立刻出现在列表顶部。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String ROLE_DOCTOR = "DOCTOR";
    private static final String ROLE_RESIDENT = "RESIDENT";
    private static final String STATUS_OPEN = "OPEN";

    /** 列表里最近消息的展示长度上限，超出截断（详情另有接口取全文） */
    private static final int PREVIEW_LEN = 60;

    /** 医生未填科室时的归并名。留空会让「医生咨询」页出现一张没有标题的卡片 */
    private static final String DEFAULT_DEPARTMENT = "其他";

    private final ChatMapper chatMapper;
    private final ConsultSessionMapper consultSessionMapper;
    private final ConsultMessageMapper consultMessageMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 当前用户参与的有消息往来的医患会话（医生：我接诊的；居民：我的）。
     *
     * <p>空会话（建了但一条消息都没发）不返回 —— 过滤在 SQL 里，
     * 见 {@code ChatMapper.selectSessionList}。
     */
    public List<ChatSessionVO> listSessions() {
        List<ChatSessionVO> list = chatMapper.selectSessionList(
                SecurityUtil.getRole(), SecurityUtil.getUserId(), mySender());
        list.forEach(this::decorate);
        return list;
    }

    public ChatSessionVO detail(Long sessionId) {
        ChatSessionVO vo = chatMapper.selectSessionDetail(sessionId, mySender());
        if (vo == null) {
            throw new BizException(404, "会话不存在");
        }
        return decorate(vo);
    }

    /**
     * 未读汇总：两端导航红点与列表角标的数字来源。
     *
     * <p>医生端与居民端调的是同一个接口、拿到的是同一个结构 ——
     * 「两端红点表现一致」由此成为结构上的事实，而不是两边各自小心的结果。
     */
    public ChatUnreadVO unreadSummary() {
        ChatUnreadVO vo = chatMapper.selectUnreadSummary(
                SecurityUtil.getRole(), SecurityUtil.getUserId(), mySender());
        // COUNT 聚合无 GROUP BY 时必定返回一行，null 只可能是映射异常。
        // 但红点拿不到数字不该把界面打崩，退化成 0（不亮）比抛出去好。
        if (vo == null) {
            vo = new ChatUnreadVO();
            vo.setSessionCount(0);
            vo.setMessageCount(0);
        }
        return vo;
    }

    /**
     * 把会话里<b>对端发来的</b>未读消息标记为已读，并返回标记后的未读汇总。
     *
     * <p>为什么返回汇总而不是 void：前端标记完就要更新红点，若让它再请求一次
     * {@code /chat/unread}，中间就多了一个「已经读过、红点还亮着」的窗口期，
     * 而那个中间态对用户没有任何意义（他要的是最后那个数字）。
     * 一次往返直接拿到权威值，红点就不会「先亮后灭」地闪一下。
     *
     * <p>只更新 {@code read_at IS NULL} 的行：已读时间一经写入不再刷新。
     * 否则每打开一次对话都会把「对方什么时候读的」改写成此刻，
     * 那时它就不再是已读回执，只是"最后访问时间"。
     *
     * <p>用 {@link LambdaUpdateWrapper} 显式 set，而不是「查出来改字段再 updateById」：
     * MyBatis-Plus 的 updateById 默认忽略 null，而这里写的正是一个可能为空的列，
     * 只有 wrapper 能明确表达「把 read_at 设成这个值」。
     *
     * <p>归属校验照走 {@link #requireMember} —— 不校验的话，任何人改一下
     * sessionId 就能把别人的未读清空（并且从此不再提醒）。
     */
    @Transactional
    public ChatUnreadVO markRead(Long sessionId) {
        requireMember(sessionId);
        consultMessageMapper.update(null,
                new LambdaUpdateWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .ne(ConsultMessage::getSender, mySender())
                        .isNull(ConsultMessage::getReadAt)
                        .set(ConsultMessage::getReadAt, LocalDateTime.now()));
        return unreadSummary();
    }

    /**
     * 当前登录角色在 {@code consult_message.sender} 上的取值。
     *
     * <p>现在它恰好等于 {@link SecurityUtil#getRole()}（两个词表同名），
     * 仍然独立成一个方法：调用方要表达的是「我发的消息在 sender 列里长什么样」，
     * 而不是「我是什么角色」。同名是当下的巧合，不是约定 ——
     * 收敛在这一处，将来 sender 词表一变（例如加渠道前缀），只有这里需要动，
     * 而三个查询里也不会有某处漏改后静默算错未读。
     *
     * <p>只可能是 DOCTOR 或 RESIDENT：/chat 的每个入口都过了
     * {@code requireChatter()}，ADMIN 进不来。
     */
    private String mySender() {
        return SecurityUtil.getRole();
    }

    /**
     * 打开与某人的会话：已有进行中的直接复用，没有才新建。
     * <p>
     * 复用而不是每次点击都建新的，是因为「聊天」在产品语义上是一个持续对话，
     * 而不是一次性工单。否则医生点十次「聊天」就会有十条空会话。
     *
     * @param targetId 医生端传 residentId，居民端传 doctorId
     */
    @Transactional
    public ChatSessionVO openSession(Long targetId) {
        String role = SecurityUtil.getRole();
        Long me = SecurityUtil.getUserId();
        boolean isDoctor = ROLE_DOCTOR.equals(role);
        if (!isDoctor && !ROLE_RESIDENT.equals(role)) {
            throw new BizException(403, "仅医生与居民可以发起聊天");
        }
        if (targetId == null) {
            throw new BizException(400, "缺少聊天对象");
        }

        // 对端角色必须匹配，防止医生传一个医生 id / 居民给另一个居民发消息。
        // selectById 已被 @TableLogic 过滤，软删账号查不到，这里无需再判 is_deleted。
        SysUser peer = sysUserMapper.selectById(targetId);
        if (peer == null) {
            throw new BizException(404, "对方账号不存在或已注销");
        }
        String expected = isDoctor ? ROLE_RESIDENT : ROLE_DOCTOR;
        if (!expected.equals(peer.getRole())) {
            throw new BizException(400, "对方不是可聊天的对象");
        }

        Long residentId = isDoctor ? targetId : me;
        Long doctorId = isDoctor ? me : targetId;

        ConsultSession exist = consultSessionMapper.selectOne(
                new LambdaQueryWrapper<ConsultSession>()
                        .eq(ConsultSession::getResidentId, residentId)
                        .eq(ConsultSession::getDoctorId, doctorId)
                        .eq(ConsultSession::getConsultType, ConsultType.DOCTOR)
                        .eq(ConsultSession::getStatus, STATUS_OPEN)
                        .orderByDesc(ConsultSession::getId)
                        .last("LIMIT 1"));
        if (exist != null) {
            return detail(exist.getId());
        }

        ConsultSession session = new ConsultSession();
        session.setResidentId(residentId);
        session.setDoctorId(doctorId);
        session.setConsultType(ConsultType.DOCTOR);
        session.setStatus(STATUS_OPEN);
        session.setCreatedAt(LocalDateTime.now());
        consultSessionMapper.insert(session);
        return detail(session.getId());
    }

    /**
     * 会话消息，按时间正序。
     *
     * @param sinceId 只取 id 大于它的增量消息，供前端轮询；传 null 取全量
     */
    public List<ConsultMessage> messages(Long sessionId, Long sinceId) {
        requireMember(sessionId);
        return consultMessageMapper.selectList(
                new LambdaQueryWrapper<ConsultMessage>()
                        .eq(ConsultMessage::getSessionId, sessionId)
                        .gt(sinceId != null, ConsultMessage::getId, sinceId)
                        .orderByAsc(ConsultMessage::getId));
    }

    /** 发送消息，返回落库后的实体（前端据此上屏并去重） */
    @Transactional
    public ConsultMessage send(Long sessionId, String content) {
        ConsultSession session = requireMember(sessionId);
        if (!STATUS_OPEN.equals(session.getStatus())) {
            throw new BizException(400, "会话已结束，无法继续发送消息");
        }
        String role = SecurityUtil.getRole();
        ConsultMessage msg = new ConsultMessage();
        msg.setSessionId(sessionId);
        msg.setSender(ROLE_DOCTOR.equals(role) ? ROLE_DOCTOR : ROLE_RESIDENT);
        msg.setSenderId(SecurityUtil.getUserId());
        msg.setContent(content.trim());
        // 人工聊天的危机词检测未实现；这里写 0 而不是留 null，
        // 避免前端 `=== 1` 与 SQL 聚合对 null 的判断产生第三态。
        msg.setCrisisFlag(0);
        msg.setCreatedAt(LocalDateTime.now());
        consultMessageMapper.insert(msg);
        return msg;
    }

    /**
     * 会话归属校验。
     * <p>
     * 每个读写入口都必须过这一关：会话 id 是自增的，
     * 只要不校验，任何登录用户都能靠改 URL 遍历别人的聊天记录。
     */
    private ConsultSession requireMember(Long sessionId) {
        ConsultSession session = consultSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(404, "会话不存在");
        }
        boolean isDoctor = ROLE_DOCTOR.equals(SecurityUtil.getRole());
        Long me = SecurityUtil.getUserId();
        boolean member = isDoctor
                ? me.equals(session.getDoctorId())
                : me.equals(session.getResidentId());
        if (!member) {
            throw new BizException(403, "无权访问该会话");
        }
        return session;
    }

    /** 按视角组装对端展示信息 */
    private ChatSessionVO decorate(ChatSessionVO vo) {
        boolean isDoctor = ROLE_DOCTOR.equals(SecurityUtil.getRole());
        if (isDoctor) {
            // 医生看患者：姓名脱敏；医生不需要看到自己的职称与执业资料。
            // 这里显式置 null 而不是依赖「SQL 没查就不填」—— peer* 是下发字段，
            // 留着不动的话，哪天 SQL 改成 INNER JOIN 或加列，就会悄悄漏出去。
            vo.setPeerName(MaskUtil.name(vo.getResidentName()));
            vo.setPeerDesc(null);
            vo.setPeerDepartment(null);
            vo.setPeerExpertise(null);
            vo.setPeerRating(null);
        } else {
            // 居民看自己的医生：不脱敏。医生是服务提供者，居民有权知情；
            // 且推荐页本来就展示医生真实姓名，两处一致才不会自相矛盾。
            String name = vo.getDoctorName();
            vo.setPeerName(name == null || name.isBlank() ? vo.getDoctorUsername() : name);
            vo.setPeerDesc(vo.getDoctorTitle());
            // 科室/擅长/评分与姓名同属「对端是谁」，一起下发。
            // 好处是聊天页拿到会话就能渲染标题栏，不必再拉一次医生目录
            //（那是个带聚合子查询的重查询，只为取三个静态字段太浪费）。
            vo.setPeerDepartment(vo.getDoctorDepartment());
            vo.setPeerExpertise(vo.getDoctorExpertise());
            vo.setPeerRating(vo.getDoctorRating());
        }

        // 「等我回复」= 最后一条是对端发的。它与 unreadCount（由 SQL 直接给出）是
        // 两个独立信号：那个回答「我看过没有」，这个回答「我回了没有」。
        // 点开看过但没回复 —— 红点该灭，而这条「待回复」该留：
        // 它提醒的是义务，不是浏览行为。所以两者都不参与对方的计算。
        String peerSender = isDoctor ? ROLE_RESIDENT : ROLE_DOCTOR;
        vo.setAwaitingMyReply(peerSender.equals(vo.getLastSender()));

        vo.setLastMessage(clip(vo.getLastMessage()));
        return vo;
    }

    /**
     * 居民视角：**有过消息往来**的医生列表（含与该居民的会话与最近消息）。
     *
     * <p>从未聊过的医生不再出现在列表里。这是与医生端「消息列表」对齐的改动 ——
     * 两侧共用「有过消息」的判定条件（见类注释）。
     *
     * <p>注意副作用：本接口原先是居民端**唯一**的「找医生开聊」入口
     * （推荐页只展示、没有聊天按钮）。过滤后新居民将无从发起第一句话，
     * 若产品需要保留该能力，应在推荐页补一个「咨询」入口，而不是把过滤退回去。
     */
    public List<ChatPeerVO> listDoctors() {
        SecurityUtil.requireRole(ROLE_RESIDENT);
        List<ChatPeerVO> list = chatMapper.selectDoctorList(SecurityUtil.getUserId());
        list.forEach(vo -> {
            if (vo.getDoctorName() == null || vo.getDoctorName().isBlank()) {
                vo.setDoctorName(vo.getDoctorUsername());
            }
            vo.setLastMessage(clip(vo.getLastMessage()));
        });
        return list;
    }

    /** 列表里的消息预览统一截断，避免一条长消息把整行撑开 */
    private String clip(String text) {
        if (text == null || text.length() <= PREVIEW_LEN) {
            return text;
        }
        return text.substring(0, PREVIEW_LEN) + "…";
    }

    /**
     * 「医生咨询」页的医生目录：按科室分组的<b>全部</b>可咨询医生。
     *
     * <p>与 {@link #listDoctors()} 的分工见 {@code ChatMapper#selectDoctorCatalog} 的注释 ——
     * 一句话：那边是「聊过的人」，这边是「可聊的人」。
     * 新居民在「我的消息」页看到的是空列表，必须从这里发起第一句话。
     *
     * <p>科室顺序是<b>确定性</b>的，依次按：
     * ① 组内是否有进行中的会话（聊过的科室靠前 —— 这是刻意的，回到这个页面的人多数想接着上次聊）；
     * ② 医生数降序；③ 科室名升序。
     *
     * <p>为什么必须显式排序：科室顺序原先直接继承 SQL 的返回顺序，而 SQL 是
     * 「有对话者优先 → 最近消息 → 评分 → id」—— 于是科室顺序被「该科室最高评分」
     * 隐式决定：加一个医生、改一次评分，都可能让整个科室挪位。
     * 3 个科室时看不出来，十个科室时就是「每次刷新顺序都可能不同」。
     * ②③ 两条与业务无关，只为让顺序可预期、可复现。
     *
     * <p>这个接口<b>只读不写</b>：不建会话、不写推荐日志。医生的会话在用户点
     * 「咨询」时才由 {@code /chat/sessions} 建立（见 {@link #openSession}）。
     */
    public List<DepartmentVO> doctorCatalog() {
        SecurityUtil.requireRole(ROLE_RESIDENT);
        List<ChatPeerVO> all = chatMapper.selectDoctorCatalog(SecurityUtil.getUserId());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        Map<String, DepartmentVO> grouped = new LinkedHashMap<>();
        for (ChatPeerVO vo : all) {
            if (vo.getDoctorName() == null || vo.getDoctorName().isBlank()) {
                vo.setDoctorName(vo.getDoctorUsername());
            }
            vo.setLastMessage(clip(vo.getLastMessage()));
            String dept = vo.getDepartment() == null || vo.getDepartment().isBlank()
                    ? DEFAULT_DEPARTMENT
                    : vo.getDepartment().trim();
            DepartmentVO group = grouped.get(dept);
            if (group == null) {
                group = new DepartmentVO();
                group.setDepartment(dept);
                group.setDoctors(new ArrayList<>());
                grouped.put(dept, group);
            }
            group.getDoctors().add(vo);
        }
        List<DepartmentVO> out = new ArrayList<>(grouped.size());
        for (DepartmentVO group : grouped.values()) {
            group.setDoctorCount(group.getDoctors().size());
            out.add(group);
        }
        // 聊过的科室优先 → 医生多的优先 → 科室名升序（理由见方法注释）
        Comparator<DepartmentVO> talkingFirst = Comparator.comparingInt(
                g -> g.getDoctors().stream().anyMatch(d -> d.getSessionId() != null) ? 0 : 1);
        Comparator<DepartmentVO> moreDoctorsFirst =
                Comparator.comparingInt(DepartmentVO::getDoctorCount).reversed();
        out.sort(talkingFirst.thenComparing(moreDoctorsFirst)
                .thenComparing(DepartmentVO::getDepartment));
        return out;
    }
}
