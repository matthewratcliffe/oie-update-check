/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.updatecheck;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered version string.
 *
 * <p>The whole plugin turns on this comparison: "is the newest published release newer
 * than what is running". Getting it wrong is not a crash, it is a chip that nags about an
 * update that does not exist -- or, worse, silence while a release sits there. So it is
 * kept away from everything that touches the network and checked against fixed vectors by
 * the build ({@link #selfTest()}), including {@code 1.0.9 < 1.0.10}, which a string
 * comparison gets backwards and which is exactly the range these projects are in.
 *
 * <p>The shapes this has to order are real ones from the two feeds it reads:
 *
 * <pre>
 *   4.6.0        the engine's own version, as ConfigurationController reports it
 *   v4.6.0       the same release's git tag
 *   1.0.3        an extension's pluginVersion
 *   4.6.0-rc1    a release candidate -- lower than 4.6.0, and never offered
 *   4.5.2-tp.1   the technical preview, likewise
 *   4.6          treated as 4.6.0, so a two-part version does not read as older
 * </pre>
 *
 * <p>Semantic versioning's ordering rules, in other words, applied leniently: anything
 * that will not parse is reported as unknown by the caller rather than guessed at, because
 * the only thing worse than not knowing is claiming to.
 */
public final class Version implements Comparable<Version> {

    private final int[] core;
    private final String[] pre;
    private final String raw;

    private Version(int[] core, String[] pre, String raw) {
        this.core = core;
        this.pre = pre;
        this.raw = raw;
    }

    /**
     * Parses a version, or returns null when the text is not one.
     *
     * <p>Null rather than a zero version: a component whose version cannot be read is
     * reported as unknown in the console, and a zero would instead order below every
     * release and so claim an update is available for something it could not even read.
     */
    public static Version parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return null;
        }
        // Tags carry a leading v; the same release's version string does not.
        if ((s.charAt(0) == 'v' || s.charAt(0) == 'V') && s.length() > 1
                && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        // Build metadata never affects ordering (semver rule 10), so it is dropped
        // rather than compared.
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }

        String coreText = s;
        String[] preParts = new String[0];
        int dash = s.indexOf('-');
        if (dash >= 0) {
            coreText = s.substring(0, dash);
            String preText = s.substring(dash + 1);
            preParts = preText.isEmpty() ? new String[0] : preText.split("\\.", -1);
        }

        String[] coreParts = coreText.split("\\.", -1);
        int[] numbers = new int[coreParts.length];
        for (int i = 0; i < coreParts.length; i++) {
            String part = coreParts[i].strip();
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                numbers[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                // A number too large for an int is not a version number.
                return null;
            }
        }
        if (numbers.length == 0) {
            return null;
        }
        // Held in its normalised form, not as it arrived. A release's tag is
        // "v4.6.0" and the engine's own version is "4.6.0", and the instructions
        // page puts this string into an `OIE_VERSION=` line -- where the v would
        // send the Dockerfile after a tarball that does not exist.
        return new Version(numbers, preParts, s);
    }

    /** Whether this is a pre-release: 4.6.0-rc1 rather than 4.6.0. */
    public boolean isPreRelease() {
        return pre.length > 0;
    }

    /**
     * The version in its normalised form: no leading {@code v}, no build metadata.
     *
     * <p>This is what is displayed and what the instructions substitute, so that one
     * version reads the same whether it came from a git tag or from the engine.
     */
    public String raw() {
        return raw;
    }

    @Override
    public int compareTo(Version other) {
        int len = Math.max(core.length, other.core.length);
        for (int i = 0; i < len; i++) {
            // A missing segment is zero, so 4.6 and 4.6.0 are one version rather than
            // the shorter one reading as older and producing a phantom update.
            int a = i < core.length ? core[i] : 0;
            int b = i < other.core.length ? other.core[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }

        // Same numbers: a pre-release precedes the release it leads to.
        if (pre.length == 0 && other.pre.length == 0) {
            return 0;
        }
        if (pre.length == 0) {
            return 1;
        }
        if (other.pre.length == 0) {
            return -1;
        }

        int n = Math.min(pre.length, other.pre.length);
        for (int i = 0; i < n; i++) {
            int cmp = compareIdentifier(pre[i], other.pre[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        // rc.1 precedes rc.1.1: more identifiers is the later pre-release.
        return Integer.compare(pre.length, other.pre.length);
    }

    /**
     * One pre-release identifier against another.
     *
     * <p>Numeric identifiers compare numerically and rank below alphanumeric ones, which
     * is what puts {@code tp.1} before {@code tp.2} and keeps {@code rc1} and {@code rc2}
     * -- alphanumeric, because the digit is glued to the word -- in the order a person
     * expects anyway.
     */
    private static int compareIdentifier(String a, String b) {
        boolean na = isNumeric(a);
        boolean nb = isNumeric(b);
        if (na && nb) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (na) {
            return -1;
        }
        if (nb) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.length() < 19 && s.chars().allMatch(Character::isDigit);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Version && compareTo((Version) obj) == 0;
    }

    @Override
    public int hashCode() {
        // Consistent with equals()/compareTo(), where a missing core segment is
        // zero: 4.6 and 4.6.0 are equal, so trailing zero segments must not
        // change the hash, or the two would land in different buckets and break
        // any HashSet<Version> / HashMap keyed on it.
        int significant = core.length;
        while (significant > 1 && core[significant - 1] == 0) {
            significant--;
        }
        int h = 7;
        for (int i = 0; i < significant; i++) {
            h = h * 31 + core[i];
        }
        for (String p : pre) {
            h = h * 31 + p.hashCode();
        }
        return h;
    }

    @Override
    public String toString() {
        return raw;
    }

    /**
     * Whether {@code candidate} is a release worth offering over {@code running}.
     *
     * <p>Every rule that decides what the chip claims, in one place:
     *
     * <ul>
     *   <li>either version unreadable -&gt; no. Unknown is not an update.
     *   <li>candidate is a pre-release -&gt; no. A release candidate is not something to
     *       nudge a production console towards; someone who wants one goes and gets it.
     *   <li>candidate not strictly greater -&gt; no. Equal is current, lower is a
     *       downgrade, and a downgrade offered as an update is how a back-ported patch
     *       on an older line ends up installed over a newer one.
     * </ul>
     */
    public static boolean isUpgrade(Version running, Version candidate) {
        if (running == null || candidate == null) {
            return false;
        }
        if (candidate.isPreRelease()) {
            return false;
        }
        return candidate.compareTo(running) > 0;
    }

    /**
     * Fixed vectors, run by build.sh before the extension is packaged.
     *
     * @return null when every case holds, otherwise a description of the failures
     */
    public static String selfTest() {
        List<String> failures = new ArrayList<>();

        expectLess(failures, "4.6.0", "4.7.0");
        expectLess(failures, "4.6.0", "4.6.1");
        expectLess(failures, "1.0.3", "1.1.0");
        // The one a string comparison gets backwards, and the range both projects
        // are actually in.
        expectLess(failures, "1.0.9", "1.0.10");
        expectLess(failures, "4.6.0-rc1", "4.6.0");
        expectLess(failures, "4.6.0-rc1", "4.6.0-rc2");
        expectLess(failures, "4.5.2-tp.1", "4.5.2");
        expectLess(failures, "4.5.2", "4.6.0");

        expectEqual(failures, "v1.0.3", "1.0.3");
        expectEqual(failures, "4.6", "4.6.0");
        expectEqual(failures, " 4.6.0 ", "4.6.0");
        expectEqual(failures, "4.6.0+build.7", "4.6.0");

        // Normalisation, which the instructions page depends on: a tag becomes the
        // version string that goes into OIE_VERSION.
        expectRaw(failures, "v4.7.0", "4.7.0");
        expectRaw(failures, "1.0.3", "1.0.3");
        expectRaw(failures, "4.6.0+build.7", "4.6.0");
        expectRaw(failures, " v4.6.0 ", "4.6.0");

        expectUnparseable(failures, "");
        expectUnparseable(failures, "latest");
        expectUnparseable(failures, "main");
        expectUnparseable(failures, null);

        // The decisions the chip is made of.
        expectUpgrade(failures, "4.6.0", "4.7.0", true);
        expectUpgrade(failures, "4.6.0", "4.6.0", false);
        expectUpgrade(failures, "4.6.0", "4.5.2", false);
        expectUpgrade(failures, "4.6.0", "4.7.0-rc1", false);
        expectUpgrade(failures, "4.6.0-rc1", "4.6.0", true);
        expectUpgrade(failures, null, "4.7.0", false);
        expectUpgrade(failures, "4.6.0", "not-a-version", false);

        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    private static void expectLess(List<String> failures, String lower, String higher) {
        Version a = parse(lower);
        Version b = parse(higher);
        if (a == null || b == null) {
            failures.add("could not parse " + lower + " or " + higher);
            return;
        }
        if (a.compareTo(b) >= 0) {
            failures.add(lower + " should order below " + higher);
        }
        if (b.compareTo(a) <= 0) {
            failures.add(higher + " should order above " + lower);
        }
    }

    private static void expectEqual(List<String> failures, String left, String right) {
        Version a = parse(left);
        Version b = parse(right);
        if (a == null || b == null) {
            failures.add("could not parse " + left + " or " + right);
            return;
        }
        if (a.compareTo(b) != 0) {
            failures.add(left + " should equal " + right);
        }
    }

    private static void expectRaw(List<String> failures, String text, String expected) {
        Version v = parse(text);
        if (v == null) {
            failures.add("could not parse " + text);
        } else if (!expected.equals(v.raw())) {
            failures.add("'" + text + "' should normalise to " + expected
                + ", not " + v.raw());
        }
    }

    private static void expectUnparseable(List<String> failures, String text) {
        if (parse(text) != null) {
            failures.add("'" + text + "' should not parse as a version");
        }
    }

    private static void expectUpgrade(List<String> failures, String running,
                                      String candidate, boolean expected) {
        boolean actual = isUpgrade(parse(running), parse(candidate));
        if (actual != expected) {
            failures.add("isUpgrade(" + running + ", " + candidate + ") should be " + expected);
        }
    }
}
