package com.micatechnologies.minecraft.mcmcp.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.micatechnologies.minecraft.mcmcp.json.Json;
import com.micatechnologies.minecraft.mcmcp.regions.BuildTarget;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildProgramTest {

    private static final BuildProgram.Components NONE = name -> null;

    private static List<String> expand(String spec) {
        return BuildProgram.expand(Json.parse(spec).getAsJsonObject(), NONE);
    }

    @Test
    void aBoxIsOneFillRelativeToTheOriginWithItsPaletteBlock() {
        assertEquals(Arrays.asList("fill 100 64 200 104 66 202 csm:brick"),
            expand("{\"origin\":[100,64,200],\"palette\":{\"W\":\"csm:brick\"},\"parts\":[{\"box\":[0,0,0,4,2,2],\"fill\":\"W\"}]}"));
    }

    @Test
    void aPaletteBlockMayCarryMetaOrState() {
        assertEquals(Arrays.asList("setblock 0 0 0 minecraft:stone_slab 8", "setblock 1 0 0 minecraft:oak_stairs facing=east,half=top"),
            expand("{\"origin\":[0,0,0],\"palette\":{\"D\":\"minecraft:stone_slab:8\",\"S\":\"minecraft:oak_stairs[facing=east,half=top]\"},"
                + "\"parts\":[{\"box\":[0,0,0,0,0,0],\"fill\":\"D\"},{\"box\":[1,0,0,1,0,0],\"fill\":\"S\"}]}"));
    }

    @Test
    void aShellIsClearedFourWallsAFloorAndFloorsEveryN() {
        List<String> ops = expand("{\"origin\":[0,0,0],\"parts\":[{\"shell\":[0,0,0,10,12,6],\"wall\":\"csm:brick\","
            + "\"floor\":\"minecraft:stone\",\"floorsEvery\":4,\"roof\":\"minecraft:stone\"}]}");
        // clear, 4 walls, base floor, floors at y4 and y8, roof
        assertEquals(9, ops.size());
        assertEquals("fill 1 0 1 9 12 5 minecraft:air", ops.get(0));
        assertTrue(ops.contains("fill 1 4 1 9 4 5 minecraft:stone"));
        assertTrue(ops.contains("fill 1 8 1 9 8 5 minecraft:stone"));
        assertEquals("fill 0 12 0 10 12 6 minecraft:stone", ops.get(8));
    }

    @Test
    void aRepeatStepsAPartAndAMirrorAddsItsReflection() {
        assertEquals(Arrays.asList("fill 2 1 0 3 3 0 minecraft:glass", "fill 6 1 0 7 3 0 minecraft:glass",
                "fill 10 1 0 11 3 0 minecraft:glass"),
            expand("{\"origin\":[0,0,0],\"parts\":[{\"repeat\":{\"box\":[2,1,0,3,3,0],\"fill\":\"minecraft:glass\"},"
                + "\"step\":[4,0,0],\"count\":3}]}"));
        assertEquals(Arrays.asList("setblock 1 0 0 minecraft:stone", "setblock 9 0 0 minecraft:stone"),
            expand("{\"origin\":[0,0,0],\"parts\":[{\"mirror\":{\"box\":[1,0,0,1,0,0],\"fill\":\"minecraft:stone\"},"
                + "\"axis\":\"x\",\"about\":5}]}"));
    }

    @Test
    void aPlanMergesRunsOfACharacterIntoFillsAndSkipsDots() {
        List<String> ops = expand("{\"origin\":[0,64,0],\"palette\":{\"W\":\"csm:brick\",\"G\":\"minecraft:glass\"},"
            + "\"parts\":[{\"plan\":[\"WWGG.W\"],\"height\":3}]}");
        assertEquals(Arrays.asList("fill 0 64 0 1 66 0 csm:brick", "fill 2 64 0 3 66 0 minecraft:glass",
            "fill 5 64 0 5 66 0 csm:brick"), ops);
    }

    @Test
    void aFacadePlanRunsTopToBottom() {
        List<String> ops = expand("{\"origin\":[0,0,0],\"palette\":{\"W\":\"csm:brick\"},"
            + "\"parts\":[{\"plan\":[\"WW\",\"W.\"],\"axis\":\"xy\",\"at\":[0,10,5]}]}");
        assertEquals(Arrays.asList("fill 0 10 5 1 10 5 csm:brick", "setblock 0 9 5 csm:brick"), ops);
    }

    @Test
    void aStampPlacesASavedComponentAtItsSmallestCorner() {
        final BuildTarget window = new BuildTarget(0);
        window.put(50, 70, 50, "minecraft:glass");
        window.put(51, 70, 50, "minecraft:glass");
        List<String> ops = BuildProgram.expand(Json.parse("{\"origin\":[0,64,0],\"parts\":[{\"stamp\":\"components/w\","
            + "\"at\":[2,1,0]}]}").getAsJsonObject(), name -> "components/w".equals(name) ? window : null);
        assertEquals(Arrays.asList("setblock 2 65 0 minecraft:glass", "setblock 3 65 0 minecraft:glass"), ops);
    }

    @Test
    void aBadSpecSaysWhereItIsWrong() {
        BuildProgram.SpecException e = assertThrows(BuildProgram.SpecException.class,
            () -> expand("{\"origin\":[0,0,0],\"parts\":[{\"box\":[0,0,0,1,1,1],\"fill\":\"X\"}]}"));
        assertTrue(e.getMessage().startsWith("parts[0]: 'X' is not in the palette"), e.getMessage());
        assertThrows(BuildProgram.SpecException.class, () -> expand("{\"parts\":[]}"));
    }

    @Test
    void aSpecTooBigForOneCallSaysToUseAJob() {
        JsonObject spec = Json.parse("{\"origin\":[0,0,0],\"parts\":[{\"repeat\":{\"box\":[0,0,0,0,0,0],"
            + "\"fill\":\"minecraft:stone\"},\"step\":[1,0,0],\"count\":10000},{\"repeat\":{\"repeat\":{\"box\":[0,0,0,0,0,0],"
            + "\"fill\":\"minecraft:stone\"},\"step\":[1,0,0],\"count\":10000},\"step\":[0,1,0],\"count\":6}]}").getAsJsonObject();
        BuildProgram.SpecException e = assertThrows(BuildProgram.SpecException.class, () -> BuildProgram.expand(spec, NONE));
        assertTrue(e.getMessage().contains("kind 'build'"), e.getMessage());
    }
}
