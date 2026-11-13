package com.caedralpay.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class WalletData internal constructor(internal val catalog: WalletCatalog) {
    val name: String get() = catalog.selected.name
    val mnemonic: String get() = catalog.selected.mnemonic
    override fun toString(): String = "WalletData([REDACTED])"
}

class WrongPinException : Exception()
class PinTemporarilyLockedException(val remainingSeconds: Long) : Exception()
class VaultUnavailableException(cause: Throwable? = null) : Exception(cause)
class VaultSaveException(val stage: String, cause: Throwable) : Exception(cause)
class WalletAccountException(message: String) : Exception(message)
enum class VaultAction { RemoveAccount, RevealPhrase, ChangePin, SignTransfer, SignOrigin, SignInvoice, ViewCard, ArmCard, RegenerateCard }
class VaultAuthorization internal constructor() {
    override fun toString() = "VaultAuthorization([REDACTED])"
}

object WalletVault {
    private const val FILE_NAME = "wallet_vault.bin"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val DEVICE_KEY_ALIAS = "caedral_device_vault_v1"
    private const val FILE_MAGIC = 0x43445631 // CDV1
    private const val RECORD_VERSION = 1
    private const val KDF_ITERATIONS = 600_000
    private const val MAX_FIELD_SIZE = 1_048_576
    private const val ATTEMPT_PREFS = "wallet_pin_attempts"
    private val random = SecureRandom()
    private val outerAad = "CaedralPay/device-vault/v1".toByteArray(Charsets.UTF_8)
    private val payloadAad = "CaedralPay/wallet-payload/v1".toByteArray(Charsets.UTF_8)
    private val pinAad = "CaedralPay/pin-wrapped-key/v1".toByteArray(Charsets.UTF_8)
    private var session: VaultSession? = null
    private class Grant(val token: VaultAuthorization, val session: VaultSession, val action: VaultAction,
        val walletId: String, val accountIndex: Int?, val expiresAt: Long, val binding: String?)
    private var grant: Grant? = null
    private class OriginConsent(val permit:OriginPaymentPermit,val session:VaultSession,val walletId:String,val index:Int,
        val draft:OriginPaymentDraft,var next:Int,var gasRemaining:java.math.BigInteger,val deadline:Long)
    private var originConsent:OriginConsent?=null

    private class VaultSession(val key: ByteArray, val snapshot: WalletAccountsSnapshot)

    @Synchronized
    fun lock() {
        OriginTapSession.clear()
        originConsent=null
        grant = null
        session?.key?.fill(0)
        session = null
    }

    @Synchronized
    fun accountsSnapshot(): WalletAccountsSnapshot = checkNotNull(session) { "Carteira bloqueada." }.snapshot

    fun exists(context: Context): Boolean = vaultFile(context).baseFile.isFile

    fun hasBiometric(context: Context): Boolean = readRecord(context).biometricWrappedKey != null

    @Synchronized
    fun create(
        context: Context,
        walletName: String,
        words: List<String>,
        pin: CharArray,
        authenticatedBiometricCipher: Cipher? = null
    ) {
        require(walletName.isNotBlank())
        require(words.size == 12)
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        check(!exists(context)) { "Já existe uma carteira neste dispositivo." }

        val dataKeyBytes = ByteArray(32).also(random::nextBytes)
        val pinSalt = ByteArray(32).also(random::nextBytes)
        var pinKeyBytes: ByteArray? = null
        var stage = "payload-encryption"
        try {
            val dataKey = SecretKeySpec(dataKeyBytes, "AES")
            val catalog = WalletCatalog.first(walletName.trim(), words.joinToString(" "))
            val snapshot = catalog.snapshot()
            val payload = WalletCatalogCodec.encode(catalog)
            val payloadEncrypted = try {
                encrypt(dataKey, payload, payloadAad)
            } finally {
                payload.fill(0)
            }

            stage = "pin-key-derivation"
            pinKeyBytes = derivePinKey(pin, pinSalt)
            stage = "pin-key-wrapping"
            val pinEncrypted = encrypt(SecretKeySpec(pinKeyBytes, "AES"), dataKeyBytes, pinAad)

            stage = "biometric-key-wrapping"
            val biometricWrapped = authenticatedBiometricCipher?.let {
                BiometricCrypto.finishAuthenticatedOperation(it, dataKeyBytes)
            }
            val biometricIv = authenticatedBiometricCipher?.iv?.clone()

            stage = "device-keystore-and-file-write"
            writeRecord(
                context,
                VaultRecord(
                    pinSalt = pinSalt,
                    pinIv = pinEncrypted.iv,
                    pinWrappedKey = pinEncrypted.ciphertext,
                    payloadIv = payloadEncrypted.iv,
                    payloadCiphertext = payloadEncrypted.ciphertext,
                    biometricIv = biometricIv,
                    biometricWrappedKey = biometricWrapped
                )
            )
            lock()
            session = VaultSession(dataKeyBytes.clone(), snapshot)
        } catch (error: Exception) {
            throw VaultSaveException(stage, error)
        } finally {
            pin.fill('\u0000')
            dataKeyBytes.fill(0)
            pinKeyBytes?.fill(0)
        }
    }

