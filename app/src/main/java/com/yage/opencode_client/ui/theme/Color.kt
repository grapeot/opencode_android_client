package com.yage.opencode_client.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Quiet Tech design language — mirrors the iOS client's DesignTokens.
//
// Single electric-blue identity color; gold reserved strictly for the "AI is
// working" state. Everything else is a neutral gray scale. Dynamic color
// (Material You) is intentionally OFF so the brand reads identically across
// devices and matches iOS, regardless of the user's wallpaper.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Primary brand — the vivid electric blue #3B82F6, matching the iOS client.
 * The same value in both themes: it reads clean and energetic on the near-black
 * dark canvas and on the white light canvas alike, so the send button and other
 * accents stay bright and unmistakably "the brand color" rather than receding.
 */
val BrandPrimary = Color(0xFF3B82F6)       // shared primary, both themes
val BrandPrimaryLight = Color(0xFF3B82F6)  // same vivid blue on light
/** Gold #D9A621 — the ONLY secondary emphasis, reserved for the transient "AI working" state. */
val BrandGold = Color(0xFFD9A621)

/**
 * Stop button red. Material's default error red (#B3261E) read as too dark AND
 * too pure at once — a muddy, heavy red. This #E5484D is lighter (higher value,
 * so it stops looking "deep") while sitting a notch off pure red (slightly lower
 * saturation, so it stops looking harsh). Close in spirit to iOS's system red.
 */
val StopRed = Color(0xFFE5484D)

// Dark scheme neutrals (dark is the primary canvas)
val BgDark = Color(0xFF0B0C0E)            // near-black app background
val SurfaceDark = Color(0xFF1A1D21)       // info card / surface fill
val ComposerDark = Color(0xFF141619)      // composer input background
val OnSurfaceDark = Color(0xFFE6E8EB)     // primary text on dark
val OnSurfaceVariantDark = Color(0xFF9BA1A8) // secondary text on dark
val OutlineDark = Color(0xFF2A2E33)       // hairline separators on dark

// Light scheme neutrals (light is the follower, mapped from the same system)
val BgLight = Color(0xFFFFFFFF)
val SurfaceLight = Color(0xFFF0F1F3)      // info card / surface fill
val ComposerLight = Color(0xFFF4F5F6)     // composer input background
val OnSurfaceLight = Color(0xFF15171A)    // primary text on light
val OnSurfaceVariantLight = Color(0xFF60656B) // secondary text on light
val OutlineLight = Color(0xFFE2E4E7)      // hairline separators on light

/**
 * Subtle tints historically used for write/patch tool cards. Under Quiet Tech
 * tool cards use the neutral surface fill instead, so these are kept only for
 * backward-compatible references and may be removed once nothing reads them.
 */
val ToolWritePatchBackground = Color(0xFFF5F9FF)
val ToolWritePatchBackgroundDark = Color(0xFF252D3D)

// ─────────────────────────────────────────────────────────────────────────────
// Functional colors (diff viewer, git file status) — kept from before.
// These are intentionally NOT part of the Quiet Tech accent system; they carry
// concrete semantic meaning (added/removed/modified) and stay as-is.
// ─────────────────────────────────────────────────────────────────────────────

val AddedLine = Color(0xFFE8F5E9)
val DeletedLine = Color(0xFFFFEBEE)
val ModifiedFile = Color(0xFFFFA726)
val AddedFile = Color(0xFF66BB6A)
val DeletedFile = Color(0xFFEF5350)
val UntrackedFile = Color(0xFF90A4AE)
