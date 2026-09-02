# M-tier — "do this" (mechanical rewrites)

> Unambiguous, safe, observably-identical view-tier rewrites. Apply them
> directly — but always **gate the whole view first**
> ([`gotchas.md`](gotchas.md)): if the view also trips a D/R rule, leave the
> entire view on Reagent. A mechanical rewrite is only "safe" inside a view that
> fully converts.
>
> Every rule cites a `MIG-NN` id so the author can audit the change. The id
> names the **Reagent construct you found**, not the destination shape.
> Examples are abstract — use the *shape*, not these literal names.

## MIG-01 — Form-1 view → `h/defview`, positional params → one prop map

The view header changes, and **every call site changes atomically in the same
edit** (positional args become a map keyed by the param names).

```clojure
;; before
(defn price [amt cur] [:span amt cur])
(defn app [] [:div [price 1 2]])

;; after
(h/defview price [{:keys [amt cur]}] [:span amt cur])
(h/defview app [_] [:div [price {:amt 1 :cur 2}]])
```

Three parts are load-bearing:

- **The argument vector is the ordinary one-props-map vector**, so destructuring
  reads as it does in any Clojure fn. A view that reads no props declares `[_]`,
  and its call sites pass `{}`.
- **A fn-call used as a component** (`(filter-link :all "All")` in child
  position) becomes a mounted site: `[filter-link {:showing :all :txt "All"}]` —
  brackets, because it is a boundary. Leave it in parens only if it genuinely is
  a body-extracting helper ([`gotchas.md`](gotchas.md) §brackets vs parens).
- **The expansion is anonymous**, so a helper named after the view does not get
  shadowed: `(defn row-body [p] …)` beside `(h/defview row [p] (row-body p))` is
  the supported spelling.

