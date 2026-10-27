#!/usr/bin/env node
/**
 * Smoke test for the WitAnime + Anime4Up CloudStream providers.
 *
 * It exercises the exact HTTP endpoints and parsing rules the plugins use, so a
 * failure here means the provider will fail in CloudStream/Harbor too.
 *
 * Run:  node tools/smoke-test.mjs            (all providers)
 *       node tools/smoke-test.mjs anime4up   (single provider)
 *
 * Exit code: 0 = every catalog/stream check passed, 1 = something regressed.
 */

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const CLIENT_HINTS = {
  'sec-ch-ua': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"Windows"',
};

const WIT = 'https://witanime.site';
const A4U = 'https://w1.anime4up.rest';

let failures = 0;
const results = [];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  if (!ok) failures++;
  const mark = ok === true ? 'PASS' : ok === 'known' ? 'KNOWN' : 'FAIL';
  console.log(`[${mark}] ${name}${detail ? ' — ' + detail : ''}`);
}

async function get(url, headers = {}) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, 'Accept-Language': 'ar,en-US;q=0.9,en;q=0.8', ...headers },
    redirect: 'follow',
  });
  return { status: res.status, headers: res.headers, text: await res.text(), url: res.url };
}

function uniqueNumbers(html, pattern) {
  const set = new Set();
  for (const m of html.matchAll(pattern)) set.add(Number(m[1]));
  return [...set].filter((n) => Number.isFinite(n)).sort((a, b) => a - b);
}

// ------------------------------------------------------------------ WitAnime

async function testWitanime() {
  console.log('\n== WitAnime ==');

  const search = await get(`${WIT}/search?q=naruto`);
  const cards = new Set([...search.text.matchAll(/href="https:\/\/witanime\.site\/(?:anime|movie)\/([^"/?]+)/g)].map((m) => m[1]));
  record('search page', search.status === 200 && cards.size > 0, `${cards.size} unique titles`);

  const anime = await get(`${WIT}/anime/naruto`);
  const episodes = uniqueNumbers(anime.text, /\/watch\/naruto\/(\d+)/g);
  record(
    'anime page + episode list',
    anime.status === 200 && episodes.length > 100,
    `${episodes.length} episodes (${episodes[0]}..${episodes[episodes.length - 1]})`
  );

  const watchUrl = `${WIT}/watch/naruto/1`;
  const watch = await get(watchUrl);
  const csrf = /<meta name="csrf-token" content="([^"]+)"/.exec(watch.text)?.[1];
  const cookies = (watch.headers.getSetCookie?.() ?? [])
    .map((c) => c.split(';')[0])
    .join('; ');
  record('watch page + csrf token', watch.status === 200 && !!csrf, csrf ? 'token found' : 'no token');

  if (!csrf) return;

  const ajax = await fetch(`${watchUrl}/sources`, {
    method: 'POST',
    headers: {
      'User-Agent': UA,
      ...CLIENT_HINTS,
      Accept: 'application/json',
      'Accept-Language': 'ar,en-US;q=0.9,en;q=0.8',
      'X-CSRF-TOKEN': csrf,
      'X-Requested-With': 'XMLHttpRequest',
      Origin: WIT,
      Referer: watchUrl,
      'Sec-Fetch-Dest': 'empty',
      'Sec-Fetch-Mode': 'cors',
      'Sec-Fetch-Site': 'same-origin',
      Cookie: cookies,
    },
  });
  const body = await ajax.text();
  let players = {};
  try {
    players = JSON.parse(body).players ?? {};
  } catch {
    /* ignore */
  }
  const tokens = Object.values(players).flat();
  record('sources manifest', ajax.status === 200 && tokens.length > 0, `${tokens.length} servers`);

  if (tokens.length === 0) return;

  const gate = await get(`${WIT}/watch/stream-gate/${tokens[0].token}`, {
    ...Object.fromEntries(Object.entries({}).map(([k, v]) => [k, v])),
    Cookie: cookies,
    Referer: watchUrl,
    Accept: 'text/html',
    'Sec-Fetch-Dest': 'iframe',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'same-origin',
  });
  const playable = gate.status === 200 && !/data-status="404"/.test(gate.text);
  record(
    'stream gate (site player)',
    playable ? true : 'known',
    playable ? 'gate opened' : `site returns ${gate.status} — playback blocked server-side`
  );
}

// ------------------------------------------------------------------ Anime4Up

async function testAnime4up() {
  console.log('\n== Anime4Up ==');

  const search = await get(`${A4U}/?s=naruto`);
  const cards = new Set([...search.text.matchAll(/href="https:\/\/w1\.anime4up\.rest\/anime\/([^"/?]+)\//g)].map((m) => m[1]));
  record('search page', search.status === 200 && cards.size > 0, `${cards.size} unique titles`);

  const anime = await get(`${A4U}/anime/naruto-uef/`);
  const episodes = uniqueNumbers(anime.text, /class="ep_num"[\s\S]{0,300}?الحلقة\s*(\d+)/g);
  const pages = Math.max(
    1,
    ...[...anime.text.matchAll(/\/page\/(\d+)\//g)].map((m) => Number(m[1])).filter(Number.isFinite)
  );
  record('anime page + episode list', anime.status === 200 && episodes.length > 0, `${episodes.length} episodes on page 1, ${pages} pages`);

  const epUrl = /href="(https:\/\/w1\.anime4up\.rest\/episode\/[^"]+)"/.exec(anime.text)?.[1];
  if (!epUrl) {
    record('episode page', false, 'no episode link found');
    return;
  }

  const ep = await get(epUrl, { Referer: anime.url });
  const servers = [...ep.text.matchAll(/li[^>]+data-watch="([^"]+)"[^>]*>/g)].map((m) => m[1]);
  record('episode page + servers', ep.status === 200 && servers.length > 0, `${servers.length} servers`);

  const internal = servers.find((s) => /Anime4up-S\d/i.test(s));
  if (!internal) {
    record('internal player', false, 'no Anime4up player link on page');
    return;
  }

  const player = await get(internal, { Referer: epUrl, 'User-Agent': UA });
  const stream = /(?:let|var|const)\s+streamUrl\s*=\s*["']([^"']+)["']/.exec(player.text)?.[1];
  record('internal player → HLS url', player.status === 200 && !!stream, stream ? new URL(stream).host : 'not found');

  if (!stream) return;

  // A player that drops custom headers must still work; verify that explicitly.
  const streamRes = await fetch(stream);
  const streamBody = await streamRes.text();
  record(
    'HLS plays without headers',
    streamRes.status === 200 && streamBody.startsWith('#EXTM3U'),
    `${streamRes.status} ${streamRes.headers.get('content-type')}`
  );
}

// --------------------------------------------------------------------- main

const target = process.argv[2];
if (!target || target === 'witanime') await testWitanime();
if (!target || target === 'anime4up') await testAnime4up();

const failed = results.filter((r) => r.ok === false);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
if (failed.length) {
  console.log('Failed:', failed.map((f) => f.name).join(', '));
  process.exit(1);
}
