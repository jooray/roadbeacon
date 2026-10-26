// Roadbeacon event kind library (NIP-XX: Road Events)
// Kind 1315 = road event report
// Kind 1316 = confirmation/denial
//
// The `alt` strings below deliberately say "Roadstr". They are part of the signed event and of
// the 110-byte frame format v1, which another independent client already implements; changing
// them changes every event ID and makes the event impossible to put on a radio. See
// docs/ecosystem.md.

import { encode as geohashEncode } from './geohash.js';

export const RELAY_TTL = 1209600; // 14 days in seconds

export const EVENT_TYPES = [
  { value: 0, string: 'police', defaultTTL: 7200, color: '#0000FF', icon: '\u{1F46E}' },
  { value: 1, string: 'speed_camera', defaultTTL: 2592000, color: '#800080', icon: '\u{1F4F7}' },
  { value: 2, string: 'traffic_jam', defaultTTL: 3600, color: '#FF8C00', icon: '\u{1F697}' },
  { value: 3, string: 'accident', defaultTTL: 10800, color: '#FF0000', icon: '\u{1F4A5}' },
  { value: 4, string: 'road_closure', defaultTTL: 604800, color: '#8B0000', icon: '\u{1F6AB}' },
  { value: 5, string: 'construction', defaultTTL: 604800, color: '#FFD700', icon: '\u{1F6A7}' },
  { value: 6, string: 'hazard', defaultTTL: 14400, color: '#FF4500', icon: '\u26A0\uFE0F' },
  { value: 7, string: 'road_condition', defaultTTL: 21600, color: '#4682B4', icon: '\u{1F6E3}\uFE0F' },
  { value: 8, string: 'pothole', defaultTTL: 604800, color: '#795548', icon: '\u{1F573}\uFE0F' },
  { value: 9, string: 'fog', defaultTTL: 10800, color: '#9E9E9E', icon: '\u{1F32B}\uFE0F' },
  { value: 10, string: 'ice', defaultTTL: 21600, color: '#00CED1', icon: '\u{1F9CA}' },
  { value: 11, string: 'animal', defaultTTL: 3600, color: '#4CAF50', icon: '\u{1F98C}' },
  { value: 255, string: 'other', defaultTTL: 7200, color: '#808080', icon: '\u2139\uFE0F' }
];

export function getTypeByString(str) {
  return EVENT_TYPES.find(t => t.string === str) || null;
}

export function getTypeByValue(val) {
  return EVENT_TYPES.find(t => t.value === val) || null;
}

/**
 * Create an unsigned kind 1315 road event report.
 */
export function createReportEvent(typeValue, lat, lon, content = '', createdAt = null) {
  const now = createdAt || Math.floor(Date.now() / 1000);
  const typeInfo = getTypeByValue(typeValue);
  if (!typeInfo) throw new Error(`Unknown event type value: ${typeValue}`);

  const geohashes = [4, 5, 6].map(p => geohashEncode(lat, lon, p));
  const expiration = now + RELAY_TTL;

  const tags = [
    ['t', typeInfo.string],
    ['g', geohashes[0]],
    ['g', geohashes[1]],
    ['g', geohashes[2]],
    ['lat', lat.toFixed(7)],
    ['lon', lon.toFixed(7)],
    ['expiration', String(expiration)],
    ['alt', `Roadstr: ${typeInfo.string} report`]
  ];

  return {
    kind: 1315,
    created_at: now,
    tags,
    content: content || ''
  };
}

/**
 * Create an unsigned kind 1316 confirmation/denial event.
 * @param {string} eventId - The event ID being confirmed/denied
 * @param {string} status - Either 'still_there' or 'no_longer_there'
 * @param {number} lat - Latitude of the referenced report (for geohash tags)
 * @param {number} lon - Longitude of the referenced report (for geohash tags)
 * @param {number} [createdAt] - Optional timestamp (defaults to now)
 */
export function createConfirmationEvent(eventId, status, lat, lon, createdAt = null) {
  const now = createdAt || Math.floor(Date.now() / 1000);

  if (status !== 'still_there' && status !== 'no_longer_there') {
    throw new Error(`Invalid status: ${status}. Must be "still_there" or "no_longer_there".`);
  }

  const geohashes = [4, 5, 6].map(p => geohashEncode(lat, lon, p));
  const altText = status === 'still_there' ? 'Roadstr: event confirmed' : 'Roadstr: event denied';
  const expiration = now + RELAY_TTL; // 14 days per spec

  const tags = [
    ['e', eventId],
    ['g', geohashes[0]],
    ['g', geohashes[1]],
    ['g', geohashes[2]],
    ['status', status],
    ['lat', lat.toFixed(7)],
    ['lon', lon.toFixed(7)],
    ['expiration', String(expiration)],
    ['alt', altText]
  ];

  return {
    kind: 1316,
    created_at: now,
    tags,
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
  const altTag = event.tags.find(t => t[0] === 'alt');

  if (!typeTag || gTags.length === 0) return null;

  const typeInfo = getTypeByString(typeTag[1]);
  if (!typeInfo) return null;

  // Extract coordinates from lat/lon tags
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
 * Per NIP spec:
 * - still_there: extends expiry from confirmation time + defaultTTL
 * - no_longer_there: immediately expires at confirmation time
 */
export function computeEffectiveTTL(report, confirmations = []) {
  const typeInfo = report.type || getTypeByString(report.typeString);
  if (!typeInfo) return report.createdAt + RELAY_TTL;

  const baseTTL = typeInfo.defaultTTL;
  let effectiveExpiry = report.createdAt + baseTTL;

  // Sort confirmations by timestamp for deterministic processing
  const sorted = [...confirmations].sort((a, b) => a.created_at - b.created_at);

  for (const c of sorted) {
    const statusTag = c.tags.find(t => t[0] === 'status');
    if (!statusTag) continue;

    if (statusTag[1] === 'still_there') {
      // Extend expiry from confirmation time
      const newExpiry = c.created_at + baseTTL;
      effectiveExpiry = Math.max(effectiveExpiry, newExpiry);
    } else if (statusTag[1] === 'no_longer_there') {
      // Immediately expire at denial time
      effectiveExpiry = Math.min(effectiveExpiry, c.created_at);
    }
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
