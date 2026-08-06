package com.toptrumps.rules

/**
 * An opaque colour as a manifest author literally types it — `@JvmInline` over a raw ARGB `Int`,
 * matching the [MetricKey]/[StatValue] idiom rather than carrying a hex `String` on the domain
 * type. Alpha is always `0xFF`; see the deck-theme-block ADR.
 */
@JvmInline
public value class ArgbColor(public val argb: Int)

/**
 * A deck's visual identity — accent/ink colours, an optional nominated hero card for the picker,
 * and an optional background image. See the deck-theme-block ADR and the themed-card-backgrounds
 * ADR. [heroCardId] and [backgroundImage], once they reach `:app`, are a manifest author's literal
 * choice: `null` if the manifest omitted the field or it named no card/file this type can confirm
 * exists — this type alone can't validate either against a card list or the filesystem, so it
 * never invents a fallback itself. Resolving a `null` [heroCardId] to an actual representative
 * card is `AppGraph`'s job (TDD decision 6); resolving an unresolvable [backgroundImage] to `null`
 * is `DeckLoader.load`'s job, the same way it already resolves `Card.image`.
 */
public data class DeckTheme(
    val accent: ArgbColor,
    val onAccent: ArgbColor,
    val heroCardId: String? = null,
    val backgroundImage: String? = null,
) {
    public companion object {
        /** Classic yellow accent with black ink, applied when a manifest has no `theme` block or a malformed one — see the deck-theme-block ADR. */
        public val DEFAULT: DeckTheme = DeckTheme(
            accent = ArgbColor(0xFFFFC400.toInt()),
            onAccent = ArgbColor(0xFF1A1A1A.toInt()),
            heroCardId = null,
            backgroundImage = null,
        )
    }
}
