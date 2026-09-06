# Principles

The load-bearing principles. When a design call has two reasonable
options, these are the tie-breakers. Implementers and contributors
should be able to read this doc and reach the same answers Xray
already reached.

These are downstream of the framework's [Principles](../../../spec/Principles.md);
they are *Xray-specific*. Where they overlap the framework's
principles, this doc cites instead of repeating.

## Read-only by default, mutate by confirmation

Xray observes the runtime. The runtime is the source of truth. Pokes
into `app-db`, dispatches, rewinds, schema substitutions — all of
these require a **user-confirmed action** before they happen.

The mechanism:

- The app-db panel is read-only forever (lock #3).
- The scrubber rebases panels *passively*; rewinds require the
  explicit `Rewind here` button or `r` keypress.
- Re-dispatch is a right-click context-menu action, never a single
  click.
- MCP-driven mutations are tagged `:origin :re-frame2-pair-mcp` and
  surface in the trace stream as distinguishable from app-issued
  mutations. They arrive through `tools/re-frame2-pair-mcp/` and its
  own `re-frame2-pair.runtime` preload, against the framework's
  instrumentation — never through Xray, which publishes no agent seam
  (rf2-7htk7). Pair owns that tool catalogue; see
  [`skills/re-frame2-pair/SKILL.md`](../../../skills/re-frame2-pair/SKILL.md)
  rather than a list restated here.

This is the v1-of-10x mistake-not-repeated. `app-db-follows-events?`
was too implicit; users accidentally rewound. Xray's posture:
**inspection is the default, rewind is opt-in**.

## Observation only — no new runtime surfaces

Xray is a **downstream consumer** of re-frame2's instrumentation
surface. It must not add:

- New registries.
- New dispatch types.
- New effect substrates.
- New component substrates.

If a Xray panel needs data the framework doesn't emit, the answer is
to **add to the framework's instrumentation** (via a spec amendment
in `spec/009-Instrumentation.md`) — not to bolt a parallel surface
onto Xray.

This is the downstream-EPs-consume-foundation rule (per Mike's
feedback) applied to tools: tools observe what the framework emits
and present it; they do not invent new substrates.

