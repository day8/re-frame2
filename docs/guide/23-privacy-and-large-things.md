# 23 - Privacy and large things

Your auth token must not end up in a Datadog log, and a 40MB PDF must not end up in the trace buffer. Both problems have the same root cause — re-frame2's killer feature is that one trace stream feeds every tool — and, satisfyingly, both have the same shape of answer: one flag on one schema slot, opt-in, composable, honoured by every consumer downstream. This chapter is the elision story, and it's two verbs on one machine.

## The firehose is also the leak

Let me restate the thing chapter 16 sold you on, because the whole of this chapter is the bill arriving for it.

re-frame2's third pillar is a single trace surface that every tool reads. The Xray cascade graph reads it. The re-frame2-pair-mcp AI surface reads it. The Story playground recorder reads it. The Datadog shipper from [chapter 16](16-observability.md) reads it. Every event a user dispatches, every `:tags :event` payload, every `app-db` snapshot, every `:rf.http/*` request and response — they all ride the one bus. That uniformity is *the* reason the tooling is any good. Six tools telling consistent stories about your running app, because there is one story to tell.

Now turn it over. A firehose makes a magnificent debugger and a catastrophic auth-token logger. The first sign-in form on your app puts `{:password "hunter2"}` on the bus, and absent any defence that password is now visible to every dev who attaches a trace listener, every Datadog dashboard, every Sentry queue, every MCP pair-server an agent connected to. You did nothing wrong. You wrote a normal login handler. The architecture's greatest strength is, unmodified, a security incident with your customer's name on it.

And there's a second, quieter version of the same problem that isn't about secrets at all. Suppose a slice of `app-db` is *huge* — a 5MB base64-encoded scanned passport, a 100K-row audit log, an image-preview blob. The bus assumes every payload can ride the wire. The instant one slice is megabytes, that assumption snaps: off-box shippers refuse the upload, on-box panels choke trying to render a 100K-row table, and an AI agent attached to your app blows its context window trying to read a `:app-db-after` payload that's mostly base64. Not a leak — a denial of service against your own observability.

Two failure modes, one cause: data goes on the bus that shouldn't go on the bus *in that form*. The framework's answer to both is **elision** — substitute something small and safe at the wire boundary before the value egresses — and it's the same machinery for both, with one flag per failure mode.

## The framework's stance: declare the truth, the walker enforces it

There's an obvious-looking wrong answer here, and it's worth naming so we can rule it out: *filter at the consumer.* Tell every tool "drop the password before you ship." This fails, and it fails for a structural reason. Consumers are written by humans who forget, by AI agents who don't know which slot is sensitive unless told, and by ops engineers wiring up a published integration they didn't read the source of. Asking N consumers to each independently get the redaction right is asking for the one that doesn't to leak everything.

So the framework inverts it: **the registration declares the truth, the walker enforces it at the boundary, every consumer reads the already-safe result.** Three pieces, and exactly one of them is yours to write — the declaration. You put a flag on a schema slot; the framework's wire-boundary walker substitutes a sentinel everywhere that slot's path appears in anything that egresses; every consumer, without doing anything, sees the redacted shape. One declaration, every consumer honours it, no per-tool plumbing.

This is the same move re-frame2 makes everywhere: push the decision to the one site where it has a stable, authoritative answer, then let the platform carry it to all the places that need it. You've seen it with schemas as the source of truth for app-db shape; privacy and size are just two more facts about a slot, declared on the same surface, in the same vocabulary.

## The one place you declare it

Both flags live on the same surface: a Malli slot's per-slot props map, the one you met in [chapter 08 — Schemas](08-schemas.md). One keyword, one map, and the whole trace-consuming world honours it.

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile      [:map [:name :string] [:email :string]]]
   [:credit-card  {:sensitive? true}                           :string]      ;; redacted in traces
   [:audit-log    {:large?     true :hint "Audit log entries"} [:vector :map]]]) ;; elided in traces
