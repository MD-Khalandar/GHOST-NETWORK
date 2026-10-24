package org.ghost.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, consistent platform-vector navigation set; labels carry the semantics. */
object GhostIcons {
    val Home = icon("Home") {
        moveTo(3f, 11f); lineTo(12f, 3f); lineTo(21f, 11f)
        moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f)
        lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f)
    }
    val Requests = icon("Requests") {
        moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(6f, 21f); close()
        moveTo(9f, 8f); lineTo(15f, 8f); moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(9f, 16f); lineTo(13f, 16f)
    }
    val OfferHelp = icon("Offer help") {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 9f); lineTo(21f, 9f)
        lineTo(21f, 15f); lineTo(15f, 15f); lineTo(15f, 21f); lineTo(9f, 21f)
        lineTo(9f, 15f); lineTo(3f, 15f); lineTo(3f, 9f); lineTo(9f, 9f); close()
    }
    val Network = icon("Network") {
        moveTo(10f, 4f); lineTo(14f, 4f); lineTo(14f, 8f); lineTo(10f, 8f); close()
        moveTo(3f, 16f); lineTo(7f, 16f); lineTo(7f, 20f); lineTo(3f, 20f); close()
        moveTo(17f, 16f); lineTo(21f, 16f); lineTo(21f, 20f); lineTo(17f, 20f); close()
        moveTo(12f, 8f); lineTo(5f, 16f); moveTo(12f, 8f); lineTo(19f, 16f)
    }

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()
}
