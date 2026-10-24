package org.ghost.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.ghost.domain.model.NodeId

/** P-256 / SHA-256. A signature authenticates content; it does not encrypt it. */
class SigningIdentity(private val privateKey: PrivateKey, val publicKey: PublicKey) {
    val nodeId: NodeId = nodeId(publicKey.encoded)
    init { decodePublicKey(publicKey.encoded) }

    fun sign(envelope: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(DOMAIN)
        update(envelope)
        sign()
    }

    companion object {
        private val DOMAIN = "GHOST-WIRE-V1\u0000".toByteArray(Charsets.UTF_8)
        fun nodeId(spki: ByteArray) = NodeId(hex(MessageDigest.getInstance("SHA-256").digest(spki)))
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decodePublicKey(spki: ByteArray): PublicKey {
            require(spki.size == 91) { "INVALID_KEY" }
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
            val expected = java.security.AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
                key.params.order == expected.order && key.params.cofactor == expected.cofactor && key.encoded.contentEquals(spki)) { "INVALID_KEY" }
            return key
        }
        fun verify(spki: ByteArray, envelope: ByteArray, signature: ByteArray): Boolean =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(decodePublicKey(spki)); update(DOMAIN); update(envelope); verify(signature)
            }
    }
}

/** Only absence creates a key. A corrupt/unusable existing identity must never silently rotate. */
class AndroidSigningIdentity {
    fun loadOrCreate(): SigningIdentity = synchronized(LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val privateKey = store.getKey(ALIAS, null) as? PrivateKey ?: error("KEY_UNAVAILABLE")
        val publicKey = store.getCertificate(ALIAS)?.publicKey ?: error("KEY_UNAVAILABLE")
        SigningIdentity(privateKey, publicKey)
    }
    companion object {
        private const val ALIAS = "ghost.identity.p256.v1"
        private val LOCK = Any()
    }
}
