# No await: continuations are data

> **Who this is for.** You've met `:on-success` on [HTTP requests](../concepts/http.md), `:reply-to` on mutations, `:on-done` on [machines](../concepts/machines.md). At some point you asked the obvious question: *why am I naming a second event instead of just `await`ing the result?* This essay is the answer. Every async surface in re-frame2 — HTTP, [resources](../concepts/server-state.md), machines, forms — leans on this argument rather than re-making it.

**The takeaway: in re-frame2 a continuation is data, not a closure.**

## What an `await` hides

A *continuation* is "the rest of the program" — everything that should happen after an async result arrives. Every async model has continuations, so the interesting question is never *whether* you have one. It's what a continuation is *made of*.

Under `async/await`, the continuation is a **closure**. Write `const quote = await fetchQuote()` and the compiler captures the rest of your function — locals and all — as an anonymous suspended function the runtime resumes later. It's ergonomic, which is why it's everywhere. It also carries four properties you stop noticing, because every mainstream language shares them. The continuation has **no name**, so you can't ask "what is this app waiting for?" It **can't be serialized**. It **dies with the process**: reload the page and every pending `await` evaporates silently. And it **closes over the world as it was** — every captured variable is a snapshot from suspension time, not arrival time.

re-frame2 sits in a different lineage. In Elm, an effect (a description of work for the runtime to perform) goes out as a `Cmd` value and the result comes back as a `Msg` you handle. The continuation isn't a suspended stack frame. It's the event (a plain data vector you dispatch) you said the answer should become:

```clojure
:on-success [:checkout/quoted]
```

That event vector **is** the continuation. Not a pointer to one, not a registration handle for one. It is the whole thing: a value you can print, diff, store, and ship. Everything else in this essay falls out of that one move.

## The stale-world trap

Before the payoffs, the bug — because this is the part that actually bites people. The closure's fourth property isn't an inconvenience. It's a correctness trap. Here is the shape people write in their first week, adapted from real migration code:

```clojure
;; THE TRAP — do not copy.
(rf/reg-event :checkout/quote
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

The `.then` closure captured `db` (your app-db, the single state map handed to the handler), frozen at issue time. Decisions made from it are decisions about the past. And the bug is invisible in every test that doesn't race an edit against a reply, which is most of them.

> **Why this ships fine elsewhere and breaks here.** In re-frame2 this exact code fails sooner and louder: the bare `rf/dispatch` fires on a fresh stack with no [frame](../concepts/frames.md) (an isolated app instance — its own db and event queue) context and raises `:rf.error/no-frame-context`. But the loud failure isn't the real disease. The stale read is — and *that* one ships fine in frameworks that allow the bare dispatch, which is most of them.

Now the same intent with the continuation as data:

```clojure
(rf/reg-event :checkout/quote
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:checkout :status] :quoting)
     :fx [[:rf.http/managed
           {:request    {:method :post :url "/api/checkout/quote"}
            :on-success [:checkout/quoted]
            :on-failure [:checkout/quote-failed]}]]}))

(rf/reg-event :checkout/quoted
  (fn [{:keys [db]} [_ {:keys [value]}]]
    ;; This `db` is current — handed to the handler at ARRIVAL time.
    {:db (if (= (:cart/version db) (:cart-version value))
           (assoc-in db [:checkout :quote] value)
           (assoc-in db [:checkout :status] :cart-changed))}))

(rf/reg-event :checkout/quote-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:checkout :status] :error)
             (assoc-in [:checkout :error]  failure))}))
