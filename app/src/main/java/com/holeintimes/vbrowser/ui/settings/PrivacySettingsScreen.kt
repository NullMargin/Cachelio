package com.holeintimes.vbrowser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.holeintimes.vbrowser.AppContainer
import com.holeintimes.vbrowser.R
import kotlinx.coroutines.launch

@Composable
fun PrivacySettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val unlocked by container.privacySession.isUnlocked.collectAsStateWithLifecycle()
    val hasPassword by container.prefs.hasPrivacyPassword.collectAsStateWithLifecycle(false)
    var showPasswordEditor by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.privacy_session_unlock),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.privacy_session_unlock_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = unlocked,
                    onCheckedChange = {
                        if (it) container.privacySession.unlock()
                        else container.privacySession.lock()
                    }
                )
            }
        }

        Text(
            text = stringResource(R.string.privacy_password_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            TextButton(
                onClick = { showPasswordEditor = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    if (hasPassword) {
                        stringResource(R.string.change_privacy_password)
                    } else {
                        stringResource(R.string.set_privacy_password)
                    }
                )
            }
        }
    }

    if (showPasswordEditor) {
        PrivacyPasswordEditorDialog(
            container = container,
            changingExisting = hasPassword,
            onDismiss = { showPasswordEditor = false }
        )
    }
}

@Composable
private fun PrivacyPasswordEditorDialog(
    container: AppContainer,
    changingExisting: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                if (changingExisting) {
                    stringResource(R.string.change_privacy_password)
                } else {
                    stringResource(R.string.set_privacy_password)
                }
            )
        },
        text = {
            Column {
                if (changingExisting) {
                    PasswordField(
                        value = current,
                        onValueChange = {
                            current = it
                            errorRes = null
                        },
                        label = stringResource(R.string.current_password)
                    )
                }
                PasswordField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        errorRes = null
                    },
                    label = stringResource(R.string.new_password)
                )
                PasswordField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        errorRes = null
                    },
                    label = stringResource(R.string.confirm_password)
                )
                errorRes?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    when {
                        newPassword.length < 4 ->
                            errorRes = R.string.password_too_short
                        newPassword != confirmation ->
                            errorRes = R.string.passwords_do_not_match
                        else -> scope.launch {
                            saving = true
                            if (changingExisting &&
                                !container.prefs.verifyPrivacyPassword(current)
                            ) {
                                errorRes = R.string.incorrect_password
                                saving = false
                                return@launch
                            }
                            container.prefs.setPrivacyPassword(newPassword)
                            saving = false
                            onDismiss()
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}
