package com.micatechnologies.minecraft.mcmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Instance identity: how an id is derived, and what must never leak out of one. */
class McmcpIdentityTest {

    @Test
    void reducesADirectoryNameToAReadableSlug() {
        assertEquals("atm9", McmcpIdentity.slugify("ATM9"));
        assertEquals("modb-dev", McmcpIdentity.slugify("modB dev"));
        assertEquals("atm9-copy-2", McmcpIdentity.slugify("ATM9 - Copy (2)"));
    }

    @Test
    void collapsesRunsOfSeparatorsAndNeverLeavesOneDangling() {
        // Instance folders are named by people, and a trailing hyphen in an id that shows up in log
        // lines and tool errors reads like a truncation bug.
        assertEquals("a-b", McmcpIdentity.slugify("a___---   b"));
        assertEquals("atm9", McmcpIdentity.slugify("  ATM9  "));
        assertEquals("atm9", McmcpIdentity.slugify("...ATM9..."));
    }

    @Test
    void fallsBackWhenADirectoryNameLeavesNothingUsable() {
        // An all-punctuation folder, or a name in a script that leaves no ASCII behind. Producing
        // an empty or hyphen-only id would be worse than a generic one.
        assertEquals("instance", McmcpIdentity.slugify("!!!"));
        assertEquals("instance", McmcpIdentity.slugify(""));
        assertEquals("instance", McmcpIdentity.slugify(null));
        assertEquals("instance", McmcpIdentity.slugify("日本語"));
    }

    @Test
    void keepsAnIdShortEnoughToReadInALogLine() {
        String slug = McmcpIdentity.slugify(
            "All The Mods 9 - Copy (2) - testing the inventory rewrite branch");

        assertTrue(slug.length() <= 24, "got a " + slug.length() + " character slug: " + slug);
        assertFalse(slug.endsWith("-"), "truncation must not leave a dangling hyphen: " + slug);
    }

    @Test
    void givesTwoCopiesOfTheSameFolderDifferentIds() {
        // Copying an instance folder is how people make a test variant of a pack. Two instances
        // both calling themselves 'atm9' would collide in an orchestrator's approval store, and
        // the consequence is one inheriting the other's approval.
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(McmcpIdentity.generateInstanceId("ATM9"));
        }

        assertEquals(50, ids.size(), "generated ids collided");
        for (String id : ids) {
            assertTrue(id.startsWith("atm9-"), "the folder name should still be recognisable: " + id);
        }
    }

    @Test
    void generatesASecretLongEnoughToAuthenticateAControlChannel() {
        String secret = McmcpIdentity.generateSecret();

        assertEquals(64, secret.length(), "256 bits, hex encoded");
        assertTrue(secret.matches("[0-9a-f]+"));
        assertNotEquals(secret, McmcpIdentity.generateSecret());
    }

    @Test
    void neverPutsTheSecretInItsOwnStringForm() {
        // This object ends up in log lines and exception messages. A toString that included the
        // secret would leak it into latest.log, which people paste into bug reports.
        McmcpIdentity identity = new McmcpIdentity("atm9-3f2a1c", "0123456789abcdef", "ATM9");

        String shown = identity.toString();
        assertFalse(shown.contains("0123456789abcdef"));
        assertTrue(shown.contains("atm9-3f2a1c"));
        assertTrue(shown.contains("ATM9"));
    }

    @Test
    void knowsWhetherItHasASecretYet() {
        assertTrue(new McmcpIdentity("a", "secret", "A").hasSecret());
        assertFalse(new McmcpIdentity("a", "", "A").hasSecret());
        assertFalse(new McmcpIdentity("a", null, "A").hasSecret());
    }
}
