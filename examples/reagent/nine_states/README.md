# Nine States of UI — worked example

This example is one small **todos list** with a control panel of
buttons above it. Click a button and the screen rearranges itself to
match what just happened — a "Get started" welcome, a "Loading…"
message, an empty "No todos yet", a single focused todo, a plain short
list, or a "too many" view with a search box. Type into the add-a-todo
form and submit: too short and you get a validation error, three
characters or more and it confirms "✓ Todo added." Press **Archive**
and the whole list freezes — it goes read-only and the controls grey
out. There's no backend to set up; a tiny fake server runs right in the
page, so you just start it and click.

Why does one little list move through so many states? Because a
data-driven view passes through far more than the happy one — an empty
account, a slow network, a rejected form, a list of four thousand items
— all real, all easy to forget. A well-known UX taxonomy names the
**nine states** such a screen typically goes through: *Nothing,
Loading, Empty, One, Some, Too Many, Incorrect, Correct, Done.* This
example builds all nine for one small domain, and shows the clean way to
handle them: not nine booleans and a tower of `cond`, but a single
[state machine](../../../docs/machines/concepts.md).

The key idea is that the nine states aren't one axis. They're **three
independent questions**, asked at the same time:

- **How much data have we got?** — `nothing → loading → empty / one /
  some / too-many → error`
- **Is the form input any good?** — `neutral → correct / incorrect`
- **Is this list still live, or archived?** — `active → done`

