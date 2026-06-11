# 23 - Privacy and large things

Your auth token must not end up in a Datadog log, and a 40MB PDF must not end up in the trace buffer. Both problems have the same root cause — re-frame2's killer feature is that one runtime story feeds every tool and every off-box shipper — and, satisfyingly, both have the same shape of answer. There is **one law**: the owner of a piece of data declares what it is, the framework projects it at the trust boundary, and every sink downstream receives an already-safe record. You declare; the framework projects; sinks consume. This chapter is that law, and the small vocabulary you use to write the declarations.

## The firehose is also the leak

Let me restate the thing chapter 16 sold you on, because the whole of this chapter is the bill arriving for it.

re-frame2 deliberately makes runtime state highly observable. That is a core productivity feature: one runtime story can feed traces, the Xray cascade graph, the Story playground recorder, the re-frame2-pair-mcp AI surface, epoch history, recorders, HTTP diagnostics, schema-validation reports, and SSR/hydration payloads. Every event a user dispatches, every `app-db` snapshot, every `:rf.http/*` request and response can ride one of those surfaces. That uniformity is *the* reason the tooling is any good — several tools telling consistent stories about your running app, because there is one story to tell. The production shippers from [chapter 16](16-observability.md) — the Datadog event forwarder, the Sentry error monitor — are a *separate*, narrow surface that survives `goog.DEBUG=false`, but they carry the same kinds of records, which is why the leak boundary has to hold across *all* of them, not just the dev ones.

Now turn it over. A firehose makes a magnificent debugger and a catastrophic auth-token logger. The first sign-in form on your app puts `{:password "hunter2"}` into an event vector, and absent any defence that password is now reachable by every dev who attaches a trace listener, every Datadog dashboard, every Sentry queue, every MCP server an agent connected to. You did nothing wrong. You wrote a normal login handler. The architecture's greatest strength is, unmodified, a security incident with your customer's name on it.

And there's a second, quieter version of the same problem that isn't about secrets at all. Suppose a slice of `app-db` is *huge* — a 5MB base64-encoded scanned passport, a 100K-row audit log, an image-preview blob. The observability surfaces assume every payload can ride the wire. The instant one slice is megabytes, that assumption snaps: off-box shippers refuse the upload, on-box panels choke trying to render a 100K-row table, and an AI agent attached to your app blows its context window trying to read a `:db-after` payload that's mostly base64. Not a leak — a denial of service against your own observability.

Two failure modes, one cause: data crosses a framework-mediated observation boundary that it shouldn't cross *in that form*. The framework's answer to both is the same: **classify** the data at its owner, **project** it at the boundary, and let every **sink** consume the projected result.

## The one law: classify, project, consume

There's an obvious-looking wrong answer here, and it's worth naming so we can rule it out: *filter at the consumer.* Tell every tool "drop the password before you ship." This fails, and it fails for a structural reason. Consumers are written by humans who forget, by AI agents who don't know which slot is sensitive unless told, and by ops engineers wiring up a published integration they didn't read the source of. Asking N consumers to each independently get the redaction right is asking for the one that doesn't to leak everything.

So the framework splits the problem into three layers and assigns each to exactly one actor:

> **Classification** names facts — *this path is sensitive, this slot is large.*
> **Projection** applies those facts at a boundary — the framework operation that takes a record and returns one safe to cross a specific trust boundary.
> **Sink policy** routes the projected records — Datadog, Sentry, Xray, MCP, the SSR response.
>
> **App authors declare classification and sink policy. The framework performs projection. Sinks consume already-projected records only.**

Everything you write is a *declaration* (what is sensitive/large, and where observations should go). Everything the framework runs is *projection*. Everything a sink does is *consume a record that is already safe*. A sink never hand-rolls redaction, and neither do you — you classify once, at the owner, and the framework carries that classification to every boundary it owns.

This is the same move re-frame2 makes everywhere: push the decision to the one site where it has a stable, authoritative answer, then let the platform carry it to all the places that need it. The only question that changes from boundary to boundary is *which boundary is this?* — and you answer that with a named profile, not a remembered combination of booleans.

