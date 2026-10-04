package com.papercut.app.feature.provider

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Close
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
    var confirmDeleteProvider by remember { mutableStateOf(false) }

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
                // connection editor — without this the local presets (Ollama /
                // LM Studio) are unusable: their URL must point at the PC's LAN IP
                var editing by remember { mutableStateOf(false) }
                BentoTile(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatCaption("Connection")
                            Text(
                                p.baseUrl,
                                color = PaperColors.Ink,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                        val editInter = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .pressScale(editInter)
                                .clip(RoundedCornerShape(PaperRadii.pill))
                                .background(PaperColors.TileSunken)
                                .tap(editInter) { editing = !editing }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (editing) "Close" else "Edit",
                                color = PaperColors.Accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (editing) {
                        var name by remember(p.id) { mutableStateOf(p.name) }
                        var url by remember(p.id) { mutableStateOf(p.baseUrl) }
                        Column(
                            modifier = Modifier.padding(top = PaperGap.s),
                            verticalArrangement = Arrangement.spacedBy(PaperGap.s),
                        ) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it },
                                label = { Text("Name") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = url, onValueChange = { url = it },
                                label = { Text("Base URL") }, singleLine = true,
                                placeholder = { Text("https://api.example.com/v1") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s), verticalAlignment = Alignment.CenterVertically) {
                                val saveInter = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .pressScale(saveInter)
                                        .clip(RoundedCornerShape(PaperRadii.pill))
                                        .background(
                                            if (url.startsWith("http")) PaperColors.Accent else PaperColors.TileSunken
                                        )
                                        .then(
                                            if (url.startsWith("http")) Modifier.tap(saveInter) {
                                                vm.saveProvider(
                                                    name.trim().ifBlank { p.name },
                                                    url.trim().trimEnd('/'),
                                                    p.models, // models are managed in their own tile below
                                                )
                                                editing = false
                                            } else Modifier
                                        )
                                        .padding(horizontal = 18.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        "Save",
                                        color = if (url.startsWith("http")) PaperColors.Canvas else PaperColors.InkFaint,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (!p.requiresKey) {
                                    StatCaption("no API key needed")
                                }
                            }
                        }
                    }
                }
            }

            item {
                // models tile — each model is a removable chip, plus an inline
                // add field; editing a comma string in a hidden editor was the
                // complaint, so the list itself IS the editor
                var newModel by remember(p.id) { mutableStateOf("") }
                BentoTile(modifier = Modifier.fillMaxWidth()) {
                    StatCaption("Models — tap one to scan with it")
                    if (p.models.isEmpty()) {
                        StatCaption("add at least one model below")
                    }
                    Column(
                        modifier = Modifier.padding(top = PaperGap.s),
                        verticalArrangement = Arrangement.spacedBy(PaperGap.xs),
                    ) {
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
                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    model,
                                    color = if (isActive) PaperColors.Accent else PaperColors.Ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                if (isActive) {
                                    Text("ACTIVE", color = PaperColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                val rmInter = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(PaperRadii.pill))
                                        .tap(rmInter) {
                                            val remaining = p.models - model
                                            vm.saveProvider(p.name, p.baseUrl, remaining)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Close, "Remove $model",
                                        tint = PaperColors.InkFaint, modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        // inline add-model field
                        OutlinedTextField(
                            value = newModel,
                            onValueChange = { newModel = it },
                            label = { Text("Add model") },
                            placeholder = { Text("model id — e.g. gemini-2.5-flash") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (newModel.isNotBlank()) {
                                    val addInter = remember { MutableInteractionSource() }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(PaperRadii.pill))
                                            .background(PaperColors.AccentSoft)
                                            .tap(addInter) {
                                                val m = newModel.trim()
                                                if (m.isNotEmpty() && m !in p.models) {
                                                    vm.saveProvider(p.name, p.baseUrl, p.models + m)
                                                }
                                                newModel = ""
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text("Add", color = PaperColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            },
                        )
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
                            .tap(delInter) { confirmDeleteProvider = true }
                            .padding(PaperGap.m),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("Delete provider", color = PaperColors.Error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (confirmDeleteProvider) {
        com.papercut.app.core.design.ConfirmSheet(
            title = "Delete “${p.name}”?",
            message = "Its ${keys.size} stored key(s) are erased from encrypted storage.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteProvider(); onBack() },
            onDismiss = { confirmDeleteProvider = false },
        )
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
        cooling -> "cooling — retry in ${(cooldownLeftMs + 999) / 1000}s"
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

/** Create-custom-provider form: name, base URL, models, and the first API key
 *  in one step — a provider is usable the moment it's created. */
@Composable
private fun NewProviderScreen(onBack: () -> Unit) {
    val settings: SettingsViewModel = appViewModel { c -> SettingsViewModel(c) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var localServer by remember { mutableStateOf(false) }

    fun valid() = name.isNotBlank() && baseUrl.startsWith("http")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas)
            .padding(PaperGap.l),
        verticalArrangement = Arrangement.spacedBy(PaperGap.m),
    ) {
        val backInter = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier.size(40.dp).pressScale(backInter)
                .clip(RoundedCornerShape(PaperRadii.small))
                .background(PaperColors.Tile)
                .tap(backInter, onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = PaperColors.Ink,
                modifier = Modifier.size(18.dp))
        }
        Text(
            "New provider",
            style = MaterialTheme.typography.titleLarge,
            color = PaperColors.Ink,
            fontWeight = FontWeight.Bold,
        )
        StatCaption("Any OpenAI-compatible server — OpenRouter, Groq, Ollama, LM Studio…")

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") },
            placeholder = { Text("e.g. OpenRouter") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://openrouter.ai/api/v1") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = models, onValueChange = { models = it },
            label = { Text("Models") },
            placeholder = { Text("model-id-1, model-id-2") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Local server (no API key)", color = PaperColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                StatCaption("Ollama, LM Studio and friends")
            }
            com.papercut.app.core.design.SmoothSwitch(checked = localServer, onCheckedChange = { localServer = it })
        }
        if (localServer) {
            StatCaption("Keys stay empty — Papercut calls the server without Authorization.")
        } else {
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API key") },
                placeholder = { Text("sk-… (paste here)") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            StatCaption("Stored encrypted on this device — never written to your scan folder.")
        }

        Text(
            "Papercut calls <base URL>/chat/completions with vision content parts.",
            fontSize = 12.sp,
            color = PaperColors.InkSecondary,
        )

        val saveInter = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(saveInter, pressedScale = 0.97f)
                .background(
                    if (valid()) PaperColors.Accent else PaperColors.TileSunken,
                    RoundedCornerShape(PaperRadii.pill),
                )
                .then(
                    if (valid())
                        Modifier.tap(saveInter) {
                            settings.addCustomProvider(
                                name.trim(),
                                baseUrl.trim().trimEnd('/'),
                                models.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                if (localServer) null else apiKey,
                            )
                            onBack()
                        }
                    else Modifier
                )
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "Create provider",
                color = if (valid()) PaperColors.Canvas else PaperColors.InkFaint,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