A param named `key`, `[a & rest]`, or a multi-arity view is a judgment call (D —
the shape isn't one map).

## MIG-02 — deref-drop: `@(subscribe …)` → `(h/sub …)`

```clojure
;; before → after
[:span @(subscribe [:total])]   =>   [:span (h/sub [:total])]
```

`h/sub` returns the **value**, and it is the **ambient collector**: a read is
legal inside a `when`, a `for`, or an ordinary `defn` helper the body calls, and
the edge is recorded *where the read happens* — so a branch not taken
contributes no edge. Dynamic query args pass through: `(h/sub [:item id])`.

Two shapes are *not* this rule. A `subscribe` that is *stored* rather than
deref'd is derived state (MIG-19). A read that runs **after** the render — in a
callback, a timer, a foreign listener — is MIG-26: hoist it to render time and
close over the value.

There is no grouped-read form. A view reading several subscriptions is several
`h/sub` calls, one per read site — that is the whole translation.

## MIG-04 / 05 — dispatch-lifting

**MIG-04 — a bare dispatch-only closure becomes the event vector itself:**

```clojure
;; before → after
{:on-click #(dispatch [:go 1])}   =>   {:on-click [:go 1]}
```

**MIG-05 — a `%`-extraction closure becomes a marker.** The roster is **two
entries** — `::h/value` and `::h/checked` — and nothing else reads the event
target:

```clojure
;; before → after
{:on-input #(dispatch [:typed (-> % .-target .-value)])}
=> {:on-input [:typed ::h/value]}

;; leading LITERAL args sit ahead of the marker:
{:on-input #(dispatch [:edit-field :title (-> % .-target .-value)])}
=> {:on-input [:edit-field :title ::h/value]}
```

**A marker substitutes only at the TOP LEVEL of the intent vector.** The
substitution is one pass over the vector's own elements; a `::h/value` nested
inside a map or an inner vector arrives at the handler as the literal keyword
`:re-frame.hicasso/value`, silently. Restructure the event's payload rather than
nesting the marker.

Anything a closure did beyond these two shapes → MIG-18 (D).

## MIG-06 — `preventDefault` → the `::h/prevent` head

```clojure
;; before → after
{:on-click (fn [e] (.preventDefault e) (dispatch [:filter/show-done]))}
=> {:on-click [::h/prevent [:filter/show-done]]}
```

`::h/prevent` is a **reserved head wrapping exactly one intent vector**. The
grammar is closed and checked once per render: exactly two elements, the second
a non-empty vector that is not itself a reserved head. Anything else raises
`:rf.error/hicasso-malformed-prevent`.

Two facts decide most sites:

- **`:on-submit` prevents by default** and needs no head — that is the one
  special position, and it applies to the data spellings (vector, key map) only.
  A plain fn or an `h/event` at `:on-submit` is *never* auto-prevented; whoever
  holds the event owns it.
- **A key map at `:on-submit` auto-prevents on every branch**, because the
  position is passed down to each branch. That is rarely what a Reagent
  keystroke handler meant — check it.

**There is no listener-options map.** `{:event […] :prevent-default true}` does
not exist, and neither does `:capture`, `:passive`, `:once` or
`:stop-propagation` — these are unrepresentable, not undocumented. So:

- **`stopPropagation`, and any other imperative event work, goes in `h/event`** (or
  a plain fn). The callback holds the event and owns it.
- **Capture phase is React's own prop spelling** — `:on-click-capture` /
  `:onClickCapture` is an ordinary event position and an intent vector lowers
  there normally.
- **`passive` and `once` have no Hicasso surface at all.** A handler that
  genuinely needs either is a callback ref plus `addEventListener` — which makes
  the view a MIG-17 decision, not a mechanical lift.

## MIG-07 — key-meta → `:key` prop (MANDATORY; the failure is silent)

```clojure
;; before → after
^{:key (:id t)} [item t]   =>   [item {:key (:id t) :t t}]
```

**Hicasso never reads Clojure metadata.** There is no `(meta …)` read in the
codec at all, so a surviving `^{:key …}` is not a spelling variant — it is a key
that is simply absent, and React reconciles the list by position instead. In a
reorderable or filterable list that is silent state corruption: the wrong row's
input keeps the wrong row's text.

It rides MIG-01's atomic call-site pass so the props map is built once. Two
signals help, and neither is complete cover:

- React's own key warning fires for a missing key; Hicasso adds nothing to it.
- `:rf.warning/hicasso-entity-key` fires when the key is neither a
  string/number/keyword nor a uuid/symbol — an entity map used as a key, which
  was never stable.

`:key` is read as the **exact literal keyword** `:key`. `"key"` and `:x/key` are
*not* the key; they land in the emitted props as ordinary attributes.

The codemod's **W1** rewrite does this at `[:> …]` crossings automatically; the
rest is yours.

## MIG-11 — the prop dialect (mostly: leave it alone)

**Start by not rewriting.** Hicasso resolves every prop key through one
canonical-slot rule, and it accepts the spellings a Reagent codebase already
has:

| you wrote | emitted React slot |
|---|---|
| `:on-click` **or** `:onClick` | `onClick` |
| `:class` / `:className` / `:class-name` / `:x/class` | `className` |
| `:for` / `:html-for` / `:htmlFor` | `htmlFor` |
| `:charset` | `charSet` |
| `:aria-label`, `:data-index` | verbatim (the `aria`/`data` first segment is exempt) |
| `:--brand-gap` | verbatim (CSS custom properties survive) |
| `:margin-top` inside a `:style` map | `marginTop` |

So `:className` and `:onClick` are **accepted unchanged**, and there is no
refusal and no warning for any spelling. Kebab is the house style; a bulk
respelling is cosmetic and you should not spend the author's diff on it.

What you *must* look for is the four places the dialect is not neutral:

- **A STRING key is verbatim** (apart from the three renames above).
  `{"on-input" f}` emits the slot `on-input`, which React ignores — the handler
  is silently dead. This is a deliberate escape hatch for custom elements, and a
  trap for anyone who reached for a string key by habit.
- **A SYMBOL key camelCases but is not an event position.** `{'on-click [:go]}`
  emits `onClick` and the intent vector crosses as an inert JavaScript array —
  no dispatch, no error.
- **Two spellings on one slot.** `:class` and `:className` together *compose*;
  everywhere else the last write wins, and which one that is depends on map
  iteration order. Pick one spelling per element.
- **`:dangerouslySetInnerHTML` passes straight through** — see MIG-34 below,
  which is a behaviour *change* dressed as a no-op.

`:class` accepts a string, keyword, symbol or **any nested collection**
(recursively flattened, nils dropped, space-joined), and composes with the tag's
`.foo` shorthand. It does **not** filter a map by truthiness:
`{:class {:active true}}` renders `"active true"`. A Reagent codebase using a
map for conditional classes needs that rewritten to a vector with `when`.

## MIG-12 — strip the `doall` laziness workaround

```clojure
;; before → after
[:ul (doall (for [t ts] ^{:key t} [:li t]))]  =>  [:ul (for [t ts] [:li {:key t} t])]
```

Reagent's `doall` is dead weight here. Note the key moves with it (MIG-07).

## MIG-14 — plain hiccup passes through unchanged

Tags, `.class`/`#id` sugar, fragments, `:style` maps, `:class` collections,
control forms and expression children pass through **structurally** — that is
the point of keeping hiccup:

```clojure
;; unchanged apart from the header
[:div.wrap#main [:span "hi"] [:p 42]]
```

The in-map entry rules (MIG-04/05/06/07/11) still rewrite entries *inside* a
literal props map. A **non-literal props-map expression** (`merge`/`assoc`/a
bound symbol) in the props position is not pass-through → MIG-28 (D). A bare
symbol in **child** position is content and is left alone — the bare-symbol trap
in [`gotchas.md`](gotchas.md).

An explicit `:children` key is *not* reserved at a native tag, but at a
**boundary** the codec writes trailing forms into `:children` and they overwrite
an author-written one. Pass children positionally.

## MIG-15 — root mounting and boot

```clojure
;; before
(defn init! []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:app/init])
  (rdom/render [app] el))

;; after
(defonce ^:private !root (atom nil))

(defn ^:export init! []
  (rf/init! reagent-adapter/adapter)                              ; still needed — see below
  (reset! !root (h/mount! el {:frame ::frame :initial-events [[:app/init]]} [app {}])))

(defn ^:dev/after-load reload! []
  (h/render! @!root [app {}]))
```

Four things matter, and the first two are the ones a migration gets wrong:

- **`h/mount!` takes a config map and ensures its frame.** Its arity is
  `(container config hiccup)`, and the config carries `:frame` (the frame
  keyword), optional `:initial-events` (ordinary events, run in order before the
  first paint when this mount CREATES the frame — never when it joins one), and
  optional `:identifier-prefix` (React's own, for a page with two roots). So the
  Reagent pair `(rdom/render …)` + `(rf/dispatch-sync [:boot])` collapses into
  one `h/mount!`. An explicit `rf/make-frame` beforehand still works, and is what
  a shared frame — or one needing `:images` / `:fx-overrides` — wants.
- **`(rf/init! …)` stays** — `make-frame` raises
  `:rf.error/no-adapter-installed` until a reactive adapter is installed, and
  nothing installs one for you: there is no default-adapter registry, so the
  install is the app's own explicit line whatever the views are written in.
  Keep the app's existing adapter install; it is not Reagent-specific
  scaffolding to be deleted. A Reagent adapter under a Hicasso tree keeps
  working exactly as it did, and is what a part-migrated page wants — every
  React-shaped adapter writes the same frame context, so the Reagent subtree
  and the Hicasso one resolve to the *same* frame. Hicasso does ship an
  adapter of its own (`re-frame.hicasso.substrate`), but it is an optional
  module nothing under Hicasso's own source requires, so `h/mount!` neither
  installs it nor displaces what the app has. *"Stays" is about a migration in
  progress. If the app ends with no Reagent view at all, the choice reopens and
  the author gets told — MIG-24 §When no Reagent view remains.*
- **`h/render!` is the hot-reload door.** It re-renders the root React already
  has, so the reloaded view code meets its own DOM. Calling `h/mount!` again
  would `createRoot` a second time and replace the tree, discarding every node
  and scrap of component state.
- **`h/unmount!` is `mount!`'s inverse** and is idempotent. It leaves sibling
  roots, their frames and the container alone.

`reagent.dom.server` / `hydrate-root` are the SSR family → MIG-23 (D): the
Hicasso pipeline ships — `server/render`, then `ssr/hydrate!`, then
`h/hydrate!` — so the open question is whether to run a Node renderer, not
whether a door exists.

## MIG-24 — ns requires (runs LAST)

Add the Hicasso require; drop `reagent.*` requires **only when the namespace has
zero remaining uses** (a held D/R view keeps them alive):

```clojure
(:require [re-frame.core :as rf]
          [re-frame.hicasso :as h]           ; add — `h` is the conventional alias
          ;; [reagent.core :as r]            ; drop only if nothing else needs it
          )
```

**The `h` alias is load-bearing, not cosmetic.** `::h/value`, `::h/checked` and
`::h/prevent` are auto-resolved keywords that read `:re-frame.hicasso/…` through
that alias. Alias it anything else and the markers you write are different
keywords that nothing substitutes.

The optional modules are separate requires and are **absent when unused** —
that is the point of them, so do not add one speculatively:

```clojure
[re-frame.hicasso.forms   :as forms]     ; buffered-field + the draft concern
[re-frame.hicasso.motion  :as motion]    ; presence
[re-frame.hicasso.overlay :as overlay]   ; popover, modal
[re-frame.hicasso.native  :as n]         ; the two Hicasso hooks for React islands
```

### When no Reagent view remains, the adapter question opens

Everything above is about a namespace. There is one require the whole-app case
reaches that a per-namespace sweep never does, and the skill would otherwise
leave the author holding a dependency for nothing.

Once **every** view in the app is converted, `re-frame.adapter.reagent` is the
last thing pulling Reagent in — and its only job was ever the substrate half of
Spec 006: the container `app-db` lives in, plus a derived value that says when
it moved. It is `day8/re-frame2-reagent` that declares the stock
`reagent/reagent` dependency; core declares none. Hicasso ships an adapter of
its own, so that job has a second answer:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.hicasso :as h]
          [re-frame.hicasso.substrate :as substrate])

