/*
 * Update Check console UI.
 *
 * Two surfaces:
 *
 *   1. An "Updates available" chip beside the version in the console header,
 *      drawn only when a release this engine does not have has been published.
 *      Clicking it opens the page.
 *   2. An "Updates" page: what is running, what is published, and what applying
 *      it involves in THIS stack -- the .env lines, the rebuild, the restart,
 *      the check afterwards. It applies nothing. There is no button here that
 *      touches the engine, because moving a production engine to a new version
 *      has a maintenance window, a database backup and a rebuild of every
 *      extension behind it, and none of those fit inside a click.
 *
 * The chip is injected into the DOM rather than registered, because the console
 * offers no header hook: registerNavItem, registerView, registerSettingsPanel,
 * registerChannelTab, registerDashboardTab, registerConnectorPropertiesPanel,
 * registerCommand and registerLoginAuthenticator cover every other surface, and
 * the topbar is built inside the shell. So this finds `.server-chip` -- the
 * element that renders `environment - server - v4.6.0` -- and inserts after it.
 *
 * That is a deliberate trade with a known failure mode, the same one the SSO
 * user guard makes: IF THE CONSOLE CHANGES THAT MARKUP, THE CHIP STOPS
 * APPEARING. It cannot break the header and it cannot leave anything
 * half-drawn, and the page it links to stays reachable from the sidebar and the
 * command palette either way. The durable fix is a header extension point
 * upstream in oie-web-client; this exists so the header says it today.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 */

const API = '/api/updatecheck';

/* How often an open console re-reads the status. The answer changes at most
 * once a day and the request is one table read, so this is about a console left
 * open for a week noticing, not about latency. */
const REFRESH_MS = 30 * 60 * 1000;

/* Fields of a component row, in the order UpdateCheckService writes them. */
const COMPONENT_FIELDS = ['id', 'label', 'kind', 'running', 'latest', 'state',
    'publishedAt', 'releaseUrl', 'downloadUrl', 'assetName', 'sha256', 'repo'];

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object. Same reason the shell scripts in this repo parse XML.
 */
function decode(node) {
    if (node === null || typeof node !== 'object') return node;
    if (Array.isArray(node)) return node.map(decode);

    const keys = Object.keys(node);

    const mapKey = keys.find(k => k === 'map' || k.endsWith('-map'));
    if (mapKey && keys.length === 1) {
        const inner = node[mapKey];
        if (inner === null) return {};
        let entries = inner.entry;
        if (entries == null) return {};
        if (!Array.isArray(entries)) entries = [entries];
        const out = {};
        for (const entry of entries) {
            const [k, v] = decodeEntry(entry);
            out[k] = v;
        }
        return out;
    }

    const listKey = keys.find(k => k === 'list' || k === 'set'
        || /(^|[.$_-])(list|set|collection)/i.test(k));
    if (listKey && keys.length === 1) {
        const inner = node[listKey];
        if (inner === null) return [];
        const elemKey = Object.keys(inner).find(k => !k.startsWith('@'));
        if (elemKey === undefined) return [];
        const items = inner[elemKey];
        if (items !== null && typeof items === 'object' && !Array.isArray(items)) {
            return [];
        }
        return (Array.isArray(items) ? items : [items]).map(decode);
    }

    const SCALARS = new Set(['string', 'boolean', 'int', 'long', 'double', 'float',
        'short', 'byte', 'char', 'big-decimal', 'big-int']);
    if (keys.length === 1 && SCALARS.has(keys[0])) {
        const v = node[keys[0]];
        return Array.isArray(v) ? v.map(decode) : decode(v);
    }

    const out = {};
    for (const k of keys) out[k] = decode(node[k]);
    return out;
}

function decodeEntry(entry) {
    if (entry === null || typeof entry !== 'object') return [String(entry), null];
    const props = Object.keys(entry);
    if (props.length === 1 && Array.isArray(entry[props[0]])) {
        const arr = entry[props[0]];
        return [String(arr[0]), arr.length > 1 ? decode(arr[1]) : null];
    }
    const keyVal = entry[props[0]];
    const key = Array.isArray(keyVal) ? String(keyVal[0]) : String(keyVal);
    if (props.length < 2) return [key, null];
    const valueName = props[1];
    return [key, decode({ [valueName]: entry[valueName] })];
}

