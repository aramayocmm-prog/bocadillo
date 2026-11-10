package app.bocadillo.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.bocadillo.R
import app.bocadillo.ui.theme.Classic
import java.util.Locale

/** "Rotate and Flip" pop-up list (shown over the Adjust menu, as in the original). */
@Composable
fun RotateFlipDialog(onDismiss: () -> Unit, onPick: (rotation: Int, flipH: Boolean, flipV: Boolean) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).background(Color(0xFFFAFAFA))) {
            Text(stringResource(R.string.op_orient), color = Classic.Accent, fontSize = 20.sp, modifier = Modifier.padding(18.dp, 16.dp))
            HorizontalDivider(color = Classic.Accent, thickness = 2.dp)
            for ((label, v) in listOf(
                R.string.rotate_90_left to Triple(270, false, false), R.string.rotate_90_right to Triple(90, false, false),
                R.string.rotate_180_menu to Triple(180, false, false), R.string.flip_horizontal to Triple(0, true, false),
                R.string.flip_vertical to Triple(0, false, true),
            )) {
                Text(
                    stringResource(label), color = Color(0xFF212121), fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(v.first, v.second, v.third) }.padding(18.dp, 14.dp),
                )
                HorizontalDivider(color = Color(0xFFDDDDDD))
            }
        }
    }
}

/** Picture Location: the GPS position from the photo's EXIF data, with a link to a maps app. */
@Composable
fun PictureLocationDialog(location: Pair<Double, Double>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_title)) },
        text = {
            Text(
                if (location == null) stringResource(R.string.location_none)
                else stringResource(R.string.location_found, "%.6f".format(Locale.US, location.first), "%.6f".format(Locale.US, location.second)),
            )
        },
        confirmButton = {
            if (location != null) TextButton({
                val (lat, lon) = location
                val geo = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                onDismiss()
            }) { Text(stringResource(R.string.location_open_map)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
