package com.example.extbrowser

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** Preferências locais do filtro de distrações do YouTube. */
class YoutubeFocusActivity : ComponentActivity() {
    private data class Option(
        val key: String,
        val title: String,
        val description: String,
        val group: String,
        val default: Boolean = false
    )

    private val options = listOf(
        Option("hide_homepage_feed", "Ocultar feed da página inicial",
            "Remove a grade de recomendações da página inicial.", "Página inicial", true),
        Option("hide_text_posts", "Ocultar publicações de texto",
            "Oculta posts textuais da comunidade, preservando publicações com imagens, vídeos ou enquetes.", "Página inicial", true),
        Option("hide_and_redirect_subscriptions", "Redirecionar início para inscrições",
            "Ao abrir a página inicial do YouTube, abre diretamente os canais inscritos.", "Página inicial", true),
        Option("hide_explore_trending", "Ocultar Explorar e Em alta",
            "Remove os atalhos de tendências e exploração.", "Página inicial", true),
        Option("hide_more_from_youtube", "Ocultar Mais do YouTube",
            "Remove a seção de serviços e conteúdos adicionais do YouTube.", "Página inicial", true),
        Option("hide_irrelevant_search_results", "Ocultar blocos irrelevantes da pesquisa",
            "Remove blocos como “relacionados à pesquisa” e “pessoas também pesquisaram”.", "Pesquisa", true),
        Option("hide_shorts_tab", "Ocultar a aba Shorts",
            "Remove o atalho de Shorts da navegação.", "Shorts e reprodução", true),
        Option("hide_youtube_shorts", "Ocultar vídeos Shorts",
            "Remove prateleiras e cartões de Shorts dos resultados e páginas.", "Shorts e reprodução", true),
        Option("disable_autoplay", "Desativar reprodução automática",
            "Desliga a reprodução automática do próximo vídeo.", "Shorts e reprodução", true),
        Option("hide_end_screen_videowall", "Ocultar mural de vídeos no encerramento",
            "Remove a parede de vídeos sugeridos ao terminar a reprodução.", "Shorts e reprodução", true),
        Option("hide_end_screen_cards", "Ocultar cartões sobre o vídeo",
            "Remove cartões e chamadas sobrepostas durante ou no final do vídeo.", "Shorts e reprodução", true),
        Option("disable_annotations", "Desativar anotações",
            "Oculta anotações e elementos interativos sobre o player.", "Shorts e reprodução"),
        Option("hide_video_sidebar", "Ocultar toda a barra lateral",
            "Remove a coluna lateral da página de reprodução.", "Página de reprodução"),
        Option("hide_recommended", "Ocultar vídeos recomendados",
            "Remove a lista de vídeos relacionados ao vídeo atual.", "Página de reprodução", true),
        Option("hide_live_chat", "Ocultar chat ao vivo",
            "Remove o chat das transmissões ao vivo.", "Página de reprodução"),
        Option("hide_playlist", "Ocultar playlists laterais",
            "Remove o painel de playlists e filas laterais.", "Página de reprodução"),
        Option("hide_comments", "Ocultar comentários",
            "Remove a seção de comentários.", "Interação"),
        Option("hide_profile_photos", "Ocultar fotos de perfil nos comentários",
            "Oculta os avatares exibidos junto aos comentários.", "Interação"),
        Option("hide_mix_radio_playlists", "Ocultar Mix e rádios automáticas",
            "Remove playlists automáticas do tipo Mix e rádio.", "Conteúdo e informações"),
        Option("hide_merch_tickets_offers", "Ocultar produtos, ingressos e ofertas",
            "Remove prateleiras comerciais e ofertas associadas ao vídeo.", "Conteúdo e informações", true),
        Option("hide_video_info", "Ocultar informações do vídeo",
            "Oculta o bloco de informações abaixo do player.", "Conteúdo e informações"),
        Option("hide_video_buttons_bar", "Ocultar botões do vídeo",
            "Oculta curtidas, descurtidas, compartilhar e ações.", "Conteúdo e informações"),
        Option("hide_channel", "Ocultar canal e inscrição",
            "Oculta o nome do canal, avatar do canal e botão de inscrição.", "Conteúdo e informações"),
        Option("hide_video_description", "Ocultar descrição",
            "Oculta a descrição e os detalhes expandidos do vídeo.", "Conteúdo e informações"),
        Option("hide_top_header", "Ocultar cabeçalho superior",
            "Remove o cabeçalho do YouTube, incluindo a caixa de pesquisa.", "Interface"),
        Option("hide_notification_bell", "Ocultar sino de notificações",
            "Remove o botão de notificações do cabeçalho.", "Interface"),
        Option("hide_homepage_shorts", "Ocultar Shorts da página inicial",
            "Remove prateleiras de Shorts especificamente do feed inicial.", "Shorts e reprodução", true),
        Option("hide_search_shorts", "Ocultar Shorts da pesquisa",
            "Remove prateleiras e resultados de Shorts na pesquisa.", "Shorts e reprodução", true),
        Option("hide_channel_shorts", "Ocultar aba Shorts dos canais",
            "Remove o acesso à aba de Shorts nas páginas dos canais.", "Shorts e reprodução", true),
        Option("hide_related_shorts", "Ocultar Shorts relacionados",
            "Remove Shorts que apareçam junto às recomendações do vídeo.", "Shorts e reprodução", true),
        Option("block_shorts_navigation", "Bloquear abertura de Shorts",
            "Impede a navegação para /shorts/ por links, navegação interna e endereço direto.", "Shorts e reprodução", true),
        Option("hide_search_suggestions", "Ocultar sugestões de pesquisa",
            "Oculta sugestões automáticas exibidas ao digitar na caixa de pesquisa.", "Pesquisa"),
        Option("hide_miniplayer", "Ocultar miniplayer",
            "Remove o miniplayer flutuante do YouTube.", "Página de reprodução"),
        Option("hide_feed_filters", "Ocultar filtros do feed",
            "Remove a faixa de filtros e chips de navegação dos feeds.", "Página inicial"),
        Option("hide_promoted_content", "Ocultar conteúdo promovido",
            "Oculta espaços publicitários identificáveis no feed; não bloqueia anúncios dentro do player nem requisições de anúncios.", "Conteúdo e informações", true),
        Option("hide_membership_promotions", "Ocultar promoções de membros",
            "Remove chamadas de adesão, banners e promoções de marcas/canais.", "Conteúdo e informações", true),
        Option("enable_personal_library", "Biblioteca e fila pessoal",
            "Adiciona ações para salvar vídeos e consultar sua coleção pessoal.", "Organização"),
        Option("enable_timestamp_bookmarks", "Marcar momentos do vídeo",
            "Salva o instante atual do vídeo junto ao link para retomada.", "Aprendizagem"),
        Option("enable_video_notes", "Anotações por vídeo",
            "Permite registrar notas vinculadas ao vídeo atual.", "Aprendizagem"),
        Option("enable_session_timer", "Temporizador de sessão",
            "Exibe um controle de sessão com duração configurável.", "Uso consciente"),
        Option("remember_channel_speed", "Velocidade por canal",
            "Memoriza a velocidade de reprodução escolhida para cada canal.", "Experiência"),
        Option("enable_study_mode", "Modo estudo",
            "Reúne marcações e anotações em uma interface compacta.", "Aprendizagem")
    )

