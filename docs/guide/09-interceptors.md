# 09 — Interceptors

Every event handler in your app should do the same three boring things around the interesting part: log what came in, snapshot state in case you need to undo it, check the input is valid. Three hundred handlers, three boring things each, and you do *not* want to write that nine hundred times. This chapter is how you write each one once, as a thing you can wrap around any handler, and never copy-paste a cross-cutting concern again.

You don't strictly need it yet. Every counter, every form, every HTTP request you've seen works without you writing a single interceptor — the framework wires the necessary ones in silently and you never notice. But the day you want to *wrap* a handler — capture every event for a test recorder, snapshot `app-db` for undo, validate input on the way in, time the handler on the way out — interceptors are the surface, and the surface is small. There's one primitive, one shape, and one rule for how they compose. Once you see the sandwich, the rest is mechanics.

## The sandwich

Start with what a handler is. A handler does exactly one thing: it takes the current state plus an event and produces a new state plus some effects. That's the meat — the filling — the interesting part you actually came to write.

An **interceptor** wraps that handler from the outside. It's a pair of functions: a `:before` that runs *on the way in*, and an `:after` that runs *on the way out*. Picture two slices of bread, one above the meat and one below:

```
                ┌──────────────────────┐
        :before │      interceptor      │ :after
   ─────────────┤   ┌──────────────┐   ├───────────►
                │   │   handler    │   │
                │   └──────────────┘   │
                └──────────────────────┘
```

The handler doesn't know the interceptor is there. The interceptor doesn't know which handler is in the middle. That mutual ignorance is the whole reason this works: the interceptor can decorate *any* handler with a cross-cutting concern, and the handler can stay a pristine pure function, because neither one reaches into the other. They only ever talk through one shared value.

Stack two interceptors around the same handler and you get a sandwich of a sandwich: each one's `:before` runs before the next, each one's `:after` runs after the next. Three, four, however many — the pattern doesn't change. Each interceptor adds one layer going in and one layer coming out.

If this feels familiar, it should. It's the same pattern Pedestal uses for HTTP middleware, and the one most Ring middleware ends up rediscovering by hand. re-frame inherited it from there, and it survived for a precise reason: it's the *smallest* construct that lets a handler stay pure while still being decorated with the concerns that, in a less disciplined architecture, would smear themselves across every handler in the app. The boring three things from the hook — log, snapshot, validate — are exactly the concerns that want to be everywhere and belong nowhere. The sandwich is where they go.

## The one shared value: the context map

The interceptor and the handler communicate through a single Clojure map, threaded through the whole sandwich, called the **context map**. It has two load-bearing keys, and you've already met both of them under other names:

| Key | What's in it | Who fills it |
|---|---|---|
| `:coeffects` | The handler's **inputs**: the event vector, the current `app-db`, plus any cofx values you injected (current time, a fresh uuid, a `localStorage` read…). | Cofx interceptors, on the way in. |
| `:effects` | The handler's **outputs**: the new `:db`, the `:fx` vector. | The handler itself, then modified by `:after` interceptors on the way out. |

This is the same `:coeffects` / `:effects` pair from [chapter 07](07-effects-and-coeffects.md) — coeffects are what the handler reads, effects are what it writes. Interceptors live in the gap between them. A typical context map, caught mid-pipeline, looks like this:

```clojure
{:coeffects {:event [:cart.item/add {:sku "abc-123" :qty 2}]
             :db    {:cart {:items [...]} :auth {...}}
             :now   1747008000}                       ;; injected by an inject-cofx interceptor
 :effects   {:db    {:cart {:items [... new-item]} :auth {...}}
             :fx    [[:rf.http/managed {...}]]}}
```

An interceptor's `:before` runs before the handler, so it sees only `:coeffects` — the outputs don't exist yet. Its `:after` runs after the handler, so it sees both — `:effects` is now filled in. That's the entire mental model: `:before` reads inputs, `:after` reads inputs *and* outputs, and an interceptor's whole power is what it chooses to do in that gap.

