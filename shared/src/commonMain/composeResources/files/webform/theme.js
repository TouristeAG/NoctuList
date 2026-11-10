// Light / dark / auto theme for every NoctuList public page.
// Loaded as a classic script in <head> so the right colours are applied before the first paint.
(function () {
  "use strict";
  var KEY = "nl-theme";
  var MODES = ["auto", "light", "dark"];
  var media = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

  var LABELS = {
    en: { auto: "Theme: automatic", light: "Theme: light", dark: "Theme: dark" },
    fr: { auto: "Thème : automatique", light: "Thème : clair", dark: "Thème : sombre" },
    es: { auto: "Tema: automático", light: "Tema: claro", dark: "Tema: oscuro" },
    de: { auto: "Design: automatisch", light: "Design: hell", dark: "Design: dunkel" },
    it: { auto: "Tema: automatico", light: "Tema: chiaro", dark: "Tema: scuro" },
  };

  var ICONS = {
    auto:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 0 0 18z" fill="currentColor" stroke="none"/></svg>',
    light:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>',
    dark:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>',
  };

  function readPref() {
    try {
      var stored = localStorage.getItem(KEY);
      return MODES.indexOf(stored) >= 0 ? stored : "auto";
    } catch (e) {
      return "auto";
    }
  }

  function resolve(pref) {
    if (pref === "light" || pref === "dark") return pref;
    return media && media.matches ? "dark" : "light";
  }

  function label(pref) {
    var lang = (document.documentElement.lang || "en").slice(0, 2);
    var table = LABELS[lang] || LABELS.en;
    return table[pref];
  }

  function paintToggles(pref) {
    var buttons = document.querySelectorAll("[data-theme-toggle]");
    for (var i = 0; i < buttons.length; i++) {
      // Static, trusted SVG markup only — never user data.
      buttons[i].innerHTML = ICONS[pref];
      buttons[i].setAttribute("aria-label", label(pref));
      buttons[i].setAttribute("title", label(pref));
    }
  }

  function apply() {
    var pref = readPref();
    var resolved = resolve(pref);
    var root = document.documentElement;
    root.setAttribute("data-theme", resolved);
    root.style.colorScheme = resolved;
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", resolved === "dark" ? "#0a1018" : "#f4f7fb");
    paintToggles(pref);
    try {
      window.dispatchEvent(new CustomEvent("nl-themechange", { detail: { theme: resolved, pref: pref } }));
    } catch (e) {
      /* very old browsers: the canvas keeps its colours */
    }
  }

  function setPref(pref) {
    try {
      localStorage.setItem(KEY, pref);
    } catch (e) {
      /* private mode: theme still applies for this page */
    }
    apply();
  }

  function cycle() {
    var next = MODES[(MODES.indexOf(readPref()) + 1) % MODES.length];
    setPref(next);
  }

  apply();
  if (media) {
    var onChange = function () {
      if (readPref() === "auto") apply();
    };
    if (media.addEventListener) media.addEventListener("change", onChange);
    else if (media.addListener) media.addListener(onChange);
  }

  document.addEventListener("DOMContentLoaded", function () {
    paintToggles(readPref());
    var buttons = document.querySelectorAll("[data-theme-toggle]");
    for (var i = 0; i < buttons.length; i++) buttons[i].addEventListener("click", cycle);
  });

  window.NoctuTheme = {
    current: function () { return resolve(readPref()); },
    pref: readPref,
    set: setPref,
    cycle: cycle,
    refreshLabels: function () { paintToggles(readPref()); },
  };
})();
