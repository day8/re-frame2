# 16 - Observability

Every event your app has ever processed went through one place, and at that one place the runtime can describe — in structured data — exactly what happened: which handler ran, what it returned, which subscriptions recomputed, which views re-rendered, how long each took. This chapter is the trace stream that carries all of it, the epoch buffer that remembers the recent past, and the reason your running re-frame2 app is the most thoroughly surveilled program you'll ever write. It's also the reason every debugging tool you'll meet in [chapter 17](17-tooling.md) is reading the same wire.

## The thing other architectures can't have

I want to start by being honest about what you give up in a normal frontend, because the contrast is the whole point.

In a typical React-shaped app, "what just happened?" is not a question with an answer. State lives in a hundred components. A click sets some `useState`, which triggers a `useEffect`, which fetches, which sets more state, which re-renders three subtrees, one of which fires *its* effect, and somewhere in there a context value changed and a fourth subtree you forgot about woke up. There is no single place you can stand and watch this go by, because there *is* no single place — the causality is smeared across the component tree, and the tools reflect that. The React DevTools can show you the tree *as it is now*. They cannot show you the *cascade* that got you here, because the cascade was never a thing; it was a hundred independent little state mutations that happened to interleave.

re-frame2 is the opposite, and it's the opposite *on purpose*. Recall the six dominoes from [chapter 04](04-events-and-the-cascade.md): an event walks through the same fixed pipeline every time — handler, effects, app-db swap, subscription recompute, view re-render. Recall from [chapter 01](01-introduction.md) the discipline that makes that possible: effects only happen at one known place, described as data before they're actioned. That discipline isn't just for cleanliness. It means a single bus can watch the *entire* runtime go past. Every dispatch, every handler return, every sub recompute, every fx, every machine transition, every error — all of it, on one wire, in emission order, as structured maps.

That wire is the **trace stream**. And once you have it, you have a thing no smeared-state architecture can offer: a complete, replayable, machine-readable account of everything the app did. Less freedom — anything can't change anything from anywhere — bought you total inspectability. That trade is the deal, and this chapter is you collecting on it.

## What a trace event is

A trace event is just a map. The runtime fires one every time something interesting happens, and "interesting" is a long, well-defined list: an event got dispatched, a handler ran, app-db changed, a subscription recomputed (or was *asked* to recompute and short-circuited because nothing changed), a view rendered, an fx fired, a state machine transitioned, an error was caught. Each one is a little immutable record describing that one moment.

The shape is deliberately boring, which is what makes it stable:

```clojure
{:operation :rf.event/dispatched   ;; what specifically happened
 :op-type   :rf.event              ;; which family it belongs to
 :time      1716800000000          ;; when (host clock, ms)
 :tags      {:rf.trace/event-id :counter/inc
             :frame :rf/default
             ...}}                  ;; the open bag of specifics
```

Two fields carry the routing. **`:op-type`** is the coarse discriminator — a small, fixed vocabulary you branch on to grab a slice of the stream: `:rf.event` for dispatches, `:rf.sub` for subscription activity, `:rf.fx` for effects, `:rf.machine` for state machines, plus the severity tiers `:error` / `:warning` / `:info`. **`:operation`** is the fine-grained identity within that slice — `:rf.event/dispatched`, `:rf.sub/run`, `:rf.machine/transition`, `:rf.error/handler-exception`. Want every error? Filter `:op-type :error`. Hunting one specific failure category? Branch further on `:operation`. The rest of the interesting stuff rides in **`:tags`**, an open map — which means new fields can be added over time without breaking any tool that's reading the old ones.

You almost never construct these by hand. The runtime emits them; your job is to *read* them, or — far more often — to let a tool read them for you. But it's worth knowing the shape, because the shape is why everything downstream works.

### One event at a time, on the emit call stack

Here's a property that surprises people: trace delivery is **synchronous**. When the runtime emits a trace event, every registered listener gets called *right then*, while the runtime is still mid-cascade, on the same call stack. There's no queue, no batch, no "we'll deliver this on the next animation frame." The event fires, the listeners run, the runtime continues.

