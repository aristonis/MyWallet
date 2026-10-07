package org.aristonis.mywallet.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The Calm Ledger palette, written as Material 3 tonal ramps rather than as a list of finished
 * roles. The values docs/Design.md pins by name are the anchors — Primary 40 is its #00696D,
 * Primary 90 its #82F5F7, Neutral 98 its surface — and the rest of each ramp is the tone that sits
 * around them. Building it this way is what lets Theme.kt fill in every one of Material's roles
 * from the same source, so a device that cannot do dynamic colour never falls through to Material's
 * purple defaults on a role nobody thought to set.
 */

// -- Primary: teal, the wallet's identity ---------------------------------------------------------

private val Primary10 = Color(0xFF002021)
private val Primary20 = Color(0xFF003739)
private val Primary30 = Color(0xFF004F53)
private val Primary40 = Color(0xFF00696D)
private val Primary80 = Color(0xFF4FD8DE)
private val Primary90 = Color(0xFF82F5F7)
private val Primary100 = Color(0xFFFFFFFF)

// -- Secondary: the same hue, drained of most of its colour ---------------------------------------

private val Secondary10 = Color(0xFF051F21)
private val Secondary20 = Color(0xFF1C3436)
private val Secondary30 = Color(0xFF334B4D)
private val Secondary40 = Color(0xFF4A6365)
private val Secondary80 = Color(0xFFB1CBCD)
private val Secondary90 = Color(0xFFCCE8E9)
private val Secondary100 = Color(0xFFFFFFFF)

// -- Tertiary: a cooler blue, for the rare accent that must not read as primary -------------------

private val Tertiary10 = Color(0xFF041C35)
private val Tertiary20 = Color(0xFF1C314B)
private val Tertiary30 = Color(0xFF334863)
private val Tertiary40 = Color(0xFF4B607C)
private val Tertiary80 = Color(0xFFB3C8E8)
private val Tertiary90 = Color(0xFFD3E4FF)
private val Tertiary100 = Color(0xFFFFFFFF)

// -- Neutral: backgrounds, text, and the surface-container stack ----------------------------------

private val Neutral4 = Color(0xFF0B0F0F)
private val Neutral6 = Color(0xFF101414)
private val Neutral10 = Color(0xFF191C1C)
private val Neutral12 = Color(0xFF1D2121)
private val Neutral17 = Color(0xFF272B2B)
private val Neutral20 = Color(0xFF2D3131)
private val Neutral22 = Color(0xFF313535)
private val Neutral24 = Color(0xFF363A3A)
private val Neutral87 = Color(0xFFD8DBDA)
private val Neutral90 = Color(0xFFE0E3E2)
private val Neutral92 = Color(0xFFE6E9E8)
private val Neutral94 = Color(0xFFECEFEE)
private val Neutral95 = Color(0xFFEFF1F0)
private val Neutral96 = Color(0xFFF2F4F3)
private val Neutral98 = Color(0xFFF8FAF9)
private val Neutral100 = Color(0xFFFFFFFF)

// -- Neutral variant: dividers, outlines, and quiet containers ------------------------------------

private val NeutralVariant30 = Color(0xFF3F4948)
private val NeutralVariant50 = Color(0xFF6F7978)
private val NeutralVariant60 = Color(0xFF899392)
private val NeutralVariant80 = Color(0xFFBFC9C7)
private val NeutralVariant90 = Color(0xFFDBE5E3)

// -- Error ----------------------------------------------------------------------------------------

private val Error10 = Color(0xFF410002)
private val Error20 = Color(0xFF690005)
private val Error30 = Color(0xFF93000A)
private val Error40 = Color(0xFFBA1A1A)
private val Error80 = Color(0xFFFFB4AB)
private val Error90 = Color(0xFFFFDAD6)
private val Error100 = Color(0xFFFFFFFF)

private val Scrim = Color(0xFF000000)

// -- Light roles ----------------------------------------------------------------------------------

internal val PrimaryLight = Primary40
internal val OnPrimaryLight = Primary100
internal val PrimaryContainerLight = Primary90
internal val OnPrimaryContainerLight = Primary10
internal val InversePrimaryLight = Primary80

internal val SecondaryLight = Secondary40
internal val OnSecondaryLight = Secondary100
internal val SecondaryContainerLight = Secondary90
internal val OnSecondaryContainerLight = Secondary10

internal val TertiaryLight = Tertiary40
internal val OnTertiaryLight = Tertiary100
internal val TertiaryContainerLight = Tertiary90
internal val OnTertiaryContainerLight = Tertiary10

