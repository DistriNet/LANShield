package org.distrinet.lanshield.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.distrinet.lanshield.PACKAGE_NAME_ROOT
import org.distrinet.lanshield.PACKAGE_NAME_SYSTEM
import org.distrinet.lanshield.PACKAGE_NAME_UNKNOWN
import org.distrinet.lanshield.Policy
import org.distrinet.lanshield.R
import org.distrinet.lanshield.database.model.LANFlow
import org.distrinet.lanshield.ui.LANShieldIcons
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun LanShieldInfoDialog(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    title: @Composable() (() -> Unit),
    text: @Composable() (() -> Unit)?
) {
    AlertDialog(
        modifier = modifier,
        icon = {
            Icon(LANShieldIcons.Info, contentDescription = stringResource(R.string.info))
        },
        title = title,
        text = text,
        onDismissRequest = onDismiss,
        dismissButton = {},
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.dismiss))
            }
        },
    )
}

@Composable
fun PolicyFilterSegmentedButtonRow(modifier: Modifier = Modifier,
                                   selectedPolicy: Policy,
                                   onSelectedPolicyChanged: (Policy) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        Policy.entries.forEachIndexed { index, policy ->
            SegmentedButton(
                selected = selectedPolicy == policy,
                onClick = { onSelectedPolicyChanged(policy) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = Policy.entries.size
                ),
                label = { PolicyFilterName(policy) }
            )
        }
    }
}

fun generateFilename(): String {
    val currentDate = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd")
    val formattedDate = currentDate.format(formatter)
    return "LANTraffic_$formattedDate.json"
}

@Composable
fun ExportFile(context: Context,
              allFlows: List<LANFlow>){
    val fileToShare = File(context.cacheDir, generateFilename())
    val array = JSONArray()
    for (flow in allFlows){
        array.put(flow.toJSON())
    }
    val json = JSONObject()
    json.put("device_sdk", Build.VERSION.SDK_INT)
    json.put("model", Build.MODEL)
    json.put("flows",array)
    FileOutputStream(fileToShare).use {
        it.write(json.toString().toByteArray())
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        fileToShare
    )
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_STREAM, uri)
        type = "application/json"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
}

@Composable
fun PolicyFilterName(policy: Policy) {
    return when(policy) {
        Policy.DEFAULT -> Text(text = stringResource(R.string.all))
        Policy.BLOCK -> Text(text = stringResource(R.string.blocked))
        Policy.ALLOW -> Text(text = stringResource(R.string.allowed))

    }
}

@Composable
private fun UnknownPackageIcon(modifier: Modifier = Modifier, packageName: String) {
    when(packageName) {
        PACKAGE_NAME_ROOT -> Icon(LANShieldIcons.Tag, PACKAGE_NAME_ROOT, modifier = modifier)
        PACKAGE_NAME_SYSTEM -> Icon(LANShieldIcons.Android, PACKAGE_NAME_SYSTEM, modifier = modifier)
        else -> Icon(LANShieldIcons.QuestionMark,
            stringResource(R.string.unknown), modifier = modifier)
    }
}

@Composable
fun PackageIcon(modifier: Modifier = Modifier, packageName: String) {
    val coroutineScope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var bitmapLookupFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if(bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = stringResource(R.string.logo),
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
    else {
        UnknownPackageIcon(modifier = modifier, packageName = packageName)
        if(!bitmapLookupFailed and !packageName.contentEquals(PACKAGE_NAME_UNKNOWN)) {
            LaunchedEffect(packageName) {
                coroutineScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        try {
                            context.packageManager.getApplicationIcon(packageName)
                                .toBitmap(config = Bitmap.Config.ARGB_8888).asImageBitmap()
                        }
                        catch (_: PackageManager.NameNotFoundException) {
                            null
                        }

                    }
                    if(icon != null) {
                        bitmap = icon
                    }
                    else bitmapLookupFailed = true

                }
            }
        }
    }
}

@Composable
fun GrantAppUsagePermissionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        icon = {
            Icon(LANShieldIcons.Info, contentDescription = stringResource(id = R.string.info))
        },
        title = { Text(text = stringResource(R.string.sharing_app_usage)) },
        text = {
            Text(text = stringResource(R.string.settings_share_app_usage_info_dialog))
        },
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                }
            ) {
                Text(text = stringResource(id = R.string.dismiss))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text(text = stringResource(id = R.string.next))
            }
        },
    )
}