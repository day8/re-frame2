# Interceptors

Some chores apply to many [event handlers](glossary.md#event-handler): log every
event, snapshot state for undo, check a precondition. Copying that code into each
handler multiplies it and spreads state like an undo stack across every mutator.

An **interceptor** holds the chore instead. You write it once, register it under a
name, and wrap handlers — or every handler in a [frame](glossary.md#frame) — by
**referencing** that name. Most apps write few or none; reach for one when the
*same* chore applies to many handlers.

| Situation | Prefer instead |
|---|---|
| Logic for **one** handler | Keep it in that handler |
| Something that **does** I/O | An [effect](effects.md) (`reg-fx`) |
| A **derived value** many places need | [subscription](subscriptions.md) or [flow](flows.md) |

## A first interceptor: a logger

This interceptor logs each event on the way in, and how long the handler took on the way out:

```clojure
(rf/reg-interceptor :my-app/logger
  {:doc "Log each event on the way in, and its timing on the way out."}
  {:before (fn [ctx]
             (let [event (get-in ctx [:coeffects :event])]
               (js/console.log "→" (pr-str event))
               (assoc ctx ::started-at (js/performance.now))))
   :after  (fn [ctx]
             (let [event   (get-in ctx [:coeffects :event])
                   elapsed (- (js/performance.now) (::started-at ctx))]
               (js/console.log "←" (pr-str event) (str elapsed "ms"))
               ctx))})
```

`reg-interceptor` takes a **qualified keyword id** (`:my-app/logger`), an optional metadata map (`{:doc ...}`), and a **descriptor** map with a `:before` function, an `:after` function, or both. `:before` runs on the way in, before the handler; `:after` runs on the way out.

Each function takes one argument, `ctx` — the **context** — and returns it, possibly modified. The context is the only channel an interceptor has: `:before` here stores the start time on it, and `:after` reads it back. (`::started-at`, with the double colon, is a keyword namespaced to the current file, so it can't collide with anyone else's keys.)

Notes:

1. **Each dispatch gets a fresh context.** Scratch like `::started-at` never leaks from one dispatch into the next, so one registration is safe on any number of handlers and frames.
2. **The id is the handle.** Chains reference the interceptor by id, the [trace stream](glossary.md#trace-stream) and [Xray](glossary.md#xray) name it by id, and overrides find it by id.
3. **Return the context from both functions.** A function that returns `nil` is treated as "unchanged", which works in a log-only `:after` until you also `assoc` something and the change is lost. Always end with `ctx`.

??? info "Coming from Express / Koa middleware?"

    Interceptors are layers around one core action, each acting on the way in and on the way out — Koa's "onion". Three things differ. There's no `next()`: the chain is a fixed vector the runtime runs forward, then backward. What passes through is an immutable map you return a new version of, not a mutable request/response object. And Express middleware performs I/O itself, while an interceptor adds effect rows and lets the runtime perform them ([below](#contribute-dont-perform)).

??? note "Document it"

    Like every other `reg-*`, an interceptor without a `:doc` draws a dev warning (`:rf.warning/missing-doc`, once per id, [elided](glossary.md#elide) from production). Tools show the `:doc` wherever they show the id.

## Attaching it to a handler

Registering an interceptor doesn't run it; you put it in a handler's **chain**. A chain is the `:interceptors` key in the event's metadata map, and it holds interceptor **references** (ids), not interceptor values:

```clojure
(rf/reg-event :cart.item/add
  {:doc               "Add an item to the cart."
   :interceptors      [:my-app/logger]    ;; a reference, not the interceptor value
   :rf.cofx/requires  [:rf/time-ms]}      ;; not needed yet — you'll see it land in the context map below
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)
     :fx [[:dispatch [:toast/show "Added"]]]}))
```

The runtime resolves `:my-app/logger` at dispatch time. Dispatch `[:cart.item/add ...]` now and the console shows the event going in and the timing coming out.

Because the chain is a vector of keywords, it is plain data you can print, diff, and carry in an [image](glossary.md#image). And because it stores a reference, re-registering `:my-app/logger` with new behaviour takes effect on the next dispatch without re-registering the event.

An interceptor *map* placed directly in a chain — `{:interceptors [{:before ...}]}` — raises `:rf.error/inline-interceptor-removed`. Register the behaviour under a name and reference the name.

## The context map: two keys

The context is one immutable map passed through the whole chain. Two of its keys matter:

| Key | Holds | Filled by |
|---|---|---|
| `:coeffects` | The handler's **inputs**: `:event`, `:db`, plus each world fact the handler declared with [`:rf.cofx/requires`](coeffects.md) — flat, under its own id | The runtime — completely, *before* the chain runs |
| `:effects` | The handler's **outputs**: the new `:db`, the `:fx` vector | The handler; then decorated by `:after` stages on the way out |

Caught mid-chain, just after the handler has run, the map looks like this:

```clojure
{:coeffects {:event      [:cart.item/add {:sku "abc-123" :qty 2}]
             :db         {:cart/items [...]}
             :rf/time-ms 1781078400123}      ;; a declared fact, delivered flat
 :effects   {:db {:cart/items [... new-item]}
             :fx [[:dispatch [:toast/show "Added"]]]}}
```

These are the [coeffects](coeffects.md) the handler reads and the [effects](effects.md) it returns. So:

- a **`:before`** sees only `:coeffects` — the outputs don't exist yet;
- an **`:after`** sees both `:coeffects` and `:effects`.

That's why the logger's `:before` can read `:event`, while the undo interceptor [below](#a-real-interceptor-undo) needs `:after` to compare the `:db` before and after the handler.

??? note "Going deeper"

    Every stage is a function `context -> context`, and running the chain folds those functions over one value. Because the value is immutable, the chain is a pure transformation, which is what lets [replay, time-travel](glossary.md#time-travel), and tests re-run it against recorded inputs and get the same answer. The runtime also stores a few framework keys on the context (the [dispatch envelope](glossary.md#event-envelope) among them) for tooling.

## The sandwich: how a chain runs

A single interceptor is a `:before`/`:after` pair wrapped around the handler. Stacked interceptors each wrap everything inside them, like slices of bread around a sandwich: the first in the chain is the outermost pair. With three — `A`, `B`, `C` — around a handler `H`, the runtime makes two passes over one context:

```text
declared:  [A B C]  + handler H

sweep 1 — :before, in declaration order:
    A:before → B:before → C:before → H   (the handler runs as the last :before)
sweep 2 — :after, in REVERSE order:
    C:after → B:after → A:after
```

As code, the whole execution is one threading expression:

```clojure
(-> context
    ((:before A)) ((:before B)) ((:before C))
    ((:before H))                              ;; the handler, wrapped
    ((:after  C)) ((:after  B)) ((:after  A)))
```

(The runtime also resolves the references and guards each call, but this is the shape.)

Two details matter. **The handler runs as the last `:before`**: the runtime wraps it as an interceptor too. And **the way out mirrors the way in**: whatever `B:before` set up, `B:after` tears down, after everything inside it has run.

The handler doesn't know it's wrapped, and an interceptor doesn't know what it wraps; they communicate only through the context. Don't write an interceptor that works only when another one happens to wrap it — that hidden ordering breaks when someone reorders the chain.

??? info "From re-frame v1 — the chain no longer rewrites itself"

    v1's context carried `:queue` and `:stack` — the interceptors still to run and those already run — and an interceptor could modify them to rewrite the chain mid-run. re-frame2 has neither: the chain you declare is the chain that runs, so a tool can print and diff it without executing it. The legitimate uses have other forms: a `:before` can still skip the handler ([below](#contribute-dont-perform)), and a [`:factory` reference](#parameterized-interceptors-the-factory-descriptor) builds a configured interceptor up front.

### Inputs are complete before the chain runs

By the time the first `:before` runs, `:db`, `:event`, and every fact the handler declared via [`:rf.cofx/requires`](coeffects.md) are already in `:coeffects`. The order for one event is:

```text
envelope finalization → context assembly → :before pass → handler → :after pass
```

(*Envelope finalization* is the runtime completing the dispatch's own metadata record — the [event envelope](glossary.md#event-envelope), covered properly with [observability](observability.md); here it only marks where the pipeline starts.)

Coeffects are delivered during **context assembly**, before the chain. A `:before` can still modify the assembled `:coeffects` map, but it never sees a half-filled one.

??? info "From re-frame v1 — coeffect injection left the chain"

    In v1, coeffect injection was itself an interceptor in the chain, so an interceptor placed before it saw an incomplete `:coeffects` map. In re-frame2 injection happens during context assembly, before the chain. An `inject-cofx` entry in a chain raises `:rf.error/inject-cofx-removed`; declare the fact with `:rf.cofx/requires` instead. See [From re-frame v1](25-from-re-frame-v1.md).

## The one standard interceptor: `path`

Core ships exactly one standard interceptor, and you attach it with a second kind of reference — an `[id arg]` vector. `[:rf.interceptor/path <path-vector>]` **focuses** a handler on an [`app-db`](glossary.md#app-db) sub-slice: on the way in it stages just that slice as the handler's `:db`; on the way out it widens the returned slice back into the full `app-db`.

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}   ;; [id arg] reference — the arg is the path
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))   ;; db here is the [:cart] slice, not the whole map
```

The handler reads and writes as if `[:cart]` were the whole of app-db, and `path` widens the result back. The bracket form is the general shape for any *parameterized* interceptor: the id names a registered factory, and the one `arg` configures it. There is no `rf/path` function; every chain entry is a bare keyword or an `[id arg]` vector.

Two edge cases:

- **The root path `[]` focuses the whole `app-db`.** `[:rf.interceptor/path []]` stages the entire `app-db` as `:db` and installs whatever the handler returns wholesale — handy when you want focusing-style ergonomics over the full map.
- **An unchanged slice stays a true no-op.** If the handler emits no `:db` effect, `path` synthesizes none.

The second point is why you should use the standard `path` rather than writing your own:

!!! warning "Gotcha — a hand-rolled `path` defeats the no-op fast path"

    re-frame2 skips the [`app-db` commit](glossary.md#commit) — and therefore all the downstream re-renders — when a handler returns an `app-db` that is `identical?` to the one it received. A naive `path` that does `(assoc-in original-db [:cart] returned-slice)` allocates a fresh top-level map *even when the slice didn't change*, defeating that identity check and causing needless re-renders. The standard `path` knows both the original full `app-db` *and* the original slice, so when the returned slice is `identical?` to the one it staged, it re-emits the **original `app-db` object** — preserving the no-op all the way down.

### Parameterized interceptors: the `:factory` descriptor

You can write parameterized interceptors too. Besides `:before` / `:after`, a descriptor can be `{:factory f}`: a function that takes the reference's **one** `arg` and returns an ordinary `:before`/`:after` descriptor for that arg:

```clojure
;; A stamp factory: each reference configures WHICH metadata key gets stamped
;; onto the event's :db write, so one registered interceptor serves many shapes.
(rf/reg-interceptor :cart/stamp-meta
  {:doc "On the way out, stamp an audit key onto the handler's :db effect."}
  {:factory (fn [meta-key]
              {:after (fn [ctx]
                        (let [event (get-in ctx [:coeffects :event])]
                          (if (contains? (:effects ctx) :db)
                            (assoc-in ctx [:effects :db meta-key]
                                      {:by (first event) :at (get-in ctx [:coeffects :rf/time-ms])})
                            ctx)))})})
```

Reference it with the bracket form, passing the factory's single arg (need several inputs? pass one map or vector):

```clojure
(rf/reg-event :cart.item/restock
  {:interceptors      [[:cart/stamp-meta :cart/last-restocked]]
   :rf.cofx/requires  [:rf/time-ms]}   ;; the :after reads it from :coeffects
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

The factory's `:after` reads `:rf/time-ms` from `:coeffects`, and an interceptor sees only the facts the *event* declared (the `:rf.cofx/requires` lives on the event, not the interceptor). Leave that line off and `:at` is `nil`.

The factory runs when the chain is assembled, building the interceptor for that arg. `[:cart/stamp-meta :a]` and `[:cart/stamp-meta :b]` are two distinct chain entries, which is why overrides (below) match the full reference, not just the id.

??? info "From re-frame v1 — the helper grab-bag is gone"

    v1's helper interceptors — `debug`, `trim-v`, `enrich`, `after`, `on-changes` — do not exist in re-frame2. Each is a few lines of `reg-interceptor`. `path` remains, as the standard `[:rf.interceptor/path …]` reference; `on-changes` is covered by [flows](flows.md).

## Two places to attach

Per-handler attachment, as above, applies to that event only. The second place is the [frame](frames.md), which takes the same references:

```clojure
(rf/make-frame
  {:id :app/main
   :interceptors [:my-app/logger]})   ;; a reference; wraps EVERY event handled in this frame
```

Per-frame interceptors are **prepended** to each event's own chain, so they run outermost: frame-wide interceptors, then event-specific ones, then the handler. A chore that applies to every event becomes one frame interceptor, with no change to handler code.

??? info "From re-frame v1 — `reg-global-interceptor` is gone"

    `reg-global-interceptor` does not exist; per-frame `:interceptors` replaces it. Each [frame](frames.md) keeps its own list, so nothing leaks across SSR requests, Story variants, or test fixtures.

### Removing or swapping a reference: `:interceptor-overrides`

A test can remove or swap one interceptor without touching registrations. `:interceptor-overrides` matches a chain entry **by its exact reference** and either removes it (`nil`) or replaces it with another reference, per dispatch or per frame:

```clojure
(rf/dispatch-sync [:cart.item/add {:sku "abc-123" :qty 2}]
                  {:frame                 :app/main
                   :interceptor-overrides {:my-app/logger nil}})         ;; remove the logger for this dispatch
```

```clojure
(rf/make-frame
  {:id :story/cart
   :interceptors          [:my-app/auth-guard]
   :interceptor-overrides {:my-app/auth-guard :story/skip-auth}})        ;; swap one reference for another
```

Matching is by the full reference, so a parameterized entry is named in full: `{[:rf.interceptor/path [:cart]] nil}` removes only that `path`, leaving `[:rf.interceptor/path [:cart :items]]` in place. Replacement values are references too, so override maps stay plain data.

When both a frame and a dispatch supply overrides, they **merge, and the per-dispatch value wins** on any key both set. A malformed override — a key or replacement that isn't a valid reference — is rejected loudly with `:rf.error/interceptor-override-invalid`.

## Contribute, don't perform

The chain runs as part of the pure event pipeline that replay, time-travel, and tests re-run against recorded inputs. So don't perform I/O in an interceptor: it runs again on every replay, and `:fx-overrides` can't redirect it, because `:fx-overrides` redirects *registered effects*, not a `localStorage` call inside an `:after`. Instead, append [effect](glossary.md#effect) rows and let the [effect handler](glossary.md#effect-handler) perform them:

```clojure
;; ❌ performs — re-fires on replay, invisible to :fx-overrides and the trace
:after (fn [ctx]
         (.setItem js/localStorage "cart" (pr-str (get-in ctx [:effects :db :cart])))
         ctx)

;; ✅ contributes — a recorded, overridable, traceable effect row
:after (fn [ctx]
         (update-in ctx [:effects :fx] (fnil conj [])
                    [:localstorage/set {:key   "cart"
                                        :value (get-in ctx [:effects :db :cart])}]))
```

(`:localstorage/set` is the app-registered effect from [effects](effects.md) — its `reg-fx` handler stays the one place that touches the host.)

Interceptors do two kinds of work:

- **Decide.** A `:before` can skip the handler by setting `:rf/skip-handler? true` on the context; every `:after` still runs. An auth guard, for example, can skip the handler and append a redirect instead ([Require sign-in on a route](../routing/how-to/require-sign-in-on-a-route.md) shows it in full):

    ```clojure
    :before (fn [ctx]
              (if (get-in ctx [:coeffects :db :auth :user])
                ctx
                (-> ctx
                    (assoc :rf/skip-handler? true)
                    (assoc-in [:effects :fx] [[:dispatch [:auth/show-login]]]))))
    ```

    (To validate untrusted input you don't need an interceptor: the `:boundary? true` registration flag checks the handler's own `:schema` before the chain runs; see [Validate with schemas](how-to/validate-with-schemas.md#in-production-what-goes-what-stays).)

- **Decorate.** Transform `:coeffects`, rewrite `[:effects :db]`, append `:fx` rows.

Effect handlers perform the I/O. The exception is diagnostics: the logger's `console.log` may stay in the body, because repeating it on replay is harmless.

!!! warning "Gotcha — a frame interceptor runs on the server too"

    A frame-wide interceptor runs on *every* event in that frame, [SSR](../ssr/glossary.md#ssr) included. The `:localstorage/set` row above is safe under SSR because it is only data, and the `reg-fx` handler that performs it declares `:platforms #{:client}`, so the server skips it. An interceptor that touches the host *directly* (a `:before` that reads `js/localStorage`) has no such check and fails during a server render. Keep host access in the effect handler, where the platform gate lives.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/unregistered-interceptor` at load time | A chain names an id nobody registered (usually a typo) | Fix the id, or register it with `reg-interceptor` |
| `:rf.error/inline-interceptor-removed` | A chain holds an interceptor map instead of an id | Register the map with `reg-interceptor` and reference its id |
| An `:after` change is lost | The function returned `nil`, which is treated as "unchanged" | End every `:before` / `:after` with the context |
| `:rf.error/interceptor-exception` with `:phase :after` | The `:after` assumed `[:effects :db]` exists, but the handler returned none or threw | Guard for a missing `:db` effect ([When the chain throws](#when-the-chain-throws)) |
| A side effect repeats on replay, or fails during SSR | The interceptor performs I/O in its body | Append an `:fx` row instead ([above](#contribute-dont-perform)) |

## Advanced

## A real interceptor: undo

Undo is a good fit. Written by hand, "remember the old value, push it if it changed, clear redo" repeats in every mutating handler. The Circle Drawer example — one of the [7GUIs](https://eugenkiss.github.io/7guis/tasks/) tasks — registers it once, and events opt in by referencing it:

```clojure
;; Adapted from examples/core/seven_guis/circle_drawer/core.cljs — registered once,
;; then referenced by id from the events that deserve it.
(rf/reg-interceptor :drawer/undoable
  {:doc "Snapshot the pre-handler circles; on a real change push to :undo and clear :redo."}
  {:before (fn before [ctx]
             ;; snapshot taken from coeffects (the pre-handler db).
             (let [db    (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc ctx ::prior-circles prior)))
   :after  (fn after [ctx]
             ;; if the handler changed db, push the prior value to :undo.
             (let [prior    (::prior-circles ctx)
                   db-after (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})
```

`:before` reads the pre-handler `:db` from `:coeffects` and stores the prior circles on the context. `:after` reads the post-handler `:db` from `:effects` (absent if the handler returned none), compares, and only then pushes an undo step and clears redo. An event is undoable if its chain references the interceptor:

```clojure
(rf/reg-event :drawer/add-circle
  {:doc "Click on canvas — add a circle of default radius."
   :interceptors [:drawer/undoable]}                      ;; reference by id
  (fn [{:keys [db]} [_ x y]]
    (let [id (get-in db [:drawer :next-id])]
      {:db (-> db
               (update-in [:drawer :circles] conj {:id id :x x :y y :radius 30})
               (assoc-in  [:drawer :next-id] (inc id)))})))

(rf/reg-event :drawer/dialog-drag
  {:doc "Slider movement — updates the draft radius only. Continuous; NOT undoable."}
  (fn [{:keys [db]} [_ new-radius]]
    {:db (assoc-in db [:drawer :dialog :draft-radius] new-radius)}))

(rf/reg-event :drawer/close-dialog
  {:doc "Commit the dialog's draft radius onto its circle. One undo step."
   :interceptors [:drawer/undoable]}                      ;; reference by id
  (fn [{:keys [db]} _]
    (let [{:keys [circle-id draft-radius]} (get-in db [:drawer :dialog])]
      {:db (-> db
               (update-in [:drawer :circles]
                          (fn [cs] (mapv #(if (= circle-id (:id %))
                                            (assoc % :radius draft-radius)
                                            %)
                                         cs)))
               (assoc-in [:drawer :dialog] nil))})))
```

The drag handler changes only the dialog's draft and doesn't reference the interceptor, so slider moves never create undo steps. When `:drawer/close-dialog` commits, the snapshot is the pre-dialog state, so the whole edit is one undo step. Undo itself is an ordinary event (redo mirrors it with the stacks swapped):

```clojure
(rf/reg-event :drawer/undo
  {:doc "Pop one snapshot from :undo, push current :circles to :redo."}
  (fn [{:keys [db]} _]
    (let [{:keys [undo circles]} (:drawer db)]
      {:db (if (empty? undo)
             db
             (-> db
                 (assoc-in [:drawer :circles] (peek undo))
                 (update-in [:drawer :undo] pop)
                 (update-in [:drawer :redo] (fnil conj []) circles)))})))
```

## Introspecting a chain

Because a chain is plain data, the introspection you use on events and subs works on interceptors too. `handler-meta` reads an interceptor's metadata and source coordinates:

```clojure
(rf/handler-meta {:source :store :kind :interceptor :id :my-app/logger})
;; => {:doc "Log each event on the way in, and its timing on the way out."
;;     :ns my-app.audit :line 12 :file "..." ...}
```

And reading an *event's* metadata gives you the chain as authored — a vector of references, not resolved interceptor values:

```clojure
(rf/handler-meta {:source :store :kind :event :id :cart.item/add})
;; => {:doc "Add an item to the cart." :interceptors [:my-app/logger] ...}
```

A tool reads the references off the event, then looks up each one's source and `:doc`; that is how [Xray](glossary.md#xray) draws a chain with jump-to-source links. (In a real app the [trace stream](observability.md) already records every event with timings; the logger at the top of this page is for teaching.)

## Testing an interceptor

`:before` and `:after` are pure functions `ctx → ctx` over a plain map, so an interceptor unit-tests like [a handler](testing/event-handlers.md): call the function with a literal context and assert on the result. Give the functions names with `defn` so a test can call them directly:

```clojure
(defn stamp-audit [ctx]
  (if (contains? (:effects ctx) :db)                 ;; no :db effect → leave it alone
    (assoc-in ctx [:effects :db :audit/last-event]
              (first (get-in ctx [:coeffects :event])))
    ctx))

(rf/reg-interceptor :my-app/audit
  {:doc "Stamp the triggering event's id onto the handler's :db write."}
  {:after stamp-audit})

(deftest audit-stamps-the-event-id
  (let [ctx {:coeffects {:event [:cart.item/add {:sku "a"}]}
             :effects   {:db {}}}]
    (is (= :cart.item/add
           (get-in (stamp-audit ctx) [:effects :db :audit/last-event])))))
```

Build the literal ctx from the [two-key shape above](#the-context-map-two-keys): a `:before` test supplies only `:coeffects`; an `:after` test supplies both. Give your `:after` one test with `[:effects :db]` missing, the shape it meets when the handler returns no `:db` or throws ([When the chain throws](#when-the-chain-throws)). Without the guard above, a handler that returns no `:db` would get a one-key `:db` effect from `stamp-audit`, replacing app-db.

To test the wiring — that the reference actually wraps the handler — dispatch through a test frame and assert on the committed state, as in [a handler's runtime check](testing/event-handlers.md#3-when-you-want-the-runtime-a-fresh-frame-per-test). When another interceptor gets in the way, [`:interceptor-overrides`](#removing-or-swapping-a-reference-interceptor-overrides) removes it for one dispatch.

## When a reference is wrong

Because a chain is data, the runtime checks it when you register. Register an event whose `:interceptors` names an id nobody has registered (usually a typo), and `reg-event` (or `make-frame`) throws `:rf.error/unregistered-interceptor`, naming the missing id. You find out when the namespace loads.

The other malformed-chain errors also [fail loud](glossary.md#fail-loud-not-silent):

| Error | What you did |
|---|---|
| `:rf.error/invalid-interceptor` | `reg-interceptor` got a descriptor that isn't `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`. |
| `:rf.error/unregistered-interceptor` | A chain references an id with no registration. |
| `:rf.error/invalid-interceptor-ref` | A chain entry is neither a bare keyword nor an `[id arg]` 2-vector. |
| `:rf.error/inline-interceptor-removed` | A public chain holds an interceptor map / value / Var instead of a reference. Register it and reference it by id. |
| `:rf.error/interceptor-factory-arity` | A bracket ref targets a non-`:factory` interceptor, or the factory can't build for that arg. |
| `:rf.error/path-interceptor-bad-path` | `[:rf.interceptor/path …]` got a non-vector path. |
| `:rf.error/interceptor-override-invalid` | An `:interceptor-overrides` key or replacement isn't a valid reference. |

## When the chain throws

Every `:before` and `:after` runs inside a guard.

!!! warning "Gotcha — your `:after` must survive error paths"

    A throw in a `:before` (or in the handler) skips the remaining `:before` stages **and the handler**. But the `:after` pass always runs in full, in reverse order — every interceptor in the chain, even those whose `:before` never ran. So cleanup belongs in `:after`, and your `:after` must survive error paths: one that assumes the handler always set `[:effects :db]` will itself throw.

Errors collect on the context: the first under `:rf/interceptor-error`, all of them under `:rf/interceptor-errors`, so Xray and Story can show every one. A throw anywhere means the event installs nothing: `app-db` unchanged, no `:fx` run. The error the trace stream emits names the actual source:

- `:rf.error/handler-exception` — the event handler itself threw.
- `:rf.error/coeffect-exception` — a coeffect supplier threw during context assembly (before any `:before` ran).
- `:rf.error/interceptor-exception` — one of *your* interceptors threw; it names the interceptor in `:failing-id` and says `:before` or `:after` in `:phase`.

An `:after` that throws is recorded but does **not** stop the remaining `:after` stages, so one buggy cleanup can't block the others. [Errors](errors.md) covers the error records themselves.

??? info "Where this pattern comes from"

    Interceptors were adapted for Clojure by the [Pedestal](https://github.com/pedestal/pedestal) team, and the shape is older still — Tomcat's interceptors, Netty's channel pipeline, the J2EE intercepting-filter pattern.
