# `testbeds/large-dispatcher`

Four handlers each commit a value to a path whose wire-elision is
governed by a different one of the three nomination paths defined in
[spec/009 §Nomination — three entry points](../../spec/009-Instrumentation.md).
A consumer (Causa, Story, pair2-mcp) reads the surface to verify
the wire-boundary walker substitutes the `:rf.size/large-elided`
marker on the appropriate slot under each path.

## The four nomination paths

| Button | `data-testid` | Path written | Mechanism | Payload size | Why it elides |
|---|---|---|---|---|---|
| A | `write-auto` | `[:auto-large-value]` | Runtime auto-detect (no declaration) | **20 KiB** | The walker's `pr-str` byte count exceeds `:rf.size/threshold-bytes` (16 KiB default); the path gets auto-flagged on first emit and elides on every subsequent emit. The `:rf.warning/runtime-large-elision` advisory fires once on first detection. |
| B | `write-declared` | `[:declared-large-value]` | `rf/declare-large-path!` (REPL wrapper) | 200 bytes | Handler calls the wrapper directly; declaration writes `{:large? true :source :declared}` into the elision registry. Elision fires regardless of size (declared wins over runtime threshold). |
| C | `write-fx-declared` | `[:fx-declared-value]` | `:rf.size/declare-large` fx | 200 bytes | Canonical AI-discoverable nomination shape — handler returns `:fx [[:rf.size/declare-large {:path ...}]]`. Same outcome as button B, different entry point. |
| D | `write-schema` | `[:schema-bag :schema-large-value]` | Malli app-schema `:large? true` slot | 200 bytes | The slot is declared `:large?` on a registered `:rf/app-schema`. The runtime walks every registered app-schema at boot (per `populate-elision-from-schemas!`) and writes a `{:large? true :source :schema}` entry into the registry. |

## DOM mirrors

| Element | What it asserts |
|---|---|
| `auto-len` | `20480` after click A — the handler sees the full 20 KiB value via the regular cofx slot. The handler body is NOT affected by elision; only the wire-emit shape is. |
| `declared-len` / `fx-len` / `schema-len` | `200` after the corresponding click — small payloads but declared/fx/schema-derived elision still fires on the wire. |
| `auto-count` / `declared-count` / `fx-count` / `schema-count` | Advances on each click — proves the handler ran. |
| `elision-decls` | The contents of `[:rf/elision :declarations]` — a Playwright spec can assert that buttons B + C + D populate the slot with the expected `:source` (`:declared` / `:declared` / `:schema`). |

## The wire marker

Per [spec/009 §Wire marker — `:rf.size/large-elided`], the walker
substitutes the elided value with a normative shape:

```clojure
{:rf.size/large-elided
  {:path    [:auto-large-value]               ;; absolute path
   :bytes   20482                              ;; pr-str byte count
   :type    :string                            ;; one of :map :vector :set :scalar :string
   :reason  :runtime-flagged                   ;; or :declared / :schema
   :handle  [:rf.elision/at [:auto-large-value]]}}
```

A consumer asserting on the wire emit looks for the
`:rf.size/large-elided` keyword in the trace event's `:tags :app-db-*`
slots (or `:db-before` / `:db-after` in the epoch record); the
specific `:reason` distinguishes the nomination path.

## Why four buttons

Three nomination paths plus one composition control (auto-detect on
a 20 KiB write vs. declared on a 200-byte write) is the smallest
shape that exercises all the discrimination axes a consumer needs:

- **Three independent `:source` values** (`:declared`, `:schema`,
  `:runtime-flagged`) — a consumer that conflates them fails on
  button A (auto-detect should show `:source :runtime-flagged`,
  not `:declared`).
- **Imperative entry point vs declarative** (button B vs button C)
  — the REPL wrapper and the fx end at the same registry slot;
  a consumer that surfaces `:source :declared` for both is correct.
- **Schema-derived vs handler-declared** (button D vs B/C) — the
  `:source :schema` entry exists in the registry from boot, before
  any click. A consumer that only walks declarations on dispatch
  misses this category.
- **Threshold-triggered vs declaration-triggered** (button A vs
  B/C/D) — the auto-detect path produces a one-shot
  `:rf.warning/runtime-large-elision` advisory; the declaration
  paths do not. The presence/absence of the warning is the
  discriminator.

## What's deliberately *missing*

- **No `:sensitive?` on any path.** Privacy markers are the
  `sensitive_dispatcher/` testbed's job; composition (sensitive
  wins over size — per Spec 009) requires both, but each surface
  exercises one axis cleanly.
- **No `:digest` slot computation.** The `:digest` field of the
  marker is gated on the `:include-digests?` config flag; this
  surface stays on the default `false` so the marker shape is
  minimal and stable across runs.
- **No off-box wire egress in the surface itself.** The MCP wire
  is exercised by the consuming tool (pair2-mcp); this surface
  produces the in-process elision shape that the wire reads.
- **No state-machine snapshots.** Machine snapshots elide via the
  same walker (per [spec/005 §Wire-boundary elision]); conflating
  the machine surface with the app-db surface would dilute
  the four nomination paths.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- **`:large?` value arrives as `:rf.size/large-elided` marker** —
  the load-bearing scenario this surface unblocks. Causa's trace
  panel must show the `[:auto-large-value]` slot replaced with the
  marker shape under `:tags :app-db-after` on the first emit after
  button A.
- `:rf.warning/runtime-large-elision` highlighted in trace stream —
  button A's first emit fires the advisory; subsequent button-A
  clicks do not (the path is cached as flagged).
- Click-to-source from trace event lands on source-coord line —
  every handler in this surface carries reader meta; the four
  buttons each resolve to their handler's coord.

**Story (18)**:
- Recorder captures click → records `:play` → replays identically
  — the four clicks are deterministic; replay reproduces the same
  elision shape on each emit.

**Cross-cutting (6)**:
- Subscribe → re-render → trace ordering preserved — the four subs
  on the per-slot length re-run only when their slice changes;
  the elision marker doesn't reach the subscription layer (subs
  see the unredacted app-db value).

## Running

From `implementation/`:

```bash
shadow-cljs watch testbeds/large-dispatcher
# Or via the orchestrator:
npm run test:examples
```

The shadow-cljs build id is `testbeds/large-dispatcher`; output
lands in `implementation/out/testbeds/large-dispatcher/`.

## Cross-references

- [`spec/009-Instrumentation.md` §Size elision in traces](../../spec/009-Instrumentation.md) — the three-nomination-path contract this surface exercises.
- [`spec/009-Instrumentation.md` §Wire marker — `:rf.size/large-elided`](../../spec/009-Instrumentation.md) — the marker shape consumers assert against.
- [`spec/API.md` §`rf/elide-wire-value`](../../spec/API.md) — the wire-boundary walker (single normative emission site).
- [`spec/Spec-Schemas.md` §`:rf/elision-marker`](../../spec/Spec-Schemas.md) — the per-field MUST-level requirements on the marker shape.
- [`spec/Conventions.md` §Reserved fx-ids](../../spec/Conventions.md) — the `:rf.size/declare-large` / `:rf.size/clear` framework fxs.
