package com.caedralpay.wallet

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.caedralpay.wallet.ui.theme.CaedralColors as C

@Composable
internal fun CaedralButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = C.Button, contentColor = C.OnButton,
        disabledContainerColor = C.Selected, disabledContentColor = C.Disabled),
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, colors = colors,
        shape = MaterialTheme.shapes.medium, content = content)
}