A note on the posture before we go further. This contract is a **leak-prevention overlay on observability**, not a security boundary. Your app still owns its own auth, authorisation, encryption-at-rest, and transport security. What this machinery buys you is that the framework's *own* observability surfaces cannot accidentally exfiltrate a user's secrets or stuff a record with multi-megabyte blobs. And the default everywhere it can't be sure is **fail-closed**: an unknown frame, an unschematized HTTP body, a derived value with a sensitive input — all redact rather than leak.

## Who owns the declaration: the three-owner table

Classification is attached to **whoever owns the data shape**. That is the single rule that tells you where to put a `:sensitive` declaration, and it has three answers depending on the *kind* of data:

| The data is… | Owner | You declare it on… | Shape |
|---|---|---|---|
| Durable, frame-wide `app-db` state (auth tokens, partner keys, big uploads); frame-local HTTP carrier names | The **frame** | `reg-frame` / `make-frame` `:sensitive` / `:large` path maps | a path map: `{:app-db [[:auth :token]]}` |
| Owner-local schema'd data — machine `:data`, resource data/params, HTTP response bodies | The **schema** that already validates it | per-slot `:sensitive?` / `:large?` Malli props on that `:data-schema` / `:params-schema` / `:decode` schema | a boolean prop on one slot: `[:token {:sensitive? true} :string]` |
| Transient payloads — event args, fx/cofx args, sub outputs, flow outputs, machine transition payloads | The **registration** that introduces the shape | `reg-event` / `reg-sub` / `reg-fx` / `reg-flow` `:sensitive` / `:large` metadata | a vector of paths into that payload: `[[:password]]` |

The one-line summary to hold in your head: *durable frame-wide facts → frame config path maps; owner-local schema'd data → per-slot schema props; transient payloads → registration metadata.* Three owners, three surfaces, no overlap. Each piece of data has exactly one place its classification lives, which is the whole point — there is never a question of "did I declare this on the frame *and* the schema?", because for any given datum only one of those owners applies.

### The `:sensitive` / `:sensitive?` distinction is deliberate

You'll notice two spellings: `:sensitive` (no `?`) on frame config and registrations, `:sensitive?` (with `?`) on schema slots. That is not an accident or an inconsistency — it's a named cross-layer distinction, and the `?` carries real meaning:

- **`:sensitive`** names a *collection of paths* — `{:app-db [[:auth :token]]}` or `[[:password]]`. It's a set, so it's plural and bare.
- **`:sensitive?`** answers a *yes/no question about one slot* — "is the value at this slot secret?" It's a boolean, so it takes the predicate `?`, following the same convention as `:show?` or `:closed?` everywhere else in the framework.