This is great for fidelity — a listener sees events in exactly the order they happened, with no reordering or coalescing. It's also a constraint you have to respect: **a listener must be cheap.** If your callback does heavy work, you're doing that heavy work inside the app's hot path, on every event. The discipline is "grab and go" — append the event to a buffer, set a flag, increment a counter, and defer anything expensive to a timer or animation frame *you* own. The tools all follow this rule; if you ever write a raw listener yourself, follow it too.

## Registering a listener

The whole consumption API is small. You hand the runtime a key and a function:

```clojure
(rf/register-listener!
  :my-app/error-logger
  (fn [trace-event]
    (when (= :error (:op-type trace-event))
      (println (:operation trace-event)
               (-> trace-event :tags :reason)))))
```

That's a working error logger. It receives *every* trace event, ignores everything that isn't an error, and prints the ones that are. The key (`:my-app/error-logger`) identifies the listener — register again under the same key and you replace the old callback atomically, which is exactly what hot-reload tools rely on. `(rf/unregister-listener! key)` removes it.

A couple of properties that matter once you have more than one tool attached:

- **Listener order is not contract.** If three tools are registered, they each see every event, but the order in which siblings get a given event is unspecified. Don't write a listener that assumes it runs before or after another one.
- **Exceptions are isolated.** If your listener throws, the runtime catches it and keeps going. One broken tool can't take down the app or block the other listeners. (It also doesn't emit a trace event about the failure — that way lies a re-entrant trace storm.)

That second property is load-bearing for the whole tooling ecosystem: you can attach a flaky experimental devtool to your running app and the worst it can do is fail quietly.

## The cascade is the unit you actually think in

The raw stream is event-at-a-time, but that's rarely the granularity you *care* about. When you click a button, you don't think "I want to see the `:rf.event/dispatched` event, then separately the `:rf.event/db-changed` event, then the four `:rf.sub/run` events, then the three `:rf.view/render` events." You think: **"show me what that click did."** That's a *cascade* — one dispatched event and everything that fired downstream of it, all six dominoes, as one unit.

The runtime makes this groupable without you having to guess. Every trace event emitted inside a single event's cascade carries the same correlation id — `:rf.trace/dispatch-id` — buried in its tags. The dispatch event itself, the db-change, every sub recompute, every render, every error: they all share that one id. So "everything that click did" is a filter: keep the events whose `:rf.trace/dispatch-id` matches. The framework ships a pure-data helper, `rf/group-cascades`, that folds the flat stream into one tidy record per cascade, with the event vector, the handler, the fx, the sub-runs, and the renders already split into named slots. You'll basically never call it yourself — but it's why a tool can show you a clean "here's the cascade" view instead of a wall of raw events.

