package com.caedralpay.wallet

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

internal object OriginTapSession {
    data class Request(val invoice:OriginInvoice?,val card:OriginCard,val recurring:RecurringOffer?=null)
    private var key:CaedralNfcCrypto.Ephemeral?=null
    private var card:OriginCard?=null
    private var deadline=0L
    private val incoming=MutableStateFlow<Request?>(null)
    val request=incoming.asStateFlow()
    @Synchronized fun arm(value:OriginCard) { clear(); card=value; key=CaedralNfcCrypto.Ephemeral(); deadline=SystemClock.elapsedRealtime()+120_000 }
    @Synchronized fun clear(){key=null;card=null;deadline=0;incoming.value=null}
    @Synchronized fun hello():ByteArray?=if(SystemClock.elapsedRealtime()<deadline && incoming.value==null)key?.publicKey else null
    @Synchronized fun accept(envelope:ByteArray):Boolean {
        if(hello()==null || envelope.size !in setOf(OriginInvoice.SIZE+72,RecurringOffer.SIZE+72))return false
        return try {
            val clear=CaedralNfcCrypto.open(requireNotNull(key),envelope)
            val owner=requireNotNull(card)
            val request=try {
                if(clear.size==RecurringOffer.SIZE){val o=RecurringOffer.decode(clear);require(!o.merchant.equals(owner.holder,true));Request(null,owner,o)}
                else {val i=OriginInvoice.decode(clear);require(!i.request.merchant.equals(owner.holder,true));Request(i,owner)}
            } finally{clear.fill(0)}
            incoming.value=request;key=null;deadline=0;true
        }catch(_:Exception){false}
    }
}
internal object OriginApdu {
    val select get()=byteArrayOf(0,0xA4.toByte(),4,0,7,0xF0.toByte(),0x43,0x41,0x45,0x44,0x52,2)
    val ok get()=byteArrayOf(0x90.toByte(),0)
    fun chunks(envelope:ByteArray):List<ByteArray> {
        require(envelope.size in setOf(OriginInvoice.SIZE+72,RecurringOffer.SIZE+72))
        return envelope.toList().chunked(200).mapIndexed { index,bytes ->
            byteArrayOf(0x80.toByte(),0x20,index.toByte(),if(index==1)1 else 0,bytes.size.toByte())+bytes.toByteArray()
        }
    }
}
class OriginHostService:HostApduService() {
    private var selected=false;private var next=0;private val buffer=ByteArrayOutputStream()
    override fun processCommandApdu(commandApdu:ByteArray?,extras:Bundle?):ByteArray {
        val a=commandApdu ?: return fail()
        val hello=OriginTapSession.hello() ?: return fail()
        if(a.contentEquals(OriginApdu.select)){selected=true;next=0;buffer.reset();return hello+OriginApdu.ok}
        if(!selected || a.size<5 || a.size>205 || a[0]!=0x80.toByte() || a[1]!=0x20.toByte() ||
            (a[2].toInt() and 255)!=next || (a[4].toInt() and 255)!=a.size-5 || next>1 ||
            a[3]!=(if(next==1)1 else 0).toByte())return fail()
        buffer.write(a,5,a.size-5);next++
        if(next==1)return OriginApdu.ok
        val bytes=buffer.toByteArray();selected=false;buffer.reset()
        return if(OriginTapSession.accept(bytes))OriginApdu.ok else fail()
    }
    override fun onDeactivated(reason:Int){selected=false;next=0;buffer.reset()}
    private fun fail():ByteArray {selected=false;buffer.reset();return byteArrayOf(0x69,0x85.toByte())}
}
internal class OriginReader(private val activity:Activity) {
    private val adapterSource=lazy{NfcAdapter.getDefaultAdapter(activity)}
    private val adapter get()=adapterSource.value
    private var reading=false;private var generation=0
    val supported get()=adapter?.isEnabled==true
    fun start(invoice:OriginInvoice,result:(Boolean)->Unit)=startPayload(invoice.wire(),{invoice.validate()},result)
    fun start(offer:RecurringOffer,result:(Boolean)->Unit)=startPayload(offer.wire(),{offer.validate()},result)
    private fun startPayload(wire:ByteArray,validate:()->Unit,result:(Boolean)->Unit) {
        check(supported);validate();val session=++generation
        reading=true
        requireNotNull(adapter).enableReaderMode(activity,{tag ->
            val sent=runCatching {
                val iso=IsoDep.get(tag) ?: return@runCatching false
                iso.use { io ->
                    io.connect();io.timeout=3000
                    val hello=io.transceive(OriginApdu.select)
                    require(hello.size==46 && hello.takeLast(2).toByteArray().contentEquals(OriginApdu.ok))
                    validate()
                    val payload=CaedralNfcCrypto.seal(hello.copyOfRange(0,44),wire)
                    OriginApdu.chunks(payload).all{io.transceive(it).contentEquals(OriginApdu.ok)}
                }
            }.getOrDefault(false)
            activity.runOnUiThread {if(session==generation){if(sent)stop();result(sent)}}
        },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)
    }
    fun stop(){generation++;if(reading){reading=false;runCatching{adapter?.disableReaderMode(activity)}}}
}
