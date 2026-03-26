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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexander.carplay.CarPlayApp
import com.alexander.carplay.R
import com.alexander.carplay.domain.model.ClimateSnapshot

const val ClimateBarHeightDp = 54
val ClimateBarHeight = ClimateBarHeightDp.dp
private val ClimateControlHeight = 46.dp

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
                    modifier = Modifier.width(96.dp),
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
                        .width(294.dp),
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
                        .width(96.dp)
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
        horizontalPadding = 16.dp,
        verticalPadding = 0.dp,
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
                    .background(Color.White.copy(alpha = 0.12f)),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = airIconRes),
                    contentDescription = "Air flow direction",
                    modifier = Modifier.size(46.dp),
                    tint = if (connected) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.28f),
                )
            }

            Box(
                modifier = Modifier
                    .size(width = 1.dp, height = 28.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_climate_fan),
                        contentDescription = "Fan speed",
                        modifier = Modifier.size(42.dp),
                        tint = if (connected) Color.White.copy(alpha = 0.84f) else Color.White.copy(alpha = 0.28f),
                    )
                    Text(
                        modifier = Modifier.width(60.dp),
                        text = formatFanSpeed(fanSpeed = fanSpeed, connected = connected),
                        color = if (connected) Color.White else Color.White.copy(alpha = 0.34f),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (connected && fanSpeed == 0) 22.sp else 30.sp,
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) {
                    accent.copy(alpha = 0.18f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    !connected -> Color.White.copy(alpha = 0.05f)
                    isActive -> accent.copy(alpha = 0.28f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = connected, onClick = onClick)
            .height(ClimateControlHeight)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(40.dp),
                tint = when {
                    !connected -> Color.White.copy(alpha = 0.28f)
                    isActive -> accent
                    else -> Color.White.copy(alpha = 0.78f)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (connected && index < level.coerceIn(0, 3)) {
                                    accent
                                } else {
                                    Color.White.copy(alpha = 0.18f)
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
    val iconSize = when (iconRes) {
        R.drawable.ic_climate_mirror_fold,
        R.drawable.ic_climate_mirror_assist,
        -> 46.dp

        else -> 42.dp
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active && connected) {
                    Color(0xFF8AD18F).copy(alpha = 0.17f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    !connected -> Color.White.copy(alpha = 0.05f)
                    active -> Color(0xFF8AD18F).copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                shape = RoundedCornerShape(12.dp),
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
                modifier = Modifier.size(iconSize),
                tint = when {
                    !connected -> Color.White.copy(alpha = 0.28f)
                    active -> Color.White
                    else -> Color.White.copy(alpha = 0.78f)
                },
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
                            else -> Color.White.copy(alpha = 0.22f)
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
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
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
