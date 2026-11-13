package com.caedralpay.wallet

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caedralpay.wallet.ui.theme.CaedralColors as C

/** Native vector illustration: customer card moving toward the merchant's phone. */
@Composable
internal fun MerchantTapAnimation(delivered:Boolean) {
    val context=LocalContext.current
    val reduced=remember{runCatching{android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}.getOrDefault(false)}
    val transition=rememberInfiniteTransition(label="merchant-tap")
    val progress by transition.animateFloat(0f,1f,infiniteRepeatable(tween(1800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="card-approach")
    val p=if(reduced || delivered)1f else progress
    val brand=C.Brand;val outline=C.Muted;val panel=C.Selected
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp).semantics{contentDescription=if(delivered)"Cartão lido. Aguardando pagamento" else "Cartão aproximando do leitor NFC do celular"}) {
            Canvas(Modifier.fillMaxSize()) {
                val phoneWidth=size.width*.34f;val phoneHeight=size.height*.84f
                val x=size.width*.55f;val y=size.height*.06f
                drawRoundRect(panel,Offset(x,y),Size(phoneWidth,phoneHeight),cornerRadius=androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                drawRoundRect(outline.copy(alpha=.5f),Offset(x,y),Size(phoneWidth,phoneHeight),cornerRadius=androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),style=Stroke(1.5.dp.toPx()))
                drawLine(outline,Offset(x+phoneWidth*.35f,y+12.dp.toPx()),Offset(x+phoneWidth*.65f,y+12.dp.toPx()),3.dp.toPx(),StrokeCap.Round)
                drawLine(outline,Offset(x+phoneWidth*.35f,y+phoneHeight-10.dp.toPx()),Offset(x+phoneWidth*.65f,y+phoneHeight-10.dp.toPx()),3.dp.toPx(),StrokeCap.Round)
                val center=Offset(x+phoneWidth*.35f,y+phoneHeight*.35f)
                for(n in 1..3){val radius=(7+n*6).dp.toPx();drawArc(brand.copy(alpha=if(reduced)1f else .4f+.6f*p),-55f,110f,false,Offset(center.x-radius,center.y-radius),Size(radius*2,radius*2),style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))}
            }
            Box(Modifier.offset(x=maxWidth*(.08f+.32f*p),y=(95-40*p).dp)
                .width(maxWidth*.40f).aspectRatio(1.586f).graphicsLayer{rotationZ=-22f+14f*p;shadowElevation=8.dp.toPx();shape=RoundedCornerShape(12.dp)}
                .clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF2D4050),Color(0xFF09131D)))).padding(14.dp)) {
                Column {Text("CAEDRAL",color=Color(0xFFE4DED4),fontSize=10.sp,letterSpacing=2.sp);Text("ORIGIN",color=Color(0xFFDDD6CD),fontSize=7.sp,letterSpacing=1.sp)}
                Image(painterResource(R.drawable.caedralpay),null,Modifier.size(44.dp).align(Alignment.CenterEnd),colorFilter=ColorFilter.tint(Color(0xFFE4DED4)))
                Box(Modifier.size(20.dp,15.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(3.dp)).background(Color(0xFFDDD6CD)))
            }
        }
        Text(if(delivered)"Aguardando pagamento"else"Pagamento por aproximação",style=MaterialTheme.typography.titleMedium)
    }
}
