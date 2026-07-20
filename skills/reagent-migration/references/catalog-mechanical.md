# M-tier — "do this" (mechanical rewrites)

> Unambiguous, safe, observably-identical view-tier rewrites. Apply them
> directly — but always **gate the whole view first** ([`gotchas.md`](gotchas.md)):
> if the view also trips a D/R rule, leave the entire view on Reagent. A
> mechanical rewrite is only "safe" inside a view that fully converts.
>
> Each rule cites a `MIG-NN` id (the framework's own rule-table numbering).
> Examples are abstract — use the *shape*, not these literal names, on a
> consumer's code.

## MIG-01 — Form-1 / `reg-view` → `ui/defview`, positional params → prop map

The view header changes, and **every call site changes atomically in the same edit** (positional args become a map keyed by the param names).

```clojure
;; before
(defn price [amt cur] [:span amt cur])
(defn app [] [:div [price 1 2]])

;; after
(ui/defview price [{:keys [amt cur]}] [:span amt cur])
(ui/defview app [] [:div [price {:amt 1 :cur 2}]])
```

A `reg-view` registration unwraps the same way: `(reg-view greeter [n] …)` → `(ui/defview greeter [{:keys [n]}] …)`. A zero-param call site emits the explicit empty map: `[status-pill]` → `[status-pill {}]`. A *fn-call* used as a component (`(filter-link :all :All)` in child position) also becomes a view site: `[filter-link {:showing :all :txt :All}]`. Param named `key`, `[a & rest]`, or multi-arity → judgment (D — the shape isn't a plain map).

## MIG-02 — deref-drop: `@(subscribe …)` → `(sub …)`

```clojure
;; before
[:span @(subscribe [:total])]
;; after
[:span (sub [:total])]
```

Dynamic query args pass through (`(sub [:item id])`). A `subscribe` that is *stored* rather than deref'd is derived state (MIG-19), not this rule. A `sub` inside a `for` body is a compile error → keyed-child extraction (MIG-08, D).

## MIG-04 / 05 / 06 — dispatch-lifting

**MIG-04 — a bare dispatch-only closure lifts to an event vector:**

```clojure
;; before → after
{:on-click #(dispatch [:go 1])}   =>   {:on-click [:go 1]}
```

**MIG-05 — a `%`-extraction closure lifts to a placeholder vector** (closed vocabulary — `:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key`):

```clojure
;; before → after
{:on-input #(dispatch [:typed (-> % .-target .-value)])}
=> {:on-input [:typed :rf.ui/value]}
```

**MIG-06 — `preventDefault`/`stopPropagation` become an options map:**

```clojure
;; before → after
{:on-submit (fn [e] (.preventDefault e) (dispatch [:save]))}
=> {:on-submit {:event [:save] :prevent-default true}}
```

The bare-fn lift is **DOM/custom-element only**. A handler on an *internal view* call site (MIG-27) or a *foreign* component (MIG-10) is a different boundary → judgment. A handler that does anything beyond these three exact shapes → MIG-18 (D). In a `for` body, a lifted vector that *captures the loop binding* is a compile error → extract a keyed child (MIG-08).

## MIG-07 — key-meta → `:key` prop

```clojure
;; before → after
^{:key (:id t)} [item t]   =>   [item {:key (:id t) :t t}]
```

`:key` is React's reserved slot; it rides MIG-01's atomic call-site pass so the props map is built once.

## MIG-09 — foreign React heads become direct

```clojure
;; before → after
[:> Button {:label "x"}]                         =>  [Button {:label "x"}]
[(r/adapt-react-class Widget) {:p 1}]            =>  [Widget {:p 1}]
```

Hoist the component to the head; delete the `adapt-react-class` wrapper. A **fn-valued** prop on a foreign head is *not* mechanical → MIG-10 (D). The sibling interop heads `:f>` / `:r>` are also judgment calls, not tag pass-throughs.

## MIG-11 — DOM prop respelling (camelCase / alias → kebab)

Table-driven from React's published attribute/event names:

```clojure
;; before → after
{:className c :htmlFor x :tabIndex 1 :onClick …}
=> {:class c :for x :tab-index 1 :on-click …}
```

`:onClick` both respells *and* lifts its dispatch (MIG-04). Unknown/custom names pass through verbatim. `:dangerouslySetInnerHTML` never respells → MIG-34.

## MIG-12 — strip the `doall` laziness workaround

```clojure
;; before → after
[:ul (doall (for [t ts] ^{:key t} [:li t]))]  =>  [:ul (for [t ts] ^{:key t} [:li t])]
```

`for` is a native control form in the compiled grammar; Reagent's `doall` is dead weight. (A `doall` wrapping a markup-returning `map` is MIG-13, D.)

## MIG-14 — plain hiccup passes through unchanged

Tags, `.class`/`#id` sugar, fragments `[:<> …]`, `:style` maps, `:class` vectors, control forms and expression children pass through **structurally** — that is the point of keeping hiccup:

```clojure
;; unchanged apart from the header
[:div.wrap#main [:span "hi"] [:p 42]]
```

The in-map entry rules (MIG-04/05/06/07/11/34) still rewrite entries *inside* a literal props map. A handful of sub-cases are shipped compile errors with a mechanical fix — two `#id` segments → keep the first; a collection value on a non-`:class`/`:style` attr → `(str/join " " xs)`; a multi-form control body → wrap siblings in `[:<> …]`; directly-nested `for`s → collapse into one `for`. A **non-literal props-map expression** (`merge`/`assoc`/a bound symbol) is *not* pass-through → MIG-28 (D). A literal keyword in child position is ambiguous → D.

## MIG-15 — mount

```clojure
;; before
(defn init! [] (rdom/render [app] el))
;; after
(defn init! [] (ui/mount [ui/frame-root {:id <frame-id> :initial-events […]} [app {}]] el))
```

Once per root. The frame id + `:initial-events` lift from the app's existing frame setup; the React-18 `create-root`/`defonce`-atom dance *deletes* (`ui/mount` is idempotent per root). No existing frame config to lift from → judgment (name the frame id with the author). `reagent.dom.server` / `hydrate-root` are the SSR family → MIG-23 (D — route between the static-page and SSR-then-hydrate paths).

## MIG-24 — ns requires (runs LAST)

Add the compiled-view require; drop `reagent.*` requires **only when the namespace has zero remaining uses** (a held D/R view keeps them alive):

```clojure
(:require [re-frame.ui :as ui :refer [defview sub]]   ; add
          ;; [reagent.core :as r]   ; drop only if nothing else needs it
          )
```

## MIG-29 — callback ref → `ui/raw-fn`

`:ref` is a reserved React slot; the bare-fn shorthand does not apply, so wrap it explicitly:

```clojure
;; before → after
{:ref (fn [n] (when n (.focus n)))}  =>  {:ref (ui/raw-fn (fn [n] (when n (.focus n))))}
```

A ref body that reads *view state* (not just the node) prefers an object ref → D. `:ref` on an *internal view* call site is a separate concern → D.

## MIG-31 — `capture-frame` → the `(frame)` body form

A zero-arg render-body capture rewrites in place to the compiled ops-bundle:

```clojure
;; before → after
(let [h (rf/capture-frame)] …)   =>   (let [h (ui/frame)] …)
```

The bundle is incarnation-fenced (ops fail loud once the captured frame is destroyed). An explicit-arity `(rf/capture-frame frame-id)`, or a capture sited *inside* a callback/loop, has no direct compiled site → D.

## MIG-32 — framework `route-link` → the compiled `ui/route-link`

`ui/route-link` is an **ordinary compiled `defview`** shipped in `re-frame.ui` — the compiled counterpart of the stock-Reagent `route-link`. The Reagent head becomes the compiled head:

```clojure
;; before → after
[rf/route-link {:to :home} :Home]   =>   [ui/route-link {:to :home} :Home]
```

`:to` (a registered route id) is required; `:params` / `:query` / `:fragment` feed both the href and the dispatch payload; **every other key** — `:class`, `:title`, `:id`, `:aria-label`, `:target`, `:download`, `:on-click`, any further HTML attribute — passes through to the underlying `<a>`. A plain `[:a {:href …}]` is **not** an equivalent (the runtime doesn't intercept plain anchors), so the head-rename is the migration.

Two things to carry: (a) rendering a `ui/route-link` **without `day8/re-frame2-routing` on the classpath fails loud** with `:rf.error/routing-artefact-missing` — confirm the routing artefact is present. (b) A caller `:on-click` runs *first* and may veto the interception (prevent-default), exactly as in Reagent; it is a prop on an internal compiled view, so a bare fn is legal-and-opaque (MIG-27 / C-13a) — reach for `ui/handler` only if a stable identity or a phase is actually needed. Framework-shipped Reagent view heads other than `route-link` have no ruled compiled counterpart → judgment (hold).

## MIG-33 — adapter boot

```clojure
;; before → after
(rf/init! reagent-adapter/adapter)   =>   (rf/init! ui/adapter)
```

Drop the Reagent-adapter require when nothing else uses it. **One confirm with the author** (this is the single judgment on an otherwise-mechanical swap): on a **mixed page** where some roots stay on Reagent, *keep* the Reagent adapter installed — swap to `ui/adapter` only when the page's every root is converted. The skill cannot know the page's root inventory; the author confirms it. Root-level, once per app.

## MIG-34 — `dangerouslySetInnerHTML` → `ui/html`

```clojure
;; before → after
[:div {:dangerouslySetInnerHTML {:__html s}}]   =>   [:div (ui/html s)]
```

Delete the prop; `s` becomes the element's sole child wrapped in `ui/html`. A **non-literal** `{:__html …}` value (computed map, inside a spread) → D.
