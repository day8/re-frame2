# 04 — Events

The idiomatic handler is not a function. It's the event vector:

```clojure
[:button {:on-click [:cart/add product-id]} "Add to cart"]
```

Clicked → `[:cart/add product-id]` dispatches to the frame this view is mounted under.
Your intent was already data everywhere else in re-frame2; the view layer just stops
converting it to a closure for the last ten feet.

*(Stage note: event vectors are data from Stage 1 — they compile, ride the JVM tree,
and are assertable in tests today. The committed dispatch behaviour on this page —
splicing, the sync door, `ui/event`/`ui/handler` semantics — lands S3.)*

## Why vectors win

- **Readable** — what does this button do? It says so: in source, in Xray's inspector
  (before any click), in a Story snapshot, in a headless test.
- **Testable without a DOM** — assert
  `(= [:cart/add 42] (-> tree (ui.test/find :button) ui.test/attrs :on-click))` on the
  rendered tree ([09](09-testing.md)).
- **No stale closures** — nothing is captured; the handler reads its committed values and
  committed frame. Re-parent a subtree and dispatch re-binds itself.
- **Fast** — vectors are values: no per-render closure churn, and props stay honestly
  comparable, which keeps memoization working ([07](07-performance.md)).

## Payloads: placeholders (three, all scalars)

```clojure
[:input {:on-input  [:form/typed :email :rf.ui/value]}]
[:input {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
[:div   {:on-key-down [:editor/key :rf.ui/key]}]        ; "Enter", "a", …
```

`:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key` — that's the whole vocabulary: a closed
set of three scalars, spliced at dispatch time. There is deliberately no `:rf.ui/event`
and no `:rf.ui/form-data` — a raw event is a host object and form payloads carry
duplicate keys and files (not EDN); both cases are exactly what `ui/event` (below)
exists for. **Placeholders work in literal vectors only** (the compiler recognizes
them at the call site); a vector forwarded through props dispatches its contents as-is,
and dev warns if it spots a placeholder keyword riding one.

DOM listener options use the map form:

```clojure
[:form {:on-submit {:event [:signup/requested] :prevent-default true}}]
```

The full option vocabulary: `:prevent-default`, `:stop-propagation`, `:capture`,
`:passive`, `:once` — the DOM listener surface is explicit, not implied. (`:passive`
and `:once` emit with S3's committed handlers; today's compiler rejects them loudly
rather than dropping them silently.)

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
It's code, not data — the manifest marks the site accordingly — and that's fine: this is
what the escape exists for. If the body merely wraps a literal vector, dev suggests the
vector.

## Forms and controlled inputs *(lands S3 — the sync-input door)*

`[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]`
works without caret jumps or IME breakage: dispatches from controlled-input sites run
**synchronously within the DOM event** — event → dispatch → commit → the new value is
back in `:value` before React repaints. This is the one sanctioned synchronous door;
everything else batches. You don't configure it; it's the law of those sites.

A whole form is just fields over app-db — one event, one sub, every field:

```clojure
(rf/reg-event :signup/init
  (fn [_ _] {:db {:signup {:email "" :password "" :newsletter? false :plan "free"}}}))

(rf/reg-event :signup/set
  (fn [{:keys [db]} [_ field value]]
    {:db (assoc-in db [:signup field] value)}))

(rf/reg-event :signup/submitted
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:signup :status] :submitting)}))  ; hand off to your transport fx here

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

Text, checkbox, select — the three placeholder-shaped payloads — and a submit that is
data plus one listener option. Every field is inspectable, replayable, and headlessly
testable:

```clojure
(deftest plan-select-carries-intent
  (let [frame (ui.test/frame {:app-db {:signup {:plan "free"}}})
        tree  (ui.test/render [signup-form] {:frame frame})]
    (is (= [:signup/set :plan :rf.ui/value]
           (-> tree (ui.test/find :select) ui.test/attrs :on-change)))))
