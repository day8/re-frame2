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

Reverse those spellings and Freehand treats it as an authoring error. The public
var is a **descriptor**, not `IFn` — `(greeting {:name "Ada"})` fails loudly.

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

Live scalars (text, checked, key) use **projection markers**. Freehand fills them
in when the event fires:

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

Freehand has no `local`, no neutral hooks in ordinary views, and no public
“who am I in the tree?” handle like `v/self` for writing state.

If state matters to the product, it lives in re-frame: app-db (or another
frame-scoped store), events, and subscriptions.

A few practical defaults:

- **Reusable leaves** should usually be **props-only**: data in, Hiccup out.  
- **Controlled fields** keep draft and validity in re-frame and use Freehand’s
  controlled-input door so typing stays correct.  
- **Hard multi-step controls** (commit-on-blur, cancel, reject) can use a library
  **semantic controller** with an explicit `:control` address — optional packaging,
  not something every field needs.

Keys and mount position answer “which React instance is this?” They are **not**
automatically “where does my draft live in app-db?”

Host machinery — DOM nodes, third-party widgets, layout measurement, React-only
protocols — lives behind explicit host boundaries.

## Day-one checklist

You can write ordinary Freehand screens when you can:

- declare with `v/defview` and call with `[view props]`  
- use `(v/sub …)` only inside a render  
- put event vectors on `:on-*`  
- keep product state in re-frame (no view-local ratom model)  
- leave compilation for later (interpreted is complete)

## If something feels wrong

| Symptom | Recovery |
|---|---|
| “I called my view as a function” | `[view props]` — public vars are not `IFn` |
| `v/sub` outside a view | `rf/subscribe-once` for probes; `v/sub` is render-only |
| Closure on every button | prefer an event vector; escape forms only when needed |
| Draft lives in a ratom / hook | move to re-frame (or a library controller later) |
| “I need Form-2 for local UI” | open/closed facts in app-db, or props-only controlled |

---

## Frames, roots, and the DOM

Every Freehand view runs under a **frame** — the re-frame world for its
subscriptions and events. A frame is not a DOM attribute. You **mount Freehand**
into a container (or embed it in a React tree); Freehand binds the frame to that
tree.

### Putting Freehand on the page

```clojure
(v/mount [app-root {}]
         (js/document.getElementById "app"))
```

**Root** = one React unit in one DOM container.  
**Frame** = the re-frame world that root’s views use.

**Preflight** ensures the frame exists and can seed before first paint. Inside the
tree you rarely thread the frame through props — `sub` and handlers already bind
to the **committed** frame.

### More than one container

Two Freehand mounts can share one frame, or use different frames. When root
identity could collide, supply an explicit `:root-id` (and frame options as the
implementation defines them).

### A Freehand island inside someone else’s React tree

Some foreign React libraries want a **component value** as a prop. Freehand’s
answer is `v/->react`:

```clojure
{:cellRenderer (v/->react person-cell)}
```

The bridge never **creates** a frame. It binds to an existing live frame via a
reserved `frame` prop or ambient context. Missing or dead frames fail loudly.

### A different frame for part of the tree

A Freehand **subtree** can retarget to another already-live frame (tool panel vs
app). Props can look equal; retarget still rebinds. Ordinary shells always observe
frame context; compiled shells may elide that only when the manifest proves it
safe.

| Law | Meaning |
|---|---|
| Input | an already-live frame |
| Effect | descendants’ `sub` and events rebind |
| **Retarget beats memo** | rebind even when props are `rf=`-equal |
| Missing / dead frame | loud failure |

Public form is not frozen yet — do not invent `v/frame-provider`. Think “retarget
this Freehand region,” not `data-frame` on a div. Multi-root mounts are separate:
explicit `:root-id` when identity can collide.

## What you give up on purpose

| You do not get | Why |
|---|---|
| Ratoms, cursors, reactions | second reactive model |
| Form-2 / Form-3 / positional view args | one declaration, one props map |
| `local` / neutral refs / effects in ordinary views | app state in re-frame; host work explicit |
| Automatic promotion to compiled | mode choice stays honest |
| Bare React components as ordinary heads | foreign UI must be a named boundary |

A small paved path is what tests, tools, compilation, and AI authoring rely on.
