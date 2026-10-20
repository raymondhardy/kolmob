package net.sourceforge.kolmafia.textui;

public interface ScriptRuntime {
    enum State {
        NORMAL,
        RETURN,
        BREAK,
        CONTINUE,
        EXIT
    }

    Object execute(String functionName, Object[] parameters);
    Object execute(String functionName, Object[] parameters, boolean executeTopLevel);
    State getState();
    void setState(State newState);
}