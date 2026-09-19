import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    normalise, chipLabel, chipTitle, when, publishedOn,
    engineSteps, extensionSteps,
} from './logic.mjs';

test('normalise coerces string booleans and numbers with the right defaults', () => {
    const n = normalise({
        ok: 'true', enabled: 'true', envDisabled: 'false',
        intervalHours: '0', updates: '2',
        components: ['e\tEngine\tengine\t4.6.0\t4.7.0\tupdate'],
    });
    assert.equal(n.enabled, true);
    assert.equal(n.envDisabled, false);
    assert.equal(n.intervalHours, 24);       // Number('0') || 24 -> 24
    assert.equal(n.updates, 2);
    assert.equal(n.components.length, 1);
    assert.equal(n.components[0].label, 'Engine');

    // ok defaults true: only the literal 'false' turns it off.
    assert.equal(normalise({}).ok, true);
    assert.equal(normalise({ ok: 'false' }).ok, false);
    assert.equal(normalise({ ok: undefined }).ok, true);
    // enabled defaults false.
    assert.equal(normalise({}).enabled, false);
    assert.equal(normalise({ updates: 'x' }).updates, 0);
});

test('chipLabel pluralises', () => {
    assert.equal(chipLabel({ updates: 0 }), '0 updates available');
    assert.equal(chipLabel({ updates: 1 }), '1 update available');
    assert.equal(chipLabel({ updates: 3 }), '3 updates available');
});

test('chipTitle lists components in the update state', () => {
    assert.equal(chipTitle({ components: [
        { state: 'update', label: 'Engine', latest: '4.7.0' },
        { state: 'current', label: 'Web', latest: '1.0' },
    ] }), 'Engine 4.7.0. Click for how to apply.');
    assert.equal(chipTitle({ components: [{ state: 'current', label: 'X', latest: '1' }] }),
        'Click for details.');
});

test('when is deterministic under an injected clock', () => {
    const now = Date.parse('2026-09-19T12:00:00Z');
    assert.equal(when('', now), 'never');
    assert.equal(when('not-a-date', now), 'not-a-date');   // unparseable echoes
    assert.equal(when('2026-09-19T11:59:40Z', now), 'just now'); // < 1 min
    assert.equal(when('2026-09-19T11:30:00Z', now), '30 min ago');
    assert.equal(when('2026-09-19T09:00:00Z', now), '3h ago');
    assert.equal(when('2026-09-16T12:00:00Z', now), '3 days ago');
});

test('publishedOn yields a YYYY-MM-DD date or echoes bad input', () => {
    assert.equal(publishedOn('2026-01-15T09:00:00Z'), '2026-01-15');
    assert.equal(publishedOn(''), '');
    assert.equal(publishedOn('garbage'), 'garbage');
});

test('engineSteps embeds the .env pin only when a checksum is known', () => {
    const withHash = engineSteps({ latest: '4.7.0', sha256: 'abc123' });
    assert.equal(withHash[1].code, 'OIE_VERSION=4.7.0\nOIE_SHA256=abc123');
    const noHash = engineSteps({ latest: '4.7.0' });
    assert.match(noHash[1].code, /# OIE_SHA256=/);
    assert.equal(engineSteps({}).length, 6);
});

test('extensionSteps pins when sha256+url present, else fetch-and-pin', () => {
    const pinned = extensionSteps({ sha256: 'deadbeef', downloadUrl: 'https://x/w.zip' });
    assert.equal(pinned[0].code, 'OIE_EXTENSION_URLS=sha256:deadbeef@https://x/w.zip');
    const unpinned = extensionSteps({ downloadUrl: 'https://x/w.zip', id: 'w', latest: '1.0' });
    assert.match(unpinned[0].code, /curl -sSLO https:\/\/x\/w\.zip/);
});