internal val ErrorLight = Error40
internal val OnErrorLight = Error100
internal val ErrorContainerLight = Error90
internal val OnErrorContainerLight = Error10

internal val BackgroundLight = Neutral98
internal val OnBackgroundLight = Neutral10
internal val SurfaceLight = Neutral98
internal val OnSurfaceLight = Neutral10
internal val SurfaceVariantLight = NeutralVariant90
internal val OnSurfaceVariantLight = NeutralVariant30
internal val OutlineLight = NeutralVariant50
internal val OutlineVariantLight = NeutralVariant80
internal val InverseSurfaceLight = Neutral20
internal val InverseOnSurfaceLight = Neutral95
internal val ScrimLight = Scrim

internal val SurfaceBrightLight = Neutral98
internal val SurfaceDimLight = Neutral87
internal val SurfaceContainerLowestLight = Neutral100
internal val SurfaceContainerLowLight = Neutral96
internal val SurfaceContainerLight = Neutral94
internal val SurfaceContainerHighLight = Neutral92
internal val SurfaceContainerHighestLight = Neutral90

// -- Dark roles -----------------------------------------------------------------------------------

internal val PrimaryDark = Primary80
internal val OnPrimaryDark = Primary20
internal val PrimaryContainerDark = Primary30
internal val OnPrimaryContainerDark = Primary90
internal val InversePrimaryDark = Primary40

internal val SecondaryDark = Secondary80
internal val OnSecondaryDark = Secondary20
internal val SecondaryContainerDark = Secondary30
internal val OnSecondaryContainerDark = Secondary90

internal val TertiaryDark = Tertiary80
internal val OnTertiaryDark = Tertiary20
internal val TertiaryContainerDark = Tertiary30
internal val OnTertiaryContainerDark = Tertiary90

internal val ErrorDark = Error80
internal val OnErrorDark = Error20
internal val ErrorContainerDark = Error30
internal val OnErrorContainerDark = Error90

internal val BackgroundDark = Neutral6
internal val OnBackgroundDark = Neutral90
internal val SurfaceDark = Neutral6
internal val OnSurfaceDark = Neutral90
internal val SurfaceVariantDark = NeutralVariant30
internal val OnSurfaceVariantDark = NeutralVariant80
internal val OutlineDark = NeutralVariant60
internal val OutlineVariantDark = NeutralVariant30
internal val InverseSurfaceDark = Neutral90
internal val InverseOnSurfaceDark = Neutral20
internal val ScrimDark = Scrim

internal val SurfaceBrightDark = Neutral24
internal val SurfaceDimDark = Neutral6
internal val SurfaceContainerLowestDark = Neutral4
internal val SurfaceContainerLowDark = Neutral10
internal val SurfaceContainerDark = Neutral12
internal val SurfaceContainerHighDark = Neutral17
internal val SurfaceContainerHighestDark = Neutral22

// -- Fixed roles ----------------------------------------------------------------------------------
// "Fixed" means exactly that: identical in light and dark, for the rare element that must keep the
// same colour when the theme flips. One set serves both schemes.

internal val PrimaryFixed = Primary90
internal val PrimaryFixedDim = Primary80
internal val OnPrimaryFixed = Primary10
internal val OnPrimaryFixedVariant = Primary30

internal val SecondaryFixed = Secondary90
internal val SecondaryFixedDim = Secondary80
internal val OnSecondaryFixed = Secondary10
internal val OnSecondaryFixedVariant = Secondary30

internal val TertiaryFixed = Tertiary90
internal val TertiaryFixedDim = Tertiary80
internal val OnTertiaryFixed = Tertiary10
internal val OnTertiaryFixedVariant = Tertiary30

// -- Ledger semantics -----------------------------------------------------------------------------
// Material 3 has no role for "money came in" / "money went out" / "recoverable problem", and these
// must not follow dynamic colour: a user's wallpaper could make income and expense near-identical.

internal val IncomeLight = Color(0xFF226C46)
internal val ExpenseLight = Error40
internal val WarningLight = Color(0xFF805500)
internal val WarningContainerLight = Color(0xFFFFDDB0)
internal val OnWarningContainerLight = Color(0xFF291800)

internal val IncomeDark = Color(0xFF8EDCAF)
internal val ExpenseDark = Error80
internal val WarningDark = Color(0xFFFFB86B)
internal val WarningContainerDark = Color(0xFF613F00)
internal val OnWarningContainerDark = Color(0xFFFFDDB0)
