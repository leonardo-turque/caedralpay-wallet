package com.caedralpay.wallet

import android.content.pm.PackageManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.math.BigInteger

@RunWith(AndroidJUnit4::class)
class CaedralNfcTest {
    private fun service(): CaedralHostService {
        lateinit var instance: CaedralHostService
        InstrumentationRegistry.getInstrumentation().runOnMainSync { instance = CaedralHostService() }
        return instance
    }
    private fun wire(): ByteArray {
        val now = Instant.now().epochSecond
        return CaedralProtocol.encode(CaedralProtocol.Invoice(97,
            "0x1111111111111111111111111111111111111111", "0x2222222222222222222222222222222222222222",
            "0x3333333333333333333333333333333333333333", BigInteger.TEN.pow(18), "ab".repeat(32), now, now + 120), now)
    }
    @Test fun serviceProtectedByPlatformPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getServiceInfo(ComponentName(context, CaedralHostService::class.java),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals("android.permission.BIND_NFC_SERVICE", info.permission)
        assertTrue(info.exported)
        assertEquals(BuildConfig.CAEDRAL_PROTOCOL_PREVIEW, info.enabled)
    }
    @Test fun closedSessionNeverAcceptsApdu() {
        CaedralNfcSession.clear()
        val service = service()
        assertFalse(service.processCommandApdu(CaedralApdu.select, null).contentEquals(CaedralApdu.ok))
        assertNull(CaedralNfcSession.invoice.value)
    }
    @Test fun previewReceivesExactlyOneRequestAndClearErasesIt() {
        if (!BuildConfig.CAEDRAL_PROTOCOL_PREVIEW) return
        try {
            CaedralNfcSession.arm()
            val service = service()
            val hello = service.processCommandApdu(CaedralApdu.select, null)
            val command = CaedralApdu.put(CaedralNfcCrypto.seal(hello.copyOfRange(0, 44), wire()))
            service.onDeactivated(0)
            assertFalse(service.processCommandApdu(command, null).contentEquals(CaedralApdu.ok))
            assertEquals(46, service.processCommandApdu(CaedralApdu.select, null).size)
            assertArrayEquals(CaedralApdu.ok, service.processCommandApdu(command, null))
            assertNotNull(CaedralNfcSession.invoice.value)
            assertFalse(service.processCommandApdu(command, null).contentEquals(CaedralApdu.ok))
            CaedralNfcSession.clear(); assertNull(CaedralNfcSession.invoice.value)
        } finally { CaedralNfcSession.clear() }
    }
    @Test fun deselectionRequiresNewSelect() {
        if (!BuildConfig.CAEDRAL_PROTOCOL_PREVIEW) return
        try {
            CaedralNfcSession.arm(); val service = service()
            service.processCommandApdu(CaedralApdu.select, null); service.onDeactivated(0)
            assertFalse(service.processCommandApdu(CaedralApdu.put(ByteArray(CaedralNfcCrypto.ENVELOPE_SIZE)), null).contentEquals(CaedralApdu.ok))
            assertNull(CaedralNfcSession.invoice.value)
        } finally { CaedralNfcSession.clear() }
    }
}
