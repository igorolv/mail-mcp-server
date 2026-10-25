package mail.mcp.model;

/** Decoded mail address; name is null when absent. */
public record Address(String name, String address) { }
