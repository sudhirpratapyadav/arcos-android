package com.arcos;

import java.io.IOException;

/** A malformed frame, a failed handshake, or a transport that went away. */
public class ArcosException extends IOException {

    public ArcosException(String message) {
        super(message);
    }

    public ArcosException(String message, Throwable cause) {
        super(message, cause);
    }
}
