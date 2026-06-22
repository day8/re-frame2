# Interceptors

Say you have three hundred event handlers — an event handler being the pure function that takes the current state plus an incoming event and returns the next state. You also have three cross-cutting chores: log every event, snapshot state for undo, validate input at the boundary. Writing each chore into each handler is nine hundred copies of code that isn't the handler's job. An **interceptor** holds a cross-cutting concern instead, so the handler stays focused on its one job. You register it once, under a name, and then wrap it around any handler — or around every handler in a frame — by *referencing that name*.

If you've written Express or Koa middleware, you already have most of the picture: layers around one core action, each touching the request on the way in and the response on the way out — Koa's "onion". Three things differ here, and they're worth holding in mind. First, there is no `next()`. The chain isn't control flow you thread by hand; it's a fixed vector the runtime sweeps forward and then backward. Second, what flows through isn't a mutable request/response object — it's an immutable two-key map. Third, Express middleware *does* things: writes headers, ends responses. A re-frame2 interceptor *describes* things and lets the runtime do them. That third difference is the rule this page builds toward:

> **Interceptors decide and decorate; effects do.**

## The sandwich

A handler is a pure function from inputs to outputs. The current `db` (your app's state map for this frame) plus the event go in; new state plus effect descriptions come out. An interceptor wraps it from outside with a pair of functions: `:before` runs on the way in, `:after` runs on the way out. You register each interceptor once, under a name, and then a handler's chain *refers* to it by that name. Stack three interceptors `A`, `B`, `C` around a handler `H` and the runtime makes two sweeps:

```text
declared:  [A B C]  + handler H

sweep 1 — :before, in declaration order:
    A:before → B:before → C:before → H   (the handler runs as the last :before)
sweep 2 — :after, in REVERSE order:
    C:after → B:after → A:after
```

The handler doesn't know it's wrapped, and the interceptor doesn't know what it wraps. That mutual ignorance is exactly why the pattern scales: any interceptor can decorate any handler, because they only ever talk through one shared value. They never reach into each other.

Two details in the diagram carry weight. First, the handler runs as the last `:before`. The runtime wraps it as an interceptor too, so there's exactly one kind of thing to execute, all the way down. Second, the trip out mirrors the trip in. Whatever `B:before` set up, `B:after` tears down — and teardown must happen *after* everything that ran inside the setup. Think of a sandwich: the outer slice goes on first and comes off last. Every cleanup interceptor you write leans on this symmetry.

## The context map: two keys

The one shared value threading through the sandwich is the **context map** — the single immutable map each stage reads from and returns. It has two load-bearing keys:

| Key | Holds | Filled by |
|---|---|---|
| `:coeffects` | The handler's **inputs**: `:event`, `:db`, plus each world fact the handler declared with `:rf.cofx/requires` — flat, under its own id | The runtime — completely, *before* the chain runs |
| `:effects` | The handler's **outputs**: the new `:db`, the `:fx` vector | The handler; then decorated by `:after` stages on the way out |

Caught mid-pipeline, after the handler has run, the map looks like this:

```clojure
{:coeffects {:event      [:todo/add {:id #uuid "..." :title "buy milk"}]
             :db         {:todos {...}}
             :rf/time-ms 1781078400123}      ;; declared fact, delivered flat
 :effects   {:db {:todos {... new-todo}}
             :fx [[:dispatch [:toast/show "Added"]]]}}
```

This is the same pair you met in [effects and coeffects](effects-and-coeffects.md) — coeffects are the world facts a handler reads, effects are the descriptions it writes. Interceptors live in the gap between them. A `:before` sees only `:coeffects`, because the outputs don't exist yet; an `:after` sees both. That's the entire mental model. The runtime also stages a few framework keys on the context — the dispatch envelope among them — for generic tooling; the normative inventory lives in [the frames spec](../../../spec/002-Frames.md).

## Inputs are complete before the chain runs

In re-frame v1, handing a handler a world fact was *itself* an interceptor. Coeffect injection rode the chain as a member, which meant an interceptor placed before it saw an incomplete `:coeffects` map. Ordering mattered, invisibly — and that's the kind of bug that costs an afternoon.

re-frame2 removes the wart by reframing the job: **coeffect satisfaction is context assembly, not a chain member.** The pipeline for one event is:

```text
envelope finalization → context assembly → :before pass → handler → :after pass
```

By the time the first `:before` runs, `:coeffects` is finished — `:db`, `:event`, and every fact the handler declared via [`:rf.cofx/requires`](effects-and-coeffects.md) are already delivered. Every interceptor sees the complete input. "An early interceptor blind to a later injection" simply can't be expressed anymore, because injection isn't a position in the chain. You can still *modify* an assembled `:coeffects` map; that's an ordinary transformation of a finished context, not a delivery mechanism.

> **Coming from re-frame v1?** v1's coeffect-injection rows in the interceptor vector are a hard error now — each fact moves to `:rf.cofx/requires` registration metadata, per [From re-frame v1](../25-from-re-frame-v1.md).

## `reg-interceptor`, and a logger

You register an interceptor the same way you register an event or a sub: name it, then hand the runtime its behaviour. The name is a **qualified keyword id**; the behaviour is a **descriptor** map carrying `:before`, `:after`, or both:

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

Three things in those lines tend to trip people up the first time:

- **The context is the only channel.** `:before` stashes the start time under a namespaced key, and its own `:after` reads it back — no closures, no side atoms. Each dispatch gets its own context map, which is why the same interceptor is safe on overlapping events.
- **The id is the handle.** Once registered, `:my-app/logger` *is* the interceptor everywhere — chains reference it by id, trace events and Xray name it by id, overrides find it by id (below), and `(rf/handler-meta :interceptor :my-app/logger)` reads back its `:doc` and source coordinates. There is no anonymous interceptor to lose track of.
- **Both slots return the context.** A slot that returns `nil` reads as "unchanged". That works by accident in a log-only slot — right up until you also `assoc` something and the accident becomes a heisenbug. Always end with `ctx`.

Both slots are optional — a descriptor is one of `{:before f}`, `{:after f}`, or `{:before f :after f}`. (A fourth shape, `{:factory f}`, builds a *parameterized family*; the standard path interceptor below is the one you'll meet.) `reg-interceptor` captures the definition-site coordinates for you, so tools jump straight to your source when a stage throws. v1's grab-bag of one-liner helpers (`debug`, `trim-v`, `enrich`, `after`, `on-changes`) is gone; anything else is a few lines of `reg-interceptor`.

Now attach it where the event is registered — the metadata map's `:interceptors` key, **as a reference**. A chain carries interceptor *references*, never inline interceptor values: a bare keyword id names a registered interceptor.

```clojure
(rf/reg-event :cart.item/add
  {:doc          "Add an item to the cart."
   :interceptors [:my-app/logger]}        ;; a reference, not the interceptor value
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

That split — register once, reference by id everywhere — is the whole shape. The handler's chain is plain data: a vector of keywords you can serialize, diff, and carry in an image. Drop an inline interceptor map into a public chain and the runtime rejects it (`:rf.error/inline-interceptor-removed`); the fix is always to register the behaviour and reference it. And because the chain stores a *reference*, re-registering `:my-app/logger` with new behaviour takes effect on the next dispatch — you don't re-register the event just because an interceptor's implementation changed.

Dispatch `[:cart.item/add ...]` and the console shows the trip in and the timed trip out. Now open Xray and focus the event's epoch: the pipeline lists your chain by `:id` with a jump-to-source link, and the after-interceptors stage shows the way out. (In a real app the [trace wire](observability.md) already records every event with timings — this logger is the teaching shape.)

## The one standard interceptor: `path`

The framework ships exactly one standard interceptor, and you reference it with the second kind of reference — an `[id arg]` vector. `[:rf.interceptor/path <path-vector>]` **focuses** a handler on an `app-db` sub-slice: on the way in it stages just that slice as the handler's `:db`, and on the way out it widens the returned slice back into the full `app-db`.

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}   ;; [id arg] reference — the arg is the path
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))   ;; db here is the [:cart] slice, not the whole map
```

The handler reads and writes as if `[:cart]` were the entire world, and `path` re-widens the result for it. The bracket form is the general shape for any *parameterized* interceptor: the id names a registered `:factory`, and the one `arg` configures it (a factory that needs several inputs takes them as a single map or vector). There is no `rf/path` value constructor to import — the reference *is* the surface, which keeps every chain uniform: bare keywords and `[id arg]` vectors, all the way down.

## Two places to attach

Per-handler attachment, as above, fires for that event only. That's the right scope for event-specific concerns like `path` or undo tags (below). The second place is the frame — a frame being one isolated re-frame2 world, with its own `app-db` and handlers — and it carries the very same references:

```clojure
(rf/reg-frame :app/main
  {:interceptors [:my-app/logger]})   ;; a reference; wraps EVERY event handled in this frame
```

Per-frame interceptors are **prepended** to each event's own chain. Frame-wide concerns sit outermost, event-specific ones inside them, the handler in the middle, and the same forward-then-reverse sweep runs across all of it. This is the answer to the three hundred handlers: the boring chores become two or three frame interceptors, registered once, referenced by id, touching no handler code.

And because the id is the handle, a test can silence or swap one without unwiring anything. `:interceptor-overrides` matches a chain entry **by its exact reference** and either removes it (`nil`) or replaces it with another reference — per dispatch or per frame:

```clojure
(rf/dispatch-sync [:cart.item/add {:sku "abc-123" :qty 2}]
                  {:interceptor-overrides {:my-app/logger nil}})         ;; remove the logger for this dispatch
```

```clojure
(rf/reg-frame :story/cart
  {:interceptors          [:my-app/auth-guard]
   :interceptor-overrides {:my-app/auth-guard :story/skip-auth}})        ;; swap one reference for another
```

Matching is by the full reference, so a parameterized entry is named in full — `{[:rf.interceptor/path [:cart]] nil}` removes only *that* `path`, leaving a sibling `[:rf.interceptor/path [:cart :items]]` untouched. The override values are references too, never inline values, which keeps story, test, SSR, and tool override state serializable and inspectable. (Per-dispatch *additive* `:interceptors` is gone — authored behaviour has exactly two homes, event metadata and frame metadata, and per-call variation is expressed by overriding a named reference.)

> **Coming from re-frame v1?** `reg-global-interceptor` is gone — per-frame `:interceptors` is the replacement, and in a multi-frame app each [frame](frames.md) stays independent: no bleed across SSR requests, story variants, or test fixtures.

## Contribute, don't perform

Here's the rule from the top of the page, made precise. The chain is part of the *step function* — the pure fold that replay, time-travel, and deterministic tests re-run against recorded inputs. This is where it pays to be disciplined.

> **Don't do work directly in an interceptor body.** Work performed directly in an interceptor body re-fires on every replay. It also escapes every seam: `:fx-overrides` redirects *registered effects*, not a stray `localStorage` write buried in an `:after`. So the sanctioned pattern is **contribute, don't perform** — append effect rows and let the interpreter execute them.

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

(`:localstorage/set` is the app-registered effect from [effects and coeffects](effects-and-coeffects.md) — its `reg-fx` handler stays the one place that touches the host.)

So what *are* interceptors allowed to do? They *decide*: a `:before` can take the handler out of play — the schema [boundary validator](../how-to/validate-with-schemas.md) does this on invalid input, marking the context so the handler becomes a no-op while every `:after` still runs. And they *decorate*: transform `:coeffects`, rewrite `[:effects :db]`, append `:fx` rows. The actual doing belongs to effect handlers. The one exemption is diagnostics — the logger's `console.log` may stay in the body, because re-executing it on replay is harmless. One more discipline rides along: never depend on chain position. An interceptor that only works when another one wraps it has encoded an ordering as a hidden precondition, and that's a trap for whoever reorders the chain next.

## Undo, included from a real example

The most satisfying interceptor is undo. Hand-rolled, it smears "remember the old value, push it, but only if it changed, and clear redo" across every mutating handler. The 7GUIs Circle Drawer example (`examples/reagent/seven_guis/circle_drawer/core.cljs`) registers it once, under a name:

```clojure
;; From examples/reagent/seven_guis/circle_drawer/core.cljs — registered once,
;; then referenced by id from the events that deserve it.
(rf/reg-interceptor :drawer/undoable
  {:doc "Snapshot the pre-handler circles; on a real change push to :undo and clear :redo."}
  {:before (fn before [ctx]
             ;; snapshot taken from coeffects (the pre-handler db).
             (let [db    (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc-in ctx [:coeffects :prior-circles] prior)))
   :after  (fn after [ctx]
             ;; if the handler changed db, push the prior value to :undo.
             (let [prior    (get-in ctx [:coeffects :prior-circles])
                   db-after (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})
```

Read it through the two-key lens. `:before` reads the *inputs* (`:coeffects`, where `:db` is the pre-handler value) and stashes the prior circles on the context. `:after` reads the *outputs* (`:effects`, where `:db` is the post-handler value — absent if nothing changed), compares, and only then pushes an undo step and clears redo. Which events are undoable is decided entirely by inclusion: both consuming events *reference* `:drawer/undoable` in their chain, and the continuous one opts out by omission.

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

The drag handler mutates only the dialog's draft, so a hundred slider moves never touch `:circles`. When `:drawer/close-dialog` commits, the snapshot `undoable` took is exactly the pre-dialog state, and the whole edit collapses into one undo step, for free. Closing the loop, undo itself is an ordinary event — no interceptor needed, just state moving between stacks (redo mirrors it with the stacks swapped):

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

One registered interceptor, plus which chains reference it: that's the entire undo feature.

## When the chain throws

Every slot runs guarded, and two rules govern how throws compose.

> **Write your `:after` to survive error paths.** A throw in a `:before` (or in the handler) skips the remaining `:before` stages and the handler — nothing runs against a half-built context. But the `:after` pass always runs, in full, even after a `:before` failure. That's exactly why cleanup belongs in `:after`, and why your `:after` should be written to run on error paths, not just happy ones.

Errors collect on the context. A throw anywhere means the event installs nothing: `app-db` unchanged, no `:fx` fired. The error surface emits one event per chain, attributed to the true culprit — `:rf.error/interceptor-exception` carries the failing interceptor's `:id` and phase, distinct from a handler or coeffect failure. The error pages those feed are covered in [errors](errors.md); the normative chain-execution contract is in [the frames spec](../../../spec/002-Frames.md).

---

**You can now:**

- register an interceptor with `reg-interceptor` and move data through the two-key context map — `:before` reads inputs, `:after` reads inputs *and* outputs
- reference interceptors from a chain by id — a bare keyword for a static interceptor, `[:rf.interceptor/path [:x]]` for the one parameterized standard interceptor — and know that inline values are rejected
- predict any chain's order: `:before` in declaration order, handler as the last `:before`, `:after` in reverse
- rely on complete inputs: coeffect delivery is context assembly, so no interceptor ever sees a half-injected `:coeffects` map
- reference a concern from one event (`:interceptors` metadata) or a whole frame (`reg-frame :interceptors`), and remove or swap one by reference with `:interceptor-overrides` in a test
- keep interceptors replay-safe by contributing `:fx` rows instead of performing work — and ship undo as one registered interceptor plus a reference on the events that deserve it
