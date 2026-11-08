// Controles próprios de foco para o YouTube. Preferências ficam no armazenamento local da extensão.
(function () {
  if (window.top !== window || window.__nmYoutubeFocusInstalled) return;
  window.__nmYoutubeFocusInstalled = true;

  var defaults = {
    hide_homepage_feed: true,
    hide_and_redirect_subscriptions: true,
    hide_explore_trending: true,
    hide_more_from_youtube: true,
    hide_irrelevant_search_results: true,
    hide_shorts_tab: true,
    hide_youtube_shorts: true,
    disable_autoplay: true,
    hide_end_screen_videowall: true,
    hide_end_screen_cards: true,
    disable_annotations: false,
    hide_video_sidebar: false,
    hide_recommended: true,
    hide_live_chat: false,
    hide_playlist: false,
    hide_comments: false,
    hide_profile_photos: false,
    hide_mix_radio_playlists: false,
    hide_merch_tickets_offers: true,
    hide_video_info: false,
    hide_video_buttons_bar: false,
    hide_channel: false,
    hide_video_description: false,
    hide_top_header: false,
    hide_notification_bell: false,
    enable_personal_library: false,
    enable_timestamp_bookmarks: false,
    enable_video_notes: false,
    enable_session_timer: false,
    remember_channel_speed: false,
    enable_study_mode: false
  };

  var settings = Object.assign({}, defaults);
  var styleId = "nm-youtube-focus-style";
  var scheduled = false;
  var searchPattern = /related to your search|latest from|searches related to|people also search for|relacionad[oa]s? à sua pesquisa|últimas? de|pesquisas relacionadas|as pessoas também pesquisaram/i;
  var morePattern = /more from youtube|mais do youtube/i;

  function addRule(rules, enabled, selector) {
    if (enabled && selector) rules.push(selector + "{display:none!important}");
  }

  function isYoutubeHost(hostname) {
    return hostname === "www.youtube.com" || hostname === "m.youtube.com";
  }

  function shortsIdFromUrl(value) {
    try {
      var url = new URL(value, location.href);
      if (!isYoutubeHost(url.hostname)) return "";
      var match = url.pathname.match(/^\/shorts\/([a-zA-Z0-9_-]+)/);
      return match ? match[1] : "";
    } catch (_) {
      return "";
    }
  }

  function shortsWatchUrl(value) {
    var id = shortsIdFromUrl(value);
    return id ? "/watch?v=" + encodeURIComponent(id) : "";
  }

  function redirectShortsRoute() {
    if (!settings.hide_youtube_shorts || !isYoutubeHost(location.hostname)) return false;
    var target = shortsWatchUrl(location.href);
    if (!target || window.__nmYoutubeShortsRedirecting) return false;
    window.__nmYoutubeShortsRedirecting = true;
    location.replace(target);
    return true;
  }

  function redirectHome() {
    if (!settings.hide_and_redirect_subscriptions) return;
    if (!isYoutubeHost(location.hostname)) return;
    if (location.pathname === "/" && !window.__nmYoutubeRedirecting) {
      window.__nmYoutubeRedirecting = true;
      location.replace("/feed/subscriptions");
    }
  }

  function installShortsNavigationGuard() {
    if (window.__nmYoutubeShortsGuardInstalled) return;
    window.__nmYoutubeShortsGuardInstalled = true;

    document.addEventListener("click", function (event) {
      if (!settings.hide_youtube_shorts) return;
      var anchor = event.target && event.target.closest
        ? event.target.closest("a[href]") : null;
      if (!anchor) return;
      var target = shortsWatchUrl(anchor.href);
      if (!target) return;
      event.preventDefault();
      event.stopPropagation();
      if (event.stopImmediatePropagation) event.stopImmediatePropagation();
      location.assign(target);
    }, true);

    ["pushState", "replaceState"].forEach(function (method) {
      var original = history[method];
      if (typeof original !== "function") return;
      history[method] = function () {
        var result = original.apply(this, arguments);
        if (settings.hide_youtube_shorts) redirectShortsRoute();
        return result;
      };
    });

    window.addEventListener("popstate", function () {
      if (settings.hide_youtube_shorts) redirectShortsRoute();
    }, true);
    document.addEventListener("yt-navigate-start", function () {
      if (settings.hide_youtube_shorts) redirectShortsRoute();
    }, true);
  }

  function buildCss() {
    var rules = [];
    addRule(rules, settings.hide_homepage_feed, 'ytd-browse[page-subtype="home"] #contents');
    addRule(rules, settings.hide_video_sidebar, 'ytd-watch-flexy #secondary');
    addRule(rules, settings.hide_recommended, 'ytd-watch-next-secondary-results-renderer');
    addRule(rules, settings.hide_live_chat, 'ytd-live-chat-frame,ytd-live-chat-renderer');
    addRule(rules, settings.hide_playlist, 'ytd-playlist-panel-renderer,ytd-compact-playlist-renderer');
    addRule(rules, settings.hide_youtube_shorts,
      'ytd-reel-shelf-renderer,ytd-reel-item-renderer,ytd-rich-shelf-renderer[is-shorts],ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),ytd-rich-item-renderer:has(a[href^="/shorts/"]),ytd-video-renderer:has(a[href^="/shorts/"]),ytd-grid-video-renderer:has(a[href^="/shorts/"]),ytd-compact-video-renderer:has(a[href^="/shorts/"]),ytm-reel-shelf-renderer,ytm-reel-item-renderer,ytm-shorts-lockup-view-model');
    addRule(rules, settings.hide_shorts_tab,
      'ytd-guide-entry-renderer:has(a[href^="/shorts"]),ytd-mini-guide-entry-renderer:has(a[href^="/shorts"])');
    addRule(rules, settings.hide_end_screen_videowall, '.ytp-endscreen-content,.ytp-endscreen-previous,.ytp-endscreen-next');
    addRule(rules, settings.hide_end_screen_cards, '.ytp-ce-element,.ytp-cards-teaser,.ytp-cards-button');
    addRule(rules, settings.hide_comments, 'ytd-comments#comments,#comments');
    addRule(rules, settings.hide_profile_photos,
      'ytd-comment-view-model #author-thumbnail,ytd-comment-renderer #author-thumbnail,#author-thumbnail');
    addRule(rules, settings.hide_mix_radio_playlists, 'ytd-radio-renderer,ytd-compact-radio-renderer');
    addRule(rules, settings.hide_merch_tickets_offers,
      'ytd-merch-shelf-renderer,ytd-ticket-shelf-renderer,yt-related-products-renderer,ytd-donation-shelf-renderer');
    addRule(rules, settings.hide_video_info, 'ytd-watch-metadata #above-the-fold');
    addRule(rules, settings.hide_video_buttons_bar, '#actions,#top-level-buttons-computed');
    addRule(rules, settings.hide_channel, '#owner,ytd-video-owner-renderer,#channel-name,#subscribe-button');
    addRule(rules, settings.hide_video_description, '#description,ytd-text-inline-expander');
    addRule(rules, settings.hide_top_header, 'ytd-masthead');
    addRule(rules, settings.hide_notification_bell, 'ytd-notification-topbar-button-renderer,#notification-button');
    addRule(rules, settings.hide_explore_trending,
      'ytd-guide-entry-renderer:has(a[href^="/feed/trending"]),ytd-guide-entry-renderer:has(a[href^="/feed/explore"])');
    addRule(rules, settings.hide_and_redirect_subscriptions,
      'ytd-guide-entry-renderer:has(a[href="/"]),ytd-mini-guide-entry-renderer:has(a[href="/"])');
    addRule(rules, settings.disable_annotations,
      '.annotation,.ytp-cards-teaser,.ytp-ce-element,.ytp-suggested-action');
    rules.push('[data-nm-youtube-focus-hidden="1"],[data-nm-youtube-shorts-hidden="1"]{display:none!important}');
    return rules.join("\n");
  }

  function ensureStyle() {
    var style = document.getElementById(styleId);
    if (!style) {
      style = document.createElement("style");
      style.id = styleId;
      (document.head || document.documentElement).appendChild(style);
    }
    var nextCss = buildCss();
    if (style.textContent !== nextCss) style.textContent = nextCss;
  }

  function hideShortsCards() {
    if (!settings.hide_youtube_shorts) {
      document.querySelectorAll('[data-nm-youtube-shorts-hidden="1"]').forEach(function (node) {
        node.removeAttribute("data-nm-youtube-shorts-hidden");
      });
      return;
    }

    var selectors = [
      "ytd-rich-item-renderer",
      "ytd-video-renderer",
      "ytd-grid-video-renderer",
      "ytd-compact-video-renderer",
      "ytd-reel-item-renderer",
      "ytm-video-with-context-renderer",
      "ytm-compact-video-renderer",
      "ytm-reel-item-renderer"
    ].join(",");
    document.querySelectorAll('a[href*="/shorts/"]').forEach(function (anchor) {
      var card = anchor.closest(selectors);
      if (card) card.setAttribute("data-nm-youtube-shorts-hidden", "1");
    });
  }

  function hideMatchingBlocks() {
    hideShortsCards();
    var searchNodes = document.querySelectorAll(
      "ytd-shelf-renderer,ytd-horizontal-card-list-renderer,ytd-search-pyv-renderer"
    );
    searchNodes.forEach(function (node) {
      var hide = settings.hide_irrelevant_search_results && searchPattern.test(node.innerText || node.textContent || "");
      if (hide) node.setAttribute("data-nm-youtube-focus-hidden", "1");
      else if (node.getAttribute("data-nm-youtube-focus-hidden") === "1")
        node.removeAttribute("data-nm-youtube-focus-hidden");
    });

    if (settings.hide_more_from_youtube) {
      document.querySelectorAll("ytd-guide-section-renderer").forEach(function (section) {
        var heading = section.querySelector("#guide-section-title");
        var text = (heading ? heading.textContent : section.textContent || "").trim();
        if (morePattern.test(text)) section.setAttribute("data-nm-youtube-focus-hidden", "1");
      });
    } else {
      document.querySelectorAll('ytd-guide-section-renderer[data-nm-youtube-focus-hidden="1"]')
        .forEach(function (section) { section.removeAttribute("data-nm-youtube-focus-hidden"); });
    }

    if (settings.hide_mix_radio_playlists) {
      document.querySelectorAll("ytd-radio-renderer,ytd-compact-radio-renderer").forEach(function (node) {
        node.setAttribute("data-nm-youtube-focus-hidden", "1");
      });
    }
  }

  function disableAutoplay() {
    if (!settings.disable_autoplay) return;
    var toggle = document.querySelector(
      "#autoplay button[aria-pressed='true'],ytd-compact-autoplay-renderer button[aria-pressed='true']"
    );
    if (toggle && !toggle.dataset.nmFocusAutoplayHandled) {
      toggle.dataset.nmFocusAutoplayHandled = "1";
      toggle.click();
    }
  }

  function apply() {
    if (redirectShortsRoute()) return;
    installShortsNavigationGuard();
    redirectHome();
    ensureStyle();
    hideMatchingBlocks();
    disableAutoplay();
    renderPersonalTools();
    applyChannelSpeed();
  }

  function scheduleApply() {
    if (scheduled) return;
    scheduled = true;
    window.requestAnimationFrame(function () {
      scheduled = false;
      apply();
    });
  }

  function start(settingsFromStorage) {
    if (settingsFromStorage && typeof settingsFromStorage === "object")
      settings = Object.assign({}, defaults, settingsFromStorage);
    apply();
    document.addEventListener("yt-navigate-finish", function () {
      if (!redirectShortsRoute()) scheduleApply();
    }, true);
    document.addEventListener("yt-page-data-updated", scheduleApply, true);
    window.addEventListener("popstate", function () {
      if (!redirectShortsRoute()) scheduleApply();
    }, true);
    if (document.documentElement && window.MutationObserver) {
      var observer = new MutationObserver(function (records) {
        var relevant = records.some(function (record) {
          if (record.target && record.target.closest &&
              record.target.closest("#nm-youtube-tools,#" + styleId)) return false;
          return Array.prototype.some.call(record.addedNodes || [], function (node) {
            return !(node.nodeType === 1 &&
              (node.id === styleId || node.id === "nm-youtube-tools" ||
               (node.closest && node.closest("#nm-youtube-tools"))));
          }) || record.removedNodes.length > 0;
        });
        if (relevant) scheduleApply();
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
  }


  // Ferramentas pessoais: dados armazenados localmente no perfil da extensão.
  var toolsId = "nm-youtube-tools";
  var toolsOpen = false;
  var sessionInterval = null;
  var sessionEndsAt = 0;
  var lastVideoKey = "";

  function currentVideo() {
    var video = document.querySelector("video.html5-main-video, video");
    var titleNode = document.querySelector("h1.ytd-watch-metadata yt-formatted-string, h1.title");
    var channelNode = document.querySelector("#channel-name a, ytd-video-owner-renderer #channel-name a");
    var url = location.href.split("&")[0];
    var id = new URLSearchParams(location.search).get("v") || location.pathname.split("/").filter(Boolean).pop() || url;
    return {
      id: id, url: url,
      title: (titleNode && titleNode.textContent || document.title || "Vídeo do YouTube").trim(),
      channel: (channelNode && channelNode.textContent || "Canal desconhecido").trim(),
      time: video ? Math.floor(video.currentTime || 0) : 0,
      video: video
    };
  }

  function readStore(key) {
    return browser.storage.local.get(key).then(function (data) { return data[key] || []; });
  }
  function writeStore(key, value) {
    var payload = {}; payload[key] = value;
    return browser.storage.local.set(payload);
  }
  function formatTime(seconds) {
    seconds = Math.max(0, Number(seconds) || 0);
    return Math.floor(seconds / 60) + ":" + String(Math.floor(seconds % 60)).padStart(2, "0");
  }
  function makeToolButton(label, action) {
    var button = document.createElement("button");
    button.type = "button"; button.textContent = label;
    button.addEventListener("click", function (event) {
      event.preventDefault(); event.stopPropagation();
      Promise.resolve(action()).catch(function (error) {
        console.warn("[YouTube Focus] Ferramenta indisponível:", error);
      });
    });
    return button;
  }
  function ensureToolsStyle() {
    if (document.getElementById("nm-youtube-tools-style")) return;
    var style = document.createElement("style");
    style.id = "nm-youtube-tools-style";
    style.textContent =
      "#nm-youtube-tools{position:fixed;z-index:2147483646;right:14px;bottom:76px;font:13px/1.4 Arial,sans-serif;color:var(--yt-spec-text-primary,#fff)}" +
      "#nm-youtube-tools button{font:inherit;cursor:pointer;border:1px solid var(--yt-spec-10-percent-layer,rgba(255,255,255,.2));border-radius:9px;padding:8px 10px;background:var(--yt-spec-badge-chip-background,rgba(35,35,35,.96));color:inherit}" +
      "#nm-youtube-tools .nm-panel{display:none;max-width:min(310px,calc(100vw - 28px));max-height:55vh;overflow:auto;margin-bottom:8px;padding:10px;border-radius:13px;background:var(--yt-spec-menu-background,rgba(25,25,25,.97));box-shadow:0 4px 24px #0008}" +
      "#nm-youtube-tools.open .nm-panel{display:block}#nm-youtube-tools .nm-actions{display:flex;flex-wrap:wrap;gap:6px}" +
      "#nm-youtube-tools .nm-list{margin-top:8px;display:grid;gap:6px}#nm-youtube-tools .nm-entry{padding:7px;border-radius:8px;background:var(--yt-spec-10-percent-layer,rgba(255,255,255,.08));overflow-wrap:anywhere}" +
      "#nm-youtube-tools .nm-muted{opacity:.72;font-size:11px;margin:5px 0}" +
      "@media(prefers-color-scheme:light){#nm-youtube-tools{color:#202124}#nm-youtube-tools .nm-panel{background:#fff;box-shadow:0 4px 24px #0003}#nm-youtube-tools button{background:#f1f3f4;color:#202124;border-color:#dadce0}}";
    (document.head || document.documentElement).appendChild(style);
  }
  function renderPersonalTools() {
    var enabled = settings.enable_personal_library || settings.enable_timestamp_bookmarks ||
      settings.enable_video_notes || settings.enable_session_timer || settings.enable_study_mode;
    var root = document.getElementById(toolsId);
    if (!enabled) { if (root) root.remove(); return; }
    if (!root) {
      ensureToolsStyle();
      root = document.createElement("div"); root.id = toolsId;
      root.innerHTML = '<div class="nm-panel"><div class="nm-muted">Ferramentas pessoais · dados salvos neste navegador</div><div class="nm-actions"></div><div class="nm-list"></div></div><button class="nm-toggle" type="button">Ferramentas</button>';
      (document.body || document.documentElement).appendChild(root);
      root.querySelector(".nm-toggle").addEventListener("click", function () {
        toolsOpen = !toolsOpen; root.classList.toggle("open", toolsOpen);
        if (toolsOpen) refreshLibraryList(root);
      });
    }
    var actions = root.querySelector(".nm-actions"); actions.textContent = "";
    if (settings.enable_personal_library) {
      actions.appendChild(makeToolButton("Salvar vídeo", saveCurrentVideo));
      actions.appendChild(makeToolButton("Biblioteca", function () { return refreshLibraryList(root); }));
    }
    if (settings.enable_timestamp_bookmarks || settings.enable_study_mode)
      actions.appendChild(makeToolButton("Marcar momento", saveTimestamp));
    if (settings.enable_video_notes || settings.enable_study_mode)
      actions.appendChild(makeToolButton("Anotar", saveVideoNote));
    if (settings.enable_session_timer)
      actions.appendChild(makeToolButton("Temporizador", configureSessionTimer));
    if (settings.enable_study_mode)
      actions.appendChild(makeToolButton("Modo estudo", function () {
        toolsOpen = true; root.classList.add("open"); return refreshLibraryList(root);
      }));
    root.classList.toggle("open", toolsOpen);
  }
  function saveCurrentVideo() {
    var item = currentVideo();
    return readStore("nm_library").then(function (items) {
      var index = items.findIndex(function (entry) { return entry.id === item.id; });
      if (index >= 0) items.splice(index, 1);
      else items.unshift({id:item.id,url:item.url,title:item.title,channel:item.channel,savedAt:Date.now()});
      return writeStore("nm_library", items.slice(0, 500));
    }).then(function () { alert("Biblioteca atualizada."); });
  }
  function saveTimestamp() {
    var item = currentVideo();
    return readStore("nm_bookmarks").then(function (items) {
      items.unshift({id:item.id,url:item.url + (item.url.indexOf("?") >= 0 ? "&" : "?") + "t=" + item.time + "s",title:item.title,channel:item.channel,time:item.time,createdAt:Date.now()});
      return writeStore("nm_bookmarks", items.slice(0, 1000));
    }).then(function () { alert("Momento salvo em " + formatTime(item.time) + "."); });
  }
  function saveVideoNote() {
    var item = currentVideo();
    var note = prompt("Anotação para: " + item.title);
    if (note === null || !note.trim()) return Promise.resolve();
    return readStore("nm_notes").then(function (items) {
      items.unshift({id:item.id,url:item.url,title:item.title,channel:item.channel,time:item.time,note:note.trim(),createdAt:Date.now()});
      return writeStore("nm_notes", items.slice(0, 1000));
    }).then(function () { alert("Anotação salva."); });
  }
  function refreshLibraryList(root) {
    var list = root.querySelector(".nm-list"); list.textContent = "Carregando…";
    return Promise.all([readStore("nm_library"), readStore("nm_bookmarks"), readStore("nm_notes")])
      .then(function (groups) {
        list.textContent = "";
        var entries = [];
        groups[0].forEach(function (x) { entries.push({kind:"Biblioteca",item:x}); });
        groups[1].forEach(function (x) { entries.push({kind:"Momento " + formatTime(x.time),item:x}); });
        groups[2].forEach(function (x) { entries.push({kind:"Anotação · " + formatTime(x.time),item:x}); });
        entries.slice(0, 30).forEach(function (entry) {
          var card = document.createElement("div"); card.className = "nm-entry";
          var link = document.createElement("a"); link.href = entry.item.url;
          link.textContent = entry.item.title || "Vídeo salvo"; link.style.color = "inherit"; link.style.display = "block";
          var meta = document.createElement("div"); meta.className = "nm-muted";
          meta.textContent = entry.kind + " · " + (entry.item.channel || "");
          card.appendChild(link); card.appendChild(meta);
          if (entry.item.note) { var note = document.createElement("div"); note.textContent = entry.item.note; card.appendChild(note); }
          list.appendChild(card);
        });
        if (!entries.length) list.textContent = "Sua biblioteca ainda está vazia.";
      });
  }
  function configureSessionTimer() {
    var raw = prompt("Duração da sessão em minutos:", "25");
    if (raw === null) return Promise.resolve();
    var minutes = Math.max(1, Math.min(240, parseInt(raw, 10) || 25));
    sessionEndsAt = Date.now() + minutes * 60000;
    if (sessionInterval) clearInterval(sessionInterval);
    var tick = function () {
      var left = Math.max(0, Math.ceil((sessionEndsAt - Date.now()) / 1000));
      var toggle = document.querySelector("#nm-youtube-tools .nm-toggle");
      if (toggle) toggle.textContent = left ? "Sessão " + formatTime(left) : "Sessão encerrada";
      if (!left) {
        clearInterval(sessionInterval); sessionInterval = null;
        var video = currentVideo().video; if (video) video.pause();
        alert("Tempo de sessão concluído. A reprodução foi pausada.");
      }
    };
    tick(); sessionInterval = setInterval(tick, 1000);
    return Promise.resolve();
  }
  function applyChannelSpeed() {
    if (!settings.remember_channel_speed) return;
    var item = currentVideo();
    if (!item.video || item.channel === "Canal desconhecido") return;
    var key = "nm_speed_" + item.channel.toLowerCase().replace(/[^a-z0-9]+/g, "_").slice(0, 70);
    var identity = item.id + "|" + item.channel;
    if (lastVideoKey === identity) return;
    lastVideoKey = identity;
    browser.storage.local.get(key).then(function (stored) {
      var rate = Number(stored[key]);
      if (item.video && Number.isFinite(rate) && rate >= 0.25 && rate <= 3) item.video.playbackRate = rate;
    });
    if (item.video && !item.video.dataset.nmSpeedListener) {
      item.video.dataset.nmSpeedListener = "1";
      item.video.addEventListener("ratechange", function () {
        var current = currentVideo();
        var channelKey = "nm_speed_" + current.channel.toLowerCase().replace(/[^a-z0-9]+/g, "_").slice(0, 70);
        var payload = {}; payload[channelKey] = current.video.playbackRate;
        browser.storage.local.set(payload);
      });
    }
  }

  try {
    browser.storage.local.get(null).then(start).catch(function (error) {
      console.warn("[YouTube Focus] Preferências indisponíveis:", error);
      start({});
    });
  } catch (error) {
    start({});
  }
})();
