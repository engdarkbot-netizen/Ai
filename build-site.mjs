/**
 * Static site builder for the news agents.
 *
 * Reads all markdown reports in ./reports and generates ./site:
 *   site/index.html          - tabbed page: latest AI news + Saudi stocks briefings + archives
 *   site/admin.html          - agent control console
 *   site/reports/<name>.html - one page per past report
 *
 * Usage: node build-site.mjs
 */

import fs from 'fs';
import path from 'path';
import { marked } from 'marked';

const REPORTS_DIR = './reports';
const SITE_DIR    = './site';

const AGENTS = [
  { slug: 'ai-news',      emoji: '🧠', title: 'AI News',       longTitle: 'Daily AI News',                accent: 'ai' },
  { slug: 'saudi-stocks', emoji: '📈', title: 'Saudi Markets', longTitle: 'Saudi Stock Market (Tadawul)', accent: 'sa' },
];

const FONTS = 'https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,500;9..144,600;9..144,700&family=Inter:wght@400;500;600;700&family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,600;1,6..72,400&display=swap';

const CSS = `
/* ── Tokens ───────────────────────────────── */
:root {
  --bg:#f7f4ed; --bg-2:#efe9dc; --card:#fffdf9; --ink:#1e1c18; --ink-2:#403b32;
  --muted:#847d6d; --line:#e6dfd0; --line-2:#d8cfbb;
  --ai:#a8481a; --ai-2:#d4783f; --ai-soft:#f7e5d6;
  --sa:#0b7350; --sa-2:#17a473; --sa-soft:#d8f0e5;
  --glass:rgba(255,253,249,.78);
  --shadow-s:0 1px 2px rgba(45,38,22,.05), 0 2px 8px rgba(45,38,22,.04);
  --shadow-m:0 2px 6px rgba(45,38,22,.06), 0 16px 40px -12px rgba(45,38,22,.14);
  --radius:18px;
}
:root[data-theme="dark"] {
  --bg:#131211; --bg-2:#1a1917; --card:#1d1c19; --ink:#f0ece2; --ink-2:#cfc9bb;
  --muted:#948d7c; --line:#302e28; --line-2:#3d3a32;
  --ai:#f0a06a; --ai-2:#e0834a; --ai-soft:#3a2a1d;
  --sa:#4fd39d; --sa-2:#2fb682; --sa-soft:#17332a;
  --glass:rgba(29,28,25,.78);
  --shadow-s:0 1px 2px rgba(0,0,0,.4);
  --shadow-m:0 2px 6px rgba(0,0,0,.4), 0 18px 44px -12px rgba(0,0,0,.6);
}
* { box-sizing:border-box; }
html { scroll-behavior:smooth; -webkit-text-size-adjust:100%; }
body {
  margin:0; background:var(--bg); color:var(--ink);
  font:16px/1.65 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  -webkit-font-smoothing:antialiased; overflow-x:hidden;
}
.wrap { max-width:860px; margin:0 auto; padding:0 20px 80px; }
::selection { background:var(--ai-soft); }

/* ── Reading progress ─────────────────────── */
#progress { position:fixed; inset:0 auto auto 0; height:3px; width:0;
  background:linear-gradient(90deg, var(--ai), var(--sa)); z-index:60; transition:width .1s linear; }

/* ── Hero ─────────────────────────────────── */
.hero { position:relative; overflow:hidden; padding:74px 20px 56px; text-align:center;
  border-bottom:1px solid var(--line);
  background:linear-gradient(180deg, var(--bg-2) 0%, var(--bg) 100%); }
.hero::before, .hero::after {
  content:''; position:absolute; border-radius:50%; filter:blur(70px); opacity:.5;
  pointer-events:none; animation:drift 22s ease-in-out infinite alternate;
}
.hero::before { width:440px; height:440px; top:-190px; left:calc(50% - 400px);
  background:radial-gradient(circle, var(--ai-2), transparent 68%); }
.hero::after  { width:400px; height:400px; top:-160px; left:calc(50% + 60px);
  background:radial-gradient(circle, var(--sa-2), transparent 68%); animation-delay:-8s; }
:root[data-theme="dark"] .hero::before, :root[data-theme="dark"] .hero::after { opacity:.34; }
@keyframes drift { from { transform:translate3d(-26px,0,0) scale(1); }
                   to   { transform:translate3d(26px,18px,0) scale(1.14); } }
@media (prefers-reduced-motion:reduce) { .hero::before, .hero::after { animation:none; } }
.hero-inner { position:relative; z-index:1; max-width:860px; margin:0 auto; }
.kicker { font:600 .7rem/1 'Inter', sans-serif; letter-spacing:.32em; text-transform:uppercase;
  color:var(--muted); margin:0 0 16px; }
.hero h1 { margin:0; font-family:'Fraunces', Georgia, serif; font-weight:600;
  font-size:clamp(2.4rem, 8vw, 4.1rem); line-height:1.02; letter-spacing:-.025em;
  background:linear-gradient(135deg, var(--ink) 30%, var(--ai) 130%);
  -webkit-background-clip:text; background-clip:text; color:transparent; }
.dateline { color:var(--muted); font-size:.9rem; margin:16px 0 0; }
.stats { display:flex; gap:10px; justify-content:center; flex-wrap:wrap; margin:26px 0 0; }
.stat { display:flex; align-items:baseline; gap:7px; background:var(--glass);
  border:1px solid var(--line); border-radius:999px; padding:7px 15px;
  backdrop-filter:blur(10px); box-shadow:var(--shadow-s); }
.stat b { font:700 .95rem 'Inter', sans-serif; }
.stat span { font-size:.76rem; color:var(--muted); letter-spacing:.04em; text-transform:uppercase; }
.hero-actions { margin:22px 0 0; }

/* ── Chrome ───────────────────────────────── */
.theme-toggle { position:fixed; top:16px; right:16px; z-index:50;
  width:42px; height:42px; border-radius:50%; cursor:pointer; font-size:1.05rem;
  background:var(--glass); color:var(--ink); border:1px solid var(--line);
  backdrop-filter:blur(10px); box-shadow:var(--shadow-s); transition:transform .18s ease; }
.theme-toggle:hover { transform:rotate(-18deg) scale(1.08); }
.admin-link, .back {
  display:inline-flex; align-items:center; gap:7px; text-decoration:none;
  font:600 .82rem 'Inter', sans-serif; color:var(--muted);
  background:var(--card); border:1px solid var(--line); border-radius:999px;
  padding:8px 16px; box-shadow:var(--shadow-s); transition:.18s ease; }
.admin-link:hover, .back:hover { color:var(--ink); border-color:var(--line-2); transform:translateY(-1px); }

/* ── Tabs ─────────────────────────────────── */
.tabbar { position:sticky; top:0; z-index:40; margin:0 -20px 30px; padding:14px 20px;
  background:var(--glass); border-bottom:1px solid var(--line);
  backdrop-filter:blur(18px) saturate(1.5); }
.tabs { display:flex; gap:6px; justify-content:center; margin:0 auto; padding:5px;
  width:fit-content; max-width:100%; background:var(--bg-2);
  border:1px solid var(--line); border-radius:999px; box-shadow:var(--shadow-s); }
.tab { appearance:none; border:0; background:transparent; color:var(--ink-2); cursor:pointer;
  border-radius:999px; padding:10px 22px; font:600 .92rem 'Inter', sans-serif;
  display:flex; align-items:center; gap:8px; white-space:nowrap; transition:.2s ease; }
.tab:hover { color:var(--ink); }
.tab.active { color:#fff; box-shadow:var(--shadow-s); }
.tab[data-accent="ai"].active { background:linear-gradient(135deg, var(--ai), var(--ai-2)); }
.tab[data-accent="sa"].active { background:linear-gradient(135deg, var(--sa), var(--sa-2)); }
:root[data-theme="dark"] .tab.active { color:#14130f; }
.panel { display:none; }
.panel.active { display:block; animation:rise .4s cubic-bezier(.22,1,.36,1); }
@keyframes rise { from { opacity:0; transform:translateY(10px); } to { opacity:1; transform:none; } }

/* ── Cards ────────────────────────────────── */
.card { position:relative; background:var(--card); border:1px solid var(--line);
  border-radius:var(--radius); padding:34px 38px; box-shadow:var(--shadow-m); overflow:hidden; }
.card::before { content:''; position:absolute; inset:0 0 auto 0; height:3px; }
.panel-ai .card::before, .page-ai .card::before { background:linear-gradient(90deg, var(--ai), var(--ai-2)); }
.panel-sa .card::before, .page-sa .card::before { background:linear-gradient(90deg, var(--sa), var(--sa-2)); }
.cardhead { display:flex; align-items:center; justify-content:space-between; gap:14px;
  flex-wrap:wrap; padding-bottom:18px; margin-bottom:10px; border-bottom:1px solid var(--line); }
.cardhead h2 { margin:0; font-family:'Fraunces', Georgia, serif; font-weight:600;
  font-size:1.5rem; letter-spacing:-.015em; }
.badge { font:700 .68rem 'Inter', sans-serif; letter-spacing:.1em; text-transform:uppercase;
  padding:6px 13px; border-radius:999px; white-space:nowrap;
  background:var(--bg-2); color:var(--muted); border:1px solid var(--line); }
.badge.ai { background:var(--ai-soft); color:var(--ai); border-color:transparent; }
.badge.sa { background:var(--sa-soft); color:var(--sa); border-color:transparent; }

/* ── Report typography ────────────────────── */
.report { font-family:'Newsreader', Georgia, serif; font-size:1.09rem; line-height:1.72; color:var(--ink-2); }
.report h1 { display:none; } /* the card header already carries the title */
.report h1, .report h2, .report h3 { font-family:'Fraunces', Georgia, serif; color:var(--ink);
  line-height:1.22; letter-spacing:-.018em; }
.report h2 { font-size:1.32rem; margin:2.1em 0 .6em; padding-top:1.2em; border-top:1px solid var(--line);
  display:flex; align-items:baseline; gap:11px; }
.report h2::before { content:''; flex:none; width:7px; height:7px; border-radius:50%; transform:translateY(-2px); }
.panel-ai .report h2::before, .page-ai .report h2::before { background:var(--ai); }
.panel-sa .report h2::before, .page-sa .report h2::before { background:var(--sa); }
.report h3 { font-size:1.08rem; margin:1.6em 0 .45em; }
.report p { margin:.85em 0; }
.report ul, .report ol { padding-inline-start:1.35em; }
.report li { margin:.5em 0; }
.report li::marker { color:var(--muted); }
.report strong { font-weight:600; color:var(--ink); }
.report em { font-style:italic; }
.report hr { border:0; height:1px; background:var(--line); margin:2.4em 0; }
.report blockquote { margin:1.4em 0; padding:.6em 1.4em; border-inline-start:3px solid var(--line-2);
  color:var(--muted); font-style:italic; }
.report code { font:.84em ui-monospace, Menlo, monospace; background:var(--bg-2);
  border:1px solid var(--line); padding:2px 7px; border-radius:6px; color:var(--ink); }
.report table { border-collapse:separate; border-spacing:0; width:100%; margin:1.5em 0;
  display:block; overflow-x:auto; font-family:'Inter', sans-serif; font-size:.88rem;
  border:1px solid var(--line); border-radius:12px; }
.report th, .report td { padding:11px 15px; text-align:start; white-space:nowrap;
  border-bottom:1px solid var(--line); }
.report tr:last-child td { border-bottom:0; }
.report th { background:var(--bg-2); font-weight:600; color:var(--ink);
  font-size:.76rem; letter-spacing:.06em; text-transform:uppercase; }
.report tbody tr:hover td { background:var(--bg-2); }
.report a { text-decoration:none; font-weight:500;
  background-image:linear-gradient(currentColor, currentColor);
  background-size:0% 1px; background-repeat:no-repeat; background-position:0 100%;
  transition:background-size .25s ease; }
.report a:hover { background-size:100% 1px; }
.panel-ai .report a, .page-ai .report a { color:var(--ai); }
.panel-sa .report a, .page-sa .report a { color:var(--sa); }

/* ── Archive ──────────────────────────────── */
.archive { margin-top:34px; padding-top:24px; border-top:1px solid var(--line); }
.archive h3 { font:600 .72rem 'Inter', sans-serif; letter-spacing:.2em; text-transform:uppercase;
  color:var(--muted); margin:0 0 14px; }
.archive-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(132px, 1fr)); gap:9px; }
.archive-grid a { text-decoration:none; color:var(--ink); background:var(--bg-2);
  border:1px solid var(--line); border-radius:11px; padding:11px 14px;
  font:600 .86rem 'Inter', sans-serif; transition:.18s ease; }
.archive-grid a:hover { transform:translateY(-2px); box-shadow:var(--shadow-s); }
.panel-ai .archive-grid a:hover { border-color:var(--ai); color:var(--ai); }
.panel-sa .archive-grid a:hover { border-color:var(--sa); color:var(--sa); }
.empty { color:var(--muted); font-style:italic; }

/* ── Console ──────────────────────────────── */
.admin-card { margin-bottom:22px; }
.admin-card .hint { color:var(--muted); font-size:.89rem; margin:14px 0 18px; line-height:1.6; }
.admin-card .hint a, .field .sub a { color:inherit; text-decoration:underline; text-underline-offset:2px; }
.row { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
.btn { appearance:none; border:1px solid var(--line-2); background:var(--card); color:var(--ink);
  border-radius:11px; padding:10px 18px; font:600 .9rem 'Inter', sans-serif; cursor:pointer;
  transition:.18s ease; }
.btn:hover:not(:disabled) { transform:translateY(-1px); box-shadow:var(--shadow-s); }
.btn:disabled { opacity:.42; cursor:not-allowed; }
.btn.primary { background:linear-gradient(135deg, var(--ink), var(--ink-2));
  border-color:transparent; color:var(--card); }
.btn.accent-ai { border-color:var(--ai); color:var(--ai); }
.btn.accent-sa { border-color:var(--sa); color:var(--sa); }
.btn.ghost { color:var(--muted); background:transparent; }
.btn.small { padding:6px 13px; font-size:.8rem; }
input[type=password], input[type=text], input[type=number] {
  font:400 .92rem 'Inter', sans-serif; padding:11px 14px; border-radius:11px;
  border:1px solid var(--line-2); background:var(--bg); color:var(--ink); min-width:0;
  transition:.18s ease; }
input:focus { outline:0; border-color:var(--ai); box-shadow:0 0 0 3px var(--ai-soft); }
input[type=password], input[type=text] { flex:1; }
input[type=number] { width:120px; }
.field { margin:0 0 20px; display:flex; flex-direction:column; gap:7px; }
.field > label:first-child { font:600 .72rem 'Inter', sans-serif; letter-spacing:.16em;
  text-transform:uppercase; color:var(--muted); }
.field .sub { font-size:.81rem; color:var(--muted); }
label.check { display:flex; align-items:center; gap:9px; font-size:.93rem; cursor:pointer; }
label.check input { width:17px; height:17px; accent-color:var(--sa); }
.status { font-size:.87rem; margin:15px 0 0; min-height:1.2em; color:var(--muted); }
.status.ok { color:var(--sa); font-weight:500; } .status.err { color:#c0392b; font-weight:500; }
:root[data-theme="dark"] .status.err { color:#f08472; }
.run { display:flex; align-items:center; gap:13px; padding:12px 14px; border:1px solid var(--line);
  border-radius:12px; margin-bottom:9px; text-decoration:none; color:var(--ink); transition:.18s ease; }
.run:hover { border-color:var(--line-2); transform:translateX(2px); }
.run-icon { font-size:1rem; width:20px; text-align:center; }
.run-ok .run-icon { color:var(--sa); } .run-bad .run-icon { color:#c0392b; } .run-live .run-icon { color:var(--ai); }
.run-title { flex:1; font:600 .9rem 'Inter', sans-serif; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.run-meta { font-size:.78rem; color:var(--muted); white-space:nowrap; }

/* ── Footer / misc ────────────────────────── */
.pagecard { margin-top:20px; }
.topbar { padding:26px 0 0; }
footer { margin-top:52px; padding-top:22px; border-top:1px solid var(--line);
  color:var(--muted); font-size:.82rem; text-align:center; line-height:1.9; }
footer a { color:inherit; }
#totop { position:fixed; right:16px; bottom:16px; z-index:50; width:42px; height:42px;
  border-radius:50%; border:1px solid var(--line); background:var(--glass); color:var(--ink);
  cursor:pointer; backdrop-filter:blur(10px); box-shadow:var(--shadow-s);
  opacity:0; pointer-events:none; transition:.25s ease; }
#totop.show { opacity:1; pointer-events:auto; }
@media (max-width:600px) {
  .card { padding:24px 20px; border-radius:15px; }
  .hero { padding:60px 18px 44px; }
  .tab { padding:9px 16px; font-size:.86rem; }
  .report { font-size:1.04rem; }
}
`;

