package net.sourceforge.kolmafia.textui.parsetree;

public enum Type {
    INT("int"),
    FLOAT("float"),
    STRING("string"),
    BOOLEAN("boolean"),
    VOID("void"),
    ARRAY("array"),
    MAP("map"),
    RECORD("record");

    private final String name;

    Type(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}