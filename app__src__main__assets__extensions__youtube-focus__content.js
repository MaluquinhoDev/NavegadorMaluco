// Controles próprios de foco para o YouTube. Preferências ficam no armazenamento local da extensão.
(function () {
  if (window.top !== window || window.__nmYoutubeFocusInstalled) return;
  window.__nmYoutubeFocusInstalled = true;

  var defaults = {
    hide_homepage_feed: true,
    hide_text_posts: true,
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
    hide_homepage_shorts: true,
    hide_search_shorts: true,
    hide_channel_shorts: true,
    hide_related_shorts: true,
    hide_search_suggestions: false,
    hide_miniplayer: false,
    hide_feed_filters: false,
    hide_promoted_content: true,
    hide_membership_promotions: true,
    block_shorts_navigation: true,
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

  function redirectHome() {
    if (!settings.hide_and_redirect_subscriptions) return;
    if (location.hostname !== "www.youtube.com" && location.hostname !== "m.youtube.com") return;
    if (location.pathname === "/" && !window.__nmYoutubeRedirecting) {
      window.__nmYoutubeRedirecting = true;
      location.replace("/feed/subscriptions");
    }
  }

  function buildCss() {
    var rules = [];
    addRule(rules, settings.hide_homepage_feed, 'ytd-browse[page-subtype="home"] #contents');
    addRule(rules, settings.hide_video_sidebar, 'ytd-watch-flexy #secondary');
    addRule(rules, settings.hide_recommended, 'ytd-watch-next-secondary-results-renderer');
    addRule(rules, settings.hide_live_chat, 'ytd-live-chat-frame,ytd-live-chat-renderer');
    addRule(rules, settings.hide_playlist, 'ytd-playlist-panel-renderer,ytd-compact-playlist-renderer');
    addRule(rules, settings.hide_youtube_shorts,
      'ytd-reel-shelf-renderer,ytd-reel-item-renderer,ytd-rich-shelf-renderer[is-shorts],ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),ytd-rich-item-renderer:has(a[href^="/shorts/"]),ytm-reel-shelf-renderer,ytm-reel-item-renderer,ytm-shorts-lockup-view-model');
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
    addRule(rules, settings.hide_homepage_shorts,
      'ytd-browse[page-subtype="home"] ytd-reel-shelf-renderer,ytd-browse[page-subtype="home"] ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),ytm-browse[page-subtype="home"] ytm-reel-shelf-renderer,ytm-browse[page-subtype="home"] ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)');
    addRule(rules, settings.hide_search_shorts,
      'ytd-search ytd-reel-shelf-renderer,ytd-search ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),ytm-search ytm-reel-shelf-renderer,ytm-search ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)');
    addRule(rules, settings.hide_channel_shorts,
      'ytd-browse[page-subtype="channels"] ytd-tab-renderer a[href*="/shorts"],ytd-c4-tabbed-header-renderer a[href*="/shorts"],ytm-channel-tab-renderer a[href*="/shorts"]');
    addRule(rules, settings.hide_related_shorts,
      'ytd-watch-next-secondary-results-renderer ytd-reel-shelf-renderer,ytd-compact-video-renderer:has(a[href^="/shorts/"]),ytm-watch-next-secondary-results-renderer ytm-reel-shelf-renderer');
    addRule(rules, settings.hide_search_suggestions,
      'ytd-searchbox[has-focus] #suggestions,ytd-searchbox #search-suggestions,ytm-searchbox #suggestions,ytm-searchbox #search-suggestions');
    addRule(rules, settings.hide_miniplayer, 'ytd-miniplayer,ytd-miniplayer-container,ytm-miniplayer');
    addRule(rules, settings.hide_feed_filters, 'yt-chip-cloud-renderer,ytm-chip-cloud-renderer');
    addRule(rules, settings.hide_promoted_content,
      'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-ad-slot-renderer,ytm-companion-ad-renderer');
    addRule(rules, settings.hide_membership_promotions,
      'ytd-membership-promo-renderer,ytm-membership-promo-renderer');
    addRule(rules, settings.hide_text_posts, '[data-nm-youtube-text-post-hidden="1"]');
    addRule(rules, settings.hide_explore_trending,
      'ytd-guide-entry-renderer:has(a[href^="/feed/trending"]),ytd-guide-entry-renderer:has(a[href^="/feed/explore"])');
    addRule(rules, settings.hide_and_redirect_subscriptions,
      'ytd-guide-entry-renderer:has(a[href="/"]),ytd-mini-guide-entry-renderer:has(a[href="/"])');
    addRule(rules, settings.disable_annotations,
      '.annotation,.ytp-cards-teaser,.ytp-ce-element,.ytp-suggested-action');
    rules.push('[data-nm-youtube-focus-hidden="1"]{display:none!important}');
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

  function hideMatchingBlocks() {
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

    markTextOnlyCommunityPosts();
  }

  function markTextOnlyCommunityPosts() {
    var postSelector = "ytd-backstage-post-renderer,ytm-backstage-post-renderer";
    var mediaSelector = [
      "ytd-backstage-image-renderer", "ytd-post-multi-image-renderer",
      "ytd-backstage-video-renderer", "ytd-backstage-poll-renderer",
      "ytm-backstage-image-renderer", "ytm-backstage-video-renderer",
      "ytm-backstage-poll-renderer", "ytd-post-video-renderer"
    ].join(",");
    document.querySelectorAll(postSelector).forEach(function (post) {
      var hasMedia = !!post.querySelector(mediaSelector);
      var hasText = !!post.querySelector("#content-text,#post-text,yt-formatted-string");
      if (settings.hide_text_posts && hasText && !hasMedia) {
        post.setAttribute("data-nm-youtube-text-post-hidden", "1");
      } else {
        post.removeAttribute("data-nm-youtube-text-post-hidden");
      }
    });
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
    redirectHome();
    enforceShortsRoute();
    ensureStyle();
    hideMatchingBlocks();
    disableAutoplay();
    renderPersonalTools();
    applyChannelSpeed();
  }

  function enforceShortsRoute() {
    if (!settings.block_shorts_navigation) return;
    if (location.pathname.indexOf("/shorts/") === 0) {
      location.replace("/feed/subscriptions");
    }
  }

  function installShortsClickGuard() {
    document.addEventListener("click", function (event) {
      if (!settings.block_shorts_navigation) return;
      var anchor = event.target && event.target.closest ? event.target.closest("a[href]") : null;
      if (!anchor) return;
      try {
        var target = new URL(anchor.href, location.href);
        if ((target.hostname !== "www.youtube.com" && target.hostname !== "m.youtube.com") ||
            target.pathname.indexOf("/shorts/") !== 0) return;

        // Mantém o comportamento de nova aba, botão do meio e modificadores:
        // o destino é trocado para inscrições, sem sequestrar o gesto original.
        anchor.href = new URL("/feed/subscriptions", location.origin).href;
        if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey ||
            event.altKey || (anchor.target && anchor.target !== "_self")) return;

        event.preventDefault();
        event.stopPropagation();
        if (event.stopImmediatePropagation) event.stopImmediatePropagation();
        location.assign("/feed/subscriptions");
      } catch (_) {}
    }, true);
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
    installShortsClickGuard();
    document.addEventListener("yt-navigate-finish", scheduleApply, true);
    document.addEventListener("yt-page-data-updated", scheduleApply, true);
    window.addEventListener("popstate", scheduleApply, true);
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
      "#nm-youtube-tools{position:fixed;z-index:2147483646;right:14px;bottom:76px;font:13px/1.45 Arial,sans-serif;color:var(--yt-spec-text-primary,#fff);max-width:calc(100vw - 28px)}" +
      "#nm-youtube-tools button{font:inherit;cursor:pointer;border:1px solid var(--yt-spec-10-percent-layer,rgba(255,255,255,.2));border-radius:11px;padding:9px 11px;background:var(--yt-spec-badge-chip-background,rgba(35,35,35,.96));color:inherit;min-height:38px}" +
      "#nm-youtube-tools .nm-toggle{border-radius:22px;padding:10px 15px;font-weight:700;box-shadow:0 3px 15px #0005}" +
      "#nm-youtube-tools .nm-panel{display:none;width:min(340px,calc(100vw - 28px));max-height:62vh;overflow:auto;margin-bottom:9px;padding:13px;border:1px solid var(--yt-spec-10-percent-layer,rgba(255,255,255,.16));border-radius:17px;background:var(--yt-spec-menu-background,rgba(25,25,25,.98));box-shadow:0 8px 30px #0008;box-sizing:border-box}" +
      "#nm-youtube-tools.open .nm-panel{display:block}#nm-youtube-tools .nm-head{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:3px}" +
      "#nm-youtube-tools .nm-title{font-size:15px;font-weight:700}#nm-youtube-tools .nm-close{padding:4px 9px;min-height:30px}" +
      "#nm-youtube-tools .nm-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px;margin-top:11px}" +
      "#nm-youtube-tools .nm-actions button{width:100%;text-align:left;background:var(--yt-spec-10-percent-layer,rgba(255,255,255,.08))}" +
      "#nm-youtube-tools .nm-list{margin-top:11px;display:grid;gap:7px}#nm-youtube-tools .nm-entry{padding:9px;border-radius:10px;background:var(--yt-spec-10-percent-layer,rgba(255,255,255,.08));overflow-wrap:anywhere}" +
      "#nm-youtube-tools .nm-entry a{text-decoration:none;font-weight:600}#nm-youtube-tools .nm-empty{padding:14px 8px;text-align:center;opacity:.75}" +
      "#nm-youtube-tools .nm-muted{opacity:.72;font-size:11px;margin:4px 0}" +
      "@media(prefers-color-scheme:light){#nm-youtube-tools{color:#202124}#nm-youtube-tools .nm-panel{background:#fff;box-shadow:0 8px 30px #0003}#nm-youtube-tools button{background:#f1f3f4;color:#202124;border-color:#dadce0}#nm-youtube-tools .nm-actions button{background:#f5f7fa}}";
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
      root.innerHTML = '<section class="nm-panel" aria-label="Ferramentas pessoais"><div class="nm-head"><div class="nm-title">Seu espaço de estudo</div><button class="nm-close" type="button" aria-label="Fechar painel">×</button></div><div class="nm-muted">Biblioteca, momentos e anotações ficam salvos neste navegador.</div><div class="nm-actions"></div><div class="nm-list"><div class="nm-empty">Abra a biblioteca para ver seus registros.</div></div></section><button class="nm-toggle" type="button">✦ Ferramentas</button>';
      (document.body || document.documentElement).appendChild(root);
      root.querySelector(".nm-toggle").addEventListener("click", function () {
        toolsOpen = !toolsOpen; root.classList.toggle("open", toolsOpen);
        if (toolsOpen) refreshLibraryList(root);
      });
      root.querySelector(".nm-close").addEventListener("click", function () {
        toolsOpen = false; root.classList.remove("open");
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
        if (!entries.length) {
          var empty = document.createElement("div"); empty.className = "nm-empty";
          empty.textContent = "Sua biblioteca ainda está vazia. Salve um vídeo ou marque um momento para começar.";
          list.appendChild(empty);
        }
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