const THEME_JS = `
(function(){
  var s = localStorage.getItem('theme');
  if (s ? s === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches)
    document.documentElement.dataset.theme = 'dark';
})();
`;

const CHROME_JS = `
addEventListener('DOMContentLoaded', function(){
  var t = document.querySelector('.theme-toggle');
  if (t) {
    var sync = function(){ t.textContent = document.documentElement.dataset.theme === 'dark' ? '☀' : '☾'; };
    sync();
    t.addEventListener('click', function(){
      var d = document.documentElement.dataset.theme === 'dark';
      if (d) delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = 'dark';
      localStorage.setItem('theme', d ? 'light' : 'dark');
      sync();
    });
  }
  var bar = document.getElementById('progress'), top = document.getElementById('totop');
  var onScroll = function(){
    var h = document.documentElement.scrollHeight - innerHeight;
    if (bar) bar.style.width = (h > 0 ? (scrollY / h) * 100 : 0) + '%';
    if (top) top.classList.toggle('show', scrollY > 600);
  };
  addEventListener('scroll', onScroll, { passive:true }); onScroll();
  if (top) top.addEventListener('click', function(){ scrollTo({ top:0, behavior:'smooth' }); });
});
`;

const TABS_JS = `
addEventListener('DOMContentLoaded', function(){
  var tabs = [].slice.call(document.querySelectorAll('.tab'));
  if (!tabs.length) return;
  function activate(slug, push){
    tabs.forEach(function(t){ t.classList.toggle('active', t.dataset.slug === slug); });
    [].slice.call(document.querySelectorAll('.panel')).forEach(function(p){
      p.classList.toggle('active', p.id === slug);
    });
    if (push) history.replaceState(null, '', '#' + slug);
  }
  tabs.forEach(function(t){ t.addEventListener('click', function(){ activate(t.dataset.slug, true); }); });
  var initial = location.hash.slice(1);
  activate(tabs.some(function(t){ return t.dataset.slug === initial; }) ? initial : tabs[0].dataset.slug, false);
});
`;

