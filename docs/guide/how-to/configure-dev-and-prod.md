# Configure dev and production builds

You're about to ship, and you want to know two things: what's actually in your production bundle, and which knobs you need to touch. For that second question the answer is *almost none*, because the defaults are already correct. **This page is the pre-ship pass** — it walks through the one flag that defines production, then the small set of dev knobs and where each lives, and finishes with the guardrails that stay on no matter what you set.

We'll build it up one piece at a time: first the single flag that makes a build "production", then how to gate your *own* dev code so it disappears alongside the framework's, then the JVM/SSR variant, then the dev knobs, and finally the always-on guardrails.

> **For JavaScript developers.** If you've shipped React, you know `NODE_ENV=production` — the build where the bundler strips out dev warnings. re-frame2's flag is the same idea with a bigger reach. ClojureScript has a standard `goog.DEBUG` flag, and when it's off, the Closure compiler doesn't just *skip* re-frame2's dev surface at runtime — it dead-code-eliminates it from the bundle entirely: schema validation, the trace stream, epoch history, all gone. Zero cost, rather than a cost you've cleverly avoided.

## 1. Production is one flag

Here is the whole production story — one line in your release build:

```clojure
;; shadow-cljs.edn — the release build
{:builds
 {:app {:target :browser
        :release {:compiler-options {:closure-defines {goog.DEBUG false}}}}}}
```

That's it. Most production CLJS builds already set `goog.DEBUG false`, so re-frame2 reuses the canonical flag rather than inventing its own — odds are you have this line already.

Under `:advanced` compilation plus `goog.DEBUG=false`, the framework surfaces sort into three piles. It's worth knowing which is which before you ship.

**Elided — gone from the bundle, zero cost:**

