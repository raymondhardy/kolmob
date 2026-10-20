package net.sourceforge.kolmafia.textui.parsetree;

import java.util.HashMap;
import java.util.Map;

public class VariableList {
    private Map<String, Variable> variables = new HashMap<>();

    public void define(Variable variable) {
        variables.put(variable.getName(), variable);
    }

    public Variable get(String name) {
        return variables.get(name);
    }

    public boolean contains(String name) {
        return variables.containsKey(name);
    }

    public void setValue(String name, Value value) {
        Variable var = variables.get(name);
        if (var != null) {
            var.setValue(value);
        }
    }
}