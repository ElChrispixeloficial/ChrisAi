package com.chrispixel.chrisai.data.permissions

/**
 * v0.9 Permission Center model — a deterministic snapshot of every capability
 * that drives ChrisAI features, so the UI can show one clear status per item
 * and route the user to the right request/action.
 */
enum class CapabilityId(val label: String) {
    MIC("Micrófono"),
    CAMERA("Cámara"),
    SCREEN("Pantalla"),
    NOTIFICATIONS("Notificaciones"),
    TOOLS("ChrisTools"),
    DRIVE("Drive"),
    VISION("Visión"),
    PROVIDER("Proveedor IA")
}

data class CapabilityStatus(
    val id: CapabilityId,
    val enabled: Boolean,
    val detail: String
)

object PermissionCenter {

    /**
     * Builds the current capability table. Kept as a pure function: callers
     * pass real platform state (permissions, feature flags) and get a stable,
     * testable summary.
     */
    fun snapshot(
        micGranted: Boolean,
        cameraGranted: Boolean,
        screenShareActive: Boolean,
        notificationsEnabled: Boolean,
        toolsEnabled: Boolean,
        driveConnected: Boolean,
        visionAvailable: Boolean,
        fallbackProvider: Boolean
    ): List<CapabilityStatus> = listOf(
        CapabilityStatus(CapabilityId.MIC, micGranted, if (micGranted) "Concedido" else "Requiere permiso"),
        CapabilityStatus(CapabilityId.CAMERA, cameraGranted, if (cameraGranted) "Concedido" else "Requiere permiso"),
        CapabilityStatus(
            CapabilityId.SCREEN,
            screenShareActive,
            if (screenShareActive) "Compartiendo ahora" else "Por petición explícita"
        ),
        CapabilityStatus(
            CapabilityId.NOTIFICATIONS,
            notificationsEnabled,
            if (notificationsEnabled) "Activas" else "Silenciadas"
        ),
        CapabilityStatus(CapabilityId.TOOLS, toolsEnabled, if (toolsEnabled) "Acciones habilitadas" else "Deshabilitado"),
        CapabilityStatus(CapabilityId.DRIVE, driveConnected, if (driveConnected) "Conectado" else "No conectado"),
        CapabilityStatus(CapabilityId.VISION, visionAvailable, if (visionAvailable) "Disponible" else "No disponible"),
        CapabilityStatus(
            CapabilityId.PROVIDER,
            fallbackProvider,
            if (fallbackProvider) "OpenRouter + Gemini de respaldo" else "OpenRouter"
        )
    )

    /** Grouping used by the Settings screen (voice/vision/tools/system). */
    fun group(id: CapabilityId): String = when (id) {
        CapabilityId.MIC, CapabilityId.CAMERA, CapabilityId.SCREEN, CapabilityId.NOTIFICATIONS ->
            "Voz y visión"
        CapabilityId.VISION, CapabilityId.PROVIDER -> "Motor"
        CapabilityId.TOOLS, CapabilityId.DRIVE -> "ChrisTools"
    }
}