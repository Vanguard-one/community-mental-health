package com.cmh.psycare.config;

import com.cmh.psycare.common.util.JwtUtil;
import com.cmh.psycare.common.util.LoginUser;
import com.cmh.psycare.common.util.SecurityUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 鉴权过滤器：解析 Authorization 头，校验黑名单，写入登录上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolve(request);
        try {
            if (StringUtils.hasText(token)) {
                Boolean black = redis.hasKey(BLACKLIST_PREFIX + token);
                if (Boolean.TRUE.equals(black)) {
                    write401(response);
                    return;
                }
                Claims claims = jwtUtil.parse(token);
                LoginUser user = new LoginUser(
                        Long.valueOf(claims.getSubject()),
                        claims.get("username", String.class),
                        claims.get("role", String.class));
                SecurityUtil.set(user);
            }
            chain.doFilter(request, response);
        } catch (Exception e) {
            log.debug("token 解析失败: {}", e.getMessage());
            write401(response);
        } finally {
            SecurityUtil.clear();
        }
    }

    private String resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return request.getParameter("token");
    }

    private void write401(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录已过期\"}");
    }
}
