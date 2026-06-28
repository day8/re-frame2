# A markdown editor with a live preview

Type markdown in the middle pane and watch it render, live, in the
preview on the right. Pick a document from the tree on the left to edit
it, or start a new one. That's the whole app: three panes — a documents
tree, a markdown editor, a live preview — built on the
[Reagent](https://github.com/reagent-project/reagent)
[substrate](../../../docs/core/glossary.md#substrate).

It shows two things. First, re-frame2 and Reagent scale past the
counter — a whole editor is just a tree of registered
[views](../../../docs/core/glossary.md#view) reading a
[subscription](../../../docs/core/glossary.md#subscription) graph.
Second, the fiddly part of any editor — rendering user-controlled
markdown — turns out simple when you treat markup as
[data](../../../docs/core/glossary.md#hiccup) instead of strings.

## What this demonstrates

The notebook is an ordinary app on purpose. What's worth reading is how
little sits between a keystroke and the rendered preview, and how the
one sharp edge — untrusted text becoming HTML — is handled. Three ideas
carry it.

**Every pane is a registered view.** No top-level "render the app"
function juggles the three panes by hand. The shell is a
[view](../../../docs/core/glossary.md#view); so are the sidebar, the
editor, and the preview. Each is its own `reg-view` Var, and the whole
UI is just those Vars composed. (The document rows inside the sidebar
are plain inline hiccup, not their own registration.)

```clojure
(reg-view notebook []
  [:div.nb-shell
   [sidebar]
   [editor]
   [preview]])
```

Because each pane is a named registration, each is inspectable on its
own and subscribes only to what it needs. The editor reads the selected
document's body; the sidebar reads the document list. The framework
skips anything downstream of an input that didn't change. So editing
the body re-derives the preview without touching the sidebar — the
panes stay decoupled, and you wire up no listeners at all.

**One edit, one turn of [the cascade](../../../docs/core/glossary.md#event-cascade).**
Typing in the editor dispatches `[:notebook/edit-body text]`. Clicking
a document dispatches `[:notebook/select id]`. From there it's the same
loop re-frame2 runs for everything. The
[event handler](../../../docs/core/glossary.md#event-handler) writes
the new [app-db](../../../docs/core/glossary.md#app-db). The
subscriptions re-derive. The views render once from the committed
state. The preview is a derived subscription that runs the selected
body through the markdown parser:

```clojure
(rf/reg-sub :notebook/selected-hiccup
  :<- [:notebook/selected-body]
  (fn [body _] (markdown->hiccup body)))
```

The preview view subscribes to `:notebook/selected-hiccup` and splices
the result straight into the DOM. Parsing happens once per edit, not on
every render. It runs in one cached node of the
[derivation graph](../../../docs/core/glossary.md#the-derivation-graph),
so the view just asks for the parsed blocks and gets a settled answer.

**The markdown parser emits hiccup, and that's the whole safety story.**
This is the key trick. `markdown->hiccup` is a tiny pure-CLJS parser. It
handles headings, bold, italic, inline code, links, paragraphs, and
ordered and unordered lists. It produces
[hiccup](../../../docs/core/glossary.md#hiccup) (`[:h1 "…"]`,
`[:strong "…"]`), not an HTML string. That one choice buys two things.
There's no extra npm dependency, so the bundle stays small. And because
Reagent renders hiccup natively, there's no `dangerouslySetInnerHTML`
anywhere in the example. The preview is a user-controlled surface —
whatever you type becomes the DOM. So the parser checks each link
destination against an allowlist of schemes before it ever reaches an
`:a`:

```clojure
(def ^:private safe-link-schemes #{"http" "https" "mailto"})

;; in the link rule:
(if-let [href (safe-href (nth m 2))]
  [:a {:href href :rel "noopener noreferrer" :target "_blank"} text]
  [:span.nb-unsafe-link text])     ;; javascript:, data:, … → inert text
```

A `[click me](javascript:alert(1))` link renders as plain, un-clickable
text instead of an XSS vector. Doing this as data — branch on the
scheme, emit a different hiccup vector — is far easier to get right than
scrubbing an HTML string after the fact. That's the case for
data-oriented rendering, in one tidy function.

One last detail in the same spirit: the **`:new` event allocates
document ids deterministically.** Instead of `(rand-int)`,
`allocate-next-doc-id` scans the `doc-N` ids already in app-db and takes
max + 1. The new id is written into durable app-db, so it has to be a
pure function of prior state. A random id would replay differently and
break determinism. This is the same principle that makes the whole loop
testable: keep durable writes a function of recorded inputs, never of
the world outside.

## Why this shape

A design-led example exists to prove that polished visuals and
interaction hold up on its substrate. So it skips the platform features
other examples already cover. No state machines, no HTTP, no routing
here — the dataflow and the rendering get to be the star. This is the
Reagent member of a three-substrate design-led trio. Each is a
substantial, genuinely different UI on a different
[substrate](../../../docs/core/glossary.md#substrate):

| Substrate | Example | Shape |
|---|---|---|
| Reagent | `notebook` (this) | Three-pane editor |
| UIx | [`dashboard_uix`](../../substrates/uix/dashboard/) | Cards + sparklines |
| Helix | [`process_monitor_helix`](../../substrates/helix/process_monitor/) | Terminal log viewer |

All three wear the same "Editorial Warm" identity from
[`examples/_shared/css/style.css`](../../_shared/css/style.css). So what
looks different between them is the layout and the interaction, never
the brand. Use the trio to see how each substrate's idiom lands at real
scale. This one shows Reagent's `reg-view` + `@(subscribe …)` idiom
doing the whole job.

One note on the substrate choice: this is **stock Reagent**
(`reagent.dom.client` + `re-frame.adapter.reagent`), not reagent-slim.
That keeps the trio on the reference substrate for each adapter.

The mount is the ordinary re-frame2 boot, in two steps.
[`init!`](../../../docs/core/glossary.md#init) installs the Reagent
[adapter](../../../docs/core/glossary.md#adapter). Then the tree renders
inside a [`frame-provider`](../../../docs/core/glossary.md#frame-provider)
given `{:id app-frame :initial-events [[:notebook/initialise]]}`: the
`:id` stands the app [frame](../../../docs/core/glossary.md#frame) up —
creating it on the first mount, reusing it untouched on a hot reload — and
`:initial-events` fires once on creation to seed the
[app-db](../../../docs/core/glossary.md#app-db) before the first paint.
With the tree inside the provider, every `dispatch`/`subscribe` resolves
to that frame; render with no provider and those calls raise
`:rf.error/no-frame-context` —
[identity is carried, not found](../../../docs/core/glossary.md#frame-identity-is-carried-not-found),
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
