# 26 — Configuration and safety primitives

## TL;DR

re-frame2 ships with a small set of knobs you can turn — and a smaller set of guardrails you can't. This chapter is about both. The knobs live behind `configure`, `set-!` / `install-!`, and per-frame metadata. The guardrails — CRLF rejection, scheme allowlists, JSON keyword caps, slow-loris timeouts, drain-depth ceilings — sit between your code and a class of failure mode you don't want to be the person who discovered.

You don't have to read this chapter to write a re-frame2 app. The defaults are sensible. But the day you wonder *why* your handler stopped at a header value containing `\r\n`, or *what number* the trace ring buffer actually holds, or *which prefix* the framework is going to flinch at when you try to register a handler under it — this is the chapter.

## The shape of the thing

The framework has three orthogonal places configuration can live, and each one exists because the **lifetime** of the thing being configured is different. Once you see the three buckets, the API stops feeling scattered:

| Lifetime | Surface | Examples |
|---|---|---|
| Process — apply to the runtime as a whole | `(rf/configure :key opts)` | trace-buffer depth, sub-cache grace period, elision threshold |
| Process — but the value is a fn or component the framework has to call | `(rf/set-x!)` / `(rf/install-x!)` | schema validator, schema explainer, substrate adapter |
| Per-frame — apply only to one frame's existence | `reg-frame` / `make-frame` metadata, or `dispatch` opts | `:drain-depth`, `:on-error`, `:fx-overrides`, `:interceptors`, `:on-create` |

The first two are global. The third is local. If you ever feel like you want to configure the same thing in two places, the option is doing two things and should be split. The framework's stance is one option, one bucket; that constraint is what makes the configuration story small enough to hold in your head.

For the canonical normative description of the three buckets see [Conventions §Configuration surfaces](../../spec/Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata).

## The shape of the safety story

Safety primitives in re-frame2 work the same way the rest of the framework does: **the architecture forbids the thing**, rather than the docs asking you politely. Each safety primitive in this chapter has the same shape:

1. A specific input or condition that, if it reached the system, would cause a class of failure (header injection, DoS-by-keyword, runaway recursion, XSS via clickable IDE link).
2. A check site, deep in the framework, that detects the condition.
3. A structured failure that surfaces to your `:on-error` policy (or your trace listener, or your error projector) so you see the bug the way you'd see any other.

The framework does not strip-and-warn. It does not silently normalise. It does not "do its best with what you gave it." It rejects, raises, and tells you exactly what was wrong. The bet — same bet [chapter 14](14-dynamic-model.md) makes about the dynamic model — is that surfacing the bug at its source is cheaper than letting it bake itself into the system's observable behaviour.

> **Where the contracts live.** This chapter is the *guide-side* tour. The normative descriptions of every safety primitive live in [`spec/Security.md`](../../spec/Security.md) — threat model + defense-in-depth catalogue. The cross-refs land you on the right anchor in that doc for any specific primitive.

## What this chapter covers

Eight sections. Each one answers one concrete question:

