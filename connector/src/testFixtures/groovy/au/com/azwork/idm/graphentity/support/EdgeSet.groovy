package au.com.azwork.idm.graphentity.support

/**
 * The fake service's relationship store: a set of [sourceId, targetId, type] that also indexes each relationship by
 * both of its entities, so "every relationship touching X" is a lookup rather than a scan. It behaves as an ordinary
 * {@link Set} (insertion order, equality, contains, iterator removal), so tests can keep using it directly; the index
 * keeps the WS8.11 scale run (100,000 entities) from measuring the fake instead of IDM.
 */
class EdgeSet extends AbstractSet<List<String>> {
    private final LinkedHashSet<List<String>> all = new LinkedHashSet<>()
    private final Map<String, LinkedHashSet<List<String>>> byEntity = new HashMap<>()

    @Override
    int size() { all.size() }

    @Override
    boolean contains(Object o) { all.contains(o) }

    @Override
    boolean add(List<String> edge) {
        List<String> e = Collections.unmodifiableList(new ArrayList<String>(edge))
        if (!all.add(e)) return false
        index(e[0]).add(e)
        index(e[1]).add(e)
        true
    }

    @Override
    boolean remove(Object o) {
        if (!all.remove(o)) return false
        List<String> e = o as List<String>
        unindex(e)
        true
    }

    @Override
    void clear() { all.clear(); byEntity.clear() }

    @Override
    Iterator<List<String>> iterator() {
        Iterator<List<String>> inner = all.iterator()
        new Iterator<List<String>>() {
            List<String> last
            boolean hasNext() { inner.hasNext() }
            List<String> next() { last = inner.next(); last }
            void remove() { inner.remove(); unindex(last) }
        }
    }

    /** Every relationship with {@code id} as source or target, in insertion order. */
    List<List<String>> touching(String id) { new ArrayList<List<String>>(byEntity.get(id) ?: []) }

    /** Removes every relationship touching {@code id}. */
    void removeTouching(String id) { touching(id).each { remove(it) } }

    private Set<List<String>> index(String id) { byEntity.computeIfAbsent(id) { new LinkedHashSet<List<String>>() } }

    private void unindex(List<String> e) {
        [e[0], e[1]].each { String id ->
            Set<List<String>> s = byEntity.get(id)
            if (s != null) { s.remove(e); if (s.isEmpty()) byEntity.remove(id) }
        }
    }
}
