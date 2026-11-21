/**
 * ============================================================================
 * Nimbus FinServe :: Swiftdesk
 * Modern Single-Page Application Client (Linear / Vercel Aesthetic)
 * Self-Contained Vanilla ES6+ (Zero External CDN Dependencies, CSP Compliant)
 * ============================================================================
 */

// Application Global State
const STATE = {
  currentUser: null,
  demoMode: false,
  demoPassword: 'Password123!',
  activeTab: 'console-employee',
  activeAdminSubtab: 'admin-catalog-panel',
  techQueueMode: 'my', // 'my' or 'team'
  techFocusId: null, // a ticket the Technician tab should scroll to and highlight once drawn (Phase M4)
  // Technician portal (ADR-051): requestSeq discards a late answer, expandedId is the one open row of the queue table
  // (kept across reloads, so an action or a refresh does not close it).
  technician: { requestSeq: 0, expandedId: null },
  teamReassignOpening: false, // one "assign to a team member" dialog is being opened at a time (Phase M3)
  simulation: {
    virtualTime: null,
    speedMultiplier: 1
  },
  clockTimer: null,
  notificationsTimer: null,
  teamLeadTimer: null,
  // Team Lead tab (Phase M2): requestSeq discards a late answer, inFlight stops the timer from piling loads up,
  // hasRoster / hasQueues say whether something real is already on screen (then a reload never blanks it).
  teamLead: { requestSeq: 0, inFlight: 0, hasRoster: false, hasQueues: false },
  adminTicketDetail: { id: null, origin: null, requestSeq: 0 },
  suggestions: { incidentId: null, requestSeq: 0, promoteInFlight: false },
  // Employee portal (ADR-050): requestSeq discards a late answer, view is 'active' or 'history', selectedId is the
  // featured ticket (kept across reloads while it exists), submitInFlight guards the report form.
  employee: { requestSeq: 0, tickets: [], loaded: false, view: 'active', selectedId: null, submitInFlight: false }
};

// ============================================================================
// 1. Toast Notification System
// ============================================================================
function showToast(message, type = 'info', duration = 3500) {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;

  let iconSvg = '';
  if (type === 'success') {
    iconSvg = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-emerald"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7"/></svg>`;
  } else if (type === 'error') {
    iconSvg = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-red"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12"/></svg>`;
  } else if (type === 'warning') {
    iconSvg = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-amber"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/></svg>`;
  } else {
    iconSvg = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-cyan"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>`;
  }

  toast.innerHTML = `
    <div style="flex-shrink: 0;">${iconSvg}</div>
    <div style="flex: 1; font-size: 0.8125rem; line-height: 1.4;">${escapeHtml(message)}</div>
    <button type="button" style="background: none; border: none; color: #94a3b8; cursor: pointer; font-size: 1rem; line-height: 1;">&times;</button>
  `;

  toast.querySelector('button').addEventListener('click', () => toast.remove());
  container.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 250);
  }, duration);
}

// Utility: HTML Escaping
function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

// ADR-046: the sign-in endpoints answer 429 with a Retry-After header (whole seconds) when one network address
// tries too often. The fallback only applies if a proxy dropped the header.
const RATE_LIMIT_FALLBACK_SECONDS = 60;
function rateLimitMessage(res) {
  const seconds = parseInt(res.headers.get('Retry-After'), 10);
  const wait = Number.isFinite(seconds) && seconds > 0 ? seconds : RATE_LIMIT_FALLBACK_SECONDS;
  return `Too many attempts from this network. Try again in ${wait} second${wait === 1 ? '' : 's'}.`;
}

// Utility: staff ticket name (current priority + id, e.g. P2-FIN-2026-000142), shown alongside the plain id.
// Only staff API responses carry displayId; nothing is rendered when it is missing or would just repeat the id.
function ticketNameHtml(ticket, asBlock) {
  if (!ticket || !ticket.displayId || ticket.displayId === ticket.id) return '';
  const cls = asBlock ? 'ticket-display-name ticket-display-name-block' : 'ticket-display-name';
  return `<span class="${cls}" title="Ticket name with its current priority">${escapeHtml(ticket.displayId)}</span>`;
}

// ============================================================================
// 2. HTTP Client with Automatic JWT Handling
// ============================================================================
// In-flight guard for writes (ADR-017): the button that started a write is disabled until the request settles,
// so a double-click cannot send the same action twice. The last clicked button is tracked in the capture phase,
// before any handler runs; apiCall disables it synchronously, before its first await.
const WRITE_GUARD_WINDOW_MS = 1500;
const WRITE_COOLDOWN_MS = 600;
const CONFLICT_CODE = 'ERR_CONCURRENT_MODIFICATION';
const STALE_STATE_CODE = 'ERR_INVALID_STATE_TRANSITION';
let lastClickedButton = null;
let lastClickAt = 0;
let refreshAfterFailureScheduled = false;
// Sign-in and registration are not writes through apiCall, so they carry their own guard (ADR-039): a second submit
// (double click, Enter twice, a demo pill) while one is in flight is ignored.
let loginInFlight = false;
let registerInFlight = false;

document.addEventListener('click', (event) => {
  const button = event.target && event.target.closest ? event.target.closest('button') : null;
  if (button && !button.disabled) {
    lastClickedButton = button;
    lastClickAt = Date.now();
  }
}, true);

function beginWrite() {
  const button = lastClickedButton;
  if (!button || !button.isConnected || Date.now() - lastClickAt > WRITE_GUARD_WINDOW_MS) {
    return () => {};
  }
  const hadFocus = document.activeElement === button;
  button.dataset.busyCount = String(Number(button.dataset.busyCount || 0) + 1);
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  return () => {
    const remaining = Number(button.dataset.busyCount || 1) - 1;
    if (remaining > 0) {
      button.dataset.busyCount = String(remaining);
      return;
    }
    delete button.dataset.busyCount;
    // Stay disabled briefly after the request settles: the list is about to be re-rendered, and a click that lands
    // in that gap (a human double-click is 100-250 ms) would act on a stale card.
    setTimeout(() => {
      if (button.dataset.busyCount) return; // another write started in the meantime and owns the button now
      button.removeAttribute('aria-busy');
      if (button.isConnected) button.disabled = false;
      if (hadFocus) restoreFocus(button);
    }, WRITE_COOLDOWN_MS);
  };
}

// A disabled button loses keyboard focus. When it is enabled again the focus goes back to it, or to its twin when the
// list was re-rendered in the meantime (same action class, same ticket id), unless the user already moved on (ADR-039).
function restoreFocus(button) {
  const active = document.activeElement;
  if (active && active !== document.body) return;
  let target = button.isConnected ? button : null;
  const id = button.getAttribute('data-id');
  if (!target && id !== null) {
    const action = [...button.classList].find(c => /^btn-(tech|emp|admin|teamlead|triage)-/.test(c));
    if (action) target = document.querySelector(`button.${action}[data-id="${CSS.escape(id)}"]`);
  }
  if (target && !target.disabled) target.focus({ preventScroll: true });
}

// A write that lost a race (409), hit a vanished ticket (404), was refused because the ticket changed (400, 403) or
// failed on the server (5xx) leaves the screen stale.
function refreshAfterFailedWrite(status) {
  if (refreshAfterFailureScheduled || !(status === 400 || status === 403 || status === 409 || status === 404 || status >= 500)) return;
  refreshAfterFailureScheduled = true;
  setTimeout(() => {
    refreshAfterFailureScheduled = false;
    refreshActiveTab();
  }, 0);
}

function failureMessage(status, body) {
  if (body.code === CONFLICT_CODE) {
    return 'This record was changed by someone else at the same time. The list has been refreshed; please try again.';
  }
  if (body.code === STALE_STATE_CODE) {
    return 'This ticket was just updated by someone else, so that action no longer applies. The list has been refreshed.';
  }
  return body.message || `Request failed with status ${status}`;
}

// Writes in flight, whatever button started them. The Team Lead tab's timer does not reload under a running write.
let pendingWrites = 0;

// ADR-047: the access token lives 15 minutes. When a call is answered 401 and the session holds a refresh token, the
// session is renewed once (one shared request, however many calls fail at the same moment) and the call is repeated once.
const SESSION_STORAGE_KEY = 'iitsm_session';
let refreshInFlight = null;

function storeSession(user) {
  try {
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(user));
  } catch (e) {
    // Storage can be blocked; the session then lasts until the page is closed
  }
}

// Resolves true when the session now has fresh tokens. A refresh token can be used once, so concurrent callers must
// share this one promise instead of each sending the same token.
function refreshSession() {
  if (refreshInFlight) return refreshInFlight;
  const used = STATE.currentUser && STATE.currentUser.refreshToken;
  if (!used) return Promise.resolve(false);
  refreshInFlight = (async () => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: used })
      });
      if (!res.ok) return false;
      const data = await res.json();
      // The user may have signed out, or in as someone else, while the request was running
      if (!STATE.currentUser || STATE.currentUser.refreshToken !== used) return false;
      STATE.currentUser = { ...STATE.currentUser, token: data.token, refreshToken: data.refreshToken };
      storeSession(STATE.currentUser);
      return true;
    } catch (e) {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

// fetch with the bearer token and one renewal-and-repeat on 401 (never for /api/auth/ calls, never without a refresh token)
async function authorizedFetch(endpoint, options = {}) {
  const send = async () => {
    const sentToken = STATE.currentUser ? STATE.currentUser.token : null;
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (sentToken) headers['Authorization'] = `Bearer ${sentToken}`;
    return { res: await fetch(endpoint, { ...options, headers }), sentToken };
  };
  const first = await send();
  const canRenew = first.res.status === 401 && !endpoint.startsWith('/api/auth/')
    && STATE.currentUser && STATE.currentUser.refreshToken;
  if (!canRenew) return first.res;
  // Another call may have renewed the session while this one was on its way: then just repeat with the new token
  const alreadyRenewed = STATE.currentUser && STATE.currentUser.token !== first.sentToken;
  if (!alreadyRenewed && !(await refreshSession())) return first.res;
  return (await send()).res;
}

async function apiCall(endpoint, options = {}) {
  const isWrite = String(options.method || 'GET').toUpperCase() !== 'GET';
  const releaseWrite = isWrite ? beginWrite() : () => {};
  if (isWrite) pendingWrites++;

  try {
    const res = await authorizedFetch(endpoint, options);
    if (res.status === 401) {
      console.warn('Authentication token expired or unauthorized.');
      handleSignOut(false);
      showToast('Session expired. Please sign in again.', 'warning');
      throw new Error('Unauthorized');
    }
    if (!res.ok) {
      const body = await res.json().catch(() => ({ message: res.statusText || 'Server Error' }));
      const failure = new Error(failureMessage(res.status, body));
      failure.status = res.status;
      failure.code = body.code;
      if (isWrite) refreshAfterFailedWrite(res.status);
      throw failure;
    }
    const contentType = res.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      return await res.json();
    }
    return null;
  } catch (err) {
    if (err.message !== 'Unauthorized') {
      console.error(`API Error on ${endpoint}:`, err);
    }
    throw err;
  } finally {
    releaseWrite();
    if (isWrite) pendingWrites--;
  }
}

// ============================================================================
// 3. Application Initialization & Authentication Flow
// ============================================================================
document.addEventListener('DOMContentLoaded', async () => {
  setupEventListeners();
  await loadPublicConfig();
  checkExistingSession();
});

async function loadPublicConfig() {
  try {
    const config = await fetch('/api/public/config').then(r => r.json());
    STATE.demoMode = !!config.demoMode;
    if (STATE.demoMode) {
      document.getElementById('demo-accounts-container').classList.remove('hidden');
      document.getElementById('persona-switcher-bar').classList.remove('hidden');
    } else {
      document.getElementById('demo-accounts-container').classList.add('hidden');
      document.getElementById('persona-switcher-bar').classList.add('hidden');
    }
  } catch (err) {
    console.warn('Failed to load /api/public/config, defaulting demoMode to false');
  }
}

function checkExistingSession() {
  const stored = sessionStorage.getItem('iitsm_session');
  if (stored) {
    try {
      const user = JSON.parse(stored);
      if (user && user.token) {
        setAuthenticatedUser(user);
        return;
      }
    } catch (e) {
      sessionStorage.removeItem('iitsm_session');
    }
  }
  showAuthView();
}

function showAuthView() {
  document.getElementById('auth-view').classList.remove('hidden');
  // Signing out after a registration must show the sign-in card, not the registration card that was open before
  document.getElementById('login-card').classList.remove('hidden');
  document.getElementById('register-card').classList.add('hidden');
  document.getElementById('app-view').classList.add('hidden');
  stopBackgroundPolling();
}

function showAppView() {
  document.getElementById('auth-view').classList.add('hidden');
  document.getElementById('app-view').classList.remove('hidden');
  startBackgroundPolling();
}

function setAuthenticatedUser(user, switchDefaultTab = true) {
  STATE.currentUser = user;
  storeSession(user);

  // Update navbar user profile
  const securityButton = document.getElementById('btn-security');
  if (securityButton) securityButton.classList.toggle('hidden', user.role !== 'ROLE_ADMIN');
  document.getElementById('user-display-name').textContent = user.name || user.email;
  document.getElementById('user-display-role').textContent = user.role || 'ROLE_USER';
  document.getElementById('user-avatar-badge').textContent = (user.name ? user.name.charAt(0) : 'U').toUpperCase();

  // Enforce persona isolation: Show ONLY the console(s) belonging to this specific user role
  updateNavTabsForRole(user.role);

  showAppView();

  // Switch natural starting tab based on role if requested
  if (switchDefaultTab) {
    if (user.role === 'ROLE_EMPLOYEE') {
      switchTab('console-employee');
    } else if (user.role === 'ROLE_TECHNICIAN') {
      switchTab('console-technician');
    } else if (user.role === 'ROLE_TEAM_LEAD') {
      switchTab('console-teamlead');
    } else if (user.role === 'ROLE_ADMIN') {
      switchTab('console-admin');
    } else {
      switchTab('console-employee');
    }
  }

  showToast(`Signed in: ${user.name || user.email} (${user.role})`, 'info');
}

function updateNavTabsForRole(role) {
  const tabs = document.querySelectorAll('.nav-tab');
  tabs.forEach(tab => {
    const target = tab.getAttribute('data-target');
    let visible = false;
    if (role === 'ROLE_EMPLOYEE') {
      visible = (target === 'console-employee');
    } else if (role === 'ROLE_TECHNICIAN') {
      visible = (target === 'console-technician');
    } else if (role === 'ROLE_TEAM_LEAD') {
      visible = (target === 'console-technician' || target === 'console-teamlead');
    } else if (role === 'ROLE_ADMIN') {
      visible = (target === 'console-admin' || target === 'console-simulation' || target === 'console-reports');
    }
    if (visible) {
      tab.classList.remove('hidden');
      tab.style.display = 'inline-flex';
    } else {
      tab.classList.add('hidden');
      tab.style.display = 'none';
    }
  });

  // Discrete advance buttons (+15m, +1h, +24h) require ROLE_ADMIN
  const simHud = document.querySelector('.sim-hud');
  if (simHud) {
    // Employees do not see the simulation clock (ADR-055); other roles are unchanged.
    simHud.style.display = (role === 'ROLE_EMPLOYEE') ? 'none' : '';
    const simBtns = simHud.querySelectorAll('.sim-ff-btn');
    simBtns.forEach(btn => {
      btn.style.display = (role === 'ROLE_ADMIN') ? 'inline-block' : 'none';
    });
  }

  const personaBar = document.getElementById('persona-switcher-bar');
  if (personaBar) {
    personaBar.classList.add('hidden');
    personaBar.style.display = 'none';
  }
}

async function loginUser(email, password, switchDefaultTab = true) {
  if (loginInFlight) return;
  loginInFlight = true;
  const errorAlert = document.getElementById('login-error-alert');
  if (errorAlert) {
    errorAlert.classList.add('hidden');
    errorAlert.innerHTML = '';
  }

  const submitBtn = document.getElementById('btn-login-submit');
  const originalBtnHtml = submitBtn ? submitBtn.innerHTML : '';
  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.innerHTML = `<span>Signing in...</span>`;
  }

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: (email || '').trim(), password: password || '' })
    });
    if (res.status === 429) {
      throw new Error(rateLimitMessage(res));
    }
    if (!res.ok) {
      let message = 'Invalid corporate email or password.';
      try {
        const err = await res.json();
        message = err.message || err.error || message;
      } catch (e) {
        if (res.status === 401 || res.status === 403) {
          message = 'Invalid corporate email or password. Please verify and retry.';
        } else {
          message = `Server returned status ${res.status}. Please check backend logs.`;
        }
      }
      throw new Error(message);
    }
    const data = await res.json();
    if (errorAlert) {
      errorAlert.classList.add('hidden');
    }
    if (data.mfaRequired) {
      showMfaStep(data.mfaToken);
      return;
    }
    setAuthenticatedUser({
      token: data.token,
      refreshToken: data.refreshToken,
      id: data.id,
      name: data.name,
      email: data.email,
      role: data.role
    }, switchDefaultTab);
  } catch (err) {
    const errorMsg = err.message || 'Invalid corporate email or password';
    if (errorAlert) {
      errorAlert.innerHTML = `
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-red" style="flex-shrink:0; margin-top: 0.1rem;">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
        </svg>
        <div>
          <div style="font-weight: 700; color: var(--danger-text); margin-bottom: 0.2rem;">Sign-in Failed</div>
          <div style="color: var(--danger-text); font-size: 0.8125rem;">${escapeHtml(errorMsg)}</div>
        </div>
      `;
      errorAlert.classList.remove('hidden');
    }
    const pwdInput = document.getElementById('login-password');
    if (pwdInput) {
      pwdInput.focus();
      pwdInput.select();
    }
    showToast(errorMsg, 'error');
  } finally {
    loginInFlight = false;
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.innerHTML = originalBtnHtml;
    }
  }
}

// ADR-047: ends the session on the server too (the refresh token stops working at once, and so does the access token).
// keepalive lets the request finish while the page is closing; a failure is not the user's problem, the tokens expire anyway.
function endServerSession(refreshToken) {
  if (!refreshToken) return;
  try {
    fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      keepalive: true
    }).catch(() => {});
  } catch (e) {
    // Nothing to do: signing out locally still happens below
  }
}

function handleSignOut(notify = true) {
  endServerSession(STATE.currentUser && STATE.currentUser.refreshToken);
  // The Security dialog can show a secret or recovery codes: it must not outlive the session (ADR-048)
  if (document.getElementById('security-modal')) closeModal();
  closeAdminTicketDetailModal({ restoreFocus: false });
  closeTeamMemberTickets();
  resetStrategyComparison();
  resetTeamLeadView();
  resetTechnicianView();
  resetEmployeeView();
  resetAdminFilterOptions();
  STATE.currentUser = null;
  sessionStorage.removeItem('iitsm_session');
  hideMfaStep();
  const securityButton = document.getElementById('btn-security');
  if (securityButton) securityButton.classList.add('hidden');
  showAuthView();
  if (notify) showToast('Signed out successfully.', 'info');
}

// ============================================================================
// 4. Tab Navigation & View Management
// ============================================================================
async function switchTab(targetId) {
  document.querySelectorAll('.nav-tab').forEach(tab => {
    if (tab.getAttribute('data-target') === targetId) {
      tab.classList.add('active');
    } else {
      tab.classList.remove('active');
    }
  });

  document.querySelectorAll('.tab-panel').forEach(panel => {
    if (panel.id === targetId) {
      panel.classList.add('active');
    } else {
      panel.classList.remove('active');
    }
  });

  STATE.activeTab = targetId;
  refreshActiveTab();
}

function refreshActiveTab() {
  if (!STATE.currentUser) return;
  switch (STATE.activeTab) {
    case 'console-employee':
      loadEmployeeTickets();
      break;
    case 'console-technician':
      loadTechnicianQueue();
      loadTechnicianDashboard();
      break;
    case 'console-teamlead':
      loadTeamLeadView();
      break;
    case 'console-admin':
      loadAdminView();
      break;
    case 'console-simulation':
      loadSimulationStatus();
      break;
    case 'console-reports':
      loadExecutiveReports();
      break;
  }
}

// ============================================================================
// 5. Background Polling (Simulation Clock & Notifications)
// ============================================================================
function startBackgroundPolling() {
  stopBackgroundPolling();
  updateSimulationClock();
  updateNotifications();
  STATE.clockTimer = setInterval(updateSimulationClock, 2500);
  STATE.notificationsTimer = setInterval(updateNotifications, 10000);
  STATE.teamLeadTimer = setInterval(autoRefreshTeamLead, TEAM_LEAD_REFRESH_MS);
}

function stopBackgroundPolling() {
  if (STATE.clockTimer) clearInterval(STATE.clockTimer);
  if (STATE.notificationsTimer) clearInterval(STATE.notificationsTimer);
  if (STATE.teamLeadTimer) clearInterval(STATE.teamLeadTimer);
}

async function updateSimulationClock() {
  if (!STATE.currentUser || STATE.currentUser.role !== 'ROLE_ADMIN') {
    return;
  }
  try {
    const res = await authorizedFetch('/api/simulation/status');
    if (!res.ok) return;
    const data = await res.json();
    STATE.simulation.virtualTime = data.virtualTime;
    STATE.simulation.speedMultiplier = data.speedMultiplier;

    // Format virtual clock
    const d = new Date(data.virtualTime);
    const timeStr = isNaN(d.getTime()) ? data.virtualTime : d.toISOString().replace('T', ' ').substring(0, 19);
    
    const timeDisplay = document.getElementById('virtual-time-display');
    if (timeDisplay) timeDisplay.textContent = timeStr;

    const speedBadge = document.getElementById('virtual-speed-badge');
    if (speedBadge) speedBadge.textContent = `${data.speedMultiplier}x`;

    const simEpoch = document.getElementById('sim-clock-epoch');
    if (simEpoch) simEpoch.textContent = timeStr;
  } catch (e) {
    // Silent catch for background polling
  }
}

async function updateNotifications() {
  if (!STATE.currentUser) return;
  try {
    const notifs = await apiCall('/api/notifications');
    if (Array.isArray(notifs)) {
      const unreadCount = notifs.filter(n => !n.read && !n.delivered).length;
      const badge = document.getElementById('unread-count-badge');
      if (badge) {
        if (unreadCount > 0) {
          badge.textContent = unreadCount;
          badge.classList.remove('hidden');
        } else {
          badge.classList.add('hidden');
        }
      }
    }
  } catch (e) {
    // Non-blocking
  }
}

// ============================================================================
// 6. Console 1: Employee Portal Logic
// ============================================================================
// Employee portal (ADR-050). One list from GET /api/employee/incidents, shown as client-side views. Everything drawn
// here comes from EmployeeIncidentView (id, title, description, public status, assignee, createdTime, resolutionText,
// auditTrail, allowedActions, reopenCount): there is no priority, SLA or internal note to show, by type (rule 12).
const EMP_FINISHED_STATUSES = ['RESOLVED', 'CLOSED'];
const EMP_STEPS = [
  { status: 'NEW', label: 'Reported', actions: ['IncidentCreated'] },
  { status: 'ASSIGNED', label: 'Assigned', actions: ['IncidentAssigned'] },
  { status: 'IN_PROGRESS', label: 'In Progress',
    actions: ['IncidentAcknowledged', 'InvestigationStarted', 'MoreInfoRequested', 'IncidentReopened'] },
  { status: 'RESOLVED', label: 'Resolved', actions: ['IncidentResolved', 'ResolutionVerified'] },
  { status: 'CLOSED', label: 'Closed', actions: ['IncidentClosed'] }
];

async function loadEmployeeTickets() {
  const container = document.getElementById('emp-tickets-container');
  if (!container) return;
  const seq = ++STATE.employee.requestSeq;
  if (!STATE.employee.loaded) {
    container.innerHTML = '<div class="emp-empty">Loading your tickets...</div>';
  }
  try {
    const tickets = await apiCall('/api/employee/incidents');
    // A late answer (an older request, or one from before a sign-out) must not overwrite newer data
    if (seq !== STATE.employee.requestSeq || !STATE.currentUser) return;
    STATE.employee.tickets = Array.isArray(tickets) ? tickets : [];
    STATE.employee.loaded = true;
    renderEmployeeTickets(STATE.employee.tickets);
  } catch (err) {
    if (seq !== STATE.employee.requestSeq || !STATE.currentUser) return;
    container.innerHTML = `<div class="emp-empty text-red">Failed to load tickets: ${escapeHtml(err.message)}</div>`;
  }
}