function shell({ title, hero = '', body, bodyClass = '', extraJs = '' }) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="Daily AI industry and Saudi stock market briefings, researched and written automatically every morning.">
<meta name="color-scheme" content="light dark">
<title>${title}</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="${FONTS}">
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📰</text></svg>">
<style>${CSS}</style>
<script>${THEME_JS}</script>
</head>
<body class="${bodyClass}">
<div id="progress"></div>
<button class="theme-toggle" aria-label="Toggle dark mode">☾</button>
${hero}
<div class="wrap">
${body}
<footer>Researched and written automatically every morning at 07:00 Riyadh time<br>
<a href="https://github.com/engdarkbot-netizen/Ai" rel="noopener">source on GitHub</a> ·
last updated ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC</footer>
</div>
<button id="totop" aria-label="Back to top">↑</button>
<script>${CHROME_JS}</script>
${extraJs ? `<script>${extraJs}</script>` : ''}
</body>
</html>`;
}

const dateOf = (file, slug) => file.slice(slug.length + 1, -3);
const prettyDate = iso =>
  new Date(iso + 'T00:00:00Z').toLocaleDateString('en-GB', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC',
  });

const files = fs.existsSync(REPORTS_DIR) ? fs.readdirSync(REPORTS_DIR).filter(f => f.endsWith('.md')) : [];

fs.rmSync(SITE_DIR, { recursive: true, force: true });
fs.mkdirSync(path.join(SITE_DIR, 'reports'), { recursive: true });

/* ── index.html ── */
const days = new Set(files.map(f => f.slice(-13, -3))).size;
const stats = `<div class="stats">
<div class="stat"><b>2</b><span>Agents</span></div>
<div class="stat"><b>${files.length}</b><span>Briefings</span></div>
<div class="stat"><b>${days}</b><span>Days covered</span></div>
<div class="stat"><b>Daily</b><span>07:00 AST</span></div>
</div>`;

const heroIndex = `<header class="hero"><div class="hero-inner">
<p class="kicker">Artificial Intelligence · Saudi Markets</p>
<h1>The Daily Brief</h1>
<p class="dateline">${prettyDate(new Date().toISOString().slice(0, 10))}</p>
${stats}
<p class="hero-actions"><a class="admin-link" href="admin.html">⚙︎ Agent console</a></p>
</div></header>`;

const tabButtons = AGENTS.map(a =>
  `<button class="tab" data-slug="${a.slug}" data-accent="${a.accent}">${a.emoji} ${a.title}</button>`
).join('');

let panels = '';
for (const a of AGENTS) {
  const reports = files.filter(f => f.startsWith(`${a.slug}-`)).sort().reverse();
  let inner;
  if (reports.length === 0) {
    inner = `<p class="empty">No briefings yet — the first one appears after the next scheduled run.</p>`;
  } else {
    const latest = reports[0];
    const md = fs.readFileSync(path.join(REPORTS_DIR, latest), 'utf8');
    inner = `<div class="cardhead">