- [Framework configuration](#framework-configuration). What `configure` lets you tune (and when to bother). The four keys, what they default to, and how to know your value is doing anything.
- [HTTP safety primitives](#http-safety-primitives). The CRLF fail-fast on server-side responses, the JSON keyword-interning cap, and the slow-loris timeout that makes the network give up on unresponsive partners before they exhaust your pool.
- [Redirects and editor URIs](#redirects-and-editor-uris). What schemes the framework rejects on click-to-source IDE templates, and the related sanity gates on `:rf.server/redirect`.
- [Drain depth and error recovery](#drain-depth-and-error-recovery). What the run-to-completion drain's depth ceiling protects you from, why the rollback is atomic, and how to tune it per frame.
- [Reserved namespaces](#reserved-namespaces). The single catalogue of every `:rf.*/*` prefix the framework owns. Tools check; the linter checks; this is the human-readable copy.
- [Machine substrate features](#machine-substrate-features). The four advanced state-node keys — `:always`, `:after`, `:spawn`, `:spawn-all` — with worked examples and the rules they enforce. Plus `:final?` / `:on-done` / `:output-key` and how parallel regions compose.
- [Privacy and elision in practice](#privacy-and-elision-in-practice). The tutorial layered on top of [ch.24 — Privacy](24-privacy.md) and [ch.25 — Large blobs](25-large-blobs.md): a single running example (payments, GDPR export, photo upload, server-side imports) walking through the four progressive tiers of declaration that keep sensitive and oversized values off the wire.
- [Exceptions under `:sensitive?`](#exceptions-under-sensitive). The residual author surface that path-marked redaction can't reach: exception messages assembled from sensitive paths, and `ex-data` maps with author-supplied keys. Three patterns and a tiny helper that closes the gap.

Read in order if you're new. Skim individual sections when something specific bites.

---

## Framework configuration

`(rf/configure key opts)` is the one entry point for process-level framework knobs. The vocabulary is closed-and-additive: four keys today, never renamed, never removed; new keys arrive by Spec change. This section enumerates them, lists their defaults, and gives you the question each one answers.

`set-!` / `install-!` are the *other* entry point — for hooks that need a fn-reference rather than a data value. Per-frame metadata is the third. The chapter index above sketched the three-bucket model; this section is the inside of bucket 1, with a glance at bucket 2.

### The `configure` keys

There are four. Each is a plain-data setting that applies to the framework runtime as a whole. The full normative table — vocabulary, opts shape, defaults, status — lives at [API.md §Configure keys](../../spec/API.md#configure-keys); this section is the guide-side narrative.

#### `:epoch-history` — how far back can you rewind?

```clojure
(rf/configure :epoch-history {:depth 50})        ;; the default
(rf/configure :epoch-history {:depth 200})       ;; deeper history; more memory
(rf/configure :epoch-history {:depth 0})         ;; disable
(rf/configure :epoch-history
  {:redact-fn (fn [record]                       ;; scrub secrets before
     (update record :db-after dissoc :auth))})   ;; the ring stores the record
```

Every dispatched event's full cascade is recorded as an *epoch record* — `:db-before`, `:db-after`, `:sub-runs`, `:renders`, `:effects`, `:trace-events` — and stored in a ring buffer. That buffer is what powers Causa's time-travel debugging, `restore-epoch`, `reset-frame-db!`, and the Tool-Pair surface.

50 epochs is enough for a typical debug session (you almost never want to rewind further than 50 user actions). 200 is reasonable for long-running stress tests. 0 disables history entirely — useful in SSR production where you have no replayer attached and the per-cascade allocation is wasted work.

`:redact-fn` is the build-time hook for apps that record sensitive material into `app-db`. The framework invokes it **once per assembled record** — between `build-record` and ring-append / listener fan-out — so the ring buffer, every `register-epoch-listener!` listener, and every off-box egressor (Causa, re-frame2-pair-mcp) see the **same** redacted shape. The fn returns the record (potentially rewritten); any slot it overwrites can ride as the `:rf/redacted` sentinel or any app-chosen shape. A throwing fn does not break the drain — the framework catches the throw, emits `:rf.warning/epoch-redact-fn-exception`, and falls back to the raw record for that drain only. One caveat: `restore-epoch` rewinds `app-db` *to the recorded `:db-after`*, so if your fn redacted `:db-after`, the rewind lands `app-db` in the redacted state. Apps that need restore fidelity should leave `:db-before` / `:db-after` alone and redact only `:trace-events` / `:trigger-event`. The full posture lives at [Tool-Pair §Time-travel](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo) and [Security §Epoch privacy posture](../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress).

The setting is **dev-only** by status. Under `:advanced` + `goog.DEBUG=false`, the recording site DCEs and the buffer never allocates, regardless of what you configured.

#### `:trace-buffer` — how many trace events sit in memory?

```clojure
(rf/configure :trace-buffer {:depth 200})        ;; the default
(rf/configure :trace-buffer {:depth 1000})       ;; longer trace history
(rf/configure :trace-buffer {:depth 0})          ;; disable the buffer
```

The trace buffer is the ring of `:rf.*/*` trace events that backs your dev tooling — the same stream that [chapter 23 — Observability](23-observability.md) ships to observability back-ends, the same stream that re-frame2-pair-mcp inspects when an agent asks "what happened in the last cascade." Bigger buffer means longer history; smaller buffer means less memory.

The default of 200 is sized to comfortably hold a single complex cascade (one user action that fans out into 30+ machine transitions, an HTTP response, a few sub-runs, the lot). If you're investigating a multi-event saga, bump it. If you're in production and you've registered listeners that ship events as they arrive (rather than reading the buffer), set it to 0 — the listeners get every event live; the buffer is wasted memory.

Like `:epoch-history`, the buffer is dev-only. Production builds elide the allocation regardless.

#### `:sub-cache` — how long do unused subscriptions live before disposal?

```clojure
(rf/configure :sub-cache {:grace-period-ms 50})   ;; the default
(rf/configure :sub-cache {:grace-period-ms 200})  ;; tolerate longer route transitions
(rf/configure :sub-cache {:grace-period-ms 0})    ;; synchronous disposal
```

Subscription caching is ref-counted: when the last consumer of a subscription disappears, the cache schedules disposal. The **grace period** is how long the disposal waits — if a new consumer shows up inside the window, the subscription is rescued and the disposal is cancelled.

The reason this matters: route transitions, modal close-and-reopen flows, and "scrub the timeline forward then back" interactions briefly unmount and remount the same subscription tree. With a 50ms grace period, those flows reuse the cached subscription without re-running the computation; the cache is doing its job. Tune up if your app has slow-mounting routes; tune down (or to 0) if you're memory-constrained and want subs to drop the second nobody's reading them.

Setting it to 0 selects synchronous disposal: the subscription tears down on the same tick as the last consumer leaves. Useful for tests that assert on cache cardinality; rarely useful in production.

Unlike the two above, `:sub-cache` is **not** dev-only — the sub-cache exists in production builds, and the grace-period configuration applies there too.

#### `:elision` — how big is "big enough to elide?"

```clojure
(rf/configure :elision {:rf.size/threshold-bytes 16384})   ;; the default — 16KB
(rf/configure :elision {:rf.size/threshold-bytes 65536})   ;; tolerate bigger inline values
(rf/configure :elision {:rf.size/threshold-bytes 0})       ;; disable runtime size-based detection
```

The wire-elision walker (per [chapter 25 — Large blobs](25-large-blobs.md) and [API.md §`rf/elide-wire-value`](../../spec/API.md#elide-wire-value-the-wire-boundary-walker)) replaces large values with `:rf.size/large-elided` markers before they egress to off-box consumers. The threshold-bytes value is the **runtime auto-detection threshold** — a value larger than this gets a marker even if the schema didn't pre-declare it as `:large?`.

16KB is the default because it's about the size where pretty-printing the value into a Datadog event starts being a bad idea. Tune up if you've got endpoints that legitimately return larger blobs that fit your back-end; tune down if you're shipping to a back-end with a smaller event limit.

Setting it to 0 disables the runtime walker for size-based detection; only **schema-declared** `:large?` slots elide. That's the right setting for environments where you've fully audited every slot via schemas and want the runtime to honour those declarations and nothing else — no accidental elision of an unexpectedly-large response payload, no false negatives from "small slot containing a large value."

Note that `:sensitive?` elision is **never** size-gated. Sensitive values redact regardless of size; the threshold only governs `:large?`-flavoured size elision.

### When to tune

The defaults are the right answer for almost every app. The cases where you tune are narrow:

- **Bumping `:epoch-history`** for a debug session where you want to scrub a longer cascade.
- **Bumping `:trace-buffer`** when you're hunting a bug that spans multiple user actions and the existing buffer is rotating events out before you can read them.
- **Bumping `:sub-cache` grace period** when route transitions feel laggy because the sub recomputed instead of resurrecting from cache. (This is rare; the default catches most cases.)
- **Bumping `:elision` threshold** when your back-end can handle larger events than 16KB and you want fewer round-trips to refetch elided values.
- **Setting any of the dev-only keys to 0** in long-running JVM SSR processes where dev recording is wasted allocation.

If you find yourself wanting to tune something that isn't on the list, the option doesn't exist — and that's deliberate. The framework's stance is that the per-process knobs are a small fixed set; new knobs land by Spec change, not by adding a "configurable" flag to wallpaper over a design issue.

### The `set-!` / `install-!` neighbours

A few things look like they should be `configure` keys but aren't, because the value the framework needs is a fn or component reference rather than data. These live under separate `set-!` / `install-!` fns; the bang on the end is the framework's way of telling you the surface mutates a process-level slot the framework calls into from arbitrary sites.

```clojure
(rf/install-adapter! reagent/adapter)                ;; install the reactive substrate
(rf/set-schema-validator! malli.core/validate)       ;; swap the schema validator
(rf/set-schema-explainer! malli.core/explain)        ;; swap the schema explainer
```

If you're using the default substrate ([chapter 21 — Adapters](21-adapters.md)) and Malli for schemas ([chapter 05 — Schemas](05-schemas.md)), you call none of these and the boot wiring is automatic. If you want to drop the Malli dependency and bring your own validator (Plumatic, Specs, hand-rolled), `set-schema-validator!` is the entry point.

These are **not** folded under `configure` because keyword-keyed addressing loses the type information a consumer needs to pass an actual fn/component reference. `configure` is for *data*; `set-!` is for *impls*. That asymmetry is the explicit signal: "the framework is going to hold this reference and call it back from places you don't control."

For the full enumeration of the `set-!` / `install-!` surface, see [API.md §Adapter lifecycle](../../spec/API.md) and [API.md §Schemas](../../spec/API.md). Both surfaces are small (five-ish fns each) and follow the same pattern: install / swap / inspect / dispose.

### The per-frame neighbour

The third bucket is per-frame metadata. Anything whose lifetime is "as long as this frame exists" — `:fx-overrides` for one test fixture, `:drain-depth` tightened for one story, `:on-error` for one production frame versus one SSR frame — lives on the frame's metadata, not in `configure`:

```clojure
(rf/reg-frame :auth
  {:on-create  [:auth/initialise]
   :drain-depth 100
   :on-error   {:default :log
                :rf.error/drain-depth-exceeded :halt}})
```

[Chapter 08 — Frames](08-frames.md) walks the whole frame-metadata grammar. The next section here — [Drain depth and error recovery](#drain-depth-and-error-recovery) — digs into `:drain-depth` and `:on-error` specifically; both are bucket-3 knobs that the safety story leans on.

---

## HTTP safety primitives

Three defenses, one section. The framework caps JSON keyword interning so a hostile upstream can't burn unbounded keyword slots. It applies a 30-second timeout to managed requests so a slow-loris partner can't pin your connection pool open forever. It refuses to ship a header (or set a cookie, or issue a redirect) whose value contains `\r\n`, because that's how header injection works. All three are on by default; all three surface as structured errors when they fire.

[Chapter 12 — HTTP](12-http.md) is the place to learn what managed-HTTP looks like to a normal app. [Chapter 13 — The server side](13-server-side.md) is the place to learn what SSR response-shape fx do. This section is the safety layer between you and a few specific classes of failure that those chapters don't dwell on. It's deliberately short because each defense is small.

### JSON keyword cap — `:rf.http/max-decoded-keys`

When `:rf.http/managed` decodes a JSON response and keywordizes its keys (the default), every unique key turns into a Clojure keyword. Clojure keywords are interned; once a JVM has seen `:foo` it remembers it for the lifetime of the process. That's normally invisible — most apps see a few hundred distinct keys across their entire wire vocabulary.

But: an upstream that's been compromised (or just hostile) can return a JSON object whose keys are `"a000000"`, `"a000001"`, `"a000002"`, … and a long-running JVM SSR worker that hits that endpoint a million times eventually fills its keyword storage and tips over. The same threat applies to a webhook receiver, an MCP server's response decode, or any other always-on JVM that decodes attacker-influenceable JSON.

The framework caps the number of unique keys it'll keywordize per request. The default is **10000** — comfortably more than any legitimate response, comfortably less than a denial-of-service. The cap is enforced at the decode site in both the JVM (Cheshire) and CLJS (the reader) paths.

You can opt up per-request:

```clojure
(rf/dispatch
 [:catalog/refresh
  {:rf.http/managed
   {:request {:method :get :url "/catalog/full-dump"}
    :rf.http/max-decoded-keys 50000           ;; one specific endpoint legitimately wants more
    :on-success [:catalog/loaded]}}])
```

When the cap trips, the request fails as a normal `:rf.http/decode-failure` reply on the request's `:on-failure` path:

```clojure
{:kind   :rf.http/decode-failure
 :reason :too-many-keys
 :limit  10000}
```

Your `:on-failure` handler sees the structured shape; the request didn't complete; no keyword slots were committed. The first line of defense — if you can — is to use `:decode :text` for endpoints whose response you don't need as a keyword-keyed map; the cap is the second line, for the JSON cases where you do.

The full normative description sits at [014-HTTPRequests.md §Keyword-interning cap](../../spec/014-HTTPRequests.md) and [Security.md §DoS by input](../../spec/Security.md#dos-by-input).

### The symmetric routing-side cap

The same accident class lives on the routing side. URL query strings are caller-controlled (deep links, partner referrals, share links) and `match-url` historically turned every query key into a keyword — exactly the unbounded-intern pattern the JSON cap above guards against. The routing parser applies a symmetric cap:

```clojure
;; URL with > 10000 unique query keys:
{:kind   :rf.error/route-too-many-keys
 :limit  10000
 :count  10001
 :url    "/search?k0=...&k10000=..."}
```

…thrown from `match-url`, propagated through the calling navigation event.

The defense layers further. When a route declares a `:query` schema, only the schema-named keys are promoted to keyword keys; unknown URL keys retain their string form in the parsed `:query` map. A `:keyword`-typed slot without an `[:enum ...]` allowlist stays as a string (the unbounded-intern site is closed); declare `[:enum :asc :desc]` for the safe, bounded keyword universe. See [012-Routing.md §Keyword-interning cap on query keys + values](../../spec/012-Routing.md#keyword-interning-cap-on-query-keys--values-rf2-3k3o7) for the full contract.

### Slow-loris defense — `:timeout-ms` default 30000

A "slow-loris" upstream is one that never quite finishes the response — it sends a header, dribbles a byte, waits, dribbles another byte, waits. On the CLJS side that's a fetch promise that never resolves. On the JVM side that's a `CompletableFuture` that never completes and a connection-pool slot that never returns. Run a long-lived JVM against a compromised partner and you can have your pool exhausted in minutes.

`:rf.http/managed` applies a **30-second per-attempt wall-clock timeout** to every request that doesn't explicitly opt out. The timeout fires regardless of what state the request is in — TCP open, TLS handshake, headers in, body in, body trickling — and surfaces as:

```clojure
{:kind :rf.http/timeout :elapsed-ms 30000}
```

on the `:on-failure` path. The `:retry` policy (if you've declared one) sees `:rf.http/timeout` like any other classifier and decides whether to retry.

The opt-outs are explicit. You either pass `:timeout-ms nil` or `:timeout-ms 0`:

```clojure
;; explicit opt-out — caller is taking responsibility for the lifetime
{:request    {:method :get :url "/very/long/streaming/endpoint"}
 :timeout-ms nil}
```

Two values mean opt-out, both deliberate-looking, because "the caller meant to" should be visible to a reviewer. If you don't write `:timeout-ms`, you get 30000. If you write it as a small positive integer (`5000` for "I expect this to be quick"), you get that. The only way to remove the timeout entirely is to type `nil` or `0` — and a reviewer who sees that line knows the call-site author signed off on unbounded wall-clock.

See [014-HTTPRequests.md §`:timeout-ms` security defaults](../../spec/014-HTTPRequests.md) and [Security.md §DoS by input](../../spec/Security.md#dos-by-input).

### CRLF fail-fast on response-shape fx

This one's for the server side. SSR's response-shape fx (per [chapter 13 — The server side](13-server-side.md)) write headers, set cookies, and issue redirects:

```clojure
{:fx [[:rf.server/set-header  "X-Trace-Id" trace-id]
      [:rf.server/set-cookie  {:name "session" :value sid :domain "example.com" :path "/"}]
      [:rf.server/redirect    302 "/dashboard"]]}
```

Each of those values eventually becomes a line in the HTTP response: `X-Trace-Id: abc123\r\n`, `Set-Cookie: session=...; Domain=example.com; Path=/\r\n`, `Location: /dashboard\r\n`. The `\r\n` at the end of each line is the framework's job, not the caller's — and it's also a problem.

If a value the caller passed *itself* contains `\r\n`, the response splits. A `trace-id` of `"abc\r\nSet-Cookie: admin=true"` becomes two response lines: the legitimate `X-Trace-Id`, then a brand-new `Set-Cookie: admin=true` the attacker injected. This is **header injection**, and the right defense is "refuse the input."

The framework refuses. Each response-shape fx checks its value (or its per-attribute fields, for cookies) for `\r` or `\n` at handler time, and on detection emits:

| Fx | Error |
|---|---|
| `:rf.server/set-header` / `:rf.server/append-header` | `:rf.error/header-invalid-value` |
| `:rf.server/redirect` (Location-header) | `:rf.error/redirect-invalid-location` |
| `:rf.server/set-cookie` (any attribute field) | `:rf.error/header-invalid-value` |

The error fires on the failing fx, the response is **not** written, the caller's `:on-error` policy (or the SSR error projector, per [ch.13 — The server side](13-server-side.md)) takes over.

A few decisions are worth pointing at, because they're deliberate:

- **No strip-and-warn.** The framework does not silently delete the CRLF and continue. Silent normalisation lets attacker-encoded variants (`%0D%0A`, double-encodings, etc.) through if the downstream decoder treats them differently — and worse, it masks the bug from the dev writing the call site. Fail-fast is the only honest answer.

- **Per-attribute on cookies.** A cookie isn't one string; it's a map of `:name`, `:value`, `:domain`, `:path`, `:max-age`, `:same-site`. Each one rides as its own attribute in the `Set-Cookie:` line, and each one is checked independently. The threat model isn't "attacker controls the whole value" — it's "attacker controls the user-id flowing into `:name`, or the partner-supplied `:domain`, and only that piece can carry CRLF."

- **Structural URL check on redirects, in addition to CRLF.** `:rf.server/redirect` also rejects malformed-URL inputs at the same site, so a `:rf.error/redirect-invalid-location` surfaces both the CRLF case and the "this isn't a URL" case under one error category.

The full normative description lives at [Security.md §CRLF injection at HTTP-response boundaries](../../spec/Security.md#crlf-injection-at-http-response-boundaries) and [011-SSR.md §Standard fx](../../spec/011-SSR.md).

### What you don't have to do

A common pattern in older Ring-style code is "sanitise every value before you pass it to the response builder." You don't have to do that with `:rf.server/*`. The framework's stance: the **fx-handler** is the sanitisation site, not your code, and the contract is "give me your data; I'll fail-fast if you handed me something dangerous." If your code passes the framework a value with `\r\n` in it, the framework's response is "your caller has a bug, here's the error category, surface it the way you'd surface any other bug." You spend exactly zero lines of defensive code at the call site.

The same posture extends to the JSON cap and the timeout: you don't write `(try ... (catch SocketTimeoutException ...))` at every call site. You declare the `:retry` policy you want, and the framework's failure classification (`:rf.http/timeout`, `:rf.http/decode-failure`, the eight-category taxonomy from [ch.12 — HTTP](12-http.md)) tells your reply handler what kind of failure it's seeing.

---

## Redirects and editor URIs

Two URI-shaped surfaces, two scheme policies. The framework's editor-launch links — the click-to-source affordance that opens your IDE at a specific line — reject three known-bad schemes (`javascript:`, `data:`, `vbscript:`) and, at the tool's click-time boundary, only honour a positive allowlist of editor schemes. `:rf.server/redirect` rejects CRLF in the `Location:` value and surfaces a structural URL check. Both defenses are small, and both are about closing the gap between "a string the framework will hand to the browser" and "a string the user controls."

The previous section covered the headers-and-cookies CRLF check on the server. This section covers the two scheme-checked surfaces — `:rf.server/redirect` (where the Location header is itself a URI) and the editor-URI templates that pair-tools use to open IDE links from in-page click affordances.

### `:rf.server/redirect` and `:rf.error/redirect-invalid-location`

A redirect from your SSR handler is a `Location:` header plus an HTTP 3xx status:

```clojure
;; common case — internal redirect after sign-in
{:fx [[:rf.server/redirect 302 "/dashboard"]]}

;; with a query string
{:fx [[:rf.server/redirect 302 (str "/login?return=" return-url)]]}

;; conditional — redirect to a partner domain
{:fx [[:rf.server/redirect 302 (str "https://" partner-domain "/oauth/return")]]}
```

The framework's `:rf.server/redirect` fx-handler runs two checks on the Location value:

1. **CRLF fail-fast** — same check as every other `:rf.server/*` value (see [HTTP safety primitives](#http-safety-primitives) above). A value containing `\r` or `\n` surfaces `:rf.error/redirect-invalid-location` and the redirect is not written.

2. **Structural URL shape** — the value must parse as a URL or as a relative path. A value that doesn't parse fails under the same `:rf.error/redirect-invalid-location` category. This catches malformed inputs (`"https//missing-colon"`, `"foo bar"` with embedded spaces, etc.) before the response goes out.

The error category is unified by design: the caller doesn't need to discriminate "CRLF in my URL" from "this isn't a URL" — both are "the redirect target you handed me isn't acceptable," and the fix is the same in both cases. The error event's `:tags` carry the failing value (subject to the `:sensitive?` redaction from [chapter 24 — Privacy](24-privacy.md) if the value rides under a sensitive slot), so your trace stream surfaces the bug at its source.

`:rf.server/redirect` is also where the **safe-redirect-fx** pattern lives. The recommended shape for "redirect to a URL the user supplied" — say, the OAuth-return URL passed through a query string — is:

```clojure
;; In your event handler:
(rf/reg-event-fx :auth/post-login-redirect
  (fn [{:keys [db]} _]
    (let [requested-return (-> db :request :query-params :return)
          ;; Validate against your allowlist BEFORE building the fx.
          target           (if (allowed-return-url? requested-return)
                             requested-return
                             "/dashboard")]
      {:fx [[:rf.server/redirect 302 target]]})))
```

The framework's CRLF + URL-shape check catches the *malformed* and the *injecting* cases. Validating against an **allowlist** of legitimate return URLs is your responsibility — the framework doesn't and can't know which return URLs are yours. The pattern is: validate at the event handler, hand the fx a value that's *already* known-good, let the framework's fail-fast catch any contract violation if you've made a mistake. Defense in depth.

See [Security.md §CRLF injection at HTTP-response boundaries](../../spec/Security.md#crlf-injection-at-http-response-boundaries) for the normative description.

### Editor URI templates — the scheme allowlist

This one's about the dev-tools surface, not user-facing HTTP. When Causa's click-to-source affordance opens your editor at a file:line, it builds a URI string and hands it to the browser:

```
vscode://file/path/to/foo.cljs:42:7
cursor://file/path/to/foo.cljs:42:7
idea://open?file=path/to/foo.cljs&line=42&column=7
```

Each tool that surfaces source-coords (Story, Causa, re-frame2-pair-mcp) lets you pick your editor at boot:

```clojure
{:rf.story/editor :vscode}     ;; one of :vscode / :cursor / :windsurf / :zed / :idea
{:rf.story/editor {:custom "vim://open?path={path}&line={line}"}}
```

The five named editors (`:vscode`, `:cursor`, `:windsurf`, `:zed`, `:idea`) are built-ins; the framework's builders for those schemes can only emit a known-good URI. The interesting surface is the `{:custom "..."}` form — the open-ended template that lets you point at any editor (Sublime's `subl:`, Emacs's `org-protocol:`, JetBrains Fleet's `fleet:`, your bespoke `my-editor://`, …) without waiting for an upstream PR.

That template, though, is a string the user wrote. If it's `{:custom "javascript:alert(1)"}`, clicking the source-coord link would run script in your dev tab. That's the threat; the defense is two-layered.

#### Layer 1 — the three-scheme reject

The framework's URI builder refuses to emit a URI whose leading scheme is one of three known-bad schemes:

| Scheme | Why it's rejected |
|---|---|
| `javascript:` | Runs arbitrary JavaScript in the current origin |
| `data:` | Can carry inline HTML/script the browser renders |
| `vbscript:` | Legacy IE script scheme; still honoured in some embedded WebView surfaces |

If a `{:custom ...}` template's leading scheme matches any of these (case-insensitive, leading-whitespace-tolerant), the builder returns `nil`. The UI layer's `(when uri ...)` wrapper hides the link rather than rendering a no-op chip — visible failure, no silent rendering of a dangerous string.

The reject is **case-insensitive** and **whitespace-tolerant** — `" JavaScript:..."` and `"DATA:..."` still trip the gate. The attacker template that prepends whitespace hoping `triml` will strip it before the browser parses doesn't get through.

#### Layer 2 — the click-time positive allowlist

At the **click-time** boundary, each tool layers a positive allowlist on top of the three-scheme reject. The allowlist is `editor-uri/allowed-editor-uri-schemes` and covers the canonical editor schemes:

```
vscode, vscode-insiders, cursor, windsurf, zed,
idea, jetbrains, fleet,
subl, emacs, emacsclient, org-protocol,
vim, nvim, mvim,
txmt, atom, file
```

A `{:custom ...}` template that resolves to `http://...` or `gopher://...` would otherwise navigate the tab rather than launch an editor — surprising to the dev who's expecting "this opens my IDE." The allowlist makes that an obvious no-op rather than a silent surprise.

The two layers are deliberately redundant. The three-scheme reject closes the script-execution attack surface — that's the must-have. The positive allowlist closes the "navigate-where-you-didn't-mean-to" footgun — that's the would-be-nice. A tool that accidentally drops one layer still has the other.

Both predicates live in `re-frame.source-coords.editor-uri` and are exported for tooling reuse: `editor-uri` (the builder) and `allowed-uri?` (the click-time gate). If you write your own dev tool that surfaces source-coords, use both.

See [Security.md §Editor URI scheme allowlist](../../spec/Security.md) for the full rationale.

### What you don't have to validate

The framework's stance — same as the previous section — is that the *fx-handler* (for redirects) and the *URI builder* (for editor templates) are the right sites for input validation, not your code at every call site. If you pass `:rf.server/redirect` a value that contains CRLF, the fx-handler surfaces the bug. If you write a `{:custom "javascript:..."}` template into your editor config, the builder returns `nil` and the UI hides the link.

What you *do* have to handle is the allowlist of return URLs — the framework can't know which targets are yours. Everything else, the framework's check sites have you covered.

---

## Drain depth and error recovery

re-frame2's run-to-completion drain has a ceiling. A handler that dispatches itself, or a cascade that bounces back and forth between two machines without ever settling, will eventually trip the ceiling. When it does, the drain aborts **atomically** — `app-db` is rolled back to its pre-cascade snapshot — and the failure surfaces as `:rf.error/drain-depth-exceeded` on your frame's `:on-error` policy. No partial writes, no half-applied cascades, no "the third event in the chain got through but the fourth didn't." This section covers the ceiling, the rollback, the tuning knob, and how it composes with the per-frame `:on-error` story.

[Chapter 08 — Frames](08-frames.md) names `:drain-depth` as a frame-metadata key in passing. [Chapter 16 — Errors](16-errors.md) names `:rf.error/drain-depth-exceeded` as a row in the error taxonomy. This section is the *why* — the threat the ceiling defends against, the semantics of the rollback, and the integration with `:on-error`.

### The threat — recursive-cascade DoS

re-frame's run-to-completion drain is the property that makes event handlers easy to reason about: a dispatched event runs end-to-end before any other event is observed, including events that dispatch other events. A handler returning `{:fx [[:dispatch [:next-step]]]}` queues the dispatched event in the drain's FIFO; the drain processes it as part of the same cascade.

The problem is that nothing structural prevents that cascade from going on forever. A handler that dispatches itself (`{:fx [[:dispatch [:current-event]]]}`) loops. Two handlers that dispatch each other ping-pong. A state machine with an `:always` transition whose guard is somehow always true microstep-loops. Without a ceiling, any of these would consume the drain loop indefinitely — the JavaScript event loop blocks, the JVM thread spins, the UI freezes, the SSR request hangs.

The ceiling makes that into a bounded failure rather than an unbounded freeze. **Recursive cascades fail; they don't hang.** That's the load-bearing property.

### The ceiling — `:drain-depth`

Every frame carries a `:drain-depth` setting. The default is **100** — comfortably more than any legitimate cascade (a single user action typically dispatches between 1 and 10 events; the worst legitimate case is a complex boot sequence that fans out to maybe 30). 100 is two orders of magnitude above legitimate; well below "the dev's editor froze."

You tune per frame:

```clojure
(rf/reg-frame :auth
  {:on-create  [:auth/initialise]
   :drain-depth 100})            ;; the framework default — written explicitly

(rf/reg-frame :test-fixture
  {:drain-depth 1000             ;; tests that deliberately fire long sagas
   :on-create  [:test/setup]})

(rf/reg-frame :story-variant
  {:preset      :story
   :drain-depth 16})             ;; story preset's default — fail fast in interactive demos
```

The `:test` and `:story` presets ship with their own defaults (1000 and 16 respectively). Tests legitimately deliberately exercise long sagas; story variants are interactive demos where a runaway cascade should fail fast under a story rather than spinning up to the production limit and confusing the demo.

You can also tune at dispatch-time if you've got a specific cascade that legitimately wants more headroom for one call:

```clojure
(rf/dispatch [:bulk-import-large-csv] {:drain-depth 500})
```

Per-call overrides merge over per-frame metadata; the call-site value wins.

### The rollback — atomic, complete

When the cascade hits the ceiling, the runtime does **three** things, in order:

1. **Restore the pre-drain `app-db` snapshot.** Whatever state the frame was in before the cascade began is the state it's in after the abort. The drain's partial writes — every event that did successfully run before the ceiling tripped — are discarded.
2. **Restore frame-local registrations** that the cascade made. A handler that ran `(rf/dispatch [:rf.machine/spawn ...])` inside the cascade — which registered a frame-local handler at the spawned actor's `[:rf/machines <id>]` slot — has that registration reverted along with the `app-db` rollback. Otherwise an aborted drain would leave orphaned handlers attached to a frame at a value that never references them.
3. **Surface the failure.** `:rf.error/drain-depth-exceeded` is emitted with `:tags {:depth :queue-size :last-event :rollback? true}` and routed through your frame's `:on-error` policy.

The remaining queued events — the ones the cascade hadn't yet reached when the ceiling tripped — are discarded. The epoch buffer (consumed by Causa) records nothing for the failed drain. The frame is at the last settled state, which is always reachable by replay.

This is the "events are atomic" principle scaled up to the cascade boundary. A handler is atomic with respect to its own side effects ([ch.04 — Events](04-events.md)); a *cascade* is atomic with respect to depth-exceeded aborts. If you've thought about events as "either all the effects happen or none of them do," the same model now applies to cascades: either the whole cascade settles or it's rolled back.

The rollback boundary is **value-shape**, not "rewind real-world side effects." Out-of-band side effects that already committed to external substrates — an HTTP request that flew, a `dispatch-later` timer that scheduled — are not undone. The framework can't unsend a request; what it *can* do is keep its own state consistent so your replay path has somewhere honest to start from.

### The integration — `:on-error`

`:rf.error/drain-depth-exceeded` arrives at your frame's `:on-error` policy like any other error category ([chapter 16 — Errors](16-errors.md)). Three things you can do with it:

```clojure
(rf/reg-frame :auth
  {:on-create [:auth/initialise]
   :on-error
   {:default                       :log
    :rf.error/drain-depth-exceeded :halt}})        ;; opinionated — stop, don't try to recover
```

The three policies that make sense for this specific category:

- **`:log`** — emit the error to your observability surface and move on. The frame is at the pre-cascade state; the next dispatched event will run. This is the right policy for *unexpected* cascades — a bug you want to surface and fix, not a system you want to halt over.

- **`:halt`** — emit and stop processing further events on this frame. Useful for fixtures where any drain overflow is a test failure and there's no point letting subsequent events run on a frame whose dynamic story has gone sideways. Story variants (`:preset :story`) lean toward this; production frames lean toward `:log`.

- **A custom handler** — `(fn [error] ...)` that does whatever your app needs. Reset a known-good state, log to a specific channel, fire a side-effect that pages an on-call human. The handler runs after the rollback, so your code is starting from the pre-cascade state — not from the half-applied middle.

The default (no `:on-error` registered for the category) is `:log`. The frame stays alive; the next event will drain normally.

See [009's `:rf.error/drain-depth-exceeded` row](../../spec/009-Instrumentation.md) and [Chapter 16 §Frame-scoped error policy](16-errors.md) for the broader `:on-error` story.

### Tuning checklist

Default `:drain-depth 100` is right for almost every frame. Cases for tuning:

- **Bump up** if you've got a frame that legitimately fans out deep cascades — bulk-import flows, multi-step migrations, test fixtures that exercise long sagas. 500 or 1000 are typical bumped values.

- **Bump down** if you've got an interactive surface (a story variant, a dev sandbox) where any runaway cascade should fail fast rather than waste a user's clock on a runaway loop. `:preset :story` defaults to 16 for exactly this reason.

- **Don't bump in production unless you've audited why your cascade is long.** A production frame routinely hitting 50+ depth is a code smell — usually a state machine ping-ponging, or an unintended self-dispatch. Bumping the ceiling masks the design issue; fixing the dispatch loop is the right move.

If you're not sure what depth your cascades typically reach, the trace surface tells you: every successful drain records its depth as part of the per-cascade trace (visible in Causa). Check the trace for your typical user actions; pick a ceiling at 5x the observed maximum.

### A note on `:always`-depth and `:raise`-depth

Inside a state machine, `:always` (eventless transitions) and `:raise` (action-scoped re-dispatch) each have their own depth ceilings — independent of the outer drain. Their defaults are both **16**, and they emit their own error categories (`:rf.error/machine-always-depth-exceeded`, `:rf.error/machine-raise-depth-exceeded`). The next section covers `:always` and friends in detail; the depth ceilings on them are part of the same defense-in-depth shape as the outer drain. Three independent depth counters, three independent ceilings, three independent abort paths — each catching the runaway-loop case at its own layer.

---

## Reserved namespaces

re-frame2 reserves a single root keyword namespace for framework-owned ids: `:rf/*`. Every framework runtime id — events, fx, cofx, subs, app-db keys, trace operations, error categories, warnings, machine lifecycle events, route events, navigation fx, SSR advisories, MCP wire markers, **everything** — lives under `:rf/*` or one of its sub-namespaces. This section is the human-readable catalogue. The linter checks this list; the migration agent checks this list; new Spec areas extend it by additive change. User code MUST NOT register handlers, fx, subs, or frames under `:rf/*`.

The normative catalogue lives at [Conventions.md §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned). This section is the guide-side narrative — the same names, in the same order, with one-line "what each one is for" so you can scan rather than read.

### Why one root

Old re-frame used 14 separate top-level prefixes: `:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, and so on. Each Spec area picked its own prefix. The result was that "is this framework-owned?" became a memorisation question — every reserved name lived in a different mental bucket, and a user registering `:route/checkout` could find it colliding with a route name they wrote half a year ago.

re-frame2 collapses to **one root prefix** and hierarchical sub-namespaces under it. Every framework-owned name starts `:rf` and a slash or a dot — the answer to "is this framework-owned?" is one character: does it start with `:rf`?

That's it. `:rf.frame/...`, `:rf.error/...`, `:rf.http/...`, `:rf.machine/...`, `:rf.size/...`, `:rf.route/...`, `:rf.epoch/...`, `:rf.causa/...` — they're all framework-owned by virtue of the prefix. Pick anything outside the prefix and the framework's stance is "your name, your problem."

### The catalogue

Every reserved framework-owned sub-namespace. The set is fixed-and-additive (entries never repurposed; new ones added by Spec change).

| Sub-namespace | What for | Owning spec |
|---|---|---|
| `:rf/*` | Pattern-level events / fx / app-db keys / subs; the universal default frame id `:rf/default` | 002 / 011 / 012 |
| `:rf.frame/<gensym>` | Anonymous frame ids minted by `make-frame` | 002 |
| `:rf.frame/<operation>` | Frame-lifecycle trace operations (`drain-interrupted`, `destroyed`, …) | 002 / 009 |
| `:rf.registry/*` | Registrar-mutation trace operations (`handler-registered`, `handler-cleared`, `handler-replaced`) | 001 / 009 |
| `:rf.fx/*` | Effect-resolution advisories (`:rf.fx/skipped-on-platform`, `:rf.fx/override-applied`) and reserved fx args (`:rf.fx/spawn-args`) | 002 / 009 |
| `:rf.cofx/*` | Cofx-resolution advisories (e.g. `:rf.cofx/skipped-on-platform`) | 009 / 011 |
| `:rf.error/*` | Error trace operations — the closed `:rf.error/<category>` taxonomy ([ch.16 — Errors](16-errors.md)) | 009 |
| `:rf.warning/*` | Warning trace operations — same shape as errors, severity `:warning` | 009 |
| `:rf.machine/*` | Machine lifecycle + transition trace operations; framework machine subs (`[:rf/machine <id>]`) | 005 |
| `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*` | Sub-areas of machine traces (further hierarchy under `:rf.machine`) | 005 |
| `:rf.route/*` | Framework routing events + subs + trace operations ([ch.19 — Routing reference](19-routing-ref.md)) | 012 |
| `:rf.nav/*` | Navigation fx ids (`:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/external`) | 012 |
| `:rf.ssr/*` | SSR-specific advisories (hydration mismatch, head mismatch, …) | 011 |
| `:rf.server/*` | Server-side response-shape fx (`:rf.server/set-status`, `-set-cookie`, `-redirect`, `-error-projection`, `-set-header`, `-append-header`) | 011 |
| `:rf.epoch/*` | Tool-Pair epoch operations (`restore-epoch`, version-mismatch, schema-mismatch, …) | Tool-Pair |
| `:rf.causa/*` | Canonical-devtools namespace for Causa — events, subs, fxs, app-db keys, traces | Tool-Pair |
| `:rf.assert/*` | Assertion-event vocabulary used by the post-v1 stories library's play functions and test runner | 007 |
| `:rf.test/*` | Test-runner-internal events and fx-stub ids | 008 |
| `:rf.http/*` | Managed-HTTP fx ids and failure taxonomy keys (`:rf.http/managed`, `-managed-abort`, `:rf.http/timeout`, `:rf.http/decode-failure`, …); security args slot `:rf.http/max-decoded-keys` | 014 |
| `:rf.http.interceptor/*` | Per-frame request-side interceptor chain lifecycle operations | 014 |
| `:rf.size/*` | Size-elision wire markers + policy keys (`:rf.size/large-elided`, `-threshold-bytes`, `-include-sensitive?`, `-include-large?`, …) | 009 |
| `:rf.elision/*` | Sentinel-handle namespace for the `:rf.elision/at` shape used by `get-path` to re-fetch an elided value | 009 |
| `:rf.mcp/*` | Cross-MCP wire-vocabulary markers (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, `:rf.mcp/ref`, `:rf.mcp/cache-hit`, `:rf.mcp/cursor-stale`) | Tool-Pair |
| `:rf.trace/*` | Trace-channel control slots — closed set of three: `:rf.trace/no-emit?`, `:rf.trace/trigger-handler`, `:rf.trace/call-site` | 009 |
| `:rf.route.nav-token/*` | Navigation-token lifecycle trace operations (`allocated`, `stale-suppressed`) | 012 / 009 |
| `:rf.adapter/*` | Substrate-adapter `:kind` discriminator values (`:rf.adapter/reagent`, `:rf.adapter/reagent-slim`, `:rf.adapter/uix`, `:rf.adapter/helix`, `:rf.adapter/plain-atom`, `:rf.adapter/ssr`) | 006 |

The answer to "what's reserved?" is "anything that starts with `:rf.` and a slash."

### How the rules apply

Three rules. They cover every collision case.

#### 1. User-registered ids must not collide

```clojure
;; FORBIDDEN — :rf/hydrate is a framework-pattern-level event
(rf/reg-event-fx :rf/hydrate ...)

;; FORBIDDEN — :rf.http/my-thing sits under a framework-reserved prefix
(rf/reg-fx :rf.http/my-thing ...)

;; FORBIDDEN — any segment under :rf.frame/* is reserved
(rf/reg-event-fx :rf.frame/my-custom-init ...)
```

The linter flags the registration. The migration agent flags the registration. The runtime registrar's `handler-replaced` trace fires loudly. If you must override a framework event for legitimate reasons (test fixtures replacing `:rf/hydrate` in-test, for example), use the documented extension points — `:on-create` on a test frame, `:fx-overrides` on a per-dispatch basis — not raw registration over a reserved id.

#### 2. Library authors choose their own prefixes

Third-party libraries pick a top-level segment of their own. The convention is "library name, no slash":

```clojure
:reagent/*           ;; the Reagent integration
:re-pressed/*        ;; the re-pressed library
:re-frisk/*          ;; the re-frisk library
:my-app/*            ;; your app's feature prefix
```

Avoid `:rf*` for library names — both `:rf` (the framework root) and `:rf.<x>` (any sub-prefix) are off-limits. The framework can grow new sub-namespaces (`:rf.queue/*`, `:rf.someday/*`) and a third-party library claiming one would silently collide.

The two **library-owned** prefixes that live *adjacent to* the framework's `:rf.*` root are:

| Library-owned prefix | Library | Used for |
|---|---|---|
| `:story.<...>` | post-v1 stories library | Story ids (`:story.auth.login-form`) and variant ids |
| `:Workspace.<...>` | post-v1 stories library | Workspace ids |

These prefixes are **library-owned, not framework-reserved** — they're canonical when the library is loaded; they don't violate the single-root invariant. Their normative catalogue lives at [Conventions.md §Library-owned prefixes](../../spec/Conventions.md#library-owned-prefixes).

#### 3. Trace-event `:operation` vocabulary is open

A library can register its own trace operations under its own prefix — `:my-lib.error/something-broke`, `:my-lib.fx/store-write`, whatever. The framework's reserved set is closed (additive only by Spec change); your library's set is open.

This matters because a tooling consumer (a Datadog shipper, an MCP server) should be able to filter by your library's prefix without worrying about colliding with the framework's set. Pick your prefix once, stamp every trace event with it, and downstream filtering is mechanical.

### How to check yourself

Three quick paths to verify a name is free:

1. **The linter.** Run your normal CLJ/CLJS lint pass; the framework ships a rule that flags any registration under `:rf*`. If your lint pass is clean, your names are clean.

2. **The migration agent.** If you're coming from re-frame v1, the migration agent's first pass renames every `:re-frame/*` to `:rf/*` and flags every user-defined name colliding with the new reserved set. The output points at every collision.

3. **`(rf/handler-ids)`.** At the REPL, ask the framework what's registered:

   ```clojure
   (filter #(re-find #"^:rf" (str %)) (rf/handler-ids))
   ```

   Anything that comes back is framework-owned (or a stamped trace operation, which uses the same prefix space). If your name shows up here when you didn't intend it to, you've collided.

### When the framework wants to add a name

The framework adds names by **additive change to the Conventions.md table**. New sub-namespaces ship under `:rf.<spec-area>/*` rather than inventing a top-level prefix. The fixed-and-additive contract means existing entries can't be repurposed and can't be removed — your code under, say, `:my-app.http/*` keeps working; a brand-new `:rf.deferred/*` namespace appears (because Spec 015 introduced it) without disturbing anything.

If you've registered a handler under a name that looks like a future framework reservation but isn't yet, the migration agent will flag it when the framework eventually claims that name. The fix is mechanical: rename your handler. The framework's commitment is that this case is rare; the catalogue above is the practical upper bound on what you should consider off-limits forever.

---

## Machine substrate features

[Chapter 11 — State machines](11-machines.md) names four state-node keys in passing: `:always`, `:after`, `:spawn`, `:spawn-all`. Plus `:final?` / `:on-done` / `:output-key` for terminal states. Plus parallel regions. This section is the worked-example tour. Each piece answers one question: "what does this key let me write, and what does the framework do for me?"

Read this when you've outgrown a flat-FSM machine and your dynamic model wants to express something xstate-shaped. The substrate has the capability; the guide should have the worked example. None of these keys is exotic — they're all sugar for things you could express by hand. The sugar earns its keep because the desugared shape is verbose, the patterns are mechanical, and stamping them by name lets tooling reason about your machine the way a flat FSM is reasoned about.

### The four substrate keys at a glance

| Key | What it does | Sugar for |
|---|---|---|
| `:always` | Eventless transition — fires when its guard becomes true | A `:raise` of a synthetic event from every action that could enable the condition |
| `:after` | Delayed transition — fires after a wall-clock delay | `:dispatch-later` + epoch-stamped synthetic event + stale check |
| `:spawn` | Declarative actor — spawn-on-entry, destroy-on-exit | `:rf.machine/spawn` in `:entry`, `:rf.machine/destroy` in `:exit` |
| `:spawn-all` | Spawn N actors in parallel, join on a condition | N `:spawn`s + join-state bookkeeping + per-condition resolution |

Each key is *declarative* — the runtime walks the spec at registration time and rewrites it into the underlying primitive. The runtime sees the desugared form; tooling sees the original spec; you write whichever is more readable.

### `:always` — eventless transitions

> "After this snapshot just changed, if condition X is true, immediately go to state Y."

```clojure
{:checking-form
 {:always [{:guard :form-valid?   :target :submitting}
           {:guard :form-invalid? :target :show-errors}]
  :on     {:edit {:action :merge-edits}}}}
```

When the machine lands in `:checking-form` (by any path), the runtime checks each `:always` entry in order, first-match-wins. If `:form-valid?` returns true, the machine transitions to `:submitting` immediately. If `:form-invalid?` returns true, it goes to `:show-errors`. If neither matches, the machine stays in `:checking-form` and waits for `:edit` to do something interesting.

The key property: **the externally-observable transition is the fixed point of the `:always` loop.** External observers (subs, other machines, tools) see only the post-cascade settled state. The intermediate "we entered `:checking-form` for one tick" is invisible. That's xstate / SCXML *macrostep* semantics.

#### The shape

`:always` is a vector of guarded transition specs. Each entry has `:guard` (a keyword resolving against the machine's `:guards` map) and `:target` (the destination state). Optional `:action` runs as part of the transition.

```clojure
(rf/reg-machine :form-flow
  {:initial :editing
   :guards  {:form-valid?   (fn [{data :data}] (every? string? (vals (:fields data))))
             :form-invalid? (fn [{data :data}] (some empty? (vals (:fields data))))}
   :states
   {:editing       {:on {:check :checking-form}}
    :checking-form {:always [{:guard :form-valid?   :target :submitting}
                              {:guard :form-invalid? :target :show-errors}]}
    :show-errors   {:on {:edit :editing}}
    :submitting    {:on {:done :complete}}
    :complete      {}}})
```

#### Microstep depth limit

The `:always` loop has a depth ceiling — default **16**, configurable per-machine via `:always-depth-limit`. A guard that's always true would loop forever; the ceiling makes that into a structured failure rather than a freeze:

```clojure
:rf.error/machine-always-depth-exceeded
;; with :tags {:machine-id <id> :depth 16 :path [<state> <state> <state> ...]}
```

The cascade halts with the snapshot **uncommitted**; observers don't see the partial path. Same atomic-rollback shape as the outer drain ([Drain depth and error recovery](#drain-depth-and-error-recovery) above).

#### Self-loops rejected at registration

A `:always` targeting its own state with the same `:guard` is a registration-time error:

```clojure
;; REJECTED — :rf.error/machine-always-self-loop
{:checking-form
 {:always [{:guard :form-valid? :target :checking-form}]}}
```

Rationale: the loop either fires repeatedly to depth-exceeded (if the guard stays true) or is a no-op (if the guard flips on first hit). In both cases the author intended something else. Catch the typo at registration.

A self-targeting `:always` with a *different* guard — used as a re-entry on a changed condition — is permitted. Only the same-guard same-target case is rejected.

For the full normative grammar see [005 §Eventless `:always` transitions](../../spec/005-StateMachines.md).

### `:after` — delayed transitions

> "If the machine is still in this state N milliseconds from now, move to state Y."

```clojure
{:splash
 {:after {3000 :main                              ;; show splash for 3 seconds
          5000 {:guard :network-slow? :target :slow-warning}
          30000 :hard-timeout}
  :on    {:user-clicked-skip :main}}}
```

Three timers run concurrently from the moment the machine enters `:splash`. Whichever timer fires first **and** matches its guard (if any) triggers its transition. The state's exit cancels any sibling timers — they go stale and silently drop on their eventual firing.

This is the canonical primitive for splash screens, polling, slow-connection nudges, soft and hard timeouts, animation gates — anything where "time elapsed in this state" is itself the signal.

#### The shape

`:after` is a map from delay (milliseconds) to either:

- a target state keyword (`3000 :main` — fire unconditionally after 3 s),
- a transition spec map (`5000 {:guard :network-slow? :target :slow-warning}` — fire after 5 s if guard passes, else suppress and let other timers continue).

```clojure
(rf/reg-machine :load-flow
  {:initial :loading
   :guards  {:still-loading? (fn [{data :data}] (not (:result data)))}
   :states
   {:loading {:after {30000 {:guard :still-loading? :target :hard-error}}
              :on    {:loaded :ready
                      :failed :error}}
    :ready   {}
    :error   {}
    :hard-error {}}})
```

If `:loaded` arrives before 30 s, the machine transitions to `:ready` and the timer cancels. If 30 s elapse, the timer fires; the guard checks whether we're still loading; if so the machine transitions to `:hard-error`.

#### Stale timers + epoch-based cancellation

You don't need to write cancellation logic. The framework uses an **epoch counter** stamped into the machine's `:data` to detect stale timers — every state exit increments the counter, every scheduled timer carries the epoch at scheduling time, and the receiving handler validates the carried epoch against the current one. A mismatch silently drops the timer and emits `:rf.machine.timer/stale-after` for observability.

The pattern is general: any async-shaped feature that re-enters a state can use epoch-based stale detection rather than imperative cancel APIs. See [Pattern-StaleDetection.md](../../spec/Pattern-StaleDetection.md) for the cross-cutting form; routing's navigation tokens ([ch.19 — Routing reference](19-routing-ref.md)) use the same shape.

#### SSR no-op

`:after` no-ops in SSR mode. Entry actions don't schedule timers; the synthetic timer-elapsed event is never emitted. The server renders the current `:state` statically; the client hydrates that state and timers begin from hydration. This is the same kind of substrate-aware behaviour as `:rf.http/managed` — the framework picks the right thing per platform.

For the full grammar see [005 §Delayed `:after` transitions](../../spec/005-StateMachines.md).

### `:spawn` — declarative child actors

> "While in this state, run a child machine. When we leave the state, destroy it."

```clojure
{:fetching
 {:spawn {:machine-id :http/protocol
           :data       {:url "/api/profile"}
           :on-spawn   (fn [{data :data id :id}] (assoc data :pending id))
           :start      [:begin]}
  :on     {:succeeded :loaded
           :failed    :error}}}
```

Entering `:fetching` spawns a `:http/protocol` actor; leaving `:fetching` destroys it. The child's lifetime is bound to the parent state's occupancy. If you write the spawn-and-destroy by hand, it looks like:

```clojure
;; what make-machine-handler desugars the :spawn into:
{:fetching
 {:entry (fn [{data :data}]
           {:fx [[:rf.machine/spawn {:machine-id :http/protocol
                                     :data       {:url "/api/profile"}
                                     :on-spawn   (fn [{d :data id :id}] (assoc d :pending id))
                                     :start      [:begin]
                                     :rf/parent-id <this-machine>
                                     :rf/spawn-id [:fetching]}]]})
  :exit  (fn [_]
           {:fx [[:rf.machine/destroy {:rf/parent-id <this-machine>
                                       :rf/spawn-id [:fetching]}]]})
  :on    {:succeeded :loaded
          :failed    :error}}}
```

The runtime sees the second form; you wrote the first. Same machine.

#### Key slots

| Key | Purpose |
|---|---|
| `:machine-id` *or* `:definition` | Which machine to spawn (registered id, or inline transition table) |
| `:data` | Initial data — literal map or `(fn [snapshot event] data)` |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — how the parent records the child id |
| `:on-done` | `(fn [data result] new-data)` — fires when child enters `:final?` (see below) |
| `:start` | Event vector dispatched to the newborn after spawn |
| `:spawn-id` | Explicit id instead of gensym — useful for tests / per-state singletons |
| `:id-prefix` | Base for the gensym'd id (defaults to `:machine-id`) |

#### What about timeouts?

`:spawn` doesn't take a `:timeout-ms` slot. Wall-clock timeouts on the spawned actor live on the *parent state's* `:after` map. One primitive, not two:

```clojure
{:authenticating
 {:spawn {:machine-id :auth-flow}
  :after  {30000 :auth-failed}                ;; 30 s wall-clock guard
  :on     {:auth/succeeded :authenticated}}}
```

When the 30 s `:after` fires, the parent's exit cascade destroys the `:auth-flow` child (which itself cascades any in-flight `:rf.http/managed` aborts — see [Cancellation cascade](../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts)). The timer is anchored to the parent state's entry, not to any HTTP attempt; the child's internal retries can't outlive the parent's deadline.

For the full description of `:spawn`'s desugaring, composition with `:entry` / `:exit`, hierarchical composition, error categories, see [005 §Declarative `:spawn`](../../spec/005-StateMachines.md).

### `:spawn-all` — spawn-and-join

> "Spawn N children in parallel. When the join condition resolves, fire a parent event."

```clojure
{:hydrating
 {:spawn-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all                      ;; or :any / {:n 2} / {:fn pred}
   :on-child-done    :child/done               ;; child → parent event keyword
   :on-child-error   :child/error
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/failed]}
  :after {60000 :hydrate/timed-out}            ;; whole-join wall-clock guard
  :on    {:hydrate/done       :ready
          :hydrate/failed     :error
          :hydrate/timed-out  :degraded}}}
```

Four children spawn in parallel. The runtime tracks completions; the join condition (`:all` here — every child must signal done) resolves when all four `:child/done` events arrive; the parent's `:hydrate/done` event fires; the machine transitions to `:ready`.

#### Join condition discriminators

| `:join` | Resolves when |
|---|---|
| `:all` (default) | Every `:children` entry has signalled `:on-child-done` |
| `:any` | The first `:on-child-done` arrives |
| `{:n N}` | The Nth `:on-child-done` arrives |
| `{:fn (fn [{:keys [done failed]}] truthy)}` | Your predicate returns truthy |

Each option fires `:on-some-complete` (for `:any` / `{:n N}` / `{:fn}`) or `:on-all-complete` (for `:all`). Any child failure short-circuits via `:on-any-failed` if you've declared it.

#### Cancel-on-decision (default `true`)

When the join resolves, siblings still in flight are cancelled. Each cancelled sibling has its `:rf.machine/destroy` fired (with the in-flight `:rf.http/managed` aborts cascading), and the runtime emits `:rf.machine.spawn/cancelled-on-join-resolution` for each.

Apps that want non-cancelling joins (analytics fan-out where each child is independently valuable) declare `:cancel-on-decision? false` — siblings run to completion; their results land in the join-state but trigger no further parent event because `:resolved?` already flipped.

#### What `:spawn-all` *isn't*

It isn't an "everything happens at once" primitive — children spawn in source order, but each child runs as its own actor with its own drain. The "parallelism" is logical-actor-parallelism, not OS-thread-parallelism. (CLJS is single-threaded; JVM SSR may execute multiple actors on multiple threads, but the contract is "the runtime coordinates the join.")

For the full description see [005 §Spawn-and-join via `:spawn-all`](../../spec/005-StateMachines.md).

### `:final?` / `:on-done` / `:output-key` — terminal states

> "When the machine enters this leaf, finish — and optionally tell the parent what came of it."

```clojure
;; Child machine — declares its terminal state with :final? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data ev :event}]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})

;; Parent — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle {:on {:submit :authenticating}}
    :authenticating
    {:spawn {:machine-id :auth-flow
              :on-done    (fn [{data :data result :result}] (assoc data :token result))}
     :on    {:auth/cancelled :idle}}}})
```

When `:auth-flow` enters `:done`, the runtime:

1. Reads the child's `:data` at `:output-key :token` — call it `result`.
2. Looks up the parent's `:spawn` and runs `:on-done` against the parent's `:data` with that `result` — the returned map replaces the parent's `:data`.
3. Emits a `:rf.machine/done` trace event with `:machine-id`, `:output`, `:parent-id`.
4. Tears down the child via the standard destroy path (with `:reason :rf.machine/finished`).

#### Singletons too

A *singleton* machine (registered top-level, no parent `:spawn`) reaching `:final?` **also auto-destroys**. "Final means final." If you want a persistent terminal state, **omit `:final?`** and use an ordinary leaf state. This is the most common gotcha for users meeting `:final?` for the first time, so it's worth saying out loud.

#### Constraints

- **Leaf-only.** A compound state can't itself be `:final?`. Express finality with a leaf inside the compound.
- **No `:on`, `:always`, `:after`, `:spawn`, `:spawn-all` on a `:final?` state.** Final means final — no further transitions.
- **`:output-key` requires `:final?`.** A non-final state declaring `:output-key` is a registration error.

#### Parallel regions

A leaf inside one region of a parallel-region machine may declare `:final? true`; the meaning is "**this region** has reached its final state." That region halts; sibling regions continue. The parent machine as a whole is `:final?` only when EVERY region's active state is `:final?` — at which point the auto-destroy and `:on-done` cascade fires as usual.

For the full normative description see [005 §Final states](../../spec/005-StateMachines.md).

### Parallel regions — orthogonal axes of one feature

```clojure
(rf/reg-machine :nine-states
  {:type    :parallel
   :regions
   {:data {:initial :loading
           :states {:loading {:on {:loaded :ready :failed :failed}}
                    :ready   {}
                    :failed  {}}}
    :form {:initial :neutral
           :states {:neutral  {:on {:edit :invalid}}
                    :invalid  {:on {:fix  :valid}}
                    :valid    {}}}
    :mode {:initial :active
           :states {:active {:on {:done :done}}
                    :done   {}}}}})
```

Three regions run **simultaneously** from one machine. Each region has its own state-tree and reacts to events independently — `:loaded` advances the `:data` region, `:edit` advances the `:form` region, `:done` advances the `:mode` region. The whole machine's snapshot at any moment is a map:

```clojure
{:state {:data :ready :form :valid :mode :active}
 :data  <shared :data slot for all three regions>
 :tags  #{<union of all three regions' active-state tags>}}
```

#### When to reach for parallel regions

Use them when the **regions are orthogonal axes of one feature** — different axes of "what is this page doing right now?" that should compose freely. The motivating case is the Nine States pattern ([Pattern-NineStates](../../spec/Pattern-NineStates.md)): a page-level convention whose render decisions slice across (data cardinality × form validity × mode).

If your regions are conceptually **independent features that don't share data**, the right answer is *N separate machines* — separate `[:rf/machines <id>]` entries coordinated via cross-actor dispatch. Both patterns ship; choose by domain shape.

#### Per-region scoping

`:after` timers and `:spawn` lifetimes are per-region. A `:after` on the `:data` region doesn't get cancelled when the `:form` region transitions. The runtime maintains a per-region epoch counter (`:rf/after-epoch-by-region` inside `:data`) so a sibling region's transition doesn't invalidate this region's in-flight timers.

`:always` cascades similarly fire per region; tags compose by union across active states.

For the full normative description see [005 §Parallel regions](../../spec/005-StateMachines.md).

### What lives in `:data`, what the runtime owns

A few `:rf/*` keys appear inside a machine's `:data` slot. These are runtime-owned — your machine bodies should read them, never write under them:

| Key | Meaning |
|---|---|
| `:rf/after-epoch` | Per-machine epoch counter for `:after`-timer stale detection (flat / compound) |
| `:rf/after-epoch-by-region` | Per-region counter for `:after`-timer stale detection (parallel regions) |
| `:rf/self-id` | The machine's own gensym'd id (set by spawn-fx for spawned actors) |
| `:rf/parent-id` | The parent machine's id (set on `:spawn` / `:spawn-all` children) |
| `:rf/spawn-id` | The `:spawn`-bearing state's prefix-path (used to address the spawn-registry slot) |
| `:rf/spawn-all-id` / `:rf/spawn-all-child-id` | `:spawn-all` analogues |

These slots are documented at [Conventions.md §Reserved snapshot-internal keys](../../spec/Conventions.md#reserved-snapshot-internal-keys-machine-runtime). Their persistence behaviour is also documented there (some survive `pr-str` / SSR hydration; some are transient).

---

## Privacy and elision in practice

You're building an app that handles money, identity, or attachments. Your trace stream is brilliant for debugging — and a liability if it ships a credit-card number to Datadog or a 5 MB scanned passport to your AI pair-programmer. This section walks you through the four tiers of declaration that keep that data out of the wire, in the order you'll reach for them.

The reference for the underlying machinery already lives in [ch.24 — Privacy](24-privacy.md) and [ch.25 — Large blobs](25-large-blobs.md). This section is the tutorial layered on top of those references: a single running example, four progressive tiers, and the trace output you'll actually see at each step.

### Why this exists

re-frame2's third pillar is one trace surface that every tool reads — Causa for the cascade graph, re-frame2-pair-mcp for AI pairing, story for playgrounds, the Datadog shipper from [ch.23 — Observability](23-observability.md) for production observability. That uniformity is the killer feature when you're debugging. It is also the killer threat: every event your app dispatches and every `app-db` snapshot the runtime captures rides the same bus. If the bus goes off-box without privacy honouring, your customer's card number lands in five places at once.

The framework's stance is **declare once at the source of truth, every consumer honours the declaration**. You write a flag on one line; the runtime substitutes a sentinel everywhere that flag's path appears. No per-consumer plumbing.

### The running example — a payments-and-records app

Imagine you're building BillFlow, a small SaaS that:

- collects card details for paid plans (sensitive PII)
- lets users download a GDPR "all my data" export bundle (sensitive composition)
- lets users attach a profile photo or a scanned ID (large blobs, sometimes sensitive)
- runs server-side jobs that import patient records from a clinic's CSV (sensitive blobs you didn't write the schema for)

Four scenarios, four tiers of disclosure. We'll build each one up.

### Tier 1 — one flag on one schema slot

You're shipping the card-details form. The user types their PAN, expiry, and CVV; your handler stores them in `app-db` long enough to call the payment processor's tokenisation endpoint, then clears the slot.

Without any declaration, here is what every consumer of the trace bus sees the moment the user clicks Pay:

```clojure
;; BEFORE — no :sensitive? declaration
{:operation :event/dispatched
 :tags      {:event [:payments/tokenise-card
                     {:pan        "4242424242424242"
                      :exp        "12/29"
                      :cvv        "737"
                      :postcode   "SW1A 1AA"}]}
 :source    :user
 ...}
```

Datadog now has the PAN. The Causa cascade graph has the PAN. The re-frame2-pair-mcp agent attached to your debug session has the PAN. The story recorder you used to capture the bug yesterday has the PAN baked into the saved scenario file. You did nothing wrong — you just hadn't told the framework which field was sensitive.

Declare it once on the schema:

```clojure
(rf/reg-app-schema
  [:payments/draft-card]
  [:map
   [:pan      {:sensitive? true} :string]
   [:exp      {:sensitive? true} :string]
   [:cvv      {:sensitive? true} :string]
   [:postcode :string]])                  ;; not sensitive — useful for fraud signals
```

That's the whole declaration. You don't change the handler. You don't add an interceptor. You don't stamp anything on the dispatch. The handler still receives the real PAN when it runs — handlers need the real value to do the work.

Now here is what every consumer sees:

```clojure
;; AFTER — :sensitive? on three schema slots
{:operation  :event/dispatched
 :tags       {:event [:payments/tokenise-card
                      {:pan        :rf/redacted
                       :exp        :rf/redacted
                       :cvv        :rf/redacted
                       :postcode   "SW1A 1AA"}]}
 :source     :user
 :sensitive? true                          ;; top-level — off-box shippers route on this
 ...}
```

Three slots redacted. `:postcode` rides through because you didn't flag it. The trace event also picked up a top-level `:sensitive? true` so the Datadog shipper from [ch.23 — Observability](23-observability.md) can drop the whole event with one boolean check; the re-frame2-pair-mcp egress walker swaps `:rf/redacted` in before sending; the on-box Causa panel shows a `[● REDACTED]` chip where the PAN used to render. One flag; five consumers; no extra wiring.

If you're curious how the framework knew to walk those exact paths, the short version is: at boot the runtime extracts every `:sensitive?` claim from every registered schema into a reserved registry under `[:rf/elision :declarations]` in `app-db`, and the wire-boundary walker consults that registry on every trace emit. You don't see that machinery from where you sit. You write the flag; the platform does the wiring. [Chapter 24 — Privacy](24-privacy.md) has the full mechanism if you want it.

### Tier 2 — the handler is the unit of sensitivity

A few sprints later you ship the GDPR data-export button. The user clicks Download my data; your handler assembles their profile, their order history, their support tickets, and their app preferences into one JSON bundle and POSTs it to the user's chosen destination URL.

None of the individual slots is sensitive in isolation. Profile fields are public-by-design. Order history is normal app state. Support tickets aren't flagged. App preferences are just feature toggles. But **the bundle**, sent to an attacker-supplied destination, is a different beast — the destination URL itself is now part of an attack surface (an attacker who controls a victim's account can name their own server as the export target), and the bundle assembles enough cross-referenced fields to identify the person in ways no single slot does.

The sensitivity is a property of *this handler*, not of any one slot. Schema declarations have nowhere to attach. This is the one escape hatch:

```clojure
(rf/reg-event-fx :gdpr/export-bundle
  {:doc        "POST the user's GDPR bundle to the destination URL they nominated."
   :sensitive? true}                                ;; ← the handler scope is sensitive
  (fn [{:keys [db]} [_ destination-url]]
    {:fx [[:rf.http/managed
           {:request {:method :post
                      :url    destination-url
                      :body   (gdpr-bundle db)}}]]}))
```

Handler-meta `:sensitive?` does three things the schema-slot flag can't:

It hoists `:sensitive? true` onto every trace event the handler emits — the `:event/dispatched`, the `:rf.http/request-started`, the eventual `:rf.http/request-complete`. The destination URL, the request body, the response status, everything in the cascade inherits the flag. Off-box shippers drop the whole cascade with one branch.

Here is the `:rf.http/request-started` trace event the cascade emits:

```clojure
;; AFTER — handler-meta :sensitive? hoisted into the cascade
{:operation  :rf.http/request-started
 :tags       {:request {:method :post
                        :url    :rf/redacted          ;; destination URL is now sensitive
                        :body   :rf/redacted}}        ;; bundle body redacted as a unit
 :sensitive? true                                      ;; cascade-wide signal
 ...}
```

Note that handler-meta is the *only* escape hatch you should reach for. If a single slot is sensitive (a card number, a session token, a person's medical record number), put the flag on the schema. Handler-meta is for the genuinely cross-cutting case where no single slot's schema can carry the truth. The asymmetry tracks the underlying semantics: data-shape facts live on the schema; behaviour-scoped facts live on the handler. Picking one and only one site is what keeps the privacy story small enough to hold in your head.

### Tier 3 — the value is too big for the wire

The next feature is a profile-photo uploader. The user picks an image; you base64-encode it client-side, store the result in `app-db` so the preview can render it, and POST it to a thumbnail-generation endpoint. A typical image is 800 KB after encoding. A scanned legal document is 5 MB.

There is nothing sensitive about a profile photo — but you can't ship 5 MB inline as a trace `:app-db-after` payload. Datadog rejects the upload. The Causa panel locks up rendering it as text. The re-frame2-pair-mcp agent's context window OOMs. The trace bus assumes every payload can ride the wire; once one slot is megabytes, the assumption breaks.

This is what `:large?` is for. Same schema surface, different verb:

```clojure
(rf/reg-app-schema
  [:profile/photo-upload]
  [:map
   [:filename     :string]
   [:mime-type    :string]
   [:encoded-blob {:large? true
                   :hint   "Base64 photo preview blob"} :string]])
```

`:hint` is a free-form short string that rides on the marker — a one-line label that tells whoever's reading the elided trace what they're looking at without fetching the value. Pair it with `:large?` whenever the slot's purpose isn't obvious from the path.

After the user uploads, the `:event/db-changed` trace event looks like this:

```clojure
;; AFTER — :large? on the photo blob slot
{:operation :event/db-changed
 :tags      {:app-db-after
             {:profile/photo-upload
              {:filename      "passport.jpg"
               :mime-type     "image/jpeg"
               :encoded-blob  {:rf.size/large-elided
                               {:path   [:profile/photo-upload :encoded-blob]
                                :bytes  4982317
                                :type   :string
                                :reason :schema
                                :hint   "Base64 photo preview blob"
                                :handle [:rf.elision/at [:profile/photo-upload :encoded-blob]]}}}}}
 ...}
```

The 5 MB string is gone. A 200-byte marker took its place — and the marker still tells you where the slot lived, how big it was, what kind it was, and why it was elided. The `:handle` is the opt-in fetch path: if the re-frame2-pair-mcp agent decides it really does need the blob to answer the user's question, it calls `get-path` with the handle and the framework fetches the live value (subject to a cap-check so a hostile fetch can't shovel 5 GB through the agent).

The handler that wrote the blob never knew the marker existed. The handler body sees the real 5 MB string and operates on it; the trace surface, the on-box dev panels, and the off-box shippers all see the marker. [Chapter 25 — Large blobs](25-large-blobs.md) has the full marker schema and the consumer-side fetch flow.

#### What happens when both flags compete

You'll eventually hit a slot that's both sensitive *and* large — a base64-encoded scan of a customer's passport, say. Both flags apply. The composition rule is **sensitive wins, deterministically**:

```clojure
(rf/reg-app-schema
  [:kyc/id-document]
  [:map
   [:document-blob {:sensitive? true
                    :large?     true
                    :hint       "Scanned ID document"} :string]])
```

The slot's value is **dropped entirely**, not replaced with the size marker. The reason is subtle but important: the size marker carries `:path` and `:bytes`, which are structural facts about the slot. Leaking "there was a 5 MB blob at `[:kyc :id-document :document-blob]`" tells an attacker more than nothing — they know the customer's KYC review has a document attached, and they know its rough size. For sensitive slots that's still too much. The trace event drops the value, and the top-level `:sensitive? true` rollup lets the off-box shippers drop the whole event the way they would for any other sensitive emit.

### Tier 4 — when schemas can't reach the slot

Some apps record state into `app-db` faster than schemas can be written for it. A server-side batch job ingests a clinic's patient CSV into a transient `[:imports/staging]` slot, processes it, and clears it — the slot is short-lived and the schema for it lives in the import library, not your app. A long-running JVM SSR session accumulates dozens of these transient slots over hours.

For everything in tiers 1-3 the right answer is "write the schema flag". For this case the right answer is **a build-time hook on the epoch recorder** — your code rewrites the record before the framework's ring buffer stores it and before any registered listener sees it:

```clojure
(rf/configure :epoch-history
  {:depth     200
   :redact-fn (fn [record]
                ;; Strip the transient staging slot from both pre- and post-state.
                ;; Trace events and trigger event are left alone — those are scoped
                ;; to the dispatch and don't normally contain raw PII.
                (-> record
                    (update :db-before dissoc :imports/staging)
                    (update :db-after  dissoc :imports/staging)))})
```

The framework invokes your `:redact-fn` **once per epoch record**, between the time the record is assembled and the time it is appended to the ring or fanned out to listeners. The per-frame ring buffer that backs time-travel debugging, every `register-epoch-listener!` listener you've installed, and every off-box egressor (Causa-MCP, re-frame2-pair-mcp, hosted post-mortem dashboards) all see the same record shape. There is no later listener that re-derives the raw slot you stripped.

Three things worth knowing before you reach for this:

**The hook is dev-only by default.** It rides the same production-elision gate the rest of the epoch surface rides — under `:advanced` + `goog.DEBUG=false` the recording site DCEs and your fn never runs. The hook is a debug-session safety net, not a production secret-scrub.

**Throwing is safe.** If your fn raises, the framework catches the throw, emits a `:rf.warning/epoch-redact-fn-exception` advisory (so you can see the bug in the trace stream), and falls back to recording the raw record for that one drain. The drain itself doesn't break and the registration stays in place — the next drain re-attempts. You don't need a defensive `try/catch` in the fn.

**Time-travel restore lands in whatever state you left.** If you redact `:db-after`, then a later `restore-epoch` call rewinds `app-db` *to the redacted shape*. There is no separate raw-state copy. For apps that genuinely use time-travel debugging in development against records the fn touched, prefer leaving `:db-before` and `:db-after` alone and target only `:trace-events` or `:trigger-event` (which the restore path doesn't consume). For batch-job staging slots whose lifetime is bounded by the job, redacting both is fine — you wouldn't replay a half-processed import anyway.

The hook composes with everything in tiers 1-3. The `:rf.epoch/sensitive?` rollup that listeners branch on is computed from the *raw* record's schema-declared sensitive leaves *before* your fn runs, so the rollup remains an accurate signal even when your fn erases the leaves it keyed on. You declare schema flags for the data-shape questions; the hook handles the leftover cases the schema can't cover.

### How the tiers compose — Tool-Pair and observability

The four tiers cover every wire-egress boundary the framework owns. The Causa cascade graph, the story playground recorder, and the re-frame2-pair-mcp AI surface all consume the same elided records. The Datadog shipper from [ch.23 — Observability](23-observability.md) runs `rf/elide-wire-value` over every event before fan-out. The Tool-Pair surface used by re-frame2-pair-mcp and Causa-MCP routes every direct-read response (`get-app-db`, `get-path`, `watch-epochs`) through the wire-elision walker with off-box defaults (`:include-sensitive? false`, `:include-large? false`).

The two on-box dev panels — Causa and Story — render a small `[● REDACTED]` / `[● ELIDED 5.2MB]` chip wherever a sentinel or marker lands in the view tree. The reader clicks the chip to opt in for a single live-fetch via the marker's `:handle`. That's the only way a sensitive or large value re-materialises on screen, and it's per-fetch, not session-wide.

You'll see consumer-side knobs in the published tools:

- **`:include-sensitive?` / `:include-large?`** — wire-egress flags on `rf/elide-wire-value`. Default `false` for every off-box shipper.
- **`:show-sensitive?` / `:show-large?`** — on-box devtools flags. Default `false`; the user opts in per-fetch via the chip.

The verb split — `include` for the wire, `show` for the UI — is deliberate. Wire-egress flags govern bytes leaving the process; UI flags govern pixels rendered to the dev. Both default off; both are explicit when on. If you're writing your own consumer, follow the convention — the framework's safety story rests on the defaults being conservative.

### Recap

Four declarations cover every privacy and size question your app will ask. In the order you'll reach for them:

1. **Schema-slot `:sensitive?`** — for data-shape secrets. The card number, the session token, the patient record number. One flag, every consumer honours it.
2. **Handler-meta `:sensitive?`** — for cross-cutting handler-scope sensitivity. The export bundle, the third-party POST, the operation that composes individually-innocent slots into a sensitive whole.
3. **Schema-slot `:large?` + `:hint`** — for size, not secrecy. The photo blob, the audit log, the cached PDF. The marker keeps `:path` / `:bytes` / `:hint` / `:handle` so consumers know what was elided and can opt in to fetch.
4. **`:redact-fn` on `:epoch-history`** — for the transient slots schemas can't reach. Dev-only by default; throws are safe; mind the restore caveat.

The first three are what you'll write 99% of the time. The fourth is the escape hatch for the cases the first three can't structurally cover. None of the four is an interceptor you have to wire by hand, a registration you have to remember at every call site, or a per-consumer filter you have to ship to every tool that reads the bus.

---

## Exceptions under `:sensitive?`

The path-marked `:sensitive?` declarations from [Spec 015](../../spec/015-Data-Classification.md) — what [chapter 24 — Privacy](24-privacy.md) and the [Privacy and elision in practice](#privacy-and-elision-in-practice) section above walked you through — redact at five observation surfaces: the trace bus, Causa, MCP, AI/LLM context, and third-party log sinks. They walk `app-db`, event arg-maps, sub outputs, fx inputs, cofx injections, machine `:data`, and flow outputs at emit time. They do **not** walk exception messages or `ex-data` maps.

That is a small but real residual: if your handler reads a sensitive-path value and then `(throw (ex-info "User <email> failed login" {:user/email email}))`, the resulting `:rf.error/handler-exception` trace event carries the email **verbatim** in `:exception-message` and `:exception-data`. The framework has no way to know the string was assembled from a sensitive path; the ex-data map carries arbitrary author-supplied keys, not paths into a marked shape.

This section tells you how to think about that gap, the three patterns that close it, and a tiny helper you can copy into your app.

### Why the gap exists

The path-mark contract is a property of the data shape — "this slot in app-db is sensitive", "this key in the event arg-map is sensitive". The walker resolves a *path* against a *known shape* at emit time. That works because the shapes are framework-shaped: the trace event carries `:tags :app-db-after` (the app-db shape; marks resolve against it), `:tags :event` (the event vector; marks resolve against arg #1), `:tags :sub-output` (the sub's return value), and so on. The walker knows where to look because the shape is documented.

An exception is different. The `ex-message` string is opaque — by the time the walker sees it, the sensitive value has been concatenated into a flat string with surrounding context. There is no path that resolves to the substring. The `ex-data` map is author-supplied with author-chosen keys — `{:user/email "..."}` has no relationship to the `[:user :email]` path in app-db; the same value appears in two different shape namespaces and the walker has no rule that says they're the same datum.

The framework's stance is the [Spec 015 §Out of scope](../../spec/015-Data-Classification.md#out-of-scope-explicit-non-goals) stance: **the contract is a leak-prevention overlay on observability, not a full taint-tracking system**. The author owns the policy at the boundary where their code assembles strings and maps from sensitive paths. The framework redacts everywhere it can resolve a path; the exception path is the one place where the author has to participate.

### What the trace event looks like

Walk through a concrete example. You've marked `[:user :email]` sensitive via `set-marks`:

```clojure
(rf/set-marks :rf/default
  {[:user :email]         :sensitive
   [:user :password-hash] :sensitive})
```

Your login handler reads the email and throws if the credentials don't validate:

```clojure
(rf/reg-event-fx :auth/log-in
  (fn [{:keys [db]} [_ {:keys [submitted-password]}]]
    (let [email         (get-in db [:user :email])
          password-hash (get-in db [:user :password-hash])]
      (when-not (valid? submitted-password password-hash)
        ;; ANTI-PATTERN — the email lands in the exception message.
        (throw (ex-info (str "User " email " failed login")
                        {:user/email email
                         :reason     :invalid-credentials}))))))
```

The `:rf.error/handler-exception` trace event the framework emits has the path-marked surfaces correctly redacted — `:app-db-before`, `:app-db-after`, the `:event` slot (no email there, just `submitted-password` which you haven't marked). But the exception-derived fields carry the raw email:

```clojure
;; The trace event the cascade emits:
{:operation         :rf.error/handler-exception
 :tags              {:event             [:auth/log-in {:submitted-password "***"}]
                     :handler-id        :auth/log-in
                     :exception-message "User alice@example.com failed login"   ;; LEAKS
                     :exception-data    {:user/email "alice@example.com"        ;; LEAKS
                                         :reason     :invalid-credentials}
                     :app-db-after      {:user {:email          :rf/redacted    ;; redacted, as expected
                                                :password-hash  :rf/redacted}}}
 :sensitive?        true                                                          ;; correctly rolled up
 ...}
```

The top-level `:sensitive? true` rolls up because *some* leaf in the record overlapped a marked path — so the Datadog shipper from [chapter 23 — Observability](23-observability.md) drops the whole event. That covers the off-box case. But the on-box dev surfaces (Causa's Event Detail panel, the re-frame2-pair-mcp AI surface in `:show-sensitive? true` mode, a story scenario you saved for replay) all render `:exception-message` and `:exception-data` verbatim. The leaf-level redaction the walker performs on `:app-db-after` does not reach the assembled message string or the author-keyed ex-data map.

That is the gap. It is one screenful — but it is real, and it is the residual surface every author who reads or writes a sensitive path needs to know about.

### The three patterns that close the gap

Pick the one that fits the call site. They compose; the recommended discipline is to use the third for the bulk of your code and reach for the others where the third doesn't fit.

#### Pattern A — don't interpolate sensitive paths into messages

The cheapest fix is to not assemble the message from sensitive data at all. The exception is for *the dev reading the trace*; the dev does not need the email to know what went wrong. The category — "invalid credentials for some user" — is what they need; the exact user identity is recoverable from `:dispatch-id` correlation or from the (correctly redacted) `:app-db-before` snapshot if they really need it.

```clojure
;; PATTERN A — message names the category, not the value.
(when-not (valid? submitted-password password-hash)
  (throw (ex-info "Invalid credentials"
                  {:reason :invalid-credentials})))
```

The trace event now reads:

```clojure
{:tags {:exception-message "Invalid credentials"
        :exception-data    {:reason :invalid-credentials}}
 ...}
```

Nothing leaks. The dev still sees the failing handler-id, the dispatch-id, and the cascade that led to the throw. They get less context per error and that's fine — the operational signal is **what went wrong**, not **whose data was involved**.

The rule of thumb: if a string substitution would have rendered a sensitive-path value, drop the substitution. Name the *category* of failure. Let correlation against the (already-redacted) app-db surfaces do the work of identifying which user / order / record it was about.

#### Pattern B — use a redaction sentinel in the message and the ex-data

Sometimes the dev genuinely needs the *structure* of the failing context, but the leaf value is sensitive. Substitute the framework's `:rf/redacted` sentinel keyword (or its string form) at the assembly site:

```clojure
;; PATTERN B — sentinel substitution at the assembly site.
(when-not (valid? submitted-password password-hash)
  (throw (ex-info "User :rf/redacted failed login"
                  {:user/email :rf/redacted
                   :reason     :invalid-credentials}))))
```

The trace event now reads:

```clojure
{:tags {:exception-message "User :rf/redacted failed login"
        :exception-data    {:user/email :rf/redacted
                            :reason     :invalid-credentials}}
 ...}
```

The dev sees the failing shape — they know an email-keyed lookup was the trigger — without seeing the email itself. The sentinel form matches what Causa's redaction chip renders for path-walked slots, so the dev's mental model is uniform: `:rf/redacted` means "the framework or the author scrubbed this".

The pattern composes with Pattern C below — the helper there emits the sentinel for you so the call site reads cleanly.

#### Pattern C — `redacted-throw` / a tiny safe-throw helper

The recommended posture is a one-line helper your app defines once and uses everywhere it throws from a handler that may have read a sensitive path. The helper takes a category keyword, an optional context map, and an optional set of keys to scrub:

```clojure
(ns my-app.errors)

(defn safe-throw
  "Throw an ex-info whose message and ex-data never carry raw values
   for the keys named in :scrub. Substitutes :rf/redacted at those keys
   in the ex-data map, and never interpolates them into the message —
   the message is the category string alone.

   Usage:
     (safe-throw :auth/invalid-credentials
                 {:user/email   email
                  :submitted-at (now)}
                 #{:user/email})

   Result: exception with message \"auth/invalid-credentials\" and
   ex-data {:user/email :rf/redacted :submitted-at <ts>}."
  ([category]
   (safe-throw category {} #{}))
  ([category context]
   (safe-throw category context #{}))
  ([category context scrub]
   (let [redacted (reduce (fn [m k] (assoc m k :rf/redacted))
                          context
                          scrub)]
     (throw (ex-info (str category)
                     (assoc redacted :reason category))))))
```

In your handlers, the call site becomes self-documenting:

```clojure
(rf/reg-event-fx :auth/log-in
  (fn [{:keys [db]} [_ {:keys [submitted-password]}]]
    (let [email         (get-in db [:user :email])
          password-hash (get-in db [:user :password-hash])]
      (when-not (valid? submitted-password password-hash)
        (errors/safe-throw :auth/invalid-credentials
                           {:user/email   email
                            :submitted-at (now)}
                           #{:user/email})))))
```

The `scrub` set is the discipline: every time you reach for `safe-throw` you ask the question "which of these keys carry sensitive content?" and you name them at the call site. The discipline is what closes the gap — the framework cannot infer the answer from the ex-data map's shape, but you can, and the call site is the only place the question has a stable answer.

The helper is twelve lines. There is nothing framework-shaped about it; it goes in your app's namespace. The point is the *convention*, not the implementation — every project that reads sensitive paths in handlers should have one.

#### Why not a framework-supplied helper?

The framework deliberately does not ship a `rf/safe-throw` for the same reason Spec 015 deliberately stops at the path boundary: the call-site knowledge of *which ex-data keys correspond to sensitive paths in this specific app* is author knowledge, not framework knowledge. A framework helper would either:

1. Demand the author name the scrub keys at every call (which is what the in-app helper does) — at which point the framework wrapping adds no value over the in-app helper.
2. Try to auto-detect sensitive ex-data keys (which is the taint-tracking system Spec 015 explicitly rejects, and would fail on author-keyed ex-data namespaces like `:user/email` that have no path-mark equivalent).

The right shape is a per-app convention. The framework's job is to redact at every observation surface where it can resolve a path; your job is the exception assembly site where the path has been flattened into a string.

### Common-case safety — when the gap doesn't bite

You don't have to be paranoid about *every* exception. Most handlers don't read sensitive paths, and most exceptions are about structural failures (a missing key, a malformed shape, a network timeout) where no sensitive data ends up in the message anyway. The gap matters only at the intersection of two facts: **the handler reads a sensitive-path value**, AND **the handler then throws with that value in the message or the ex-data map**.

A few observations that narrow the residual surface:

- **Handlers that only *write* to sensitive paths are safe.** The event arg-map is path-walked at emit time; whatever the handler does internally is invisible to the trace. The exception path matters only when the handler *reads* and then *throws*.
- **Handlers that read, compute, and write back to app-db are safe even if they throw later.** The trace event's `:app-db-after` is walked at emit time. A downstream event that subscribes to the (now correctly redacted) `[:user :email]` slot and throws from there has the same gap, but the trigger boundary moves with the read.
- **The top-level `:sensitive? true` rollup covers off-box leaks.** Where the rollup fires (any path-marked slot present in the record), every off-box shipper — Datadog, Sentry, re-frame2-pair-mcp egress, story recorders writing to disk — drops the whole event by policy. The remaining surface is the *on-box dev panel* (Causa, the in-process REPL) where `:show-sensitive? true` would render the leaf, plus the `:exception-message` and `:exception-data` fields that the walker doesn't touch.

So in practice you write the helper once, you use it in the handlers that read marked paths, and the on-box dev panel — where the dev has set `:show-sensitive? true` because they wanted to inspect the failing flow — shows them `:rf/redacted` in the slot they were going to read regardless. Off-box, the rollup carries the policy. The residual is a discipline at the assembly site, not a constant tax.

### Common pitfalls

A few patterns to watch for:

**`(str ...)` over a map that may carry sensitive paths.** A debug fallback like `(throw (ex-info (str "context: " (pr-str ctx)) {}))` pretty-prints the whole context including every sensitive leaf. If `ctx` is a slice of `app-db` (or derived from one), the exception message now carries every sensitive value the slice covered. The fix is the helper — never `str` a map that may have come from `app-db`.

**Re-throw with `(throw (ex-info ... {:cause-message (.getMessage e)}))`.** If the inner exception came from a `validate!` call or a Malli-thrown failure that included the sensitive value in *its* message, the wrap propagates the leak. The fix is the same as Pattern A: name the *category* of failure in your wrap, not the inner message. If you genuinely need the inner exception for diagnostics, attach it as `:cause` and let the dev consult it on-box via the (redacted) trace — don't flatten its message into your wrap.

**Logging at the throw site.** A `console.log` or `logger/info` call that prints the exception's full context before the throw — same shape as the gap above, with no trace-bus involvement at all, so the path-marked redaction never gets a chance. The fix is the helper plus a convention: if your app logs in addition to throwing, log the category, not the values. Or, log to the `:on-error` slot and let your registered error projector ([Spec 011 §Server error projection](../../spec/011-SSR.md#server-error-projection)) decide what to render.

### Recap — the rule of thumb

Two lines of guidance carry the whole pattern:

1. **If your handler reads a sensitive-path value and then `throw`s, assume the exception path is the leak channel and use the helper.** The framework has redacted everywhere it can resolve a path; the exception is the gap because the path has been flattened.
2. **Name categories, not values, in exception messages.** The dev consuming the trace needs to know *what went wrong*; the *whose data was involved* answer lives on the correlated (and already-redacted) app-db slots, not in the message string.

The helper is the implementation of the rule. The rule is the discipline. Both are author-side; both compose with the framework's redaction at every other surface.
