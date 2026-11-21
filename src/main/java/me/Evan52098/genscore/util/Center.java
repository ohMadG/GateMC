package me.Evan52098.genscore.util;

public final class Center {

    private static final int CHAT_HALF = 154;

    private Center() {
    }

    public static String pad(String legacy) {
        int width = width(legacy);
        int spaces = Math.max(0, (CHAT_HALF - width / 2) / 4);
        return " ".repeat(spaces) + legacy;
    }

    public static String line(String line, String placeholder, String label) {
        if (!line.startsWith("{center}")) {
            return line;
        }
        String body = line.substring("{center}".length());
        int spaces = Math.max(0, (CHAT_HALF - width(body.replace(placeholder, label)) / 2) / 4);
        return " ".repeat(spaces) + body;
    }

    public static int width(String legacy) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(i + 1));
                if (code == '#' && i + 7 < legacy.length()) {
                    bold = false;
                    i += 7;
                    continue;
                }
                if ("0123456789abcdefr".indexOf(code) >= 0) {
                    bold = false;
                    i++;
                    continue;
                }
                if (code == 'l') {
                    bold = true;
                    i++;
                    continue;
                }
                if ("kmno".indexOf(code) >= 0) {
                    i++;
                    continue;
                }
            }
            w += glyph(c) + 1 + (bold && c != ' ' ? 1 : 0);
        }
        return w;
    }

    private static int glyph(char c) {
        return switch (c) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 1;
            case 'l', '`' -> 2;
            case 'I', 't', '[', ']', ' ' -> 3;
            case 'f', 'k', '<', '>', '(', ')', '{', '}', '"', '*' -> 4;
            case '@', '~' -> 6;
            default -> 5;
        };
    }
}
