package fixture.baseline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.fail;

class UnrelatedFailureTest {
    @Test
    void unrelatedFailure() {
        fail("fixture baseline failure");
    }
}
