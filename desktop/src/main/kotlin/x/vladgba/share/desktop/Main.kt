package x.vladgba.share.desktop

import com.formdev.flatlaf.FlatDarkLaf
import javax.swing.SwingUtilities

fun main() {
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        val window = MainWindow()
        window.isVisible = true
    }
}
