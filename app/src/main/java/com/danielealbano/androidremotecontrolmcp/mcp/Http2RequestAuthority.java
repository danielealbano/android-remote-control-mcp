package com.danielealbano.androidremotecontrolmcp.mcp;

import io.ktor.server.netty.http2.NettyHttp2ApplicationRequest;
import io.ktor.server.request.ApplicationRequest;

/**
 * Reads the {@code :authority} of an HTTP/2 request, which Ktor offers no public way to read.
 *
 * <p>Java on purpose: {@code NettyHttp2ApplicationRequest} is {@code internal} in Kotlin, which the Kotlin compiler
 * refuses and javac does not know about. A Ktor update may rename or move it and break this class. When that happens it
 * MUST be fixed to read {@code :authority} again, NOT removed: see "Public base URL" in docs/PROJECT.md.
 */
final class Http2RequestAuthority {
    private Http2RequestAuthority() {}

    /** The request's {@code :authority}, or {@code null} when it is not an HTTP/2 request or carries none. */
    static String of(ApplicationRequest request) {
        if (!(request instanceof NettyHttp2ApplicationRequest)) {
            return null;
        }
        CharSequence authority = ((NettyHttp2ApplicationRequest) request).getNettyHeaders().authority();
        return authority == null ? null : authority.toString();
    }
}
