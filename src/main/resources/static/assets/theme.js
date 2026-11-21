/**
 * ============================================================================
 * Nimbus FinServe :: I-ITSM  -  light / dark mode
 * ============================================================================
 * Loaded by app.js (section 19). One button, always showing both choices ("Light" and "Dark"), the active one
 * highlighted. It sits in the top right corner of the sign-in page and, after sign-in, in the header next to the
 * notification bell. The choice is remembered in this browser. The default is Light.
 *
 * Nothing in app.js, index.html or styles.css is edited: this file only sets data-theme on <html>, loads
 * /assets/theme.css and adds the button. CSP compliant (no inline handlers, no external resources).
 */
(function () {
  'use strict';

  const STORAGE_KEY = 'iitsm_theme';
  const STYLESHEET_URL = '/assets/theme.css';
  const LIGHT = 'light';
  const DARK = 'dark';

  function readSavedTheme() {
    try {
      return window.localStorage.getItem(STORAGE_KEY) === DARK ? DARK : LIGHT;
    } catch (e) {
      return LIGHT; // storage blocked (private window, site data off): start in Light
    }
  }

  function saveTheme(theme) {
    try {
      window.localStorage.setItem(STORAGE_KEY, theme);
    } catch (e) {
      // storage blocked: the choice then lasts until the page is reloaded
    }
  }

  let theme = readSavedTheme();
  let button = null;
  let lightOption = null;
  let darkOption = null;

  function loadStylesheet() {
    if (document.querySelector('link[data-theme-css]')) return;
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = STYLESHEET_URL;
    link.setAttribute('data-theme-css', '');
    document.head.appendChild(link);
  }

  function applyTheme(next) {
    theme = next;
    document.documentElement.setAttribute('data-theme', theme);
    if (!button) return;
    const dark = theme === DARK;
    button.setAttribute('aria-checked', String(dark));
    button.title = dark
      ? 'Dark mode is on. Click to switch to light mode.'
      : 'Light mode is on. Click to switch to dark mode.';
    lightOption.classList.toggle('is-active', !dark);
    darkOption.classList.toggle('is-active', dark);
  }

  function buildOption(icon, label) {
    const option = document.createElement('span');
    option.className = 'theme-toggle-opt';
    option.textContent = icon + ' ' + label;
    return option;
  }

  /** Signed out: floating in the top right corner. Signed in: inside the header's action area. */
  function placeButton() {
    const appView = document.querySelector('#app-view');
    const actions = appView ? appView.querySelector('.nav-actions') : null;
    const signedIn = !!actions && !appView.classList.contains('hidden');
    button.classList.toggle('theme-toggle--inline', signedIn);
    button.classList.toggle('theme-toggle--floating', !signedIn);
    const target = signedIn ? actions : document.body;
    if (button.parentNode === target) return;
    const hadFocus = document.activeElement === button;
    if (signedIn) {
      actions.insertBefore(button, actions.firstChild);
    } else {
      document.body.appendChild(button);
    }
    if (hadFocus) button.focus();
  }

  function init() {
    if (document.querySelector('.theme-toggle')) return;
    lightOption = buildOption('☀', 'Light');
    darkOption = buildOption('☾', 'Dark');
    button = document.createElement('button');
    button.type = 'button';
    button.className = 'theme-toggle';
    button.setAttribute('role', 'switch');
    button.setAttribute('aria-label', 'Dark mode');
    button.appendChild(lightOption);
    button.appendChild(darkOption);
    button.addEventListener('click', () => {
      const next = theme === DARK ? LIGHT : DARK;
      saveTheme(next);
      applyTheme(next);
    });
    placeButton();
    applyTheme(theme);
    const appView = document.querySelector('#app-view');
    if (appView) {
      new MutationObserver(placeButton).observe(appView, { attributes: true, attributeFilter: ['class'] });
    }
  }

  // Set the theme before anything is drawn by this script, then add the stylesheet and the button.
  document.documentElement.setAttribute('data-theme', theme);
  loadStylesheet();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
