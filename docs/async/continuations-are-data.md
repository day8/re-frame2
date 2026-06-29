# No await: continuations are data

You've met `:on-success` on [HTTP requests](http.md), `:reply-to` on mutations, `:on-done` on [machines](../machines/concepts.md). At some point you asked the obvious question: *why am I naming a second event instead of just `await`ing the result?* This page is the answer. It's one idea — and once it clicks, every async surface in re-frame2 reads the same way.

**The takeaway, up front: in re-frame2 a continuation is data, not a closure.** The rest of the page earns that sentence. We'll first see the one move that makes it true, then see — concretely — what `await` was quietly costing you, and finally what you get to do once the continuation is a value you can hold in your hand.

## Start with the one move

Here is the [effect map](../core/glossary.md#effect-map) an [event handler](../core/glossary.md#event-handler) returns to ask for an HTTP request. (In re-frame2 a handler doesn't *do* things — it's a pure function that returns a *description* of what should happen, under the `:fx` key, and the runtime carries it out.) Watch where the *result* is supposed to go:

```clojure
{:fx [[:rf.http/managed
       {:request    {:method :get :url "/api/articles/welcome"}
        :on-success [:article/loaded]
        :on-failure [:article/load-failed]}]]}
```

That `:on-success [:article/loaded]` is the whole trick. You didn't write code to run when the answer arrives — you named an [event](../core/glossary.md#event) to dispatch when it arrives. The answer becomes an `:article/loaded` event, handled by an ordinary handler, exactly as if a user had clicked a button.

The word for "the rest of the program" — everything that should happen after an async result lands — is a **continuation**. Every async model has one; the only interesting question is what a continuation is *made of*. Under `async/await` it's a hidden closure (the suspended rest of your function). Here it's that vector — `[:article/loaded]` — a value you can print, diff, store, and ship. Everything else on this page falls out of that one move.

> **Coming from Elm?** This is the Elm architecture, and it's worth borrowing the mental model wholesale. In Elm an effect goes out as a `Cmd` value and its result comes back as a `Msg` you handle — the continuation is never a suspended stack frame, it's the message you said the answer should become. re-frame2's `:on-success [:article/loaded]` is that same `Cmd → Msg` round-trip: you describe the work, you *name* the reply, and the runtime delivers it as a fresh event into [the cascade](../core/glossary.md#event-cascade).

## What an `await` quietly hides

To see what you gained, look at what you left behind.

Under `async/await`, the continuation is a **closure**. Write `const quote = await fetchQuote()` and the compiler captures the rest of your function — locals and all — as an anonymous suspended function the runtime resumes later. It's ergonomic, which is why it's everywhere. It also carries four properties you stop noticing, because every mainstream language shares them:

- It has **no name**, so you can't ask "what is this app waiting for?"
- It **can't be serialized** — there's no way to turn a suspended stack frame into bytes.
- It **dies with the process** — reload the page and every pending `await` evaporates silently.
- It **closes over the world as it was** — every captured variable is a snapshot from suspension time, not arrival time.

That event vector — `[:article/loaded]` — has none of those properties. It *has* a name (it *is* a name). It serializes. It survives a reload. And, as we're about to see, it never reads a stale world.

> **From re-frame v1.** re-frame v1 already pointed this direction — effectful handlers returned `{:dispatch [:some-event]}` rather than calling code inline, and the original `:http-xhrio` effect took `:on-success` / `:on-failure` event vectors. v2 keeps that spelling and makes it the *whole framework's* spine: every [managed](../resources/glossary.md#managed-http) async surface — HTTP, [resources](../resources/concepts.md), mutations, [machines](../machines/concepts.md) — addresses its reply by a named event, and the result arrives as a single [uniform reply](../core/glossary.md#the-uniform-reply) — one [reply map](../resources/glossary.md#reply-map), every surface. The bare `(rf/dispatch …)` inside a `.then` callback that v1 tolerated now fails loud (see the trap below) — there's a sanctioned managed effect for every async job.

## The bug this kills: the stale-world trap

The fourth closure property — "closes over the world as it was" — isn't an inconvenience. It's a correctness trap, and it's the part that actually bites people. Here's the shape someone writes in their first week, adapted from real migration code:

```clojure
;; THE TRAP — do not copy.
(rf/reg-event :article/load
  ;; `db` is destructured out of the handler's first argument: it's app-db,
  ;; the single state map — the whole app's state — handed to the handler.
  (fn [{:keys [db]} _]
    (-> (js/fetch "/api/articles/welcome")        ;; reach for the browser's fetch directly
        (.then #(.json %))
        (.then (fn [article]
                 ;; `db` here is the db from when the request was ISSUED.
                 ;; If the user navigated to a different article while the
                 ;; request flew, this guard checks a world that no longer exists.
                 (when (= (:article/viewing db) (aget article "slug"))
                   (rf/dispatch [:article/loaded article])))))
    {}))                                          ;; return no effects — we did the work by hand
```

The `.then` closure captured `db` — your [app-db](../core/glossary.md#app-db) — frozen at issue time. Any decision made from it is a decision about the past. And the bug is invisible in every test that doesn't race a navigation against a reply, which is most of them.

Now the same intent, with the continuation as data. The request handler just *names* where the answer goes; two more handlers receive it:

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles/welcome"}
            :on-success [:article/loaded]
            :on-failure [:article/load-failed]}]]}))

