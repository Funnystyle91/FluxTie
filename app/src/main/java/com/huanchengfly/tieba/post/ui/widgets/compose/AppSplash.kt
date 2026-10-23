package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.R
import androidx.core.graphics.drawable.toBitmap
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 启动屏就绪信号：首页数据加载完成后置位，启动屏随之淡出。
 */
object SplashState {
    val ready = MutableStateFlow(false)

    /** 系统 SplashScreen 是否已开始退场交接 */
    val systemSplashDismissed = MutableStateFlow(false)

    /** 启动屏是否已结束（退场触发）——供启动静默检查等待，避免弹窗被遮挡。 */
    val finished = MutableStateFlow(false)

    fun markReady() {
        ready.value = true
    }

    fun markSystemSplashDismissed() {
        systemSplashDismissed.value = true
    }

    fun reset() {
        ready.value = false
        systemSplashDismissed.value = false
        finished.value = false
    }
}

/** 启动屏字体:Roboto(Google Material 官方字体) */
private val SplashFontFamily = FontFamily(
    Font(R.font.roboto_regular),
    Font(R.font.roboto_medium, FontWeight.Medium),
)

/**
 * 应用内启动屏（方案 C 一镜到底流体编排）：
 * 1. 初始阶段（0~420ms）：Logo 严格处于屏幕物理中心（160dp 满态），零偏差承接系统 SplashScreen AVD 动效终态。
 * 2. 一镜到底升维（420~1040ms）：Logo 平滑上浮（0dp -> -142dp）并等比收敛（160dp -> 83dp），成为通透舒展的头部徽标。
 * 3. 内容随动与留白（640~1080ms）：在 Logo 充分腾出空间后，FluxTie 品牌名、专属液态流光徽章“为兴趣而生”与水球 Loading 顺势滑入，留出足足 78dp 黄金留白，绝不重叠。
 * 4. 退场交接：首页数据就绪后，整体品牌面板轻柔向上浮升淡出，底栏与 Feed 流迎面浮现。
 */
