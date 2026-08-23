package dev.dankyeeter.btdashboard.privileged.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The app's ADB identity: one RSA key pair and a certificate to wear it.
 *
 * `adbd` decides whether to accept a connection by looking at the client
 * certificate offered during the TLS handshake and checking whether that key is
 * on its trusted list. Pairing is what puts it there. So this key **must
 * outlive reboots and app updates** - a fresh key would mean pairing again, and
 * the whole point of the exercise is that the user pairs once.
 *
 * ## Why the key sits in a plain file
 *
 * Not in the Android Keystore, and that is a deliberate trade rather than an
 * oversight. Keystore-backed keys are non-exportable, which is exactly what
 * makes them safe - but the TLS stack needs a `PrivateKey` it can use with a
 * self-signed certificate the app builds itself, and hardware-backed keys make
 * that path considerably more awkward for no benefit here: the key's only power
 * is "may talk to adbd on this device", and anything able to read the app's
 * private storage already has more than that.
 *
 * ## Why a certificate at all
 *
 * TLS client authentication needs one; adbd only ever looks at the public key
 * inside it. It is self-signed, valid for a long time, and its subject is
 * cosmetic - it shows up in nothing the user sees.
 */
class AdbKeyStore(context: Context) {

    private val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
    private val privateFile = File(keyDir, "adbkey")
    private val publicFile = File(keyDir, "adbkey.pub")

    /** True once a key exists, i.e. the app has an identity to be trusted. */
    val exists: Boolean get() = privateFile.exists() && publicFile.exists()

    /** Loads the stored pair, creating one on first use. */
    fun keyPair(): KeyPair = synchronized(this) {
        if (exists) {
            runCatching { load() }
                .onFailure { Log.w(TAG, "stored ADB key unreadable, generating a new one", it) }
                .getOrNull()
                ?.let { return it }
        }
        return generate().also(::store)
    }

    /**
     * The public key in the format `adbd` writes into its trusted list.
     *
     * Kept because a human comparing this against `/data/misc/adb/adb_keys` is
     * the fastest way to answer "is this app actually trusted", which is the
     * first question when a connection is refused.
     */
    fun publicKeyBase64(): String =
        Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)

    /**
     * The public key in the shape `adbd` stores in its trusted list.
     *
     * **Not** the standard `SubjectPublicKeyInfo` that [publicKeyBase64]
     * returns. adb carries its own packed structure, inherited from a
     * bootloader-era Montgomery implementation, and stores base64 of *that*
     * followed by a space and a name:
     *
     * ```
     * uint32  len       modulus length in 32-bit words (64 for RSA-2048)
     * uint32  n0inv     -1 / n[0] mod 2^32
     * uint32  n[64]     modulus, little-endian words
     * uint32  rr[64]    R^2 mod n, with R = 2^2048
     * int32   exponent  65537
     * ```
     *
     * Everything is little-endian. `n0inv` and `rr` are precomputed Montgomery
     * helpers that adb could derive but stores instead - they must be right,
     * because the daemon reads them rather than recomputing.
     *
     * Sending an X.509 blob here does not fail loudly: the daemon happily
     * writes the line into `adb_keys` and then never matches it against the
     * certificate we present, so pairing "succeeds" and every later connection
     * is refused as untrusted.
     *
     * The trailing name is what a person sees when reviewing trusted keys.
     */
    fun adbFormatPublicKey(name: String = DEFAULT_KEY_NAME): String {
        val modulus = (keyPair().public as RSAPublicKey).modulus
        val words = modulus.bitLength().let { (it + 31) / 32 }
        require(words == RSA_WORDS) { "expected a $KEY_SIZE-bit key, got ${modulus.bitLength()}" }

        val buffer = ByteBuffer.allocate(4 + 4 + RSA_WORDS * 4 * 2 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(RSA_WORDS)

        // n0inv = -(n mod 2^32)^-1 mod 2^32, as an unsigned 32-bit value.
        val base = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.mod(base)
        buffer.putInt(base.subtract(n0.modInverse(base)).toInt())

        putLittleEndianWords(buffer, modulus)

        // rr = R^2 mod n, R = 2^(32 * words)
        val rr = BigInteger.ONE.shiftLeft(32 * RSA_WORDS * 2).mod(modulus)
        putLittleEndianWords(buffer, rr)

        buffer.putInt(EXPONENT)

        val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        return "$encoded $name"
    }

    /** Writes [value] as exactly [RSA_WORDS] little-endian 32-bit words. */
    private fun putLittleEndianWords(buffer: ByteBuffer, value: BigInteger) {
        var remaining = value
        val mask = BigInteger.ONE.shiftLeft(32) - BigInteger.ONE
        repeat(RSA_WORDS) {
            buffer.putInt(remaining.and(mask).toLong().toInt())
            remaining = remaining.shiftRight(32)
        }
    }

    /** Forgets the identity; the next connection will need pairing again. */
    fun clear() = synchronized(this) {
        privateFile.delete()
        publicFile.delete()
        Unit
    }

    private fun generate(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()

    private fun store(pair: KeyPair) {
        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
    }

    private fun load(): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val private: PrivateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        val public: PublicKey = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
        return KeyPair(public, private)
    }

    /**
     * A self-signed certificate around [keyPair].
     *
     * Built with the platform's hidden X.509 generator via reflection, because
     * the public API offers no way to create a certificate and pulling in
     * BouncyCastle for one self-signed cert would add megabytes to an app that
     * has none of it. If the hidden class ever disappears this throws, which is
     * the honest outcome: without a certificate there is no TLS handshake, and
     * pretending otherwise would fail later and less clearly.
     */
    fun certificate(): X509Certificate {
        val pair = keyPair()
        val generatorClass = Class.forName("com.android.org.bouncycastle.x509.X509V3CertificateGenerator")
        val generator = generatorClass.getDeclaredConstructor().newInstance()

        fun call(name: String, type: Class<*>, value: Any) {
            generatorClass.getMethod(name, type).invoke(generator, value)
        }

        val now = System.currentTimeMillis()
        call("setSerialNumber", BigInteger::class.java, BigInteger.valueOf(now))
        call("setSubjectDN", X500Principal::class.java, X500Principal(SUBJECT))
        call("setIssuerDN", X500Principal::class.java, X500Principal(SUBJECT))
        call("setNotBefore", Date::class.java, Date(now - BACKDATE_MS))
        call("setNotAfter", Date::class.java, Date(now + VALIDITY_MS))
        call("setPublicKey", PublicKey::class.java, pair.public)
        call("setSignatureAlgorithm", String::class.java, SIGNATURE_ALGORITHM)

        val generate = generatorClass.getMethod("generate", PrivateKey::class.java)
        return generate.invoke(generator, pair.private) as X509Certificate
    }

    private companion object {
        const val TAG = "AdbKeyStore"
        const val KEY_SIZE = 2048

        /** 2048-bit modulus as 32-bit words; adb's structure is fixed-width. */
        const val RSA_WORDS = KEY_SIZE / 32
        const val EXPONENT = 65537

        /** Shown next to the key when someone reviews the trusted list. */
        const val DEFAULT_KEY_NAME = "btdashboard@android"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val SUBJECT = "CN=BT Dashboard"

        /** Guards against a phone whose clock is a little behind the CA-less world. */
        const val BACKDATE_MS = 24L * 60 * 60 * 1000

        /** Ten years: the key is meant to be paired once and never thought about again. */
        const val VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
