# State reads and events

## Read through subscriptions

```clojure
(ui/defview account-summary []
  (let [account (ui/sub [::current-account])]
    [:section
     [:h2 (:account/name account)]
     [:p  (:account/plan account)]]))
```

`ui/sub` returns a value and captures the concrete subscription node as a dependency of the current render. The committed view rerenders when that node publishes an `rf=`-different value.

Do not write `@`, `deref`, `use-subscribe`, or `useMemo` around it.

## Keep computation in registered subscriptions

Views may perform small presentation calculations. Reusable or expensive derived data belongs in the derivation graph:

```clojure
(rf/reg-sub ::visible-orders
  :<- [::orders]
  :<- [::order-filter]
  (fn [[orders filter] _]
    (filter-orders orders filter)))

(ui/defview order-list []
  (let [orders (ui/sub [::visible-orders])]
    ...))
```

Benefits:

- computation runs only when inputs change;
- multiple views share it;
- Xray shows its inputs, skips, changes, and cause;
- headless tests can compute it without React;
- the view remains a mechanical projection.

## Read near use

If child rows update independently, pass identity and let the child read its projection:

```clojure
(ui/defview order-row [{:keys [order-id]}]
  (let [order (ui/sub [::order order-id])]
    [:li (:order/title order)]))

(ui/defview orders []
  (let [ids (ui/sub [::order-ids])]
    [:ul
     (for [id ids]
       [order-row {:key id :order-id id}])]))
```

Now one order change need not rebuild every row prop. This is a pattern, not a requirement to normalize all state. For a small immutable list that always changes together, passing item values may be simpler and fast enough.

## Conditional dependencies

```clojure
(ui/defview inspector [{:keys [open? item-id]}]
  (let [summary (ui/sub [::item-summary item-id])
        history (when open?
                  (ui/sub [::item-history item-id]))]
    ...))
```

Only the committed open branch owns the history subscription. This differs from React Hooks: `ui/sub` may be conditional because the ViewCell reconciles dependencies after commit.

Each lexical read site may execute at most once per render. A read in a loop is a compiler error; make it a child view or aggregate it.

## Explicit-frame read

Infrastructure views can pin a read:

```clojure
(ui/sub :preview/frame [::document document-id])
```

Most application code should instead scope a subtree with `ui/frame`. Explicit cross-frame reads are easy to abuse and make ownership harder to see.

## Direct event vectors

```clojure
[:button
 {:type :button
  :on-click [::archive order-id]}
 "Archive"]
```

This is the primary event form. The compiler creates a stable React callback that:

- uses the view's committed `order-id`;
- dispatches into the committed frame;
- carries its source site into development tracing;
- retains no fresh render closure;
- remains event data in JVM view tests.

Event vectors should express domain intent, not DOM mechanics:

```clojure
[::archive order-id]                  ; good
[::button-clicked order-id]           ; describes the widget, not the action
```

## Extract from the React event

```clojure
[:input
 {:value value
  :on-input
  (ui/event [e]
    [::value-edited (.-value (.-currentTarget e))])}]
```

`ui/event` evaluates when React invokes the callback. The final value must be an event vector.

Use `currentTarget` when reading the element that owns the handler. React may clear or retarget `target` semantics as events bubble through nested elements.

### Checkbox

```clojure
[:input
 {:type :checkbox
  :checked enabled?
  :on-change
  (ui/event [e]
    [::enabled-changed (.-checked (.-currentTarget e))])}]
```

### Keyboard event

```clojure
[:input
 {:on-key-down
  (ui/event [e]
    (when (= "Enter" (.-key e))
      [::submitted item-id]))}]
```

An event expression may return `nil` to dispatch nothing. This is useful for local event filtering, not for domain authorization; handlers still validate domain conditions.

### Prevent default

```clojure
[:form
 {:on-submit
  (ui/event [e]
    (.preventDefault e)
    [::submitted form-id])}
 ...]
```

Keep event-local browser mechanics here. Application effects belong in the event handler/effect map.

## Forward event intent through a component

```clojure
(ui/defview icon-button [{:keys [event label icon]}]
  [:button.icon-button
   {:type :button
    :aria-label label
    :on-click (ui/event event)}
   [icon-view {:name icon}]])

[icon-button
 {:event [::delete item-id]
  :label "Delete item"
  :icon :trash}]
```

The parent passes data, not a closure. The child dispatches in its committed frame and its DOM site appears in the causal trace.

Use a callback prop when the protocol genuinely expects a return value or immediate imperative interaction. Do not turn every component API into an event DSL.

## Imperative handler

```clojure
[:canvas
 {:on-pointer-move
  (ui/handler [e]
    (draw-preview! (.-currentTarget e) brush e))}]
```

`ui/handler` creates a stable callback that sees latest committed free locals. It does not dispatch automatically. The compiler marks it as an imperative boundary.

Use it only for callbacks invoked after commit, such as DOM events or documented foreign change callbacks. A key/comparator/formatter/render callback that the foreign component invokes during render uses pure `ui/render-fn` instead:

```clojure
[Sorter {:compare (ui/render-fn [a b]
                    (compare-items a b))}]
```

`ui/render-fn` observes the current render and makes no identity-stability promise. It cannot dispatch or use Hooks/subscriptions. The phase distinction prevents a concurrent render from either seeing stale committed data or mutating callbacks attached to old visible DOM.

For a foreign API that uses callback identity itself as a signal, pass through explicitly:

```clojure
[ForeignWidget {:callback (ui/raw-handler callback)}]
```

You give up stable-slot optimization and source-level event intent for that prop.

## Dispatch from an effect or foreign callback

```clojure
(let [dispatch! (ui/dispatch-fn)]
  (react/use-effect [topic]
    (fn []
      (listen! topic
        #(dispatch! [::message-received topic %])))))
```

The function is stable and frame-bound to the committed cell. Do not call it during render; development throws a targeted render-purity error.

## Do not dispatch from render

```clojure
;; Wrong
(ui/defview bad []
  (rf/dispatch [::initialize])
  [:div])
```

Render can run repeatedly or be abandoned. Initialization belongs in frame/root initial events. A user action belongs in an event attr. Prop-dependent external synchronization belongs in a carefully scoped effect. Workflow belongs in events or machines.

## No hidden fetch in reads

`ui/sub`, including `[:rf/resource ...]`, is passive. It never starts work. This keeps render pure and makes causality visible. See [Resources and asynchronous UI](08-resources-and-async-ui.md).

## Diagnose excess renders

Before changing code, ask Xray why the instance rendered:

- If a subscription changed, inspect its output and upstream inputs.
- If a prop changed, inspect the generating parent and whether the value is stable.
- If local state changed, check whether it should be app-db.
- If the cause is foreign React, isolate that boundary.

Do not merge independent subscriptions merely to reduce the visible count. The ViewCell already uses one React bridge; merge only when the derived value is one meaningful reusable projection.
