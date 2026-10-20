package net.sourceforge.kolmafia.textui.parsetree;

public abstract class ParseTreeNode {
    private Location location;

    public ParseTreeNode(Location location) {
        this.location = location;
    }

    public Location getLocation() {
        return location;
    }
}