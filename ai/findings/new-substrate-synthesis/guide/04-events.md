# 04 — Events

The idiomatic handler is not a function. It is the **event vector**:

```clojure
[:button {:on-click [:cart/add product-id]} "Add to cart"]
```

Clicked → `[:cart/add product-id]` dispatches to the frame this view is mounted under.
Your intent was already data everywhere else in re-frame2; the view layer stops
converting it to a closure for the last ten feet.

!!! note "Stage"
    Event vectors are data from Stage 1 — they compile, ride the JVM tree, and are
    assertable in tests today. Committed dispatch behaviour on this page (splicing,
    the sync door, full `ui/event` / `ui/handler` semantics) lands S3. Until then,
    drive the loop from the REPL or `ui.test/dispatch!` as in [01](01-getting-started.md).

## Why vectors win

- **Readable** — what does this button do? It says so: in source, in Xray before any
  click, in a Story snapshot, in a headless test.
- **Testable without a DOM** — assert
  `(= [:cart/add 42] (-> tree (ui.test/find :button) ui.test/attrs :on-click))`
  ([08](08-testing.md)).
- **No stale closures** — nothing is captured; the handler reads committed values and
  the committed frame. Re-parent a subtree and dispatch re-binds itself.
- **Fast** — vectors are values: no per-render closure churn, and props stay honestly
  comparable, which keeps memoisation working ([10](10-performance.md)).

!!! tip "See it live"
    Assert a vector headlessly, or hover the element in Xray and read it before any
    click. Your AI pair reads the same surface over MCP. *(Xray's UI-substrate panels
    land S3.)*

## Payloads: three placeholders

```clojure
[:input {:on-input  [:form/typed :email :rf.ui/value]}]
[:input {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
[:div   {:on-key-down [:editor/key :rf.ui/key]}]        ; "Enter", "a", …
```

`:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key` — a closed set of three scalars,
spliced at dispatch time. There is deliberately no `:rf.ui/event` and no
`:rf.ui/form-data` (raw events and form payloads are host/EDN-hostile); both cases are
what `ui/event` (below) exists for.

**Placeholders work in literal vectors only.** A vector forwarded through props
dispatches its contents as-is; dev warns if it spots a placeholder keyword riding one.

DOM listener options use the map form:

```clojure
[:form {:on-submit {:event [:signup/requested] :prevent-default true}}]
```

Full option vocabulary: `:prevent-default`, `:stop-propagation`, `:capture`,
`:passive`, `:once`.

## Beyond the vocabulary: `ui/event`

For payloads that need the live event — form contents, files, coordinates, filtering:

```clojure
[:form
 {:on-submit (ui/event [e]
               (.preventDefault e)
               [:signup/submitted (form-fields e)])}]

[:input {:on-key-down (ui/event [e]
                        (when (= "Enter" (.-key e))
                          [::submitted item-id]))}]     ; nil ⇒ dispatch nothing
```

The body runs when the callback fires; the last form is the event vector (or `nil`).
If the body merely wraps a literal vector, dev suggests the vector form instead.

## Forms and controlled inputs *(lands S3 — sync-input door)*

```clojure
[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]
```

works without caret jumps or IME breakage: dispatches from controlled-input sites run
**synchronously within the DOM event** — browser input event → synchronous drain and
epoch commit → ViewCell snapshot advance → React's discrete render observes the same
value. React therefore performs no restorative DOM write, preserving caret and IME
state. This is the one sanctioned synchronous door; everything else batches. You do
not configure it; it is the law of those sites.

A whole form is fields over app-db — one event, one sub, every field:

```clojure
(rf/reg-event :signup/init
  (fn [_ _] {:db {:signup {:email "" :password "" :newsletter? false :plan "free"}}}))

(rf/reg-event :signup/set
  (fn [{:keys [db]} [_ field value]]
    {:db (assoc-in db [:signup field] value)}))

(rf/reg-event :signup/submitted
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:signup :status] :submitting)}))  ; hand off to transport fx

(rf/reg-sub :signup/field
  (fn [db [_ field]] (get-in db [:signup field])))

(ui/defview signup-form []
  [:form {:on-submit {:event [:signup/submitted] :prevent-default true}}
   [:input {:type :email
            :value (sub [:signup/field :email])
            :on-input [:signup/set :email :rf.ui/value]}]
   [:input {:type :password
            :value (sub [:signup/field :password])
            :on-input [:signup/set :password :rf.ui/value]}]
   [:label
    [:input {:type :checkbox
             :checked (sub [:signup/field :newsletter?])
             :on-change [:signup/set :newsletter? :rf.ui/checked]}]
    "Send me the newsletter"]
   [:select {:value (sub [:signup/field :plan])
             :on-change [:signup/set :plan :rf.ui/value]}
    [:option {:value "free"} "Free"]
    [:option {:value "pro"}  "Pro"]]
   [:button "Sign up"]])
```

