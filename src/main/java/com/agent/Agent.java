package com.agent;

import java.util.Locale;

public class Agent {
    public String respond(String input) {
        return respond(input, false);
    }

    public String respond(String input, boolean upper) {
        String response;
        if (input == null || input.isBlank()) {
            response = "Hello, Agent!";
        } else if ("hello".equalsIgnoreCase(input.trim())) {
            response = "Hello, Agent!";
        } else {
            response = "I heard: " + input;
        }

        return upper ? response.toUpperCase(Locale.ROOT) : response;
    }
}
