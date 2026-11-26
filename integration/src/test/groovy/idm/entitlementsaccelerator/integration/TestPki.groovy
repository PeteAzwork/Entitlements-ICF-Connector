package idm.entitlementsaccelerator.integration

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.nio.file.Files
import java.security.KeyStore

/**
 * A private certificate authority for the TLS specs (docs/deployment-guide.md §5), made with the JDK's keytool the
 * way an operator would. It issues:
 * <ul>
 *   <li>{@code trusted}: a server certificate for localhost and 127.0.0.1, signed by the CA;</li>
 *   <li>{@code wrongHost}: signed by the CA, but for another host name;</li>
 *   <li>{@code untrusted}: self-signed, from no CA IDM knows.</li>
 * </ul>
 * The CA certificate is imported into IDM's trust store before IDM starts ({@link #trustIn}).
 */
class TestPki {
    static final String PASS = 'changeit'
    final File dir = Files.createTempDirectory('entitlementsaccelerator-pki').toFile()
    final File caPem = new File(dir, 'ca.pem')

    TestPki() {
        keytool('-genkeypair', '-alias', 'ca', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=entitlementsaccelerator IT test CA',
                '-ext', 'bc:c', '-validity', '3', '-keystore', file('ca.p12'), '-storetype', 'PKCS12', '-storepass', PASS)
        keytool('-exportcert', '-alias', 'ca', '-keystore', file('ca.p12'), '-storepass', PASS, '-rfc', '-file', caPem.path)
        issue('trusted', 'dns:localhost,ip:127.0.0.1')
        issue('wrongHost', 'dns:service.invalid')
        keytool('-genkeypair', '-alias', 'server', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=localhost',
                '-ext', 'san=dns:localhost,ip:127.0.0.1', '-validity', '3', '-keystore', file('untrusted.p12'),
                '-storetype', 'PKCS12', '-storepass', PASS)
    }

    /** An SSLContext serving the named certificate: trusted, wrongHost or untrusted. */
    SSLContext serverContext(String name) {
        KeyStore ks = KeyStore.getInstance('PKCS12')
        new File(dir, "${name}.p12").withInputStream { ks.load(it, PASS.toCharArray()) }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.defaultAlgorithm)
        kmf.init(ks, PASS.toCharArray())
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(kmf.keyManagers, null, null)
        ctx
    }

    /**
     * Imports the CA into IDM's trust store in a created, not yet started, container: the trust store is
     * {@code security/truststore} (JKS, password {@code changeit}), which IDM's JVM uses as javax.net.ssl.trustStore.
     */
    void trustIn(String container) {
        File ts = new File(dir, 'truststore')
        IdmStack.dockerChecked('cp', "${container}:/opt/openidm/security/truststore", ts.path)
        keytool('-importcert', '-noprompt', '-alias', 'entitlementsaccelerator-service-ca', '-file', caPem.path,
                '-keystore', ts.path, '-storepass', PASS)
        // Back as IDM's user (uid 11111, group 0) with mode 600, as the image has it: a root-owned trust store is
        // unreadable to IDM, which then fails to start (deployment guide §5). docker cp from a tar stream keeps them.
        Process p = new ProcessBuilder('sh', '-c',
            'tar --owner=11111 --group=0 --mode=600 -C "$1" -cf - truststore | docker cp - "$2":/opt/openidm/security/',
            'sh', dir.path, container).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        if (p.waitFor() != 0) throw new IllegalStateException("copying the trust store into IDM failed: ${out}")
    }

    private void issue(String name, String san) {
        String ks = file("${name}.p12")
        keytool('-genkeypair', '-alias', 'server', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=entitlementsaccelerator-service',
                '-validity', '3', '-keystore', ks, '-storetype', 'PKCS12', '-storepass', PASS)
        keytool('-certreq', '-alias', 'server', '-keystore', ks, '-storepass', PASS, '-file', file("${name}.csr"))
        keytool('-gencert', '-alias', 'ca', '-keystore', file('ca.p12'), '-storepass', PASS, '-infile', file("${name}.csr"),
                '-outfile', file("${name}.pem"), '-ext', "san=${san}", '-validity', '3', '-rfc')
        keytool('-importcert', '-noprompt', '-alias', 'ca', '-file', caPem.path, '-keystore', ks, '-storepass', PASS)
        keytool('-importcert', '-alias', 'server', '-file', file("${name}.pem"), '-keystore', ks, '-storepass', PASS)
    }

    private String file(String name) { new File(dir, name).path }

    private static void keytool(String... args) {
        String keytool = new File(System.getProperty('java.home'), 'bin/keytool').path
        Process p = new ProcessBuilder([keytool] + (args as List)).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        if (p.waitFor() != 0) throw new IllegalStateException("keytool ${args[0]} failed: ${out}")
    }
}
