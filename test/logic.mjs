/*
 * The pure helpers out of webadmin/web/plugin.js, so they can be tested without
 * a browser or a React runtime.
 *
 * Copied rather than imported, the same arrangement the Backup / Git Sync /
 * Volume Monitor extensions use. decode/decodeEntry/encodeMap/rows/normalise/
 * chipLabel/chipTitle/publishedOn and the engineSteps/extensionSteps builders
 * are copied verbatim. when() reads Date.now() in plugin.js; here it takes an
 * injected `now` so the relative-time thresholds are deterministic under test.
 */

export const COMPONENT_FIELDS = ['id', 'label', 'kind', 'running', 'latest', 'state',
    'publishedAt', 'releaseUrl', 'downloadUrl', 'assetName', 'sha256', 'repo'];

export function decode(node) {
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

export function decodeEntry(entry) {
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

export function encodeMap(obj) {
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

export function rows(list, names) {
    return (list || []).map(line => {
        const parts = String(line).split('\t');
        const out = {};
        names.forEach((name, i) => { out[name] = parts[i] === undefined ? '' : parts[i]; });
        return out;
    });
}

export function normalise(payload) {
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

export function chipLabel(status) {
    const n = status.updates;
    return n === 1 ? '1 update available' : `${n} updates available`;
}

export function chipTitle(status) {
    const names = status.components
        .filter(c => c.state === 'update')
        .map(c => `${c.label} ${c.latest}`);
    return names.length
        ? `${names.join(', ')}. Click for how to apply.`
        : 'Click for details.';
}

export function when(iso, now = Date.now()) {
    if (!iso) return 'never';
    const t = Date.parse(iso);
    if (Number.isNaN(t)) return iso;
    const mins = Math.round((now - t) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins} min ago`;
    const hours = Math.round(mins / 60);
    if (hours < 48) return `${hours}h ago`;
    return `${Math.round(hours / 24)} days ago`;
}

export function publishedOn(iso) {
    if (!iso) return '';
    const t = Date.parse(iso);
    return Number.isNaN(t) ? iso : new Date(t).toISOString().slice(0, 10);
}

/* engineSteps / extensionSteps: the .env-line and pin construction is the
 * branch worth testing; the prose bodies are copied so a step-count assertion
 * still means something. */
export function engineSteps(component) {
    const version = component.latest || 'x.y.z';
    const envLines = component.sha256
        ? `OIE_VERSION=${version}\nOIE_SHA256=${component.sha256}`
        : `OIE_VERSION=${version}\n# OIE_SHA256=<from the release's sha256sums asset>`;

    return [
        { title: 'Back up PostgreSQL first', code: null, codeLabel: null },
        { title: 'Point the image at the new release', code: envLines, codeLabel: '.env' },
        {
            title: 'Rebuild and restart',
            code: 'docker compose build engine\ndocker compose up -d', codeLabel: 'shell',
        },
        { title: 'Rebuild the extensions built in this repository', code: null, codeLabel: 'shell' },
        { title: 'Re-pin the community extensions', code: null, codeLabel: null },
        { title: 'Confirm what actually loaded', code: null, codeLabel: 'shell' },
    ];
}

export function extensionSteps(component) {
    const url = component.downloadUrl;
    const asset = component.assetName || `${component.id}-${component.latest}.zip`;
    const pin = component.sha256 && url
        ? `OIE_EXTENSION_URLS=sha256:${component.sha256}@${url}`
        : null;

    const steps = [];
    if (pin) {
        steps.push({ title: 'Update the pinned URL', code: pin });
    } else {
        steps.push({
            title: 'Fetch the zip and pin what you got',
            code: url ? `curl -sSLO ${url}\nsha256sum ${asset}` : null,
        });
    }
    steps.push({ title: 'Or drop the zip in extensions/', code: `cp ~/Downloads/${asset} extensions/` });
    steps.push({ title: 'Restart the engine' });
    steps.push({ title: 'Check it declares this engine version' });
    return steps;
}