/*
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped. A plain
 * JSON object body gives a 500; the XML map gives a 200.
 */
function encodeMap(obj) {
    const esc = v => String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    const entries = Object.keys(obj)
        .filter(k => obj[k] !== undefined && obj[k] !== null)
        .map(k => `<entry><string>${esc(k)}</string><string>${esc(obj[k])}</string></entry>`)
        .join('');
    return `<map>${entries}</map>`;
}

/* Rows arrive tab-separated; see Tsv.java for why they are not nested objects. */
function rows(list, names) {
    return (list || []).map(line => {
        const parts = String(line).split('\t');
        const out = {};
        names.forEach((name, i) => { out[name] = parts[i] === undefined ? '' : parts[i]; });
        return out;
    });
}

async function call(method, path, body) {
    const headers = {
        Accept: 'application/json',
        'X-Requested-With': 'oie-webadmin',
    };
    if (body !== undefined) headers['Content-Type'] = 'application/xml';

    const res = await fetch(API + path, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : encodeMap(body),
    });

    let payload = null;
    const text = await res.text();
    if (text) {
        try {
            payload = decode(JSON.parse(text));
        } catch (err) {
            payload = { error: text };
        }
    }
    if (!res.ok) {
        const error = new Error((payload && (payload.error || payload.message))
            || `HTTP ${res.status}`);
        error.status = res.status;
        throw error;
    }
    return payload || {};
}

function normalise(payload) {
    const status = payload || {};
    return {
        ok: String(status.ok) !== 'false',
        error: status.error || '',
        enabled: String(status.enabled) === 'true',
        envDisabled: String(status.envDisabled) === 'true',
        storedEnabled: String(status.storedEnabled) === 'true',
        checking: String(status.checking) === 'true',
        intervalHours: Number(status.intervalHours) || 24,
        checkedAt: status.checkedAt || '',
        apiBase: status.apiBase || '',
        updates: Number(status.updates) || 0,
        components: rows(status.components, COMPONENT_FIELDS),
    };
}

/*
 * One status, shared by the chip and the page.
 *
 * Both want the same answer at the same time -- the page opens because the chip
 * was clicked -- and two components each fetching on mount is two requests and
 * two chances to disagree about what the header says.
 */
const store = {
    status: null,
    fetchedAt: 0,
    inflight: null,
    listeners: new Set(),
};

function publish() {
    store.listeners.forEach(fn => {
        try {
            fn(store.status);
        } catch (err) {
            console.warn('[updatecheck] listener failed:', err);
        }
    });
}

function load(force) {
    if (!force && store.inflight) return store.inflight;
    if (!force && store.status && Date.now() - store.fetchedAt < REFRESH_MS) {
        return Promise.resolve(store.status);
    }
    store.inflight = call('GET', '/status')
        .then(payload => {
            store.status = normalise(payload);
            store.fetchedAt = Date.now();
            publish();
            return store.status;
        })
        .catch(err => {
            /*
             * Never let a failed status read change the header. A console whose
             * session has expired, or a user whose role does not carry the view
             * permission, gets the header they have always had rather than an
             * error chip next to the server name.
             */
            console.warn('[updatecheck] could not read status:', err.message || err);
            return null;
        })
        .finally(() => { store.inflight = null; });
    return store.inflight;
}

function subscribe(fn) {
    store.listeners.add(fn);
    if (store.status) fn(store.status);
    return () => store.listeners.delete(fn);
}

// ----------------------------------------------------------------------
// The chip
// ----------------------------------------------------------------------

const CHIP_ID = 'oie-update-chip';
const STYLE_ID = 'oie-update-chip-style';

