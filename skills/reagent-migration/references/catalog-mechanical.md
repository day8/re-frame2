# M-tier — "do this" (mechanical rewrites)

> Unambiguous, safe, observably-identical view-tier rewrites. Apply them
> directly — but always **gate the whole view first** ([`gotchas.md`](gotchas.md)):
> if the view also trips a D/R rule, leave the entire view on Reagent. A
> mechanical rewrite is only "safe" inside a view that fully converts.
>
> Every rule cites a `MIG-NN` id so the author can audit the change.
> Examples are abstract — use the *shape*, not these literal names, on a
> consumer's code. All of them assume the **interpreted** tier, which is
> Freehand's default; `{:compiled true}` is a separate later decision.

## MIG-01 — Form-1 / `reg-view` → `v/defview`, positional params → prop map

The view header changes, and **every call site changes atomically in the same edit** (positional args become a map keyed by the param names).

```clojure
;; before
(defn price [amt cur] [:span amt cur])
(defn app [] [:div [price 1 2]])

;; after
(v/defview price [{:keys [amt cur]}] [:span amt cur])
(v/defview app [_] [:div [price {:amt 1 :cur 2}]])
```

A `reg-view` registration unwraps the same way: `(reg-view greeter [n] …)` → `(v/defview greeter [{:keys [n]}] …)`.

Three parts of this are load-bearing:

- **Exactly one parameter, always the props map.** A view that reads no props still declares it — `[_]`, never `[]`. A declaration with zero or two-plus parameters raises `:rf.error/defview-bad-args` at macro-expansion.
- **A zero-prop call site emits the explicit empty map:** `[status-pill]` → `[status-pill {}]`.
- **A fn-call used as a component** (`(filter-link :all "All")` in child position) becomes a mounted site: `[filter-link {:showing :all :txt "All"}]` — brackets, because it is a boundary. Leave it in parens only if it genuinely is a body-extracting helper (see [`gotchas.md`](gotchas.md) §brackets vs parens).

