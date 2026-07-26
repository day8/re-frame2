# Mental model

You are about to write Freehand views and do not want a second mental model next
to re-frame. Hold **four ideas**. If they stick, the rest of the guide is mostly
detail.

Freehand is re-frame2’s first-party **view layer** — same *job* as Reagent/UIx,
re-frame-native in *contract*. Upstream events, app-db, and subscriptions do not
change; only the last mile does. Hiccup is still Hiccup.

> **Declare a boundary. Read with `sub`. Intent as data. State in re-frame.**

## 1. One way to declare a view — and an optional compiled mode

There is exactly one way to write a Freehand view:

```clojure
(v/defview greeting [{:keys [name]}]
  [:p "Hello, " name])
```

`v/defview` does not merely define a function. It declares a **mounted boundary**:
something Freehand can track for subscriptions, identity, hot reload, errors,
profiling, and (later) compilation.

You **call** that boundary with a vector:

```clojure
[greeting {:name "Ada"}]
```

Helpers are ordinary functions — call them with parentheses. If a helper reads a
subscription, that read belongs to the **enclosing** boundary (the nearest
vector-called view), not to the helper:

```clojure
(defn price-line [{:keys [label amount]}]
  [:div.price-line [:span label] [:span (money amount)]])

(v/defview invoice [{:keys [id]}]
  [:section
   (price-line {:label "Subtotal"
                :amount (v/sub [:invoice/subtotal id])})
   [tax-summary {:invoice-id id}]])
```

Here is the rule in one table:

| What you wrote | How you call it | What it means |
|---|---|---|
| `v/defview` | `[view props]` | a real boundary (identity, invalidation, keys, …) |
| plain `defn` helper | `(helper …)` | inline structure — never a vector head |

Reverse those spellings and Freehand treats it as an authoring error.
`(greeting {:name "Ada"})` raises `:rf.error/view-called-directly` and names the
three legal recoveries: mount it, inline it as a plain `defn`, or extract a shared
helper. The var holds a **descriptor**, and it implements the host call protocol
for precisely that reason — so calling one can explain itself rather than
returning `nil`. `(ifn? greeting)` is therefore `true`, which is why `v/view?` is
the question to ask when you mean "is this a view?".

**Compilation is an option on the same declaration**, not a second macro and not a
second namespace:

```clojure
(v/defview todo-row
  {:compiled true}
  [{:keys [id]}]
  …)
```

Day to day you stay in **interpreted** mode: full Clojure, flexible helpers. You
reach for **compiled** mode when a boundary is hot or you need static proof.
Call sites and tests stay the same.

## 2. Subscriptions read as values

In Reagent you typically write something like `@(rf/subscribe [:count])`. You get
a reaction-like object and you deref it.

In Freehand you write:

```clojure
[:span "Count: " (v/sub [:count])]
```

`(v/sub [:count])` **is the value** — nothing to deref. Freehand records that this
boundary read that query; when the value moves, that boundary re-renders.

Conditional reads are fine: untaken branches do not contribute dependencies. Avoid
an unbounded number of reactive sites in one parent loop — extract a keyed child
that subscribes for one row. Compiled mode requires that factoring; it is good
style even when you stay interpreted.

**`v/sub` is only legal during a declared view render.** In a REPL, event handler,
timer, or tool, use the one-shot read:

```clojure
(rf/subscribe-once [:basket/total] {:frame :app/main})
```

That returns a value without installing a view dependency. Reactive read and
one-shot probe stay separate on purpose. Errors name the case (no active render,
wrong thread, missing frame) so recovery is obvious.

## 3. Handlers are data

The everyday event handler is not a function. It is an event vector:

```clojure
[:button {:on-click [:count/inc]} "+"]
```

On click, Freehand dispatches `[:count/inc]` on the view’s frame. The vector is
the **intent** — readable in source, visible to tools before it fires, assertable
on the JVM without a DOM.

Live scalars — the text of an input, whether a box is checked, which key was
pressed — use **projection markers**. Freehand fills them in when the event fires:

```clojure
[:input {:value email
         :on-input [:form/edit :email ::v/value]}]
```

Need the raw DOM event or another payload? Use `v/event` (returns one vector or
`nil`). Need imperative foreign work with no app intent? Use `v/handler`.

