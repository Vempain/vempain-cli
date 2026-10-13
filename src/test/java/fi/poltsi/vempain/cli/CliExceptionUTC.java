package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.core.CliException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CliExceptionUTC {

	@Test
	void carriesMessageAndOptionalCause() {
		var withoutCause = new CliException("plain failure");
		assertEquals("plain failure", withoutCause.getMessage());
		assertNull(withoutCause.getCause());

		var cause     = new IOException("socket closed");
		var withCause = new CliException("transport failure", cause);
		assertEquals("transport failure", withCause.getMessage());
		assertSame(cause, withCause.getCause());
	}
}
