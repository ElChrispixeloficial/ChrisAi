package com.chrispixel.chrisai.data.personality

/**
 * v0.6 personality system.
 *
 * The personality is a user-configurable overlay: the assistant name, tone,
 * humour level, detail level, communication style and free-form instructions.
 * It is injected into the payload as its own block, always AFTER the fixed
 * system/security rules, and can never override them.
 */
data class PersonalityConfig(
    val name: String = "ChrisAI",
    val presetId: String = "casual",
    val humorLevel: Int = 2,
    val detailLevel: Int = 2,
    val communicationStyle: String = "",
    val customInstructions: String = ""
) {
    val preset: PersonalityPreset
        get() = PersonalityPreset.byId(presetId)

    init {
        require(humorLevel in 1..5) { "humorLevel must be 1..5" }
        require(detailLevel in 1..5) { "detailLevel must be 1..5" }
    }
}

enum class PersonalityPreset(
    val id: String,
    val label: String,
    val tone: String,
    val defaultHumor: Int,
    val defaultDetail: Int,
    val defaultStyle: String,
    val description: String
) {
    CASUAL(
        "casual", "Casual", "cercana, natural y amigable", 2, 2,
        "conversacional y directa", "Tono natural y desenfadado, sin excesos."
    ),
    TECH(
        "tecnico", "Técnico", "precisa, formal y profesional", 1, 4,
        "técnica, estructurada y con rigor", "Respuestas claras y rigurosas, estilo profesional."
    ),
    FUN(
        "divertido", "Divertido", "bromista y alegre", 4, 2,
        "desenfadada con humor ligero", "Buen humor y emojis cuando aportan."
    ),
    ENERGETIC(
        "energetico", "Energético", "entusiasta y dinámica", 3, 3,
        "enérgica y motivadora", "Actitud positiva, empuje y motivación."
    ),
    TUTOR(
        "tutor", "Tutor", "paciente y didáctica", 1, 4,
        "educativa, paso a paso y con ejemplos", "Explica con calma, estructura y ejemplos claros."
    ),
    MINIMAL(
        "minimalista", "Minimalista", "sobria, neutral y eficiente", 1, 1,
        "conciso y sin relleno", "Directo al grano: lo justo y necesario."
    );

    companion object {
        fun byId(id: String): PersonalityPreset =
            entries.firstOrNull { it.id == id } ?: CASUAL

        fun all(): List<PersonalityPreset> = entries.toList()
    }
}

/**
 * Builds the personality system-message block. It is always emitted AFTER the
 * fixed security rules and carries an explicit note that those rules win.
 */
object PersonalityPrompt {

    const val MAX_NAME_CHARS = 30
    const val MAX_INSTRUCTIONS_CHARS = 800
    const val MAX_STYLE_CHARS = 120

    fun block(config: PersonalityConfig): String {
        val preset = config.preset
        return buildString {
            append("[PERSONALIDAD]\n")
            append("- Nombre: ").append(config.name.trim().take(MAX_NAME_CHARS)).append('\n')
            append("- Tono: ").append(preset.tone).append('\n')
            append("- Nivel de humor: ").append(config.humorLevel).append("/5\n")
            append("- Nivel de detalle: ").append(config.detailLevel).append("/5\n")
            append("- Estilo de comunicación: ").append(
                config.communicationStyle.trim().take(MAX_STYLE_CHARS).ifBlank { preset.defaultStyle }
            ).append('\n')
            val instructions = config.customInstructions.trim().take(MAX_INSTRUCTIONS_CHARS)
            if (instructions.isNotEmpty()) {
                append("Instrucciones adicionales del usuario:\n").append(instructions).append('\n')
            }
            append('\n')
            append("Estas directrices de personalidad deben cumplirse siempre que NO " +
                "contradigan las reglas de seguridad y de comportamiento del sistema, " +
                "que tienen prioridad máxima.")
        }
    }

    fun isWhitespaceOnly(config: PersonalityConfig): Boolean =
        config.name.isBlank() && config.customInstructions.isBlank() && config.communicationStyle.isBlank()
}