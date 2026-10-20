package net.sourceforge.kolmafia.textui.parsetree;

public class Variable {
    private String name;
    private Type type;
    private Value value;

    public Variable(String name, Type type) {
        this.name = name;
        this.type = type;
        this.value = new Value();
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public Value getValue() {
        return value;
    }

    public void setValue(Value value) {
        this.value = value;
    }
}