/**
 * Shared runner for the news agents (ai-news-agent.mjs / saudi-stocks-agent.mjs).
 *
 * Calls a Claude model through OpenRouter (OpenAI-compatible API) with
 * OpenRouter's web search plugin to gather live news, streams the digest
 * to the console, and saves a dated markdown report.
 *
 * Required env vars:
 *   OPENROUTER_API_KEY - OpenRouter API key (https://openrouter.ai/keys)
 *
 * Optional env vars:
 *   NEWS_MODEL         - OpenRouter model slug (default: anthropic/claude-sonnet-4.5)
 *   MAX_SEARCH_RESULTS - Web results fed to the model per run (default: 5)
 *   OUTPUT_DIR         - Where reports are written (default: ./reports)
 */

import OpenAI from 'openai';
import fs from 'fs';
import path from 'path';

const API_KEY     = process.env.OPENROUTER_API_KEY;
const MODEL       = process.env.NEWS_MODEL || 'anthropic/claude-sonnet-4.5';
const MAX_RESULTS = Number(process.env.MAX_SEARCH_RESULTS || 5);
const OUTPUT_DIR  = process.env.OUTPUT_DIR || './reports';

const LINE = '─'.repeat(64);

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
  console.log(`  🤖  ${MODEL} via OpenRouter · web search: up to ${MAX_RESULTS} results`);
  console.log(`  📅  ${today}`);
  console.log(`${LINE}\n`);

  const stream = await client.chat.completions.create({
    model: MODEL,
    stream: true,
    max_tokens: 16000,
    // OpenRouter web search plugin — grounds the answer in live results
    plugins: [{ id: 'web', max_results: MAX_RESULTS }],
    messages: [
      { role: 'system', content: system },
      { role: 'user', content: prompt },
    ],
  });

  let digest = '';
  let finishReason = null;
  for await (const chunk of stream) {
    const choice = chunk.choices?.[0];
    const delta = choice?.delta?.content || '';
    if (delta) {
      digest += delta;
      process.stdout.write(delta);
    }
    if (choice?.finish_reason) finishReason = choice.finish_reason;
  }

  if (!digest.trim()) {
    console.error(`\n  ERROR: No digest produced (finish_reason: ${finishReason})\n`);
    process.exit(1);
  }
  if (finishReason === 'length') {
    console.warn('\n  WARNING: Output hit the max_tokens limit and may be truncated');
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const outPath = path.join(OUTPUT_DIR, `${slug}-${today}.md`);
  fs.writeFileSync(outPath, `# ${title} — ${today}\n\n${digest}\n`);

  console.log(`\n\n${LINE}`);
  console.log(`  ✔  Report saved: ${outPath}`);
  console.log(`${LINE}\n`);

  return { outPath, digest };
}