// Forget what is on screen (sign-out): the next sign-in starts empty and an answer still on its way is discarded.
function resetEmployeeView() {
  STATE.employee.requestSeq++;
  STATE.employee.tickets = [];
  STATE.employee.loaded = false;
  STATE.employee.view = 'active';
  STATE.employee.selectedId = null;
  STATE.employee.submitInFlight = false;
  ['emp-tickets-container', 'emp-featured', 'emp-tracking'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.innerHTML = '';
  });
  updateEmployeeStats([]);
}

// An employee sees the public status only (a ticket waiting for their reply is "IN_PROGRESS"), so what they may do
// comes from allowedActions, which the server sends (ADR-039); the status 'PENDING_INFO' never reaches this screen.
function awaitsMyReply(ticket) {
  return (ticket.allowedActions || []).includes('provideInfo');
}

function empIsOpen(ticket) {
  return !EMP_FINISHED_STATUSES.includes(ticket.status);
}

function empTime(iso) {
  const t = iso ? new Date(iso).getTime() : NaN;
  return isNaN(t) ? 0 : t;
}

function empNewestFirst(tickets) {
  return [...tickets].sort((a, b) => empTime(b.createdTime) - empTime(a.createdTime));
}

function empPercent(count, total) {
  return total > 0 ? Math.round((count / total) * 100) : 0;
}

function updateEmployeeStats(tickets) {
  const total = tickets.length;
  const counts = {
    total,
    active: tickets.filter(t => empIsOpen(t) && !awaitsMyReply(t)).length,
    pending: tickets.filter(awaitsMyReply).length,
    resolved: tickets.filter(t => !empIsOpen(t)).length
  };
  Object.keys(counts).forEach(key => {
    const value = document.getElementById(`emp-stat-${key}`);
    if (value) value.textContent = counts[key];
    const pct = empPercent(counts[key], total);
    const bar = document.getElementById(`emp-bar-${key}`);
    if (bar) {
      bar.style.width = `${pct}%`;
      bar.parentElement.setAttribute('aria-valuenow', String(pct));
    }
    const label = document.getElementById(`emp-pct-${key}`);
    if (label) label.textContent = `${pct}% of my tickets`;
  });
  const open = total - counts.resolved;
  const setText = (id, text) => {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
  };
  setText('emp-count-active', String(open));
  setText('emp-count-history', String(counts.resolved));
  setText('emp-history-button-count', String(counts.resolved));
}

// The ticket the featured card shows: the one already selected while it still exists, otherwise the newest ticket
// awaiting my reply, else the newest open ticket, else the newest ticket.
function empPickSelection(tickets) {
  const current = tickets.find(t => t.id === STATE.employee.selectedId);
  if (current) return current;
  const sorted = empNewestFirst(tickets);
  return sorted.find(awaitsMyReply) || sorted.find(empIsOpen) || sorted[0] || null;
}

function empTicketsInView(tickets) {
  const wanted = STATE.employee.view === 'history' ? t => !empIsOpen(t) : empIsOpen;
  return empNewestFirst(tickets.filter(wanted));
}

function renderEmployeeTickets(tickets) {
  updateEmployeeStats(tickets);
  const selected = empPickSelection(tickets);
  STATE.employee.selectedId = selected ? selected.id : null;
  empRenderSubnav();
  empRenderList(empTicketsInView(tickets));
  empRenderFeatured(selected);
  empRenderTracking(selected);
}

function empRenderSubnav() {
  document.querySelectorAll('.emp-subnav-btn[data-emp-view]').forEach(button => {
    const view = button.getAttribute('data-emp-view');
    button.setAttribute('aria-pressed', String(view === STATE.employee.view));
  });
  const title = document.getElementById('emp-list-title');
  if (title) title.textContent = STATE.employee.view === 'history' ? 'My History' : 'My Active Tickets';
  const subtitle = document.getElementById('emp-list-subtitle');
  if (subtitle) {
    subtitle.textContent = STATE.employee.view === 'history'
      ? 'Resolved and closed tickets' : 'Tickets still being worked on';
  }
}

function empStatusBadge(status) {
  const value = String(status || 'NEW');
  return `<span class="badge badge-status-${escapeHtml(value.toLowerCase().replace(/[_ ]/g, '-'))}">${escapeHtml(value.replace(/_/g, ' '))}</span>`;
}

// "5 min ago", "3 h ago", "2 days ago"; older dates, and dates ahead of the browser (the simulation clock), show the date.
function empRelativeTime(iso) {
  const time = empTime(iso);
  if (!time) return '--';
  const seconds = Math.round((Date.now() - time) / 1000);
  if (seconds < 0) return formatDate(iso);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  const days = Math.floor(seconds / 86400);
  if (days < 30) return `${days} day${days === 1 ? '' : 's'} ago`;
  return formatDate(iso);
}

function empRenderList(tickets) {
  const container = document.getElementById('emp-tickets-container');
  if (!container) return;
  if (tickets.length === 0) {
    const message = STATE.employee.view === 'history'
      ? 'No resolved or closed tickets yet.'
      : 'No open tickets. Use Report Issue to ask the IT service desk for help.';
    container.innerHTML = `<div class="emp-empty">${message}</div>`;
    return;
  }
  container.innerHTML = tickets.map(t => {
    const selected = t.id === STATE.employee.selectedId;
    return `
      <button type="button" class="emp-ticket-row btn-emp-select${selected ? ' is-selected' : ''}" data-id="${escapeHtml(t.id)}" aria-pressed="${selected}">
        <span class="emp-row-top">
          <span class="font-mono font-bold text-cyan text-xs">${escapeHtml(t.id)}</span>
          ${empStatusBadge(t.status)}
          ${awaitsMyReply(t) ? '<span class="emp-reply-pill">Your reply needed</span>' : ''}
        </span>
        <span class="emp-row-title">${escapeHtml(t.title)}</span>
        <span class="emp-row-time" title="${escapeHtml(formatDate(t.createdTime))}">${escapeHtml(empRelativeTime(t.createdTime))}</span>
      </button>`;
  }).join('');
  container.querySelectorAll('.btn-emp-select').forEach(row => {
    row.addEventListener('click', () => empSelectTicket(row.getAttribute('data-id')));
  });
}

function empSelectTicket(id) {
  if (!STATE.employee.tickets.some(t => t.id === id)) return;
  STATE.employee.selectedId = id;
  renderEmployeeTickets(STATE.employee.tickets);
  const row = document.querySelector(`.btn-emp-select[data-id="${CSS.escape(id)}"]`);
  if (row) row.focus({ preventScroll: true });
}

// Newest audit entry of one of the given actions, or null.
function empNewestEntry(ticket, actions) {
  return (ticket.auditTrail || [])
    .filter(r => actions.includes(r.action))
    .sort((a, b) => empTime(b.timestamp) - empTime(a.timestamp))[0] || null;
}

function empLastUpdate(ticket) {
  const newest = (ticket.auditTrail || []).reduce((best, r) => Math.max(best, empTime(r.timestamp)), 0);
  return newest ? new Date(newest).toISOString() : ticket.createdTime;
}

// The buttons follow allowedActions only, never the status (ADR-039): the server decides what an employee may do.
function empFeaturedActionsHtml(t) {
  const allowed = t.allowedActions || [];
  const id = escapeHtml(t.id);
  return [
    awaitsMyReply(t) ? `<button type="button" class="btn btn-sm btn-warning btn-emp-provide-info" data-id="${id}">Provide info</button>` : '',
    allowed.includes('verify') ? `<button type="button" class="btn btn-sm btn-success btn-emp-verify" data-id="${id}">Confirm Fixed</button>` : '',
    allowed.includes('reject') ? `<button type="button" class="btn btn-sm btn-danger btn-emp-reject" data-id="${id}">Reject fix</button>` : ''
  ].join('');
}