**One user action → one semantic event, or nothing.** No vector-of-vectors in the
view. Several effects from one click means one domain event whose handler returns
them.

## 4. One reactive state system — re-frame only

Freehand has no view-local reactive cell, no neutral hooks in ordinary views, and
no public “who am I in the tree?” handle for writing state.

If state matters to the product, it lives in re-frame: app-db (or another
frame-scoped store), events, and subscriptions.

A few practical defaults:

- **Reusable views** should usually be **props-only**: data in, intent out.  
- **Controlled fields** keep draft and validity in re-frame and ride Freehand’s
  controlled-input door so typing stays correct.  
- **Hard multi-step controls** (commit-on-blur, cancel, reject) can use a library
  **semantic controller** with an explicit `:control` address — optional packaging,
  not something every field needs.

Keys and mount position answer “which React instance is this?” They are **not**
automatically “where does my draft live in app-db?”

Host machinery — DOM nodes, third-party widgets, layout measurement, React-only
protocols — lives behind explicit host boundaries.

## Recap

You can write ordinary Freehand screens when you can:

- declare with `v/defview` and call with `[view props]`  
- use `(v/sub …)` only inside a render  
- put event vectors on `:on-*`  
- keep product state in re-frame (no view-local ratom model)  
- leave compilation for later (interpreted is complete)

## Troubleshooting

| Symptom | Recovery |
|---|---|
| `:rf.error/view-called-directly` | `[view props]` — a declared view is mounted, never called |
| `v/sub` outside a view | `rf/subscribe-once` for probes; `v/sub` is render-only |
| Closure on every button | prefer an event vector; escape forms only when needed |
| Draft lives in a ratom / hook | move to re-frame (or a library controller later) |
| “I need Form-2 for a bit of local UI” | open/closed facts in app-db, or a prop the parent owns |

---

## Frames, roots, and the DOM

Every Freehand view runs under a **frame** — the re-frame world for its
subscriptions and events. A frame is not a DOM attribute. You **mount Freehand**
into a container (or embed it in a React tree); Freehand binds the frame to that
tree.

### Putting Freehand on the page

```clojure
(v/mount [app-root {}]
         (js/document.getElementById "app")
         {:frame {:id :my.app/main :initial-events [[:app/init]]}})
```

**Root** = one React unit in one DOM container.  
**Frame** = the re-frame world that root’s views use.

The `:frame` plan runs to completion **before** React sees anything, so a body
that reads a subscription on its first render finds a frame that is already
seeded. Inside the tree you never thread the frame through props — `v/sub` and
event sites already bind to the **committed** frame.

### More than one container

Two Freehand mounts can share one frame or use different ones. A root's id is
derived from the view it mounts, so mounting the same view twice needs a
`:disambiguator` or an explicit `:root-id` at the second site — see
[Install](install.md#two-roots-on-one-page).

### A Freehand island inside someone else’s React tree

Some foreign React libraries want a **component value** as a prop. Freehand’s
answer is `v/->react`:

```clojure
{:cellRenderer (v/->react person-cell)}
```

The bridge **selects** a frame; it never creates one. A `frame` prop — a frame-id
keyword or a live frame value — scopes an already-live frame, and with no `frame`
prop the exported view resolves ambiently exactly as a view mounted anywhere else
does. *Presence* of the prop decides, not truthiness, so `frame={null}` is a
stated target that fails loudly rather than falling through.

### There is no frame provider

A Freehand subtree cannot retarget itself onto a different frame. There is no
`v/frame-provider`, no `v/frame` and no `data-frame` attribute, and the omission
is deliberate rather than pending: a frame is chosen at a **root**, or at the
`v/->react` bridge where foreign React hands one in.

Two worlds on one page are therefore two roots. Mount the tool panel into its own
container against its own frame, and mount the application into its own — which is
also what makes them independently testable and independently tearable-down.

## What you give up on purpose

| You do not get | Why |
|---|---|
| Ratoms, cursors, reactions | second reactive model |
| Form-2 / Form-3 / positional view args | one declaration, one props map |
| View-local cells, neutral refs, effects in ordinary views | app state in re-frame; host work explicit |
| Automatic promotion to compiled | mode choice stays honest |
| Bare React components as ordinary heads | foreign UI must be a named boundary |

A small paved path is what tests, tools, compilation, and AI authoring rely on.