<h2>${a.longTitle}</h2>
<span class="badge ${a.accent}">${prettyDate(dateOf(latest, a.slug))}</span>
</div>
<div class="report">${marked.parse(md)}</div>`;
    if (reports.length > 1) {
      inner += `<div class="archive"><h3>Previous briefings</h3><div class="archive-grid">` +
        reports.slice(1).map(r =>
          `<a href="reports/${r.replace(/\.md$/, '.html')}">${dateOf(r, a.slug)}</a>`
        ).join('') + `</div></div>`;
    }
  }
  panels += `<section class="panel panel-${a.accent}" id="${a.slug}"><div class="card">${inner}</div></section>`;
}

fs.writeFileSync(
  path.join(SITE_DIR, 'index.html'),
  shell({
    title: 'The Daily Brief — AI & Saudi Markets',
    hero: heroIndex,
    body: `<div class="tabbar"><nav class="tabs">${tabButtons}</nav></div>${panels}`,
    extraJs: TABS_JS,
  }),
);

/* ── admin console ── */
if (fs.existsSync('./admin-template.html')) {
  const heroAdmin = `<header class="hero"><div class="hero-inner">
<p class="kicker">Control Panel</p>
<h1>Agent Console</h1>
<p class="dateline">Run the agents, tune their settings, watch every run</p>
<p class="hero-actions"><a class="back" href="index.html">← Back to briefings</a></p>
</div></header>`;
  fs.writeFileSync(
    path.join(SITE_DIR, 'admin.html'),
    shell({
      title: 'Agent Console — The Daily Brief',
      hero: heroAdmin,
      body: `<div class="topbar"></div>${fs.readFileSync('./admin-template.html', 'utf8')}`,
    }),
  );
}

/* ── individual report pages ── */
for (const f of files) {
  const agent = AGENTS.find(a => f.startsWith(`${a.slug}-`));
  if (!agent) continue;
  const md = fs.readFileSync(path.join(REPORTS_DIR, f), 'utf8');
  const date = dateOf(f, agent.slug);
  const hero = `<header class="hero"><div class="hero-inner">
<p class="kicker">${agent.emoji} Archive</p>
<h1>${agent.title}</h1>
<p class="dateline">${prettyDate(date)}</p>
<p class="hero-actions"><a class="back" href="../index.html#${agent.slug}">← Back to latest briefings</a></p>
</div></header>`;
  const body = `<section class="panel panel-${agent.accent} active pagecard"><div class="card">
<div class="cardhead"><h2>${agent.longTitle}</h2><span class="badge ${agent.accent}">${prettyDate(date)}</span></div>
<div class="report">${marked.parse(md)}</div>
</div></section>`;
  fs.writeFileSync(
    path.join(SITE_DIR, 'reports', f.replace(/\.md$/, '.html')),
    shell({ title: `${agent.longTitle} — ${date}`, hero, body, bodyClass: `page-${agent.accent}` }),
  );
}

console.log(`Site built: ${SITE_DIR} (${files.length} report(s))`);
