package com.caedralpay.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted local records; missing key/corrupt file never becomes an empty record. */
internal class PrivateRecordStore(context: Context, name: String) {
    init { require(name.matches(Regex("[a-z_]{1,40}"))) }
    private val file = AtomicFile(context.filesDir.resolve("$name.bin"))
    private val aad = "CaedralPay/local/v1/$name".toByteArray()
    fun read(): ByteArray? {
        if (!file.baseFile.exists() && !java.io.File(file.baseFile.path + ".bak").exists()) return null
        val raw = file.openRead().use { it.readNBytes(65_565) }
        require(raw.size in 29..65_564)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            updateAAD(aad); doFinal(raw.copyOfRange(12, raw.size))
        }
    }
    fun write(clear: ByteArray) {
        require(clear.size <= 65_536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(clear)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (error: Throwable) { file.failWrite(out); throw error }
    }
    private fun key(create: Boolean): SecretKey = synchronized(PrivateRecordStore::class.java) {
        val alias = "caedral_local_records_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        check(create)
        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUnlockedDeviceRequired(true).build())
        }.generateKey()
    }
}
