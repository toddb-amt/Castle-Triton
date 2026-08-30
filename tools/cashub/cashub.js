#!/usr/bin/env node
/*
 * cashub — CLI for the Castles CasHUB Web (RESTful) API.
 *
 * Automates the fleet operations we otherwise click through the CasHUB portal:
 *   - fleet health   (which terminals are connected / stale)
 *   - parameter push (central config for castech.emvtxn: host/TID/fees)
 *   - app push       (roll a CAP already uploaded to CasHUB out to terminals)
 *
 * Zero dependencies; requires Node 18+ (built-in fetch).
 *
 * Setup: cp config.example.json config.json and fill it in.
 *        Credentials come from CasHUB portal -> My Enterprise -> API Service
 *        (the credential carries an IP allowlist — this machine's public IP
 *        must be on it).
 *
 * API: CasHUB Tenant API v3 (online docs, 2.50.x). v3 auth is a single step —
 * POST /tenant/v3/token {appid, secret} -> access_token (7200s TTL, carries
 * the tenant) sent as an Access-Token header; there is NO login call and no
 * Auth-Id/Tenant-Id headers (those were v2). The credential's API Service
 * entry must have an API User bound in the portal or v3 calls are refused.
 * Response envelope is {data, msg, status} with status 0 = success.
 * Rate limit: 10 requests/second per application.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const CONFIG_PATH = process.env.CASHUB_CONFIG || path.join(HERE, 'config.json');
const STATE_PATH = path.join(HERE, '.cashub-state.json');

// Parameter keys the app actually reads (CasHubParams.applyToConfig ->
// KmsConfigStore.applyPayload). Anything else in a manifest is almost
// certainly a typo, so we warn.
const RECOGNIZED_KEYS = new Set([
  'host_address', 'host_port', 'terminal_id', 'processor_type', 'protocol_type',
  'use_flat_fee', 'flat_fee', 'percentage_fee', 'min_amount', 'max_amount',
]);

// CasHubParams merges terminal rows first, merchant rows second, LAST WINS —
// a merchant-level value overrides every terminal under it. These keys are
// per-terminal identity and must never be pushed at merchant level.
const TERMINAL_ONLY_KEYS = new Set(['terminal_id']);

const DEFAULT_TARGET_APP = 'castech.emvtxn';

// ---------------------------------------------------------------- utilities

function die(msg) {
  console.error('ERROR: ' + msg);
  process.exit(1);
}

function readJson(file, what) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    die(`cannot read ${what || file}: ${e.message}`);
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    die(`${what || file} is not valid JSON: ${e.message}`);
  }
}

// "YYYY-MM-DD HH:mm:ss" — the format every example in the API doc uses.
function fmtTime(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
         `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function printTable(rows, cols) {
  const widths = cols.map((c) => Math.max(c.label.length,
    ...rows.map((r) => String(r[c.key] == null ? '' : r[c.key]).length)));
  const line = (vals) => vals.map((v, i) => String(v == null ? '' : v).padEnd(widths[i])).join('  ');
  console.log(line(cols.map((c) => c.label)));
  console.log(line(widths.map((w) => '-'.repeat(w))));
  for (const r of rows) console.log(line(cols.map((c) => r[c.key])));
}

// Minimal flag parser: positionals + --key value + boolean --flags.
const BOOL_FLAGS = new Set(['dry-run', 'all', 'json', 'mandatory', 'force']);
function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      if (BOOL_FLAGS.has(key) || i + 1 >= argv.length || argv[i + 1].startsWith('--')) {
        args[key] = true;
      } else {
        args[key] = argv[++i];
      }
    } else {
      args._.push(a);
    }
  }
  return args;
}

// ------------------------------------------------------------ config/state

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    die(`missing ${CONFIG_PATH}\nCopy config.example.json to config.json and fill it in.`);
  }
  const cfg = readJson(CONFIG_PATH, 'config.json');
  for (const k of ['base_url', 'appid', 'secret']) {
    if (!cfg[k] || String(cfg[k]).startsWith('REPLACE')) die(`config.json: "${k}" is not set`);
  }
  cfg.base_url = cfg.base_url.replace(/\/+$/, '');
  return cfg;
}

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_PATH, 'utf8')); } catch (e) { return {}; }
}

function saveState(state) {
  fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2), { mode: 0o600 });
}

// ------------------------------------------------------------------- HTTP

class HttpAuthError extends Error {}

async function rawRequest(cfg, method, apiPath, body, headers) {
  const url = cfg.base_url + apiPath;
  const res = await fetch(url, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (res.status === 401 || res.status === 403) {
    throw new HttpAuthError(`HTTP ${res.status} on ${apiPath}: ${text.slice(0, 300)}`);
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} on ${method} ${apiPath}: ${text.slice(0, 500)}`);
  }
  let json;
  try { json = JSON.parse(text); } catch (e) {
    throw new Error(`non-JSON response from ${apiPath}: ${text.slice(0, 300)}`);
  }
  if (json.status !== 0) {
    throw new Error(`CasHUB API ${method} ${apiPath} failed: ${json.msg} (status ${json.status})`);
  }
  return json;
}

/**
 * v3 auth: a single POST /tenant/v3/token {appid, secret} -> access_token
 * (7200s TTL); the response also identifies the credential's tenant. Every
 * other call carries just the Access-Token header. Cached in
 * .cashub-state.json between runs.
 */