```

That's the declaration. There is no second site, no companion interceptor, no registration call. `:sensitive?` says *secret*; `:large?` says *too big to ship raw*. Same map, two verbs.

What makes this the right surface — and not, say, a handler annotation or a runtime registration — is that it's where an AI agent or a human reading the schema *already is.* The schema is the AI-first surface for app shape (per chapter 08); declaring the privacy claim and the size claim on the same line as the type means an agent reading `[:credit-card {:sensitive? true} :string]` sees the whole truth about the slot in one glance, in the same vocabulary as everything else. There's no separate handler-side declaration to cross-reference, no interceptor to grep for, no runtime side effect to chase. The fact lives where the type lives.

Mechanically: at boot the runtime walks every registered schema and writes the per-slot `:sensitive?` / `:large?` verdicts into a reserved registry under `[:rf/runtime :elision :declarations]` in `app-db`. At every wire-boundary emit, the walker consults that registry once per visited path. You never see this machinery from where you sit — you write the flag, the platform does the wiring — but it's worth knowing it's a boot-time extraction plus a per-emit lookup, not a runtime scan of your data on the hot path.

## `:sensitive?` — keeping secrets off the wire

`:sensitive? true` means: this value never appears in any trace, off-box ship, dev-panel render, or schema-validation error. At every wire emit the slot's value is substituted with the `:rf/redacted` sentinel, and the trace event gets `:sensitive? true` stamped at its top level so off-box listeners can drop the whole event with one boolean check.

The part people expect to be hard and isn't: **you do not write an interceptor.** The framework auto-installs the scrub for any handler that reads or writes a `:sensitive?` slot. Watch the whole surface for a credit card:

```clojure
;; 1. Declare the schema slot. This is the entire privacy declaration.
(rf/reg-app-schema
  [:user/account]
  [:map
   [:username    :string]
   [:credit-card {:sensitive? true} :string]])

;; 2. The handler that writes it. No metadata. No interceptor. Plain.
(rf/reg-event-db :user.account/update-card
  (fn [db [_ new-card-number]]
    (assoc-in db [:user/account :credit-card] new-card-number)))
```

That's all you write. Notice the asymmetry that makes this safe *and* usable: the handler body sees `new-card-number` verbatim — handlers need the real value to do their work, obviously, you can't tokenise a card you can't read. But the `:event/dispatched` trace event for the handler, and the `:event/db-changed` event that follows, both ship with `:credit-card` substituted by `:rf/redacted`. The real value flows through your code; only the *observable shadow* of it on the trace bus gets scrubbed. You did not write a `redact-interceptor`. You did not stamp the handler. The schema is the single source of truth and the framework installs the scrub.

### The one escape hatch

Schemas answer data-shape questions: "is the value at *this path* secret?" That's a fact about a *kind of thing*, stable across every handler that ever touches the path. But occasionally the unit of sensitivity isn't a slot — it's a whole *operation*.

The motivating case: a GDPR export handler. The user clicks "download all my data," and your handler assembles their profile, order history, support tickets, and preferences into one bundle and POSTs it to a destination URL the user nominated. None of the individual slots is sensitive — profile fields are public-by-design, order history is normal state, preferences are feature toggles. But *the bundle*, sent to a user-supplied destination, is a different animal: the destination URL is itself attack surface (an attacker who controls the account names their own server), and the assembled bundle cross-references enough fields to identify the person in ways no single slot does. The sensitivity is a property of *this handler's behaviour*, and no slot's schema can carry it.

For exactly that case — and only that case — there's handler-meta `:sensitive?`:

```clojure
(rf/reg-event-fx :gdpr/export-bundle
  {:doc        "POST the user's GDPR bundle to their nominated destination URL."
   :sensitive? true}                                ;; ← the whole handler scope is sensitive
  (fn [{:keys [db]} [_ destination-url]]
    {:fx [[:rf.http/managed
           {:request {:method :post
                      :url    destination-url
                      :body   (gdpr-bundle db)}}]]}))
