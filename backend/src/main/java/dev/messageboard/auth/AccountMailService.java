package dev.messageboard.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class AccountMailService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountMailService.class);
    private final ObjectProvider<JavaMailSender> sender;
    private final String publicUrl;
    private final String from;

    public AccountMailService(ObjectProvider<JavaMailSender> sender,
                              @Value("${board.public-url:http://127.0.0.1:5173}") String publicUrl,
                              @Value("${board.mail.from:}") String from) {
        this.sender = sender; this.publicUrl = publicUrl; this.from = from;
    }

    public void sendEmailVerification(User user, String token) {
        send(user.email(), "验证你的 ECHO 邮箱", "/?verifyEmail=" + token,
            "请打开下面的链接验证邮箱：\n" + publicUrl + "/?verifyEmail=" + token);
    }

    public void sendPasswordReset(User user, String token) {
        send(user.email(), "重置你的 ECHO 密码", "/?resetToken=" + token,
            "请打开下面的链接重置密码：\n" + publicUrl + "/?resetToken=" + token);
    }

    private void send(String email, String subject, String path, String text) {
        if (email == null || email.isBlank()) return;
        JavaMailSender mailSender = sender.getIfAvailable();
        if (mailSender == null || from.isBlank()) {
            LOG.info("本地邮件未配置，已生成账号邮件 type={} recipientDomain={} tokenSuffix={}",
                path.startsWith("/?reset") ? "password-reset" : "email-verification",
                email.substring(email.indexOf('@') + 1),
                text.substring(text.length() - 6));
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from); helper.setTo(email); helper.setSubject(subject); helper.setText(text);
            mailSender.send(message);
        } catch (MessagingException ex) {
            LOG.warn("账号邮件发送失败 recipientDomain={}", email.substring(email.indexOf('@') + 1));
        }
    }
}
