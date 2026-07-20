/**
 * Static site builder for the news agents.
 *
 * Reads all markdown reports in ./reports and generates ./site:
 *   site/index.html          - latest AI news + Saudi stocks briefings, with archive
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
  { slug: 'ai-news',      emoji: '🧠', title: 'Daily AI News' },
  { slug: 'saudi-stocks', emoji: '📈', title: 'Saudi Stock Market (Tadawul)' },
];

const CSS = `
:root { --bg:#faf9f6; --fg:#1c1c1c; --muted:#6b6b6b; --card:#ffffff; --line:#e5e2da; --accent:#0f6b4f; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#131313; --fg:#e8e6e1; --muted:#9a9890; --card:#1c1c1c; --line:#2c2b28; --accent:#4cc296; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg);
  font: 16px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
.wrap { max-width: 860px; margin: 0 auto; padding: 24px 20px 64px; }
header.site { border-bottom: 1px solid var(--line); padding-bottom: 16px; margin-bottom: 28px; }
header.site h1 { font-size: 1.7rem; margin: 0 0 4px; }
header.site p { color: var(--muted); margin: 0; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 10px;
  padding: 24px 28px; margin-bottom: 28px; overflow-x: auto; }
.card h2.agent { margin-top: 0; font-size: 1.3rem; }
.card .meta { color: var(--muted); font-size: 0.85rem; margin-bottom: 12px; }
.report h1 { font-size: 1.4rem; }
.report h2 { font-size: 1.15rem; margin-top: 1.6em; }
.report a { color: var(--accent); }
.report table { border-collapse: collapse; width: 100%; margin: 12px 0; }
.report th, .report td { border: 1px solid var(--line); padding: 6px 10px; text-align: left; }
.report code { background: var(--line); padding: 1px 5px; border-radius: 4px; font-size: 0.9em; }
.archive ul { columns: 2; margin: 8px 0 0; padding-left: 20px; }
.archive a { color: var(--accent); text-decoration: none; }
.archive a:hover { text-decoration: underline; }
.empty { color: var(--muted); font-style: italic; }
footer { color: var(--muted); font-size: 0.8rem; border-top: 1px solid var(--line); padding-top: 16px; }
a.back { color: var(--accent); text-decoration: none; }
`;

function page(title, body) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>${CSS}</style>
</head>
<body><div class="wrap">${body}
<footer>Generated automatically by the news agents · updated ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC</footer>
</div></body>
</html>`;
}

function reportsFor(slug, files) {
  return files
    .filter(f => f.startsWith(`${slug}-`) && f.endsWith('.md'))
    .sort()
    .reverse(); // newest first (dates are YYYY-MM-DD)
}

const files = fs.existsSync(REPORTS_DIR) ? fs.readdirSync(REPORTS_DIR) : [];

fs.rmSync(SITE_DIR, { recursive: true, force: true });
fs.mkdirSync(path.join(SITE_DIR, 'reports'), { recursive: true });

let indexBody = `<header class="site">
<h1>📰 Daily Briefings</h1>
<p>AI industry news and Saudi stock market updates, refreshed every morning.</p>
</header>`;

for (const agent of AGENTS) {
  const reports = reportsFor(agent.slug, files);

  indexBody += `<section class="card"><h2 class="agent">${agent.emoji} ${agent.title}</h2>`;

  if (reports.length === 0) {
    indexBody += `<p class="empty">No briefings yet — the first one appears after the next scheduled run.</p>`;
  } else {
    const latest = reports[0];
    const md = fs.readFileSync(path.join(REPORTS_DIR, latest), 'utf8');
    indexBody += `<div class="meta">Latest briefing · ${latest.slice(agent.slug.length + 1, -3)}</div>`;
    indexBody += `<div class="report">${marked.parse(md)}</div>`;

    if (reports.length > 1) {
      indexBody += `<div class="archive"><h3>Archive</h3><ul>`;
      for (const r of reports.slice(1)) {
        const date = r.slice(agent.slug.length + 1, -3);
        indexBody += `<li><a href="reports/${r.replace(/\.md$/, '.html')}">${date}</a></li>`;
      }
      indexBody += `</ul></div>`;
    }
  }
  indexBody += `</section>`;
}

fs.writeFileSync(path.join(SITE_DIR, 'index.html'), page('Daily Briefings', indexBody));

// One page per report (for the archive links)
for (const f of files.filter(f => f.endsWith('.md'))) {
  const md = fs.readFileSync(path.join(REPORTS_DIR, f), 'utf8');
  const body = `<p><a class="back" href="../index.html">← Latest briefings</a></p>
<div class="card"><div class="report">${marked.parse(md)}</div></div>`;
  fs.writeFileSync(
    path.join(SITE_DIR, 'reports', f.replace(/\.md$/, '.html')),
    page(f.replace(/\.md$/, ''), body),
  );
}

console.log(`Site built: ${SITE_DIR} (${files.length} report(s))`);
