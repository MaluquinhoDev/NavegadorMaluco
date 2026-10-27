package com.example.extbrowser

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/** Tela para importar, ligar/desligar e remover extensões. */
class ExtensionsActivity : ComponentActivity() {
    private lateinit var manager: ExtensionManager
    private lateinit var list: LinearLayout

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runImport { manager.importFromZip(uri) }
    }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runImport { manager.importFromTree(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = ExtensionManager(this)

        val title = TextView(this).apply {
            text = "Extensões"
            textSize = 22f
            setPadding(0, 0, 0, dp(8))
        }
        val btnZip = Button(this).apply {
            text = "Importar .zip"
            setOnClickListener {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }
        val btnDir = Button(this).apply {
            text = "Importar pasta"
            setOnClickListener { pickTree.launch(null) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnZip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btnDir, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val btnAttention = Button(this).apply {
            text = "Controle de atenção"
            setOnClickListener {
                startActivity(Intent(this@ExtensionsActivity, AttentionActivity::class.java).apply {
                    putExtra("attention_current_page", intent.getStringExtra("attention_current_page"))
                })
            }
        }
        val hint = TextView(this).apply {
            text = "Ao voltar para o navegador, a página recarrega para aplicar as mudanças."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(buttons)
            addView(btnAttention)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        hint.alpha = 0.82f
        list.setPadding(0, dp(8), 0, dp(8))
        setContentView(root)

        val pad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }

        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = manager.entries()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nenhuma extensão instalada.\nImporte um arquivo ZIP ou uma pasta para começar."
                textSize = 15f
                setPadding(dp(16), dp(24), dp(16), dp(24))
                background = cardBackground()
            })
        }
        for (e in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = cardBackground()
            }
            if (e.error != null) {
                val msg = TextView(this).apply {
                    text = e.name + " · erro: " + e.error
                    setTextColor(if (isNightMode()) Color.rgb(255, 145, 145) else Color.rgb(172, 24, 24))
                    textSize = 14f
                }
                row.addView(msg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            } else {
                val sw = Switch(this).apply {
                    text = e.name
                    isChecked = e.enabled
                    minHeight = dp(48)
                    setOnCheckedChangeListener { _, on ->
                        manager.setEnabled(e.id, on)
                        markChanged()
                    }
                }
                row.addView(sw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            val del = Button(this).apply {
                text = "Remover"
                contentDescription = "Remover extensão ${e.name}"
                minHeight = dp(48)
                setOnClickListener { confirmRemove(e) }
            }
            row.addView(del, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun cardBackground() = GradientDrawable().apply {
        setColor(if (isNightMode()) Color.rgb(39, 43, 52) else Color.rgb(245, 247, 251))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), if (isNightMode()) Color.rgb(69, 74, 84) else Color.rgb(221, 227, 235))
    }

    private fun confirmRemove(e: ExtEntry) {
        AlertDialog.Builder(this)
            .setTitle("Remover extensão")
            .setMessage("Remover \"" + e.name + "\"? Os dados salvos por ela também serão apagados.")
            .setPositiveButton("Remover") { _, _ ->
                try {
                    manager.remove(e.id)
                    markChanged()
                } catch (ex: Exception) {
                    Toast.makeText(this, "Falha: " + (ex.message ?: ex.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun runImport(block: () -> String) {
        Toast.makeText(this, "Importando...", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching(block)
            runOnUiThread {
                result.onSuccess { id ->
                    markChanged()
                    Toast.makeText(this, "Importada: $id", Toast.LENGTH_SHORT).show()
                }.onFailure { ex ->
                    Toast.makeText(this, "Falha: " + (ex.message ?: ex.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun markChanged() {
        setResult(RESULT_OK)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}