@Composable
fun AppSplashOverlay() {
    var visible by remember { mutableStateOf(true) }
    var isHeaderStage by remember { mutableStateOf(false) }

    // 1. 一镜到底核心动效：Logo 充分向上浮升（-142dp）并等比缩放至 83dp 精致头部徽标
    val logoTranslateY by animateDpAsState(
        targetValue = if (isHeaderStage) (-142).dp else 0.dp,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "logoTranslateY"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (isHeaderStage) 0.52f else 1.0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "logoScale"
    )

    // 2. 标语、应用名与 Loading 随动滑入与淡入 (留足 220ms 延迟，待 Logo 腾出空间后再从容滑入，彻底消除视觉穿帮)
    val contentRevealAlpha by animateFloatAsState(
        targetValue = if (isHeaderStage) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 440, delayMillis = 220, easing = FastOutSlowInEasing),
        label = "contentRevealAlpha"
    )
    val contentRevealSlide by animateDpAsState(
        targetValue = if (isHeaderStage) 0.dp else 28.dp,
        animationSpec = tween(durationMillis = 480, delayMillis = 220, easing = FastOutSlowInEasing),
        label = "contentRevealSlide"
    )

    // 3. 整体退场过渡（开门交接）
    val transition = updateTransition(targetState = visible, label = "splashExit")
    val bgAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 300, easing = LinearEasing) },
        label = "splashBgAlpha"
    ) { if (it) 1f else 0f }
    val exitAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 220, easing = EaseInCubic) },
        label = "splashExitAlpha"
    ) { if (it) 1f else 0f }
    val exitScale by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 260, easing = FastOutSlowInEasing) },
        label = "splashExitScale"
    ) { if (it) 1f else 0.96f }
    val exitTranslateY by transition.animateDp(
        transitionSpec = { tween(durationMillis = 260, easing = FastOutSlowInEasing) },
        label = "splashExitTranslateY"
    ) { if (it) 0.dp else (-18).dp }

    LaunchedEffect(Unit) {
        SplashState.reset()
        // 1. 等待系统 SplashScreen 开始揭开交接（设置 3000ms 兜底避免极端无信号卡死）
        val waitStart = System.currentTimeMillis()
        while (!SplashState.systemSplashDismissed.value && (System.currentTimeMillis() - waitStart) < 3000L) {
            delay(40)
        }
        // 系统揭开透明遮罩后，在中心短暂稳定停留 100ms 确保视觉零顿挫，随后启动一镜到底升腾
        delay(100)
        isHeaderStage = true
        val animStart = System.currentTimeMillis()

        // 循环播放直到：加载完成且达到最短展示时长（1400ms 保证一镜到底完整呈现）；3.5s 超时兜底
        val minDuration = 1400L
        val timeout = 3500L
        while (true) {
            val elapsed = System.currentTimeMillis() - animStart
            if ((SplashState.ready.value && elapsed >= minDuration) || elapsed >= timeout) break
            delay(100)
        }
        visible = false
        SplashState.finished.value = true
    }

    if (bgAlpha > 0.01f) {
        val splashBg = colorResource(id = R.color.colorSplashBg)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = bgAlpha }
                .background(splashBg)
        ) {
            // 核心应用图标：初始 160dp 绝对居中，随着一镜到底上移至头部（-142dp）并等比缩放至 83dp
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationY = (logoTranslateY + exitTranslateY).toPx()
                        scaleX = logoScale * exitScale
                        scaleY = logoScale * exitScale
                        alpha = exitAlpha
                    }
            ) {
                SplashBrandIcon(size = 160.dp)
            }

            // 品牌文案与 Loading：在 Logo 上浮腾出充足空间后顺势跟随展开，与 Logo 底部保持足足 78dp 通透呼吸间距
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 52.dp)
                    .graphicsLayer {
                        translationY = (contentRevealSlide + exitTranslateY).toPx()
                        alpha = contentRevealAlpha * exitAlpha
                        scaleX = exitScale
                        scaleY = exitScale
                    }
            ) {
                // 1. 品牌英文大标
                Text(
                    text = "FluxTie",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    fontFamily = SplashFontFamily,
                    color = ExtendedTheme.colors.text
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. 核心专属特殊效果标语：“为兴趣而生”液态流光徽章
                LiquidSloganBadge(
                    text = stringResource(id = R.string.splash_tagline)
                )

                Spacer(modifier = Modifier.height(30.dp))

                // 3. 液态水球 Loading
                BlobLoading()
            }

            // 底部版本号
            Text(
                text = stringResource(id = R.string.summary_about_version, BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = SplashFontFamily,
                color = ExtendedTheme.colors.textSecondary.copy(alpha = exitAlpha),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            )
        }
    }
}

/**
 * “为兴趣而生”专属液态流光徽章组件：
 * 1. 冰川天蓝、贴吧深蓝与加精琥珀星石三色液态流光渐变循环流动；
 * 2. 仿 iOS 27 液态玻璃微胶囊容器（极细微光流光边框 + 通透浅色冷底）；
 * 3. 伴随呼吸律动脉冲与两端星石微闪，呈现流动的社区生命力。
 */
@Composable
private fun LiquidSloganBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sloganFlow")

    // 渐变流光在 0f..1f 之间平滑循环位移
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    // 文字流光：在方案 C 色系间循环插值（避免着色器文字在软件渲染器上崩溃）
    val colorPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sloganColorPhase"
    )

    // 星石与高光的呼吸律动 (0.85 ~ 1.15)
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // 方案 C 核心流体色系
    val skyBlue = Color(0xFF42A5F5)    // 冰川天蓝
    val deepBlue = Color(0xFF1565C0)   // 贴吧经典深蓝
    val amberGold = Color(0xFFFFB300)  // 加精琥珀星石

    val animatedTextColor = run {
        val palette = listOf(
            deepBlue,
            Color(0xFF1E88E5),
            skyBlue,
            Color(0xFF1E88E5),
            deepBlue
        )
        val t = colorPhase * (palette.size - 1)
        val index = t.toInt().coerceIn(0, palette.size - 2)
        lerpColor(palette[index], palette[index + 1], t - index)
    }

    // 流光 Brush：随 shimmerOffset 动态滑动的水波折射光泽
    // 胶囊外壳流光边框
    val borderBrush = Brush.horizontalGradient(
        colors = listOf(
            skyBlue.copy(alpha = 0.40f),
            amberGold.copy(alpha = 0.55f),
            deepBlue.copy(alpha = 0.35f)
        ),
        startX = shimmerOffset * 180f,
        endX = (shimmerOffset + 1.2f) * 180f
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(skyBlue.copy(alpha = 0.08f))
            .border(
                border = BorderStroke(1.dp, borderBrush),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        // 左侧加精琥珀微星
        Canvas(
            modifier = Modifier
                .size(11.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
        ) {
            drawSparkleStar(color = amberGold)
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 核心文字：为兴趣而生
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.5.sp,
            fontFamily = SplashFontFamily,
            color = animatedTextColor
        )

        Spacer(modifier = Modifier.width(6.dp))

        // 右侧冰川微珠
        Canvas(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    scaleX = 2f - pulseScale
                    scaleY = 2f - pulseScale
                }
        ) {
            drawCircle(color = skyBlue)
            drawCircle(color = Color.White, radius = size.minDimension / 4f)
        }
    }
}

