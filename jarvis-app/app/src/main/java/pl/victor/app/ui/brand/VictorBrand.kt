package pl.victor.app.ui.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.victor.app.R

/**
 * Znak V.I.C.T.O.R. - jeden plik wektorowy ([R.drawable.ic_launcher_foreground],
 * dwie soczewki-pierścienie połączone mostkiem), przebarwiany w locie przez
 * [Icon]. Dzięki temu nie trzeba trzymać osobnych zasobów na jasne/ciemne tło -
 * kolor dostaje się z aktualnego motywu (patrz [pl.victor.app.ui.theme.VictorTheme]),
 * więc automatycznie pasuje też do wysokiego kontrastu i dynamic color.
 */
@Composable
fun VictorMark(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

/**
 * Napis "V·I·C·T·O·R·" - kropki po każdej literze w kolorze akcentu,
 * tak jak w oficjalnym logotypie.
 */
@Composable
fun VictorWordmark(
    modifier: Modifier = Modifier,
    letterColor: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    letterStyle: TextStyle = MaterialTheme.typography.titleLarge,
    dotSize: Dp = 4.dp
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        "VICTOR".forEachIndexed { index, letter ->
            if (index > 0) Spacer(Modifier.width(2.dp))
            Text(letter.toString(), style = letterStyle, fontWeight = FontWeight.Bold, color = letterColor)
            Spacer(Modifier.width(2.dp))
            Dot(dotColor, dotSize)
        }
    }
}

@Composable
private fun Dot(color: Color, dotSize: Dp) {
    Box(
        modifier = Modifier
            .padding(bottom = 5.dp)
            .size(dotSize)
            .background(color)
    )
}

/** Znak + wordmark pod spodem - do ekranów powitalnych/about. */
@Composable
fun VictorBrandMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 64.dp
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        VictorMark(modifier = Modifier.size(markSize))
        Spacer(Modifier.height(12.dp))
        VictorWordmark()
    }
}
