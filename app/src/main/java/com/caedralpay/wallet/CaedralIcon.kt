package com.caedralpay.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caedralpay.wallet.ui.theme.CaedralColors

internal enum class CaedralSymbol { Buy, Swap, Send, Receive, Chevron, Close, Check, Copy, Network, Qr, More, Appearance, History }

/** Ícones vetoriais próprios CaedralPay, em grade de 24 unidades. */
@Composable
internal fun CaedralIcon(symbol: CaedralSymbol, color: Color = CaedralColors.Brand, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val path = Path().apply {
            when (symbol) {
                CaedralSymbol.History -> {
                    addOval(androidx.compose.ui.geometry.Rect(3f, 3f, 21f, 21f))
                    moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
                }
                CaedralSymbol.More -> {
                    addOval(androidx.compose.ui.geometry.Rect(10.5f, 3f, 13.5f, 6f))
                    addOval(androidx.compose.ui.geometry.Rect(10.5f, 10.5f, 13.5f, 13.5f))
                    addOval(androidx.compose.ui.geometry.Rect(10.5f, 18f, 13.5f, 21f))
                }
                CaedralSymbol.Appearance -> {
                    addOval(androidx.compose.ui.geometry.Rect(3f, 3f, 21f, 21f))
                    moveTo(12f, 3f); lineTo(12f, 21f)
                    moveTo(15f, 5f); lineTo(15f, 19f)
                    moveTo(18f, 8f); lineTo(18f, 16f)
                }
                CaedralSymbol.Qr -> {
                    addRect(androidx.compose.ui.geometry.Rect(3f, 3f, 9f, 9f))
                    addRect(androidx.compose.ui.geometry.Rect(15f, 3f, 21f, 9f))
                    addRect(androidx.compose.ui.geometry.Rect(3f, 15f, 9f, 21f))
                    moveTo(15f, 15f); lineTo(18f, 15f); lineTo(18f, 18f); lineTo(21f, 18f)
                    moveTo(15f, 18f); lineTo(15f, 21f); lineTo(18f, 21f)
                    moveTo(21f, 12f); lineTo(21f, 14f)
                }
                CaedralSymbol.Network -> {
                    addOval(androidx.compose.ui.geometry.Rect(3f, 3f, 21f, 21f))
                    addOval(androidx.compose.ui.geometry.Rect(8f, 3f, 16f, 21f))
                    moveTo(3f, 12f); lineTo(21f, 12f)
                }
                CaedralSymbol.Send -> { moveTo(4f, 20f); lineTo(20f, 4f); moveTo(5f, 4f); lineTo(20f, 4f); lineTo(20f, 19f) }
                CaedralSymbol.Receive -> { moveTo(20f, 4f); lineTo(4f, 20f); moveTo(4f, 5f); lineTo(4f, 20f); lineTo(19f, 20f) }
                CaedralSymbol.Swap -> { moveTo(7f, 3f); lineTo(7f, 21f); moveTo(3f, 17f); lineTo(7f, 21f); lineTo(11f, 17f); moveTo(17f, 21f); lineTo(17f, 3f); moveTo(13f, 7f); lineTo(17f, 3f); lineTo(21f, 7f) }
                CaedralSymbol.Buy -> { moveTo(17f, 6f); cubicTo(5f, 0f, 3f, 12f, 12f, 12f); cubicTo(22f, 12f, 19f, 24f, 6f, 18f); moveTo(12f, 1f); lineTo(12f, 23f) }
                CaedralSymbol.Chevron -> { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
                CaedralSymbol.Close -> { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
                CaedralSymbol.Check -> { moveTo(4f, 12f); lineTo(9f, 17f); lineTo(20f, 6f) }
                CaedralSymbol.Copy -> { moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 21f); lineTo(8f, 21f); close(); moveTo(4f, 16f); lineTo(4f, 3f); lineTo(16f, 3f) }
            }
        }
        val scaleX = this.size.width / 24f
        val scaleY = this.size.height / 24f
        withTransform({ scale(scaleX, scaleY, Offset.Zero) }) {
            drawPath(path, color, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
