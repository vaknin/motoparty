package com.kivan.motoparty.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kivan.motoparty.CallUi
import com.kivan.motoparty.UiAction

/**
 * The rider's cellular call, in place of what is playing (2026-10-02): who is calling, and the
 * buttons for a gloved thumb — Decline (red) and Answer (green) while it rings, End once on the
 * call — each at least [CALL_BUTTON_MIN] tall. The music line says what the passenger hears,
 * because that is the one thing the rider cannot check under the helmet.
 */
@Composable
fun CallCard(c: CallUi, clientName: String?, onAction: (UiAction) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun act(a: UiAction) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onAction(a)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            // The landscape middle is short: the name shrinks before the buttons do.
            val roomy = maxHeight >= 360.dp
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (roomy) 12.dp else 6.dp),
            ) {
                Text(
                    if (c.onCall) "On a call" else "Incoming call",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (c.onCall) Palette.Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.name?.let {
                    Text(
                        it,
                        style = if (roomy) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!c.onCall) VoiceLine(c.voice)
                Text(
                    "Your music is muted · ${clientName ?: "the passenger"} keeps listening",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                when {
                    !c.canAnswer -> Text(
                        "Allow “Answer calls” to answer from here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Waiting,
                        textAlign = TextAlign.Center,
                    )
                    c.onCall -> BigButton("End call", Palette.TalkOpen, Palette.OnTalkOpen, Modifier.fillMaxWidth()) { act(UiAction.EndCall) }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Answer on the right, where the phone's own screen has it.
                        BigButton("Decline", Palette.TalkOpen, Palette.OnTalkOpen, Modifier.weight(1f)) { act(UiAction.DeclineCall) }
                        BigButton("Answer", Palette.Good, OnGood, Modifier.weight(1f)) { act(UiAction.AnswerCall) }
                    }
                }
            }
        }
    }
}

/** What saying "answer" can do right now. */
@Composable
private fun VoiceLine(voice: CallUi.Voice) {
    val (text, color) = when (voice) {
        CallUi.Voice.LISTENING -> "Say “answer” or “decline”" to MaterialTheme.colorScheme.onSurface
        CallUi.Voice.NO_LARK -> "Say answer needs the Lark" to Palette.Waiting
        CallUi.Voice.OFF -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (voice == CallUi.Voice.LISTENING) Icons.Mic else Icons.MicOff, null, Modifier.size(22.dp), tint = color)
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun BigButton(label: String, color: Color, onColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = CALL_BUTTON_MIN),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = onColor),
    ) {
        Text(label, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Glove-sized: a third bigger than the play button's 72 dp minimum on the same screen. */
private val CALL_BUTTON_MIN = 104.dp

/** Text on [Palette.Good], dark like [Palette.OnTalk] (white on that green is about 1.9:1). */
private val OnGood = Color(0xFF052E16)