    @Synchronized
    fun unlockWithPin(context: Context, pin: CharArray): WalletData {
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        val remaining = remainingLockMillis(context)
        if (remaining > 0) {
            pin.fill('\u0000')
            throw PinTemporarilyLockedException((remaining + 999) / 1_000)
        }
        var pinKeyBytes: ByteArray? = null
        var dataKeyBytes: ByteArray? = null
        try {
            val record = readRecord(context)
            pinKeyBytes = derivePinKey(pin, record.pinSalt)
            dataKeyBytes = try {
                decrypt(SecretKeySpec(pinKeyBytes, "AES"), record.pinIv, record.pinWrappedKey, pinAad)
            } catch (_: AEADBadTagException) {
                recordFailedAttempt(context)
                throw WrongPinException()
            }
            return decryptPayload(record, dataKeyBytes).also {
                val snapshot = it.catalog.snapshot()
                lock()
                session = VaultSession(dataKeyBytes.clone(), snapshot)
                clearFailedAttempts(context)
            }
        } finally {
            pin.fill('\u0000')
            pinKeyBytes?.fill(0)
            dataKeyBytes?.fill(0)
        }
    }

    fun biometricCipherForUnlock(context: Context): Cipher {
        val record = readRecord(context)
        val iv = record.biometricIv ?: throw VaultUnavailableException()
        if (record.biometricWrappedKey == null) throw VaultUnavailableException()
        return BiometricCrypto.cipherForDecryption(iv)
    }

    @Synchronized
    fun unlockWithBiometric(context: Context, authenticatedCipher: Cipher): WalletData {
        val record = readRecord(context)
        val wrappedKey = record.biometricWrappedKey ?: throw VaultUnavailableException()
        var dataKeyBytes: ByteArray? = null
        try {
            dataKeyBytes = BiometricCrypto.finishAuthenticatedOperation(authenticatedCipher, wrappedKey)
            return decryptPayload(record, dataKeyBytes).also {
                val snapshot = it.catalog.snapshot()
                lock()
                session = VaultSession(dataKeyBytes.clone(), snapshot)
            }
        } finally {
            dataKeyBytes?.fill(0)
        }
    }

