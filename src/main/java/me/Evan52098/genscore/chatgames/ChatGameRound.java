package me.Evan52098.genscore.chatgames;

import java.util.UUID;

/** One round: what was shown, the answer, and when it started. Immutable, so any thread may read it. */
public record ChatGameRound(ChatGameType type, String prompt, String answer, boolean caseSensitive, String id, long startedAtMs) {

    public ChatGameRound(ChatGameType type, String prompt, String answer, boolean caseSensitive) {
        this(type, prompt, answer, caseSensitive, UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    public boolean matches(String candidate) {
        String trimmed = candidate.trim();
        return caseSensitive ? trimmed.equals(answer) : trimmed.equalsIgnoreCase(answer);
    }
}
