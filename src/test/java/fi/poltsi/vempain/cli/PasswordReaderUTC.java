package fi.poltsi.vempain.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.cli.core.PasswordReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The console reader falls back to {@code System.in} when the JVM has no console, which is the case in the test JVM; the console branch is exercised with a
 * mocked {@link Console}.
 */
class PasswordReaderUTC {

    private InputStream previousIn;
    private PrintStream previousOut;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void setUp() {
        assumeTrue(System.console() == null, "the fallback reader is only used without a console");
        previousIn = System.in;
        previousOut = System.out;
        outBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setIn(previousIn);
        System.setOut(previousOut);
    }

    @Test
    void consoleReader_withoutConsole_readsOneLineFromStandardInput() {
        System.setIn(new ByteArrayInputStream("secret\nnext line\n".getBytes(StandardCharsets.UTF_8)));

        var password = PasswordReader.consoleReader()
            .readPassword("Password: ");

        assertArrayEquals("secret".toCharArray(), password);
        assertEquals("Password: ", outBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void consoleReader_stopsAtCarriageReturnAndEndOfInput() {
        System.setIn(new ByteArrayInputStream("pw\r\n".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals("pw".toCharArray(), PasswordReader.consoleReader()
            .readPassword("> "));

        System.setIn(new ByteArrayInputStream("unterminated".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals("unterminated".toCharArray(), PasswordReader.consoleReader()
            .readPassword("> "));

        System.setIn(new ByteArrayInputStream(new byte[0]));
        assertArrayEquals(new char[0], PasswordReader.consoleReader()
            .readPassword("> "));
    }

    @Test
    void consoleReader_returnsWhatWasReadBeforeAnInputFailure() {
        System.setIn(new InputStream() {

            private final byte[] bytes = "ab".getBytes(StandardCharsets.UTF_8);
            private int index;

            @Override
            public int read() throws IOException {
                if (index < bytes.length) {
                    return bytes[index++];
                }
                throw new IOException("stdin closed");
            }
        });

        assertArrayEquals("ab".toCharArray(), PasswordReader.consoleReader()
            .readPassword("> "));
    }

    @Test
    void consoleReader_withConsole_readsWithoutEchoThroughTheConsole() {
        var console = mock(Console.class);
        when(console.readPassword("%s", "Password: ")).thenReturn("from-console".toCharArray());

        var password = PasswordReader.consoleReader(console)
            .readPassword("Password: ");

        assertArrayEquals("from-console".toCharArray(), password);
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8), "the prompt is written by the console, not by System.out");
        verify(console).readPassword("%s", "Password: ");
        verifyNoMoreInteractions(console);
    }
}
