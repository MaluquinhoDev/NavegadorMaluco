package com.example.extbrowser

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
            "Remove anúncios e vídeos promovidos identificados na interface do YouTube.", "Conteúdo e informações", true),
        Option("hide_membership_promotions", "Ocultar promoções de membros",
            "Remove chamadas de adesão, banners e promoções de marcas/canais.", "Conteúdo e informações", true),
        Option("enable_personal_library", "Biblioteca e fila pessoal",
            "Adiciona controles para salvar vídeos e organizar uma fila manual.", "Organização"),
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
    private val prefs get() = getSharedPreferences("ext_youtube-focus", MODE_PRIVATE)
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (getSharedPreferences("appearance", MODE_PRIVATE)
                .getString("theme", "light") == "dark")
            android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        loadValues()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val heading = TextView(this).apply {
            text = "YouTube sem distrações"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Controles locais da página. Vídeos longos, pesquisa e reprodução manual continuam disponíveis."
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(heading)
        root.addView(subtitle)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        var previousGroup = ""
        options.forEach { option ->
            if (option.group != previousGroup) {
                previousGroup = option.group
                list.addView(TextView(this).apply {
                    text = option.group
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (isDark()) Color.rgb(120, 185, 245) else Color.rgb(25, 103, 170))
                    setPadding(dp(4), dp(14), dp(4), dp(6))
                })
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = cardBackground()
            }
            val toggle = Switch(this).apply {
                text = option.title
                textSize = 14f
                isChecked = values[option.key] == true
                minHeight = dp(44)
                setOnCheckedChangeListener { _, enabled ->
                    values[option.key] = enabled
                    changed = true
                    saveValues()
                }
            }
            val description = TextView(this).apply {
                text = option.description
                textSize = 12f
                alpha = 0.76f
                setPadding(0, 0, dp(4), dp(4))
            }
            card.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            card.addView(description)
            list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(7)
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(Button(this).apply {
            text = "Padrão"
            setOnClickListener {
                values.clear()
                values.putAll(defaults)
                changed = true
                saveValues()
                Toast.makeText(this@YoutubeFocusActivity, "Padrões restaurados", Toast.LENGTH_SHORT).show()
                finishWithResult()
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "Concluído"
            setOnClickListener { finishWithResult() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(actions)
        setContentView(root)
        val pad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
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

    private fun isDark() = getSharedPreferences("appearance", MODE_PRIVATE)
        .getString("theme", "light") == "dark"

    private fun cardBackground() = GradientDrawable().apply {
        setColor(if (isDark()) Color.rgb(39, 43, 52) else Color.rgb(245, 247, 251))
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), if (isDark()) Color.rgb(69, 74, 84) else Color.rgb(221, 227, 235))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
