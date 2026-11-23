package dev.messageboard.auth;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class PasswordPolicy {
    private static final Set<String> DEFAULT_COMMON = Set.of(
        "password", "password1", "password1!", "123456789", "1234567890",
        "qwerty123", "qwertyuiop", "abcdefghij", "letmein123", "admin123",
        "welcome123", "iloveyou123", "changeme123"
    );
    private final Set<String> common;

    public PasswordPolicy(@org.springframework.beans.factory.annotation.Value("${board.auth.blocked-passwords:}") String configured) {
        Set<String> extra = configured == null || configured.isBlank() ? Set.of() : Arrays.stream(configured.split(","))
            .map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.common = java.util.stream.Stream.concat(DEFAULT_COMMON.stream(), extra.stream()).collect(Collectors.toUnmodifiableSet());
    }

    public void check(String password, String loginName) {
        String lower = password.toLowerCase(Locale.ROOT);
        if (common.contains(lower) || (loginName != null && loginName.length() >= 3
                && lower.contains(loginName.toLowerCase(Locale.ROOT)))) {
            throw new dev.messageboard.common.ApiException(400, "密码过于常见或包含账号信息，请换一个更独特的密码");
        }
    }
}
