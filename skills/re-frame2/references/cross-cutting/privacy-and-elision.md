# Privacy and size elision — the egress-policy model

re-frame2's killer feature is also its leak: one runtime story feeds every tool and every off-box shipper, so an auth token in an event vector is reachable by every trace listener, every Datadog dashboard, every MCP server an agent attached to. A multi-megabyte `app-db` slice is the same shape of problem — a denial-of-service against your own observability. Both have one answer.

> **Mental model: data classification + taint-at-the-boundary.** If you have used Rails' filtered parameters, a logging redactor, or a DLP/data-classification scheme, the move is familiar — *but inverted from the usual mistake.* The wrong answer is to filter at each consumer ("tell every tool to drop the password"); the one that doesn't, leaks everything. re-frame2 classifies once **at the owner of the data**, projects at the **trust boundary**, and every **sink receives an already-safe record**. You declare; the framework projects; sinks consume.

This leaf is the recipe and when-to-reach-for-it. The depth — the full law, the exception gap, the three sentinels, the SSR allowlist — lives in [Keep secrets out of traces](../../../../docs/guide/how-to/keep-secrets-out-of-traces.md). The normative home is [`spec/015-Data-Classification.md`](../../../../spec/015-Data-Classification.md).

## The one law

> **Classification** names facts — *this path is sensitive, this slot is large.*
> **Projection** applies them at a boundary — the framework op that returns a record safe to cross *this* trust boundary.
> **Sink policy** routes the projected records — Datadog, Sentry, Xray, MCP, the SSR response.
>
> **App authors declare classification and sink policy. The framework performs projection. Sinks consume already-projected records only.**

Everything you write is a *declaration*; everything the framework runs is *projection*; a sink never hand-rolls redaction and neither do you. The default everywhere the framework can't be sure is **fail-closed**: an unknown frame, an unschematized HTTP body, a derived value with a sensitive input — all redact rather than leak.

This is a **leak-prevention overlay on observability, not a security boundary.** Your app still owns auth, authorisation, encryption-at-rest, and transport. What this buys you is that the framework's *own* observation surfaces cannot accidentally exfiltrate a secret or stuff a record with megabytes.

## Where you declare it: the three-owner table

Classification attaches to **whoever owns the data shape**. That single rule has three answers:

| The data is… | Owner | Declare it on… | Shape |
|---|---|---|---|
| Durable, frame-wide `app-db` state (auth tokens, partner keys, big uploads); frame-local HTTP carrier names | the **frame** | `reg-frame` / `make-frame` `:sensitive` / `:large` path maps | a path map: `{:app-db [[:auth :token]]}` |
| Owner-local schema'd data — machine `:data`, resource data/params, HTTP response bodies | the **schema** that already validates it | per-slot `:sensitive?` / `:large?` Malli props on that `:data-schema` / `:params-schema` / `:decode` schema | a boolean prop on one slot: `[:token {:sensitive? true} :string]` |
| Transient payloads — event args, fx/cofx args, sub outputs, flow outputs, machine transition payloads | the **registration** that introduces the shape | `reg-event` / `reg-sub` / `reg-fx` / `reg-flow` `:sensitive` / `:large` metadata | a vector of paths: `[[:password]]` |

Hold this one line in your head: *durable frame-wide facts → frame config path maps; owner-local schema'd data → per-slot schema props; transient payloads → registration metadata.* Three owners, three surfaces, no overlap. For any given datum exactly one owner applies — there is never "did I declare this on the frame *and* the schema?"

**`:sensitive` (no `?`) vs `:sensitive?` (with `?`) is deliberate, not an inconsistency.** `:sensitive` names a *collection of paths* (a set → plural, bare); `:sensitive?` answers a *yes/no question about one slot* (a boolean → predicate `?`). Same for `:large` / `:large?`. Because `:sensitive` already means "a collection of paths," you must **never** reuse `:sensitive false` to declassify a derived value — that's a different fact with its own key (see [derived values](#derived-values-inherit-by-default)).

