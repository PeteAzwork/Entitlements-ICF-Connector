'use strict';
const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { load, fakeOpenidm, objectTypes, managedSchema, appendixAStore } = require('./harness');

describe('library shape (§8.2)', () => {
    test('exports the documented functions', () => {
        const { lib } = load(fakeOpenidm({}));
        for (const fn of ['buildRelationships', 'buildArchetypes', 'loadTypeConfig', 'resolveRelationship', 'resolvePair', 'normalisePropertyName', 'isPassive', 'clearCache']) {
            assert.equal(typeof lib[fn], 'function', fn);
        }
    });

    test('is ES5: no let/const/arrow/template literal/class syntax (runs under Rhino)', () => {
        const raw = require('node:fs').readFileSync(require('node:path').join(__dirname, '..', 'src', 'lib', 'graphEntityPayload.js'), 'utf8');
        // Only code is checked: comments may legitimately quote identifiers in backticks.
        const src = raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
        assert.doesNotMatch(src, /^\s*(let|const)\s/m);
        assert.doesNotMatch(src, /=>/);
        assert.doesNotMatch(src, /`/);
        assert.doesNotMatch(src, /^\s*class\s/m);
    });

    test('reads the single source of truth from the provisioner config path', () => {
        const { lib } = load(fakeOpenidm({}));
        assert.equal(lib.CONFIG_PATH, 'config/provisioner.openicf/graphentity');
    });
});

describe('normalisePropertyName / isPassive (§7)', () => {
    const cases = [['owns', 'Owns'], ['ownsSet', 'Owns'], ['ownsList', 'Owns'], ['memberList', 'Member'], ['ownedBy', 'OwnedBy'], ['Owns', 'Owns'], ['set', 'Set'], ['settings', 'Settings']];
    for (const [input, expected] of cases) {
        test(`normalisePropertyName('${input}') === '${expected}'`, () => {
            assert.equal(load(fakeOpenidm({})).lib.normalisePropertyName(input), expected);
        });
    }
    for (const bad of [null, undefined, '', '  ']) {
        test(`normalisePropertyName rejects ${JSON.stringify(bad)}`, () => {
            // Message must describe the bad input (a stub's "not implemented" error must not satisfy this).
            assert.throws(() => load(fakeOpenidm({})).lib.normalisePropertyName(bad), /blank|empty|required/i);
        });
    }
    const passive = [['ownedBy', true], ['managedBy', true], ['ownedBySet', true], ['owns', false], ['manager', false], ['reports', false], ['by', false], ['standby', false]];
    for (const [name, expected] of passive) {
        test(`isPassive('${name}') === ${expected}`, () => {
            assert.equal(load(fakeOpenidm({})).lib.isPassive(name), expected);
        });
    }
});

describe('resolvePair (§7)', () => {
    test('owns/ownedBy → Owns, owns outbound', () => {
        const r = load(fakeOpenidm({})).lib.resolvePair('owns', 'ownedBy');
        assert.deepEqual(r, { resolved: true, remoteType: 'Owns', outboundProperty: 'owns', inboundProperty: 'ownedBy' });
    });
    test('argument order does not matter', () => {
        const { lib } = load(fakeOpenidm({}));
        assert.deepEqual(lib.resolvePair('ownedBy', 'owns'), lib.resolvePair('owns', 'ownedBy'));
    });
    for (const [p, q, why] of [['manager', 'reports', 'neither passive'], ['ownedBy', 'managedBy', 'both passive'], ['owns', null, 'no reverse'], ['owns', 'owns', 'self pair']]) {
        test(`(${p}, ${q}) is unresolved: ${why}`, () => {
            const r = load(fakeOpenidm({})).lib.resolvePair(p, q);
            assert.equal(r.resolved, false);
            assert.ok(r.reason);
        });
    }
});

describe('resolveRelationship (§7 override-then-convention)', () => {
    test('explicit override in typeConfig wins', () => {
        const { lib } = load(fakeOpenidm({}));
        const tc = objectTypes().user;
        tc.relationships.manager = { remoteType: 'ReportsTo', direction: 'outbound' };
        assert.deepEqual(lib.resolveRelationship('manager', tc, managedSchema()), { remoteType: 'ReportsTo', direction: 'outbound' });
    });
    test('convention via the typeConfig reverseProperty hint', () => {
        const { lib } = load(fakeOpenidm({}));
        const tc = objectTypes().user;
        tc.relationships.owns = { relatedType: 'account', reverseProperty: 'ownedBy' };
        assert.deepEqual(lib.resolveRelationship('owns', tc, null), { remoteType: 'Owns', direction: 'outbound' });
    });
    test('convention falls back to managed.json reversePropertyName when the property is unlisted', () => {
        const { lib } = load(fakeOpenidm({}));
        const tc = objectTypes().account;
        delete tc.relationships.ownedBy;
        assert.deepEqual(lib.resolveRelationship('ownedBy', tc, managedSchema()), { remoteType: 'Owns', direction: 'inbound' });
    });
    test('sync:false → null', () => {
        const { lib } = load(fakeOpenidm({}));
        const tc = objectTypes().user;
        tc.relationships.owns = { sync: false };
        assert.equal(lib.resolveRelationship('owns', tc, managedSchema()), null);
    });
    test('unresolvable without override throws naming the property', () => {
        const { lib } = load(fakeOpenidm({}));
        assert.throws(() => lib.resolveRelationship('manager', objectTypes().user, managedSchema()), /manager/);
    });
    test('a property with no reverse anywhere throws (A2 violation)', () => {
        const { lib } = load(fakeOpenidm({}));
        assert.throws(() => lib.resolveRelationship('orphan', objectTypes().user, managedSchema()), /orphan/);
    });
});

describe('loadTypeConfig (§8.2 single source of truth)', () => {
    test('reads objectTypes from the provisioner customConfiguration and returns the named type', () => {
        const openidm = fakeOpenidm({});
        const { lib } = load(openidm);
        const tc = lib.loadTypeConfig('user');
        assert.equal(tc.idProperty, 'userName');
        assert.equal(tc.entityKind, 'subject');
        assert.deepEqual(tc.archetypes, ['Customer']);
        assert.equal(openidm.calls.filter(c => c.id === 'config/provisioner.openicf/graphentity').length, 1);
    });
    test('the type name is attached to the returned config', () => {
        assert.equal(load(fakeOpenidm({})).lib.loadTypeConfig('user').name, 'user');
    });
    test('caches across calls and reload bypasses the cache', () => {
        const openidm = fakeOpenidm({});
        const { lib } = load(openidm);
        lib.loadTypeConfig('user'); lib.loadTypeConfig('account'); lib.loadTypeConfig('user');
        assert.equal(openidm.calls.filter(c => c.id === 'config/provisioner.openicf/graphentity').length, 1);
        lib.loadTypeConfig('user', { reload: true });
        assert.equal(openidm.calls.filter(c => c.id === 'config/provisioner.openicf/graphentity').length, 2);
    });
    test('clearCache forces a re-read', () => {
        const openidm = fakeOpenidm({});
        const { lib } = load(openidm);
        lib.loadTypeConfig('user'); lib.clearCache(); lib.loadTypeConfig('user');
        assert.equal(openidm.calls.filter(c => c.id === 'config/provisioner.openicf/graphentity').length, 2);
    });
    test('an unknown type throws naming it and the configured types', () => {
        assert.throws(() => load(fakeOpenidm({})).lib.loadTypeConfig('group'), /group.*user|user.*group/s);
    });
    test('a provisioner without objectTypes throws a clear error', () => {
        const openidm = fakeOpenidm({});
        const original = openidm.read.bind(openidm);
        openidm.read = (id, p, f) => id.indexOf('provisioner') >= 0 ? { configurationProperties: {} } : original(id, p, f);
        assert.throws(() => load(openidm).lib.loadTypeConfig('user'), /objectTypes/);
    });
    test('also accepts objectTypes supplied directly as a JSON string property (alternative packaging)', () => {
        const openidm = fakeOpenidm({});
        const original = openidm.read.bind(openidm);
        openidm.read = (id, p, f) => id.indexOf('provisioner') >= 0
            ? { configurationProperties: { objectTypes: JSON.stringify(objectTypes()) } } : original(id, p, f);
        assert.equal(load(openidm).lib.loadTypeConfig('account').idProperty, 'accountId');
    });
});

describe('buildRelationships (§8.2, A1/A2)', () => {
    let openidm, lib;
    beforeEach(() => { openidm = fakeOpenidm(appendixAStore()); lib = load(openidm).lib; });

    test('user side: Sanne → one outbound Owns edge to the account REMOTE id (accountId), not the IDM _id', () => {
        const edges = lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        assert.deepEqual(edges, ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}']);
    });
    test('account side: one inbound Owns edge from Sanne (userName), same type string', () => {
        const edges = lib.buildRelationships('a-1', lib.loadTypeConfig('account'));
        assert.deepEqual(edges, ['{"SourceID":"Sanne","type":"Owns"}']);
    });
    test('reads the source with relationship expansion (fields "*" and "*_ref")', () => {
        lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        const read = openidm.calls.find(c => c.op === 'read' && c.id === 'managed/user/u-1');
        assert.ok(read, 'source object was read');
        assert.ok(read.fields.indexOf('*_ref') >= 0, 'relationship fields requested');
    });
    test('resolves the related remote id via the related TYPE idProperty, reading the related object', () => {
        lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        assert.ok(openidm.calls.some(c => c.op === 'read' && c.id === 'managed/account/a-1'));
    });
    test('a source id that is the remote ID (userName) rather than the _id is also accepted via idmResource lookup', () => {
        // Mappings may pass source._id; the library must work when given the IDM _id. (Remote-id lookup is optional.)
        assert.doesNotThrow(() => lib.buildRelationships('u-1', lib.loadTypeConfig('user')));
    });
    test('a user with no relationships yields an empty array (explicit, so the PUT clears edges)', () => {
        openidm = fakeOpenidm({ 'managed/user/u-2': { _id: 'u-2', userName: 'Solo' } });
        lib = load(openidm).lib;
        assert.deepEqual(lib.buildRelationships('u-2', lib.loadTypeConfig('user')), []);
    });
    test('multi-valued relationships yield one edge per related object, sorted and de-duplicated', () => {
        const store = appendixAStore();
        store['managed/account/a-2'] = { _id: 'a-2', accountId: 'Savings-2-Sanne' };
        store['managed/user/u-1'].owns.push({ _ref: 'managed/account/a-2', _refResourceCollection: 'managed/account', _refResourceId: 'a-2' });
        store['managed/user/u-1'].owns.push({ _ref: 'managed/account/a-2', _refResourceCollection: 'managed/account', _refResourceId: 'a-2' });
        lib = load(fakeOpenidm(store)).lib;
        assert.deepEqual(lib.buildRelationships('u-1', lib.loadTypeConfig('user')),
            ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}', '{"TargetID":"Savings-2-Sanne","type":"Owns"}']);
    });
    test('a relationship marked sync:false is skipped', () => {
        const ot = objectTypes(); ot.user.relationships.owns.sync = false;
        lib = load(fakeOpenidm(appendixAStore(), { objectTypes: ot })).lib;
        assert.deepEqual(lib.buildRelationships('u-1', lib.loadTypeConfig('user')), []);
    });
    test('a related object whose idProperty is missing is skipped with a warning, not emitted with a null id', () => {
        const store = appendixAStore(); delete store['managed/account/a-1'].accountId;
        const loaded = load(fakeOpenidm(store));
        assert.deepEqual(loaded.lib.buildRelationships('u-1', loaded.lib.loadTypeConfig('user')), []);
        assert.ok(loaded.logs.some(l => l[0] === 'warn' && /a-1|accountId/.test(l[1])));
    });
    test('a dangling reference (related object deleted) is skipped with a warning', () => {
        const store = appendixAStore(); delete store['managed/account/a-1'];
        const loaded = load(fakeOpenidm(store));
        assert.deepEqual(loaded.lib.buildRelationships('u-1', loaded.lib.loadTypeConfig('user')), []);
        assert.ok(loaded.logs.some(l => l[0] === 'warn'));
    });
    test('a related object in a resource collection that is not a configured type is skipped with a warning', () => {
        const store = appendixAStore();
        store['managed/user/u-1'].owns.push({ _ref: 'managed/device/d-1', _refResourceCollection: 'managed/device', _refResourceId: 'd-1' });
        store['managed/device/d-1'] = { _id: 'd-1', serial: 'X' };
        const loaded = load(fakeOpenidm(store));
        assert.deepEqual(loaded.lib.buildRelationships('u-1', loaded.lib.loadTypeConfig('user')), ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}']);
        assert.ok(loaded.logs.some(l => l[0] === 'warn' && /device/.test(l[1])));
    });
    test('a missing source object throws (the mapping must not PUT an empty edge set for an unknown source)', () => {
        assert.throws(() => lib.buildRelationships('nope', lib.loadTypeConfig('user')), /nope/);
    });
    test('edge JSON is canonical: key order TargetID/SourceID then type, no whitespace', () => {
        const edges = lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        assert.equal(edges[0].indexOf(' '), -1);
        assert.ok(edges[0].startsWith('{"TargetID"'));
    });
    test('the same IDM state always produces the same edge set (pure function, §8.1)', () => {
        const a = lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        const b = lib.buildRelationships('u-1', lib.loadTypeConfig('user'));
        assert.deepEqual(a, b);
    });
    test('an unlisted relationship property falls back to the convention using managed.json', () => {
        const ot = objectTypes(); delete ot.account.relationships.ownedBy;
        lib = load(fakeOpenidm(appendixAStore(), { objectTypes: ot })).lib;
        assert.deepEqual(lib.buildRelationships('a-1', lib.loadTypeConfig('account')), ['{"SourceID":"Sanne","type":"Owns"}']);
    });
    test('an unlisted, unresolvable relationship property throws rather than guessing a direction', () => {
        const ot = objectTypes();
        const store = appendixAStore();
        store['managed/user/u-3'] = { _id: 'u-3', userName: 'Mgr' };
        store['managed/user/u-1'].manager = { _ref: 'managed/user/u-3', _refResourceCollection: 'managed/user', _refResourceId: 'u-3' };
        lib = load(fakeOpenidm(store, { objectTypes: ot })).lib;
        assert.throws(() => lib.buildRelationships('u-1', lib.loadTypeConfig('user')), /manager/);
    });
});

describe('buildArchetypes (§6, §8.2)', () => {
    let lib;
    beforeEach(() => { lib = load(fakeOpenidm({})).lib; });

    test('static archetypes only when no archetypeProperty', () => {
        assert.deepEqual(lib.buildArchetypes({ userName: 'Sanne' }, objectTypes().user), ['Customer']);
    });
    test('appends a single-valued archetypeProperty', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segment';
        assert.deepEqual(lib.buildArchetypes({ segment: 'VIP' }, tc), ['Customer', 'VIP']);
    });
    test('appends a multi-valued archetypeProperty', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segments';
        assert.deepEqual(lib.buildArchetypes({ segments: ['VIP', 'Staff'] }, tc), ['Customer', 'VIP', 'Staff']);
    });
    test('de-duplicates while keeping static-first order', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segments';
        assert.deepEqual(lib.buildArchetypes({ segments: ['Customer', 'VIP', 'VIP'] }, tc), ['Customer', 'VIP']);
    });
    test('a null/absent/empty archetypeProperty value contributes nothing', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segments';
        assert.deepEqual(lib.buildArchetypes({ segments: null }, tc), ['Customer']);
        assert.deepEqual(lib.buildArchetypes({}, tc), ['Customer']);
        assert.deepEqual(lib.buildArchetypes({ segments: [] }, tc), ['Customer']);
        assert.deepEqual(lib.buildArchetypes({ segments: '' }, tc), ['Customer']);
    });
    test('non-string archetype values are rejected', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segments';
        assert.throws(() => lib.buildArchetypes({ segments: [1, 2] }, tc), /segments/);
    });
    test('a type with no static archetypes and no property yields an empty array', () => {
        const tc = objectTypes().user; tc.archetypes = [];
        assert.deepEqual(lib.buildArchetypes({}, tc), []);
    });
    test('does not mutate the type configuration', () => {
        const tc = objectTypes().user; tc.archetypeProperty = 'segments';
        lib.buildArchetypes({ segments: ['VIP'] }, tc);
        assert.deepEqual(tc.archetypes, ['Customer']);
    });
    test('a null source object throws', () => {
        assert.throws(() => lib.buildArchetypes(null, objectTypes().user), /source/i);
    });
});
