# News Agents

Two AI agents that gather live news via web search and produce dated markdown
briefings. They call Claude models through **OpenRouter** (OpenAI-compatible
API), so all you need is an OpenRouter API key.

| Agent | Script | Report |
|-------|--------|--------|
| 🧠 Daily AI News | `ai-news-agent.mjs` | `reports/ai-news-YYYY-MM-DD.md` |
| 📈 Saudi Stock Market (Tadawul) | `saudi-stocks-agent.mjs` | `reports/saudi-stocks-YYYY-MM-DD.md` |

Both agents share a common runner (`news-agent-core.mjs`) that streams the
briefing to the console as it's written and saves the final markdown report.
Live news comes from [OpenRouter's web search plugin](https://openrouter.ai/docs/features/web-search),
which grounds the model's answer in fresh web results.

## What each agent covers

**AI News Agent** — model/product releases, research breakthroughs, funding and
business news, policy/regulation, and developer tooling. Output: TL;DR, top
stories with sources, and a "Worth Watching" section.

**Saudi Stocks Agent** — TASI market summary, top movers with tickers, company
news (earnings, dividends, contracts), IPOs on the main market and Nomu, CMA /
Saudi Exchange regulatory announcements, and macro context (oil, Vision 2030,
PIF). The prompt targets regional sources (Argaam, saudiexchange.sa, CMA) and
is aware of the Sunday–Thursday trading week.

## Run locally

```bash
export OPENROUTER_API_KEY=sk-or-...   # get one at https://openrouter.ai/keys

npm install
npm run news:ai      # AI news briefing
npm run news:saudi   # Saudi stocks briefing
npm run news         # both
```

### Configuration (env vars)

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENROUTER_API_KEY` | — (required) | OpenRouter API key |
| `NEWS_MODEL` | `anthropic/claude-sonnet-4.5` | Any OpenRouter model slug |
| `MAX_SEARCH_RESULTS` | `5` | Web results fed to the model per run |
| `OUTPUT_DIR` | `./reports` | Where markdown reports are written |

You can point `NEWS_MODEL` at any model OpenRouter offers (e.g. a newer Claude,
GPT, or Gemini slug) — the web search plugin works across providers.

## Deployment: daily runs + public web page

`.github/workflows/daily-news.yml` does everything in the cloud — no server
needed:

1. Runs both agents every day at 07:00 Riyadh time (04:00 UTC)
2. Commits the markdown reports to the repo (`reports/`), building an archive
3. Builds a static website from them (`build-site.mjs` → `site/`)
4. Publishes the site to **GitHub Pages**:
   `https://<owner>.github.io/<repo>/`

The page shows the latest AI news and Saudi stocks briefings plus an archive
of past days, and refreshes automatically after every run. It can also be
triggered manually from the Actions tab (Run workflow).

**One-time setup:**

1. Add `OPENROUTER_API_KEY` as a repository secret
   (Settings → Secrets and variables → Actions → New repository secret)
2. Make sure GitHub Pages is allowed: on a free GitHub plan the repo must be
   **public** for Pages to work (Settings → General → Change visibility)
3. Trigger the workflow once from the Actions tab — the first run enables
   Pages and publishes the site

Preview the site locally with `npm run build:site` then open `site/index.html`.

## Notes

- Web search runs through OpenRouter's web plugin — no scraping code or news
  API keys are needed. OpenRouter bills a small per-result fee for web search
  on top of normal model token usage.
- Reports are gitignored locally; the workflow publishes them as artifacts.
- Every claim in a briefing includes a source link, and the agents are
  instructed to skip sections with no real news rather than pad.
