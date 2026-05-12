# 009-AI-CoPilot

The AI co-pilot is a **pull-only** Q&A and slash-command surface for
asking the runtime questions in natural language. The user types; the
LLM answers; the LLM cites data the user can verify.

It is **not** a narration mode — Causa never streams chatter at the
user (lock #10 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)).
It is **not** a code generator — code authoring belongs to
re-frame-pair (editor-side authority). It is **not** an oracle — the
model is a *navigator*; the user is the verifier.

## Default state

Collapsed (lock #8). A subtle cue marks the rail entry in the top
strip (per [`007-UX-IA.md`](./007-UX-IA.md) §The AI co-pilot
collapsed cue) — a magenta `◇` glyph that pulses every 8 seconds
until the user has used the co-pilot once, then stays static.

Toggle: `Ctrl+Shift+/` or click the sidebar `Co-pilot` entry.

## Panel layout

When open, a 320px rail on the right of the canvas:

```
┌──────────────────────────────────────┐
│  AI Co-pilot                ⌗ ⛶  ✕  │  ← title, model picker, expand, close
├──────────────────────────────────────┤
│  ▸ Why did :checkout/submit fire?    │
│    It was dispatched by an           │
│    :fx [[:dispatch ...]] inside the  │
│    :cart/finalise handler            │
│    (events.cljs:213). That handler   │
│    ran because of                    │
│    :user/clicked-checkout at         │
│    10:43:21.                         │
│    [ Open source ] [ Show graph ]    │
│  ─────────────────────────────────── │
│  ▸ What's the auth-flow state?       │
│    :auth/login-flow → :authenticating│
│    Parent :login is :open. Pending   │
│    :after timer at 5000ms, 2.1s left.│
│    [ Open machine chart ]            │
├──────────────────────────────────────┤
│ ╭──────────────────────────────────╮ │
│ │ Ask anything…                  ↑ │ │
│ ╰──────────────────────────────────╯ │
│ /slash for commands                  │
└──────────────────────────────────────┘
```

Conversation newest-at-bottom. Each turn: question (▸ in violet) →
response.

## Pull-only model

Causa never sends a token to the LLM unless the user submits a
question. Specifically:

- **No background narration.** Causa does not periodically describe
  what's happening to the user. (The original v2 design floated this;
  it was dropped — lock #10.)
- **No proactive "I notice..." alerts.** The Issues ribbon and the
  schema-violation timeline surface anomalies; the co-pilot does not
  push messages.
- **No auto-summaries.** Pinning a snapshot does not invite the model
  to summarise.
- **No conversation resumption prompts.** When the panel opens after a
  reload, the conversation area is empty — the model never asks "Do
  you want me to pick up where we left off?" (Conversations are
  ephemeral; see below.)

The model speaks only when spoken to.

## Slash commands

Typing `/` at the start of the input opens a dropdown:

| Slash command | What it does |
|---|---|
| `/explain <event-id-or-epoch>` | Describe one epoch — the cause, the effect, the state delta. |
| `/diff <epoch-a> <epoch-b>` | What changed between two epochs? |
| `/find <pattern>` | Search the trace stream. |
| `/rewind <event-or-epoch>` | Propose a rewind (executes only on user confirmation). |
| `/state <machine-id>` | Describe a machine's current state. |
| `/why <epoch>` | Causal-ancestor walk. |
| `/whatif <hypothetical>` | Speculative reasoning (the model labels the answer as reasoning, not measurement). |
| `/clear` | Clear conversation. |

Slash commands are **typed shortcuts** for common questions; the
model can still answer the same questions in plain English. The slash
form is faster for power users.

## Provider abstraction

A `⌗` icon next to the panel title opens a dropdown:

- **Claude** (default)
- **OpenAI**
- **Gemini**
- **Local** (Ollama)
- **Custom** (user-supplied URL + headers)

Each uses the user's API key, configured in Settings → AI Provider.
Keys are stored **only in localStorage**; never sent to Day8 or to
any service other than the chosen provider. The Settings panel has a
"Telemetry" section that exists solely to state this fact.

Switching providers is live; conversation history is sent to the new
provider as context (the user accepts this by switching).

## System prompt

The system prompt is bundled and editable in Settings. The default:

```
You are a debugger's assistant.

Context you have:
- The user is debugging a re-frame2 application.
- You can read the trace buffer (last 200 events), epoch history (last 50
  per frame), the current app-db, registered handlers, registered
  schemas, machine snapshots.
- The user submits a question or a slash command.

Rules:
- Cite source coords for every claim (e.g. "events.cljs:213").
- Reference epoch ids so the user can verify in the panel.
- Say "I cannot determine that" plainly when you cannot.
- One action suggestion at a time.
- Never invent data. If you do not see it in the trace, do not claim it.
- Short answers; detail only when asked.
- No preambles, no apologising, no hedging.

When the user asks "why did X fire?", walk :parent-dispatch-id upward
and return the chain.

When the user asks "what changed?", diff :db-before / :db-after on the
named epoch.

When the user asks about a machine, read [:rf/machines <id>] in the
current frame's app-db.
```

The prompt is user-editable in Settings — `Edit system prompt`. Causa
ships the default and warns the user that customising the prompt may
change the model's behaviour.

## Tool / function calling

The co-pilot uses the LLM's function-calling surface to read the
runtime. Tools the model can call:

| Tool | Reads |
|---|---|
| `get-trace-buffer` | A slice of the trace stream by filter. |
| `get-epoch-history` | Per-frame epoch history. |
| `get-app-db` | Current value at a frame, optionally at a path. |
| `get-machine-state` | Snapshot for a named machine. |
| `get-issues` | Recent errors/warnings/schema-violations. |
| `get-handlers` | Registered handlers' metadata. |
| `get-source-coord` | Source coord for a given id. |

These are **read-only**. The model cannot call:

- `restore-epoch`
- `reset-frame-db!`
- `dispatch`

…through tool use. The model can *propose* these via action chips
(`[Rewind to here]`, `[Re-dispatch]`); the user clicks to confirm.

The tool catalogue mirrors what `tools/causa-mcp/` exposes (see
[`010-MCP-Server.md`](./010-MCP-Server.md)) — the same surface is
available to local agents (Claude Code, Cursor) over MCP.

## Result formatting

Responses are markdown rendered with:

- **Code blocks** in mono with syntax highlighting (cljs / edn /
  json).
- **Source-coord chips.** `src/cart/events.cljs:213` renders as a
  chip with a `🔗` icon; click opens source.
- **Action chips.** `[Open source]`, `[Show graph]`, `[Rewind here]`,
  `[Filter to this cascade]` — inline buttons in the response.
  Action chips that mutate runtime (rewind, re-dispatch) always
  require a click.
- **Embedded data.** Small data structures render with the same
  chrome as the main panels. 200px max height; expandable.
- **Mini-graphs.** A causal chain renders as a 5-node mini-graph
  using the same SVG primitive as the Causality panel.

Responses **stream** (per token, ~25 tokens/sec). First words within
~600ms of Enter. Respects `prefers-reduced-motion` (instant render).

## Ephemeral conversation

Conversation is **per-session, in-memory only** (lock #12 in
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)):

