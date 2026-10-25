package com.example.extbrowser

/**
 * Estado visual do Media3, independente do estado de reprodução.
 * A sessão Media3 é a única responsável por áudio, vídeo e posição.
 *
 * Este coordenador não guarda Activity, View ou MediaController para evitar vazamentos.
 */
internal class PlayerDisplayCoordinator {
    enum class Mode { HIDDEN, MINI, FULLSCREEN, PIP }

    var mode: Mode = Mode.HIDDEN
        private set

    fun browserState(hasMedia: Boolean, fullPlayerVisible: Boolean, pipActive: Boolean): Mode {
        mode = when {
            pipActive -> Mode.PIP
            fullPlayerVisible -> Mode.FULLSCREEN
            hasMedia -> Mode.MINI
            else -> Mode.HIDDEN
        }
        return mode
    }

    fun canOpenPip(hasMedia: Boolean, pipSupported: Boolean, pipActive: Boolean): Boolean =
        hasMedia && pipSupported && !pipActive

    fun shouldOpenFullscreen(pipActive: Boolean): Boolean = !pipActive
}
