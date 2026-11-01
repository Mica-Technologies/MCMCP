package com.micatechnologies.minecraft.mcmcp.tools;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.translation.I18n;

/**
 * A command's reply as plain text, keeping what a translation key would have said even when this
 * side cannot translate it (#67).
 *
 * <p>{@code getUnformattedText} renders a {@link TextComponentTranslation} through the server's
 * {@code LanguageMap}. A dedicated server knows vanilla's keys and often not a mod's, and an
 * untranslated key renders as the bare key with its arguments dropped: {@code /storm list} came back
 * as thirty copies of {@code command.storm.list.entry}, the storm names and coordinates gone. Here an
 * untranslatable key renders as {@code key(arg, arg, ...)} instead, its arguments rendered the same
 * way, so the information survives even though the wording does not.
 *
 * <p>A component with nothing untranslatable in it renders exactly as {@code getUnformattedText}
 * does, so vanilla output is unchanged.
 */
public final class CommandText {

    private CommandText() {
    }

    /** The component's text, with untranslatable keys shown as {@code key(args)}. */
    public static String plain(ITextComponent component) {
        return hasUntranslatable(component) ? render(component) : component.getUnformattedText();
    }

    private static boolean hasUntranslatable(ITextComponent component) {
        if (component instanceof TextComponentTranslation) {
            TextComponentTranslation translation = (TextComponentTranslation) component;
            if (!I18n.canTranslate(translation.getKey())) {
                return true;
            }
            for (Object argument : translation.getFormatArgs()) {
                if (argument instanceof ITextComponent && hasUntranslatable((ITextComponent) argument)) {
                    return true;
                }
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            if (hasUntranslatable(sibling)) {
                return true;
            }
        }
        return false;
    }

    /** One component and its siblings, as text. */
    private static String render(ITextComponent component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof TextComponentTranslation) {
            TextComponentTranslation translation = (TextComponentTranslation) component;
            Object[] arguments = translation.getFormatArgs();
            String[] rendered = new String[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                rendered[i] = arguments[i] instanceof ITextComponent ? render((ITextComponent) arguments[i])
                    : String.valueOf(arguments[i]);
            }
            if (I18n.canTranslate(translation.getKey())) {
                // Translated here rather than through the component, whose own rendering would take
                // the untranslatable arguments' bare keys.
                text.append(I18n.translateToLocalFormatted(translation.getKey(), (Object[]) rendered));
            }
            else {
                text.append(translation.getKey());
                if (rendered.length > 0) {
                    text.append('(').append(String.join(", ", rendered)).append(')');
                }
            }
        }
        else {
            text.append(component.getUnformattedComponentText());
        }
        for (ITextComponent sibling : component.getSiblings()) {
            text.append(render(sibling));
        }
        return text.toString();
    }

    /** Commands that exist to put a message in front of players, by their vanilla names. */
    private static final java.util.Set<String> MESSAGE_COMMANDS = new java.util.HashSet<>(
        java.util.Arrays.asList("tellraw", "say", "me", "msg", "tell", "w"));

    /**
     * Whether {@code command}'s chat should still reach the player it runs as (#183). Taking it all
     * was right for feedback such as "Set the time to 6000", but a tellraw an agent sent its own
     * player, or a /say, was taken too: the call said it succeeded and nobody saw the message. A
     * command's feedback and a message sent to its player take the same path, so it is told by the
     * command.
     */
    public static boolean deliversToPlayer(String command) {
        String word = command.trim();
        if (word.startsWith("/")) {
            word = word.substring(1);
        }
        int space = word.indexOf(' ');
        word = (space < 0 ? word : word.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
        int colon = word.indexOf(':');
        return MESSAGE_COMMANDS.contains(colon < 0 ? word : word.substring(colon + 1));
    }

}
