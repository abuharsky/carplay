package com.alexander.carplay.domain.model

enum class ProjectionProtocolPhase(
    val shortLabel: String,
    val title: String,
) {
    NONE("--", "Нет активной протокольной фазы"),
    HOST_INIT("H0", "Host init"),
    INIT_ECHO("P1", "Init echo"),
    PHONE_SEARCH("P2", "Phone search"),
    PHONE_FOUND_BT_CONNECTED("P3", "BT connected"),
    CARPLAY_SESSION_SETUP("P4", "CarPlay setup"),
    AIRPLAY_NEGOTIATING("7", "AirPlay negotiating"),
    STREAMING_ACTIVE("8", "Streaming active"),
    SESSION_ENDED("0", "Session ended"),
    NEGOTIATION_FAILED("13", "Negotiation failed"),
    WAITING_RETRY("R", "Rescanning"),
}
