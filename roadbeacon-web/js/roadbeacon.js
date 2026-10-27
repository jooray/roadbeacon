// Roadbeacon event kind library (NIP-XX: Road Events)
// Kind 1315 = road event report
// Kind 1316 = confirmation/denial
//
// The layout is the shared one, written natively by the independent Roadstr client:
//   report: lat, lon, g4, g5, g6, t, expiration[, maxspeed]   (no alt, six decimals)
//   vote:   e, status
// The `expiration` tag is created_at + max(14 days, type lifetime), so votes can extend a report.
// See docs/ecosystem.md and nips/road-events.md.

import { encode as geohashEncode } from './geohash.js';

/** Floor for relay-side retention (NIP-40): 14 days. */
export const RELAY_TTL = 1209600;

/** The shared category registry: byte value in the frame, `t` tag string, display lifetime. */
export const EVENT_TYPES = [
  { value: 0, string: 'police', defaultTTL: 4 * 3600, color: '#0000FF', icon: '\u{1F46E}' },
  { value: 12, string: 'police_station', defaultTTL: 30 * 86400, color: '#1E3A8A', icon: '\u{1F3DB}\uFE0F' },
  { value: 1, string: 'speed_camera', defaultTTL: 30 * 86400, color: '#800080', icon: '\u{1F4F7}' },
  { value: 2, string: 'traffic_jam', defaultTTL: 3600, color: '#FF8C00', icon: '\u{1F697}' },
  { value: 3, string: 'accident', defaultTTL: 4 * 3600, color: '#FF0000', icon: '\u{1F4A5}' },
  { value: 4, string: 'road_closure', defaultTTL: 24 * 3600, color: '#8B0000', icon: '\u{1F6AB}' },
  { value: 5, string: 'construction', defaultTTL: 15 * 86400, color: '#FFD700', icon: '\u{1F6A7}' },
  { value: 6, string: 'hazard', defaultTTL: 4 * 3600, color: '#FF4500', icon: '\u26A0\uFE0F' },
  { value: 7, string: 'road_condition', defaultTTL: 15 * 86400, color: '#4682B4', icon: '\u{1F6E3}\uFE0F' },
  { value: 8, string: 'pothole', defaultTTL: 7 * 86400, color: '#795548', icon: '\u{1F573}\uFE0F' },
  { value: 9, string: 'fog', defaultTTL: 4 * 3600, color: '#9E9E9E', icon: '\u{1F32B}\uFE0F' },
  { value: 10, string: 'ice', defaultTTL: 4 * 3600, color: '#00CED1', icon: '\u{1F9CA}' },
  { value: 11, string: 'animal', defaultTTL: 3600, color: '#4CAF50', icon: '\u{1F98C}' },
  { value: 13, string: 'surveillance_camera', defaultTTL: 30 * 86400, color: '#4C1D95', icon: '\u{1F3A5}' },
  { value: 255, string: 'other', defaultTTL: 4 * 3600, color: '#808080', icon: '\u2139\uFE0F' }
];

export function getTypeByString(str) {
  return EVENT_TYPES.find(t => t.string === str) || null;
}

export function getTypeByValue(val) {
  return EVENT_TYPES.find(t => t.value === val) || null;
}

/** Relay retention for a report: never shorter than its own lifetime. */
export function expirationFor(typeInfo, createdAt) {
  return createdAt + Math.max(RELAY_TTL, typeInfo.defaultTTL);
}

/**
 * Create an unsigned kind 1315 road event report.
 */
export function createReportEvent(typeValue, lat, lon, content = '', createdAt = null) {
  const now = createdAt || Math.floor(Date.now() / 1000);
  const typeInfo = getTypeByValue(typeValue);
  if (!typeInfo) throw new Error(`Unknown event type value: ${typeValue}`);

  // Six decimals: the value a frame carries and prints back. Geohashes use the same text.
  const latText = lat.toFixed(6);
  const lonText = lon.toFixed(6);
  const latRounded = parseFloat(latText);
  const lonRounded = parseFloat(lonText);
  const geohashes = [4, 5, 6].map(p => geohashEncode(latRounded, lonRounded, p));

  const tags = [
    ['lat', latText],
    ['lon', lonText],
    ['g', geohashes[0]],
    ['g', geohashes[1]],
    ['g', geohashes[2]],
    ['t', typeInfo.string],
    ['expiration', String(expirationFor(typeInfo, now))]
  ];

  return {
    kind: 1315,
    created_at: now,
    tags,
    content: content || ''
  };
}