/*
 * Styled here rather than borrowed from .tag, because the topbar is painted
 * with the rail's gradient and the shell's tag colours are mixed against the
 * page background -- an amber .tag reads as a bruise up there. These rules
 * follow .server-chip's own shape (the pill next to it) and take their colour
 * from --warn against the topbar's foreground token, so both themes work
 * without this file knowing which one is on.
 */
const CHIP_CSS = `
#${CHIP_ID} {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border: 1px solid color-mix(in srgb, var(--warn) 55%, transparent);
    border-radius: 89px;
    background: color-mix(in srgb, var(--warn) 18%, transparent);
    color: var(--topbar-fg);
    font: inherit;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.01em;
    white-space: nowrap;
    cursor: pointer;
}
#${CHIP_ID}:hover { background: color-mix(in srgb, var(--warn) 30%, transparent); }
#${CHIP_ID}:focus-visible { outline: 2px solid var(--warn); outline-offset: 2px; }
#${CHIP_ID} .dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--warn);
    flex: none;
}
/* The shell hides .server-chip on a narrow viewport because the server identity
   is repeated in the status bar. This is not repeated anywhere, and it is the
   one chip up there worth tapping, so it stays -- without its text. */
@media (max-width: 640px) {
    #${CHIP_ID} { padding: 4px 7px; }
    #${CHIP_ID} .label { display: none; }
}`;

function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = CHIP_CSS;
    document.head.appendChild(style);
}

function chipLabel(status) {
    const n = status.updates;
    return n === 1 ? '1 update available' : `${n} updates available`;
}

function chipTitle(status) {
    const names = status.components
        .filter(c => c.state === 'update')
        .map(c => `${c.label} ${c.latest}`);
    return names.length
        ? `${names.join(', ')}. Click for how to apply.`
        : 'Click for details.';
}

/*
 * Puts the chip where it belongs, or takes it away.
 *
 * Called on every relevant mutation, so it has to be cheap and idempotent: it
 * re-uses the existing node and only touches the DOM when something it owns is
 * actually wrong. The anchor is `.server-chip` -- the pill showing
 * `environment - server - v4.6.0` -- with the topbar itself as a fallback, so a
 * console that has renamed or dropped that element still gets a chip in the
 * header rather than none at all.
 */
function ensureChip(platform, status) {
    const header = document.querySelector('header.topbar');
    const existing = document.getElementById(CHIP_ID);

    if (!header || !status || status.updates <= 0) {
        if (existing) existing.remove();
        return;
    }

    ensureStyle();

    let chip = existing;
    if (!chip) {
        chip = document.createElement('button');
        chip.id = CHIP_ID;
        chip.type = 'button';
        const dot = document.createElement('span');
        dot.className = 'dot';
        const label = document.createElement('span');
        label.className = 'label';
        chip.appendChild(dot);
        chip.appendChild(label);
        chip.addEventListener('click', () => {
            try {
                platform.router.navigate('/updates');
            } catch (err) {
                // A router that has moved on is not worth breaking the header
                // over; the sidebar item still goes there.
                console.warn('[updatecheck] could not navigate:', err);
            }
        });
    }

    const label = chipLabel(status);
    const labelEl = chip.querySelector('.label');
    if (labelEl && labelEl.textContent !== label) labelEl.textContent = label;
    const title = chipTitle(status);
    if (chip.title !== title) chip.title = title;
    // The visible text disappears at phone widths, so the accessible name is
    // carried separately rather than being read off the button.
    chip.setAttribute('aria-label', label);

    /*
     * Position is re-asserted, not just set once. React owns this header's
     * children: it never removes a node it did not create, but it inserts its
     * own relative to its own, so a re-render of the server chip can leave this
     * one adrift. Cheap to check, and the alternative is a chip that slowly
     * migrates to the wrong end of the bar.
     */
    const anchor = header.querySelector('.server-chip');
    const shouldFollow = anchor || null;
    if (shouldFollow) {
        if (chip.previousElementSibling !== shouldFollow || chip.parentElement !== header) {
            shouldFollow.after(chip);
        }
    } else if (chip.parentElement !== header) {
        // No anchor: before the account menu if there is one, otherwise last.
        const userChip = header.querySelector('.user-chip');
        if (userChip) header.insertBefore(chip, userChip);
        else header.appendChild(chip);
    }
}

