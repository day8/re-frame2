# Configure dev and production builds

Before you ship, you need to know what ends up in the production bundle and which settings to change. The defaults are correct, so the answer to the second is almost none.

??? info "Coming from React?"

    re-frame2's production flag plays the role of `NODE_ENV=production`, with a bigger reach. ClojureScript production builds go through the Closure compiler in `:advanced` mode, which deletes code it can prove never runs (dead-code elimination, or DCE). With ClojureScript's standard `goog.DEBUG` flag set to `false`, Closure [elides](../glossary.md#elide) re-frame2's dev surface from the bundle: dev-time schema checks, the trace stream, and epoch history are all absent rather than skipped at runtime.

## 1. Production is one flag

One setting in your release build:

```clojure
;; shadow-cljs.edn — the release build
{:builds
 {:app {:target :browser
        :release {:compiler-options {:closure-defines {goog.DEBUG false}}}}}}
```

Most production ClojureScript builds already set `goog.DEBUG false`, and re-frame2 reuses that flag rather than adding its own.

Under an `:advanced` build with `goog.DEBUG=false`, the framework's surfaces sort into three groups.

**Elided (absent from the bundle):**

- **The schema checks you declared over your own code:** the `:schema` on an [event](../glossary.md#event), a [subscription](../glossary.md#subscription)'s return, an [fx](../glossary.md#effect)'s args, an *ambient* [cofx](../glossary.md#coeffect), a flow's output, and every `reg-app-schema` path on [app-db](../glossary.md#app-db). Schemas stay registered so tools can read them; they are never checked. The same `:schema` can survive when the framework reads it at a different checkpoint, as `:boundary? true` does below ([Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays)).
- **The trace stream:** the `:trace` listener stream and the per-frame trace rings. Nothing emits and no [listener](../glossary.md#listener) fires.
- **Epoch history:** the per-frame [time-travel](../glossary.md#time-travel) ring. Nothing is recorded.
- **Dev tooling:** [Xray](../glossary.md#xray) and the pair server read the trace surface; keep their artefacts off a release build's classpath.

**Kept in every build:**

- **The handled-event and error streams:** one record per processed event and one per [error record](../glossary.md#error-record). This is the production observability surface. A frame declares `:handled-events` / `:errors` entries under `:observability` with an egress profile, you register the sink function with `rf/register-observability-sink!`, and each record arrives already projected under that frame's classification. To cover every frame, and records whose frame does not resolve, declare the entries once with `(rf/configure! {:observability …})` ([Report errors in production](report-errors-in-production.md)).
- **Every guardrail in [section 5](#5-the-guardrails-you-cant-turn-off).**
- **A handler registered `:boundary? true`.** Its own `:schema` is checked on untrusted input (an HTTP reply, a `postMessage` payload) in every build, before any interceptor runs. In dev it changes nothing, since the check already runs. `:interceptor-overrides` cannot remove it, and registering it without a `:schema` throws `:rf.error/at-boundary-missing-schema`. A production rejection produces one `:rf.error/schema-validation-failure` record (`:source :boundary`) on the error stream and a `:rejected` status on that dispatch's handled-event record. The production record carries identifiers only, never the payload ([Validate with schemas](validate-with-schemas.md#in-production-what-goes-what-stays)).
- **Checks the framework relies on to keep its own promises:** a *recordable* [cofx](../glossary.md#coeffect)'s `:schema` (which throws rather than record a value a replay would rebuild corrupt state from), a declared route's shape (checked whenever the schemas artefact is present), a managed-HTTP `:decode` schema (part of parsing the response, not a diagnostic over it), and the arguments of the reserved `:rf.server/*` effects. Most of these read a schema you wrote; what decides survival is what the check is for, not who wrote the schema.

**Opt-in:** event, sub, fx, and render timing through the browser Performance API has its own flag, `{:closure-defines {re-frame.performance/enabled? true}}`, and is off by default in every build ([Find and fix a slow view](fix-a-slow-view.md#4-only-slow-in-production-the-rf-timing-channel)).

??? info "From re-frame v1"

    There is no separate tracing dependency and no `10x` preload or `closure-define` to add: dev builds trace by default, and production elision uses the `goog.DEBUG=false` you already set.

??? note "Why elision rather than a runtime check"

    The dev surfaces are written as `(when ^boolean re-frame.interop/debug-enabled? …)`, and `debug-enabled?` is a `goog-define` constant. With `goog.DEBUG=false` in `:advanced` mode it folds to `false`, every guarded body becomes unreachable, and DCE removes it along with everything only it referenced. This repository's `npm run test:elision` probe checks that the strings are absent from the bundle.

## 2. Gate your own dev-only code

Your own dev-only code, such as a trace listener or debug hook, needs the same gate to disappear with the framework's. Put the framework's predicate as the outermost form:

```clojure
(when ^boolean re-frame.interop/debug-enabled?   ;; alias of goog.DEBUG
  (rf/register-listener! :trace :my-app/console-tap
    (fn [trace-event]
      (js/console.log (:operation trace-event) trace-event))))
```

In production `debug-enabled?` is the constant `false`, so the registration is dead code and is removed.

`(when (and something debug-enabled?) ...)` does not constant-fold: Closure can't rule out `something`, so the code ships. Keep `debug-enabled?` as the outermost test, on its own. The same applies to every dev-only call you write, such as `(rf/register-listener! :epoch …)`, a `trace-buffer` read, or `(rf/configure! {:trace-buffer …})`.

## 3. Shipping a JVM/SSR tier? One system property

On the JVM there's no Closure compiler, so the gate is a runtime flag, and it defaults to on. A production [SSR](../../ssr/glossary.md#ssr) or webhook process that handles untrusted input should turn it off, so the trace rings and epoch history don't retain user input:

```
java -Dre-frame.debug=false -jar app.jar
```

You can use either spelling:

- the Java system property `re-frame.debug`, as above;
- the environment variable `RE_FRAME_DEBUG`, often simpler in a container (`RE_FRAME_DEBUG=false` in the Dockerfile or orchestrator config).

Each accepts `false`, `0`, `no`, `off`, or the empty string, case-insensitively. Anything else, including unset, leaves the flag on, so a typo like `RE_FRAME_DEBUG=disabled` keeps dev mode on. If both are set, the system property wins.

The flag is read once, when `re-frame.interop` loads, so set it at process level rather than with `System/setProperty` after boot. With it off, the JVM drops the same dev surfaces an `:advanced` browser build does: no trace rings and no epoch history. The handled-event and error streams, and the SSR error projector that turns a server-render failure into a safe public error page, keep working.

## 4. Where settings live

Dev-side configuration lives in three places, sorted by the lifetime of what you configure. A setting that makes sense at two lifetimes has a place at each: `:observability` for the process and for one frame (the two combine per stream), and trace retention for the process (`:trace-buffer`) and for one frame (`:rf.trace/events-retained`).

| Lifetime | Surface | What lives there |
|---|---|---|
| Process-wide, the value is plain data | `(rf/configure! {key opts})` | `:epoch-history`, `:trace-buffer`, `:elision`, `:observability` |
| Slot-level, the value is a swappable implementation | `set-…!` / `install-…!` | schema validator/explainer, substrate adapter |
| One frame | frame config (`make-frame`) / `dispatch` opts | `:drain-depth`, `:observability`, `:fx-overrides` |

### The `configure!` bucket: process-wide data

`configure!` takes one nested map with four top-level keys, shown here at their defaults:

```clojure
(rf/configure!
  {:epoch-history {:depth 50}                       ;; how far time-travel rewinds
   :trace-buffer  {:events-retained 50}             ;; events held for dev tools
   :elision       {:rf.egress/threshold-bytes 16384}   ;; "too big for the wire"
   :observability nil})                             ;; production sinks; none by default
```

A missing top-level key leaves that subsystem untouched, so you can pass one setting, such as `(rf/configure! {:trace-buffer {:events-retained 200}})`, or all four at once.

An unknown top-level key applies nothing. In dev builds, an unknown bare (or `rf`-namespaced) key such as `:epoch-histroy` also emits `:rf.warning/unknown-configure-key`, naming what you typed and the keys the runtime reads; the call still returns `nil`. A key under your own namespace (`:myapp/thing`) is silent, so a wrapper can pass its own keys through in the same map. The argument itself must be a map: a vector or `nil` throws `:rf.error/configure-bad-arg` in every build.

Inside a key, only `:trace-buffer` checks what you pass: anything but a non-negative `:events-retained`, including `{:depth N}`, emits `:rf.warning/trace-buffer-unrecognised-opts` in dev and changes nothing. `:epoch-history` drops an invalid value or an unknown key without a warning, and `:elision` drops an unknown key the same way. Read back what took effect with `(rf/current-config)`, which returns the same nested shape.

The four keys:

- **`:epoch-history`**: depth of the per-frame epoch ring, the buffer of recent [app-db](../glossary.md#app-db) states behind Xray's [time travel](../glossary.md#time-travel); `:depth 0` disables it. `:trace-events-keep` (a non-negative integer, default 50) sets how many of each frame's most recent epoch records keep their raw trace events; older records keep only the structured summaries. Dev-only. There is no scrub hook; an epoch leaving the process goes through `rf/project-egress` ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md#classification-and-time-travel-epoch-records-stay-raw)).
- **`:trace-buffer`**: how many events the dev trace ring keeps, one slot per dispatched event however many trace events it emitted. Raise it for a bug that spans more user actions than the default 50. `:events-retained 0` keeps nothing while listeners still fire. Dev-only.
- **`:elision`**: the size above which an *undeclared* large value on a wire-bound surface triggers the `:rf.warning/large-value-unschema'd` warning. The value is still forwarded whole; only values you declared large (or marked `:large?` in a schema) are replaced by the `:rf.size/large-elided` marker. `:rf.egress/threshold-bytes 0` turns the warning off. Declared `:large` elision is not dev-only: it shapes the records your production sinks receive ([Keep secrets and large things out of traces](keep-secrets-out-of-traces.md#two-axes-sensitive-and-large)).
- **`:observability`**: the process default for production sinks, in the same grammar as a frame's `:observability` (below): `{:handled-events [<entry>…] :errors [<entry>…]}`. Declare your Sentry or Datadog policy once here instead of on every frame. Unlike the other three keys it applies in production, `nil` clears it (`(rf/configure! {:observability nil})`), and it is validated at the call: a malformed policy throws `:rf.error/bad-frame-classification` ([Report errors in production](report-errors-in-production.md#declare-it-once-for-the-whole-process)).

There is no `:sub-cache` key: a [subscription](../glossary.md#subscription) is disposed as soon as its last reader goes away, so there is no grace period to tune.

### The `set-…!` bucket: swappable implementations

You use this bucket only to replace an implementation: a non-Malli validator or explainer with `re-frame.schemas/set-schema-fns!` ([Validate with schemas](validate-with-schemas.md#swap-the-validator-malli-is-the-default)), or a [substrate](../glossary.md#substrate) with `rf/init!`. Most apps call only `rf/init!`, at boot.

### The per-frame bucket: frame-lifetime overrides

These keys live in the frame config: `:drain-depth`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:initial-events`, `:on-destroy`, and `:observability`. `:fx-overrides` and `:interceptor-overrides` can also be passed per dispatch in the `dispatch` opts, where the per-call value wins.

```clojure
;; A frame with its own error sink. Observability is frame policy, so it
;; applies in production.
(rf/make-frame
  {:id             :app
   :initial-events [[:todo/initialise]]
   :drain-depth    100
   :observability  {:errors [{:sink :app.sinks/sentry}]}})
```

An `:observability` entry names a `:sink` keyword that you register, with an optional `:rf.egress/profile`. The entry takes those two keys only; anything else throws at `make-frame`. Vendor configuration belongs in the sink function you register, which closes over it. The frame key accepts `:handled-events` and `:errors` ([Report errors in production](report-errors-in-production.md)).

Most apps don't need this frame key: declare the policy once with `(rf/configure! {:observability …})` and every frame inherits it. Use the frame key when one frame differs; the two combine per stream ([Report errors in production](report-errors-in-production.md#declare-it-once-for-the-whole-process)).

`:drain-depth` is covered with the guardrails below.

??? info "Coming from React?"

    There are no `.env` files or scattered `process.env` reads. Configuration is split by lifetime: process-wide data through `configure!`, swappable implementations through their setters, and per-frame overrides on the frame.

The frame config accepts more keys than this page covers, such as `:preset` and `:rf.cofx/mint-policy`. The full catalogues are `configure!` and `make-frame` in the [API reference](../../api/re-frame.core.md).

## 5. The guardrails you can't turn off

These run in every build, dev and production alike, and each [fails loud](../glossary.md#fail-loud-not-silent) with a structured `:rf.error/*`:

- **Drain depth** (default 100, per-frame `:drain-depth`): a runaway dispatch [drain](../glossary.md#drain--run-to-completion) halts at the limit with `:rf.error/drain-depth-exceeded` instead of freezing the tab. Events already settled stay committed (each [commit](../glossary.md#commit) is atomic on its own); the event that would exceed the limit does not run. The recovery is `:no-recovery`, because reaching the limit means a dispatch cycle to fix, not a number to raise.
- **HTTP keyword cap** (`:rf.http/max-decoded-keys`, default 10000): a hostile JSON reply can't intern unbounded keywords in a long-running process; the decode fails and your `:on-failure` runs.
- **Request timeout** (`:timeout-ms`, default 30000): every [managed HTTP](../../resources/glossary.md#managed-http) attempt has a wall-clock timeout, which defends against slow-loris servers. Opting out takes an explicit `:timeout-ms nil` that a reviewer can see.
- **CRLF rejection**: the server-side `:rf.server/*` response effects won't put `\r` or `\n` on the wire. A header `:value` containing one throws `:rf.error/header-invalid-value`, a redirect location containing one throws `:rf.error/redirect-invalid-location`, and cookies are structured maps that can't be string-spliced.
- **Open-redirect guard**: `:rf.server/redirect` trusts its caller and gets only the CRLF check. For a location built from untrusted input, such as a `?next=…` query param, use `:rf.server/safe-redirect`. It rejects `javascript:` / `data:` / `vbscript:` schemes (`:rf.error/safe-redirect-scheme-rejected`) and unparseable URLs (`:rf.error/safe-redirect-invalid-url`), and can restrict to relative URLs or an `:allow` host list (`:rf.error/safe-redirect-host-disallowed`).
- **Editor-URI scheme rejection**: click-to-source links refuse `javascript:` / `data:` / `vbscript:` schemes (editor schemes such as `vscode:`, `idea:`, and `cursor:` pass), so a custom editor template can't run script in your dev tab.
- **Reserved effects can't be overridden**: an `:fx-overrides` entry that targets a framework-owned effect such as `:rf.machine/spawn` is ignored and reported as `:rf.error/reserved-fx-override`; the real effect runs.

Each of these defends against a production threat (a recursive dispatch loop, keyword interning, header injection), which is why none of them is dev-only. [Observability](../observability.md#in-production-builds) covers the elision mechanism and the always-on streams in depth.

## The pre-ship checklist

1. Release build sets `{:closure-defines {goog.DEBUG false}}` (most templates already do).
2. Your own dev-only registrations sit behind `^boolean re-frame.interop/debug-enabled?`, outermost.
3. Production observability is wired on an always-on surface — a frame `:observability` sink, or the same entry grammar declared once with `(rf/configure! {:observability …})`.
4. Handlers receiving untrusted payloads carry a `:schema` and are registered `:boundary? true`.
5. A JVM/SSR tier ships with `-Dre-frame.debug=false`.
6. No Xray preload or pair-server artefact on the release classpath.
