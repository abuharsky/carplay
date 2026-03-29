package com.alexander.carplay.presentation.climate

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.ClimateSnapshot

const val ClimateBarHeightDp = 54
val ClimateBarHeight = ClimateBarHeightDp.dp
private val ClimateControlHeight = 42.dp
private val ClimateControlCornerRadius = 14.dp

private data class ClimateIconSpec(
    val size: Dp,
    val yOffset: Dp = 0.dp,
)

@Composable
fun rememberClimateBarState(enabled: Boolean): ClimateBarState {
    val appContext = LocalContext.current.applicationContext as CarPlayApp
    val controller = remember(appContext) { appContext.appContainer.climateController }
    val seatAutoComfortController = remember(appContext) { appContext.appContainer.seatAutoComfortController }

    if (controller == null) {
        return remember {
            ClimateBarState(
                controller = null,
                seatAutoComfortController = null,
                snapshot = ClimateSnapshot(
                    driverTemp = -1f,
                    passengerTemp = -1f,
                    fanSpeed = -1,
                    fanDirection = 0,
                    isConnected = false,
                ),
            )
        }
    }

    val snapshot by controller.snapshot.collectAsStateWithLifecycle()

    DisposableEffect(controller, enabled) {
        if (enabled) {
            controller.connect()
        }
        onDispose {
            controller.disconnect()
        }
    }

    return remember(controller, seatAutoComfortController, snapshot) {
        ClimateBarState(
            controller = controller,
            seatAutoComfortController = seatAutoComfortController,
            snapshot = snapshot,
        )
    }
}