```

The flag does three things the schema-slot version can't. It hoists `:sensitive? true` to the **top level** of every trace event emitted while the handler is in scope (alongside `:source` / `:recovery`, not nested under `:tags`, so off-box listeners route on it directly). It **propagates through the cascade** — every `:rf.http/*` trace event the handler triggers inherits the flag, so the destination URL, the request body, and the response all redact as one. And it drops the whole cascade from off-box shippers' default policy.

The asymmetry between the two sites is intentional and it tracks the underlying semantics: **data-shape facts live on the schema; behaviour-scoped facts live on the handler.** When a single slot is the secret — a card number, a session token, a medical record number — put the flag on the schema. Reach for handler-meta only when no single slot's schema can carry the truth. Picking one site and one site only is the discipline that keeps the privacy story small enough to hold in your head. Note also the deliberate restraint here: there's exactly one primary site and exactly one escape hatch. An earlier life of this design had *three* declaration sites plus a "belt and braces" recommendation to use two of them at once — which was just a confession that no single site was sufficient. Three sites for one decision is three chances to disagree with yourself. One-and-one is the whole vocabulary now.

## `:large?` — keeping the wire small

`:large? true` is the same surface, different problem. The value is replaced at every wire emit with the `:rf.size/large-elided` marker — a small map that stands in for the big value while preserving everything a consumer needs to know *about* it:

```clojure
{:rf.size/large-elided
  {:path   [:user :uploaded-pdf]                     ;; where the value lived
   :bytes  5242880                                   ;; how big it was (pr-str byte count)
   :type   :string                                   ;; :map / :vector / :set / :scalar / :string
   :reason :schema                                   ;; only :schema today
   :hint   "Upload preview blob"                      ;; the slot's :hint, copied verbatim
   :handle [:rf.elision/at [:user :uploaded-pdf]]}}  ;; opt-in fetch path
```

The 5MB string is gone; a ~200-byte marker took its place, and the marker still tells you where the slot lived, how big it was, what kind it was, and why it went away. On-box panels render it as `[● ELIDED 5.2MB]`; off-box shippers ship the marker, not the value.

`:hint` is a free-form short string that rides on the marker — pair it with `:large?` whenever the path alone doesn't make the slot's purpose obvious. An agent or a dev-tool tooltip can read "Resume PDF preview blob" without fetching the 5MB binary to find out what it's looking at.

```clojure
(rf/reg-app-schema
  [:profile/photo-upload]
  [:map
   [:filename     :string]
   [:mime-type    :string]
   [:encoded-blob {:large? true :hint "Base64 photo preview blob"} :string]])

;; The handler that writes it — no metadata, no interceptor:
(rf/reg-event-db :profile/load-photo-preview
  (fn [db [_ encoded]]
    (assoc-in db [:profile/photo-upload :encoded-blob] encoded)))
```

Same asymmetry as `:sensitive?`: the handler body operates on the real 5MB string, and only the trace surface sees the marker. The wire-boundary walker does the substitution; the handler never knew the marker existed.

One pointed difference from `:sensitive?`: **`:large?` has no handler-meta escape hatch, by design.** Largeness is *always* a property of the value at a path, never of a handler's behaviour. A handler that reads a small slot doesn't make it large by touching it; a handler that reads a large slot found it large before it ran. There's nowhere for the declaration to live except the schema, so the schema is the only place it lives.

### The escape valve when you haven't written a schema yet

You'll write code faster than you write schemas — that's just how development goes — and a `[:user :photo-cache]` slot can quietly balloon past the wire budget before you've declared its shape. The framework nudges you without resorting to a runtime size-walker on the hot path. When the wire-boundary walker is about to emit a value over the 16KB threshold *and* the path has no schema declaration at all, it emits a one-time warning:

```clojure
{:operation :rf.warning/large-value-unschema'd
 :tags      {:path  [:user :photo-cache]
             :bytes 87324
             :hint  "Add `{:large? true}` to the schema slot for this path."}}
```

It fires **once per slot per session** (re-emits on the same path short-circuit, so a chatty cascade doesn't flood your dev panel), and it's **dev-only** — under `goog.DEBUG=false` the entire warning emit-site compiles away to nothing, zero production cost. The fix when you see it: open the schema for the slice, add `{:large? true}` (or `{:large? false}` if you've decided the slot really should ride the wire and you want to silence the nudge), reload. Both verdicts are valid; both are explicit.

## When both flags land on one slot

A base64-encoded scan of a customer's passport is both sensitive *and* large. Both flags apply, and the composition rule is deterministic: **sensitive wins, and the value is *dropped*, not marker-substituted.**

```clojure
(cond
  (and sensitive? large?)  ::drop                  ; no marker; emit :sensitive? true
  sensitive?               ::redact-or-drop        ; :rf/redacted sentinel
  large?                   ::elide-with-marker     ; :rf.size/large-elided marker
  :else                    ::pass-through)
```

The reason drop beats the size marker is subtle but real: the marker itself carries `:path` and `:bytes`, which are *structural facts* about the slot. Leaking "there's a 5MB blob at `[:kyc :id-document]`" tells an attacker that this customer's KYC review has a document attached and roughly how big it is — and for a sensitive slot, that's still too much. So when both flags fire, the value vanishes entirely and the top-level `:sensitive? true` rollup lets off-box shippers drop the whole event the way they would for any other secret. This same rule binds everywhere the walker runs — wire emits, schema-validation traces, every tool downstream. One rule, no per-site dialect.

## The one walker

Everything above bottoms out on a single function. Every tool that emits wire data calls `rf/elide-wire-value`; it is the *only* normative emission site for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker, and per-tool reimplementation is prohibited. One function means one place where redaction and elision are correct, instead of five places where four of them are correct and one ships your password.

```clojure
(rf/elide-wire-value v
                     {:rf.size/include-sensitive? false    ;; default false — sensitive drops
                      :rf.size/include-large?     false    ;; default false — large elides
                      :rf.size/include-digests?   false    ;; default false — no sha256 in marker
                      :rf.size/threshold-bytes    16384
                      :frame                      :rf/default})
;; → v unchanged, OR
;; → nil (sensitive event dropped entirely), OR
;; → v with :rf/redacted at sensitive paths, OR
;; → v with :rf.size/large-elided markers at large paths
```

The `:handle` on a large marker is a plain EDN vector — not a tagged literal — so agents pattern-match on the leading `:rf.elision/at` keyword and pass the handle straight to the re-frame2-pair-mcp `get-path` tool to fetch the elided value when they genuinely need it (subject to that tool's own cap check; an over-cap fetch fails with `:rf.mcp/overflow`). One round-trip per elided value the consumer actually wants, and never by default.

## HTTP is the canonical leak surface

Passwords ride request bodies, auth tokens ride headers, user PII rides response payloads — HTTP is where secrets go to get logged. The managed-HTTP cascade from [chapter 10 — HTTP](10-http.md) layers three cooperating defences on top of the generic `:sensitive?` machinery. None of the three is an app-writer declaration; all three honour your schema's verdict automatically.

**Header denylist (always-on).** A canonical set of header names is *always* sensitive — the name itself declares the value secret, regardless of any surrounding flag. The closed list is twelve names: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-Session-Token`, `X-CSRF-Token`, `X-XSRF-Token`, `Authentication`, `WWW-Authenticate`, `Proxy-Authenticate`. Their values become `:rf/redacted` in every `:rf.http/*` trace event carrying a `:headers` slot. Extend with `(rf.http/declare-sensitive-header! "X-Honeycomb-Team")`.

**URL query-string denylist (always-on).** Same idea on the parallel axis: a closed set of query-param names whose values redact inline. `?api_key=SECRET&page=2` becomes `?api_key=:rf/redacted&page=2` — name and position survive (so you can see *which* endpoint was hit), the secret doesn't. A denylist hit also *stamps* `:sensitive? true` on the trace event, because the mere presence of a denylisted param signals the request carries an auth secret. Extend with `(rf.http/declare-sensitive-query-param! "shop_token")`.

**Body redaction (effective-sensitive).** When a request is sensitive — because the handler-meta says so, *or* because a schema slot the body assembles from is `:sensitive?` — the body redaction fires: `:body`, `:body-text`, `:decoded`, `:detail`, `:params`, and all `:url` query-string values become `:rf/redacted`. Three OR-reduced sources contribute the effective flag (handler `:sensitive?`, the `:request` map's `:sensitive?`, or a top-level `:sensitive?` on the `:rf.http/managed` args); any one true means sensitive.

Here's the whole login story, declared once and enforced everywhere:

```clojure
;; 1. Declare the schema slot. The password is the entire privacy surface.
(rf/reg-app-schema
  [:auth]
  [:map
   [:username :string]
   [:password {:sensitive? true} :string]])

;; 2. Sign-in handler — no metadata, no interceptor.
;;    The schema's :sensitive? drives the trace scrub AND the HTTP body redaction.
(rf/reg-event-fx :auth/sign-in
  {:doc "Verify credentials and start a session."}
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   {:u username :p password}}}]]}))

;; 3. The trace stream when the user signs in:

;;    Event A — :event/dispatched
;;    {:operation :event/dispatched
;;     :tags      {:event [:auth/sign-in {:username "ada" :password :rf/redacted}]}
;;     :sensitive? true}            ;; schema-driven scrub on :password; flag at top level

;;    Event B — :rf.http/request-started
;;    {:operation :rf.http/request-started
;;     :tags      {:request {:url "/auth/login" :body :rf/redacted}}
;;     :sensitive? true}            ;; HTTP body redaction fired off the same schema verdict

;;    Event C — the Datadog shipper from ch.16
;;    Events A and B drop (sensitive). Datadog sees the cascade shape, the
;;    timing, the error class — it just never sees the password.
```

One declaration site, one walker, every consumer honours it.

## Schema-validation errors: the back door

There's one sneaky leak path worth calling out explicitly. When `app-db` fails validation, the runtime emits `:rf.error/schema-validation-failure` with the failing value in `:tags :value` (plus the surrounding `:explain` map). For a sensitive slot, *the value the schema rejected is exactly the value you didn't want on the bus* — the validation error is the one place a redaction overlay could quietly hand the secret right back.

It doesn't. The validation emit-site walks the failing path's schema; if the slot is `:sensitive?`, the `:value` and `:received` fields are substituted with `:rf/redacted` before emit and the event is stamped `:sensitive? true` at the top level. It's the same declaration you already wrote on the schema slot — there's no second site to also inform the validator.

```clojure
(rf/reg-app-schema
  [:map [:token {:sensitive? true} :string]])

;; A validation failure on [:token] now emits:
;;   {:operation :rf.error/schema-validation-failure
;;    :tags      {:path  [:token]
;;                :value :rf/redacted             ;; ← scrubbed
;;                :explain {...}}
;;    :sensitive? true                            ;; ← consumers route on this
;;    ...}
```

Same `sensitive-wins` composition as everywhere else: if the slot were also `:large?`, no size marker is emitted, because the marker's `:path` / `:bytes` would themselves leak structure.

## The consumer side: conservative by default

You've been writing the *producer* side — the declarations. The other half is the *consumer's* policy: the per-call opts map every tool passes when it invokes `rf/elide-wire-value`. The rule that makes the whole system safe is that every off-box consumer ships with **maximum elision by default.**

| Consumer | `:include-sensitive?` | `:include-large?` | Off-box? |
|---|---|---|---|
| re-frame2-pair-mcp (AI surface) | `false` | `false` | Yes |
| story-mcp (story playgrounds) | `false` | `false` | Yes |
| Xray-MCP (cascade graph) | `false` | `false` | Yes |
| Story panel (on-box dev UI) | `false` | `false` | No |
| Xray panel (on-box dev UI) | `false` | `false` | No |

The Datadog shipper from [chapter 16](16-observability.md) is the sixth consumer and follows the same rule: **off-box shippers MUST default both `include-*` flags to `false`.** Off-box means the data is leaving your trust boundary, and Datadog's trust boundary is not yours. That conservative default is the framework's safety net for the app author who wires up a published integration without reading its source — the failure mode is "I see a `[● ELIDED]` chip and have to opt in," not "I shipped a card number and found out from a customer."

The on-box dev UIs render a small `[● REDACTED]` / `[● ELIDED 5.2MB]` chip wherever a sentinel or marker lands in the rendered tree; the dev clicks the chip to opt in for a single live-fetch via the marker's `:handle`. That's the *only* path by which a sensitive or large value re-materialises on screen, and it's per-fetch, not session-wide. (There's a deliberate verb split worth internalising if you write your own consumer: `include-*` governs *bytes leaving the process*, `show-*` governs *pixels rendered to the dev*. Both default off; both are explicit when on.)

## The one gap: exceptions

The path-marked declarations redact everywhere the walker can resolve a *path against a known shape* — `app-db`, event arg-maps, sub outputs, fx inputs, cofx injections, machine `:data`, flow outputs. They do **not** walk exception messages or `ex-data` maps, and that's a small but real residual you need to know about.

Here's the bite. If a handler reads a sensitive-path value and then throws with it interpolated into the message —

```clojure
;; ANTI-PATTERN — the email lands in the exception message.
(throw (ex-info (str "User " email " failed login")
                {:user/email email :reason :invalid-credentials}))
```

— the resulting `:rf.error/handler-exception` trace carries the email **verbatim** in `:exception-message` and `:exception-data`. The framework has no way to know the string was assembled from a sensitive path; once the value is concatenated into a flat message there's no path that resolves to the substring, and an `ex-data` map carries arbitrary author-chosen keys (`:user/email`) with no relationship to the `[:user :email]` path in app-db. This is the deliberate boundary of the contract: it's a leak-prevention overlay on observability, not a full taint-tracking system. The framework redacts everywhere it can resolve a path; the exception-assembly site is the one place *you* have to participate.

The cheapest fix, and the one to reach for by default: **don't interpolate sensitive values into messages at all.** The exception exists for the dev reading the trace, and the dev needs the *category* of failure, not the user's identity — which is recoverable anyway from dispatch-id correlation against the (already-redacted) app-db snapshot.

```clojure
;; Name the category, not the value. Nothing leaks.
(throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
```

When you genuinely need the *structure* of the failing context but not the leaf value, substitute the `:rf/redacted` sentinel at the assembly site (`{:user/email :rf/redacted}`) so the dev sees that an email-keyed lookup was the trigger without seeing the email. And if you throw from sensitive-path-reading handlers often enough that you want it systematic, write a twelve-line `safe-throw` helper in your app that takes a category, a context map, and a set of keys to scrub:

```clojure
(defn safe-throw
  "Throw an ex-info whose message and ex-data never carry raw values for
   the keys named in `scrub`. Message is the category; named keys redact."
  ([category] (safe-throw category {} #{}))
  ([category context] (safe-throw category context #{}))
  ([category context scrub]
   (let [redacted (reduce #(assoc %1 %2 :rf/redacted) context scrub)]
     (throw (ex-info (str category) (assoc redacted :reason category))))))
```

The framework deliberately does *not* ship this for you, and the reason is the same reason Spec 015 stops at the path boundary: knowing *which ex-data keys correspond to sensitive paths in your specific app* is author knowledge, not framework knowledge. A framework helper would either make you name the scrub keys at every call anyway (adding nothing over the in-app version) or try to auto-detect them (the taint-tracking system the design explicitly rejects). The right shape is a per-app convention, and the point is the convention, not the twelve lines.

None of this should make you paranoid about every exception. The gap bites only at the intersection of two facts: the handler reads a sensitive-path value, *and* it throws with that value in the message or ex-data. Most handlers do neither, and most exceptions are about structural failures — a missing key, a timeout — where no secret ends up in the message at all.

## Four declarations, in the order you'll reach for them

Everything in this chapter reduces to four moves, ranked by how often you'll use them:

1. **Schema-slot `:sensitive?`** — for data-shape secrets. The card number, the session token, the patient record number. One flag, every consumer honours it. You'll write this 90% of the time.
2. **Schema-slot `:large?` + `:hint`** — for size, not secrecy. The photo blob, the audit log, the cached PDF. The marker keeps `:path` / `:bytes` / `:hint` / `:handle` so consumers know what was elided and can opt in to fetch.
3. **Handler-meta `:sensitive?`** — for cross-cutting handler-scope sensitivity. The export bundle, the third-party POST, the operation that composes individually-innocent slots into a sensitive whole. Rare.
4. **A `safe-throw` convention** — for the exception-assembly gap the walker can't reach. The one place the contract asks you to participate.

Not one of these is an interceptor you wire by hand, a registration you remember at every call site, or a per-consumer filter you ship to every tool. You declare the truth once where the truth lives; the platform carries it to every wire boundary it owns.

Privacy is one half of operational safety. The other half is the set of knobs and hard guardrails that make the safe behaviour the default and the dangerous behaviour explicit.