## 1 — Frame-owned: durable app-db state

Durable `app-db` classification, frame-local HTTP carrier names, and observability sink policy all live on the frame, declared once at creation:

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

   :initial-events [[:app/init]]})
```

The path on the frame is the whole of it — no companion interceptor, no per-tool plumbing. Worth knowing:

- Classification is installed **atomically as part of frame creation**, before `:initial-events` run. The frame is never live without its policy.
- Re-registering a frame **replaces** its classification wholesale — no additive-merge surprise.
- `:app-db` entries are ordinary `:rf/path` values (the same path vocabulary you use everywhere), not a fourth notation.
- **Sensitive wins over large.** A path that's both redacts as sensitive and emits *no* size marker (the marker's `:path` / `:bytes` would themselves leak structure).
- Built-in HTTP carrier names (`Authorization`, `Cookie`, `X-API-Key`, …) are **immutable framework defaults**; frame `:http` carriers *union onto* them.
- Malformed paths, unknown keys, and non-string carrier names **fail loudly at frame registration** — fail-fast, not silent-ignore.

There is **no** schema-attached or imperative-mark route to classify an app-db path — the frame owns it, full stop.

## 2 — Schema-owned: machine, resource, and HTTP-body data

Some data's natural home *is* a schema. For those owners the per-slot Malli prop *is* the owner declaring policy, and it's the **one and only** route for that owner's data:

```clojure
(rf/reg-machine :checkout/payment
  {:data-schema
   [:map
    [:payment [:map
               [:token       {:sensitive? true} :string]
               [:receipt-pdf {:large? true}     :bytes]]]]
   :initial :collecting
   :states  {,,,}})
```

The `:sensitive?` slot redacts `[:data :payment :token]` everywhere the machine snapshot egresses (every transition's before/after, the Xray Machine Inspector, the pair-MCP wire, the epoch record); `:large?` elides the PDF the same way. This is the **EP-0005 schema-first machine surface, unchanged** — a co-located prop on a declared schema *is* owner-declares-policy when the owner's natural surface is a schema, and it's structurally immune to the rename-drift a sibling path map would carry (rename the slot, the prop moves with it). The same mechanism covers resource data/params and HTTP response bodies (`:decode` schema). One mechanism, three owners. This is **not** a second route to classify app-db — a schema is the *only* route where the schema *is* the owner's surface; the frame is the only route for durable app-db.

## 3 — Registration-owned: transient payloads

Values that flow *through* the cascade rather than *living* in durable state are owned by the registration that introduces their shape:

```clojure
(rf/reg-event
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db db
     :rf.http/managed
     {:request {:method :post :url "/api/login"
                :body {:email email :password password}}}}))

(rf/reg-sub
  :partner/api-token
  {:sensitive [[]]}                ;; empty path → the whole sub output
  (fn [db _] (get-in db [:tenant :partner-api-key])))
```

The paths index into the registration's primary data shape — the event arg-map (the second element of `[:event-id {arg-map}]`), the fx-input map, the sub/flow output. The empty path `[[]]` marks the whole shape. A mark at a missing slot is a silent no-op (payload shapes evolve); a malformed path *vector* fails at registration.

Note the asymmetry that makes this both safe and usable: the **handler body sees `password` verbatim** — handlers need real values to work. Only the *observable shadow* on a framework surface (the `:event/dispatched` trace event, the always-on event-emit record, the HTTP body) ships with `:password` → `:rf/redacted`. The registration declares the truth; the framework projects it.

## Choosing where observations go: frame `:observability`

Sink policy is the other thing you declare. Production observability — the always-on handled-event / error records that survive a release build — is routed by frame `:observability`:

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
   :initial-events [[:app/init]]})

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

The framework ships no Datadog / Sentry client — the sink id lives outside the framework namespace (`:my-app.sinks/datadog`, not `:rf.sink/datadog`) and vendor options ride a local `:opts` map. The sink fn receives an **already-projected** record. Routing is **fail-closed and frame-scoped**: an unresolved frame or a frame with no `:observability` policy routes *nothing* (never synthesizes `:rf/default`); a throwing sink is isolated. Under the default `:rf.egress/off-box-observability` profile a handled-event record carries frame, event id, status, elapsed, effect keys, and correlation ids — and **omits the `:event` args slot entirely**. The low-level `register-event-listener!` / `register-error-listener!` registries remain as advanced integration APIs, not the normal production story.

## Projection profiles: which boundary is this?

The framework projects, but needs to know *which trust boundary* a record is about to cross. You answer with a named **egress profile** — a value from a **closed six-member enum** under `:rf.egress/profile`, not a remembered combination of booleans:

```clojure
(rf/project-egress record
  {:frame :app/main
   :rf.egress/profile :rf.egress/off-box-observability})