You'll occasionally glimpse two more keys — `:rf/skip-handler?` (set when a validation check wants to abort the handler) and `:rf/interceptor-error` (set when a slot throws). Those are framework-internal; the chain runtime owns them, and your interceptors mostly won't touch them.

> **A note for re-frame v1 refugees.** v1's context map carried `:queue` (interceptors still to run) and `:stack` (interceptors that had run). v2's runtime executes the chain as a straight forward sweep followed by a reverse sweep over a fixed vector — there's no in-flight queue to consult, no stack to inspect. Each interceptor is still a plain inspectable map; the runtime just doesn't expose its scheduling state on the context anymore. Cleaner, fewer hidden levers. If you wrote a v1 interceptor that read `:queue` or popped `:stack`, it needs rewriting — the migration agent flags it.

## `->interceptor`: the only primitive

There's one constructor. It builds the map. That's the whole API:

```clojure
(rf/->interceptor
  :id     :my-app/logger    ;; required if you want override-by-id
  :before (fn [ctx] ...)    ;; optional — runs on the way in
  :after  (fn [ctx] ...))   ;; optional — runs on the way out
```

Both `:before` and `:after` receive the context map and return a (possibly modified) context map. If a slot returns `nil`, the runtime reads it as "return the context unchanged" — so a `:before` that exists purely to side-effect, a log line say, doesn't strictly need a trailing `ctx`. (Though you'll see in a moment why returning it anyway is the safe habit.)

`:id` is conventionally a namespaced keyword, and it earns its keep two ways: trace events name your interceptor by its id, and per-frame `:interceptor-overrides` substitute by id (a test frame can do `{:interceptor-overrides {:my-app/logger nil}}` to silence the logger just for that frame). An anonymous interceptor with no `:id` works fine but can't be overridden and can't be found by tooling — so give it one unless you have a reason not to.

The whole construct is data. You can `pprint` it. You can compose it. You can stash interceptors in a registry and look them up. v1 shipped a fistful of one-shape helpers — `debug`, `trim-v`, `enrich`, `after`, `on-changes` — that each wrapped `->interceptor` for one specific pattern; v2 drops them on principle. The principle: keep helpers that do non-trivial work (`path`, `unwrap`, `inject-cofx`); drop the ones that are just `(->interceptor :before f)` wearing a different name. Custom `:before` / `:after` work is three lines of `->interceptor` directly — there's nothing to abstract.

## The one rule: forward in order, backward in reverse

The chain runs in two sweeps, and exactly one detail in it is load-bearing, so I'll telegraph it: **`:before` runs in declaration order; the handler runs in the middle; `:after` runs in *reverse* declaration order.**

Three interceptors `A`, `B`, `C` wrapping a handler `H`:

```
   declared:   [ A  B  C  H ]

   sweep 1 (:before, in order):
                A:before
                B:before
                C:before
                H:before  ←  the handler runs as the last :before
   sweep 2 (:after, in reverse):
                C:after
                B:after
                A:after
```

Two things in that picture deserve a second look. First, **the handler runs as the last `:before`.** That's not a quirk — it's how v2 implements "handler" uniformly. The handler is itself wrapped as an interceptor, with its `:before` slot doing the actual work, so the runtime has exactly one kind of thing to run: interceptors, all the way down. No special case.

Second, **why reverse on the way out?** Because each `:after` is the dual of its `:before`. If `B:before` set something up on the context, `B:after` is the natural place to tear it down — and tearing down should happen *after* everything that ran inside the setup. `B` was outside `C` on the way in (its `:before` ran first), so `B` should be outside `C` on the way out (its `:after` runs last). It's a real sandwich: the outer slice goes on first and comes off last. The `path` and `unwrap` helpers both lean on exactly this symmetry — stash on the way in, restore on the way out — and so will any cleanup interceptor you write. If you only remember one thing from this chapter, remember that the trip out mirrors the trip in.

