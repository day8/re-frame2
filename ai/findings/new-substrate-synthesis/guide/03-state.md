# 03 — State

A view has four inputs, each with one spelling:

| Question | Answer |
|---|---|
| Shared application state? | `(sub [:query …])` |
| Given by my parent? | props |
| Ephemeral, mine, gone on unmount? | `(local initial)` |
| Keep a resource alive while I'm visible? | `(lease descriptor)` |

There is no fifth — and that's the feature. No ratoms, cursors, reactions, or external
stores: state that matters lives in app-db, where events, time-travel, Story, and Xray
can all see it.

## Subscriptions: `sub`

```clojure
(ui/defview order-summary [{:keys [order-id]}]
  (let [order (sub [:orders/by-id order-id])
        total (sub [:orders/total order-id])]
    [:div [:h3 (:title order)] [:strong (money total)]]))
```

- Returns the **current value**; re-renders this view when it changes.
- Parametric queries just work — when `order-id` changes, the old target releases and the
  new one acquires, atomically. You will never see order A's data against order B's id,
  even for one frame.
- **Conditional reads are legal.** `sub` is not a hook:

```clojure
;; guide:no-fixture — illustrative fragment
(let [details (when expanded? (sub [:orders/details id]))] …)   ; ✓
```

  A collapsed row genuinely subscribes to nothing. The *one* restriction is loops —
  a `(sub …)` inside `(for …)` is a compile error, because a view's read sites must be
  finite. The fix is the natural factoring anyway:

```clojure
(ui/defview order-row [{:keys [id]}]
  [:li (:title (sub [:orders/by-id id]))])

(ui/defview order-list []
  [:ul (for [id (sub [:orders/visible-ids])]
         [order-row {:key id :id id}])])                          ; ✓
```

- Keep real computation in `reg-sub` (shared, cached, Xray-visible, JVM-testable); views
  do presentation math only.

> **See it live.** Mount any view from this page and open Xray's inspector on it: the
> Dependencies panel's static column is exactly the `sub` sites you just wrote,
> readable before a single event fires. *(The inspector's causal surfaces land S3.)*

## Local state: `local` *(lands S3)*

```clojure
(ui/defview search-box []
  (let [[text set-text] (local "")]
    [:div
     [:input {:value text :on-input #(set-text (.. % -target -value))}]
     [:button {:on-click [:search/run text]} "Search"]]))
```

`(local initial)` returns the current value and a setter. Setting it re-renders this
view only. The value lives in host state underneath — deliberately outside app-db, so
it is never time-travelled.

**The doctrine (read twice):** `local` is for keystroke-latency ephemera — uncommitted
input text, an open/closed disclosure, hover. Handlers in the same view may read it —
`[:search/run text]` above is the canonical, conforming seam (handlers read committed
slots, local ephemera included; ruled 2026-07-12). The boundary: the moment a value needs
cross-view observation, replay or persistence, schema or tool inspection, durable
navigation semantics, or subscription-derived computation, it belongs in app-db behind an
event. Note the seam above: the *keystroke* is a fn + `local`; the *intent*
(`[:search/run text]`) is data. When the field's text **is** product state, skip `local`
entirely:

```clojure
[:input {:value (sub [:form/email]) :on-input [:form/typed :email :rf.ui/value]}]
```

## Effects: `effect` *(lands S3)*

For synchronizing with the world outside the tree — DOM measurement, chart libraries,
focus. Never for app logic (that's events and fx).

```clojure
(ui/defview chart [{:keys [series]}]
  (let [[node set-node] (local nil)]
    (effect [node series]                    ; VALUE deps, compared by rf=
      (when node
        (draw! node series)
        #(destroy! node)))                   ; optional cleanup
    [:canvas {:ref (ui/raw-fn set-node)}]))  ; refs are explicit — never bare fns
```

(Why `ui/raw-fn` on the ref: `:ref` is not an event property — React calls it during
commit, so the bare-fn shorthand doesn't apply; the explicit form marks that. Object refs
work too.)

Deps are values — a rebuilt-but-equal vector is the same dep. No identity traps, no
`useCallback`, no stale closures. `(effect :connect …)` runs at each connect (mount, or
reveal after an Activity hide) with cleanup at each disconnect — there is deliberately no
"once" in a lifecycle React can replay. Dispatching
from an effect uses `(ui/dispatch-fn)` — stable, bound to the committed frame, and loud if
called after the view disconnects (it catches leaked listeners for you). Dispatching
*during render* gets a dev warning pointing here.

## Resource liveness: `lease` *(lands S2; view-level lease semantics confirm at S3)*

```clojure
(ui/defview article-page [{:keys [slug]}]
  (lease {:resource :article/by-slug :params {:slug slug}})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :article/by-slug
                                                  :params {:slug slug}}])]
    (case status
      :loading [spinner]
      :error   [load-failed]
      [article-body {:article data}])))   ; :loaded — and :fetching, which keeps prior data visible
```

`lease` declares *interest*: while this view is mounted and visible, keep the resource
alive. First lease in → the resource is ensured (fetch starts, or an in-flight one is
joined); last lease out → it can wind down. **Lease never returns data and never fetches
during render**; reading is always the passive `(sub [:rf/resource …])`. Conditional
leases follow the same rule as conditional reads: `(when live? (lease …))` is legal —
a hidden tile holds nothing alive — while a `lease` inside a loop is a compile error,
with the same fix (extract a keyed child view). Rule of thumb:
loading that belongs to navigation or workflow rides route/event resource plans; `lease`
is for liveness that genuinely follows visible UI — dashboard tiles, hover cards, modals.

Two pointers to keep this section honest. The status vocabulary shown is the common
subset — the full enum (`:idle` included) and the query map's `:scope` key are
Spec 016's. And nothing on this page *fetches*: the resource system does. Leases only
declare that somebody is watching; how data actually arrives — effects, transports,
the events they dispatch — is core re-frame2 dataflow ([core effects](../../../../docs/core/effects.md), [resources](../../../../docs/core/where-state-lives.md)),
and [10](10-worked-app.md) shows the seam from the view side.
