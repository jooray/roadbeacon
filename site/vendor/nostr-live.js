/*! nostr-live 1.0.0
   Live Nostr posts for any website. One file, no build step, no dependencies.

     <div data-nostr-live data-nprofile="nprofile1..."></div>
     <script src="nostr-live.js" defer></script>

   or  NostrLive.mount(element, { nprofile, relays, limit, layout, ... })

   Everything that arrives from a relay is untrusted. Events are checked (shape,
   id, BIP-340 signature, author) before they are used, and every node is built
   with createElement/textContent. There is no innerHTML in this file.
   Styling is injected by this file and written with :where(), so a site's own
   CSS always wins. Theme it with the --nl-* custom properties (see README.md).

   The bech32/TLV, BIP-340 and relay-collection code started life in the
   projects-showcase feed, which in turn adapted tamersofentropy.net/nostr-viewer.js.

   Layout of this file:
     1. config, strings          5. relay pool and queries
     2. bytes, bech32, TLV       6. loading, selection, cache
     3. BIP-340 verification     7. note parsing (links, media, quotes)
     4. events                   8. styles, DOM, the widget, the public API */

(() => {
  'use strict';

  const VERSION = '1.0.0';
  const root = typeof window !== 'undefined' ? window : globalThis;
  // a nonce on our own <script> is reused for the injected <style> (strict CSP)
  const ownScript = typeof document !== 'undefined' ? document.currentScript : null;

  /* ---------------------------------------------------------- 1. config -- */

  const CFG = {
    relays: ['wss://nostr.cypherpunk.today', 'wss://relay.primal.net', 'wss://nos.lol'],
    maxRelays: 5,
    maxLimit: 20,
    timeout: 8000,          // hard stop for a relay query
    grace: 1500,            // how long the others get once one relay has answered
    connectTimeout: 6000,   // a socket that has not opened by then is given up on
    relayCooldown: 20000,   // a relay that never connected is not retried for this long
    idleMs: 15000,          // an unused socket is closed after this
    ttl: 60 * 60 * 1000,    // sessionStorage entries older than this are ignored
    fresh: 30 * 1000,       // a cache entry younger than this is not refetched
    revisit: 5 * 60 * 1000, // a tab that comes back after this long refreshes
    tickMs: 6000,           // ticker rotation
    maxMedia: 4,            // media items per note
    maxQuotes: 3,           // quoted notes per note
    collapseAt: 420,        // characters before "show more"
    hardCap: 6000,          // characters of one note ever rendered
    maxContent: 60000,      // events with more content than this are dropped
    maxMentions: 12,
    maxRefs: 6,
    cachePrefix: 'nostrlive1:'
  };

  const JUMP = 'https://njump.me/';
  const REL = 'noopener noreferrer nofollow';
  const KINDS_SHOWN = [1, 30023];

  /* Built-in strings. {name} is the only placeholder. Missing keys in another
     language fall back to English, so a partial `strings` override is fine. */
  const STRINGS = {
    en: {
      region: 'Nostr posts from {name}', loading: 'Loading posts…', loaded: 'Posts loaded',
      empty: 'No posts yet.', error: 'The relays did not answer.', retry: 'Try again',
      unsupported: 'This browser cannot verify Nostr posts.',
      more: 'Show more', less: 'Show less', article: 'Article', readArticle: 'Read article',
      quotedNote: 'Quoted note', quotedArticle: 'Quoted article', openNote: 'Open note',
      imageAlt: 'Image in a post', videoLabel: 'Video in a post',
      follow: 'Follow on Nostr', followTitle: 'Opens your Nostr app', openIn: 'Open in',
      copy: 'Copy nprofile', copied: 'Copied', copyFailed: 'Copy failed',
      prev: 'Previous post', next: 'Next post'
    },
    sk: {
      region: 'Príspevky na Nostri: {name}', loading: 'Načítavam príspevky…', loaded: 'Príspevky načítané',
      empty: 'Zatiaľ žiadne príspevky.', error: 'Relaye neodpovedali.', retry: 'Skúsiť znova',
      unsupported: 'Tento prehliadač nevie overiť príspevky z Nostru.',
      more: 'Zobraziť viac', less: 'Zobraziť menej', article: 'Článok', readArticle: 'Čítať článok',
      quotedNote: 'Citovaný príspevok', quotedArticle: 'Citovaný článok', openNote: 'Otvoriť príspevok',
      imageAlt: 'Obrázok v príspevku', videoLabel: 'Video v príspevku',
      follow: 'Sledovať na Nostri', followTitle: 'Otvorí tvoju aplikáciu pre Nostr', openIn: 'Otvoriť v',
      copy: 'Kopírovať nprofile', copied: 'Skopírované', copyFailed: 'Kopírovanie zlyhalo',
      prev: 'Predchádzajúci príspevok', next: 'Ďalší príspevok'
    },
    cs: {
      region: 'Příspěvky na Nostru: {name}', loading: 'Načítám příspěvky…', loaded: 'Příspěvky načteny',
      empty: 'Zatím žádné příspěvky.', error: 'Relaye neodpověděly.', retry: 'Zkusit znovu',
      unsupported: 'Tento prohlížeč neumí ověřit příspěvky z Nostru.',
      more: 'Zobrazit víc', less: 'Zobrazit méně', article: 'Článek', readArticle: 'Číst článek',
      quotedNote: 'Citovaný příspěvek', quotedArticle: 'Citovaný článek', openNote: 'Otevřít příspěvek',
      imageAlt: 'Obrázek v příspěvku', videoLabel: 'Video v příspěvku',
      follow: 'Sledovat na Nostru', followTitle: 'Otevře tvou aplikaci pro Nostr', openIn: 'Otevřít v',
      copy: 'Kopírovat nprofile', copied: 'Zkopírováno', copyFailed: 'Kopírování selhalo',
      prev: 'Předchozí příspěvek', next: 'Další příspěvek'
    },
    de: {
      region: 'Nostr-Beiträge von {name}', loading: 'Beiträge werden geladen…', loaded: 'Beiträge geladen',
      empty: 'Noch keine Beiträge.', error: 'Die Relays haben nicht geantwortet.', retry: 'Erneut versuchen',
      unsupported: 'Dieser Browser kann Nostr-Beiträge nicht prüfen.',
      more: 'Mehr anzeigen', less: 'Weniger anzeigen', article: 'Artikel', readArticle: 'Artikel lesen',
      quotedNote: 'Zitierter Beitrag', quotedArticle: 'Zitierter Artikel', openNote: 'Beitrag öffnen',
      imageAlt: 'Bild in einem Beitrag', videoLabel: 'Video in einem Beitrag',
      follow: 'Auf Nostr folgen', followTitle: 'Öffnet deine Nostr-App', openIn: 'Öffnen in',
      copy: 'nprofile kopieren', copied: 'Kopiert', copyFailed: 'Kopieren fehlgeschlagen',
      prev: 'Vorheriger Beitrag', next: 'Nächster Beitrag'
    },
    es: {
      region: 'Publicaciones de {name} en Nostr', loading: 'Cargando publicaciones…', loaded: 'Publicaciones cargadas',
      empty: 'Aún no hay publicaciones.', error: 'Los relays no respondieron.', retry: 'Reintentar',
      unsupported: 'Este navegador no puede verificar las publicaciones de Nostr.',
      more: 'Ver más', less: 'Ver menos', article: 'Artículo', readArticle: 'Leer artículo',
      quotedNote: 'Publicación citada', quotedArticle: 'Artículo citado', openNote: 'Abrir publicación',
      imageAlt: 'Imagen en una publicación', videoLabel: 'Vídeo en una publicación',
      follow: 'Seguir en Nostr', followTitle: 'Abre tu aplicación de Nostr', openIn: 'Abrir en',
      copy: 'Copiar nprofile', copied: 'Copiado', copyFailed: 'Error al copiar',
      prev: 'Publicación anterior', next: 'Publicación siguiente'
    }
  };

  /* ------------------------------------------------ 2. bytes, bech32, TLV -- */

  /* ------------------------------------------------------------- bytes -- */

  const HEX = /^[0-9a-f]+$/;
  const isHex = (s, len) => typeof s === 'string' && s.length === len && HEX.test(s);

  const toHex = bytes => {
    let out = '';
    for (const b of bytes) out += b.toString(16).padStart(2, '0');
    return out;
  };
  const fromHex = hex => {
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    return out;
  };
  const utf8 = s => new TextEncoder().encode(s);
  const concat = (...arrs) => {
    const out = new Uint8Array(arrs.reduce((n, a) => n + a.length, 0));
    let at = 0;
    for (const a of arrs) { out.set(a, at); at += a.length; }
    return out;
  };

  /* ------------------------------------------------------------ bech32 -- */

  const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
  const GEN = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

  const polymod = values => {
    let chk = 1;
    for (const v of values) {
      const top = chk >>> 25;
      chk = ((chk & 0x1ffffff) << 5) ^ v;
      for (let i = 0; i < 5; i++) if ((top >>> i) & 1) chk ^= GEN[i];
    }
    return chk;
  };
  const hrpExpand = hrp => {
    const out = [];
    for (let i = 0; i < hrp.length; i++) out.push(hrp.charCodeAt(i) >> 5);
    out.push(0);
    for (let i = 0; i < hrp.length; i++) out.push(hrp.charCodeAt(i) & 31);
    return out;
  };

  const convertBits = (data, from, to, pad) => {
    let acc = 0, bits = 0;
    const out = [];
    const maxv = (1 << to) - 1;
    for (const value of data) {
      if (value < 0 || (value >> from) !== 0) return null;
      acc = (acc << from) | value;
      bits += from;
      while (bits >= to) {
        bits -= to;
        out.push((acc >> bits) & maxv);
      }
      acc &= (1 << bits) - 1;
    }
    if (pad) {
      if (bits > 0) out.push((acc << (to - bits)) & maxv);
    } else if (bits >= from || ((acc << (to - bits)) & maxv)) {
      return null;
    }
    return out;
  };

  /* returns { hrp, bytes } or null; never throws */
  const bech32Decode = input => {
    if (typeof input !== 'string' || input.length < 8 || input.length > 5000) return null;
    if (input !== input.toLowerCase() && input !== input.toUpperCase()) return null;
    const str = input.toLowerCase();
    const sep = str.lastIndexOf('1');
    if (sep < 1 || sep + 7 > str.length) return null;
    const hrp = str.slice(0, sep);
    const data = [];
    for (let i = sep + 1; i < str.length; i++) {
      const c = CHARSET.indexOf(str[i]);
      if (c === -1) return null;
      data.push(c);
    }
    if (polymod(hrpExpand(hrp).concat(data)) !== 1) return null;
    const bytes = convertBits(data.slice(0, -6), 5, 8, false);
    return bytes ? { hrp, bytes: Uint8Array.from(bytes) } : null;
  };

  const bech32Encode = (hrp, bytes) => {
    const data = convertBits(Array.from(bytes), 8, 5, true);
    const mod = polymod(hrpExpand(hrp).concat(data, [0, 0, 0, 0, 0, 0])) ^ 1;
    const check = [];
    for (let i = 0; i < 6; i++) check.push((mod >> (5 * (5 - i))) & 31);
    let out = hrp + '1';
    for (const c of data.concat(check)) out += CHARSET[c];
    return out;
  };

  const parseTLV = bytes => {
    const out = {};
    let at = 0;
    while (at + 2 <= bytes.length) {
      const type = bytes[at++];
      const len = bytes[at++];
      if (at + len > bytes.length) break;
      (out[type] = out[type] || []).push(bytes.slice(at, at + len));
      at += len;
    }
    return out;
  };
  const tlv = (type, bytes) => concat(Uint8Array.of(type, bytes.length), bytes);
  const u32 = n => Uint8Array.of((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
  const readU32 = b => ((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) >>> 0;

  const shortKey = pk => `${pk.slice(0, 6)}…${pk.slice(-4)}`;
  const encodeNpub = pk => bech32Encode('npub', fromHex(pk));
  const shortNpub = pk => {
    const n = encodeNpub(pk);
    return `${n.slice(0, 9)}…${n.slice(-4)}`;
  };
  const encodeNevent = (id, author, relay, kind) => bech32Encode('nevent', concat(
    tlv(0, fromHex(id)),
    relay ? tlv(1, utf8(relay)) : new Uint8Array(0),
    isHex(author, 64) ? tlv(2, fromHex(author)) : new Uint8Array(0),
    Number.isInteger(kind) ? tlv(3, u32(kind)) : new Uint8Array(0)
  ));
  const encodeNaddr = (kind, pubkey, identifier, relay) => bech32Encode('naddr', concat(
    tlv(0, utf8(identifier)),
    relay ? tlv(1, utf8(relay)) : new Uint8Array(0),
    tlv(2, fromHex(pubkey)),
    tlv(3, u32(kind))
  ));

  /* Any nostr reference to a plain object, validated, or null. Relay hints that
     ride inside a reference are deliberately dropped: a note must not be able
     to make the visitor's browser dial a host of its choosing. */
  const decodeRef = raw => {
    const dec = bech32Decode(String(raw).replace(/^nostr:/i, ''));
    if (!dec) return null;
    const { hrp, bytes } = dec;
    if (hrp === 'npub' || hrp === 'note') {
      if (bytes.length !== 32) return null;
      return hrp === 'npub' ? { type: 'npub', pubkey: toHex(bytes) } : { type: 'note', id: toHex(bytes) };
    }
    if (hrp !== 'nprofile' && hrp !== 'nevent' && hrp !== 'naddr') return null;
    const rec = parseTLV(bytes);
    if (hrp === 'nprofile') {
      if (!rec[0] || rec[0][0].length !== 32) return null;
      const relays = (rec[1] || []).map(b => new TextDecoder().decode(b));
      return { type: 'nprofile', pubkey: toHex(rec[0][0]), relays };
    }
    if (hrp === 'nevent') {
      if (!rec[0] || rec[0][0].length !== 32) return null;
      const author = rec[2] && rec[2][0].length === 32 ? toHex(rec[2][0]) : '';
      return { type: 'nevent', id: toHex(rec[0][0]), author };
    }
    if (!rec[0] || !rec[2] || rec[2][0].length !== 32 || !rec[3] || rec[3][0].length !== 4) return null;
    return {
      type: 'naddr',
      identifier: new TextDecoder().decode(rec[0][0]),
      pubkey: toHex(rec[2][0]),
      kind: readU32(rec[3][0])
    };
  };

  /* ----------------------------------------------------------- BIP-340 -- */
  /* secp256k1 Schnorr verification in BigInt, so a relay cannot hand the page
     a note the account never signed. Verify only: there is no signing here and
     no secret ever touches it. Not constant time, which does not matter for
     public data. */

  const P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2Fn;
  const N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141n;
  const GX = 0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798n;
  const GY = 0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8n;

  const md = a => { const r = a % P; return r >= 0n ? r : r + P; };
  const powmod = (base, exp, m) => {
    let result = 1n;
    base %= m;
    while (exp > 0n) {
      if (exp & 1n) result = (result * base) % m;
      base = (base * base) % m;
      exp >>= 1n;
    }
    return result;
  };

  /* Jacobian points [X, Y, Z]; Z = 0 is the point at infinity */
  const INF = [0n, 1n, 0n];
  const jdouble = pt => {
    const [X, Y, Z] = pt;
    if (Z === 0n || Y === 0n) return INF;
    const A = md(X * X), B = md(Y * Y), C = md(B * B);
    const D = md(2n * (md((X + B) * (X + B)) - A - C));
    const E = 3n * A, F = md(E * E);
    const X3 = md(F - 2n * D);
    return [X3, md(E * (D - X3) - 8n * C), md(2n * Y * Z)];
  };
  const jadd = (p1, p2) => {
    if (p1[2] === 0n) return p2;
    if (p2[2] === 0n) return p1;
    const [X1, Y1, Z1] = p1, [X2, Y2, Z2] = p2;
    const Z1Z1 = md(Z1 * Z1), Z2Z2 = md(Z2 * Z2);
    const U1 = md(X1 * Z2Z2), U2 = md(X2 * Z1Z1);
    const S1 = md(Y1 * Z2 * Z2Z2), S2 = md(Y2 * Z1 * Z1Z1);
    if (U1 === U2) return S1 === S2 ? jdouble(p1) : INF;
    const H = md(U2 - U1), I = md(4n * H * H), J = md(H * I);
    const r = md(2n * (S2 - S1)), V = md(U1 * I);
    const X3 = md(r * r - J - 2n * V);
    return [X3, md(r * (V - X3) - 2n * S1 * J), md(H * md(md((Z1 + Z2) * (Z1 + Z2)) - Z1Z1 - Z2Z2))];
  };

  const G = [GX, GY, 1n];

  /* a*G + b*Q by Shamir's trick */
  const mul2 = (a, b, Q) => {
    const GQ = jadd(G, Q);
    let acc = INF;
    for (let i = 255; i >= 0; i--) {
      acc = jdouble(acc);
      const ba = (a >> BigInt(i)) & 1n, bb = (b >> BigInt(i)) & 1n;
      if (ba && bb) acc = jadd(acc, GQ);
      else if (ba) acc = jadd(acc, G);
      else if (bb) acc = jadd(acc, Q);
    }
    return acc;
  };

  const sha256 = async bytes => new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  let challengeTag = null;

  const verifySchnorr = async (sigHex, idHex, pubHex) => {
    if (!isHex(sigHex, 128) || !isHex(idHex, 64) || !isHex(pubHex, 64)) return false;
    const px = BigInt('0x' + pubHex);
    const r = BigInt('0x' + sigHex.slice(0, 64));
    const s = BigInt('0x' + sigHex.slice(64));
    if (px >= P || r >= P || s >= N) return false;
    // lift_x: the point with this x and an even y
    const c = md(px * px * px + 7n);
    let y = powmod(c, (P + 1n) / 4n, P);
    if (md(y * y) !== c) return false;
    if (y & 1n) y = P - y;
    if (!challengeTag) challengeTag = await sha256(utf8('BIP0340/challenge'));
    const e = BigInt('0x' + toHex(await sha256(concat(
      challengeTag, challengeTag, fromHex(sigHex.slice(0, 64)), fromHex(pubHex), fromHex(idHex)
    )))) % N;
    // R = s*G - e*Q
    const R = mul2(s, (N - e) % N, [px, y, 1n]);
    if (R[2] === 0n) return false;
    const zinv = powmod(R[2], P - 2n, P);
    const z2 = md(zinv * zinv);
    const rx = md(R[0] * z2);
    const ry = md(R[1] * z2 * zinv);
    return (ry & 1n) === 0n && rx === r;
  };

  /* ----------------------------------------------------------- events -- */

  const wellFormed = ev =>
    ev && typeof ev === 'object' &&
    isHex(ev.id, 64) && isHex(ev.pubkey, 64) && isHex(ev.sig, 128) &&
    Number.isSafeInteger(ev.kind) && ev.kind >= 0 && ev.kind < 65536 &&
    Number.isSafeInteger(ev.created_at) && ev.created_at > 0 &&
    typeof ev.content === 'string' && ev.content.length <= CFG.maxContent &&
    Array.isArray(ev.tags) && ev.tags.length <= 2000 &&
    ev.tags.every(tag => Array.isArray(tag) && tag.every(x => typeof x === 'string'));

  /* shape, id (sha-256 of the canonical serialisation) and signature */
  const verifyEvent = async ev => {
    try {
      if (!wellFormed(ev)) return false;
      const digest = toHex(await sha256(utf8(JSON.stringify(
        [0, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content]
      ))));
      if (digest !== ev.id) return false;
      return await verifySchnorr(ev.sig, ev.id, ev.pubkey);
    } catch (e) {
      return false;
    }
  };

  const canVerify = () => !!(globalThis.crypto && crypto.subtle && typeof BigInt === 'function');

  const tagValue = (ev, name) => {
    const tag = ev.tags.find(x => x[0] === name && x[1]);
    return tag ? tag[1] : '';
  };

  /* ----------------------------------------------------------- relays -- */

  const cleanRelay = url => {
    try {
      const u = new URL(url);
      if (u.protocol !== 'wss:' || u.username || u.password) return null;
      return u.href.replace(/\/$/, '');
    } catch (e) {
      return null;
    }
  };

  /* ------------------------------------------------ 5. relay pool, queries -- */
  /* One WebSocket per relay URL, shared by every widget on the page. Each
     widget opens a short-lived subscription on it. A relay that cannot be
     reached only fails its own subscriptions, and is left alone for a while. */

  const conns = new Map();
  const wsSend = (c, frame) => { try { c.ws.send(frame); } catch (e) { /* closing */ } };

  // a socket still dialling is closed the moment it opens, which keeps the
  // console free of "closed before the connection was established" warnings
  const shut = ws => {
    try {
      if (ws.readyState === 0) ws.addEventListener('open', () => ws.close());
      else if (ws.readyState === 1) ws.close();
    } catch (e) { /* already closed */ }
  };

  const failConn = c => {
    if (c.dead) return;
    c.dead = true;
    clearTimeout(c.idle);
    clearTimeout(c.connTimer);
    if (!c.opened) c.failedAt = Date.now();                     // never connected: cool down
    else if (conns.get(c.url) === c) conns.delete(c.url);       // dropped later: reconnect next time
    const subs = [...c.subs.values()];
    c.subs.clear();
    c.queue = [];
    if (c.ws) shut(c.ws);
    for (const h of subs) h.fail();
  };

  const connect = url => {
    let c = conns.get(url);
    if (c && (!c.dead || (c.failedAt && Date.now() - c.failedAt < CFG.relayCooldown))) return c;
    c = { url, ws: null, open: false, opened: false, dead: false, failedAt: 0, subs: new Map(), queue: [], idle: 0, connTimer: 0 };
    conns.set(url, c);
    let ws;
    try { ws = new WebSocket(url); } catch (e) { c.dead = true; c.failedAt = Date.now(); return c; }
    c.ws = ws;
    c.connTimer = setTimeout(() => failConn(c), CFG.connectTimeout);
    ws.addEventListener('open', () => {
      clearTimeout(c.connTimer);
      c.open = c.opened = true;
      for (const frame of c.queue) wsSend(c, frame);
      c.queue = [];
    });
    ws.addEventListener('message', msg => {
      if (typeof msg.data !== 'string' || msg.data.length > 400000) return;
      let m;
      try { m = JSON.parse(msg.data); } catch (e) { return; }
      if (!Array.isArray(m)) return;
      const h = c.subs.get(m[1]);
      if (!h) return;
      if (m[0] === 'EVENT') h.event(m[2]);
      else if (m[0] === 'EOSE') h.eose();
      else if (m[0] === 'CLOSED') h.fail();
    });
    ws.addEventListener('error', () => failConn(c));
    ws.addEventListener('close', () => failConn(c));
    return c;
  };

  const armIdle = c => {
    clearTimeout(c.idle);
    if (!c.subs.size && !c.dead) c.idle = setTimeout(() => closeIdle(c), CFG.idleMs);
  };
  const closeIdle = c => {
    if (c.subs.size || c.dead) return;
    c.dead = true;
    clearTimeout(c.idle);
    clearTimeout(c.connTimer);
    if (conns.get(c.url) === c) conns.delete(c.url);
    if (c.ws) shut(c.ws);
  };

  const subscribe = (url, filters, handlers) => {
    const c = connect(url);
    if (c.dead) { setTimeout(handlers.fail, 0); return () => {}; }
    const id = 'nl' + Math.random().toString(36).slice(2, 10);
    clearTimeout(c.idle);
    c.subs.set(id, handlers);
    const frame = JSON.stringify(['REQ', id, ...filters]);
    if (c.open) wsSend(c, frame); else c.queue.push(frame);
    return () => {
      if (!c.subs.delete(id)) return;
      if (c.open) wsSend(c, JSON.stringify(['CLOSE', id]));
      else c.queue = c.queue.filter(f => f !== frame);
      armIdle(c);
    };
  };

  /* Ask several relays the same question. Resolves with the union of events
     and how many relays actually answered; never rejects. Once one relay has
     said it is done the rest get a short grace, so a dead relay costs the
     visitor seconds only if every relay is dead. */
  const query = (relays, filters, opts = {}) => new Promise(resolve => {
    const events = new Map();
    const unsubs = [];
    let answered = 0, finished = 0, settled = false, graceTimer = 0;
    const done = () => {
      if (settled) return;
      settled = true;
      clearTimeout(hard);
      clearTimeout(graceTimer);
      for (const stop of unsubs) stop();
      resolve({ events: [...events.values()], answered, total: relays.length });
    };
    const hard = setTimeout(done, opts.timeout || CFG.timeout);
    if (!relays.length) { done(); return; }
    for (const url of relays) {
      let over = false;
      const finish = ok => {
        if (over) return;
        over = true;
        finished += 1;
        if (ok) {
          answered += 1;
          if (!graceTimer && finished < relays.length) graceTimer = setTimeout(done, CFG.grace);
        }
        if (finished >= relays.length) done();
      };
      unsubs.push(subscribe(url, filters, {
        event: ev => {
          if (ev && typeof ev.id === 'string' && events.size < 300 && !events.has(ev.id)) events.set(ev.id, ev);
        },
        eose: () => finish(true),
        fail: () => finish(false)
      }));
    }
  });

  /* Pause relay work while the tab is hidden: idle sockets are closed, and
     widgets wait for the tab to come back before they start a load. */
  const whenVisible = () => {
    if (typeof document === 'undefined' || !document.hidden) return Promise.resolve();
    return new Promise(resolve => {
      const check = () => {
        if (document.hidden) return;
        document.removeEventListener('visibilitychange', check);
        resolve();
      };
      document.addEventListener('visibilitychange', check);
    });
  };
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) for (const c of [...conns.values()]) closeIdle(c);
    });
  }

  /* -------------------------------------------------- 6. loading, caching -- */

  const str = (v, max) => (typeof v === 'string' ? v.trim().slice(0, max) : '');

  const parseProfile = ev => {
    let meta = {};
    try {
      const parsed = JSON.parse(ev.content);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) meta = parsed;
    } catch (e) { /* a kind 0 that is not JSON is just an empty profile */ }
    return {
      pubkey: ev.pubkey,
      name: str(meta.display_name, 80) || str(meta.displayName, 80) || str(meta.name, 80),
      nip05: str(meta.nip05, 120),
      about: str(meta.about, 600),
      picture: str(meta.picture, 600)
    };
  };

  const memory = new Map();
  const cacheKey = (pk, o) => `${CFG.cachePrefix}${pk}:${o.kinds.join(',')}:${o.limit}:${o.skipReplies ? 1 : 0}`;

  const cacheGet = key => {
    const hit = memory.get(key);
    if (hit && Date.now() - hit.t < CFG.ttl) return hit;
    try {
      const raw = sessionStorage.getItem(key);
      if (!raw) return null;
      const rec = JSON.parse(raw);
      if (!rec || Date.now() - rec.t > CFG.ttl || !Array.isArray(rec.items)) return null;
      // anything that came back out of storage is checked for shape again
      if (!rec.items.every(wellFormed) || (rec.profile && !wellFormed(rec.profile))) return null;
      const ex = rec.extras || {};
      rec.extras = { profiles: {}, refs: {} };
      for (const part of ['profiles', 'refs']) {
        for (const [k, ev] of Object.entries(ex[part] || {})) if (wellFormed(ev)) rec.extras[part][k] = ev;
      }
      memory.set(key, rec);
      return rec;
    } catch (e) {
      return null;
    }
  };
  const cachePut = (key, S) => {
    const rec = { t: Date.now(), profile: S.profile, items: S.items, extras: S.extras };
    memory.set(key, rec);
    try { sessionStorage.setItem(key, JSON.stringify(rec)); } catch (e) { /* full or blocked */ }
  };
  const cacheDrop = key => {
    memory.delete(key);
    try { sessionStorage.removeItem(key); } catch (e) { /* blocked */ }
  };

  const refKey = ref => (ref.type === 'naddr' ? `${ref.kind}:${ref.pubkey}:${ref.identifier}` : ref.id);
  const isReply = ev => ev.kind === 1 && ev.tags.some(t => t[0] === 'e' && t[3] !== 'mention');
  const newest = (a, b) => b.created_at - a.created_at;

  /* From the raw events, the newest `limit` that really verify. Signatures are
     checked lazily, newest first, so a long feed costs only what is shown.
     Replies are skipped unless the account has nothing else. */
  async function selectEvents(events, pubkey, o) {
    const posts = events
      .filter(ev => wellFormed(ev) && ev.pubkey === pubkey && o.kinds.includes(ev.kind))
      .sort(newest);
    const memo = new Map();
    const take = async list => {
      const out = [], seen = new Set();
      for (const ev of list) {
        if (out.length >= o.limit) break;
        const key = ev.kind === 30023 ? `a:${tagValue(ev, 'd')}` : ev.id;
        if (seen.has(key)) continue;
        if (!memo.has(ev)) memo.set(ev, await verifyEvent(ev));
        if (memo.get(ev)) { seen.add(key); out.push(ev); }
      }
      return out;
    };
    let items = await take(o.skipReplies ? posts.filter(ev => !isReply(ev)) : posts);
    if (!items.length && o.skipReplies) items = await take(posts); // an account that only replies
    return items;
  }

  async function newestProfile(events, pubkey) {
    const list = events.filter(ev => ev.kind === 0 && ev.pubkey === pubkey && wellFormed(ev)).sort(newest);
    for (const ev of list) if (await verifyEvent(ev)) return ev;
    return null;
  }

  /* Round one: the account's own profile and recent posts. Widgets that ask
     the same question at the same moment share one answer. */
  const inflight = new Map();
  function loadAccount(pubkey, relays, o) {
    const key = [pubkey, relays.join(), o.kinds.join(), o.limit, o.skipReplies].join('|');
    if (inflight.has(key)) return inflight.get(key);
    const job = (async () => {
      const res = await query(relays, [
        { kinds: [0], authors: [pubkey], limit: 3 },
        { kinds: o.kinds, authors: [pubkey], limit: Math.min(100, Math.max(30, o.limit * 6)) }
      ]);
      return {
        profile: await newestProfile(res.events, pubkey),
        items: await selectEvents(res.events, pubkey, o),
        answered: res.answered,
        total: res.total
      };
    })();
    inflight.set(key, job);
    const clear = () => inflight.delete(key);
    job.then(clear, clear);
    return job;
  }

  /* what the shown notes point at, so round two can be one query */
  function collectRefs(items, extras, wantQuotes) {
    const pubkeys = new Set(), refs = new Map();
    for (const ev of items) {
      if (ev.kind !== 1) continue;
      for (const m of ev.content.slice(0, CFG.hardCap).matchAll(REF_SCAN)) {
        const ref = decodeRef(m[0]);
        if (!ref) continue;
        if (ref.type === 'npub' || ref.type === 'nprofile') pubkeys.add(ref.pubkey);
        else if (wantQuotes) refs.set(refKey(ref), ref);
      }
    }
    for (const key of Object.keys(extras.refs)) refs.delete(key);
    for (const pk of Object.keys(extras.profiles)) pubkeys.delete(pk);
    return { pubkeys: [...pubkeys].slice(0, CFG.maxMentions), refs: [...refs.values()].slice(0, CFG.maxRefs) };
  }

  /* Round two: profiles of people mentioned and the notes/articles quoted.
     Verified like everything else; an unresolved reference just stays a link.
     Returns true when anything was added. */
  async function loadExtras(items, extras, relays, wantQuotes) {
    const { pubkeys, refs } = collectRefs(items, extras, wantQuotes);
    if (!pubkeys.length && !refs.length) return false;
    const filters = [];
    if (pubkeys.length) filters.push({ kinds: [0], authors: pubkeys });
    const ids = refs.filter(r => r.type === 'note' || r.type === 'nevent').map(r => r.id);
    if (ids.length) filters.push({ ids });
    for (const r of refs.filter(x => x.type === 'naddr')) {
      filters.push({ kinds: [r.kind], authors: [r.pubkey], '#d': [r.identifier], limit: 1 });
    }
    const res = await query(relays, filters);
    const wantedIds = new Set(ids);
    const wantedAddr = new Set(refs.filter(r => r.type === 'naddr').map(refKey));
    const wantedProfiles = new Set(pubkeys);
    const authors = new Set();
    let added = false;
    const keep = async (bucket, key, ev) => {
      const old = bucket[key];
      if (old && old.created_at >= ev.created_at) return false;
      if (!(await verifyEvent(ev))) return false;
      bucket[key] = ev;
      return true;
    };
    for (const ev of res.events) {
      if (!wellFormed(ev)) continue;
      if (ev.kind === 0 && wantedProfiles.has(ev.pubkey)) {
        if (await keep(extras.profiles, ev.pubkey, ev)) added = true;
        continue;
      }
      let key = null;
      if (wantedIds.has(ev.id)) key = ev.id;
      else if (ev.kind >= 30000 && ev.kind < 40000) {
        const k = `${ev.kind}:${ev.pubkey}:${tagValue(ev, 'd')}`;
        if (wantedAddr.has(k)) key = k;
      }
      if (key && await keep(extras.refs, key, ev)) { authors.add(ev.pubkey); added = true; }
    }
    // authors of quoted notes, so the quote card can say who wrote it
    const missing = [...authors].filter(pk => !extras.profiles[pk]).slice(0, CFG.maxMentions);
    if (missing.length) {
      const second = await query(relays, [{ kinds: [0], authors: missing }]);
      for (const ev of second.events) {
        if (ev.kind === 0 && missing.includes(ev.pubkey) && wellFormed(ev)) await keep(extras.profiles, ev.pubkey, ev);
      }
    }
    return added;
  }

  /* ------------------------------------------------- 7. note parsing ------ */

  const IMAGE_EXT = /\.(jpe?g|png|gif|webp|avif|svg)$/i;
  const VIDEO_EXT = /\.(mp4|webm|mov)$/i;
  const BECH = '[023456789acdefghjklmnpqrstuvwxyz]';
  const REF_SCAN = new RegExp(`(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1${BECH}+`, 'gi');
  const TOKEN = new RegExp(
    `(https?:\\/\\/[^\\s<>"]+)|((?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1${BECH}+)|(#[\\p{L}\\p{N}_]{1,50})`,
    'giu'
  );

  /* http(s) only, no embedded credentials (https://bank.com@evil.example) */
  const safeUrl = value => {
    try {
      const u = new URL(value);
      if ((u.protocol !== 'https:' && u.protocol !== 'http:') || u.username || u.password) return null;
      return u;
    } catch (e) {
      return null;
    }
  };

  const mediaKind = u => {
    if (u.protocol !== 'https:') return null; // http media would be blocked as mixed content anyway
    if (IMAGE_EXT.test(u.pathname)) return 'image';
    if (VIDEO_EXT.test(u.pathname)) return 'video';
    return null;
  };

  const trimUrl = raw => {
    let url = raw;
    for (;;) {
      const last = url[url.length - 1];
      if (/[.,;:!?'"*_]/.test(last)) url = url.slice(0, -1);
      else if (last === ')' && (url.match(/\(/g) || []).length < (url.match(/\)/g) || []).length) url = url.slice(0, -1);
      else break;
    }
    return url;
  };

  const shownUrl = u => {
    const text = (u.host + u.pathname + u.search).replace(/\/$/, '');
    return text.length > 46 ? `${text.slice(0, 45)}…` : text;
  };

  const parseImeta = ev => {
    const out = new Map();
    for (const tag of ev.tags) {
      if (tag[0] !== 'imeta') continue;
      const info = {};
      for (const part of tag.slice(1)) {
        const space = part.indexOf(' ');
        if (space < 1) continue;
        info[part.slice(0, space)] = part.slice(space + 1);
      }
      const u = info.url && safeUrl(info.url);
      if (!u) continue;
      const dim = /^(\d{1,5})x(\d{1,5})$/.exec(info.dim || '');
      out.set(u.href, {
        w: dim ? +dim[1] : 0,
        h: dim ? +dim[2] : 0,
        alt: str(info.alt, 300),
        mime: str(info.m, 60)
      });
    }
    return out;
  };

  /* Split a note into inline segments, extracted media and quoted references.
     With withMedia false, media URLs stay in the text as ordinary links. */
  const analyse = (ev, withMedia = true) => {
    const content = ev.content.slice(0, CFG.hardCap);
    const imeta = parseImeta(ev);
    const segs = [], media = [], quotes = [];
    const seenMedia = new Set();
    let at = 0;

    const text = s => { if (s) segs.push({ t: 'text', v: s }); };
    TOKEN.lastIndex = 0;
    let m;
    while ((m = TOKEN.exec(content)) !== null) {
      let raw = m[0];
      const start = m.index;
      if (m[1]) {
        raw = trimUrl(raw);
        const u = safeUrl(raw);
        text(content.slice(at, start));
        at = start + raw.length;
        TOKEN.lastIndex = at;
        if (!u) { text(raw); continue; }
        const kind = withMedia ? mediaKind(u) : null;
        if (kind && media.length < CFG.maxMedia) {
          if (!seenMedia.has(u.href)) {
            seenMedia.add(u.href);
            media.push({ kind, url: u.href, ...(imeta.get(u.href) || {}) });
          }
        } else {
          segs.push({ t: 'url', href: u.href, v: shownUrl(u) });
        }
      } else if (m[2]) {
        const ref = decodeRef(raw);
        text(content.slice(at, start));
        at = start + raw.length;
        const bare = raw.replace(/^nostr:/i, '');
        if (!ref) { text(raw); continue; }
        if (ref.type === 'npub' || ref.type === 'nprofile') {
          segs.push({ t: 'mention', ref, raw: bare });
        } else if (quotes.length < CFG.maxQuotes) {
          quotes.push({ ref, raw: bare });
        } else {
          segs.push({ t: 'url', href: JUMP + bare, v: bare.slice(0, 16) + '…' });
        }
      } else {
        const prev = start > 0 ? content[start - 1] : ' ';
        text(content.slice(at, start));
        at = start + raw.length;
        if (/[\p{L}\p{N}_&/#]/u.test(prev) || /^#\d+$/.test(raw)) text(raw);
        else segs.push({ t: 'tag', v: raw });
      }
    }
    text(content.slice(at));

    // media that only lives in imeta tags
    if (withMedia) {
      for (const [href, info] of imeta) {
        if (media.length >= CFG.maxMedia || seenMedia.has(href)) continue;
        const u = new URL(href);
        const kind = mediaKind(u) || (info.mime.startsWith('image/') ? 'image' : info.mime.startsWith('video/') ? 'video' : null);
        if (kind && u.protocol === 'https:') media.push({ kind, url: href, ...info });
      }
    }

    // merge adjacent text, tidy the blank lines media and quotes leave behind
    const merged = [];
    for (const s of segs) {
      const last = merged[merged.length - 1];
      if (s.t === 'text' && last && last.t === 'text') last.v += s.v;
      else merged.push({ ...s });
    }
    for (const s of merged) if (s.t === 'text') s.v = s.v.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n');
    while (merged.length && merged[0].t === 'text' && !merged[0].v.trim()) merged.shift();
    if (merged.length && merged[0].t === 'text') merged[0].v = merged[0].v.replace(/^\s+/, '');
    while (merged.length && merged[merged.length - 1].t === 'text' && !merged[merged.length - 1].v.trim()) merged.pop();
    if (merged.length && merged[merged.length - 1].t === 'text') {
      merged[merged.length - 1].v = merged[merged.length - 1].v.replace(/\s+$/, '');
    }
    return { segs: merged, media, quotes };
  };

  /* ------------------------------------------------------- 8a. styles ----- */
  /* Every selector is wrapped in :where(), so it has zero specificity and any
     rule a site writes for these classes wins without !important. The look
     comes from currentColor and inherited fonts unless a --nl-* variable says
     otherwise. */

  const CSS = `
:where(.nl){
  --_gap:var(--nl-gap,1rem);
  --_radius:var(--nl-radius,10px);
  --_mradius:var(--nl-media-radius,var(--nl-radius,10px));
  --_av:var(--nl-avatar-size,48px);
  --_accent:var(--nl-accent,currentColor);
  --_muted:var(--nl-muted,color-mix(in srgb,currentColor 62%,transparent));
  --_border:var(--nl-border,color-mix(in srgb,currentColor 18%,transparent));
  --_card:var(--nl-card-bg,color-mix(in srgb,currentColor 5%,transparent));
  --_skel:color-mix(in srgb,currentColor 12%,transparent);
  --_link:var(--nl-link,inherit);
  --_hfont:var(--nl-heading-font,inherit);
  display:block;box-sizing:border-box;min-width:0;max-width:100%;
  color:var(--nl-fg,inherit);background:var(--nl-bg,transparent);padding:var(--nl-pad,0);
  font-family:var(--nl-font,inherit);line-height:var(--nl-leading,1.5);overflow-wrap:anywhere;
}
@supports not (color:color-mix(in srgb,red,blue)){
  :where(.nl){--_muted:var(--nl-muted,currentColor);--_border:var(--nl-border,rgba(128,128,128,.35));--_card:var(--nl-card-bg,rgba(128,128,128,.08));--_skel:rgba(128,128,128,.2)}
}
:where(.nl) *,:where(.nl) *::before,:where(.nl) *::after{box-sizing:border-box}
:where(.nl) [hidden]{display:none!important}
:where(.nl) :where(p,ol,ul,h1,h2,h3,h4,h5,h6,figure){margin:0;padding:0}
:where(.nl) :where(a){color:inherit}
:where(.nl) :where(button){font:inherit;color:inherit;margin:0}
:where(.nl) :focus-visible{outline:2px solid var(--_accent);outline-offset:2px}
:where(.nl__live){position:absolute;width:1px;height:1px;margin:-1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}

/* header */
:where(.nl__head){display:grid;grid-template-columns:var(--_av) minmax(0,1fr);gap:calc(var(--_gap)*.75);align-items:center;margin-bottom:var(--_gap)}
:where(.nl__avatar){position:relative;display:grid;place-items:center;width:var(--_av);height:var(--_av);border-radius:var(--nl-avatar-radius,50%);background:var(--_card);border:1px solid var(--_border);overflow:hidden;flex:none}
:where(.nl__letter){font-family:var(--_hfont);font-weight:700;font-size:calc(var(--_av)*.42);line-height:1;color:var(--_muted)}
:where(.nl__avatar img){position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
:where(.nl__who){min-width:0}
:where(.nl__name){font-family:var(--_hfont);font-weight:700;font-size:1.2em;line-height:1.2}
:where(.nl__nip05){font-size:.85em;color:var(--_muted)}
:where(.nl__about){margin-top:.25em;font-size:.9em;color:var(--_muted);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
:where(.nl--compact,.nl--ticker){--_av:calc(var(--nl-avatar-size,48px)*.62)}
:where(.nl--compact .nl__head,.nl--ticker .nl__head){margin-bottom:calc(var(--_gap)*.6)}
:where(.nl--compact .nl__name,.nl--ticker .nl__name){font-size:1em}

/* list of notes */
:where(.nl__list){list-style:none;display:flex;flex-direction:column}
:where(.nl--list .nl__item,.nl--compact .nl__item){padding:calc(var(--_gap)*.8) 0;border-top:1px solid var(--_border)}
:where(.nl--list .nl__item:last-child,.nl--compact .nl__item:last-child){border-bottom:1px solid var(--_border)}
:where(.nl--cards .nl__list){display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,19rem),1fr));gap:var(--_gap)}
:where(.nl--cards .nl__item){min-width:0;padding:var(--nl-card-pad,calc(var(--_gap)*.95));background:var(--_card);border:1px solid var(--_border);border-radius:var(--_radius)}
:where(.nl__meta){display:flex;flex-wrap:wrap;align-items:center;gap:.2em .7em;margin-bottom:.35em;font-size:.82em;color:var(--_muted)}
:where(.nl__time){color:inherit;text-decoration:none}
:where(a.nl__time:hover){text-decoration:underline;text-underline-offset:.2em}
:where(.nl__kind){padding:.05em .55em;border:1px solid var(--_border);border-radius:99px;font-size:.88em;letter-spacing:.04em;text-transform:uppercase}
:where(.nl__body){min-width:0}
:where(.nl__text){white-space:pre-wrap}
:where(.nl__text a){color:var(--_link);text-decoration:underline;text-decoration-thickness:1px;text-underline-offset:.18em}
:where(.nl__text a:hover){text-decoration-thickness:2px}
:where(.nl__mention){font-weight:600}
:where(.nl__tag){text-decoration-style:dotted!important}
:where(.nl__more){appearance:none;background:none;border:0;padding:.3em 0 0;cursor:pointer;font-size:.88em;font-weight:600;color:var(--_link);text-decoration:underline;text-underline-offset:.18em}

/* media */
:where(.nl__medialist){display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,11rem),1fr));gap:.5em;margin-top:.7em;align-items:start}
:where(.nl__medialist:has(> :only-child)){grid-template-columns:minmax(0,34rem)}
:where(.nl__media){position:relative;width:100%;border-radius:var(--_mradius);background:var(--_card);overflow:hidden}
:where(.nl__media.is-tall){max-width:min(100%,20rem)}
:where(.nl__imglink){display:block;width:100%;height:100%}
:where(.nl__media img,.nl__media video){display:block;width:100%;height:100%;object-fit:contain}
:where(.nl__media video){background:#000}
:where(.nl__media.is-broken){display:grid;place-items:center;padding:.6em;aspect-ratio:auto!important}
:where(.nl__media.is-broken .nl__imglink){font-size:.82em;color:var(--_muted);text-align:center}

/* quoted notes and articles */
:where(.nl__quote,.nl__article){display:block;margin-top:.7em;padding:.65em .8em;border:1px solid var(--_border);border-radius:var(--_radius);background:var(--_card);color:inherit;text-decoration:none}
:where(.nl__quote:hover,.nl__article:hover){border-color:var(--_accent)}
:where(.nl__quote > span,.nl__article > span){display:block}
:where(.nl__qtop){display:flex!important;flex-wrap:wrap;justify-content:space-between;gap:0 .8em;font-size:.82em;color:var(--_muted)}
:where(.nl__qname){font-weight:600;color:inherit}
:where(.nl__qtitle,.nl__atitle){margin:.15em 0 0;font-family:var(--_hfont);font-weight:700;font-size:1.08em;line-height:1.25}
:where(.nl__qbody,.nl__asum){margin-top:.25em;font-size:.92em;color:var(--_muted);line-height:1.45}
:where(.nl__qbody){display:-webkit-box!important;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
:where(.nl__article){margin-top:0}
:where(.nl__article .nl__media){margin-bottom:.7em}
:where(.nl__atags){display:flex!important;flex-wrap:wrap;gap:.3em .5em;margin-top:.5em;font-size:.8em;color:var(--_muted)}
:where(.nl__quote.is-bare){width:max-content;max-width:100%}
:where(.nl__quote.is-bare,.nl__aread){display:flex!important;align-items:center;gap:.5em;font-size:.88em;font-weight:600}
:where(.nl__aread){margin-top:.6em;color:var(--_link)}
:where(.nl--cards .nl__article){padding:0;border:0;background:none;border-radius:0}
:where(.nl .arrow){flex:none}

/* status, buttons, follow */
:where(.nl__status){display:flex;flex-wrap:wrap;align-items:center;gap:.5em .9em;margin-top:var(--_gap);color:var(--_muted)}
:where(.nl__btn){display:inline-flex;align-items:center;justify-content:center;gap:.45em;appearance:none;padding:.5em .95em;border:1px solid var(--nl-accent,var(--_border));border-radius:var(--_radius);background:transparent;color:inherit;font-size:.9em;font-weight:600;line-height:1.2;text-decoration:none;cursor:pointer}
:where(.nl__btn:hover){background:var(--_card);border-color:var(--_accent)}
:where(.nl__btn--primary){background:var(--nl-accent,transparent);color:var(--nl-accent-fg,inherit);border-color:var(--_accent)}
:where(.nl__btn--primary:hover){background:var(--nl-accent,var(--_card));filter:brightness(1.08)}
:where(.nl__foot){margin-top:calc(var(--_gap)*1.1)}
:where(.nl__follow){display:flex;flex-wrap:wrap;gap:.5em}
:where(.nl__openin){display:flex;flex-wrap:wrap;align-items:center;gap:.2em 1.1em;margin-top:.7em;font-size:.9em}
:where(.nl__openin a,.nl__openin button){appearance:none;background:none;border:0;padding:.3em 0;cursor:pointer;color:var(--_link);font-weight:600;text-decoration:underline;text-underline-offset:.2em}

/* skeletons */
:where(.nl__skel){display:block;height:.85em;border-radius:.4em;background:var(--_skel);animation:nl-pulse 1.4s ease-in-out infinite}
:where(.nl__skel--avatar){width:var(--_av);height:var(--_av);border-radius:var(--nl-avatar-radius,50%)}
:where(.nl__skel--media){height:auto;aspect-ratio:16/9;border-radius:var(--_mradius);margin-top:.7em}
:where(.nl__item.is-skel){display:flex;flex-direction:column;gap:.55em;pointer-events:none}
@keyframes nl-pulse{50%{opacity:.45}}

/* compact and ticker */
:where(.nl__tlink){display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:inherit;text-decoration:none}
:where(a.nl__tlink:hover){text-decoration:underline;text-underline-offset:.2em}
:where(.nl--ticker .nl__row){display:flex;align-items:center;gap:.5em;min-height:2.4em;border-block:1px solid var(--_border)}
:where(.nl--ticker .nl__list){flex:1;min-width:0}
:where(.nl--ticker .nl__item){display:flex;align-items:center;gap:.7em;min-width:0}
:where(.nl--ticker .nl__item .nl__meta){margin:0;flex:none}
:where(.nl--ticker .nl__tlink){display:block;min-width:0;flex:1;white-space:nowrap;text-overflow:ellipsis}
:where(.nl--ticker .nl__item.is-in){animation:nl-in .35s ease}
:where(.nl__tctl){display:flex;flex:none}
:where(.nl__tbtn){appearance:none;display:grid;place-items:center;width:2.2em;height:2.2em;padding:0;border:0;border-radius:var(--_radius);background:none;cursor:pointer;color:var(--_muted)}
:where(.nl__tbtn:hover){color:inherit;background:var(--_card)}
@keyframes nl-in{from{opacity:0;transform:translateY(.45em)}}
@media (prefers-reduced-motion:reduce){
  :where(.nl__skel){animation:none}
  :where(.nl--ticker .nl__item.is-in){animation:none}
}
@media (max-width:480px){
  :where(.nl--ticker .nl__item .nl__meta){display:none}
}
`;

  const injectStyles = () => {
    if (typeof document === 'undefined' || document.getElementById('nostr-live-style')) return;
    if (document.documentElement.hasAttribute('data-nostr-live-no-style')) return;
    const style = document.createElement('style');
    style.id = 'nostr-live-style';
    const nonce = ownScript && (ownScript.nonce || ownScript.getAttribute('nonce'));
    if (nonce) style.setAttribute('nonce', nonce);
    style.textContent = CSS;
    (document.head || document.documentElement).prepend(style);
  };

  /* --------------------------------------------------------- 8b. DOM ----- */

  // classes that start with nl__ also become the element's `part`, so a site
  // that wraps the widget in a shadow root can reach them with ::part()
  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) {
      node.className = cls;
      if (cls.startsWith('nl__')) node.setAttribute('part', cls.split(' ')[0].slice(4));
    }
    if (text != null) node.textContent = text;
    return node;
  };

  const extLink = (href, cls, text) => {
    const a = el('a', cls, text);
    a.href = href;
    a.target = '_blank';
    a.rel = REL;
    return a;
  };

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const icon = (paths, size = 12, cls = '') => {
    const svg = document.createElementNS(SVG_NS, 'svg');
    for (const [k, v] of Object.entries({
      viewBox: '0 0 16 16', width: size, height: size, 'aria-hidden': 'true', focusable: 'false', fill: 'none',
      stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round'
    })) svg.setAttribute(k, v);
    if (cls) svg.setAttribute('class', cls);
    for (const d of paths) {
      const p = document.createElementNS(SVG_NS, 'path');
      p.setAttribute('d', d);
      svg.appendChild(p);
    }
    return svg;
  };
  const arrow = () => icon(['M4 12L12 4', 'M6 4h6v6'], 12, 'arrow');

  const skel = (cls, width) => {
    const s = el('span', 'nl__skel' + (cls ? ' ' + cls : ''));
    if (width) s.style.width = width;
    return s;
  };

  const mediaBox = (info, fallbackRatio) => {
    const box = el('div', 'nl__media');
    const ok = info.w > 0 && info.h > 0;
    box.style.aspectRatio = ok ? `${info.w} / ${info.h}` : fallbackRatio;
    if (ok && info.h / info.w > 1.25) box.classList.add('is-tall');
    return box;
  };

  const imageNode = (info, altFallback) => {
    const box = mediaBox(info, '16 / 10');
    const link = extLink(info.url, 'nl__imglink');
    const img = document.createElement('img');
    img.src = info.url;
    img.alt = info.alt || altFallback;
    img.loading = 'lazy';
    img.decoding = 'async';
    img.referrerPolicy = 'no-referrer';
    if (info.w > 0 && info.h > 0) { img.width = info.w; img.height = info.h; }
    img.addEventListener('error', () => {
      box.classList.add('is-broken');
      img.remove();
      link.textContent = shownUrl(new URL(info.url));
    });
    link.appendChild(img);
    box.appendChild(link);
    return box;
  };

  const videoNode = (info, label) => {
    const box = mediaBox(info, '16 / 9');
    const video = document.createElement('video');
    video.controls = true;
    video.preload = 'none';
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.src = info.url;
    video.setAttribute('aria-label', info.alt || label);
    video.referrerPolicy = 'no-referrer';
    box.appendChild(video);
    return box;
  };

  const copyText = async text => {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      const area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.cssText = 'position:fixed;top:0;left:0;opacity:0';
      document.body.appendChild(area);
      area.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
      area.remove();
      return ok;
    }
  };

  /* ----------------------------------------------------- 8c. options ----- */

  const instances = new WeakMap();
  let uid = 0;

  const truthy = (v, dflt) => {
    if (v === undefined || v === null || v === '') return dflt;
    if (typeof v === 'string') return !/^(false|0|no|off)$/i.test(v.trim());
    return !!v;
  };

  const fromDataset = host => {
    const d = host.dataset, out = {};
    if (d.nprofile) out.nprofile = d.nprofile;
    if (d.relays) out.relays = d.relays.split(',').map(s => s.trim()).filter(Boolean);
    if (d.limit) out.limit = +d.limit;
    if (d.layout) out.layout = d.layout;
    if (d.lang) out.lang = d.lang;
    if (d.follow !== undefined) out.follow = d.follow;
    if (d.header !== undefined) out.header = d.header;
    if (d.media !== undefined) out.media = d.media;
    if (d.loadOn) out.loadOn = d.loadOn;
    if (d.onlyKinds) out.onlyKinds = d.onlyKinds.split(',').map(s => +s.trim());
    if (d.skipReplies !== undefined) out.skipReplies = d.skipReplies;
    if (d.emptyText) out.emptyText = d.emptyText;
    if (d.class) out.className = d.class;
    return out;
  };

  const normalise = (raw, host) => {
    const o = {};
    const ref = decodeRef(String(raw.nprofile || '').trim());
    o.valid = !!ref && ref.type === 'nprofile';
    o.pubkey = o.valid ? ref.pubkey : '';
    o.nprofile = o.valid ? String(raw.nprofile).trim().replace(/^nostr:/i, '') : '';
    const listed = Array.isArray(raw.relays) && raw.relays.length ? raw.relays : CFG.relays;
    o.relays = [...new Set([...(o.valid ? ref.relays : []), ...listed].map(cleanRelay).filter(Boolean))].slice(0, CFG.maxRelays);
    o.limit = Math.min(CFG.maxLimit, Math.max(1, Math.floor(+raw.limit) || 5));
    o.layout = ['list', 'cards', 'compact', 'ticker'].includes(raw.layout) ? raw.layout : 'list';
    o.header = truthy(raw.header, true);
    o.follow = truthy(raw.follow, true);
    o.media = truthy(raw.media, true);
    o.skipReplies = truthy(raw.skipReplies, true);
    o.loadOn = raw.loadOn === 'now' ? 'now' : 'visible';
    const kinds = (Array.isArray(raw.onlyKinds) ? raw.onlyKinds : KINDS_SHOWN).filter(k => KINDS_SHOWN.includes(k));
    o.kinds = kinds.length ? [...new Set(kinds)].sort((a, b) => a - b) : KINDS_SHOWN.slice();
    o.emptyText = typeof raw.emptyText === 'string' ? raw.emptyText : '';
    o.className = typeof raw.className === 'string' ? raw.className : '';
    o.headingLevel = Math.min(5, Math.max(1, Math.floor(+raw.headingLevel) || 3));
    // how many notes are drawn: the compact layout is a teaser, the ticker rotates through them
    o.shown = o.layout === 'compact' ? Math.min(o.limit, 3) : o.limit;

    const asked = String(raw.lang || 'auto').trim();
    const holder = host.closest ? host.closest('[lang]') : null;
    const full = asked && asked.toLowerCase() !== 'auto' ? asked
      : (holder && holder.getAttribute('lang')) || document.documentElement.lang || 'en';
    o.lang = String(full).toLowerCase().split(/[-_]/)[0] || 'en';
    try { o.locale = Intl.getCanonicalLocales(String(full).replace('_', '-'))[0]; } catch (e) { o.locale = o.lang; }

    // `strings` is { sk: {...}, pt: {...} } or, as a shorthand, one flat { key: text } for this language
    const custom = raw.strings && typeof raw.strings === 'object' ? raw.strings : {};
    const flat = Object.values(custom).some(v => typeof v === 'string');
    const mine = flat ? custom : (custom[o.lang] || {});
    const dict = Object.assign({}, STRINGS.en, STRINGS[o.lang] || {}, mine);
    o.t = (key, vars) => String(dict[key] != null ? dict[key] : key)
      .replace(/\{(\w+)\}/g, (_, k) => (vars && vars[k] != null ? vars[k] : ''));
    return o;
  };

  /* ---------------------------------------------------- 8d. the widget -- */

  function mount(host, options) {
    if (!host || host.nodeType !== 1) throw new TypeError('NostrLive.mount: first argument must be an element');
    const previous = instances.get(host);
    if (previous) previous.destroy();
    injectStyles();

    const o = normalise(Object.assign({}, fromDataset(host), options || {}), host);
    const t = o.t;
    const id = ++uid;
    const hint = o.relays[0] || '';
    const originalChildren = [...host.childNodes];
    const addedClasses = ['nl', `nl--${o.layout}`, ...(o.className ? o.className.split(/\s+/).filter(Boolean) : [])];
    const hadRole = host.getAttribute('role'), hadLabel = host.getAttribute('aria-label');

    const S = { profile: null, items: [], extras: { profiles: {}, refs: {} }, shown: false, key: '', fetchedAt: 0 };
    const mentionSpots = [];   // { a, pubkey }
    const quoteSpots = [];     // { card, ref }
    const teaserSpots = [];    // { node, ev }
    let destroyed = false, runId = 0, observer = null, tickTimer = 0, tickIndex = 0, signature = '';
    const cleanups = [];

    const profileOf = pk => {
      if (pk === o.pubkey && S.profile) return parseProfile(S.profile);
      const ev = S.extras.profiles[pk];
      return ev ? parseProfile(ev) : null;
    };
    const nameOf = pk => {
      const p = profileOf(pk);
      return (p && p.name) || shortNpub(pk);
    };

    /* ----- time ----- */
    const rtf = (() => {
      try { return new Intl.RelativeTimeFormat(o.locale, { numeric: 'auto' }); } catch (e) { return null; }
    })();
    const when = ts => {
      const diff = Math.min(0, ts - Math.floor(Date.now() / 1000));
      const abs = -diff;
      if (!rtf) return new Date(ts * 1000).toLocaleDateString();
      for (const [unit, secs] of [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]]) {
        if (abs >= secs) return rtf.format(Math.round(diff / secs), unit);
      }
      return rtf.format(0, 'second');
    };
    const absolute = ts => {
      try { return new Date(ts * 1000).toLocaleString(o.locale, { dateStyle: 'medium', timeStyle: 'short' }); }
      catch (e) { return new Date(ts * 1000).toISOString(); }
    };
    const timeNode = ts => {
      const time = el('time', null, when(ts));
      time.dateTime = new Date(ts * 1000).toISOString();
      time.title = absolute(ts);
      return time;
    };

    /* ----- skeleton of the page ----- */
    host.replaceChildren();
    host.classList.add(...addedClasses);
    host.setAttribute('role', 'region');
    host.setAttribute('aria-label', t('region', { name: 'Nostr' }));
    const live = el('p', 'nl__live');
    live.setAttribute('role', 'status');
    const head = el('header', 'nl__head');
    const body = el('div', 'nl__content');
    const list = el('ol', 'nl__list');
    const status = el('p', 'nl__status');
    status.hidden = true;
    const foot = el('div', 'nl__foot');
    host.append(live, head, body, status, foot);

    const setState = (state, message) => {
      host.dataset.nlState = state;
      host.classList.toggle('is-loading', state === 'loading');
      host.classList.toggle('is-empty', state === 'empty');
      host.classList.toggle('is-error', state === 'error');
      host.setAttribute('aria-busy', String(state === 'loading'));
      live.textContent = message || '';
    };
    const emit = (name, detail) => {
      host.dispatchEvent(new CustomEvent(name, { bubbles: true, detail: Object.assign({ pubkey: o.pubkey }, detail) }));
    };

    /* ----- header ----- */
    function drawHead() {
      head.replaceChildren();
      head.hidden = !o.header || !o.valid;
      if (head.hidden) return;
      const p = profileOf(o.pubkey);
      if (!p && !S.shown) {
        head.append(skel('nl__skel--avatar'));
        const who = el('div', 'nl__who');
        who.style.cssText = 'display:grid;gap:.5em';
        who.append(skel('', '9em'), skel('', '6em'));
        head.append(who);
        return;
      }
      const name = (p && p.name) || shortNpub(o.pubkey);
      const avatar = el('span', 'nl__avatar');
      avatar.appendChild(el('span', 'nl__letter', ([...name][0] || '·').toUpperCase()));
      const pic = p && p.picture && safeUrl(p.picture);
      if (pic && pic.protocol === 'https:') {
        const img = document.createElement('img');
        img.alt = '';
        img.width = 96;
        img.height = 96;
        img.loading = 'lazy';
        img.decoding = 'async';
        img.referrerPolicy = 'no-referrer';
        img.addEventListener('error', () => img.remove());
        img.src = pic.href;
        avatar.appendChild(img);
      }
      head.appendChild(avatar);
      const who = el('div', 'nl__who');
      who.appendChild(el('h' + o.headingLevel, 'nl__name', name));
      const slim = o.layout === 'compact' || o.layout === 'ticker';
      if (p && p.nip05 && !slim) who.appendChild(el('p', 'nl__nip05', p.nip05));
      if (p && p.about && !slim) who.appendChild(el('p', 'nl__about', p.about));
      head.appendChild(who);
      host.setAttribute('aria-label', t('region', { name }));
    }

    /* ----- inline text ----- */
    function mentionNode(seg) {
      const a = extLink(JUMP + seg.raw, 'nl__mention', `@${nameOf(seg.ref.pubkey)}`);
      mentionSpots.push({ a, pubkey: seg.ref.pubkey });
      return a;
    }

    /* Draws segments into `parent` until `budget` characters are spent.
       Returns true when it stopped early. A link is never cut in half. */
    function drawSegments(parent, segs, budget) {
      let used = 0;
      for (const seg of segs) {
        if (seg.t === 'text') {
          const room = budget - used;
          if (seg.v.length <= room) {
            parent.appendChild(document.createTextNode(seg.v));
            used += seg.v.length;
          } else {
            let cut = seg.v.lastIndexOf(' ', room);
            if (cut < room * 0.6) cut = room;
            parent.appendChild(document.createTextNode(seg.v.slice(0, cut).replace(/\s+$/, '') + '…'));
            return true;
          }
          continue;
        }
        const cost = seg.t === 'mention' ? 14 : seg.v.length;
        if (used + cost > budget && used > 0) {
          parent.appendChild(document.createTextNode('…'));
          return true;
        }
        used += cost;
        if (seg.t === 'url') parent.appendChild(extLink(seg.href, 'nl__link', seg.v));
        else if (seg.t === 'mention') parent.appendChild(mentionNode(seg));
        else parent.appendChild(extLink(`https://primal.net/search/${encodeURIComponent(seg.v)}`, 'nl__tag', seg.v));
      }
      return false;
    }

    /* ----- quote cards ----- */
    const snippet = (text, max) => {
      const plain = text
        .replace(/(?:nostr:)?(?:npub|nprofile|note|nevent|naddr)1[023456789acdefghjklmnpqrstuvwxyz]+/gi, '')
        .replace(/https?:\/\/\S+/g, '')
        .replace(/\s+/g, ' ')
        .trim();
      return plain.length > max ? `${plain.slice(0, max - 1)}…` : plain;
    };

    function fillQuote(spot) {
      const { card, ref } = spot;
      const ev = S.extras.refs[refKey(ref)];
      card.replaceChildren();
      if (!ev) {
        card.classList.add('is-bare');
        card.appendChild(el('span', 'nl__qlabel', ref.type === 'naddr' ? t('quotedArticle') : t('quotedNote')));
        card.appendChild(arrow());
        return;
      }
      card.classList.remove('is-bare');
      const top = el('span', 'nl__qtop');
      top.append(el('span', 'nl__qname', nameOf(ev.pubkey)), el('span', 'nl__qtime', when(ev.created_at)));
      card.appendChild(top);
      if (ev.kind === 30023) {
        card.appendChild(el('span', 'nl__qtitle', str(tagValue(ev, 'title'), 160) || tagValue(ev, 'd')));
        const sum = snippet(str(tagValue(ev, 'summary'), 400) || ev.content.replace(/[#*_>`[\]()!]/g, ''), 160);
        if (sum) card.appendChild(el('span', 'nl__qbody', sum));
      } else {
        card.appendChild(el('span', 'nl__qbody', snippet(ev.content, 200) || t('quotedNote')));
      }
    }

    function quoteNode(q) {
      const card = extLink(JUMP + q.raw, 'nl__quote');
      card.setAttribute('dir', 'auto');
      const spot = { card, ref: q.ref };
      quoteSpots.push(spot);
      fillQuote(spot);
      return card;
    }

    /* ----- one note ----- */
    const noteTarget = ev => (ev.kind === 30023
      ? JUMP + encodeNaddr(30023, ev.pubkey, tagValue(ev, 'd'), hint)
      : JUMP + encodeNevent(ev.id, ev.pubkey, hint, ev.kind));

    /* plain one-line text of a note, for the compact and ticker layouts */
    function teaserText(ev) {
      if (ev.kind === 30023) return str(tagValue(ev, 'title'), 200) || tagValue(ev, 'd') || t('article');
      const { segs, media } = analyse(ev, o.media);
      const text = segs.map(s => (s.t === 'mention' ? `@${nameOf(s.ref.pubkey)}` : s.v)).join('')
        .replace(/\s+/g, ' ').trim();
      if (!text) return media.length ? t('imageAlt') : t('openNote');
      return text.length > 180 ? `${text.slice(0, 179)}…` : text;
    }

    function teaserNode(ev) {
      const li = el('li', 'nl__item nl__teaser');
      const meta = el('div', 'nl__meta');
      meta.appendChild(timeNode(ev.created_at));
      if (ev.kind === 30023) meta.appendChild(el('span', 'nl__kind', t('article')));
      const link = extLink(noteTarget(ev), 'nl__tlink', teaserText(ev));
      link.setAttribute('dir', 'auto');
      teaserSpots.push({ link, ev });
      li.append(meta, link);
      return li;
    }

    function articleNode(ev, target) {
      const card = extLink(target, 'nl__article');
      card.setAttribute('dir', 'auto');
      const d = tagValue(ev, 'd');
      const image = safeUrl(tagValue(ev, 'image'));
      if (o.media && image && image.protocol === 'https:') {
        const info = parseImeta(ev).get(image.href) || { w: 0, h: 0, alt: '' };
        card.appendChild(imageNode({ ...info, url: image.href }, ''));
      }
      card.appendChild(el('h' + Math.min(6, o.headingLevel + 1), 'nl__atitle', str(tagValue(ev, 'title'), 200) || d || t('article')));
      const sum = snippet(str(tagValue(ev, 'summary'), 500) || ev.content.replace(/[#*_>`[\]()!]/g, ''), 240);
      if (sum) card.appendChild(el('span', 'nl__asum', sum));
      const tags = [...new Set(ev.tags.filter(x => x[0] === 't' && x[1]).map(x => str(x[1], 40)).filter(Boolean))].slice(0, 5);
      if (tags.length) {
        const row = el('span', 'nl__atags');
        for (const tag of tags) row.appendChild(el('span', null, `#${tag}`));
        card.appendChild(row);
      }
      const read = el('span', 'nl__aread');
      read.append(el('span', null, t('readArticle')), arrow());
      card.appendChild(read);
      return card;
    }

    function noteNode(ev) {
      const li = el('li', 'nl__item');
      const target = noteTarget(ev);
      const article = ev.kind === 30023;

      const meta = el('div', 'nl__meta');
      const stamp = extLink(target, 'nl__time');
      stamp.setAttribute('aria-label', `${t('openNote')}, ${absolute(ev.created_at)}`);
      stamp.appendChild(timeNode(ev.created_at));
      meta.appendChild(stamp);
      if (article) meta.appendChild(el('span', 'nl__kind', t('article')));
      li.appendChild(meta);

      const bodyNode = el('div', 'nl__body');
      li.appendChild(bodyNode);
      if (article) {
        bodyNode.appendChild(articleNode(ev, target));
        return li;
      }

      const { segs, media, quotes } = analyse(ev, o.media);
      const totalText = segs.reduce((n, s) => n + (s.t === 'text' ? s.v.length : 12), 0);
      if (segs.length) {
        const text = el('p', 'nl__text');
        text.setAttribute('dir', 'auto');
        const draw = full => drawSegments((text.replaceChildren(), text), segs, full ? CFG.hardCap : CFG.collapseAt);
        const cut = draw(false);
        bodyNode.appendChild(text);
        if (totalText > CFG.collapseAt && cut) {
          const more = el('button', 'nl__more', t('more'));
          more.type = 'button';
          more.setAttribute('aria-expanded', 'false');
          more.addEventListener('click', () => {
            const open = more.getAttribute('aria-expanded') === 'true';
            draw(!open);
            more.setAttribute('aria-expanded', String(!open));
            more.textContent = open ? t('more') : t('less');
          });
          bodyNode.appendChild(more);
        }
      }
      if (media.length) {
        const wrap = el('div', 'nl__medialist');
        for (const info of media) {
          wrap.appendChild(info.kind === 'image' ? imageNode(info, t('imageAlt')) : videoNode(info, t('videoLabel')));
        }
        bodyNode.appendChild(wrap);
      }
      for (const q of quotes) bodyNode.appendChild(quoteNode(q));
      return li;
    }

    /* ----- the list ----- */
    function skeletonList() {
      const count = o.layout === 'ticker' ? 1 : Math.min(o.shown, 3);
      list.replaceChildren();
      for (let i = 0; i < count; i++) {
        const li = el('li', 'nl__item is-skel');
        li.setAttribute('aria-hidden', 'true');
        if (o.layout === 'ticker') li.append(skel('', '4em'), skel('', '70%'));
        else if (o.layout === 'compact') li.append(skel('', '30%'), skel('', '90%'));
        else {
          li.append(skel('', '28%'), skel('', '96%'), skel('', '82%'));
          if (i === 0 && o.media && o.layout !== 'compact') li.append(skel('nl__skel--media'));
        }
        list.appendChild(li);
      }
      mountList();
    }

    function mountList() {
      body.replaceChildren();
      if (o.layout !== 'ticker') { body.appendChild(list); return; }
      const row = el('div', 'nl__row');
      row.appendChild(list);
      body.appendChild(row);
    }

    function drawList() {
      stopTicker();
      list.replaceChildren();
      mentionSpots.length = 0;
      quoteSpots.length = 0;
      teaserSpots.length = 0;
      const items = S.items.slice(0, o.shown);
      const teaser = o.layout === 'compact' || o.layout === 'ticker';
      for (const ev of items) list.appendChild(teaser ? teaserNode(ev) : noteNode(ev));
      mountList();
      if (o.layout === 'ticker' && items.length) startTicker(items.length);
    }

    /* ----- ticker: one teaser at a time, never autoplaying under reduced motion ----- */
    const reduced = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };
    function showTick(n) {
      const nodes = [...list.children];
      tickIndex = (n + nodes.length) % nodes.length;
      nodes.forEach((li, i) => { li.hidden = i !== tickIndex; li.classList.toggle('is-in', i === tickIndex); });
    }
    let paused = false;
    for (const [on, off] of [['mouseenter', 'mouseleave'], ['focusin', 'focusout']]) {
      host.addEventListener(on, () => { paused = true; });
      host.addEventListener(off, () => { paused = false; });
    }
    function stopTicker() { clearInterval(tickTimer); tickTimer = 0; }
    function startTicker(count) {
      showTick(0);
      if (count < 2) return;
      const row = body.querySelector('.nl__row');
      const ctl = el('div', 'nl__tctl');
      for (const [dir, key, d] of [[-1, 'prev', 'M10 3L5 8l5 5'], [1, 'next', 'M6 3l5 5-5 5']]) {
        const b = el('button', 'nl__tbtn');
        b.type = 'button';
        b.setAttribute('aria-label', t(key));
        b.title = t(key);
        b.appendChild(icon([d], 14));
        b.addEventListener('click', () => showTick(tickIndex + dir));
        ctl.appendChild(b);
      }
      row.appendChild(ctl);
      tickTimer = setInterval(() => {
        if (paused || reduced.matches || document.hidden || !host.isConnected) return;
        showTick(tickIndex + 1);
      }, CFG.tickMs);
    }

    /* ----- follow ----- */
    function drawFoot() {
      foot.hidden = !o.follow || !o.valid;
      if (foot.hidden || foot.firstChild) return;
      const row = el('div', 'nl__follow');
      const follow = el('a', 'nl__btn nl__btn--primary');
      follow.href = `nostr:${o.nprofile}`;
      follow.title = t('followTitle');
      follow.append(el('span', null, t('follow')), arrow());
      const menuId = `nl-menu-${id}`;
      const toggle = el('button', 'nl__btn nl__openbtn');
      toggle.type = 'button';
      toggle.setAttribute('aria-expanded', 'false');
      toggle.setAttribute('aria-controls', menuId);
      toggle.append(el('span', null, t('openIn')), icon(['M4 6l4 4 4-4'], 12));
      row.append(follow, toggle);

      const menu = el('div', 'nl__openin');
      menu.id = menuId;
      menu.hidden = true;
      for (const [name, href] of [
        ['YakiHonne', `https://yakihonne.com/profile/${o.nprofile}`],
        ['Primal', `https://primal.net/p/${o.nprofile}`],
        ['njump', `https://njump.me/${o.nprofile}`]
      ]) menu.appendChild(extLink(href, null, name));
      const copy = el('button', null, t('copy'));
      copy.type = 'button';
      let timer = 0;
      copy.addEventListener('click', async () => {
        const ok = await copyText(o.nprofile);
        const said = t(ok ? 'copied' : 'copyFailed');
        copy.textContent = said;
        live.textContent = said;
        clearTimeout(timer);
        timer = setTimeout(() => { copy.textContent = t('copy'); }, 1800);
      });
      menu.appendChild(copy);

      const setOpen = open => { menu.hidden = !open; toggle.setAttribute('aria-expanded', String(open)); };
      toggle.addEventListener('click', () => setOpen(menu.hidden));
      foot.addEventListener('keydown', e => { if (e.key === 'Escape' && !menu.hidden) { setOpen(false); toggle.focus(); } });
      const outside = e => { if (!menu.hidden && !foot.contains(e.target)) setOpen(false); };
      document.addEventListener('click', outside);
      cleanups.push(() => document.removeEventListener('click', outside));
      foot.append(row, menu);
    }

    /* ----- status line (empty, error) ----- */
    function setStatus(text, retry = true) {
      status.replaceChildren();
      status.hidden = !text;
      if (!text) return;
      status.appendChild(document.createTextNode(text));
      if (!retry) return;
      const btn = el('button', 'nl__btn nl__retry', t('retry'));
      btn.type = 'button';
      btn.addEventListener('click', () => { cacheDrop(S.key); load(true); });
      status.appendChild(btn);
    }

    /* mention names and quote cards that arrived after the notes were drawn */
    function patch() {
      for (const spot of mentionSpots) spot.a.textContent = `@${nameOf(spot.pubkey)}`;
      for (const spot of quoteSpots) fillQuote(spot);
      for (const spot of teaserSpots) spot.link.textContent = teaserText(spot.ev);
    }

    function render() {
      S.shown = true;
      drawHead();
      drawList();
      drawFoot();
    }

    const sign = () => `${S.profile ? S.profile.id : ''}|${S.items.map(ev => ev.id).join()}`;

    /* ----- the whole load: cache first, then relays, then extras ----- */
    async function load(force, cacheOnly) {
      const run = ++runId;
      const live_ = () => !destroyed && run === runId;
      S.key = cacheKey(o.pubkey, o);

      if (!globalThis.crypto || !crypto.subtle || typeof BigInt !== 'function') {
        // without WebCrypto nothing can be checked, and unchecked posts are not shown
        drawHead(); skeletonList(); drawFoot();
        list.replaceChildren();
        setState('error', t('unsupported'));
        setStatus(t('unsupported'), false);
        emit('nostr-live:error', { reason: 'unsupported' });
        return;
      }

      if (!S.shown) {
        const cached = cacheGet(S.key);
        if (cached) {
          S.profile = cached.profile;
          S.items = cached.items;
          S.extras = cached.extras;
          S.fetchedAt = cached.t;
          render();
          signature = sign();
          setState(S.items.length ? 'ready' : 'empty', t('loaded'));
          setStatus(S.items.length ? '' : (o.emptyText || t('empty')));
          emit('nostr-live:loaded', { count: S.items.length, source: 'cache' });
        } else {
          drawHead(); skeletonList(); drawFoot();
          setStatus('');
          setState('loading', t('loading'));
        }
      } else if (force) {
        host.classList.add('is-refreshing');
        if (!S.items.length) { skeletonList(); setStatus(''); setState('loading', t('loading')); }
      }
      if (cacheOnly || (!force && S.shown && Date.now() - S.fetchedAt < CFG.fresh)) return;

      await whenVisible();
      if (!live_()) return;

      let first;
      try { first = await loadAccount(o.pubkey, o.relays, o); }
      catch (e) { first = { profile: null, items: [], answered: 0, total: o.relays.length }; }
      if (!live_()) return;
      host.classList.remove('is-refreshing');

      if (first.answered === 0) {
        // nobody answered: keep whatever is on screen, otherwise say so with a retry
        if (!S.items.length) {
          S.shown = true;
          drawHead();
          list.replaceChildren();
          setState('error', t('error'));
          setStatus(t('error'));
        }
        emit('nostr-live:error', { reason: 'relays', stale: S.shown, relays: o.relays.slice() });
        return;
      }

      // relays answered but know nothing: do not blank a cached feed on that evidence
      if (first.items.length || !S.items.length) {
        S.profile = first.profile || S.profile;
        S.items = first.items;
      } else if (first.profile) {
        S.profile = first.profile;
      }
      S.fetchedAt = Date.now();
      if (!S.shown || !S.items.length || sign() !== signature) {
        render();
        signature = sign();
      }
      setState(S.items.length ? 'ready' : 'empty', S.items.length ? t('loaded') : '');
      setStatus(S.items.length ? '' : (o.emptyText || t('empty')));
      emit('nostr-live:loaded', { count: S.items.length, source: 'relays' });

      try {
        const wantQuotes = o.layout !== 'compact' && o.layout !== 'ticker';
        if (S.items.length && await loadExtras(S.items, S.extras, o.relays, wantQuotes) && live_()) patch();
      } catch (e) { /* the notes are on screen already; extras are a bonus */ }
      if (live_()) cachePut(S.key, S);
    }

    /* ----- when to start ----- */
    const start = () => load(false);
    drawHead(); drawFoot();
    if (o.valid) {
      S.key = cacheKey(o.pubkey, o);
      if (o.loadOn === 'visible' && typeof IntersectionObserver === 'function') {
        // cached notes appear at once; the network waits until the widget is near the screen
        const cached = cacheGet(S.key);
        if (cached) load(false, true); else { skeletonList(); setState('loading', ''); }
        observer = new IntersectionObserver(entries => {
          if (!entries.some(e => e.isIntersecting)) return;
          observer.disconnect();
          observer = null;
          start();
        }, { rootMargin: '240px' });
        observer.observe(host);
      } else {
        start();
      }
    } else {
      S.shown = true;
      drawHead();
      list.replaceChildren();
      setState('error', t('error'));
      setStatus(t('error'), false);
      if (root.console) console.warn('NostrLive: the nprofile is missing or not valid', host);
      emit('nostr-live:error', { reason: 'nprofile' });
    }

    const onVisible = () => {
      if (!document.hidden && S.shown && !observer && Date.now() - S.fetchedAt > CFG.revisit) load(true);
    };
    document.addEventListener('visibilitychange', onVisible);
    cleanups.push(() => document.removeEventListener('visibilitychange', onVisible));

    const handle = {
      refresh() { if (!destroyed && o.valid) { if (observer) { observer.disconnect(); observer = null; } load(true); } },
      destroy() {
        if (destroyed) return;
        destroyed = true;
        if (observer) observer.disconnect();
        stopTicker();
        for (const fn of cleanups) fn();
        instances.delete(host);
        host.classList.remove(...addedClasses, 'is-loading', 'is-empty', 'is-error', 'is-refreshing');
        host.removeAttribute('data-nl-state');
        host.removeAttribute('aria-busy');
        if (hadRole === null) host.removeAttribute('role'); else host.setAttribute('role', hadRole);
        if (hadLabel === null) host.removeAttribute('aria-label'); else host.setAttribute('aria-label', hadLabel);
        host.replaceChildren(...originalChildren);
      }
    };
    instances.set(host, handle);
    return handle;
  }

  /* ----------------------------------------------- 8e. public API --------- */

  /* Mounts every [data-nostr-live] under `scope` that is not mounted yet. Runs
     once on DOMContentLoaded; call it again after inserting markup later. */
  const scan = scope => {
    const out = [];
    for (const host of (scope || document).querySelectorAll('[data-nostr-live]')) {
      if (instances.has(host) || host.getAttribute('data-nostr-live') === 'manual') continue;
      out.push(mount(host));
    }
    return out;
  };

  const api = {
    version: VERSION,
    mount,
    scan,
    get: host => instances.get(host) || null,
    css: CSS,
    strings: STRINGS,
    // exposed for tests and for sites that want to reuse the decoders
    _test: { bech32Decode, bech32Encode, decodeRef, parseTLV, encodeNevent, encodeNaddr, encodeNpub, verifySchnorr, verifyEvent, analyse, cleanRelay, safeUrl, wellFormed }
  };

  if (typeof module === 'object' && module && module.exports) module.exports = api;
  if (typeof window !== 'undefined') {
    window.NostrLive = api;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => scan());
    else scan();
  }
})();
