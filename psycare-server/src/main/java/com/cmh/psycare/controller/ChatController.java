package com.cmh.psycare.controller;

import com.cmh.psycare.common.result.Result;
import com.cmh.psycare.common.util.SecurityUtil;
import com.cmh.psycare.dto.ChatOpenRequest;
import com.cmh.psycare.dto.ChatPeerVO;
import com.cmh.psycare.dto.ChatSendRequest;
import com.cmh.psycare.dto.ChatSessionVO;
import com.cmh.psycare.dto.ChatUnreadVO;
import com.cmh.psycare.dto.DepartmentVO;
import com.cmh.psycare.entity.ConsultMessage;
import com.cmh.psycare.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医患一对一聊天（医生端 + 居民端共用同一套接口）。
 *
 * <h3>为什么两端共用一个 Controller 而不是各写一份</h3>
 * 医生与居民在这条链路上做的事完全对称：列会话、开会话、拉消息、发消息，
 * 差别只在「我是哪一侧」——而这个信息 JWT 里就有。
 * 拆成两个 Controller 会把同一套归属校验写两遍，还会给越权留下缝隙。
 *
 * <p>权限：仅 DOCTOR 与 RESIDENT。ADMIN 不参与医患对话，进不来。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 对话双方都可用；发送者身份由 Service 从 JWT 推导，不取前端入参 */
    private void requireChatter() {
        SecurityUtil.requireRole("DOCTOR", "RESIDENT");
    }

    /** 我的会话列表 */
    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> sessions() {
        requireChatter();
        return Result.ok(chatService.listSessions());
    }

    /**
     * 未读汇总：有未读的会话数 + 未读消息条数。
     *
     * <p>医生端侧栏与居民端顶部导航的角标都读这一个接口 —— 两端红点的
     * 「数字从哪来」是同一处，所以不可能出现一边算了另一边没算的情况。
     *
     * <p>返回的是两个数字而不是一个布尔：角标显示会话数（见 {@link ChatUnreadVO}
     * 的说明），会话列表里按行显示条数。
     */
    @GetMapping("/unread")
    public Result<ChatUnreadVO> unread() {
        requireChatter();
        return Result.ok(chatService.unreadSummary());
    }

    /**
     * 居民视角：可聊天的医生列表（含最近消息）。
     *
     * <p>居民端「我的消息」页要能自己挑医生开聊，而不是只能等医生先发消息。
     * 仅 RESIDENT 可用 —— 医生的对端列表是「用户信息」页（带患者档案），职责不同。
     */
    @GetMapping("/doctors")
    public Result<List<ChatPeerVO>> doctors() {
        SecurityUtil.requireRole("RESIDENT");
        return Result.ok(chatService.listDoctors());
    }

    /**
     * 「医生咨询」页：按科室分组的全部可咨询医生。
     *
     * <p>与 `/chat/doctors` 的区别是一个用词：那边只列<b>聊过</b>的医生（「我的消息」页），
     * 这边列<b>全部可聊</b>的医生（供新居民找第一句话的入口）。
     * 两个都要有，合并成一个就会牺牲其中一边的语义 ——
     * 「我的消息」里出现从没聊过的医生，或新居民找不到任何医生，都是错的。
     */
    @GetMapping("/doctor-catalog")
    public Result<List<DepartmentVO>> doctorCatalog() {
        SecurityUtil.requireRole("RESIDENT");
        return Result.ok(chatService.doctorCatalog());
    }

    /** 打开（或复用）与某人的会话 */
    @PostMapping("/sessions")
    public Result<ChatSessionVO> open(@Valid @RequestBody ChatOpenRequest req) {
        requireChatter();
        return Result.ok(chatService.openSession(req.getTargetId()));
    }

    /** 会话详情：刷新页面后还原标题栏用 */
    @GetMapping("/sessions/{id}")
    public Result<ChatSessionVO> detail(@PathVariable Long id) {
        requireChatter();
        return Result.ok(chatService.detail(id));
    }

    /**
     * 会话消息（时间正序）。
     *
     * @param sinceId 增量拉取：只返回 id 大于它的消息，供前端轮询。
     *                传 null 则返回全量（进页面首次加载）
     */
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ConsultMessage>> messages(@PathVariable Long id,
                                                 @RequestParam(required = false) Long sinceId) {
        requireChatter();
        return Result.ok(chatService.messages(id, sinceId));
    }

    /** 发送消息 */
    @PostMapping("/sessions/{id}/messages")
    public Result<ConsultMessage> send(@PathVariable Long id,
                                       @Valid @RequestBody ChatSendRequest req) {
        requireChatter();
        return Result.ok(chatService.send(id, req.getContent()));
    }

    /**
     * 标记已读：把该会话里**对端发来的、尚未读**的消息打上读取时间。
     *
     * <p>刻意做成显式的 POST 而不是「调 GET /messages 就算读过」：
     * 拉消息是幂等读操作，让它顺手写库会有两个问题 ——
     * ① 轮询、预取、重试都会带着副作用，未读数会以不可预期的方式掉下去；
     * ② 何时算「用户真的看到了」是前端的判断（如页面在前台），
     *    这个决定不该藏在服务端的读接口里。
     *
     * <p>返回标记后的未读汇总，前端据此直接更新红点，不必再请求一次
     * {@code /chat/unread}（见 {@code ChatService#markRead}）。
     */
    @PostMapping("/sessions/{id}/read")
    public Result<ChatUnreadVO> read(@PathVariable Long id) {
        requireChatter();
        return Result.ok(chatService.markRead(id));
    }
}