    private fun decryptPayload(record: VaultRecord, dataKeyBytes: ByteArray): WalletData {
        val clear = decrypt(
            SecretKeySpec(dataKeyBytes, "AES"),
            record.payloadIv,
            record.payloadCiphertext,
            payloadAad
        )
        return try {
            WalletData(WalletCatalogCodec.decode(clear))
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    fun selectAccount(context: Context, walletId: String, index: Int): WalletAccountsSnapshot =
        updateCatalog(context) { catalog ->
            val wallet = catalog.wallets.first { it.id == walletId }
            require(index in wallet.accountNames.indices)
            require(index !in wallet.hiddenIndices)
            WalletCatalog(catalog.wallets, walletId, index)
        }

    @Synchronized
    fun addAccount(context: Context, walletId: String, name: String): WalletAccountsSnapshot =
        updateCatalog(context) { catalog ->
            val wallet = catalog.wallets.first { it.id == walletId }
            if (wallet.accountNames.size >= WalletCatalogCodec.MAX_ACCOUNTS) {
                throw WalletAccountException("Limite de contas desta carteira atingido.")
            }
            require(name.isNotBlank() && name.length <= 30)
            val updated = StoredWallet(wallet.id, wallet.name, wallet.mnemonic, wallet.accountNames + name.trim(), wallet.hiddenIndices)
            WalletCatalog(catalog.wallets.map { if (it.id == walletId) updated else it }, walletId, updated.accountNames.lastIndex)
        }

    @Synchronized
    fun importWallet(
        context: Context, name: String, words: List<String>, accountCount: Int
    ): WalletAccountsSnapshot {
        val normalized = words.map(::normalizeRecoveryWord)
        if (!isValidRecoveryPhrase(normalized)) throw WalletAccountException("Frase de recuperação inválida.")
        require(name.isNotBlank() && name.length <= 30)
        require(accountCount in 1..WalletCatalogCodec.MAX_ACCOUNTS)
        val mnemonic = normalized.joinToString(" ")
        return updateCatalog(context) { catalog ->
            if (catalog.wallets.any { it.mnemonic == mnemonic }) {
                throw WalletAccountException("Esta carteira já está adicionada. Selecione-a na lista.")
            }
            if (catalog.wallets.size >= WalletCatalogCodec.MAX_WALLETS) {
                throw WalletAccountException("Limite de carteiras deste dispositivo atingido.")
            }
            val wallet = StoredWallet(UUID.randomUUID().toString(), name.trim(), mnemonic,
                List(accountCount) { "Conta ${it + 1}" })
            WalletCatalog(catalog.wallets + wallet, wallet.id, 0)
        }
    }

    @Synchronized
    fun renameWallet(context: Context, walletId: String, name: String): WalletAccountsSnapshot = updateCatalog(context) { catalog ->
        require(name.isNotBlank() && name.length <= 30)
        require(catalog.wallets.any { it.id == walletId })
        WalletCatalog(catalog.wallets.map {
            if (it.id == walletId) StoredWallet(it.id, name.trim(), it.mnemonic, it.accountNames, it.hiddenIndices) else it
        }, catalog.selectedWalletId, catalog.selectedAccountIndex)
    }

    @Synchronized
    fun renameAccount(context: Context, walletId: String, index: Int, name: String): WalletAccountsSnapshot = updateCatalog(context) { catalog ->
        require(name.isNotBlank() && name.length <= 30)
        val wallet = catalog.wallets.first { it.id == walletId }
        require(index in wallet.accountNames.indices && index !in wallet.hiddenIndices)
        val updated = StoredWallet(wallet.id, wallet.name, wallet.mnemonic,
            wallet.accountNames.mapIndexed { i, old -> if (i == index) name.trim() else old }, wallet.hiddenIndices)
        WalletCatalog(catalog.wallets.map { if (it.id == walletId) updated else it }, catalog.selectedWalletId, catalog.selectedAccountIndex)
    }

    @Synchronized
    fun authorizeWithPin(context: Context, pin: CharArray, action: VaultAction, walletId: String, index: Int? = null, binding: String? = null): VaultAuthorization {
        grant = null
        try {
            checkNotNull(session) { "Carteira bloqueada." }
            unlockWithPin(context, pin)
            return issueGrant(action, walletId, index, binding)
        } finally { pin.fill('\u0000') }
    }

    @Synchronized
    fun authorizeWithBiometric(context: Context, cipher: Cipher, action: VaultAction, walletId: String, index: Int? = null, binding: String? = null): VaultAuthorization {
        grant = null
        checkNotNull(session) { "Carteira bloqueada." }
        unlockWithBiometric(context, cipher)
        return issueGrant(action, walletId, index, binding)
    }

    private fun issueGrant(action: VaultAction, walletId: String, index: Int?, binding: String?): VaultAuthorization {
        val current = checkNotNull(session)
        require(current.snapshot.wallets.any { it.id == walletId })
        val token = VaultAuthorization()
        require(action !in setOf(VaultAction.SignTransfer,VaultAction.SignOrigin,VaultAction.SignInvoice,VaultAction.ViewCard,VaultAction.ArmCard,VaultAction.RegenerateCard) || binding?.matches(Regex("[0-9a-f]{64}")) == true)
        grant = Grant(token, current, action, walletId, index, android.os.SystemClock.elapsedRealtime() + 60_000, binding)
        return token
    }

    private fun consume(token: VaultAuthorization, action: VaultAction, walletId: String, index: Int? = null, binding: String? = null) {
        val current = grant
        grant = null
        check(current != null && current.token === token && current.session === session && current.action == action &&
            current.walletId == walletId && current.accountIndex == index && current.binding == binding && android.os.SystemClock.elapsedRealtime() <= current.expiresAt) {
            "Autentique-se novamente para continuar."
        }
    }

    @Synchronized
    fun removeAccount(context: Context, walletId: String, index: Int, token: VaultAuthorization): WalletAccountsSnapshot {
        consume(token, VaultAction.RemoveAccount, walletId, index)
        return updateCatalog(context) { catalog ->
            val wallet = catalog.wallets.first { it.id == walletId }
            require(index in wallet.accountNames.indices && index !in wallet.hiddenIndices)
            if (wallet.accountNames.size - wallet.hiddenIndices.size <= 1)
                throw WalletAccountException("Mantenha pelo menos uma conta nesta carteira.")
            val hidden = wallet.hiddenIndices + index
            val updated = StoredWallet(wallet.id, wallet.name, wallet.mnemonic,
                wallet.accountNames.mapIndexed { i, old -> if (i == index) "" else old }, hidden)
            val selected = if (catalog.selectedWalletId == walletId && catalog.selectedAccountIndex == index)
                wallet.accountNames.indices.first { it !in hidden } else catalog.selectedAccountIndex
            WalletCatalog(catalog.wallets.map { if (it.id == walletId) updated else it }, catalog.selectedWalletId, selected)
        }
    }

    @Synchronized
    fun revealPhrase(context: Context, walletId: String, token: VaultAuthorization): List<String> {
        consume(token, VaultAction.RevealPhrase, walletId)
        val current = checkNotNull(session)
        return decryptPayload(readRecord(context), current.key).catalog.wallets.first { it.id == walletId }.mnemonic.split(" ")
    }

    @Synchronized
    fun changePin(context: Context, walletId: String, newPin: CharArray, token: VaultAuthorization) {
        var derived: ByteArray? = null
        try {
            consume(token, VaultAction.ChangePin, walletId)
            require(newPin.size == 6 && newPin.all { it in '0'..'9' })
            val current = checkNotNull(session)
            val record = readRecord(context)
            val salt = ByteArray(32).also(random::nextBytes)
            derived = derivePinKey(newPin, salt)
            val wrapped = encrypt(SecretKeySpec(derived, "AES"), current.key, pinAad)
            writeRecord(context, record.copy(pinSalt = salt, pinIv = wrapped.iv, pinWrappedKey = wrapped.ciphertext))
            clearFailedAttempts(context)
        } finally { newPin.fill('\u0000'); derived?.fill(0) }
    }

    @Synchronized
    fun revokeAuthorization() { grant = null }

    @Synchronized
    internal fun signTransfer(context: Context, walletId: String, index: Int, quote: PreparedTransfer, token: VaultAuthorization): Pair<String, String> {
        consume(token, VaultAction.SignTransfer, walletId, index, quote.digest)
        quote.validate()
        val destination = quote.recipient.drop(2)
        if (destination.any { it in 'a'..'f' } && destination.any { it in 'A'..'F' }) {
            require(wallet.core.jni.Ethereum.addressChecksummed(quote.recipient) == quote.recipient) { "Checksum do endereço inválido." }
        }
        val current = checkNotNull(session)
        val stored = decryptPayload(readRecord(context), current.key).catalog.wallets.first { it.id == walletId }
        require(index in stored.accountNames.indices && index !in stored.hiddenIndices)
        require(deriveEvmAddress(stored.mnemonic, index).equals(quote.from, true))
        System.loadLibrary("TrustWalletCore")
        val hdWallet = wallet.core.jni.HDWallet(stored.mnemonic, "")
        val key = hdWallet.getKey(wallet.core.jni.CoinType.ETHEREUM, "m/44'/60'/0'/0/$index").data()
        try {
            fun bytes(value: java.math.BigInteger): com.google.protobuf.ByteString {
                require(value.signum() >= 0)
                return com.google.protobuf.ByteString.copyFrom(if (value.signum() == 0) byteArrayOf() else value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
            }
            val transfer = wallet.core.jni.proto.Ethereum.Transaction.newBuilder()
            if (quote.contract == null) transfer.setTransfer(wallet.core.jni.proto.Ethereum.Transaction.Transfer.newBuilder().setAmount(bytes(quote.units)))
            else transfer.setErc20Transfer(wallet.core.jni.proto.Ethereum.Transaction.ERC20Transfer.newBuilder()
                .setTo(quote.recipient).setAmount(bytes(quote.units)))
            val input = wallet.core.jni.proto.Ethereum.SigningInput.newBuilder()
                .setPrivateKey(com.google.protobuf.ByteString.copyFrom(key)).setChainId(bytes(quote.chain.chainId.toBigInteger()))
                .setNonce(bytes(quote.nonce)).setGasPrice(bytes(quote.gasPrice)).setGasLimit(bytes(quote.gasLimit))
                .setToAddress(quote.to).setTransaction(transfer).build()
            val result = wallet.core.java.AnySigner.sign(input, wallet.core.jni.CoinType.ETHEREUM, wallet.core.jni.proto.Ethereum.SigningOutput.parser())
            check(result.error == wallet.core.jni.proto.Common.SigningError.OK && !result.encoded.isEmpty) { "Não foi possível assinar a transação." }
            val encoded = result.encoded.toByteArray()
            return "0x" + wallet.core.jni.Hash.keccak256(encoded).hex() to "0x" + encoded.hex()
        } finally { key.fill(0) }
    }

    @Synchronized
    internal fun beginOriginPayment(walletId:String,index:Int,draft:OriginPaymentDraft,token:VaultAuthorization):OriginPaymentPermit {
        consume(token,VaultAction.SignOrigin,walletId,index,draft.digest)
        draft.validate()
        val current=checkNotNull(session)
        val account=current.snapshot.wallets.first{it.id==walletId}.accounts.first{it.index==index}
        require(account.address.equals(draft.first.from,true))
        val permit=OriginPaymentPermit()
        originConsent=OriginConsent(permit,current,walletId,index,draft,0,draft.maximumGas,android.os.SystemClock.elapsedRealtime()+90_000)
        return permit
    }

    @Synchronized
    internal fun authorizeOriginPaymentStep(permit:OriginPaymentPermit,q:OriginQuote):VaultAuthorization {
        val consent=checkNotNull(originConsent){"Confirme a compra novamente."}
        check(consent.permit===permit && consent.session===session && android.os.SystemClock.elapsedRealtime()<=consent.deadline)
        q.validate()
        val first=consent.draft.first
        require(consent.next<consent.draft.steps.size && q.call.kind==consent.draft.steps[consent.next])
        require(q.from.equals(first.from,true) && q.call.cardId==first.call.cardId && q.call.expiry==first.call.expiry && q.call.registrationSalt==first.call.registrationSalt &&
            q.call.settlement.equals(first.call.settlement,true) && q.call.invoice!!.wire().contentEquals(first.call.invoice!!.wire()))
        require(q.gasPrice<=first.gasPrice && q.fee<=consent.gasRemaining)
        if(consent.next==0)require(q.digest==first.digest)
        else require(q.gasLimit<=OriginPaymentDraft.gasCap(q.call.kind))
        consent.next++;consent.gasRemaining-=q.fee
        return issueGrant(VaultAction.SignOrigin,consent.walletId,consent.index,q.digest)
    }

    @Synchronized
    internal fun cancelOriginPayment(permit:OriginPaymentPermit?=null) {
        if(permit==null || originConsent?.permit===permit){originConsent=null;grant=null}
    }

    @Synchronized
    internal fun consumeCardAuthorization(token: VaultAuthorization, action: VaultAction, walletId: String, index: Int, binding: String) {
        require(action in setOf(VaultAction.ViewCard,VaultAction.ArmCard,VaultAction.RegenerateCard))
        consume(token,action,walletId,index,binding)
    }

    @Synchronized
    internal fun createOriginCard(context: Context, walletId: String, index: Int, first: String, last: String,
        regeneration: VaultAuthorization? = null): OriginCard {
        val current = checkNotNull(session)
        val existing = OriginCardStore.latest(context,walletId,index)
        if(existing != null) consume(requireNotNull(regeneration),VaultAction.RegenerateCard,walletId,index,existing.fingerprint)
        val account = current.snapshot.wallets.first{it.id==walletId}.accounts.first{it.index==index}
        require(OriginJournal(context).all().none { !it.terminal && it.quote.call.kind==OriginAction.Register && it.quote.from.equals(account.address,true) }) { "Aguarde a ativação anterior." }
        originConsent=null
        val previous = existing?.let { openOriginDetails(it,current.key) }
        var card = OriginCard.generate(walletId,account,first,last)
        var attempts=0
        while(previous!=null && (card.number.takeLast(4)==previous.number.takeLast(4) || card.securityCode==previous.securityCode)) {
            check(++attempts<16);card=OriginCard.generate(walletId,account,first,last)
        }
        val raw = org.json.JSONObject().put("number",card.number).put("code",card.securityCode).toString().toByteArray()
        val aad = originAad(card)
        val encrypted = try { encrypt(SecretKeySpec(current.key,"AES"),raw,aad) } finally { raw.fill(0) }
        val sealed = android.util.Base64.encodeToString(encrypted.iv+encrypted.ciphertext,android.util.Base64.NO_WRAP)
        return OriginCard(card.walletId,card.index,card.holder,card.id,card.name,"","",card.issuedAt,card.expiresAt,sealed,card.number.takeLast(4),card.salt)
            .also { OriginCardStore(context,true).save(it);OriginCardStore(context).save(it) }
    }

    @Synchronized
    internal fun originCardDetails(card: OriginCard, token: VaultAuthorization): OriginCardDetails {
        consume(token,VaultAction.ViewCard,card.walletId,card.index,card.fingerprint)
        return openOriginDetails(card,checkNotNull(session).key)
    }
    @Synchronized
    internal fun authorizeOriginTap(context:Context,card:OriginCard,token:VaultAuthorization) {
        consume(token,VaultAction.ArmCard,card.walletId,card.index,card.fingerprint)
        require(OriginCardStore.latest(context,card.walletId,card.index)?.id==card.id && card.expiresAt>java.time.Instant.now().epochSecond){"Este cartão foi substituído ou expirou."}
        originClear(card,checkNotNull(session).key).fill(0)
    }
    private fun originAad(card:OriginCard)=listOf("CaedralPay/Origin/v1",card.walletId,card.index.toString(),card.id,
        card.holder.lowercase(java.util.Locale.ROOT),card.salt,card.name,card.issuedAt.toString(),card.expiresAt.toString(),card.lastFour).joinToString("|").toByteArray()
    private fun originClear(card:OriginCard,key:ByteArray):ByteArray {
        val raw=android.util.Base64.decode(card.sealed,android.util.Base64.NO_WRAP)
        require(raw.size in 29..1024)
        return decrypt(SecretKeySpec(key,"AES"),raw.copyOfRange(0,12),raw.copyOfRange(12,raw.size),originAad(card))
    }
    private fun openOriginDetails(card:OriginCard,key:ByteArray):OriginCardDetails {
        val clear=originClear(card,key)
        return try{val data=org.json.JSONObject(clear.toString(Charsets.UTF_8));OriginCardDetails(data.getString("number"),data.getString("code"))}
        finally{clear.fill(0)}
    }

    @Synchronized
    internal fun signOriginInvoice(context:Context,walletId:String,index:Int,invoice:CaedralProtocol.Invoice,token:VaultAuthorization):OriginInvoice {
        invoice.validate(java.time.Instant.now().epochSecond)
        require(invoice.settlement.equals(OriginNetwork.address,true) && invoice.chainId==97L)
        val binding=OriginAbi.sha(OriginAbi.invoiceDigest(invoice).hex())
        consume(token,VaultAction.SignInvoice,walletId,index,binding)
        val key=originKey(context,walletId,index,invoice.merchant)
        return try {
            val private=wallet.core.jni.PrivateKey(key)
            val signature=wallet.core.jni.EthereumMessageSigner.signTypedMessage(private,OriginAbi.typedInvoice(invoice)).removePrefix("0x")
            OriginInvoice(invoice,signature).also{it.validate()}
        } finally { key.fill(0) }
    }

    @Synchronized
    internal fun signOriginOperation(context:Context,walletId:String,index:Int,q:OriginQuote,token:VaultAuthorization):Pair<String,String> {
        consume(token,VaultAction.SignOrigin,walletId,index,q.digest); q.validate()
        val localCard=requireNotNull(OriginCardStore.latest(context,walletId,index))
        require(localCard.id==q.call.cardId && localCard.holder.equals(q.from,true)) { "Os dados do cartão mudaram. Revise a operação." }
        require(localCard.expiresAt==q.call.expiry && localCard.salt==q.call.registrationSalt)
        originClear(localCard,checkNotNull(session).key).fill(0)
        val key=originKey(context,walletId,index,q.from)
        return try {
            fun bytes(value:java.math.BigInteger)=com.google.protobuf.ByteString.copyFrom(if(value.signum()==0)byteArrayOf() else value.toByteArray().dropWhile{it==0.toByte()}.toByteArray())
            val tx=wallet.core.jni.proto.Ethereum.Transaction.newBuilder().setContractGeneric(
                wallet.core.jni.proto.Ethereum.Transaction.ContractGeneric.newBuilder().setAmount(bytes(q.call.value))
                    .setData(com.google.protobuf.ByteString.copyFrom(q.call.data.hexBytes())))
            val input=wallet.core.jni.proto.Ethereum.SigningInput.newBuilder().setPrivateKey(com.google.protobuf.ByteString.copyFrom(key))
                .setChainId(bytes(97.toBigInteger())).setNonce(bytes(q.nonce)).setGasPrice(bytes(q.gasPrice)).setGasLimit(bytes(q.gasLimit))
                .setToAddress(q.call.to).setTransaction(tx).build()
            val result=wallet.core.java.AnySigner.sign(input,wallet.core.jni.CoinType.ETHEREUM,wallet.core.jni.proto.Ethereum.SigningOutput.parser())
            check(result.error==wallet.core.jni.proto.Common.SigningError.OK && !result.encoded.isEmpty)
            val raw=result.encoded.toByteArray(); "0x"+wallet.core.jni.Hash.keccak256(raw).hex() to "0x"+raw.hex()
        } finally { key.fill(0) }
    }
    @Synchronized
    internal fun signRecurringOffer(context:Context,walletId:String,index:Int,offer:RecurringOffer,token:VaultAuthorization):RecurringOffer {
        offer.validate(signed=false)
        consume(token,VaultAction.SignInvoice,walletId,index,OriginAbi.sha(offer.digest().hex()))
        val key=originKey(context,walletId,index,offer.merchant)
        return try{offer.copy(signature=wallet.core.jni.EthereumMessageSigner.signTypedMessage(wallet.core.jni.PrivateKey(key),offer.typed()).removePrefix("0x")).also{it.validate()}}finally{key.fill(0)}
    }

    @Synchronized
    internal fun signRecurring(context:Context,walletId:String,index:Int,q:RecurringQuote,token:VaultAuthorization):Pair<String,String> {
        consume(token,VaultAction.SignOrigin,walletId,index,q.digest);q.validate()
        if(q.action==RecurringAction.Authorize){val card=checkNotNull(OriginCardStore.latest(context,walletId,index));require(card.id==q.cardId && card.salt==q.salt && card.expiresAt==q.cardExpiry && card.holder.equals(q.from,true));originClear(card,checkNotNull(session).key).fill(0)}
        val key=originKey(context,walletId,index,q.from)
        return try {
            fun bytes(value:java.math.BigInteger)=com.google.protobuf.ByteString.copyFrom(if(value.signum()==0)byteArrayOf()else value.toByteArray().dropWhile{it==0.toByte()}.toByteArray())
            val tx=wallet.core.jni.proto.Ethereum.Transaction.newBuilder().setContractGeneric(wallet.core.jni.proto.Ethereum.Transaction.ContractGeneric.newBuilder()
                .setAmount(com.google.protobuf.ByteString.EMPTY).setData(com.google.protobuf.ByteString.copyFrom(q.data.hexBytes())))
            val input=wallet.core.jni.proto.Ethereum.SigningInput.newBuilder().setPrivateKey(com.google.protobuf.ByteString.copyFrom(key)).setChainId(bytes(97.toBigInteger()))
                .setNonce(bytes(q.nonce)).setGasLimit(bytes(q.gasLimit)).setToAddress(q.settlement).setTransaction(tx)
            if(q.delegate){
                // WalletCore's automatic authorization assumes a self-call. This transaction
                // calls the fixed settlement contract; sign an explicit chain-bound tuple.
                val authorizationNonce=bytes(q.nonce+java.math.BigInteger.ONE)
                val authorizationHash=wallet.core.jni.Eip7702.getAuthorizationHash(bytes(97.toBigInteger()).toByteArray(),q.accountImplementation,authorizationNonce.toByteArray())
                val authorizationSignature=wallet.core.jni.PrivateKey(key).sign(authorizationHash,wallet.core.jni.Curve.SECP256K1)
                val authorization=wallet.core.jni.proto.Ethereum.Authorization.newBuilder().setAddress(q.accountImplementation)
                    .setCustomSignature(wallet.core.jni.proto.Ethereum.AuthorizationCustomSignature.newBuilder().setChainId(bytes(97.toBigInteger())).setNonce(authorizationNonce).setSignature(authorizationSignature.hex()))
                input.setTxMode(wallet.core.jni.proto.Ethereum.TransactionMode.SetCode).setMaxInclusionFeePerGas(bytes(q.gasPrice)).setMaxFeePerGas(bytes(q.gasPrice))
                .setEip7702Authorization(authorization)}
            else input.setGasPrice(bytes(q.gasPrice))
            val result=wallet.core.java.AnySigner.sign(input.build(),wallet.core.jni.CoinType.ETHEREUM,wallet.core.jni.proto.Ethereum.SigningOutput.parser())
            check(result.error==wallet.core.jni.proto.Common.SigningError.OK && !result.encoded.isEmpty){"Não foi possível assinar a autorização."}
            val raw=result.encoded.toByteArray();"0x"+wallet.core.jni.Hash.keccak256(raw).hex() to "0x"+raw.hex()
        }finally{key.fill(0)}
    }

    private fun originKey(context:Context,walletId:String,index:Int,from:String):ByteArray {
        val current=checkNotNull(session)
        val stored=decryptPayload(readRecord(context),current.key).catalog.wallets.first{it.id==walletId}
        require(index in stored.accountNames.indices && index !in stored.hiddenIndices && deriveEvmAddress(stored.mnemonic,index).equals(from,true))
        System.loadLibrary("TrustWalletCore")
        return wallet.core.jni.HDWallet(stored.mnemonic,"").getKey(wallet.core.jni.CoinType.ETHEREUM,"m/44'/60'/0'/0/$index").data()
    }

    // Chamado somente sob o monitor do cofre: serializa alterações e bloqueio de sessão.
    private fun updateCatalog(context: Context, transform: (WalletCatalog) -> WalletCatalog): WalletAccountsSnapshot {
        val authorized = checkNotNull(session) { "Carteira bloqueada." }
        val record = readRecord(context)
        val catalog = transform(decryptPayload(record, authorized.key).catalog)
        val snapshot = catalog.snapshot()
        val clear = WalletCatalogCodec.encode(catalog)
        try {
            val encrypted = encrypt(SecretKeySpec(authorized.key, "AES"), clear, payloadAad)
            writeRecord(context, record.copy(payloadIv = encrypted.iv, payloadCiphertext = encrypted.ciphertext))
            // Atualiza a seleção somente depois de a gravação terminar com sucesso.
            session = VaultSession(authorized.key, snapshot)
            return snapshot
        } finally {
            clear.fill(0)
        }
    }

    private fun writeRecord(context: Context, record: VaultRecord) {
        val clearRecord = encodeRecord(record)
        try {
            val encrypted = encrypt(deviceKey(create = true), clearRecord, outerAad)
            val atomicFile = vaultFile(context)
            val output = atomicFile.startWrite()
            try {
                DataOutputStream(output).apply {
                    writeInt(FILE_MAGIC)
                    writeInt(RECORD_VERSION)
                    writeSized(encrypted.iv)
                    writeSized(encrypted.ciphertext)
                    flush()
                }
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        } finally {
            clearRecord.fill(0)
        }
    }

    private fun readRecord(context: Context): VaultRecord {
        try {
            DataInputStream(vaultFile(context).openRead()).use { input ->
                check(input.readInt() == FILE_MAGIC)
                check(input.readInt() == RECORD_VERSION)
                val iv = input.readSized(32)
                val encrypted = input.readSized(MAX_FIELD_SIZE)
                val clear = decrypt(deviceKey(create = false), iv, encrypted, outerAad)
                return try {
                    decodeRecord(clear)
                } finally {
                    clear.fill(0)
                }
            }
        } catch (error: VaultUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw VaultUnavailableException(error)
        }
    }

    private fun deviceKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(DEVICE_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw VaultUnavailableException()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                DEVICE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun derivePinKey(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, KDF_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun remainingLockMillis(context: Context): Long {
        val until = context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE)
            .getLong("locked_until", 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun recordFailedAttempt(context: Context) {
        val preferences = context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE)
        val failures = preferences.getInt("failures", 0) + 1
        val lockSeconds = if (failures < 5) {
            0L
        } else {
            (30L shl (failures - 5).coerceAtMost(7)).coerceAtMost(3_600L)
        }
        preferences.edit()
            .putInt("failures", failures)
            .putLong("locked_until", System.currentTimeMillis() + lockSeconds * 1_000L)
            .apply()
    }

    private fun clearFailedAttempts(context: Context) {
        context.getSharedPreferences(ATTEMPT_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun encrypt(key: SecretKey, clear: ByteArray, aad: ByteArray): EncryptedValue {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return EncryptedValue(cipher.iv.clone(), cipher.doFinal(clear))
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun encodeRecord(record: VaultRecord): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(RECORD_VERSION)
                output.writeSized(record.pinSalt)
                output.writeSized(record.pinIv)
                output.writeSized(record.pinWrappedKey)
                output.writeSized(record.payloadIv)
                output.writeSized(record.payloadCiphertext)
                output.writeBoolean(record.biometricWrappedKey != null)
                if (record.biometricWrappedKey != null) {
                    output.writeSized(requireNotNull(record.biometricIv))
                    output.writeSized(record.biometricWrappedKey)
                }
            }
            bytes.toByteArray()
        }

    private fun decodeRecord(bytes: ByteArray): VaultRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == RECORD_VERSION)
            val pinSalt = input.readSized(64)
            val pinIv = input.readSized(32)
            val pinWrapped = input.readSized(256)
            val payloadIv = input.readSized(32)
            val payload = input.readSized(MAX_FIELD_SIZE)
            val hasBiometric = input.readBoolean()
            val biometricIv = if (hasBiometric) input.readSized(32) else null
            val biometricWrapped = if (hasBiometric) input.readSized(256) else null
            VaultRecord(pinSalt, pinIv, pinWrapped, payloadIv, payload, biometricIv, biometricWrapped)
        }

    private fun DataOutputStream.writeSized(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSized(maxSize: Int): ByteArray {
        val size = readInt()
        require(size in 1..maxSize)
        return ByteArray(size).also(::readFully)
    }

    private fun vaultFile(context: Context) = AtomicFile(context.filesDir.resolve(FILE_NAME))

    private data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray)

    private data class VaultRecord(
        val pinSalt: ByteArray,
        val pinIv: ByteArray,
        val pinWrappedKey: ByteArray,
        val payloadIv: ByteArray,
        val payloadCiphertext: ByteArray,
        val biometricIv: ByteArray?,
        val biometricWrappedKey: ByteArray?
    )
}
