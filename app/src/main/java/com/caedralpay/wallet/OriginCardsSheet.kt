package com.caedralpay.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun OriginCardsSheet(snapshot:WalletAccountsSnapshot,network:BscChain,onDismiss:()->Unit,gateway:EvmGateway?=null) {
    val context=LocalContext.current
    val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().first()
    val lifecycle=(activity as androidx.lifecycle.LifecycleOwner).lifecycle
    val wallet=snapshot.selectedWalletId;val account=snapshot.selectedAccount
    val contentScroll=rememberScrollState()
    val scope=rememberCoroutineScope();val engine=remember(context,gateway){OriginEngine(context,gateway ?: EvmGateway())}
    var card by remember(wallet,account.index){mutableStateOf<OriginCard?>(null)}
    var loading by remember{mutableStateOf(true)}
    var first by remember{mutableStateOf("")};var last by remember{mutableStateOf("")}
    var form by remember{mutableStateOf(false)}
    var auth by remember{mutableStateOf<OriginAuth?>(null)};var error by remember{mutableStateOf<String?>(null)}
    var preferredService by remember{mutableStateOf<CardEmulation?>(null)}
    var details by remember{mutableStateOf<OriginCardDetails?>(null)};var tapping by remember{mutableStateOf(false)}
    var paymentDraft by remember{mutableStateOf<OriginPaymentDraft?>(null)}
    var permit by remember{mutableStateOf<OriginPaymentPermit?>(null)}
    var received by remember{mutableStateOf<OriginInvoice?>(null)};var quote by remember{mutableStateOf<OriginQuote?>(null)}
    var operation by remember{mutableStateOf<OriginRecord?>(null)}
    val recurringEngine=remember(context,gateway){RecurringEngine(context,gateway ?: EvmGateway())}
    var recurringQuote by remember{mutableStateOf<RecurringQuote?>(null)}
    var recurringRecord by remember{mutableStateOf<RecurringRecord?>(null)}
    var subscriptionsOpen by remember{mutableStateOf(false)}
    val incoming by OriginTapSession.request.collectAsState()
    fun fail(e:Exception){error=e.message?.takeIf{it.length<160 && it!="Failed requirement."} ?: "Não foi possível concluir. Tente novamente."}
    fun stopTap(){
        OriginTapSession.clear();tapping=false
        val selected=preferredService;preferredService=null
        if(selected!=null)runCatching{selected.unsetPreferredService(activity)}
    }
    suspend fun reload() {
        val loaded=withContext(Dispatchers.IO){
            Triple(OriginCardStore.latest(context,wallet,account.index),engine.history().lastOrNull{!it.terminal && it.quote.from.equals(account.address,true)},recurringEngine.history().lastOrNull{!it.terminal && it.quote.from.equals(account.address,true)})
        }
        withContext(Dispatchers.Main.immediate){card=loaded.first;operation=loaded.second;recurringRecord=loaded.third}
    }
    suspend fun prepareReceived(invoice:OriginInvoice,owner:OriginCard){
        invoice.validate();require(owner.id==card?.id && owner.walletId==wallet && owner.index==account.index)
        received=invoice
        paymentDraft=engine.paymentDraft(owner,invoice,account.address)
        quote=paymentDraft!!.first
    }
    suspend fun sendConsented(q:OriginQuote):OriginRecord {
        val capability=checkNotNull(permit)
        return try {
            val token=withContext(Dispatchers.IO){WalletVault.authorizeOriginPaymentStep(capability,q)}
            engine.send(q,wallet,account.index,token)
        } catch(e:Exception){WalletVault.cancelOriginPayment(capability);permit=null;throw e}
    }
    fun requestTap(current:OriginCard,revalidation:Boolean=false) {
        val adapter=NfcAdapter.getDefaultAdapter(activity)
        if(adapter?.isEnabled!=true || !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            error="Ative o NFC em um aparelho compatível para continuar.";return
        }
        auth=OriginAuth(if(revalidation)"Revalidar aproximação"else"Habilitar aproximação",VaultAction.ArmCard,wallet,account.index,current.fingerprint){token->
                        withContext(Dispatchers.IO){WalletVault.authorizeOriginTap(context,current,token)}
                        val adapter=NfcAdapter.getDefaultAdapter(activity);require(adapter?.isEnabled==true){"Ative o NFC do celular."}
                        val service=CardEmulation.getInstance(adapter)
                        require(service.setPreferredService(activity,ComponentName(activity,OriginHostService::class.java)))
                        preferredService=service
                        try{OriginTapSession.arm(current);tapping=true;error=null;quote=null;paymentDraft=null;received=null;operation=null;recurringQuote=null;recurringRecord=null}
                        catch(e:Exception){stopTap();throw e}
                    }
    }
    LaunchedEffect(wallet,account.index){loading=true;auth=null;error=null;try{stopTap();details=null;quote=null;reload();form=card==null}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}finally{loading=false}}
    LaunchedEffect(incoming){val request=incoming ?: return@LaunchedEffect
        stopTap();loading=true;error=null
        try{if(request.recurring!=null){require(request.card.id==card?.id && request.card.walletId==wallet && request.card.index==account.index);recurringQuote=recurringEngine.prepare(request.recurring,request.card)}else prepareReceived(requireNotNull(request.invoice),request.card)}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}finally{loading=false}
    }
    LaunchedEffect(tapping){if(tapping){
        contentScroll.animateScrollTo(0);delay(OriginTapSession.READY_WINDOW_MS)
        val current=card;stopTap()
        if(current!=null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))requestTap(current,true)
    }}
    LaunchedEffect(details){if(details!=null){withFrameNanos{};contentScroll.animateScrollTo(contentScroll.maxValue);delay(30_000);details=null}}
    LaunchedEffect(operation?.hash){
        var record=operation ?: return@LaunchedEffect
        if(record.terminal)return@LaunchedEffect
        while(!record.terminal){
            try{if(record.status==TransactionStatus.Signed)record=engine.resume(record);record=engine.refresh(record);operation=record}
            catch(c:CancellationException){throw c}catch(_:Exception){error="A confirmação está temporariamente indisponível. Sua operação está salva."}
            if(!record.terminal)delay(2500)
        }
        if(record.status==TransactionStatus.Confirmed){
            if(record.quote.call.kind==OriginAction.Approve || record.quote.call.kind==OriginAction.ResetApproval) {
                val invoice=record.quote.call.invoice!!
                try {
                    if(permit!=null) {
                        val next=engine.prepare(OriginCall.forCard(if(record.quote.call.kind==OriginAction.ResetApproval)OriginAction.Approve else OriginAction.Pay,requireNotNull(card),invoice),account.address)
                        operation=sendConsented(next)
                    } else prepareReceived(invoice,requireNotNull(card))
                }catch(c:CancellationException){throw c}catch(e:Exception){WalletVault.cancelOriginPayment(permit);permit=null;fail(e)}
            } else {WalletVault.cancelOriginPayment(permit);permit=null}
        }else {WalletVault.cancelOriginPayment(permit);permit=null;error="A operação não foi concluída pela rede. Confira a atividade antes de tentar novamente."}
    }

    LaunchedEffect(recurringRecord?.hash){var current=recurringRecord ?: return@LaunchedEffect
        while(!current.terminal){try{if(current.status==TransactionStatus.Signed)current=recurringEngine.resume(current);current=recurringEngine.refresh(current);recurringRecord=current}catch(c:CancellationException){throw c}catch(_:Exception){error="Não foi possível atualizar a confirmação."};if(!current.terminal)delay(2000)}
        if(current.status==TransactionStatus.Failed)error="A autorização não foi confirmada."
    }
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE){stopTap();details=null;WalletVault.cancelOriginPayment(permit);permit=null}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);stopTap();details=null;WalletVault.cancelOriginPayment(permit);permit=null;WalletVault.revokeAuthorization()}}
    if(subscriptionsOpen){OriginSubscriptionsSheet(snapshot,onDismiss={subscriptionsOpen=false},gateway=gateway);return}
    CaedralSheet("Seu cartão Origin",onDismiss=onDismiss){
        Column(Modifier.verticalScroll(contentScroll),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            val shown=card
            OriginCardVisual(shown?.name ?: "SEU NOME",shown?.lastFour.orEmpty(),tapping,
                onClick=card?.takeIf{!loading && !form && !tapping && operation?.terminal!=false && recurringRecord?.terminal!=false && permit==null && quote==null && recurringQuote==null}?.let{current->{requestTap(current)}})
            if(tapping)NfcPulse()
            if(loading)CircularProgressIndicator(Modifier.size(24.dp))
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(form){
                OutlinedTextField(first,{first=it.take(40)},label={Text("Nome no cartão")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(last,{last=it.take(40)},label={Text("Sobrenome")},singleLine=true,modifier=Modifier.fillMaxWidth())
                CaedralButton(onClick={
                    suspend fun create(token:VaultAuthorization?) {
                        loading=true;error=null
                        try {
                            card=withContext(Dispatchers.IO){WalletVault.createOriginCard(context,wallet,account.index,first,last,token)}
                            operation=null;quote=null;details=null;form=false
                        } finally { loading=false }
                    }
                    val current=card
                    if(current!=null) auth=OriginAuth("Gerar novo Origin",VaultAction.RegenerateCard,wallet,account.index,current.fingerprint){create(it)}
                    else scope.launch{try{create(null)}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}}
                },enabled=first.isNotBlank() && last.isNotBlank() && !loading,modifier=Modifier.fillMaxWidth()){Text(if(card==null)"Criar meu Origin" else "Gerar novo cartão")}

            }else if(recurringRecord?.terminal==false){
                Text("Confirmando assinatura",style=MaterialTheme.typography.titleMedium);CircularProgressIndicator(Modifier.size(24.dp))
                BscExplorerButton("Comprovante","/tx/${recurringRecord!!.hash}",OriginNetwork.chain)
            }else if(recurringQuote!=null){
                val q=recurringQuote!!;val o=q.offer!!
                Text("Autorizar recorrência",style=MaterialTheme.typography.titleLarge)
                Text(formatAssetQuantity(BigDecimal(o.gross,18),if(o.token==CaedralProtocol.ZERO_ADDRESS)OriginNetwork.chain.nativeSymbol else "USDT"),style=MaterialTheme.typography.headlineMedium)
                Text("A cada ${o.intervalDays} dias")
                Text(if(o.endAt==0L)"Sem encerramento" else "Até "+DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(o.endAt)))
                Text("Lojista: "+o.merchant.take(10)+"…"+o.merchant.takeLast(8),style=MaterialTheme.typography.bodySmall,color=C.Muted)
                Text("Taxa de rede máxima: "+formatAssetQuantity(BigDecimal(q.fee,18),OriginNetwork.chain.nativeSymbol),style=MaterialTheme.typography.bodySmall,color=C.Muted)
                CaedralButton(onClick={auth=OriginAuth("Autorizar débitos automáticos",VaultAction.SignOrigin,wallet,account.index,q.digest){token->recurringRecord=recurringEngine.send(q,wallet,account.index,token);recurringQuote=null;error=null}},modifier=Modifier.fillMaxWidth()){Text("Autorizar e pagar")}
                TextButton(onClick={recurringQuote=null}){Text("Cancelar")}
            }else if(operation?.terminal==false || permit!=null){
                Text("Processando compra",style=MaterialTheme.typography.titleMedium)
                CircularProgressIndicator(Modifier.size(24.dp))
                operation?.let{BscExplorerButton("Ver na rede","/tx/${it.hash}",OriginNetwork.chain)}
            }else if(quote!=null){
                val q=quote!!
                Text("Confirmar compra",style=MaterialTheme.typography.titleLarge)
                q.call.invoice?.let { i->
                    Text("Lojista: ${i.request.merchant.take(10)}…${i.request.merchant.takeLast(8)}")
                    Text("Valor: ${BigDecimal(i.request.gross,18).stripTrailingZeros().toPlainString()} ${if(i.request.token==CaedralProtocol.ZERO_ADDRESS)OriginNetwork.chain.nativeSymbol else "USDT"}")
                    val split=CaedralProtocol.split(i.request.gross,18,CaedralProtocol.Tier.Origin,Instant.now())
                    val symbol=if(i.request.token==CaedralProtocol.ZERO_ADDRESS)OriginNetwork.chain.nativeSymbol else "USDT"
                    Text("Cashback na sua conta: ${BigDecimal(split.cashback,18).stripTrailingZeros().toPlainString()} $symbol",fontWeight=FontWeight.SemiBold)
                }
                Text("Taxa de rede máxima: ${BigDecimal(requireNotNull(paymentDraft).maximumGas,18).stripTrailingZeros().toPlainString()} ${OriginNetwork.chain.nativeSymbol}")
                CaedralButton(onClick={val draft=requireNotNull(paymentDraft);auth=OriginAuth("Confirmar compra",VaultAction.SignOrigin,wallet,account.index,draft.digest){token->
                    engine.validatePaymentFunds(draft)
                    permit=withContext(Dispatchers.IO){WalletVault.beginOriginPayment(wallet,account.index,draft,token)}
                    operation=sendConsented(q);quote=null;paymentDraft=null;error=null
                }},modifier=Modifier.fillMaxWidth()){Text("Confirmar com segurança")}
                TextButton(onClick={quote=null;paymentDraft=null;received=null;WalletVault.cancelOriginPayment(permit);permit=null}){Text("Cancelar")}
            }else if(tapping){ /* Revalidation is automatic; dismissal ends the session. */ }
            else {
                if(operation?.status==TransactionStatus.Confirmed && operation?.quote?.call?.kind==OriginAction.Pay){Text("Pagamento confirmado",style=MaterialTheme.typography.titleLarge)}
                if(recurringRecord?.status==TransactionStatus.Confirmed)Text("Assinatura autorizada",style=MaterialTheme.typography.titleLarge)
                card?.let{current->
                    CaedralButton(onClick={requestTap(current)},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Aproximar para pagar")}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick={auth=OriginAuth("Ver dados do cartão",VaultAction.ViewCard,wallet,account.index,current.fingerprint){token->details=withContext(Dispatchers.IO){WalletVault.originCardDetails(current,token)}}},enabled=!loading,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,C.Divider),colors=ButtonDefaults.outlinedButtonColors(contentColor=C.Text)){Text("Ver dados")}
                        OutlinedButton(onClick={form=true;first="";last="";details=null},enabled=!loading,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,C.Divider),colors=ButtonDefaults.outlinedButtonColors(contentColor=C.Text)){Text("Regenerar")}
                    }
                }
            }
            if(!tapping && operation?.terminal!=false && recurringRecord?.terminal!=false && permit==null)Surface(color=C.Selected,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().clickable(role=Role.Button){stopTap();subscriptionsOpen=true}) {
                Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    CaedralIcon(CaedralSymbol.History,C.Brand,24.dp)
                    Column(Modifier.weight(1f)){Text("Assinaturas",fontWeight=FontWeight.SemiBold);Text("Gerenciar débitos automáticos",style=MaterialTheme.typography.bodySmall,color=C.Muted)}
                    Text("›",color=C.Muted,style=MaterialTheme.typography.headlineSmall)
                }
            }
            details?.let{d->OriginCardDetailsPanel(requireNotNull(card),d,onHide={details=null})}
        }
    }
    auth?.let{request->OriginAuthDialog(request,onDismiss={auth=null})}
}

@Composable
internal fun OriginCardDetailsPanel(card:OriginCard,details:OriginCardDetails,onHide:()->Unit) {
    Surface(color=C.Selected.copy(alpha=.5f),shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,C.Divider.copy(alpha=.45f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("Dados do cartão",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("TITULAR",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(card.name,style=MaterialTheme.typography.bodyLarge)
            }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("NÚMERO",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(details.number.chunked(4).joinToString(" "),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("VALIDADE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(DateTimeFormatter.ofPattern("MM/yy").withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(card.expiresAt)),fontFamily=FontFamily.Monospace)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("CÓDIGO DE SEGURANÇA",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(details.securityCode,fontFamily=FontFamily.Monospace)
                }
            }
            TextButton(onClick=onHide,modifier=Modifier.align(Alignment.End)){Text("Ocultar dados")}
        }
    }
}
