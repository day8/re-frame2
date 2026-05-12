# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers Causa
already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md);
they are *Causa-specific*. Where they overlap the framework's
principles, this doc cites instead of repeating.

## Read-only by default, mutate by confirmation

Causa observes the runtime. The runtime is the source of truth. Pokes
into `app-db`, dispatches, rewinds, schema substitutions — all of
these require a **user-confirmed action** before they happen.

The mechanism:

- The app-db panel is read-only forever (lock #3).
- The scrubber rebases panels *passively*; rewinds require the
  explicit `Rewind here` button or `r` keypress.
- Re-dispatch is a right-click context-menu action, never a single
  click.
- The AI co-pilot proposes actions via clickable chips; clicking is
  the user's commitment.
- The MCP server's mutation tools (`restore-epoch`, `reset-frame-db`,
  `dispatch`) are tagged `:origin :causa-mcp` and surface in the
  trace stream as distinguishable from app-issued mutations.

This is the v1-of-10x mistake-not-repeated. `app-db-follows-events?`
was too implicit; users accidentally rewound. Causa's posture:
**inspection is the default, rewind is opt-in**.

## Observation only — no new runtime surfaces

Causa is a **downstream consumer** of re-frame2's instrumentation
surface. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

If a Causa panel needs data the framework doesn't emit, the answer is
to **add to the framework's instrumentation** (via a spec amendment
in `spec/009-Instrumentation.md`) — not to bolt a parallel surface
onto Causa.

This is the downstream-EPs-consume-foundation rule (per Mike's
feedback) applied to tools: tools observe what the framework emits
and present it; they do not invent new substrates.

Concretely: when the implementation surfaces a spec gap (e.g., "I
want to render `:invoke-all` join state but the trace events are
missing a `:child-ids` tag"), file a `bd` bead against the spec.
Don't silently work around.

## The five canonical questions

Every panel earns its keep by helping answer one of:

1. **Why did this event fire?**
2. **What did that event change?**
3. **Why is this subscription returning the wrong value?**
4. **Why is this view re-rendering?**
5. **What's currently broken?**

If a panel doesn't help with one of the five, it doesn't ship. The
discipline keeps the surface from accreting features that *demo*
well but aren't *habit-forming*.

Restraint is what keeps the tool useful daily instead of impressive
once.

## Habit beats demo

A graph is striking. A flat panel that answers in one click is
useful. When the two compete, the flat panel wins.

This is why the event-detail panel is the hero, not the causality
graph (lock #7). The graph is a peer — first-class, sidebar entry,
keyboard mnemonic `c` — but the front door is the panel that answers
in zero clicks.

The bet: programmers land in the detail panel 90% of the time. The
graph is the second-click answer when the first click wasn't enough.
If usage data later shows the graph carries more weight than
predicted, Causa self-instruments (panel-view durations) and the
decision is reversible. Low-cost reversal.

## Cite the evidence

The AI co-pilot's responses are valuable in proportion to how
quickly the user can verify them. Every claim references data the
user can verify:

- Source coords (`events.cljs:213`)
- Epoch ids (`epoch 10`)
- Event vectors (`:cart/finalise`)
- Machine states (`:auth/login-flow → :authenticating`)

If a claim doesn't link to data, it's a tell — and the model is
hallucinating. The system prompt encodes this discipline; the UX
surfaces every reference as a clickable chip so verification is
one click.

The co-pilot is a **navigator, not an oracle**.

## Ephemeral by default

Conversations are session-local (lock #12). Pins are session-local.
Settings persist (theme, density, AI provider key); content does
not.

The privacy bet beats the utility bet. The user's runtime may
contain sensitive data; Causa stores nothing it doesn't need to.

Settings → Telemetry has a section explaining we ship none.

## Animation communicates, not decorates

Three durations: quick (100ms — hover, focus), standard (200–250ms —
panel switches, scrubber snap), slow (400–600ms — diff flashes, error
pulses, slide-in).

No looping animations except the machine-active state pulse (1.2s
heartbeat — only on the active machine's node in the machine chart;
the only continuous animation in Causa).

Every animation respects `prefers-reduced-motion`. Reduced motion
clamps durations to 0 except a 1-frame opacity tween where layout
needs to settle.

The error pulse is single — one 600ms expand-fade on entry, then
done. No "look at me I'm an error" continuous strobe.

## Colour is never alone

Every coloured marker pairs with a shape or icon (per
[`007-UX-IA.md`](./007-UX-IA.md) §Colour is never alone). Errors are
red dots + `!` icons + "Error" labels. Schema violations are yellow
triangles + paths. Active machines are green + filled glyphs.

The colour-blind path is reachable without hue.

## Production elision is non-negotiable

Causa ships **zero bytes** in production. The trace bus, the epoch
history, the schema validation, the registrar trace emit — all
gated on `re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`).
Production builds (`:advanced` + `goog.DEBUG=false`) elide all of it.

Per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code).
CI's `npm run test:elision` job verifies the contract.

Causa contributes its own sentinels to the elision verifier; CI
blocks any leak.

## Restraint over completeness

16 panels is already a lot. The temptation to add more — DOM
mutation recording, video replay, AI-generates-tests, code
generation, marketplace plugins, session export — is real and
should be resisted.

If the question being answered is in one of the five canonical
questions, the panel ships. Otherwise: defer to a future version, or
let another tool own the lane.

(Sentry / Replay.io stay in their lane; Causa stays in re-frame's
data plane. re-frame-pair owns code authoring; Causa owns
observation.)

## Frame-first

Multi-frame is a first-class concept. Every panel has a frame
picker; per-frame buffers are independent; cross-frame causality is
surfaced explicitly (swimlanes in the graph; coloured rings on the
strip pills).

v1 of 10x assumed one frame and broke gracefully on multi-frame
apps. Causa is built frame-first; single-frame apps degrade to
"the picker is a static label."

## Pull-only AI

The AI co-pilot speaks when spoken to. No narration mode (lock
#10), no background commentary, no proactive alerts.

The Issues ribbon and the schema-violation timeline are *passive*
surfaces — they surface anomalies without the model needing to
explain them. When the user wants explanation, they ask.

This is a privacy choice (no unsolicited LLM calls) and a cost choice
(no unsolicited tokens) and a UX choice (no chatter the user didn't
opt into).

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — there's one obvious way to do a
  thing in Causa, too.
- **Named things over anonymous things** — every panel has a stable
  id; every keybind has a stable mnemonic; every action chip has a
  stable label.
- **Public query surfaces** — Causa reads only what the framework's
  public registrar / trace bus / epoch-history surfaces expose.
- **Deterministic execution** — Causa's rendering is a function of
  the trace state at panel mount; no hidden side-effects.

Causa is a downstream artefact of the framework's AI-first
discipline. The principles above are what *Causa adds* over the
framework's baseline; everything below is inherited.
