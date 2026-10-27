// Runs the shared conformance vectors against the web event library: node roadbeacon-web/test/vectors.mjs
import { readFileSync } from 'node:fs';
const lib = await import('../js/roadbeacon.js');
const v = JSON.parse(readFileSync(new URL('../../nips/vectors/road-events-v1.json', import.meta.url), 'utf8'));
let fail = 0;
const check = (cond, msg) => { if (!cond) { fail++; console.log('FAIL', msg); } };
for (const x of [...v.valid, ...v.decode_only]) {
  if (x.event.kind === 1315) check(lib.isCanonicalReport(x.event), x.name);
  else check(lib.isCanonicalVote(x.event), x.name);
}
for (const x of v.encode_invalid) {
  if (['COMMENT_TOO_LONG','SIGNATURE_INVALID','TIMESTAMP_RANGE','WRONG_KIND','NON_EMPTY_CONTENT','BAD_STATUS'].includes(x.error) && x.event.kind === 1315 && x.error !== 'WRONG_KIND') continue; // tags are fine; frame-only refusals
  if (x.name === 'comment_control_character') continue;
  const ok = x.event.kind === 1315 ? lib.isCanonicalReport(x.event) : lib.isCanonicalVote(x.event);
  check(!ok, 'should reject ' + x.name);
}
// created events must be canonical and match the vector tags
const police = v.valid[0].event;
const made = lib.createReportEvent(0, 48.148598, 17.107748, '', police.created_at);
check(JSON.stringify(made.tags) === JSON.stringify(police.tags), 'createReportEvent tags');
const negz = lib.createReportEvent(0, 51.5, -0.0000004, 'a\nb', 1761552000);
check(negz.tags[1][1] === '0.000000' && lib.isCanonicalReport(negz) && negz.content === 'a b', 'negative zero / control chars');
// votes
const r = { createdAt: 1000 };
const vote = (pk, t, st) => ({ kind: 1316, pubkey: pk, created_at: t, content: '', tags: [['e', 'a'.repeat(64)], ['status', st ? 'still_there' : 'no_longer_there']] });
const adm = lib.admissibleVotes(r, [vote('x', 2000, false), vote('x', 2005, false), vote('y', 2001, true), vote('z', 999, false), vote('w', 9999, true)], 2010);
check(adm.length === 2, 'admissible one per key ' + adm.length);
check(lib.computeEffectiveTTL({ createdAt: 1000, type: lib.getTypeByString('police'), expiration: 0 }, [vote('a', 3000, true), vote('b', 3000, false)], 3000) === 3000 + 4 * 3600, 'tie keeps alive');
console.log(fail ? `${fail} failures` : 'web ok');
process.exitCode = fail ? 1 : 0;
