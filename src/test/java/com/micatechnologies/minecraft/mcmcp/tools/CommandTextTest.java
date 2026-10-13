package com.micatechnologies.minecraft.mcmcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import org.junit.jupiter.api.Test;

class CommandTextTest {

    @Test
    void a_mod_key_the_server_cannot_translate_keeps_its_arguments() {
        // The shape of /storm list's reply on a dedicated server (#67), which came back as the bare key.
        TextComponentTranslation entry = new TextComponentTranslation("command.storm.list.entry",
            "Tropical Depression Julian", "StormObject", 3, 3, 3156, 2406, 1000, " (dying)");

        assertEquals("command.storm.list.entry(Tropical Depression Julian, StormObject, 3, 3, 3156, 2406, "
            + "1000,  (dying))", CommandText.plain(entry));
    }

    @Test
    void an_untranslatable_key_with_no_arguments_is_just_the_key() {
        assertEquals("command.storm.list.header",
            CommandText.plain(new TextComponentTranslation("command.storm.list.header")));
    }

    @Test
    void untranslatable_keys_nested_as_arguments_and_siblings_are_rendered_too() {
        TextComponentTranslation outer = new TextComponentTranslation("mod.outer",
            new TextComponentTranslation("mod.inner", "x"), 7);
        outer.appendSibling(new TextComponentString(" tail"));

        assertEquals("mod.outer(mod.inner(x), 7) tail", CommandText.plain(outer));
    }

    @Test
    void a_vanilla_key_still_reads_as_the_server_translates_it() {
        TextComponentTranslation vanilla = new TextComponentTranslation("commands.generic.notFound");

        assertEquals(vanilla.getUnformattedText(), CommandText.plain(vanilla));
    }

    @Test
    void plain_text_is_unchanged() {
        TextComponentString text = new TextComponentString("Teleported");
        text.appendSibling(new TextComponentString(" McmcpDev"));

        assertEquals("Teleported McmcpDev", CommandText.plain(text));
    }
}
