package net.prototux.remoteagents.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons that `material-icons-core` lacks, drawn as 24 dp vectors in the Material style. */
object AppIcons {
    private fun icon(name: String, path: String, evenOdd: Boolean = false) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    ).build()

    val Mic = icon("Mic", "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")
    val Stop = icon("Stop", "M6,6h12v12H6z")
    val Copy = icon("Copy", "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z")
    val QrCode = icon("QrCode", "M3,3h8v8H3zM5,5v4h4V5zM13,3h8v8h-8zM15,5v4h4V5zM3,13h8v8H3zM5,15v4h4v-4zM13,13h3v3h-3zM18,13h3v3h-3zM13,18h3v3h-3zM18,18h3v3h-3zM15.5,15.5h3v3h-3z", evenOdd = true)
    val History = icon("History", "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z")
    val Paste = icon("Paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0S9.6,0.84 9.18,2H5c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z")
    val Terminal = icon("Terminal", "M20,4H4c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.89,-2 -2,-2zM20,18H4V8h16v10zM18,17h-6v-2h6v2zM7.5,17l-1.41,-1.41L8.67,13l-2.59,-2.59L7.5,9l4,4 -4,4z")
    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    val Cloud = icon("Cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
}
