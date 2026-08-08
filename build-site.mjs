/**
 * Static site builder for the news agents.
 *
 * Reads all markdown reports in ./reports and generates ./site:
 *   site/index.html          - segmented AI News / Saudi Markets briefings + archives
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

/* ── Design tokens: near-black/white neutrals, system font stack (renders
   as real San Francisco on Apple devices), vivid accent glows. ── */
const CSS = `
:root {
  --nav-h:56px;
  --bg:#ffffff; --bg-2:#f5f5f7; --card:#ffffff;
  --ink:#1d1d1f; --ink-2:#424245; --muted:#86868b;
  --line:#d2d2d7; --line-2:#c4c4c9;
  --ai:#d9530f; --ai-2:#ff8c42; --ai-soft:#fdece0;
  --sa:#0a8f5f; --sa-2:#22d68f; --sa-soft:#dcf7ec;
  --glass:rgba(255,255,255,.72);
  --shadow-s:0 1px 2px rgba(0,0,0,.04), 0 1px 1px rgba(0,0,0,.03);
  --shadow-m:0 2px 5px rgba(0,0,0,.04), 0 24px 48px -16px rgba(0,0,0,.14);
  --radius:26px;
}
:root[data-theme="dark"] {
  --bg:#000000; --bg-2:#0a0a0c; --card:#1c1c1e;
  --ink:#f5f5f7; --ink-2:#d1d1d6; --muted:#8e8e93;
  --line:#38383a; --line-2:#48484a;
  --ai:#ff9f4d; --ai-2:#ffc487; --ai-soft:#3a2412;
  --sa:#30d98f; --sa-2:#6ff4b7; --sa-soft:#0d2e21;
  --glass:rgba(10,10,12,.68);
  --shadow-s:0 1px 2px rgba(0,0,0,.5);
  --shadow-m:0 2px 5px rgba(0,0,0,.5), 0 24px 48px -16px rgba(0,0,0,.7);
}
* { box-sizing:border-box; }
html { scroll-behavior:smooth; -webkit-text-size-adjust:100%; }
body {
  margin:0; background:var(--bg); color:var(--ink);
  font-family:-apple-system, BlinkMacSystemFont, "SF Pro Display", "SF Pro Text",
    "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  font-size:16px; line-height:1.5; -webkit-font-smoothing:antialiased; overflow-x:hidden;
}
.wrap { max-width:900px; margin:0 auto; padding:0 22px 100px; }
::selection { background:var(--ai-soft); }

/* ── Reading progress ─────────────────────── */
#progress { position:fixed; inset:0 auto auto 0; height:2px; width:0; z-index:80;
  background:linear-gradient(90deg, var(--ai), var(--sa)); }

/* ── Navbar ───────────────────────────────── */
.navbar { position:fixed; top:0; left:0; right:0; z-index:70; height:var(--nav-h);
  display:flex; align-items:center; justify-content:space-between; padding:0 24px;
  background:var(--glass); backdrop-filter:blur(20px) saturate(1.8);
  -webkit-backdrop-filter:blur(20px) saturate(1.8);
  border-bottom:1px solid transparent; transition:border-color .3s ease, box-shadow .3s ease; }
.navbar.scrolled { border-color:var(--line); box-shadow:0 1px 0 var(--line); }
.nav-brand { display:flex; align-items:center; gap:8px; text-decoration:none; color:var(--ink);
  font-weight:600; font-size:.95rem; letter-spacing:-.01em; }
.nav-brand .dot { width:7px; height:7px; border-radius:50%;
  background:linear-gradient(135deg, var(--ai), var(--sa)); }
.nav-right { display:flex; align-items:center; gap:6px; }
.nav-link { text-decoration:none; color:var(--ink-2); font-size:.86rem; font-weight:500;
  padding:7px 14px; border-radius:980px; transition:background .2s ease, color .2s ease; }
.nav-link:hover { background:var(--bg-2); color:var(--ink); }
.theme-toggle { width:32px; height:32px; border-radius:50%; cursor:pointer; font-size:.95rem;
  background:transparent; color:var(--ink); border:1px solid var(--line);
  transition:transform .18s ease, background .2s ease; display:flex; align-items:center; justify-content:center; }
.theme-toggle:hover { transform:rotate(-16deg); background:var(--bg-2); }

/* ── Hero ─────────────────────────────────── */
.hero { position:relative; overflow:hidden; padding:calc(var(--nav-h) + 64px) 22px 60px;
  text-align:center; background:var(--bg); }
.blob { position:absolute; border-radius:50%; filter:blur(80px); opacity:.55;
  pointer-events:none; will-change:transform; animation:drift 24s ease-in-out infinite alternate; }
.blob.b1 { width:56vw; max-width:620px; height:56vw; max-height:620px; top:-30%; left:-14%;
  background:radial-gradient(circle, var(--ai-2), transparent 68%); }
.blob.b2 { width:50vw; max-width:560px; height:50vw; max-height:560px; top:-26%; right:-12%;
  background:radial-gradient(circle, var(--sa-2), transparent 68%); animation-delay:-10s; }
.blob.b3 { width:34vw; max-width:360px; height:34vw; max-height:360px; bottom:-22%; left:34%;
  background:radial-gradient(circle, var(--ai), transparent 70%); opacity:.28; animation-delay:-5s; }
:root[data-theme="dark"] .blob { opacity:.4; }
:root[data-theme="dark"] .blob.b3 { opacity:.22; }
@keyframes drift { from { transform:translate3d(-24px,-10px,0) scale(1); }
                   to   { transform:translate3d(28px,20px,0) scale(1.18); } }
.hero-inner { position:relative; z-index:1; max-width:780px; margin:0 auto; }
.kicker { font:600 .72rem/1 -apple-system, sans-serif; letter-spacing:.24em; text-transform:uppercase;
  color:var(--muted); margin:0 0 20px; opacity:0; animation:fadeUp .7s cubic-bezier(.16,1,.3,1) .05s forwards; }

.hero h1 { margin:0; font-weight:700; font-size:clamp(2.6rem, 8.4vw, 5.4rem);
  line-height:1.04; letter-spacing:-.035em; color:var(--ink); }
.hero h1 .w { display:inline-block; overflow:hidden; padding-bottom:.08em; margin-bottom:-.08em; vertical-align:top; }
.hero h1 .wi { display:inline-block; transform:translateY(112%); opacity:0;
  background:linear-gradient(135deg, var(--ink) 20%, var(--ai) 160%);
  -webkit-background-clip:text; background-clip:text; color:transparent;
  animation:wordIn 1s cubic-bezier(.16,1,.3,1) forwards; }
@keyframes wordIn { to { transform:translateY(0); opacity:1; } }
@keyframes fadeUp { from { opacity:0; transform:translateY(14px); } to { opacity:1; transform:none; } }

.subhead { max-width:520px; margin:22px auto 0; color:var(--muted);
  font-size:clamp(1.02rem, 2vw, 1.18rem); line-height:1.5; font-weight:400;
  opacity:0; animation:fadeUp .8s cubic-bezier(.16,1,.3,1) .5s forwards; }

.trust-row { display:flex; gap:8px; justify-content:center; flex-wrap:wrap; margin:24px 0 0;
  opacity:0; animation:fadeUp .8s cubic-bezier(.16,1,.3,1) .62s forwards; }
.trust-pill { display:inline-flex; align-items:center; gap:6px; font:600 .74rem -apple-system, sans-serif;
  color:var(--ink-2); background:var(--bg-2); border:1px solid var(--line);
  border-radius:999px; padding:6px 13px; }

.stat-row { display:flex; align-items:stretch; justify-content:center; flex-wrap:wrap;
  gap:0 30px; margin:44px 0 0;
  opacity:0; animation:fadeUp .8s cubic-bezier(.16,1,.3,1) .74s forwards; }
.stat-item { display:flex; flex-direction:column; align-items:center; gap:4px; padding:0 4px; position:relative; }
.stat-item + .stat-item::before { content:''; position:absolute; left:-15px; top:8px; bottom:8px;
  width:1px; background:var(--line); }
.stat-num { font-size:clamp(1.7rem, 4.2vw, 2.5rem); font-weight:700; letter-spacing:-.02em;
  font-variant-numeric:tabular-nums; color:var(--ink); }
.stat-label { font:600 .7rem -apple-system, sans-serif; letter-spacing:.06em; text-transform:uppercase; color:var(--muted); }

.scroll-cue { margin:56px auto 0; width:26px; height:42px; border:2px solid var(--line-2); border-radius:14px;
  position:relative; cursor:pointer; background:transparent;
  opacity:0; animation:fadeUp .8s cubic-bezier(.16,1,.3,1) .9s forwards; }
.scroll-cue::before { content:''; position:absolute; top:7px; left:50%; width:4px; height:8px; margin-left:-2px;
  border-radius:2px; background:var(--muted); animation:cueBob 1.8s ease-in-out infinite; }
@keyframes cueBob { 0%,100% { transform:translateY(0); opacity:1; } 50% { transform:translateY(9px); opacity:.3; } }

@media (prefers-reduced-motion:reduce) {
  .blob { animation:none; }
  .kicker, .hero h1 .wi, .subhead, .trust-row, .stat-row, .scroll-cue {
    animation:none !important; opacity:1 !important; transform:none !important; }
  .scroll-cue::before { animation:none; }
}

/* ── Scroll reveal ────────────────────────── */
.reveal { opacity:0; transform:translateY(24px);
  transition:opacity .7s cubic-bezier(.16,1,.3,1), transform .7s cubic-bezier(.16,1,.3,1); }
.reveal.in { opacity:1; transform:none; }
@media (prefers-reduced-motion:reduce) { .reveal { opacity:1; transform:none; transition:none; } }

/* ── Segmented control ────────────────────── */
.tabbar { position:sticky; top:var(--nav-h); z-index:40; margin:0 -22px 34px; padding:16px 22px;
  background:var(--glass); backdrop-filter:blur(18px) saturate(1.6); -webkit-backdrop-filter:blur(18px) saturate(1.6);
  border-bottom:1px solid var(--line); display:flex; justify-content:center; }
.segmented { position:relative; display:inline-flex; background:var(--bg-2); border:1px solid var(--line);
  border-radius:14px; padding:4px; }
.seg-indicator { position:absolute; top:4px; bottom:4px; left:4px; width:0; border-radius:11px;
  box-shadow:var(--shadow-s); transition:transform .5s cubic-bezier(.16,1,.3,1), width .5s cubic-bezier(.16,1,.3,1),
  background .35s ease; z-index:1; }
.seg-btn { position:relative; z-index:2; appearance:none; border:0; background:transparent; cursor:pointer;
  padding:9px 22px; border-radius:11px; font:600 .9rem -apple-system, sans-serif; color:var(--muted);
  display:flex; align-items:center; gap:7px; white-space:nowrap; transition:color .3s ease; }
.seg-btn.active { color:#fff; }
:root[data-theme="dark"] .seg-btn.active { color:#0a0a0c; }
.panel { display:none; }
.panel.active { display:block; animation:panelIn .5s cubic-bezier(.16,1,.3,1); }
@keyframes panelIn { from { opacity:0; transform:translateY(14px) scale(.99); } to { opacity:1; transform:none; } }

/* ── Cards ────────────────────────────────── */
.card { position:relative; background:var(--card); border:1px solid var(--line);
  border-radius:var(--radius); padding:42px 46px; box-shadow:var(--shadow-m);
  transition:box-shadow .3s ease, transform .3s ease; }
.cardhead { display:flex; align-items:center; justify-content:space-between; gap:14px;
  flex-wrap:wrap; padding-bottom:16px; }
.cardhead h2 { margin:0; font-weight:700; font-size:1.5rem; letter-spacing:-.02em; }
.badge { font:700 .68rem -apple-system, sans-serif; letter-spacing:.08em; text-transform:uppercase;
  padding:6px 13px; border-radius:999px; white-space:nowrap;
  background:var(--bg-2); color:var(--muted); border:1px solid var(--line); }
.badge.ai { background:var(--ai-soft); color:var(--ai); border-color:transparent; }
.badge.sa { background:var(--sa-soft); color:var(--sa); border-color:transparent; }

/* ── Provenance strip ─────────────────────── */
.prov { display:flex; gap:8px; flex-wrap:wrap; align-items:center;
  padding:0 0 20px; margin-bottom:10px; border-bottom:1px solid var(--line); }
.prov .chip { display:inline-flex; align-items:center; gap:6px;
  font:600 .74rem -apple-system, sans-serif; color:var(--muted);
  background:var(--bg-2); border:1px solid var(--line); border-radius:999px; padding:5px 12px; }
.prov .chip.ok { color:var(--sa); background:var(--sa-soft); border-color:transparent; }
.prov .chip code { font:600 .74rem ui-monospace, Menlo, monospace; color:var(--ink-2); }

/* ── Report typography ────────────────────── */
.report { font-size:1.06rem; line-height:1.68; color:var(--ink-2); }
.report h1 { display:none; }
.report h2, .report h3 { color:var(--ink); font-weight:700; letter-spacing:-.015em; }
.report h2 { font-size:1.28rem; margin:2.1em 0 .6em; padding-top:1.2em; border-top:1px solid var(--line);
  display:flex; align-items:baseline; gap:11px; }
.report h2::before { content:''; flex:none; width:7px; height:7px; border-radius:50%; transform:translateY(-2px); }
.panel-ai .report h2::before, .page-ai .report h2::before { background:var(--ai); }
.panel-sa .report h2::before, .page-sa .report h2::before { background:var(--sa); }
.report h3 { font-size:1.06rem; margin:1.6em 0 .45em; }
.report p { margin:.85em 0; }
.report ul, .report ol { padding-inline-start:1.3em; }
.report li { margin:.5em 0; }
.report li::marker { color:var(--muted); }
.report strong { font-weight:700; color:var(--ink); }
.report hr { border:0; height:1px; background:var(--line); margin:2.4em 0; }
.report blockquote { margin:1.4em 0; padding:.6em 1.4em; border-inline-start:3px solid var(--line-2);
  color:var(--muted); font-style:italic; }
.report code { font:.84em ui-monospace, Menlo, monospace; background:var(--bg-2);
  border:1px solid var(--line); padding:2px 7px; border-radius:6px; color:var(--ink); }
.report table { border-collapse:separate; border-spacing:0; width:100%; margin:1.5em 0;
  display:block; overflow-x:auto; font-size:.88rem;
  border:1px solid var(--line); border-radius:14px; }
.report th, .report td { padding:11px 15px; text-align:start; white-space:nowrap;
  border-bottom:1px solid var(--line); }
.report tr:last-child td { border-bottom:0; }
.report th { background:var(--bg-2); font-weight:700; color:var(--ink);
  font-size:.74rem; letter-spacing:.06em; text-transform:uppercase; }
.report tbody tr { transition:background .15s ease; }
.report tbody tr:hover td { background:var(--bg-2); }
.report a { text-decoration:none; font-weight:600;
  background-image:linear-gradient(currentColor, currentColor);
  background-size:0% 1px; background-repeat:no-repeat; background-position:0 100%;
  transition:background-size .25s ease; }
.report a:hover { background-size:100% 1px; }
.panel-ai .report a, .page-ai .report a { color:var(--ai); }
.panel-sa .report a, .page-sa .report a { color:var(--sa); }

/* ── Sources ──────────────────────────────── */
.sources { margin-top:32px; padding-top:24px; border-top:1px solid var(--line); }
.sources h3, .archive h3 { font:700 .72rem -apple-system, sans-serif; letter-spacing:.18em;
  text-transform:uppercase; color:var(--muted); margin:0 0 14px; }
.src-grid { display:flex; flex-wrap:wrap; gap:8px; }
.src-grid a { display:inline-flex; align-items:center; gap:7px; text-decoration:none;
  color:var(--ink-2); background:var(--bg-2); border:1px solid var(--line);
  border-radius:999px; padding:6px 13px; font:600 .8rem -apple-system, sans-serif;
  transition:transform .2s cubic-bezier(.16,1,.3,1), box-shadow .2s ease, border-color .2s ease, color .2s ease; }
.src-grid a b { font-weight:700; font-size:.72rem; color:var(--muted); }
.src-grid a:hover { transform:translateY(-2px); box-shadow:var(--shadow-s); }
.panel-ai .src-grid a:hover, .page-ai .src-grid a:hover { border-color:var(--ai); color:var(--ai); }
.panel-sa .src-grid a:hover, .page-sa .src-grid a:hover { border-color:var(--sa); color:var(--sa); }

/* ── Archive ──────────────────────────────── */
.archive { margin-top:32px; padding-top:24px; border-top:1px solid var(--line); }
.archive-scroll { display:flex; gap:10px; overflow-x:auto; padding:2px 2px 10px; scroll-snap-type:x proximity;
  -webkit-overflow-scrolling:touch; mask-image:linear-gradient(90deg, transparent, #000 16px, #000 calc(100% - 16px), transparent);
  -webkit-mask-image:linear-gradient(90deg, transparent, #000 16px, #000 calc(100% - 16px), transparent); }
.archive-scroll::-webkit-scrollbar { height:0; }
.archive-scroll a { flex:none; scroll-snap-align:start; text-decoration:none; color:var(--ink);
  background:var(--bg-2); border:1px solid var(--line); border-radius:13px; padding:12px 18px;
  font:600 .86rem -apple-system, sans-serif; transition:transform .2s cubic-bezier(.16,1,.3,1), box-shadow .2s ease; }
.archive-scroll a:hover { transform:translateY(-2px); box-shadow:var(--shadow-s); }
.panel-ai .archive-scroll a:hover { border-color:var(--ai); color:var(--ai); }
.panel-sa .archive-scroll a:hover { border-color:var(--sa); color:var(--sa); }
.empty { color:var(--muted); font-style:italic; }

/* ── Console ──────────────────────────────── */
.admin-card { margin-bottom:22px; }
.admin-card .hint { color:var(--muted); font-size:.89rem; margin:14px 0 18px; line-height:1.6; }
.admin-card .hint a, .field .sub a { color:inherit; text-decoration:underline; text-underline-offset:2px; }
.row { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
.btn { appearance:none; border:1px solid var(--line-2); background:var(--card); color:var(--ink);
  border-radius:12px; padding:10px 20px; font:600 .9rem -apple-system, sans-serif; cursor:pointer;
  transition:transform .18s cubic-bezier(.16,1,.3,1), box-shadow .18s ease, opacity .18s ease; }
.btn:hover:not(:disabled) { transform:translateY(-1px); box-shadow:var(--shadow-s); }
.btn:active:not(:disabled) { transform:scale(.97); }
.btn:disabled { opacity:.4; cursor:not-allowed; }
.btn.primary { background:var(--ink); border-color:var(--ink); color:var(--bg); }
.btn.accent-ai { border-color:var(--ai); color:var(--ai); }
.btn.accent-sa { border-color:var(--sa); color:var(--sa); }
.btn.ghost { color:var(--muted); background:transparent; }
.btn.small { padding:6px 13px; font-size:.8rem; }
input[type=password], input[type=text], input[type=number] {
  font:400 .92rem -apple-system, sans-serif; padding:11px 14px; border-radius:12px;
  border:1px solid var(--line-2); background:var(--bg); color:var(--ink); min-width:0;
  transition:border-color .18s ease, box-shadow .18s ease; }
input:focus { outline:0; border-color:var(--ai); box-shadow:0 0 0 4px var(--ai-soft); }
input[type=password], input[type=text] { flex:1; }
input[type=number] { width:120px; }
.field { margin:0 0 20px; display:flex; flex-direction:column; gap:7px; }
.field > label:first-child { font:700 .72rem -apple-system, sans-serif; letter-spacing:.14em;
  text-transform:uppercase; color:var(--muted); }
.field .sub { font-size:.81rem; color:var(--muted); }
label.check { display:flex; align-items:center; gap:9px; font-size:.93rem; cursor:pointer; }
label.check input { width:17px; height:17px; accent-color:var(--sa); }
.status { font-size:.87rem; margin:15px 0 0; min-height:1.2em; color:var(--muted); }
.status.ok { color:var(--sa); font-weight:600; } .status.err { color:#e0392b; font-weight:600; }
:root[data-theme="dark"] .status.err { color:#ff6b5b; }
.run { display:flex; align-items:center; gap:13px; padding:12px 14px; border:1px solid var(--line);
  border-radius:13px; margin-bottom:9px; text-decoration:none; color:var(--ink); transition:transform .18s ease, border-color .18s ease; }
.run:hover { border-color:var(--line-2); transform:translateX(3px); }
.run-icon { font-size:1rem; width:20px; text-align:center; }
.run-ok .run-icon { color:var(--sa); } .run-bad .run-icon { color:#e0392b; } .run-live .run-icon { color:var(--ai); }
.run-live .run-icon { animation:spin 1.1s linear infinite; display:inline-block; }
@keyframes spin { to { transform:rotate(360deg); } }
.run-title { flex:1; font:600 .9rem -apple-system, sans-serif; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.run-meta { font-size:.78rem; color:var(--muted); white-space:nowrap; }

/* ── Footer / misc ────────────────────────── */
.pagecard { margin-top:24px; }
footer { margin-top:56px; padding-top:24px; border-top:1px solid var(--line);
  color:var(--muted); font-size:.82rem; text-align:center; line-height:1.9; }
footer a { color:inherit; }
#totop { position:fixed; right:18px; bottom:18px; z-index:50; width:44px; height:44px;
  border-radius:50%; border:1px solid var(--line); background:var(--glass); color:var(--ink);
  cursor:pointer; backdrop-filter:blur(14px); box-shadow:var(--shadow-s);
  opacity:0; pointer-events:none; transition:opacity .3s ease, transform .3s cubic-bezier(.16,1,.3,1); }
#totop.show { opacity:1; pointer-events:auto; }
#totop:hover { transform:translateY(-3px); }
@media (max-width:600px) {
  .card { padding:28px 22px; border-radius:20px; }
  .hero { padding:calc(var(--nav-h) + 40px) 18px 44px; }
  .seg-btn { padding:9px 16px; font-size:.86rem; }
  .stat-row { gap:0 20px; }
  .stat-item + .stat-item::before { left:-10px; }
  .nav-brand span { display:none; }
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
  var reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  var fine = matchMedia('(pointer: fine)').matches;

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

  var bar = document.getElementById('progress'), top = document.getElementById('totop'),
      nav = document.getElementById('navbar'), blobs = [].slice.call(document.querySelectorAll('.blob'));
  var onScroll = function(){
    var h = document.documentElement.scrollHeight - innerHeight;
    if (bar) bar.style.width = (h > 0 ? (scrollY / h) * 100 : 0) + '%';
    if (top) top.classList.toggle('show', scrollY > 600);
    if (nav) nav.classList.toggle('scrolled', scrollY > 8);
    if (!reduced) blobs.forEach(function(b, i){
      b.style.translate = '0 ' + (scrollY * (i % 2 ? .16 : .26)) + 'px';
    });
  };
  addEventListener('scroll', onScroll, { passive:true }); onScroll();
  if (top) top.addEventListener('click', function(){ scrollTo({ top:0, behavior:'smooth' }); });

  var cue = document.querySelector('.scroll-cue');
  if (cue) cue.addEventListener('click', function(){
    var target = document.querySelector('.tabbar') || document.querySelector('.wrap');
    if (target) target.scrollIntoView({ behavior:'smooth', block:'start' });
  });

  // Scroll reveal
  var io = new IntersectionObserver(function(es){
    es.forEach(function(e){ if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } });
  }, { rootMargin:'0px 0px -8% 0px' });
  document.querySelectorAll('.reveal').forEach(function(el){ io.observe(el); });

  // Count-up stats
  document.querySelectorAll('.stat-num[data-n]').forEach(function(el){
    var n = Number(el.dataset.n); if (!isFinite(n)) return;
    if (reduced) { el.textContent = n; return; }
    var t0 = null;
    var step = function(ts){
      if (!t0) t0 = ts;
      var p = Math.min((ts - t0) / 1000, 1);
      el.textContent = Math.round(n * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });

  // Magnetic hover on hero CTA
  if (fine && !reduced) {
    document.querySelectorAll('.magnetic').forEach(function(el){
      el.addEventListener('mousemove', function(e){
        var r = el.getBoundingClientRect();
        var x = (e.clientX - r.left - r.width / 2) * .25;
        var y = (e.clientY - r.top - r.height / 2) * .35;
        el.style.transform = 'translate(' + x + 'px,' + y + 'px)';
      });
      el.addEventListener('mouseleave', function(){ el.style.transform = ''; });
    });
  }
});
`;

