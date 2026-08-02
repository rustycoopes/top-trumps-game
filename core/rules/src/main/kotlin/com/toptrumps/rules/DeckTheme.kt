package com.toptrumps.rules

/**
 * An opaque colour as a manifest author literally types it — `@JvmInline` over a raw ARGB `Int`,
 * matching the [MetricKey]/[StatValue] idiom rather than carrying a hex `String` on the domain
 * type. Alpha is always `0xFF`; see the deck-theme-block ADR.
 */
@JvmInline
public value class ArgbColor(public val argb: Int)

/**
 * A deck's visual identity — accent/ink colours and an optional nominated hero card for the
 * picker. See the deck-theme-block ADR. [heroCardId], once it reaches `:app`, is a manifest
 * author's literal choice: `null` if the manifest omitted it or it named no card in this deck —
 * this type alone can't validate that against a card list, so it never invents a fallback id
 * itself. Resolving `null` to an actual representative card is `AppGraph`'s job (TDD decision 6).
 */
public data class DeckTheme(
    val accent: ArgbColor,
    val onAccent: ArgbColor,
    val heroCardId: String? = null,
) {
    public companion object {
        /** Classic yellow accent with black ink, applied when a manifest has no `theme` block or a malformed one — see the deck-theme-block ADR. */
        public val DEFAULT: DeckTheme = DeckTheme(
            accent = ArgbColor(0xFFFFC400.toInt()),
            onAccent = ArgbColor(0xFF1A1A1A.toInt()),
            heroCardId = null,
        )
    }
}