async function ensureAuth(cfg, force) {
  let state = force ? {} : loadState();

  if (!state.access_token || Date.now() > (state.token_expires_at || 0) - 60000) {
    const r = await rawRequest(cfg, 'POST', '/tenant/v3/token', { appid: cfg.appid, secret: cfg.secret });
    state.access_token = r.data.access_token;
    state.token_expires_at = Date.now() + ((r.data.expires_in || 7200) * 1000);
    // v3 returns the API Service's tenant in the token response; a config
    // tenant_id (e.g. a sub-enterprise) overrides it.
    state.tenant_id = cfg.tenant_id || r.data.tenant_id || state.tenant_id || null;
    state.tenant_name = r.data.tenant_name || state.tenant_name || null;
  }
  if (cfg.tenant_id) state.tenant_id = cfg.tenant_id;

  saveState(state);
  return state;
}

/** Authenticated call; on an auth failure, get a fresh token once and retry. */
async function api(cfg, method, apiPath, body) {
  let state = await ensureAuth(cfg, false);
  try {
    return await rawRequest(cfg, method, apiPath, body, { 'Access-Token': state.access_token });
  } catch (e) {
    if (!(e instanceof HttpAuthError)) throw e;
    state = await ensureAuth(cfg, true);
    return await rawRequest(cfg, method, apiPath, body, { 'Access-Token': state.access_token });
  }
}

/** Tenant id is needed for a few tenant-scoped listings. */
async function requireTenant(cfg) {
  const state = await ensureAuth(cfg, false);
  if (!state.tenant_id) {
    die('tenant id unknown — the token response did not include one; set "tenant_id" in config.json');
  }
  return state.tenant_id;
}

// ------------------------------------------------------------ API wrappers

async function listTerminals(cfg) {
  const r = await api(cfg, 'GET', '/tenant/v3/terminals?page=1&limit=500');
  const items = (r.data && r.data.items) || [];
  const total = (r.data && r.data.total) || items.length;
  if (total > items.length) {
    console.error(`WARNING: enterprise has ${total} terminals, listing returned ${items.length} — pagination needed`);
  }
  return items;
}

async function listMerchants(cfg) {
  const tenantId = await requireTenant(cfg);
  const r = await api(cfg, 'GET', `/tenant/v3/tenants/${tenantId}/merchants`);
  return (r.data && r.data.items) || [];
}

async function listApps(cfg) {
  const r = await api(cfg, 'GET', '/tenant/v3/apps');
  const d = r.data;
  return (d && d.items) || (Array.isArray(d) ? d : []);
}

function matchesTerminal(t, needle) {
  const n = needle.toLowerCase();
  return [t.terminal_name, t.hardware_sn, t.self_defined_id]
    .some((v) => v && String(v).toLowerCase() === n);
}

async function resolveTerminal(cfg, needle, terminals) {
  const list = terminals || await listTerminals(cfg);
  const hits = list.filter((t) => matchesTerminal(t, needle));
  if (hits.length === 0) die(`no terminal matches "${needle}" (checked terminal_name, hardware_sn, self_defined_id)`);
  if (hits.length > 1) die(`"${needle}" matches ${hits.length} terminals — be more specific`);
  return hits[0];
}

async function resolveMerchant(cfg, needle) {
  const list = await listMerchants(cfg);
  const n = needle.toLowerCase();
  const hits = list.filter((m) =>
    [m.name, m.merchant_number].some((v) => v && String(v).toLowerCase() === n));
  if (hits.length === 0) die(`no merchant matches "${needle}" (checked name, merchant_number)`);
  if (hits.length > 1) die(`"${needle}" matches ${hits.length} merchants — be more specific`);
  return hits[0];
}

// -------------------------------------------------------- manifest (fleet)

/**
 * Fleet manifest shape (see fleet.example.json):
 *   { "defaults": {shared params}, "terminals": [ {"match": "<name|sn|self-id>",
 *     "params": {per-terminal params, must include terminal_id}} ] }
 */
