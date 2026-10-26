// Main entry point: wires map, auth, nostr, and UI together

import { initMap, setClickHandler, setConfirmHandler, addEventMarker, updateMarkerCounts, removeExpired, getCenter, getZoom, onMoveEnd } from './map.js';
import { connect, subscribe, subscribeRoadEvents, subscribeConfirmations, fetchEventById, publish, closeSub, close, getRelays, addRelay, removeRelay, resetRelays, getDefaultRelays, getRelayStatus, setRelayStatusCallback } from './nostr.js';
import { initAuth, signEvent, getAuthMode, getPubkey, logout, hasStoredKey } from './auth.js';
import { EVENT_TYPES, createReportEvent, createConfirmationEvent, parseReportEvent, computeEffectiveTTL, isExpired } from './roadbeacon.js';
import { encode as geohashEncode, neighbors } from './geohash.js';

// State
let currentSub = null;
const reports = new Map(); // eventId -> parsed report
const confirmations = new Map(); // eventId -> [confirmation events]
let resubscribeTimeout = null;
const RESUBSCRIBE_DEBOUNCE_MS = 3000;

// Query caching - track recently queried geohashes to avoid redundant REQs
const queriedGeohashes = new Map(); // geohash -> timestamp
const QUERY_CACHE_TTL_MS = 2 * 60 * 1000; // 2 minutes
const QUERY_PRECISION = 4; // Use coarser precision (larger area) to reduce queries

// Loading state
let isInitialLoad = true;

// DOM elements
const authDialog = document.getElementById('auth-dialog');
const reportDialog = document.getElementById('report-dialog');
const btnAuth = document.getElementById('btn-auth');
const btnAuthConfirm = document.getElementById('btn-auth-confirm');
const btnAuthLogout = document.getElementById('btn-auth-logout');
const btnAuthCancel = document.getElementById('btn-auth-cancel');
const authStatus = document.getElementById('auth-status');
const nsecInput = document.getElementById('nsec-input');
const nip46Display = document.getElementById('nip46-display');
const reportCoords = document.getElementById('report-coords');
const typeGrid = document.getElementById('type-grid');
const reportComment = document.getElementById('report-comment');
const btnReportSubmit = document.getElementById('btn-report-submit');
const btnReportCancel = document.getElementById('btn-report-cancel');
const relayStatusEl = document.getElementById('relay-status');
const fabReport = document.getElementById('fab-report');
const relayDialog = document.getElementById('relay-dialog');
const relayList = document.getElementById('relay-list');
const relayInput = document.getElementById('relay-input');
const btnRelayAdd = document.getElementById('btn-relay-add');
const btnRelayReset = document.getElementById('btn-relay-reset');
const btnRelayClose = document.getElementById('btn-relay-close');
const publishStatusBar = document.getElementById('publish-status-bar');
const publishStatusText = publishStatusBar?.querySelector('.publish-status-text');
const publishRelayDots = document.getElementById('publish-relay-dots');

let selectedType = null;
let reportLat = null;
let reportLon = null;

// --- Initialization ---