```

A closure **closes over the past; a handler receives the present.** The reply handler (the function registered to run when the answer arrives) is given the db of the moment the reply lands. So "did the cart change while we waited?" is a live comparison, not a memory. It takes no discipline on your part — there is simply no mechanism by which the old db can leak into the new decision.

## What a named continuation can do that a closure can't

Naming the continuation makes it a value, and values are governable. Five properties, each impossible for a closure:

- **Recordable.** A reply is dispatched as an ordinary event, so it lands in [the event ledger](../concepts/events-and-the-cascade.md) like everything else. An awaited value slips into the handler through the call stack, where nothing else can see it, and leaves no line in the record. A reply event leaves one: traced, replayable.
- **Inspectable.** In-flight work and its continuation are both data, so the runtime can show them to you. Every managed surface keeps a queryable registry of what's in flight. Server-state work goes further: a durable **work ledger**, literally a table of outstanding continuations. Each row carries what was started, who owns it, and the reply target it will complete. "What is this app waiting on right now?" is a query, not a hunt through invisible suspended stack frames.
- **Survivable.** The event vector is a *name*, resolved at delivery time. Hot-reload mid-flight and the reply finds the newest handler registered under that name. A closure would have resumed the stale one. And because a ledger row is plain data, it serializes: server-side rendering waits on outstanding work and ships its summary across the wire, which no captured closure could survive. (Honesty note: the *host work itself* — the socket, the timer — is never revived across a reload or restore. A late completion whose correlation no longer matches is suppressed. The continuation survives as data; the in-flight attempt fails safe.)
- **Lawful.** Re-targeting a continuation — a feature module relocating a reply onto its parent's event — is a pure data transform on the target vector. Concretely: a child feature issues work with `:on-success [:child/loaded]`, and a parent that embeds the child rewrites that target to `[:parent/child-loaded]` before the work flies. Because the target is just a vector, this is data-in, data-out — the role `Cmd.map` plays in Elm. The guarantee attached is precise: mapping the target changes *only* which event completes, never the issuance, the work identity (`:work/id`), the status classification, or the staleness checks. Wrappers compose predictably (`map(identity)` is a no-op; `map(f ∘ g)` is `map(f)` after `map(g)`). There is no hidden callback to smuggle behavior through.
- **Managed.** A value can be refused. Every managed reply carries a closed `:status` — exactly one of `:ok`, `:partial`, `:error`, `:cancelled`, or `:stale` — and the runtime checks staleness *before* delivery. A reply whose correlation was superseded (the search-box race, a navigation, a re-fired mutation) is classified `:stale` and **never dispatched to your handler at all**. Cancellation arrives as data too (`:status :cancelled`, with a `:cancel/reason`), never as a silently dropped promise. The set is closed and the same five everywhere: `:ok` is a current success; `:error` is a current failure (a structured error *map*, never a loose string); `:partial` is for protocols that can return data *and* errors in one completion (GraphQL is the motivating case — usable `:value` alongside a family error map); `:cancelled` is a deliberate, still-live cancellation; `:stale` is a completion whose moment has passed. Try writing "suppress this continuation if superseded" over a captured closure — you can't. The runtime can't see inside it.

The delivered shape is itself plain data: your carried context, then the reply map appended.

```clojure
[:article/load-replied
 {:id 42}                                   ;; context you chose to carry
 {:status       :ok
  :value        {:title "Welcome"}
  :work/id      [:rf.work/http :article/by-id 42 1]
  :completed-at 1781078400456}]
