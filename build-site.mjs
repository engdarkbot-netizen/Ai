/**
 * Static site builder for the news agents.
 *
 * Reads all markdown reports in ./reports and generates ./site:
 *   site/index.html          - tabbed page: latest AI news + Saudi stocks briefings + archives
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
  { slug: 'ai-news',      emoji: '🧠', title: 'AI News',       longTitle: 'Daily AI News',                    accent: 'ai' },
  { slug: 'saudi-stocks', emoji: '📈', title: 'Saudi Markets', longTitle: 'Saudi Stock Market (Tadawul)',     accent: 'sa' },
];

const CSS = `
:root {
  --bg:#f6f4ee; --card:#fffdf8; --ink:#211f1b; --muted:#7a7568; --line:#e5dfd2;
  --ai:#a34e1b; --ai-soft:#f6e3d4; --sa:#0d7a52; --sa-soft:#d9efe4;
  --shadow: 0 1px 3px rgba(40,35,20,.06), 0 8px 24px rgba(40,35,20,.05);
}
:root[data-theme="dark"] {
  --bg:#161513; --card:#1e1d1a; --ink:#eae7df; --muted:#98937f; --line:#33312a;
  --ai:#e08b52; --ai-soft:#3a2b1f; --sa:#43bd8b; --sa-soft:#1c2f27;
  --shadow: 0 1px 3px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.3);
}
* { box-sizing:border-box; }
html { scroll-behavior:smooth; }
body {
  margin:0; background:var(--bg); color:var(--ink);
  font:16px/1.65 -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
  -webkit-font-smoothing:antialiased;
}
.wrap { max-width:820px; margin:0 auto; padding:0 20px 72px; }

/* ── Masthead ─────────────────────────────── */
.masthead { padding:36px 0 20px; text-align:center; border-bottom:3px double var(--line); }
.masthead .kicker {
  font-size:.72rem; letter-spacing:.28em; text-transform:uppercase; color:var(--muted); margin:0 0 10px;
}
.masthead h1 {
  margin:0; font-family:Georgia, "Times New Roman", serif; font-weight:600;
  font-size:clamp(2rem, 6vw, 3rem); letter-spacing:-.01em;
}
.masthead .dateline { color:var(--muted); font-size:.85rem; margin:10px 0 0; }
.theme-toggle {
  position:absolute; top:18px; right:18px; background:var(--card); color:var(--ink);
  border:1px solid var(--line); border-radius:999px; width:38px; height:38px;
  font-size:1rem; cursor:pointer; box-shadow:var(--shadow);
}