document.addEventListener('DOMContentLoaded', () => {
  // Init map
  initMap('map');

  // Setup UI handlers
  setupAuthUI();
  setupReportUI();
  setupRelayUI();

  // Map click -> open report (no auth required, uses ephemeral if not authed)
  setClickHandler((lat, lon) => {
    openReportDialog(lat, lon);
  });

  // FAB -> open report at map center
  fabReport.addEventListener('click', () => {
    const center = getCenter();
    openReportDialog(center.lat, center.lng);
  });

  // Marker confirm/deny (uses ephemeral signing if no auth)
  setConfirmHandler(async (eventId, status) => {
    const action = status === 'still_there' ? 'Confirming' : 'Denying';
    showPublishStatusBar(`${action} report...`);

    // Look up the original report to get its coordinates for geohash tags
    const report = reports.get(eventId);
    if (!report) {
      console.error(`[roadbeacon] Cannot confirm: report not found: ${eventId}`);
      hidePublishStatusBar();
      return;
    }

    try {
      const unsigned = createConfirmationEvent(eventId, status);
      const signed = await signEvent(unsigned);

      const relayStates = new Map();

      const { accepted, total } = await publish(signed, {
        timeoutMs: 5000,
        onRelayStart: (relay) => {
          relayStates.set(relay, 'pending');
          updatePublishRelayDots(relayStates);
        },
        onRelayResult: (result) => {
          relayStates.set(result.relay, result.status === 'fulfilled' ? 'success' : 'failed');
          updatePublishRelayDots(relayStates);
        }
      });

      // Track locally and update UI
      if (!confirmations.has(eventId)) confirmations.set(eventId, []);
      confirmations.get(eventId).push(signed);

      // Update marker counts
      const confs = confirmations.get(eventId);
      const counts = computeConfirmationCounts(confs);
      updateMarkerCounts(eventId, counts);

      // Show result
      const actionDone = status === 'still_there' ? 'Confirmed' : 'Denied';
      const success = accepted > 0;
      showPublishStatusBar(`${actionDone}: ${accepted}/${total} relays`, success ? 'success' : 'error');
      setTimeout(() => hidePublishStatusBar(), 2500);
    } catch (err) {
      console.error('Failed to publish confirmation:', err);
      showPublishStatusBar('Failed to publish confirmation', 'error');
      setTimeout(() => hidePublishStatusBar(), 3000);
    }
  });

  // Auto-login: use stored key if exists, otherwise default to ephemeral
  if (hasStoredKey()) {
    initAuth('generate').then((pubkey) => {
      setAuthStatus(`Key loaded: ${pubkey.slice(0, 12)}...`, 'success');
      btnAuthLogout.classList.remove('hidden');
    });
  } else {
    // Default to ephemeral (anonymous) identity
    initAuth('ephemeral').then(() => {
      console.log('[roadbeacon] Using ephemeral identity by default');
    });
  }

  // Connect to relays and subscribe immediately (no auth needed for reading)
  connectAndSubscribe();

  // Map move -> re-subscribe (debounced to avoid relay rate limits)
  onMoveEnd(() => {
    if (resubscribeTimeout) clearTimeout(resubscribeTimeout);
    resubscribeTimeout = setTimeout(() => {
      resubscribe();
    }, RESUBSCRIBE_DEBOUNCE_MS);
  });

  // Periodic cleanup
  setInterval(cleanupExpired, 30000);
});

// --- Auth UI ---

function setupAuthUI() {
  btnAuth.addEventListener('click', () => {
    updateAuthUI();
    authDialog.showModal();
  });

  btnAuthCancel.addEventListener('click', () => authDialog.close());

  btnAuthLogout.addEventListener('click', () => {
    logout();
    setAuthStatus('Logged out. Using ephemeral keys for interactions.', '');
    btnAuthLogout.classList.add('hidden');
  });

  btnAuthConfirm.addEventListener('click', async () => {
    const mode = document.querySelector('input[name="auth-mode"]:checked').value;
    try {
      let params = {};
      if (mode === 'import') {
        params.nsec = nsecInput.value.trim();
        if (!params.nsec) {
          setAuthStatus('Please enter an nsec', 'error');
          return;
        }
      }
      if (mode === 'nip46') {
        params.onUri = (uri) => {
          showNip46QR(uri);
        };
      }

      const pubkey = await initAuth(mode, params);

      if (mode === 'nip46') {
        setAuthStatus('Scan QR code with Amber or click the link', '');
      } else if (pubkey) {
        setAuthStatus(`Connected: ${pubkey.slice(0, 12)}...`, 'success');
        btnAuthLogout.classList.remove('hidden');
        authDialog.close();
      } else if (mode === 'ephemeral') {
        setAuthStatus('Ephemeral mode: each report uses a new identity', 'success');
        btnAuthLogout.classList.remove('hidden');
        authDialog.close();
      }
    } catch (err) {
      setAuthStatus(err.message, 'error');
    }
  });

  // Toggle param inputs based on mode selection
  document.querySelectorAll('input[name="auth-mode"]').forEach(radio => {
    radio.addEventListener('change', () => updateAuthUI());
  });
}

function updateAuthUI() {
  const mode = document.querySelector('input[name="auth-mode"]:checked').value;
  nsecInput.classList.toggle('hidden', mode !== 'import');
  nip46Display.classList.toggle('hidden', mode !== 'nip46');

  if (getAuthMode()) {
    btnAuthLogout.classList.remove('hidden');
  }
}

