package com.caedralpay.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import com.caedralpay.wallet.ui.theme.CaedralColors as C

@Composable
internal fun OriginSubscriptionsSheet(snapshot:WalletAccountsSnapshot,onDismiss:()->Unit,gateway:EvmGateway?=null) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val engine=remember{RecurringEngine(context,gateway ?: EvmGateway())}
    val account=snapshot.selectedAccount
    var subscriptions by remember{mutableStateOf<List<RecurringSubscription>?>(null)}
    var error by remember{mutableStateOf<String?>(null)};var quote by remember{mutableStateOf<RecurringQuote?>(null)}
    var auth by remember{mutableStateOf<OriginAuth?>(null)};var record by remember{mutableStateOf<RecurringRecord?>(null)}
    var more by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    suspend fun load(append:Boolean=false){loading=true;try{val page=engine.list(account.address,if(append)subscriptions.orEmpty().size else 0);subscriptions=if(append)subscriptions.orEmpty()+page else page;more=page.size==25;error=null}catch(c:CancellationException){throw c}catch(_:Exception){error="Não foi possível carregar as assinaturas."}finally{loading=false}}
    LaunchedEffect(account.address){record=engine.history().lastOrNull{!it.terminal && it.quote.from.equals(account.address,true) && it.quote.action==RecurringAction.Cancel};load()}
    LaunchedEffect(record?.hash){var current=record ?: return@LaunchedEffect;while(!current.terminal){try{if(current.status==TransactionStatus.Signed)current=engine.resume(current);current=engine.refresh(current);record=current}catch(c:CancellationException){throw c}catch(_:Exception){};if(!current.terminal)delay(2000)};if(current.status==TransactionStatus.Confirmed){quote=null;load()}else error="O cancelamento não foi confirmado."}
    CaedralSheet("Assinaturas",onDismiss=onDismiss){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(loading)CircularProgressIndicator(Modifier.size(24.dp))
            error?.let{Text(it,color=C.Error);TextButton(onClick={scope.launch{load()}}){Text("Tentar novamente")}}
            if(record?.terminal==false){Text("Cancelando assinatura");CircularProgressIndicator(Modifier.size(24.dp));BscExplorerButton("Comprovante","/tx/${record!!.hash}",OriginNetwork.chain)}
            else if(quote!=null){val q=quote!!;Text("Cancelar débitos automáticos?",style=MaterialTheme.typography.titleLarge)
                Text("Taxa de rede máxima: "+formatAssetQuantity(BigDecimal(q.fee,18),OriginNetwork.chain.nativeSymbol),color=C.Muted)
                CaedralButton(onClick={auth=OriginAuth("Cancelar assinatura",VaultAction.SignOrigin,snapshot.selectedWalletId,account.index,q.digest){token->record=engine.send(q,snapshot.selectedWalletId,account.index,token)}},modifier=Modifier.fillMaxWidth()){Text("Confirmar cancelamento")}
                TextButton(onClick={quote=null}){Text("Manter assinatura")}
            }else {
                if(subscriptions?.isEmpty()==true)Text("Nenhuma assinatura",color=C.Muted)
                subscriptions.orEmpty().forEach{s->Surface(color=C.Selected,shape=MaterialTheme.shapes.large){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(formatAssetQuantity(BigDecimal(s.gross,18),if(s.token==CaedralProtocol.ZERO_ADDRESS)OriginNetwork.chain.nativeSymbol else "USDT"),style=MaterialTheme.typography.titleLarge)
                    Text("A cada ${s.intervalDays} dias",color=C.Muted)
                    Text(s.merchant.take(10)+"…"+s.merchant.takeLast(8),style=MaterialTheme.typography.bodySmall,color=C.Muted)
                    Text(if(s.cancelled)"Cancelada" else if(s.ended())"Encerrada" else "Próximo débito: "+DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(s.nextDue)))
                    if(!s.ended())TextButton(onClick={scope.launch{loading=true;try{quote=engine.prepareCancel(s,account.address)}catch(c:CancellationException){throw c}catch(e:Exception){error=e.message?.takeIf{it.length<120 && it!="Failed requirement."} ?: "Não foi possível preparar o cancelamento."}finally{loading=false}}},enabled=!loading){Text("Cancelar assinatura")}
                }}}
                if(more)TextButton(onClick={scope.launch{load(true)}},enabled=!loading){Text("Carregar mais")}
            }
        }
    }
    auth?.let{OriginAuthDialog(it,onDismiss={auth=null})}
}
