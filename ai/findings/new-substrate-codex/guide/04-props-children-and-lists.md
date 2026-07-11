# Props, children, and lists

## One named props map

```clojure
(ui/defview status-pill [{:keys [status compact?]}]
  [:span
   {:class ["status-pill"
            (str "status-" (name status))
            (when compact? "compact")]}
   (name status)])

[status-pill {:status :running :compact? true}]
```

Named props make call sites understandable and let the compiler validate, encode, compare, and explain them.

There are no positional component arguments. If two values form one concept, name the concept in the props map.

## Destructuring

Supported fast-path forms include:

```clojure
;; Unqualified keys
[{:keys [title tone]}]

;; Namespaced keys
[{:user/keys [id name]}]

;; Explicit binding and defaults
[{item-id :item/id
  :keys [selected?]
  :or {selected? false}}]
```

The compiler turns these into direct reads from the JS props object. It does not rebuild a CLJS props map.

Using `:as props` is allowed for a genuinely generic component, but materializes the whole props map and selects a generic comparison path:

```clojure
(ui/defview debug-props [{:keys [title] :as props}]
  ...)
```

Development reports the whole-props cost. Prefer explicit keys in hot or repeated components.

## Props schema

```clojure
(ui/defview money
  {:props [:map
           [:amount number?]
           [:currency [:enum :aud :usd :eur]]
           [:emphasis? {:optional true} boolean?]]}
  [{:keys [amount currency emphasis?]}]
  ...)
```

The schema serves three roles:

- literal calls missing required keys fail at compile time;
- development runtime calls from dynamic/foreign boundaries validate values;
- the view manifest gives tools a usable prop shape.

Schemas do not generate a TypeScript-like runtime type system. Dynamic Clojure remains dynamic, and production assertions normally elide.

## Children

A view accepts children by naming `:children`:

```clojure
(ui/defview panel [{:keys [heading children]}]
  [:section.panel
   [:h2 heading]
   [:div.panel-body children]])

[panel {:heading "Profile"}
 [:p "Account details"]
 [:button {:on-click [::edit]} "Edit"]]
```

Trailing child forms compile into React's `children` prop. Zero, one, and many children follow React semantics; the compiler handles the JS representation.

Treat children as opaque React content. Do not map over or mutate them unless you are writing a deliberate React interop component. Prefer explicit data props when a component needs to inspect a collection.

## Default memoization

`defview` generates an allocation-free prop comparator over declared slots. You do not wrap it in `React.memo`.

The comparator uses `rf=`:

- identical persistent values return quickly;
- equal keywords behave consistently across CLJS construction sites;
- subscription results stabilized by the ViewCell preserve their prior exact reference;
- a fresh equal large map may require an equality walk.

The component still renders when its own subscription, local state, frame, or context changes. Memoization only avoids unchanged parent-prop cascades.

## Keep props stable naturally

Prefer values already stable through persistent data and subscriptions:

```clojure
(let [settings (ui/sub [::settings])]
  [settings-panel {:settings settings}])
```

Avoid rebuilding equal wrapper maps for no reason:

```clojure
;; Adds an equal-but-fresh wrapper on each parent render.
[settings-panel {:settings {:theme (:theme settings)}}]
```

If the child needs only a theme, pass the scalar or register a focused subscription:

```clojure
[settings-panel {:theme (:theme settings)}]
```

Event vectors and `ui/handler` functions are stabilized by the compiler. Do not add `useCallback` around ordinary component callbacks.

## Data versus identity props

For independently changing entities, passing an ID and reading in the child often isolates updates:

```clojure
[user-row {:key user-id :user-id user-id}]
```

For a small presentational child rendered only with its parent, passing the whole immutable value is simpler:

```clojure
[avatar {:user user}]
```

Choose based on update ownership and clarity, not a blanket normalization rule. Xray makes the resulting dependencies visible.

## Lists and keys

```clojure
[:tbody
 (for [invoice invoices]
   [invoice-row
    {:key (:invoice/id invoice)
     :invoice-id (:invoice/id invoice)}])]
```

Keys are React identity, not merely warning suppression. A stable key preserves a row's local refs/effects across reordering and lets React remove the right instance.

Good keys:

- database/entity ID;
- stable composite identity such as `[tenant-id invoice-id]` converted to an accepted React key representation by the compiler;
- immutable slug when it is truly unique.

Bad keys:

- random UUID generated during render;
- array index for a reorderable/filterable list;
- display label that can collide or change;
- an entire map's printed representation.

The compiler can encode Clojure scalar/composite keys deterministically without exposing that encoding to application code.

## Row subscriptions

Do not read per-row subscriptions in the parent's `for`:

```clojure
;; Rejected
(for [id ids]
  [:li (ui/sub [::title id])])
```

Extract a row:

```clojure
(ui/defview title-row [{:keys [id]}]
  (let [title (ui/sub [::title id])]
    [:li title]))

(for [id ids]
  [title-row {:key id :id id}])
```

Each row now has a fixed read site, its own ViewCell only if reactive, and exact lifecycle under its key.

If thousands of tiny ViewCells are not appropriate, register one aggregate subscription and pass stable row data. Benchmark the real update pattern.

## Filtering and sorting

Put reusable or expensive collection work in a subscription:

```clojure
(rf/reg-sub ::visible-invoice-ids
  :<- [::invoice-ids]
  :<- [::filter]
  (fn [[ids filter] _]
    (filter-and-sort ids filter)))
```

The view then maps a stable result. Recomputing filter/sort inside render makes parent/local renders repeat the work and hides it from the derivation trace.

## Dynamic component props

Internal `defview` calls prefer literal maps. For a foreign or generic runtime prop map:

```clojure
[ForeignButton (ui/spread js-library-props
                          {:on-click (ui/event [::clicked])})]
```

Event-vector conversion from the opaque dynamic map is impossible because the compiler cannot see its final key/value shape. Put callback props in a literal override argument to `ui/spread`, as above, where the compiler can assign the event site. Foreign props use explicit `ui/event`/`ui/handler`; only native DOM event attrs accept a direct vector. Merge order is left-to-right; later literal props win.

## Callback props

Prefer event intent for a reusable application component:

```clojure
[menu-item {:activate-event [::route-opened route-id]}]
```

Use `ui/render-fn` when the child/foreign API calls the function while rendering, as comparators and formatters commonly do:

```clojure
[Sorter {:compare (ui/render-fn [a b] (compare-items a b))}]
```

The function sees current render values, remains pure, and has no identity guarantee. For a change/click callback invoked after commit, use stable `ui/handler` or event data. If the foreign API has a different documented identity protocol, use `ui/raw-handler` and document why.

## Unknown props

Literal unknown props on an internal view are compile errors. This catches typos and stale APIs early.

Foreign React components have open props because the Clojure compiler may not know their schema. A future generated TypeScript/JS binding can add checks, but raw interop remains explicit and permissive.

## Performance rule of thumb

Use normal immutable data and clear view boundaries first. The compiler and ViewCell already handle direct props, stable events, query identity, and memoization. Reach for a manual React memo primitive only at a foreign protocol that requires it and after a profile identifies the cost.