- **Schema validation** — every `:schema` check on events (the messages your app dispatches), subscriptions (your read-side queries over state), fx, cofx, and `app-db` (your app's single state map) compiles out. Schemas stay *registered* so tooling can still introspect them; they are simply never *checked*. ([Validate with schemas](validate-with-schemas.md))
- **The trace stream** — the `:trace` listener stream and the per-frame trace rings; nothing emits, no listener ever fires.
- **Epoch history** — the per-frame time-travel ring; nothing records, so there is nothing to rewind.
- **Dev tooling attachment points** — Xray and the pair server consume the trace surface; their artefacts must not be on a release build's classpath.

**Survives — always-on by design:**

- **The event-emit and error-emit streams** — `(rf/register-listener! :events id f)` and `(rf/register-listener! :errors id f)` deliver one tight, pre-redacted record per processed event and per runtime error. This is the production observability surface; wire your APM and error monitor here, or — the normal off-box path — declare a frame `:observability` sink. ([Report errors in production](report-errors-in-production.md))
- **Every guardrail in §5.**
- **The `:rf.schema/at-boundary` interceptor** — referenced in a handler's `:interceptors` chain, it forces a schema check on untrusted ingress (an HTTP reply, a `postMessage` payload) regardless of the flag. Keep the reference on exactly those handlers; everything else stays zero-cost.

**Opt-in:** the Performance API channel rides its own independent flag — `{:closure-defines {re-frame.performance/enabled? true}}` — for event/sub/fx/render timing in production via `PerformanceObserver`. It's off by default in every build, so you turn it on only when you specifically want production timing. ([Find and fix a slow view](fix-a-slow-view.md))

> **From re-frame v1.** There is no separate tracing dependency and no `10x` preload-and-`closure-define` dance: dev builds trace by default with zero config, and production elision rides the `goog.DEBUG=false` you were already setting. The single flag does the whole job.

> **Going deeper.** Why is this *elimination* and not a runtime `if`? The dev surfaces are written as `(when ^boolean re-frame.interop/debug-enabled? …)`, and `debug-enabled?` is a `goog-define` — a constant the Closure compiler can fold. In `:advanced` mode with `goog.DEBUG=false` the predicate folds to the literal `false`, every guarded body becomes provably unreachable, and dead-code elimination removes it along with everything only *it* referenced. The "three piles" above aren't a policy the runtime enforces; they're a consequence of which code is reachable once one constant is pinned. That's why the cost isn't "small" — it's structurally *absent*, and the elision probe (`npm run test:elision`) asserts the strings simply aren't in the bundle.

## 2. Gate your own dev-only code

The framework elides its own dev surface; yours needs the same gate so it disappears alongside it. Any trace listener or debug hook you wrote should sit behind the framework's own predicate, placed as the **outermost** form:

```clojure
(when ^boolean re-frame.interop/debug-enabled?   ;; alias of goog.DEBUG
  (rf/register-listener! :trace :my-app/console-tap
    (fn [trace-event]
      (js/console.log (:operation trace-event) trace-event))))
```

In production, `debug-enabled?` is the constant `false`, so the `when` body is dead code and the whole registration disappears with everything else.

> **Gotcha — the gate must be outermost.** `(when (and something debug-enabled?) ...)` does **not** constant-fold. Closure can't rule out `something`, so it can't prove the branch is dead, and the code ships in your bundle. Keep `debug-enabled?` as the outermost test, on its own. The same applies to every dev-only call you write — a `register-epoch-listener!`, a `trace-buffer` read, a `(rf/configure! {:trace-buffer …})` — each belongs under its own `when ^boolean re-frame.interop/debug-enabled?` guard.

## 3. Shipping a JVM/SSR tier? One system property

On the JVM there's no Closure compiler, so the same gate becomes a runtime flag instead of a compile-time one. It defaults to *on* for dev parity. A production SSR or webhook process facing untrusted input should flip it off, so the trace rings and epoch history don't retain user input:

```
java -Dre-frame.debug=false -jar app.jar
```

Two spellings; pick whichever fits your deployment:

- **Java system property `re-frame.debug`** — the `-Dre-frame.debug=false` above, on the JVM command line.
- **Environment variable `RE_FRAME_DEBUG`** — set in the process environment, which is often the cleaner fit for a containerised deploy (`RE_FRAME_DEBUG=false` in the Dockerfile / orchestrator config, no command-line surgery).

The flag is read **once**, at namespace load, so set it before `re-frame.interop` loads — i.e. as a real process-level setting, not something you `System/setProperty` after the app has booted. With it off, every JVM-side dev surface drops to the same no-op floor that Closure DCE gives a `:advanced` + `goog.DEBUG=false` browser build: no trace rings, no epoch history retaining user input. The always-on event/error streams and the SSR error projector keep firing — they exist precisely for this posture.

> **Going deeper.** The browser path eliminates code; the JVM path can't (no Closure pass), so it reads `re-frame.debug` *once* into a plain `def` at namespace load and branches on that constant for the process lifetime. Reading once is deliberate: a per-call check would be a hot-path tax, and a value that can change mid-run would make "is tracing on?" ambiguous. The contract is the same on both substrates — *gated off ⇒ no retention of user input* — only the mechanism differs: DCE on CLJS, a load-time constant on the JVM ([Security.md §Production gates](../../../spec/Security.md#production-gates)).

## 4. The dev knobs: three buckets, one rule

Now the dev-side configuration. It lives in exactly three places, sorted by how long the configured thing lives, with one rule on top: **one option, one bucket.** Nothing is settable in two places, so there's never a question of where a setting "really" comes from.

| Lifetime | Surface | What lives there |
|---|---|---|
| Process-wide, value is data | `(rf/configure! {key opts})` | `:epoch-history`, `:trace-buffer`, `:elision` |
| Slot-level, value is an impl | `set-…!` / `install-…!` | schema validator/explainer, substrate adapter |
| One frame | `reg-frame` metadata / `dispatch` opts | `:drain-depth`, `:observability`, `:fx-overrides` |

A *frame*, here, is one isolated app instance — its own `app-db`, its own handlers.

### The `configure!` bucket: process-wide data

`configure!` takes a single nested map; its vocabulary is just three top-level keys, fixed-and-additive, shown here at their defaults:

```clojure
(rf/configure!
  {:epoch-history {:depth 50}                       ;; how far time-travel rewinds
   :trace-buffer  {:cascades-retained 50}           ;; cascades held for dev tools
   :elision       {:rf.size/threshold-bytes 16384}});; "too big for the wire"
```

A missing top-level key leaves that subsystem untouched, so you can pass just the one knob you want — `(rf/configure! {:trace-buffer {:cascades-retained 200}})` — or compose all three in one value. An unknown top-level key is a silent no-op, which is what lets a wrapper hand `configure!` a composed config without first filtering it.

> **One thing fails loud.** The *argument* must be a map. `(rf/configure! [:trace-buffer …])` — a vector, say, because you mistyped — doesn't quietly do nothing; it throws. The silent no-op is reserved for *unknown keys inside* a well-formed map (that's the property that lets a wrapper pass a composed config straight through); a malformed argument is a programming error and surfaces as one.

