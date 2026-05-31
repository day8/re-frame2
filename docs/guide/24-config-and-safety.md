# 24 - Configuration and safety

Every knob the framework exposes — what to validate, what to trace, how deep to remember, what to elide, what to elide in production — lives in one small, deliberately-bounded set, and this chapter is the whole catalogue with the safe defaults called out and the footguns flagged. The other half of the chapter is the guardrails you *can't* turn off: the CRLF rejection, the keyword caps, the slow-loris timeout, the drain-depth ceiling. Those sit quietly between your code and a class of failure you really don't want to be the person who discovered in production.

You do not have to read this chapter to write a re-frame2 app. The defaults are correct. But the day you wonder *why* a handler stopped at a header value containing `\r\n`, or *what number* the trace ring actually holds, or *which prefix* the framework flinches at when you register a handler — this is the chapter, and it's organised so you can skim straight to the bite.

## The shape of the knobs: three buckets, one rule

The first thing to understand is that the configuration surface isn't scattered — it's *three buckets*, and the reason there are exactly three is that the **lifetime** of the thing being configured differs in three ways. Once you see the buckets, the API stops feeling like a junk drawer.

| Lifetime | Surface | Examples |
|---|---|---|
| Process-wide, value is data | `(rf/configure :key opts)` | trace-buffer depth, elision threshold, epoch history |
| Process-wide, value is a fn/component | `(rf/set-x!)` / `(rf/install-x!)` | schema validator, schema explainer, substrate adapter |
| Per-frame, lives as long as one frame | `reg-frame` / `make-frame` metadata, or `dispatch` opts | `:drain-depth`, `:on-error`, `:fx-overrides`, `:interceptors` |

The first two are global; the third is local. And there's a load-bearing rule sitting on top: **one option, one bucket.** If you ever feel the urge to configure the same thing in two places, that's the framework telling you the option is secretly doing two jobs and should be split. That constraint is the entire reason the configuration story is small enough to hold in your head — there's no "you can set this here *or* there *or* via this other thing" ambiguity to memorise.

### Bucket 1 — `configure`, for data knobs

`(rf/configure key opts)` is the one entry point for process-level data settings. The vocabulary is closed-and-additive: there are four keys today, they're never renamed, never removed, and new ones arrive only by Spec change. Four keys. That's it.

**`:epoch-history` — how far back can you rewind?**

```clojure
(rf/configure :epoch-history {:depth 50})        ;; the default
(rf/configure :epoch-history {:depth 200})       ;; deeper history; more memory
(rf/configure :epoch-history {:depth 0})         ;; disable entirely
```

Every dispatched event's full cascade is recorded as an *epoch record* — `:db-before`, `:db-after`, `:sub-runs`, `:renders`, `:effects`, `:trace-events` — into a ring buffer, and that buffer is what powers Xray's time-travel, `restore-epoch`, `reset-frame-db!`, and the Tool-Pair surface. 50 epochs comfortably covers a debug session (you almost never want to rewind further than 50 user actions). 200 suits a long stress run. 0 disables it — the right call for SSR production, where no replayer is attached and the per-cascade allocation is pure waste.

There's a `:redact-fn` build-time hook for apps that record sensitive material into `app-db` and want it scrubbed *before* the ring stores it — see [chapter 23 — Privacy and large things](23-privacy-and-large-things.md) for when you'd reach for it over schema flags. The one caveat to keep in mind: `restore-epoch` rewinds `app-db` to the recorded `:db-after`, so if your fn redacts `:db-after`, the rewind lands in the redacted state. If you use time-travel against records the fn touched, redact `:trace-events` / `:trigger-event` and leave the db snapshots alone.

`:epoch-history` is **dev-only by status.** Under `:advanced` + `goog.DEBUG=false` the recording site dead-code-eliminates and the buffer never allocates, no matter what you configured. This is a recurring theme: the dev knobs cost nothing in production because they aren't *there* in production.

**`:trace-buffer` — how many trace events sit in memory?**

```clojure
(rf/configure :trace-buffer {:depth 200})        ;; the default
(rf/configure :trace-buffer {:depth 1000})       ;; longer trace history
(rf/configure :trace-buffer {:depth 0})          ;; disable the buffer
```