const SEGMENTED_JS = `
addEventListener('DOMContentLoaded', function(){
  var seg = document.getElementById('segmented');
  if (!seg) return;
  var indicator = document.getElementById('seg-indicator');
  var btns = [].slice.call(seg.querySelectorAll('.seg-btn'));
  var accentBg = { ai:'linear-gradient(135deg, var(--ai), var(--ai-2))', sa:'linear-gradient(135deg, var(--sa), var(--sa-2))' };

  function move(btn){
    indicator.style.width = btn.offsetWidth + 'px';
    indicator.style.transform = 'translateX(' + btn.offsetLeft + 'px)';
    indicator.style.background = accentBg[btn.dataset.accent] || accentBg.ai;
  }
  function activate(slug, push){
    var btn = btns.find(function(b){ return b.dataset.slug === slug; });
    if (!btn) return;
    btns.forEach(function(b){ b.classList.toggle('active', b === btn); });
    document.querySelectorAll('.panel').forEach(function(p){ p.classList.toggle('active', p.id === slug); });
    move(btn);
    if (push) history.replaceState(null, '', '#' + slug);
  }
  btns.forEach(function(b){ b.addEventListener('click', function(){ activate(b.dataset.slug, true); }); });
  addEventListener('resize', function(){
    var active = btns.find(function(b){ return b.classList.contains('active'); });
    if (active) move(active);
  });

  var initial = location.hash.slice(1);
  var startSlug = btns.some(function(b){ return b.dataset.slug === initial; }) ? initial : btns[0].dataset.slug;
  requestAnimationFrame(function(){ activate(startSlug, false); });
});
`;

