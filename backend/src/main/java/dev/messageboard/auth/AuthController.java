package dev.messageboard.auth;

import dev.messageboard.common.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.Base64;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final AccountMailService mail;
    private final RateLimiter loginLimiter;
    private final int authRate;
    private final String dummyHash;
    public AuthController(UserMapper users, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                          AccountMailService mail, RateLimiter loginLimiter,
                          @org.springframework.beans.factory.annotation.Value("${board.rate.auth}") int authRate) {
        this.users=users; this.encoder=encoder; this.passwordPolicy=passwordPolicy; this.mail=mail;
        this.loginLimiter=loginLimiter; this.authRate=authRate;
        this.dummyHash=encoder.encode(UUID.randomUUID().toString());
    }
    public record Register(String loginName, String displayName, String password, String confirmPassword, String email) {
        public Register(String loginName, String displayName, String password, String confirmPassword) {
            this(loginName, displayName, password, confirmPassword, null);
        }
    }
    public record Login(String loginName, String password) {}
    public record Forgot(String loginOrEmail) {}
    public record Reset(String token, String password, String confirmPassword) {}
    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName",token.getHeaderName()); }
    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth) {
        Map<String,Object> result = new HashMap<>();
        result.put("user", auth != null && auth.getPrincipal() instanceof User.View v ? v : null);
        return result;
    }
    @PostMapping("/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Transactional
    public User.View register(@RequestBody Register data) {
        String name = normalize(data.loginName());
        if (!name.matches("[a-z0-9_]{3,32}")) throw ApiException.bad("账号应为 3～32 位字母、数字或下划线");
        if (Set.of("admin","administrator","moderator","system","support","root").contains(name))
            throw ApiException.bad("这个账号名不能使用，请换一个");
        Checks.text(data.password(),9,128,"密码");
        passwordPolicy.check(data.password(), name);
        if (!data.password().equals(data.confirmPassword())) throw ApiException.bad("两次密码不一致");
        String display = Checks.blank(data.displayName()) ? name : data.displayName().strip();
        Checks.text(display,1,30,"昵称");
        String email = normalizeEmail(data.email());
        User user = new User(UUID.randomUUID(),name,display,encoder.encode(data.password()),email,null);
        try { users.insert(user); } catch (DuplicateKeyException ex) { throw new ApiException(409,"账号或邮箱已被使用"); }
        if (email != null) {
            String token = issueToken(user.id(), "EMAIL_VERIFY");
            mail.sendEmailVerification(user, token);
        }
        return user.view();
    }
    @PostMapping("/login")
    public User.View login(@RequestBody Login data, HttpServletRequest req, HttpServletResponse res) {
        String name=normalize(data.loginName());
        String password=Checks.text(data.password(),1,128,"密码");
        if (!loginLimiter.allow("auth:user:" + name, authRate))
            throw new ApiException(429,"操作过于频繁，请稍后重试");
        User user = users.find(name);
        if (!encoder.matches(password, user == null ? dummyHash : user.passwordHash()) || user == null)
            throw new ApiException(401,"账号或密码不正确");
        if (Boolean.TRUE.equals(users.blocked(user.id()))) throw new ApiException(403,"账号已被限制登录");
        req.getSession(true);
        req.changeSessionId();
        req.getSession().setAttribute("authenticatedAt", Instant.now().getEpochSecond());
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.view(),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,req,res);
        return user.view();
    }

    @GetMapping("/verify-email")
    @Transactional
    public Map<String,String> verifyEmail(@RequestParam String token) {
        var row = users.findToken(hash(token), "EMAIL_VERIFY");
        if (row == null || users.consumeToken(row.id()) != 1 || users.markEmailVerified(row.userId()) != 1)
            throw ApiException.bad("邮箱验证链接无效或已过期");
        return Map.of("message", "邮箱验证成功");
    }

    @PostMapping("/forgot-password")
    public Map<String,String> forgotPassword(@RequestBody Forgot data) {
        String value = data == null || data.loginOrEmail() == null ? "" : data.loginOrEmail().strip();
        User user = value.contains("@") ? users.findByEmail(normalizeEmail(value)) : users.find(normalize(value));
        if (user != null && user.email() != null && user.emailVerifiedAt() != null) {
            String token = issueToken(user.id(), "PASSWORD_RESET");
            mail.sendPasswordReset(user, token);
        }
        return Map.of("message", "如果账号存在且已验证邮箱，我们会发送密码重置邮件");
    }

    @PostMapping("/reset-password")
    @Transactional
    public Map<String,String> resetPassword(@RequestBody Reset data) {
        if (data == null || data.token() == null || data.token().isBlank())
            throw ApiException.bad("缺少重置令牌");
        UserMapper.AccountToken row = users.findToken(hash(data.token()), "PASSWORD_RESET");
        if (row == null || row.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC)) || row.usedAt() != null)
            throw ApiException.bad("重置链接无效或已过期");
        User user = users.findById(row.userId());
        if (user == null) throw ApiException.bad("重置链接无效或已过期");
        Checks.text(data.password(),9,128,"密码");
        passwordPolicy.check(data.password(), user.loginName());
        if (!data.password().equals(data.confirmPassword())) throw ApiException.bad("两次密码不一致");
        if (users.consumeToken(row.id()) != 1) throw ApiException.bad("重置链接无效或已过期");
        users.updatePassword(user.id(), encoder.encode(data.password()));
        return Map.of("message", "密码已重置，请使用新密码登录");
    }

    private String issueToken(UUID userId, String purpose) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        users.insertToken(new UserMapper.AccountToken(UUID.randomUUID(), userId, purpose,
            hash(token), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30), null));
        return token;
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private String normalizeEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String email = value.strip().toLowerCase(Locale.ROOT);
        Checks.text(email,3,320,"邮箱");
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw ApiException.bad("邮箱格式不正确");
        return email;
    }
    private String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }
}