This is the ring of `:rf.*/*` trace events backing your dev tooling — the same stream [chapter 16 — Observability](16-observability.md) ships off-box, the same stream re-frame2-pair-mcp reads when an agent asks "what just happened." 200 holds one complex cascade comfortably (a user action fanning out into 30+ machine transitions, an HTTP round-trip, a handful of sub-runs). Bump it when you're hunting a bug that spans multiple user actions and events are rotating out before you can read them. Set it to 0 if you've registered listeners that ship events live (they get every event regardless of the buffer; the buffer is then just wasted memory). Dev-only, same as `:epoch-history`.

**`:elision` — how big is "big enough to elide"?**

```clojure
(rf/configure :elision {:rf.size/threshold-bytes 16384})   ;; the default — 16KB
(rf/configure :elision {:rf.size/threshold-bytes 65536})   ;; tolerate bigger inline values
(rf/configure :elision {:rf.size/threshold-bytes 0})       ;; disable runtime size-detection
```

This is the *runtime auto-detection* threshold for the wire-elision walker from [chapter 23](23-privacy-and-large-things.md): a value bigger than this gets a `:rf.size/large-elided` marker even when no schema pre-declared it `:large?`. 16KB is the default because that's about where pretty-printing a value into a Datadog event starts being a bad idea. Tune up if your back-end eats larger events and you want fewer refetch round-trips. Set it to 0 and *only* schema-declared `:large?` slots elide — the right setting if you've audited every slot via schemas and want zero accidental elision. One thing that is **never** size-gated: `:sensitive?`. Secrets redact regardless of size; the threshold governs `:large?` only.

**When to tune any of this:** narrowly. The defaults are right for almost every app. You bump `:epoch-history` or `:trace-buffer` for a specific debug session, you bump `:elision` when your back-end handles bigger events, and you zero the dev-only keys in long-running SSR JVMs where dev recording is wasted allocation. If you want to tune something *not* on the list, the knob doesn't exist — and that's deliberate. The per-process surface is a small fixed set; new knobs land by Spec change, not by someone adding a flag to wallpaper over a design problem.

### Bucket 2 — `set-!` / `install-!`, for impls

A few things look like they should be `configure` keys but aren't, because the value the framework needs is a *function or component reference*, not data:

```clojure
(rf/install-adapter! reagent/adapter)            ;; the reactive substrate (ch.22)
(rf/set-schema-validator! malli.core/validate)   ;; swap the schema validator
(rf/set-schema-explainer! malli.core/explain)    ;; swap the schema explainer
```

The bang on the end is the framework telling you the surface mutates a process-level slot it'll call back into from arbitrary sites. If you use the default substrate ([chapter 22 — Adapters](22-adapters.md)) and Malli for schemas ([chapter 08 — Schemas](08-schemas.md)), you call none of these — the boot wiring is automatic. The reason these aren't folded under `configure` is precise rather than arbitrary: keyword-keyed addressing loses the type information a consumer needs to pass an actual fn reference. **`configure` is for data; `set-!` is for impls.** That asymmetry is the signal: "the framework is going to hold this reference and call it back from places you don't control."

### Bucket 3 — per-frame metadata

Anything whose lifetime is "as long as this frame exists" — `:fx-overrides` for one test fixture, `:drain-depth` tightened for one story, `:on-error` for a production frame versus an SSR frame — rides the frame's metadata, not `configure`:

```clojure
(rf/reg-frame :auth
  {:on-create  [:auth/initialise]
   :drain-depth 100
   :on-error   {:default :log
                :rf.error/drain-depth-exceeded :halt}})
```

[Chapter 18 — Frames](18-frames.md) walks the whole frame-metadata grammar. `:drain-depth` and `:on-error` are the two bucket-3 knobs the safety story leans on, and they get their own section below.

## The shape of the guardrails: the architecture forbids it

Now the other half: the things you *can't* turn off. Safety primitives in re-frame2 work the way the rest of the framework works — **the architecture forbids the dangerous thing, rather than the docs asking you politely not to do it.** Every safety primitive in this chapter has the same three-part shape:

1. a specific input or condition that, if it reached the system, would cause a class of failure (header injection, DoS-by-keyword, runaway recursion, script-execution via a clickable IDE link);
2. a check site, deep in the framework, that detects the condition;
3. a structured failure that surfaces to your `:on-error` policy (or trace listener, or error projector) so you see the bug the way you'd see any other.

