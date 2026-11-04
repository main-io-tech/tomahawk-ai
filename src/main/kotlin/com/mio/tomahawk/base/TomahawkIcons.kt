package com.mio.tomahawk.base

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Um ícone colorido por sistema de ataque, usado nas abas da tool window. */
object TomahawkIcons {
    val toolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", TomahawkIcons::class.java)
    val lancador: Icon = IconLoader.getIcon("/icons/lancador.svg", TomahawkIcons::class.java)
    val avaliacao: Icon = IconLoader.getIcon("/icons/avaliacao.svg", TomahawkIcons::class.java)
    val telemetria: Icon = IconLoader.getIcon("/icons/telemetria.svg", TomahawkIcons::class.java)
    val arsenal: Icon = IconLoader.getIcon("/icons/arsenal.svg", TomahawkIcons::class.java)
}