## A logger, written from scratch

The simplest interceptor that does something genuinely useful is a logger. It records the event on the way in and the elapsed time on the way out — the first of the three boring things from the hook, written exactly once:

```clojure
(def logger
  (rf/->interceptor
    :id     :my-app/logger
    :before (fn [ctx]
              (let [event (get-in ctx [:coeffects :event])]
                (js/console.log "→" (pr-str event))
                (assoc ctx ::logger-start (js/performance.now))))
    :after  (fn [ctx]
              (let [event   (get-in ctx [:coeffects :event])
                    elapsed (- (js/performance.now) (::logger-start ctx))]
                (js/console.log "←" (pr-str event) (str elapsed "ms"))
                ctx))))
```

Three things to read here, because they're the three things that bite first-timers:

**The context is the only channel.** The `:before` stashes the start time under a namespaced key, `::logger-start`; the `:after` reads it back from that same key. No closures, no thread-locals, no atom on the side — every invocation has its own context map, and the only way `:before` talks to its own `:after` is by leaving something on the context for it to find. This is also *why* it's safe to run the same interceptor on overlapping dispatches: there's no shared mutable state to corrupt.

**The `:id` is namespaced for a reason.** `:my-app/logger` is the handle a test frame uses to do `{:interceptor-overrides {:my-app/logger nil}}` and silence this thing for that frame, or swap in a different one — a recorder that writes to an atom instead of the console. Anonymous interceptors can't be reached this way.

**Both slots return the context.** Even the `:after`, which only really wants to `console.log`, ends in `ctx`. This is the most common interceptor bug there is: forget the trailing `ctx`, the slot returns `nil`, the runtime reads that as "no change" — which works *by accident* in a slot that only side-effects, right up until you also `assoc` something on the way in, at which point the accident becomes a heisenbug. Always return the context. Make it muscle memory.

Wiring it on is one vector in the positional middle slot of the registration:

```clojure
(rf/reg-event-db :counter/inc
  [logger]                       ;; ← the interceptors slot
  (fn [db _] (update db :count inc)))
```

Fire `:counter/inc` and you get the inbound log, the handler runs, the outbound log with timing. The handler has no idea any of that happened.

## An event recorder, for tests

This is the pattern [chapter 13](13-testing.md) uses to assert on what a sequence of dispatches actually fired. An atom collects every event; afterward the test reads it:

```clojure
(def recorded (atom []))

(def event-recorder
  (rf/->interceptor
    :id :test/event-recorder
    :before (fn [ctx]
              (swap! recorded conj (-> ctx :coeffects :event))
              ctx)))
```

No `:after` — there's nothing to do on the way out. The `:before` reaches into `:coeffects` for the event vector, appends it, returns the context untouched. The interesting move is *where* you attach it. Instead of bolting it onto one event, attach it to a whole **frame**:

```clojure
(rf/reg-frame :test/recorder-frame
  {:interceptors [event-recorder]})       ;; prepended to every event in the frame
```

Now every event handled in `:test/recorder-frame` gets the recorder prepended to its chain. The test runs its dispatches; `@recorded` is the list of everything that fired. No mocks, no global state to scrub between tests, no per-handler "should I capture this one?" — the frame-level attachment makes "see every event" a single line. (More on per-handler vs per-frame in a moment.)

## An undo interceptor, for real

The most satisfying interceptor is undo, because it's the one that, in a typical app, would be a sprawling tangle of "remember the old value, push it on a stack, but only if it changed, and clear the redo stack" copy-pasted into every mutating action. As an interceptor it's a single self-contained thing you tag onto the events that should be reversible. This is the shape the Circle Drawer in `examples/reagent/seven_guis/circle_drawer/` uses:

