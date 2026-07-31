package com.micatechnologies.minecraft.mcmcp.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Matching and variable extraction for the RFC 6570 URI templates MCP uses to describe resource
 * families, e.g. {@code minecraft://world/chunk/{x}/{z}}.
 *
 * <p>Deliberately a small subset of RFC 6570: simple string expansion ({@code {name}}) only, with
 * one variable per path segment. The full spec includes operators for path, query, fragment and
 * list expansion, none of which MCMCP's resource URIs use, and implementing them would be a few
 * hundred lines serving no caller. If a future resource genuinely needs {@code {?query,params}},
 * that is the moment to reach for a real implementation, not before.
 *
 * <p>Variables never match across a {@code /}, so {@code minecraft://world/chunk/{x}/{z}} does not
 * match {@code minecraft://world/chunk/1/2/3}. That containment is what keeps templates from
 * swallowing sibling resources.
 */
public final class UriTemplates {

    private UriTemplates() {
    }

    public static boolean matches(@Nullable String template, @Nullable String uri) {
        return extract(template, uri) != null;
    }

    /**
     * Extracts template variables from {@code uri}.
     *
     * @return the variable bindings, or null when the URI does not match the template. An empty map
     *         is a successful match of a template with no variables, which is why "no match" is
     *         null rather than empty.
     */
    @Nullable
    public static Map<String, String> extract(@Nullable String template, @Nullable String uri) {
        if (template == null || uri == null) {
            return null;
        }

        Map<String, String> variables = new LinkedHashMap<>();
        int templateIndex = 0;
        int uriIndex = 0;

        while (templateIndex < template.length()) {
            char templateChar = template.charAt(templateIndex);

            if (templateChar != '{') {
                if (uriIndex >= uri.length() || uri.charAt(uriIndex) != templateChar) {
                    return null;
                }
                templateIndex++;
                uriIndex++;
                continue;
            }

            int closingBrace = template.indexOf('}', templateIndex);
            if (closingBrace < 0) {
                // A template with an unbalanced brace is a registration bug. Failing the match
                // rather than throwing keeps one malformed resource from breaking resolution for
                // every other one; the registering mod sees its resource simply never resolve.
                return null;
            }
            String variableName = template.substring(templateIndex + 1, closingBrace);
            templateIndex = closingBrace + 1;

            // The variable runs until the next literal character from the template, or the end of
            // the URI, and never past a '/'.
            char terminator = templateIndex < template.length() ? template.charAt(templateIndex) : '\0';
            int valueStart = uriIndex;
            while (uriIndex < uri.length()
                && uri.charAt(uriIndex) != '/'
                && (terminator == '\0' || uri.charAt(uriIndex) != terminator)) {
                uriIndex++;
            }
            if (uriIndex == valueStart) {
                // Empty bindings are rejected: matching {x} against nothing would make
                // "minecraft://world/chunk//5" a legal chunk reference.
                return null;
            }
            variables.put(variableName, uri.substring(valueStart, uriIndex));
        }

        return uriIndex == uri.length() ? Collections.unmodifiableMap(variables) : null;
    }
}
