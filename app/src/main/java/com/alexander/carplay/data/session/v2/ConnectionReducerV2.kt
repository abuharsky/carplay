package com.alexander.carplay.data.session.v2

data class ReductionResultV2(
    val snapshot: ConnectionSnapshotV2,
    val effects: List<ConnectionEffectV2> = emptyList(),
)

object ConnectionReducerV2 {
    fun reduce(
        previous: ConnectionSnapshotV2,
        event: ConnectionEventV2,
    ): ReductionResultV2 {
        return when (event) {
            ConnectionEventV2.TransportDetached -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        transport = previous.transport.copy(
                            state = TransportStateV2.DETACHED,
                            connectionLabel = null,
                            openAcknowledged = false,
                            width = null,
                            height = null,
                            fps = null,
                            lastFailure = null,
                        ),
                        discovery = DiscoveryLaneV2(),
                        projection = ProjectionLaneV2(),
                        policy = previous.policy.copy(
                            knownDeviceCount = 0,
                            autoConnectEligible = false,
                            selectionMode = SelectionModeV2.NONE,
                        ),
                    ),
                    effects = listOf(
                        ConnectionEffectV2.StopFrameRequests,
                        ConnectionEffectV2.StopBleAdvertising,
                        ConnectionEffectV2.ClearPendingAutoConnect,
                    ),
                )
            }

            is ConnectionEventV2.TransportOpening -> {
                if (event.epoch < previous.transport.epoch) {
                    ReductionResultV2(previous)
                } else {
                    ReductionResultV2(
                        snapshot = freshTransportSnapshot(
                            previous = previous,
                            epoch = event.epoch,
                            state = TransportStateV2.OPENING,
                            connectionLabel = event.connectionLabel,
                        ),
                        effects = listOf(
                            ConnectionEffectV2.StopFrameRequests,
                            ConnectionEffectV2.StopBleAdvertising,
                            ConnectionEffectV2.ClearPendingAutoConnect,
                        ),
                    )
                }
            }

            is ConnectionEventV2.TransportOpened -> {
                if (event.epoch < previous.transport.epoch) {
                    ReductionResultV2(previous)
                } else {
                    val baseSnapshot = if (event.epoch != previous.transport.epoch) {
                        freshTransportSnapshot(
                            previous = previous,
                            epoch = event.epoch,
                            state = TransportStateV2.OPEN,
                            connectionLabel = event.connectionLabel,
                        )
                    } else {
                        previous.copy(
                            transport = previous.transport.copy(
                                state = TransportStateV2.OPEN,
                                connectionLabel = event.connectionLabel ?: previous.transport.connectionLabel,
                                lastFailure = null,
                            ),
                        )
                    }
                    val isFreshOpen = previous.transport.state != TransportStateV2.OPEN || previous.transport.epoch != event.epoch
                    ReductionResultV2(
                        snapshot = baseSnapshot,
                        effects = if (isFreshOpen) {
                            listOf(
                                ConnectionEffectV2.StartHeartbeat,
                                ConnectionEffectV2.QueueInitSequence(event.includeBrandingAssets),
                            )
                        } else {
                            emptyList()
                        },
                    )
                }
            }

            is ConnectionEventV2.TransportClosed -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        transport = previous.transport.copy(
                            state = TransportStateV2.CLOSED,
                            connectionLabel = null,
                            openAcknowledged = false,
                            width = null,
                            height = null,
                            fps = null,
                            lastFailure = event.reason,
                        ),
                        discovery = DiscoveryLaneV2(),
                        projection = ProjectionLaneV2(),
                    ),
                    effects = listOf(
                        ConnectionEffectV2.StopFrameRequests,
                        ConnectionEffectV2.StopBleAdvertising,
                        ConnectionEffectV2.ClearPendingAutoConnect,
                    ),
                )
            }

            is ConnectionEventV2.TransportFailed -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        transport = previous.transport.copy(
                            state = TransportStateV2.FAILED,
                            connectionLabel = null,
                            openAcknowledged = false,
                            width = null,
                            height = null,
                            fps = null,
                            lastFailure = event.reason,
                        ),
                        discovery = DiscoveryLaneV2(),
                        projection = ProjectionLaneV2(),
                    ),
                    effects = listOf(
                        ConnectionEffectV2.StopFrameRequests,
                        ConnectionEffectV2.StopBleAdvertising,
                        ConnectionEffectV2.ClearPendingAutoConnect,
                    ),
                )
            }

            is ConnectionEventV2.VehicleAvailabilityChanged -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        policy = previous.policy.copy(vehicleReady = event.ready),
                    ),
                )
            }

            is ConnectionEventV2.PolicyUpdated -> {
                val selectionMode = resolveSelectionMode(
                    knownDeviceCount = event.knownDeviceCount,
                    autoConnectEligible = event.autoConnectEligible,
                    manualSelectionRequired = event.manualSelectionRequired,
                )
                val snapshot = previous.copy(
                    policy = previous.policy.copy(
                        knownDeviceCount = event.knownDeviceCount,
                        autoConnectEligible = event.autoConnectEligible,
                        selectionMode = selectionMode,
                    ),
                    discovery = alignDiscoveryToPolicy(
                        discovery = previous.discovery,
                        selectionMode = selectionMode,
                        openAcknowledged = previous.transport.openAcknowledged,
                        sessionEstablished = previous.isSessionEstablished(),
                    ),
                )
                ReductionResultV2(
                    snapshot = snapshot,
                    effects = armingDiscoveryEffects(
                        selectionMode = selectionMode,
                        knownDeviceCount = event.knownDeviceCount,
                        openAcknowledged = previous.transport.openAcknowledged,
                        sessionEstablished = previous.isSessionEstablished(),
                    ),
                )
            }

            is ConnectionEventV2.ManualSelectionRequested -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        policy = previous.policy.copy(selectionMode = SelectionModeV2.MANUAL),
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.MANUAL_SELECTION,
                            activeDeviceId = event.deviceId,
                            lastReason = "manual selection requested",
                        ),
                    ),
                    effects = listOf(
                        ConnectionEffectV2.StartBleAdvertising,
                        ConnectionEffectV2.ClearPendingAutoConnect,
                    ),
                )
            }

            is ConnectionEventV2.OpenAcknowledged -> {
                val updatedTransport = previous.transport.copy(
                    openAcknowledged = true,
                    width = event.width ?: previous.transport.width,
                    height = event.height ?: previous.transport.height,
                    fps = event.fps ?: previous.transport.fps,
                )
                val updatedDiscovery = alignDiscoveryToPolicy(
                    discovery = previous.discovery,
                    selectionMode = previous.policy.selectionMode,
                    openAcknowledged = true,
                    sessionEstablished = previous.isSessionEstablished(),
                )
                ReductionResultV2(
                    snapshot = previous.copy(
                        transport = updatedTransport,
                        discovery = updatedDiscovery,
                    ),
                    effects = if (previous.transport.openAcknowledged) {
                        emptyList()
                    } else {
                        armingDiscoveryEffects(
                            selectionMode = previous.policy.selectionMode,
                            knownDeviceCount = previous.policy.knownDeviceCount,
                            openAcknowledged = true,
                            sessionEstablished = previous.isSessionEstablished(),
                        )
                    },
                )
            }

            is ConnectionEventV2.DiscoveryScanning -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.SCANNING,
                            lastReason = event.reason,
                        ),
                    ),
                )
            }

            is ConnectionEventV2.DeviceFound -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.DEVICE_FOUND,
                            activeDeviceId = event.deviceId ?: previous.discovery.activeDeviceId,
                            lastReason = "device found",
                        ),
                    ),
                )
            }

            is ConnectionEventV2.BluetoothConnectStarting -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.BT_CONNECTING,
                            activeDeviceId = event.deviceId ?: previous.discovery.activeDeviceId,
                            lastReason = "bluetooth connecting",
                        ),
                    ),
                )
            }

            ConnectionEventV2.BluetoothPairingStarted -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.BT_CONNECTING,
                            lastReason = "bluetooth pairing in progress",
                        ),
                    ),
                )
            }

            is ConnectionEventV2.BluetoothConnected -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = DiscoveryStateV2.BT_CONNECTED,
                            activeDeviceId = event.deviceId ?: previous.discovery.activeDeviceId,
                            lastReason = "bluetooth connected",
                        ),
                    ),
                )
            }

            ConnectionEventV2.BluetoothDisconnected -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = recoveryDiscoveryState(),
                            lastReason = "bluetooth disconnected",
                        ),
                    ),
                    effects = retryDiscoveryEffects(previous),
                )
            }

            ConnectionEventV2.ConnectDeviceFailed -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = recoveryDiscoveryState(),
                            lastReason = "device connect failed",
                        ),
                    ),
                    effects = retryDiscoveryEffects(previous),
                )
            }

            ConnectionEventV2.DeviceNotFound -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = recoveryDiscoveryState(),
                            lastReason = "device not found",
                        ),
                    ),
                    effects = retryDiscoveryEffects(previous),
                )
            }

            is ConnectionEventV2.Plugged -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        projection = previous.projection.copy(
                            state = promoteProjectionToPlugged(previous.projection.state),
                            phoneType = event.phoneType,
                            wifiState = event.wifiState,
                        ),
                    ),
                    effects = if (!previous.isSessionEstablished()) {
                        listOf(
                            ConnectionEffectV2.StopBleAdvertising,
                            ConnectionEffectV2.ClearPendingAutoConnect,
                            ConnectionEffectV2.RequestKeyFrame,
                            ConnectionEffectV2.StartFrameRequests,
                        )
                    } else {
                        emptyList()
                    },
                )
            }

            is ConnectionEventV2.RawPhaseReceived -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        projection = reduceProjectionPhase(previous.projection, event.value),
                    ),
                    effects = when (event.value) {
                        0, 13 -> listOf(
                            ConnectionEffectV2.StopFrameRequests,
                            ConnectionEffectV2.StartBleAdvertising,
                            if (previous.policy.selectionMode == SelectionModeV2.AUTO) {
                                ConnectionEffectV2.RequestAutoConnect
                            } else {
                                ConnectionEffectV2.ClearPendingAutoConnect
                            },
                        )

                        8 -> listOf(
                            ConnectionEffectV2.StopBleAdvertising,
                            ConnectionEffectV2.ClearPendingAutoConnect,
                        )

                        else -> emptyList()
                    },
                )
            }

            is ConnectionEventV2.FirstVideoFrame -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        projection = previous.projection.copy(
                            state = ProjectionStateV2.STREAMING,
                            firstVideoSeen = true,
                            videoWidth = event.width,
                            videoHeight = event.height,
                        ),
                    ),
                    effects = if (!previous.isStreamingActive()) {
                        listOf(
                            ConnectionEffectV2.StopBleAdvertising,
                            ConnectionEffectV2.ClearPendingAutoConnect,
                        )
                    } else {
                        emptyList()
                    },
                )
            }

            ConnectionEventV2.Unplugged -> {
                ReductionResultV2(
                    snapshot = previous.copy(
                        discovery = previous.discovery.copy(
                            state = recoveryDiscoveryState(),
                            lastReason = "unplugged",
                        ),
                        projection = previous.projection.copy(
                            state = ProjectionStateV2.DISCONNECTED,
                            rawPhase = null,
                            phoneType = null,
                            wifiState = null,
                            firstVideoSeen = false,
                            videoWidth = null,
                            videoHeight = null,
                        ),
                    ),
                    effects = listOf(ConnectionEffectV2.StopFrameRequests) + retryDiscoveryEffects(previous),
                )
            }
        }
    }

    private fun freshTransportSnapshot(
        previous: ConnectionSnapshotV2,
        epoch: Long,
        state: TransportStateV2,
        connectionLabel: String?,
    ): ConnectionSnapshotV2 {
        return ConnectionSnapshotV2(
            transport = TransportLaneV2(
                state = state,
                epoch = epoch,
                connectionLabel = connectionLabel,
            ),
            discovery = DiscoveryLaneV2(),
            projection = ProjectionLaneV2(),
            policy = PolicyLaneV2(vehicleReady = previous.policy.vehicleReady),
        )
    }

    private fun resolveSelectionMode(
        knownDeviceCount: Int,
        autoConnectEligible: Boolean,
        manualSelectionRequired: Boolean,
    ): SelectionModeV2 {
        if (knownDeviceCount <= 0) return SelectionModeV2.NONE
        if (manualSelectionRequired) return SelectionModeV2.MANUAL
        return if (autoConnectEligible) SelectionModeV2.AUTO else SelectionModeV2.NONE
    }

    private fun alignDiscoveryToPolicy(
        discovery: DiscoveryLaneV2,
        selectionMode: SelectionModeV2,
        openAcknowledged: Boolean,
        sessionEstablished: Boolean,
    ): DiscoveryLaneV2 {
        if (sessionEstablished || !openAcknowledged) return discovery
        if (
            discovery.state == DiscoveryStateV2.DEVICE_FOUND ||
            discovery.state == DiscoveryStateV2.BT_CONNECTING ||
            discovery.state == DiscoveryStateV2.BT_CONNECTED
        ) {
            return discovery
        }
        return when (selectionMode) {
            SelectionModeV2.AUTO -> discovery.copy(
                state = DiscoveryStateV2.SCANNING,
                lastReason = "auto-connect policy armed",
            )

            SelectionModeV2.MANUAL -> discovery.copy(
                state = DiscoveryStateV2.MANUAL_SELECTION,
                lastReason = "manual selection required",
            )

            SelectionModeV2.NONE -> discovery.copy(
                state = DiscoveryStateV2.IDLE,
                lastReason = "awaiting pairing or policy decision",
            )
        }
    }

    private fun armingDiscoveryEffects(
        selectionMode: SelectionModeV2,
        knownDeviceCount: Int,
        openAcknowledged: Boolean,
        sessionEstablished: Boolean,
    ): List<ConnectionEffectV2> {
        if (!openAcknowledged || sessionEstablished) return emptyList()
        return buildList {
            add(ConnectionEffectV2.StartBleAdvertising)
            if (selectionMode == SelectionModeV2.AUTO && knownDeviceCount > 0) {
                add(ConnectionEffectV2.RequestAutoConnect)
            } else {
                add(ConnectionEffectV2.ClearPendingAutoConnect)
            }
        }
    }

    private fun retryDiscoveryEffects(previous: ConnectionSnapshotV2): List<ConnectionEffectV2> {
        if (previous.isSessionEstablished()) return emptyList()
        val knownDeviceCount = previous.policy.knownDeviceCount
        return buildList {
            add(ConnectionEffectV2.StartBleAdvertising)
            if (previous.policy.selectionMode == SelectionModeV2.AUTO && knownDeviceCount > 0) {
                add(ConnectionEffectV2.RequestAutoConnect)
            } else {
                add(ConnectionEffectV2.ClearPendingAutoConnect)
            }
        }
    }

    private fun recoveryDiscoveryState(): DiscoveryStateV2 {
        return DiscoveryStateV2.RETRYING
    }

    private fun promoteProjectionToPlugged(current: ProjectionStateV2): ProjectionStateV2 {
        return when (current) {
            ProjectionStateV2.STREAMING -> ProjectionStateV2.STREAMING
            ProjectionStateV2.NEGOTIATING -> ProjectionStateV2.NEGOTIATING
            ProjectionStateV2.PLUGGED -> ProjectionStateV2.PLUGGED
            ProjectionStateV2.NONE,
            ProjectionStateV2.DISCONNECTED,
            ProjectionStateV2.ENDED,
            ProjectionStateV2.FAILED,
            -> ProjectionStateV2.PLUGGED
        }
    }

    private fun maxProjectionState(
        current: ProjectionStateV2,
        candidate: ProjectionStateV2,
    ): ProjectionStateV2 {
        return if (projectionRank(candidate) >= projectionRank(current)) {
            candidate
        } else {
            current
        }
    }

    private fun projectionRank(state: ProjectionStateV2): Int {
        return when (state) {
            ProjectionStateV2.NONE -> 0
            ProjectionStateV2.DISCONNECTED -> 0
            ProjectionStateV2.PLUGGED -> 1
            ProjectionStateV2.NEGOTIATING -> 2
            ProjectionStateV2.STREAMING -> 3
            ProjectionStateV2.ENDED -> 4
            ProjectionStateV2.FAILED -> 4
        }
    }

    private fun reduceProjectionPhase(
        projection: ProjectionLaneV2,
        rawPhase: Int,
    ): ProjectionLaneV2 {
        return when (rawPhase) {
            7 -> projection.copy(
                state = maxProjectionState(projection.state, ProjectionStateV2.NEGOTIATING),
                rawPhase = rawPhase,
            )

            8 -> projection.copy(
                state = ProjectionStateV2.STREAMING,
                rawPhase = rawPhase,
            )

            13 -> projection.copy(
                state = ProjectionStateV2.FAILED,
                rawPhase = rawPhase,
                firstVideoSeen = false,
                videoWidth = null,
                videoHeight = null,
            )

            0 -> projection.copy(
                state = ProjectionStateV2.ENDED,
                rawPhase = rawPhase,
                firstVideoSeen = false,
                videoWidth = null,
                videoHeight = null,
            )

            else -> projection.copy(rawPhase = rawPhase)
        }
    }
}