function renderFleet(file) {
  const m = readJson(file, 'fleet manifest');
  const defaults = m.defaults || {};
  if (!Array.isArray(m.terminals) || m.terminals.length === 0) {
    die('fleet manifest has no "terminals" array');
  }
  const entries = [];
  for (const t of m.terminals) {
    if (!t.match) die('fleet manifest: every terminal entry needs a "match" (CasHUB terminal name / hardware SN / self-defined ID)');
    const params = Object.assign({}, defaults, t.params || {});
    for (const k of Object.keys(params)) {
      if (!RECOGNIZED_KEYS.has(k)) {
        console.error(`WARNING [${t.match}]: key "${k}" is not one the app reads (typo?)`);
      }
    }
    if (!params.terminal_id) die(`fleet manifest [${t.match}]: missing terminal_id`);
    entries.push({ match: t.match, params, target_app: t.target_app || m.target_app || DEFAULT_TARGET_APP });
  }
  const tids = entries.map((e) => e.params.terminal_id);
  const dupes = tids.filter((v, i) => tids.indexOf(v) !== i);
  if (dupes.length) die(`fleet manifest: duplicate terminal_id(s): ${[...new Set(dupes)].join(', ')}`);
  return entries;
}

function paramBody(entry, opts) {
  return {
    name: opts.name || `atm-config-${entry.params.terminal_id}-${fmtTime(new Date()).replace(/[^0-9]/g, '').slice(0, 12)}`,
    content: JSON.stringify(entry.params),
    mandatory: opts.mandatory ? 1 : 0,
    target_app: entry.target_app,
    execute_at: opts.executeAt || fmtTime(new Date()),
  };
}

// ---------------------------------------------------------------- commands

async function cmdLogin(cfg) {
  const state = await ensureAuth(cfg, true);
  console.log('Auth OK — access token obtained.');
  console.log(`Tenant: ${state.tenant_name || '(name not returned)'}  ${state.tenant_id || '(id not returned)'}`);
  console.log(`Token expires: ${new Date(state.token_expires_at).toLocaleString()}`);
}

async function cmdTerminals(cfg, args) {
  const items = await listTerminals(cfg);
  if (args.json) { console.log(JSON.stringify(items, null, 2)); return; }
  printTable(items.map((t) => ({
    name: t.terminal_name, sn: t.hardware_sn, model: t.model_name,
    merchant: t.merchant_name, connected: t.connected ? 'yes' : 'NO',
    last_seen: t.last_connected_at, active: t.active ? 'yes' : 'NO',
  })), [
    { key: 'name', label: 'TERMINAL' }, { key: 'sn', label: 'HW SERIAL' },
    { key: 'model', label: 'MODEL' }, { key: 'merchant', label: 'MERCHANT' },
    { key: 'connected', label: 'CONN' }, { key: 'last_seen', label: 'LAST SEEN' },
    { key: 'active', label: 'ACTIVE' },
  ]);
}

async function cmdStatus(cfg, args) {
  const staleHours = Number(args['stale-hours'] || 26);
  const items = await listTerminals(cfg);
  const now = Date.now();
  const problems = [];
  for (const t of items) {
    const lastMs = t.last_connected_at ? Date.parse(t.last_connected_at.replace(' ', 'T')) : NaN;
    const ageH = Number.isFinite(lastMs) ? (now - lastMs) / 3600000 : Infinity;
    if (!t.connected && ageH > staleHours) {
      problems.push({
        name: t.terminal_name, sn: t.hardware_sn, merchant: t.merchant_name,
        last_seen: t.last_connected_at || 'never',
        age: Number.isFinite(lastMs) ? `${ageH.toFixed(1)}h` : '-',
      });
    }
  }
  console.log(`Fleet: ${items.length} terminals, ${items.filter((t) => t.connected).length} connected right now.`);
  if (problems.length === 0) {
    console.log(`All terminals have phoned home within ${staleHours}h. ✔`);
  } else {
    console.log(`\n${problems.length} terminal(s) disconnected AND silent for over ${staleHours}h:`);
    printTable(problems, [
      { key: 'name', label: 'TERMINAL' }, { key: 'sn', label: 'HW SERIAL' },
      { key: 'merchant', label: 'MERCHANT' }, { key: 'last_seen', label: 'LAST SEEN' },
      { key: 'age', label: 'AGE' },
    ]);
    process.exitCode = 2; // scriptable: non-zero when the fleet has stragglers
  }
}

