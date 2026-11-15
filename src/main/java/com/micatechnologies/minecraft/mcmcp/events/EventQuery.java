package com.micatechnologies.minecraft.mcmcp.events;

import com.google.gson.JsonObject;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.annotation.Nullable;

/**
 * An {@code /events} stream's query string as {@code game_events_wait} arguments, so the stream and
 * the tool read the same names the same way: {@code types=chat,join&contains=%40Claude&since=12}.
 */
public final class EventQuery {

    private EventQuery() {
    }

    /** Unknown names are kept; the reader ignores what it does not use. Never null. */
    public static JsonObject parse(@Nullable String rawQuery) {
        JsonObject arguments = new JsonObject();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return arguments;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if ("since".equals(name)) {
                try {
                    arguments.addProperty(name, Long.parseLong(value.trim()));
                }
                catch (NumberFormatException ignored) {
                    // A bad cursor is no cursor: wait for what happens from now on.
                }
                continue;
            }
            // A repeated name adds to the list: players=a&players=b is players=a,b.
            if (arguments.has(name) && !value.isEmpty()) {
                value = arguments.get(name).getAsString() + "," + value;
            }
            arguments.addProperty(name, value);
        }
        return arguments;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        }
        catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return text;
        }
    }
}
