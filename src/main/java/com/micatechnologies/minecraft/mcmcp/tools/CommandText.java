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
}
