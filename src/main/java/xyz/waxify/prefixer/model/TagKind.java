package xyz.waxify.prefixer.model;

public enum TagKind {
    PREFIX("prefix", "setprefix"),
    SUFFIX("suffix", "setsuffix");

    private final String id;
    private final String permissionRoot;

    TagKind(String id, String permissionRoot) {
        this.id = id;
        this.permissionRoot = permissionRoot;
    }

    public String id() {
        return id;
    }

    public String permissionRoot() {
        return permissionRoot;
    }
}