There's a second correlation channel for the case where one event *causes* another. When a handler's effects dispatch a child event, that child gets its *own* dispatch-id (it's a separate cascade) plus a `:rf.trace/parent-dispatch-id` pointing back at the cascade that spawned it. Walk those parent links and you get the causal *tree*: "this dispatch happened because that one did, which happened because the user clicked here." That tree is what lets a tool — or an AI — answer "what chain of events broke my app?" rather than just "what was the last thing that happened."

> The cascade is the same thing as the **epoch** you met in [chapter 04](04-events-and-the-cascade.md): one dequeued event, its full six-domino run, settled. "Cascade" is the word for it on the trace stream; "epoch" is the word for it when we talk about state-over-time. One dispatch = one cascade = one epoch. Two names, one picture.

## The epoch buffer: remembering the recent past

Synchronous delivery has a catch: if you weren't *registered as a listener* when an event fired, you missed it. There's no replay of the live stream. That's a problem for any tool that attaches *after* something interesting already happened — a REPL you opened mid-session, an AI pair-programmer you summoned because the app's already broken, a devtools panel you toggled open three clicks too late.

So alongside the live stream, each frame keeps a **ring buffer** of recent history. (Frames are the isolated app contexts from [chapter 18](18-frames.md); for a single-app page there's just the one, `:rf/default`.) The buffer's unit of retention is the *cascade*, not the individual event — one dispatched event takes one slot, regardless of whether that cascade emitted five trace events or fifty thousand. When a new cascade arrives and the buffer is full, the oldest cascade — and every trace event under it — gets evicted as a unit.

There's exactly one knob:

```clojure
(rf/configure! :trace-buffer {:cascades-retained 50})  ;; the default
```

Fifty cascades. That's "the last fifty things the app did," diagnostic detail and all. It matches how operators actually think — "show me my last 50 events" — and it has a pleasant property: a chatty subscription that recomputes a thousand times in one cascade can't flood the buffer and evict the cascade you actually care about, because the whole noisy cascade lives or dies in *one* slot. Set it to `0` to disable the buffer (live delivery still works); raise it for a longer post-mortem window.

Reading the buffer is one call, per frame:

```clojure
(rf/trace-buffer :rf/default)
;; → a vector of cascade bundles, oldest first — each one the grouped
;;   {:event :handler :fx :effects :subs :renders ...} shape, ready to render.
```

This is the bootstrap mechanism for late-attaching tools. Open a devtool now, and the first thing it does is read the buffer to populate its view with what already happened — *then* it registers a live listener to keep current. The buffer is "where did I just come from"; the live stream is "what's happening now." Together they mean a tool never has to have been watching from boot to be useful.

> **Why per-frame?** This is the per-frame model from [chapter 18](18-frames.md) extended one rung further. A frame already owns its own app-db, its own subscription cache, its own epoch history — so it owns its own trace ring too. A devtool mounted in its *own* frame (which the inspectors do) can emit a sub-recompute storm without that noise polluting the application frame's buffer. Isolation all the way down.

### Epochs as state-over-time

The trace ring remembers what the app *did*. There's a parallel surface that remembers what the app *was*: **epoch history**. Each cascade, the runtime can snapshot app-db before and after, alongside the structured `:sub-runs` / `:renders` / `:effects` projection, into one assembled **epoch record**. There's a listener API for it that mirrors the raw one:

```clojure
(rf/register-epoch-listener!
  :my-app/cascade-logger
  (fn [epoch-record]
    (println (:event-id epoch-record)
             "→" (count (:effects epoch-record)) "fx"
             "/" (count (:sub-runs epoch-record)) "sub-runs")))
```

Where the raw listener delivers each event as it fires, the epoch listener delivers one fully-assembled record per cascade, *after* it settles, with `:db-before` and `:db-after` already populated. It's the right shape for any tool that routes off "what just happened in this cascade" without wanting to re-fold the raw stream itself.

And because each record carries the *before* and *after* state, the epoch history is what makes **time-travel** possible. A tool can take a recorded epoch and call `restore-epoch` to rewind a frame's app-db to exactly the value it held at that point in history — scrub the app backwards, replay the cascade that broke, land on the precise state the bug lived in. That's not a special debug build; it's a direct consequence of state being one immutable value that the runtime snapshots on every cascade. The tools that present and drive that experience are mapped in [chapter 17](17-tooling.md), with their detailed workflows in the tool-specific docs.

## The six tools all read the same wire

Here's the punchline, and it's the reason this chapter exists as its own thing rather than as a footnote in the tooling chapter: **there is one stream, and everything reads it.**

The devtools panel that paints the cascade for you. The component playground that runs each variant in its own frame. The AI pair-programmer that attaches to your live runtime over a socket. The schema-violation timeline. The migration assistant. The log shipper forwarding to an APM. None of these tools has a private back-channel into the runtime. None of them patches the framework or instruments your handlers. They all subscribe to the *same* trace stream — `register-listener!` for the live feed, `register-epoch-listener!` for assembled cascades, `trace-buffer` to bootstrap from recent history — and because they're all reading the same structured data, they all tell *consistent* stories about it. The cascade the devtools panel shows you and the cascade the AI describes over the socket are the same cascade, because they're the same events.

This is a direct payoff of the architecture. You can build a new tool — a custom recorder, a debug overlay, a domain-specific monitor — by registering one listener, with no framework changes, because the framework already exposes everything as data. The framework owns the *shape*; tools own the *rendering*. Spec 009 (the instrumentation spec) is long and dense precisely so that every tool downstream can be short and confident.

## Then it all disappears in production

There's one more property, and it's the one that makes the whole "surveillance state" thing acceptable: **none of it ships to production.**

The entire trace surface — every emit site, the per-frame rings, the epoch history, the listener registries, the correlation machinery — is gated behind a single compile-time flag (`goog.DEBUG`, exposed as `re-frame.interop/debug-enabled?`). In a `:advanced` production build with that flag off, the Closure compiler **constant-folds the gate and dead-code-eliminates everything behind it.** The emit calls don't become no-ops at runtime; they evaporate at *compile* time. Production binaries contain zero trace code. `register-listener!` becomes a registration that nothing will ever invoke. `trace-buffer` returns an empty vector and the ring is never allocated.

This is non-negotiable and it's the right call. The fidelity that makes dev debugging magical — a structured event for *every* sub recompute — would be a bundle-size and hot-path tax you absolutely do not want shipped. So you don't ship it. The trace bus is a dev-time concern, full stop.

But "your app goes dark in production" would be a bad place to leave you, so it doesn't. Production keeps a deliberately *narrow* observability surface that survives elision — a small set of always-on substrates, separate from the trace bus, designed for exactly the production-monitoring questions you actually have:

- **An event-emit listener** fires one tight record per processed event — `{:event :event-id :frame :time :outcome :elapsed-ms}`. Enough to answer "which events are firing, how often, with what latency, and did they succeed?" — the throughput-and-latency questions an APM dashboard lives on. Not enough for causal reconstruction (the correlation ids rode the dev-only trace stream and elided with it), and that's the deliberate line.
- **An error-emit listener** fires one record per runtime error — `{:error :event :event-id :frame :time :exception :elapsed-ms}`. This is how a handler exception in production reaches Sentry or Rollbar with its frame and event-id context attached, instead of surfacing as a bare `window.onerror` with no idea what the user was doing.
- **The per-frame `:on-error` slot** ([chapter 14](14-errors.md)) rides the same always-on error substrate, so your in-app recovery policy fires in production too — and it's the recommended integration point for a hosted error monitor.
- **An opt-in Performance API channel** that, if you flip its own independent flag, emits standard User-Timing measure entries at the four hot paths (event / sub / fx / render) — readable by any `PerformanceObserver`, including your APM's. Off by default, zero cost when not opted in.

Both production listener substrates pass every record through the elision pass first, so a `:sensitive?` field is redacted and a `:large?` payload is marker-substituted *before* your listener ever sees the record — the privacy story from [chapter 23](23-privacy-and-large-things.md), applied automatically at the production wire. The shape is generic: register the two listeners, map each record to your backend's wire format, ship it over managed HTTP ([chapter 10](10-http.md)) so you get retry and abort-on-destroy for free. Datadog, Honeycomb, Sentry, your own in-house pipeline — same two substrates, different consumers.

A belt-and-braces note worth burning into muscle memory: gate your production-listener *registration* on `(not ^boolean re-frame.interop/debug-enabled?)` alongside your own config flag. It costs nothing and it catches the nasty bug class where a dev bundle gets deployed with production config baked in — instead of leaking dev-verbose data to your APM at 3am, the listener simply refuses to register and you notice the silence on the dashboard.

So: dev gets the firehose — every sub recompute, every render, time-travel, the works — because dev can afford it and you need it. Production gets two narrow, always-on, privacy-scrubbed wires for the questions that survive into the real world. The line between them is one compile-time flag, and the safe default is on the safe side of it.
