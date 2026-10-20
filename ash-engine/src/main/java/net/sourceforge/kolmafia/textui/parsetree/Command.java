package net.sourceforge.kolmafia.textui.parsetree;

public abstract class Command extends ParseTreeNode implements Evaluable {
    public Command(Location location) {
        super(location);
    }
}