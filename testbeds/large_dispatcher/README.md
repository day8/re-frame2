# `testbeds/large-dispatcher`

Four handlers exercise the wire-elision surface defined
in [spec/009 §Size elision in traces](../../spec/009-Instrumentation.md).
Three buttons write to paths classified `:large` via the EP-0025
commit-plane classification effect, and one button
writes to an undeclared slot to exercise the
`:rf.warning/large-value-unschema'd` dev-mode advisory. A consumer
(Xray, Story, re-frame2-pair-mcp) reads the surface to verify the
wire-boundary walker substitutes the `:rf.size/large-elided`
marker on the appropriate slot.

## The four buttons

| Button | `data-testid` | Path written | Mechanism | Payload size | Outcome |
|---|---|---|---|---|---|
| A | `write-auto` | `[:auto-large-value]` | No classification | **20 KiB** | The wire-boundary walker emits the `:rf.warning/large-value-unschema'd` dev-mode advisory once (the path is not classified `:large` and exceeds the 16 KiB warning threshold) and leaves the value inline. Classify the path with the `:large` effect to elide it regardless of size — the warning is the nudge. (Fail-open: an unclassified small value rides raw.) |
| B | `write-declared` | `[:declared-large-value]` | Classified `:large` (commit-plane effect) | 200 bytes | The path is classified `:large` by the `:large` effect the boot event returns alongside `:db`. The walker substitutes the `:rf.size/large-elided` marker on every emit regardless of size. |
| C | `write-fx-declared` | `[:fx-declared-value]` | Second `:large`-classified path | 200 bytes | Same outcome as Button B on a second path — gives consumers two simultaneous marker rows to assert against. |
| D | `write-schema` | `[:schema-bag :schema-large-value]` | Nested `:large`-classified path | 200 bytes | Same outcome as Button B/C but the classified path is nested inside a composed `:map` (validated by `SchemaLarge`) rather than at the flat root. Exercises the walker's traversal into nested slots. The schema here only **validates** shape — the `:large` classification rides the commit-plane effect. |

## DOM mirrors

| Element | What it asserts |
|---|---|
| `auto-len` | `20480` after click A — the handler sees the full 20 KiB value via the regular cofx slot. Click A does NOT cause wire elision (path not classified); it only fires the unschema'd-large warning. |
| `declared-len` / `fx-len` / `schema-len` | `200` after the corresponding click — small payloads but the `:large` classification still fires elision on the wire. |
| `auto-count` / `declared-count` / `fx-count` / `schema-count` | Advances on each click — proves the handler ran. |
| `elision-decls` | The contents of `[:rf.runtime/elision :declarations]` — populated at boot by the `:large` commit-plane effect. A Playwright spec can assert that all three classified paths land with `:source :effect` (the durable app-db classification route). |

## The wire marker

Per [spec/009 §Wire marker — `:rf.size/large-elided`], the walker
substitutes the elided value with a normative shape:

```clojure
{:rf.size/large-elided
  {:path    [:declared-large-value]            ;; absolute path
   :bytes   202                                ;; pr-str byte count
   :type    :string                            ;; one of :map :vector :set :scalar :string
   :reason  :effect                            ;; classified via the :large commit-plane effect
   :handle  [:rf.elision/at [:declared-large-value]]}}
```

A consumer asserting on the wire emit looks for the
`:rf.size/large-elided` keyword in the trace event's `:tags :app-db-*`
slots (or `:db-before` / `:db-after` in the epoch record). All three
classified paths emit `:reason :effect`; click A does not produce
a marker (it produces the `:rf.warning/large-value-unschema'd`
advisory instead).

## Why four buttons

Three classified paths plus one warning-only control is the
smallest shape that exercises every discrimination axis a consumer
needs:

- **Flat vs nested classified path** (B / C vs D) — buttons B and C
  classify flat root paths; button D classifies a path nested
  inside a composed `:map`. A consumer that only resolves flat
  paths misses button D.
- **Two simultaneous declarations** (B vs C) — having two
  classified paths active at the same time lets a consumer
  assert the registry contains a *map* of declarations, not
  just a single one. A consumer that overwrites declarations
  on each write fails this check.
- **Classified vs unclassified large value** (B / C / D vs A) — only
  classified paths emit the marker; an unclassified large
  slot emits the `:rf.warning/large-value-unschema'd` advisory
  once (and ships raw — fail-open). The presence/absence of the warning
  is the discriminator between "classify the path" and "I already did".

## What's deliberately *missing*

- **No `:sensitive` classification on any path.** This surface
  exercises the `:large` axis cleanly; composition (sensitive
  wins over large — per Spec 015) requires both, but each surface
  exercises one axis.
- **No `:digest` slot computation.** The `:digest` field of the
  marker is gated on the `:include-digests?` config flag; this
  surface stays on the default `false` so the marker shape is
  minimal and stable across runs.
- **No off-box wire egress in the surface itself.** The MCP wire
  is exercised by the consuming tool (re-frame2-pair-mcp); this surface
  produces the in-process elision shape that the wire reads.
- **No state-machine snapshots.** Machine snapshots elide via the
  same walker (per [spec/005 §Wire-boundary elision]); conflating
  the machine surface with the app-db surface would dilute
  the four nomination paths.

## Test scenarios from rf2-fe84r this surface enables

**Xray (26)**:
- **A `:large`-classified value arrives as a `:rf.size/large-elided` marker** —
  the load-bearing scenario this surface unblocks. Xray's trace
  panel must show the `[:declared-large-value]` slot replaced with
  the marker shape under `:tags :db-after` on the first emit
  after button B.
- `:rf.warning/large-value-unschema'd` highlighted in trace stream —
  button A's first emit fires the advisory; subsequent button-A
  clicks do not (the path is cached as warned-once).
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
npm run test:adapter-smokes
```

The shadow-cljs build id is `testbeds/large-dispatcher`; output
lands in `implementation/out/testbeds/large-dispatcher/`.

## Cross-references

- [`spec/009-Instrumentation.md` §Size elision in traces](../../spec/009-Instrumentation.md) — the three-nomination-path contract this surface exercises.
- [`spec/009-Instrumentation.md` §Wire marker — `:rf.size/large-elided`](../../spec/009-Instrumentation.md) — the marker shape consumers assert against.
- [`spec/API.md` §`rf/elide-wire-value`](../../spec/API.md) — the wire-boundary walker (single normative emission site).
- [`spec/Spec-Schemas.md` §`:rf/elision-marker`](../../spec/Spec-Schemas.md) — the per-field MUST-level requirements on the marker shape.
- [`spec/Conventions.md` §Reserved namespaces](../../spec/Conventions.md) — the `:rf.size/*` and `:rf.elision/*` reserved-namespace rows.
