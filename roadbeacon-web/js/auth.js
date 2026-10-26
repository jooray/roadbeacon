// Key management: generate, import nsec, NIP-07, NIP-46, ephemeral

import { generateSecretKey, getPublicKey, finalizeEvent } from 'https://esm.sh/nostr-tools@2/pure';
import { nsecEncode, decode as nip19Decode } from 'https://esm.sh/nostr-tools@2/nip19';
import { bytesToHex, hexToBytes } from 'https://esm.sh/@noble/hashes@1/utils';

/** Storage identifiers predate the rename; changing them would orphan a visitor's key. */
const STORAGE_KEY = 'roadstr_privkey';

let currentMode = null; // 'generate' | 'import' | 'ephemeral' | 'nip07' | 'nip46'
let secretKeyHex = null;
let nip46Signer = null;

/**
 * Initialize auth with the chosen mode.
 * @param {'generate'|'import'|'ephemeral'|'nip07'|'nip46'} mode
 * @param {object} [params] - Mode-specific params (e.g. { nsec } for import)
 * @returns {Promise<string>} Public key hex
 */
export async function initAuth(mode, params = {}) {
  currentMode = mode;
  secretKeyHex = null;
  nip46Signer = null;

  switch (mode) {
    case 'generate': {
      // Check localStorage first
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored) {
        secretKeyHex = stored;
      } else {
        const sk = generateSecretKey();
        secretKeyHex = bytesToHex(sk);
        localStorage.setItem(STORAGE_KEY, secretKeyHex);
      }
      return getPublicKey(hexToBytes(secretKeyHex));
    }

    case 'import': {
      const { nsec } = params;
      if (!nsec) throw new Error('nsec required for import mode');
      const { type, data } = nip19Decode(nsec);
      if (type !== 'nsec') throw new Error('Invalid nsec');
      secretKeyHex = bytesToHex(data);
      localStorage.setItem(STORAGE_KEY, secretKeyHex);
      return getPublicKey(data);
    }

    case 'ephemeral': {
      // No stored key; fresh keypair per event
      return null;
    }

    case 'nip07': {
      if (!window.nostr) throw new Error('No NIP-07 extension found');
      const pubkey = await window.nostr.getPublicKey();
      return pubkey;
    }

    case 'nip46': {
      // NIP-46 remote signing (e.g., Amber)
      const { connectUri, onUri } = params;
      if (connectUri) {
        // Already have a connect URI, handle externally
        return null;
      }
      // Generate local keypair for NIP-46 session
      const sk = generateSecretKey();
      const localPubkey = getPublicKey(sk);
      const relay = 'wss://relay.damus.io';
      const uri = `nostrconnect://${localPubkey}?relay=${encodeURIComponent(relay)}&metadata=${encodeURIComponent(JSON.stringify({ name: 'Roadbeacon' }))}`;
      if (onUri) onUri(uri);
      // Store session key for signing requests
      secretKeyHex = bytesToHex(sk);
      nip46Signer = { localPubkey, relay, connected: false };
      return localPubkey;
    }

    default:
      throw new Error(`Unknown auth mode: ${mode}`);
  }
}

/**
 * Get the current auth mode.
 */
export function getAuthMode() {
  return currentMode;
}

/**
 * Get the current public key.
 * @returns {Promise<string|null>} Hex pubkey
 */
export async function getPubkey() {
  switch (currentMode) {
    case 'generate':
    case 'import':
      return secretKeyHex ? getPublicKey(hexToBytes(secretKeyHex)) : null;
    case 'ephemeral':
      return null; // No persistent pubkey
    case 'nip07':
      return window.nostr ? window.nostr.getPublicKey() : null;
    case 'nip46':
      return nip46Signer ? nip46Signer.localPubkey : null;
    default:
      return null;
  }
}

/**
 * Sign an unsigned event.
 * @param {object} unsignedEvent - Event without id/sig/pubkey
 * @returns {Promise<object>} Signed event with id, sig, pubkey
 */
export async function signEvent(unsignedEvent) {
  switch (currentMode) {
    case 'generate':
    case 'import': {
      if (!secretKeyHex) throw new Error('No key available');
      const sk = hexToBytes(secretKeyHex);
      return finalizeEvent(unsignedEvent, sk);
    }

    case 'ephemeral': {
      const sk = generateSecretKey();
      return finalizeEvent(unsignedEvent, sk);
    }

    case 'nip07': {
      if (!window.nostr) throw new Error('No NIP-07 extension');
      const pubkey = await window.nostr.getPublicKey();
      const eventWithPubkey = { ...unsignedEvent, pubkey };
      return window.nostr.signEvent(eventWithPubkey);
    }

    case 'nip46': {
      // For NIP-46, the remote signer handles it
      // This is a simplified implementation - full NIP-46 would require
      // back-and-forth relay messages with the remote signer
      if (!secretKeyHex) throw new Error('NIP-46 session not initialized');
      // Fallback to local signing with session key for demo
      const sk = hexToBytes(secretKeyHex);
      return finalizeEvent(unsignedEvent, sk);
    }

    default: {
      // No auth mode set: use ephemeral key (anonymous)
      const sk = generateSecretKey();
      return finalizeEvent(unsignedEvent, sk);
    }
  }
}

/**
 * Clear stored keys and reset state.
 */
export function logout() {
  localStorage.removeItem(STORAGE_KEY);
  secretKeyHex = null;
  nip46Signer = null;
  currentMode = null;
}

/**
 * Check if a stored key exists (for auto-login).
 */
export function hasStoredKey() {
  return !!localStorage.getItem(STORAGE_KEY);
}

/**
 * Get the stored nsec for display/backup.
 */
export function getStoredNsec() {
  const hex = localStorage.getItem(STORAGE_KEY);
  if (!hex) return null;
  return nsecEncode(hexToBytes(hex));
}
