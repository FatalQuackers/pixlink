package app.pixlink.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's TLS identity. The key lives in the Android Keystore (never leaves the
 * secure hardware) and Android creates a self-signed certificate for it automatically.
 */
object Identity {
    private const val ALIAS = "pixlink-client"
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=Pixlink Phone"))
                .build()
        )
        gen.generateKeyPair()
    }

    /** SHA-256 of our certificate, in AA:BB:CC form. The PC pins this. */
    val fingerprint: String
        get() {
            ensureKey()
            return sha256(keyStore.getCertificate(ALIAS).encoded)
        }

    /** TLS socket factory that presents our cert and trusts only the pinned PC cert. */
    fun socketFactory(pinnedPcFingerprint: String): SSLSocketFactory {
        ensureKey()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(kmf.keyManagers, arrayOf(PinnedTrustManager(pinnedPcFingerprint)), null)
        return ctx.socketFactory
    }

    fun sha256(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    /** Accepts exactly one certificate: the one whose fingerprint was in the QR code. */
    private class PinnedTrustManager(private val expected: String) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No certificate")
            val actual = sha256(leaf.encoded)
            if (!actual.equals(expected.replace("SHA256:", ""), ignoreCase = true)) {
                throw CertificateException("PC certificate does not match the paired PC")
            }
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