function empRenderFeatured(t) {
  const container = document.getElementById('emp-featured');
  if (!container) return;
  if (!t) {
    container.innerHTML = `
      <div class="emp-empty">
        <p class="font-semibold text-main">No tickets reported yet</p>
        <p class="text-xs text-muted">Use Report Issue to submit an IT support request.</p>
      </div>`;
    return;
  }
  const question = awaitsMyReply(t) ? empNewestEntry(t, ['MoreInfoRequested']) : null;
  const actions = empFeaturedActionsHtml(t);
  container.innerHTML = `
    <div class="emp-featured-head">
      <span class="font-mono font-bold text-cyan" id="emp-featured-id">${escapeHtml(t.id)}</span>
      ${empStatusBadge(t.status)}
      <span class="text-xs text-dim" title="${escapeHtml(formatDate(t.createdTime))}">Raised ${escapeHtml(empRelativeTime(t.createdTime))}</span>
    </div>
    <h3 class="emp-featured-title">${escapeHtml(t.title)}</h3>
    <p class="emp-featured-desc">${escapeHtml(t.description)}</p>
    ${question ? `
      <div class="emp-callout-reply">
        <span class="emp-info-label">The technician asked</span>
        <p>${escapeHtml(question.description)}</p>
      </div>` : ''}
    ${t.resolutionText ? `
      <div class="emp-resolution">
        <span class="emp-info-label">Resolution</span>
        <p>${escapeHtml(t.resolutionText)}</p>
      </div>` : ''}
    <div class="emp-info-grid">
      <div class="emp-info-box">
        <span class="emp-info-label">Assigned to</span>
        <span class="emp-info-value">${escapeHtml(t.assigneeName || 'Awaiting assignment')}</span>
      </div>
      <div class="emp-info-box">
        <span class="emp-info-label">Last update</span>
        <span class="emp-info-value" title="${escapeHtml(formatDate(empLastUpdate(t)))}">${escapeHtml(empRelativeTime(empLastUpdate(t)))}</span>
      </div>
    </div>
    ${actions ? `<div class="emp-featured-actions">${actions}</div>` : ''}`;

  container.querySelectorAll('.btn-emp-provide-info').forEach(b => {
    b.addEventListener('click', () => openProvideInfoModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-emp-verify').forEach(b => {
    b.addEventListener('click', () => openVerifyModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-emp-reject').forEach(b => {
    b.addEventListener('click', () => openRejectModal(b.getAttribute('data-id')));
  });
}

function empCurrentStep(status) {
  const index = EMP_STEPS.findIndex(s => s.status === status);
  return index < 0 ? 1 : index + 1;
}

function empRenderTracking(t) {
  const container = document.getElementById('emp-tracking');
  if (!container) return;
  if (!t) {
    container.innerHTML = '<div class="emp-empty">Select a ticket to follow its progress.</div>';
    return;
  }
  const current = empCurrentStep(t.status);
  const steps = EMP_STEPS.map((step, i) => {
    const number = i + 1;
    // The last step is reached for good, not in progress, so it shows as completed
    const state = number < current || (number === current && number === EMP_STEPS.length)
      ? 'completed' : (number === current ? 'active' : 'pending');
    const entry = number <= current ? empNewestEntry(t, step.actions) : null;
    return `
      <li class="emp-track-step is-${state}" data-step="${number}">
        <span class="emp-track-dot" aria-hidden="true">${state === 'completed' ? '&#10003;' : number}</span>
        <div class="emp-track-body">
          <div class="emp-track-label">
            <span>${step.label}</span>
            ${state === 'active' ? '<span class="emp-active-pill">ACTIVE</span>' : ''}
          </div>
          ${entry ? `
            <span class="emp-track-time font-mono">${escapeHtml(formatDate(entry.timestamp))}</span>
            <span class="emp-track-text">${escapeHtml(entry.description)}</span>` : ''}
        </div>
      </li>`;
  }).join('');
  const history = [...(t.auditTrail || [])]
    .sort((a, b) => empTime(b.timestamp) - empTime(a.timestamp))
    .map(r => `
      <li><span class="font-mono text-dim">${escapeHtml(formatDate(r.timestamp))}</span>
        <span>${escapeHtml(r.description)}</span></li>`).join('');

  container.innerHTML = `
    <div class="emp-track-head">
      <span class="font-mono text-xs text-cyan">${escapeHtml(t.id)}</span>
      <span class="emp-track-progress" id="emp-track-progress">${current * 20}% complete</span>
    </div>
    <div class="emp-track-bar" role="progressbar" aria-label="Ticket progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${current * 20}">
      <span style="width: ${current * 20}%"></span>
    </div>
    ${t.reopenCount > 0 ? `<p class="emp-reopened">Reopened ${Number(t.reopenCount)} time(s)</p>` : ''}
    <ol class="emp-track-steps">${steps}</ol>
    <details class="emp-activity">
      <summary>Activity history (${(t.auditTrail || []).length})</summary>
      ${history ? `<ul>${history}</ul>` : '<p class="text-xs text-dim">No updates recorded yet.</p>'}
    </details>`;
}

function setEmployeeView(view) {
  STATE.employee.view = view === 'history' ? 'history' : 'active';
  const inView = empTicketsInView(STATE.employee.tickets);
  // Keep the featured ticket when it belongs to the chosen view, otherwise feature the newest ticket of the view
  if (inView.length > 0 && !inView.some(t => t.id === STATE.employee.selectedId)) {
    STATE.employee.selectedId = inView[0].id;
  }
  renderEmployeeTickets(STATE.employee.tickets);
}

function focusEmployeeReportForm() {
  const card = document.getElementById('emp-report-card');
  if (card) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
  const title = document.getElementById('emp-inc-title');
  if (title) title.focus({ preventScroll: true });
}

// Quick-fill texts for the demonstration. Each is pinned by EmployeePortalMarkupTest to the category whose team the
// chip names (KeywordClassifier lexicon), so the label tells the truth about where the ticket goes.
const EMP_TEMPLATES = {
  vpn: { title: 'VPN tunnel keeps dropping from the London gateway',
    description: 'My laptop loses the VPN tunnel to the London gateway every few minutes; the AnyConnect client shows a handshake timeout and the wifi itself is fine.' },
  firewall: { title: 'Firewall blocking outbound traffic to the market data feed',
    description: 'The perimeter firewall drops outbound packets to the market data feed since this morning; a packet capture shows the block on the router interface.' },
  db: { title: 'PostgreSQL deadlock timeout on the core banking database',
    description: 'The HikariCP pool is exhausted and the PostgreSQL database reports deadlock errors on every transaction during the trade batch.' },
  payment: { title: 'Payment portal checkout fails during settlement',
    description: 'The customer payment portal shows an error at checkout for card settlements and the billing page times out for every invoice.' },
  sso: { title: 'SSO login fails after password reset',
    description: 'After the password reset the SSO login rejects my credentials and the MFA prompt never arrives; Active Directory says the account is fine.' },
  display: { title: 'Laptop display flickering on the docking station',
    description: 'The external monitor connected through the laptop dock flickers with horizontal lines; the laptop screen and the HDMI cable were already swapped.' }
};

function applyEmployeeTemplate(type) {
  const template = EMP_TEMPLATES[type];
  const titleEl = document.getElementById('emp-inc-title');
  const descEl = document.getElementById('emp-inc-desc');
  if (!template || !titleEl || !descEl) return;
  titleEl.value = template.title;
  descEl.value = template.description;
  descEl.dispatchEvent(new Event('input'));
  titleEl.focus({ preventScroll: true });
}

function updateEmployeeCharCount() {
  const descEl = document.getElementById('emp-inc-desc');
  const counter = document.getElementById('desc-char-count');
  if (descEl && counter) counter.textContent = `${descEl.value.length} / 4000`;
}

// Raise a ticket. A second submit while one is running is ignored (Enter twice does not go through the click guard).
async function submitEmployeeIncident(form) {
  if (STATE.employee.submitInFlight) return;
  const title = document.getElementById('emp-inc-title').value.trim();
  const description = document.getElementById('emp-inc-desc').value.trim();
  if (title.length < 5 || description.length < 20) {
    showToast('Please give a title of at least 5 characters and a description of at least 20.', 'warning');
    return;
  }
  STATE.employee.submitInFlight = true;
  const submit = document.getElementById('btn-submit-incident');
  if (submit) submit.setAttribute('aria-busy', 'true');
  try {
    const created = await apiCall('/api/employee/incidents', {
      method: 'POST',
      body: JSON.stringify({ title, description })
    });
    showToast('Ticket submitted successfully!', 'success');
    form.reset();
    updateEmployeeCharCount();
    if (created && created.id) {
      STATE.employee.selectedId = created.id;
      STATE.employee.view = empIsOpen(created) ? 'active' : 'history';
    }
    await loadEmployeeTickets();
  } catch (err) {
    showToast(err.message, 'error');
  } finally {
    STATE.employee.submitInFlight = false;
    if (submit) submit.removeAttribute('aria-busy');
  }
}

function setupEmployeePortalListeners() {
  const createForm = document.getElementById('form-create-incident');
  if (createForm) {
    createForm.addEventListener('submit', (e) => {
      e.preventDefault();
      submitEmployeeIncident(createForm);
    });
  }
  const descEl = document.getElementById('emp-inc-desc');
  if (descEl) descEl.addEventListener('input', updateEmployeeCharCount);
  document.querySelectorAll('.template-chip').forEach(chip => {
    chip.addEventListener('click', () => applyEmployeeTemplate(chip.getAttribute('data-template')));
  });
  document.querySelectorAll('.emp-subnav-btn[data-emp-view]').forEach(button => {
    button.addEventListener('click', () => setEmployeeView(button.getAttribute('data-emp-view')));
  });
  const report = document.getElementById('emp-nav-report');
  if (report) report.addEventListener('click', focusEmployeeReportForm);
  const history = document.getElementById('btn-emp-open-history');
  if (history) history.addEventListener('click', () => setEmployeeView('history'));
  const refresh = document.getElementById('btn-refresh-emp-tickets');
  if (refresh) refresh.addEventListener('click', loadEmployeeTickets);
}

// ============================================================================
// 7. Console 2: Technician Console Logic
// ============================================================================
// Technician portal (ADR-051, front end only). The tab shows the capacity banner, four counters, a spotlight for the
// most urgent ticket in progress and the queue as a table whose rows expand into the full detail and the action bar.
// Which buttons appear is still decided by the server (`allowedActions`, `promotable`, `reassignable`), never here.
const TP_LOAD_MODERATE_PCT = 40;
const TP_LOAD_HEAVY_PCT = 75;
const TP_NOTES_SHOWN = 3;
const TP_DESCRIPTION_CLIP = 220;
const TP_MS_MINUTE = 60000;
const TP_MINUTES_PER_HOUR = 60;
const TP_HOURS_PER_DAY = 24;
const TP_FINISHED_STATUSES = ['RESOLVED', 'VERIFIED', 'CLOSED'];
const TP_LOAD_BANDS = {
  ok: { badge: 'tp-load-badge--ok', fill: '', label: 'Available' },
  moderate: { badge: 'tp-load-badge--moderate', fill: 'tp-bar-fill--moderate', label: 'Moderate Load' },
  heavy: { badge: 'tp-load-badge--heavy', fill: 'tp-bar-fill--heavy', label: 'Heavy Load' }
};
const TP_PRIORITY_LABELS = { P1: 'P1 - Critical', P2: 'P2 - High', P3: 'P3 - Medium', P4: 'P4 - Low' };
const TP_STATUS_LABELS = {
  ASSIGNED: 'Assigned', ACKNOWLEDGED: 'Acknowledged', IN_PROGRESS: 'Investigating', PENDING_INFO: 'Waiting on User',
  RESOLVED: 'Resolved', VERIFIED: 'Verified', CLOSED: 'Closed'
};
const TP_COLUMNS = [
  ['Ticket ID', ''], ['Priority', ''], ['Status', ''], ['Requester', ''], ['Date Raised', ''], ['Deadline', ''],
  ['Details', 'tp-col-end']
];

function tpLoadBand(pct) {
  if (pct > TP_LOAD_HEAVY_PCT) return TP_LOAD_BANDS.heavy;
  if (pct >= TP_LOAD_MODERATE_PCT) return TP_LOAD_BANDS.moderate;
  return TP_LOAD_BANDS.ok;
}

async function loadTechnicianDashboard() {
  try {
    const data = await apiCall('/api/technician/dashboard');
    if (!data) return;

    const assigned = data.assignedIncidentsCount || 0;
    const investigating = data.inProgressCount || 0;
    const pending = data.pendingInfoCount || 0;
    const atRisk = data.atRiskCount || 0;
    const maxCapacity = data.maxConcurrentTickets || 7;
    const activeTotal = assigned + investigating + pending;
    const pct = Math.min(100, Math.round((activeTotal / maxCapacity) * 100));
    const band = tpLoadBand(pct);

    document.getElementById('tech-stat-assigned').textContent = assigned;
    document.getElementById('tech-stat-investigating').textContent = investigating;
    document.getElementById('tech-stat-pending').textContent = pending;
    document.getElementById('tech-stat-atrisk').textContent = atRisk;

    document.getElementById('tech-capacity-text').textContent = `${activeTotal} / ${maxCapacity} Active Tickets (${pct}%)`;
    const fill = document.getElementById('tech-capacity-fill');
    fill.style.width = `${pct}%`;
    fill.className = `tp-bar-fill ${band.fill}`.trim();
    const status = document.getElementById('tech-capacity-status');
    status.className = `tp-load-badge ${band.badge}`;
    status.textContent = `${band.label} (${pct}%)`;
    const bar = document.getElementById('tp-capacity-bar');
    if (bar) bar.setAttribute('aria-valuenow', String(pct));
  } catch (e) {
    // Non-blocking
  }
}

// One place that changes the Technician tab's mode, so the state and the two buttons never disagree (before Phase M4
// "Inspect Escalation" set the state and left the buttons showing "my queue").
function setTechQueueMode(mode) {
  STATE.techQueueMode = mode;
  [['btn-tech-mode-my', 'my'], ['btn-tech-mode-team', 'team']].forEach(([id, buttonMode]) => {
    const button = document.getElementById(id);
    if (!button) return;
    button.classList.add('tp-seg-btn');
    button.setAttribute('aria-pressed', String(mode === buttonMode));
  });
}

// Forget what is on screen (sign-out): an answer still on its way from the previous session is discarded.
function resetTechnicianView() {
  STATE.technician.requestSeq++;
  STATE.technician.expandedId = null;
  const spotlight = document.getElementById('tp-spotlight');
  if (spotlight) {
    spotlight.hidden = true;
    spotlight.innerHTML = '';
  }
}

// Scrolls to and highlights the ticket "Inspect Escalation" asked for, once, after the queue is drawn (Phase M4). The
// row is opened as well, so the technician sees the ticket's detail and actions at once.
function focusRequestedTicket(container) {
  const id = STATE.techFocusId;
  if (!id) return;
  STATE.techFocusId = null;
  const card = [...container.querySelectorAll('[data-ticket-card]')].find(c => c.getAttribute('data-ticket-card') === id);
  if (!card) return;
  STATE.technician.expandedId = id;
  tpApplyExpanded(container);
  card.classList.add('ticket-focus');
  card.scrollIntoView({ block: 'center', behavior: 'smooth' });
  setTimeout(() => card.classList.remove('ticket-focus'), TICKET_FOCUS_MS);
}
const TICKET_FOCUS_MS = 4000;

async function loadTechnicianQueue() {
  const container = document.getElementById('tech-queue-container');
  if (!container) return;
  const seq = ++STATE.technician.requestSeq;
  if (!container.querySelector('.tp-table')) {
    container.innerHTML = '<div class="tp-empty">Loading technician queue...</div>';
  }

  try {
    const isTeamMode = STATE.techQueueMode === 'team';
    const queue = await apiCall(isTeamMode ? '/api/technician/team-queue' : '/api/technician/queue');
    // A newer load, a mode change or a sign-out has started since: this answer is out of date.
    if (seq !== STATE.technician.requestSeq || !STATE.currentUser) return;
    const all = Array.isArray(queue) ? queue : [];
    // The spotlight looks at the whole queue, not at the filtered list, so a status filter never hides it.
    renderTechnicianSpotlight(STATE.techQueueMode === 'my' ? all : []);
    const filter = document.getElementById('tech-queue-filter').value;
    renderTechnicianQueue(filter === 'ALL' ? all : all.filter(q => q.status === filter), isTeamMode);
  } catch (err) {
    if (seq !== STATE.technician.requestSeq) return;
    container.innerHTML = `<div class="tp-empty tp-empty--error">Error: ${escapeHtml(err.message)}</div>`;
  }
}

// ---- Small pure helpers ----------------------------------------------------------------------------------------
// The simulation clock moves the server's time; only an administrator's browser polls it, so everyone else falls back
// to the browser clock. The SLA status from the server is what decides "breached"; the time left is a convenience.
function tpNowMs() {
  const virtual = STATE.simulation.virtualTime ? Date.parse(STATE.simulation.virtualTime) : NaN;
  return isNaN(virtual) ? Date.now() : virtual;
}

function tpDeadlineIso(t) {
  return t.resolutionDueAt || t.responseDueAt;
}

function tpTimeLeft(iso, nowMs) {
  const due = iso ? Date.parse(iso) : NaN;
  if (isNaN(due) || due <= nowMs) return '';
  const minutes = Math.floor((due - nowMs) / TP_MS_MINUTE);
  const hours = Math.floor(minutes / TP_MINUTES_PER_HOUR);
  if (hours >= TP_HOURS_PER_DAY) return `${Math.floor(hours / TP_HOURS_PER_DAY)}d ${hours % TP_HOURS_PER_DAY}h left`;
  return hours > 0 ? `${hours}h ${minutes % TP_MINUTES_PER_HOUR}m left` : `${minutes}m left`;
}

function tpInitials(name) {
  const letters = String(name || '').trim().split(/\s+/).filter(Boolean).slice(0, 2).map(w => w.charAt(0).toUpperCase());
  return letters.join('') || '?';
}

function tpClip(text, max) {
  const value = String(text || '');
  return value.length > max ? `${value.slice(0, max).trimEnd()}...` : value;
}

// The deadline cell's second line: what the SLA looks like right now. `state` drives the colours of the whole row.
function tpSlaLine(t, nowMs) {
  if (t.status === 'PENDING_INFO') return { state: 'paused', cls: 'tp-sla-line--paused', text: 'SLA paused - awaiting reply' };
  if (t.slaStatus === 'BREACHED') return { state: 'breach', cls: 'tp-sla-line--breach', text: 'Breached' };
  const left = tpTimeLeft(tpDeadlineIso(t), nowMs);
  if (t.slaStatus === 'AT_RISK' || t.slaStatus === 'WARNING') {
    return { state: 'warn', cls: 'tp-sla-line--warn', text: left ? `At risk - ${left}` : 'At risk' };
  }
  if (t.slaStatus === 'MET') return { state: 'met', cls: '', text: 'Target met' };
  return { state: 'ok', cls: '', text: left ? `On track - ${left}` : 'On track' };
}

function tpIdTone(t, line) {
  if (line.state === 'breach') return 'tp-id--red';
  if (line.state === 'warn' || line.state === 'paused') return 'tp-id--amber';
  return TP_FINISHED_STATUSES.includes(t.status) ? 'tp-id--green' : '';
}

function tpStatusChip(t, line) {
  const finished = TP_FINISHED_STATUSES.includes(t.status);
  if (!finished && line.state === 'breach') return '<span class="tp-status tp-status--risk">Breached SLA</span>';
  if (!finished && line.state === 'warn') return '<span class="tp-status tp-status--risk">At-Risk SLA</span>';
  const tone = line.state === 'paused' ? ' tp-status--paused' : (finished ? ' tp-status--done' : '');
  return `<span class="tp-status${tone}">${escapeHtml(TP_STATUS_LABELS[t.status] || t.status)}</span>`;
}

function tpPriorityChip(priority) {
  const key = String(priority || 'P3').toUpperCase();
  return `<span class="tp-pri tp-pri--${escapeHtml(key.toLowerCase())}">${escapeHtml(TP_PRIORITY_LABELS[key] || key)}</span>`;
}

// ---- Spotlight --------------------------------------------------------------------------------------------------
function tpDueMs(t) {
  const due = Date.parse(tpDeadlineIso(t));
  return isNaN(due) ? Infinity : due;
}

function tpPriorityRank(priority) {
  const index = ['P1', 'P2', 'P3', 'P4'].indexOf(priority);
  return index < 0 ? 9 : index;
}

// The technician's own ticket in progress that most needs attention: a breached one first, then the earliest deadline,
// then the higher priority. Nothing is shown when the technician has no ticket in progress.
function tpSpotlightPick(queue, myId) {
  const mine = queue.filter(t => t.status === 'IN_PROGRESS' && t.assigneeId && t.assigneeId === myId);
  if (mine.length === 0) return null;
  const breachRank = t => (t.slaStatus === 'BREACHED' ? 0 : 1);
  mine.sort((a, b) => (breachRank(a) - breachRank(b))
    || (tpDueMs(a) - tpDueMs(b))
    || (tpPriorityRank(a.priority) - tpPriorityRank(b.priority)));
  return mine[0];
}

function tpSpotlightDue(t, line, nowMs) {
  if (line.state === 'breach') return { cls: ' tp-spot-due--breach', text: 'Breached' };
  const left = tpTimeLeft(tpDeadlineIso(t), nowMs);
  return { cls: line.state === 'warn' ? ' tp-spot-due--warn' : '', text: left || formatDate(tpDeadlineIso(t)) };
}

function tpSpotlightHtml(t, nowMs) {
  const line = tpSlaLine(t, nowMs);
  const due = tpSpotlightDue(t, line, nowMs);
  const actions = t.allowedActions || [];
  const id = escapeHtml(t.id);
  return `
    <div class="tp-spot-head">
      <div class="tp-spot-meta">
        <span class="tp-spot-pill"><span class="tp-spot-dot"></span>Under active investigation</span>
        <span class="tp-id ${tpIdTone(t, line)}">${id}</span>
        ${ticketNameHtml(t)}
        ${tpPriorityChip(t.priority)}
        <span class="tp-spot-lead">Lead: <strong>${escapeHtml(t.assigneeName || 'You')}</strong> (You)</span>
      </div>
      <div class="tp-spot-due${due.cls}">SLA time left: <strong>${escapeHtml(due.text)}</strong></div>
    </div>
    <div class="tp-spot-body">
      <div>
        <h3 class="tp-spot-title">${escapeHtml(t.title)}</h3>
        <p class="tp-spot-desc">${escapeHtml(tpClip(t.description, TP_DESCRIPTION_CLIP))}</p>
        <div class="tp-spot-facts">
          <div><span class="tp-fact-label">Requester</span><span class="tp-fact-value">${escapeHtml(t.reporterName || t.reporterId)}</span></div>
          <div><span class="tp-fact-label">Department</span><span class="tp-fact-value">${escapeHtml(t.reporterDepartment || 'General')}</span></div>
          <div><span class="tp-fact-label">Category</span><span class="tp-fact-value">${escapeHtml(t.category || 'GENERAL')}</span></div>
        </div>
      </div>
      <div class="tp-spot-side">
        <div class="tp-spot-actions">
          ${actions.includes('requestInfo') ? `<button type="button" class="btn btn-xs btn-warning btn-tp-spot-reqinfo" data-id="${id}">Request Extra Info</button>` : ''}
          ${actions.includes('resolve') ? `<button type="button" class="btn btn-xs btn-success btn-tp-spot-resolve" data-id="${id}">Mark Resolved</button>` : ''}
          <button type="button" class="btn btn-xs btn-secondary btn-tp-spot-open" data-id="${id}">View Details</button>
        </div>
      </div>
    </div>`;
}

function renderTechnicianSpotlight(queue) {
  const panel = document.getElementById('tp-spotlight');
  if (!panel) return;
  const myId = STATE.currentUser ? STATE.currentUser.id : null;
  const ticket = STATE.techQueueMode === 'my' ? tpSpotlightPick(queue, myId) : null;
  if (!ticket) {
    panel.hidden = true;
    panel.innerHTML = '';
    return;
  }
  panel.className = `tp-spotlight${ticket.slaStatus === 'BREACHED' ? ' tp-spotlight--breach' : ''}`;
  panel.innerHTML = tpSpotlightHtml(ticket, tpNowMs());
  panel.hidden = false;
  panel.querySelectorAll('.btn-tp-spot-reqinfo').forEach(b => b.addEventListener('click', () => openRequestInfoModal(b.getAttribute('data-id'))));
  panel.querySelectorAll('.btn-tp-spot-resolve').forEach(b => b.addEventListener('click', () => openResolveModal(b.getAttribute('data-id'))));
  panel.querySelectorAll('.btn-tp-spot-open').forEach(b => b.addEventListener('click', () => tpOpenRow(b.getAttribute('data-id'))));
}

// ---- Queue table ------------------------------------------------------------------------------------------------
// What the viewer may do with a ticket. Every rule is the server's (`allowedActions` and the two flags); the only local
// rule is whose ticket it is: a technician acts on their own tickets, a team lead on any ticket of the team.
function tpPermissions(t, isTeamMode) {
  const myId = STATE.currentUser ? STATE.currentUser.id : null;
  const isTeamLead = !!(STATE.currentUser && STATE.currentUser.role === 'ROLE_TEAM_LEAD');
  const canAct = (!isTeamMode) || (t.assigneeId && t.assigneeId === myId) || isTeamLead;
  const actions = t.allowedActions || [];
  const isResolved = TP_FINISHED_STATUSES.includes(t.status);
  const actsForColleague = isTeamLead && t.assigneeId && t.assigneeId !== myId;
  return {
    canAct,
    isResolved,
    canAck: canAct && actions.includes('acknowledge'),
    canInvestigate: canAct && actions.includes('startInvestigation'),
    canWrongDomain: canAct && actions.includes('flagWrongDomain'),
    canRequestInfo: canAct && actions.includes('requestInfo'),
    // "Resolve" is only valid when the domain State Pattern permits it (IN_PROGRESS); the server's list says so.
    canResolve: canAct && actions.includes('resolve'),
    canAddNote: canAct && (t.status === 'ACKNOWLEDGED' || t.status === 'IN_PROGRESS' || t.status === 'PENDING_INFO'),
    // Suggested fixes help while the ticket is worked on; promotion follows the server's flag (ADR-043).
    canSuggest: !isResolved,
    canPromote: isResolved && t.promotable === true,
    // The server decides (ADR-045): a team lead, an open team ticket, not in triage.
    canReassign: isTeamLead && t.reassignable === true,
    reassignLabel: t.assigneeId ? 'Reassign' : 'Assign',
    // A lead who works a ticket held by a colleague acts for that colleague; say so instead of "Acknowledge".
    ackLabel: actsForColleague ? `Acknowledge for ${escapeHtml(t.assigneeName || t.assigneeId)}` : 'Acknowledge'
  };
}

function tpNoticesHtml(t, p) {
  const notices = [];
  if (t.status === 'PENDING_INFO') notices.push('<span class="tp-notice tp-notice--warn">Clock Paused - Waiting on User Response</span>');
  if (t.status === 'RESOLVED') notices.push('<span class="tp-notice tp-notice--done">Resolved (Awaiting Reporter Verification)</span>');
  else if (p.isResolved) notices.push(`<span class="tp-notice tp-notice--done">${escapeHtml(TP_STATUS_LABELS[t.status] || t.status)}</span>`);
  if (!p.canAct) notices.push(`<span class="tp-notice">Assigned to team member (${escapeHtml(t.assigneeName || 'Queued')})</span>`);
  return notices.join('');
}

function tpActionsHtml(t, p) {
  const id = escapeHtml(t.id);
  const button = (cls, style, label, extra) => `<button type="button" class="btn btn-xs ${style} ${cls}" data-id="${id}"${extra || ''}>${label}</button>`;
  return [
    p.canAck ? button('btn-tech-ack', 'btn-primary', p.ackLabel) : '',
    p.canInvestigate ? button('btn-tech-investigate', 'btn-secondary', 'Start Investigation') : '',
    p.canWrongDomain ? button('btn-tech-wrongdomain', 'btn-danger', 'Flag Out-of-Scope') : '',
    p.canAddNote ? button('btn-tech-note', 'btn-secondary', 'Add Note') : '',
    p.canRequestInfo ? button('btn-tech-reqinfo', 'btn-warning', 'Request Info (Pause SLA)') : '',
    p.canResolve ? button('btn-tech-resolve', 'btn-success', 'Resolve') : '',
    p.canReassign ? button('btn-tech-reassign', 'btn-secondary', p.reassignLabel) : '',
    p.canSuggest ? button('btn-tech-suggest', 'btn-secondary', 'Suggested fixes') : '',
    p.canPromote ? button('btn-tech-promote', 'btn-primary', 'Promote to knowledge base', ` data-title="${escapeHtml(t.title)}"`) : '',
    tpNoticesHtml(t, p)
  ].join('');
}

function tpFactsHtml(t, isTeamMode) {
  const facts = [
    ['Status', TP_STATUS_LABELS[t.status] || t.status],
    ['Category', t.category || 'GENERAL'],
    ['Created', formatDate(t.createdAt)],
    ['Response due', formatDate(t.responseDueAt)]
  ];
  if (isTeamMode) facts.push(['Assignee', t.assigneeName || (t.assigneeId ? t.assigneeId : 'Unassigned / Triage')]);
  if (t.reopenCount > 0) facts.push(['Reopened', `${t.reopenCount} time(s)`]);
  return facts.map(([label, value]) => `<div><span class="tp-fact-label">${escapeHtml(label)}</span><span class="tp-fact-value">${escapeHtml(value)}</span></div>`).join('');
}

function tpNotesHtml(t) {
  const notes = (t.notes || []).slice(-TP_NOTES_SHOWN);
  if (notes.length === 0) return '';
  const items = notes.map(n => `<li><strong>${escapeHtml(n.authorName || n.authorId)}</strong> &middot; ${escapeHtml(formatDate(n.createdAt))}<br>${escapeHtml(n.noteText)}</li>`).join('');
  return `<div><h4 class="tp-section-title">Latest notes</h4><ul class="tp-notes">${items}</ul></div>`;
}

function tpResolutionHtml(t) {
  if (!t.resolution) return '';
  const r = t.resolution;
  return `<div><h4 class="tp-section-title">Resolution</h4><div class="tp-resolution"><strong>Root cause:</strong> ${escapeHtml(r.rootCause)}<br><strong>Steps taken:</strong> ${escapeHtml(r.stepsTaken)}</div></div>`;
}

function tpDetailHtml(t, isTeamMode, p) {
  return `
    <div class="tp-detail">
      <div>
        <h4 class="tp-detail-title">${escapeHtml(t.title)}</h4>
        <p class="tp-detail-desc">${escapeHtml(t.description)}</p>
      </div>
      <div class="tp-facts">${tpFactsHtml(t, isTeamMode)}</div>
      ${tpNotesHtml(t)}
      ${tpResolutionHtml(t)}
      <div class="tp-actions">${tpActionsHtml(t, p)}</div>
    </div>`;
}

function tpRowHtml(t, isTeamMode, nowMs) {
  const line = tpSlaLine(t, nowMs);
  const p = tpPermissions(t, isTeamMode);
  const id = escapeHtml(t.id);
  const avatarTone = line.state === 'breach' ? ' tp-avatar--red' : (line.state === 'warn' || line.state === 'paused' ? ' tp-avatar--amber' : '');
  const deadline = tpDeadlineIso(t);
  return `
    <tbody class="tp-ticket" data-ticket-card="${id}">
      <tr class="tp-row" data-tp-toggle="${id}">
        <td class="tp-nowrap"><button type="button" class="tp-id ${tpIdTone(t, line)}">${id}</button>${ticketNameHtml(t, true)}</td>
        <td class="tp-nowrap">${tpPriorityChip(t.priority)}</td>
        <td class="tp-nowrap">${tpStatusChip(t, line)}${isTeamMode ? `<span class="tp-muted"> ${escapeHtml(t.assigneeName || 'Unassigned')}</span>` : ''}</td>
        <td><div class="tp-requester"><span class="tp-avatar${avatarTone}">${escapeHtml(tpInitials(t.reporterName || t.reporterId))}</span><div><div class="tp-requester-name">${escapeHtml(t.reporterName || t.reporterId)}</div><div class="tp-muted">${escapeHtml(t.reporterDepartment || 'General')}</div></div></div></td>
        <td class="tp-nowrap">${escapeHtml(formatDate(t.createdAt))}</td>
        <td class="tp-nowrap ${line.state === 'breach' ? 'tp-deadline--breach' : ''}"><span class="tp-deadline-date">${escapeHtml(formatDate(deadline))}</span><span class="tp-sla-line ${line.cls}">${escapeHtml(line.text)}</span></td>
        <td class="tp-nowrap tp-col-end"><button type="button" class="tp-details-btn" aria-expanded="false" aria-controls="tp-detail-${id}">View Details</button></td>
      </tr>
      <tr class="tp-detail-row" id="tp-detail-${id}" hidden>
        <td colspan="7">${tpDetailHtml(t, isTeamMode, p)}</td>
      </tr>
    </tbody>`;
}

function tpEmptyHtml(isTeamMode) {
  const message = isTeamMode
    ? 'No active incidents currently in your support team queue.'
    : 'No active incidents currently assigned to your queue.';
  return `<div class="tp-empty"><strong>Work queue is empty</strong>${escapeHtml(message)}</div>`;
}

// Opens one row and closes the others (a reload keeps the open row: the id lives in STATE.technician).
function tpApplyExpanded(container) {
  if (!container) return;
  container.querySelectorAll('.tp-ticket').forEach(group => {
    const open = group.getAttribute('data-ticket-card') === STATE.technician.expandedId;
    group.classList.toggle('is-open', open);
    const detail = group.querySelector('.tp-detail-row');
    if (detail) detail.hidden = !open;
    const toggle = group.querySelector('.tp-details-btn');
    if (toggle) {
      toggle.setAttribute('aria-expanded', String(open));
      toggle.textContent = open ? 'Hide Details' : 'View Details';
    }
  });
}

function tpToggleRow(id) {
  const tech = STATE.technician;
  tech.expandedId = tech.expandedId === id ? null : id;
  tpApplyExpanded(document.getElementById('tech-queue-container'));
}

// The spotlight's "View Details": open that ticket's row and bring it into view.
function tpOpenRow(id) {
  STATE.technician.expandedId = id;
  const container = document.getElementById('tech-queue-container');
  tpApplyExpanded(container);
  const group = container && [...container.querySelectorAll('[data-ticket-card]')].find(c => c.getAttribute('data-ticket-card') === id);
  if (group) group.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

function renderTechnicianQueue(queue, isTeamMode = false) {
  const container = document.getElementById('tech-queue-container');
  if (!container) return;

  if (queue.length === 0) {
    container.innerHTML = tpEmptyHtml(isTeamMode);
    STATE.techFocusId = null;
    return;
  }

  const nowMs = tpNowMs();
  const head = TP_COLUMNS.map(([label, cls]) => `<th scope="col" class="${cls}">${label}</th>`).join('');
  container.innerHTML = `
    <div class="tp-table-wrap">
      <table class="tp-table">
        <thead><tr>${head}</tr></thead>
        ${queue.map(t => tpRowHtml(t, isTeamMode, nowMs)).join('')}
      </table>
    </div>`;

  // The whole row opens and closes the detail; the buttons inside the detail keep their own handlers.
  container.querySelectorAll('.tp-row').forEach(row => {
    row.addEventListener('click', () => tpToggleRow(row.getAttribute('data-tp-toggle')));
  });
  container.querySelectorAll('.btn-tech-ack').forEach(b => {
    b.addEventListener('click', () => technicianAcknowledge(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-investigate').forEach(b => {
    b.addEventListener('click', () => technicianStartInvestigation(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-note').forEach(b => {
    b.addEventListener('click', () => openAddNoteModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-reqinfo').forEach(b => {
    b.addEventListener('click', () => openRequestInfoModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-wrongdomain').forEach(b => {
    b.addEventListener('click', () => openFlagWrongDomainModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-resolve').forEach(b => {
    b.addEventListener('click', () => openResolveModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-suggest').forEach(b => {
    b.addEventListener('click', () => openSuggestionsModal(b.getAttribute('data-id')));
  });
  container.querySelectorAll('.btn-tech-promote').forEach(b => {
    b.addEventListener('click', () => openPromoteModal(b.getAttribute('data-id'), b.getAttribute('data-title')));
  });
  container.querySelectorAll('.btn-tech-reassign').forEach(b => {
    b.addEventListener('click', () => openTeamReassignModal(b.getAttribute('data-id')));
  });
  tpApplyExpanded(container);
  focusRequestedTicket(container);
}

async function technicianAcknowledge(id) {
  try {
    await apiCall(`/api/technician/incidents/${id}/acknowledge`, { method: 'POST' });
    showToast(`Acknowledged incident ${id}`, 'success');
    loadTechnicianQueue();
    loadTechnicianDashboard();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

async function technicianStartInvestigation(id) {
  try {
    await apiCall(`/api/technician/incidents/${id}/start-investigation`, { method: 'POST' });
    showToast(`Investigation started for ${id}`, 'success');
    loadTechnicianQueue();
    loadTechnicianDashboard();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ============================================================================
// 8. Console 3: Team Lead Console Logic
// ============================================================================
// The tab reloads itself this often while it is open and idle (Phase M2). A technician's or the administrator's change
// shows up without a click; the server has no push channel the browser can use with the JWT header.
const TEAM_LEAD_REFRESH_MS = 10000;

function teamLeadElements() {
  return {
    grid: document.getElementById('teamlead-roster-grid'),
    unassigned: document.getElementById('teamlead-unassigned-container'),
    escalation: document.getElementById('teamlead-escalation-container'),
    updated: document.getElementById('teamlead-updated')
  };
}

// Forget what is on screen (sign-out): the next sign-in shows the loading text again, and an answer still on its way
// from the previous session is discarded.
function resetTeamLeadView() {
  STATE.teamLead.requestSeq++;
  STATE.teamLead.inFlight = 0;
  STATE.teamLead.hasRoster = false;
  STATE.teamLead.hasQueues = false;
  const { updated } = teamLeadElements();
  if (updated) updated.textContent = '';
}

function teamLeadErrorHtml(message, extraStyle) {
  return `<div class="text-center text-red" style="padding: 1.5rem; ${extraStyle}">Could not load: ${escapeHtml(message)}</div>`;
}

// One load for every caller (opening the tab, Refresh, an action, the timer). Loading text appears only while nothing
// real has been drawn yet; after that the old content stays until the new content replaces it. The roster and the
// queue load independently, so one failing call never hides the other (Phase M2).
async function loadTeamLeadView() {
  const lead = STATE.teamLead;
  const { grid, unassigned, escalation, updated } = teamLeadElements();
  const seq = ++lead.requestSeq;

  if (!lead.hasRoster && grid) grid.innerHTML = '<div class="text-center text-muted" style="padding: 2rem; grid-column: 1 / -1;">Loading team roster...</div>';
  if (!lead.hasQueues && unassigned) unassigned.innerHTML = '<div class="text-center text-muted" style="padding: 1.5rem;">Checking unassigned queue...</div>';
  if (!lead.hasQueues && escalation) escalation.innerHTML = '<div class="text-center text-muted" style="padding: 1.5rem;">Checking team escalations...</div>';

  lead.inFlight++;
  let results;
  try {
    results = await Promise.allSettled([apiCall('/api/admin/technicians'), apiCall('/api/technician/team-queue')]);
  } finally {
    lead.inFlight = Math.max(0, lead.inFlight - 1); // every call counts itself out, newer load or not
  }
  // A newer load, a sign-out or a sign-in as someone else has started since: this answer is out of date.
  if (seq !== lead.requestSeq || !STATE.currentUser) return;

  const [rosterResult, queueResult] = results;
  let failed = false;

  if (rosterResult.status === 'fulfilled' && Array.isArray(rosterResult.value)) {
    renderTeamRoster(rosterResult.value);
    lead.hasRoster = true;
  } else {
    failed = true;
    if (!lead.hasRoster && grid) {
      grid.innerHTML = teamLeadErrorHtml(rosterResult.reason ? rosterResult.reason.message : 'roster unavailable', 'grid-column: 1 / -1;');
    }
  }

  if (queueResult.status === 'fulfilled' && Array.isArray(queueResult.value)) {
    renderTeamLeadQueues(queueResult.value);
    lead.hasQueues = true;
  } else {
    failed = true;
    if (!lead.hasQueues) {
      const message = queueResult.reason ? queueResult.reason.message : 'team queue unavailable';
      if (unassigned) unassigned.innerHTML = teamLeadErrorHtml(message, '');
      if (escalation) escalation.innerHTML = teamLeadErrorHtml(message, '');
    }
  }

  if (updated) {
    const time = new Date().toLocaleTimeString([], { hour12: false });
    updated.textContent = failed ? `Update failed at ${time}; showing the last data received` : `Updated ${time}`;
  }
}

// The timer's guard: only while the Team Lead tab is the one on screen, the browser tab is visible, no dialog is open,
// no write is running and no load is already waiting for the server.
function autoRefreshTeamLead() {
  if (!STATE.currentUser || STATE.activeTab !== 'console-teamlead') return;
  if (document.hidden || openDialogElement() || pendingWrites > 0 || STATE.teamLead.inFlight > 0) return;
  loadTeamLeadView();
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) autoRefreshTeamLead();
});

// The team queue holds tickets of every status (a finished ticket is history, not work). The lead's two lists are about
// work in flight, so they only look at tickets that are not RESOLVED, VERIFIED or CLOSED (Phase M1).
const FINISHED_TICKET_STATUSES = ['RESOLVED', 'VERIFIED', 'CLOSED'];
const ESCALATION_SLA_STATUSES = ['BREACHED', 'AT_RISK', 'WARNING'];

function isOpenTicket(t) {
  return !FINISHED_TICKET_STATUSES.includes(t.status);
}

function renderTeamLeadQueues(teamQueue) {
  const unassignedContainer = document.getElementById('teamlead-unassigned-container');
  const escalationContainer = document.getElementById('teamlead-escalation-container');

  const open = teamQueue.filter(isOpenTicket);
  const unassigned = open.filter(t => !t.assigneeId);
  const escalations = open.filter(t => ESCALATION_SLA_STATUSES.includes(t.slaStatus));

  if (unassignedContainer) {
    if (unassigned.length === 0) {
      unassignedContainer.innerHTML = `
        <div class="text-center text-muted" style="padding: 2rem;">
          <p class="text-xs text-emerald font-semibold">✓ All team tickets currently assigned to active specialists.</p>
        </div>`;
    } else {
      unassignedContainer.innerHTML = unassigned.map(t => `
        <div class="card flex items-center justify-between gap-3" style="padding: 0.75rem 1rem; background: #ffffff; border: 1px solid var(--border);">
          <div>
            <div class="flex items-center gap-2">
              <span class="font-mono text-cyan text-xs font-bold">${escapeHtml(t.id)}</span>
              ${ticketNameHtml(t)}
              <span class="badge badge-status-new">${escapeHtml(t.status)}</span>
              <span class="font-semibold text-main text-xs">${escapeHtml(t.title)}</span>
            </div>
            <p class="text-xs text-muted" style="margin-top: 0.2rem;">Reporter: ${escapeHtml(t.reporterName || t.reporterId)} &middot; Created: ${formatDate(t.createdAt)}</p>
          </div>
          ${t.reassignable === true
            ? `<button type="button" class="btn btn-xs btn-primary btn-teamlead-assign" data-id="${escapeHtml(t.id)}">Assign</button>`
            : `<span class="text-xs text-dim italic">In triage: an administrator decides</span>`}
        </div>
      `).join('');

      unassignedContainer.querySelectorAll('.btn-teamlead-assign').forEach(b => {
        b.addEventListener('click', () => openTeamReassignModal(b.getAttribute('data-id')));
      });
    }
  }

  if (escalationContainer) {
    if (escalations.length === 0) {
      escalationContainer.innerHTML = `
        <div class="text-center text-muted" style="padding: 2rem;">
          <p class="text-xs text-cyan font-semibold">✓ No open team ticket is at risk of, or past, its SLA deadline.</p>
        </div>`;
    } else {
      escalationContainer.innerHTML = escalations.map(t => {
        const slaClass = t.slaStatus === 'BREACHED' ? 'sla-breached' : 'sla-warning';
        return `
          <div class="card flex items-center justify-between gap-3" style="padding: 0.75rem 1rem; background: var(--danger-subtle); border: 1px solid var(--danger-border);">
            <div>
              <div class="flex items-center gap-2">
                <span class="font-mono text-red text-xs font-bold">${escapeHtml(t.id)}</span>
                ${ticketNameHtml(t)}
                <span class="badge badge-p1">${escapeHtml(t.priority || '-')}</span>
                <span class="sla-timer-pill ${slaClass}">SLA: ${escapeHtml(t.slaStatus)}</span>
                <span class="font-semibold text-main text-xs">${escapeHtml(t.title)}</span>
              </div>
              <p class="text-xs text-dim" style="margin-top: 0.2rem;">Assigned: <strong class="text-main">${escapeHtml(t.assigneeName || 'Unassigned')}</strong> &middot; Target: ${formatDate(t.resolutionDueAt || t.responseDueAt)}</p>
            </div>
            <div class="flex items-center gap-2">
              ${t.reassignable === true
                ? `<button type="button" class="btn btn-xs btn-secondary btn-teamlead-reassign" data-id="${escapeHtml(t.id)}">${t.assigneeId ? 'Reassign' : 'Assign'}</button>`
                : ''}
              <button type="button" class="btn btn-xs btn-danger btn-teamlead-intervene" data-id="${escapeHtml(t.id)}">Inspect Escalation</button>
            </div>
          </div>
        `;
      }).join('');

      escalationContainer.querySelectorAll('.btn-teamlead-reassign').forEach(b => {
        b.addEventListener('click', () => openTeamReassignModal(b.getAttribute('data-id')));
      });
      // "Inspect Escalation" opens the team queue on the Technician tab at that ticket: the mode buttons follow, the
      // status filter is cleared so the ticket cannot be filtered out, and the card is scrolled to and highlighted.
      escalationContainer.querySelectorAll('.btn-teamlead-intervene').forEach(b => {
        b.addEventListener('click', () => {
          STATE.techFocusId = b.getAttribute('data-id');
          setTechQueueMode('team');
          const filter = document.getElementById('tech-queue-filter');
          if (filter) filter.value = 'ALL';
          switchTab('console-technician');
        });
      });
    }
  }
}

function renderTeamRoster(technicians) {
  const grid = document.getElementById('teamlead-roster-grid');
  if (!grid) return;

  if (technicians.length === 0) {
    grid.innerHTML = '<div class="text-center text-muted w-full" style="padding: 2rem; grid-column: 1 / -1;">No technicians registered.</div>';
    return;
  }

  grid.innerHTML = technicians.map(tech => {
    const active = tech.activeIncidentCount || 0;
    const max = tech.maxCapacity || 7;
    const pct = Math.min(100, Math.round((active / max) * 100));
    const role = tech.role === 'ROLE_TEAM_LEAD' ? 'Team Lead' : 'Technician';
    const categories = [...new Set((tech.skills || []).map(s => s.category))].join(', ') || 'None';

    return `
      <div class="card tlm-card" role="button" tabindex="0" data-id="${escapeHtml(tech.id)}" data-name="${escapeHtml(tech.name)}" aria-haspopup="dialog" aria-label="Show the current tickets of ${escapeHtml(tech.name)}" style="padding: 1rem; background: #ffffff; border: 1px solid var(--border);">
        <div class="flex items-center justify-between" style="margin-bottom: 0.5rem;">
          <h4 class="font-bold text-main text-sm">${escapeHtml(tech.name)}</h4>
          <span class="badge ${pct > 75 ? 'badge-p1' : 'badge-status-resolved'}">${pct}% Load</span>
        </div>
        <div class="text-xs text-muted" style="margin-bottom: 0.4rem;">
          <span>Skills: <strong class="text-cyan">${escapeHtml(categories)}</strong></span> &middot;
          <span>Role: <strong class="text-main">${escapeHtml(role)}</strong></span>
        </div>
        <div class="capacity-bar">
          <div class="capacity-fill ${pct > 75 ? 'high' : ''}" style="width: ${pct}%;"></div>
        </div>
        <div class="flex justify-between items-center text-xs text-dim" style="margin-top: 0.4rem;">
          <span>Active Tickets: <strong class="text-main">${active}</strong> / ${max}</span>
          <span class="font-mono text-cyan">${escapeHtml(tech.id)}</span>
        </div>
      </div>
    `;
  }).join('');
}

// ============================================================================
// 9. Console 4: Admin Command Centre Logic
// ============================================================================
async function loadAdminView() {
  loadAdminStats();
  if (STATE.activeAdminSubtab === 'admin-catalog-panel') {
    loadAdminFilterOptions();
    searchAdminIncidents();
  } else if (STATE.activeAdminSubtab === 'admin-triage-panel') {
    loadAdminTriageQueue();
  } else if (STATE.activeAdminSubtab === 'admin-strategy-panel') {
    loadAssignmentStrategies();
  }
}

async function loadAdminStats() {
  try {
    const stats = await apiCall('/api/admin/stats');
    if (!stats) return;
    document.getElementById('admin-kpi-total').textContent = stats.totalIncidents || 0;
    document.getElementById('admin-kpi-open').textContent = stats.activeIncidents || 0;
    document.getElementById('admin-kpi-triage').textContent = stats.pendingTriageCount || 0;
    document.getElementById('admin-kpi-compliance').textContent = `${stats.slaComplianceRate || 100}%`;
  } catch (e) {
    // Non-blocking
  }
}

/** Start (00:00:00.000) or end (23:59:59.999) of a yyyy-mm-dd day in local time, as an ISO instant; '' if no day. */
function localDayBoundaryIso(day, endOfDay) {
  if (!day) return '';
  const [year, month, date] = day.split('-').map(Number);
  const instant = endOfDay
    ? new Date(year, month - 1, date, 23, 59, 59, 999)
    : new Date(year, month - 1, date, 0, 0, 0, 0);
  return instant.toISOString();
}

function selectedOf(filter) {
  return filter ? filter.getSelected() : [];
}

/** Every filter of the admin directory as request parameters (ADR-012, ADR-049). */
function adminSearchParams(fromDay, toDay) {
  const params = new URLSearchParams();
  const query = document.getElementById('admin-search-text').value.trim();
  if (query) params.append('query', query);
  const selectedCategories = selectedOf(ADMIN_FILTERS.categories);
  const selectedPriorities = selectedOf(ADMIN_FILTERS.priorities);
  selectedCategories.forEach(category => params.append('category', category));
  selectedPriorities.forEach(priority => params.append('priority', priority));
  selectedOf(ADMIN_FILTERS.statuses).forEach(status => params.append('status', status));
  selectedOf(ADMIN_FILTERS.slaStates).forEach(slaStatus => params.append('slaStatus', slaStatus));
  const serviceId = document.getElementById('admin-filter-service').value;
  const assigneeId = document.getElementById('admin-filter-technician').value;
  if (serviceId) params.append('serviceId', serviceId);
  if (assigneeId) params.append('assigneeId', assigneeId);
  if (fromDay) params.append('fromDate', localDayBoundaryIso(fromDay, false));
  if (toDay) params.append('toDate', localDayBoundaryIso(toDay, true));
  return params;
}

async function searchAdminIncidents() {
  const tbody = document.getElementById('admin-incidents-tbody');
  if (!tbody) return;
  const fromDay = document.getElementById('admin-filter-from').value;
  const toDay = document.getElementById('admin-filter-to').value;
  if (fromDay && toDay && fromDay > toDay) {
    // The server refuses this too (400 ERR_VALIDATION_FAILED); no request is sent
    showToast('"Created from" must not be after "Created to".', 'error');
    return;
  }
  tbody.innerHTML = '<tr><td colspan="9" class="text-center text-muted" style="padding: 2.5rem 1rem;">Searching enterprise incidents...</td></tr>';

  try {
    const params = adminSearchParams(fromDay, toDay);
    const incidents = await apiCall(`/api/admin/incidents?${params.toString()}`);
    renderAdminIncidents(incidents || []);
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="9" class="text-center text-red" style="padding: 2.5rem 1rem;">Error: ${escapeHtml(err.message)}</td></tr>`;
  }
}

function adminIncidentRowHtml(t) {
  const priority = t.priorityDecision ? t.priorityDecision.priority : null;
  const sla = t.slaTracker || null;
  const breached = !!sla && sla.status === 'BREACHED';
  const priorityCell = priority
    ? `<span class="badge badge-${tdHyphenate(priority)}">${escapeHtml(priority)}</span>`
    : '<span class="text-dim">--</span>';
  const slaTitle = breached ? 'SLA resolution deadline (breached)' : 'SLA resolution deadline';
  const slaStatusCell = sla && sla.status
    ? `<span class="badge badge-sla-${tdHyphenate(sla.status)}">${escapeHtml(sla.status)}</span>`
    : '<span class="text-dim">--</span>';

  return `
      <tr>
        <td class="font-mono font-bold text-cyan text-xs"><span class="ticket-link cursor-pointer btn-admin-view-ticket" role="button" tabindex="0" title="View full ticket details" data-id="${escapeHtml(t.id)}">${escapeHtml(t.id)}</span>${ticketNameHtml(t, true)}</td>
        <td class="font-semibold text-main">${escapeHtml(t.title)}</td>
        <td><span class="badge" style="background: #f1f5f9; color: var(--text-muted); border: 1px solid var(--border);">${escapeHtml(t.category || 'GENERAL')}</span></td>
        <td>${priorityCell}</td>
        <td><span class="badge badge-status-${tdHyphenate(t.status)}">${escapeHtml(t.status)}</span></td>
        <td>${slaStatusCell}</td>
        <td>${escapeHtml(t.assigneeName || 'Unassigned')}</td>
        <td class="font-mono text-xs ${breached ? 'text-red font-bold' : 'text-muted'}" title="${slaTitle}">${escapeHtml(formatDate(sla ? sla.resolutionDueAt : null))}</td>
        <td class="text-right">
          <button type="button" class="btn btn-xs btn-secondary btn-admin-override" data-id="${escapeHtml(t.id)}" title="Override Priority">Override</button>
          <button type="button" class="btn btn-xs btn-secondary btn-admin-reassign" data-id="${escapeHtml(t.id)}" title="Reassign">Reassign</button>
        </td>
      </tr>
    `;
}

function renderAdminIncidents(incidents) {
  const tbody = document.getElementById('admin-incidents-tbody');
  if (!tbody) return;

  if (incidents.length === 0) {
    tbody.innerHTML = '<tr><td colspan="9" class="text-center text-muted" style="padding: 2.5rem 1rem;">No incidents found matching criteria.</td></tr>';
    return;
  }

  tbody.innerHTML = incidents.map(adminIncidentRowHtml).join('');

  bindAdminTicketLinks(tbody);
  tbody.querySelectorAll('.btn-admin-override').forEach(b => {
    b.addEventListener('click', () => openPriorityOverrideModal(b.getAttribute('data-id')));
  });
  tbody.querySelectorAll('.btn-admin-reassign').forEach(b => {
    b.addEventListener('click', () => openReassignModal(b.getAttribute('data-id')));
  });
}

async function loadAdminTriageQueue() {
  const container = document.getElementById('admin-triage-container');
  if (!container) return;
  container.innerHTML = '<div class="text-center text-muted" style="padding: 2rem;">Loading triage queue...</div>';

  try {
    const triage = await apiCall('/api/admin/triage-queue');
    if (!triage || triage.length === 0) {
      container.innerHTML = '<div class="text-center text-muted" style="padding: 2.5rem 1rem;">Triage queue is empty. All incidents assigned with high confidence.</div>';
      return;
    }

    container.innerHTML = triage.map(triageCardHtml).join('');

    container.querySelectorAll('.btn-triage-resolve').forEach(b => {
      b.addEventListener('click', () => openReclassifyModal(b.getAttribute('data-id')));
    });
    container.querySelectorAll('.btn-triage-reject').forEach(b => {
      b.addEventListener('click', () => openRejectReassignmentModal(b.getAttribute('data-request-id'), b.getAttribute('data-id')));
    });
  } catch (err) {
    container.innerHTML = `<div class="text-center text-red" style="padding: 2rem;">Error: ${escapeHtml(err.message)}</div>`;
  }
}

// One card per ticket (ADR-029). A ticket a technician flagged shows the technician and the reason and can be rejected;
// a ticket the intake held back shows the low-confidence reason. Both show the classifier's suggestion.
function triageCardHtml(item) {
  const flagged = item.triageType === 'WRONG_DOMAIN';
  const suggestion = (item.suggestions && item.suggestions.length) ? item.suggestions[0] : null;
  const suggestionText = suggestion
    ? `${escapeHtml(suggestion.category)} (${Math.round(suggestion.confidence * 100)}% confidence)`
    : 'none';
  return `
      <div class="card" style="padding: 1rem; background: #ffffff; border: 1px solid var(--warning-border);">
        <div class="flex items-center justify-between flex-wrap gap-2" style="margin-bottom: 0.35rem;">
          <div class="flex items-center gap-2">
            <span class="font-mono font-bold text-cyan text-xs">${escapeHtml(item.incidentId)}</span>
            ${ticketNameHtml({ id: item.incidentId, displayId: item.displayId })}
            <span class="badge ${flagged ? 'badge-p2' : 'badge-p3'}">${flagged ? 'Flagged by technician' : 'Low confidence'}</span>
          </div>
          <span class="text-xs text-dim font-mono">${formatDate(item.createdAt)}</span>
        </div>
        <h4 class="font-bold text-main text-sm" style="margin-bottom: 0.25rem;">${escapeHtml(item.title)}</h4>
        <p class="text-xs text-main" style="margin-bottom: 0.4rem;">${flagged ? 'Technician reason' : 'Reason'}: <em>"${escapeHtml(item.reason)}"</em></p>
        <p class="text-xs text-muted">Current category: <strong class="text-main">${escapeHtml(item.category || 'none')}</strong> &middot; Classifier suggests: <strong class="text-main">${suggestionText}</strong></p>
        <div class="text-xs text-muted flex items-center justify-between flex-wrap gap-2" style="border-top: 1px solid var(--border-subtle); padding-top: 0.5rem; margin-top: 0.5rem;">
          <span>${flagged ? `Requested by: <strong class="text-main">${escapeHtml(item.requestingTechnicianId)}</strong>` : 'Held back by the automatic intake'}</span>
          <span class="flex items-center gap-1.5">
            ${flagged ? `<button type="button" class="btn btn-xs btn-secondary btn-triage-reject" data-request-id="${escapeHtml(item.requestId)}" data-id="${escapeHtml(item.incidentId)}">Reject request</button>` : ''}
            <button type="button" class="btn btn-xs btn-primary btn-triage-resolve" data-id="${escapeHtml(item.incidentId)}">
              <span>Reclassify &amp; Reassign</span>
            </button>
          </span>
        </div>
      </div>
    `;
}

// Rejecting a technician flag gives the ticket back to that technician; the reason becomes a note on the ticket.
function openRejectReassignmentModal(requestId, incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Reject Reassignment Request</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-reject-reassignment" class="space-y-4">
      <p class="text-xs text-muted">Rejecting the request gives ticket <strong>${escapeHtml(incidentId)}</strong> back to the technician who flagged it. Say why it belongs to the team: the reason is added to the ticket as a note for the technician.</p>
      <div class="form-group">
        <label class="form-label">Reason</label>
        <textarea id="modal-reject-reassignment-reason" class="form-textarea" rows="3" required maxlength="1000" placeholder="e.g. This is a VPN issue and the Network team owns it. Please continue."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">Reject and Return to Technician</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-reject-reassignment').addEventListener('submit', async (e) => {
    e.preventDefault();
    const reason = document.getElementById('modal-reject-reassignment-reason').value.trim();
    try {
      await apiCall(`/api/admin/reassignments/${encodeURIComponent(requestId)}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason })
      });
      closeModal();
      showToast('Request rejected: the ticket went back to the technician.', 'success');
      loadAdminTriageQueue();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

async function loadAssignmentStrategies() {
  const container = document.getElementById('strategies-grid');
  if (!container) return;
  container.innerHTML = '<div class="text-center text-muted w-full" style="padding: 2rem; grid-column: 1 / -1;">Loading strategies...</div>';

  try {
    const strategies = await apiCall('/api/admin/assignment/strategies');
    if (!strategies) return;

    container.innerHTML = strategies.map(s => `
      <div class="card flex flex-col justify-between" style="padding: 1.25rem; background: #ffffff; border: 1px solid ${s.active ? 'var(--primary)' : 'var(--border)'};">
        <div>
          <div class="flex items-center justify-between" style="margin-bottom: 0.5rem;">
            <h4 class="font-mono font-bold text-main text-sm">${escapeHtml(s.name)}</h4>
            ${s.active ? '<span class="badge badge-status-resolved">ACTIVE</span>' : ''}
          </div>
          <p class="text-xs text-muted" style="margin-bottom: 1rem;">${escapeHtml(s.description)}</p>
        </div>
        <button type="button" class="btn btn-xs ${s.active ? 'btn-secondary' : 'btn-primary'} btn-activate-strategy" data-name="${s.name}" ${s.active ? 'disabled' : ''}>
          ${s.active ? 'Currently Active' : 'Activate Strategy'}
        </button>
      </div>
    `).join('');

    container.querySelectorAll('.btn-activate-strategy:not([disabled])').forEach(b => {
      b.addEventListener('click', async () => {
        const name = b.getAttribute('data-name');
        try {
          await apiCall('/api/admin/assignment/strategies/active', {
            method: 'PUT',
            body: JSON.stringify({ strategyName: name })
          });
          showToast(`Activated strategy: ${name}`, 'success');
          loadAssignmentStrategies();
        } catch (err) {
          showToast(err.message, 'error');
        }
      });
    });
  } catch (err) {
    container.innerHTML = `<div class="text-center text-red w-full" style="padding: 2rem; grid-column: 1 / -1;">Error: ${escapeHtml(err.message)}</div>`;
  }
}

// ============================================================================
// 10. Console 5: Simulation Engine Logic
// ============================================================================
async function loadSimulationStatus() {
  await updateSimulationClock();
}

async function runScenario(scenarioId) {
  const terminal = document.getElementById('sim-terminal-logs');
  logTerminal(`[SCENARIO] Initiating execution of ${scenarioId}...`, 'info');

  try {
    if (STATE.currentUser && STATE.currentUser.role !== 'ROLE_ADMIN') {
      if (STATE.demoMode) {
        logTerminal(`[AUTH] Auto-elevating to System Admin (admin@nimbusfinserve.com) for simulation privileges...`, 'info');
        await loginUser('admin@nimbusfinserve.com', STATE.demoPassword, false);
      } else {
        throw new Error('Simulation scenarios require Administrator privileges.');
      }
    }

    const result = await apiCall(`/api/simulation/run/${scenarioId}`, { method: 'POST' });
    logTerminal(`[SCENARIO ${scenarioId}] Execution finished with status: ${result.status}`, result.status === 'SUCCESS' ? 'success' : 'warn');
    if (Array.isArray(result.logs)) {
      result.logs.forEach(l => logTerminal(l, 'info'));
    }
    showToast(`Scenario ${scenarioId} completed successfully!`, 'success');
  } catch (err) {
    logTerminal(`[ERROR ${scenarioId}] ${err.message}`, 'error');
    showToast(`Scenario ${scenarioId} failed: ${err.message}`, 'error');
  }
}

function logTerminal(message, type = 'info') {
  const terminal = document.getElementById('sim-terminal-logs');
  if (!terminal) return;
  const entry = document.createElement('span');
  entry.className = `log-entry ${type}`;
  entry.textContent = message;
  terminal.appendChild(entry);
  terminal.scrollTop = terminal.scrollHeight;
}

// ============================================================================
// 11. Console 6: Executive Reports Logic
// ============================================================================
async function loadExecutiveReports() {
  try {
    const mttr = await apiCall('/api/admin/reports/mttr');
    if (mttr) {
      document.getElementById('kpi-mttr-val').textContent = mttr.mttrMinutes !== undefined ? mttr.mttrMinutes : '--';
      document.getElementById('kpi-mttresp-val').textContent = mttr.mttRespMinutes !== undefined ? mttr.mttRespMinutes : '--';
    }

    const sla = await apiCall('/api/admin/reports/sla');
    if (sla) {
      document.getElementById('kpi-compliance-val').textContent = `${sla.complianceRatePercent || 100}%`;
    }

    const workload = await apiCall('/api/admin/reports/workload');
    const workloadContainer = document.getElementById('report-workload-container');
    if (workload && workload.teamWorkloads && workloadContainer) {
      workloadContainer.innerHTML = workload.teamWorkloads.map(w => `
        <div class="flex items-center justify-between text-xs" style="padding: 0.4rem 0; border-bottom: 1px solid var(--border-subtle);">
          <span class="font-semibold text-main">${escapeHtml(w.teamName)}</span>
          <span class="font-mono text-cyan">${w.activeTickets} active (${w.resolvedTickets} resolved)</span>
        </div>
      `).join('');
    }

    const escalations = await apiCall('/api/admin/reports/escalations');
    const escContainer = document.getElementById('report-escalations-container');
    if (escalations && escalations.escalations && escContainer) {
      escContainer.innerHTML = escalations.escalations.map(e => `
        <div class="text-xs" style="padding: 0.35rem 0; border-bottom: 1px solid var(--border-subtle);">
          <span class="badge badge-p1 font-mono">${escapeHtml(e.tier || 'TIER-1')}</span>
          <span class="text-main">${escapeHtml(e.incidentId)}</span> &middot;
          <span class="text-muted">${escapeHtml(e.reason)}</span>
        </div>
      `).join('');
    }
  } catch (e) {
    // Non-blocking
  }
}

// ============================================================================
// 12. Interactive Action Modals
// ============================================================================
// Keyboard focus in dialogs (ADR-039): Tab and Shift+Tab wrap inside the open dialog, focus moves into an action
// dialog when it opens and returns to the control that opened it when it closes.
const FOCUSABLE_SELECTOR = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
let modalOpener = null;

function openDialogElement() {
  const ticketDialog = document.getElementById('admin-ticket-detail-modal');
  if (ticketDialog && !ticketDialog.classList.contains('hidden')) return ticketDialog;
  const container = document.getElementById('modal-container');
  if (container && !container.classList.contains('hidden')) return container;
  return null;
}

function trapTabKey(event, dialog) {
  const items = [...dialog.querySelectorAll(FOCUSABLE_SELECTOR)].filter(el => el.offsetParent !== null || el === document.activeElement);
  if (items.length === 0) {
    event.preventDefault();
    return;
  }
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  if (!dialog.contains(active)) {
    event.preventDefault();
    first.focus();
  } else if (event.shiftKey && active === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}

document.addEventListener('keydown', (event) => {
  if (event.key !== 'Tab') return;
  const dialog = openDialogElement();
  if (dialog) trapTabKey(event, dialog);
});

function openModal(contentHtml) {
  const container = document.getElementById('modal-container');
  const content = document.getElementById('modal-content');
  if (container.classList.contains('hidden')) {
    modalOpener = document.activeElement;
  }
  content.innerHTML = contentHtml;
  container.classList.remove('hidden');

  content.querySelectorAll('[data-close-modal]').forEach(b => {
    b.addEventListener('click', closeModal);
  });
  const firstField = content.querySelector('input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])')
    || content.querySelector(FOCUSABLE_SELECTOR);
  if (firstField) firstField.focus({ preventScroll: true });
}

function closeModal() {
  const container = document.getElementById('modal-container');
  container.classList.add('hidden');
  const opener = modalOpener;
  modalOpener = null;
  if (opener && opener.isConnected && typeof opener.focus === 'function') {
    opener.focus({ preventScroll: true });
  }
  // The list behind the dialog is usually re-drawn right after a write; the twin of the opener takes the focus then.
  if (opener) setTimeout(() => restoreFocus(opener), WRITE_COOLDOWN_MS + 100);
}

function openProvideInfoModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Respond to Information Request</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-provide-info" class="space-y-4">
      <div class="form-group">
        <label class="form-label">Workstation / Issue Details</label>
        <textarea id="modal-info-response" class="form-textarea" rows="4" required placeholder="Provide the details requested by engineering..."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">Submit Response</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-provide-info').addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = document.getElementById('modal-info-response').value.trim();
    try {
      await apiCall(`/api/employee/incidents/${incidentId}/provide-info`, {
        method: 'POST',
        body: JSON.stringify({ responseText: text })
      });
      closeModal();
      showToast('Information submitted to engineering.', 'success');
      loadEmployeeTickets();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openVerifyModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Verify &amp; Rate Incident Resolution</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-verify" class="space-y-4">
      <p class="text-xs text-muted">Please confirm that your technical issue has been resolved to your satisfaction and rate the support received.</p>
      <div class="form-group">
        <label class="form-label">Service Satisfaction Rating (1–5 Stars)</label>
        <select id="modal-rating" class="form-select">
          <option value="5" selected>⭐⭐⭐⭐⭐ 5 Stars - Excellent</option>
          <option value="4">⭐⭐⭐⭐ 4 Stars - Good</option>
          <option value="3">⭐⭐⭐ 3 Stars - Average</option>
          <option value="2">⭐⭐ 2 Stars - Below Expectations</option>
          <option value="1">⭐ 1 Star - Unsatisfactory</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Optional Feedback Comments</label>
        <textarea id="modal-feedback-comment" class="form-textarea" rows="2" placeholder="Great assistance, thanks!"></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-success">Verify &amp; Close Ticket</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-verify').addEventListener('submit', async (e) => {
    e.preventDefault();
    const rating = parseInt(document.getElementById('modal-rating').value, 10);
    const comment = document.getElementById('modal-feedback-comment').value.trim();
    try {
      await apiCall(`/api/employee/incidents/${incidentId}/verify`, { method: 'POST' });
      await apiCall(`/api/employee/incidents/${incidentId}/feedback`, {
        method: 'POST',
        body: JSON.stringify({ rating, comment })
      });
      closeModal();
      showToast('Resolution verified and feedback submitted.', 'success');
      loadEmployeeTickets();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openRejectModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title text-red">Reject Resolution</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-reject" class="space-y-4">
      <div class="form-group">
        <label class="form-label">Reason for Rejection</label>
        <textarea id="modal-reject-reason" class="form-textarea" rows="3" required placeholder="Explain why the issue persists..."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-danger">Reject Resolution</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-reject').addEventListener('submit', async (e) => {
    e.preventDefault();
    const reason = document.getElementById('modal-reject-reason').value.trim();
    try {
      await apiCall(`/api/employee/incidents/${incidentId}/reject`, {
        method: 'POST',
        body: JSON.stringify({ reason })
      });
      closeModal();
      showToast('Resolution rejected. Ticket returned to engineering.', 'warning');
      loadEmployeeTickets();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openAddNoteModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Add Work Note</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-note" class="space-y-4">
      <div class="form-group">
        <label class="form-label">Note Text</label>
        <textarea id="modal-note-text" class="form-textarea" rows="3" required placeholder="Document technical investigation findings..."></textarea>
      </div>
      <div class="form-group flex items-center gap-2">
        <input type="checkbox" id="modal-note-internal" checked>
        <label for="modal-note-internal" class="text-xs text-muted">Internal engineering note only (masked from employee)</label>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">Add Note</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-note').addEventListener('submit', async (e) => {
    e.preventDefault();
    const noteText = document.getElementById('modal-note-text').value.trim();
    const internalOnly = document.getElementById('modal-note-internal').checked;
    try {
      await apiCall(`/api/technician/incidents/${incidentId}/notes`, {
        method: 'POST',
        body: JSON.stringify({ noteText, internalOnly })
      });
      closeModal();
      showToast('Note added successfully.', 'success');
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openRequestInfoModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title text-amber">Request Additional Info</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-reqinfo" class="space-y-4">
      <p class="text-xs text-muted">Requesting information will put the ticket in <strong>PENDING_INFO</strong> status and <strong>pause the SLA clock</strong> until the user responds.</p>
      <div class="form-group">
        <label class="form-label">Information Required</label>
        <textarea id="modal-reqinfo-text" class="form-textarea" rows="3" required placeholder="Specify what information you need from the employee..."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-warning">Request Info &amp; Pause SLA</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-reqinfo').addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = document.getElementById('modal-reqinfo-text').value.trim();
    try {
      await apiCall(`/api/technician/incidents/${incidentId}/request-info`, {
        method: 'POST',
        body: JSON.stringify({ responseText: text })
      });
      closeModal();
      showToast('Information requested. SLA clock paused.', 'warning');
      loadTechnicianQueue();
      loadTechnicianDashboard();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openFlagWrongDomainModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title text-red">Flag Out-of-Scope (Wrong Domain)</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-flag" class="space-y-4">
      <p class="text-xs text-muted">Flagging this incident will remove it from your queue and route it to the <strong>Admin Triage Queue</strong> for domain reclassification.</p>
      <div class="form-group">
        <label class="form-label">Reason / Correct Domain Suggestion</label>
        <textarea id="modal-flag-reason" class="form-textarea" rows="3" required placeholder="e.g. This is a Database issue, not a Network issue. Reassign to Database team."></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-danger">Route to Admin Triage</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-flag').addEventListener('submit', async (e) => {
    e.preventDefault();
    const reason = document.getElementById('modal-flag-reason').value.trim();
    try {
      await apiCall(`/api/technician/incidents/${incidentId}/flag-wrong-domain`, {
        method: 'POST',
        body: JSON.stringify({ reason })
      });
      closeModal();
      showToast('Ticket routed to Admin Triage queue.', 'info');
      loadTechnicianQueue();
      loadTechnicianDashboard();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openResolveModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title text-emerald">Resolve Incident</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-resolve" class="space-y-4">
      <div class="form-group">
        <label class="form-label">Root Cause Summary</label>
        <input type="text" id="modal-root-cause" class="form-input" required placeholder="e.g. VPN tunnel gateway IP changed due to firewall update">
      </div>
      <div class="form-group">
        <label class="form-label">Steps Taken to Resolve</label>
        <textarea id="modal-steps-taken" class="form-textarea" rows="3" required placeholder="e.g. Reconfigured routing table and restarted IPsec daemon"></textarea>
      </div>
      <div class="form-group">
        <label class="form-label">Confirmed Category</label>
        <select id="modal-confirmed-category" class="form-select">
          <option value="NETWORK">NETWORK</option>
          <option value="HARDWARE">HARDWARE</option>
          <option value="SOFTWARE">SOFTWARE</option>
          <option value="DATABASE">DATABASE</option>
          <option value="SECURITY">SECURITY</option>
          <option value="CLOUD">CLOUD</option>
          <option value="ACCESS">ACCESS</option>
          <option value="APPLICATION">APPLICATION</option>
        </select>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-success">Mark as Resolved</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-resolve').addEventListener('submit', async (e) => {
    e.preventDefault();
    const rootCause = document.getElementById('modal-root-cause').value.trim();
    const stepsTaken = document.getElementById('modal-steps-taken').value.trim();
    const confirmedCategory = document.getElementById('modal-confirmed-category').value;

    try {
      await apiCall(`/api/technician/incidents/${incidentId}/resolve`, {
        method: 'POST',
        body: JSON.stringify({ rootCause, stepsTaken, confirmedCategory })
      });
      closeModal();
      showToast('Incident resolved! Next step: Sign out & sign in as Employee to verify and close with a 5-star rating.', 'success', 6000);
      loadTechnicianQueue();
      loadTechnicianDashboard();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openPriorityOverrideModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Override Priority</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-priority" class="space-y-4">
      <div class="form-group">
        <label class="form-label">New Priority Level</label>
        <select id="modal-new-priority" class="form-select">
          <option value="P1">P1 (Critical - 1hr SLA)</option>
          <option value="P2">P2 (High - 4hr SLA)</option>
          <option value="P3">P3 (Medium - 8hr SLA)</option>
          <option value="P4">P4 (Low - 24hr SLA)</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Justification / Audit Reason</label>
        <input type="text" id="modal-priority-reason" class="form-input" required placeholder="e.g. VIP executive impact; escalated priority">
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">Apply Override</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-priority').addEventListener('submit', async (e) => {
    e.preventDefault();
    const priority = document.getElementById('modal-new-priority').value;
    const reason = document.getElementById('modal-priority-reason').value.trim();
    try {
      await apiCall(`/api/admin/incidents/${incidentId}/override-priority`, {
        method: 'POST',
        body: JSON.stringify({ priority, reason })
      });
      closeModal();
      showToast(`Priority updated to ${priority}`, 'success');
      searchAdminIncidents();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openReassignModal(incidentId) {
  apiCall('/api/admin/technicians').then(technicians => {
    const options = (technicians || []).map(t => `<option value="${t.id}">${escapeHtml(t.name)} (${t.domain || 'Tech'})</option>`).join('');
    openModal(`
      <div class="modal-header">
        <h3 class="modal-title">Reassign Incident</h3>
        <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
      </div>
      <form id="form-modal-reassign" class="space-y-4">
        <div class="form-group">
          <label class="form-label">Target Technician</label>
          <select id="modal-target-tech" class="form-select">
            ${options}
          </select>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
          <button type="submit" class="btn btn-primary">Assign</button>
        </div>
      </form>
    `);

    document.getElementById('form-modal-reassign').addEventListener('submit', async (e) => {
      e.preventDefault();
      const technicianId = document.getElementById('modal-target-tech').value;
      try {
        await apiCall(`/api/admin/incidents/${incidentId}/assign`, {
          method: 'POST',
          body: JSON.stringify({ technicianId })
        });
        closeModal();
        showToast('Incident reassigned.', 'success');
        searchAdminIncidents();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });
  }).catch(err => showToast(err.message, 'error'));
}

// Why a team member cannot be picked right now, or null when they can take the ticket (Phase M3). The server checks the
// same things again; this only keeps the lead from choosing someone who is certain to be refused.
function teamMemberBlocker(member, currentAssigneeId) {
  if (member.id === currentAssigneeId) return 'Current technician';
  if (!member.active) return 'Inactive';
  if (member.availabilityStatus === 'OFFLINE') return 'Offline';
  if ((member.activeIncidentCount || 0) >= (member.maxCapacity || 0)) return 'No free slot';
  return null;
}

function teamMemberOptionHtml(member, currentAssigneeId) {
  const blocker = teamMemberBlocker(member, currentAssigneeId);
  const role = member.role === 'ROLE_TEAM_LEAD' ? 'Team Lead' : 'Technician';
  const skills = [...new Set((member.skills || []).map(s => s.name))].join(', ') || 'No listed skills';
  return `
    <label class="team-reassign-option${blocker ? ' is-disabled' : ''}">
      <input type="radio" name="team-reassign-tech" value="${escapeHtml(member.id)}"${blocker ? ' disabled' : ''}>
      <span class="team-reassign-body">
        <span class="team-reassign-name"><strong>${escapeHtml(member.name)}</strong> <span class="text-dim">(${escapeHtml(role)})</span></span>
        <span class="team-reassign-meta">Load ${member.activeIncidentCount || 0} / ${member.maxCapacity || 0} &middot; ${escapeHtml(skills)}</span>
      </span>
      ${blocker ? `<span class="badge team-reassign-blocker">${escapeHtml(blocker)}</span>` : ''}
    </label>`;
}

// A team lead gives a ticket of the team to a technician of the same team (ADR-045, Phase M3). The roster and the ticket
// are read when the dialog opens, so the loads and the current holder are fresh.
async function openTeamReassignModal(ticketId) {
  if (STATE.teamReassignOpening) return;
  STATE.teamReassignOpening = true;
  let roster;
  let ticket;
  try {
    [roster, ticket] = await Promise.all([
      apiCall('/api/admin/technicians'),
      apiCall(`/api/technician/incidents/${encodeURIComponent(ticketId)}`)
    ]);
  } catch (err) {
    showToast(err.message, 'error');
    return;
  } finally {
    STATE.teamReassignOpening = false;
  }
  if (!STATE.currentUser) return;
  const members = roster || [];
  const verb = ticket.assigneeId ? 'Reassign' : 'Assign';
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">${verb} ticket ${escapeHtml(ticket.id)}</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-team-reassign" class="space-y-4">
      <p class="text-xs text-muted">${escapeHtml(ticket.title)}</p>
      <p class="text-xs text-muted">${ticket.assigneeId
        ? `Now with <strong>${escapeHtml(ticket.assigneeName || ticket.assigneeId)}</strong> (${escapeHtml(ticket.status)}). The new technician will have to acknowledge it.`
        : 'Waiting for a technician.'} Only members of your team can be chosen.</p>
      <div class="form-group">
        <label class="form-label">Technician</label>
        <div id="team-reassign-options" class="team-reassign-list" role="radiogroup" aria-label="Technician">
          ${members.map(m => teamMemberOptionHtml(m, ticket.assigneeId)).join('') || '<p class="text-xs text-muted">No team members found.</p>'}
        </div>
      </div>
      <div class="form-group">
        <label class="form-label" for="modal-team-reassign-reason">Reason (kept as an internal note)</label>
        <textarea id="modal-team-reassign-reason" class="form-textarea" rows="2" required placeholder="Why does this technician take the ticket?"></textarea>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">${verb}</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-team-reassign').addEventListener('submit', async (e) => {
    e.preventDefault();
    const chosen = document.querySelector('#form-modal-team-reassign input[name="team-reassign-tech"]:checked');
    const reason = document.getElementById('modal-team-reassign-reason').value.trim();
    if (!chosen) {
      showToast('Choose a technician first.', 'warning');
      return;
    }
    if (!reason) {
      showToast('A reason is required.', 'warning');
      return;
    }
    try {
      await apiCall(`/api/technician/incidents/${encodeURIComponent(ticket.id)}/reassign`, {
        method: 'POST',
        body: JSON.stringify({ technicianId: chosen.value, reason })
      });
      closeModal();
      showToast(`Ticket ${ticket.id} given to ${(members.find(m => m.id === chosen.value) || {}).name || chosen.value}.`, 'success');
      refreshActiveTab();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

function openReclassifyModal(incidentId) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Triage &amp; Reclassify</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal>&times;</button>
    </div>
    <form id="form-modal-reclassify" class="space-y-4">
      <div class="form-group">
        <label class="form-label">New Category</label>
        <select id="modal-new-category" class="form-select">
          <option value="DATABASE">DATABASE</option>
          <option value="NETWORK">NETWORK</option>
          <option value="APPLICATION">APPLICATION</option>
          <option value="SECURITY">SECURITY</option>
          <option value="CLOUD">CLOUD</option>
          <option value="HARDWARE">HARDWARE</option>
        </select>
      </div>
      <div class="form-group">
        <label class="form-label">Audit Reason</label>
        <input type="text" id="modal-reclassify-reason" class="form-input" required value="Human triage reclassification after out-of-scope report">
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary">Reclassify &amp; Route</button>
      </div>
    </form>
  `);

  document.getElementById('form-modal-reclassify').addEventListener('submit', async (e) => {
    e.preventDefault();
    const category = document.getElementById('modal-new-category').value;
    const reason = document.getElementById('modal-reclassify-reason').value.trim();
    try {
      await apiCall(`/api/admin/incidents/${incidentId}/reclassify`, {
        method: 'POST',
        body: JSON.stringify({ category, reason })
      });
      closeModal();
      showToast('Incident reclassified and dispatched.', 'success');
      loadAdminTriageQueue();
    } catch (err) {
      showToast(err.message, 'error');
    }
  });
}

// ============================================================================
// 13. DOM Event Listeners Setup
// ============================================================================
function setupEventListeners() {
  // Login form
  const loginForm = document.getElementById('form-login');
  if (loginForm) {
    loginForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const email = document.getElementById('login-email').value;
      const pwd = document.getElementById('login-password').value;
      loginUser(email, pwd);
    });
  }

  const clearLoginAlert = () => {
    const el = document.getElementById('login-error-alert');
    if (el) el.classList.add('hidden');
  };
  const emailInput = document.getElementById('login-email');
  if (emailInput) emailInput.addEventListener('input', clearLoginAlert);
  const pwdInput = document.getElementById('login-password');
  if (pwdInput) pwdInput.addEventListener('input', clearLoginAlert);

  // Register form
  const regForm = document.getElementById('form-register');
  if (regForm) {
    regForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (registerInFlight) return;
      registerInFlight = true;
      const regButton = document.getElementById('btn-register-submit');
      const regButtonHtml = regButton ? regButton.innerHTML : '';
      if (regButton) {
        regButton.disabled = true;
        regButton.setAttribute('aria-busy', 'true');
        regButton.innerHTML = '<span>Creating account...</span>';
      }
      const regAlert = document.getElementById('register-error-alert');
      if (regAlert) regAlert.classList.add('hidden');

      const name = document.getElementById('reg-name').value.trim();
      const email = document.getElementById('reg-email').value.trim();
      const pwd = document.getElementById('reg-password').value;
      const dept = document.getElementById('reg-department').value;

      try {
        const res = await fetch('/api/auth/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, email, password: pwd, department: dept })
        });
        if (res.status === 429) {
          throw new Error(rateLimitMessage(res));
        }
        if (!res.ok) {
          const err = await res.json().catch(() => ({ message: 'Registration failed' }));
          throw new Error(err.message || 'Registration failed');
        }
        const data = await res.json();
        showToast('Registration successful! Signing in...', 'success');
        setAuthenticatedUser({
          token: data.token,
          refreshToken: data.refreshToken,
          id: data.id,
          name: data.name,
          email: data.email,
          role: data.role
        });
      } catch (err) {
        if (regAlert) {
          regAlert.innerHTML = `
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="icon text-red" style="flex-shrink:0; margin-top: 0.1rem;">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"/>
            </svg>
            <div>
              <div style="font-weight: 700; color: var(--danger-text); margin-bottom: 0.2rem;">Registration Failed</div>
              <div style="color: var(--danger-text); font-size: 0.8125rem;">${escapeHtml(err.message)}</div>
            </div>
          `;
          regAlert.classList.remove('hidden');
        }
        showToast(err.message, 'error');
      } finally {
        registerInFlight = false;
        if (regButton) {
          regButton.disabled = false;
          regButton.removeAttribute('aria-busy');
          regButton.innerHTML = regButtonHtml;
        }
      }
    });
  }

  // Toggle Login/Register
  document.getElementById('btn-toggle-register').addEventListener('click', () => {
    clearLoginAlert();
    document.getElementById('login-card').classList.add('hidden');
    document.getElementById('register-card').classList.remove('hidden');
  });
  document.getElementById('btn-toggle-login').addEventListener('click', () => {
    const regAlert = document.getElementById('register-error-alert');
    if (regAlert) regAlert.classList.add('hidden');
    document.getElementById('register-card').classList.add('hidden');
    document.getElementById('login-card').classList.remove('hidden');
  });

  // Demo pills in auth view
  document.querySelectorAll('.demo-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      const email = pill.getAttribute('data-email');
      document.getElementById('login-email').value = email;
      document.getElementById('login-password').value = STATE.demoPassword;
      loginUser(email, STATE.demoPassword);
    });
  });

  // Persona chips in header bar
  document.querySelectorAll('.persona-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      const email = chip.getAttribute('data-email');
      loginUser(email, STATE.demoPassword);
    });
  });

  // Sign out button
  document.getElementById('btn-logout').addEventListener('click', () => handleSignOut(true));
  setupSecurityListeners();
  setupTeamMemberListeners();

  // Navigation tabs
  document.querySelectorAll('.nav-tab').forEach(tab => {
    tab.addEventListener('click', () => switchTab(tab.getAttribute('data-target')));
  });

  // Admin subtabs
  document.querySelectorAll('.admin-subtab').forEach(subtab => {
    subtab.addEventListener('click', () => {
      document.querySelectorAll('.admin-subtab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.admin-panel').forEach(p => p.classList.remove('active'));
      subtab.classList.add('active');
      const target = subtab.getAttribute('data-subtarget');
      document.getElementById(target).classList.add('active');
      STATE.activeAdminSubtab = target;
      loadAdminView();
    });
  });

  // Employee portal: report form, quick-fill chips, sub-views, refresh (ADR-050)
  setupEmployeePortalListeners();

  const refTech = document.getElementById('btn-refresh-tech-queue');
  if (refTech) refTech.addEventListener('click', () => {
    loadTechnicianQueue();
    loadTechnicianDashboard();
  });

  const btnTechMy = document.getElementById('btn-tech-mode-my');
  const btnTechTeam = document.getElementById('btn-tech-mode-team');
  if (btnTechMy && btnTechTeam) {
    btnTechMy.addEventListener('click', () => {
      setTechQueueMode('my');
      loadTechnicianQueue();
    });
    btnTechTeam.addEventListener('click', () => {
      setTechQueueMode('team');
      loadTechnicianQueue();
    });
  }

  const refTeam = document.getElementById('btn-refresh-teamlead');
  if (refTeam) refTeam.addEventListener('click', () => loadTeamLeadView());

  const refTriage = document.getElementById('btn-refresh-triage');
  if (refTriage) refTriage.addEventListener('click', loadAdminTriageQueue);

  const filterTech = document.getElementById('tech-queue-filter');
  if (filterTech) filterTech.addEventListener('change', loadTechnicianQueue);

  const btnSearch = document.getElementById('btn-admin-search');
  if (btnSearch) btnSearch.addEventListener('click', searchAdminIncidents);

  // Fast forward buttons
  document.querySelectorAll('.sim-ff-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const minutes = btn.getAttribute('data-minutes');
      try {
        await apiCall(`/api/simulation/advance?minutes=${minutes}`, { method: 'POST' });
        showToast(`Clock advanced by +${minutes} minutes`, 'info');
        updateSimulationClock();
        refreshActiveTab();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });
  });

  // Speed multiplier buttons
  document.querySelectorAll('.sim-speed-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const speed = btn.getAttribute('data-speed');
      try {
        await apiCall(`/api/simulation/speed?multiplier=${speed}`, { method: 'POST' });
        showToast(`Clock speed set to ${speed}x`, 'info');
        updateSimulationClock();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });
  });

  // Reset clock button
  const resetBtn = document.getElementById('btn-clock-reset');
  if (resetBtn) {
    resetBtn.addEventListener('click', async () => {
      try {
        await apiCall('/api/simulation/reset', { method: 'POST' });
        showToast('Virtual clock reset to real-time.', 'info');
        updateSimulationClock();
        refreshActiveTab();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });
  }

  // Scenarios buttons S1-S5
  document.querySelectorAll('.btn-run-scenario').forEach(btn => {
    btn.addEventListener('click', () => {
      const s = btn.getAttribute('data-scenario');
      runScenario(s);
    });
  });

  // Terminal copy/clear
  const copyBtn = document.getElementById('btn-copy-sim-logs');
  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      const logs = document.getElementById('sim-terminal-logs').innerText;
      navigator.clipboard.writeText(logs).then(() => showToast('Terminal logs copied to clipboard!', 'info'));
    });
  }

  const clearBtn = document.getElementById('btn-clear-sim-logs');
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      document.getElementById('sim-terminal-logs').innerHTML = '<span class="log-entry info">// Terminal cleared.</span>';
    });
  }

  // In-app notifications bell
  const bell = document.getElementById('btn-notification-bell');
  if (bell) {
    bell.addEventListener('click', async () => {
      const modal = document.getElementById('notification-modal');
      const container = document.getElementById('notifications-list-container');
      modal.classList.remove('hidden');
      container.innerHTML = '<div class="text-center text-muted" style="padding: 2rem;">Loading notifications...</div>';

      try {
        const notifs = await apiCall('/api/notifications');
        if (!notifs || notifs.length === 0) {
          container.innerHTML = '<div class="text-center text-muted" style="padding: 2rem;">No notifications.</div>';
          return;
        }

        container.innerHTML = notifs.map(n => `
          <div class="card" style="padding: 0.65rem; background: #ffffff; border: 1px solid ${n.read ? 'var(--border)' : 'var(--primary)'};">
            <div class="flex items-center justify-between" style="margin-bottom: 0.2rem;">
              <span class="badge ${n.channel === 'EMAIL' ? 'badge-p3' : 'badge-status-in-progress'}">${escapeHtml(n.channel)}</span>
              <span class="text-xs text-dim font-mono">${formatDate(n.createdAt)}</span>
            </div>
            <p class="text-xs text-main" style="margin-top: 0.2rem;">${escapeHtml(n.message)}</p>
          </div>
        `).join('');
      } catch (err) {
        container.innerHTML = `<div class="text-center text-red" style="padding: 2rem;">Error: ${escapeHtml(err.message)}</div>`;
      }
    });
  }

  const closeNotifs = document.getElementById('btn-close-notifications');
  if (closeNotifs) {
    closeNotifs.addEventListener('click', () => {
      document.getElementById('notification-modal').classList.add('hidden');
    });
  }

  setupTicketDetailListeners();
  setupStrategyComparisonListeners();
  setupAdminFilterMultiSelects();
}

function formatDate(isoStr) {
  if (!isoStr) return '--';
  try {
    const d = new Date(isoStr);
    if (isNaN(d.getTime())) return isoStr;
    return d.toISOString().replace('T', ' ').substring(0, 16);
  } catch (e) {
    return isoStr;
  }
}

// ============================================================================
// 14. Admin Ticket Inspection Dialog
// ============================================================================
const TICKET_DETAIL_EMPTY = '--';
const TICKET_DETAIL_MAX_RATING = 5;
const SECONDS_PER_MINUTE = 60;
const MINUTES_PER_HOUR = 60;
const SLA_STATUS_LABELS = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  WARNING: 'Warning',
  BREACHED: 'Breached',
  MET: 'Met'
};
const SOLVED_STATUSES = ['RESOLVED', 'VERIFIED', 'CLOSED'];

// --- Small rendering helpers (every dynamic value passes through escapeHtml) ---
function tdEsc(value) {
  return value === null || value === undefined ? '' : escapeHtml(String(value));
}

function tdText(value) {
  const text = tdEsc(value);
  return text === '' ? TICKET_DETAIL_EMPTY : text;
}

function tdMono(value) {
  return `<span class="font-mono">${tdText(value)}</span>`;
}

/** A person shown by name with the stored id beside it (ADR-037); an actor without a known name shows the stored text. */
function tdActor(id, name) {
  if (id === null || id === undefined || id === '') {
    return TICKET_DETAIL_EMPTY;
  }
  if (!name) {
    return `<span class="font-mono">${tdEsc(id)}</span>`;
  }
  return `${tdEsc(name)} <span class="font-mono text-xs text-muted">(${tdEsc(id)})</span>`;
}

function tdDate(iso) {
  return `<span class="font-mono">${escapeHtml(formatDate(iso))}</span>`;
}

function tdNum(value, digits) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(digits) : TICKET_DETAIL_EMPTY;
}

function tdEmpty(message) {
  return `<div class="td-empty">${escapeHtml(message)}</div>`;
}

function tdRows(rows) {
  return rows.map(([label, valueHtml]) => `<dt>${escapeHtml(label)}</dt><dd>${valueHtml}</dd>`).join('');
}

function tdHyphenate(value) {
  return String(value || '').toLowerCase().replace(/_/g, '-');
}

function tdHumanizeFactor(name) {
  return String(name || '').replace(/([a-z])([A-Z])/g, '$1 $2');
}

function tdFormatDuration(totalSeconds) {
  const seconds = Number(totalSeconds) || 0;
  if (seconds < SECONDS_PER_MINUTE) return `${seconds} s`;
  const minutes = Math.floor(seconds / SECONDS_PER_MINUTE);
  if (minutes < MINUTES_PER_HOUR) return `${minutes} min`;
  return `${Math.floor(minutes / MINUTES_PER_HOUR)} h ${minutes % MINUTES_PER_HOUR} min`;
}

function tdSetHtml(id, html) {
  document.getElementById(id).innerHTML = html;
}

function tdSetText(id, text) {
  document.getElementById(id).textContent = text;
}

// --- Section renderers ---
function renderTicketDetailHeader(d) {
  tdSetText('ticket-detail-id', d.id);
  tdSetText('ticket-detail-display-name', d.displayId && d.displayId !== d.id ? d.displayId : '');
  tdSetText('ticket-detail-title', d.title);
  const priority = d.priorityDecision ? d.priorityDecision.priority : null;
  tdSetHtml('ticket-detail-pills', [
    `<span class="badge" style="background: #f1f5f9; color: var(--text-muted); border: 1px solid var(--border);">${tdText(d.category || 'GENERAL')}</span>`,
    `<span class="badge badge-status-${tdHyphenate(d.status)}">${tdText(d.status)}</span>`,
    priority ? `<span class="badge badge-${tdHyphenate(priority)}">${tdEsc(priority)}</span>` : '',
    `<span class="badge badge-status-new" title="Status shown to the employee">Public: ${tdText(d.publicStatus)}</span>`,
    `<span class="badge badge-p4">Reopened: ${tdEsc(d.reopenCount || 0)}</span>`
  ].join(''));
}

function tdCriticalityPill(service) {
  if (!service.criticalityTier) return TICKET_DETAIL_EMPTY;
  const tier = (/TIER_(\d)/.exec(service.criticalityTier) || [])[1] || '1';
  return `<span class="badge badge-tier-${tier}">${tdText(service.criticalityLabel || service.criticalityTier)}</span>`;
}

function renderTicketDetailIdentity(d) {
  const reporter = d.reporter || {};
  const service = d.service || {};
  const team = d.supportTeam || {};
  const sla = d.slaTracker || {};
  const resolution = d.resolution || {};
  const technician = d.assigneeName
    ? tdText(d.assigneeName)
    : '<span class="badge badge-p4">Unassigned</span>';

  tdSetHtml('td-reporter', tdRows([
    ['Name', tdText(reporter.name || d.reporterName)],
    ['Email', tdText(reporter.email)],
    ['Department', tdText(reporter.department || d.reporterDepartment)],
    ['Designation', tdText(reporter.designation)],
    ['Reporter ID', tdMono(d.reporterId)]
  ]));
  tdSetHtml('td-service', tdRows([
    ['Service ID', tdMono(d.serviceId)],
    ['Service', tdText(service.name)],
    ['Criticality', tdCriticalityPill(service)]
  ]));
  tdSetHtml('td-assignment', tdRows([
    [d.assigneeId ? 'Support team' : 'Owning team', tdText(team.name)],
    ['Technician', technician],
    ['Technician ID', tdMono(d.assigneeId)]
  ]));
  tdSetHtml('td-lifecycle', tdRows([
    ['Created', tdDate(d.createdAt)],
    ['Responded', tdDate(sla.respondedAt)],
    ['Resolved', tdDate(sla.resolvedAt || resolution.resolvedAt)],
    ['Last modified', tdDate(d.lastModifiedAt)]
  ]));
}

function tdMetric(label, valueHtml, subText) {
  return `<div class="td-metric"><div class="td-metric-label">${escapeHtml(label)}</div>`
    + `<div class="td-metric-value">${valueHtml}</div><div class="td-metric-sub">${tdEsc(subText)}</div></div>`;
}

function tdSlaPills(sla, ticketStatus) {
  const label = SLA_STATUS_LABELS[sla.status] || sla.status;
  const paused = ticketStatus === 'PENDING_INFO'
    ? '<span class="badge badge-sla-paused">Clock paused</span>'
    : '';
  return `<span class="badge badge-sla-${tdHyphenate(sla.status)}">${tdText(label)}</span>${paused}`;
}

function renderTicketDetailSla(d) {
  const sla = d.slaTracker;
  if (!sla) {
    tdSetHtml('td-sla', tdEmpty('No SLA tracker is attached to this ticket yet.'));
    return;
  }
  const pausedSeconds = Number(sla.pausedDurationSeconds) || 0;
  tdSetHtml('td-sla', `
    <div class="flex items-center gap-2" style="margin-bottom: 0.75rem;">
      <span class="td-score-label">SLA status</span>${tdSlaPills(sla, d.status)}
    </div>
    <div class="td-metrics">
      ${tdMetric('Response due', tdDate(sla.responseDueAt), `Responded: ${sla.respondedAt ? formatDate(sla.respondedAt) : 'not yet'}`)}
      ${tdMetric('Resolution due', tdDate(sla.resolutionDueAt), `Resolved: ${sla.resolvedAt ? formatDate(sla.resolvedAt) : 'not yet'}`)}
      ${tdMetric('Paused duration', escapeHtml(tdFormatDuration(pausedSeconds)), `${pausedSeconds} seconds accrued while waiting for information`)}
    </div>`);
}

function tdFactorRow(factor, compositeScore) {
  const share = compositeScore > 0
    ? Math.min(100, Math.round((Number(factor.weightedScore) / compositeScore) * 100))
    : 0;
  return `<tr>
    <td class="font-semibold">${tdText(tdHumanizeFactor(factor.factorName))}</td>
    <td class="td-num">${tdNum(factor.rawScore, 2)}</td>
    <td class="td-num">${tdNum(factor.weight, 2)}</td>
    <td class="td-num" style="min-width: 110px;">${tdNum(factor.weightedScore, 3)}
      <div class="td-bar"><div class="td-bar-fill" style="width: ${share}%;"></div></div></td>
    <td class="td-explanation">${tdText(factor.explanation)}</td>
  </tr>`;
}

function tdFactorTable(decision) {
  const factors = decision.factorContributions || [];
  if (factors.length === 0) {
    return '<div class="td-empty" style="padding: 0.9rem 1rem;">Factor breakdown was not recorded for this ticket.</div>';
  }
  return `<table class="table"><thead><tr><th>Factor</th><th class="td-num">Raw (s)</th>`
    + `<th class="td-num">Weight (w)</th><th class="td-num">Weighted (w &times; s)</th><th>Explanation</th></tr></thead>`
    + `<tbody>${factors.map(f => tdFactorRow(f, Number(decision.compositeScore))).join('')}</tbody></table>`;
}

function tdOverrideInfo(decision) {
  const rules = (decision.rulesApplied || [])
    .map(rule => `<span class="td-chip">${tdEsc(rule)}</span>`).join('');
  const rulesHtml = rules
    ? `<div style="margin-top: 0.85rem;"><div class="td-score-label" style="margin-bottom: 0.4rem;">Policy rules applied</div><div class="td-chips">${rules}</div></div>`
    : '';
  const overrideHtml = decision.overriddenBy
    ? `<div class="td-callout"><strong>Manual override</strong> by ${tdActor(decision.overriddenBy, decision.overriddenByName)} &middot; ${tdDate(decision.calculatedAt)}<br>Reason: ${tdText(decision.overrideReason)}</div>`
    : '';
  return (rulesHtml || overrideHtml) ? rulesHtml + overrideHtml : tdEmpty('No policy rules or manual overrides applied.');
}

function renderTicketDetailPriority(d) {
  const decision = d.priorityDecision;
  if (!decision) {
    tdSetHtml('td-priority-summary', tdEmpty('Priority has not been calculated for this ticket yet.'));
    tdSetHtml('td-priority-factors', '');
    tdSetHtml('td-priority-overrides', '');
    return;
  }
  tdSetHtml('td-priority-summary', `
    <div><div class="td-score-label">Composite score</div>
      <div class="td-score" title="${tdNum(decision.compositeScore, 4)}">${tdNum(decision.compositeScore, 3)}</div></div>
    <span class="badge badge-${tdHyphenate(decision.priority)}">${tdText(decision.priority)}</span>
    ${decision.overriddenBy ? '<span class="text-xs text-muted">priority set manually</span>' : ''}`);
  tdSetHtml('td-priority-factors', tdFactorTable(decision));
  tdSetHtml('td-priority-overrides', tdOverrideInfo(decision));
}

// --- Why this technician (ADR-044): the recorded assignment decisions, administrators only ---
const TD_WHY_FACTOR_LABELS = {
  SKILL_MATCH: 'Skill match',
  LOAD: 'Free capacity',
  LOAD_PENALTY: 'Load penalty',
  EXPERIENCE: 'Experience',
  SERVICE_EXPERIENCE: 'Service experience',
  ROTATION: 'Rotation',
  STRATEGY_SCORE: 'Strategy score'
};

function tdWhyFactorLabel(key) {
  return TD_WHY_FACTOR_LABELS[key] || tdHumanizeFactor(String(key || '').toLowerCase().replace(/_/g, ' '));
}

/** One factor cell: raw x weight = contribution; weight 0 only breaks ties, a negative weight is a penalty. */
function tdWhyFactorCell(factor) {
  if (!factor) {
    return `<td class="td-num">${TICKET_DETAIL_EMPTY}</td>`;
  }
  const weight = Number(factor.weight);
  const kind = weight < 0 ? ' td-why-penalty' : (weight === 0 ? ' td-why-tiebreak' : '');
  const note = weight === 0 ? 'tie-break only' : (weight < 0 ? 'penalty' : '');
  return `<td class="td-num${kind}" title="${tdEsc(factor.detail)}">`
    + `${tdNum(factor.rawScore, 2)} &times; ${tdNum(factor.weight, 2)} = ${tdNum(factor.contribution, 3)}`
    + `<div class="td-why-detail">${tdEsc(factor.detail)}${note ? ` <em>(${note})</em>` : ''}</div></td>`;
}

function tdWhyCandidateRow(candidate, factorKeys) {
  const byKey = {};
  (candidate.factors || []).forEach(f => { byKey[f.key] = f; });
  const chosen = candidate.chosen === true;
  return `<tr class="${chosen ? 'td-why-chosen' : ''}">`
    + `<td class="td-num">${tdEsc(candidate.rank)}${chosen ? ' <span class="badge badge-p4">chosen</span>' : ''}</td>`
    + `<td>${tdActor(candidate.technicianId, candidate.technicianName)}</td>`
    + factorKeys.map(key => tdWhyFactorCell(byKey[key])).join('')
    + `<td class="td-num font-semibold">${tdNum(candidate.total, 3)}</td></tr>`;
}

function tdWhyCandidateTable(decision) {
  const candidates = decision.candidates || [];
  if (candidates.length === 0) {
    return '';
  }
  const factorKeys = (candidates[0].factors || []).map(f => f.key);
  const shown = Number(decision.candidateCount) > candidates.length
    ? `<div class="td-why-note">Showing the best ${candidates.length} of ${tdEsc(decision.candidateCount)} ranked candidates.</div>`
    : '';
  return `<div class="table-container"><table class="table td-why-table"><thead><tr><th class="td-num">Rank</th><th>Technician</th>`
    + factorKeys.map(key => `<th class="td-num">${tdEsc(tdWhyFactorLabel(key))}<div class="td-why-detail">raw &times; weight</div></th>`).join('')
    + `<th class="td-num">Total</th></tr></thead><tbody>`
    + candidates.map(c => tdWhyCandidateRow(c, factorKeys)).join('')
    + `</tbody></table></div>${shown}`;
}

function tdWhyDecision(decision) {
  const strategy = decision.strategyName
    ? `<span class="td-chip">${tdEsc(decision.strategyName)}</span>` : '';
  return `<div class="td-why-decision"><div class="td-why-head">`
    + `<span class="badge badge-p4">${tdText(decision.poolLabel)}</span>${strategy}`
    + `<span class="td-timeline-time">${escapeHtml(formatDate(decision.decidedAt))}</span>`
    + `<span class="text-xs text-muted">by ${tdActor(decision.actorId, decision.actorName)}</span></div>`
    + `<p class="td-why-summary">${tdEsc(decision.summary)}</p>${tdWhyCandidateTable(decision)}</div>`;
}

function renderTicketDetailAssignmentWhy(d) {
  const decisions = d.assignmentDecisions || [];
  tdSetHtml('td-assignment-why', decisions.length
    ? decisions.map(tdWhyDecision).join('')
    : tdEmpty('No assignment record for this ticket.'));
}

function tdNoteHtml(note) {
  const internal = note.internalOnly ? '<span class="badge badge-p4">Internal</span>' : '';
  return `<div class="td-note"><div class="td-note-meta"><strong class="text-main">${tdText(note.authorName)}</strong>`
    + `<span class="font-mono">${escapeHtml(formatDate(note.createdAt))}</span>${internal}</div>`
    + `<div class="td-note-text">${tdEsc(note.noteText)}</div></div>`;
}

function tdResolutionHtml(resolution) {
  return `<div class="td-resolution"><div class="td-card-title">Resolution</div><dl class="td-dl">${tdRows([
    ['Root cause', tdText(resolution.rootCause)],
    ['Corrective actions', tdText(resolution.stepsTaken)],
    ['Preventive action', tdText(resolution.preventiveAction)],
    ['Confirmed category', tdText(resolution.confirmedCategory)],
    ['Resolved by', tdActor(resolution.resolvedBy, resolution.resolvedByName)],
    ['Resolved at', tdDate(resolution.resolvedAt)]
  ])}</dl></div>`;
}

function renderTicketDetailNotes(d) {
  const notes = [...(d.notes || [])].sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)));
  tdSetHtml('td-notes', notes.length ? notes.map(tdNoteHtml).join('') : tdEmpty('No investigation notes recorded.'));
  if (d.resolution) {
    tdSetHtml('td-resolution', tdResolutionHtml(d.resolution));
  } else if (SOLVED_STATUSES.includes(d.status)) {
    tdSetHtml('td-resolution', tdEmpty('This ticket has no resolution record.'));
  } else {
    tdSetHtml('td-resolution', '');
  }
}

function tdStars(rating) {
  const filled = Math.max(0, Math.min(TICKET_DETAIL_MAX_RATING, Number(rating) || 0));
  return Array.from({ length: TICKET_DETAIL_MAX_RATING },
    (_, i) => `<span class="${i < filled ? 'td-star-on' : ''}">&#9733;</span>`).join('');
}

function renderTicketDetailFeedback(d) {
  const feedback = d.feedback;
  if (!feedback) {
    tdSetHtml('td-feedback', tdEmpty('No feedback has been submitted for this ticket.'));
    return;
  }
  tdSetHtml('td-feedback', `
    <div class="flex items-center gap-3">
      <span class="td-stars" aria-label="${tdEsc(feedback.rating)} out of ${TICKET_DETAIL_MAX_RATING} stars">${tdStars(feedback.rating)}</span>
      <span class="font-semibold">${tdEsc(feedback.rating)} / ${TICKET_DETAIL_MAX_RATING}</span>
      <span class="text-xs text-dim font-mono">${escapeHtml(formatDate(feedback.submittedAt))}</span>
    </div>
    <p class="text-sm text-muted" style="margin-top: 0.4rem;">${feedback.comment ? tdEsc(feedback.comment) : 'No comment left.'}</p>`);
}

function renderTicketDetailLinked(d) {
  const linked = [...(d.linkedIncidentIds || [])].sort();
  const triage = d.needsTriage
    ? '<span class="badge badge-p2">Needs triage</span>'
    : '<span class="text-xs text-muted">Not flagged for triage</span>';
  const chips = linked.length
    ? `<div class="td-chips">${linked.map(id => `<span class="td-chip">${tdEsc(id)}</span>`).join('')}</div>`
    : tdEmpty('No linked duplicate tickets.');
  tdSetHtml('td-linked', `<div class="flex items-center gap-2" style="margin-bottom: 0.6rem;"><span class="text-sm text-muted">Triage flag</span>${triage}</div>${chips}`);
}

function tdAuditItem(record) {
  const transition = (record.previousState || record.newState)
    ? `<span class="badge badge-p4">${tdText(record.previousState)} &rarr; ${tdText(record.newState)}</span>`
    : '';
  return `<li class="td-timeline-item"><div class="td-timeline-head"><strong>${tdText(record.action)}</strong>`
    + `<span class="td-timeline-time">${escapeHtml(formatDate(record.timestamp))}</span>`
    + `<span class="text-xs text-muted">by ${tdActor(record.actorId, record.actorName)}</span>${transition}</div>`
    + `<div class="td-timeline-desc">${tdEsc(record.description)}</div></li>`;
}

function renderTicketDetailAudit(d) {
  const records = d.auditTrail || [];
  tdSetHtml('td-audit', records.length
    ? `<ol class="td-timeline">${records.map(tdAuditItem).join('')}</ol>`
    : tdEmpty('No audit records for this ticket.'));
}

function renderAdminTicketDetail(d) {
  renderTicketDetailHeader(d);
  tdSetText('td-description', d.description || 'No description provided.');
  renderTicketDetailIdentity(d);
  renderTicketDetailSla(d);
  renderTicketDetailPriority(d);
  renderTicketDetailAssignmentWhy(d);
  renderTicketDetailNotes(d);
  renderTicketDetailFeedback(d);
  renderTicketDetailLinked(d);
  renderTicketDetailAudit(d);
}

// --- Dialog state, open / close ---
function isTicketDetailOpen() {
  const modal = document.getElementById('admin-ticket-detail-modal');
  return !!modal && !modal.classList.contains('hidden');
}

function showTicketDetailPanel(panel) {
  document.getElementById('ticket-detail-loading').classList.toggle('hidden', panel !== 'loading');
  document.getElementById('ticket-detail-error').classList.toggle('hidden', panel !== 'error');
  document.getElementById('ticket-detail-content').classList.toggle('hidden', panel !== 'content');
}

function showTicketDetailLoading(ticketId) {
  tdSetText('ticket-detail-id', ticketId);
  tdSetText('ticket-detail-title', '');
  tdSetHtml('ticket-detail-pills', '');
  showTicketDetailPanel('loading');
  document.getElementById('ticket-detail-body').scrollTop = 0;
  document.getElementById('admin-ticket-detail-modal').classList.remove('hidden');
  document.body.classList.add('modal-open');
  document.getElementById('btn-close-ticket-detail').focus();
}

function showTicketDetailError(message) {
  tdSetText('ticket-detail-error', `Could not load ticket details: ${message}`);
  showTicketDetailPanel('error');
}

async function openAdminTicketDetailModal(ticketId, originElement = null) {
  const detail = STATE.adminTicketDetail;
  detail.id = ticketId;
  detail.origin = originElement;
  const requestSeq = ++detail.requestSeq;
  showTicketDetailLoading(ticketId);
  try {
    const data = await apiCall('/api/admin/incidents/' + encodeURIComponent(ticketId));
    if (requestSeq !== detail.requestSeq) return; // closed or superseded while loading
    renderAdminTicketDetail(data);
    showTicketDetailPanel('content');
  } catch (err) {
    if (requestSeq === detail.requestSeq) showTicketDetailError(err.message);
  }
}

function closeAdminTicketDetailModal(options = {}) {
  const detail = STATE.adminTicketDetail;
  detail.requestSeq++; // discard any in-flight response
  document.getElementById('admin-ticket-detail-modal').classList.add('hidden');
  document.body.classList.remove('modal-open');
  const origin = detail.origin;
  detail.id = null;
  detail.origin = null;
  if (options.restoreFocus !== false && origin && origin.isConnected) origin.focus();
}

function handOffFromTicketDetail(openAction) {
  const ticketId = STATE.adminTicketDetail.id;
  closeAdminTicketDetailModal({ restoreFocus: false });
  if (ticketId) openAction(ticketId);
}

function setupTicketDetailListeners() {
  const modal = document.getElementById('admin-ticket-detail-modal');
  if (!modal) return;
  modal.addEventListener('click', (e) => {
    if (e.target === modal) closeAdminTicketDetailModal();
  });
  document.getElementById('btn-close-ticket-detail').addEventListener('click', () => closeAdminTicketDetailModal());
  document.getElementById('btn-ticket-detail-close').addEventListener('click', () => closeAdminTicketDetailModal());
  document.getElementById('btn-ticket-detail-override').addEventListener('click', () => handOffFromTicketDetail(openPriorityOverrideModal));
  document.getElementById('btn-ticket-detail-reassign').addEventListener('click', () => handOffFromTicketDetail(openReassignModal));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && isTicketDetailOpen()) closeAdminTicketDetailModal();
  });
}

function bindAdminTicketLinks(container) {
  container.querySelectorAll('.btn-admin-view-ticket').forEach(link => {
    const open = () => openAdminTicketDetailModal(link.getAttribute('data-id'), link);
    link.addEventListener('click', open);
    link.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        open();
      }
    });
  });
}

// ============================================================================
// 15. Multi-Select Filter Dropdowns (Admin Command Centre)
// ============================================================================
const MULTISELECT_MAX_LISTED = 2;
const ADMIN_FILTERS = { categories: null, priorities: null, statuses: null, slaStates: null };

function buildMultiSelectOption(option) {
  const el = document.createElement('div');
  el.className = 'multiselect-option';
  el.setAttribute('role', 'option');
  el.setAttribute('tabindex', '-1');
  el.setAttribute('aria-selected', 'false');
  el.dataset.value = option.value;
  const box = document.createElement('span');
  box.className = 'custom-checkbox';
  box.setAttribute('aria-hidden', 'true');
  const label = document.createElement('span');
  label.className = 'option-label';
  label.textContent = option.label || option.value;
  el.append(box, label);
  return el;
}

/**
 * Turns a `.custom-multiselect` container into a multi-select dropdown. The menu stays open while options are
 * toggled and closes on an outside click, Escape or Tab. Options are read from the markup (`data-value`), or
 * built from `options` ([{ value, label }]) when that array is given.
 * @returns {{getSelected: Function, setSelected: Function, clear: Function, open: Function, close: Function}|null}
 */
function initMultiSelect(containerId, options, onChange) {
  const container = document.getElementById(containerId);
  const trigger = container && container.querySelector('.multiselect-trigger');
  const menu = container && container.querySelector('.multiselect-menu');
  const list = container && container.querySelector('.multiselect-options');
  const triggerLabel = container && container.querySelector('.trigger-label');
  if (!trigger || !menu || !list || !triggerLabel) return null;

  if (Array.isArray(options) && options.length > 0) {
    list.replaceChildren(...options.map(buildMultiSelectOption));
  }
  const allLabel = container.getAttribute('data-all-label') || triggerLabel.textContent;
  const selected = new Set();
  const optionEls = () => Array.from(list.querySelectorAll('.multiselect-option'));
  const getSelected = () => optionEls().map(el => el.dataset.value).filter(value => selected.has(value));
  const isOpen = () => !menu.classList.contains('hidden');

  function render() {
    optionEls().forEach(el => {
      const on = selected.has(el.dataset.value);
      el.setAttribute('aria-selected', on ? 'true' : 'false');
      el.querySelector('.custom-checkbox').classList.toggle('selected', on);
    });
    const values = getSelected();
    if (values.length === 0) {
      triggerLabel.textContent = allLabel;
    } else if (values.length <= MULTISELECT_MAX_LISTED) {
      triggerLabel.textContent = values.join(', ');
    } else {
      triggerLabel.textContent = `${values.length} selected`;
    }
  }

  function change(mutate) {
    const before = getSelected().join('|');
    mutate();
    render();
    if (getSelected().join('|') !== before && typeof onChange === 'function') onChange(getSelected());
  }

  function setOpen(open) {
    menu.classList.toggle('hidden', !open);
    trigger.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  function focusOption(index) {
    const els = optionEls();
    if (els.length > 0) els[(index + els.length) % els.length].focus();
  }

  function toggleOption(el) {
    change(() => {
      const value = el.dataset.value;
      if (selected.has(value)) selected.delete(value); else selected.add(value);
    });
  }

  trigger.addEventListener('click', () => setOpen(!isOpen()));
  trigger.addEventListener('keydown', e => {
    if (e.key !== 'ArrowDown') return;
    e.preventDefault();
    setOpen(true);
    focusOption(0);
  });

  list.addEventListener('click', e => {
    const el = e.target.closest('.multiselect-option');
    if (el) toggleOption(el);
  });
  list.addEventListener('keydown', e => {
    const el = e.target.closest('.multiselect-option');
    if (!el) return;
    const index = optionEls().indexOf(el);
    const moves = { ArrowDown: index + 1, ArrowUp: index - 1, Home: 0, End: -1 };
    if (e.key in moves) {
      e.preventDefault();
      focusOption(moves[e.key]);
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      toggleOption(el);
    }
  });

  container.querySelector('.btn-select-all').addEventListener('click', () => {
    change(() => optionEls().forEach(el => selected.add(el.dataset.value)));
  });
  container.querySelector('.btn-clear-all').addEventListener('click', () => change(() => selected.clear()));

  container.addEventListener('keydown', e => {
    if (!isOpen()) return;
    if (e.key === 'Escape') {
      setOpen(false);
      trigger.focus();
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  });
  document.addEventListener('click', e => {
    if (isOpen() && !container.contains(e.target)) setOpen(false);
  });

  render();
  return {
    getSelected,
    setSelected(values) {
      const known = new Set(optionEls().map(el => el.dataset.value));
      change(() => {
        selected.clear();
        (values || []).filter(value => known.has(value)).forEach(value => selected.add(value));
      });
    },
    clear() { change(() => selected.clear()); },
    open() { setOpen(true); },
    close() { setOpen(false); }
  };
}

function setupAdminFilterMultiSelects() {
  ADMIN_FILTERS.categories = initMultiSelect('multiselect-category', null);
  ADMIN_FILTERS.priorities = initMultiSelect('multiselect-priority', null);
  ADMIN_FILTERS.statuses = initMultiSelect('multiselect-status', null);
  ADMIN_FILTERS.slaStates = initMultiSelect('multiselect-sla', null);
  const clearButton = document.getElementById('btn-admin-clear-filters');
  if (clearButton) clearButton.addEventListener('click', clearAdminFilters);
}

/** Empties every filter of the admin directory, then searches once (ADR-049). */
function clearAdminFilters() {
  document.getElementById('admin-search-text').value = '';
  [ADMIN_FILTERS.categories, ADMIN_FILTERS.priorities, ADMIN_FILTERS.statuses, ADMIN_FILTERS.slaStates]
    .forEach(filter => { if (filter) filter.clear(); });
  ['admin-filter-service', 'admin-filter-technician', 'admin-filter-from', 'admin-filter-to']
    .forEach(id => { document.getElementById(id).value = ''; });
  searchAdminIncidents();
}

// The service and technician lists are read once per sign-in (ADR-049); a failure leaves the "All ..." option only
const ADMIN_FILTER_OPTIONS = { loaded: false, requestSeq: 0 };

/** Keeps the first ("All ...") option of a select and appends one option per item; labels go in as text. */
function fillFilterSelect(selectId, items) {
  const select = document.getElementById(selectId);
  if (!select) return;
  const current = select.value;
  while (select.options.length > 1) select.remove(1);
  items.forEach(item => {
    const option = document.createElement('option');
    option.value = item.id;
    option.textContent = item.name || item.id;
    select.appendChild(option);
  });
  select.value = items.some(item => item.id === current) ? current : '';
}

async function loadAdminFilterOptions() {
  if (ADMIN_FILTER_OPTIONS.loaded) return;
  ADMIN_FILTER_OPTIONS.loaded = true;
  const seq = ++ADMIN_FILTER_OPTIONS.requestSeq;
  const [services, technicians] = await Promise.allSettled([
    apiCall('/api/admin/services'),
    apiCall('/api/admin/technicians')
  ]);
  if (seq !== ADMIN_FILTER_OPTIONS.requestSeq) return; // signed out (or reloaded) meanwhile
  if (services.status === 'fulfilled') fillFilterSelect('admin-filter-service', services.value || []);
  if (technicians.status === 'fulfilled') fillFilterSelect('admin-filter-technician', technicians.value || []);
  if (services.status !== 'fulfilled' || technicians.status !== 'fulfilled') {
    ADMIN_FILTER_OPTIONS.loaded = false; // try again the next time the catalogue is shown
  }
}

/** Sign-out: forget the lists and the chosen values, so the next user starts from "All ...". */
function resetAdminFilterOptions() {
  ADMIN_FILTER_OPTIONS.loaded = false;
  ADMIN_FILTER_OPTIONS.requestSeq++;
  fillFilterSelect('admin-filter-service', []);
  fillFilterSelect('admin-filter-technician', []);
}

// ============================================================================
// 16. Suggested Resolutions and the Knowledge Base (spec feature G, ADR-040 to ADR-043)
// ============================================================================
// Suggestions are fetched only when a technician opens them, never while a list is drawn. Every dynamic value goes
// through escapeHtml. Votes and promotions go through apiCall, so the in-flight guard, the refresh after a refused
// write (ADR-039) and the focus return apply exactly as for the other technician actions. A late answer for a dialog
// that was closed or replaced is discarded (STATE.suggestions.requestSeq).
const SUGGESTION_FAILURES = {
  ERR_ALREADY_PROMOTED: 'This ticket is already in the knowledge base.',
  ERR_NOT_RESOLVED: 'This ticket is no longer resolved, so its fix cannot be promoted. The list has been refreshed.',
  ERR_NOT_FOUND: 'That fix is no longer available. The suggestions have been refreshed.'
};

function suggestionFailureMessage(err) {
  if (err && err.code && SUGGESTION_FAILURES[err.code]) return SUGGESTION_FAILURES[err.code];
  if (err && err.status === 403) return 'You are not allowed to do this for this ticket. The list has been refreshed.';
  if (err && err.status === 404) return 'This ticket no longer exists. The list has been refreshed.';
  return (err && err.message) || 'The request failed.';
}

function formatSuggestionScore(value) {
  return (typeof value === 'number' && isFinite(value)) ? value.toFixed(3) : '-';
}

function suggestionVoteCount(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? String(Math.floor(n)) : '0';
}

function suggestionSourceHtml(s) {
  if (s.sourceType === 'KB_ARTICLE') {
    return `<span class="badge suggestion-source suggestion-source-kb">Knowledge base</span>
            <span class="font-mono text-xs">${escapeHtml(s.sourceId)}</span>`;
  }
  return `<span class="badge suggestion-source suggestion-source-ticket">Resolved ticket</span>
          <span class="font-mono text-xs">${escapeHtml(s.sourceDisplayId || s.sourceId)}</span>`;
}

function suggestionCardHtml(s) {
  const id = escapeHtml(s.sourceId);
  const type = escapeHtml(s.sourceType);
  const title = escapeHtml(s.title);
  const terms = (s.matchedTerms || []).map(t => `<span class="suggestion-term">${escapeHtml(t)}</span>`).join(' ');
  return `
    <li class="suggestion-card" data-source-id="${id}">
      <div class="suggestion-card-header">
        <div class="flex items-center gap-2">${suggestionSourceHtml(s)}</div>
        <span class="suggestion-score" title="Text similarity, and the ranking score after technicians' votes">
          Similarity ${formatSuggestionScore(s.similarity)} &middot; score ${formatSuggestionScore(s.score)}
        </span>
      </div>
      <h4 class="suggestion-title">${title}</h4>
      <dl class="suggestion-fields">
        <dt>Root cause</dt><dd>${escapeHtml(s.rootCause)}</dd>
        <dt>Steps taken</dt><dd>${escapeHtml(s.stepsTaken)}</dd>
        ${s.preventiveAction ? `<dt>Preventive action</dt><dd>${escapeHtml(s.preventiveAction)}</dd>` : ''}
      </dl>
      ${terms ? `<div class="suggestion-terms"><span class="text-xs text-muted">Matched terms:</span> ${terms}</div>` : ''}
      <div class="suggestion-votes">
        <button type="button" class="btn btn-xs btn-success btn-tech-vote-worked" data-id="${id}" data-source-type="${type}"
                aria-label="This fix worked: ${title}">This worked <span class="suggestion-count">${suggestionVoteCount(s.workedVotes)}</span></button>
        <button type="button" class="btn btn-xs btn-secondary btn-tech-vote-failed" data-id="${id}" data-source-type="${type}"
                aria-label="This fix did not work: ${title}">Didn't work <span class="suggestion-count">${suggestionVoteCount(s.didNotWorkVotes)}</span></button>
      </div>
    </li>`;
}

function suggestionsDialogIsOpen(incidentId) {
  const container = document.getElementById('modal-container');
  return !!document.getElementById('suggestions-body') && container && !container.classList.contains('hidden')
    && STATE.suggestions.incidentId === incidentId;
}

function openSuggestionsModal(incidentId) {
  STATE.suggestions.incidentId = incidentId;
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Suggested fixes for <span class="font-mono">${escapeHtml(incidentId)}</span></h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal aria-label="Close">&times;</button>
    </div>
    <p class="text-xs text-muted suggestions-intro">Fixes used for similar past problems, best match first. Vote to help the next technician.</p>
    <div id="suggestions-body" class="suggestions-body" aria-live="polite">
      <p class="text-sm text-muted">Loading suggested fixes...</p>
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-secondary" data-close-modal>Close</button>
    </div>`);
  loadSuggestions(incidentId);
}

async function loadSuggestions(incidentId) {
  const seq = ++STATE.suggestions.requestSeq;
  try {
    const list = await apiCall(`/api/technician/incidents/${encodeURIComponent(incidentId)}/suggested-resolutions`);
    if (seq !== STATE.suggestions.requestSeq || !suggestionsDialogIsOpen(incidentId)) return; // late answer
    renderSuggestions(incidentId, Array.isArray(list) ? list : []);
  } catch (err) {
    if (seq !== STATE.suggestions.requestSeq || !suggestionsDialogIsOpen(incidentId)) return;
    document.getElementById('suggestions-body').innerHTML =
      `<p class="text-sm text-red suggestions-error">${escapeHtml(suggestionFailureMessage(err))}</p>`;
  }
}

function renderSuggestions(incidentId, list) {
  const body = document.getElementById('suggestions-body');
  if (list.length === 0) {
    body.innerHTML = '<p class="text-sm text-muted suggestions-empty">No similar past fix was found for this ticket.</p>';
    return;
  }
  body.innerHTML = `<ol class="suggestion-list">${list.map(suggestionCardHtml).join('')}</ol>`;
  body.querySelectorAll('.btn-tech-vote-worked').forEach(b => {
    b.addEventListener('click', () => castSuggestionVote(incidentId, b.getAttribute('data-source-type'), b.getAttribute('data-id'), true));
  });
  body.querySelectorAll('.btn-tech-vote-failed').forEach(b => {
    b.addEventListener('click', () => castSuggestionVote(incidentId, b.getAttribute('data-source-type'), b.getAttribute('data-id'), false));
  });
}

async function castSuggestionVote(incidentId, sourceType, sourceId, worked) {
  try {
    await apiCall(`/api/technician/incidents/${encodeURIComponent(incidentId)}/suggested-resolutions/votes`, {
      method: 'POST',
      body: JSON.stringify({ sourceType, sourceId, worked })
    });
    showToast(worked ? 'Thank you: recorded as a fix that worked.' : "Thank you: recorded as a fix that didn't work.", 'success');
  } catch (err) {
    showToast(suggestionFailureMessage(err), 'error');
  }
  // Counts and order change after a vote, and a refused vote means the list is stale: draw it again either way.
  if (suggestionsDialogIsOpen(incidentId)) loadSuggestions(incidentId);
}

function openPromoteModal(incidentId, ticketTitle) {
  openModal(`
    <div class="modal-header">
      <h3 class="modal-title">Promote to knowledge base</h3>
      <button type="button" class="btn btn-xs btn-secondary" data-close-modal aria-label="Close">&times;</button>
    </div>
    <form id="form-modal-promote" class="space-y-4">
      <p class="text-xs text-muted">The fix of <span class="font-mono">${escapeHtml(incidentId)}</span> (root cause, steps and
        preventive action) becomes a knowledge-base article that is suggested for similar tickets, also once this ticket is
        old. The reporter's name, e-mail and department are removed.</p>
      <div class="form-group">
        <label class="form-label" for="modal-promote-title">Article title (optional)</label>
        <input type="text" id="modal-promote-title" class="form-input" maxlength="255" placeholder="${escapeHtml(ticketTitle || '')}">
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Cancel</button>
        <button type="submit" class="btn btn-primary btn-tech-promote-submit">Promote</button>
      </div>
    </form>`);

  document.getElementById('form-modal-promote').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (STATE.suggestions.promoteInFlight) return; // Enter pressed twice: one promotion only
    STATE.suggestions.promoteInFlight = true;
    const submit = document.querySelector('#form-modal-promote .btn-tech-promote-submit');
    if (submit) { submit.disabled = true; submit.setAttribute('aria-busy', 'true'); }
    const title = document.getElementById('modal-promote-title').value.trim();
    try {
      const article = await apiCall('/api/technician/knowledge-articles', {
        method: 'POST',
        body: JSON.stringify({ sourceIncidentId: incidentId, title: title || null })
      });
      showToast(`Added to the knowledge base as ${article && article.id ? article.id : 'a new article'}`, 'success');
      closeModal();
      loadTechnicianQueue();
    } catch (err) {
      showToast(suggestionFailureMessage(err), 'error');
      if (err && err.status && err.status !== 400) closeModal(); // the ticket changed: the refreshed list shows why
    } finally {
      STATE.suggestions.promoteInFlight = false;
      if (submit && submit.isConnected) { submit.disabled = false; submit.removeAttribute('aria-busy'); }
    }
  });
}

// ============================================================================
// 17. Assignment strategy comparison (ADR-044, Phase L3)
// ============================================================================
// POST /api/simulation/strategy-comparison replays one seeded synthetic stream through every strategy in memory. The
// answer is read-only simulated data (never production data): the panel says so, marks the best value of every column
// and the differences that are larger than chance, and lists the model's assumptions.
const CMP_MAX_TICKETS = 5000;
const CMP_MAX_REPLICATIONS = 50;
const CMP_MAX_PRODUCT = 25000;
const CMP_PERCENT = 100;
let strategyComparisonInFlight = false;

/** The three form values as numbers, or a friendly message for the first one that cannot be sent. */
function cmpReadInputs() {
  const seed = Number(document.getElementById('cmp-seed').value);
  const tickets = Number(document.getElementById('cmp-tickets').value);
  const replications = Number(document.getElementById('cmp-replications').value);
  if (!Number.isSafeInteger(seed)) {
    return { error: 'The seed must be a whole number.' };
  }
  if (!Number.isInteger(tickets) || tickets < 1 || tickets > CMP_MAX_TICKETS) {
    return { error: `Tickets per run must be a whole number from 1 to ${CMP_MAX_TICKETS}.` };
  }
  if (!Number.isInteger(replications) || replications < 1 || replications > CMP_MAX_REPLICATIONS) {
    return { error: `Replications must be a whole number from 1 to ${CMP_MAX_REPLICATIONS}.` };
  }
  if (tickets * replications > CMP_MAX_PRODUCT) {
    return { error: `Tickets x replications must not exceed ${CMP_MAX_PRODUCT} (now ${tickets * replications}).` };
  }
  return { body: { seed, ticketCount: tickets, replications } };
}

/** A value in the unit of its metric: minutes with one decimal, shares as percent, gini with three decimals. */
function cmpFormat(unit, value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return TICKET_DETAIL_EMPTY;
  if (unit === 'share') return `${(number * CMP_PERCENT).toFixed(1)} %`;
  if (unit === 'gini') return number.toFixed(3);
  if (unit === 'min') return `${number.toFixed(1)} min`;
  return number.toFixed(1);
}

/** The half width of an interval or a difference in the unit of its metric (shares in percentage points). */
function cmpFormatSpread(unit, value, signed) {
  const number = Number(value);
  if (!Number.isFinite(number)) return TICKET_DETAIL_EMPTY;
  const sign = signed && number > 0 ? '+' : '';
  if (unit === 'share') return `${sign}${(number * CMP_PERCENT).toFixed(1)} pts`;
  if (unit === 'gini') return `${sign}${number.toFixed(3)}`;
  return `${sign}${number.toFixed(1)}`;
}

function cmpDifferenceHtml(metric, view, reference) {
  if (view.differenceVsReference === null || view.differenceVsReference === undefined) {
    return '<div class="cmp-diff">reference</div>';
  }
  const marker = view.distinguishable
    ? ' <span class="cmp-sig" title="Larger than chance: the 95 % interval of the difference excludes 0">&#9679;</span>'
    : '';
  return `<div class="cmp-diff" title="Difference to ${tdEsc(reference)}">&Delta; ${tdEsc(cmpFormatSpread(metric.unit, view.differenceVsReference, true))}${marker}</div>`;
}

function cmpCell(metric, strategy, data) {
  const view = strategy.metrics[metric.key];
  const best = (data.winners[metric.key] || []).includes(strategy.name);
  return `<td class="td-num${best ? ' cmp-best' : ''}">`
    + `<div class="cmp-value">${tdEsc(cmpFormat(metric.unit, view.mean))}</div>`
    + `<div class="cmp-spread">&plusmn; ${tdEsc(cmpFormatSpread(metric.unit, view.ci95HalfWidth, false))}</div>`
    + `${cmpDifferenceHtml(metric, view, data.referenceStrategy)}</td>`;
}

function cmpRow(strategy, metrics, data) {
  const reference = strategy.reference ? ' <span class="badge badge-p4">reference</span>' : '';
  return `<tr><th scope="row" class="cmp-name">${tdEsc(strategy.name)}${reference}</th>`
    + metrics.map(metric => cmpCell(metric, strategy, data)).join('') + '</tr>';
}

function cmpResultsTable(data) {
  const metrics = data.metrics || [];
  const head = metrics.map(m => `<th class="td-num">${tdEsc(m.label)}<div class="td-why-detail">${tdEsc(m.unit)}</div></th>`).join('');
  return `<table class="table cmp-table"><thead><tr><th>Strategy</th>${head}</tr></thead><tbody>`
    + (data.strategies || []).map(strategy => cmpRow(strategy, metrics, data)).join('')
    + '</tbody></table>';
}

function cmpSummaryLine(data) {
  const p = data.parameters;
  return `Seed ${tdEsc(p.seed)}, ${tdEsc(p.ticketCount)} tickets x ${tdEsc(p.replications)} replications at `
    + `${tdEsc(p.arrivalsPerHour)} tickets/hour, ${tdEsc(data.technicianCount)} simulated technicians `
    + `(${data.poolSource === 'ROSTER' ? 'copied from the current roster' : 'invented pool: there is no roster'}); `
    + `ran in ${tdEsc(data.runMillis)} ms. Mean over the replications &plusmn; 95 % interval; differences are against `
    + `${tdEsc(data.referenceStrategy)}; <span class="cmp-sig">&#9679;</span> = larger than chance; the best value of each column is highlighted.`;
}

function renderStrategyComparison(data) {
  const notice = document.getElementById('cmp-notice');
  notice.textContent = data.notice || '';
  notice.classList.toggle('hidden', !data.notice);
  const results = document.getElementById('cmp-results');
  results.innerHTML = cmpResultsTable(data);
  results.classList.remove('hidden');
  const assumptions = document.getElementById('cmp-assumptions');
  assumptions.innerHTML = `<p class="cmp-hint">${cmpSummaryLine(data)}</p>`
    + '<h4 class="td-card-title">Model assumptions</h4>'
    + `<ul class="cmp-assumption-list">${(data.assumptions || []).map(a => `<li>${tdEsc(a)}</li>`).join('')}</ul>`;
  assumptions.classList.remove('hidden');
}

function cmpSetStatus(message, isError) {
  const status = document.getElementById('cmp-status');
  status.textContent = message || '';
  status.classList.toggle('hidden', !message);
  status.classList.toggle('cmp-status-error', !!isError);
}

/** A message a person can act on: the server explains a refused value itself. */
function cmpFriendlyError(err) {
  if (err.status === 400) return `The comparison was not run. ${err.message}`;
  if (err.status === 403) return 'Only administrators can run the strategy comparison.';
  return `Could not run the comparison: ${err.message}`;
}

function resetStrategyComparison() {
  ['cmp-results', 'cmp-assumptions', 'cmp-notice', 'cmp-status'].forEach(id => {
    const element = document.getElementById(id);
    if (!element) return;
    element.innerHTML = '';
    element.classList.add('hidden');
  });
  strategyComparisonInFlight = false;
}

async function runStrategyComparison() {
  if (strategyComparisonInFlight) return;
  const inputs = cmpReadInputs();
  if (inputs.error) {
    cmpSetStatus(inputs.error, true);
    return;
  }
  strategyComparisonInFlight = true;
  const button = document.getElementById('btn-run-comparison');
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  cmpSetStatus('Running the comparison: every strategy replays the same synthetic stream. This takes a few seconds...', false);
  try {
    const data = await apiCall('/api/simulation/strategy-comparison', { method: 'POST', body: JSON.stringify(inputs.body) });
    renderStrategyComparison(data);
    cmpSetStatus('', false);
  } catch (err) {
    cmpSetStatus(cmpFriendlyError(err), true);
    showToast(cmpFriendlyError(err), 'error');
  } finally {
    strategyComparisonInFlight = false;
    button.removeAttribute('aria-busy');
    button.disabled = false;
  }
}

function setupStrategyComparisonListeners() {
  const form = document.getElementById('form-strategy-comparison');
  if (!form) return;
  form.addEventListener('submit', event => {
    event.preventDefault();
    runStrategyComparison();
  });
}

// ============================================================================
// 18. Security: two-step verification and "sign out everywhere" (authentication hardening, ADR-048)
// ============================================================================
// An account with two-step verification answers the password step with a short-lived challenge (mfaToken) and no tokens;
// the code goes to /api/auth/mfa/verify. Administrators manage their own two-step verification in the Security dialog,
// which is drawn into the shared dialog (openModal), so the Tab trap and the return of keyboard focus apply.
let mfaChallengeToken = null;
let mfaSubmitInFlight = false;
let securityDialogSeq = 0;

function showMfaStep(challengeToken) {
  mfaChallengeToken = challengeToken;
  const loginForm = document.getElementById('form-login');
  const mfaForm = document.getElementById('form-mfa');
  if (!mfaForm) return;
  if (loginForm) loginForm.classList.add('hidden');
  const alertBox = document.getElementById('mfa-error-alert');
  if (alertBox) {
    alertBox.classList.add('hidden');
    alertBox.textContent = '';
  }
  mfaForm.classList.remove('hidden');
  const input = document.getElementById('mfa-code');
  if (input) {
    input.value = '';
    input.focus();
  }
}

function hideMfaStep() {
  mfaChallengeToken = null;
  const mfaForm = document.getElementById('form-mfa');
  if (mfaForm) mfaForm.classList.add('hidden');
  const input = document.getElementById('mfa-code');
  if (input) input.value = '';
  const loginForm = document.getElementById('form-login');
  if (loginForm) loginForm.classList.remove('hidden');
}

function mfaStepError(message) {
  const alertBox = document.getElementById('mfa-error-alert');
  if (!alertBox) return;
  alertBox.textContent = message;
  alertBox.classList.remove('hidden');
}

async function submitMfaCode(code) {
  if (mfaSubmitInFlight || !mfaChallengeToken) return;
  mfaSubmitInFlight = true;
  const button = document.getElementById('btn-mfa-submit');
  if (button) button.disabled = true;
  try {
    const res = await fetch('/api/auth/mfa/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mfaToken: mfaChallengeToken, code: (code || '').trim() })
    });
    if (res.status === 429) {
      mfaStepError(rateLimitMessage(res));
      return;
    }
    if (!res.ok) {
      mfaStepError('That code was not accepted, or the sign-in took too long. Check the code and try again, or go back and sign in again.');
      const input = document.getElementById('mfa-code');
      if (input) {
        input.focus();
        input.select();
      }
      return;
    }
    const data = await res.json();
    hideMfaStep();
    setAuthenticatedUser({
      token: data.token,
      refreshToken: data.refreshToken,
      id: data.userId || data.id,
      name: data.name,
      email: data.email,
      role: data.role
    }, true);
  } catch (e) {
    mfaStepError('The verification could not be sent. Check the connection and try again.');
  } finally {
    mfaSubmitInFlight = false;
    if (button) button.disabled = false;
  }
}

// One call of the Security dialog. Domain answers carry "code" (a wrong code or password); the security layer's 401
// carries "errorCode" and means the access token expired: renew the session once and repeat the call.
async function secCall(path, body) {
  const send = () => authorizedFetch(path, { method: 'POST', body: JSON.stringify(body || {}) });
  let res = await send();
  let payload = null;
  const read = async () => {
    try { payload = await res.json(); } catch (e) { payload = null; }
  };
  await read();
  if (res.status === 401 && payload && payload.errorCode === 'ERR_UNAUTHORIZED' && await refreshSession()) {
    res = await send();
    await read();
  }
  return { status: res.status, body: payload };
}

function secMessage(result) {
  const body = result.body || {};
  if (result.status === 429) return 'Too many attempts from this network. Wait a moment and try again.';
  if (body.code === 'ERR_INVALID_MFA_CODE') return 'That code was not accepted. Check the code (and the time on your phone) and try again.';
  if (body.code === 'ERR_INVALID_CREDENTIALS') return 'The password is not correct.';
  if (result.status === 401) return 'Your session has ended. Sign in again.';
  return body.message || `The request failed (status ${result.status}).`;
}

function secShowError(message) {
  const box = document.getElementById('sec-error');
  if (!box) return;
  box.textContent = message;
  box.classList.remove('hidden');
}

// Draws the dialog for one state: 'off', 'setup' (extra = {secret, uri}), 'codes' (extra = recovery codes) or 'on'.
function secRender(state, extra) {
  let body = '';
  if (state === 'off') {
    body = `
      <p class="sec-status text-muted">Two-step verification is <strong>off</strong>.</p>
      <p class="sec-hint">Protect your administrator account with a code from an authenticator app (any app that supports time-based codes).</p>
      <div class="sec-actions"><button type="button" class="btn btn-primary" id="btn-sec-setup">Set up two-step verification</button></div>`;
  } else if (state === 'setup') {
    body = `
      <p class="sec-status"><strong>Step 1.</strong> Add this account to your authenticator app.</p>
      <p class="sec-hint">Enter the key by hand (type: time based), or open the link text in an app that reads it. It is shown only now.</p>
      <label class="form-label">Secret key</label>
      <code class="sec-secret" id="sec-secret">${escapeHtml(extra.secret)}</code>
      <label class="form-label" style="margin-top: 0.6rem;">Setup link</label>
      <code class="sec-uri" id="sec-uri">${escapeHtml(extra.uri)}</code>
      <form id="form-sec-enable" class="space-y-4" style="margin-top: 0.9rem;" autocomplete="off">
        <div class="form-group">
          <label class="form-label" for="sec-enable-code"><strong>Step 2.</strong> Enter the 6-digit code the app shows</label>
          <input type="text" id="sec-enable-code" class="form-input" inputmode="numeric" autocomplete="one-time-code" maxlength="32" required>
        </div>
        <div class="sec-actions">
          <button type="submit" class="btn btn-primary" id="btn-sec-enable">Turn on</button>
          <button type="button" class="btn btn-secondary" id="btn-sec-cancel-setup">Cancel</button>
        </div>
      </form>`;
  } else if (state === 'codes') {
    const items = extra.map(code => `<li>${escapeHtml(code)}</li>`).join('');
    body = `
      <p class="sec-status"><strong>Two-step verification is on.</strong></p>
      <p class="sec-hint">Save these recovery codes somewhere safe. Each works once if you lose your authenticator. They are shown only now.</p>
      <ul class="sec-codes" id="sec-recovery-codes">${items}</ul>
      <div class="sec-actions"><button type="button" class="btn btn-primary" id="btn-sec-codes-saved">I have saved these codes</button></div>`;
  } else {
    body = `
      <p class="sec-status"><strong>Two-step verification is on.</strong></p>
      <form id="form-sec-disable" class="space-y-4" style="margin-top: 0.6rem;" autocomplete="off">
        <p class="sec-hint">To turn it off, enter your password and a current code (or a recovery code).</p>
        <div class="form-group">
          <label class="form-label" for="sec-disable-password">Password</label>
          <input type="password" id="sec-disable-password" class="form-input" autocomplete="current-password" required>
        </div>
        <div class="form-group">
          <label class="form-label" for="sec-disable-code">Code</label>
          <input type="text" id="sec-disable-code" class="form-input" autocomplete="one-time-code" maxlength="32" required>
        </div>
        <div class="sec-actions"><button type="submit" class="btn btn-secondary" id="btn-sec-disable">Turn off</button></div>
      </form>`;
  }
  openModal(`
    <div id="security-modal" role="group" aria-labelledby="security-title">
      <div class="modal-header">
        <h3 class="modal-title" id="security-title">Security</h3>
        <button type="button" class="btn btn-xs btn-secondary" data-close-modal aria-label="Close">&times;</button>
      </div>
      <div id="sec-error" class="alert alert-error hidden" role="alert"></div>
      <div class="sec-section">${body}</div>
      <div class="sec-section">
        <p class="sec-status">Sign out everywhere</p>
        <p class="sec-hint">Ends every session of your account, on every device, including this one.</p>
        <div class="sec-actions"><button type="button" class="btn btn-secondary" id="btn-sec-logout-all">Sign out everywhere</button></div>
      </div>
    </div>`);
  secWireDialog(state);
}

function secWireDialog(state) {
  const on = (id, event, handler) => {
    const element = document.getElementById(id);
    if (element) element.addEventListener(event, handler);
  };
  on('btn-sec-setup', 'click', secBeginSetup);
  on('form-sec-enable', 'submit', secConfirmSetup);
  on('btn-sec-cancel-setup', 'click', () => secRender('off'));
  on('btn-sec-codes-saved', 'click', () => secRender('on'));
  on('form-sec-disable', 'submit', secDisable);
  on('btn-sec-logout-all', 'click', secLogoutAll);
  const first = document.querySelector('#security-modal input:not([disabled]), #security-modal button.btn-primary');
  if (first) first.focus({ preventScroll: true });
}

async function openSecurityModal() {
  if (!STATE.currentUser || STATE.currentUser.role !== 'ROLE_ADMIN') return;
  const seq = ++securityDialogSeq;
  openModal('<div id="security-modal"><div class="modal-header"><h3 class="modal-title">Security</h3>'
    + '<button type="button" class="btn btn-xs btn-secondary" data-close-modal aria-label="Close">&times;</button></div>'
    + '<p class="text-muted">Loading...</p></div>');
  try {
    const res = await authorizedFetch('/api/auth/me');
    const me = res.ok ? await res.json() : null;
    if (seq !== securityDialogSeq) return; // closed or replaced while loading
    if (!me) {
      closeModal();
      showToast('Could not read your security status.', 'error');
      return;
    }
    secRender(me.mfaEnabled ? 'on' : 'off');
  } catch (e) {
    if (seq === securityDialogSeq) {
      closeModal();
      showToast('Could not read your security status.', 'error');
    }
  }
}

async function secBeginSetup(event) {
  const button = event.currentTarget;
  button.disabled = true;
  const result = await secCall('/api/auth/mfa/setup');
  if (result.status === 200) {
    secRender('setup', { secret: result.body.secret, uri: result.body.otpauthUri });
    return;
  }
  button.disabled = false;
  secShowError(result.status === 409 ? 'Two-step verification is already on for this account.' : secMessage(result));
}

async function secConfirmSetup(event) {
  event.preventDefault();
  const button = document.getElementById('btn-sec-enable');
  const input = document.getElementById('sec-enable-code');
  if (button.disabled) return;
  button.disabled = true;
  const result = await secCall('/api/auth/mfa/enable', { code: input.value.trim() });
  if (result.status === 200) {
    secRender('codes', result.body.recoveryCodes || []);
    return;
  }
  button.disabled = false;
  secShowError(secMessage(result));
  input.focus();
  input.select();
}

async function secDisable(event) {
  event.preventDefault();
  const button = document.getElementById('btn-sec-disable');
  const password = document.getElementById('sec-disable-password');
  const code = document.getElementById('sec-disable-code');
  if (button.disabled) return;
  button.disabled = true;
  const result = await secCall('/api/auth/mfa/disable', { password: password.value, code: code.value.trim() });
  password.value = '';
  if (result.status === 204) {
    showToast('Two-step verification is off.', 'info');
    secRender('off');
    return;
  }
  button.disabled = false;
  secShowError(secMessage(result));
  code.value = '';
}

async function secLogoutAll(event) {
  const button = event.currentTarget;
  if (button.disabled) return;
  button.disabled = true;
  const result = await secCall('/api/auth/logout-all');
  if (result.status === 204 || result.status === 401) {
    closeModal();
    handleSignOut(false);
    showToast('Signed out everywhere.', 'info');
    return;
  }
  button.disabled = false;
  secShowError(secMessage(result));
}

function setupSecurityListeners() {
  const mfaForm = document.getElementById('form-mfa');
  if (mfaForm) {
    mfaForm.addEventListener('submit', event => {
      event.preventDefault();
      submitMfaCode(document.getElementById('mfa-code').value);
    });
  }
  const cancel = document.getElementById('btn-mfa-cancel');
  if (cancel) {
    cancel.addEventListener('click', () => {
      hideMfaStep();
      const password = document.getElementById('login-password');
      if (password) password.focus();
    });
  }
  const security = document.getElementById('btn-security');
  if (security) security.addEventListener('click', openSecurityModal);
}

// ============================================================================
// 19. Light / dark mode (the whole feature lives in /assets/theme.js and /assets/theme.css)
// ============================================================================
(function loadThemeToggle() {
  const script = document.createElement('script');
  script.src = '/assets/theme.js';
  script.defer = true;
  document.head.appendChild(script);
})();

// ============================================================================
// 20. Team Lead: the current tickets of one team member, read only (ADR-054)
// ============================================================================
// Clicking a technician on the Team Lead tab opens a window with every ticket that technician holds and that the
// employee has not yet confirmed as fixed (VERIFIED and CLOSED are left out). The window only reads: it has a close
// button and no action button, and it sends one GET. Pattern: none (a view over GET /api/technician/team-queue).
const TLM_CONFIRMED_STATUSES = ['VERIFIED', 'CLOSED'];
const TLM_PROGRESS_STEPS = ['Reported', 'Assigned', 'In Progress', 'Resolved', 'Closed'];
const TLM_STEP_OF_STATUS = {
  NEW: 1, CLASSIFIED: 1, ASSIGNED: 2, ACKNOWLEDGED: 2, IN_PROGRESS: 3, PENDING_INFO: 3, RESOLVED: 4
};
const TLM_PERCENT = 100;
let tlmRequestSeq = 0;

function tlmDialogOpen() {
  const container = document.getElementById('modal-container');
  return !!(container && !container.classList.contains('hidden') && container.querySelector('.tlm-dialog'));
}

function closeTeamMemberTickets() {
  tlmRequestSeq++; // a late answer for this window is discarded
  if (tlmDialogOpen()) closeModal();
}

function tlmCategory(category) {
  const text = String(category || '').replace(/_/g, ' ').toLowerCase();
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : 'Not set';
}

function tlmProgressHtml(t) {
  const step = TLM_STEP_OF_STATUS[t.status] || 1;
  let label = `${TLM_PROGRESS_STEPS[step - 1]} (step ${step} of ${TLM_PROGRESS_STEPS.length})`;
  if (t.status === 'RESOLVED') label += ' - waiting for the employee to confirm';
  if (t.status === 'PENDING_INFO') label += ' - waiting for the employee to reply';
  const percent = Math.round((step / TLM_PROGRESS_STEPS.length) * TLM_PERCENT);
  return `
    <div class="tlm-progress">
      <div class="tlm-progress-track"><div class="tlm-progress-fill" style="width: ${percent}%;"></div></div>
      <span class="tlm-progress-label">${escapeHtml(label)}</span>
    </div>`;
}

function tlmNotesHtml(notes) {
  if (!Array.isArray(notes) || notes.length === 0) return '<p class="text-xs text-muted">No notes yet.</p>';
  return notes.map(n => `
    <p class="tlm-note"><strong>${escapeHtml(n.authorName || 'Unknown')}</strong>
      <span class="text-dim">${escapeHtml(formatDate(n.createdAt))}</span><br>${escapeHtml(n.noteText)}</p>`).join('');
}

function tlmResolutionHtml(resolution) {
  if (!resolution) return '';
  return `
    <h5 class="tlm-sub">Resolution</h5>
    <p class="tlm-note"><strong>Root cause:</strong> ${escapeHtml(resolution.rootCause)}<br>
      <strong>Steps taken:</strong> ${escapeHtml(resolution.stepsTaken)}<br>
      <strong>Preventive action:</strong> ${escapeHtml(resolution.preventiveAction)}</p>`;
}

function tlmDetailsHtml(t) {
  return `
    <details class="tlm-details">
      <summary>Description, notes and resolution</summary>
      <h5 class="tlm-sub">Description</h5>
      <p class="tlm-note">${escapeHtml(t.description)}</p>
      <h5 class="tlm-sub">Notes</h5>
      ${tlmNotesHtml(t.notes)}
      ${tlmResolutionHtml(t.resolution)}
    </details>`;
}

function tlmTicketHtml(t, nowMs) {
  const line = tpSlaLine(t, nowMs);
  return `
    <li class="tlm-ticket" data-ticket-id="${escapeHtml(t.id)}">
      <div class="tlm-ticket-head">
        <span class="font-mono text-cyan text-xs font-bold">${escapeHtml(t.id)}</span>
        ${ticketNameHtml(t)}
        ${tpPriorityChip(t.priority)}
        ${tpStatusChip(t, line)}
      </div>
      <div class="tlm-ticket-title">${escapeHtml(t.title)}</div>
      <div class="tlm-ticket-meta text-xs text-muted">Type: <strong class="text-main">${escapeHtml(tlmCategory(t.category))}</strong>
        &middot; Raised: ${escapeHtml(formatDate(t.createdAt))}
        &middot; Requester: ${escapeHtml(t.reporterName || t.reporterId)}
        &middot; SLA: ${escapeHtml(line.text)}</div>
      ${tlmProgressHtml(t)}
      ${tlmDetailsHtml(t)}
    </li>`;
}

// The tickets the technician holds and the employee has not yet confirmed; the server's urgency order is kept.
function tlmCurrentTickets(queue, techId) {
  return queue.filter(t => t.assigneeId === techId && !TLM_CONFIRMED_STATUSES.includes(t.status));
}

function tlmBodyHtml(queue, techId) {
  const tickets = tlmCurrentTickets(queue, techId);
  if (tickets.length === 0) {
    return '<p class="text-center text-muted" style="padding: 2rem;">This team member has no current tickets.</p>';
  }
  const nowMs = Date.now();
  return `
    <p class="text-xs text-muted tlm-count">${tickets.length} current ticket${tickets.length === 1 ? '' : 's'}
      (not yet confirmed fixed by the employee)</p>
    <ul class="tlm-list">${tickets.map(t => tlmTicketHtml(t, nowMs)).join('')}</ul>`;
}

function tlmShellHtml(name, bodyHtml) {
  return `
    <div class="tlm-dialog" role="dialog" aria-modal="true" aria-labelledby="tlm-title">
      <div class="modal-header">
        <h3 class="modal-title" id="tlm-title">Current tickets: ${escapeHtml(name)}</h3>
        <button type="button" class="btn btn-xs btn-secondary tlm-close" data-close-modal aria-label="Close">&times;</button>
      </div>
      <div id="tlm-body">${bodyHtml}</div>
      <div class="modal-footer">
        <button type="button" class="btn btn-secondary" data-close-modal>Close</button>
      </div>
    </div>`;
}

function tlmSetBody(html) {
  const body = document.getElementById('tlm-body');
  if (body) body.innerHTML = html;
}

async function openTeamMemberTicketsModal(techId, name) {
  const seq = ++tlmRequestSeq;
  openModal(tlmShellHtml(name, '<p class="text-center text-muted" style="padding: 2rem;">Loading tickets...</p>'));
  let queue;
  try {
    queue = await apiCall('/api/technician/team-queue');
  } catch (error) {
    if (seq === tlmRequestSeq && tlmDialogOpen()) {
      tlmSetBody(`<p class="text-center text-red" style="padding: 2rem;">Could not load the tickets: ${escapeHtml(error.message)}</p>`);
    }
    return;
  }
  // The window was closed, replaced or the user signed out while the answer was on its way
  if (seq !== tlmRequestSeq || !tlmDialogOpen()) return;
  tlmSetBody(tlmBodyHtml(Array.isArray(queue) ? queue : [], techId));
}

function tlmCardActivated(event) {
  const card = event.target.closest('.tlm-card');
  if (!card) return false;
  openTeamMemberTicketsModal(card.getAttribute('data-id'), card.getAttribute('data-name'));
  return true;
}

function setupTeamMemberListeners() {
  const grid = document.getElementById('teamlead-roster-grid');
  if (grid) {
    grid.addEventListener('click', tlmCardActivated);
    grid.addEventListener('keydown', event => {
      if ((event.key === 'Enter' || event.key === ' ') && tlmCardActivated(event)) event.preventDefault();
    });
  }
  // The shared dialog has no backdrop or Escape handling of its own: this window gets both
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && tlmDialogOpen()) closeTeamMemberTickets();
  });
  const container = document.getElementById('modal-container');
  if (container) {
    container.addEventListener('click', event => {
      if (event.target === container && tlmDialogOpen()) closeTeamMemberTickets();
    });
  }
}
