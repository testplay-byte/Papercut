package com.papercut.app.feature.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papercut.app.core.data.model.ScanMode
import com.papercut.app.core.design.BentoTile
import com.papercut.app.core.design.PaperColors
import com.papercut.app.core.design.PaperGap
import com.papercut.app.core.design.PaperRadii
import com.papercut.app.core.design.SegmentedPill
import com.papercut.app.core.design.StatCaption
import com.papercut.app.core.design.pressScale
import com.papercut.app.core.design.tap
import com.papercut.app.di.appViewModel
import com.papercut.app.feature.settings.SettingsViewModel

/**
 * Prompt editor for Text/Notes modes.
 *
 * Critical fix vs old app: the editor initializes ONCE per mode via
 * rememberSaveable keyed on mode — the old version re-keyed on the settings
 * object, so any background settings write silently wiped typed text.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = appViewModel { c -> SettingsViewModel(c) }
    val settings by vm.settings.collectAsState()

    var mode by rememberSaveable { mutableStateOf(ScanMode.TEXT) }

    // init once when the mode changes; NOT reactive to settings updates
    var draft by rememberSaveable(mode) {
        mutableStateOf(settings.prompts[mode.name] ?: com.papercut.app.core.domain.PromptTemplates.defaultFor(mode))
    }

    val savedMatchesDraft = settings.prompts[mode.name] == draft

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColors.Canvas)
            .padding(PaperGap.l)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(PaperGap.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PaperGap.m)) {
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
            Text("Prompts", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = PaperColors.Ink, fontWeight = FontWeight.Bold)
        }

        SegmentedPill(
            options = listOf(ScanMode.TEXT to "Text twin", ScanMode.NOTES to "Notes"),
            selected = mode,
            onSelect = { mode = it },
            modifier = Modifier.fillMaxWidth(),
        )

        BentoTile(modifier = Modifier.fillMaxWidth()) {
            StatCaption(if (mode == ScanMode.TEXT) "Full-page digital twin (HTML + MathJax)" else "Structured study notes")
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                minLines = 10,
                maxLines = 20,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PaperGap.s),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PaperGap.s),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatCaption("${draft.length} chars")
                Row(horizontalArrangement = Arrangement.spacedBy(PaperGap.s)) {
                    val resetInter = remember { MutableInteractionSource() }
                    Text(
                        "Reset to default",
                        color = PaperColors.InkSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .pressScale(resetInter)
                            .tap(resetInter) {
                                vm.resetPrompt(mode)
                                draft = com.papercut.app.core.domain.PromptTemplates.defaultFor(mode)
                            }
                            .padding(6.dp),
                    )
                    val saveInter = remember { MutableInteractionSource() }
                    Text(
                        "Save",
                        color = if (savedMatchesDraft) PaperColors.InkFaint else PaperColors.Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .pressScale(saveInter)
                            .then(if (!savedMatchesDraft) Modifier.tap(saveInter) { vm.savePrompt(mode, draft) } else Modifier)
                            .padding(6.dp),
                    )
                }
            }
        }

        if (!savedMatchesDraft) {
            Text("Unsaved changes", color = PaperColors.Warning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