function shell({ title, hero = '', body, bodyClass = '', extraJs = '', navLink = { href: 'admin.html', label: 'Console' } }) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="Two AI agents research, cross-check, and publish AI industry and Saudi market news every morning — every claim linked to its source.">
<meta name="color-scheme" content="light dark">
<title>${title}</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📰</text></svg>">
<style>${CSS}</style>
<script>${THEME_JS}</script>
</head>
<body class="${bodyClass}">
<div id="progress"></div>
<nav class="navbar" id="navbar">
  <a class="nav-brand" href="index.html"><span class="dot"></span><span>The Daily Brief</span></a>
  <div class="nav-right">
    <a class="nav-link" href="${navLink.href}">${navLink.label}</a>
    <button class="theme-toggle" aria-label="Toggle dark mode">☾</button>
  </div>
</nav>
${hero}
<div class="wrap">
${body}
<footer>Each briefing is researched with live web search, then fact-checked in a second
verification pass before publishing — every claim links to its source.<br>
Runs automatically every morning at 07:00 Riyadh time ·
<a href="https://github.com/engdarkbot-netizen/Ai" rel="noopener">source on GitHub</a> ·
last updated ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC</footer>
</div>
<button id="totop" aria-label="Back to top">↑</button>
<script>${CHROME_JS}</script>
${extraJs ? `<script>${extraJs}</script>` : ''}
</body>
</html>`;
}

/* ── Report parsing helpers ── */
const dateOf = (file, slug) => file.slice(slug.length + 1, -3);
const prettyDate = iso =>
  new Date(iso + 'T00:00:00Z').toLocaleDateString('en-GB', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC',
  });

function parseReport(md) {
  let meta = null;
  const m = md.match(/<!-- meta: (.*?) -->/);
  if (m) {
    meta = Object.fromEntries(m[1].split(' ').map(kv => kv.split('=')));
    md = md.replace(m[0], '');
  }
  const domains = new Map(); // hostname -> { url, count }
  for (const link of md.matchAll(/\]\((https?:\/\/[^)\s]+)\)/g)) {
    try {
      const host = new URL(link[1]).hostname.replace(/^www\./, '');
      const e = domains.get(host) || { url: link[1], count: 0 };
      e.count++;
      domains.set(host, e);
    } catch { /* ignore malformed URLs */ }
  }
  return { md, meta, domains };
}

function provenanceHtml(meta, domains) {
  const chips = [];
  if (meta?.verified === 'yes') chips.push(`<span class="chip ok">✓ Fact-checked</span>`);
  chips.push(`<span class="chip">🔗 ${domains.size} sources</span>`);
  if (meta?.model) chips.push(`<span class="chip">🤖 <code>${meta.model}</code></span>`);
  if (meta?.generated) chips.push(`<span class="chip">🕐 ${meta.generated.slice(11, 16)} UTC</span>`);
  return `<div class="prov">${chips.join('')}</div>`;
}

function sourcesHtml(domains) {
  if (!domains.size) return '';
  const items = [...domains.entries()]
    .sort((a, b) => b[1].count - a[1].count)
    .map(([host, { url, count }]) =>
      `<a href="${url}" target="_blank" rel="noopener">${host}${count > 1 ? ` <b>×${count}</b>` : ''}</a>`
    ).join('');
  return `<div class="sources reveal"><h3>Sources in this briefing</h3><div class="src-grid">${items}</div></div>`;
}

// Wrap each word in overflow-hidden + inner span for a masked reveal animation.
function revealWords(text, delayStep = 65) {
  return text.split(' ').map((w, i) =>
    `<span class="w"><span class="wi" style="animation-delay:${i * delayStep}ms">${w}</span></span>`
  ).join(' ');
}

function heroSection({ kicker, title, subtitle, trust, stats, cue }) {
  return `<header class="hero">
