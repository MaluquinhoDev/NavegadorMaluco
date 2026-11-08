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
    hide_notification_bell: false
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
      'ytd-reel-shelf-renderer,ytd-reel-item-renderer,ytd-rich-shelf-renderer[is-shorts],ytd-rich-section-renderer:has(ytd-reel-shelf-renderer),ytd-rich-item-renderer:has(a[href^="/shorts/"])');
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
    style.textContent = buildCss();
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
    ensureStyle();
    hideMatchingBlocks();
    disableAutoplay();
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
    document.addEventListener("yt-navigate-finish", scheduleApply, true);
    document.addEventListener("yt-page-data-updated", scheduleApply, true);
    window.addEventListener("popstate", scheduleApply, true);
    if (document.documentElement && window.MutationObserver) {
      var observer = new MutationObserver(scheduleApply);
      observer.observe(document.documentElement, { childList: true, subtree: true });
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
