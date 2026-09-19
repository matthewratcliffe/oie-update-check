/*
 * SPDX-License-Identifier: MPL-2.0
 *
 * Standalone unit test for org.openintegrationengine.plugins.updatecheck.Version.
 *
 * The plugins in this repository have no Maven/Gradle project and no JUnit on
 * the classpath -- they are compiled by build.sh inside a container against the
 * engine jars. Version.java, though, depends on nothing but java.util, so this
 * test compiles the REAL class (not a copy) alongside itself and runs it with a
 * plain `main`. It drives Version.selfTest() -- the fixed-vector suite the class
 * already ships and build.sh runs before packaging -- plus a handful of extra
 * cases, and exits non-zero on any failure so CI fails the job.
 *
 * Run (from the plugin root, plugins/oie-update-check):
 *
 *   javac -d build/test-classes \
 *       src/org/openintegrationengine/plugins/updatecheck/Version.java \
 *       test/java/VersionTest.java
 *   java  -cp build/test-classes VersionTest
 *
 * The GitHub Actions java-unit job does exactly this.
 */

import org.openintegrationengine.plugins.updatecheck.Version;

import java.util.ArrayList;
import java.util.List;

public class VersionTest {

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        // 1. The class's own fixed-vector suite (parse, order, normalise, upgrade).
        String selfTest = Version.selfTest();
        check("Version.selfTest() passes", selfTest == null,
                "selfTest reported: " + selfTest);

        // 2. Independent restatement of the load-bearing cases, so a regression in
        //    selfTest itself cannot hide a regression in the logic.
        check("parse strips a leading v",
                "4.6.0".equals(req("v4.6.0").raw()), null);
        check("parse drops build metadata",
                "4.6.0".equals(req("4.6.0+build.7").raw()), null);
        check("4.6 equals 4.6.0",
                req("4.6").compareTo(req("4.6.0")) == 0, null);
        check("1.0.9 orders below 1.0.10 (the string-compare trap)",
                req("1.0.9").compareTo(req("1.0.10")) < 0, null);
        check("a pre-release precedes its release",
                req("4.6.0-rc1").compareTo(req("4.6.0")) < 0, null);

        check("parse(null) is null", Version.parse(null) == null, null);
        check("parse(\"\") is null", Version.parse("") == null, null);
        check("parse(\"latest\") is null", Version.parse("latest") == null, null);
        check("parse(\"4.6.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0\") parses",
                Version.parse("4.6.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0") != null, null);

        check("isUpgrade forward is true",
                Version.isUpgrade(req("4.6.0"), req("4.7.0")), null);
        check("isUpgrade equal is false",
                !Version.isUpgrade(req("4.6.0"), req("4.6.0")), null);
        check("isUpgrade downgrade is false",
                !Version.isUpgrade(req("4.6.0"), req("4.5.2")), null);
        check("isUpgrade to a pre-release is false",
                !Version.isUpgrade(req("4.6.0"), req("4.7.0-rc1")), null);
        check("isUpgrade from a pre-release is true",
                Version.isUpgrade(req("4.6.0-rc1"), req("4.6.0")), null);
        check("isUpgrade with unknown running is false",
                !Version.isUpgrade(null, req("4.7.0")), null);

        check("equal versions have equal hashCode",
                req("4.6").hashCode() == req("4.6.0").hashCode(), null);
        check("equal versions with several trailing zeros hash equally",
                req("4").hashCode() == req("4.0.0").hashCode(), null);
        check("distinct versions usually differ in hashCode",
                req("4.6.0").hashCode() != req("4.7.0").hashCode(), null);

        System.out.printf("%nVersionTest: %d checks, %d failed%n", checks, failures.size());
        if (!failures.isEmpty()) {
            for (String f : failures) {
                System.out.println("  FAIL: " + f);
            }
            System.exit(1);
        }
        System.out.println("PASS");
    }

    private static Version req(String text) {
        Version v = Version.parse(text);
        if (v == null) {
            failures.add("expected '" + text + "' to parse");
        }
        return v;
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            System.out.println("FAIL " + name);
            failures.add(name + (detail == null ? "" : " -- " + detail));
        }
    }
}
