package com.micatechnologies.minecraft.mcmcp.tools;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The file-name guard in front of the registry dump.
 *
 * <p>The dump itself walks live Forge registries and is exercised by the server smoke test. What
 * can be checked here is the one thing that must never regress: a caller-supplied name cannot
 * place the file anywhere but the dump directory.</p>
 */
class RegistryDumpToolsTest {

    @Test
    @DisplayName("a bare .json file name is accepted")
    void bareJsonNameIsAccepted() {
        assertThat(RegistryDumpTools.validateFileName("registries-csm.json"), nullValue());
        assertThat(RegistryDumpTools.validateFileName("before.JSON"), nullValue());
    }

    @Test
    @DisplayName("a name with a path separator is rejected")
    void pathSeparatorsAreRejected() {
        assertThat(RegistryDumpTools.validateFileName("../config/mcmcp.json"),
            containsString("no path separators"));
        assertThat(RegistryDumpTools.validateFileName("sub/dump.json"),
            containsString("no path separators"));
        assertThat(RegistryDumpTools.validateFileName("sub\\dump.json"),
            containsString("no path separators"));
    }

    @Test
    @DisplayName("a name that is not a .json file is rejected")
    void nonJsonNamesAreRejected() {
        assertThat(RegistryDumpTools.validateFileName("dump.txt"), containsString(".json"));
        assertThat(RegistryDumpTools.validateFileName(""), containsString("empty"));
        assertThat(RegistryDumpTools.validateFileName(null), containsString("empty"));
    }
}
