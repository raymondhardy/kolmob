package net.sourceforge.kolmafia.textui.parsetree;

public class Value {
    private Object value;
    private Type type;

    public Value() {
        this.value = null;
        this.type = Type.VOID;
    }

    public Value(Object value) {
        this.value = value;
        if (value instanceof Integer) {
            this.type = Type.INT;
        } else if (value instanceof Double) {
            this.type = Type.FLOAT;
        } else if (value instanceof String) {
            this.type = Type.STRING;
        } else if (value instanceof Boolean) {
            this.type = Type.BOOLEAN;
        } else {
            this.type = Type.VOID;
        }
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Type getType() {
        return type;
    }

    public int intValue() {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Double) return ((Double) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        return 0;
    }

    public double floatValue() {
        if (value instanceof Double) return (Double) value;
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        return 0.0;
    }

    public String stringValue() {
        return value != null ? value.toString() : "";
    }

    public boolean booleanValue() {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Integer) return (Integer) value != 0;
        if (value instanceof Double) return (Double) value != 0.0;
        if (value instanceof String) return !((String) value).isEmpty();
        return false;
    }
}