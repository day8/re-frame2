# Listener And Sink Registries

Status: draft finding — **new in the 2026-06-18 fresh consolidation pass**
(technical-taste + empirical-call-site lenses; no prior corpus coverage).

## Crowding Signal

The facade carries **five structurally identical id-keyed register/unregister
registries**, each as its own pair (or trio) of public vars, differing only in
the record shape and the always-on/dev-only axis:

- trace: `register-listener!` / `unregister-listener!` / `clear-listeners!`
- handled events: `register-event-listener!` / `unregister-event-listener!`
- errors: `register-error-listener!` / `unregister-error-listener!`
- observability sink: `register-observability-sink!` /
  `unregister-observability-sink!`
- epoch: `register-epoch-listener!` / `unregister-epoch-listener!`

Ten-plus facade vars for one shape — `(register-X! id f)` with same-id-replace
semantics and a returned id. CONSISTENCY achieved by repetition rather than by a
parameter; the difference between them is **data** (which stream), not API shape.

## Implementation / call-site evidence (verified)

- Exports: trace `core.cljc:2716-2728`, event + error `2759-2807`, observability
  sink `2845-2860`, epoch `3010-3020`. Each docstring repeats "Same-id
  registration replaces. Returns id."
- **Adoption is near-zero at the facade.** Across `examples/**` +
  `tools/**/src/**`: `register-observability-sink!` 0/0,
  `register-event-listener!` 0/0, `register-error-listener!` 0/0. tests-impl:
  24 / 20 / 93, all in `*_emit_*`, `always_on_axis_conformance`,
  `ep0008_producers_*`, `core_api_additions` suites. The docstrings name
  Datadog/Sentry/Honeybadger as the intended consumers; none exists in-repo and
  no example wires one.
- The *mechanisms* earn their keep internally — the router fan-out and the
  frame-teardown error discriminator reuse the error-emit registry via
  `late-bind` — but no caller registers through the **facade** export.
- The observability-sink pair is documented as "the normal production path"
  while event/error-listener are "advanced corpus-wide"
  (`core.cljc:2768-2775`): two tiers doing overlapping jobs, both with zero
  production callers.

## Observed use cases

1. Off-box shippers (the intended but absent observability-sink consumer).
2. Xray / Story / pair subscribe to the trace stream.
3. Epoch recorders subscribe to epoch records.
4. The error stream is consumed internally (router + teardown), not via the
   facade register fn.

The streams are genuinely distinct (handled-event vs error vs trace vs epoch),
so collapsing them to **one** registry would over-merge — the always-on vs
DCE-gated axes differ. The waste is the **per-stream name-mangled pairs** on the
facade, plus the redundant second production-observation surface.

## Proposed smaller API

Two moves:

1. **Stream-parameterize the verb.** Keep one register/unregister shape and pass
   the stream as data:

   ```clojure
   (rf/register-listener! :trace  id f)
   (rf/register-listener! :events id f)
   (rf/register-listener! :errors id f)
   (rf/register-listener! :epoch  id f)
   ```

   The per-stream registries stay where they are (they have different always-on
   / DCE characteristics); the facade gains a `stream` argument instead of N
   name-mangled pairs. Collapses ~8 vars to ~2.

2. **One production-observation surface.** Keep
   `register-observability-sink!` / `unregister-observability-sink!` as the
   single blessed production path (frame-routed, projected through
   `project-egress`). Drop the corpus-wide `register-event-listener!` /
   `register-error-listener!` from the facade; retain them internally for the
   router's own fan-out, reachable via `re-frame.event-emit` /
   `re-frame.error-emit` for the rare cross-frame case. Merge the API.md
   §Event-emit / §Error-emit sections into one "Production observability"
   section pointing at the sink.

## Classification

Decision bead + ordinary beads. The stream-parameterization is a facade-shape
decision (one verb + data vs N pairs); the observability-sink consolidation is a
facade-pruning bead once the two unused corpus-listener pairs are confirmed
movable. Neither changes the underlying per-stream registry mechanisms.

## Why this is better

When five surfaces share a verb, an argument, and a replace-on-same-id contract,
the thing that varies between them is the stream — that is data, and it belongs
in an argument, not in five hand-mangled names. And a "normal production path"
plus an "advanced corpus-wide" path for the same job — both with zero production
callers — is one observation surface too many for a pre-alpha with no consumers
to keep both honest.

## Implementation

- **Vehicle: one decision bead (facade shape) + ordinary beads.** No EP.
- Decision: stream-parameterize the verb (`register-listener! :trace id f`) vs keep
  N named pairs.
- Beads: (1) collapse to one production-observation surface
  (`register-observability-sink!`), drop the corpus event/error-listener pairs from
  the facade (0 callers); (2) apply the stream-parameter outcome to
  trace/event/error/epoch.
- Pre-alpha disposition: the zero-caller pairs are removed, not demoted.
