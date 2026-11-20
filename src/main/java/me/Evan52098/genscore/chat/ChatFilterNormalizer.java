package me.Evan52098.genscore.chat;

// Copied unchanged from BlockvilleCore (bvcore/filter/ChatFilterNormalizer), only the package and Lombok annotations
// changed. Pure text handling, so nothing here needed Folia changes.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ChatFilterNormalizer {
    private static final Map<Character, Character> LEET = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('9', 'g'), Map.entry('@', 'a'), Map.entry('$', 's'),
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('у', 'y'), Map.entry('х', 'x'), Map.entry('і', 'i'),
            Map.entry('ѕ', 's'), Map.entry('ј', 'j'), Map.entry('к', 'k'), Map.entry('һ', 'h'),
            Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ρ', 'p'), Map.entry('τ', 't'),
            Map.entry('υ', 'u'), Map.entry('ν', 'v'), Map.entry('κ', 'k'), Map.entry('ι', 'i'),
            Map.entry('χ', 'x'), Map.entry('ς', 's'),
            Map.entry('¡', 'i'), Map.entry('×', 'x'), Map.entry('þ', 'p'), Map.entry('ı', 'i'), Map.entry('ſ', 'f'), Map.entry('Ƅ', 'b'),
            Map.entry('ƍ', 'g'), Map.entry('ƒ', 'f'), Map.entry('Ɩ', 'l'), Map.entry('Ƨ', '2'), Map.entry('Ʒ', '3'), Map.entry('Ƽ', '5'),
            Map.entry('ƽ', 's'), Map.entry('ƿ', 'p'), Map.entry('ǀ', 'l'), Map.entry('Ȝ', '3'), Map.entry('Ȣ', '8'), Map.entry('ȣ', '8'),
            Map.entry('ȷ', 'j'), Map.entry('ɑ', 'a'), Map.entry('ɡ', 'g'), Map.entry('ɣ', 'y'), Map.entry('ɩ', 'i'), Map.entry('ɪ', 'i'),
            Map.entry('ɯ', 'w'), Map.entry('ʄ', 'f'), Map.entry('ʋ', 'u'), Map.entry('ʏ', 'y'), Map.entry('Ι', 'l'), Map.entry('γ', 'y'),
            Map.entry('σ', 'o'), Map.entry('Ϩ', '2'), Map.entry('Ϭ', '6'), Map.entry('ϭ', 'o'), Map.entry('ϱ', 'p'), Map.entry('ϲ', 'c'),
            Map.entry('ϳ', 'j'), Map.entry('ϸ', 'p'), Map.entry('І', 'l'), Map.entry('З', '3'), Map.entry('Ь', 'b'), Map.entry('б', '6'),
            Map.entry('г', 'r'), Map.entry('ш', 'w'), Map.entry('ѡ', 'w'), Map.entry('ѵ', 'v'), Map.entry('ү', 'y'), Map.entry('Һ', 'h'),
            Map.entry('ҽ', 'e'), Map.entry('Ӏ', 'l'), Map.entry('ӏ', 'l'), Map.entry('Ӡ', '3'), Map.entry('ԁ', 'd'), Map.entry('ԛ', 'q'),
            Map.entry('ԝ', 'w'), Map.entry('Յ', '3'), Map.entry('ա', 'w'), Map.entry('գ', 'q'), Map.entry('զ', 'q'), Map.entry('հ', 'h'),
            Map.entry('յ', 'j'), Map.entry('ո', 'n'), Map.entry('ռ', 'n'), Map.entry('ս', 'u'), Map.entry('ց', 'g'), Map.entry('ւ', 'i'),
            Map.entry('ք', 'f'), Map.entry('օ', 'o'), Map.entry('׀', 'l'), Map.entry('ו', 'l'), Map.entry('ט', 'v'), Map.entry('ן', 'l'),
            Map.entry('ס', 'o'), Map.entry('ا', 'l'), Map.entry('ه', 'o'), Map.entry('١', 'l'), Map.entry('٥', 'o'), Map.entry('ھ', 'o'),
            Map.entry('ہ', 'o'), Map.entry('ە', 'o'), Map.entry('۱', 'l'), Map.entry('۵', 'o'), Map.entry('ߊ', 'l'), Map.entry('ߋ', 'o'),
            Map.entry('ߕ', 'b'), Map.entry('ࡀ', 'o'), Map.entry('।', 'l'), Map.entry('०', 'o'), Map.entry('३', '3'), Map.entry('০', 'o'),
            Map.entry('৪', '8'), Map.entry('৭', '9'), Map.entry('੦', 'o'), Map.entry('੧', '9'), Map.entry('੪', '8'), Map.entry('૦', 'o'),
            Map.entry('૩', '3'), Map.entry('ଃ', '8'), Map.entry('୦', 'o'), Map.entry('୨', '9'), Map.entry('௦', 'o'), Map.entry('ం', 'o'),
            Map.entry('౦', 'o'), Map.entry('ಂ', 'o'), Map.entry('ം', 'o'), Map.entry('ട', 's'), Map.entry('ഠ', 'o'), Map.entry('൦', 'o'),
            Map.entry('൭', '9'), Map.entry('ං', 'o'), Map.entry('๐', 'o'), Map.entry('໐', 'o'), Map.entry('င', 'c'), Map.entry('ဝ', 'o'),
            Map.entry('၀', 'o'), Map.entry('၊', 'l'), Map.entry('ၚ', 'c'), Map.entry('Ⴙ', 'h'), Map.entry('Ⴧ', '2'), Map.entry('ყ', 'y'),
            Map.entry('ჿ', 'o'), Map.entry('ᄋ', 'o'), Map.entry('ᆼ', 'o'), Map.entry('Ꭵ', 'i'), Map.entry('Ꮒ', 'h'), Map.entry('Ꮞ', '4'),
            Map.entry('Ꮟ', 'b'), Map.entry('Ꮷ', 'd'), Map.entry('Ꮾ', '6'), Map.entry('ᑯ', 'd'), Map.entry('ᑲ', 'b'), Map.entry('ᒿ', '2'),
            Map.entry('ᕁ', 'x'), Map.entry('ᕽ', 'x'), Map.entry('ᖯ', 'b'), Map.entry('ᘖ', '2'), Map.entry('᙮', 'x'), Map.entry('ᛁ', 'l'),
            Map.entry('ᛐ', 'l'), Map.entry('ᜂ', '3'), Map.entry('ᜪ', '7'), Map.entry('ᝣ', 'x'), Map.entry('០', 'o'), Map.entry('ᩅ', 'o'),
            Map.entry('᪀', 'o'), Map.entry('᪐', 'o'), Map.entry('ᯪ', 'o'), Map.entry('ᯬ', 'x'), Map.entry('ᲂ', 'o'), Map.entry('ᲃ', 'c'),
            Map.entry('ᲄ', '7'), Map.entry('Ვ', '3'), Map.entry('Ჷ', '2'), Map.entry('ᴄ', 'c'), Map.entry('ᴏ', 'o'), Map.entry('ᴑ', 'o'),
            Map.entry('ᴜ', 'u'), Map.entry('ᴠ', 'v'), Map.entry('ᴡ', 'w'), Map.entry('ᴢ', 'z'), Map.entry('ᴦ', 'r'), Map.entry('ᶃ', 'g'),
            Map.entry('ᶌ', 'y'), Map.entry('ẝ', 'f'), Map.entry('ỿ', 'y'), Map.entry('ℊ', 'g'), Map.entry('ℎ', 'h'), Map.entry('ℐ', 'l'),
            Map.entry('ℑ', 'l'), Map.entry('ℓ', 'l'), Map.entry('℮', 'e'), Map.entry('ℯ', 'e'), Map.entry('ℴ', 'o'), Map.entry('ℹ', 'i'),
            Map.entry('ℽ', 'y'), Map.entry('ⅆ', 'd'), Map.entry('ⅇ', 'e'), Map.entry('ⅈ', 'i'), Map.entry('ⅉ', 'j'), Map.entry('Ⅰ', 'l'),
            Map.entry('ⅰ', 'i'), Map.entry('ⅴ', 'v'), Map.entry('ⅹ', 'x'), Map.entry('ⅼ', 'l'), Map.entry('ⅽ', 'c'), Map.entry('ⅾ', 'd'),
            Map.entry('↿', 'l'), Map.entry('∣', 'l'), Map.entry('∨', 'v'), Map.entry('⋁', 'v'), Map.entry('⍳', 'i'), Map.entry('⍴', 'p'),
            Map.entry('⍺', 'a'), Map.entry('⏽', 'l'), Map.entry('│', 'l'), Map.entry('┃', 'l'), Map.entry('⤫', 'x'), Map.entry('⤬', 'x'),
            Map.entry('⧢', 'w'), Map.entry('⨯', 'x'), Map.entry('ⱬ', 'z'), Map.entry('ⲅ', 'r'), Map.entry('ⲍ', 'z'), Map.entry('Ⲓ', 'l'),
            Map.entry('ⲓ', 'i'), Map.entry('Ⲝ', '3'), Map.entry('ⲟ', 'o'), Map.entry('ⲣ', 'p'), Map.entry('ⲥ', 'c'), Map.entry('ⲩ', 'y'),
            Map.entry('ⲽ', 'w'), Map.entry('Ⳅ', '3'), Map.entry('Ⳋ', '9'), Map.entry('ⳋ', '9'), Map.entry('Ⳍ', '3'), Map.entry('ⳏ', 'p'),
            Map.entry('Ⳓ', '6'), Map.entry('ⳓ', '6'), Map.entry('Ⳝ', '6'), Map.entry('ⴭ', 'z'), Map.entry('ⵊ', 'l'), Map.entry('ⵏ', 'l'),
            Map.entry('ㅇ', 'o'), Map.entry('ꓒ', 'd'), Map.entry('ꓲ', 'l'), Map.entry('ꕃ', '6'), Map.entry('ꕯ', 'l'), Map.entry('ꖉ', '8'),
            Map.entry('Ꙅ', '2'), Map.entry('ꙇ', 'i'), Map.entry('ꛯ', '2'), Map.entry('ꜱ', 's'), Map.entry('Ꝛ', '2'), Map.entry('Ꝫ', '3'),
            Map.entry('Ꝯ', '9'), Map.entry('ꝯ', '9'), Map.entry('ꞁ', 'l'), Map.entry('ꞙ', 'f'), Map.entry('ꞟ', 'u'), Map.entry('Ɜ', '3'),
            Map.entry('Ɪ', 'l'), Map.entry('ꟺ', 'w'), Map.entry('ꟾ', 'l'), Map.entry('꠰', 'l'), Map.entry('꣎', 'l'), Map.entry('꩝', 'l'),
            Map.entry('ꬲ', 'e'), Map.entry('ꬵ', 'f'), Map.entry('ꬽ', 'o'), Map.entry('ꭇ', 'r'), Map.entry('ꭈ', 'r'), Map.entry('ꭎ', 'u'),
            Map.entry('ꭒ', 'u'), Map.entry('ꭚ', 'y'), Map.entry('ꭤ', 'a'), Map.entry('ꭵ', 'i'), Map.entry('ꮁ', 'r'), Map.entry('ꮃ', 'w'),
            Map.entry('ꮓ', 'z'), Map.entry('ꮞ', '4'), Map.entry('ꮤ', 'w'), Map.entry('ꮩ', 'v'), Map.entry('ꮪ', 's'), Map.entry('ꮯ', 'c'),
            Map.entry('ꮾ', '6'), Map.entry('ﮦ', 'o'), Map.entry('ﮧ', 'o'), Map.entry('ﮨ', 'o'), Map.entry('ﮩ', 'o'), Map.entry('ﮪ', 'o'),
            Map.entry('ﮫ', 'o'), Map.entry('ﮬ', 'o'), Map.entry('ﮭ', 'o'), Map.entry('︱', 'l'), Map.entry('ﺍ', 'l'), Map.entry('ﺎ', 'l'),
            Map.entry('ﻩ', 'o'), Map.entry('ﻪ', 'o'), Map.entry('ﻫ', 'o'), Map.entry('ﻬ', 'o'), Map.entry('ﾷ', 'o'), Map.entry('￨', 'l'));
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");
    private static final Pattern BANG_AS_I = Pattern.compile("(?<=[a-z0-9])!(?=[a-z0-9])");
    private static final Pattern STRETCHED = Pattern.compile("(.)\\1+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final char FULLWIDTH_FIRST = '！';
    private static final char FULLWIDTH_LAST = '～';
    private static final int FULLWIDTH_OFFSET = 0xFEE0;

    private ChatFilterNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            throw new NullPointerException("input is marked non-null but is null");
        }
        return STRETCHED.matcher(stripToAlnum(input)).replaceAll("$1");
    }

    public record Token(String display, String matchable) {
    }

    public static List<Token> tokenize(String input) {
        if (input == null) {
            throw new NullPointerException("input is marked non-null but is null");
        }
        List<String> words = new ArrayList<>();
        for (String rawWord : WHITESPACE.split(input.trim())) {
            String alnum = stripToAlnum(rawWord);
            if (!alnum.isEmpty()) {
                words.add(alnum);
            }
        }

        List<String> glued = new ArrayList<>(words.size());
        int i = 0;
        while (i < words.size()) {
            if (!isTiny(words.get(i))) {
                glued.add(words.get(i));
                i++;
                continue;
            }
            int end = i;
            int singles = 0;
            while (end < words.size() && isTiny(words.get(end))) {
                if (words.get(end).length() == 1) {
                    singles++;
                }
                end++;
            }
            if (end - i >= 3 && singles >= 2) {
                StringBuilder run = new StringBuilder();
                for (int k = i; k < end; k++) {
                    run.append(words.get(k));
                }
                glued.add(run.toString());
            } else {
                int k = i;
                while (k < end) {
                    if (words.get(k).length() == 1) {
                        StringBuilder run = new StringBuilder();
                        while (k < end && words.get(k).length() == 1) {
                            run.append(words.get(k));
                            k++;
                        }
                        glued.add(run.toString());
                    } else {
                        glued.add(words.get(k));
                        k++;
                    }
                }
            }
            i = end;
        }

        List<Token> tokens = new ArrayList<>(glued.size());
        for (String word : glued) {
            tokens.add(new Token(word, STRETCHED.matcher(word).replaceAll("$1")));
        }
        return tokens;
    }

    public static String strip(String input) {
        return stripToAlnum(input);
    }

    public static String collapse(String input) {
        return STRETCHED.matcher(input).replaceAll("$1");
    }

    private static boolean isTiny(String word) {
        return STRETCHED.matcher(word).replaceAll("$1").length() <= 2;
    }


    private static String stripToAlnum(String input) {
        StringBuilder leeted = new StringBuilder(input.length());
        for (char c : BANG_AS_I.matcher(input.toLowerCase(Locale.ROOT)).replaceAll("i").toCharArray()) {
            char folded = c >= FULLWIDTH_FIRST && c <= FULLWIDTH_LAST ? (char) (c - FULLWIDTH_OFFSET) : c;
            leeted.append(LEET.getOrDefault(folded, folded));
        }
        return NON_ALNUM.matcher(leeted).replaceAll("");
    }
}