Every field is inspectable, replayable, and headlessly testable after installing the
[test namespace fixture](08-testing.md#test-namespace-setup):

```clojure
(deftest plan-select-carries-intent
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[:signup/init]]})]
    (let [tree (ui.test/render [signup-form] {:frame frame})]
      (is (= [:signup/set :plan :rf.ui/value]
             (-> tree (ui.test/find :select) ui.test/attrs :on-change))))))
```

**When the door applies.** The compiler proves an element controlled by seeing a
literal `:value`/`:checked` prop co-present with the vector-handler site. A dynamic
props map, `ui/spread`, or a `ui/event`/bare-fn dispatch at such a site falls back to
ordinary batching, and dev tells you so. Keep controlled fields literal.

**Uncommitted drafts stay in `local`.** When another view must react to every
keystroke, the keystroke *is* product state — dispatch placeholders. When only this
view cares until submit, hold the draft in `(local "")` and dispatch on submit — the
search-box seam in [03](03-state.md).

## Forwarding intent through components

```clojure
(ui/defview icon-button [{:keys [event label icon]}]
  [:button {:aria-label label :on-click event} [icon-view {:name icon}]])

[icon-button {:event [::delete item-id] :label "Delete" :icon :trash}]
```

Parents pass data; the child's site dispatches in its committed frame. Placeholders
belong at the literal site, so a reusable *input* control takes the field id and
builds `[:form/typed field :rf.ui/value]` itself.

## The decision table

| You need | Write | Notes |
|---|---|---|
| Dispatch intent (the 90%) | `[:event … :rf.ui/value]` | data; canonical |
| The live event — forms, files, filtering | `(ui/event [e] … [:vector …])` | stable; committed values + the event |
| Imperative work, stable identity | `(ui/handler [x] …)` | foreign change-callbacks in memoised children |
| Callback a foreign component calls **while rendering** | `(ui/render-fn [x] …)` | pure; current render; no identity promise |
| Quick local work in a **native** `:on-*` | bare `#(…)` | legal there only — shorthand for `ui/handler` |
| Any callback prop on a **foreign component** | one of the forms above, explicitly | bare fn is a compile error |
| Identity-as-protocol foreign APIs | `(ui/raw-fn f)` | passes identity through untouched |

**Phase rule:** event handlers see **committed** values (a click on old DOM means old
values — coherent with what the user saw); render callbacks see the **current**
render. One function cannot do both, so the forms are distinct.

```clojure
;; ui/handler — foreign change-callback, stable identity
[VirtualList {:items rows
              :on-scroll (ui/handler [offset] (save-scroll-position! offset))}]

;; ui/render-fn — called while rendering, pure
[DataGrid {:rows     rows
           :row-key  (ui/render-fn [row] (:id row))
           :render-cell (ui/render-fn [cell] (str (:value cell)))}]
```

## Lists

A handler that does not touch the loop variable is fine inline
(`{:on-click [:list/refresh]}` — one shared callback). One that captures the row
(`[::open (:id t)]`) is a compile error with the fix: extract a keyed child view —
each row then owns its site.

## Lifecycle is not an event

There is deliberately no `:on-mount`. React mounts, replays, hides, and restores
components for mechanical reasons (StrictMode, Activity, HMR, error recovery) —
"the user viewed this" cannot be inferred from them. Dispatch domain visibility from
domain transitions (route `:on-match`, a machine state, the event that opened the
modal); synchronise with the host world in `(effect …)` ([03](03-state.md)).

## Safety nets

Dev checks every data handler's event id against the registrar at render — a typo'd
`[:cart/ad id]` warns immediately with the element's file:line.
