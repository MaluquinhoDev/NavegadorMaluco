package com.example.extbrowser

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Tela do controle de atenção: cadastro dos sites monitorados e uso de hoje. */
class AttentionActivity : ComponentActivity() {
    private lateinit var scroll: ScrollView
    private lateinit var domainEt: EditText
    private lateinit var dailyEt: EditText
    private lateinit var sessionEt: EditText
    private lateinit var startEt: EditText
    private lateinit var endEt: EditText
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Attention.init(this)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "Controle de atenção"
            textSize = 22f
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "O tempo só conta enquanto o app está na tela, em uma página do site monitorado."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        domainEt = field(content, "Site", "youtube.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "")
        dailyEt = field(content, "Limite diário (minutos, 0 = sem limite)", "60", InputType.TYPE_CLASS_NUMBER, "60")
        sessionEt = field(content, "Duração da sessão (minutos, 0 = sem limite)", "30", InputType.TYPE_CLASS_NUMBER, "30")
        startEt = timeField(content, "Horário permitido: início (vazio = qualquer horário)")
        endEt = timeField(content, "Horário permitido: fim")

        content.addView(Button(this).apply {
            text = "Adicionar site"
            setOnClickListener { addSite() }
        })

        content.addView(TextView(this).apply {
            text = "Sites monitorados"
            textSize = 18f
            setPadding(0, dp(20), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        val pad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun field(parent: LinearLayout, label: String, hint: String, type: Int, initial: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setPadding(0, dp(8), 0, 0)
        })
        val et = EditText(this).apply {
            this.hint = hint
            inputType = type
            setSingleLine()
            setText(initial)
        }
        parent.addView(et, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return et
    }

    /** Campo de horário: toque abre o seletor de hora (não depende do teclado); "Limpar" esvazia. */
    private fun timeField(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setPadding(0, dp(8), 0, 0)
        })
        val et = EditText(this).apply {
            hint = "--:--"
            isFocusable = false
            isClickable = true
            setSingleLine()
            setOnClickListener { pickTime(this) }
        }
        val clear = Button(this).apply {
            text = "Limpar"
            setOnClickListener { et.setText("") }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(et, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clear, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return et
    }

    private fun pickTime(et: EditText) {
        val current = AttentionLogic.minutesOf(et.text.toString()) ?: (18 * 60)
        TimePickerDialog(
            this,
            { _, hour, minute ->
                et.setText(hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0'))
            },
            current / 60, current % 60, true,
        ).show()
    }

    private fun addSite() {
        val daily = dailyEt.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
        val session = sessionEt.text.toString().trim().ifEmpty { "0" }.toIntOrNull()
        if (daily == null || session == null) {
            Toast.makeText(this, "Os limites precisam ser números.", Toast.LENGTH_LONG).show()
            return
        }
        val domain = domainEt.text.toString()
        val error = Attention.addSite(domain, daily, session, startEt.text.toString(), endEt.text.toString())
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        domainEt.setText("")
        render()
        Toast.makeText(this, "Site adicionado: " + AttentionLogic.normalizeDomain(domain), Toast.LENGTH_SHORT).show()
        scroll.post { scroll.smoothScrollTo(0, list.top) }
    }

    private fun render() {
        list.removeAllViews()
        val sites = Attention.sites()
        if (sites.isEmpty()) {
            list.addView(TextView(this).apply { text = "Nenhum site configurado." })
            return
        }
        for (s in sites) {
            val daily = if (s.dailyMinutes > 0) "${s.dailyMinutes} min/dia" else "sem limite diário"
            val session = if (s.sessionMinutes > 0) {
                "${s.sessionMinutes} min/sessão (pausa de ${AttentionLogic.breakMinutes(s.sessionMinutes)} min)"
            } else {
                "sem limite de sessão"
            }
            val hours = if (s.start.isNotEmpty()) "${s.start}–${s.end}" else "qualquer horário"
            val used = AttentionLogic.formatTime(Attention.usedTodaySeconds(s.id))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            card.addView(TextView(this).apply {
                text = s.domain
                textSize = 16f
            })
            card.addView(TextView(this).apply { text = "$daily · $session · $hours" })
            card.addView(TextView(this).apply { text = "Uso hoje: $used" })
            card.addView(Button(this).apply {
                text = "Remover"
                setOnClickListener { confirmRemove(s) }
            })
            list.addView(card)
        }
    }

    private fun confirmRemove(s: AttentionSite) {
        AlertDialog.Builder(this)
            .setTitle("Remover site")
            .setMessage("Parar de monitorar \"" + s.domain + "\"? O uso registrado dele também será apagado.")
            .setPositiveButton("Remover") { _, _ ->
                Attention.removeSite(s.id)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}