- **Not persisted to localStorage.**
- **Not exported.**
- **Cleared on tab reload.**
- **Cleared on Causa close** (the conversation buffer is part of
  Causa's mounted state).

The privacy bet beats the utility bet. Conversation may contain
sensitive app data (user emails, internal handler names, snippets of
production-shaped repros). Per-session-only is the conservative
default and aligns with Settings → Telemetry = off.

In-session affordances:

- `Ctrl+L` clears conversation.
- `Ctrl+P` / `Ctrl+N` walk previous / next questions.
- `Ctrl+R` searches within the conversation.

## Voice / STT

**Not at v1.0** (lock #13 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md)).
The input is text only. The Web Speech API is implementable but the
quality is patchy; cloud STT adds cost and latency; v1.0 scope is
already large.

No mic permission prompts. No `🎙` button in the v1.0 UI.

Voice may ship in v1.1+ if users demand. Pure additive feature.

## When the model is wrong

Three error states:

### API error

Network failure, rate limit, expired key. Panel shows a red banner:

```
Couldn't reach Claude.
Retry in 30s? [Retry now] [Switch provider]
```

The user's question is preserved. The model is not retried
automatically (Causa never spends the user's tokens unprompted).

### Model says "I don't know"

The system prompt instructs the model to say so plainly. Sample
response:

```
I can't determine that from the trace I have access to.
The trace buffer has the last 200 events. You can see them in the
Trace panel. [Open Trace]
```

No hallucination. Failure case is a navigation aid.

### Model is confidently wrong

Hardest case. There is no automatic detection. Mitigation:

- **Every claim the model makes references data the user can
  verify** — source coords, epoch ids, event vectors.
- When the model says "epoch 10 dispatched `:cart/finalise`," the
  user can click that link and see whether it's right.
- If a claim doesn't link to data, it's a tell.

The failure-mode design: the co-pilot is **a navigator, not an
oracle.** It points at the data; the user verifies. Every response
surfaces its evidence.

## Tone / personality

Direct, observational, programmer-to-programmer. No preambles, no
apologising, no hedging. The system prompt encodes this; the user can
edit the prompt to taste.

Bad: "Of course! I'd be happy to help you investigate this. Let me
take a look at the trace and see what I can find..."

Good: "Dispatched at events.cljs:213 by :cart/finalise. Want me to
open the file?"

## Performance

- **No background work.** The co-pilot consumes zero CPU/network when
  collapsed or idle.
- **Streaming reduces perceived latency.** First token within ~600ms;
  full response in 2-5s for the canonical questions.
- **Tool-call round-trips happen client-side** (Causa reads its own
  in-process state and feeds it to the model); no extra server.
- **Conversation buffer** is in-memory only; never grows beyond ~1MB
  in practice.

## Empty state

First open, conversation empty:

```
   Ask Causa anything about this runtime.

   Try:
   • Why did :checkout/submit fire?
   • What changed in the last 10 epochs?
   • Why is :cart/total returning 0?
   • /state :auth/login-flow

   The co-pilot reads the same data you see —
   it cites every claim with a source coord or epoch id.
   Verify before trusting.
```

The final line is deliberate. The co-pilot is a navigator; the user
verifies.

## What this doesn't do

- **No narration / live commentary.** The model speaks when spoken
  to.
- **No code generation.** Causa is a debugger; code authoring belongs
  to re-frame-pair.
- **No persistent memory.** Conversations are session-local.
- **No agent autonomy.** No "I'll dispatch X for you" — every mutate
  is a user click.
- **No telemetry sent to Day8.** Causa never proxies the conversation
  through a Day8 server; the user's API key calls the user's
  provider directly.
- **No voice at v1.0.** Text only.
