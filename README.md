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

## Daily automation

`.github/workflows/daily-news.yml` runs both agents every day at 07:00 Riyadh
time (04:00 UTC) and uploads the reports as workflow artifacts (kept 30 days).
It can also be triggered manually from the Actions tab.

**Setup:** add `OPENROUTER_API_KEY` as a repository secret
(Settings → Secrets and variables → Actions → New repository secret).

## Notes

- Web search runs through OpenRouter's web plugin — no scraping code or news
  API keys are needed. OpenRouter bills a small per-result fee for web search
  on top of normal model token usage.
- Reports are gitignored locally; the workflow publishes them as artifacts.
- Every claim in a briefing includes a source link, and the agents are
  instructed to skip sections with no real news rather than pad.
