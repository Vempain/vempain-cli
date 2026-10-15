package fi.poltsi.vempain.cli;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.cli.core.BackendVersionValidator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendVersionValidatorUTC {

    @Test
    void acceptsMatchingVersion() {
        assertDoesNotThrow(() -> BackendVersionValidator.validate(new JSONObject().put("version", "0.0.1-SNAPSHOT")));
    }

    @Test
    void rejectsDifferentVersion() {
        assertThrows(IllegalStateException.class, () -> BackendVersionValidator.validate(new JSONObject().put("version", "0.0.2")));
    }

    @Test
    void rejectsResponseWithoutVersion() {
        var exception = assertThrows(IllegalStateException.class, () -> BackendVersionValidator.validate(new JSONObject().put("service", "test")));

        assertEquals("Backend version response did not include a version", exception.getMessage());
    }
}
