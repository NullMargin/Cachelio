package com.holeintimes.vbrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.holeintimes.vbrowser.domain.AppLanguage
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenAbout: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onLanguageChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs by container.prefs.preferences.collectAsStateWithLifecycle(
        initialValue = com.holeintimes.vbrowser.domain.UserPreferences()
    )
    val scope = rememberCoroutineScope()
    var showLanguage by remember { mutableStateOf(false) }
    var showPrivacyPassword by remember { mutableStateOf(false) }
    var privacyPassword by remember { mutableStateOf("") }
    var privacyPasswordError by remember { mutableStateOf(false) }
    var checkingPrivacyPassword by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_general)) {
            PrefRow(title = stringResource(R.string.language), subtitle = languageLabel(prefs.language)) {
                showLanguage = true
            }
            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.web_page_dark_mode),
                subtitle = stringResource(R.string.web_page_dark_mode_desc),
                checked = prefs.forceWebDarkMode,
                onChecked = { scope.launch { container.prefs.setForceWebDarkMode(it) } }
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_browser)) {
            SwitchRow(
                title = stringResource(R.string.auto_download),
                subtitle = stringResource(R.string.auto_download_desc),
                checked = prefs.autoDownload,
                onChecked = { scope.launch { container.prefs.setAutoDownload(it) } }
            )
            HorizontalDivider()
            SwitchRow(
                title = stringResource(R.string.show_found_list),
                subtitle = null,
                checked = prefs.showFoundList,
                onChecked = { scope.launch { container.prefs.setShowFoundList(it) } }
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_files)) {
            SwitchRow(
                title = stringResource(R.string.auto_play_next),
                subtitle = stringResource(R.string.auto_play_next_desc),
                checked = prefs.autoPlayNext,
                onChecked = { scope.launch { container.prefs.setAutoPlayNext(it) } }
            )
            HorizontalDivider()
            PrefRow(
                title = stringResource(R.string.privacy_mode),
                subtitle = stringResource(R.string.privacy_mode_desc),
                onClick = {
                    scope.launch {
                        if (container.privacySession.isUnlocked.value ||
                            !container.prefs.isPrivacyPasswordSet()
                        ) {
                            onOpenPrivacy()
                        } else {
                            privacyPassword = ""
                            privacyPasswordError = false
                            showPrivacyPassword = true
                        }
                    }
                }
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            PrefRow(title = stringResource(R.string.nav_about), subtitle = null, onClick = onOpenAbout)
        }
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    LanguageOption(stringResource(R.string.lang_system), prefs.language == AppLanguage.System) {
                        scope.launch {
                            container.prefs.setLanguage(AppLanguage.System)
                            showLanguage = false
                            onLanguageChanged()
                        }
                    }
                    LanguageOption(stringResource(R.string.lang_en), prefs.language == AppLanguage.English) {
                        scope.launch {
                            container.prefs.setLanguage(AppLanguage.English)
                            showLanguage = false
                            onLanguageChanged()
                        }
                    }
                    LanguageOption(stringResource(R.string.lang_zh), prefs.language == AppLanguage.Chinese) {
                        scope.launch {
                            container.prefs.setLanguage(AppLanguage.Chinese)
                            showLanguage = false
                            onLanguageChanged()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguage = false }) { Text(stringResource(R.string.clear)) }
            }
        )
    }

    if (showPrivacyPassword) {
        AlertDialog(
            onDismissRequest = {
                if (!checkingPrivacyPassword) showPrivacyPassword = false
            },
            title = { Text(stringResource(R.string.privacy_password_required)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = privacyPassword,
                        onValueChange = {
                            privacyPassword = it
                            privacyPasswordError = false
                        },
                        label = { Text(stringResource(R.string.privacy_password)) },
                        singleLine = true,
                        isError = privacyPasswordError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (privacyPasswordError) {
                        Text(
                            stringResource(R.string.incorrect_password),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !checkingPrivacyPassword && privacyPassword.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            checkingPrivacyPassword = true
                            val valid = container.prefs.verifyPrivacyPassword(privacyPassword)
                            checkingPrivacyPassword = false
                            if (valid) {
                                showPrivacyPassword = false
                                onOpenPrivacy()
                            } else {
                                privacyPasswordError = true
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.unlock))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !checkingPrivacyPassword,
                    onClick = { showPrivacyPassword = false }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun PrefRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun languageLabel(lang: AppLanguage): String = when (lang) {
    AppLanguage.System -> stringResource(R.string.lang_system)
    AppLanguage.English -> stringResource(R.string.lang_en)
    AppLanguage.Chinese -> stringResource(R.string.lang_zh)
}