```clojure
(def undoable
  (rf/->interceptor
    :id     :undoable
    :before (fn [ctx]
              ;; Snapshot the pre-handler value from :coeffects.
              (let [db    (get-in ctx [:coeffects :db])
                    prior (get-in db [:drawer :circles])]
                (assoc-in ctx [:coeffects :prior-circles] prior)))
    :after  (fn [ctx]
              ;; If the handler actually changed it, push prior onto the undo stack.
              (let [prior    (get-in ctx [:coeffects :prior-circles])
                    db-after (get-in ctx [:effects :db])]
                (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                  (-> ctx
                      (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                      (assoc-in  [:effects :db :drawer :redo] []))
                  ctx)))))
```

Read it through the input/output lens and it's clean. The `:before` reads `:coeffects` — the *inputs*, where `:db` is the pre-handler value — and stashes the prior circles. The `:after` reads `:effects` — the *outputs*, where `:db` is the post-handler value, or absent entirely if the handler changed nothing — compares the two, and only pushes onto the undo stack if something actually moved. The interceptor lives in the gap between input and output and uses both halves of the context, which is the gap's entire purpose.

Wiring decides which events are undoable — no registry, no opt-in macro, no metadata flag, just whether `[undoable]` is in the chain:

```clojure
(rf/reg-event-db :drawer/add-circle
  [undoable]
  (fn [db [_ x y]]
    (update-in db [:drawer :circles] conj
               {:id (random-uuid) :x x :y y :radius 30})))

;; A continuous drag opts OUT — it shouldn't pollute undo history with every pixel.
(rf/reg-event-db :drawer/dialog-drag
  (fn [db [_ new-radius]]
    (assoc-in db [:drawer :dialog :draft-radius] new-radius)))
```

Same handlers as you'd write anyway; the `[undoable]` slot is the entire undo feature, applied surgically to exactly the events that deserve it.

## Per-handler vs per-frame: two places to attach

You've now seen interceptors attached two ways, and the distinction is worth making explicit because it's new in v2 and it's the thing that scales the pattern up.

**Per-handler interceptors** go in the positional middle slot of `reg-event-*`, and fire *only for that event*:

```clojure
(rf/reg-event-db :cart.item/add
  {:doc "Add an item to the cart."}                       ;; optional reflection metadata
  [undoable rf/validate-at-boundary-interceptor]          ;; ← the interceptors slot
  (fn [db [_ item]] (update db :items conj item)))
```

Use per-handler when the concern is event-specific — `undoable` only on the reversible events, `path` to focus a handler on a sub-slice, `unwrap` to flatten one event's payload.

**Per-frame interceptors** go on the frame's `:interceptors` key, and fire for *every event handled in that frame*:

```clojure
(rf/reg-frame :app/main
  {:interceptors [logger app-db-validator]})              ;; prepended to every event in this frame
```

Use per-frame when the concern is cross-cutting — a logger that should see every event, a validator that should run after every state change, a recorder for a story or test fixture. *This is the answer to the hook:* the three boring things every handler does become two or three per-frame interceptors, written once, that wrap every handler in the frame automatically. You don't touch the three hundred handlers at all.

The per-frame chain is **prepended** to the per-handler chain. An event with three per-handler interceptors, handled in a frame with two per-frame interceptors, runs a five-deep sandwich — frame ones outermost, handler ones inside them, the wrapped handler in the middle — with the same forward-then-reverse sweep as always. Frame-wide concerns wrap event-specific concerns wrap the handler. That nesting is exactly the order you want: the logger sees the event before the validator before the undo snapshot, and they unwind in reverse on the way out.

> **What replaced v1's `reg-global-interceptor`.** v1 had a single process-global interceptor list that fired for every event in every frame. v2 doesn't ship it (the migration agent flags `reg-global-interceptor` and points here). Per-frame is the replacement, and it's strictly better: for a single-frame app it's the same convenience (attach to `:rf/default`), but in a multi-frame app each frame's `:interceptors` is independent — no bleed across SSR requests, across story variants, across test fixtures. Cross-*frame* observation — audit logging, tracing — goes through the trace bus ([chapter 16](16-observability.md)), not through interceptors.