<div class="blob b1"></div><div class="blob b2"></div><div class="blob b3"></div>
<div class="hero-inner">
<p class="kicker">${kicker}</p>
<h1>${revealWords(title)}</h1>
${subtitle ? `<p class="subhead">${subtitle}</p>` : ''}
${trust ? `<div class="trust-row">${trust}</div>` : ''}
${stats ? `<div class="stat-row">${stats}</div>` : ''}
${cue ? `<button class="scroll-cue" aria-label="Scroll to briefings"></button>` : ''}
</div></header>`;
}

/* ── Build ── */
const files = fs.existsSync(REPORTS_DIR) ? fs.readdirSync(REPORTS_DIR).filter(f => f.endsWith('.md')) : [];

fs.rmSync(SITE_DIR, { recursive: true, force: true });
fs.mkdirSync(path.join(SITE_DIR, 'reports'), { recursive: true });

/* index.html */
const allDomains = new Set();
for (const f of files) {
  for (const host of parseReport(fs.readFileSync(path.join(REPORTS_DIR, f), 'utf8')).domains.keys())
    allDomains.add(host);
}
const days = new Set(files.map(f => f.slice(-13, -3))).size;

const stat = (n, label) => `<div class="stat-item"><span class="stat-num" data-n="${n}">0</span><span class="stat-label">${label}</span></div>`;
const indexStats = stat(files.length, 'Briefings') + stat(days, 'Days') + stat(allDomains.size, 'Sources')
  + `<div class="stat-item"><span class="stat-num">07:00</span><span class="stat-label">Daily · AST</span></div>`;

const trustBadges = ['✓ Fact-checked', '🔎 Live web search', '🔗 Every source linked']
  .map(t => `<span class="trust-pill">${t}</span>`).join('');

const heroIndex = heroSection({
  kicker: 'Artificial Intelligence · Saudi Markets',
  title: 'The Daily Brief',
  subtitle: 'Two independent agents research, cross-check, and publish market-moving news every morning — with every claim linked to its source.',
  trust: trustBadges,
  stats: indexStats,
  cue: true,
});

const segButtons = AGENTS.map((a, i) =>
  `<button class="seg-btn${i === 0 ? ' active' : ''}" data-slug="${a.slug}" data-accent="${a.accent}">${a.emoji} ${a.title}</button>`
).join('');

let panels = '';
for (const a of AGENTS) {
  const reports = files.filter(f => f.startsWith(`${a.slug}-`)).sort().reverse();
  let inner;
  if (reports.length === 0) {
    inner = `<p class="empty">No briefings yet — the first one appears after the next scheduled run.</p>`;
  } else {
    const latest = reports[0];
    const { md, meta, domains } = parseReport(fs.readFileSync(path.join(REPORTS_DIR, latest), 'utf8'));
    inner = `<div class="cardhead">
