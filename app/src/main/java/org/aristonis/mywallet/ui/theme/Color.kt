package org.aristonis.mywallet.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The Slate Ledger palette: a deep slate base, a trust blue for actions, and green / red reserved for
 * money in and money out. Written as tonal ramps so Theme.kt can fill every Material role from one
 * source; a device without dynamic colour (and this app turns it off by default) must never fall
 * through to Material's purple defaults on a role nobody thought to set.
 *
 * Dark is the primary design target. Every text colour below was measured against the three dark
 * surfaces it sits on (background, card, raised card) and clears 4.5:1.
 */

// -- Primary: trust blue -------------------------------------------------------------------------

private val Blue950 = Color(0xFF172554)
private val Blue900 = Color(0xFF1E3A8A)
private val Blue800 = Color(0xFF1E40AF)
private val Blue700 = Color(0xFF1D4ED8)
private val Blue600 = Color(0xFF2563EB)
private val Blue400 = Color(0xFF60A5FA)
private val Blue300 = Color(0xFF93C5FD)
private val Blue100 = Color(0xFFDBEAFE)

// -- Secondary: slate, the quiet companion to blue ------------------------------------------------

private val Slate950 = Color(0xFF0B1222)
private val Slate900 = Color(0xFF0F172A)
private val Slate875 = Color(0xFF131C30)
private val Slate850 = Color(0xFF192134)
private val Slate825 = Color(0xFF222B40)
private val Slate835 = Color(0xFF1D2538)
private val Slate800 = Color(0xFF1E293B)
private val Slate790 = Color(0xFF273349)
private val Slate775 = Color(0xFF2A3550)
private val Slate740 = Color(0xFF334058)
private val Slate700 = Color(0xFF334155)
private val Slate600 = Color(0xFF475569)
private val Slate550 = Color(0xFF5F6E84)
private val Slate500 = Color(0xFF64748B)
private val Slate400 = Color(0xFF94A3B8)
private val Slate350 = Color(0xFFA5B4CB)
private val Slate300 = Color(0xFFCBD5E1)
private val Slate250 = Color(0xFFD5DCE6)
private val Slate225 = Color(0xFFDCE3EC)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate175 = Color(0xFFDCE3EE)
private val Slate150 = Color(0xFFEEF2F7)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate50 = Color(0xFFF8FAFC)
private val White = Color(0xFFFFFFFF)

// -- Tertiary: emerald, the "money grew" accent ---------------------------------------------------

private val Emerald950 = Color(0xFF022C22)
private val Emerald900 = Color(0xFF064E3B)
private val Emerald800 = Color(0xFF065F46)
private val Emerald700 = Color(0xFF047857)
private val Emerald400 = Color(0xFF34D399)
private val Emerald300 = Color(0xFF6EE7B7)
private val Emerald200 = Color(0xFFA7F3D0)
private val Emerald100 = Color(0xFFD1FAE5)

// -- Error / expense: red -------------------------------------------------------------------------

private val Red950 = Color(0xFF450A0A)
private val Red900 = Color(0xFF7F1D1D)
private val Red700 = Color(0xFFB91C1C)
private val Red400 = Color(0xFFF87171)
private val Red200 = Color(0xFFFECACA)
private val Red100 = Color(0xFFFEE2E2)

// -- Warning: amber -------------------------------------------------------------------------------

private val Amber950 = Color(0xFF451A03)
private val Amber900 = Color(0xFF78350F)
private val Amber800 = Color(0xFF92400E)
private val Amber400 = Color(0xFFFBBF24)
private val Amber100 = Color(0xFFFEF3C7)

private val Scrim = Color(0xFF000000)

// -- Light roles ----------------------------------------------------------------------------------

internal val PrimaryLight = Blue700
internal val OnPrimaryLight = White
internal val PrimaryContainerLight = Blue100
internal val OnPrimaryContainerLight = Blue900
internal val InversePrimaryLight = Blue300

internal val SecondaryLight = Slate600
internal val OnSecondaryLight = White
internal val SecondaryContainerLight = Slate200
internal val OnSecondaryContainerLight = Slate800

internal val TertiaryLight = Emerald700
internal val OnTertiaryLight = White
internal val TertiaryContainerLight = Emerald100
internal val OnTertiaryContainerLight = Emerald900

internal val ErrorLight = Red700
internal val OnErrorLight = White
internal val ErrorContainerLight = Red100
internal val OnErrorContainerLight = Red900