```

That's the four keys you reach for most, but the envelope carries more than this when it's useful — and every field is plain data. Alongside `:status`, `:value` (the decoded result, always spelled `:value` on a reply map, every family), `:work/id`, and `:completed-at`, a reply may carry `:error` (a structured error map on the `:error`/`:partial` paths), `:work/kind` (`:http`, `:resource`, `:mutation`, `:machine`, `:timer`, `:route`, …), `:attempt` (which transport retry produced this), `:rf.frame/id` (the frame the work belongs to), and `:started-at` / `:deadline-at` to bracket the timing. Optional fields are *omitted* when they don't apply rather than filled with `nil` sentinels — so a field's presence is itself a fact.

Even *when it finished* rides along as data (`:completed-at`). So a handler that stores a timestamp derives it from the reply — replay-faithful — instead of re-sampling a wall clock that will disagree tomorrow.

> **Gotcha — don't reach for the clock in a reply handler.** The temptation, fresh off `await`, is to write `(js/Date.now)` when you store "last updated." Don't: that's a fresh ambient read, and on replay it samples *today's* clock, not the run you're replaying. Read `(:completed-at reply)` instead. The reply already carries the causal time; it's the same durable value the framework stamps on the dispatch as the built-in `:rf/time-ms` coeffect, so the two never drift. This is the whole reason completion time is a field and not something you re-sample.

The full contract — the reply map, the closed status set, the work-id correlation, the suppression rules — is one framework-wide law, normatively at [Managed-Effects](../../../spec/Managed-Effects.md).

> **Do, observe.** Dispatch a slow request with Xray open. While it flies, the outstanding work is visible as data — its work id, its owner, its reply target. When it lands, the reply is just another event row in the ledger. Now give the request a stable `:request-id` and re-fire before the first answer arrives: the superseded completion is classified stale, the trace records the suppression, and your handler never runs.

> **For the categorically curious.** Effects *sequence but never bind*: a handler may ask for several effects in order, but never "run this effect, then feed its result into the next expression" — that would be monadic binding, the awaited-value shape. Results return as the next causal event instead. And re-targeting a reply is a functor map over the continuation slot — the role `Cmd.map` plays in Elm — obeying identity and composition: `map(identity) = identity`, `map(f ∘ g) = map(f) ∘ map(g)`.

## The honest trade

This costs you something, and pretending otherwise would be marketing. With `async/await`, three dependent steps read top-to-bottom in one function and the continuations cost zero keystrokes. Here, every continuation is named: a second event id, a second handler, the flow split across registrations that read in dispatch order rather than page order. For one request that's one extra handler. For a five-step workflow it's five, and hand-chaining them through raw events gets genuinely tedious — which is exactly the point to reach for a [state machine](../concepts/machines.md), whose job is to fold those replies into explicit states.

What you buy with the ceremony: the continuation is **on the record**. Visible to every tool watching the trace, queryable while outstanding, faithful under replay, safe under races you didn't think to test, and testable by dispatching a plain data event — no mock runtime required to "resume" anything. You name the continuation yourself. In exchange, nothing about your app's future is invisible.

> **Coming from redux-saga?** You've already accepted half this idea: saga *effects* are descriptions the middleware interprets. re-frame2 makes the *continuation* (which a saga keeps as a suspended generator — a closure that dies with the process) data too.

## One doctrine, four surfaces

You'll meet this everywhere, spelled per surface but argued once, here. The canonical target key is `:rf/reply-to` with an event-vector prefix, and the canonical carried shape is the reply map above. Every surface either accepts `:rf/reply-to` directly or exposes sugar that *lowers* to it — so what you write changes, but the envelope underneath does not:

| Surface | What you write | What lands |
|---|---|---|
| [HTTP](../concepts/http.md) | `:on-success [:article/loaded]` / `:on-failure [:article/load-error]` (or co-located `:rf/reply` — `:rf.http/managed` does not take a bare `:rf/reply-to`) | The success handler gets `{:kind :success :value …}`; failure gets `{:kind :failure :failure …}` — the same envelope in HTTP's shorter clothing (`:kind :success` *is* `:status :ok`; `:kind :failure` *is* `:status :error`). |
| [Resources](../concepts/server-state.md) | a query key — the framework owns the reply | The continuation goes in the **work ledger**; stale generations are suppressed structurally by `:work/id` + generation. |
| Mutations | `:reply-to [:favorite/replied slug]` — the spine of [form submission](../how-to/build-a-form.md) | The same reply map appended; a superseded generation never delivers. |
| [Machines](../concepts/machines.md) | `:on-done` / `:on-error` on a spawned child, `:after` for timers | The completion folds into a state transition; a reply from an actor whose owning state has already exited is dropped. |

Learn the shape once; it is the same shape everywhere. (One honest wrinkle: HTTP's public spelling is `:kind`, while resources and machines speak `:status` directly — same five outcomes, two surface vocabularies for the *one* closed set. The substrate is identical; only the public word on the slot differs.)

---

**You can now:**

- Say precisely what re-frame2 traded `await` for: the continuation became a recordable, inspectable, survivable, lawful, managed value instead of an anonymous closure.
- Spot the stale-world trap in any `.then`-closure code — and explain why a reply *handler* is immune by construction.
- Read `:on-success`, `:reply-to`, and `:on-done` as one idea wearing three surface spellings, and predict how each behaves under races, reloads, and cancellation.