async function cmdMerchants(cfg) {
  const items = await listMerchants(cfg);
  printTable(items.map((m) => ({
    name: m.name, number: m.merchant_number, active: m.active ? 'yes' : 'NO', id: m.merchant_id,
  })), [
    { key: 'name', label: 'MERCHANT' }, { key: 'number', label: 'NUMBER' },
    { key: 'active', label: 'ACTIVE' }, { key: 'id', label: 'MERCHANT_ID' },
  ]);
}

async function cmdApps(cfg) {
  const items = await listApps(cfg);
  printTable(items.map((a) => ({
    name: a.name, pkg: a.package_name, version: a.version, code: a.version_code, id: a.app_id,
  })), [
    { key: 'name', label: 'APP' }, { key: 'pkg', label: 'PACKAGE' },
    { key: 'version', label: 'VERSION' }, { key: 'code', label: 'CODE' },
    { key: 'id', label: 'APP_ID' },
  ]);
}

async function cmdParamsRender(args) {
  const entries = renderFleet(args.fleet || die('--fleet <file> required'));
  const filtered = args.terminal ? entries.filter((e) => e.match === args.terminal) : entries;
  if (filtered.length === 0) die(`no manifest entry with match "${args.terminal}"`);
  for (const e of filtered) {
    console.log(`# ${e.match}  (target_app: ${e.target_app})`);
    console.log(JSON.stringify(e.params, null, 2));
    console.log('');
  }
}

async function cmdParamsPush(cfg, args) {
  if (!args.fleet) die('--fleet <file> required');
  let entries = renderFleet(args.fleet);
  if (args.terminal) {
    entries = entries.filter((e) => e.match === args.terminal);
    if (entries.length === 0) die(`no manifest entry with match "${args.terminal}"`);
  } else if (entries.length > 1 && !args.all) {
    die(`manifest has ${entries.length} terminals — pass --all to push to all of them, or --terminal <match> for one`);
  }

  const opts = { executeAt: args.at, mandatory: !!args.mandatory, name: args.name };

  if (args['dry-run']) {
    for (const e of entries) {
      console.log(`DRY RUN — would POST /tenant/v2/terminals/{terminal_id of "${e.match}"}/app-prms:`);
      console.log(JSON.stringify(paramBody(e, opts), null, 2));
      console.log('');
    }
    return;
  }

  const terminals = await listTerminals(cfg);
  let ok = 0;
  for (const e of entries) {
    const t = await resolveTerminal(cfg, e.match, terminals);
    const body = paramBody(e, opts);
    const r = await api(cfg, 'POST', `/tenant/v3/terminals/${t.terminal_id}/app-prms`, body);
    console.log(`pushed ${body.name} -> ${t.terminal_name} (${t.hardware_sn})` +
      (r.data && r.data.id ? `  [param id ${r.data.id}]` : ''));
    ok++;
  }
  console.log(`\n${ok}/${entries.length} parameter pushes accepted. Terminals apply on next poll ` +
    `(or live via PARAMETER_UPDATED broadcast if the app is running).`);
}

async function cmdParamsPushMerchant(cfg, args) {
  if (!args.merchant) die('--merchant <name|number> required');
  if (!args.file) die('--file <params.json> required');
  const params = readJson(args.file, 'params file');
  for (const k of Object.keys(params)) {
    if (TERMINAL_ONLY_KEYS.has(k)) {
      die(`"${k}" must NEVER be pushed at merchant level — the app merges merchant params ` +
          'over terminal params (last-wins), so this would clobber every terminal\'s ' + k);
    }
    if (!RECOGNIZED_KEYS.has(k)) console.error(`WARNING: key "${k}" is not one the app reads (typo?)`);
  }
  const entry = { params, target_app: args['target-app'] || DEFAULT_TARGET_APP };
  const body = paramBody(entry, { executeAt: args.at, mandatory: !!args.mandatory, name: args.name || `atm-merchant-config-${Date.now()}` });
  if (args['dry-run']) {
    console.log(`DRY RUN — would POST /tenant/v3/merchants/{merchant_id of "${args.merchant}"}/app-prms:`);
    console.log(JSON.stringify(body, null, 2));
    return;
  }
  const m = await resolveMerchant(cfg, args.merchant);
  const r = await api(cfg, 'POST', `/tenant/v3/merchants/${m.merchant_id}/app-prms`, body);
  console.log(`pushed ${body.name} -> merchant ${m.name}` + (r.data && r.data.id ? `  [param id ${r.data.id}]` : ''));
}