internal val BackgroundLight = Slate50
internal val OnBackgroundLight = Slate900
internal val SurfaceLight = Slate50
internal val OnSurfaceLight = Slate900
internal val SurfaceVariantLight = Slate200
internal val OnSurfaceVariantLight = Slate600
// Slate500 sits at 2.98:1 on the highest light container, just under the 3:1 a control border needs.
internal val OutlineLight = Slate550
internal val OutlineVariantLight = Slate300
internal val InverseSurfaceLight = Slate800
internal val InverseOnSurfaceLight = Slate100
internal val ScrimLight = Scrim

internal val SurfaceBrightLight = Slate50
internal val SurfaceDimLight = Slate250
internal val SurfaceContainerLowestLight = White
internal val SurfaceContainerLowLight = Slate100
internal val SurfaceContainerLight = Slate150
internal val SurfaceContainerHighLight = Slate200
internal val SurfaceContainerHighestLight = Slate225

// -- Dark roles -----------------------------------------------------------------------------------

internal val PrimaryDark = Blue400
internal val OnPrimaryDark = Slate900
internal val PrimaryContainerDark = Blue900
internal val OnPrimaryContainerDark = Blue100
internal val InversePrimaryDark = Blue600

internal val SecondaryDark = Slate350
internal val OnSecondaryDark = Slate800
internal val SecondaryContainerDark = Slate790
internal val OnSecondaryContainerDark = Slate175

internal val TertiaryDark = Emerald400
internal val OnTertiaryDark = Emerald950
internal val TertiaryContainerDark = Emerald900
internal val OnTertiaryContainerDark = Emerald200

internal val ErrorDark = Red400
internal val OnErrorDark = Red950
internal val ErrorContainerDark = Red900
internal val OnErrorContainerDark = Red200

internal val BackgroundDark = Slate900
internal val OnBackgroundDark = Slate100
internal val SurfaceDark = Slate900
internal val OnSurfaceDark = Slate100
internal val SurfaceVariantDark = Slate825
internal val OnSurfaceVariantDark = Slate400
// Outline must reach 3:1 on the background for outlined buttons and fields; the hairline that
// separates cards is outlineVariant, which only has to be visible, not legible.
internal val OutlineDark = Slate500
internal val OutlineVariantDark = Slate775
internal val InverseSurfaceDark = Slate200
internal val InverseOnSurfaceDark = Slate800
internal val ScrimDark = Scrim

internal val SurfaceBrightDark = Slate740
internal val SurfaceDimDark = Slate900
internal val SurfaceContainerLowestDark = Slate950
internal val SurfaceContainerLowDark = Slate875
internal val SurfaceContainerDark = Slate850
// Material's filled cards and text fields sit on surfaceContainerHighest, so it is kept one quiet step
// above the page rather than the brightest tone in the stack.
internal val SurfaceContainerHighDark = Slate835
internal val SurfaceContainerHighestDark = Slate825

// -- Fixed roles ----------------------------------------------------------------------------------
// "Fixed" means exactly that: identical in light and dark, for the rare element that must keep the
// same colour when the theme flips. One set serves both schemes.

internal val PrimaryFixed = Blue100
internal val PrimaryFixedDim = Blue300
internal val OnPrimaryFixed = Blue950
internal val OnPrimaryFixedVariant = Blue800

internal val SecondaryFixed = Slate200
internal val SecondaryFixedDim = Slate300
internal val OnSecondaryFixed = Slate900
internal val OnSecondaryFixedVariant = Slate700

internal val TertiaryFixed = Emerald100
internal val TertiaryFixedDim = Emerald300
internal val OnTertiaryFixed = Emerald950
internal val OnTertiaryFixedVariant = Emerald800

// -- Ledger semantics -----------------------------------------------------------------------------
// Material 3 has no role for "money came in" / "money went out" / "recoverable problem", and these
// must not follow dynamic colour: a user's wallpaper could make income and expense near-identical.

// One step darker than the light tertiary green: Emerald700 drops to 4.4:1 on the raised
// light surfaces, under the 4.5:1 that amounts need.
internal val IncomeLight = Emerald800
internal val ExpenseLight = Red700
internal val WarningLight = Amber800
internal val WarningContainerLight = Amber100
internal val OnWarningContainerLight = Amber950

internal val IncomeDark = Emerald400
internal val ExpenseDark = Red400
internal val WarningDark = Amber400
internal val WarningContainerDark = Amber900
internal val OnWarningContainerDark = Amber100
