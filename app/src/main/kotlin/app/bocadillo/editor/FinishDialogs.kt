package app.bocadillo.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.bocadillo.R
import app.bocadillo.ui.theme.Classic
import kotlin.math.roundToInt

private val Head = Color(0xFF262626)
private val Light = Color(0xFFFAFAFA)
private val Dark = Color(0xFF212121)

/** Classic dialog: dark header with title and text, light rows below (like Set Size). */
@Composable
private fun ClassicChoiceDialog(title: String, text: String?, onDismiss: () -> Unit, rows: List<Pair<String, () -> Unit>>, danger: Int = -1) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(Head).padding(16.dp)) {
                Text(title, color = Classic.Text, fontSize = 20.sp)
                if (text != null) Text(text, color = Classic.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            rows.forEachIndexed { i, (label, action) ->
                if (i > 0) HorizontalDivider(color = Color(0xFFE0E0E0))
                Text(
                    label, color = if (i == danger) Color(0xFFC62828) else Dark, fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth().background(Light).clickable(role = Role.Button) { action() }.padding(horizontal = 18.dp, vertical = 15.dp),
                )
            }
        }
    }
}

/**
 * «Finalizar» (green check of the editor, or Back with unsaved changes): Guardar imagen,
 * Guardar proyecto, Continuar editando, Salir sin guardar.
 */
@Composable
fun FinishDialog(pending: Boolean, onSaveImage: () -> Unit, onSaveProject: () -> Unit, onContinue: () -> Unit, onExit: () -> Unit) {
    ClassicChoiceDialog(
        stringResource(R.string.finish_title),
        stringResource(if (pending) R.string.finish_text_pending else R.string.finish_text_saved),
        onContinue,
        listOf(
            stringResource(R.string.finish_save_image) to onSaveImage,
            stringResource(R.string.finish_save_project) to onSaveProject,
            stringResource(R.string.finish_continue) to onContinue,
            stringResource(R.string.finish_exit_without_saving) to onExit,
        ),
        danger = 3,
    )
}

/** Confirmation before throwing changes away. */
@Composable
fun DiscardConfirmDialog(draft: Boolean, onDiscard: () -> Unit, onCancel: () -> Unit) {
    ClassicChoiceDialog(
        stringResource(R.string.discard_title),
        stringResource(if (draft) R.string.discard_text_draft else R.string.discard_text),
        onCancel,
        listOf(stringResource(R.string.discard_confirm) to onDiscard, stringResource(R.string.cancel) to onCancel),
        danger = 0,
    )
}

/** After «Guardar imagen» succeeded: where it is, then go on editing or leave. */
@Composable
fun ImageSavedDialog(where: String, onContinue: () -> Unit, onExit: () -> Unit) {
    ClassicChoiceDialog(
        stringResource(R.string.image_saved_title), where, onContinue,
        listOf(stringResource(R.string.finish_continue) to onContinue, stringResource(R.string.image_saved_exit) to onExit),
    )
}

/**
 * «Guardar imagen»: file name, PNG (keeps transparency, no compression loss) or JPG with its
 * quality, and where (the phone's gallery, or a place chosen with the system file picker).
 */
@Composable
fun SaveImageDialog(
    suggestedName: String,
    hasTransparency: Boolean,
    onSave: (name: String, png: Boolean, quality: Int, choosePlace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var png by rememberSaveable { mutableStateOf(hasTransparency) }
    var quality by rememberSaveable { mutableFloatStateOf(90f) }
    var choose by rememberSaveable { mutableStateOf(false) }
    val valid = name.isNotBlank()
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(Head).padding(16.dp)) {
                Text(stringResource(R.string.save_image_title), color = Classic.Text, fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    name, { name = it.take(80) }, label = { Text(stringResource(R.string.save_image_name)) }, singleLine = true,
                    suffix = { Text(if (png) ".png" else ".jpg", color = Classic.TextDim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Classic.Text, unfocusedTextColor = Classic.Text, focusedLabelColor = Classic.TextDim, unfocusedLabelColor = Classic.TextDim),
                    modifier = Modifier.fillMaxWidth(),
                )
                Section(stringResource(R.string.save_image_format))
                Choice(stringResource(R.string.save_image_png), stringResource(R.string.save_image_png_note), png) { png = true }
                Choice(stringResource(R.string.save_image_jpg), stringResource(R.string.save_image_jpg_note), !png) { png = false }
                if (!png) {
                    Text(stringResource(R.string.save_image_quality, quality.roundToInt()), color = Classic.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    Slider(quality, { quality = it }, valueRange = 50f..100f, steps = 9)
                }
                Section(stringResource(R.string.save_image_location))
                Choice(stringResource(R.string.save_image_gallery), stringResource(R.string.save_image_gallery_note), !choose) { choose = false }
                Choice(stringResource(R.string.save_image_choose), stringResource(R.string.save_image_choose_note), choose) { choose = true }
            }
            Row(Modifier.fillMaxWidth().background(Light)) {
                Text(
                    stringResource(R.string.cancel), color = Dark, fontSize = 18.sp,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onDismiss).padding(16.dp),
                )
                Text(
                    stringResource(R.string.save_image_save), color = if (valid) Color(0xFF1565C0) else Color(0xFFAAAAAA), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable(enabled = valid, role = Role.Button) { onSave(name.trim(), png, quality.roundToInt(), choose) }.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, color = Classic.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Choice(label: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF42A5F5), unselectedColor = Classic.TextDim))
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, color = Classic.Text, fontSize = 16.sp)
            Text(note, color = Classic.TextDim, fontSize = 12.sp)
        }
    }
}
