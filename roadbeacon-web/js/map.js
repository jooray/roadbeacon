// Leaflet map setup and marker management

import { MAP_DISPLAY_CONFIG, getConfiguredMapMode, getConfiguredVectorStyleUrl } from './config.js';

const OPENFREEMAP_ATTRIBUTION = '&copy; <a href="https://openfreemap.org">OpenFreeMap</a> &copy; <a href="https://www.openmaptiles.org/">OpenMapTiles</a> Data from <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';
const VECTOR_BASEMAP_LOAD_TIMEOUT_MS = 10000;

let map = null;
let baseLayer = null;
let markers = new Map(); // eventId -> L.Marker
let onMapClick = null;
let onMarkerConfirm = null;
let popupOpen = false;

/**
 * Initialize the Leaflet map.
 * @param {string} containerId - DOM element ID for the map
 * @param {object} [options] - Initial view options
 * @returns {L.Map} The map instance
 */
export function initMap(containerId, options = {}) {
  const { lat = 48.15, lon = 17.11, zoom = 13 } = options; // Default: Bratislava

  map = L.map(containerId, {
    zoomControl: false
  }).setView([lat, lon], zoom);

  addConfiguredBaseLayer();

  // Add zoom control to bottom right
  L.control.zoom({
    position: 'bottomright'
  }).addTo(map);

  // Geocoder control (search)
  if (L.Control.Geocoder) {
    const geocoder = L.Control.geocoder({
      defaultMarkGeocode: false,
      position: 'topleft',
      placeholder: 'Search location...'
    }).on('markgeocode', (e) => {
      map.setView(e.geocode.center, 15);
    }).addTo(map);

    // Style the geocoder input
    const geocoderInput = document.querySelector('.leaflet-control-geocoder-form input');
    if (geocoderInput) {
      geocoderInput.style.background = 'rgba(15, 23, 42, 0.9)';
      geocoderInput.style.color = '#f1f5f9';
      geocoderInput.style.border = '1px solid rgba(51, 65, 85, 0.8)';
      geocoderInput.style.borderRadius = '12px';
      geocoderInput.style.padding = '10px 14px';
    }
  }

  // Locate control (GPS)
  if (L.control.locate) {
    L.control.locate({
      position: 'bottomright',
      flyTo: true,
      keepCurrentZoomLevel: true,
      strings: { title: 'Show my location' },
      locateOptions: {
        enableHighAccuracy: true,
        maxZoom: 16
      }
    }).addTo(map);
  }

  // Track popup state to suppress click-through
  map.on('popupopen', () => { popupOpen = true; });
  map.on('popupclose', () => { setTimeout(() => { popupOpen = false; }, 50); });

  // Map click handler for reporting
  map.on('click', (e) => {
    if (popupOpen) return;
    if (onMapClick) {
      onMapClick(e.latlng.lat, e.latlng.lng);
    }
  });

  // Scale marker size on zoom change
  map.on('zoomend', () => {
    const newSize = getSizeForZoom(map.getZoom());
    for (const marker of markers.values()) {
      marker.setIcon(createEventIcon(marker._eventIcon, newSize, marker._eventOpacity));
    }
  });

  return map;
}

function addConfiguredBaseLayer() {
  const mode = getConfiguredMapMode();

  if (mode === 'vector' && tryAddVectorBaseLayer()) {
    return;
  }

  addRasterBaseLayer();
}

