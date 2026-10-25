package org.ghost.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.ghost.protocol.ChatProtocol
import java.security.*
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.*

/** Separate encryption identity. No signing key is repurposed for decryption. */
class ChatCrypto(private val pair:KeyPair) {
    val publicKey:ByteArray get()=pair.public.encoded
    data class Sealed(val nonce:ByteArray,val ciphertext:ByteArray,val wrapped:ByteArray,val ownWrapped:ByteArray)
    fun seal(text:String,recipient:ByteArray,aad:ByteArray):Sealed {
        require(text.isNotBlank() && text.length<=1000)
        val key=ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val nonce=ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(aad)
            fun wrap(public:ByteArray)=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
                init(Cipher.ENCRYPT_MODE,ChatProtocol.rsa(public),OAEP);doFinal(key)
            }
            return Sealed(nonce,cipher.doFinal(text.toByteArray(Charsets.UTF_8)),wrap(recipient),wrap(publicKey))
        } finally { key.fill(0) }
    }
    fun open(nonce:ByteArray,ciphertext:ByteArray,wrapped:ByteArray,aad:ByteArray):String {
        val key=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {init(Cipher.DECRYPT_MODE,pair.private,OAEP);doFinal(wrapped)}
        try {
            require(key.size==32)
            val clear=Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD(aad);doFinal(ciphertext)
            }
            try { return clear.toString(Charsets.UTF_8).also { require(it.isNotBlank() && it.length<=1000) } }
            finally {clear.fill(0)}
        } finally {key.fill(0)}
    }
    companion object {
        private val OAEP=OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT)
        private const val ALIAS="ghost.chat.rsa.v1"
        fun load():ChatCrypto=synchronized(ChatCrypto::class.java) {
            val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            if(!store.containsAlias(ALIAS))KeyPairGenerator.getInstance("RSA","AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
            ChatCrypto(KeyPair(requireNotNull(store.getCertificate(ALIAS)?.publicKey),store.getKey(ALIAS,null) as PrivateKey))
        }
        fun aad(proposal:String,message:String,sender:String,recipient:String)=
            "GHOST-CHAT-V1\u0000$proposal\u0000$message\u0000$sender\u0000$recipient".toByteArray(Charsets.UTF_8)
    }
}
