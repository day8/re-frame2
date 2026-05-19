# 24.08 — Exceptions under `:sensitive?` — the residual author surface

## TL;DR

The path-marked `:sensitive?` declarations from [Spec 015](../../../spec/015-Data-Classification.md) — what [chapter 23a](../23a-privacy-secrets.md) and [chapter 24.07](07-privacy-and-elision.md) walked you through — redact at five observation surfaces: the trace bus, Causa, MCP, AI/LLM context, and third-party log sinks. They walk `app-db`, event arg-maps, sub outputs, fx inputs, cofx injections, machine `:data`, and flow outputs at emit time. They do **not** walk exception messages or `ex-data` maps.

That is a small but real residual: if your handler reads a sensitive-path value and then `(throw (ex-info "User <email> failed login" {:user/email email}))`, the resulting `:rf.error/handler-exception` trace event carries the email **verbatim** in `:exception-message` and `:exception-data`. The framework has no way to know the string was assembled from a sensitive path; the ex-data map carries arbitrary author-supplied keys, not paths into a marked shape.

This page tells you how to think about that gap, the three patterns that close it, and a tiny helper you can copy into your app.

## Why the gap exists

The path-mark contract is a property of the data shape — "this slot in app-db is sensitive", "this key in the event arg-map is sensitive". The walker resolves a *path* against a *known shape* at emit time. That works because the shapes are framework-shaped: the trace event carries `:tags :app-db-after` (the app-db shape; marks resolve against it), `:tags :event` (the event vector; marks resolve against arg #1), `:tags :sub-output` (the sub's return value), and so on. The walker knows where to look because the shape is documented.

An exception is different. The `ex-message` string is opaque — by the time the walker sees it, the sensitive value has been concatenated into a flat string with surrounding context. There is no path that resolves to the substring. The `ex-data` map is author-supplied with author-chosen keys — `{:user/email "..."}` has no relationship to the `[:user :email]` path in app-db; the same value appears in two different shape namespaces and the walker has no rule that says they're the same datum.

The framework's stance is the [Spec 015 §Out of scope](../../../spec/015-Data-Classification.md#out-of-scope-explicit-non-goals) stance: **the contract is a leak-prevention overlay on observability, not a full taint-tracking system**. The author owns the policy at the boundary where their code assembles strings and maps from sensitive paths. The framework redacts everywhere it can resolve a path; the exception path is the one place where the author has to participate.

## What the trace event looks like

Walk through a concrete example. You've marked `[:user :email]` sensitive via `reg-marks`:

```clojure
(rf/reg-marks :rf/default
  {:sensitive [[:user :email]
               [:user :password-hash]]})
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

The top-level `:sensitive? true` rolls up because *some* leaf in the record overlapped a marked path — so the Datadog shipper from [chapter 22](../22-trace-to-datadog.md) drops the whole event. That covers the off-box case. But the on-box dev surfaces (Causa's Event Detail panel, the re-frame2-pair-mcp AI surface in `:show-sensitive? true` mode, a story scenario you saved for replay) all render `:exception-message` and `:exception-data` verbatim. The leaf-level redaction the walker performs on `:app-db-after` does not reach the assembled message string or the author-keyed ex-data map.

That is the gap. It is one screenful — but it is real, and it is the residual surface every author who reads or writes a sensitive path needs to know about.

## The three patterns that close the gap

Pick the one that fits the call site. They compose; the recommended discipline is to use the third for the bulk of your code and reach for the others where the third doesn't fit.

### Pattern A — don't interpolate sensitive paths into messages

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

### Pattern B — use a redaction sentinel in the message and the ex-data

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

### Pattern C — `redacted-throw` / a tiny safe-throw helper

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

### Why not a framework-supplied helper?

The framework deliberately does not ship a `rf/safe-throw` for the same reason Spec 015 deliberately stops at the path boundary: the call-site knowledge of *which ex-data keys correspond to sensitive paths in this specific app* is author knowledge, not framework knowledge. A framework helper would either:

1. Demand the author name the scrub keys at every call (which is what the in-app helper does) — at which point the framework wrapping adds no value over the in-app helper.
2. Try to auto-detect sensitive ex-data keys (which is the taint-tracking system Spec 015 explicitly rejects, and would fail on author-keyed ex-data namespaces like `:user/email` that have no path-mark equivalent).

The right shape is a per-app convention. The framework's job is to redact at every observation surface where it can resolve a path; your job is the exception assembly site where the path has been flattened into a string.

## Common-case safety — when the gap doesn't bite

You don't have to be paranoid about *every* exception. Most handlers don't read sensitive paths, and most exceptions are about structural failures (a missing key, a malformed shape, a network timeout) where no sensitive data ends up in the message anyway. The gap matters only at the intersection of two facts: **the handler reads a sensitive-path value**, AND **the handler then throws with that value in the message or the ex-data map**.

A few observations that narrow the residual surface:

- **Handlers that only *write* to sensitive paths are safe.** The event arg-map is path-walked at emit time; whatever the handler does internally is invisible to the trace. The exception path matters only when the handler *reads* and then *throws*.
- **Handlers that read, compute, and write back to app-db are safe even if they throw later.** The trace event's `:app-db-after` is walked at emit time. A downstream event that subscribes to the (now correctly redacted) `[:user :email]` slot and throws from there has the same gap, but the trigger boundary moves with the read.
- **The top-level `:sensitive? true` rollup covers off-box leaks.** Where the rollup fires (any path-marked slot present in the record), every off-box shipper — Datadog, Sentry, re-frame2-pair-mcp egress, story recorders writing to disk — drops the whole event by policy. The remaining surface is the *on-box dev panel* (Causa, the in-process REPL) where `:show-sensitive? true` would render the leaf, plus the `:exception-message` and `:exception-data` fields that the walker doesn't touch.

So in practice you write the helper once, you use it in the handlers that read marked paths, and the on-box dev panel — where the dev has set `:show-sensitive? true` because they wanted to inspect the failing flow — shows them `:rf/redacted` in the slot they were going to read regardless. Off-box, the rollup carries the policy. The residual is a discipline at the assembly site, not a constant tax.

## Common pitfalls

A few patterns to watch for:

**`(str ...)` over a map that may carry sensitive paths.** A debug fallback like `(throw (ex-info (str "context: " (pr-str ctx)) {}))` pretty-prints the whole context including every sensitive leaf. If `ctx` is a slice of `app-db` (or derived from one), the exception message now carries every sensitive value the slice covered. The fix is the helper — never `str` a map that may have come from `app-db`.

**Re-throw with `(throw (ex-info ... {:cause-message (.getMessage e)}))`.** If the inner exception came from a `validate!` call or a Malli-thrown failure that included the sensitive value in *its* message, the wrap propagates the leak. The fix is the same as Pattern A: name the *category* of failure in your wrap, not the inner message. If you genuinely need the inner exception for diagnostics, attach it as `:cause` and let the dev consult it on-box via the (redacted) trace — don't flatten its message into your wrap.

**Logging at the throw site.** A `console.log` or `logger/info` call that prints the exception's full context before the throw — same shape as the gap above, with no trace-bus involvement at all, so the path-marked redaction never gets a chance. The fix is the helper plus a convention: if your app logs in addition to throwing, log the category, not the values. Or, log to the `:on-error` slot and let your registered error projector ([Spec 011 §Server error projection](../../../spec/011-SSR.md#server-error-projection)) decide what to render.

## Recap — the rule of thumb

Two lines of guidance carry the whole pattern:

1. **If your handler reads a sensitive-path value and then `throw`s, assume the exception path is the leak channel and use the helper.** The framework has redacted everywhere it can resolve a path; the exception is the gap because the path has been flattened.
2. **Name categories, not values, in exception messages.** The dev consuming the trace needs to know *what went wrong*; the *whose data was involved* answer lives on the correlated (and already-redacted) app-db slots, not in the message string.

The helper is the implementation of the rule. The rule is the discipline. Both are author-side; both compose with the framework's redaction at every other surface.

## Next

- [24.07 — Privacy and elision in practice](07-privacy-and-elision.md) — the four tiers of path-marked declaration that this page is the residual gap for.
- [23a — Privacy reference](../23a-privacy-secrets.md) — the full mechanism behind the path-walked redaction at the trace bus.
- [14 — Errors](../14-errors.md) — the structured error vocabulary (`:rf.error/handler-exception` and friends) the trace event carries.
- [22 — Production observability](../22-trace-to-datadog.md) — the consumer side; how the top-level `:sensitive? true` rollup lets the shipper drop the whole event.
- [Spec 015 — Data Classification](../../../spec/015-Data-Classification.md) — the normative spec for path-marked sensitivity, including the explicit non-goal that closes this gap on the framework side.
- [Spec Security §Privacy / secret handling](../../../spec/Security.md#privacy--secret-handling) — the pattern-level MUST that names the author responsibility documented here.
