# Update Check

An **updates available** chip beside the version in the console header, and an
**Updates** page saying what applying each one involves in this stack.

```
engine · local-utility · v4.6.0   ● 2 updates available
```

It reads release feeds. It never downloads, installs, restarts or upgrades
anything — moving an engine that carries clinical traffic to a new version has a
maintenance window, a database backup and a rebuild of every extension behind
it, and none of that fits inside a click. The console's job here is to say a
release exists and what applying it involves, and then get out of the way.

## What it watches

| | running version from | releases from |
| --- | --- | --- |
| **Open Integration Engine** | `ConfigurationController.getServerVersion()` — the same string `GET /api/server/version` returns | [`OpenIntegrationEngine/engine`](https://github.com/OpenIntegrationEngine/engine) |
| **Web administrator** | the Web Support extension's `pluginVersion` | [`gibson9583/oie-web-support-plugin`](https://github.com/gibson9583/oie-web-support-plugin) |

The web console is not something you upgrade on its own: what you install is the
Web Support extension, and its version is what the release you would fetch is
tagged with. So that is what is compared, rather than the console bundle's own
build version, which nothing publishes releases of.

Add more with `OIE_UPDATE_CHECK_EXTENSIONS`, which is worth doing here because
this stack installs four other community extensions the same way:

```bash
OIE_UPDATE_CHECK_EXTENSIONS=Sentinel=gibson9583/oie-sentinel,Thread Viewer=gibson9583/engine-thread-viewer,OIDC Authentication=gibson9583/oie-oidc-auth
```

The key is the extension's name as its `plugin.xml` declares it (its directory
name works too) — that is what makes the running version readable. Nothing built
from `plugins/` is watched: those are built from this repository, and their
version is whatever the last build stamped.

## The egress, stated plainly

This is the one thing in this stack that calls out on a timer.

- **One GET per watched project, once a day**, to `https://api.github.com`, plus
  one for a release's checksums file when it publishes one.
- **Per cluster, not per node.** A node writes the timestamp before it makes the
  request, so the others find it fresh and leave it alone. Three engines make one
  request a day between them, and every node's console answers from the shared
  result — there is nothing to add to `OIE_DISABLE_EXTENSIONS`.
- **Nothing about this engine is sent.** It is the same request as opening the
  releases page in a browser: no version, no server id, no channel names, no
  identifiers of any kind. Unauthenticated by default.
- **`OIE_UPDATE_CHECK=false` stops it entirely.** That beats the setting stored
  in the database and cannot be undone from the console, so it holds against an
  administrator with UI rights and against a database restored from another
  environment. The page then says so rather than showing a stale answer as if it
  were current.
- **`OIE_UPDATE_CHECK_API_BASE`** points it at a mirror that answers the same
  shape, for a network that cannot reach GitHub but should still be told.

## What the page says

One panel per component: what is running, what is published, a link to the
release notes, and — when there is an update — the steps, with the versions and
checksums already filled in. For the engine that is:

1. back up PostgreSQL, because the schema migration on first boot is one-way;
2. the two `.env` lines, `OIE_VERSION` and `OIE_SHA256`, with the checksum taken
   from the release's own `sha256sums` asset;
3. `docker compose build engine && docker compose up -d`;
4. **rebuild every extension in `plugins/`** with the new `MIRTH_VERSION` —
   compatibility is an exact string match, and one that is not rebuilt is refused
   outright and silently absent, which turns every channel using its connectors
   into an invalid channel;
5. re-pin the community extensions to builds that declare the new version;
6. `oie-check-extensions.sh`, because the engine starts whether or not an
   extension was refused.

For an extension it is the pinned `OIE_EXTENSION_URLS` entry — `sha256:<hash>@<url>`,
ready to paste, using the hash the project published — or the mounted-zip route,
then a recreate and a check that the new zip declares this engine's version.

Every code block has a Copy button, because retyping a 64-character checksum by
eye is how a pin ends up wrong, and a wrong pin fails the boot it was meant to
secure.

## How the chip hooks in, and the catch

The console exposes no registration hook for the header. `registerNavItem`,
`registerView`, `registerSettingsPanel`, `registerChannelTab`,
`registerDashboardTab`, `registerConnectorPropertiesPanel`, `registerCommand`
and `registerLoginAuthenticator` cover every other surface; the topbar is built
inside the shell.

So the chip is inserted into the DOM after `.server-chip` — the pill that renders
`environment · server · v4.6.0` — and its position is re-asserted on mutations,
because React owns that header's children and can leave a foreign node adrift
when it re-renders its own.

That is a deliberate trade with a known failure mode, the same one
[`oie-sso-user-guard`](../oie-sso-user-guard/) makes: **if the console changes
that markup, the chip stops appearing.** It cannot break the header, it cannot
leave anything half-drawn, and the Updates page stays reachable from the sidebar
and the command palette either way. The durable fix is a header extension point
upstream in [`oie-web-client`](https://github.com/gibson9583/oie-web-client).

## Build and install

```bash
./plugins/oie-update-check/build.sh
cp plugins/oie-update-check/dist/updatecheck-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "Update Check"
```

The build compiles inside a container against the jars in the engine image, runs
the version-ordering vectors, validates `plugin.xml` and packages the console
half. It bundles nothing: the two JSON documents are read with the JDK's own
`HttpClient`, and Jackson comes from the engine's `server-lib`.

Its jar list is **globbed rather than version-pinned** (`log4j-api-*.jar`, not
`log4j-api-2.25.3.jar`), unlike the other build scripts here. Those name the jars
with their versions and so break on the first engine release that bumps a
dependency — a version bump failing the build of an extension whose own code has
not changed.

## The API

```
GET  /api/updatecheck/status     what the last check found, versus what is running
POST /api/updatecheck/check      read the feeds now
POST /api/updatecheck/settings   enabled, intervalHours
```

`status` reads the `configuration` table and nothing else, so the chip costs one
query on page load and an engine with no route out is exactly as fast as one with
a route out. Two permissions, not one: *View available updates* draws the chip and
every signed-in user needs it, while *Manage the update check* covers the outbound
request and the settings.

```bash
OIE_PASSWORD=... ./scripts/oie-api.sh GET /updatecheck/status
OIE_PASSWORD=... ./scripts/oie-api.sh POST /updatecheck/check
```

## Seeing the chip when everything is current

Nothing is drawn when there is nothing to say, which makes the feature awkward to
look at on a stack that is up to date. To see it, age one of the cached records —
the state is computed from it against the running version on every read:

```bash
docker compose exec -T db psql -U mirthdb -d mirthdb -c \
  "update configuration set value = regexp_replace(value, '^[^\t]*', '4.7.0')
   where category='Update Check' and name='latest.engine';"
```

Reload the console: the chip appears, and the Updates page offers the (imaginary)
4.7.0 steps. Put the truth back with a real check:

```bash
OIE_PASSWORD=... ./scripts/oie-api.sh POST /updatecheck/check
```

## Where things are stored

Everything is in the `configuration` table under the group `Update Check`, which
is what makes it survive this image's reset of `conf/` and `extensions/` on every
boot, and what makes one node's check answer for all of them:

```
enabled, intervalHours   the settings, editable on the page
checkedAt                when a check last completed, or was claimed
error                    why the last one did not, empty when it did
latest.<component>       the newest published release of that component
```

Only facts about releases are stored — never a verdict. Whether one of them is an
*update* is worked out against the running version each time the status is read,
so an engine that comes back up on the new version stops claiming one
immediately, rather than at the next check.