A param named `key`, `[a & rest]`, or a multi-arity view is a judgment call (D — the shape isn't one map).

## MIG-02 — deref-drop: `@(subscribe …)` → `(v/sub …)`

```clojure
;; before
[:span @(subscribe [:total])]
;; after
[:span (v/sub [:total])]
```

`v/sub` returns the **value**. Dynamic query args pass through (`(v/sub [:item id])`), and a read inside a `for` body is legal in the interpreted tier — the render records the reads it actually made. A read from an ordinary `defn` helper called by the body is also legal: the render owns the read wherever the call lexically sits.

Two shapes are *not* this rule. A `subscribe` that is *stored* rather than deref'd is derived state (MIG-19). A read outside an active render — a timer, a `v/event` body, a foreign listener — fails loud with `:rf.error/view-read-outside-render`; those use `rf/subscribe-once`.

## MIG-04 / 05 / 06 — dispatch-lifting

**MIG-04 — a bare dispatch-only closure lifts to an event vector:**

```clojure
;; before → after
{:on-click #(dispatch [:go 1])}   =>   {:on-click [:go 1]}
```

**MIG-05 — a `%`-extraction closure lifts to a projection marker** (a closed roster — `::v/value`, `::v/checked`, `::v/key`, `::v/scroll-top`, `::v/new-state`):

```clojure
;; before → after
{:on-input #(dispatch [:typed (-> % .-target .-value)])}
=> {:on-input [:typed ::v/value]}

;; leading LITERAL args sit ahead of the marker — markers are positional,
;; filled from the live payload at firing time:
{:on-input #(dispatch [:edit-field :title (-> % .-target .-value)])}
=> {:on-input [:edit-field :title ::v/value]}
```

A marker only projects in a **top-level** argument position; nested inside another value it is ordinary application data.

**MIG-06 — `preventDefault`/`stopPropagation` become the listener options map:**

```clojure
;; before → after
{:on-submit (fn [e] (.preventDefault e) (dispatch [:save]))}
=> {:on-submit {:event [:save] :prevent-default true}}
```

The options roster is **closed** — `:event` (required), `:prevent-default`, `:stop-propagation`, `:capture`, `:passive`, `:once` — and an unknown key is rejected rather than silently ignored.

A handler that does anything beyond these three exact shapes → MIG-18 (D). A fn-valued prop on an *internal view* (MIG-27) or a *foreign* component (MIG-10) is a different boundary entirely.

## MIG-07 — key-meta → `:key` prop

```clojure
;; before → after
^{:key (:id t)} [item t]   =>   [item {:key (:id t) :t t}]
```

`:key` is React's list-identity slot, not an attribute: it rides MIG-01's atomic call-site pass so the props map is built once, and it is refused inside a `v/spread` (it must be literal at the element that carries it).

## MIG-11 — DOM prop respelling (camelCase / alias → kebab)

Freehand's props are **kebab-case**, one spelling per name:

```clojure
;; before → after
{:className c :htmlFor x :onClick …}   =>   {:class c :for x :on-click …}
```

`:class-name` and `:html-for` are refused with `:class` / `:for` named as the replacement; `:children` is reserved (children are positional). `data-*` and `aria-*` pass through verbatim, as do names React does not recognise. `:onClick` both respells *and* lifts its dispatch (MIG-04). `:dangerouslySetInnerHTML` is refused under every spelling and does not respell to a prop at all — it becomes the trusted-markup verb in the element's CHILD position → MIG-34.

## MIG-12 — strip the `doall` laziness workaround

```clojure
;; before → after
[:ul (doall (for [t ts] ^{:key t} [:li t]))]  =>  [:ul (for [t ts] [:li {:key t} t])]
```

Reagent's `doall` is dead weight here. Note the key moves with it (MIG-07).

## MIG-14 — plain hiccup passes through unchanged

Tags, `.class`/`#id` sugar, fragments `[:<> …]`, `:style` maps, `:class` vectors, control forms and expression children pass through **structurally** — that is the point of keeping hiccup:

```clojure
;; unchanged apart from the header
[:div.wrap#main [:span "hi"] [:p 42]]
```

The in-map entry rules (MIG-04/05/06/07/11) still rewrite entries *inside* a literal props map. A **non-literal props-map expression** (`merge`/`assoc`/a bound symbol) in the props position is not pass-through → MIG-28 (D). A bare symbol in **child** position is content and is left alone — the bare-symbol trap in [`gotchas.md`](gotchas.md).

## MIG-15 — mount and boot

```clojure
;; before
(defn init! []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:app/init])
  (rdom/render [app] el))

;; after
(defn init! []
  (v/mount [app {}] el {:frame {:id :app :initial-events [[:app/init]]}}))
```

Three things happen at once, and all three are simplifications:

- **`v/mount` takes the root form, the container, and a closed opts map.** Its `:frame` opt is the preflight: a `make-frame` opts map carrying `:id` **ENSUREs** a frame the root owns for its lifetime and drains `:initial-events` **before React sees anything**, so a body that reads a subscription on first render finds seeded state. A bare frame-id keyword instead **SCOPES** a frame something else already owns.
- **There is no adapter install.** Freehand needs no `rf/init!` call of its own. On a mixed page keep the existing `(rf/init! reagent-adapter/adapter)` for the roots still on Reagent, and delete it only when the last of them converts — **confirm the page's root inventory with the author**, since the skill cannot see it.
- **The React-18 `create-root` / `defonce`-atom dance deletes.** `v/mount` is idempotent per root: re-mounting the same root-id into the same container re-renders the existing host root, which is the hot-reload path.

Root identity derives from the mounted view's registered id, so a single-root page authors nothing. Two roots for one view need `:disambiguator` or an explicit `:root-id`. `reagent.dom.server` / `hydrate-root` are the SSR family → MIG-23 (D).

## MIG-24 — ns requires (runs LAST)

Add the Freehand require; drop `reagent.*` requires **only when the namespace has zero remaining uses** (a held D/R view keeps them alive):

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand :as v]        ; add — `v` is the conventional alias
          ;; [reagent.core :as r]          ; drop only if nothing else needs it
          )
```

Alias Freehand as `v` and write `v/defview` / `v/sub` qualified. The projection markers are `::v/value` / `::v/checked` / `::v/key` / `::v/scroll-top` / `::v/new-state`, which resolve through that alias — so the alias is load-bearing, not cosmetic.

## MIG-32 — framework `route-link` → `v/route-link`

`v/route-link` is an ordinary `v/defview` shipped in Freehand — the counterpart of the stock-Reagent `route-link`. The head renames:

```clojure
;; before → after
[rf/route-link {:to :home} "Home"]   =>   [v/route-link {:to :home} "Home"]
```

`:to` (a registered route id) is required; `:params` / `:query` / `:fragment` feed both the href and the dispatch payload; **every other key** — `:class`, `:target`, `:download`, `:aria-label`, `:on-click`, any further HTML attribute — passes through to the underlying `<a>`. A plain `[:a {:href …}]` is not equivalent, which is why the head-rename is the migration.

Two things to carry: rendering it **without `day8/re-frame2-routing` on the classpath fails loud** with `:rf.error/routing-artefact-missing` — confirm the routing artefact is present. And a caller `:on-click` runs *first* and may veto the interception, exactly as in Reagent; modifier and middle clicks defer to the browser, which is the whole reason to use this view rather than hand-rolling an anchor.

Framework-shipped Reagent view heads other than `route-link` have no ruled Freehand counterpart → judgment (hold).
