# Interceptors

Say you have three hundred [event handlers](../glossary.md#event-handler), and three cross-cutting chores that touch all of them: log every event, snapshot state for undo, validate input at the boundary. Writing each chore into each handler is nine hundred copies of code that isn't the handler's job. An **interceptor** holds a cross-cutting concern *once*, under a name, so the handler stays focused on its one job — turning [coeffects](../glossary.md#coeffect) into an [effect map](../glossary.md#effect-map). You register the interceptor once, then wrap it around any handler — or around every handler in a [frame](../glossary.md#frame) — by *referencing that name*.

This page builds toward one rule, so let's put it up front and earn it as we go:

> **Interceptors decide and decorate; effects do.**

We'll start with the simplest interceptor that does something useful, then add one idea at a time.

## A first interceptor: a logger

Here's a complete, registered interceptor. It logs each event on the way in, and prints how long the handler took on the way out:

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

You register an interceptor the same way you register an event or a sub: give it a **qualified keyword id** (`:my-app/logger`), an optional metadata map (`{:doc ...}`), and then its behaviour — a **descriptor** map carrying a `:before` function, an `:after` function, or both. `:before` runs on the way in; `:after` runs on the way out.

Each function takes one argument, `ctx` — the **context** — and returns it (possibly modified). That single value is the only channel an interceptor has: `:before` here stashes the start time on the context, and its own `:after` reads it back. (That `::started-at` key, with the double colon, is just Clojure shorthand for a keyword namespaced to the current file — a safe place to park scratch data without colliding with anyone else's keys.) No closures, no side atoms.

Three small things trip people up the first time:

- **The context is the only channel.** Each [dispatch](../glossary.md#dispatch) gets its own fresh context map, which is why the same interceptor is safe even on events that overlap in time.
- **The id is the handle.** Once registered, `:my-app/logger` *is* the interceptor everywhere — chains reference it by id, the [trace stream](../glossary.md#trace-stream) and [Xray](../glossary.md#xray) name it by id, overrides find it by id. There is no anonymous interceptor to lose track of.
- **Both slots must return the context.** ("Slot" is just a handy name for one of the two functions — the `:before` or the `:after`.) A slot that returns `nil` reads as "unchanged". That works by accident in a log-only slot — right up until you also `assoc` something and the accident becomes a heisenbug. Always end with `ctx`.

??? info "Coming from Express / Koa middleware?"

    You have most of the picture already: layers around one core action, each touching things on the way in and on the way out — Koa's "onion". Three things differ. There's no `next()` — the chain isn't control flow you thread by hand; it's a fixed vector the runtime sweeps forward then backward. What flows through isn't a mutable request/response object — it's an immutable map you return a *new version* of. And Express middleware *does* things (writes headers, ends responses); a re-frame2 interceptor *describes* things and lets the runtime do them. That last difference is the rule at the top of this page.

!!! note "Document it"

    Like every other `reg-*`, an interceptor without a `:doc` draws a one-shot dev warning (`:rf.warning/missing-doc`, once per id, [elided](../glossary.md#elide) from production). The id is how the whole toolchain refers to your interceptor; `:doc` is what it shows when it does.

## Attaching it to a handler

Registering an interceptor doesn't run it — you have to put it in a handler's **chain**. A chain is the `:interceptors` key in the event's registration map, and it carries interceptor **references**, not interceptor values:

```clojure
(rf/reg-event :cart.item/add
  {:doc          "Add an item to the cart."
   :interceptors [:my-app/logger]}        ;; a reference, not the interceptor value
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

That bare keyword `:my-app/logger` *names* the registered interceptor; the runtime resolves it at dispatch time. Dispatch `[:cart.item/add ...]` now and the console shows the trip in and the timed trip out.

This register-once-reference-everywhere split is the whole shape, and it has a quiet payoff: the chain is **plain data** — a vector of keywords you can serialize, diff, and carry in an [image](../glossary.md#image). And because the chain stores a *reference*, re-registering `:my-app/logger` with new behaviour takes effect on the very next dispatch; you don't re-register the event just because an interceptor's implementation changed.

!!! warning "Gotcha — an inline interceptor map in a chain is rejected"

    Drop an interceptor *map* straight into a public chain — `{:interceptors [{:before ...}]}` — and the runtime refuses it (`:rf.error/inline-interceptor-removed`). A chain holds references only. The fix is always the same: register the behaviour under a name, and reference that name.

## The context map: two keys

We've been reaching into `ctx` with `get-in [:coeffects :event]`. Time to look at what's actually in there. The context is one immutable map threading through the whole chain, and it has two load-bearing keys:

| Key | Holds | Filled by |
|---|---|---|
| `:coeffects` | The handler's **inputs**: `:event`, `:db`, plus each world fact the handler declared with [`:rf.cofx/requires`](effects-and-coeffects.md) — flat, under its own id | The runtime — completely, *before* the chain runs |
| `:effects` | The handler's **outputs**: the new `:db`, the `:fx` vector | The handler; then decorated by `:after` stages on the way out |

Caught mid-chain, just after the handler has run, the map looks like this:

```clojure
{:coeffects {:event      [:cart.item/add {:sku "abc-123" :qty 2}]
             :db         {:cart/items [...]}
             :rf/time-ms 1781078400123}      ;; a declared fact, delivered flat
 :effects   {:db {:cart/items [... new-item]}
             :fx [[:dispatch [:toast/show "Added"]]]}}
```

This is the same `:coeffects` / `:effects` pair you met in [effects and coeffects](effects-and-coeffects.md): coeffects are the world facts a handler reads, effects are the descriptions it writes. Interceptors live in the gap between them. So the whole mental model fits in one line:

- a **`:before`** sees only `:coeffects` — the outputs don't exist yet;
- an **`:after`** sees both `:coeffects` *and* `:effects`.

That's why our logger's `:before` could read `:event` but our undo example (later) needs `:after` to compare the before-`:db` against the after-`:db`.

??? note "Going deeper"

    The context is a *comonad*-flavoured value: every stage is a function `context -> context`, and composing the whole chain is just folding those functions over one threaded value. Because the value is immutable and each stage is total (`ctx` in, `ctx` out), the chain is a pure transformation — which is exactly what lets [replay, time-travel](../glossary.md#time-travel), and deterministic tests re-run it against recorded inputs and get the same answer every time. The runtime also stages a few framework keys (the [dispatch envelope](../glossary.md#event-envelope) among them) for generic tooling.

## The sandwich: how a chain runs

A single interceptor is a `:before`/`:after` pair wrapped around the handler. Stack three of them — `A`, `B`, `C` — around a handler `H` and the runtime makes two sweeps over one shared context:

```text
declared:  [A B C]  + handler H

sweep 1 — :before, in declaration order:
    A:before → B:before → C:before → H   (the handler runs as the last :before)
sweep 2 — :after, in REVERSE order:
    C:after → B:after → A:after
```

Two details carry weight. First, **the handler runs as the last `:before`.** The runtime wraps it as an interceptor too, so there's exactly one kind of thing to execute, all the way down. Second, **the trip out mirrors the trip in.** Whatever `B:before` set up, `B:after` tears down — and teardown happens *after* everything that ran inside the setup. Think of a sandwich: the outer slice goes on first and comes off last. Every cleanup interceptor you write leans on this symmetry.

The handler doesn't know it's wrapped, and an interceptor doesn't know what it wraps. That mutual ignorance is exactly why the pattern scales: any interceptor can decorate any handler, because they only ever talk through one shared value. They never reach into each other.

!!! note "One discipline:"

    never depend on chain position. An interceptor that only works when another one happens to wrap it has encoded an ordering as a hidden precondition — a trap for whoever reorders the chain next.

### Inputs are complete before the chain runs

Notice the table said `:coeffects` is filled "completely, *before* the chain runs." That's a deliberate guarantee. By the time the first `:before` runs, `:coeffects` is finished — `:db`, `:event`, and every fact the handler declared via [`:rf.cofx/requires`](effects-and-coeffects.md) are already delivered. Every interceptor sees the complete input.

The order for one event is:

```text
envelope finalization → context assembly → :before pass → handler → :after pass
```

Coeffect satisfaction is **context assembly**, a step that runs to completion *before* the chain. You can still *modify* an assembled `:coeffects` map inside a `:before` — that's an ordinary transformation of a finished context — but you can never witness a half-filled one.

??? info "From re-frame v1"

    In v1, handing a handler a world fact was *itself* an interceptor: coeffect injection rode the chain as a member, so an interceptor placed before it saw an incomplete `:coeffects` map. Ordering mattered, invisibly — the kind of bug that costs an afternoon. re-frame2 retires that: coeffect injection is context assembly, not a chain member, so "an early interceptor blind to a later injection" simply can't be expressed. v1's coeffect-injection rows in the interceptor vector are a hard error now; each fact moves to `:rf.cofx/requires` registration metadata. See [From re-frame v1](../25-from-re-frame-v1.md).

## The one standard interceptor: `path`

The framework ships exactly one standard interceptor, and you reference it with a second kind of reference — an `[id arg]` vector. `[:rf.interceptor/path <path-vector>]` **focuses** a handler on an [`app-db`](../glossary.md#app-db) sub-slice: on the way in it stages just that slice as the handler's `:db`; on the way out it widens the returned slice back into the full `app-db`.

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}   ;; [id arg] reference — the arg is the path
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))   ;; db here is the [:cart] slice, not the whole map
```

The handler reads and writes as if `[:cart]` were the entire world, and `path` re-widens the result for it. The bracket form is the general shape for any *parameterized* interceptor: the id names a registered factory, and the one `arg` configures it. There's no `rf/path` value constructor to import — the reference *is* the surface, which keeps every chain uniform: bare keywords and `[id arg]` vectors, all the way down.

Two edge cases are worth knowing:

- **The root path `[]` focuses the whole `app-db`.** `[:rf.interceptor/path []]` stages the entire `app-db` as `:db` and installs whatever the handler returns wholesale — handy when you want focusing-style ergonomics over the full map. Hand `path` a non-vector and you get `:rf.error/path-interceptor-bad-path`.
- **An unchanged slice stays a true no-op.** If the handler emits no `:db` effect, `path` synthesizes none.

That second point hides the real reason `path` is a *framework* interceptor and not something you'd vendor yourself:

!!! warning "Gotcha — a hand-rolled `path` defeats the no-op fast path"

    re-frame2 skips the [`app-db` commit](../glossary.md#commit) — and therefore all the downstream re-renders — when a handler returns an `app-db` that is `identical?` to the one it received. A naive `path` that does `(assoc-in original-db [:cart] returned-slice)` allocates a fresh top-level map *even when the slice didn't change*, defeating that identity check and re-rendering the world for nothing. The standard `path` knows both the original full `app-db` *and* the original slice, so when the returned slice is `identical?` to the one it staged, it re-emits the **original `app-db` object** — preserving the no-op all the way down. Getting this right by hand is fiddly; that's why there's exactly one, in the framework.

### Parameterized interceptors: the `:factory` descriptor

`path` is the standard parameterized interceptor, but the mechanism is open — you can register your own parameterized family too. So far a descriptor has been one of three shapes: `:before`, `:after`, or both. There's a fourth, `{:factory f}`. A `:factory` function receives the reference's **one** `arg` and returns one of those plain `:before`/`:after` descriptors, built for that arg:

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
(rf/reg-event :cart.item/add
  {:interceptors [[:cart/stamp-meta :cart/last-touched]]}
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items conj item)}))
```

The factory runs once per chain assembly to build the executable interceptor for that arg. Two refs to the *same* factory with *different* args (`[:cart/stamp-meta :a]` and `[:cart/stamp-meta :b]`) are two distinct chain entries, each matchable on its own in overrides — which is exactly why override matching (below) is by full reference, not by id.

??? info "From re-frame v1"

    v1's grab-bag of one-liner helper interceptors — `debug`, `trim-v`, `enrich`, `after`, `on-changes` — is gone. Each was a few lines wrapping a closure; in re-frame2 anything they did is a few lines of `reg-interceptor`, registered under a name and referenced by id. The one survivor in spirit is `path`, now the standard `[:rf.interceptor/path …]` reference.

## Two places to attach

Per-handler attachment, as above, fires for that event only — the right scope for event-specific concerns like `path` or undo tags. The second place is the [**frame**](../glossary.md#frame) — one isolated, running instance of your app, with its own `app-db`, event queue, and subscription cache (see [frames](frames.md)) — and it carries the very same references:

```clojure
(rf/reg-frame :app/main
  {:interceptors [:my-app/logger]})   ;; a reference; wraps EVERY event handled in this frame
```

Per-frame interceptors are **prepended** to each event's own chain. Frame-wide concerns sit outermost, event-specific ones inside them, the handler in the middle, and the same forward-then-reverse sweep runs across all of it. This is the answer to the three hundred handlers from the top of the page: the boring chores become two or three frame interceptors, registered once, referenced by id, touching no handler code.

??? info "From re-frame v1"

    `reg-global-interceptor` is gone — per-frame `:interceptors` is the replacement. In a multi-frame app each [frame](frames.md) stays independent, so there's no bleed across SSR requests, story variants, or test fixtures the way a single global registry would leak.

### Removing or swapping a reference: `:interceptor-overrides`

Because the id is the handle, a test can silence or swap one interceptor without unwiring anything. `:interceptor-overrides` matches a chain entry **by its exact reference** and either removes it (`nil`) or replaces it with another reference — per dispatch or per frame:

```clojure
(rf/dispatch-sync [:cart.item/add {:sku "abc-123" :qty 2}]
                  {:interceptor-overrides {:my-app/logger nil}})         ;; remove the logger for this dispatch
```

```clojure
(rf/reg-frame :story/cart
  {:interceptors          [:my-app/auth-guard]
   :interceptor-overrides {:my-app/auth-guard :story/skip-auth}})        ;; swap one reference for another
```

Matching is by the *full* reference, so a parameterized entry is named in full — `{[:rf.interceptor/path [:cart]] nil}` removes only *that* `path`, leaving a sibling `[:rf.interceptor/path [:cart :items]]` untouched. The override values are references too, never inline values, which keeps story, test, SSR, and tool override state serializable and inspectable.

When both a frame and a dispatch supply overrides, they **merge, and on any key they both touch the per-call one wins** — the frame sets the default, the dispatch gets the last word. A frame might swap your auth guard for a permissive stub by default, and one test dispatch can still re-swap it for that single call. A malformed override — a key or replacement that isn't a valid reference — is rejected loudly with `:rf.error/interceptor-override-invalid`.

## Contribute, don't perform

Here's the rule from the top of the page, made precise. The chain is part of the *step function* — the pure fold that replay, time-travel, and deterministic tests re-run against recorded inputs. So this is where discipline pays off:

!!! warning "Gotcha — work done in an interceptor body re-fires on replay"

    Don't do real work directly in an interceptor body. It re-fires on every replay, and it escapes every seam: `:fx-overrides` redirects *registered effects*, not a stray `localStorage` write buried in an `:after`. The sanctioned pattern is **contribute, don't perform** — append [effect](../glossary.md#effect) rows and let the [effect handler](../glossary.md#effect-handler) execute them.

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

So what *are* interceptors allowed to do? Two things. They **decide**: a `:before` can take the handler out of play — the schema [boundary validator](../how-to/validate-with-schemas.md) (a second framework-provided reference, attached as `[:rf.schema/at-boundary]`) does this on invalid input, marking the context so the handler becomes a no-op while every `:after` still runs. And they **decorate**: transform `:coeffects`, rewrite `[:effects :db]`, append `:fx` rows. The actual doing belongs to effect handlers. The one exemption is diagnostics — the logger's `console.log` may stay in the body, because re-executing it on replay is harmless.

!!! warning "Gotcha — a frame interceptor runs on the server too"

    A frame-wide interceptor runs on *every* event in that frame, [SSR](../../ssr/glossary.md#ssr) included. That's another reason the contribute pattern matters: the `:localstorage/set` row above is safe under SSR because it's just a `:db`-derived effect row, and the `reg-fx` handler that performs it carries `:platforms #{:client}` — so the server's fx resolver simply skips it. An interceptor that instead pokes the host *directly* in its body (a `:before` that reads `js/localStorage`) has no such fence and throws on the JVM during a server render. Keep host access in the effect handler, where the platform gate lives.

## A real interceptor: undo

The most satisfying interceptor is undo. Hand-rolled, it smears "remember the old value, push it, but only if it changed, and clear redo" across every mutating handler. The 7GUIs Circle Drawer example registers it once, under a name, and then events opt in by *referencing* it:

```clojure
;; From examples/core/seven_guis/circle_drawer/core.cljs — registered once,
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

Read it through the two-key lens. `:before` reads the *inputs* (`:coeffects`, where `:db` is the pre-handler value) and stashes the prior circles on the context. `:after` reads the *outputs* (`:effects`, where `:db` is the post-handler value — absent if nothing changed), compares, and only then pushes an undo step and clears redo. Which events are undoable is decided entirely by inclusion:

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

The drag handler mutates only the dialog's draft, so a hundred slider moves never touch `:circles` — and the continuous event opts out of undo simply by *omitting* the reference. When `:drawer/close-dialog` commits, the snapshot `undoable` took is exactly the pre-dialog state, and the whole edit collapses into one undo step, for free. Closing the loop, undo itself is an ordinary event — no interceptor needed, just state moving between stacks (redo mirrors it with the stacks swapped):

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

## Introspecting a chain

Because a chain is plain data, every introspection you get on events and subs works on interceptors too. `handler-meta` reads an interceptor's metadata and source coordinates by `(kind, id)`:

```clojure
(rf/handler-meta :interceptor :my-app/logger)
;; => {:doc "Log each event on the way in, and its timing on the way out."
;;     :ns my-app.audit :line 12 :file "..." ...}
```

And reading an *event's* metadata gives you the chain as authored — a vector of references, not resolved interceptor values:

```clojure
(rf/handler-meta :event :cart.item/add)
;; => {:doc "Add an item to the cart." :interceptors [:my-app/logger] ...}
```

The two compose: a tool reads the refs off the event, then resolves each ref's source and `:doc` via `handler-meta :interceptor`. That's exactly how [Xray](../glossary.md#xray) draws a chain with jump-to-source links on every stage. (In a real app the [trace stream](observability.md) already records every event with timings — the logger up top is the teaching shape.)

## Testing an interceptor

Both slots are pure functions `ctx → ctx`, and the context is a plain map — so an interceptor unit-tests the way [a handler does](../testing/event-handlers.md): call the function with a literal context, assert on the one it returns. Give the slots named `defn`s and reference them from the descriptor, so a test can reach them without any registry:

```clojure
(defn stamp-audit [ctx]
  (assoc-in ctx [:effects :db :audit/last-event]
            (first (get-in ctx [:coeffects :event]))))

(rf/reg-interceptor :my-app/audit
  {:doc "Stamp the triggering event's id onto the handler's :db write."}
  {:after stamp-audit})

(deftest audit-stamps-the-event-id
  (let [ctx {:coeffects {:event [:cart.item/add {:sku "a"}]}
             :effects   {:db {}}}]
    (is (= :cart.item/add
           (get-in (stamp-audit ctx) [:effects :db :audit/last-event])))))
```

Build the literal ctx from the [two-key shape above](#the-context-map-two-keys): a `:before` test supplies only `:coeffects`; an `:after` test supplies both halves — including the error-path shape where the handler never populated `[:effects :db]`, which is exactly the case [When the chain throws](#when-the-chain-throws) warns your `:after` must survive. The *wiring* — that the reference actually wraps the handler — is one notch up, the same move as [a handler's runtime check](../testing/event-handlers.md#3-when-you-want-the-runtime-a-fresh-frame-per-test): dispatch through a test frame and assert on the committed state. And when someone *else's* interceptor is in the way of the thing you're testing, [`:interceptor-overrides`](#removing-or-swapping-a-reference-interceptor-overrides) is the seam that takes it out of play for one dispatch.

## When a reference is wrong

Because a chain is just data, the runtime can check it *eagerly* — and it does. The single most common mistake, a misspelled id, dies at the earliest possible moment:

!!! warning "Gotcha"

    Register an event whose `:interceptors` names an id that nobody has registered, and `reg-event` (or `reg-frame`) throws `:rf.error/unregistered-interceptor` right there at the registration site — naming the missing id. You find out when you load the namespace, not when an unlucky user trips the chain.

A handful of sibling errors cover the other ways a reference can be malformed. They all [fail loud](../glossary.md#fail-loud-not-silent) — re-frame2 never silently drops a chain entry it can't make sense of:

| Error | What you did |
|---|---|
| `:rf.error/invalid-interceptor` | `reg-interceptor` got a descriptor that isn't `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`. |
| `:rf.error/unregistered-interceptor` | A chain references an id with no registration. |
| `:rf.error/invalid-interceptor-ref` | A chain entry is neither a bare keyword nor an `[id arg]` 2-vector. |
| `:rf.error/inline-interceptor-removed` | A public chain holds an interceptor map / value / Var instead of a reference. Register it and reference it by id. |
| `:rf.error/interceptor-factory-arity` | A bracket ref targets a non-`:factory` interceptor, or the factory can't build for that arg. |
| `:rf.error/path-interceptor-bad-path` | `[:rf.interceptor/path …]` got a non-vector path. |
| `:rf.error/interceptor-override-invalid` | An `:interceptor-overrides` key or replacement isn't a valid reference. |

These are *static* failures — the chain is wrong before any event runs. The other family of failure is a slot that runs and throws, which behaves quite differently.

## When the chain throws

Every slot runs guarded, and two rules govern how throws compose.

!!! warning "Gotcha — your `:after` must survive error paths"

    A throw in a `:before` (or in the handler) skips the remaining `:before` stages **and the handler** — nothing runs against a half-built context. But the `:after` pass always runs, in full, even after a `:before` failure, in the same reverse order. That's exactly why cleanup belongs in `:after` — and why your `:after` should be written to run on error paths, not just happy ones. An `:after` that assumes the handler always populated `[:effects :db]` will itself throw on the error path.

Errors collect on the context — the first throw under `:rf/interceptor-error`, every throw under `:rf/interceptor-errors`, so post-hoc inspection (Xray, Story) sees them all even though the trace stream emits just one. A throw anywhere means the event installs nothing: `app-db` unchanged, no `:fx` fired. That one emitted event is attributed to the **true culprit**, not just "something in the chain":

- `:rf.error/handler-exception` — the event handler itself threw.
- `:rf.error/coeffect-exception` — a coeffect supplier threw during context assembly (before any `:before` ran).
- `:rf.error/interceptor-exception` — one of *your* interceptor slots threw; it carries the failing interceptor's `:id` and a `:phase` tag that says `:before` or `:after`.

An `:after` that throws is recorded but does **not** abort the remaining `:after` stages — the runtime still drives the rest of the teardown, so one buggy cleanup can't strand the others. The error pages these feed are covered in [errors](errors.md).
