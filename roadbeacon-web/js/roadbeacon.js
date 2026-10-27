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

/** Votes more than this far in the future are ignored (nips/road-events.md). */
export const MAX_FUTURE_SKEW = 300;

const LATITUDE_SHAPE = /^-?[0-9]{1,2}\.[0-9]{6}$/;
const LONGITUDE_SHAPE = /^-?[0-9]{1,3}\.[0-9]{6}$/;

/** Degrees x 10^6, rounded (never truncated); null when not on Earth. */
function toE6(degrees, limit) {
  if (!Number.isFinite(degrees)) return null;
  const e6 = Math.round(degrees * 1e6);
  return Math.abs(e6) <= limit ? e6 : null;
}

/** `-?D+.DDDDDD` from an integer: the only spelling the format accepts (never `-0.000000`). */
export function formatE6(e6) {
  const magnitude = Math.abs(e6);
  const sign = e6 < 0 ? '-' : '';
  return `${sign}${Math.floor(magnitude / 1e6)}.${String(magnitude % 1e6).padStart(6, '0')}`;
}

/** The integer behind a coordinate tag, or null unless the text is exactly canonical. */
function parseE6(text, shape, limit) {
  if (typeof text !== 'string' || !shape.test(text)) return null;
  const negative = text.startsWith('-');
  const [whole, fraction] = (negative ? text.slice(1) : text).split('.');
  const magnitude = Number(whole) * 1e6 + Number(fraction);
  if (magnitude > limit) return null;
  const e6 = negative ? -magnitude : magnitude;
  return formatE6(e6) === text ? e6 : null;
}

/** Tags of a report in the shared layout, rebuilt from the integer position. */
function reportTags(latE6, lonE6, typeInfo, expiration, maxspeed = null) {
  const lat = latE6 / 1e6;
  const lon = lonE6 / 1e6;
  const tags = [
    ['lat', formatE6(latE6)],
    ['lon', formatE6(lonE6)],
    ['g', geohashEncode(lat, lon, 4)],
    ['g', geohashEncode(lat, lon, 5)],
    ['g', geohashEncode(lat, lon, 6)],
    ['t', typeInfo.string],
    ['expiration', String(expiration)]
  ];
  if (maxspeed !== null) tags.push(['maxspeed', String(maxspeed)]);
  return tags;
}

/**
 * True when a kind 1315 event is in the current shared layout, exactly as the Android client and
 * Roadstr check it: canonical coordinate text, matching geohashes, canonical numbers, known type.
 */
export function isCanonicalReport(event) {
  if (event.kind !== 1315 || !Array.isArray(event.tags)) return false;
  const tag = name => event.tags.find(t => t.length === 2 && t[0] === name);
  const latE6 = parseE6(tag('lat')?.[1], LATITUDE_SHAPE, 90e6);
  const lonE6 = parseE6(tag('lon')?.[1], LONGITUDE_SHAPE, 180e6);
  const typeInfo = getTypeByString(tag('t')?.[1]);
  const expText = tag('expiration')?.[1];
  if (latE6 === null || lonE6 === null || !typeInfo || !/^(0|[1-9][0-9]*)$/.test(expText || '')) return false;
  const expiration = Number(expText);
  if (expiration > 0xFFFFFFFF) return false;
  const speedText = tag('maxspeed')?.[1];
  let maxspeed = null;
  if (speedText !== undefined) {
    if (!/^[1-9][0-9]*$/.test(speedText)) return false;
    maxspeed = Number(speedText);
    if (maxspeed < 5 || maxspeed > 300) return false;
  }
  return JSON.stringify(event.tags) === JSON.stringify(reportTags(latE6, lonE6, typeInfo, expiration, maxspeed));
}

/** True when a kind 1316 event is exactly `e, status` with an empty content. */
export function isCanonicalVote(event) {
  return event.kind === 1316 &&
    event.content === '' &&
    Array.isArray(event.tags) &&
    event.tags.length === 2 &&
    event.tags[0].length === 2 && event.tags[0][0] === 'e' && /^[0-9a-f]{64}$/.test(event.tags[0][1]) &&
    event.tags[1].length === 2 && event.tags[1][0] === 'status' &&
    (event.tags[1][1] === 'still_there' || event.tags[1][1] === 'no_longer_there');
}

/**
 * Create an unsigned kind 1315 road event report.
 */
export function createReportEvent(typeValue, lat, lon, content = '', createdAt = null) {
  const now = createdAt || Math.floor(Date.now() / 1000);
  const typeInfo = getTypeByValue(typeValue);
  if (!typeInfo) throw new Error(`Unknown event type value: ${typeValue}`);

  // Six decimals from an integer: the value a frame carries and prints back. Geohashes use it too.
  const latE6 = toE6(lat, 90e6);
  const lonE6 = toE6(lon, 180e6);
  if (latE6 === null || lonE6 === null) throw new Error(`Position out of range: ${lat}, ${lon}`);

  return {
    kind: 1315,
    created_at: now,
    tags: reportTags(latE6, lonE6, typeInfo, expirationFor(typeInfo, now)),
    // Control characters would keep the report off the radio (it could not be framed).
    content: (content || '').replace(/[\u0000-\u001f\u007f]+/g, ' ').trim()
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
  if (!isCanonicalReport(event)) return null;

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
 * The votes that count (nips/road-events.md, "Display lifetime"): only canonical votes, one voice
 * per key (its latest; on a tie "still there"), nothing older than the report or more than five
 * minutes in the future.
 */
export function admissibleVotes(report, confirmations = [], now = null) {
  const currentTime = now || Math.floor(Date.now() / 1000);
  const latest = new Map();
  for (const c of confirmations) {
    if (!isCanonicalVote(c)) continue;
    if (c.created_at < report.createdAt || c.created_at > currentTime + MAX_FUTURE_SKEW) continue;
    const known = latest.get(c.pubkey);
    const stillThere = c.tags[1][1] === 'still_there';
    if (!known || c.created_at > known.created_at ||
        (c.created_at === known.created_at && stillThere)) {
      latest.set(c.pubkey, c);
    }
  }
  return [...latest.values()];
}

/** More denials than confirmations in the last five minutes hides a report (a tie stays). */
export function deniedByRecentMajority(votes, now = null) {
  const currentTime = now || Math.floor(Date.now() / 1000);
  const recent = votes.filter(c => currentTime - c.created_at < 300);
  const confirmed = recent.filter(c => c.tags[1][1] === 'still_there').length;
  return recent.length - confirmed > confirmed;
}

/**
 * Compute effective TTL expiry timestamp given confirmations/denials.
 * - still_there: extends expiry from confirmation time + defaultTTL
 * - no_longer_there: expires at confirmation time
 * Never later than the event's own `expiration`: a marker must not outlive the data.
 */
export function computeEffectiveTTL(report, confirmations = [], now = null) {
  const typeInfo = report.type || getTypeByString(report.typeString);
  if (!typeInfo) return report.createdAt + RELAY_TTL;

  const baseTTL = typeInfo.defaultTTL;
  let effectiveExpiry = report.createdAt + baseTTL;

  // Same timestamp: the denial first, so a tie keeps the report alive.
  const rank = c => (c.tags[1][1] === 'still_there' ? 1 : 0);
  const sorted = admissibleVotes(report, confirmations, now)
    .sort((a, b) => a.created_at - b.created_at || rank(a) - rank(b));

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
  if (currentTime >= computeEffectiveTTL(report, confirmations, currentTime)) return true;
  return deniedByRecentMajority(admissibleVotes(report, confirmations, currentTime), currentTime);
}