(rf/init! substrate/adapter)          ;; :kind :rf.adapter/hicasso
```

`re-frame.hicasso.substrate` ships inside `day8/re-frame2-hicasso`, so that line
costs no coordinate — and it is what lets the two `deps.edn` entries
`day8/re-frame2-reagent` and `reagent/reagent` go, rather than standing for a
substrate the app no longer writes a line of.

**This is the author's call, not the skill's** (cardinal rule 5). Name the
option and the two dependencies it would retire; then let them decide. Keeping
the Reagent adapter is a complete, supported configuration whatever the views
are written in — nothing degrades under it, and an app that may grow a Reagent
or UIx subtree later has a standing reason to keep it. And the question does not
arise at all while a single Reagent view survives: until then MIG-15's *the
install stays* is the whole of the answer.

## MIG-33 — keystroke handlers → a key map

A Reagent handler that branches on `.-key` is the one case where the mechanical
rewrite also **fixes a bug**:

```clojure
;; before
{:on-key-down (fn [e] (case (.-key e)
                        "Enter"  (dispatch [:commit id])
                        "Escape" (dispatch [:cancel id])
                        nil))}
;; after
{:on-key-down {"Enter"  [:commit id]
               "Escape" [:cancel id]}}
```

Keys are the browser's own `.key` strings. Branch values may be an intent vector
(markers and `::h/prevent` included) or a function; an unlisted key does
nothing. The whole map is **composition-gated**: a keystroke arriving
mid-IME-composition commits nothing, which is exactly what the hand-written
version got wrong for every user who composes.

Two things to carry: a branch value that is neither a vector nor a function is
**silently dropped**, and a key map at `:on-submit` prevents on every branch
(MIG-06).

## MIG-34 — `dangerouslySetInnerHTML` converts, and that is the problem

**Hicasso has no trusted-markup verb, and it does not need one: the prop passes
straight through to React.** So the view converts with no edit at all — and that
is precisely why it needs flagging rather than skipping.

Reagent **deleted** this prop unless it was wrapped; Hicasso **passes it
through**. A site that was inert under Reagent becomes live under Hicasso —
markup that has not been rendered for however long this code has existed starts
being injected. The codemod reports every such site as `:dangerous-html`, a
runtime blocker, for exactly this reason.

So: do not rewrite it, **do** surface it to the author, with the question that
matters — *is this string still trusted?* Whatever cleared the markup before has
to be shown to still be clearing it, because for these sites it may never have
been exercised.

One further trap: only the camel spelling reaches React.
`:dangerously-set-inner-html` emits `dangerouslySetInnerHtml` (lowercase `tml`)
and `:inner-html` emits `innerHtml`; React ignores both. A Reagent codebase
using the kebab spelling had a dead prop under both runtimes.
