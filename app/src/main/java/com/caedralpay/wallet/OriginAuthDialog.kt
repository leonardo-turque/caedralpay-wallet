package com.caedralpay.wallet

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

internal data class OriginAuth(val title:String,val action:VaultAction,val walletId:String,val index:Int,val binding:String,
    val operation:suspend(VaultAuthorization)->Unit)
@Composable
internal fun OriginAuthDialog(request:OriginAuth,onDismiss:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var pin by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)}
    var signal by remember{mutableStateOf<CancellationSignal?>(null)}
    val biometric=remember{runCatching{WalletVault.hasBiometric(context)}.getOrDefault(false)}
    fun run(block:suspend()->Unit){if(busy)return;busy=true;error=null;scope.launch{
        try{block();onDismiss()}catch(c:CancellationException){throw c}catch(e:Exception){error=when(e){is WrongPinException->"PIN incorreto.";is PinTemporarilyLockedException->"Aguarde antes de tentar novamente.";else->e.message?.takeIf{it.length<180 && it!="Failed requirement."} ?: "Não foi possível concluir. Tente novamente."}}
        finally{busy=false}
    }}
    fun bio(){
        if(busy)return
        try{val cipher=WalletVault.biometricCipherForUnlock(context);val cancellation=CancellationSignal();signal=cancellation;busy=true
            BiometricPrompt.Builder(context).setTitle(request.title).setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Usar PIN",context.mainExecutor){_,_->cancellation.cancel();busy=false}
                .build().authenticate(BiometricPrompt.CryptoObject(cipher),cancellation,context.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){busy=false;run{
                        val token=withContext(Dispatchers.IO){WalletVault.authorizeWithBiometric(context,requireNotNull(result.cryptoObject?.cipher),request.action,request.walletId,request.index,request.binding)}
                        request.operation(token)
                    }}
                    override fun onAuthenticationError(code:Int,message:CharSequence){busy=false}
                })
        }catch(_:Exception){busy=false;error="Use seu PIN para continuar."}
    }
    LaunchedEffect(Unit){if(biometric)bio()}
    DisposableEffect(Unit){onDispose{signal?.cancel();WalletVault.revokeAuthorization();pin=""}}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(request.title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Confirme com a biometria ou o PIN deste aparelho.")
        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(6)},label={Text("PIN de 6 dígitos")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(biometric)TextButton(onClick=::bio,enabled=!busy){Text("Usar biometria")}
    }},confirmButton={TextButton(onClick={val entered=pin.toCharArray();pin="";run{
        try{val token=withContext(Dispatchers.IO){WalletVault.authorizeWithPin(context,entered,request.action,request.walletId,request.index,request.binding)};request.operation(token)}finally{entered.fill('\u0000')}
    }},enabled=!busy && pin.length==6){if(busy)CircularProgressIndicator(Modifier.size(20.dp))else Text("Confirmar")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancelar")}})
}
