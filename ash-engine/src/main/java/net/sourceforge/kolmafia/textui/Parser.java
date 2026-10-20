package net.sourceforge.kolmafia.textui;

import java.util.StringTokenizer;

public class Parser {
    private String fileName;

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public StringBuffer parseAndExecute(String script, ScriptRuntime runtime) {
        StringBuffer output = new StringBuffer();
        StringTokenizer tokenizer = new StringTokenizer(script, "\n");
        
        while (tokenizer.hasMoreTokens()) {
            String line = tokenizer.nextToken().trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            // Simple command parsing
            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";
            
            switch (command) {
                case "visit":
                    output.append("Visiting: ").append(args).append("\n");
                    break;
                case "print":
                    output.append(args).append("\n");
                    break;
                case "adventure":
                    output.append("Adventure: ").append(args).append("\n");
                    break;
                case "use":
                    output.append("Using: ").append(args).append("\n");
                    break;
                case "equip":
                    output.append("Equipping: ").append(args).append("\n");
                    break;
                default:
                    output.append("Unknown command: ").append(command).append("\n");
            }
        }
        
        return output;
    }
}