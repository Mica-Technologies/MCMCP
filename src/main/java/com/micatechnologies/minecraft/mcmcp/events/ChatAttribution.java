package com.micatechnologies.minecraft.mcmcp.events;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Who said a chat line a client received, when that can be told (#201).
 *
 * <p>A server sends player chat as a translation with the sender as its first argument, and that
 * is exact. Servers with a chat-format plugin send plain text instead ({@code [Mod] Angel: hi},
 * {@code <Angel> hi}, {@code Angel » hi}), and the only check on a name read out of that is whether
 * a player of that name is in the tab list: {@code Server: restarting in 5} names nobody online.
 * Without a match the line is still an event, with no player and its whole text.
 */
public final class ChatAttribution {

    private static final String NAME = "([A-Za-z0-9_]{1,16})";

    /** {@code <Name> text}, after any {@code [rank]} prefixes. */
    private static final Pattern ANGLED = Pattern.compile("^(?:\\[[^\\]]{0,32}\\]\\s*)*<" + NAME + "> ?(.*)$",
        Pattern.DOTALL);

    /** {@code Name: text}, {@code Name » text}, {@code Name > text}, after any {@code [rank]} prefixes. */
    private static final Pattern SEPARATED = Pattern.compile(
        "^(?:\\[[^\\]]{0,32}\\]\\s*)*(?:[^\\sA-Za-z0-9_]\\s*)?" + NAME + "\\s*(?::|»|>>|>)\\s?(.*)$", Pattern.DOTALL);

    /** The sender and what they said. */
    public static final class Line {

        @Nullable
        public final String player;
        public final String text;

        Line(@Nullable String player, String text) {
            this.player = player;
            this.text = text;
        }
    }

    private ChatAttribution() {
    }

    /**
     * @param translationKey the line's translation key, or null for plain text
     * @param arguments      the translation's arguments as text
     * @param text           the whole line, unformatted
     * @param online         whether a name is a player in the tab list
     */
    public static Line attribute(@Nullable String translationKey, String[] arguments, String text,
        Predicate<String> online) {
        if (translationKey != null && arguments.length >= 2) {
            switch (translationKey) {
                case "chat.type.text":
                case "chat.type.emote":
                case "commands.message.display.incoming":
                    return new Line(arguments[0], arguments[1]);
                default:
                    break;
            }
        }
        String plain = stripCodes(text);
        for (Pattern pattern : new Pattern[] {ANGLED, SEPARATED}) {
            Matcher matcher = pattern.matcher(plain);
            if (matcher.matches() && online.test(matcher.group(1))) {
                return new Line(matcher.group(1), matcher.group(2));
            }
        }
        return new Line(null, plain);
    }

    /** Legacy {@code §} codes, which plugins still put in plain text. */
    static String stripCodes(String text) {
        return text.replaceAll("§[0-9A-Fa-fK-Ok-oRr]", "");
    }

    /** Whether a tab-list name looks like a player's. Tab plugins add filler entries that do not. */
    public static boolean isPlayerName(@Nullable String name) {
        return name != null && name.matches(NAME);
    }
}
