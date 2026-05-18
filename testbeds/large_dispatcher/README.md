# `testbeds/large-dispatcher`

Four handlers exercise the schema-first wire-elision surface defined
in [spec/009 §Size elision in traces](../../spec/009-Instrumentation.md).
Three buttons write to schema-declared `:large?` slots and one button
writes to an undeclared slot to exercise the
`:rf.warning/large-value-unschema'd` dev-mode advisory. A consumer
(Causa, Story, re-frame2-pair-mcp) reads the surface to verify the
wire-boundary walker substitutes the `:rf.size/large-elided`
marker on the appropriate slot.

## The four buttons

| Button | `data-testid` | Path written | Mechanism | Payload size | Outcome |
|---|---|---|---|---|---|
| A | `write-auto` | `[:auto-large-value]` | No schema declaration | **20 KiB** | The wire-boundary walker emits the `:rf.warning/large-value-unschema'd` dev-mode advisory once (the path has no `:large?` schema and exceeds the 16 KiB warning threshold) and leaves the value inline. Schemas are the only path to elision — the warning is the nudge to add one. |
| B | `write-declared` | `[:declared-large-value]` | Flat Malli `:large? true` slot | 200 bytes | The slot is registered with `:large? true` via `rf/reg-app-schemas`. The walker substitutes the `:rf.size/large-elided` marker on every emit regardless of size. |
| C | `write-fx-declared` | `[:fx-declared-value]` | Second flat Malli `:large? true` slot | 200 bytes | Same outcome as Button B on a second slot — gives consumers two simultaneous schema-declared marker rows to assert against. |
| D | `write-schema` | `[:schema-bag :schema-large-value]` | Nested Malli `:large? true` slot | 200 bytes | Same outcome as Button B/C but the `:large?` lives inside a composed `:map` schema (`SchemaLarge`) rather than at the flat root. Exercises the schema walker's traversal into nested slots. |

## DOM mirrors

| Element | What it asserts |
|---|---|
| `auto-len` | `20480` after click A — the handler sees the full 20 KiB value via the regular cofx slot. Click A does NOT cause wire elision (no schema declaration); it only fires the unschema'd-large warning. |
| `declared-len` / `fx-len` / `schema-len` | `200` after the corresponding click — small payloads but schema-declared elision still fires on the wire. |
| `auto-count` / `declared-count` / `fx-count` / `schema-count` | Advances on each click — proves the handler ran. |
| `elision-decls` | The contents of `[:rf/elision :declarations]` — populated at boot from the schema. A Playwright spec can assert that all three schema-declared paths land with `:source :schema` (the only nomination path the runtime supports). |

## The wire marker

Per [spec/009 §Wire marker — `:rf.size/large-elided`], the walker
substitutes the elided value with a normative shape:

```clojure
{:rf.size/large-elided
  {:path    [:declared-large-value]            ;; absolute path
   :bytes   202                                ;; pr-str byte count
   :type    :string                            ;; one of :map :vector :set :scalar :string
   :reason  :schema                            ;; schema is the only nomination path
   :handle  [:rf.elision/at [:declared-large-value]]}}
```

A consumer asserting on the wire emit looks for the
`:rf.size/large-elided` keyword in the trace event's `:tags :app-db-*`
slots (or `:db-before` / `:db-after` in the epoch record). All three
schema-declared paths emit `:reason :schema`; click A does not produce
a marker (it produces the `:rf.warning/large-value-unschema'd`
advisory instead).

## Why four buttons

Three schema-declared paths plus one warning-only control is the
smallest shape that exercises every discrimination axis a consumer
needs:

- **Flat vs nested schema slot** (B / C vs D) — buttons B and C
  exercise flat root slots registered directly via
  `rf/reg-app-schemas`; button D exercises a `:large?` slot nested
  inside a composed Malli `:map` schema. A consumer that only
  resolves flat registrations misses button D.
- **Two simultaneous flat declarations** (B vs C) — having two
  flat declarations active at the same time lets a consumer
  assert the registry contains a *map* of declarations, not
  just a single one. A consumer that overwrites declarations
  on each registration fails this check.
- **Schema vs unschema'd large value** (B / C / D vs A) — only
  schema-declared slots emit the marker; an undeclared large
  slot emits the `:rf.warning/large-value-unschema'd` advisory
  once. The presence/absence of the warning is the discriminator
  between "add a schema" and "I already did".

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
  is exercised by the consuming tool (re-frame2-pair-mcp); this surface
  produces the in-process elision shape that the wire reads.
- **No state-machine snapshots.** Machine snapshots elide via the
  same walker (per [spec/005 §Wire-boundary elision]); conflating
  the machine surface with the app-db surface would dilute
  the four nomination paths.

## Test scenarios from rf2-fe84r this surface enables

**Causa (26)**:
- **`:large?` value arrives as `:rf.size/large-elided` marker** —
  the load-bearing scenario this surface unblocks. Causa's trace
  panel must show the `[:declared-large-value]` slot replaced with
  the marker shape under `:tags :app-db-after` on the first emit
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
npm run test:examples
```

The shadow-cljs build id is `testbeds/large-dispatcher`; output
lands in `implementation/out/testbeds/large-dispatcher/`.

## Cross-references

- [`spec/009-Instrumentation.md` §Size elision in traces](../../spec/009-Instrumentation.md) — the three-nomination-path contract this surface exercises.
- [`spec/009-Instrumentation.md` §Wire marker — `:rf.size/large-elided`](../../spec/009-Instrumentation.md) — the marker shape consumers assert against.
- [`spec/API.md` §`rf/elide-wire-value`](../../spec/API.md) — the wire-boundary walker (single normative emission site).
- [`spec/Spec-Schemas.md` §`:rf/elision-marker`](../../spec/Spec-Schemas.md) — the per-field MUST-level requirements on the marker shape.
- [`spec/Conventions.md` §Reserved namespaces](../../spec/Conventions.md) — the `:rf.size/*` and `:rf.elision/*` reserved-namespace rows.