/*
 * Watches for the header being (re)built.
 *
 * The topbar is mounted after the plugins load on a cold start, and replaced on
 * a sign-out and back in, so one placement at register() time is not enough.
 * Throttled to an animation frame: a React render produces a burst of mutations
 * and ensureChip() only needs to run once after it settles.
 */
function watchHeader(platform) {
    let queued = false;
    const apply = () => {
        queued = false;
        try {
            ensureChip(platform, store.status);
        } catch (err) {
            console.warn('[updatecheck] could not place the chip:', err);
        }
    };
    // Bound, not just referenced: requestAnimationFrame called detached from
    // window throws "Illegal invocation" in Chromium.
    const soon = window.requestAnimationFrame
        ? window.requestAnimationFrame.bind(window)
        : (fn) => window.setTimeout(fn, 0);
    const schedule = () => {
        if (queued) return;
        queued = true;
        soon(apply);
    };

    const observer = new MutationObserver(schedule);
    observer.observe(document.body, { childList: true, subtree: true });
    subscribe(schedule);
    schedule();
}

// ----------------------------------------------------------------------
// Registration
// ----------------------------------------------------------------------

/* A downward arrow into a tray: "there is something to bring in". */
const ICON_UPDATES = 'M12 3v10m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2';

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    // ------------------------------------------------------------------
    // Presentation, in the shell's own classes and CSS variables so the page
    // follows the app's spacing, borders and both themes.
    // ------------------------------------------------------------------

    const viewStyle = {
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    };

    function Panel(title, children, right, key) {
        return h('div', { key, className: 'panel', style: { marginBottom: '11px' } }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                right || null,
            ]) : null,
            h('div', { key: 'b', className: 'panel-body' }, children),
        ]);
    }

    function Button(label, onClick, opts) {
        const o = opts || {};
        const classes = ['btn'];
        if (o.primary) classes.push('btn-primary');
        return h('button', {
            key: o.key,
            type: 'button',
            className: classes.join(' '),
            disabled: !!o.disabled,
            title: o.title || '',
            onClick,
            style: { marginRight: '6px' },
        }, label);
    }

    function Banner(kind, children, key) {
        const token = { error: '--err', warn: '--warn', ok: '--ok', info: '--accent' }[kind]
            || '--accent';
        return h('div', {
            key,
            style: {
                border: `1px solid var(${token})`,
                borderLeftWidth: '3px',
                borderRadius: '4px',
                padding: '8px 10px',
                marginBottom: '10px',
                fontSize: '0.9em',
            },
        }, children);
    }

    function Tag(text, tone) {
        return h('span', { className: 'tag' + (tone ? ' ' + tone : '') }, text);
    }

    /*
     * A copyable block.
     *
     * Every instruction on this page is something to paste into .env or a shell,
     * and retyping a 64-character checksum by eye is how a pin ends up wrong --
     * which then fails the boot it was meant to secure. The textarea fallback is
     * for the case that actually happens here: navigator.clipboard is
     * unavailable on an insecure origin, and an engine reached over plain HTTP
     * on a lab network is exactly that.
     */
    function Code({ text, label }) {
        const [copied, setCopied] = React.useState(false);

        const copy = () => {
            const done = () => {
                setCopied(true);
                window.setTimeout(() => setCopied(false), 1500);
            };
            if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(text).then(done, () => fallback());
                return;
            }
            fallback();

            function fallback() {
                try {
                    const area = document.createElement('textarea');
                    area.value = text;
                    area.setAttribute('readonly', '');
                    area.style.position = 'fixed';
                    area.style.opacity = '0';
                    document.body.appendChild(area);
                    area.select();
                    document.execCommand('copy');
                    document.body.removeChild(area);
                    done();
                } catch (err) {
                    console.warn('[updatecheck] copy failed:', err);
                }
            }
        };

        return h('div', { style: { marginBottom: '10px' } }, [
            h('div', {
                key: 'head',
                style: {
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    marginBottom: '4px',
                },
            }, [
                h('span', { key: 'l', className: 'hint' }, label || ''),
                h('button', {
                    key: 'c',
                    type: 'button',
                    className: 'btn',
                    onClick: copy,
                    style: { fontSize: '0.85em', padding: '2px 8px' },
                }, copied ? 'Copied' : 'Copy'),
            ]),
            h('pre', {
                key: 'pre',
                className: 'mono',
                style: {
                    margin: 0,
                    padding: '8px 10px',
                    border: '1px solid var(--line)',
                    borderRadius: '4px',
                    background: 'var(--bg2)',
                    overflowX: 'auto',
                    fontSize: '0.85em',
                    whiteSpace: 'pre',
                },
            }, text),
        ]);
    }

    // ------------------------------------------------------------------
    // Instructions
    //
    // Written for THIS stack, which is the only reason they are worth putting
    // in front of someone: a generic "download the new version" helps nobody,
    // while "these two lines in .env, then this command, then rebuild the nine
    // extensions because compatibility is an exact string match" is the actual
    // work. Everything version-specific is substituted from the check.
    // ------------------------------------------------------------------

    function engineSteps(component) {
        const version = component.latest || 'x.y.z';
        const envLines = component.sha256
            ? `OIE_VERSION=${version}\nOIE_SHA256=${component.sha256}`
            : `OIE_VERSION=${version}\n# OIE_SHA256=<from the release's sha256sums asset>`;

        return [
            {
                title: 'Back up PostgreSQL first',
                body: 'The engine migrates its schema on first boot of a new version, and'
                    + ' the migration is one-way. A dump taken before the rebuild is the'
                    + ' only route back to the version you are on now.',
                code: null,
                codeLabel: null,
            },
            {
                title: 'Point the image at the new release',
                body: component.sha256
                    ? 'Both lines together, in .env and in your CI variables. The checksum'
                        + ' is the one published alongside the release and is enforced at'
                        + ' build time.'
                    : 'In .env and in your CI variables. This release publishes no checksums'
                        + ' asset that could be read here, so take the hash from the release'
                        + ' page and fill it in -- an unpinned tarball is arbitrary code in'
                        + " the engine's JVM.",
                code: envLines,
                codeLabel: '.env',
            },
            {
                title: 'Rebuild and restart',
                body: 'The Dockerfile fetches the tarball, verifies it against OIE_SHA256'
                    + ' and rebuilds. Set OIE_TARBALL_URL as well if you mirror releases'
                    + ' internally.',
                code: 'docker compose build engine\ndocker compose up -d',
                codeLabel: 'shell',
            },
            {
                title: 'Rebuild the extensions built in this repository',
                body: 'Extension compatibility is an EXACT string match against the server'
                    + ' version, so every extension in plugins/ has to be rebuilt and'
                    + ' reinstalled for the new engine. One that is not is refused outright'
                    + ' and silently absent, and every channel using its connectors is then'
                    + ' stored as an invalid channel.',
                code: `for p in plugins/oie-*/; do MIRTH_VERSION=${version} "$p/build.sh"; done\n`
                    + 'cp plugins/oie-*/dist/*.zip extensions/\n'
                    + 'docker compose up -d --force-recreate engine',
                codeLabel: 'shell',
            },
            {
                title: 'Re-pin the community extensions',
                body: 'Web Support, Sentinel, Thread Viewer, TLS Manager and OIDC Auth each'
                    + ' need a build that declares this engine version in OIE_EXTENSION_URLS.'
                    + ' Ask for one rather than reaching for OIE_EXTENSION_RETAG_VERSION,'
                    + ' which asserts a compatibility claim the vendor has not made.',
                code: null,
                codeLabel: null,
            },
            {
                title: 'Confirm what actually loaded',
                body: 'The engine starts whether or not an extension was refused, so this is'
                    + ' the step that tells you the upgrade worked rather than half-worked.',
                code: 'docker compose logs engine | grep -iE "installed extension|not compatible"\n'
                    + './scripts/oie-check-extensions.sh',
                codeLabel: 'shell',
            },
        ];
    }

    function extensionSteps(component) {
        const url = component.downloadUrl;
        const asset = component.assetName || `${component.id}-${component.latest}.zip`;
        const pin = component.sha256 && url
            ? `OIE_EXTENSION_URLS=sha256:${component.sha256}@${url}`
            : null;

        const steps = [];

        if (pin) {
            steps.push({
                title: 'Update the pinned URL',
                body: 'Replace this extension\'s entry in OIE_EXTENSION_URLS, keeping the'
                    + ' others comma-separated. The checksum is the one published with the'
                    + ' release and the entrypoint refuses to start on a mismatch.',
                code: pin,
                codeLabel: '.env (this extension\'s entry)',
            });
        } else {
            steps.push({
                title: 'Fetch the zip and pin what you got',
                body: 'This release publishes no checksums asset, so there is no vendor'
                    + ' hash to pin. Download it, satisfy yourself it is what you expect,'
                    + ' and pin the hash of the artifact you inspected -- that protects'
                    + ' against the file changing under you from now on, which is not the'
                    + ' same as a published value.',
                code: url
                    ? `curl -sSLO ${url}\nsha256sum ${asset}`
                    : `# the release page has the artifact: ${component.releaseUrl}`,
                codeLabel: 'shell',
            });
        }

        steps.push({
            title: 'Or drop the zip in extensions/',
            body: 'The mounted-zip route avoids depending on the network at boot at all,'
                + ' which is the better answer for production. extensions/ is mounted'
                + ' read-only at /opt/engine/custom-extensions and unpacked before the'
                + ' engine starts.',
            code: `cp ~/Downloads/${asset} extensions/\nrm -f extensions/${component.id}-*.zip.old`,
            codeLabel: 'shell',
        });

        steps.push({
            title: 'Restart the engine',
            body: 'Extensions are read at startup and this image resets extensions/ from'
                + ' its pristine copy on every boot, so copying files into a running'
                + ' container achieves nothing. Install the zip and recreate.',
            code: 'docker compose up -d --force-recreate engine\n'
                + `./scripts/oie-check-extensions.sh "${component.label}"`,
            codeLabel: 'shell',
        });

        steps.push({
            title: 'Check it declares this engine version',
            body: 'Compatibility is an exact string match. A zip built for another version'
                + ' is refused outright and the console simply will not have it.',
            code: `unzip -p extensions/${asset} '*/plugin.xml' | grep mirthVersion`,
            codeLabel: 'shell',
        });

        return steps;
    }

    function Steps({ component }) {
        const steps = component.kind === 'engine'
            ? engineSteps(component)
            : extensionSteps(component);

        return h('ol', {
            style: { margin: '4px 0 0', paddingLeft: '18px', lineHeight: 1.55 },
        }, steps.map((step, i) => h('li', { key: i, style: { marginBottom: '14px' } }, [
            h('div', { key: 't', style: { fontWeight: 600, marginBottom: '3px' } }, step.title),
            h('div', { key: 'b', style: { marginBottom: step.code ? '7px' : 0 } }, step.body),
            step.code ? h(Code, { key: 'c', text: step.code, label: step.codeLabel }) : null,
        ])));
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    function when(iso) {
        if (!iso) return 'never';
        const t = Date.parse(iso);
        if (Number.isNaN(t)) return iso;
        const mins = Math.round((Date.now() - t) / 60000);
        if (mins < 1) return 'just now';
        if (mins < 60) return `${mins} min ago`;
        const hours = Math.round(mins / 60);
        if (hours < 48) return `${hours}h ago`;
        return `${Math.round(hours / 24)} days ago`;
    }

    function publishedOn(iso) {
        if (!iso) return '';
        const t = Date.parse(iso);
        return Number.isNaN(t) ? iso : new Date(t).toISOString().slice(0, 10);
    }

    function ComponentPanel({ component }) {
        const [open, setOpen] = React.useState(component.state === 'update');

        const state = component.state;
        const tag = state === 'update' ? Tag('UPDATE', 'amber')
            : state === 'current' ? Tag('CURRENT', 'accent')
                : state === 'not-installed' ? Tag('NOT INSTALLED')
                    : Tag('UNKNOWN');

        const right = h('span', { className: 'hint' },
            state === 'update' && component.publishedAt
                ? `published ${publishedOn(component.publishedAt)}`
                : component.repo);

        const versions = h('div', {
            style: {
                display: 'flex',
                flexWrap: 'wrap',
                gap: '18px',
                alignItems: 'baseline',
                marginBottom: state === 'update' ? '10px' : 0,
            },
        }, [
            h('div', { key: 'r' }, [
                h('div', { key: 'l', className: 'hint' }, 'Running'),
                h('div', { key: 'v', className: 'mono' }, component.running || 'unknown'),
            ]),
            h('div', { key: 'a' }, [
                h('div', { key: 'l', className: 'hint' }, 'Available'),
                h('div', { key: 'v', className: 'mono' }, component.latest || 'unknown'),
            ]),
            component.releaseUrl ? h('div', { key: 'n' }, [
                h('div', { key: 'l', className: 'hint' }, 'Release'),
                h('a', {
                    key: 'v',
                    href: component.releaseUrl,
                    target: '_blank',
                    rel: 'noopener noreferrer',
                }, 'notes'),
            ]) : null,
        ]);

        const body = [versions];

        if (state === 'update') {
            body.push(h('div', { key: 'toggle', style: { marginBottom: open ? '8px' : 0 } },
                Button(open ? 'Hide how to apply this' : 'How to apply this',
                    () => setOpen(!open), { primary: !open })));
            if (open) {
                body.push(h('div', { key: 'steps' }, [
                    Banner('info', [
                        h('strong', { key: 's' }, 'Nothing here is applied for you. '),
                        'These are the steps for this stack, with the versions filled in.',
                    ], 'note'),
                    h(Steps, { key: 'st', component }),
                ]));
            }
        } else if (state === 'not-installed') {
            body.push(h('div', { key: 'm', className: 'hint' },
                'Watched, but not installed on this engine. Nothing is requested for it.'));
        } else if (state === 'unknown') {
            body.push(h('div', { key: 'm', className: 'hint' },
                'No published release has been read for this yet.'));
        }

        return Panel(
            h('span', null, [
                h('span', { key: 'l', style: { marginRight: '8px' } }, component.label),
                h('span', { key: 't' }, tag),
            ]),
            body,
            right);
    }

    function UpdatesView() {
        const [status, setStatus] = React.useState(store.status);
        const [busy, setBusy] = React.useState(false);
        const [failed, setFailed] = React.useState('');

        React.useEffect(() => {
            const off = subscribe(setStatus);
            load(false);
            return off;
        }, []);

        const refresh = (force) => {
            setBusy(true);
            setFailed('');
            const work = force
                ? call('POST', '/check').then(p => {
                    store.status = normalise(p);
                    store.fetchedAt = Date.now();
                    publish();
                })
                : load(true);
            work.catch(err => setFailed(err.message || String(err)))
                .finally(() => setBusy(false));
        };

        const saveSettings = (settings) => {
            setBusy(true);
            setFailed('');
            call('POST', '/settings', settings)
                .then(p => {
                    store.status = normalise(p);
                    store.fetchedAt = Date.now();
                    publish();
                })
                .catch(err => setFailed(err.message || String(err)))
                .finally(() => setBusy(false));
        };

        if (!status) {
            return h('div', { className: 'view', style: viewStyle },
                h('div', { className: 'hint' }, 'Loading...'));
        }

        const content = [];

        if (failed) {
            content.push(h('div', { key: 'failed' },
                Banner('error', [h('strong', { key: 's' }, 'That did not work. '), failed])));
        }

        if (status.envDisabled) {
            content.push(h('div', { key: 'env' }, Banner('warn', [
                h('strong', { key: 's' }, 'Switched off by the deployment. '),
                'OIE_UPDATE_CHECK is false, so this engine makes no outbound request and'
                + ' the versions below are whatever was last recorded. Change it in the'
                + " environment; it deliberately cannot be turned back on from here.",
            ])));
        } else if (!status.storedEnabled) {
            content.push(h('div', { key: 'off' }, Banner('info', [
                h('strong', { key: 's' }, 'The check is off. '),
                'Nothing is requested and no chip appears in the header.',
            ])));
        }

        if (status.error) {
            content.push(h('div', { key: 'err' }, Banner('warn', [
                h('strong', { key: 's' }, 'The last check had trouble: '),
                status.error,
            ])));
        }

        content.push(h('div', { key: 'components' },
            status.components.map(c => h(ComponentPanel, { key: c.id, component: c }))));

        content.push(Panel('The check itself', [
            h('div', { key: 'facts', className: 'hint', style: { marginBottom: '10px' } }, [
                `Last checked ${when(status.checkedAt)}`,
                status.enabled ? `, every ${status.intervalHours}h` : '',
                `. Reads ${status.apiBase || 'the release feed'} and sends nothing about`
                + ' this engine -- it is the same request as opening the releases page in a'
                + ' browser. In a cluster the result is shared through the database, so'
                + ' every node answers from one check.',
            ].join('')),
            h('div', { key: 'controls', style: { display: 'flex', flexWrap: 'wrap', gap: '6px' } }, [
                Button(busy ? 'Working...' : 'Check now', () => refresh(true), {
                    key: 'now',
                    primary: true,
                    disabled: busy || !status.enabled,
                    title: status.enabled ? '' : 'The check is switched off',
                }),
                Button('Reload', () => refresh(false), { key: 'reload', disabled: busy }),
                status.envDisabled ? null : Button(
                    status.storedEnabled ? 'Turn the check off' : 'Turn the check on',
                    () => saveSettings({ enabled: String(!status.storedEnabled) }),
                    { key: 'toggle', disabled: busy }),
            ]),
            h('div', {
                key: 'interval',
                style: { marginTop: '10px', display: 'flex', alignItems: 'center', gap: '6px' },
            }, [
                h('label', { key: 'l', htmlFor: 'oie-update-interval', className: 'hint' },
                    'Check every'),
                h('input', {
                    key: 'i',
                    id: 'oie-update-interval',
                    type: 'number',
                    min: 1,
                    max: 720,
                    defaultValue: status.intervalHours,
                    disabled: busy || status.envDisabled,
                    style: { width: '80px' },
                    onBlur: (e) => {
                        const hours = Number(e.target.value);
                        if (hours && hours !== status.intervalHours) {
                            saveSettings({ intervalHours: String(hours) });
                        }
                    },
                }),
                h('span', { key: 'u', className: 'hint' }, 'hours'),
            ]),
        ], null, 'check'));

        return h('div', { className: 'view', style: viewStyle }, content);
    }

    // ------------------------------------------------------------------
    // Wiring
    //
    // Each registration is guarded on its own: a console that does not offer one
    // of these hooks should still get everything else, rather than the whole
    // plugin failing on the first missing function.
    // ------------------------------------------------------------------

    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[updatecheck] could not register ' + label, err);
        }
    };

    safely('icon', () => {
        platform.registerIcon('updates', ICON_UPDATES);
    });

    safely('updates view', () => {
        platform.registerView('/updates', platform.reactView(UpdatesView), {
            title: 'Updates',
        });
        platform.registerNavItem({
            id: 'updates',
            label: 'Updates',
            icon: 'updates',
            path: '/updates',
            section: 'Plugins',
            order: 95,
        });
    });

    safely('command', () => {
        platform.registerCommand({
            id: 'updates',
            label: 'Updates',
            icon: 'updates',
            path: '/updates',
        });
    });

    safely('header chip', () => {
        watchHeader(platform);
    });

    // Last, so a failure fetching cannot stop the page being registered.
    load(false);
    window.setInterval(() => load(true), REFRESH_MS);
}
