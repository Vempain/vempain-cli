package fi.poltsi.vempain.cli.core;

import java.io.IOException;

/**
 * A backend answered a request with a non-2xx status. The message carries the method, URL, status and body as before; the status code is available for callers
 * that react to specific answers, such as a rejected session.
 */
public class HttpStatusException extends IOException {

    private final int statusCode;

    public HttpStatusException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * @return whether the backend no longer accepts the session's token (401 Unauthorized or 403 Forbidden)
     */
    public boolean isSessionRejected() {
        return statusCode == 401
               || statusCode == 403;
    }
}
