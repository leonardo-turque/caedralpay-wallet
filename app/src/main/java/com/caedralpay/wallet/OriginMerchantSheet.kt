package com.caedralpay.wallet

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.time.Instant

@Composable
internal fun OriginMerchantSheet(snapshot:WalletAccountsSnapshot,network:BscChain,onDismiss:()->Unit) {
    val context=LocalContext.current
    val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().first()
    val lifecycle=(activity as androidx.lifecycle.LifecycleOwner).lifecycle
    val reader=remember{OriginReader(activity)};val merchant=remember{OriginMerchant(context)};val scope=rememberCoroutineScope()
    val account=snapshot.selectedAccount
    var amount by remember{mutableStateOf("")};var asset by remember{mutableStateOf(WalletAsset.USDT)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    var sale by remember{mutableStateOf<OriginSale?>(null)};var delivered by remember{mutableStateOf(false)}
    var auth by remember{mutableStateOf<OriginAuth?>(null)}
    LaunchedEffect(account.address,network){if(network!=OriginNetwork.chain)return@LaunchedEffect;sale=withContext(Dispatchers.IO){OriginSales(context).all().lastOrNull{it.hash==null && !it.expired && it.invoice.request.merchant.equals(account.address,true)}}}
    LaunchedEffect(sale?.invoice?.request?.id){
        val saved=sale ?: return@LaunchedEffect
        if(saved.hash!=null || saved.expired)return@LaunchedEffect
        var current=saved
        while(current.hash==null && !current.expired){
            try{current=merchant.poll(current);sale=current;if(current.hash!=null)reader.stop()}
            catch(c:CancellationException){throw c}catch(_:Exception){error="Não foi possível atualizar a confirmação. A cobrança permanece salva."}
            delay(2500)
        }
        reader.stop()
    }
    fun startReader(current:OriginSale) {
        try{reader.start(current.invoice){ok->delivered=ok;if(!ok)error="Peça ao cliente para abrir o Origin e habilitar a aproximação."}}
        catch(_:Exception){error="Ative o NFC para apresentar a cobrança."}
    }
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)reader.stop()}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);reader.stop()}}
    CaedralSheet("Receber com Caedral",busy=busy,onDismiss=onDismiss){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("Maquininha",style=MaterialTheme.typography.headlineMedium)
            Text("Receber na conta ${account.name}",style=MaterialTheme.typography.bodyMedium)
            val current=sale
            if(current==null){
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { network.assets.forEach{choice->FilterChip(selected=asset==choice,onClick={asset=choice},label={Text(choice.symbolOn(network))})} }
                OutlinedTextField(amount,{amount=it.take(40);error=null},label={Text("Valor da compra")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                CaedralButton(onClick={busy=true;error=null;scope.launch{
                    try{val draft=merchant.draft(network,account.address,asset,amount)
                        auth=OriginAuth("Apresentar cobrança",VaultAction.SignInvoice,snapshot.selectedWalletId,account.index,OriginAbi.sha(OriginAbi.invoiceDigest(draft.first).hex())){token->
                            val invoice=withContext(Dispatchers.IO){WalletVault.signOriginInvoice(context,snapshot.selectedWalletId,account.index,draft.first,token)}
                            val saved=withContext(Dispatchers.IO){merchant.save(invoice,draft.second)};sale=saved;startReader(saved)
                        }
                    }catch(c:CancellationException){throw c}catch(e:Exception){error=e.message?.takeIf{it.length<160 && it!="Failed requirement."} ?: "Confira o valor e a rede selecionada."}
                    finally{busy=false}
                }},enabled=amount.isNotBlank() && !busy,modifier=Modifier.fillMaxWidth()){if(busy)CircularProgressIndicator(Modifier.size(20.dp))else Text("Criar cobrança")}
            }else {
                val i=current.invoice.request
                Text("${BigDecimal(i.gross,18).stripTrailingZeros().toPlainString()} ${if(i.token==CaedralProtocol.ZERO_ADDRESS)network.nativeSymbol else "USDT"}",style=MaterialTheme.typography.displaySmall)
                val split=CaedralProtocol.split(i.gross,18,CaedralProtocol.Tier.Origin,Instant.ofEpochSecond(i.issuedAt))
                Text("Você recebe ${BigDecimal(split.merchant,18).stripTrailingZeros().toPlainString()} após a taxa Caedral.")
                when {
                    current.hash!=null->{Text("Pagamento recebido",style=MaterialTheme.typography.headlineSmall);BscExplorerButton("Ver comprovante","/tx/${current.hash}",OriginNetwork.chain)}
                    current.expired->Text("Esta cobrança expirou sem pagamento confirmado. Crie uma nova para continuar.")
                    else->{NfcPulse("Aproxime o celular do cliente");Text(if(delivered)"Cobrança entregue. Aguardando pagamento na rede." else "O cliente deve abrir seu Origin e aproximar o celular.")
                        CaedralButton(onClick={startReader(current)},enabled=reader.supported,modifier=Modifier.fillMaxWidth()){Text("Apresentar por aproximação")}}
                }
                if(current.hash!=null || current.expired)CaedralButton(onClick={sale=null;amount="";delivered=false;error=null},modifier=Modifier.fillMaxWidth()){Text("Nova cobrança")}
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    auth?.let{request->OriginAuthDialog(request,onDismiss={auth=null})}
}
