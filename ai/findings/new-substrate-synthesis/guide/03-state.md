# 03 — State

A view has **four inputs**, each with one spelling:

| Question | Answer |
|---|---|
| Shared application state? | `(sub [:query …])` |
| Given by my parent? | props |
| Ephemeral, mine, gone on unmount? | `(local initial)` |
| Keep a resource alive while I'm visible? | `(lease descriptor)` |

There is no fifth — and that is the feature. No ratoms, cursors, reactions, or
external stores. State that matters lives in app-db, where events, time-travel,
Story, and Xray can all see it.

## Subscriptions: `sub`

```clojure
(ui/defview order-summary [{:keys [order-id]}]
  (let [order (sub [:orders/by-id order-id])
        total (sub [:orders/total order-id])]
    [:div [:h3 (:title order)] [:strong (money total)]]))
```

- Returns the **current value**. Re-renders this view when it changes.
- Parametric queries just work: when `order-id` changes, the old target releases and
  the new one acquires, atomically. You never see order A's data against order B's id,
  even for one frame.
- **Conditional reads are legal.** `sub` is not a hook:

```clojure
;; guide:no-fixture — illustrative fragment
(let [details (when expanded? (sub [:orders/details id]))] …)   ; ✓
```

A collapsed row genuinely subscribes to nothing. The *one* restriction is loops: a
`(sub …)` inside `(for …)` is a compile error, because a view's read sites must be
finite. The fix is the natural factoring:

```clojure
(ui/defview order-row [{:keys [id]}]
  [:li (:title (sub [:orders/by-id id]))])

(ui/defview order-list []
  [:ul (for [id (sub [:orders/visible-ids])]
         [order-row {:key id :id id}])])                          ; ✓
```

Keep real computation in `reg-sub` (shared, cached, Xray-visible, JVM-testable).
Views do presentation math only.

!!! tip "See it live"
    Mount any view from this page and open Xray's inspector: the Dependencies panel's
    static column is exactly the `sub` sites you wrote, readable before a single event
    fires. *(Causal inspector surfaces land S3.)*

## Local state: `local` *(lands S3)*

```clojure
(ui/defview search-box []
  (let [[text set-text] (local "")]
    [:div
     [:input {:value text :on-input #(set-text (.. % -target -value))}]
     [:button {:on-click [:search/run text]} "Search"]]))
```

`(local initial)` returns the current value, a setter, and — *(added 2026-07-16,
readiness P0-1)* — an atomic updater: `[text set-text update-text!]`. `set-text`
stores its argument exactly (functions included); `update-text!` applies
`(f current & args)` to the **latest host state**, so several same-turn callbacks
(key + pointer + timer) compose instead of losing writes. Two-element destructuring,
as above, stays valid. Setting it re-renders **this view only**. The value lives in
host state underneath — deliberately outside app-db, so it is never time-travelled.

> **Doctrine:** `local` is for keystroke-latency ephemera — uncommitted input text, an
> open/closed disclosure, hover. The moment a value needs cross-view observation,
> replay, persistence, schema, tool inspection, navigation semantics, or
> subscription-derived computation, it belongs in app-db behind an event.

Note the seam above: the *keystroke* is a fn + `local`; the *intent*
(`[:search/run text]`) is data. When the field's text **is** product state, skip
`local` entirely:

```clojure
[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]
```

## Effects: `effect` *(lands S3)*

For synchronising with the world outside the tree — DOM measurement, chart libraries,
focus. Never for app logic (that is events and fx).

```clojure
(ui/defview chart [{:keys [series]}]
  (let [[node set-node] (local nil)]
    (effect [node series]                    ; VALUE deps, compared by rf=
      (when node
        (draw! node series)
        #(destroy! node)))                   ; optional cleanup
    [:canvas {:ref (ui/raw-fn set-node)}]))  ; refs are explicit — never bare fns
```

Deps are values — a rebuilt-but-equal vector is the same dep. No identity traps, no
`useCallback`, no stale closures.

- `(effect :connect …)` runs at each connect (mount, or reveal after an Activity hide)
  with cleanup at each disconnect. There is deliberately no "once" in a lifecycle
  React can replay.
- Dispatch from an effect with `(ui/dispatch-fn)` — stable, bound to the committed
  frame, loud if called after disconnect (it catches leaked listeners).
- Dispatch *during render* gets a dev warning pointing here.

## Resource liveness: `lease`

```clojure
(ui/defview article-page [{:keys [slug]}]
  (lease {:resource :article/by-slug :params {:slug slug}})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :article/by-slug
                                                  :params {:slug slug}}])]
    (case status
      :loading [spinner]
      :error   [load-failed]
      [article-body {:article data}])))   ; :loaded — and :fetching keeps prior data visible
```

`lease` declares *interest*: while this view is mounted and visible, keep the resource
alive. First lease in → the resource is ensured; last lease out → it can wind down.

**Lease never returns data and never fetches during render.** Reading is always the
passive `(sub [:rf/resource …])`. Unlike a conditional read, a `lease` is a *leading
declaration*, not an expression — so you make liveness conditional inside the
descriptor: `(lease (when live? descriptor))`, where the descriptor evaluates to `nil`
(lease nothing) or a map (lease that resource). A `lease` in expression position —
inside a `when`, a prop, or a template — is a compile error, and a `lease` inside a
loop is too (extract a keyed child view).

Rule of thumb: loading that belongs to navigation or workflow rides route/event
resource plans; `lease` is for liveness that genuinely follows visible UI — dashboard
tiles, hover cards, modals.

!!! note
    Nothing on this page *fetches*. The resource system does. How data actually
    arrives — effects, transports, the events they dispatch — is core re-frame2
    dataflow, walked end-to-end in [07](07-servers.md).
