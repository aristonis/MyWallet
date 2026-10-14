package org.aristonis.mywallet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing and size roles signed for the ledger screens. */
@Immutable
data class WalletDimensions(
    val grid: Dp = 4.dp,
    val relatedGap: Dp = 8.dp,
    val cornerMedium: Dp = 12.dp,
    val screenPadding: Dp = 16.dp,
    val regionGap: Dp = 16.dp,
    val sectionGap: Dp = 24.dp,
    val cardPadding: Dp = 16.dp,
    val iconSize: Dp = 24.dp,
    val iconContentGap: Dp = 16.dp,
    val minTouchTarget: Dp = 48.dp,
    val fieldHeight: Dp = 56.dp,
)

internal val SignedWalletDimensions = WalletDimensions()
