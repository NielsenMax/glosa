package com.maxi.lector.explain

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

object ClaudeHandoff {
    private const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /**
     * Opens the Claude app with the question already written. The text also goes to the clipboard
     * because the share target may drop it, so the reader can paste it there.
     */
    fun send(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Consulta para Claude", text))

        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent(share).setPackage(CLAUDE_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No encontré la app de Claude; elegí dónde enviarlo", Toast.LENGTH_LONG).show()
            context.startActivity(Intent.createChooser(share, "Preguntar en…"))
        }
    }
}
