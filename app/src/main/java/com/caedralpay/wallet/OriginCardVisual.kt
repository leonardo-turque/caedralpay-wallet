package com.caedralpay.wallet

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun OriginCardVisual(name:String,lastFour:String,portrait:Boolean=false,onClick:(()->Unit)?=null) {
    val shape=RoundedCornerShape(22.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val rotation by animateFloatAsState(if(portrait)90f else 0f,tween(420,easing=FastOutSlowInEasing),label="card-rotation")
      val frame by animateDpAsState(if(portrait)410.dp else maxWidth/1.586f,tween(420),label="card-frame")
      val width by animateDpAsState(if(portrait)390.dp else maxWidth,tween(420),label="card-width")
      val height by animateDpAsState(if(portrait)246.dp else maxWidth/1.586f,tween(420),label="card-height")
      Box(Modifier.fillMaxWidth().height(frame),contentAlignment=Alignment.Center) {
        Box(Modifier.requiredSize(width,height).graphicsLayer{rotationZ=rotation}
            .clip(shape).background(Brush.linearGradient(listOf(Color(0xFF263541),Color(0xFF111D28),Color(0xFF071018))))
            .border(1.dp,Color.White.copy(alpha=0.18f),shape).then(if(onClick!=null)Modifier.clickable(onClick=onClick)else Modifier).padding(26.dp)) {
            Column(Modifier.align(Alignment.TopStart)) {
                Text("CAEDRAL",color=Color(0xFFEAE7E0),fontSize=18.sp,letterSpacing=5.sp,fontWeight=FontWeight.Medium)
                Text("ORIGIN",color=Color(0xFFDDD6CD),fontSize=11.sp,letterSpacing=3.sp,modifier=Modifier.padding(top=6.dp))
            }
            Image(painterResource(R.drawable.caedralpay),"Caedral Origin",Modifier.align(Alignment.CenterEnd).size(110.dp),colorFilter=ColorFilter.tint(Color(0xFFE4DED4)))
            Box(Modifier.align(Alignment.CenterStart).size(40.dp,32.dp).clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFE8E2D8),Color(0xFF9A9995)))).border(1.dp,Color.Black.copy(alpha=.2f),RoundedCornerShape(7.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    val ink=Color(0xFF676663).copy(alpha=.65f);val line=0.7.dp.toPx()
                    for(x in listOf(.31f,.69f))drawLine(ink,Offset(size.width*x,0f),Offset(size.width*x,size.height),line)
                    for(y in listOf(.32f,.68f))drawLine(ink,Offset(0f,size.height*y),Offset(size.width,size.height*y),line)
                }
            }
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(name.uppercase(),color=Color(0xFFEAE7E0),fontSize=11.sp,letterSpacing=1.5.sp,maxLines=1)
                Text("••••  ${lastFour.ifEmpty{"••••"}}",color=Color.White.copy(alpha=.6f),fontSize=12.sp,modifier=Modifier.padding(top=5.dp))
            }
        }
    }
    }
}
@Composable
internal fun NfcPulse(label:String="Aproxime para pagar") {
    val context=LocalContext.current
    val reduced=remember{runCatching{android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f}.getOrDefault(false)}
    val iconColor=MaterialTheme.colorScheme.onSurface
    val animation=rememberInfiniteTransition(label="nfc")
    val alpha by animation.animateFloat(.35f,1f,infiniteRepeatable(tween(850),RepeatMode.Reverse),label="aproximacao")
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Canvas(Modifier.size(52.dp).alpha(if(reduced)1f else alpha).semantics{contentDescription=label}) {
            val center=Offset(size.width*.28f,size.height*.5f)
            for(n in 1..3){val r=size.width*(.12f+n*.1f);drawArc(iconColor,-55f,110f,false,Offset(center.x-r,center.y-r),Size(r*2,r*2),style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))}
        }
        Text(label,style=MaterialTheme.typography.titleMedium)
    }
}
