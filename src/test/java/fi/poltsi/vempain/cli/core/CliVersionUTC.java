package fi.poltsi.vempain.cli.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliVersionUTC {

    @Test
    void orDevelopment_fallsBackWhenTheManifestHasNoVersion() {
        assertEquals("0.0.1-SNAPSHOT", CliVersion.orDevelopment(null));
        assertEquals("0.0.1-SNAPSHOT", CliVersion.orDevelopment("  "));
        assertEquals("1.2.3", CliVersion.orDevelopment("1.2.3"));
    }

    @Test
    void current_readsTheImplementationVersionOfThePackage() {
        assertEquals(CliVersion.orDevelopment(CliVersion.class.getPackage()
            .getImplementationVersion()), CliVersion.current());
    }
}