function setAuthStatus(msg, type) {
  authStatus.textContent = msg;
  authStatus.className = 'auth-status' + (type ? ` ${type}` : '');
}

function showNip46QR(uri) {
  const qrContainer = document.getElementById('nip46-qr');
  qrContainer.innerHTML = '';

  // Use qr-creator if available
  if (window.QrCreator) {
    const canvas = document.createElement('canvas');
    QrCreator.render({ text: uri, size: 200 }, canvas);
    qrContainer.appendChild(canvas);
  } else {
    // Fallback: show URI as text
    const code = document.createElement('code');
    code.textContent = uri;
    code.style.fontSize = '10px';
    code.style.wordBreak = 'break-all';
    qrContainer.appendChild(code);
  }

  // Deep link for Amber
  const link = document.getElementById('nip46-link');
  link.href = `intent:${uri}#Intent;scheme=nostrconnect;package=com.greenart7c3.nostrsigner;end`;
}

// --- Report UI ---

function setupReportUI() {
  console.log('Setting up report UI, EVENT_TYPES count:', EVENT_TYPES.length);
  
  // Clear existing buttons first
  typeGrid.innerHTML = '';
  
  // Build type grid
  for (const type of EVENT_TYPES) {
    const btn = document.createElement('button');
    btn.className = 'type-btn';
    btn.dataset.value = type.value;
    btn.innerHTML = `
      <span class="type-icon">${type.icon}</span>
      <span class="type-label">${type.string.replace(/_/g, ' ')}</span>
    `;
    btn.addEventListener('click', () => {
      console.log('Type selected:', type.value, type.string);
      typeGrid.querySelectorAll('.type-btn').forEach(b => b.classList.remove('selected'));
      btn.classList.add('selected');
      selectedType = type.value;
      btnReportSubmit.disabled = false;
    });
    typeGrid.appendChild(btn);
  }

  btnReportCancel.addEventListener('click', () => {
    reportDialog.close();
    resetReportDialog();
  });

  // Close dialog when clicking outside (on backdrop)
  reportDialog.addEventListener('click', (e) => {
    if (e.target === reportDialog) {
      reportDialog.close();
      resetReportDialog();
    }
  });

  btnReportSubmit.addEventListener('click', async (e) => {
    e.preventDefault();
    e.stopPropagation();

    console.log('Submit clicked, selectedType:', selectedType);

    if (selectedType === null) {
      showToast('Please select an event type first');
      return;
    }

    // Close dialog immediately and show publishing status bar
    reportDialog.close();
    showPublishStatusBar('Publishing event...');

    // Publish in background with real-time updates
    (async () => {
      try {
        const unsigned = createReportEvent(selectedType, reportLat, reportLon, reportComment.value.trim());
        const signed = await signEvent(unsigned);

        // Track relay results
        const relayStates = new Map();

        const { accepted, total } = await publish(signed, {
          timeoutMs: 5000,
          onRelayStart: (relay) => {
            relayStates.set(relay, 'pending');
            updatePublishRelayDots(relayStates);
          },
          onRelayResult: (result) => {
            relayStates.set(result.relay, result.status === 'fulfilled' ? 'success' : 'failed');
            updatePublishRelayDots(relayStates);
            const done = [...relayStates.values()].filter(s => s !== 'pending').length;
            updatePublishStatusText(`Publishing... ${done}/${relayStates.size}`);
          }
        });

        // Add to local state and map immediately
        const parsed = parseReportEvent(signed);
        if (parsed) {
          parsed.relayCount = accepted;
          reports.set(parsed.id, parsed);
          addEventMarker(parsed);
        }

        // Show final result
        const success = accepted > 0;
        showPublishStatusBar(`Published to ${accepted}/${total} relays`, success ? 'success' : 'error');

        // Also update header relay status
        const status = accepted > 0 ? 'connected' : 'error';
        updateRelayStatus(status, `${accepted}/${total} relays`);

        // Reset dialog for next use
        resetReportDialog();

        // Hide status bar and reset header after delay
        setTimeout(() => {
          hidePublishStatusBar();
          updateRelayStatus('connected', `${total} connected`);
        }, 3000);
      } catch (err) {
        console.error('Failed to publish report:', err);
        showPublishStatusBar('Publish failed: ' + err.message, 'error');
        updateRelayStatus('error', 'Publish failed');
        setTimeout(() => {
          hidePublishStatusBar();
          updateRelayStatus('connected', `${getRelays().length} connected`);
        }, 4000);
      }
    })();
  });
  
  console.log('Report UI setup complete, submit button listener attached');
}

