package com.caedralpay.wallet

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class,ExperimentalAnimationApi::class)
@Composable
internal fun OriginMerchantSheet(snapshot:WalletAccountsSnapshot,network:BscChain,onDismiss:()->Unit,gateway:EvmGateway?=null) {
    val context=LocalContext.current
    val activity=generateSequence(context){(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().first()
    val lifecycle=(activity as androidx.lifecycle.LifecycleOwner).lifecycle
    val scope=rememberCoroutineScope();val keyboard=LocalSoftwareKeyboardController.current
    val reader=remember(activity){OriginReader(activity)}
    val merchant=remember{OriginMerchant(context,gateway ?: EvmGateway())}
    val recurring=remember{RecurringMerchant(context,gateway ?: EvmGateway())}
    val account=snapshot.selectedAccount
    var stepName by rememberSaveable{mutableStateOf(MerchantStep.Network.name)}
    var chainName by rememberSaveable{mutableStateOf<String?>(null)}
    var assetName by rememberSaveable{mutableStateOf<String?>(null)}
    var kindName by rememberSaveable{mutableStateOf(MerchantPaymentKind.Once.name)}
    var interval by rememberSaveable{mutableStateOf("30")}
    var noEnd by rememberSaveable{mutableStateOf(true)}
    var endDay by rememberSaveable{mutableStateOf<Long?>(null)}
    var amount by rememberSaveable{mutableStateOf("")}
    var dateOpen by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    var sale by remember{mutableStateOf<OriginSale?>(null)};var recurringSale by remember{mutableStateOf<RecurringSale?>(null)}
    var delivered by remember{mutableStateOf(false)};var auth by remember{mutableStateOf<OriginAuth?>(null)}
    val chain=chainName?.let(BscChain::valueOf)
    val asset=assetName?.let(WalletAsset::valueOf)
    val kind=MerchantPaymentKind.valueOf(kindName)
    val step=MerchantStep.valueOf(stepName)
    val path=if(kind==MerchantPaymentKind.Recurring)listOf(MerchantStep.Network,MerchantStep.Asset,MerchantStep.Kind,MerchantStep.Schedule,MerchantStep.Amount,MerchantStep.Review)else listOf(MerchantStep.Network,MerchantStep.Asset,MerchantStep.Kind,MerchantStep.Amount,MerchantStep.Review)
    fun go(next:MerchantStep){keyboard?.hide();error=null;stepName=next.name}
    fun previous(){if(step==MerchantStep.Present || step==MerchantStep.Receipt){onDismiss();return};val i=path.indexOf(step);if(i>0)go(path[i-1])else onDismiss()}
    fun request(validate:Boolean=true):MerchantCharge {
        val days=if(kind==MerchantPaymentKind.Recurring)requireNotNull(MerchantCharge.interval(interval)){"Informe o intervalo em dias."}else 0
        val end=if(kind==MerchantPaymentKind.Recurring && !noEnd)MerchantCharge.endOfDay(LocalDate.ofEpochDay(requireNotNull(endDay){"Escolha a data de encerramento."}),ZoneId.systemDefault())else 0
        return MerchantCharge(requireNotNull(chain),requireNotNull(asset),amount,kind,days,end).also{if(validate)it.validate()}
    }
    fun message(e:Exception)=e.message?.takeIf{it.length<140 && it!="Failed requirement."} ?: "Não foi possível preparar a cobrança."
    fun startReader(){
        try {
            val callback:(Boolean)->Unit={ok->delivered=ok;if(!ok)error="Não foi possível ler o cartão. Aproxime novamente."}
            if(recurringSale!=null)reader.start(recurringSale!!.offer,callback)else reader.start(requireNotNull(sale).invoice,callback)
        }catch(_:Exception){error="Ative o NFC do celular para aproximar."}
    }
    fun create(){
        val charge=try{request()}catch(e:Exception){error=message(e);return}
        busy=true;error=null;scope.launch {
            try {
                if(charge.kind==MerchantPaymentKind.Once){
                    val draft=merchant.draft(charge.chain,account.address,charge.asset,charge.amount)
                    auth=OriginAuth("Criar cobrança",VaultAction.SignInvoice,snapshot.selectedWalletId,account.index,OriginAbi.sha(OriginAbi.invoiceDigest(draft.first).hex())){token->
                        val signed=withContext(Dispatchers.IO){WalletVault.signOriginInvoice(context,snapshot.selectedWalletId,account.index,draft.first,token)}
                        sale=withContext(Dispatchers.IO){merchant.save(signed,draft.second)};delivered=false;go(MerchantStep.Present);startReader()
                    }
                }else {
                    val draft=recurring.draft(charge,account.address)
                    auth=OriginAuth("Criar recorrência",VaultAction.SignInvoice,snapshot.selectedWalletId,account.index,OriginAbi.sha(draft.first.digest().hex())){token->
                        val signed=withContext(Dispatchers.IO){WalletVault.signRecurringOffer(context,snapshot.selectedWalletId,account.index,draft.first,token)}
                        recurringSale=withContext(Dispatchers.IO){recurring.save(signed,draft.second)};delivered=false;go(MerchantStep.Present);startReader()
                    }
                }
            }catch(c:CancellationException){throw c}catch(e:Exception){error=message(e)}finally{busy=false}
        }
    }
    LaunchedEffect(Unit){if((step==MerchantStep.Present || step==MerchantStep.Receipt) && sale==null && recurringSale==null)go(MerchantStep.Network)}
    LaunchedEffect(sale?.invoice?.request?.id){var current=sale ?: return@LaunchedEffect
        while(current.hash==null && !current.expired){try{current=merchant.poll(current);sale=current;if(current.hash!=null){reader.stop();go(MerchantStep.Receipt)}}catch(c:CancellationException){throw c}catch(_:Exception){};if(current.hash==null && !current.expired)delay(2000)}
    }
    LaunchedEffect(recurringSale?.offer?.id){var current=recurringSale ?: return@LaunchedEffect
        while(current.hash==null && !current.expired){try{current=recurring.poll(current);recurringSale=current;if(current.hash!=null){reader.stop();go(MerchantStep.Receipt)}}catch(c:CancellationException){throw c}catch(_:Exception){};if(current.hash==null && !current.expired)delay(2000)}
    }
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)reader.stop()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);reader.stop()}}
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn,dismissOnBackPress=false)) {
        BackHandler(enabled=!busy){previous()}
        Surface(Modifier.fillMaxSize(),color=C.Background,contentColor=C.Text) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    if(step!=MerchantStep.Network)IconButton(onClick=::previous,enabled=!busy,modifier=Modifier.semantics{contentDescription="Etapa anterior"}){Text("‹",style=MaterialTheme.typography.headlineLarge)}
                    Text("Maquininha",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f).padding(start=if(step==MerchantStep.Network)8.dp else 0.dp))
                    IconButton(onClick=onDismiss,enabled=!busy,modifier=Modifier.semantics{contentDescription="Fechar maquininha"}){CaedralIcon(CaedralSymbol.Close,C.Muted)}
                }
                if(step in path)Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {path.forEachIndexed{i,_->Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if(i<=path.indexOf(step))C.Brand else C.Divider))}}
                AnimatedContent(targetState=step,transitionSpec={
                    val forward=targetState.ordinal>=initialState.ordinal
                    (fadeIn(tween(200,70))+slideInHorizontally(tween(260)){if(forward)it/12 else -it/12}) togetherWith
                        (fadeOut(tween(130))+slideOutHorizontally(tween(220)){if(forward)-it/12 else it/12})
                },label="merchant-steps",modifier=Modifier.weight(1f)) { currentStep ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        when(currentStep){
                            MerchantStep.Network->{
                                MerchantHeading("Escolha a rede")
                                BscChain.entries.forEach{candidate->
                                    val available=candidate==OriginNetwork.chain
                                    MerchantOption("BNB Smart Chain",if(candidate.isTestnet)"Testnet" else "Mainnet",enabled=available,badge=if(available)null else "Em breve",mark="B"){chainName=candidate.name;assetName=null;go(MerchantStep.Asset)}
                                }
                            }
                            MerchantStep.Asset->{
                                MerchantHeading("Escolha a moeda")
                                requireNotNull(chain).assets.forEach{coin->MerchantOption(coin.symbolOn(chain),coin.displayName,mark=if(coin==WalletAsset.BNB)"B" else "₮",tint=if(coin==WalletAsset.BNB)Color(0xFFF0B90B)else Color(0xFF26A17B)){assetName=coin.name;go(MerchantStep.Kind)}}
                            }
                            MerchantStep.Kind->{
                                MerchantHeading("Tipo de pagamento")
                                MerchantOption("Pagamento único","Uma cobrança",mark="1"){kindName=MerchantPaymentKind.Once.name;go(MerchantStep.Amount)}
                                MerchantOption("Recorrente","Débito a cada intervalo",mark="↻"){kindName=MerchantPaymentKind.Recurring.name;go(MerchantStep.Schedule)}
                            }
                            MerchantStep.Schedule->{
                                MerchantHeading("Defina a recorrência")
                                OutlinedTextField(interval,{interval=it.filter(Char::isDigit).take(4);error=null},label={Text("Intervalo em dias")},suffix={Text("dias")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),modifier=Modifier.fillMaxWidth())
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){listOf(7,15,30).forEach{days->FilterChip(selected=interval==days.toString(),onClick={interval=days.toString()},label={Text("$days dias")})}}
                                HorizontalDivider(color=C.Divider)
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Sem encerramento",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=noEnd,onCheckedChange={noEnd=it;error=null})}
                                AnimatedVisibility(!noEnd){OutlinedButton(onClick={dateOpen=true},shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text(endDay?.let{LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} ?: "Data de encerramento")}}
                            }
                            MerchantStep.Amount->{
                                MerchantHeading(if(kind==MerchantPaymentKind.Recurring)"Valor por cobrança"else"Qual é o valor?")
                                Text(requireNotNull(asset).symbolOn(requireNotNull(chain)),style=MaterialTheme.typography.titleLarge,color=C.Muted)
                                OutlinedTextField(amount,{amount=it.take(40);error=null},label={Text("Valor")},singleLine=true,textStyle=MaterialTheme.typography.headlineLarge,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={try{request();go(MerchantStep.Review)}catch(e:Exception){error=message(e)}}),modifier=Modifier.fillMaxWidth())
                            }
                            MerchantStep.Review->{
                                MerchantHeading("Revise a cobrança")
                                val charge=request(false);val symbol=charge.asset.symbolOn(charge.chain)
                                Text(formatAssetQuantity(BigDecimal(charge.gross,18),symbol),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold)
                                Surface(color=C.Selected,shape=MaterialTheme.shapes.large){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                                    MerchantSummary("Rede",if(charge.chain.isTestnet)"BNB · Testnet"else"BNB Smart Chain")
                                    MerchantSummary("Moeda",symbol)
                                    MerchantSummary("Pagamento",if(kind==MerchantPaymentKind.Once)"Único"else"Recorrente")
                                    if(kind==MerchantPaymentKind.Recurring){MerchantSummary("Intervalo","${charge.intervalDays} dias");MerchantSummary("Encerramento",if(noEnd)"Sem encerramento"else LocalDate.ofEpochDay(endDay!!).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))}
                                    HorizontalDivider(color=C.Divider)
                                    val split=CaedralProtocol.split(charge.gross,18,CaedralProtocol.Tier.Origin,Instant.now())
                                    MerchantSummary("Taxa Caedral",formatAssetQuantity(BigDecimal(split.fee,18),symbol))
                                    MerchantSummary("Você recebe",formatAssetQuantity(BigDecimal(split.merchant,18),symbol),true)
                                }}
                            }
                            MerchantStep.Present,MerchantStep.Receipt->{
                                val charge=request(false);val symbol=charge.asset.symbolOn(charge.chain)
                                val expired=sale?.expired==true || recurringSale?.expired==true
                                val confirmed=currentStep==MerchantStep.Receipt
                                MerchantHeading(if(confirmed)"Pagamento recebido"else if(expired)"Cobrança expirada"else"Receber pagamento")
                                Text(formatAssetQuantity(BigDecimal(charge.gross,18),symbol),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.SemiBold)
                                if(kind==MerchantPaymentKind.Recurring)Text("A cada ${charge.intervalDays} dias",color=C.Muted)
                                Spacer(Modifier.height(24.dp))
                                if(confirmed){Text("✓",style=MaterialTheme.typography.displayLarge,color=C.Positive);val hash=sale?.hash ?: recurringSale?.hash;hash?.let{BscExplorerButton("Comprovante","/tx/$it",charge.chain)}}
                                else if(!expired)NfcPulse(if(delivered)"Aguardando pagamento"else"Aproxime o celular")
                                Spacer(Modifier.height(16.dp))
                                if(!confirmed && !expired)OutlinedButton(onClick=::startReader,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=MaterialTheme.shapes.medium){Text(if(delivered)"Aproximar novamente"else"Apresentar cobrança")}
                            }
                        }
                        error?.let{Text(it,color=C.Error,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if(step==MerchantStep.Schedule || step==MerchantStep.Amount || step==MerchantStep.Review || step==MerchantStep.Receipt || (step==MerchantStep.Present && (sale?.expired==true || recurringSale?.expired==true))) {
                    Surface(color=C.Background){Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(top=12.dp,bottom=20.dp)){
                        CaedralButton(onClick={when(step){
                            MerchantStep.Schedule->{val days=MerchantCharge.interval(interval);if(days==null)error="Informe o intervalo em dias."else if(!noEnd && endDay==null)error="Escolha a data de encerramento."else go(MerchantStep.Amount)}
                            MerchantStep.Amount->{try{request();go(MerchantStep.Review)}catch(e:Exception){error=message(e)}}
                            MerchantStep.Review->create()
                            else->{reader.stop();sale=null;recurringSale=null;amount="";delivered=false;go(MerchantStep.Network)}
                        }},enabled=!busy,modifier=Modifier.fillMaxWidth()){
                            if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=C.OnButton)else Text(when(step){MerchantStep.Review->"Cobrar por aproximação";MerchantStep.Receipt->"Nova cobrança";MerchantStep.Present->"Nova cobrança";else->"Continuar"})
                        }
                    }}
                }
            }
        }
    }
    if(dateOpen){
        val today=LocalDate.now();val state=rememberDatePickerState(initialSelectedDateMillis=(endDay ?: today.toEpochDay())*86400000,selectableDates=object:SelectableDates{override fun isSelectableDate(utcTimeMillis:Long)=utcTimeMillis/86400000>=today.toEpochDay()})
        DatePickerDialog(onDismissRequest={dateOpen=false},confirmButton={TextButton(onClick={endDay=state.selectedDateMillis!!/86400000;dateOpen=false;error=null},enabled=state.selectedDateMillis!=null){Text("Selecionar")}},dismissButton={TextButton(onClick={dateOpen=false}){Text("Cancelar")}}){DatePicker(state)}
    }
    auth?.let{OriginAuthDialog(it,onDismiss={auth=null})}
}
@Composable
private fun MerchantHeading(text:String){Text(text,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp,bottom=8.dp).semantics{heading()})}
@Composable
private fun MerchantSummary(label:String,value:String,strong:Boolean=false){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){Text(label,color=C.Muted,modifier=Modifier.weight(1f));Text(value,fontWeight=if(strong)FontWeight.SemiBold else FontWeight.Normal)}}
@Composable
private fun MerchantOption(title:String,subtitle:String,enabled:Boolean=true,badge:String?=null,mark:String,tint:Color?=null,onClick:()->Unit){
    Surface(color=C.Selected.copy(alpha=if(enabled).6f else .25f),shape=MaterialTheme.shapes.large,border=BorderStroke(1.dp,C.Divider.copy(alpha=.6f)),modifier=Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(enabled=enabled,role=Role.Button,onClick=onClick)) {
        Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Box(Modifier.size(48.dp).clip(CircleShape).background(tint ?: C.Brand.copy(alpha=.12f)),contentAlignment=Alignment.Center){Text(mark,style=MaterialTheme.typography.titleLarge,color=if(tint!=null)Color.White else C.Brand)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=if(enabled)C.Text else C.Disabled);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=C.Muted)}
            if(badge!=null)Text(badge,style=MaterialTheme.typography.labelSmall,color=C.Muted)else Text("›",style=MaterialTheme.typography.headlineMedium,color=C.Muted)
        }
    }
}
