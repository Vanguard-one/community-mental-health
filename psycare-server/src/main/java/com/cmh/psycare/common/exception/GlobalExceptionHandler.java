package com.cmh.psycare.common.exception;

import com.cmh.psycare.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：把异常统一转成 Result，避免把堆栈直接吐给前端。
 *
 * <h3>为什么要把「路由不存在 / 方法不对 / 报文坏掉」从兜底里拆出来</h3>
 * 它们都属于**调用方契约错误**，此前全部落进 {@code Exception} 兜底，
 * 被归一化成「系统异常，请稍后重试」—— 这个提示有两个实际害处：
 * <ol>
 *   <li><b>误导排查方向。</b>后端没重启（新接口还没生效）时，前端拿到的
 *       与「数据库连不上」一模一样的文案，完全指不出真实原因。</li>
 *   <li><b>污染日志。</b>404 会以 ERROR 级 + 完整堆栈落盘，把真正的故障淹掉。</li>
 * </ol>
 * 拆开后：404 只记一行 INFO/warn 且文案直指「接口不存在 + 请确认后端版本」，
 * 坏 JSON 返回「请求体格式不正确」，方法不对返回 405。
 *
 * <h3>注意业务错误码仍走 HTTP 200</h3>
 * 本类只决定 {@code body.code}，不改 HTTP 状态码（这是项目约定）——
 * 唯一的例外是 JwtAuthFilter 里未登录时直接写的 HTTP 401。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> biz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> valid(BindException e) {
        String msg = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return Result.fail(400, msg);
    }

    /**
     * 路由不存在。
     * <p>
     * 最常见的成因不是前端写错 URL，而是**后端进程还跑在改动前的构建上** ——
     * 所以提示里直接把这句话写出来，省掉一轮「接口是不是没生效」的来回。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> notFound(NoResourceFoundException e, HttpServletRequest req) {
        String msg = "接口不存在：" + req.getMethod() + " " + req.getRequestURI()
                + "（请确认后端已重启到最新版本）";
        log.warn("{}", msg);
        return Result.fail(404, msg);
    }

    /** HTTP 方法不匹配，如把 POST 接口用 GET 调 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> methodNotAllowed(HttpRequestMethodNotSupportedException e,
                                        HttpServletRequest req) {
        String msg = "请求方法不支持：" + req.getMethod() + " " + req.getRequestURI()
                + "（应为 " + e.getSupportedHttpMethods() + "）";
        log.warn("{}", msg);
        return Result.fail(405, msg);
    }

    /** 报文解析失败：body 不是合法 JSON、字段类型对不上等 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> notReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(400, "请求体格式不正确，请检查 JSON 结构与字段类型");
    }

    /** 缺少必填的 query 参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> missingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.fail(400, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 路径变量 / query 参数的类型转换失败，如把 {@code /alert/abc} 传给 {@code Long id}。
     *
     * <p>此前它落进兜底，把「调用方参数写错」报成 500「系统异常，请稍后重试」——
     * 这个提示会把人引向错误方向：去查数据库、查空指针、查后端有没有重启，
     * 而真实原因只是前端拼错了一段 URL。归到 400 才指得准。
     *
     * <p>信息里带上参数名与期望类型：排查时不用回去翻 Controller 签名。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> typeMismatch(MethodArgumentTypeMismatchException e,
                                    HttpServletRequest req) {
        Class<?> need = e.getRequiredType();
        String msg = "参数类型不正确：" + e.getName()
                + "（应为 " + (need == null ? "未知类型" : need.getSimpleName()) + "）";
        log.warn("{} | {}", msg, req.getRequestURI());
        return Result.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e) {
        log.error("系统异常", e);
        return Result.fail("系统异常，请稍后重试");
    }
}
