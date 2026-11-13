package com.caedralpay.wallet

import android.app.Activity
import android.content.ComponentName
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var loading by remember{mutableStateOf(true)};var active by remember{mutableStateOf(false)}
    var first by remember{mutableStateOf("")};var last by remember{mutableStateOf("")}
    var form by remember{mutableStateOf(false)}
    var auth by remember{mutableStateOf<OriginAuth?>(null)};var error by remember{mutableStateOf<String?>(null)}
    var details by remember{mutableStateOf<OriginCardDetails?>(null)};var tapping by remember{mutableStateOf(false)}
    var received by remember{mutableStateOf<OriginInvoice?>(null)};var quote by remember{mutableStateOf<OriginQuote?>(null)}
    var operation by remember{mutableStateOf<OriginRecord?>(null)}
    val incoming by OriginTapSession.request.collectAsState()
    fun fail(e:Exception){error=e.message?.takeIf{it.length<160 && it!="Failed requirement."} ?: "Não foi possível concluir. Tente novamente."}
    fun stopTap(){OriginTapSession.clear();tapping=false;runCatching{NfcAdapter.getDefaultAdapter(activity)?.let{CardEmulation.getInstance(it).unsetPreferredService(activity)}}}
    suspend fun reload() {
        val saved=withContext(Dispatchers.IO){OriginCardStore.latest(context,wallet,account.index)};card=saved
        active=if(saved!=null && network==OriginNetwork.chain)runCatching{engine.isActive(saved)}.getOrDefault(false)else false
        operation=if(network==OriginNetwork.chain)withContext(Dispatchers.IO){engine.history().lastOrNull{!it.terminal && it.quote.from.equals(account.address,true)}}else null
    }
    fun prepare(call:OriginCall){loading=true;error=null;scope.launch{
        try{OriginNetwork.requireAvailable(network);quote=engine.prepare(call,account.address)}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}finally{loading=false}
    }}
    suspend fun prepareReceived(invoice:OriginInvoice,owner:OriginCard){
        OriginNetwork.requireAvailable(network)
        invoice.validate();require(owner.id==card?.id && owner.walletId==wallet && owner.index==account.index)
        received=invoice
        val allowance=if(invoice.request.token==CaedralProtocol.ZERO_ADDRESS)invoice.request.gross else engine.allowance(account.address)
        quote=engine.prepare(OriginCall(if(allowance>=invoice.request.gross)OriginAction.Pay else if(allowance.signum()==0)OriginAction.Approve else OriginAction.ResetApproval,owner.id,invoice=invoice),account.address)
    }
    fun requestTap(current:OriginCard) {
        val adapter=NfcAdapter.getDefaultAdapter(activity)
        if(adapter?.isEnabled!=true || !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            error="Ative o NFC em um aparelho compatível para continuar.";return
        }
        auth=OriginAuth("Habilitar aproximação",VaultAction.ArmCard,wallet,account.index,current.fingerprint){token->
                        OriginNetwork.requireAvailable(network);require(engine.isActive(current)){"Este cartão foi substituído ou expirou."}
                        WalletVault.consumeCardAuthorization(token,VaultAction.ArmCard,wallet,account.index,current.fingerprint)
                        val adapter=NfcAdapter.getDefaultAdapter(activity);require(adapter?.isEnabled==true){"Ative o NFC do celular."}
                        require(CardEmulation.getInstance(adapter).setPreferredService(activity,ComponentName(activity,OriginHostService::class.java)))
                        OriginTapSession.arm(current);tapping=true;received=null;operation=null
                    }
    }
    LaunchedEffect(wallet,account.index,network){loading=true;active=false;auth=null;error=null;try{stopTap();details=null;quote=null;reload();form=card==null}catch(e:Exception){fail(e)}finally{loading=false}}
    LaunchedEffect(incoming){val request=incoming ?: return@LaunchedEffect
        stopTap();loading=true
        try{prepareReceived(request.invoice,request.card)}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}finally{loading=false}
    }
    LaunchedEffect(tapping){if(tapping){contentScroll.animateScrollTo(0);delay(120_000);stopTap()}}
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
            if(record.quote.call.kind==OriginAction.Register){card=withContext(Dispatchers.IO){OriginCardStore(context).find(wallet,account.index)};active=true}
            if(record.quote.call.kind==OriginAction.Approve || record.quote.call.kind==OriginAction.ResetApproval){val invoice=record.quote.call.invoice!!;try{quote=engine.prepare(OriginCall(if(record.quote.call.kind==OriginAction.ResetApproval)OriginAction.Approve else OriginAction.Pay,record.quote.call.cardId,invoice=invoice),account.address)}catch(e:Exception){fail(e)}}
        }else error="A operação não foi concluída pela rede. Confira a atividade antes de tentar novamente."
    }
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE){stopTap();details=null}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);stopTap();details=null;WalletVault.revokeAuthorization()}}
    CaedralSheet("Seu cartão Origin",onDismiss=onDismiss){
        Column(Modifier.verticalScroll(contentScroll),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            val shown=card
            OriginCardVisual(shown?.name ?: "SEU NOME",shown?.lastFour.orEmpty(),tapping,
                onClick=card?.takeIf{active && !loading && !form && !tapping && operation?.terminal!=false && quote==null}?.let{current->{requestTap(current)}})
            if(tapping)NfcPulse()
            OriginAccountSummary(account.name)
            if(card!=null && !tapping && quote==null && operation?.terminal!=false) {
                Text("Um cartão para todas as redes",style=MaterialTheme.typography.titleMedium)
                Text("Mesmo número, nome, validade e código de segurança. Trocar de rede não altera seu cartão.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(loading)CircularProgressIndicator(Modifier.size(24.dp))
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(form){
                if(card!=null)Text("Os dados mudam em todas as redes. Depois, ative os pagamentos com os novos dados nas redes que usar.")
                OutlinedTextField(first,{first=it.take(40)},label={Text("Nome no cartão")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(last,{last=it.take(40)},label={Text("Sobrenome")},singleLine=true,modifier=Modifier.fillMaxWidth())
                CaedralButton(onClick={
                    suspend fun create(token:VaultAuthorization?) {
                        loading=true;error=null
                        try {
                            card=withContext(Dispatchers.IO){WalletVault.createOriginCard(context,wallet,account.index,first,last,token)}
                            active=false;operation=null;quote=null;details=null;form=false
                        } finally { loading=false }
                    }
                    val current=card
                    if(current!=null) auth=OriginAuth("Gerar novo Origin",VaultAction.RegenerateCard,wallet,account.index,current.fingerprint){create(it)}
                    else scope.launch{try{create(null)}catch(c:CancellationException){throw c}catch(e:Exception){fail(e)}}
                },enabled=first.isNotBlank() && last.isNotBlank() && !loading,modifier=Modifier.fillMaxWidth()){Text(if(card==null)"Criar meu Origin" else "Gerar novo cartão")}

            }else if(operation?.terminal==false){
                Text(when(operation!!.quote.call.kind){OriginAction.Register->"Ativando seu cartão";OriginAction.ResetApproval->"Redefinindo autorização do token";OriginAction.Approve->"Confirmando autorização do token";OriginAction.Pay->"Acompanhando pagamento"},style=MaterialTheme.typography.titleMedium)
                CircularProgressIndicator(Modifier.size(24.dp))
                BscExplorerButton("Ver na rede","/tx/${operation!!.hash}",OriginNetwork.chain)
            }else if(quote!=null){
                val q=quote!!
                Text(when(q.call.kind){OriginAction.Register->"Ativação do Origin";OriginAction.ResetApproval->"Redefinir autorização anterior de USDT";OriginAction.Approve->"Autorizar uso deste valor de USDT";OriginAction.Pay->"Confirmar compra"},style=MaterialTheme.typography.titleLarge)
                q.call.invoice?.let { i->
                    Text("Lojista: ${i.request.merchant.take(10)}…${i.request.merchant.takeLast(8)}")
                    Text("Valor: ${BigDecimal(i.request.gross,18).stripTrailingZeros().toPlainString()} ${if(i.request.token==CaedralProtocol.ZERO_ADDRESS)network.nativeSymbol else "USDT"}")
                    val split=CaedralProtocol.split(i.request.gross,18,CaedralProtocol.Tier.Origin,Instant.now())
                    val symbol=if(i.request.token==CaedralProtocol.ZERO_ADDRESS)network.nativeSymbol else "USDT"
                    Text("Cashback na sua conta: ${BigDecimal(split.cashback,18).stripTrailingZeros().toPlainString()} $symbol",fontWeight=FontWeight.SemiBold)
                    Text("Creditado na mesma transação, descontado da taxa Caedral.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Taxa de rede máxima: ${BigDecimal(q.fee,18).stripTrailingZeros().toPlainString()} ${network.nativeSymbol}")
                CaedralButton(onClick={auth=OriginAuth("Confirmar operação",VaultAction.SignOrigin,wallet,account.index,q.digest){token->
                    val result=engine.send(q,wallet,account.index,token);operation=result;quote=null;error=null
                }},modifier=Modifier.fillMaxWidth()){Text("Confirmar com segurança")}
                TextButton(onClick={quote=null;received=null}){Text("Cancelar")}
            }else if(tapping){TextButton(onClick=::stopTap){Text("Encerrar aproximação")}}
            else {
                if(operation?.status==TransactionStatus.Confirmed && operation?.quote?.call?.kind==OriginAction.Pay){Text("Pagamento confirmado",style=MaterialTheme.typography.titleLarge);Text("O cashback foi creditado nesta conta, na moeda da compra.",style=MaterialTheme.typography.bodyMedium)}
                card?.let{current->
                    if(!active && network==OriginNetwork.chain)CaedralButton(onClick={prepare(OriginCall(OriginAction.Register,current.id,current.expiresAt,registrationSalt=current.salt))},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Ativar pagamentos nesta rede")}
                    if(active)CaedralButton(onClick={requestTap(current)},modifier=Modifier.fillMaxWidth()){Text("Aproximar para pagar")}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick={auth=OriginAuth("Ver dados do cartão",VaultAction.ViewCard,wallet,account.index,current.fingerprint){token->details=withContext(Dispatchers.IO){WalletVault.originCardDetails(current,token)}}},enabled=!loading,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,C.Divider),colors=ButtonDefaults.outlinedButtonColors(contentColor=C.Text)){Text("Ver dados")}
                        OutlinedButton(onClick={form=true;first="";last="";details=null},enabled=!loading,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,C.Divider),colors=ButtonDefaults.outlinedButtonColors(contentColor=C.Text)){Text("Regenerar")}
                    }
                    if(!active)Text(if(network==OriginNetwork.chain)"Seus dados já estão prontos. Ative os pagamentos nesta rede usando o mesmo cartão." else "Pagamentos Caedral ainda não disponíveis nesta rede.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            details?.let{d->OriginCardDetailsPanel(requireNotNull(card),d,onHide={details=null})}
        }
    }
    auth?.let{request->OriginAuthDialog(request,onDismiss={auth=null})}
}

@Composable
internal fun OriginAccountSummary(accountName:String) {
    Surface(color=C.Selected.copy(alpha=.5f),shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,C.Divider.copy(alpha=.45f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text("CONTA VINCULADA",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(accountName,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Compras e cashback usam diretamente esta conta.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
