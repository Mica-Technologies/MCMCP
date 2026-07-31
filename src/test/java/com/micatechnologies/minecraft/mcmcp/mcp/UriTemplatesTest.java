package com.micatechnologies.minecraft.mcmcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * URI template matching for resource families.
 *
 * <p>The containment rules are the interesting part: a variable must not match across a {@code /},
 * and must not match nothing. Both failures let a template swallow URIs that belong to a different
 * resource, which presents as one resource mysteriously answering another's reads.
 */
class UriTemplatesTest {

    @Test
    void extractsASingleVariable() {
        Map<String, String> variables = UriTemplates.extract(
            "minecraft://client/screenshot/{name}",
            "minecraft://client/screenshot/mcmcp-1234.png");
        assertNotNull(variables);
        assertEquals("mcmcp-1234.png", variables.get("name"));
    }

    @Test
    void extractsSeveralVariables() {
        Map<String, String> variables = UriTemplates.extract(
            "minecraft://world/chunk/{x}/{z}",
            "minecraft://world/chunk/12/-4");
        assertNotNull(variables);
        assertEquals("12", variables.get("x"));
        assertEquals("-4", variables.get("z"));
    }

    /** A variable stops at a path separator, so a deeper URI is not a match. */
    @Test
    void variablesDoNotMatchAcrossSlashes() {
        assertFalse(UriTemplates.matches("minecraft://world/chunk/{x}/{z}",
            "minecraft://world/chunk/1/2/3"));
        assertFalse(UriTemplates.matches("minecraft://client/screenshot/{name}",
            "minecraft://client/screenshot/nested/shot.png"));
    }

    /**
     * An empty binding is not a match.
     *
     * <p>Without this, {@code minecraft://world/chunk//5} would parse as a chunk at an empty x —
     * a URI no caller meant to write, resolving to a resource that then fails confusingly.
     */
    @Test
    void rejectsEmptyVariableBindings() {
        assertNull(UriTemplates.extract("minecraft://world/chunk/{x}/{z}",
            "minecraft://world/chunk//5"));
        assertNull(UriTemplates.extract("minecraft://client/screenshot/{name}",
            "minecraft://client/screenshot/"));
    }

    @Test
    void requiresTheWholeUriToBeConsumed() {
        assertFalse(UriTemplates.matches("minecraft://client/screenshot/{name}",
            "minecraft://client/screenshot/a.png/extra"));
        assertFalse(UriTemplates.matches("minecraft://client/screenshot/{name}",
            "minecraft://client/screenshot"));
    }

    @Test
    void matchesTemplatesWithNoVariables() {
        Map<String, String> variables =
            UriTemplates.extract("minecraft://server/status", "minecraft://server/status");
        assertNotNull(variables, "an exact match with no variables is still a match");
        assertTrue(variables.isEmpty());
    }

    @Test
    void rejectsMismatchedLiterals() {
        assertFalse(UriTemplates.matches("minecraft://server/status", "minecraft://server/players"));
    }

    /** A malformed template fails to match rather than throwing, so one bad registration is contained. */
    @Test
    void unbalancedBracesFailToMatchInsteadOfThrowing() {
        assertNull(UriTemplates.extract("minecraft://broken/{name", "minecraft://broken/value"));
    }

    @Test
    void nullInputsAreNotAMatch() {
        assertNull(UriTemplates.extract(null, "minecraft://server/status"));
        assertNull(UriTemplates.extract("minecraft://server/status", null));
    }
}