The framework does not strip-and-warn. It does not silently normalise. It does not "do its best with what you gave it." It rejects, raises, and tells you exactly what was wrong. The bet — the same bet the dynamic model makes — is that surfacing the bug *at its source* is cheaper than letting it bake itself into the system's observable behaviour where you'll find it three weeks later wearing a disguise. The normative descriptions all live in [`spec/Security.md`](../../spec/Security.md); this is the guide-side tour.

## HTTP safety: three defences, all on by default

The managed-HTTP cascade from [chapter 10 — HTTP](10-http.md) carries three guardrails, and you don't write a single line of defensive code to get any of them.

**The keyword cap — `:rf.http/max-decoded-keys`.** When managed HTTP decodes a JSON response and keywordizes the keys (the default), each unique key interns a Clojure keyword — and interned keywords live for the lifetime of the process. Normally invisible; most apps see a few hundred distinct keys ever. But a compromised or hostile upstream can return an object with keys `"a000000"`, `"a000001"`, `"a000002"`, … and a long-running JVM SSR worker hitting that endpoint a million times eventually fills its keyword storage and tips over. So the framework caps unique keywordized keys per request at **10000** — comfortably above any legitimate response, comfortably below a denial of service. Trip it and the request fails as a normal reply on the `:on-failure` path:

```clojure
{:kind :rf.http/decode-failure :reason :too-many-keys :limit 10000}
```

No keyword slots get committed; the request just didn't complete. Opt up per-request with `:rf.http/max-decoded-keys 50000` for the one endpoint that legitimately wants more, or sidestep it with `:decode :text` for responses you don't need as keyword-keyed maps. The routing parser carries a *symmetric* cap on URL query keys, by the way — same accident class, same defence, because deep-links and share-links are equally caller-controlled.

**The slow-loris timeout — `:timeout-ms`, default 30000.** A slow-loris upstream never quite finishes: it sends a header, dribbles a byte, waits, dribbles another. On CLJS that's a fetch promise that never resolves; on the JVM it's a `CompletableFuture` that never completes and a connection-pool slot that never returns. Run a long-lived JVM against a compromised partner and your pool exhausts in minutes. Every managed request gets a **30-second per-attempt wall-clock timeout** that fires regardless of request state and surfaces as `{:kind :rf.http/timeout :elapsed-ms 30000}` on `:on-failure`, where your `:retry` policy sees it like any other classifier. The opt-out is deliberately visible — you write `:timeout-ms nil` or `:timeout-ms 0`, two distinct ways to say "the caller is taking responsibility for an unbounded lifetime," precisely so a reviewer reading that line knows a human signed off on it. Don't write `:timeout-ms` at all and you get 30000.

**CRLF fail-fast on response-shape fx.** This one's server-side. SSR's `:rf.server/*` fx write headers, set cookies, and issue redirects, and each value becomes a line in the HTTP response: `X-Trace-Id: abc123\r\n`. The `\r\n` is the framework's job — and a problem, because if a caller-supplied value *itself* contains `\r\n`, the response splits and an attacker injects a brand-new header. That's **header injection**, and the right defence is "refuse the input." Each `:rf.server/*` fx checks its value (per-attribute, for cookies) for `\r` or `\n` at handler time and on detection raises `:rf.error/header-invalid-value` (or `:rf.error/redirect-invalid-location` for redirects), the response is *not* written, and your `:on-error` policy takes over.

Three decisions here are worth pointing at because they're deliberate. **No strip-and-warn** — silent normalisation lets encoded variants (`%0D%0A`, double-encodings) through if a downstream decoder treats them differently, and worse, hides the bug from the dev who wrote the call site. **Per-attribute on cookies** — a cookie is a map of `:name` / `:value` / `:domain` / `:path` / `:max-age` / `:same-site`, and each rides as its own attribute, so each is checked independently; the threat is "attacker controls the user-id flowing into `:name`," not "attacker controls the whole string." And **a structural URL check on redirects** in addition to the CRLF check, so a malformed-URL input fails under the same category. The upshot for you: you spend exactly zero lines of `(try ... (catch ...))` defensive code at the call site. The fx-handler is the sanitisation site; you hand it your data and it fails-fast if you handed it something dangerous.

## Editor URIs: the scheme allowlist

