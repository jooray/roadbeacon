// New-version check. The page has no service worker, but the browser's HTTP cache can still hand
// an open or reopened page old modules; so the page asks the server which version is live and, on
// a change, re-fetches every file past the cache before reloading.

import { VERSION } from './version.js';

const CHECK_INTERVAL_MS = 10 * 60 * 1000;
const RELOADED_FOR = 'roadbeacon-reloaded-for';

async function liveVersion() {
  const response = await fetch(`version.json?t=${Date.now()}`, { cache: 'no-store' });
  if (!response.ok) return null;
  return response.json();
}

async function check(onUpdated) {
  let live;
  try {
    live = await liveVersion();
  } catch {
    return; // offline: try again later
  }
  if (!live || live.version === VERSION) return;

  // One forced refresh per version; if the files still disagree, do not loop.
  try {
    if (sessionStorage.getItem(RELOADED_FOR) === live.version) return;
    sessionStorage.setItem(RELOADED_FOR, live.version);
  } catch { /* storage unavailable: refresh anyway */ }

  onUpdated(live.version);
  await Promise.allSettled((live.files || []).map(file => fetch(file, { cache: 'reload' })));
  setTimeout(() => location.reload(), 1500);
}

/** Checks at start, every ten minutes and when the page becomes visible again. */
export function watchForUpdates(onUpdated) {
  check(onUpdated);
  setInterval(() => check(onUpdated), CHECK_INTERVAL_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') check(onUpdated);
  });
}
