package dev.messageboard.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod")
public class ProductionConfiguration {
    @Value("${board.cursor-secret:}") String cursorSecret;
    @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie;
    @Value("${board.public-url:}") String publicUrl;
    @Value("${board.upload-dir:}") String uploadDir;
    @PostConstruct
    void validate() {
        if(cursorSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<32) throw new IllegalStateException("生产环境必须配置至少 32 字节 CURSOR_SECRET");
        if(!secureCookie) throw new IllegalStateException("生产环境必须启用 COOKIE_SECURE=true");
        if(!publicUrl.startsWith("https://")) throw new IllegalStateException("生产环境 PUBLIC_URL 必须使用 HTTPS");
        if(uploadDir.isBlank() || !java.nio.file.Path.of(uploadDir).isAbsolute()) throw new IllegalStateException("生产环境 UPLOAD_DIR 必须是绝对路径");
    }
}
