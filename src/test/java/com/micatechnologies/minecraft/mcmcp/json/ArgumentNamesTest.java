package com.micatechnologies.minecraft.mcmcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Refusing arguments a tool's schema does not declare. */
class ArgumentNamesTest {

    private static final JsonObject WORLD_CREATE = JsonSchema.object()
        .string("name", "World name")
        .string("worldType", "Terrain type")
        .bool("cheats", "Allow cheats")
        .build();

    private static JsonObject arguments(String... namesAndValues) {
        JsonObject arguments = new JsonObject();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            arguments.addProperty(namesAndValues[i], namesAndValues[i + 1]);
        }
        return arguments;
    }

    @Test
    void aCallUsingOnlyDeclaredArgumentsIsAccepted() {
        assertNull(ArgumentNames.describeUnknown("client_world_create", WORLD_CREATE,
            arguments("name", "test", "worldType", "flat")));
    }

    @Test
    void aSnakeCaseSpellingOfACamelCaseArgumentIsRefusedWithTheRealName() {
        String message = ArgumentNames.describeUnknown("client_world_create", WORLD_CREATE,
            arguments("world_type", "flat"));

        assertTrue(message.contains("Unknown argument 'world_type' for client_world_create"), message);
        assertTrue(message.contains("did you mean 'worldType'?"), message);
        assertTrue(message.contains("Nothing was done."), message);
        assertTrue(message.contains("Valid arguments: name, worldType, cheats."), message);
    }

    @Test
    void aNearMissByEditDistanceIsSuggested() {
        assertEquals("cheats", ArgumentNames.closest("cheat", Arrays.asList("name", "worldType", "cheats")));
    }

    @Test
    void anArgumentLikeNothingDeclaredGetsNoSuggestion() {
        String message = ArgumentNames.describeUnknown("client_world_create", WORLD_CREATE,
            arguments("seedling_count", "4"));

        assertTrue(message.contains("Unknown argument 'seedling_count'"), message);
        assertTrue(!message.contains("did you mean"), message);
    }

    @Test
    void everyUnknownArgumentIsNamedNotOnlyTheFirst() {
        String message = ArgumentNames.describeUnknown("client_world_create", WORLD_CREATE,
            arguments("world_type", "flat", "Cheats", "true"));

        assertTrue(message.contains("'world_type'"), message);
        assertTrue(message.contains("'Cheats'"), message);
        assertTrue(message.contains("did you mean 'cheats'?"), message);
    }

    @Test
    void aToolThatTakesNoArgumentsSaysSo() {
        String message = ArgumentNames.describeUnknown("client_runtime_info", JsonSchema.noArguments(),
            arguments("verbose", "true"));

        assertTrue(message.contains("client_runtime_info takes no arguments."), message);
    }
}