Model that as one flat enum and the answers multiply: you get
`:loading-and-form-invalid-and-active` and a long tail of its cousins.
Model it instead as a `:type :parallel`
[machine](../../../docs/machines/glossary.md#machine) with three
**regions** — one per question — and the axes stay separate. Each region
is a small chart of a few
[states](../../../docs/machines/glossary.md#state). The
[snapshot](../../../docs/machines/glossary.md#snapshot)'s `:state` is then
a *map* of region → state, with all three running at once.

The second idea is how the
[view](../../../docs/guide/glossary.md#view) decides what to draw. It does
**not** read the three regions and reason about their combinations — that
just moves the `cond` tower into render. Instead:

- Every state carries [tags](../../../docs/machines/glossary.md#state-tag)
  describing its intent (`:data/loading`, `:form/invalid`,
  `:mode/read-only`, …).
- The runtime unions every active state's tags onto the snapshot.
- One plain-data `render-priority` table — read top to bottom by a single
  [subscription](../../../docs/guide/glossary.md#subscription),
  `:ui/render` — collapses that tag union to *one* render-model keyword.

The view's whole branching logic is then a single `case` over that
keyword. Nine states, three regions, one branch site.

## The nine states

| # | Name | What it shows | Trigger |
|---|---|---|---|
| 1 | **Nothing**   | Blank initial slate; never fetched. "Get started" CTA. | `[:nine-states.app/initialise]` |
| 2 | **Loading**   | First fetch in flight; no data yet. Spinner / skeleton. | `[:nine-states.demo/load {:n N}]` (transient) |
| 3 | **Empty**     | Fetched, but the result is the empty list. "No results" CTA. | `[:nine-states.demo/load {:n 0}]` |
| 4 | **One**       | Exactly one item; focused single-item layout. | `[:nine-states.demo/load {:n 1}]` |
| 5 | **Some**      | A small, manageable list; standard list rendering. | `[:nine-states.demo/load {:n 4}]` |
| 6 | **Too Many**  | Overwhelming amount; needs search / pagination / virtualisation. | `[:nine-states.demo/load {:n 25}]` |
| 7 | **Incorrect** | Form submission failed validation. Per-field errors visible. | type a 1-char title, submit |
| 8 | **Correct**   | Form submission succeeded; "Todo added." confirmation. | type a 3+ char title, submit |
| 9 | **Done**      | Mode region reached `:done`; terminal, read-only. | `[:ui/nine-states [:archive {}]]` |

A control panel at the top of the demo triggers each transition, so you
can walk the whole taxonomy by clicking.

## How the model is structured

One machine, three regions. Read each region as a self-contained little
lifecycle that shares the machine's `:data` map with its siblings:

- **`:data` region** — the cardinality axis: `:nothing → :loading →
  :resolving → {:empty | :one | :some | :too-many} | :error`. The
  region's *state keyword is the status*. There's no separate
  `:status :loading` field in app-db drifting out of sync with reality,
  because being in the `:loading` state **is** the loading status.
  `:resolving` is a transient step: it has no UI of its own. The moment a
  fetch's items land, an eventless
  [`:always`](../../../docs/machines/concepts.md) transition reads the
  item count off the shared `:data` and falls through to the right
  cardinality bucket in a single step — first guard that matches wins.
- **`:form` region** — the form's validation lifecycle: `:neutral →
  {:correct | :incorrect}`. `:correct` is transient too; the next `:edit`
  event returns the region to `:neutral`, so the "✓ Todo added" message
  clears itself the moment the user starts typing the next one. (The
  form's *runtime* — the draft text, per-field errors, which fields have
  been touched — lives in app-db at `:new-todo`, validated by a
  [schema](../../../docs/guide/glossary.md#schema). The region tracks only
  *which stage of the lifecycle* the form is in. Two kinds of fact, two
  homes.)
- **`:mode` region** — the live/archived axis: `:active → :done`.
  `:done` is terminal and tagged `:mode/read-only`. The form and the
  control buttons disable themselves by asking `machine-has-tag?` for
  `:mode/read-only` — *ask, don't tell*. The view never needs to know
  *which* state of *which* region carries the read-only intent; it asks a
  question and gets a yes/no.

Every state declares `:tags` for its per-axis intent. The
`render-priority` table over those tags is the one place the page's
display priorities live — and they encode a real product decision,
readable at a glance: `:mode` wins outright (an archived list replaces
everything), `:form` acknowledgements overlay next (they're transient),
and the `:data` cardinality bucket is the fallback. To change which state
beats which, you edit one table, not ten views.

## What this example demonstrates

- **Parallel regions + tags.** Three orthogonal axes declared in one
  machine; tags compose across regions; a single selector subscription
  folds the tag union into a render keyword, so the view branches exactly
  once. This is the headline. See
  [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md)
  and [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md)
  §Parallel regions / §State tags.
- **A remote-data lifecycle, folded into a region.** A real
  [managed-HTTP](../../../docs/guide/glossary.md#effect) request drives
  the fetch. Its reply comes back the re-frame2 way — as a dispatched
  [event](../../../docs/guide/glossary.md#event) carrying a reply map
  ([the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply)),
  which the demo events forward into the machine as `:fetch-succeeded` /
  `:fetch-failed`. No promise-threading glue between the network and the
  state graph. See
  [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md).
- **A form, the data-oriented way.** The `{:draft :errors :touched}`
  slice in app-db holds the form's working state; a pure validator decides
  correct-vs-incorrect; the form region tracks the lifecycle. See
  [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md).
- **Inspectability bias.** The non-trivial guards and actions
  (`:too-many?`, `:set-items`, `:stamp-archived`, …) are *named* entries
  in the machine's `:guards` / `:actions` maps, so a diagram or a tool
  reads the condition right off the arrow. Only the genuinely trivial
  transitions use inline anonymous fns. The machine declaration is meant
  to be read.

## Legacy variant

This example is the canonical implementation of Pattern-NineStates. The
older variant — nine boolean discriminator subs plus a priority `cond` —
still works and is supported, but it's the shape this example exists to
retire. Reach for the machine.

## File layout

```
examples/reagent/nine_states/
  core.cljs            single-file example: schemas, the :ui/nine-states
                       parallel machine, demo events, the render-priority
                       table + :ui/render sub, per-state views, mount.
  index.html           minimal host page (the live app).
  stories.cljs         Story showcase: one variant per canonical render
                       keyword, plus the fetch-lifecycle story (auxiliary;
                       see below).
  stories_host.cljs    Story-showcase entry point (live-app ↔ shell hash router).
  stories.index.html   host page for the Story-showcase build.
  README.md            this file.
```

The three `stories*` files are an auxiliary Story showcase layered over
this example. They source `nine-states.core`'s real parallel machine and
`:ui/render` selector and enumerate the nine canonical render keywords
(plus the async fetch-lifecycle) as Story variants, with the Xray preload
wired so the `load → loading → loaded/error` cascade is inspectable. See
[How to run](#how-to-run) for the showcase command.

The whole example is one file for brevity. In a real codebase you'd split
it the way re-frame2 conventions recommend — `schema.cljc / machine.cljc
/ events.cljs / subs.cljs / views.cljs` — but a single readable file
makes the shape easier to take in at one sitting.

## How to run

From `implementation/`, watch the build and open it:

```bash
shadow-cljs watch examples/nine-states
```

To run the Story showcase instead, watch its build and open the Story
shell:

```bash
shadow-cljs watch :examples/nine-states-with-stories   # then open http://localhost:8040/#/stories
```

`#/` renders the live demo; `#/stories` mounts the Story shell with each
canonical render state as a variant. Press <kbd>Ctrl+Shift+C</kbd> on
either surface to open Xray over the load cascade — pick the `:some` or
`:error` variant and watch the fetch light up the Epoch, Trace, and Side
Effects panels end to end.
