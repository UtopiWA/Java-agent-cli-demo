package fixture.calculator;

public class Calculator {
    public int add(int left, int right) {
        return left + right;
    }

    public int divide(int value, int divisor) {
        if (divisor == 0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        return value / divisor;
    }
}