The three keys, in detail:

- **`:epoch-history`** — depth of the per-frame epoch ring that powers Xray's time travel; `:depth 0` disables it. This one is dev-only: in production the ring elides whatever you set. It carries two more opts, both for the security-conscious deployment: `:trace-events-keep` (a non-negative integer) caps how many raw `:trace-events` each epoch record retains, and `:redact-fn` (`(fn [record] …)` or `nil`, default `nil`) is a projection-side override invoked once per record *at the off-box egress boundary* — the moment an epoch is about to leave the process for a hosted post-mortem dashboard. It does **not** mutate the in-process ring, so it never affects `restore-epoch!` fidelity; it only shapes what egresses. Reach for it when the built-in classification (schema `:sensitive?`, the commit-plane effects) doesn't cover a field you need to scrub on the way out. ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md))
- **`:trace-buffer`** — how many whole *cascades* (one dispatch plus everything it fanned into) the dev trace ring retains; bump it for a bug spanning more user actions than the default 50. `:cascades-retained 0` disables retention while the surface stays live (listeners still fire; nothing is *kept*). Dev-only, same as `:epoch-history`.
- **`:elision`** — the size threshold above which a value is replaced by a `:rf.size/large-elided` marker on wire-bound surfaces. `:rf.size/threshold-bytes 0` turns off runtime size auto-detection entirely, so only values you *declared* large (or marked via a schema `:large?`) elide. This one is *not* dev-only — it shapes the always-on listener records your production monitors receive, so it matters in a release build too. ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md))

> **Looking for `:sub-cache`?** It's gone. The old `:sub-cache {:grace-period-ms N}` knob — a deferred-disposal timer for subscriptions — no longer exists: sub-cache disposal is now synchronous the instant a subscription's derefer count hits zero, so there's no grace window to tune. If you have it in an old config, drop it (it'll no-op as an unknown key, but it's dead weight).

### The `set-…!` bucket: swappable implementations

You touch the `set-…!` bucket only to replace an implementation — a non-Malli validator via `rf/set-schema-validator!`, an explainer via `rf/set-schema-explainer!`, a substrate via `rf/install-adapter!` — and on the happy path the boot wiring does both for you, so most apps never call them directly.

### The per-frame bucket: frame-lifetime overrides

