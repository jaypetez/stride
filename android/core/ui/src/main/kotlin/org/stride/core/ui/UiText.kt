package org.stride.core.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message produced outside composition (ViewModels, validation):
 * a string (or plurals) resource plus its format arguments, resolved in the UI so
 * it follows the device locale (AUD-034). Arguments may themselves be [UiText].
 */
@Immutable
data class UiText(val res: Int, val args: List<Any>, val quantity: Int? = null) {
    constructor(@StringRes res: Int, vararg args: Any) : this(res, args.toList())

    @Composable
    @Suppress("SpreadOperator") // format args are a handful of values
    fun resolve(): String {
        val resolved = args.map { if (it is UiText) it.resolve() else it }.toTypedArray()
        return if (quantity != null) pluralStringResource(res, quantity, *resolved) else stringResource(res, *resolved)
    }

    @Suppress("SpreadOperator")
    fun resolve(context: Context): String {
        val resolved = args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
        return if (quantity != null) context.resources.getQuantityString(res, quantity, *resolved) else context.getString(res, *resolved)
    }

    companion object {
        /** A plurals resource chosen by [quantity]; [args] fill its format (usually the quantity itself). */
        fun plural(
            @PluralsRes res: Int,
            quantity: Int,
            vararg args: Any
        ): UiText = UiText(res = res, args = args.toList(), quantity = quantity)
    }
}