    private val defaults by lazy { options.associate { it.key to it.default } }
    private val values = linkedMapOf<String, Boolean>()
    private val expanded = mutableSetOf("Página inicial", "Shorts e reprodução")
    private val prefs get() = getSharedPreferences("ext_youtube-focus", MODE_PRIVATE)
    private var changed = false
    private var query = ""
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isDark()) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        loadValues()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(if (isDark()) Color.rgb(17, 19, 24) else Color.rgb(247, 248, 252))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        heading.addView(TextView(this).apply {
            text = "YouTube sem distrações"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText())
        })
        heading.addView(TextView(this).apply {
            text = "Seu espaço de foco, organização e aprendizagem"
            textSize = 12f
            setTextColor(secondaryText())
        })
        top.addView(heading)
        top.addView(Button(this).apply { text = "Perfis"; setOnClickListener { showProfiles() } })
        root.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (isDark()) Color.rgb(34, 48, 66) else Color.rgb(226, 238, 252), 16)
        }
        hero.addView(TextView(this).apply {
            text = "O YouTube do seu jeito"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark()) Color.WHITE else Color.rgb(22, 55, 88))
        })
        hero.addView(TextView(this).apply {
            text = "Reduza interrupções, escolha o que aparece e transforme vídeos em material de estudo."
            textSize = 12f
            setTextColor(if (isDark()) Color.rgb(218, 228, 240) else Color.rgb(45, 67, 91))
            setPadding(0, dp(4), 0, dp(8))
        })
        summary = TextView(this).apply { textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(if (isDark()) Color.WHITE else Color.rgb(22, 55, 88)) }
        hero.addView(summary)
        root.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(10) })

        val search = EditText(this).apply {
            hint = "Buscar configuração..."
            textSize = 14f
            singleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(if (isDark()) Color.rgb(38, 42, 50) else Color.WHITE, 12)
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        val quickActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        quickActions.addView(Button(this).apply { text = "Ativar tudo"; setOnClickListener { setAll(true) } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        quickActions.addView(Button(this).apply { text = "Desativar tudo"; setOnClickListener { setAll(false) } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(quickActions)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(Button(this).apply {
            text = "Restaurar padrões"
            setOnClickListener {
                values.clear(); values.putAll(defaults); changed = true
                saveValues(); render()
                Toast.makeText(this@YoutubeFocusActivity, "Padrões restaurados", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        footer.addView(Button(this).apply { text = "Concluído"; setOnClickListener { finishWithResult() } },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(footer)
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString()?.trim().orEmpty(); render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
        val pad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val filtered = options.filter {
            query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) || it.group.contains(query, true)
        }
        filtered.groupBy { it.group }.forEach { (group, items) ->
            val active = items.count { values[it.key] == true }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(10), dp(12))
                background = rounded(if (isDark()) Color.rgb(35, 39, 47) else Color.WHITE, 13)
                isClickable = true
                isFocusable = true
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            labels.addView(TextView(this).apply { text = group; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(primaryText()) })
            labels.addView(TextView(this).apply { text = "$active de ${items.size} ativos"; textSize = 11f; setTextColor(secondaryText()) })
            header.addView(labels)
            header.addView(TextView(this).apply { text = if (query.isNotBlank() || group in expanded) "−" else "+"; textSize = 22f; setTextColor(accent()) })
            header.setOnClickListener { if (group in expanded) expanded.remove(group) else expanded.add(group); render() }
            list.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) })

            if (query.isNotBlank() || group in expanded) {
                items.forEach { option ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(7), dp(12), dp(9))
                        background = rounded(if (isDark()) Color.rgb(27, 30, 37) else Color.WHITE, 11)
                    }
                    val toggle = Switch(this).apply {
                        text = option.title
                        textSize = 13f
                        setTextColor(primaryText())
                        isChecked = values[option.key] == true
                        setOnCheckedChangeListener { _, enabled ->
                            values[option.key] = enabled
                            changed = true
                            saveValues()
                            updateSummary()
                            (labels.getChildAt(1) as TextView).text = "${items.count { values[it.key] == true }} de ${items.size} ativos"
                        }
                    }
                    card.addView(toggle)
                    card.addView(TextView(this).apply {
                        text = option.description
                        textSize = 11f
                        setTextColor(secondaryText())
                        setPadding(0, dp(2), 0, dp(2))
                    })
                    list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4); leftMargin = dp(5); rightMargin = dp(5) })
                }
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                actions.addView(TextView(this).apply {
                    text = "Ativar grupo"; textSize = 11f; setTextColor(accent())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener { items.forEach { values[it.key] = true }; changed = true; saveValues(); render() }
                })
                actions.addView(TextView(this).apply {
                    text = "Desativar grupo"; textSize = 11f; setTextColor(accent())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener { items.forEach { values[it.key] = false }; changed = true; saveValues(); render() }
                })
                list.addView(actions)
            }
        }
        if (filtered.isEmpty()) list.addView(TextView(this).apply {
            text = "Nenhuma configuração encontrada."
            textSize = 14f
            setTextColor(secondaryText())
            setPadding(dp(12), dp(24), dp(12), dp(24))
        })
        updateSummary()
    }

    private fun updateSummary() {
        if (::summary.isInitialized) summary.text = "${options.count { values[it.key] == true }} de ${options.size} controles ativados"
    }

    private fun setAll(enabled: Boolean) {
        options.forEach { values[it.key] = enabled }
        changed = true; saveValues(); render()
    }

    private fun showProfiles() {
        val profiles = arrayOf("Foco essencial", "Estudo", "Equilibrado", "Personalizado")
        AlertDialog.Builder(this).setTitle("Escolha um perfil").setItems(profiles) { _, which ->
            when (which) {
                0 -> applyProfile(setOf("hide_homepage_feed", "hide_text_posts", "hide_and_redirect_subscriptions", "hide_explore_trending", "hide_more_from_youtube", "hide_shorts_tab", "hide_youtube_shorts", "hide_homepage_shorts", "hide_search_shorts", "hide_channel_shorts", "hide_related_shorts", "block_shorts_navigation", "disable_autoplay", "hide_end_screen_videowall", "hide_end_screen_cards", "hide_recommended", "hide_irrelevant_search_results", "hide_promoted_content", "hide_membership_promotions", "hide_merch_tickets_offers"))
                1 -> applyProfile(setOf("hide_homepage_feed", "hide_text_posts", "hide_and_redirect_subscriptions", "hide_shorts_tab", "hide_youtube_shorts", "hide_homepage_shorts", "hide_search_shorts", "hide_channel_shorts", "hide_related_shorts", "block_shorts_navigation", "disable_autoplay", "hide_end_screen_videowall", "hide_end_screen_cards", "hide_recommended", "enable_personal_library", "enable_timestamp_bookmarks", "enable_video_notes", "enable_study_mode", "enable_session_timer", "remember_channel_speed"))
                2 -> { values.clear(); values.putAll(defaults); changed = true; saveValues(); render() }
                3 -> Toast.makeText(this, "O perfil personalizado mantém suas escolhas atuais.", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    private fun applyProfile(enabledKeys: Set<String>) {
        options.forEach { values[it.key] = it.key in enabledKeys }
        changed = true; saveValues(); render()
        Toast.makeText(this, "Perfil aplicado", Toast.LENGTH_SHORT).show()
    }

    private fun loadValues() {
        values.putAll(defaults)
        val saved = runCatching { JSONObject(prefs.getString("data", "{}") ?: "{}") }.getOrNull()
            ?: return
        options.forEach { option ->
            if (saved.has(option.key)) values[option.key] = saved.optBoolean(option.key, option.default)
        }
    }

    private fun saveValues() {
        // Preserva biblioteca, marcações, anotações e demais dados da extensão.
        val json = runCatching {
            JSONObject(prefs.getString("data", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        values.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("data", json.toString()).apply()
        setResult(RESULT_OK)
    }

    private fun finishWithResult() {
        if (changed) setResult(RESULT_OK)
        finish()
    }

    override fun onBackPressed() {
        finishWithResult()
    }

    private fun isDark() = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", "light") == "dark"
    private fun primaryText() = if (isDark()) Color.rgb(242, 244, 248) else Color.rgb(30, 34, 42)
    private fun secondaryText() = if (isDark()) Color.rgb(173, 180, 192) else Color.rgb(103, 111, 124)
    private fun accent() = if (isDark()) Color.rgb(130, 190, 255) else Color.rgb(25, 103, 170)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
