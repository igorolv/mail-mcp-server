package mail.mcp.model;

/** Decoded mail address; name is null when absent. */
public record Address(String name, String address) {
    public String display() { return name == null ? address : name + " <" + address + ">"; }
}
