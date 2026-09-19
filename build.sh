#!/usr/bin/env bash
#
# Builds the Update Check engine extension into dist/updatecheck-<version>.zip.
#
#   ./plugins/oie-update-check/build.sh
#
# No local JDK or Maven required: it compiles inside a container and takes the
# engine jars straight out of the image this repo builds, so it is always
# compiled against exactly the engine it will run on. That matters because the
# jars are not published to Maven Central -- the usual alternative is a
# third-party mirror pinned to some other version.
#
# Nothing is bundled. The plugin reads two JSON documents over HTTPS using the
# JDK's own HttpClient, and the only library it needs beyond the engine's
# controllers is Jackson, which the engine already loads from server-lib. So
# there are no third-party jars to verify and nothing extra in the engine's JVM.
#
# Environment:
#   OIE_IMAGE      image to take engine jars from   (default oie/engine:4.6.0)
#   JDK_IMAGE      compiler image                   (default eclipse-temurin:21-jdk)
#   PLUGIN_VERSION version stamped into plugin.xml   (default 0.1.0)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${HERE}"

OIE_IMAGE="${OIE_IMAGE:-oie/engine:4.6.0}"
JDK_IMAGE="${JDK_IMAGE:-eclipse-temurin:21-jdk}"
PLUGIN_VERSION="${PLUGIN_VERSION:-0.1.0}"

# Must match the engine the extension is installed on, or ExtensionLoader
# refuses it outright -- compatibility is an exact string match.
MIRTH_VERSION="${MIRTH_VERSION:-4.6.0}"

BUILD="${HERE}/build"
LIBS="${BUILD}/libs"
DIST="${HERE}/dist"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

