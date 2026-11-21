/*
 * graphEntityPayload.js — shared payload assembly library for IDM mappings (design §8.2).
 *
 * Deployed to <idm>/script/lib/graphEntityPayload.js and loaded from mapping property transformations with
 *   var lib = require("lib/graphEntityPayload");
 *
 * Runs inside IDM's Rhino engine: ES5 only (no let/const/arrow functions/template literals), and the only
 * globals available are `openidm`, `logger` and what the mapping passes in.
 *
 * Responsibilities (see ../README.md and the executable specification in ../test):
 *   - loadTypeConfig(typeName)        read objectTypes from the provisioner config (single source of truth, §8.2), cached
 *   - resolveRelationship(...)        apply the §7 override-then-convention rule for one property
 *   - buildRelationships(sourceId, typeConfig)   enumerate forward + reverse relationship properties, resolve the
 *                                     related object's *remote ID* via the related type's idProperty, and return the
 *                                     edge set as canonical JSON strings {"sourceID","targetID","type"} (decision P17:
 *                                     both ends of a relationship emit the identical string)
 *   - buildArchetypes(sourceObject, typeConfig)  static archetypes + archetypeProperty values, de-duplicated
 *
 * The §7 rules (normalisePropertyName, isPassive, resolvePair) must match the connector's RelationshipConvention
 * exactly; both are tested against fixtures/convention-cases.json.
 */
