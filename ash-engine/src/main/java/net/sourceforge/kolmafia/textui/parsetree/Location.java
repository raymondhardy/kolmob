package net.sourceforge.kolmafia.textui.parsetree;

public class Location {
    private final int line;
    private final int column;

    public Location(int line, int column) {
        this.line = line;
        this.column = column;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    @Override
    public String toString() {
        return "Line " + line + ", Column " + column;
    }
}