// Nostr relay communication using nostr-tools SimplePool

import { SimplePool } from 'https://esm.sh/nostr-tools@2.7.2/pool';
import { verifyEvent } from 'https://esm.sh/nostr-tools@2/pure';

/**
 * Every event is verified before it is displayed: a relay is an untrusted boundary, and a frame
 * or event without a valid signature is not an event. Verification is cheap next to the damage a
 * forged coordinate can do (it puts a marker on somebody's road).
 */
function verified(onEvent) {
  return (event) => {
    if (verifyEvent(event)) {
      onEvent(event);
    } else {
      console.warn('[nostr] Dropped event with an invalid signature:', event && event.id);
    }
  };
}

const DEFAULT_RELAYS = [
  'wss://relay.damus.io',
  'wss://nos.lol',
  'wss://relay.primal.net',
  'wss://offchain.pub'
];

// Storage identifier predates the rename; changing it would drop a visitor's relay list.
const RELAY_STORAGE_KEY = 'roadstr_relays';

let pool = null;
let relays = [];
let activeSubscriptions = [];
const relayStatus = new Map(); // url -> 'connecting' | 'connected' | 'error'
let onRelayStatusChange = null;

/**
 * Initialize the relay pool.
 * @param {string[]} [relayUrls] - Relay URLs to connect to
 */
export function connect(relayUrls) {
  pool = new SimplePool();
  // Load from localStorage if available, otherwise use provided or defaults
  const stored = loadRelays();
  relays = relayUrls || stored || DEFAULT_RELAYS;
  for (const relay of relays) {
    relayStatus.set(relay, 'connecting');
  }
  if (onRelayStatusChange) onRelayStatusChange(new Map(relayStatus));
  return relays;
}

/**
 * Set a callback for relay status changes.
 * @param {function} cb - (Map<url, status>) => void
 */
export function setRelayStatusCallback(cb) {
  onRelayStatusChange = cb;
}

/**
 * Get current relay connection status.
 * @returns {Map<string, string>} url -> 'connecting' | 'connected' | 'error'
 */
export function getRelayStatus() {
  return new Map(relayStatus);
}

/**
 * Get the current relay list.
 */
export function getRelays() {
  return relays;
}

/**
 * Subscribe to kind 1315 events filtered by geohash prefixes.
 * @param {string[]} geohashes - Geohash prefixes to filter on
 * @param {number} since - Unix timestamp for since filter
 * @param {function} onEvent - Callback for each event
 * @returns {object} Subscription handle (call .close() to unsubscribe)
 */
export function subscribe(geohashes, since, onEvent) {
  if (!pool) throw new Error('Pool not connected. Call connect() first.');

  const filter = {
    kinds: [1315],
    '#g': geohashes,
    since
  };

  console.log(`[nostr] Subscribing to ${relays.length} relays with filter:`, JSON.stringify(filter));
  const sub = pool.subscribeMany(relays, [filter], {
    onevent: verified(onEvent),
    oneose: () => {
      console.log(`[nostr] EOSE received — relay finished sending stored events`);
    }
  });

  activeSubscriptions.push(sub);
  return sub;
}

/**
 * Subscribe to road events (1315) and confirmations (1316) with efficient two-phase loading.
 * Phase 1: Fetch reports by geohash, wait for EOSE
 * Phase 2: Fetch confirmations for those specific report IDs
 * After both phases, calls onReady. Real-time events go to onEvent.
 *
 * @param {string[]} geohashes - Geohash prefixes to filter on
 * @param {number} since - Unix timestamp for since filter
 * @param {object} callbacks - Callback functions
 * @param {function} callbacks.onReady - Called after initial load with {reports: [], confirmations: []}
 * @param {function} callbacks.onEvent - Called for each real-time event after initial load
 * @returns {object} Subscription handle (for the reports subscription)
 */