(rf/reg-event :article/loaded
  ;; The event vector that arrives is [:article/loaded <reply-map>]. The handler's
  ;; second argument is that vector; `[_ {:keys [value]}]` ignores the event id (`_`)
  ;; and destructures `:value` — the decoded result — out of the reply map.
  (fn [{:keys [db]} [_ {:keys [value]}]]
    ;; This `db` is current — handed to the handler at ARRIVAL time.
    {:db (if (= (:article/viewing db) (:slug value))
           (assoc-in db [:article :data] value)
           (assoc-in db [:article :status] :navigated-away))}))

(rf/reg-event :article/load-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  failure))}))
```

A closure **closes over the past; a handler receives the present.** The reply handler — the ordinary [event handler](../core/glossary.md#event-handler) registered to run when the answer arrives — is given the `db` of the moment the reply lands. So "did the user navigate away while we waited?" is a *live* comparison, not a memory. It takes no discipline on your part: there is simply no mechanism by which the old `db` can leak into the new decision.

> **Gotcha — why the trap fails *louder* here.** In re-frame2 the trap above doesn't even reach the stale read: the bare `rf/dispatch` inside the `.then` fires on a fresh stack with no [frame](../core/glossary.md#frame) in scope (a frame is an isolated app instance — its own db and event queue) and raises a `:rf.error/no-frame-context` [error record](../core/glossary.md#error-record). Useful, but don't mistake the loud failure for the disease. The *stale read* is the disease — and that one ships fine in frameworks that allow the bare dispatch, which is most of them. The data-continuation form is immune by construction, not by vigilance. (Need to dispatch from a callback on purpose — a `setTimeout`, a WebSocket message? Capture a [capture-frame](../core/glossary.md#capture-frame) while the frame is in scope and dispatch through that.)

## What a named continuation can do that a closure can't

Naming the continuation makes it a value, and values are governable. Five properties follow, each impossible for a closure:

- **Recordable.** A reply is dispatched as an ordinary event, so it lands in [the event ledger](../core/concepts/events-and-the-cascade.md) like everything else — traced, replayable. An awaited value slips into a handler through the call stack, where nothing else can see it, and leaves no line in the record. A reply event leaves one.
- **Inspectable.** In-flight work and its continuation are both data, so the runtime can show them to you. Every managed surface keeps a queryable registry of what's in flight; server-state work goes further with a durable **work ledger** — literally a table of outstanding continuations, each row carrying what was started, who owns it, and the reply target it will complete. "What is this app waiting on right now?" is a query, not a hunt through invisible suspended stack frames.
- **Survivable.** The event vector is a *name*, resolved at delivery time. Hot-reload mid-flight and the reply finds the newest handler registered under that name — a closure would have resumed the stale one. And because a ledger row is plain data, it serializes: server-side rendering can wait on outstanding work and ship its summary across the wire, which no captured closure could survive. (Honest footnote: the *host work itself* — the socket, the timer — is never revived across a reload or restore. A late completion whose correlation no longer matches is suppressed. The continuation survives as data; the in-flight attempt fails safe.)
- **Lawful.** Re-targeting a continuation — a feature module relocating a reply onto its parent's event — is a pure data transform on the target vector. Picture a reusable child feature that issues work with `:on-success [:child/loaded]`; a parent embedding the child rewrites that target to `[:parent/child-loaded]` before the work flies, so the parent hears about it. Because the target is just a vector, this is data-in, data-out, and the guarantee is precise: mapping the target changes *only* which event completes — never the issuance, the work identity (`:work/id`), the status classification, or the staleness checks. There is no hidden callback to smuggle behaviour through.
- **Managed.** A value can be refused. Every managed reply carries a closed `:status` — exactly one of [`:ok` / `:partial` / `:error` / `:cancelled` / `:stale`, defined once on the status table at its home](http.md#one-envelope-under-every-async-surface). The leverage that buys *this* page's argument: the runtime checks staleness *before* delivery, so a reply whose correlation was superseded (the search-box race, a navigation, a re-fired mutation) is classified `:stale` and **never dispatched to your handler at all** — and a cancellation arrives as `:cancelled` data, never as a silently dropped promise. Try writing "suppress this continuation if superseded" over a captured closure — you can't. The runtime can't see inside it.

> **Gotcha — silencing a reply on purpose stays honest.** You *can* decline the continuation: `:on-success nil` / `:on-failure nil` is fire-and-forget, the right shape for a telemetry beacon you genuinely don't care about. But silence is the one place this model could quietly regrow the bug it kills — a dropped failure is an error nobody sees. So the runtime keeps it honest: the first time a *real* (non-aborted) failure — a transport error, a 5xx, a timeout, a decode failure — is dropped by `:on-failure nil`, it emits a one-shot dev-only `:rf.warning/failure-swallowed` trace, naming the silence rather than letting it vanish. (A *cancelled* request that no longer wants its reply is correct-by-design silence, not a swallowed error, so an `:rf.http/aborted` failure is excluded.) The warning is informational — there's no `:rf.error/*` here — but it means even your deliberate silences leave a line in the record.

### The reply that lands

The delivered event is plain data — your carried context, then the [reply map](../resources/glossary.md#reply-map) appended — and its field-by-field shape (`:status`, `:value`, `:work/id`, `:completed-at`, the optional `:error` / `:work/kind` / `:attempt` / timing keys) is catalogued at its home, [managed HTTP's envelope section](http.md#one-envelope-under-every-async-surface). One field matters to *this* argument: **completion time rides along as data** (`:completed-at`). So a handler that stores a timestamp derives it from the reply — replay-faithful — instead of re-sampling a wall clock that will disagree tomorrow.

> **Gotcha — don't reach for the clock in a reply handler.** The temptation, fresh off `await`, is to write `(js/Date.now)` when you store "last updated." Don't: that's a fresh ambient read, and on replay it samples *today's* clock, not the run you're replaying. Read `(:completed-at reply)` instead — the same durable value the framework stamps on the dispatch as the built-in `:rf/time-ms` [coeffect](../core/glossary.md#coeffect), so the two never drift. This is the whole reason completion time is a field and not something you re-sample.

> **Going deeper — the shape of the algebra.** Effects *sequence but never bind*: a handler may ask for several effects in order, but never "run this effect, then feed its result into the next expression" — that would be monadic *binding*, the awaited-value shape. Results return as the next causal event instead, so the structure is closer to an applicative / free-effects pipeline than a monad. And re-targeting a reply is a **functor map** over the continuation slot — the role `Cmd.map` plays in Elm — obeying the functor laws: `map(identity) = identity` and `map(f ∘ g) = map(f) ∘ map(g)`. That's why the lawful guarantee above is exactly "changes only which event completes": a lawful functor map can't touch anything but the mapped slot. The full contract — the reply map, the closed status set, the work-id correlation, the suppression rules — is one framework-wide law at [Managed-Effects](../../spec/Managed-Effects.md).

> **Do, observe.** Dispatch a slow request with [Xray](../core/how-to/debug-with-xray.md) open. While it flies, the outstanding work is visible as data — its work id, its owner, its reply target. When it lands, the reply is just another event row in the ledger. Now give the request a stable `:request-id` and re-fire before the first answer arrives: the superseded completion is classified stale, the trace records the suppression, and your handler never runs.

## The honest trade

This costs you something, and pretending otherwise would be marketing. With `async/await`, three dependent steps read top-to-bottom in one function and the continuations cost zero keystrokes. Here, every continuation is named: a second event id, a second handler, the flow split across registrations that read in *dispatch* order rather than *page* order. For one request that's one extra handler. For a five-step workflow it's five — and hand-chaining them through raw events gets genuinely tedious, which is exactly the point to reach for a [state machine](../machines/concepts.md), whose job is to fold those replies into explicit states.

What you buy with the ceremony: the continuation is **on the record**. Visible to every tool watching the trace, queryable while outstanding, faithful under replay, safe under races you didn't think to test, and testable by dispatching a plain data event — no mock runtime required to "resume" anything. You name the continuation yourself; in exchange, nothing about your app's future is invisible.

> **Coming from redux-saga?** You've already accepted half this idea: saga *effects* are descriptions the middleware interprets, not direct calls. re-frame2 makes the *continuation* data too — the thing a saga keeps as a suspended generator (a closure that dies with the process) becomes a named event vector that doesn't. So you keep saga's "effects are data" win and lose its "the rest of the flow lives in an un-serializable, un-inspectable generator" cost.

## One doctrine, four surfaces

You'll meet this idea everywhere, spelled per surface but argued once, here. The canonical target key is `:rf/reply-to` with an event-vector prefix, and the canonical carried shape is the [reply map](../resources/glossary.md#reply-map). Every surface either accepts `:rf/reply-to` directly or exposes sugar that *lowers* to it — so what you *write* changes, but the envelope underneath does not:

| Surface | What you write | What lands |
|---|---|---|
| [HTTP](http.md) | `:on-success [:article/loaded]` / `:on-failure [:article/load-error]` (or the co-located `:rf/reply` sentinel — `:rf.http/managed` takes the sugar, not a bare `:rf/reply-to`) | The success handler gets `{:kind :success :value …}`; failure gets `{:kind :failure :failure …}` — the same envelope in HTTP's shorter clothing (`:kind :success` *is* `:status :ok`; `:kind :failure` *is* `:status :error`). |
| [Resources](../resources/concepts.md) | a `:scope` + `:params` read key — the framework owns the reply | The continuation goes in the **work ledger**; stale generations are suppressed structurally by `:work/id` + generation. |
| Mutations | `:reply-to [:favorite/replied slug]` — the spine of [form submission](../core/how-to/build-a-form.md) | The same reply map appended; a superseded generation never delivers. |
| [Machines](../machines/concepts.md) | `:on-done` / `:on-error` on a spawned child, `:after` for timers | The completion folds into a state transition; a reply from an actor whose owning state has already exited is dropped. |

Learn the shape once; it is the same shape everywhere. (One honest wrinkle, the same one the [glossary](../core/glossary.md#the-uniform-reply) flags: HTTP's public spelling is `:kind`, while resources and machines speak `:status` directly — same five outcomes, two surface vocabularies for the *one* closed set. The substrate is identical; only the public word on the slot differs.)
