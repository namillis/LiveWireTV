package com.livewire.tv.feature.settings.data

/**
 * How captions are drawn over video, chosen in Settings › Subtitles. Persisted by
 * [SettingsStore]; applied to the player's caption view by
 * [com.livewire.tv.core.player.CaptionStyling]. The defaults match Media3's own caption look
 * (medium white text on a black box), so a user who never opens the screen sees no change.
 */
data class SubtitleStyle(
    val size: CaptionSize = CaptionSize.MEDIUM,
    val color: CaptionColor = CaptionColor.WHITE,
    val background: CaptionBackground = CaptionBackground.BOX,
    val edge: CaptionEdge = CaptionEdge.NONE,
    val font: CaptionFont = CaptionFont.DEFAULT,
    val placement: CaptionPlacement = CaptionPlacement.AS_BROADCAST,
) {
    val isDefault: Boolean get() = this == SubtitleStyle()

    /** One line for the Settings row, e.g. "Medium · white on black · as broadcast". */
    val summary: String
        get() {
            val look = when (background) {
                CaptionBackground.BOX -> "${color.word} on black"
                CaptionBackground.SEE_THROUGH -> "${color.word} on see-through"
                CaptionBackground.NONE -> when (edge) {
                    CaptionEdge.OUTLINE -> "${color.word} outlined"
                    CaptionEdge.SHADOW -> "${color.word} with shadow"
                    CaptionEdge.NONE -> "${color.word}, no background"
                }
            }
            val font = if (font == CaptionFont.DEFAULT) "" else " · ${font.label.lowercase()}"
            return "${size.label} · $look$font · ${placement.label.lowercase()}"
        }
}

/** Caption text height as a fraction of the video height (Media3's default is 0.0533). */
enum class CaptionSize(val label: String, val fraction: Float) {
    SMALL("Small", 0.042f),
    MEDIUM("Medium", 0.0533f),
    LARGE("Large", 0.07f),
    EXTRA_LARGE("Extra large", 0.09f),
}

enum class CaptionColor(val label: String, val argb: Int) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    YELLOW("Yellow", 0xFFFFE135.toInt()),
    CYAN("Cyan", 0xFF4DE3FF.toInt()),
    GREEN("Green", 0xFF6EF08A.toInt()),
    ;
    val word: String get() = label.lowercase()
}

enum class CaptionBackground(val label: String, val argb: Int) {
    BOX("Box", 0xFF000000.toInt()),
    SEE_THROUGH("See-through", 0x99000000.toInt()),
    NONE("None", 0x00000000),
}

enum class CaptionEdge(val label: String) { NONE("None"), OUTLINE("Outline"), SHADOW("Shadow") }

enum class CaptionFont(val label: String) { DEFAULT("Default"), MONO("Mono"), SERIF("Serif") }

/**
 * Where captions sit. [AS_BROADCAST] keeps the row and column the broadcaster sent (US CEA-608
 * captions move around to stay clear of score bugs and name titles); [BOTTOM] stacks every
 * caption line in one centered block at the bottom.
 */
enum class CaptionPlacement(val label: String) { AS_BROADCAST("As broadcast"), BOTTOM("Always bottom") }

/** The next or previous value of an enum, stopping at either end (for ◀ ▶ on a Settings row). */
inline fun <reified E : Enum<E>> E.step(forward: Boolean): E {
    val all = enumValues<E>()
    val i = (ordinal + if (forward) 1 else -1).coerceIn(0, all.lastIndex)
    return all[i]
}

/** Whether [step] in that direction would change the value. */
inline fun <reified E : Enum<E>> E.canStep(forward: Boolean): Boolean =
    if (forward) ordinal < enumValues<E>().lastIndex else ordinal > 0

/** The enum constant named [name], or [fallback] when it is missing or unknown (an older or newer build wrote it). */
inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