```

| Profile | Default behaviour |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb). Omit raw app-db / runtime-db; redact sensitive; elide large; omit digests unless enabled. |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire. Redact sensitive; elide large; include structural indicators so a tool reasons about *shape* not *content*. |
| `:rf.egress/local-redacted` | local dev UI **default**. Suppress sensitive display; may show indicators. |
| `:rf.egress/local-raw` | trusted local operator. Include sensitive + large (size caps may still apply). |
| `:rf.egress/ssr-hydration` | the projection applied **after** the SSR allowlist — defence-in-depth, never a parallel SSR mechanism. |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values. |

The boolean `:rf.size/*` flags (`:include-sensitive?`, `:include-large?`, `:include-digests?`) remain beneath the profiles as an **advanced override layer** — reach for them only to override one axis of a profile, not as the everyday choice. (Names are *provisional*: the set is closed but the spellings don't lock until each profile is exercised by a real consumer surface.)

### The two projection primitives

Real egress surfaces emit **records**, not bare values. The public, record-level boundary primitive is **`rf/project-egress`** — the required step before any off-box sink. It dispatches on a record's `:kind` (`:rf.observe/*`) to a private per-kind projector, applies per-kind rules (like omitting `:event` args off-box), and delegates each tree-shaped slot to the low-level walker:

```clojure
(rf/project-egress
  {:kind :rf.observe/handled-event :frame :app/main
   :event [:auth/login {:password "secret"}] :status :ok}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

Beneath it, **`rf/elide-wire-value`** is the single low-level walker for *tree-shaped values* — it substitutes sentinels at the slots the frame's classification says to redact or elide. Use it for custom forwarders, loggers, and direct-value egress; do not reimplement the walk. Sinks and tools should reach for `project-egress` and rarely call the walker directly:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main :path [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

## Direct reads must project, with the frame known

`rf/app-db-value`, `rf/sub-cache`, and an MCP `get-path` bypass the trace surface. Any direct read crossing an egress boundary must **project app-side with the frame known**. Egress policy is frame-scoped and inherits the no-default-frame rule: **if a projection needs frame policy and no frame is known, it fails closed — it does not synthesize `:rf/default`.** On-box visibility is **per (tool, frame) pair** — no single process-global "show sensitive" toggle; local tools default `:rf.egress/local-redacted`, raw requires explicit `:rf.egress/local-raw` opt-in. The payoff of routing everything through one projection: **revealing sensitive data is an operator act and is itself trace-visible** — auditable, not silent.

## Derived values inherit by default

A subscription, flow, or machine selector can reshape a sensitive input into a new value. The conservative rule:

> **If a framework-known derivation depends on a sensitive input, its output is treated as sensitive — unless the registration explicitly declares the output safe.**

"Framework-known" = the dependency graphs re-frame2 owns: **subscription topology** (including realized inputs for parametric subs), **flows**, and **machine selectors**. Handler internals are out of scope (no pretend taint-tracking). When a derived value is genuinely safe — a token *prefix*, a hash, a count — declassify it explicitly with **`:rf.egress/output-sensitivity`** (closed value set: `:rf.egress/inherit` (default) | `:rf.egress/sensitive` | `:rf.egress/public`):

```clojure
(rf/reg-sub :auth/token-prefix
  {:inputs [[:auth :token]]
   :rf.egress/output-sensitivity :rf.egress/public}
  (fn [token _] (subs token 0 4)))
```

Do **not** spell this `:sensitive false`. The payoff: a `:rf.egress/public` claim is the declassification analogue of a `:rf.scope/global` resource claim — **Xray enumerates every `:public` claim as a standing audit surface**, so a reviewer sees every place an author asserted "this derived-from-sensitive value is safe." Fail-closed default + audit list is the whole bet: you opt *out* of safety deliberately and visibly, never *into* a leak by forgetting.

## The display contract: three sentinels

Projection substitutes one of three framework-reserved sentinel forms (your app must never use them as legitimate payload values):

- **`:rf/redacted` — sensitive only.** Carries *no* information about content — not type, size, hash, or prefix.
- **`:rf.size/large-elided` / `:rf/large {:bytes N :head "…"}` — large only.** Size plus an optional `:head` (the first N chars).
- **`:rf/redacted {:bytes N}` — sensitive + large composed.** Sensitive wins on content visibility (no `:head` ever); a size diagnostic may ride alongside.

The rendering rule is uniform and load-bearing: a **large marker is drillable** (click-to-expand, subject to a size confirmation), but **`:rf/redacted` MUST NOT be expandable, ever** — a "show original" affordance against `:rf/redacted` is the exact leak the contract exists to prevent.

## Retired surfaces → flip to the model

EP-0015 removed several older escape hatches from the public façade. If you see these in older code or older guidance, they teach a broken call:

| Retired surface | Replacement | Why |
|---|---|---|
| `add-marks` / `set-marks` — imperative post-creation app-db marks | frame `:sensitive` / `:large {:app-db …}` path maps | the frame owns durable app-db policy; one declaration site (`re-frame.story/add-marks` survives only as a story-internal helper, **not** the `re-frame.core` façade) |
| `redact-interceptor` — positional payload scrubber on the handler | registration `:sensitive [[:path]]` metadata | privacy is a property of the *value at a path*, owned by the registration — not of an interceptor's stack position |
| `declare-sensitive-header!` / `declare-sensitive-query-param!` — process-global carrier mutation | frame `:sensitive {:http {:headers [...] :query-params [...]}}` | carrier policy is frame-local, not a process-wide mutable fact |
| schema-attached `{:sensitive? true}` on an **app-db** `reg-app-schema` slot | frame `:sensitive {:app-db …}` | schemas describe shape/validation; the frame owns app-db egress policy. (The same `:sensitive?` prop on a **machine / resource / HTTP-body** schema is *correct and live* — that's the schema-owned row above.) |
| the epoch-history `:redact-fn` storage hook | frame/profile projection at *export* (`project-egress`) | epoch records are causal-replay material; mutating them at rest corrupts the replay contract. Project at export, never at rest. |
| handler-meta `{:sensitive? true}` as a whole-handler switch | registration `:sensitive` paths | **removed from the runtime — does nothing.** Marking a handler `{:sensitive? true}` ships the payload unredacted. |

Calling any of these retired names fails the skills projection gate (it no longer resolves to a `re-frame.core` manifest row) — that loud error is the point.

## Cross-references

- [Guide — Keep secrets out of traces](../../../../docs/guide/how-to/keep-secrets-out-of-traces.md): the full law, the exception gap, the SSR allowlist, `safe-throw`.
- [`spec/015-Data-Classification.md`](../../../../spec/015-Data-Classification.md): normative home.
- `production-observability.md`: the listener/event/error substrate behind frame `:observability`.
- Spec 009: privacy at the trace/emission boundary. Spec 010: per-slot Malli metadata and the schema walker.