Concretely: when the implementation surfaces a spec gap (e.g., "I
want to render `:spawn-all` join state but the trace events are
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

This is why the focused-epoch panel is the hero (lock #7). Post
rf2-5gl5r the **Epoch** tab is the panel that answers in zero
clicks — the numbered-cascade view that supersedes the retired
Event/Handler panel. The causality graph that originally
accompanied this principle has since been dropped entirely
(rf2-y0z5b) — cascade lineage is inspected via the Epoch tab +
Trace tab tags.

## Ephemeral by default

Pins are session-local. Settings persist (theme, density); content
does not.

The privacy bet beats the utility bet. The user's runtime may
contain sensitive data; Xray stores nothing it doesn't need to.

Xray ships no telemetry — nothing is sent to Day8, Anthropic, or
anywhere. (A Settings tab labelled "Telemetry" briefly carried an
opt-in toggle but was removed per rf2-jh9ws — chrome must not
pretend to control something that does not exist.)

## Animation communicates, not decorates

Three durations: quick (100ms — hover, focus), standard (200–250ms —
panel switches, scrubber snap), slow (400–600ms — diff flashes, error
pulses, slide-in).

No looping animations except the machine-active state pulse (1.2s
heartbeat — only on the active machine's node in the machine chart;
the only continuous animation in Xray).

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

## Production posture is build placement

Xray is kept out of production builds by **where the host puts it**, not
by anything in Xray's own construction. Three facts, and they are the
same three at every carrier in this spec:

**1. The preload path is dev-only build configuration.**
`day8.re-frame2-xray.preload` is wired through shadow-cljs's
`:devtools/preloads`, which is dev build config — a release build never
loads the namespace. The preload wraps its boot block in
`(when rf.interop/debug-enabled? …)`, which Closure folds away under
`:advanced` + `goog.DEBUG=false`; that is a second line of defence for
that path only. The trace and epoch collectors gate their own entry
points the same way (`trace_collector.cljs`, `drop_in.cljc`).

**2. The manual `init!` / mount path carries no `goog.DEBUG` gate.**
`init!` registers the `:rf.xray/*` handlers, the trace and epoch
collectors, the browser-global exports and the keybinding listener
unconditionally; `open!` gates only on a substrate adapter being
installed, which every app that called `rf/init!` has in production
exactly as in dev. Requiring `day8.re-frame2-xray.core` at all runs
load-time registrations, so guarding the *calls* is not enough.
Exclusion is the host's job, discharged by keeping the `:require` **and**
the calls in a namespace only the dev entry point loads — the recipe in
`skills/re-frame2-xray/references/launch-programmatic.md` §Keeping the
manual path out of production.

**3. No CI gate proves Xray's absence from a release bundle.**
`npm run test:elision` roots `re-frame.elision-probe` and greps
sentinels drawn from `re-frame.*` namespaces; it roots no Xray namespace
at all. `implementation/scripts/check-bundle-isolation.cjs` pins that the
counter example's *no-feature* production bundle does not pull the
tooling siblings or Xray-only dependencies (xyflow, elkjs, zprint,
editscript) — a dependency-leak check on a bundle that never installed
Xray, not a proof about one that did. Grepping a release bundle for
`rf-xray-root` or `rf.xray` is a leak detector, not proof of zero
retained bytes.

The framework's own instrumentation elision is a separate guarantee and
is unaffected by any of the above: the trace bus, the epoch history, the
schema validation and the registrar trace emit are gated on
`re-frame.interop/debug-enabled?` (alias of `goog.DEBUG`), so a
production build (`:advanced` + `goog.DEBUG=false`) elides all of it —
per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code),
verified by `npm run test:elision` and
`npm run test:browser-prod-elision`. The advanced gate executes a
private-state assertion in addition to bundle sentinels. Its second
advanced build deliberately creates and mutates a renamed `cacheline*`
atom and must fail the runtime assertion, proving this specific
evidence-state oracle still has teeth after minification; it is not a
general heap analyser. Those jobs are the *framework's* contract: a
release build that accidentally loaded Xray would find those substrate
surfaces inert, and Xray's own bytes would still be in the bundle.

## Restraint over completeness

16 panels is already a lot. The temptation to add more — DOM
mutation recording, video replay, AI-generates-tests, code
generation, marketplace plugins, session export — is real and
should be resisted.

If the question being answered is in one of the five canonical
questions, the panel ships. Otherwise: defer to a future version, or
let another tool own the lane.

(Sentry / Replay.io stay in their lane; Xray stays in re-frame's
data plane. re-frame-pair owns code authoring; Xray owns
observation.)

## Frame-first

Multi-frame is a first-class concept. Every panel has a frame
picker; per-frame buffers are independent.

v1 of 10x assumed one frame and broke gracefully on multi-frame
apps. Xray is built frame-first; single-frame apps degrade to
"the picker is a static label."

## No AI in the panel surface

Xray is the human surface only. AI integration lives in the
separate `tools/re-frame2-pair-mcp/` jar. The Issues ribbon and the
schema-violation timeline are *passive* surfaces — they surface
anomalies without an in-panel narrator. The previous AI co-pilot
rail was removed under bead rf2-s3vx5; the cost / privacy / UX
trade-offs that earlier locks debated now route through the MCP
integration instead.

## Silent by default — UI text earns its keep

Xray is an information-dense devtool. Every pixel of chrome competes
with the data the developer is here to inspect. UI text is **silent by
default** — prose appears only when an affordance is genuinely
non-obvious AND has no iconographic alternative, or when the user is in
a state they couldn't otherwise know about. Panel subheads, empty-state
explainers, and "click X to Y" narration are banned; tooltips carry
shortcuts and disambiguation, not descriptions.

This is the AI-first principle applied to surface text: information
density is the read, and narration is its enemy. The full policy —
banned phrasings, tooltip discipline, empty-state tiers, the
"earn its keep" test, and audit cadence — lives in
[`Conventions.md` §UI text](./Conventions.md#ui-text). Recent cleanups
under this policy: PRs #1435, #1436, #1437, #1439.

## Backed by the framework's principles

When in doubt, defer to the framework's [Principles](../../../spec/Principles.md):

- **Regularity over cleverness** — there's one obvious way to do a
  thing in Xray, too.
- **Named things over anonymous things** — every panel has a stable
  id; every keybind has a stable mnemonic; every action chip has a
  stable label.
- **Public query surfaces** — Xray reads only what the framework's
  public registrar / trace bus / epoch-history surfaces expose.
- **Deterministic execution** — Xray's rendering is a function of
  the trace state at panel mount; no hidden side-effects.

Xray is a downstream artefact of the framework's AI-first
discipline. The principles above are what *Xray adds* over the
framework's baseline; everything below is inherited.
