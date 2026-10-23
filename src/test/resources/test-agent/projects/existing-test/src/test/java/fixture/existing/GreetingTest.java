package fixture.existing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingTest {
    @Test
    void greetsByName() {
        assertEquals("Hello, Ada", new Greeting().hello("Ada"));
    }
}