export function subscribeRoadEvents(geohashes, since, { onReady, onEvent }) {
  if (!pool) throw new Error('Pool not connected. Call connect() first.');

  const bufferedReports = [];
  const bufferedConfirmations = [];
  let phase1Complete = false;
  let phase2Complete = false;
  let confirmSub = null;

  // Phase 1: Reports by geohash
  const reportFilter = { kinds: [1315], '#g': geohashes, since };

  console.log(`[nostr] Phase 1: Subscribing to reports on ${relays.length} relays, geohashes:`, geohashes);

  const reportSub = pool.subscribeMany(relays, [reportFilter], {
    onevent: (event) => {
      if (!phase1Complete) {
        // Buffer until EOSE — verified first, like everything that leaves this module.
        if (verifyEvent(event) && !bufferedReports.some(e => e.id === event.id)) {
          bufferedReports.push(event);
        }
      } else {
        // Real-time report after initial load
        if (verifyEvent(event)) onEvent(event);
      }
    },
    oneose: () => {
      if (phase1Complete) return;
      phase1Complete = true;

      // Mark all relays as connected after successful EOSE
      for (const relay of relays) {
        if (relayStatus.get(relay) !== 'error') {
          relayStatus.set(relay, 'connected');
        }
      }
      if (onRelayStatusChange) onRelayStatusChange(new Map(relayStatus));

      console.log(`[nostr] Phase 1 complete: ${bufferedReports.length} reports`);

      if (bufferedReports.length === 0) {
        // No reports, skip phase 2
        phase2Complete = true;
        onReady({ reports: [], confirmations: [] });
        return;
      }

      // Phase 2: Fetch confirmations for these report IDs
      const reportIds = bufferedReports.map(r => r.id);
      console.log(`[nostr] Phase 2: Fetching confirmations for ${reportIds.length} reports`);

      const confirmFilter = { kinds: [1316], '#e': reportIds };

      confirmSub = pool.subscribeMany(relays, [confirmFilter], {
        onevent: (event) => {
          if (!phase2Complete) {
            if (verifyEvent(event) && !bufferedConfirmations.some(e => e.id === event.id)) {
              bufferedConfirmations.push(event);
            }
          } else {
            // Real-time confirmation after initial load
            if (verifyEvent(event)) onEvent(event);
          }
        },
        oneose: () => {
          if (phase2Complete) return;
          phase2Complete = true;

          console.log(`[nostr] Phase 2 complete: ${bufferedConfirmations.length} confirmations`);
          onReady({
            reports: bufferedReports,
            confirmations: bufferedConfirmations
          });
        }
      });

      activeSubscriptions.push(confirmSub);
    }
  });

  activeSubscriptions.push(reportSub);

  // Return a wrapper that closes both subscriptions
  return {
    close: () => {
      try { reportSub.close(); } catch (e) { /* ignore */ }
      if (confirmSub) {
        try { confirmSub.close(); } catch (e) { /* ignore */ }
      }
      activeSubscriptions = activeSubscriptions.filter(s => s !== reportSub && s !== confirmSub);
    }
  };
}

/**
 * Subscribe to kind 1316 confirmations for given event IDs.
 * @param {string[]} eventIds - Event IDs to get confirmations for
 * @param {function} onEvent - Callback for each confirmation event
 * @returns {object} Subscription handle
 */
export function subscribeConfirmations(eventIds, onEvent) {
  if (!pool) throw new Error('Pool not connected. Call connect() first.');
  if (eventIds.length === 0) return null;

  const filter = {
    kinds: [1316],
    '#e': eventIds
  };

  const sub = pool.subscribeMany(relays, [filter], {
    onevent: verified(onEvent),
    oneose: () => {}
  });

  activeSubscriptions.push(sub);
  return sub;
}

/**
 * Fetch a specific event by ID from relays.
 * @param {string} eventId - The event ID to fetch
 * @param {function} onEvent - Callback when event is found
 * @returns {object} Subscription handle (auto-closes after finding event)
 */
export function fetchEventById(eventId, onEvent) {
  if (!pool) throw new Error('Pool not connected. Call connect() first.');

  const filter = {
    ids: [eventId]
  };

  let found = false;
  const sub = pool.subscribeMany(relays, [filter], {
    onevent: (event) => {
      if (!found) {
        found = true;
        if (verifyEvent(event)) onEvent(event);
        // Close subscription after finding the event
        try { sub.close(); } catch (e) { /* ignore */ }
        activeSubscriptions = activeSubscriptions.filter(s => s !== sub);
      }
    },
    oneose: () => {
      // Close after all relays have responded if event wasn't found
      setTimeout(() => {
        if (!found) {
          try { sub.close(); } catch (e) { /* ignore */ }
          activeSubscriptions = activeSubscriptions.filter(s => s !== sub);
        }
      }, 1000);
    }
  });

  activeSubscriptions.push(sub);
  return sub;
}