function openReportDialog(lat, lon) {
  reportLat = lat;
  reportLon = lon;
  reportCoords.textContent = `Location: ${lat.toFixed(5)}, ${lon.toFixed(5)}`;
  reportDialog.showModal();
}

function resetReportDialog() {
  selectedType = null;
  reportComment.value = '';
  typeGrid.querySelectorAll('.type-btn').forEach(b => b.classList.remove('selected'));
  btnReportSubmit.disabled = true;
}

function updateRelayStatus(state, text) {
  relayStatusEl.className = 'relay-status';
  if (state === 'connected') relayStatusEl.classList.add('connected');
  if (state === 'error') relayStatusEl.classList.add('error');
  if (state === 'publishing') relayStatusEl.classList.add('publishing');
  
  const statusText = relayStatusEl.querySelector('.status-text');
  if (statusText) {
    statusText.textContent = text;
  } else {
    // Fallback if structure is different
    relayStatusEl.innerHTML = `<span class="status-dot"></span><span class="status-text">${text}</span>`;
  }
}

// --- Relay UI ---

function setupRelayUI() {
  relayStatusEl.style.cursor = 'pointer';
  relayStatusEl.addEventListener('click', () => {
    renderRelayList();
    relayDialog.showModal();
  });

  btnRelayAdd.addEventListener('click', () => {
    let url = relayInput.value.trim();
    if (!url) return;
    if (!url.startsWith('wss://')) url = 'wss://' + url;
    addRelay(url);
    relayInput.value = '';
    renderRelayList();
    reconnectRelays();
  });

  relayInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') btnRelayAdd.click();
  });

  btnRelayReset.addEventListener('click', () => {
    resetRelays();
    renderRelayList();
    reconnectRelays();
  });

  btnRelayClose.addEventListener('click', () => {
    relayDialog.close();
  });
}

function renderRelayList() {
  const currentRelays = getRelays();
  const statuses = getRelayStatus();
  relayList.innerHTML = '';
  for (const url of currentRelays) {
    const li = document.createElement('li');
    li.className = 'relay-item';
    const dot = document.createElement('span');
    const status = statuses.get(url) || 'connecting';
    dot.className = `relay-dot ${status}`;
    dot.title = status;
    li.appendChild(dot);
    const span = document.createElement('span');
    span.className = 'relay-url';
    span.textContent = url;
    const btn = document.createElement('button');
    btn.className = 'btn-relay-remove';
    btn.textContent = '\u00d7';
    btn.title = 'Remove relay';
    btn.addEventListener('click', () => {
      removeRelay(url);
      renderRelayList();
      reconnectRelays();
    });
    li.appendChild(span);
    li.appendChild(btn);
    relayList.appendChild(li);
  }
}

function reconnectRelays() {
  close();
  connected = false;
  queriedGeohashes.clear(); // Clear cache on reconnect to get fresh data
  reports.clear();
  confirmations.clear();
  isInitialLoad = true;
  connectAndSubscribe();
}

// --- Nostr connection & subscriptions ---

let connected = false;

function connectAndSubscribe() {
  if (connected) return;
  const connectedRelays = connect();
  relayStatusEl.classList.add('connected');
  const statusText = relayStatusEl.querySelector('.status-text');
  if (statusText) {
    statusText.textContent = `${connectedRelays.length} connected`;
  } else {
    relayStatusEl.textContent = `${connectedRelays.length} connected`;
  }
  connected = true;
  resubscribe();
}

