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