/**
 * Publish a signed event to all relays with timeout and progress callbacks.
 * @param {object} signedEvent - Signed nostr event
 * @param {object} [options] - Options
 * @param {number} [options.timeoutMs=5000] - Timeout per relay in ms
 * @param {function} [options.onRelayStart] - Called when publishing to a relay starts
 * @param {function} [options.onRelayResult] - Called when a relay responds (success or failure)
 * @returns {Promise<{accepted: number, total: number, results: object[]}>}
 */
export async function publish(signedEvent, options = {}) {
  const { timeoutMs = 5000, onRelayStart, onRelayResult } = options;

  if (!pool) throw new Error('Pool not connected. Call connect() first.');

  // Notify about all relays starting
  if (onRelayStart) {
    for (const relay of relays) {
      onRelayStart(relay);
    }
  }

  // Publish with timeout per relay - fire and don't wait for all
  const publishPromises = relays.map(async (relay) => {
    const timeoutPromise = new Promise((_, reject) => {
      setTimeout(() => reject(new Error('Timeout')), timeoutMs);
    });
    try {
      await Promise.race([pool.publish([relay], signedEvent), timeoutPromise]);
      relayStatus.set(relay, 'connected');
      const result = { status: 'fulfilled', relay };
      if (onRelayResult) onRelayResult(result);
      return result;
    } catch (err) {
      relayStatus.set(relay, 'error');
      const result = { status: 'rejected', relay, reason: err.message };
      if (onRelayResult) onRelayResult(result);
      return result;
    }
  });

  const results = await Promise.all(publishPromises);
  const accepted = results.filter(r => r.status === 'fulfilled').length;
  const failed = results.filter(r => r.status === 'rejected');
  console.log(`[nostr] Published to ${accepted}/${relays.length} relays`);
  for (const f of failed) {
    console.warn(`[nostr] Relay rejected:`, f.reason);
  }
  if (onRelayStatusChange) onRelayStatusChange(new Map(relayStatus));
  return { accepted, total: relays.length, results };
}

/**
 * Add a relay to the list and persist.
 * @param {string} url - Relay WebSocket URL
 */
export function addRelay(url) {
  if (!url.startsWith('wss://')) return;
  try { new URL(url); } catch { return; }
  if (relays.includes(url)) return;
  relays.push(url);
  saveRelays();
}

/**
 * Remove a relay from the list and persist.
 * @param {string} url - Relay URL to remove
 */
export function removeRelay(url) {
  relays = relays.filter(r => r !== url);
  saveRelays();
}

/**
 * Reset relays to defaults and clear stored config.
 */
export function resetRelays() {
  relays = [...DEFAULT_RELAYS];
  localStorage.removeItem(RELAY_STORAGE_KEY);
}

/**
 * Get the default relay list.
 */
export function getDefaultRelays() {
  return DEFAULT_RELAYS;
}

function saveRelays() {
  localStorage.setItem(RELAY_STORAGE_KEY, JSON.stringify(relays));
}

function loadRelays() {
  try {
    const stored = localStorage.getItem(RELAY_STORAGE_KEY);
    if (stored) return JSON.parse(stored);
  } catch (e) { /* ignore */ }
  return null;
}

/**
 * Close all subscriptions and pool.
 */
export function close() {
  for (const sub of activeSubscriptions) {
    try { sub.close(); } catch (e) { /* ignore */ }
  }
  activeSubscriptions = [];
  if (pool) {
    pool.close(relays);
    pool = null;
  }
  relayStatus.clear();
}

/**
 * Close a specific subscription and remove from tracking.
 */
export function closeSub(sub) {
  if (sub) {
    try { sub.close(); } catch (e) { /* ignore */ }
    activeSubscriptions = activeSubscriptions.filter(s => s !== sub);
  }
}
