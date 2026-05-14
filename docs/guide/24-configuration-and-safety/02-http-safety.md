# 24.02 — HTTP safety primitives

## TL;DR

Three defenses, one chapter. The framework caps JSON keyword interning so a hostile upstream can't burn unbounded keyword slots. It applies a 30-second timeout to managed requests so a slow-loris partner can't pin your connection pool open forever. It refuses to ship a header (or set a cookie, or issue a redirect) whose value contains `\r\n`, because that's how header injection works. All three are on by default; all three surface as structured errors when they fire.

[Chapter 10](../10-doing-http-requests.md) is the place to learn what managed-HTTP looks like to a normal app. [Chapter 11](../11-server-side.md) is the place to learn what SSR response-shape fx do. This page is the safety layer between you and a few specific classes of failure that those chapters don't dwell on. It's deliberately short because each defense is small.

## JSON keyword cap — `:rf.http/max-decoded-keys`

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

The full normative description sits at [014-HTTPRequests.md §Keyword-interning cap](../../../spec/014-HTTPRequests.md) and [Security.md §DoS by input](../../../spec/Security.md#dos-by-input).

### The symmetric routing-side cap (rf2-3k3o7)

The same accident class lives on the routing side. URL query strings are caller-controlled (deep links, partner referrals, share links) and `match-url` historically turned every query key into a keyword — exactly the unbounded-intern pattern the JSON cap above guards against. Per rf2-3k3o7 the routing parser applies a symmetric cap:

```clojure
;; URL with > 10000 unique query keys:
{:kind   :rf.error/route-too-many-keys
 :limit  10000
 :count  10001
 :url    "/search?k0=...&k10000=..."}
```

…thrown from `match-url`, propagated through the calling navigation event.

The defense layers further. When a route declares a `:query` schema, only the schema-named keys are promoted to keyword keys; unknown URL keys retain their string form in the parsed `:query` map. A `:keyword`-typed slot without an `[:enum ...]` allowlist stays as a string (the unbounded-intern site is closed); declare `[:enum :asc :desc]` for the safe, bounded keyword universe. See [012-Routing.md §Keyword-interning cap on query keys + values](../../../spec/012-Routing.md#keyword-interning-cap-on-query-keys--values-rf2-3k3o7) for the full contract.

## Slow-loris defense — `:timeout-ms` default 30000

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

See [014-HTTPRequests.md §`:timeout-ms` security defaults](../../../spec/014-HTTPRequests.md) and [Security.md §DoS by input](../../../spec/Security.md#dos-by-input).

## CRLF fail-fast on response-shape fx

This one's for the server side. SSR's response-shape fx (per [chapter 11](../11-server-side.md)) write headers, set cookies, and issue redirects:

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

The error fires on the failing fx, the response is **not** written, the caller's `:on-error` policy (or the SSR error projector, per [ch.11](../11-server-side.md)) takes over.

A few decisions are worth pointing at, because they're deliberate:

- **No strip-and-warn.** The framework does not silently delete the CRLF and continue. Silent normalisation lets attacker-encoded variants (`%0D%0A`, double-encodings, etc.) through if the downstream decoder treats them differently — and worse, it masks the bug from the dev writing the call site. Fail-fast is the only honest answer.

- **Per-attribute on cookies.** A cookie isn't one string; it's a map of `:name`, `:value`, `:domain`, `:path`, `:max-age`, `:same-site`. Each one rides as its own attribute in the `Set-Cookie:` line, and each one is checked independently. The threat model isn't "attacker controls the whole value" — it's "attacker controls the user-id flowing into `:name`, or the partner-supplied `:domain`, and only that piece can carry CRLF."

- **Structural URL check on redirects, in addition to CRLF.** `:rf.server/redirect` also rejects malformed-URL inputs at the same site, so a `:rf.error/redirect-invalid-location` surfaces both the CRLF case and the "this isn't a URL" case under one error category.

The full normative description lives at [Security.md §CRLF injection at HTTP-response boundaries](../../../spec/Security.md#crlf-injection-at-http-response-boundaries) and [011-SSR.md §Standard fx](../../../spec/011-SSR.md).

## What you don't have to do

A common pattern in older Ring-style code is "sanitise every value before you pass it to the response builder." You don't have to do that with `:rf.server/*`. The framework's stance: the **fx-handler** is the sanitisation site, not your code, and the contract is "give me your data; I'll fail-fast if you handed me something dangerous." If your code passes the framework a value with `\r\n` in it, the framework's response is "your caller has a bug, here's the error category, surface it the way you'd surface any other bug." You spend exactly zero lines of defensive code at the call site.

The same posture extends to the JSON cap and the timeout: you don't write `(try ... (catch SocketTimeoutException ...))` at every call site. You declare the `:retry` policy you want, and the framework's failure classification (`:rf.http/timeout`, `:rf.http/decode-failure`, the eight-category taxonomy from [ch.10](../10-doing-http-requests.md)) tells your reply handler what kind of failure it's seeing.

## Cross-references

- [Chapter 10 — Doing HTTP requests](../10-doing-http-requests.md) — the managed-HTTP narrative; this page is the defensive layer underneath.
- [Chapter 11 — The server side](../11-server-side.md) — the response-shape fx; this page covers their CRLF check.
- [Chapter 14 — Errors](../14-errors.md) — `:on-error` policies and per-category recovery.
- [Security.md](../../../spec/Security.md) — the threat model + defense catalogue. The eventual pattern/impl split (rf2-1g6cj) may rename this doc; the content moves with it.
- [014-HTTPRequests.md](../../../spec/014-HTTPRequests.md) — normative spec for managed-HTTP, including the keyword cap and the timeout default.
- [011-SSR.md](../../../spec/011-SSR.md) — normative spec for SSR response-shape fx, including the CRLF check site.
