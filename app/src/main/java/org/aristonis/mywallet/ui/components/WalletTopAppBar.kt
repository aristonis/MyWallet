package org.aristonis.mywallet.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.aristonis.mywallet.R
import org.aristonis.mywallet.ui.icons.WalletIcons

/**
 * The app bar every screen wears. Passing [onBack] adds the back affordance; the top-level screens
 * leave it out. The back arrow is a Material Symbol rather than a "Cancel" text button so it mirrors
 * automatically in a right-to-left layout and reads as one 48dp target to a screen reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = WalletIcons.Back,
                        contentDescription = stringResource(R.string.cd_navigate_back),
                    )
                }
            }
        },
        actions = actions,
    )
}
