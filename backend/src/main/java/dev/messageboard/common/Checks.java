package dev.messageboard.common;

public final class Checks {
    private Checks() {}
    public static String text(String value, int min, int max, String field) {
        if (value == null || value.codePointCount(0, value.length()) < min
                || value.codePointCount(0, value.length()) > max || value.codePoints().anyMatch(c -> c == 0)) {
            throw ApiException.bad(field + "长度应为 " + min + "～" + max + " 个字符");
        }
        return value;
    }
    public static boolean blank(String value) {
        return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}