Slightly different surface, same posture. When a dev tool's click-to-source affordance opens your editor at a file:line, it builds a URI like `vscode://file/path/to/foo.cljs:42:7` and hands it to the browser. Five editors are built in (`:vscode`, `:cursor`, `:windsurf`, `:zed`, `:idea`); the open-ended `{:custom "vim://open?path={path}&line={line}"}` template lets you point at anything else without waiting for an upstream PR. But a `{:custom "javascript:alert(1)"}` template would run script in your dev tab on click. So the defence is two layers, deliberately redundant:

- **Layer 1 — the three-scheme reject.** The URI builder refuses to emit anything whose leading scheme is `javascript:`, `data:`, or `vbscript:` (case-insensitive, whitespace-tolerant — `" JavaScript:..."` still trips it). On a match the builder returns `nil` and the UI's `(when uri ...)` wrapper hides the link rather than rendering a dangerous chip. This closes the script-execution attack surface — the must-have.
- **Layer 2 — the click-time positive allowlist.** Each tool layers a positive allowlist of canonical editor schemes (`vscode`, `cursor`, `zed`, `idea`, `subl`, `emacs`, `vim`, `file`, and the rest) on top. A template resolving to `http://...` would otherwise *navigate the tab* — surprising the dev who expected "this opens my IDE" — so the allowlist makes that an obvious no-op. The would-be-nice.

The two layers are independent on purpose: a tool that accidentally drops one still has the other. Both predicates live in `re-frame.source-coords.editor-uri` (`editor-uri` the builder, `allowed-uri?` the click-time gate) and are exported for reuse — use both if you write your own source-coords tool.

## Drain depth: recursive cascades fail, they don't hang

re-frame2's run-to-completion drain is the property that makes handlers easy to reason about: a dispatched event runs end-to-end before any other event is observed, *including* events that dispatch other events. A handler returning `{:fx [[:dispatch [:next-step]]]}` queues the next event into the drain's FIFO and it processes as part of the same cascade. Lovely — until you notice nothing structural stops that cascade from running forever. A handler that dispatches itself loops. Two handlers that dispatch each other ping-pong. A state-machine `:always` whose guard is somehow always true microstep-loops. Without a ceiling, any of these spins the drain indefinitely: the JS event loop blocks, the JVM thread spins, the UI freezes, the SSR request hangs.

The ceiling turns that unbounded freeze into a bounded failure. **Recursive cascades fail; they don't hang.** That's the load-bearing property, and it's why the default `:drain-depth` is **100** — two orders of magnitude above any legitimate cascade (a user action typically dispatches 1–10 events; the worst legitimate case, a complex boot, maybe 30), well below "the dev's editor froze."

```clojure
(rf/reg-frame :auth         {:drain-depth 100})    ;; the framework default, written explicitly
(rf/reg-frame :test-fixture {:drain-depth 1000})   ;; tests that deliberately fire long sagas
(rf/reg-frame :story-variant {:preset :story :drain-depth 16})  ;; fail fast in interactive demos
```

The `:test` and `:story` presets ship their own defaults (1000 and 16) for exactly those reasons. You can also override per dispatch — `(rf/dispatch [:bulk-import] {:drain-depth 500})` — and the call-site value wins.