<h2>${a.longTitle}</h2>
<span class="badge ${a.accent}">${prettyDate(dateOf(latest, a.slug))}</span>
</div>
${provenanceHtml(meta, domains)}
<div class="report reveal in">${marked.parse(md)}</div>
${sourcesHtml(domains)}`;
    if (reports.length > 1) {
      inner += `<div class="archive reveal"><h3>Previous briefings</h3><div class="archive-scroll">` +
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
    body: `<div class="tabbar"><div class="segmented" id="segmented" role="tablist">
<span class="seg-indicator" id="seg-indicator"></span>${segButtons}</div></div>${panels}`,
    extraJs: SEGMENTED_JS,
    navLink: { href: 'admin.html', label: 'Console' },
  }),
);

/* admin console */
if (fs.existsSync('./admin-template.html')) {
  const heroAdmin = heroSection({
    kicker: 'Control Panel',
    title: 'Agent Console',
    subtitle: 'Run the agents on demand, tune their settings, and watch every run in real time.',
  });
  fs.writeFileSync(
    path.join(SITE_DIR, 'admin.html'),
    shell({
      title: 'Agent Console — The Daily Brief',
      hero: heroAdmin,
      body: fs.readFileSync('./admin-template.html', 'utf8'),
      navLink: { href: 'index.html', label: 'Briefings' },
    }),
  );
}

/* individual report pages */
for (const f of files) {
  const agent = AGENTS.find(a => f.startsWith(`${a.slug}-`));
  if (!agent) continue;
  const { md, meta, domains } = parseReport(fs.readFileSync(path.join(REPORTS_DIR, f), 'utf8'));
  const date = dateOf(f, agent.slug);
  const hero = heroSection({
    kicker: `${agent.emoji} Archive`,
    title: agent.title,
    subtitle: prettyDate(date),
  });
  const body = `<section class="panel panel-${agent.accent} active pagecard"><div class="card">
<div class="cardhead"><h2>${agent.longTitle}</h2><span class="badge ${agent.accent}">${prettyDate(date)}</span></div>
${provenanceHtml(meta, domains)}
<div class="report reveal in">${marked.parse(md)}</div>
${sourcesHtml(domains)}
</div></section>`;
  fs.writeFileSync(
    path.join(SITE_DIR, 'reports', f.replace(/\.md$/, '.html')),
    shell({
      title: `${agent.longTitle} — ${date}`,
      hero, body, bodyClass: `page-${agent.accent}`,
      navLink: { href: '../index.html#' + agent.slug, label: 'Briefings' },
    }),
  );
}

console.log(`Site built: ${SITE_DIR} (${files.length} report(s), ${allDomains.size} distinct source domains)`);