/**
 * Create an unsigned kind 1316 confirmation/denial event. A vote carries no position: two tags.
 * @param {string} eventId - The event ID being confirmed/denied
 * @param {string} status - Either 'still_there' or 'no_longer_there'
 * @param {number} [createdAt] - Optional timestamp (defaults to now)
 */
export function createConfirmationEvent(eventId, status, createdAt = null) {
  const now = createdAt || Math.floor(Date.now() / 1000);

  if (status !== 'still_there' && status !== 'no_longer_there') {
    throw new Error(`Invalid status: ${status}. Must be "still_there" or "no_longer_there".`);
  }

  return {
    kind: 1316,
    created_at: now,
    tags: [
      ['e', eventId],
      ['status', status]
    ],
    content: ''
  };
}

/**
 * Parse a kind 1315 event into structured data.
 */
export function parseReportEvent(event) {
  if (event.kind !== 1315) return null;

  const typeTag = event.tags.find(t => t[0] === 't');
  const gTags = event.tags.filter(t => t[0] === 'g');
  const expTag = event.tags.find(t => t[0] === 'expiration');

  // Only the current shared layout: `lat, lon, g, g, g, t, expiration[, maxspeed]`, no alt.
  const names = event.tags.map(t => t[0]);
  if (event.tags.some(t => t[0] === 'alt')) return null;
  if (names.slice(0, 6).join(',') !== 'lat,lon,g,g,g,t') return null;
  if (names.length !== 7 && names.length !== 8) return null;

  if (!typeTag || gTags.length === 0) return null;

  const typeInfo = getTypeByString(typeTag[1]);
  if (!typeInfo) return null;

  const latTag = event.tags.find(t => t[0] === 'lat');
  const lonTag = event.tags.find(t => t[0] === 'lon');
  const lat = latTag ? parseFloat(latTag[1]) : null;
  const lon = lonTag ? parseFloat(lonTag[1]) : null;

  return {
    id: event.id,
    pubkey: event.pubkey,
    type: typeInfo,
    lat,
    lon,
    content: event.content,
    createdAt: event.created_at,
    expiration: expTag ? parseInt(expTag[1]) : null,
    geohashes: gTags.map(t => t[1])
  };
}

/**
 * Compute effective TTL expiry timestamp given confirmations/denials.
 * - still_there: extends expiry from confirmation time + defaultTTL
 * - no_longer_there: expires at confirmation time
 * Never later than the event's own `expiration`: a marker must not outlive the data.
 */
export function computeEffectiveTTL(report, confirmations = []) {
  const typeInfo = report.type || getTypeByString(report.typeString);
  if (!typeInfo) return report.createdAt + RELAY_TTL;

  const baseTTL = typeInfo.defaultTTL;
  let effectiveExpiry = report.createdAt + baseTTL;

  // A vote cannot predate its report. Sort the rest by timestamp for deterministic processing.
  const sorted = [...confirmations]
    .filter(c => c.created_at >= report.createdAt)
    .sort((a, b) => a.created_at - b.created_at);

  for (const c of sorted) {
    const statusTag = c.tags.find(t => t[0] === 'status');
    if (!statusTag) continue;

    if (statusTag[1] === 'still_there') {
      effectiveExpiry = Math.max(effectiveExpiry, c.created_at + baseTTL);
    } else if (statusTag[1] === 'no_longer_there') {
      effectiveExpiry = Math.min(effectiveExpiry, c.created_at);
    }
  }

  if (report.expiration) {
    effectiveExpiry = Math.min(effectiveExpiry, report.expiration);
  }

  return effectiveExpiry;
}

/**
 * Check if a report is expired based on effective TTL.
 */
export function isExpired(report, confirmations = [], now = null) {
  const currentTime = now || Math.floor(Date.now() / 1000);
  const expiry = computeEffectiveTTL(report, confirmations);
  return currentTime >= expiry;
}
