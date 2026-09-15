package com.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) throws IOException {
        boolean upper = parseUpperOption(args);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        run(reader, writer, upper);
    }

    static void run(BufferedReader reader, PrintWriter writer, boolean upper) throws IOException {
        Agent agent = new Agent();
        String line;
        while ((line = reader.readLine()) != null) {
            if ("quit".equalsIgnoreCase(line.trim())) { // quit 退出
                return;
            }
            writer.println(agent.respond(line, upper));
        }
    }

    private static boolean parseUpperOption(String[] args) { // upper 参数会将输出转大写
        boolean upper = false;
        for (String arg : args) {
            if ("--upper".equals(arg)) {
                upper = true;
            } else {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        return upper;
    }
}
