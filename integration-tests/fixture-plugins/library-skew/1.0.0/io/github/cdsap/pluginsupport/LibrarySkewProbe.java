package io.github.cdsap.pluginsupport;

/**
 * Test-only class added to the plugin-support artifacts that integration-tests publishes to its local repository;
 * it is not part of the real library. This 1.0.0 variant is in the 1.0.0 artifact, the 1.1.0 variant (which adds
 * {@code since110}) in the 1.1.0 and 2.0.0 artifacts. It simulates an API added in a minor release: a plugin compiled
 * against 1.1.0 that runs against the 1.0.0 class gets {@link NoSuchMethodError} unless the version guard stops it.
 */
public final class LibrarySkewProbe {
    private LibrarySkewProbe() {
    }

    public static String introducedIn() {
        return "1.0.0";
    }
}