function resubscribe() {
  const center = getCenter();

  // Use fixed coarser precision for larger coverage area
  const centerHash = geohashEncode(center.lat, center.lon, QUERY_PRECISION);
  const geohashes = neighbors(centerHash);

  // Filter out geohashes we've already queried recently
  const now = Date.now();
  const newGeohashes = geohashes.filter(gh => {
    const lastQueried = queriedGeohashes.get(gh);
    return !lastQueried || (now - lastQueried) > QUERY_CACHE_TTL_MS;
  });

  // Nothing new to query - all areas already covered
  if (newGeohashes.length === 0) {
    console.log(`[roadbeacon] All geohashes already queried recently, skipping`);
    return;
  }

  // Close previous subscription
  if (currentSub) {
    closeSub(currentSub);
    currentSub = null;
  }

  // Mark these geohashes as queried
  for (const gh of newGeohashes) {
    queriedGeohashes.set(gh, now);
  }

  // Clean up old entries from cache
  for (const [gh, timestamp] of queriedGeohashes) {
    if (now - timestamp > QUERY_CACHE_TTL_MS) {
      queriedGeohashes.delete(gh);
    }
  }

  console.log(`[roadbeacon] Subscribing with geohashes:`, newGeohashes, `precision:`, QUERY_PRECISION, `(${geohashes.length - newGeohashes.length} cached)`);

  // Subscribe since 14 days ago (relay expiration window)
  const since = Math.floor(Date.now() / 1000) - 1209600;

  // Use new batched subscription that fetches both reports and confirmations
  // and waits for EOSE before processing
  currentSub = subscribeRoadEvents(newGeohashes, since, {
    onReady: ({ reports: rawReports, confirmations: rawConfirmations }) => {
      processInitialLoad(rawReports, rawConfirmations);
    },
    onEvent: (event) => {
      handleRealtimeEvent(event);
    }
  });
}

/**
 * Process initial load of events after EOSE.
 * Groups confirmations with reports and only displays valid (non-expired) events.
 */
function processInitialLoad(rawReports, rawConfirmations) {
  console.log(`[roadbeacon] Processing initial load: ${rawReports.length} reports, ${rawConfirmations.length} confirmations`);

  const now = Math.floor(Date.now() / 1000);

  // Index confirmations by target event ID
  const confirmationsByEvent = new Map();
  for (const conf of rawConfirmations) {
    const eTag = conf.tags.find(t => t[0] === 'e');
    if (!eTag) continue;
    const targetId = eTag[1];
    if (!confirmationsByEvent.has(targetId)) {
      confirmationsByEvent.set(targetId, []);
    }
    const list = confirmationsByEvent.get(targetId);
    if (!list.some(c => c.id === conf.id)) {
      list.push(conf);
    }
  }

  // Process each report with its confirmations
  let displayed = 0;
  let expired = 0;

  for (const event of rawReports) {
    const parsed = parseReportEvent(event);
    if (!parsed) continue;

    // Check relay expiration
    if (parsed.expiration && now >= parsed.expiration) {
      continue;
    }

    // Get confirmations for this report
    const confs = confirmationsByEvent.get(parsed.id) || [];

    // Check effective TTL (considering confirmations)
    if (isExpired(parsed, confs, now)) {
      expired++;
      continue;
    }

    // Store in state
    reports.set(parsed.id, parsed);
    confirmations.set(parsed.id, confs);

    // Add to map
    const expiry = computeEffectiveTTL(parsed, confs);
    const counts = computeConfirmationCounts(confs);
    addEventMarker(parsed, expiry, counts);
    displayed++;
  }

  console.log(`[roadbeacon] Initial load complete: ${displayed} displayed, ${expired} expired (filtered)`);
  isInitialLoad = false;
}

/**
 * Handle real-time events after initial load.
 */
