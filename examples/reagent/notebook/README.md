# notebook — Reagent design-led example

A three-pane editorial layout on the [Reagent](https://github.com/reagent-project/reagent)
[substrate](../../../docs/guide/glossary.md#substrate): a documents tree
on the left, a markdown editor in the middle, a live preview on the
right. The point it makes is that re-frame2 + Reagent scale past the
counter — a whole editor's worth of UI is just a tree of registered
[views](../../../docs/guide/glossary.md#view) reading a
[subscription](../../../docs/guide/glossary.md#subscription) graph — and
that the one genuinely fiddly part of an editor, rendering
user-controlled markdown, falls out cleanly when you treat markup as
[data](../../../docs/guide/glossary.md#hiccup) instead of strings.

## What this demonstrates

The notebook is deliberately ordinary as an app. What's worth reading
is *how little* sits between a keystroke and the rendered preview, and
where the one sharp edge — untrusted text becoming HTML — gets sanded
down. Three ideas carry it.

**Every pane is a registered view.** There's no
top-level "render the app" function juggling the three panes by hand.
The shell is a [view](../../../docs/guide/glossary.md#view); so are the
sidebar, the editor, and the preview. Each is its own `reg-view` Var,
and the whole UI is just those Vars composed (the document rows inside
the sidebar are ordinary inline hiccup, not their own registration):

```clojure
(reg-view notebook []
  [:div.nb-shell
   [sidebar]
   [editor]
   [preview]])
```

Because each pane is a named registration, each is independently
inspectable and subscribes only to what it actually needs — the editor
reads the selected document's body, the sidebar reads the document
list. The framework prunes everything downstream of an input that
didn't move, so editing the body re-derives the preview without
touching the sidebar, and the panes stay decoupled without you wiring
up a single listener.

**One edit, one turn of [the cascade](../../../docs/guide/glossary.md#event-cascade).**
Typing in the editor dispatches `[:notebook/edit-body text]`; clicking
a document dispatches `[:notebook/select id]`. From there it's the
same loop re-frame2 runs for everything: the
[event handler](../../../docs/guide/glossary.md#event-handler) writes
the new [app-db](../../../docs/guide/glossary.md#app-db), the
subscriptions re-derive, the views render once from the committed
state. The preview is the prettiest link in that chain — it's a derived
subscription that runs the selected body through the markdown parser:

```clojure
(rf/reg-sub :notebook/selected-hiccup
  :<- [:notebook/selected-body]
  (fn [body _] (markdown->hiccup body)))
```

The preview view subscribes to `:notebook/selected-hiccup` and splices
the result straight into the DOM. Parsing happens *once per edit*, in
one cached node of the
[derivation graph](../../../docs/guide/glossary.md#the-derivation-graph),
not on every render — the view just asks for the parsed blocks and gets
a settled answer.

**The markdown parser emits hiccup, and that's the whole safety story.**
This is the load-bearing trick. `markdown->hiccup` is a tiny pure-CLJS
parser — headings, bold, italic, inline code, links, paragraphs,
ordered and unordered lists — that produces
[hiccup](../../../docs/guide/glossary.md#hiccup) (`[:h1 "…"]`,
`[:strong "…"]`) rather than an HTML string. That one choice buys two
things at once. There's no extra npm dependency, so the bundle stays
small; and because Reagent renders hiccup natively, there's no
`dangerouslySetInnerHTML` escape hatch anywhere in the example. The
preview is a *user-controlled surface* — whatever you type becomes the
DOM — so the parser sanitizes link destinations against a scheme
allowlist before they ever reach an `:a`:

```clojure
(def ^:private safe-link-schemes #{"http" "https" "mailto"})

;; in the link rule:
(if-let [href (safe-href (nth m 2))]
  [:a {:href href :rel "noopener noreferrer" :target "_blank"} text]
  [:span.nb-unsafe-link text])     ;; javascript:, data:, … → inert text
```

A `[click me](javascript:alert(1))` link renders as plain, un-clickable
text instead of an XSS vector. Doing this as *data* — branch on the
scheme, emit a different hiccup vector — is far easier to get right
than scrubbing an HTML string after the fact, which is the whole
argument for data-oriented rendering in one tidy function.

One last detail in the same spirit: the **`:new` event allocates
document ids deterministically** — `allocate-next-doc-id` scans the
`doc-N` ids already in app-db and takes max + 1, rather than reaching
for `(rand-int)`. The new id gets written into durable app-db, so it
has to be a pure function of prior state; otherwise replaying the
event stream would mint a different id and break replay determinism.
It's a small nod to the same principle that makes the whole loop
testable: keep the durable writes a function of recorded inputs, never
of the ambient world.

## Why this shape

A design-led example earns its keep by proving polished visuals and
interaction hold up on its substrate — so it deliberately skips the
platform features other examples already cover. No state machines, no
HTTP, no routing here; the dataflow and the rendering get to be the
star. This is the Reagent member of a three-substrate design-led trio,
each a genuinely different, substantial UI on a different
[substrate](../../../docs/guide/glossary.md#substrate):

| Substrate | Example | Shape |
|---|---|---|
| Reagent | `notebook` (this) | Three-pane editor |
| UIx | [`dashboard_uix`](../../uix/dashboard_uix/) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../helix/process_monitor_helix/) | Terminal log viewer |

All three wear the same "Editorial Warm" identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css), so what
your eye reads as *different* between them is the layout and the
interaction, never the brand. Use the trio to feel how each substrate's
idiom lands at non-trivial scale; this one shows Reagent's `reg-view` +
`@(subscribe …)` idiom doing the whole job.

A word on the substrate choice: this is **stock Reagent**
(`reagent.dom.client` + `re-frame.adapter.reagent`), not reagent-slim,
which keeps the trio on the reference substrate for each adapter.

The mount itself is the ordinary re-frame2 boot dance:
[`init!`](../../../docs/guide/glossary.md#init) installs the Reagent
[adapter](../../../docs/guide/glossary.md#adapter),
[`reg-frame`](../../../docs/guide/glossary.md#registration) registers the
app [frame](../../../docs/guide/glossary.md#frame), a
[`dispatch-sync`](../../../docs/guide/glossary.md#dispatch-sync) seeds
the [app-db](../../../docs/guide/glossary.md#app-db) before the first
paint, and the tree renders inside a `frame-provider-existing` so every
in-tree `dispatch`/`subscribe` resolves to that frame. Render with *no*
provider and those calls raise `:rf.error/no-frame-context` —
[identity is carried, not found](../../../docs/guide/glossary.md#frame-identity-is-carried-not-found),
even for a three-pane toy.

## Files

```
notebook/
  core.cljs    — events, subs, markdown parser, views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
shadow-cljs watch examples/notebook
```

Then open [`index.html`](index.html) against the watch build.
