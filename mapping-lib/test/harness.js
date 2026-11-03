'use strict';
/*
 * Loads graphEntityPayload.js the way IDM's Rhino would: in an isolated context with `openidm` and `logger`
 * globals and a CommonJS-style `exports`. Node's vm module stands in for Rhino; the library must stay ES5.
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const LIB = fs.readFileSync(path.join(__dirname, '..', 'src', 'lib', 'graphEntityPayload.js'), 'utf8');

/** The §6 objectTypes document, with discovery-emitted relatedType/reverseProperty hints. */
function objectTypes() {
    return {
        user: {
            idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName',
            attributes: ['name', 'mail', 'telephoneNumber'], archetypes: ['Customer'], archetypeProperty: null,
            relationships: { owns: { remoteType: 'Owns', direction: 'outbound', relatedType: 'account', reverseProperty: 'ownedBy' } }
        },
        account: {
            idmResource: 'managed/account', entityKind: 'resource', idProperty: 'accountId',
            attributes: ['name', 'productCode'], archetypes: ['Account'],
            relationships: { ownedBy: { remoteType: 'Owns', direction: 'inbound', relatedType: 'user', reverseProperty: 'owns' } }
        }
    };
}

/** managed.json fragment used for the convention fallback (reversePropertyName lookup). */
function managedSchema() {
    return {
        objects: [
            { name: 'user', schema: { properties: {
                userName: { type: 'string' },
                owns: { type: 'array', items: { type: 'relationship', reversePropertyName: 'ownedBy', resourceCollection: [{ path: 'managed/account' }] } },
                manager: { type: 'relationship', reversePropertyName: 'reports', resourceCollection: [{ path: 'managed/user' }] },
                reports: { type: 'array', items: { type: 'relationship', reversePropertyName: 'manager', resourceCollection: [{ path: 'managed/user' }] } }
            } } },
            { name: 'account', schema: { properties: {
                accountId: { type: 'string' },
                ownedBy: { type: 'relationship', reversePropertyName: 'owns', resourceCollection: [{ path: 'managed/user' }] }
            } } }
        ]
    };
}

/**
 * A fake `openidm` binding backed by an in-memory store of managed objects.
 * store: { 'managed/user/<_id>': {...}, 'managed/account/<_id>': {...} }
 */
function fakeOpenidm(store, opts) {
    opts = opts || {};
    const calls = [];
    const provisioner = {
        _id: 'provisioner.openicf/graphentity',
        configurationProperties: {
            serviceAddress: 'http://localhost:5276',
            username: 'svc-idm-sync',
            customConfiguration: "objectTypes='" + JSON.stringify(opts.objectTypes || objectTypes()).replace(/'/g, "\\'") + "'"
        }
    };
    const managed = opts.managedSchema || managedSchema();
    return {
        calls,
        read(id, params, fields) {
            calls.push({ op: 'read', id, fields: fields || null });
            if (id === 'config/provisioner.openicf/graphentity') return JSON.parse(JSON.stringify(provisioner));
            if (id === 'config/managed') return JSON.parse(JSON.stringify(managed));
            const obj = store[id];
            if (!obj) return null;
            // Emulate IDM: relationship fields are returned only when requested via fields ("*_ref" or by name).
            const copy = JSON.parse(JSON.stringify(obj));
            const wantRefs = fields && (fields.indexOf('*_ref') >= 0);
            if (!wantRefs) {
                Object.keys(copy).forEach(k => { if (Array.isArray(copy[k]) && copy[k].length && copy[k][0]._ref) delete copy[k]; if (copy[k] && copy[k]._ref) delete copy[k]; });
            }
            return copy;
        },
        query(id, params) { calls.push({ op: 'query', id, params }); return { result: [] }; }
    };
}

function load(openidm, extraGlobals) {
    const logs = [];
    const context = {
        openidm,
        logger: { debug: (m) => logs.push(['debug', m]), info: (m) => logs.push(['info', m]), warn: (m) => logs.push(['warn', m]), error: (m) => logs.push(['error', m]) },
        exports: {},
        JSON
    };
    Object.assign(context, extraGlobals || {});
    vm.createContext(context);
    vm.runInContext(LIB, context, { filename: 'graphEntityPayload.js' });
    return { lib: context.exports, logs };
}

/** Appendix A store: Sanne owns Current-Account-1-Sanne (visible from both sides). */
function appendixAStore() {
    return {
        'managed/user/u-1': { _id: 'u-1', userName: 'Sanne', name: 'Sanne Groen',
            owns: [{ _ref: 'managed/account/a-1', _refResourceCollection: 'managed/account', _refResourceId: 'a-1', _refProperties: { _id: 'r-1' } }] },
        'managed/account/a-1': { _id: 'a-1', accountId: 'Current-Account-1-Sanne', name: 'Current Account', productCode: 'CA1',
            ownedBy: { _ref: 'managed/user/u-1', _refResourceCollection: 'managed/user', _refResourceId: 'u-1', _refProperties: { _id: 'r-1' } } }
    };
}

module.exports = { load, fakeOpenidm, objectTypes, managedSchema, appendixAStore };
