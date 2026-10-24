package com.example.extbrowser

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
            addView(btnZip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(btnDir, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val btnAttention = Button(this).apply {
            text = "Controle de atenção"
            setOnClickListener { startActivity(Intent(this@ExtensionsActivity, AttentionActivity::class.java)) }
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
            list.addView(TextView(this).apply { text = "Nenhuma extensão instalada." })
        }
        for (e in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            if (e.error != null) {
                val msg = TextView(this).apply {
                    text = e.id + ": " + e.error
                    setTextColor(Color.RED)
                }
                row.addView(msg, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            } else {
                val sw = Switch(this).apply {
                    text = e.name
                    isChecked = e.enabled
                    setOnCheckedChangeListener { _, on ->
                        manager.setEnabled(e.id, on)
                        markChanged()
                    }
                }
                row.addView(sw, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            val del = Button(this).apply {
                text = "Remover"
                setOnClickListener { confirmRemove(e) }
            }
            row.addView(del)
            list.addView(row)
        }
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
