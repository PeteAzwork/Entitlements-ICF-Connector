package au.com.azwork.idm.entitlementsaccelerator.support

/**
 * Runs the in-memory stand-in for the Entitlements Accelerator Service on its own, for development against a local IDM (WS7.06,
 * dev/README.md): {@code ./gradlew :connector:runFakeService}. It has the real service's HTTP semantics as far as they
 * are known (FakeGraphService), keeps state in memory only, and seeds a manifest defining the sample archetypes.
 *
 * <p>Settings, from the environment: {@code GRAPH_FAKE_PORT} (default 5276), {@code GRAPH_FAKE_BIND} (default
 * 0.0.0.0, so a container can reach it through host.docker.internal) and {@code GRAPH_FAKE_API_KEY} (default
 * {@code dev-api-key}). Development only: never expose it beyond your machine.
 */
class FakeServiceMain {
    static void main(String[] args) {
        int port = (System.getenv('GRAPH_FAKE_PORT') ?: '5276') as int
        String bind = System.getenv('GRAPH_FAKE_BIND') ?: '0.0.0.0'
        String key = System.getenv('GRAPH_FAKE_API_KEY') ?: 'dev-api-key'
        FakeGraphService service = new FakeGraphService()
        service.seedManifest('dev', ['Customer'], ['Account'])
        FakeGraphHttpServer server = new FakeGraphHttpServer(service, key, FakeGraphHttpServer.DEFAULT_API_KEY_HEADER,
            new InetSocketAddress(bind, port)).start()
        println "Fake Entitlements Accelerator Service on http://${bind}:${server.port} (state in memory; Ctrl-C to stop)"
        Thread.currentThread().join()
    }
}