(function (exports) {
    'use strict';

    var CONFIG_PATH = 'config/provisioner.openicf/graphentity';
    var MANAGED_PATH = 'config/managed';
    /** How long the provisioner and managed configuration are cached, unless options.ttlMs says otherwise. */
    var DEFAULT_TTL_MS = 5 * 60 * 1000;
    /** A capitalised Set/List suffix with at least one character before it. */
    var COLLECTION_SUFFIX = /^(.+?)(Set|List)$/;

    var cache = { objectTypes: null, managed: null, loadedAt: 0 };

    // ------------------------------------------------------------------ logging

    function log(level, message) {
        if (typeof logger !== 'undefined' && logger && typeof logger[level] === 'function') {
            logger[level]('graphEntityPayload: ' + message);
        }
    }

    // ------------------------------------------------------------------ §7 convention

    function stripSuffix(propertyName) {
        if (propertyName === null || propertyName === undefined || String(propertyName).trim() === '') {
            throw new Error('A relationship property name is required and must not be blank');
        }
        var name = String(propertyName).trim();
        var m = COLLECTION_SUFFIX.exec(name);
        return m ? m[1] : name;
    }

    /** owns → Owns, ownsSet → Owns, memberList → Member (§7). */
    exports.normalisePropertyName = function (propertyName) {
        var base = stripSuffix(propertyName);
        return base.charAt(0).toUpperCase() + base.substring(1);
    };

    /** True for the passive form, e.g. ownedBy (§7): after any Set/List suffix, a capital By with something before it. */
    exports.isPassive = function (propertyName) {
        return /^.+By$/.test(stripSuffix(propertyName));
    };

    function unresolved(reason) {
        return { resolved: false, reason: reason };
    }

    /**
     * Resolve a (property, reverseProperty) pair by convention.
     * Returns { resolved: true, remoteType, outboundProperty, inboundProperty } or { resolved: false, reason }.
     */
    exports.resolvePair = function (property, reverseProperty) {
        if (property === null || property === undefined || String(property).trim() === '') {
            return unresolved('the property name is blank');
        }
        if (reverseProperty === null || reverseProperty === undefined || String(reverseProperty).trim() === '') {
            return unresolved("'" + property + "' has no reverse property, so it is not bidirectional (design A2)");
        }
        var p = String(property).trim();
        var q = String(reverseProperty).trim();
        if (p === q) {
            return unresolved("'" + p + "' is its own reverse, which gives no direction");
        }
        var pPassive = exports.isPassive(p);
        var qPassive = exports.isPassive(q);
        var pair = [p, q].sort();
        if (pPassive === qPassive) {
            return unresolved((pPassive ? 'both' : 'neither') + " of '" + pair[0] + "' and '" + pair[1] + "' " +
                (pPassive ? 'are' : 'is') + " passive (ending in 'By'), so the convention cannot tell which is the source");
        }
        var active = pPassive ? q : p;
        var passive = pPassive ? p : q;
        return { resolved: true, remoteType: exports.normalisePropertyName(active), outboundProperty: active, inboundProperty: passive };
    };

    function typeNameOf(typeConfig) {
        if (typeConfig && typeConfig.name) {
            return typeConfig.name;
        }
        var resource = typeConfig && typeConfig.idmResource ? String(typeConfig.idmResource) : '';
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    /** The managed.json property definition of `property` on the type's managed object, or null. */
    function managedProperty(managedSchema, typeConfig, property) {
        if (!managedSchema || !managedSchema.objects) {
            return null;
        }
        var name = typeNameOf(typeConfig);
        var i;
        for (i = 0; i < managedSchema.objects.length; i++) {
            var object = managedSchema.objects[i];
            if (object && object.name === name) {
                var props = object.schema && object.schema.properties;
                return props && props[property] ? props[property] : null;
            }
        }
        return null;
    }

    function managedReverse(managedSchema, typeConfig, property) {
        var definition = managedProperty(managedSchema, typeConfig, property);
        if (!definition) {
            return null;
        }
        return definition.reversePropertyName || (definition.items && definition.items.reversePropertyName) || null;
    }

    function syncDisabled(entry) {
        return entry && (entry.sync === false || String(entry.sync).toLowerCase() === 'false');
    }

    /**
     * Resolve one property on a type: explicit override (remoteType + direction) wins; otherwise the convention
     * using reverseProperty (from the typeConfig entry, else from the managed schema). Returns null when
     * sync === false. Throws when unresolvable.
     * @returns {{remoteType: string, direction: 'outbound'|'inbound'}|null}
     */
    exports.resolveRelationship = function (property, typeConfig, managedSchema) {
        var entry = typeConfig && typeConfig.relationships ? typeConfig.relationships[property] : null;
        if (syncDisabled(entry)) {
            return null;
        }
        if (entry && entry.remoteType && entry.direction) {
            return { remoteType: entry.remoteType, direction: String(entry.direction).toLowerCase() };
        }
        var reverse = (entry && entry.reverseProperty) || managedReverse(managedSchema, typeConfig, property);
        if (!reverse) {
            throw new Error("Relationship '" + property + "' of " + typeNameOf(typeConfig) +
                ' has no reverse property in objectTypes or managed.json, so its direction cannot be derived (design A2, §7).' +
                ' Declare remoteType and direction for it.');
        }
        var pair = exports.resolvePair(property, reverse);
        if (!pair.resolved) {
            throw new Error("Relationship '" + property + "' (reverse '" + reverse + "') of " + typeNameOf(typeConfig) +
                ' cannot be resolved by the convention (§7): ' + pair.reason + '. Declare remoteType and direction for it.');
        }
        return { remoteType: pair.remoteType, direction: pair.outboundProperty === String(property).trim() ? 'outbound' : 'inbound' };
    };

    // ------------------------------------------------------------------ configuration

    /** Undoes Groovy string escapes (\' \" \\ \n \t \r \b \f \/ \uXXXX). */
    function unescapeGroovy(text) {
        return text.replace(/\\(u[0-9a-fA-F]{4}|.)/g, function (all, esc) {
            if (esc.charAt(0) === 'u' && esc.length === 5) {
                return String.fromCharCode(parseInt(esc.substring(1), 16));
            }
            switch (esc) {
                case 'n': return '\n';
                case 't': return '\t';
                case 'r': return '\r';
                case 'b': return '\b';
                case 'f': return '\f';
                default: return esc;
            }
        });
    }

    /** objectTypes from customConfiguration, a ConfigSlurper script such as  objectTypes='{...}'. */
    function objectTypesFromCustom(custom) {
        if (!custom) {
            return null;
        }
        var m = /(?:^|[\s;])objectTypes\s*=\s*(?:'((?:[^'\\]|\\[\s\S])*)'|"((?:[^"\\]|\\[\s\S])*)")/.exec(String(custom));
        if (!m) {
            return null;
        }
        return unescapeGroovy(m[1] !== undefined && m[1] !== null ? m[1] : m[2]);
    }

    function parseObjectTypes(raw) {
        if (raw && typeof raw === 'object') {
            return JSON.parse(JSON.stringify(raw));
        }
        try {
            return JSON.parse(String(raw));
        } catch (e) {
            throw new Error('objectTypes in ' + CONFIG_PATH + ' is not valid JSON: ' + e.message);
        }
    }

    function readObjectTypes() {
        var provisioner = openidm.read(CONFIG_PATH, null, null);
        if (!provisioner) {
            throw new Error('The provisioner configuration ' + CONFIG_PATH + ' was not found');
        }
        var props = provisioner.configurationProperties || {};
        var raw = props.objectTypes;
        if (raw === null || raw === undefined) {
            raw = objectTypesFromCustom(props.customConfiguration);
        }
        if (raw === null || raw === undefined || raw === '') {
            throw new Error('The provisioner configuration ' + CONFIG_PATH +
                ' has no objectTypes, in configurationProperties or customConfiguration');
        }
        var types = parseObjectTypes(raw);
        if (!types || typeof types !== 'object' || Object.keys(types).length === 0) {
            throw new Error('objectTypes in ' + CONFIG_PATH + ' defines no object types');
        }
        return types;
    }

    function ensureLoaded(options) {
        var ttl = options && typeof options.ttlMs === 'number' ? options.ttlMs : DEFAULT_TTL_MS;
        var stale = cache.objectTypes === null || (options && options.reload) || (Date.now() - cache.loadedAt) > ttl;
        if (stale) {
            cache.objectTypes = readObjectTypes();
            cache.managed = null;
            cache.loadedAt = Date.now();
        }
        return cache.objectTypes;
    }

    function withName(name, config) {
        var copy = JSON.parse(JSON.stringify(config));
        copy.name = name;
        return copy;
    }

    /**
     * Reads and caches the objectTypes document from the provisioner configuration. `options.reload` bypasses
     * the cache; `options.ttlMs` overrides its lifetime (default five minutes). Throws when the type is not
     * configured. Returns a copy, with the type's name attached.
     */
    exports.loadTypeConfig = function (typeName, options) {
        var types = ensureLoaded(options);
        if (!Object.prototype.hasOwnProperty.call(types, typeName)) {
            throw new Error("Object type '" + typeName + "' is not configured in " + CONFIG_PATH +
                '; configured types: ' + Object.keys(types).join(', '));
        }
        return withName(typeName, types[typeName]);
    };

    /** Clears the configuration cache (tests, and after a provisioner update). */
    exports.clearCache = function () {
        cache.objectTypes = null;
        cache.managed = null;
        cache.loadedAt = 0;
    };

    function managedSchema() {
        if (cache.managed === null) {
            cache.managed = openidm.read(MANAGED_PATH, null, null) || { objects: [] };
        }
        return cache.managed;
    }

    // ------------------------------------------------------------------ relationships

    /**
     * True for a JavaScript array or an array-like list. Inside IDM, openidm.read returns wrapped Java lists, for
     * which Array.isArray is false, so lists are recognised by their numeric length and read by index.
     */
    function isList(value) {
        return Array.isArray(value) ||
            (value !== null && typeof value === 'object' && typeof value.length === 'number' && value._ref === undefined);
    }

    function toArray(value) {
        var out = [];
        var i;
        for (i = 0; i < value.length; i++) {
            out.push(value[i]);
        }
        return out;
    }

    /** The relationship references held by a property value: one {_ref} object or a list of them. */
    function referencesOf(value) {
        if (value === null || value === undefined) {
            return [];
        }
        var list = isList(value) ? toArray(value) : [value];
        var refs = [];
        var i;
        for (i = 0; i < list.length; i++) {
            if (list[i] && typeof list[i] === 'object' && list[i]._ref) {
                refs.push(list[i]);
            }
        }
        return refs;
    }

    function isRelationshipValue(value) {
        if (isList(value)) {
            return value.length > 0 && referencesOf(value).length === value.length;
        }
        return referencesOf(value).length === 1;
    }

    /** Collection and id of a reference; some IDM versions return only _ref, so it is split when needed. */
    function target(ref) {
        var path = String(ref._ref);
        var cut = path.lastIndexOf('/');
        return {
            collection: ref._refResourceCollection || path.substring(0, cut),
            id: ref._refResourceId || path.substring(cut + 1),
            path: path
        };
    }

    function typeForCollection(types, collection) {
        var names = Object.keys(types);
        var i;
        for (i = 0; i < names.length; i++) {
            if (types[names[i]].idmResource === collection) {
                return withName(names[i], types[names[i]]);
            }
        }
        return null;
    }

    function edge(sourceId, targetId, type) {
        return '{"sourceID":' + JSON.stringify(String(sourceId)) + ',"targetID":' + JSON.stringify(String(targetId)) +
            ',"type":' + JSON.stringify(String(type)) + '}';
    }

    /**
     * Builds the complete edge set for the source object (A1/A2, §8.1): every synced relationship property,
     * forward and reverse, with each related object's remote ID resolved via the related type's idProperty.
     * Each edge names both ends: {"sourceID":<source remote id>,"targetID":<target remote id>,"type":<remoteType>},
     * keys in that order, no whitespace (P17). For an inbound property the related object is the source.
     *
     * A relationship property not listed in objectTypes is resolved by the convention through managed.json when it
     * points at a configured type, and ignored (debug) when it does not (roles, for example). A reference to an
     * unconfigured type, a deleted object, or an object without its remote ID is skipped with a warning.
     * @param sourceId the IDM _id of the source object
     * @returns {string[]} canonical JSON strings, sorted, de-duplicated
     */
    exports.buildRelationships = function (sourceId, typeConfig, options) {
        var types = ensureLoaded(options);
        var sourcePath = typeConfig.idmResource + '/' + sourceId;
        var source = openidm.read(sourcePath, null, ['*', '*_ref']);
        if (!source) {
            throw new Error('Cannot build relationships: ' + sourcePath + ' (' + sourceId + ') does not exist');
        }
        var me = source[typeConfig.idProperty];
        if (me === null || me === undefined || String(me) === '') {
            throw new Error('Cannot build relationships: ' + sourcePath + ' has no ' + typeConfig.idProperty + ', its remote ID');
        }
        var related = {};   // per-call cache of related remote IDs, by path
        var seen = {};
        var edges = [];
        var listed = typeConfig.relationships || {};

        Object.keys(source).sort().forEach(function (property) {
            if (property.charAt(0) === '_' || !isRelationshipValue(source[property])) {
                return;
            }
            var refs = referencesOf(source[property]);
            var isListed = Object.prototype.hasOwnProperty.call(listed, property);
            if (!isListed) {
                var configured = refs.some(function (ref) { return typeForCollection(types, target(ref).collection) !== null; });
                if (!configured) {
                    log('debug', "ignoring relationship '" + property + "' of " + sourcePath + ': it points at no configured type');
                    return;
                }
            }
            var resolution = exports.resolveRelationship(property, typeConfig, managedSchema());
            if (resolution === null) {
                return;
            }
            refs.forEach(function (ref) {
                var t = target(ref);
                var relatedType = typeForCollection(types, t.collection);
                if (relatedType === null) {
                    log('warn', "skipping " + t.path + " in '" + property + "' of " + sourcePath + ': ' + t.collection + ' is not a configured object type');
                    return;
                }
                if (!Object.prototype.hasOwnProperty.call(related, t.path)) {
                    var object = openidm.read(t.path, null, [relatedType.idProperty]);
                    if (!object) {
                        log('warn', "skipping " + t.path + " in '" + property + "' of " + sourcePath + ': the object does not exist');
                        related[t.path] = null;
                    } else if (object[relatedType.idProperty] === null || object[relatedType.idProperty] === undefined ||
                        String(object[relatedType.idProperty]) === '') {
                        log('warn', 'skipping ' + t.path + " (" + t.id + ") in '" + property + "' of " + sourcePath + ': it has no ' +
                            relatedType.idProperty + ', its remote ID');
                        related[t.path] = null;
                    } else {
                        related[t.path] = String(object[relatedType.idProperty]);
                    }
                }
                var other = related[t.path];
                if (other === null) {
                    return;
                }
                var text = resolution.direction === 'outbound' ? edge(me, other, resolution.remoteType) : edge(other, me, resolution.remoteType);
                if (!seen[text]) {
                    seen[text] = true;
                    edges.push(text);
                }
            });
        });
        return edges.sort();
    };

    // ------------------------------------------------------------------ archetypes

    /** Static archetypes plus archetypeProperty values (string or array), de-duplicated, order-stable. */
    exports.buildArchetypes = function (sourceObject, typeConfig) {
        if (sourceObject === null || sourceObject === undefined) {
            throw new Error('buildArchetypes needs the source object');
        }
        var result = [];
        function add(value) {
            if (result.indexOf(value) < 0) {
                result.push(value);
            }
        }
        toArray(typeConfig.archetypes || []).forEach(add);
        var property = typeConfig.archetypeProperty;
        if (property) {
            var raw = sourceObject[property];
            var values = raw === null || raw === undefined || raw === '' ? [] : (isList(raw) && typeof raw !== 'string' ? toArray(raw) : [raw]);
            values.forEach(function (value) {
                if (typeof value !== 'string') {
                    throw new Error("archetypeProperty '" + property + "' holds a " + typeof value + '; archetype names must be strings');
                }
                if (value.trim() !== '') {
                    add(value.trim());
                }
            });
        }
        return result;
    };

    // ------------------------------------------------------------------ idProperty changes (plan WS9.04)

    /**
     * Guards a change of the type's idProperty, for a managed object's onUpdate hook:
     *   require('lib/graphEntityPayload').onIdPropertyChange(newObject, oldObject, 'user');
     *
     * The remote ID is the idProperty and cannot change (design A3): the connector refuses an update whose __NAME__
     * differs from the entity's ID. So when the idProperty changes, this deletes the entity under its old ID in the
     * service and removes IDM's links for the mappings that target it; implicit sync then finds no target and creates
     * the entity under its new ID, with its relationships. A failure to delete propagates, so the rename fails rather
     * than leaving the service out of step; an entity already gone is fine.
     * @param options.connector the connector name (default 'graphentity')
     * @returns true when the idProperty changed and the old entity was handled
     */
    exports.onIdPropertyChange = function (newObject, oldObject, typeName, options) {
        if (!newObject || !oldObject) {
            return false;
        }
        var typeConfig = exports.loadTypeConfig(typeName, options);
        var property = typeConfig.idProperty;
        var oldId = oldObject[property];
        var newId = newObject[property];
        if (oldId === null || oldId === undefined || String(oldId) === '' || String(oldId) === String(newId)) {
            return false;
        }
        var connector = (options && options.connector) || 'graphentity';
        var target = 'system/' + connector + '/' + typeName;

        try {
            openidm['delete'](target + '/' + oldId, null);
            log('info', typeName + ' ' + property + " changed from '" + oldId + "' to '" + newId + "': deleted " + target + '/' + oldId +
                ', to be re-created under the new ID');
        } catch (e) {
            if (!isNotFound(e)) {
                throw e;
            }
            log('info', typeName + ' ' + property + " changed; " + target + '/' + oldId + ' was already absent');
        }

        var sync = openidm.read('config/sync', null, null) || {};
        var mappings = sync.mappings || [];
        var i;
        for (i = 0; i < mappings.length; i++) {
            if (mappings[i].source === typeConfig.idmResource && mappings[i].target === target) {
                removeLinks(mappings[i].name, oldObject._id || newObject._id);
            }
        }
        return true;
    };

    function removeLinks(mapping, sourceId) {
        var filter = 'linkType eq "' + mapping + '" and firstId eq "' + sourceId + '"';
        var found = openidm.query('repo/link', { _queryFilter: filter }, null);
        var links = (found && found.result) || [];
        var i;
        for (i = 0; i < links.length; i++) {
            openidm['delete']('repo/link/' + links[i]._id, null);
        }
    }

    function isNotFound(e) {
        var text = String((e && (e.javaException || e.message)) || e);
        return /404|not ?found|NotFound|UnknownUid/i.test(text);
    }

    exports.CONFIG_PATH = CONFIG_PATH;
})(typeof exports !== 'undefined' ? exports : (this.graphEntityPayload = {}));