## What an interceptor may do, and what it must not

The three framework helpers re-frame2 keeps — `path`, `unwrap`, `inject-cofx` — are a good catalogue of the legitimate moves.

**Add a coeffect.** [`inject-cofx`](07-effects-and-coeffects.md) is the canonical shape: a `:before` that runs a registered cofx fn and merges its result into `:coeffects`. The handler then reads the new value from its coeffects map. (Remember `reg-event-db` only sees `db` and the event vector, not injected cofx values — use `reg-event-fx` for any handler that needs them. [Chapter 07](07-effects-and-coeffects.md) is the cofx deep-dive; this just locates it inside the interceptor model.)

**Modify an effect.** An `:after` that walks `[:effects :db]` and transforms it lets you write handler-agnostic state-shape policy — the `undoable` example above is exactly this, an `:after` that conditionally writes to `[:effects :db :drawer :undo]` after every change.

**Short-circuit the handler.** Set `:rf/skip-handler?` truthy on the context from a `:before` and the handler-interceptor becomes a no-op. The downstream `:after` stages still run — they get their chance to clean up. The schema-validation interceptor uses this on a failure; a custom auth gate could do the same.

**Add a follow-up dispatch.** An `:after` can `update-in [:effects :fx]` to append a `[:dispatch ...]` row, queued behind whatever the handler itself returned. This is how the auth guard in the [routing chapter](19-routing.md) redirects unauthorised navigations — it rewrites the event coeffect to point at the login route instead.

And the two things an interceptor must *not* do:

**Don't perform side-effects directly.** An interceptor that writes to `js/localStorage` is just an effect handler in disguise — register it with `reg-fx` instead. You get a clearer trace, cleaner tests, and the override-by-id surface. Interceptors decide and decorate; effects *do*. Keep the line crisp.

**Don't depend on chain position.** A well-behaved interceptor works whether it's first or last in the chain. If yours only works when wrapped by another specific interceptor, you've encoded an ordering as a hidden precondition, and that's the kind of fragility that detonates the day someone reorders the vector.

## When the chain throws

Each `:before` and `:after` runs inside a try/catch. If a slot throws, the exception is captured on the context under `:rf/interceptor-error`, tagged with the failing interceptor's `:id` and the phase. Crucially, **the chain keeps running** — the remaining stages still get the error-bearing context, so they get a chance to clean up. After the chain completes, the runtime checks for `:rf/interceptor-error` and emits `:rf.error/handler-exception` naming the *interceptor's* id (not the event's). [Chapter 14 — Errors](14-errors.md) walks the full recovery semantics — a `:before` throw aborts the handler, an `:after` throw halts the cascade so the effects don't fire.

The takeaway for here: write defensively. The context your `:after` receives may already carry an error from a prior stage, so if your `:after` is releasing a resource or undoing a setup, check for the error and clean up anyway. A cleanup that only runs on the happy path isn't a cleanup.

## Three rules and a warning

Nearly everything reduces to three rules:

1. **`:before` runs in declaration order.** The first interceptor in the vector sees the context first.
2. **`:after` runs in reverse declaration order.** The first interceptor sees the *final* context last — symmetric with the trip in.
3. **Per-frame interceptors prepend per-handler interceptors.** Frame-wide concerns wrap event-specific ones, which wrap the handler.

If you find yourself reaching for a fourth rule, stop — you're probably overthinking it. Interceptors are deliberately small. The discipline isn't in the interceptor; it's in what you choose to make one *for*. The good candidates are exactly the boring, repeated, cross-cutting things — the log, the snapshot, the validate from the very first sentence of this chapter. Write each one once, wrap it around three hundred handlers, and never think about it again. That's the whole job, and it's smaller than it looked.