When the cascade hits the ceiling the runtime does three things, in order, and the first one is the important one: it **restores the pre-drain `app-db` snapshot** — every partial write from events that ran before the ceiling tripped is discarded. Then it reverts any frame-local registrations the cascade made (so an aborted drain doesn't leave orphaned spawned-actor handlers attached). Then it surfaces `:rf.error/drain-depth-exceeded` through your frame's `:on-error`. The rollback is **atomic and complete** — no partial writes, no "the third event got through but the fourth didn't." This is the "events are atomic" principle from [chapter 04](04-events-and-the-cascade.md) scaled up to the cascade boundary: a handler is atomic with respect to its own effects; a cascade is atomic with respect to depth-exceeded aborts.

One honest caveat: the rollback boundary is **value-shape, not real-world side effects.** An HTTP request that already flew, a `dispatch-later` timer that already scheduled — those aren't undone. The framework can't unsend a request. What it *can* do is keep its own state consistent so your replay path has somewhere honest to start from.

`:rf.error/drain-depth-exceeded` arrives at `:on-error` like any error category ([chapter 14 — Errors](14-errors.md)). Three sensible policies: **`:log`** (emit and move on — the frame's at the pre-cascade state, the next event runs; right for unexpected cascades you want to fix, not halt over), **`:halt`** (emit and stop processing — for fixtures where any overflow is a test failure), or **a custom handler** that runs *after* the rollback, so your code starts from the pre-cascade state, not the half-applied middle. The default, with no `:on-error` registered, is `:log`.

And the footgun flag: **don't bump `:drain-depth` in production unless you've audited why your cascade is long.** A production frame routinely hitting 50+ depth is a code smell — usually a state machine ping-ponging or an unintended self-dispatch. Bumping the ceiling masks the design issue; fixing the dispatch loop is the move. The trace surface tells you your typical depth (every successful drain records it); pick a ceiling at ~5x the observed maximum. (State machines carry their *own* independent depth ceilings for `:always` and `:raise`, both defaulting to 16 with their own error categories — three independent counters, three independent ceilings, three independent abort paths, each catching the runaway-loop case at its own layer. That's the substrate detail; [chapter 12 — State machines](12-machines.md) is where the machine keys live.)

## Reserved namespaces: the one-character answer

re-frame2 reserves a single root keyword namespace for framework-owned ids: **`:rf/*`**. Every framework runtime id — events, fx, cofx, subs, app-db keys, trace operations, error categories, warnings, machine lifecycle events, route events, navigation fx, SSR advisories, MCP wire markers, *everything* — lives under `:rf/*` or one of its sub-namespaces. The linter checks this; the migration agent checks this; user code MUST NOT register handlers, fx, subs, or frames under it.

The *why* is a small lesson in API hygiene. Old re-frame used fourteen separate top-level prefixes — `:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, and so on, each Spec area picking its own — and the result was that "is this framework-owned?" became a *memorisation* question, with every reserved name in a different mental bucket and a user's `:route/checkout` free to collide with a route name they wrote half a year ago. re-frame2 collapses to **one root prefix** with hierarchical sub-namespaces under it. The answer to "is this framework-owned?" is now one character: **does it start with `:rf`?** `:rf.frame/...`, `:rf.error/...`, `:rf.http/...`, `:rf.machine/...`, `:rf.size/...`, `:rf.route/...`, `:rf.epoch/...`, `:rf.xray/...` — all framework-owned by virtue of the prefix. Pick anything outside it and the framework's stance is "your name, your problem."

The full catalogue (every `:rf.<spec-area>/*` sub-namespace, fixed-and-additive) lives at [Conventions.md §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned); the practical rules are three:

1. **User ids must not collide.** `(rf/reg-event-fx :rf/hydrate ...)` is forbidden; so is anything under any `:rf.*/` prefix. The linter flags it, the migration agent flags it, the registrar's `handler-replaced` trace fires loudly. If you genuinely must override a framework event (a test fixture replacing `:rf/hydrate` in-test), use the documented extension points — `:on-create` on a test frame, `:fx-overrides` per dispatch — not raw registration over a reserved id.
2. **Library authors pick their own top-level segment** — "library name, no slash": `:re-pressed/*`, `:re-frisk/*`, `:my-app/*`. Avoid anything `:rf`-shaped, because the framework can grow new sub-namespaces and a library claiming one would silently collide. (Two library-owned prefixes do live adjacent to the framework root by special arrangement — `:story.<...>` and `:Workspace.<...>` for the stories library — but those are canonical-when-loaded, not framework-reserved.)
3. **Trace-event `:operation` vocabulary is open.** A library registers its own trace operations under its own prefix (`:my-lib.error/something-broke`); the framework's set is closed-and-additive, yours is open, and a downstream Datadog shipper can filter by your prefix without colliding.

To check yourself: run your lint pass (the framework ships a rule that flags any registration under `:rf*`), let the migration agent's first pass flag collisions if you're coming from v1, or ask the REPL — `(filter #(re-find #"^:rf" (str %)) (rf/handler-ids))` returns everything framework-owned, and your name showing up there when you didn't intend it means you've collided.

That's the configuration surface and the safety surface, end to end. The knobs are a small fixed set with conservative defaults; the guardrails are non-negotiable and fail loudly at the source. Both follow the same framework instinct — make the safe thing the default and the dangerous thing structurally hard — which is the instinct the rest of this guide has been earning your trust in, one chapter at a time.

If you are arriving from re-frame v1, this is the point where the new surfaces stop looking like isolated features and start looking like one migration story. The architecture survived; the global assumptions did not.