function tryAddVectorBaseLayer() {
  if (!L.maplibreGL || !window.maplibregl) {
    console.warn('[roadbeacon] MapLibre GL Leaflet is unavailable, falling back to raster tiles');
    return false;
  }

  const styleUrl = getConfiguredVectorStyleUrl();
  let vectorLoaded = false;
  let fallbackHandled = false;

  const vectorLayer = L.maplibreGL({
    style: styleUrl,
    interactive: false,
    pane: 'tilePane',
    attributionControl: {
      customAttribution: OPENFREEMAP_ATTRIBUTION
    }
  }).addTo(map);

  baseLayer = vectorLayer;

  const glMap = typeof vectorLayer.getMaplibreMap === 'function'
    ? vectorLayer.getMaplibreMap()
    : null;

  const fallbackToRaster = (reason, error = null) => {
    if (vectorLoaded || fallbackHandled) return;
    fallbackHandled = true;

    if (error) {
      console.error(`[roadbeacon] ${reason}`, error);
    } else {
      console.warn(`[roadbeacon] ${reason}`);
    }

    if (!MAP_DISPLAY_CONFIG.fallbackToRasterOnError) {
      return;
    }

    removeBaseLayer();
    addRasterBaseLayer();
  };

  if (!glMap) {
    fallbackToRaster('OpenFreeMap vector layer did not expose a MapLibre instance');
    return true;
  }

  const loadTimeout = window.setTimeout(() => {
    fallbackToRaster(`OpenFreeMap vector basemap did not finish loading within ${VECTOR_BASEMAP_LOAD_TIMEOUT_MS}ms`);
  }, VECTOR_BASEMAP_LOAD_TIMEOUT_MS);

  glMap.once('load', () => {
    vectorLoaded = true;
    window.clearTimeout(loadTimeout);
    console.info(`[roadbeacon] OpenFreeMap vector basemap loaded (${styleUrl})`);
  });

  glMap.on('error', (error) => {
    fallbackToRaster('OpenFreeMap vector basemap failed before initial load', error);
  });

  return true;
}

function addRasterBaseLayer() {
  removeBaseLayer();
  baseLayer = L.tileLayer(
    MAP_DISPLAY_CONFIG.raster.tileUrl,
    MAP_DISPLAY_CONFIG.raster.options
  ).addTo(map);
  console.info('[roadbeacon] Raster basemap active');
}

function removeBaseLayer() {
  if (baseLayer && map && map.hasLayer(baseLayer)) {
    map.removeLayer(baseLayer);
  }
  baseLayer = null;
}

/**
 * Get the map instance.
 */
export function getMap() {
  return map;
}

/**
 * Set the click handler for reporting.
 * @param {function} handler - (lat, lon) => void
 */
export function setClickHandler(handler) {
  onMapClick = handler;
}

/**
 * Set the confirm/deny handler for markers.
 * @param {function} handler - (eventId, status) => void
 */
export function setConfirmHandler(handler) {
  onMarkerConfirm = handler;
}

/**
 * Add or update an event marker on the map.
 * @param {object} report - Parsed report object from roadbeacon.js
 * @param {number} [effectiveExpiry] - Effective expiry timestamp
 * @param {object} [confirmCounts] - {confirmed: N, denied: M}
 */
export function addEventMarker(report, effectiveExpiry = null, confirmCounts = null) {
  if (!report.lat || !report.lon) return;
  if (markers.has(report.id)) return; // Already displayed

  const now = Math.floor(Date.now() / 1000);
  const typeInfo = report.type;

  // Compute opacity based on remaining TTL
  let opacity = 0.95;
  if (effectiveExpiry) {
    const totalDuration = effectiveExpiry - report.createdAt;
    const remaining = effectiveExpiry - now;
    if (totalDuration > 0 && remaining > 0) {
      const fraction = remaining / totalDuration;
      opacity = fraction < 0.25 ? 0.6 : 0.95;
    }
  }

  const size = getSizeForZoom(map.getZoom());
  const marker = L.marker([report.lat, report.lon], {
    icon: createEventIcon(typeInfo.icon, size, opacity)
  }).addTo(map);
  marker._eventIcon = typeInfo.icon;
  marker._eventOpacity = opacity;
  marker._reportData = report; // Store for popup rebuild

  // Popup content
  const popupHtml = buildPopupHtml(report, confirmCounts);

  marker.bindPopup(popupHtml, {
    closeButton: false,
    className: 'custom-popup',
    offset: [0, -10]
  });

  marker.on('popupopen', () => {
    const popup = marker.getPopup().getElement();
    if (!popup) return;

    popup.querySelectorAll('.btn-confirm, .btn-deny').forEach(btn => {
      const newBtn = btn.cloneNode(true);
      btn.parentNode.replaceChild(newBtn, btn);
      newBtn.addEventListener('click', () => {
        if (onMarkerConfirm) {
          onMarkerConfirm(newBtn.dataset.eventId, newBtn.dataset.status);
        }
        marker.closePopup();
      });
    });
  });

  markers.set(report.id, marker);
}

