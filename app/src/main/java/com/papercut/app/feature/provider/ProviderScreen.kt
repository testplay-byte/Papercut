package com.papercut.app.feature.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.core.data.model.KeyEntry
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel
import com.papercut.app.feature.settings.ProviderViewModel
import com.papercut.app.feature.settings.SettingsViewModel

/**
 * Provider detail: base URL, models, keys (encrypted, masked) with usage +
 * cooldown state visible — the "much better rotation" the user asked for is
 * shown here, not hidden: you see which key is benched and why.
 *
 * providerId == "__new__" turns this into the create-custom-provider form.
 */
@Composable
fun ProviderScreen(providerId: String, onBack: () -> Unit) {
    if (providerId == "__new__") {
        NewProviderScreen(onBack)
        return
    }
    val vm: ProviderViewModel = appViewModel { c -> ProviderViewModel(c, providerId) }
    val provider by vm.provider.collectAsState()
    val keys by vm.keys.collectAsState()
    val settings: SettingsViewModel = appViewModel { c -> SettingsViewModel(c) }
    val active by settings.settings.collectAsState()

    var showAddKey by remember { mutableStateOf(false) }

    val p = provider ?: run {
        // deleted while open
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaperGap.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaperGap.m),
        ) {
            val backInter = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .pressScale(backInter)
                    .background(PaperColors.Tile, RoundedCornerShape(PaperRadii.small))
                    .tap(backInter, onBack)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.Ink, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(p.name, style = MaterialTheme.typography.titleLarge, color = PaperColors.Ink, fontWeight = FontWeight.Bold)
                StatCaption(p.baseUrl)
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = PaperGap.l, vertical = PaperGap.s),
            verticalArrangement = Arrangement.spacedBy(PaperGap.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                // model picker
                BentoTile(modifier = Modifier.fillMaxWidth()) {
                    StatCaption("Model — tap to select")
                    Column(modifier = Modifier.padding(top = PaperGap.s), verticalArrangement = Arrangement.spacedBy(PaperGap.xs)) {
                        if (p.models.isEmpty()) {
                            StatCaption("no models defined — edit below")
                        }
                        p.models.forEach { model ->
                            val isActive = p.id == active.activeProviderId && model == active.activeModel
                            val inter = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressScale(inter)
                                    .background(
                                        if (isActive) PaperColors.AccentSoft else PaperColors.TileSunken,
                                        RoundedCornerShape(PaperRadii.small),
                                    )
                                    .tap(inter) { vm.selectModel(model) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(model, color = if (isActive) PaperColors.Accent else PaperColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            item {
                StatCaption("API keys — stored encrypted on this device")
            }

            items(keys, key = { it.id }) { entry ->
                KeyTile(
                    entry = entry,
                    masked = vm.masked(entry.id),
                    cooldownLeftMs = vm.cooldownLeft(entry),
                    onRemove = { vm.removeKey(entry.id) },
                )
            }

            item {
                val addInter = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(addInter)
                        .background(PaperColors.TileSunken, RoundedCornerShape(PaperRadii.tile))
                        .tap(addInter) { showAddKey = true }
                        .padding(PaperGap.m),
                ) { Text("＋ Add API key", color = PaperColors.InkSecondary, fontSize = 14.sp) }
            }

            if (!p.isBuiltIn) {
                item {
                    val delInter = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(delInter)
                            .background(PaperColors.AccentSoft, RoundedCornerShape(PaperRadii.tile))
                            .tap(delInter) { vm.deleteProvider(); onBack() }
                            .padding(PaperGap.m),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("Delete provider", color = PaperColors.Error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showAddKey) {
        var label by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddKey = false },
            title = { Text("Add API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name (e.g. personal)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Key") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Stored encrypted via the Android keystore. Never written to your scan folder.", fontSize = 12.sp, color = PaperColors.InkSecondary)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.addKey(label, secret); showAddKey = false },
                    enabled = secret.isNotBlank(),
                ) { Text("Save key") }
            },
            dismissButton = { TextButton(onClick = { showAddKey = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyTile(entry: KeyEntry, masked: String, cooldownLeftMs: Long, onRemove: () -> Unit) {
    val cooling = cooldownLeftMs > 0
    val state = when {
        cooling -> "benched ${cooldownLeftMs / 1000}s"
        entry.totalUses > 0 -> "used ${entry.totalUses}×"
        else -> "unused"
    }
    BentoTile(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium, color = PaperColors.Ink)
                Text(
                    masked,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = PaperColors.InkSecondary,
                )
                StatCaption(state)
            }
            val remInter = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressScale(remInter)
                    .background(PaperColors.TileSunken, RoundedCornerShape(PaperRadii.pill))
                    .tap(remInter, onRemove)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("Remove", color = PaperColors.Error, fontSize = 12.sp) }
        }
    }
}

/** Create-custom-provider form: name, base URL, models (comma-separated). */
@Composable
private fun NewProviderScreen(onBack: () -> Unit) {
    val settings: SettingsViewModel = appViewModel { c -> SettingsViewModel(c) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas)
            .padding(PaperGap.l),
        verticalArrangement = Arrangement.spacedBy(PaperGap.m),
    ) {
        StatCaption("Add a custom provider (any OpenAI-compatible server)")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name — e.g. My VPS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, placeholder = { Text("http://192.168.1.20:11434/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = models, onValueChange = { models = it }, label = { Text("Models, comma separated") }, modifier = Modifier.fillMaxWidth())
        Text(
            "Papercut calls <base URL>/chat/completions with vision content parts — works with OpenRouter, Together, DeepSeek, Ollama, LM Studio and similar.",
            fontSize = 12.sp,
            color = PaperColors.InkSecondary,
        )
        val saveInter = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(saveInter, pressedScale = 0.97f)
                .background(
                    if (name.isNotBlank() && baseUrl.length > 8) PaperColors.Accent else PaperColors.InkFaint,
                    RoundedCornerShape(PaperRadii.pill),
                )
                .then(
                    if (name.isNotBlank() && baseUrl.length > 8)
                        Modifier.tap(saveInter) {
                            settings.addCustomProvider(
                                name.trim(),
                                baseUrl.trim(),
                                models.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            )
                            onBack()
                        }
                    else Modifier
                )
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("Create provider", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
