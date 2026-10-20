package net.sourceforge.kolmafia.textui.parsetree;

import net.sourceforge.kolmafia.textui.ScriptRuntime;

public interface Evaluable {
    Object evaluate(ScriptRuntime runtime);
}