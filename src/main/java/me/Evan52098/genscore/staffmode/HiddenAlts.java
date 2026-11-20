package me.Evan52098.genscore.staffmode;

import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hidden alts (HxCore {@code hxcore.hiddenalts.bypass}): the owner lists accounts under
 * {@code staffmode.hidden-alts} (UUIDs) and any alt lookup shows a plain "no other accounts" result for them, unless
 * the viewer has {@code genscore.hiddenalts.bypass}.
 *
 * <p>GensCore has no alt lookup yet; whichever module adds one (/alts, /seen with addresses) calls
 * {@link #showReal(CommandSender, UUID)} for the looked-up player and for every account it would list.
 */
public final class HiddenAlts {

    public static final String BYPASS = "genscore.hiddenalts.bypass";

    private static volatile Set<UUID> hidden = Set.of();

    private HiddenAlts() {
    }

    static void load(java.util.List<String> uuids, java.util.logging.Logger log) {
        hidden = uuids.stream().map(s -> {
            try {
                return UUID.fromString(s.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warning("[staffmode] hidden-alts: '" + s + "' is not a UUID; ignored");
                return null;
            }
        }).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    /** True when this account is on the hidden list. */
    public static boolean isHidden(UUID player) {
        return hidden.contains(player);
    }

    /**
     * True when {@code viewer} may see the real alt result for {@code player}; false means show the plain result
     * (as if the player had no other accounts, and leave the player out of other players' alt lists).
     */
    public static boolean showReal(CommandSender viewer, UUID player) {
        return !hidden.contains(player) || viewer.hasPermission(BYPASS);
    }
}