@Composable
fun ClimateBarScreen(
    state: ClimateBarState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = Color.Black,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ClimateBarHeight)
                .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TemperatureDisplay(
                    modifier = Modifier.width(110.dp),
                    value = formatSetpointTemp(state.driverTemp),
                    connected = state.isConnected,
                    textAlign = TextAlign.Start,
                )

                Row(
                    modifier = Modifier.padding(start = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeatActionButton(
                        modifier = Modifier.width(150.dp),
                        iconRes = R.drawable.ic_climate_heat,
                        contentDescription = "Driver seat heating",
                        accent = Color(0xFFFF9667),
                        level = state.driverSeatHeat,
                        connected = state.isConnected,
                        onClick = state::onDriverSeatHeatClick,
                    )

                    SeatActionButton(
                        modifier = Modifier.width(150.dp),
                        iconRes = R.drawable.ic_climate_cool,
                        contentDescription = "Driver seat ventilation",
                        accent = Color(0xFF7CCBFF),
                        level = state.driverSeatVent,
                        connected = state.isConnected,
                        onClick = state::onDriverSeatVentClick,
                    )
                }

                Row(
                    modifier = Modifier.padding(start = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickToggleChip(
                        modifier = Modifier.width(124.dp),
                        iconRes = R.drawable.ic_climate_door_light,
                        contentDescription = "Door light",
                        active = isActiveToggle(state.doorLight),
                        connected = state.isConnected,
                        onClick = state::onDoorLightClick,
                    )

                    QuickToggleChip(
                        modifier = Modifier.width(124.dp),
                        iconRes = R.drawable.ic_climate_mirror_fold,
                        contentDescription = "Mirror auto fold",
                        active = isActiveToggle(state.mirrorAutoFold),
                        connected = state.isConnected,
                        onClick = state::onMirrorAutoFoldClick,
                    )

                    QuickToggleChip(
                        modifier = Modifier.width(124.dp),
                        iconRes = R.drawable.ic_climate_mirror_assist,
                        contentDescription = "Rear mirror assist",
                        active = isActiveToggle(state.mirrorRearAssist),
                        connected = state.isConnected,
                        onClick = state::onMirrorRearAssistClick,
                    )
                }

                ClimateIndicatorsCard(
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .width(336.dp),
                    cabinTemp = formatAmbientTemp(state.cabinTemp),
                    airIconRes = fanDirectionIconRes(state.fanDirection),
                    fanSpeed = state.fanSpeed,
                    connected = state.isConnected,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeatActionButton(
                        modifier = Modifier.width(150.dp),
                        iconRes = R.drawable.ic_climate_heat,
                        contentDescription = "Passenger seat heating",
                        accent = Color(0xFFFF9667),
                        level = state.passengerSeatHeat,
                        connected = state.isConnected,
                        onClick = state::onPassengerSeatHeatClick,
                    )

                    SeatActionButton(
                        modifier = Modifier.width(150.dp),
                        iconRes = R.drawable.ic_climate_cool,
                        contentDescription = "Passenger seat ventilation",
                        accent = Color(0xFF7CCBFF),
                        level = state.passengerSeatVent,
                        connected = state.isConnected,
                        onClick = state::onPassengerSeatVentClick,
                    )
                }

                TemperatureDisplay(
                    modifier = Modifier
                        .width(110.dp)
                        .padding(start = 6.dp),
                    value = formatSetpointTemp(state.passengerTemp),
                    connected = state.isConnected,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun TemperatureDisplay(
    value: String,
    connected: Boolean,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(
                start = if (textAlign == TextAlign.Start) 14.dp else 2.dp,
                end = if (textAlign == TextAlign.End) 14.dp else 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = if (connected) value else "--",
            color = if (connected) Color.White else Color.White.copy(alpha = 0.34f),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                letterSpacing = (-0.5).sp,
                fontFeatureSettings = "tnum",
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            textAlign = textAlign,
            maxLines = 1,
        )
    }
}

@Composable
private fun ClimateIndicatorsCard(
    cabinTemp: String,
    airIconRes: Int,
    fanSpeed: Int,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier,
        minHeight = ClimateControlHeight,
        horizontalPadding = 20.dp,
        verticalPadding = 0.dp,
        backgroundColor = Color.White.copy(alpha = 0.045f),
        borderColor = Color.White.copy(alpha = 0.065f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                SmallTemperatureIndicator(
                    label = "",
                    value = cabinTemp,
                    connected = connected,
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 1.dp, height = 28.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val airflowIconSpec = airflowIconSpec(airIconRes)
                Icon(
                    painter = painterResource(id = airIconRes),
                    contentDescription = "Air flow direction",
                    modifier = Modifier
                        .size(airflowIconSpec.size)
                        .offset(y = airflowIconSpec.yOffset),
                    tint = if (connected) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.28f),
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 1.dp, height = 28.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val fanIconSpec = fanIconSpec()
                    Icon(
                        painter = painterResource(id = R.drawable.ic_climate_fan),
                        contentDescription = "Fan speed",
                        modifier = Modifier
                            .size(fanIconSpec.size)
                            .offset(y = fanIconSpec.yOffset),
                        tint = if (connected) Color.White.copy(alpha = 0.84f) else Color.White.copy(alpha = 0.28f),
                    )
                    Text(
                        modifier = Modifier.width(
                            if (connected && fanSpeed == 0) 54.dp else 48.dp,
                        ),
                        text = formatFanSpeed(fanSpeed = fanSpeed, connected = connected),
                        color = if (connected) Color.White else Color.White.copy(alpha = 0.34f),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (connected && fanSpeed == 0) 22.sp else 30.sp,
                            fontFeatureSettings = "tnum",
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallTemperatureIndicator(
    label: String,
    value: String,
    connected: Boolean,
) {
    Row(
        modifier = Modifier.width(82.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = if (connected) Color.White.copy(alpha = 0.46f) else Color.White.copy(alpha = 0.22f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
        Text(
            text = if (connected) value else "--",
            color = if (connected) Color.White else Color.White.copy(alpha = 0.32f),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                fontFeatureSettings = "tnum",
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeatActionButton(
    iconRes: Int,
    contentDescription: String,
    accent: Color,
    level: Int,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = connected && level.coerceIn(0, 3) > 0
    val backgroundColor = when {
        !connected -> Color.White.copy(alpha = 0.025f)
        isActive -> accent.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.045f)
    }
    val borderColor = when {
        !connected -> Color.White.copy(alpha = 0.05f)
        isActive -> accent.copy(alpha = 0.28f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val iconTint = when {
        !connected -> Color.White.copy(alpha = 0.22f)
        isActive -> accent
        else -> Color.White.copy(alpha = 0.70f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ClimateControlCornerRadius))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(ClimateControlCornerRadius),
            )
            .clickable(enabled = connected, onClick = onClick)
            .height(ClimateControlHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val iconSpec = seatActionIconSpec(iconRes)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(iconSpec.size)
                    .offset(y = iconSpec.yOffset),
                tint = iconTint,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    connected && index < level.coerceIn(0, 3) -> accent
                                    connected -> Color.White.copy(alpha = 0.24f)
                                    else -> Color.White.copy(alpha = 0.10f)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickToggleChip(
    iconRes: Int,
    contentDescription: String,
    active: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSpec = quickToggleIconSpec(iconRes)
    val backgroundColor = when {
        !connected -> Color.White.copy(alpha = 0.025f)
        active -> Color(0xFF8AD18F).copy(alpha = 0.17f)
        else -> Color.White.copy(alpha = 0.045f)
    }
    val borderColor = when {
        !connected -> Color.White.copy(alpha = 0.05f)
        active -> Color(0xFF8AD18F).copy(alpha = 0.24f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val iconTint = when {
        !connected -> Color.White.copy(alpha = 0.22f)
        active -> Color.White
        else -> Color.White.copy(alpha = 0.70f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ClimateControlCornerRadius))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(ClimateControlCornerRadius),
            )
            .clickable(enabled = connected, onClick = onClick)
            .height(ClimateControlHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(iconSpec.size)
                    .offset(y = iconSpec.yOffset),
                tint = iconTint,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !connected -> Color.White.copy(alpha = 0.16f)
                            active -> Color(0xFF8AD18F)
                            else -> Color.White.copy(alpha = 0.30f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 36.dp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 10.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 5.dp,
    backgroundColor: Color = Color.White.copy(alpha = 0.06f),
    borderColor: Color = Color.White.copy(alpha = 0.08f),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .defaultMinSize(minHeight = minHeight)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun formatSetpointTemp(value: Float): String = when {
    value < 0f -> "--"
    value >= 33f -> "HI"
    value <= 16f -> "LO"
    value == value.toInt().toFloat() -> "${value.toInt()}°"
    else -> "${"%.1f".format(value)}°"
}

private fun formatAmbientTemp(value: Float?): String = when {
    value == null -> "--"
    value == value.toInt().toFloat() -> "${value.toInt()}°"
    else -> "${"%.1f".format(value)}°"
}

private fun seatActionIconSpec(iconRes: Int): ClimateIconSpec = when (iconRes) {
    R.drawable.ic_climate_heat -> ClimateIconSpec(size = 37.dp, yOffset = 0.5.dp)
    R.drawable.ic_climate_cool -> ClimateIconSpec(size = 37.dp, yOffset = 0.5.dp)
    else -> ClimateIconSpec(size = 37.dp, yOffset = 0.5.dp)
}

private fun quickToggleIconSpec(iconRes: Int): ClimateIconSpec = when (iconRes) {
    R.drawable.ic_climate_mirror_fold,
    R.drawable.ic_climate_mirror_assist,
    -> ClimateIconSpec(size = 42.dp, yOffset = 0.5.dp)

    else -> ClimateIconSpec(size = 39.dp, yOffset = 0.5.dp)
}

private fun airflowIconSpec(iconRes: Int): ClimateIconSpec = when (iconRes) {
    R.drawable.ic_climate_air_defrost,
    R.drawable.ic_climate_air_foot_defrost,
    -> ClimateIconSpec(size = 42.dp, yOffset = 0.5.dp)

    else -> ClimateIconSpec(size = 42.dp, yOffset = 0.5.dp)
}

private fun fanIconSpec(): ClimateIconSpec = ClimateIconSpec(
    size = 35.dp,
    yOffset = 0.5.dp,
)

private fun formatFanSpeed(
    fanSpeed: Int,
    connected: Boolean,
): String = when {
    !connected || fanSpeed < 0 -> "--"
    fanSpeed == 0 -> "OFF"
    else -> fanSpeed.toString()
}

private fun fanDirectionIconRes(value: Int): Int = when (value) {
    1 -> R.drawable.ic_climate_air_face
    2 -> R.drawable.ic_climate_air_foot
    3 -> R.drawable.ic_climate_air_face_foot
    4 -> R.drawable.ic_climate_air_defrost
    5 -> R.drawable.ic_climate_air_foot_defrost
    else -> R.drawable.ic_climate_air
}

private fun isActiveToggle(value: Int): Boolean = value == 2