/**
 * Update confirmation counts on an existing marker's popup.
 * @param {string} eventId - Event ID
 * @param {object} confirmCounts - {confirmed: N, denied: M}
 */
export function updateMarkerCounts(eventId, confirmCounts) {
  const marker = markers.get(eventId);
  if (!marker || !marker._reportData) return;

  const popupHtml = buildPopupHtml(marker._reportData, confirmCounts);
  marker.setPopupContent(popupHtml);
}

/**
 * Remove a marker by event ID.
 */
export function removeMarker(eventId) {
  const marker = markers.get(eventId);
  if (marker) {
    map.removeLayer(marker);
    markers.delete(eventId);
  }
}

/**
 * Remove expired markers.
 * @param {Set<string>} expiredIds - Set of expired event IDs
 */
export function removeExpired(expiredIds) {
  for (const id of expiredIds) {
    removeMarker(id);
  }
}

/**
 * Get the current map view center.
 * @returns {{ lat: number, lon: number }}
 */
export function getCenter() {
  const c = map.getCenter();
  return { lat: c.lat, lon: c.lng };
}

/**
 * Get the current zoom level.
 */
export function getZoom() {
  return map.getZoom();
}

/**
 * Set the map move/zoom end handler.
 */
export function onMoveEnd(handler) {
  map.on('moveend', handler);
}

function getSizeForZoom(zoom) {
  if (zoom <= 10) return 40;
  if (zoom <= 13) return 36;
  return 32;
}

function createEventIcon(icon, size, opacity = 0.95) {
  return L.divIcon({
    className: 'event-marker-icon',
    html: `<span style="font-size:${size}px;opacity:${opacity};filter:drop-shadow(0 3px 6px rgba(0,0,0,0.5));transition:transform 0.2s ease;">${icon}</span>`,
    iconSize: [size + 8, size + 8],
    iconAnchor: [(size + 8) / 2, (size + 8) / 2],
    popupAnchor: [0, -(size + 8) / 2]
  });
}

function buildPopupHtml(report, confirmCounts = null) {
  const now = Math.floor(Date.now() / 1000);
  const typeInfo = report.type;
  const age = formatAge(now - report.createdAt);
  const pubkeyShort = report.pubkey ? report.pubkey.slice(0, 8) + '...' : 'anonymous';

  const counts = confirmCounts || { confirmed: 0, denied: 0 };
  const countsHtml = (counts.confirmed > 0 || counts.denied > 0)
    ? `<span class="popup-counts">+${counts.confirmed} / −${counts.denied}</span>`
    : '';

  return `
    <div class="marker-popup">
      <div class="popup-header">
        <span class="popup-icon">${typeInfo.icon}</span>
        <strong>${typeInfo.string.replace(/_/g, ' ')}</strong>
        ${countsHtml}
      </div>
      <div class="popup-meta">${age} · ${pubkeyShort}</div>
      ${report.content ? `<div class="popup-content">${escapeHtml(report.content)}</div>` : ''}
      <div class="popup-actions">
        <button class="btn-confirm" data-event-id="${report.id}" data-status="still_there">✓ Still there</button>
        <button class="btn-deny" data-event-id="${report.id}" data-status="no_longer_there">✗ Gone</button>
      </div>
    </div>
  `;
}

function formatAge(seconds) {
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} hr ago`;
  return `${Math.floor(seconds / 86400)} days ago`;
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}