async function cmdAppPush(cfg, args) {
  if (!args.app) die('--app <package|name> required');
  if (!args.terminal && !args.merchant) die('--terminal <match> or --merchant <name|number> required');
  const apps = await listApps(cfg);
  let hits = apps.filter((a) => a.package_name === args.app || a.name === args.app);
  if (args.version) hits = hits.filter((a) => String(a.version) === String(args.version));
  if (hits.length === 0) die(`no uploaded app matches "${args.app}"${args.version ? ` v${args.version}` : ''} — run: cashub apps`);
  if (hits.length > 1) {
    die('multiple versions match — add --version:\n' +
      hits.map((a) => `  ${a.name}  v${a.version} (code ${a.version_code})`).join('\n'));
  }
  const app = hits[0];
  const body = { app_id: app.app_id, install_time: args.at || fmtTime(new Date()) };

  if (args['dry-run']) {
    const target = args.terminal ? `terminal "${args.terminal}"` : `merchant "${args.merchant}"`;
    console.log(`DRY RUN — would push ${app.name} v${app.version} (code ${app.version_code}) to ${target}:`);
    console.log(JSON.stringify(body, null, 2));
    return;
  }

  if (args.terminal) {
    const t = await resolveTerminal(cfg, args.terminal);
    await api(cfg, 'POST', `/tenant/v3/terminals/${t.terminal_id}/apps`, body);
    console.log(`push accepted: ${app.name} v${app.version} -> ${t.terminal_name} (installs at ${body.install_time})`);
  } else {
    const m = await resolveMerchant(cfg, args.merchant);
    await api(cfg, 'POST', `/tenant/v3/merchants/${m.merchant_id}/apps`, body);
    console.log(`push accepted: ${app.name} v${app.version} -> ALL terminals under merchant ${m.name} (installs at ${body.install_time})`);
  }
}

// -------------------------------------------------------------------- main

const HELP = `cashub — CasHUB fleet automation for the Cashless ATM app

USAGE
  cashub login                          verify the credential, show tenant
  cashub status [--stale-hours 26]     fleet health; exit 2 if any terminal is silent
  cashub terminals [--json]            list terminals (name, SN, connected, last seen)
  cashub merchants                     list merchants
  cashub apps                          list apps uploaded to CasHUB (for app push)

  cashub params render --fleet fleet.json [--terminal <match>]
                                       show what would be pushed (no network)
  cashub params push --fleet fleet.json (--terminal <match> | --all)
                     [--at "YYYY-MM-DD HH:mm:ss"] [--mandatory] [--name <n>] [--dry-run]
                                       push per-terminal config parameters
  cashub params push-merchant --merchant <name> --file params.json
                     [--target-app pkg] [--at ...] [--mandatory] [--dry-run]
                                       push SHARED params (fees/limits) merchant-wide;
                                       refuses terminal_id (would clobber every TID)

  cashub app push --app <package|name> [--version 6.2.3]
                  (--terminal <match> | --merchant <name>)
                  [--at "YYYY-MM-DD HH:mm:ss"] [--dry-run]
                                       roll an uploaded app out

NOTES
  <match> = CasHUB terminal name, hardware serial, or self-defined ID (exact).
  Config: ${path.relative(process.cwd(), CONFIG_PATH)} (see config.example.json).
  Auth: CasHUB Tenant API v3 — appid+secret only, no portal login needed.
  Token cached in .cashub-state.json; delete it to force re-auth.
`;

async function main() {
  if (typeof fetch !== 'function') die('Node 18+ required (built-in fetch)');
  const args = parseArgs(process.argv.slice(2));
  const [cmd, sub] = args._;

  // commands that need no config/network
  if (!cmd || cmd === 'help' || args.help) { console.log(HELP); return; }
  if (cmd === 'params' && sub === 'render') return cmdParamsRender(args);
  if (cmd === 'params' && sub === 'push' && args['dry-run']) return cmdParamsPush(null, args);
  if (cmd === 'params' && sub === 'push-merchant' && args['dry-run']) return cmdParamsPushMerchant(null, args);

  const cfg = loadConfig();
  if (cmd === 'login') return cmdLogin(cfg);
  if (cmd === 'status') return cmdStatus(cfg, args);
  if (cmd === 'terminals') return cmdTerminals(cfg, args);
  if (cmd === 'merchants') return cmdMerchants(cfg);
  if (cmd === 'apps') return cmdApps(cfg);
  if (cmd === 'params' && sub === 'push') return cmdParamsPush(cfg, args);
  if (cmd === 'params' && sub === 'push-merchant') return cmdParamsPushMerchant(cfg, args);
  if (cmd === 'app' && sub === 'push') return cmdAppPush(cfg, args);

  die(`unknown command "${args._.join(' ')}" — run: cashub help`);
}

main().catch((e) => die(e.message));
