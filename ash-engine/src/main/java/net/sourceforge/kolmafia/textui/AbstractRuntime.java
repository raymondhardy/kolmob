package net.sourceforge.kolmafia.textui;

public abstract class AbstractRuntime implements ScriptRuntime {
    protected State runtimeState = State.EXIT;

    @Override
    public Object execute(final String functionName, final Object[] parameters) {
        return execute(functionName, parameters, true);
    }

    @Override
    public abstract Object execute(final String functionName, final Object[] parameters, final boolean executeTopLevel);

    @Override
    public State getState() {
        return runtimeState;
    }

    @Override
    public void setState(final State newState) {
        runtimeState = newState;
    }
}