function handleRealtimeEvent(event) {
  const now = Math.floor(Date.now() / 1000);

  if (event.kind === 1315) {
    // New report
    console.log(`[roadbeacon] Real-time report:`, event.id.slice(0, 8));

    // Skip if we already have it
    if (reports.has(event.id)) return;

    const parsed = parseReportEvent(event);
    if (!parsed) return;

    // Check relay expiration
    if (parsed.expiration && now >= parsed.expiration) return;

    // For new reports, check base TTL (no confirmations yet)
    // New reports should always be valid since they were just created
    const confs = confirmations.get(parsed.id) || [];

    // Store and display
    reports.set(parsed.id, parsed);
    const expiry = computeEffectiveTTL(parsed, confs);
    const counts = computeConfirmationCounts(confs);
    addEventMarker(parsed, expiry, counts);

  } else if (event.kind === 1316) {
    // New confirmation
    const eTag = event.tags.find(t => t[0] === 'e');
    if (!eTag) return;

    const targetId = eTag[1];
    console.log(`[roadbeacon] Real-time confirmation for:`, targetId.slice(0, 8));

    // Store confirmation
    if (!confirmations.has(targetId)) confirmations.set(targetId, []);
    const confs = confirmations.get(targetId);
    if (!confs.some(c => c.id === event.id)) {
      confs.push(event);
    }

    // Update marker if we have the report
    const report = reports.get(targetId);
    if (report) {
      // Check if now expired
      if (isExpired(report, confs, now)) {
        removeExpired(new Set([targetId]));
        reports.delete(targetId);
        confirmations.delete(targetId);
      } else {
        // Update counts and expiry
        const counts = computeConfirmationCounts(confs);
        updateMarkerCounts(targetId, counts);
      }
    } else {
      // Backfill: fetch the report for this confirmation
      console.log(`[roadbeacon] Backfilling report for confirmation:`, targetId.slice(0, 8));
      fetchEventById(targetId, (reportEvent) => {
        if (reportEvent.kind === 1315) {
          handleRealtimeEvent(reportEvent);
        }
      });
    }
  }
}

function computeConfirmationCounts(confs) {
  let confirmed = 0;
  let denied = 0;
  for (const c of confs) {
    const statusTag = c.tags.find(t => t[0] === 'status');
    if (!statusTag) continue;
    if (statusTag[1] === 'still_there') {
      confirmed++;
    } else if (statusTag[1] === 'no_longer_there') {
      denied++;
    }
  }
  return { confirmed, denied };
}

function cleanupExpired() {
  const now = Math.floor(Date.now() / 1000);
  const expired = new Set();

  for (const [id, report] of reports) {
    const confs = confirmations.get(id) || [];
    if (isExpired(report, confs, now)) {
      expired.add(id);
    }
  }

  if (expired.size > 0) {
    removeExpired(expired);
    for (const id of expired) {
      reports.delete(id);
      confirmations.delete(id);
    }
  }
}

const activeToasts = new Map(); // message -> { element, timeoutId }

function showToast(message, duration = 3000) {
  if (activeToasts.has(message)) {
    clearTimeout(activeToasts.get(message).timeoutId);
    const entry = activeToasts.get(message);
    entry.timeoutId = setTimeout(() => {
      entry.element.remove();
      activeToasts.delete(message);
    }, duration);
    return;
  }

  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.textContent = message;
  document.body.appendChild(toast);

  const timeoutId = setTimeout(() => {
    toast.remove();
    activeToasts.delete(message);
  }, duration);

  activeToasts.set(message, { element: toast, timeoutId });
}

// --- Publish Status Bar ---

function showPublishStatusBar(text, state = '') {
  if (!publishStatusBar) return;

  publishStatusBar.className = 'publish-status-bar';
  if (state) publishStatusBar.classList.add(state);

  if (publishStatusText) publishStatusText.textContent = text;
  if (publishRelayDots) publishRelayDots.innerHTML = '';

  // Show with animation
  publishStatusBar.classList.remove('hidden');
  requestAnimationFrame(() => {
    publishStatusBar.classList.add('visible');
  });
}

function hidePublishStatusBar() {
  if (!publishStatusBar) return;
  publishStatusBar.classList.remove('visible');
  setTimeout(() => {
    publishStatusBar.classList.add('hidden');
  }, 300);
}

function updatePublishStatusText(text) {
  if (publishStatusText) publishStatusText.textContent = text;
}

function updatePublishRelayDots(relayStates) {
  if (!publishRelayDots) return;

  publishRelayDots.innerHTML = '';
  for (const [relay, state] of relayStates) {
    const dot = document.createElement('span');
    dot.className = `relay-dot ${state}`;
    // Extract domain from relay URL for tooltip
    try {
      const url = new URL(relay);
      dot.title = url.hostname;
    } catch {
      dot.title = relay;
    }
    publishRelayDots.appendChild(dot);
  }
}
