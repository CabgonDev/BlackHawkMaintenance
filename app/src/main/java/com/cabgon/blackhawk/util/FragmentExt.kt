package com.cabgon.blackhawk.util

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.cabgon.blackhawk.R

/**
 * Navegación estándar del proyecto (Fix 3).
 *
 * - navigateClean(): replace + opcional backstack
 * - navigateRoot(): limpia backstack completo y hace replace (para "pantallas raíz")
 *
 * IMPORTANT: Por defecto usa R.id.fragmentContainer (tu contenedor estándar).
 * Si algún flujo usa otro container, pásalo con containerId.
 */

fun Fragment.navigateClean(
    fragment: Fragment,
    addToBackStack: Boolean = true,
    @IdRes containerId: Int = R.id.fragmentContainer,
    backStackName: String = fragment::class.java.simpleName
) {
    val fm = parentFragmentManager
    val tx = fm.beginTransaction()
        .setReorderingAllowed(true)
        .replace(containerId, fragment)

    if (addToBackStack) tx.addToBackStack(backStackName)
    tx.commit()
}

fun Fragment.navigateRoot(
    fragment: Fragment,
    @IdRes containerId: Int = R.id.fragmentContainer
) {
    val fm = parentFragmentManager

    // Limpia TODO el backstack para evitar overlays
    fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

    fm.beginTransaction()
        .setReorderingAllowed(true)
        .replace(containerId, fragment)
        .commit()
}
