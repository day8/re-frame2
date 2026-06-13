# No await: continuations are data

> **Who this is for.** You've met `:on-success` on [HTTP requests](../concepts/http.md), `:reply-to` on mutations, `:on-done` on [machines](../concepts/machines.md) — and somewhere along the way you asked the obvious question: *why am I naming a second event instead of just `await`ing the result?* This essay is the answer. Every async surface in re-frame2 — HTTP, [resources](../concepts/server-state.md), machines, forms — leans on this argument rather than re-making it.

**The takeaway: in re-frame2 a continuation is data, not a closure.**

## What an `await` hides

A *continuation* is "the rest of the program" — everything that should happen after an async result arrives. Every async model has continuations; the models differ only in what a continuation *is made of*.

Under `async/await`, the continuation is a **closure**. Write `const quote = await fetchQuote()` and the compiler captures the rest of your function — locals and all — as an anonymous suspended function for the runtime to resume later. Wonderfully ergonomic, with four properties you stop noticing because every mainstream language shares them: the continuation has **no name** (you can't ask "what is this app waiting for?"), it **can't be serialized**, it **dies with the process** (reload the page and every pending `await` evaporates silently), and it **closes over the world as it was** — every captured variable is a snapshot from suspension time, not arrival time.

re-frame2 sits in a different lineage — Elm's, where an effect goes out as a `Cmd` value and the result comes back as a `Msg` you handle. The continuation isn't a suspended stack frame; it's the event you said the answer should become:

```clojure
:on-success [:checkout/quoted]
```

That event vector **is** the continuation. Not a pointer to one, not a registration handle for one — it is the whole thing, a value you can print, diff, store, and ship. Everything else in this essay falls out of that one move.

## The stale-world trap

Before the payoffs, the bug — because the closure's fourth property isn't an inconvenience, it's a correctness trap. Here is the shape people write in their first week, adapted from real migration code:

```clojure
;; THE TRAP — do not copy.
(rf/reg-event-fx :checkout/quote
  (fn [{:keys [db]} _]
    (-> (js/fetch "/api/checkout/quote")
        (.then #(.json %))
        (.then (fn [quote]
                 ;; `db` here is the db from when the request was ISSUED.
                 ;; If the user edited the cart while the request flew,
                 ;; this guard checks a world that no longer exists.
                 (when (= (:cart/version db) (aget quote "cart-version"))
                   (rf/dispatch [:checkout/quoted quote])))))
    {}))
```

The `.then` closure captured `db` — frozen at issue time. Decisions made from it are decisions about the past, and the bug is invisible in every test that doesn't race an edit against a reply. (In re-frame2 this code fails sooner and louder than that: the bare `rf/dispatch` fires on a fresh stack with no [frame](../concepts/frames.md) context and raises `:rf.error/no-frame-context` — but the deeper disease is the stale read, and that one ships fine in frameworks that allow it.)

Now the same intent with the continuation as data:

```clojure
(rf/reg-event-fx :checkout/quote
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:checkout :status] :quoting)
     :fx [[:rf.http/managed
           {:request    {:method :post :url "/api/checkout/quote"}
            :on-success [:checkout/quoted]
            :on-failure [:checkout/quote-failed]}]]}))

(rf/reg-event-db :checkout/quoted
  (fn [db [_ {:keys [value]}]]
    ;; This `db` is current — handed to the handler at ARRIVAL time.
    (if (= (:cart/version db) (:cart-version value))
      (assoc-in db [:checkout :quote] value)
      (assoc-in db [:checkout :status] :cart-changed))))

(rf/reg-event-db :checkout/quote-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:checkout :status] :error)
        (assoc-in [:checkout :error]  failure))))
```

A closure **closes over the past; a handler receives the present.** The reply handler is given the db of the moment the reply arrives, so "did the cart change while we waited?" is a live comparison, not a memory. No discipline required — there is simply no mechanism by which the old db can leak into the new decision.

## What a named continuation can do that a closure can't

Naming the continuation makes it a value, and values are governable. Five properties, each impossible for a closure:

- **Recordable.** A reply is dispatched as an ordinary event, so it lands in [the event ledger](../concepts/events-and-the-cascade.md) like everything else. An awaited value slips into the handler through the call stack — a place nothing else can see — leaving no line in the record. A reply event leaves one: traced, replayable.
- **Inspectable.** Because in-flight work and its continuation are both data, the runtime can show them to you: every managed surface keeps a queryable registry of what's in flight, and server-state work goes further — a durable **work ledger**, literally a table of outstanding continuations, each row carrying what was started, who owns it, and the reply target it will complete. "What is this app waiting on right now?" is a query, not a debugging séance over invisible suspended stack frames.
- **Survivable.** The event vector is a *name*, resolved at delivery time. Hot-reload mid-flight and the reply finds the newest handler registered under that name — a closure would have resumed the stale one. And because a ledger row is plain data, it serializes: server-side rendering waits on outstanding work and ships its summary across the wire, which no captured closure could survive. (Honesty note: the *host work itself* — the socket, the timer — is never revived across a reload or restore; a late completion whose correlation no longer matches is suppressed. The continuation survives as data; the in-flight attempt fails safe.)
- **Lawful.** Re-targeting a continuation — a feature module relocating a reply onto its parent's event — is a pure data transform on the target vector, with a guarantee attached: mapping the target changes *only* which event completes, never the issuance, the work identity, the status classification, or the staleness checks. Wrappers compose predictably; there is no hidden callback to smuggle behavior through.
- **Managed.** A value can be refused. Every managed reply carries a closed `:status` — `:ok`, `:partial`, `:error`, `:cancelled`, or `:stale` — and the runtime checks staleness *before* delivery: a reply whose correlation was superseded (the search-box race, a navigation, a re-fired mutation) is classified `:stale` and **never dispatched to your handler at all**. Cancellation arrives as data too (`:status :cancelled`, with a reason), never as a silently dropped promise. Try writing "suppress this continuation if superseded" over a captured closure — you can't; the runtime can't see inside it.

The delivered shape is itself plain data — your carried context, then the reply map appended:

```clojure
[:article/load-replied
 {:id 42}                                   ;; context you chose to carry
 {:status       :ok
  :value        {:title "Welcome"}
  :work/id      [:rf.work/http :article/by-id 42 1]
  :completed-at 1781078400456}]
```

Even *when it finished* rides along as data (`:completed-at`), so a handler that stores a timestamp derives it from the reply — replay-faithful — instead of re-sampling a wall clock that will disagree tomorrow. The full contract — the reply map, the closed status set, the suppression rules — is one framework-wide law, normatively at [Managed-Effects](../../../spec/Managed-Effects.md).

> **Do, observe.** Dispatch a slow request with Xray open. While it flies, the outstanding work is visible as data — its work id, its owner, its reply target. When it lands, the reply is just another event row in the ledger. Now give the request a stable `:request-id` and re-fire before the first answer arrives: the superseded completion is classified stale, the trace records the suppression, and your handler never runs.

<details markdown="1">
<summary>For the categorically curious</summary>

Effects *sequence but never bind*: a handler may ask for several effects in order, but never "run this effect, then feed its result into the next expression" — that would be monadic binding, the awaited-value shape. Results return as the next causal event instead. And re-targeting a reply is a functor map over the continuation slot — the role `Cmd.map` plays in Elm — obeying identity and composition: `map(identity) = identity`, `map(f ∘ g) = map(f) ∘ map(g)`.
</details>

## The honest trade

This costs you something, and pretending otherwise would be marketing. With `async/await`, three dependent steps read top-to-bottom in one function and the continuations cost zero keystrokes. Here, every continuation is named: a second event id, a second handler, the flow split across registrations that read in dispatch order rather than page order. For one request that's one extra handler; for a five-step workflow it's five, and hand-chaining them through raw events gets genuinely tedious — which is exactly the point to reach for a [state machine](../concepts/machines.md), whose job is to fold those replies into explicit states.

What you buy with the ceremony: the continuation is **on the record**. Visible to every tool watching the trace, queryable while outstanding, faithful under replay, safe under races you didn't think to test, and testable by dispatching a plain data event — no mock runtime required to "resume" anything. You name the continuation yourself; in exchange, nothing about your app's future is invisible.

> **Coming from redux-saga?** You've already accepted half this idea — saga *effects* are descriptions the middleware interprets; re-frame2 makes the *continuation* (which a saga keeps as a suspended generator, a closure that dies with the process) data too.

## One doctrine, four surfaces

You'll meet this everywhere, spelled per surface but argued once, here. [HTTP](../concepts/http.md)'s `:on-success` / `:on-failure` and co-located `:rf/reply` are public sugar over the one envelope. [Resources](../concepts/server-state.md) put the continuation in the work ledger and suppress stale generations. A mutation's `:reply-to` — the spine of [form submission](../how-to/build-a-form.md) — appends the same reply map and never delivers a stale one. [Machines](../concepts/machines.md) fold completions (`:on-done`, `:on-error`, `:after` timers) into states the same way. Learn the shape once; it is the same shape everywhere.

---

**You can now:**

- Say precisely what re-frame2 traded `await` for: the continuation became a recordable, inspectable, survivable, lawful, managed value instead of an anonymous closure.
- Spot the stale-world trap in any `.then`-closure code — and explain why a reply *handler* is immune by construction.
- Read `:on-success`, `:reply-to`, and `:on-done` as one idea wearing three surface spellings, and predict how each behaves under races, reloads, and cancellation.

**Next:** [HTTP: the managed request](../concepts/http.md) — the envelope's guided tour on the wire · [State machines](../concepts/machines.md) — folding many replies into explicit states.
