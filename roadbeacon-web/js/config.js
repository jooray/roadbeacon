// Source-level web app configuration.
//
// Basemap selection is intentionally configured here, not in the UI.
// Change `mode` to 'raster' to restore the previous Leaflet raster tiles.
// In vector mode, Roadbeacon uses OpenFreeMap via MapLibre GL Leaflet.

export const OPENFREEMAP_STYLES = ['liberty', 'positron', 'bright'];

export const MAP_DISPLAY_CONFIG = {
  // 'vector' = OpenFreeMap vector tiles (default)
  // 'raster' = legacy CARTO raster tiles
  mode: 'vector',

  // If vector initialization fails before the style fully loads,
  // automatically fall back to the raster basemap.
  fallbackToRasterOnError: true,

  vector: {
    // Supported OpenFreeMap defaults: liberty, positron, bright
    style: 'liberty',

    // Optional full style URL override. Leave empty to use the style above.
    // Example: 'https://tiles.openfreemap.org/styles/liberty'
    styleUrl: ''
  },

  raster: {
    tileUrl: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
    options: {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
      subdomains: 'abcd',
      maxZoom: 20
    }
  }
};

export function getConfiguredMapMode() {
  return MAP_DISPLAY_CONFIG.mode === 'raster' ? 'raster' : 'vector';
}

export function getConfiguredVectorStyleUrl() {
  const customStyleUrl = MAP_DISPLAY_CONFIG.vector?.styleUrl?.trim();
  if (customStyleUrl) return customStyleUrl;

  const configuredStyle = MAP_DISPLAY_CONFIG.vector?.style?.trim();
  const style = OPENFREEMAP_STYLES.includes(configuredStyle) ? configuredStyle : 'liberty';
  return `https://tiles.openfreemap.org/styles/${style}`;
}
