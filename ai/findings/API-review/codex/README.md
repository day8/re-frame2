# API Review: Crowded Public Surfaces

Status: draft findings.

This directory records API areas where the implementation exposes more than
one similar way to say the same thing. The review looked at `implementation/`,
then checked actual pressure from `examples/`, `tools/`, `spec/`, and `docs/`.
It also folds in the parallel Claude review under
`ai/findings/API-review/claude`.

The signal used here is not "many functions are bad". Some concepts really do
need separate names. The smell is when two public call shapes answer the same
user question, or when a historical implementation concept is still visible
after the model has moved on.

The most important global rule after EP-0023 is:

- the public model is `image -> frame -> event stream`;
- retired app/realm/module vocabulary should not appear on the public facade,
  in current docs, or in app-facing examples except as named drift to remove;
- any current API or code mention of that vocabulary is a cleanup target, not an
  accepted substrate to preserve.

The repeated cleanup rule is:

- prefer one data-shaped spelling for optional dimensions;
- make lifecycle and addressing separate words;
- teach data query vectors before wrapper functions;
- keep helper names only when they name a different intent, not a shorter path
  to the same intent;
- keep advanced implementation vocabulary out of the front-door API.

Findings:

- [retired-app-composition-vocabulary.md](retired-app-composition-vocabulary.md)
- [frame-targeting-and-lifecycle.md](frame-targeting-and-lifecycle.md)
- [registrar-query-addressing.md](registrar-query-addressing.md)
- [machine-helper-surfaces.md](machine-helper-surfaces.md)
- [view-registration-and-rendering.md](view-registration-and-rendering.md)
- [frame-state-and-history.md](frame-state-and-history.md)
- [registration-programmatic-forms.md](registration-programmatic-forms.md)
- [resource-and-mutation-reads.md](resource-and-mutation-reads.md)
- [boot-config-and-adapters.md](boot-config-and-adapters.md)

Coverage notes:

- Claude's app-composition review is folded into
  `retired-app-composition-vocabulary.md`.
- Claude's frame-targeting review is folded into
  `frame-targeting-and-lifecycle.md`.
- Claude's introspection/state-reader review is split across
  `registrar-query-addressing.md` and `frame-state-and-history.md`.
- Claude's registration-form review is folded into
  `registration-programmatic-forms.md`.
- The remaining Codex findings cover additional crowded areas Claude did not
  separately write up: machines, views, resources/mutations, and boot/adapters.

The common philosophical point is very Clojure: the stable API should be small
values and clear operations over those values. A framework can have a rich
runtime, but the public language should not ask users to remember which of
three aliases was canonical in the current context.

## Fresh consolidation pass (2026-06-18)

A second, fully independent five-lens pass (the same five lenses, blind to this
corpus) was consolidated in. It re-discovered the areas above independently
(cross-validation) and added three new findings plus a cross-cutting one. Each
area file gained a `## Implementation` section.

New files added this pass:
- [frame-object-record-unification.md](frame-object-record-unification.md) -
  highest-leverage frame finding and the most contentious (questions the graduated
  EP-0023 internal structure: two `make-frame` constructors + two registries).
- [elision-redaction-helpers.md](elision-redaction-helpers.md) - four-lens-
  converged: 9 granular redaction helpers + 1 assembling tool + API.md drift; plus
  the imperative-marks residue.
- [listener-and-sink-registries.md](listener-and-sink-registries.md) - five
  identical register/unregister registries -> one stream-parameterized verb + one
  production sink.
- [facade-accretion-and-removal.md](facade-accretion-and-removal.md) -
  cross-cutting; the layered-accretion mechanism beneath ~half the findings, plus
  the pre-alpha delete-not-demote disposition that governs them.

### Governing disposition: pre-alpha removal, not demotion (Mike, 2026-06-18)

re-frame2 is pre-alpha - no back-compat shims, no migration layers. A superseded
or layered surface is either kept because the live internal design genuinely uses
it (then internal-only: its own namespace, never facade / manifest / spec/API.md)
or kept only so prior callers keep working (then removed outright). There is no
"retained-for-migration" facade tier. Demotion-by-docstring is not enough: the
disposition must fire on the export list, the api-manifest* rows, and the
spec/API.md rows. This resolves the open facade ruling toward removal.

### Implementation routing

- **One EP - frame grammar + object model.** frame-targeting-and-lifecycle +
  frame-object-record-unification + the :frame/:realm query split in
  registrar-query-addressing are one coupled public-grammar change; land them as a
  single EP (or EP-0023 amendment), not three. Needs owner adjudication first.
- **Existing epic rf2-pl97nd.** retired-app-composition-vocabulary - the pre-alpha
  disposition resolves its open ruling toward removal.
- **One guardrail bead.** facade-accretion-and-removal - generalize the pl97nd.5
  gate into a standing manifest-hygiene check. The disposition itself is ruled.
- **Ordinary beads (docs-first or facade-pruning).** machine helpers, views,
  resource/mutation reads, registration programmatic forms, frame-state, elision
  helpers, listener/sink registries, boot/adapters - each with its own
  Implementation section.

### Overlap / conflict / new

- **Overlap** (re-discovered, evidence strengthened): EP-0013 retired vocab,
  :realm/:frame address fusion, partition readers / snapshot-of / compute-sub,
  machine helpers, *-fn / reg-view* / removed-stubs, frame-bound-fn /
  with-new-frame.
- **Conflict** (one, adjudicated): the minimalist lens proposed moving
  machine-has-tag? off the facade; the empirical lens (64 hits) + the corpus keep
  it. Adoption wins; sub-machine is the only machine helper to drop.
- **New**: the four files above, plus deltas - removed-stub data table, interceptor
  context accessors, ->interceptor tier fix, schema validator cluster,
  trace-projection re-exports, make-frame-handle -> frame-handle*, EP-0023
  vocabulary near-synonyms, and the HTTP test-support pair.
