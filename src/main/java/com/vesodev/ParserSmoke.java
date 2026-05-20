package com.vesodev;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class ParserSmoke {
    public static void main(String[] args) throws Exception {
        File f = new File("src/main/java/com/vesodev/sample log");
        if (!f.exists()) {
            System.out.println("Sample log not found: " + f.getAbsolutePath());
            return;
        }
        int lineNo = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                lineNo++;
                LogParser.ParsedData d = LogParser.parse(line);
                boolean has = d.getSampleMin().isPresent() || d.getSampleMax().isPresent() || d.getProcessedAvg().isPresent();
                if (has) {
                    StringBuilder out = new StringBuilder();
                    out.append("Line ").append(lineNo).append(": ");
                    d.getSampleMin().ifPresent(v -> out.append("min=").append(v).append(" "));
                    d.getSampleMax().ifPresent(v -> out.append("max=").append(v).append(" "));
                    d.getProcessedAvg().ifPresent(v -> out.append("avg=").append(v).append(" "));
                    System.out.println(out.toString());
                }
            }
        }
    }
}
