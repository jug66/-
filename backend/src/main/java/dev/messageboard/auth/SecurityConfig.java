package dev.messageboard.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.messageboard.common.RequestGuard;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean RateLimiter rateLimiter() { return new RateLimiter(); }
    @Bean SecurityFilterChain security(HttpSecurity http,ObjectMapper json,
            @Value("${board.session-absolute-seconds}") long absolute,
            @Value("${board.rate.read}") int reads,@Value("${board.rate.auth}") int auths,@Value("${board.rate.write}") int writes,
            RateLimiter limiter) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a -> a.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/**").permitAll()
                .requestMatchers("/api/auth/register","/api/auth/login","/api/auth/forgot-password","/api/auth/reset-password","/error").permitAll()
                .requestMatchers("/api/auth/verify-email").permitAll().anyRequest().authenticated())
            .requestCache(c -> c.disable())
            .formLogin(c -> c.disable()).httpBasic(c -> c.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> RequestGuard.error(req,res,401,"请先登录",json))
                .accessDeniedHandler((req,res,ex) -> RequestGuard.error(req,res,403,"没有权限或安全令牌已失效，请重试；若仍失败，请重新登录",json)))
            .logout(l -> l.logoutUrl("/api/auth/logout").invalidateHttpSession(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                HttpSession session=req.getSession(false);
                if(session!=null && session.getAttribute("authenticatedAt") instanceof Long born
                        && Instant.now().getEpochSecond()-born>=absolute) {
                    session.invalidate(); SecurityContextHolder.clearContext();
                }
                String path=req.getRequestURI();
                if(!path.startsWith("/api/")) { chain.doFilter(req,res); return; }
                boolean auth=path.equals("/api/auth/login") || path.equals("/api/auth/register");
                boolean read=req.getMethod().equals("GET");
                String group=auth?"auth":read?"read":"write";
                int max=auth?auths:read?reads:writes;
                var principal=SecurityContextHolder.getContext().getAuthentication();
                boolean allowed=limiter.allow(group+":ip:"+req.getRemoteAddr(),max);
                if(!read && principal!=null && principal.getPrincipal() instanceof User.View user)
                    allowed=limiter.allow(group+":user:"+user.id(),max) && allowed;
                if(!allowed) { res.setHeader("Retry-After","60"); RequestGuard.error(req,res,429,"操作过于频繁，请稍后重试",json); return; }
                chain.doFilter(req,res);
            }
        }, SecurityContextHolderFilter.class);
        return http.build();
    }
}
