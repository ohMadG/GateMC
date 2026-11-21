package me.Evan52098.genscore.powers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('#').hexColors().build();

    private Text() {
    }

    static Component of(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy).decoration(TextDecoration.ITALIC, false);
    }

    static String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(of(legacy));
    }
}