/**
 * 绘制 4 芒微星石（呼应 Logo 右上角的论坛加精琥珀星石）
 */
private fun DrawScope.drawSparkleStar(color: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val rOuter = minOf(w, h) / 2f
    val rInner = rOuter * 0.28f

    val path = Path().apply {
        moveTo(cx, cy - rOuter)
        quadraticTo(cx, cy, cx + rInner, cy)
        lineTo(cx + rOuter, cy)
        quadraticTo(cx, cy, cx, cy + rInner)
        lineTo(cx, cy + rOuter)
        quadraticTo(cx, cy, cx - rInner, cy)
        lineTo(cx - rOuter, cy)
        quadraticTo(cx, cy, cx, cy - rOuter)
        close()
    }
    drawPath(path, color)
    drawCircle(Color.White, radius = rInner * 0.75f, center = Offset(cx, cy))
}

/**
 * 启动屏品牌图标：严格保持与系统 SplashScreen 规范（160dp 核心圆底）像素级一致。
 * 强制圆形裁剪（CircleShape）+ ic_launcher_new_background 品牌底色 + ic_launcher_new_foreground 矢量前景。
 * 彻底消除在 HyperOS / OneUI 等自适应桌面图标下，从系统启动屏圆形跳变为桌面圆角矩形（Squircle）的「圆角跳闪」。
 */
@Composable
private fun SplashBrandIcon(
    modifier: Modifier = Modifier,
    size: Dp = 160.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colorResource(id = R.color.ic_launcher_new_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_new_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}


/**
 * 液态 blob Loading：8 点谐波半径驱动闭合曲线持续流动形变（液滴/水球效果），
 * 叠加光泽渐变。零额外依赖，Canvas 手绘。
 */
@Composable
private fun BlobLoading() {
    val transition = rememberInfiniteTransition(label = "blob")
    // 相位 0..2π 线性推进,双频谐波叠加产生非重复的流动形变
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "blobPhase"
    )
    val sheen by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blobSheen"
    )
    val base = ExtendedTheme.colors.primary
    Canvas(modifier = Modifier.size(36.dp)) {
        drawLiquidBlob(
            phase = phase,
            brush = Brush.linearGradient(
                colors = listOf(base, lerpColor(base, Color.White, 0.22f * sheen))
            )
        )
    }
}

private fun DrawScope.drawLiquidBlob(phase: Float, brush: Brush) {
    val points = 8
    val radius = size.minDimension / 2f * 0.84f
    val vertices = (0 until points).map { i ->
        val angle = 2.0 * PI * i / points
        val wobble = 0.10f * sin(phase * 1.0f + i * 0.9f) +
                0.06f * sin(phase * 1.7f + i * 1.9f)
        val r = radius * (1f + wobble)
        Offset(
            center.x + (cos(angle) * r).toFloat(),
            center.y + (sin(angle) * r).toFloat()
        )
    }
    // 平滑闭合曲线:相邻点中点作为锚,顶点作为控制点
    val path = Path()
    val mid = { a: Offset, b: Offset -> Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f) }
    val firstMid = mid(vertices[0], vertices[1])
    path.moveTo(firstMid.x, firstMid.y)
    for (i in 1..points) {
        val control = vertices[i % points]
        val anchor = mid(control, vertices[(i + 1) % points])
        path.quadraticTo(control.x, control.y, anchor.x, anchor.y)
    }
    path.close()
    drawPath(path, brush)
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color =
    Color(
        red = start.red + (stop.red - start.red) * fraction,
        green = start.green + (stop.green - start.green) * fraction,
        blue = start.blue + (stop.blue - start.blue) * fraction,
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction
    )
