package problem.api;

import lib.LibClass;
import lib.LibMethods;

public class JavaTest {
    void test() {
        new LibClass();
        LibMethods.staticMethod();
    }

    static void overloads(LibMethods lib) {
        lib.overload1(12);
        lib.overload1("Some");

        lib.overload2(12);
        lib.overload2("Some");

        //noinspection IncompatibleAPI
        lib.overload2(13);
    }

    public static class Extends extends LibClass {
    }
}
