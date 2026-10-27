package com.example.extbrowser

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.View
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
    private lateinit var taskList: LinearLayout
    private lateinit var rewardStatus: TextView
    private lateinit var taskTitle: EditText
    private lateinit var taskMinutes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Attention.init(this)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "Controle de atenção"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = "Conta o uso do site monitorado na tela e a reprodução de mídia detectada, inclusive em segundo plano."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        content.addView(TextView(this).apply {
            text = "Checklist e recompensas"
            textSize = 18f
            setPadding(0, dp(8), 0, dp(8))
        })
        rewardStatus = TextView(this).apply {
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = panelBackground()
        }
        content.addView(rewardStatus)
        taskList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(taskList)
        taskTitle = field(content, "Nova tarefa", "Estudar por 30 minutos", InputType.TYPE_CLASS_TEXT, "")
        taskMinutes = field(content, "Recompensa (1 a 60 minutos)", "15", InputType.TYPE_CLASS_NUMBER, "15")
        content.addView(Button(this).apply {
            text = "Adicionar tarefa"
            setOnClickListener {
                val minutes = taskMinutes.text.toString().toIntOrNull() ?: 0
                if (AttentionRewards.add(taskTitle.text.toString(), minutes)) {
                    taskTitle.setText(""); render()
                } else Toast.makeText(this@AttentionActivity, "Informe uma tarefa e de 1 a 60 minutos.", Toast.LENGTH_LONG).show()
            }
        })

        // Mostrar o estado atual antes do formulário evita que a lista fique
        // escondida sob todos os campos em telas pequenas.
        content.addView(TextView(this).apply {
            text = "Sites monitorados"
            textSize = 18f
            setPadding(0, dp(8), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val siteForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val toggleSiteForm = Button(this).apply {
            text = "Adicionar site e definir limites ▾"
            contentDescription = "Exibir formulário de novo site e seus limites"
            setOnClickListener {
                val expanded = siteForm.visibility != View.VISIBLE
                siteForm.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "Ocultar formulário ▴" else "Adicionar site e definir limites ▾"
                contentDescription = if (expanded) "Ocultar formulário de novo site"
                    else "Exibir formulário de novo site e seus limites"
            }
        }
        content.addView(toggleSiteForm, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        content.addView(siteForm)

        val suggestedDomain = AttentionLogic.rootDomainOf(
            intent.getStringExtra("attention_current_page"))
        domainEt = field(siteForm, "Site", "youtube.com",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            suggestedDomain.orEmpty())
        if (suggestedDomain != null) {
            siteForm.addView(TextView(this).apply {
                text = "Site atual detectado: $suggestedDomain · inclui subdomínios"
                textSize = 13f
            })
        }
        dailyEt = field(siteForm, "Limite diário (minutos, 0 = sem limite)", "60", InputType.TYPE_CLASS_NUMBER, "60")
        sessionEt = field(siteForm, "Duração da sessão (minutos, 0 = sem limite)", "30", InputType.TYPE_CLASS_NUMBER, "30")
        startEt = timeField(siteForm, "Horário permitido: início (vazio = qualquer horário)")
        endEt = timeField(siteForm, "Horário permitido: fim")

        siteForm.addView(Button(this).apply {
            text = "Adicionar site"
            setOnClickListener { addSite() }
        })

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
            minHeight = dp(48)
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
            minHeight = dp(48)
            contentDescription = label
            setSingleLine()
            setOnClickListener { pickTime(this) }
        }
        val clear = Button(this).apply {
            text = "Limpar"
            minHeight = dp(48)
            contentDescription = "Limpar $label"
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
        rewardStatus.text = "Banco: ${AttentionRewards.balance()} min · ganho restante hoje: ${AttentionRewards.earnedRemaining()} min (máximo 60/dia)"
        taskList.removeAllViews()
        for (task in AttentionRewards.tasks()) {
            val completed = task.completedDay == java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = panelBackground()
            }
            row.addView(TextView(this).apply {
                text = (if (completed) "✓ " else "○ ") + task.title + " · +${task.minutes} min"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            })
            row.addView(Button(this).apply {
                text = if (completed) "Concluída hoje" else "Concluir tarefa"
                isEnabled = !completed
                setOnClickListener {
                    val granted = AttentionRewards.complete(task.id)
                    Toast.makeText(this@AttentionActivity, if (granted > 0) "+$granted minutos no banco" else "Limite de recompensas atingido", Toast.LENGTH_SHORT).show()
                    render()
                }
            })
            row.addView(Button(this).apply {
                text = "Excluir tarefa"
                setOnClickListener { AttentionRewards.remove(task.id); render() }
            })
            taskList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        list.removeAllViews()
        val sites = Attention.sites()
        if (sites.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nenhum site configurado. Adicione um domínio abaixo para definir limites."
                setPadding(dp(12), dp(16), dp(12), dp(16))
                background = panelBackground()
            })
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
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = panelBackground()
            }
            card.addView(TextView(this).apply {
                text = s.domain
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply { text = "$daily · $session · $hours" })
            card.addView(TextView(this).apply { text = "Uso hoje: $used" })
            card.addView(TextView(this).apply {
                text = "Créditos resgatados hoje: ${AttentionRewards.allocatedMinutes(s.id)} min"
            })
            card.addView(Button(this).apply {
                text = "Resgatar 5 minutos para ${s.domain}"
                isEnabled = AttentionRewards.balance() >= 5 && s.dailyMinutes > 0
                setOnClickListener {
                    if (AttentionRewards.redeem(s.id, 5)) render()
                }
            })
            card.addView(Button(this).apply {
                text = "Remover"
                setOnClickListener { confirmRemove(s) }
            })
            list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setColor(if (dark) Color.rgb(39, 43, 52) else Color.rgb(245, 247, 251))
        setStroke(dp(1), if (dark) Color.rgb(69, 74, 84) else Color.rgb(221, 227, 235))
        cornerRadius = dp(16).toFloat()
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
