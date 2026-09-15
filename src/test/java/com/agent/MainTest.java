package com.agent;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {
    @Test
    void shouldProcessInputUntilEndOfStream() throws Exception {
        assertEquals(
                "Hello, Agent!" + System.lineSeparator() +
                        "I heard: Java" + System.lineSeparator(),
                runCli("hello\nJava\n", false));
    }

    @Test
    void shouldStopAtQuitWithoutPrintingAResponse() throws Exception {
        assertEquals(
                "I heard: before" + System.lineSeparator(),
                runCli("before\n QuIt \nafter\n", false));
    }

    @Test
    void shouldApplyUpperOptionToAllResponses() throws Exception {
        assertEquals(
                "HELLO, AGENT!" + System.lineSeparator() +
                        "I HEARD: JAVA" + System.lineSeparator(),
                runCli("hello\nJava\n", true));
    }

    private String runCli(String input, boolean upper) throws Exception {
        StringWriter output = new StringWriter();
        Main.run(
                new BufferedReader(new StringReader(input)),
                new PrintWriter(output, true),
                upper);
        return output.toString();
    }
}