The per-frame bucket rides `reg-frame` metadata (or, per-dispatch, the `dispatch` opts argument, which merges over the frame's metadata on conflict). Its keys are the frame-lifetime ones — `:drain-depth`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:initial-events`, `:on-destroy`, and the production-relevant `:observability`:

```clojure
;; A frame that ships its own error sink — survives goog.DEBUG=false,
;; because production observability is a frame-owned policy, not a dev knob.
(rf/reg-frame :my-app/main
  {:initial-events [[:my-app/boot]]
   :drain-depth    100
   :observability  {:errors [{:sink :my-app.sinks/sentry}]}})
```

An `:observability` entry names a user- or library-owned `:sink` keyword (you register the sink; the framework routes pre-redacted records to it), with an optional `:rf.egress/profile` and `:opts` map. The two collections it accepts are `:handled-events` and `:errors` — the production read of the event-emit and error-emit streams, declared once on the frame rather than wired imperatively. ([Report errors in production](report-errors-in-production.md))

Its safety-relevant knob is `:drain-depth`, which comes up next in the guardrails.

> **Why three buckets, not one config map?** The split sorts settings by the lifetime and *shape* of the thing being set: process-wide data (a number, a map) goes through `configure!`; a swappable *implementation* (a function, an adapter) goes through `set-…!`; a per-frame *override* rides that frame's metadata. Each shape has exactly one home, so reading config is never a scavenger hunt and merging two configs never produces a conflict — a property worth more than the convenience of a single grab-bag map.

> **For JavaScript developers.** No `.env` files, no `process.env` reads scattered through the app, no a-context-provider-here-a-prop-there config drift. The closest analogy is a single typed config object — except it's split by *lifetime*: process-global data, swappable services, and per-instance overrides each have their own setter, so two pieces of config can never disagree about who owns a key.

Tune narrowly, usually for one debug session. If the knob you want isn't here, it doesn't exist — new knobs arrive by spec change, not by accumulating flags. Full catalogue: [API.md §Configure keys](../../../spec/API.md#configure-keys).

## 5. The guardrails you can't turn off

These run in every build, dev and production alike. Each one rejects *loudly* with a structured `:rf.error/*` instead of silently stripping and warning, so a failure surfaces like any other bug rather than slipping past you.

- **Drain depth** (default 100, per-frame `:drain-depth`) — a runaway dispatch cascade fails atomically (full rollback to the pre-drain `app-db`, then `:rf.error/drain-depth-exceeded`) instead of freezing the tab. A cascade near the ceiling is a bug to fix, not a number to raise.
- **HTTP keyword cap** (`:rf.http/max-decoded-keys`, default 10000) — a hostile JSON reply can't intern unbounded keywords and slowly kill a long-running process; the request fails onto your `:on-failure` path.
- **Slow-loris timeout** (`:timeout-ms`, default 30000) — every managed HTTP request gets a wall-clock per-attempt timeout; opting out is deliberately loud (`:timeout-ms nil`) so a reviewer sees it.
- **CRLF fail-fast** — server-side `:rf.server/*` fx reject header, cookie, and redirect values containing `\r` or `\n` (`:rf.error/header-invalid-value`), closing header injection at the fx site.
- **Editor-URI scheme rejection** — click-to-source links refuse `javascript:` / `data:` / `vbscript:` schemes, so a custom editor template can't run script in your dev tab.
- **The `:rf/*` reserved namespace** — registering anything under an `:rf`-prefixed id is refused territory; one prefix answers "is this framework-owned?".

> **Why always-on, not dev-only?** A guardrail that only runs in development is a guardrail you've disabled for the exact users who can attack you. Each of these defends a *production* threat — a recursive dispatch DoS, a keyword-interning DoS, header injection — so each survives `goog.DEBUG=false` by design. The full threat model behind each lives in [Security.md](../../../spec/Security.md).

The elision mechanism and the production observability matrix are in [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code).

## The pre-ship checklist

1. Release build sets `{:closure-defines {goog.DEBUG false}}` (most templates already do).
2. Your own dev-only registrations sit behind `^boolean re-frame.interop/debug-enabled?`, outermost.
3. Production observability is wired on an always-on surface — a frame `:observability` sink, or the corpus-wide `:events` / `:errors` listener streams.
4. Handlers receiving untrusted payloads reference the `:rf.schema/at-boundary` interceptor in their `:interceptors` chain.
5. A JVM/SSR tier ships with `-Dre-frame.debug=false`.
6. No Xray preload or pair-server artefact on the release classpath.
