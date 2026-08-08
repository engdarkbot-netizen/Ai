/**
 * Shared runner for the news agents (ai-news-agent.mjs / saudi-stocks-agent.mjs).
 *
 * Two-pass pipeline through OpenRouter (OpenAI-compatible API):
 *   1. RESEARCH - the model, grounded by OpenRouter's web search plugin,
 *      drafts the briefing from live results
 *   2. VERIFY   - a second call (fresh search grounding) audits the draft:
 *      flags unsourced or stale claims, fills coverage gaps, tightens the
 *      copy, and emits the final briefing
 *
 * Reports carry a metadata line (model, sources count, verification) that
 * the site surfaces so readers can judge provenance.
 *
 * Required env vars:
 *   OPENROUTER_API_KEY - OpenRouter API key (https://openrouter.ai/keys)
 *
 * Settings come from config.json (editable from the admin page); env vars
 * override it:
 *   NEWS_MODEL         - OpenRouter model slug
 *   MAX_SEARCH_RESULTS - Web results per search pass
 *   VERIFY             - "0" disables the verification pass
 *   OUTPUT_DIR         - Where reports are written (default: ./reports)
 */

import OpenAI from 'openai';
import fs from 'fs';
import path from 'path';

export function loadConfig() {
  const defaults = {
    model: 'anthropic/claude-sonnet-4.5',
    maxSearchResults: 8,
    verify: true,
    agents: { 'ai-news': true, 'saudi-stocks': true },
  };
  try {
    return { ...defaults, ...JSON.parse(fs.readFileSync('./config.json', 'utf8')) };
  } catch {
    return defaults;
  }
}

const CONFIG      = loadConfig();
const API_KEY     = process.env.OPENROUTER_API_KEY;
const MODEL       = process.env.NEWS_MODEL || CONFIG.model;
const MAX_RESULTS = Number(process.env.MAX_SEARCH_RESULTS || CONFIG.maxSearchResults);
const VERIFY      = process.env.VERIFY ? process.env.VERIFY !== '0' : CONFIG.verify !== false;
const OUTPUT_DIR  = process.env.OUTPUT_DIR || './reports';

const LINE = '─'.repeat(64);

async function complete(client, { system, user, label }) {
  const stream = await client.chat.completions.create({
    model: MODEL,
    stream: true,
    max_tokens: 16000,
    plugins: [{ id: 'web', max_results: MAX_RESULTS }],
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: user },
    ],
  });

  let text = '';
  let finishReason = null;
  const annotations = [];
  for await (const chunk of stream) {
    const choice = chunk.choices?.[0];
    const delta = choice?.delta?.content || '';
    if (delta) { text += delta; process.stdout.write(delta); }
    const ann = choice?.delta?.annotations;
    if (Array.isArray(ann)) annotations.push(...ann);
    if (choice?.finish_reason) finishReason = choice.finish_reason;
  }
  if (!text.trim()) throw new Error(`${label}: model returned no output (finish_reason: ${finishReason})`);
  if (finishReason === 'length') console.warn(`\n  WARNING [${label}]: output hit max_tokens and may be truncated`);
  return { text, annotations };
}

// The report should start at its first markdown heading — drop any
// narration the model emitted before it.
function stripPreamble(text) {
  const i = text.search(/^#{1,3} /m);
  return i > 0 ? text.slice(i) : text;
}

function countSources(md) {
  return new Set([...md.matchAll(/\]\((https?:\/\/[^)\s]+)\)/g)]
    .map(m => { try { return new URL(m[1]).hostname.replace(/^www\./, ''); } catch { return null; } })
    .filter(Boolean)).size;
}

export async function runNewsAgent({ title, emoji, slug, system, prompt }) {
  if (!API_KEY) {
    console.error('\n  ERROR: Set OPENROUTER_API_KEY environment variable\n');
    process.exit(1);
  }

  const client = new OpenAI({
    baseURL: 'https://openrouter.ai/api/v1',
    apiKey: API_KEY,
    defaultHeaders: {
      'HTTP-Referer': 'https://github.com/engdarkbot-netizen/Ai',
      'X-Title': 'News Agents',
    },
  });

  const today = new Date().toISOString().slice(0, 10);

  console.log(`\n${LINE}`);
  console.log(`  ${emoji}  ${title}`);
  console.log(`  🤖  ${MODEL} via OpenRouter · web search: up to ${MAX_RESULTS} results/pass`);
  console.log(`  🔎  verification pass: ${VERIFY ? 'on' : 'off'}`);
  console.log(`  📅  ${today}`);
  console.log(`${LINE}\n`);

  /* ── Pass 1: research & draft ── */
  const draft = stripPreamble((await complete(client, {
    system, user: prompt, label: 'research',
  })).text);

  let final = draft;

  /* ── Pass 2: verify & finalize ── */
  if (VERIFY) {
    console.log(`\n\n${LINE}\n  🔎  Verification pass\n${LINE}\n`);
    const verifySystem = `You are a sceptical news editor fact-checking a briefing before
publication. Today's date is ${today}. You have web search - use it to spot-check
the draft's most important claims and to look for major stories it missed.

Your tasks, in order:
1. Verify the key facts (figures, dates, names) against current sources; fix
   anything wrong or stale, and prefer primary sources.
2. Check for significant stories from the last 24-48 hours that the draft
   missed; add them in the appropriate section.
3. Remove or flag any claim that has no source link. Every factual claim in
   the final briefing must cite a source.
4. Tighten the writing; remove padding and repetition.

Output the complete corrected briefing in the same markdown structure - it
must stand alone and start directly with the "# ..." title heading. Do not
output your audit notes, commentary, or any text before the title.`;
    try {
      final = stripPreamble((await complete(client, {
        system: verifySystem,
        user: `Fact-check and finalize this draft briefing:\n\n${draft}`,
        label: 'verify',
      })).text);
    } catch (e) {
      console.warn(`\n  WARNING: verification pass failed (${e.message}) - publishing the draft`);
      final = draft;
    }
  }

  /* ── Save ── */
  const sources = countSources(final);
  const meta = `<!-- meta: model=${MODEL} sources=${sources} verified=${VERIFY ? 'yes' : 'no'} generated=${new Date().toISOString()} -->`;

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const outPath = path.join(OUTPUT_DIR, `${slug}-${today}.md`);
  fs.writeFileSync(outPath, `# ${title} — ${today}\n${meta}\n\n${final}\n`);

  console.log(`\n\n${LINE}`);
  console.log(`  ✔  Report saved: ${outPath}`);
  console.log(`  🔗  ${sources} distinct source domains · verified: ${VERIFY ? 'yes' : 'no'}`);
  console.log(`${LINE}\n`);

  return { outPath, digest: final };
}
