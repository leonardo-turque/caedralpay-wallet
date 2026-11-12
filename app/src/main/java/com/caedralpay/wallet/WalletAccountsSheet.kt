package com.caedralpay.wallet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Referência visual e de interação: MetaMask Mobile — ConsenSys Software Inc.
 * https://github.com/MetaMask/metamask-mobile/blob/a216ff1ef6c0af89eafbf8a19aab1a15b4b281af/app/components/Views/AccountSelector/AccountSelector.tsx
 * CaedralPay: lista agrupada em Compose, rodapé fixo e integração com o cofre próprio.
 * Autorização visual informada pelo responsável em 11/11/2026; sem controllers MetaMask.
 */
@Composable
internal fun WalletAccountsSheet(snapshot: WalletAccountsSnapshot, onChanged: (WalletAccountsSnapshot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(AccountPage.List) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Conta ${snapshot.selectedWallet.nextAccountIndex + 1}") }
    var management by remember { mutableStateOf<ManagementTarget?>(null) }
    if (management != null) {
        WalletManagementSheet(requireNotNull(management), snapshot, onChanged, onDismiss = { management = null })
        return
    }
    fun change(operation: () -> WalletAccountsSnapshot) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { operation() }
                onChanged(updated)
                finished = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (failure is WalletAccountException) failure.message.orEmpty()
                    else "Não foi possível salvar a alteração. Tente novamente."
            } finally { busy = false }
        }
    }
    CaedralSheet(
        title = when (page) { AccountPage.List -> "Carteiras e contas"; AccountPage.Create -> "Nova conta"; AccountPage.Import -> "Importar carteira" },
        busy = busy, closeRequested = finished, onDismiss = onDismiss
    ) {
        BackHandler(enabled = page == AccountPage.Create) { if (!busy) { page = AccountPage.List; error = "" } }
        when (page) {
            AccountPage.List -> {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("wallet-account-list").semantics { selectableGroup() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    snapshot.wallets.forEach { wallet ->
                        item(key = "heading-${wallet.id}") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(wallet.name.uppercase(java.util.Locale.forLanguageTag("pt-BR")), color = C.Muted,
                                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f).padding(top = 16.dp, bottom = 8.dp))
                            AccountOverflow("Opções da carteira ${wallet.name}", listOf(
                                ManagementAction.RenameWallet, ManagementAction.RevealPhrase, ManagementAction.ChangePin
                            )) { management = ManagementTarget(wallet.id, null, it) }
                            }
                        }
                        items(wallet.accounts, key = { "${wallet.id}-${it.index}" }) { account ->
                            val selected = wallet.id == snapshot.selectedWalletId && account.index == snapshot.selectedAccountIndex
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(if (selected) C.Selected else C.Panel)
                                    .selectable(selected, enabled = !busy, role = Role.RadioButton) {
                                        if (selected) finished = true else change { WalletVault.selectAccount(context, wallet.id, account.index) }
                                    }.padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(Modifier.size(34.dp).background(if (selected) C.Brand else C.Selected, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("${account.index + 1}", color = if (selected) C.Background else C.Brand, fontSize = 13.sp)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, fontSize = 16.sp, color = C.Text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    Text("${account.address.take(8)}…${account.address.takeLast(6)}", color = C.Muted,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                                if (selected) CaedralIcon(CaedralSymbol.Check, size = 20.dp)
                                AccountOverflow("Opções da conta ${account.name}", listOf(ManagementAction.RenameAccount, ManagementAction.RemoveAccount),
                                    removalEnabled = wallet.accounts.size > 1) {
                                    management = ManagementTarget(wallet.id, account.index, it)
                                }
                            }
                        }
                        item(key = "divider-${wallet.id}") { HorizontalDivider(Modifier.padding(top = 8.dp), color = C.Divider) }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                CaedralButton(
                    onClick = { page = AccountPage.Create; error = "" }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)
                ) { Text("Criar conta", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { page = AccountPage.Import; error = "" }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Importar outra carteira") }
            }
            AccountPage.Create -> {
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text("Na carteira ${snapshot.selectedWallet.name}", color = C.Muted, modifier = Modifier.padding(bottom = 16.dp))
                OutlinedTextField(name, onValueChange = { if (it.length <= 30) name = it }, label = { Text("Nome da conta") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("A nova conta usa a mesma frase de recuperação desta carteira.", color = C.Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp))
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                CaedralButton(onClick = { change { WalletVault.addAccount(context, snapshot.selectedWalletId, name) } },
                    enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.Brand)
                    else Text("Criar e selecionar")
                }
                }
            }
            AccountPage.Import -> {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    ImportWalletScreen(
                        onBack = { page = AccountPage.List; error = "" },
                        onAdditionalImport = { walletName, words, count -> change { WalletVault.importWallet(context, walletName, words, count) } },
                        busy = busy, externalError = error, embedded = true
                    )
                }
            }
        }
    }
}

private enum class AccountPage { List, Create, Import }

@Composable
private fun AccountOverflow(description: String, actions: List<ManagementAction>, removalEnabled: Boolean = true,
    onSelect: (ManagementAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            CaedralIcon(CaedralSymbol.More, C.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action -> DropdownMenuItem(
                text = { Column {
                    Text(action.title)
                    if (action == ManagementAction.RemoveAccount && !removalEnabled)
                        Text("Mantenha uma conta nesta carteira", style = MaterialTheme.typography.bodySmall)
                } }, enabled = action != ManagementAction.RemoveAccount || removalEnabled,
                onClick = { open = false; onSelect(action) }) }
        }
    }
}
