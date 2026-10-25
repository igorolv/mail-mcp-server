package mail.mcp.source.oauth.google;

import java.util.Map;

/** Test seam: implementations must never retry, redirect, log payloads or return raw provider errors. */
@FunctionalInterface
public interface GoogleTokenEndpoint {
    GoogleTokenResponse exchange(Map<String, String> fields);
}