The same distinction applies to `:large` / `:large?`. Because `:sensitive` already means "a collection of paths," you must never reuse `:sensitive false` to mean "this derived value is safe" — that's a different fact with its own key, which we'll meet under [derived sensitivity](#derived-values-inherit-by-default) below.

## Frame-owned classification: durable app-db state

Durable app-db classification, frame-local HTTP carrier names, and frame observability sink policy all live on the frame, declared once at frame creation:

```clojure
(rf/reg-frame :app/main
  {:sensitive
   {:app-db [[:auth :token]
             [:auth :refresh-token]
             [:tenant :partner-api-key]]
    :http    {:headers      ["X-Honeycomb-Team"]
              :query-params ["shop_token"]}}

   :large
   {:app-db [[:documents :csv-upload]
             [:reports :raw-export]]}

   :on-create [:app/init]})
```

That's the declaration. No companion interceptor, no per-tool plumbing — the path on the frame is the whole of it. A few semantics worth knowing:

- Frame classification is installed **atomically as part of frame creation**, before `:on-create` runs. The frame is never live for a moment without its policy.
- Re-registering a frame **replaces** its classification wholesale — the declaration *is* the frame's policy, so there's no additive-merge surprise to reason about.
- `:sensitive :app-db` and `:large :app-db` entries are ordinary `:rf/path` values — the same path vocabulary you use everywhere else (chapter 12's path optics), not a fourth ad-hoc notation.
- **Sensitive wins over large.** A path declared both sensitive and large redacts as sensitive and emits *no* large marker — because the large marker itself carries `:path` and `:bytes`, and for a secret slot even "there's a 5MB blob here" is too much to leak.
- Malformed paths, unknown classification keys, and non-string HTTP carrier names **fail loudly at frame registration** — fail-fast, not silent-ignore. A typo in a sensitive path is a bug you want to hear about at boot, not discover in a leaked log.

What makes the frame the right owner for *durable app-db* policy — rather than, say, a schema or a runtime call — is that the frame is where durable, cross-frame-distinct state lives. A multi-frame app can route different frames' `app-db` to different sensitivity policies and different sinks, and the declaration sits next to `:on-create` and the rest of the frame's identity. There is **no** schema-attached or imperative-mark route to classify the same app-db path; the frame owns it, full stop.

## Schema-owned classification: machine, resource, and HTTP-body data

Some data's natural home *is* a schema — a machine's `:data` already has a `:data-schema` that validates it; a resource has a `:data-schema` / `:params-schema`; a managed HTTP request has a `:decode` schema for its response body. For those owners, the per-slot Malli prop *is* the owner declaring policy, and it is the **one and only** route for that owner's data.

```clojure
(rf/reg-machine :checkout/payment
  {:data-schema
   [:map
    [:payment [:map
               [:token       {:sensitive? true} :string]
               [:receipt-pdf {:large? true}     :bytes]]]]
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging    {:spawn {:src :checkout/charge :on-done :done}}
             :done        {}}})
```

The `:sensitive?` slot redacts `[:data :payment :token]` everywhere the machine snapshot egresses — every transition's `:before` / `:after`, the Xray Machine Inspector, the pair-MCP surface, the epoch wire. The `:large?` slot elides the receipt PDF the same way. This is the schema-first machine surface from [chapter 12](12-machines.md), and it is unchanged — co-located props on a declared schema *are* owner-declares-policy when the owner's natural declaration surface is a schema, and a co-located prop is structurally immune to the rename-drift hazard a sibling path map would carry (rename the slot, the prop moves with it).

The same mechanism covers resource data/params ([chapter 27](27-resources.md)) and HTTP response bodies ([chapter 10](10-http.md)) — three owners, one mechanism. This is **not** in tension with "the frame owns durable app-db policy." The rule is precise: a schema must not be a *second* route to classify an app-db path the frame already owns. But where a schema *is* the owner's natural surface — machine `:data`, resource data, an HTTP body — per-slot props are the *only* route, and there's no competing frame-config route for those shapes. One owner, one route.

## Registration-owned classification: transient payloads

Transient payloads — the values that flow *through* the cascade rather than *living* in durable state — are owned by the registration that introduces their shape. Event args, cofx values, fx args, sub outputs, flow outputs: each is classified by metadata on the registration that defines it.

```clojure
(rf/reg-event-fx
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db db
     :rf.http/managed
     {:request {:method :post
                :url    "/api/login"
                :body   {:email email :password password}}}}))

(rf/reg-sub
  :partner/api-token
  {:sensitive [[]]}                ;; empty path → the whole sub output
  (fn [db _]
    (get-in db [:tenant :partner-api-key])))

(rf/reg-flow
  :auth/session-summary
  {:inputs {:token [:auth :token]
            :user  [:auth :user]}
   :output [:auth :session-summary]
   :sensitive [[:token-hash]]
   :derive (fn [{:keys [token user]}]
             {:user-id    (:id user)
              :token-hash (sha256 token)})})
```

The paths index into the registration's primary data shape — the event arg-map (the second element of `[:event-id {arg-map}]`), the fx-input map, the sub output, the flow output. The empty path `[[]]` marks the whole shape. A mark at a slot that doesn't exist (yet) is a silent no-op — payload shapes evolve, and a stale mark shouldn't crash you — but a malformed path *vector* fails at registration, because that's a bug.

Note the asymmetry that makes this both safe and usable: the handler body sees `password` verbatim — handlers need real values to do their work, obviously. But the `:event/dispatched` trace event, the always-on event-emit record, the HTTP body — all ship with `:password` projected to `:rf/redacted`. The real value flows through your code; only the *observable shadow* of it on a framework surface gets scrubbed. You did not write a `redact-interceptor`; you did not stamp the handler. The registration declares the truth and the framework projects it.

## Choosing where observations go: frame `:observability`

Classification is one of the two things you declare; **sink policy** is the other. Production observability — the always-on handled-event and error records from [chapter 16](16-observability.md) that survive into a production build — is routed by frame `:observability` policy:

```clojure
(rf/reg-frame :app/main
  {:observability
   {:handled-events [{:sink :my-app.sinks/datadog
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]
    :errors         [{:sink :my-app.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]}

   :sensitive {:app-db [[:auth :token]]}
   :on-create [:app/init]})
```

Two streams route here:

- **`:handled-events`** — one production-safe observation record per re-frame event processed by this frame. (Not browser DOM events; not the dev trace stream's many fine-grained events.)
- **`:errors`** — production-survivable error records, the always-on error axis from chapter 16 / [EP-0008](../EP/EP-0008-production-observability-channels.md).

The app declares a *sink id* the frame policy names, then registers the concrete sink fn against that id. The framework ships no Datadog or Sentry client — the sink fn is your (or an integration library's) concern, and that's why the sink id lives outside the framework namespace (`:my-app.sinks/datadog`, not `:rf.sink/datadog`) and vendor options ride a local `:opts` map:

```clojure
(rf/reg-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

The sink fn receives an **already-projected** record. It does no redaction of its own — by the time it runs, the runtime has already built the record, projected it under the owning frame's classification and the entry's `:rf.egress/profile`, and handed it over safe. That conservative default is the framework's safety net for the app author who wires up a published integration without reading its source: the failure mode is "I see a `:rf/redacted` in the payload and have to widen the policy deliberately," not "I shipped a card number and found out from a customer."

A couple of properties make this robust:

- **Routing is fail-closed and frame-scoped.** An unresolved frame (destroyed or never registered) or a frame with no `:observability` policy routes **nothing** — the runtime never synthesizes `:rf/default`, never borrows another frame's sinks, and never ships a record under unknown classification.
- **Sinks are isolated.** A throwing sink is dropped; a buggy sink cannot block its siblings or crash the dispatch.
- **The off-box default omits event args.** Under the default `:rf.egress/off-box-observability` profile, a handled-event record carries frame, event id, status, elapsed, effect keys, and work/correlation ids — and **omits the `:event` args slot entirely**. A tool can opt into a richer payload, but it receives a *projected* payload, never raw values. (This is the same rule as EP-0008's "structured data only — never raw values"; the two specs cross-cite one statement.)

```clojure
;; The off-box handled-event record a sink actually receives:
{:kind        :rf.observe/handled-event
 :frame       :app/main
 :event-id    :checkout/submit
 :status      :ok
 :elapsed-ms  12
 :effects     [:db :rf.http/managed]
 :correlation {:work-id "w-77" :dispatch-id "d-91"}}
;; No :event slot at all under the off-box default.
```

The low-level `register-event-listener!` / `register-error-listener!` registries still exist as advanced integration APIs, but the normal production story is declaring a sink under frame `:observability` and registering its fn — not hand-wiring a listener.

## Projection profiles: which boundary is this?

The framework performs projection, but it needs to know *which trust boundary* a record is about to cross, because the rules differ — a local dev panel may show indicators, a hosted log sink must omit raw state entirely, a public error response must never carry an internal value. The normal way you (or a tool) answer that question is a named **egress profile**, passed as `:rf.egress/profile`:

```clojure
(rf/project-egress record
  {:frame :app/main
   :rf.egress/profile :rf.egress/off-box-observability})
```

`:rf.egress/profile` takes a value from a **closed six-member enum** — the public question is "which boundary?", not "which combination of booleans did I remember?":

| Profile | Default behaviour |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb). Omit raw app-db / runtime-db; redact sensitive; elide large; omit digests unless explicitly enabled. |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire. Redact sensitive; elide large; include structural indicators / counters so a tool can reason about *shape* without seeing *content*. |
| `:rf.egress/local-redacted` | local dev UI default. Suppress sensitive display by default; may show indicators. |
| `:rf.egress/local-raw` | trusted local operator. Include sensitive and large, unless size caps still require handles. |
| `:rf.egress/ssr-hydration` | the projection applied **after** the SSR allowlist — defence-in-depth, never a parallel SSR mechanism. |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values. |

The boolean `:rf.size/*` flags (`:include-sensitive?`, `:include-large?`, `:include-digests?`) remain beneath the profiles as an **advanced override layer** — you reach for them when you genuinely need to override one axis of a profile, not as the everyday choice. (The profile names are currently *provisional*: the set is closed, but the exact names don't lock until each profile is exercised by a real consumer surface — a hosted sink, an MCP wire, an SSR payload, and so on.)

### The two projection primitives

Real egress surfaces emit **records**, not bare values — a handled-event record, an error record, an epoch record, an MCP snapshot, an HTTP diagnostic. The public, record-level boundary primitive is **`rf/project-egress`**: it's the required step before any off-box sink, it knows which slots of a record are app-db-shaped, event-shaped, exception-shaped, or summary-only, and it applies the per-record-kind rules (like omitting `:event` args off-box):

```clojure
(rf/project-egress
  {:kind   :rf.observe/handled-event
   :frame  :app/main
   :event  [:auth/login {:password "secret"}]
   :status :ok
   :effects [:db :rf.http/managed]}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

Beneath it, **`rf/elide-wire-value`** is the single low-level walker for *tree-shaped values*. It walks a value and substitutes sentinels at the slots the frame's classification says to redact or elide:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main
   :path  [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

`elide-wire-value` knows nothing about record shapes; it's the leaf-level primitive `project-egress` delegates to for each tree-shaped slot. Sinks and tools should reach for `project-egress` and rarely call the walker directly.

## Direct reads must project, with the frame known

A few accessors bypass the trace surface entirely — `rf/app-db-value`, `rf/sub-cache`, an MCP `get-path`. Any direct read that crosses an egress boundary must **project app-side, with the frame known**:

```clojure
(rf/project-egress value
  {:frame :app/main
   :path  [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

Egress policy is frame-scoped, so it inherits the no-default-frame rule (chapter 18): **if a projection needs frame policy and no frame is known, it fails closed — it does not synthesize `:rf/default`.** A record reaching the projector frameless redacts rather than leaks; that's belt-and-braces over the routing-level fail-closed above.

On-box visibility is **per (tool, frame) pair** — there's no single process-global "show sensitive" toggle. Local tools default to `:rf.egress/local-redacted`; raw requires an explicit trusted-local opt-in (`:rf.egress/local-raw`). And here's the payoff of routing everything through one projection: **revealing sensitive data is an operator act, and it is itself trace-visible** — auditable, not silent.

## HTTP is the canonical leak surface

Passwords ride request bodies, auth tokens ride headers, user PII rides response payloads — HTTP is where secrets go to get logged. The managed-HTTP cascade from [chapter 10](10-http.md) layers cooperating defences on top of the general classification machinery.

**Header denylist (always-on).** A canonical set of header names is *always* sensitive — the name itself declares the value secret. The closed built-in list is twelve names: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-Session-Token`, `X-CSRF-Token`, `X-XSRF-Token`, `Authentication`, `WWW-Authenticate`, `Proxy-Authenticate`. Their values become `:rf/redacted` in every `:rf.http/*` record carrying a `:headers` slot. These are **immutable framework defaults** — no frame can remove them. Extend them with your own carrier names on the **frame**, where they union onto the built-ins:

```clojure
(rf/reg-frame :app/main
  {:sensitive {:http {:headers ["X-Honeycomb-Team"]}}})
```

**URL query-string denylist (always-on).** Same idea on the parallel axis: a closed set of query-param names whose values redact inline. `?api_key=SECRET&page=2` becomes `?api_key=:rf/redacted&page=2` — name and position survive, the secret doesn't. Extend on the frame the same way: `{:sensitive {:http {:query-params ["shop_token"]}}}`.

**Response-body classification (per-slot, on the `:decode` schema).** A response body is a *transient payload* owned by the request's `:decode` schema, classified per-slot with the same `:sensitive?` / `:large?` props the machine and resource surfaces use:

```clojure
(rf/reg-event-fx :auth/sign-in
  {:doc "Verify credentials and start a session."}
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   {:u username :p password}
                      ;; the :decode schema owns response-body classification:
                      :decode [:map
                               [:user-id :string]
                               [:token {:sensitive? true} :string]]}}]}))
```

The `{:sensitive? true}` prop on the decoded `:token` slot redacts that field everywhere the response egresses. Whole-body sensitivity is a root-level prop on the `:decode` schema. And the fail-closed default bites here too: an **unschematized response body is treated as whole-sensitive** — off-box production traces and captures omit response bodies entirely unless a classified projection is explicitly requested. You don't leak a login response because you forgot to write its `:decode` schema; you get a redacted body until you describe its shape.

Here's the whole login story, declared once and projected everywhere:

```clojure
;; Frame owns the durable app-db secret (if the token lands in app-db):
(rf/reg-frame :app/main
  {:sensitive {:app-db [[:auth :token]]}})

;; The event arg-map's :password is a transient payload — registration owns it:
(rf/reg-event-fx :auth/sign-in
  {:sensitive [[:password]]}
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   {:u username :p password}
                      :decode [:map [:token {:sensitive? true} :string]]}}]}))

;; Production: the Datadog handled-event record the frame's :observability routes.
;;   {:kind :rf.observe/handled-event :frame :app/main
;;    :event-id :auth/sign-in :status :ok :elapsed-ms 12 :effects [:db :rf.http/managed]}
;; The off-box default omits the :event args slot entirely — Datadog gets the
;; event-id, frame, status, and timing (a built-in sign-in audit trail), no secret.
```

Three owners — the frame (durable token in app-db), the registration (the transient `:password` arg), the `:decode` schema (the response `:token`) — each declares its own piece once, and `project-egress` carries every declaration to every boundary.

## Schema-validation errors: the back door, closed

When `app-db` or a payload fails validation, the runtime emits `:rf.error/schema-validation-failure` with the failing value. For a sensitive slot, *the value the schema rejected is exactly the value you didn't want on the bus* — the validation error is the one place a leak could quietly hand the secret right back. It doesn't: the validation emit-site projects under the owning frame's classification, so a value at a frame-sensitive (or schema-`:sensitive?`) slot is substituted with `:rf/redacted` and the record stamped sensitive before emit. It's the same declaration you already wrote — there's no second site to also inform the validator. The same `sensitive-wins` composition holds: a slot that's also large emits no size marker, because the marker's `:path` / `:bytes` would themselves leak structure.

## Derived values inherit by default

Derived values are the hardest case: a subscription, flow, or machine selector can copy, summarise, hash, or reshape a sensitive input into a new value. The conservative rule:

> **If a framework-known derivation depends on a sensitive input, its output is treated as sensitive — unless the registration explicitly declares the output safe.**

"Framework-known" means the dependency graphs re-frame2 actually owns: **subscription topology** (chapter 05's fixed-topology invariant, including realized inputs for parametric subs), **flows**, and **machine selectors** (recognised as subscription variants). Handler internals are honestly *out of scope* — there's no pretend taint-tracking through arbitrary handler bodies. A sub reading `[:auth :token]` projects its output redacted by default, even though you never marked the sub itself.

When a derived value is genuinely safe to surface — a token *prefix*, a hash, a count — you declassify it explicitly with **`:rf.egress/output-sensitivity`**, whose closed value set is `:rf.egress/inherit` (the default), `:rf.egress/sensitive` (force-mark even from public inputs), and `:rf.egress/public` (declassify despite sensitive inputs):

```clojure
(rf/reg-sub
  :auth/token-prefix
  {:inputs [[:auth :token]]
   :rf.egress/output-sensitivity :rf.egress/public}
  (fn [token _]
    (subs token 0 4)))            ;; only the non-secret prefix
```

The key is flat under `:rf.egress/*`, and — per the cross-layer distinction above — you must **not** spell this `:sensitive false`, because `:sensitive` already means "a collection of paths." A `:rf.egress/public` claim is the declassification analogue of a `:rf.scope/global` resource claim: **Xray enumerates every `:public` claim as a standing audit surface**, so a reviewer can see every place an author asserted "this derived-from-sensitive value is safe." The fail-closed default plus the audit list is the whole bet — you opt *out* of safety deliberately and visibly, never *into* a leak by forgetting.

## SSR and hydration are allowlist-first

SSR / hydration is production egress to the browser, and it asks a *different* question from the rest of this chapter. It does not primarily ask "which leaves are sensitive?" — it asks **"which state is allowed to cross this boundary?"** So it's an **allowlist-first** boundary:

```clojure
(rf/reg-frame :app/server
  {:ssr
   {:hydrate
    {:include-app-db [[:route]
                      [:public-config]
                      [:catalog :visible-items]]}}})
```

Frame classification still composes as **defence-in-depth**: if an allowlisted slice contains a sensitive child, projection redacts it unless the SSR policy explicitly permits. But the primary safety property is that **unlisted state does not cross** — a secret you forgot to classify still can't ride to the browser, because it wasn't on the allowlist in the first place. The `:rf.egress/ssr-hydration` profile is exactly the projection applied *after* this allowlist, never a parallel mechanism. (Resource SSR/hydration follows the same allowlist-first rule — see [chapter 27](27-resources.md).)

## Epoch records: project at export, never mutate at rest

Epoch records ([chapter 24](24-config-and-safety.md)) are **causal replay material** — one assembled record per dequeued event, the substance time-travel and `restore-epoch` replay from. That changes how you protect them. You do **not** scrub them in storage: mutating an epoch record at rest corrupts the replay contract, because the replayer needs the record it actually recorded.

So the posture is:

- raw epoch records may remain **in-process local dev state** — that's where time-travel reads them;
- off-box epoch *export* **must use egress projection** (`project-egress` with an off-box profile);
- frame-level epoch projection policy replaces the old process-global redaction hook for ordinary use;
- **storage-side mutation is removed**, not merely discouraged.

In short: classify your sensitive `app-db` paths on the frame, and epoch *export* projects them at the boundary like every other off-box surface — while the in-process record stays replay-faithful for time-travel.

## The display contract: three sentinels

Projection substitutes one of three sentinel forms, spanning the two-axis (sensitive × large) space. The sentinel keywords are framework-reserved — your app must never use them as legitimate payload values.

**`:rf/redacted` — sensitive only.** An opaque keyword carrying *no* information about the underlying content: not its type, not its size, not a hash, not a prefix.

```clojure
{:auth/token :rf/redacted}
```

**`:rf/large {:bytes N :head "..."}` — large only.** A sentinel plus a metadata map: `:bytes` (the size) and an optional `:head` (the first N chars of a printable rendering — the CLJS reference uses 128). The low-level walker emits this as `:rf.size/large-elided`; surfaces that preserve size diagnostics render the rich form.

```clojure
{:docs/csv-upload :rf/large {:bytes 4523198 :head "ID,Name,Email\n42,Alice,…"}}
```

**`:rf/redacted {:bytes N}` — sensitive + large composed.** Sensitive wins on content visibility — `:rf/redacted` rides the slot, no `:head` is ever permitted — though a size diagnostic *may* ride alongside.

```clojure
{:internal/diff-blob :rf/redacted {:bytes 4523198}}
```

The rendering rule for any consuming tool is uniform and load-bearing: **a large marker is drillable** (click-to-expand, subject to a per-tool size-confirmation), but **`:rf/redacted` MUST NOT be expandable, ever**. A "show original" affordance against `:rf/redacted` is non-conformant — that affordance is the exact leak the contract exists to prevent.

## The one gap: exceptions

Projection walks **known data shapes** and substitutes sentinels at classified paths. It does **not** walk exception messages or `ex-data` maps, and that's a small but real residual you need to know about. Once a sensitive value has been concatenated into a flat `ex-message` string, no path resolves to the substring; and `ex-data` keys are author-chosen (`{:user/email "..."}`) with no relationship to a frame's classified paths — a value-comparison rule would be the taint-tracking non-goal the contract explicitly rejects.

The bite is narrow — it's the *intersection* of two facts: the handler read a sensitive value, *and* the handler then threw with that value in the message or `ex-data`:

```clojure
;; ANTI-PATTERN — the email lands in the error record verbatim.
(throw (ex-info (str "User " email " failed login")
                {:user/email email :reason :invalid-credentials}))
```

The cheapest fix, and the one to reach for by default: **name the category of failure, not the value.** The dev reading the trace needs to know *what* failed, not *whose* identity — and the identity is recoverable anyway by correlating the dispatch-id against the (already-projected) app-db snapshot:

```clojure
;; Name the category. Nothing leaks.
(throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
```

When you genuinely need the *structure* of the failing context but not the leaf value, substitute the `:rf/redacted` sentinel at the assembly site (`{:user/email :rf/redacted}`) so the dev sees that an email-keyed lookup was the trigger without seeing the email. If you throw from sensitive-path-reading handlers often enough to want it systematic, a per-app `safe-throw` convention (a category, a context map, a set of keys to scrub) is the right shape. The framework deliberately does **not** ship that helper, and for the same reason the projection stops at the path boundary: *which `ex-data` keys correspond to sensitive paths in your specific app* is author knowledge, not framework knowledge. A framework helper would either make you name the scrub keys at every call (adding nothing) or auto-detect them (the rejected taint-tracking system). The point is the convention, not the twelve lines.

None of this should make you paranoid about every exception. Most handlers neither read a sensitive value nor throw with one in the message, and most exceptions are structural — a missing key, a timeout — where no secret reaches the message at all.

## The five moves, in the order you'll reach for them

Everything in this chapter reduces to a small set of declarations, and the law underneath them all is the one from the top: *you classify at the owner, the framework projects at the boundary, sinks consume the projected record.*

1. **Frame `:sensitive` / `:large {:app-db …}`** — for durable app-db secrets and big slices. The auth token, the partner key, the 5MB upload. Declared once on the frame; every off-box surface honours it. The everyday move.
2. **Schema `:sensitive?` / `:large?` props** — for owner-local schema'd data: a machine's `:data`, a resource's data/params, an HTTP response body's `:decode` slots. The prop lives next to the type; one mechanism across all three owners.
3. **Registration `:sensitive` / `:large`** — for transient payloads: event args, sub outputs, flow outputs. The `:password` in a login event, the whole output of a token sub.
4. **`:rf.egress/output-sensitivity :rf.egress/public`** — the deliberate, audited declassification of a derived value that's safe despite a sensitive input. Rare, and Xray lists every one.
5. **A `safe-throw` convention** — for the exception-assembly gap projection can't reach. The one place the contract asks *you* to participate.

Not one of these is an interceptor you wire by hand or a per-consumer filter you ship to every tool. You declare the truth once, where the truth lives — on the frame, on the schema, or on the registration — choose where production observations go, and the platform projects it at every boundary it owns, fail-closed by default and auditable when you open it.