```

*(This test runs on main today — a Tier-1 render resolves its `sub` sites against the
frame you minted.)*

**When the door applies — honestly.** The compiler proves an element controlled by
seeing a literal `:value`/`:checked` prop co-present with the vector-handler site —
then the door is on. A dynamic props map, `ui/spread`, or a `ui/event`/bare-fn dispatch
at such a site falls back to ordinary batching, and dev tells you so, naming the
sync-door conditions. Keep controlled fields literal — the form above qualifies at
every site. (Caret and IME behaviour is pinned by contract and verified in jsdom; the
real-browser matrix — Chromium/WebKit IME, caret-on-restore — is a named gate, G-8,
not yet run.)

**Uncommitted drafts stay in `local`.** When another view must react to every
keystroke (live filtering, the form above), the keystroke *is* product state —
dispatch placeholders. When only this view cares until submit, hold the draft in
`(local "")` and dispatch the intent on submit — the search-box seam in
[03](03-state.md). The boundary rides the value, not the widget.

## Forwarding intent through components

```clojure
(ui/defview icon-button [{:keys [event label icon]}]
  [:button {:aria-label label :on-click event} [icon-view {:name icon}]])

[icon-button {:event [::delete item-id] :label "Delete" :icon :trash}]
```

Parents pass data; the child's site dispatches in its committed frame. (Placeholders
belong at the literal site, so a reusable *input* control takes the field id and builds
`[:form/typed field :rf.ui/value]` itself.)

## The decision table

| You need | Write | Notes |
|---|---|---|
| Dispatch intent (the 90%) | `[:event … :rf.ui/value]` | data; canonical |
| The live event — forms, files, filtering, mechanics | `(ui/event [e] … [:vector …])` | stable; committed values + the event |
| Imperative work, stable identity | `(ui/handler [x] …)` | foreign change-callbacks in memoized children |
| A callback the foreign component calls **while rendering** | `(ui/render-fn [x] …)` | pure; current render; no identity promise |
| Quick local work in a **native event property** (`:on-*`) | bare `#(…)` | legal there only — shorthand for `ui/handler` (invoker and phase known). Not refs, not arbitrary fn props. Strict teams: `{:re-frame.ui/bare-handlers :error}` |
| Any callback prop on a **foreign component** | one of the forms above, explicitly | a bare fn here is a compile error — the foreign invoker's phase is unknown |
| Identity-as-protocol foreign APIs | `(ui/raw-fn f)` | passes identity through untouched |

The phase rule behind it: **event handlers see committed values** (a click on old DOM
means old values — coherent with what the user saw); **render callbacks see the current
render**. One function can't do both, so the forms are distinct.

Two rows deserve a worked example. `ui/handler` is for a foreign component's
change-callback — imperative work, stable identity (so the memoized child doesn't
re-render), committed values:

```clojure
[VirtualList {:items rows
              :on-scroll (ui/handler [offset] (save-scroll-position! offset))}]
```

`ui/render-fn` is for callbacks a foreign component calls **while rendering** — pure,
no dispatch, no identity promise:

```clojure
[DataGrid {:rows     rows
           :row-key  (ui/render-fn [row] (:id row))
           :render-cell (ui/render-fn [cell] (str (:value cell)))}]
```

## Lists

A handler that doesn't touch the loop variable is fine inline
(`{:on-click [:list/refresh]}` — one shared callback). One that captures the row
(`[::open (:id t)]`) is a compile error with the fix: extract a keyed child view — each
row then owns its site. (A bare fn in a loop *works* but costs a closure per row and
hides intent — dev nudges you to the child view.)

## Lifecycle is not an event

There is deliberately no `:on-mount`. React mounts, replays, hides, and restores
components for mechanical reasons (StrictMode, Activity, HMR, error recovery) — "the
user viewed this" cannot be inferred from them. Dispatch domain visibility from domain
transitions (the route's `:on-match`, a machine state, the event that opened the modal);
synchronize with the host world in `(effect …)` ([03](03-state.md)).

## Safety nets

Dev checks every data handler's event id against the registrar at render — a typo'd
`[:cart/ad id]` warns immediately with the element's file:line. (The registrar is
process-global; a lazily-loaded module that registers later can false-positive — the
warning says so.)
