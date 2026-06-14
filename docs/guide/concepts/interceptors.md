# Interceptors

Say you have three hundred event handlers — an event handler being the pure function that takes the current state plus an incoming event and returns the next state. You also have three cross-cutting chores: log every event, snapshot state for undo, validate input at the boundary. Writing each chore into each handler is nine hundred copies of code that isn't the handler's job. An **interceptor** holds a cross-cutting concern instead, so the handler stays focused on its one job. You write it once, as a plain value, and wrap it around any handler — or around every handler in a frame.

If you've written Express or Koa middleware, you already have most of the picture: layers around one core action, each touching the request on the way in and the response on the way out — Koa's "onion". Three things differ here, and they're worth holding in mind. First, there is no `next()`. The chain isn't control flow you thread by hand; it's a fixed vector the runtime sweeps forward and then backward. Second, what flows through isn't a mutable request/response object — it's an immutable two-key map. Third, Express middleware *does* things: writes headers, ends responses. A re-frame2 interceptor *describes* things and lets the runtime do them. That third difference is the rule this page builds toward:

> **Interceptors decide and decorate; effects do.**

## The sandwich

A handler is a pure function from inputs to outputs. The current `db` (your app's state map for this frame) plus the event go in; new state plus effect descriptions come out. An interceptor wraps it from outside with a pair of functions: `:before` runs on the way in, `:after` runs on the way out. Stack three interceptors `A`, `B`, `C` around a handler `H` and the runtime makes two sweeps:

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

## `->interceptor`, and a logger

One constructor builds interceptors:

```clojure
(def logger
  (rf/->interceptor
    :id     :my-app/logger
    :before (fn [ctx]
              (let [event (get-in ctx [:coeffects :event])]
                (js/console.log "→" (pr-str event))
                (assoc ctx ::started-at (js/performance.now))))
    :after  (fn [ctx]
              (let [event   (get-in ctx [:coeffects :event])
                    elapsed (- (js/performance.now) (::started-at ctx))]
                (js/console.log "←" (pr-str event) (str elapsed "ms"))
                ctx))))
```

Three things in those twelve lines tend to trip people up the first time:

- **The context is the only channel.** `:before` stashes the start time under a namespaced key, and its own `:after` reads it back — no closures, no side atoms. Each dispatch gets its own context map, which is why the same interceptor is safe on overlapping events.
- **The `:id` earns its keep.** Trace events and Xray name your interceptor by id, and overrides find it by id (below). An anonymous interceptor works, but you can't silence it, swap it, or find it.
- **Both slots return the context.** A slot that returns `nil` reads as "unchanged". That works by accident in a log-only slot — right up until you also `assoc` something and the accident becomes a heisenbug. Always end with `ctx`.

Both slots are optional. An interceptor is just a map carrying `:id`, `:before`, `:after`. What `->interceptor` adds over a hand-rolled map is definition-site coordinates, so tools jump to your source when a stage throws. Beyond the constructor, the shipped helpers are `rf/path` (focus a handler on an `app-db` sub-slice — `:before` narrows `:db`, `:after` splices the result back) and `rf/unwrap-interceptor` (replace `:event` with its payload map on the way in). v1's grab-bag of one-liner helpers (`debug`, `trim-v`, `enrich`, `after`, `on-changes`) is gone; anything else is three lines of `->interceptor`.

Attach it where the event is registered: the metadata map's `:interceptors` key. The historical positional vector has been removed, so interceptor chains always live alongside the rest of the registration metadata:

```clojure
(rf/reg-event :cart.item/add
  {:doc          "Add an item to the cart."
   :interceptors [logger]}
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

Dispatch `[:cart.item/add ...]` and the console shows the trip in and the timed trip out. Now open Xray and focus the event's epoch: the pipeline lists your chain by `:id` with a jump-to-source link, and the after-interceptors stage shows the way out. (In a real app the [trace wire](observability.md) already records every event with timings — this logger is the teaching shape.)

## Two places to attach

Per-handler attachment, as above, fires for that event only. That's the right scope for event-specific concerns like `path` or undo tags (below). The second place is the frame — a frame being one isolated re-frame2 world, with its own `app-db` and handlers:

```clojure
(rf/reg-frame :app/main
  {:interceptors [logger]})   ;; wraps EVERY event handled in this frame
```

Per-frame interceptors are **prepended** to each event's own chain. Frame-wide concerns sit outermost, event-specific ones inside them, the handler in the middle, and the same forward-then-reverse sweep runs across all of it. This is the answer to the three hundred handlers: the boring chores become two or three frame interceptors, written once, touching no handler code.

And because the `:id` is the handle, a test can silence or swap one without unwiring anything — per dispatch or per frame:

```clojure
(rf/dispatch-sync [:cart.item/add {:sku "abc-123" :qty 2}]
                  {:interceptor-overrides {:my-app/logger nil}})
```

> **Coming from re-frame v1?** `reg-global-interceptor` is gone — per-frame `:interceptors` is the replacement, and in a multi-frame app each [frame](frames.md) stays independent: no bleed across SSR requests, story variants, or test fixtures.

## Contribute, don't perform

Here's the rule from the top of the page, made precise. The chain is part of the *step function* — the pure fold that replay, time-travel, and deterministic tests re-run against recorded inputs. This is where it pays to be disciplined.

!!! warning "Don't do work directly in an interceptor body"

    Work performed directly in an interceptor body re-fires on every replay. It also escapes every seam: `:fx-overrides` redirects *registered effects*, not a stray `localStorage` write buried in an `:after`. So the sanctioned pattern is **contribute, don't perform** — append effect rows and let the interpreter execute them.

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

The most satisfying interceptor is undo. Hand-rolled, it smears "remember the old value, push it, but only if it changed, and clear redo" across every mutating handler. The 7GUIs Circle Drawer example (`examples/reagent/seven_guis/circle_drawer/core.cljs`) ships it as one value:

```clojure
;; From examples/reagent/seven_guis/circle_drawer/core.cljs — written as a
;; bare map, a reminder that an interceptor IS plain data. In your own code
;; prefer ->interceptor for the source-coords tooling reads.
(def undoable
  {:id    :undoable
   :before (fn before [ctx]
             ;; snapshot taken from coeffects (the pre-handler db).
             (let [db   (get-in ctx [:coeffects :db])
                   prior (get-in db [:drawer :circles])]
               (assoc-in ctx [:coeffects :prior-circles] prior)))
   :after  (fn after [ctx]
             ;; if the handler changed db, push the prior value to :undo.
             (let [prior     (get-in ctx [:coeffects :prior-circles])
                   db-after  (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})
```

Read it through the two-key lens. `:before` reads the *inputs* (`:coeffects`, where `:db` is the pre-handler value) and stashes the prior circles on the context. `:after` reads the *outputs* (`:effects`, where `:db` is the post-handler value — absent if nothing changed), compares, and only then pushes an undo step and clears redo. Which events are undoable is decided entirely by inclusion: both consuming events tag the chain, and the continuous one opts out by omission.

```clojure
(rf/reg-event :drawer/add-circle
  {:doc "Click on canvas — add a circle of default radius."
   :interceptors [undoable]}
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
   :interceptors [undoable]}
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

One interceptor, plus which chains include it: that's the entire undo feature.

## When the chain throws

Every slot runs guarded, and two rules govern how throws compose.

!!! warning "Write your `:after` to survive error paths"

    A throw in a `:before` (or in the handler) skips the remaining `:before` stages and the handler — nothing runs against a half-built context. But the `:after` pass always runs, in full, even after a `:before` failure. That's exactly why cleanup belongs in `:after`, and why your `:after` should be written to run on error paths, not just happy ones.

Errors collect on the context. A throw anywhere means the event installs nothing: `app-db` unchanged, no `:fx` fired. The error surface emits one event per chain, attributed to the true culprit — `:rf.error/interceptor-exception` carries the failing interceptor's `:id` and phase, distinct from a handler or coeffect failure. The error pages those feed are covered in [errors](errors.md); the normative chain-execution contract is in [the frames spec](../../../spec/002-Frames.md).

---

**You can now:**

- build an interceptor with `->interceptor` and move data through the two-key context map — `:before` reads inputs, `:after` reads inputs *and* outputs
- predict any chain's order: `:before` in declaration order, handler as the last `:before`, `:after` in reverse
- rely on complete inputs: coeffect delivery is context assembly, so no interceptor ever sees a half-injected `:coeffects` map
- attach a concern to one event (`:interceptors` metadata) or a whole frame (`reg-frame :interceptors`), and silence one by `:id` in a test
- keep interceptors replay-safe by contributing `:fx` rows instead of performing work — and ship undo as one value plus a tag on the events that deserve it