/* ── Tabs ─────────────────────────────────── */
.tabs { display:flex; gap:10px; justify-content:center; margin:24px 0 28px; flex-wrap:wrap; }
.tab {
  appearance:none; border:1px solid var(--line); background:var(--card); color:var(--ink);
  border-radius:999px; padding:9px 20px; font-size:.92rem; font-weight:600; cursor:pointer;
  display:flex; align-items:center; gap:8px; transition:transform .12s ease;
}
.tab:hover { transform:translateY(-1px); }
.tab[data-accent="ai"].active { background:var(--ai); border-color:var(--ai); color:#fff; }
.tab[data-accent="sa"].active { background:var(--sa); border-color:var(--sa); color:#fff; }
:root[data-theme="dark"] .tab.active { color:#161513; }
.panel { display:none; }
.panel.active { display:block; animation:fade .25s ease; }
@keyframes fade { from { opacity:0; transform:translateY(4px);} to { opacity:1; transform:none;} }

/* ── Briefing card ────────────────────────── */
.card {
  background:var(--card); border:1px solid var(--line); border-radius:14px;
  padding:30px 34px; box-shadow:var(--shadow);
}
.card .cardhead { display:flex; align-items:baseline; justify-content:space-between; gap:12px;
  flex-wrap:wrap; border-bottom:1px solid var(--line); padding-bottom:14px; margin-bottom:6px; }
.card .cardhead h2 { margin:0; font-family:Georgia, serif; font-weight:600; font-size:1.35rem; }
.badge { font-size:.72rem; font-weight:700; letter-spacing:.08em; text-transform:uppercase;
  padding:4px 10px; border-radius:999px; white-space:nowrap; }
.badge.ai { background:var(--ai-soft); color:var(--ai); }
.badge.sa { background:var(--sa-soft); color:var(--sa); }

/* ── Report typography ────────────────────── */
.report { font-family:Georgia, "Times New Roman", serif; font-size:1.02rem; }
.report > h1:first-child { display:none; } /* title already in card header */
.report h1, .report h2, .report h3 { font-family:Georgia, serif; line-height:1.3; letter-spacing:-.01em; }
.report h2 { font-size:1.22rem; margin:1.9em 0 .5em; padding-top:1.1em; border-top:1px solid var(--line); }
.report h3 { font-size:1.05rem; margin:1.4em 0 .4em; }
.report p, .report li { color:var(--ink); }
.report li { margin:.35em 0; }
.report strong { font-weight:700; }
.report hr { border:none; border-top:1px solid var(--line); margin:2em 0; }
.report blockquote { margin:1em 0; padding:.2em 1.2em; border-inline-start:3px solid var(--line); color:var(--muted); }
.report code { font-family:ui-monospace, Menlo, monospace; font-size:.85em;
  background:var(--bg); border:1px solid var(--line); padding:1px 6px; border-radius:5px; }
.report table { border-collapse:collapse; width:100%; margin:14px 0; display:block; overflow-x:auto;
  font-family:-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; font-size:.9rem; }
.report th, .report td { border:1px solid var(--line); padding:8px 12px; text-align:start; white-space:nowrap; }
.report th { background:var(--bg); font-weight:700; }
.panel-ai .report a, .page-ai .report a { color:var(--ai); }
.panel-sa .report a, .page-sa .report a { color:var(--sa); }
.report a { text-decoration:none; border-bottom:1px solid transparent; }
.report a:hover { border-bottom-color:currentColor; }

/* ── Archive ──────────────────────────────── */
.archive { margin-top:26px; }
.archive h3 { font-size:.78rem; letter-spacing:.18em; text-transform:uppercase; color:var(--muted); margin:0 0 12px; }
.archive-grid { display:flex; flex-wrap:wrap; gap:8px; }
.archive-grid a {
  font-size:.85rem; font-weight:600; text-decoration:none; color:var(--ink);
  background:var(--card); border:1px solid var(--line); border-radius:8px; padding:6px 12px;
}
.panel-ai .archive-grid a:hover { border-color:var(--ai); color:var(--ai); }
.panel-sa .archive-grid a:hover { border-color:var(--sa); color:var(--sa); }
.empty { color:var(--muted); font-style:italic; }

/* ── Report page / footer ─────────────────── */
.back { display:inline-block; margin:22px 0 0; font-size:.9rem; font-weight:600; text-decoration:none; color:var(--muted); }
.back:hover { color:var(--ink); }
.pagecard { margin-top:18px; }
footer { margin-top:44px; padding-top:18px; border-top:1px solid var(--line);
  color:var(--muted); font-size:.8rem; text-align:center; }
footer a { color:inherit; }
@media (max-width:560px) { .card { padding:22px 18px; } }
`;

const THEME_JS = `
(function(){
  var saved = localStorage.getItem('theme');
  var dark = saved ? saved === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
  if (dark) document.documentElement.dataset.theme = 'dark';
  addEventListener('DOMContentLoaded', function(){
    var b = document.querySelector('.theme-toggle');
    if (!b) return;
    var sync = function(){ b.textContent = document.documentElement.dataset.theme === 'dark' ? '☀' : '☾'; };
    sync();
    b.addEventListener('click', function(){
      var d = document.documentElement.dataset.theme === 'dark';
      if (d) delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = 'dark';
      localStorage.setItem('theme', d ? 'light' : 'dark');
      sync();
    });
  });
})();
`;

const TABS_JS = `
addEventListener('DOMContentLoaded', function(){
  var tabs = [].slice.call(document.querySelectorAll('.tab'));
  function activate(slug, push){
    tabs.forEach(function(t){ t.classList.toggle('active', t.dataset.slug === slug); });
    [].slice.call(document.querySelectorAll('.panel')).forEach(function(p){
      p.classList.toggle('active', p.id === slug);
    });
    if (push) history.replaceState(null, '', '#' + slug);
  }
  tabs.forEach(function(t){
    t.addEventListener('click', function(){ activate(t.dataset.slug, true); });
  });
  var initial = location.hash.slice(1);
  activate(tabs.some(function(t){ return t.dataset.slug === initial; }) ? initial : tabs[0].dataset.slug, false);
});
`;

function shell({ title, body, bodyClass = '', extraJs = '' }) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="Daily AI industry and Saudi stock market briefings, generated automatically every morning.">
<title>${title}</title>
<style>${CSS}</style>
<script>${THEME_JS}</script>
</head>
<body class="${bodyClass}">
<button class="theme-toggle" aria-label="Toggle dark mode">☾</button>
<div class="wrap">
${body}
<footer>Generated automatically every morning at 07:00 Riyadh time ·
<a href="https://github.com/engdarkbot-netizen/Ai" rel="noopener">source</a> ·
updated ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC</footer>
</div>
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
const masthead = `<header class="masthead">
<p class="kicker">AI · Markets · Every Morning</p>
<h1>The Daily Brief</h1>
<p class="dateline">${prettyDate(new Date().toISOString().slice(0, 10))}</p>
</header>`;

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
    body: `${masthead}<nav class="tabs">${tabButtons}</nav>${panels}`,
    extraJs: TABS_JS,
  }),
);

/* ── individual report pages ── */
for (const f of files) {
  const agent = AGENTS.find(a => f.startsWith(`${a.slug}-`));
  if (!agent) continue;
  const md = fs.readFileSync(path.join(REPORTS_DIR, f), 'utf8');
  const date = dateOf(f, agent.slug);
  const body = `<a class="back" href="../index.html#${agent.slug}">← Back to latest briefings</a>
<section class="panel panel-${agent.accent} active pagecard"><div class="card">
<div class="cardhead"><h2>${agent.longTitle}</h2><span class="badge ${agent.accent}">${prettyDate(date)}</span></div>
<div class="report">${marked.parse(md)}</div>
</div></section>`;
  fs.writeFileSync(
    path.join(SITE_DIR, 'reports', f.replace(/\.md$/, '.html')),
    shell({ title: `${agent.longTitle} — ${date}`, body, bodyClass: `page-${agent.accent}` }),
  );
}

console.log(`Site built: ${SITE_DIR} (${files.length} report(s))`);
