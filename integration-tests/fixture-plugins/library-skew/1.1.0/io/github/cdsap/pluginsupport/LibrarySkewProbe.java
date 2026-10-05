package io.github.cdsap.pluginsupport;

/**
 * Test-only class added to the plugin-support artifacts that integration-tests publishes to its local repository;
 * it is not part of the real library. This 1.1.0 variant, in the 1.1.0 and 2.0.0 artifacts, adds {@link #since110()}
 * to the 1.0.0 variant.
 */
public final class LibrarySkewProbe {
    private LibrarySkewProbe() {
    }

    public static String introducedIn() {
        return "1.0.0";
    }

    public static String since110() {
        return "1.1.0";
    }
}