########################################################################
# 1. Engine jars, straight from the image
########################################################################
prepare_libs() {
    mkdir -p "$LIBS"

    if [[ -f "${LIBS}/.engine-jars-ok" ]]; then
        log "engine jars already extracted (rm -rf ${BUILD} to refresh)"
        return 0
    fi

    log "extracting engine jars from ${OIE_IMAGE}"
    # A throwaway container is the only way to read an image's filesystem
    # without a running one; `docker create` does not start it.
    local cid
    cid="$(docker create "$OIE_IMAGE" /bin/true)"
    trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' RETURN

    # Deliberately globbed rather than named with their versions. The engine
    # bumps its dependencies on its own schedule, and a build script that names
    # log4j-api-2.25.3.jar fails with "no such file" on the first release that
    # ships 2.26 -- which is a version bump breaking the build of an extension
    # whose own code has not changed. The glob picks up whatever that engine
    # actually has, which is the jar this is meant to compile against anyway.
    #
    # One container resolves every pattern: the globs have to be expanded inside
    # the image, because they name files this host does not have.
    local -a patterns=(
        'server-lib/mirth-server.jar'
        'server-lib/mirth-client-core.jar'
        'server-lib/xstream-*.jar'
        'server-lib/donkey/donkey-server.jar'
        'server-lib/javax/javax.servlet-api-*.jar'
        'server-lib/jackson/jackson-core-*.jar'
        'server-lib/jackson/jackson-databind-*.jar'
        'server-lib/jackson/jackson-annotations-*.jar'
        'client-lib/donkey-model.jar'
        'client-lib/log4j-api-*.jar'
        'client-lib/javax.ws.rs-api-*.jar'
        'client-lib/swagger-annotations-*.jar'
        'client-lib/jersey-media-multipart-*.jar'
    )
    # One line: the list is interpolated into a `for` in the container's shell,
    # and a newline after `in` is a syntax error there.
    local list="${patterns[*]}"

    local resolved
    resolved="$(docker run --rm --entrypoint sh "$OIE_IMAGE" -c "
        cd /opt/engine || exit 1
        for p in ${list}; do
            # First match only: a pattern is expected to name one jar, and two
            # copies of the same library on a classpath is its own problem.
            set -- \$p
            if [ -f \"\$1\" ]; then printf '%s\n' \"\$1\"; else printf 'MISSING %s\n' \"\$p\"; fi
        done")" || die "could not list jars in ${OIE_IMAGE}"

    if printf '%s\n' "$resolved" | grep -q '^MISSING '; then
        printf '%s\n' "$resolved" | grep '^MISSING ' >&2
        die "the engine image does not carry every jar this extension compiles against"
    fi

    local jar
    while IFS= read -r jar; do
        [[ -n "$jar" ]] || continue
        docker cp "${cid}:/opt/engine/${jar}" "${LIBS}/$(basename "$jar")" >/dev/null
    done <<< "$resolved"

    touch "${LIBS}/.engine-jars-ok"
    log "extracted $(ls -1 "${LIBS}"/*.jar | wc -l) engine jars"
}

########################################################################
# 2. Compile
########################################################################
compile() {
    log "compiling with ${JDK_IMAGE}"
    rm -rf "${BUILD}/classes"
    mkdir -p "${BUILD}/classes"

    # Paths are passed relative to the mount so this works the same on Windows.
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "${HERE}:/p" \
        -w /p \
        "$JDK_IMAGE" \
        sh -c '
            set -e
            CP=$(ls build/libs/*.jar 2>/dev/null | tr "\n" ":")
            find src -name "*.java" > build/sources.txt
            echo "    $(wc -l < build/sources.txt) source files"
            javac -Xlint:all -Xlint:-options -Werror -source 17 -target 17 \
                -cp "$CP" -d build/classes @build/sources.txt
        '
    log "compiled"
}

########################################################################
# 3. Verify the version ordering
########################################################################
#
# Everything this plugin claims rests on one comparison, and the way it fails is
# not a crash: it is a chip nagging about an update that does not exist, or
# silence while a release sits there. 1.0.9 against 1.0.10 is the case a string
# comparison gets backwards, and it is the range both watched projects are in.
selftest() {
    log "checking version ordering against fixed vectors"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        cat > /tmp/SelfTest.java <<EOF
import org.openintegrationengine.plugins.updatecheck.Version;
public class SelfTest {
    public static void main(String[] a) {
        String r = Version.selfTest();
        if (r != null) { System.err.println("    FAILED: " + r); System.exit(1); }
        System.out.println("    version ordering and upgrade rules hold");
    }
}
EOF
        javac -cp build/classes -d /tmp /tmp/SelfTest.java
        java -cp build/classes:/tmp SelfTest
    ' || die "version ordering is wrong; refusing to package"
}

########################################################################
# 4. Package
########################################################################
package() {
    local stage="${BUILD}/stage/updatecheck"
    rm -rf "${BUILD}/stage"
    mkdir -p "${stage}/libs" "${DIST}"

    # One jar for the whole extension. The engine's extension classloader does
    # not care about the client/server/shared split unless the Swing client
    # needs its own half, and this plugin's UI is the web console.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c 'cd build/classes && jar cf ../stage/updatecheck/libs/updatecheck-server.jar .'

    # plugin.xml carries the version and the compatibility string, both of which
    # have to be right or the extension is silently not loaded.
    sed -e "s|@PLUGIN_VERSION@|${PLUGIN_VERSION}|g" \
        -e "s|@MIRTH_VERSION@|${MIRTH_VERSION}|g" \
        "${HERE}/plugin.xml.in" > "${stage}/plugin.xml"

    # And it has to parse. MirthLauncher reads it before the engine starts and,
    # on a parse error, logs one line and carries on without the extension --
    # after which everything downstream looks like it simply is not installed.
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" sh -c '
        cat > /tmp/ValidateXml.java <<JAVA
import javax.xml.parsers.DocumentBuilderFactory;
public class ValidateXml {
    public static void main(String[] args) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new java.io.File(args[0]));
    }
}
JAVA
        java /tmp/ValidateXml.java build/stage/updatecheck/plugin.xml
    ' || die "plugin.xml does not parse; the engine would refuse the extension"

    # The console UI half travels inside the extension, so one install delivers
    # both (the pattern documented in the web administrator's PLUGINS.md).
    cp -a "${HERE}/webadmin" "${stage}/webadmin"

    # Keep the version in the console manifest in step with plugin.xml: the
    # console shows it on the Extensions page, and two versions for one
    # extension is the kind of small lie that costs an hour later.
    sed -i.bak "s|\"version\": \"[^\"]*\"|\"version\": \"${PLUGIN_VERSION}\"|" \
        "${stage}/webadmin/plugin.json"
    rm -f "${stage}/webadmin/plugin.json.bak"

    local zip="${DIST}/updatecheck-${PLUGIN_VERSION}.zip"
    rm -f "$zip"
    MSYS_NO_PATHCONV=1 docker run --rm -v "${HERE}:/p" -w /p "$JDK_IMAGE" \
        sh -c "cd build/stage && jar cfM ../../dist/updatecheck-${PLUGIN_VERSION}.zip updatecheck"

    log "built ${zip#"$ROOT/"}"
    sha256sum "$zip" | sed 's/^/    /'
}

prepare_libs
compile
selftest
package

cat <<EOF

Install it by dropping the zip in extensions/ and restarting:

    cp $(printf '%s' "${DIST}/updatecheck-${PLUGIN_VERSION}.zip" | sed "s|${ROOT}/||") extensions/
    docker compose up -d --force-recreate engine
    ./scripts/oie-check-extensions.sh "Update Check"

It reads https://api.github.com once a day. Set OIE_UPDATE_CHECK=false to stop
it reaching the network at all -- the console then says so instead of showing a
stale answer.
EOF
