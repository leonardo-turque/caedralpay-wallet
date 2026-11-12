package com.caedralpay.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.SecureFlagPolicy
import com.caedralpay.wallet.ui.theme.CaedralColors as C
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaedralSheet(
    title: String,
    busy: Boolean = false,
    closeRequested: Boolean = false,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val currentBusy by rememberUpdatedState(busy)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !currentBusy })
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    fun dismiss() {
        if (currentBusy || closing) return
        closing = true
        scope.launch {
            state.hide()
            currentDismiss()
        }
    }
    LaunchedEffect(closeRequested) { if (closeRequested) dismiss() }
    ModalBottomSheet(
        onDismissRequest = { dismiss() }, sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = C.Panel, contentColor = C.Text,
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(width = 36.dp, height = 4.dp)
            .background(C.Muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp))) },
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(220)).heightIn(max = 640.dp).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { dismiss() }, enabled = !busy && !closing, modifier = Modifier.semantics { contentDescription = "Fechar painel" }) {
                    CaedralIcon(CaedralSymbol.Close, C.Muted.copy(alpha = if (busy) 0.4f else 1f))
                }
            }
            content()
        }
    }
}
