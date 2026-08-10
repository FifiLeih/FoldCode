package dev.foldcode.ide

import kotlin.reflect.KProperty

/** A small bridge that lets controllers read and update Compose-owned state. */
internal class MutableWorkspaceValue<T>(
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        setter(value)
    }
}

internal fun <T> mutableWorkspaceValue(getter: () -> T, setter: (T) -> Unit) =
    MutableWorkspaceValue(getter, setter)
