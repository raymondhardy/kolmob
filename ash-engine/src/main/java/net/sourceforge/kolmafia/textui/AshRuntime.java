package net.sourceforge.kolmafia.textui;

public class AshRuntime extends AbstractRuntime {
    private Parser parser;
    private ScriptRuntime scriptRuntime;

    public AshRuntime() {
        this.parser = new Parser();
        this.scriptRuntime = this;
    }

    public Parser getParser() {
        return parser;
    }

    public ScriptRuntime getScriptRuntime() {
        return scriptRuntime;
    }

    @Override
    public Object execute(String functionName, Object[] parameters, boolean executeTopLevel) {
        return null; // placeholder
    }
}