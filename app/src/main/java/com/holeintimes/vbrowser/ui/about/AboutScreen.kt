package com.holeintimes.vbrowser.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.holeintimes.vbrowser.BuildConfig
import com.holeintimes.vbrowser.R

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val email = stringResource(R.string.about_email)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.our_site_name), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.version, BuildConfig.VERSION_NAME))
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = email,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("email", email))
                    Toast.makeText(
                        context,
                        context.getString(R.string.email_copied_no_app),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
