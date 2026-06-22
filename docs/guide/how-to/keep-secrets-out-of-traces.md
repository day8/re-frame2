# Keep secrets and large things out of traces

Your login form just put `{:password "hunter2"}` into an event vector — and an event in re-frame2 is just the data that describes something that happened, so that password is now sitting in plain data. Here's why that matters: re-frame2 sends all its observability over one wire. Events, snapshots of `app-db` (your app's single state map), and HTTP records all travel the same path. That one wire feeds Xray, the epoch ledger, and any production shipper you wire up ([one wire, every tool](../concepts/observability.md)). This page is the short list of declarations that keep the password, the token, and the 5MB upload off that wire — without ever hiding them from your own handler code.

> **Coming from Sentry?** The instinct is `beforeSend`: a scrub function you write into each consumer, run just before shipping. re-frame2 does the opposite. You classify data **once, at the owner of its shape**, and the framework applies it at every boundary it owns. There's no `beforeSend` to write — which means there's no Nth consumer left to forget one.

> **This is hygiene, not a security boundary — and it is fail-open.** The framework only keeps secrets off its *own* observability wire; your app still owns auth, encryption, and transport. And the contract is **fail-open**: a path you never classify ships raw. That's the bargain — convenient leak-prevention, not a guarantee. Classify the *path* a secret lives at, and the framework redacts whatever occupies it; forget a path and it leaks. There's no taint-tracking and no propagation, so a secret you copy to a new path (a re-keyed value, a rendered field) ships raw until you classify *that* path too.

The model has three layers, and they build on each other.

The first is **classification**. You name facts as data: this *path* is sensitive, this slot is large. You write the fact next to whatever owns the data's shape — an event handler (for durable `app-db` paths), a registration (for transient payloads), or a subsystem definition (for machine / resource data). (A *registration* is the call where you tell re-frame2 about a handler, the function that runs your logic; a *frame* is an isolated instance of your app's state and event machinery.) Classification does nothing on its own. Your handlers always see the real values.

The second is **projection**. The framework applies your facts at a trust boundary — that's its job, not yours and not the sink's. When a record is about to cross a boundary, the runtime projects it under the owning frame's classification and substitutes sentinels at the classified slots. How strict it gets depends on which boundary, named by an *egress profile*. The profile is a closed enum (`:rf.egress/off-box-observability` for hosted monitoring, `:rf.egress/local-redacted` for on-box dev panels, and so on). The profile matrix is in [Spec 015](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional); the wider posture-by-surface matrices are in [Spec 009](../../../spec/009-Instrumentation.md#production-debugging-what-remains).

The third is **sink policy**, which routes the projected records. A sink is your Datadog forwarder or a Sentry client — it receives records that are already safe, and it never scrubs anything itself. **You declare; the framework projects; sinks consume already-safe records.**

## 1. Classify durable app-db secrets from an event

State that *lives* in `app-db` is yours, at an absolute path you own. You classify it by returning a **classification effect** from a handler — alongside `:db`, in the same event. There are four, one per axis-and-direction:

```clojure
:sensitive       [[path] …]   ; classify each path sensitive (redact at egress)
:large           [[path] …]   ; classify each path large    (size marker at egress)
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

Where you return them tracks *when* the secret's path becomes known:

```clojure
;; known at authoring — classify in the frame's init event
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token]]
     :large     [[:documents :csv-upload]]}))

;; discovered at runtime — classify in the handler that writes it
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))
```

The two axes are **independent**: clearing the sensitive axis never touches the large axis, and vice-versa — `:clear-sensitive` is the precise inverse of `:sensitive`, like `dissoc` to `assoc`. You reach for `:clear-*` rarely. A classification over absent data is already a no-op, so when a value disappears its redaction effectively disappears with it; `:clear-*` earns its keep only when a *path is reused* for non-secret data:

```clojure
;; a path that held PII is overwritten with sanitised content — un-classify it
(rf/reg-event :doc/sanitised
  (fn [{:keys [db]} [_ doc-id clean]]
    {:db              (assoc-in db [:docs doc-id :body] clean)
     :clear-sensitive [[:docs doc-id :body]]}))
```

Wire `:auth/init` to run at frame creation — `:initial-events [[:auth/init]]` on `reg-frame` is the preferred home, so the classification is in place before any off-box egress. (`:initial-events` is the only init surface; there is no `:initial-db` and no `:on-create` — seeding app-db is itself an event, `[:rf/set-db {…}]`, dispatched as the first `:initial-events` step. See [the frames concept page](../concepts/frames.md).) The effects are applied **with the `:db` write** (a frame-state transform at the commit point, not a later `:fx`), so a path classified in an event is redacted from its *first* egress; a classification made earlier trivially covers it. Three things worth holding onto:

- **Classification is value-independent.** Classify a path *before* any value exists there — the common, safe pattern. The classification redacts whatever later occupies the path; over an absent path it's a harmless no-op. You don't re-classify per write.
- **Sensitive wins over large.** A path declared both redacts as sensitive, because even "there's a 5MB blob here" says too much about a secret. No size marker (whose `:path` / `:bytes` would leak structure) is emitted.
- **Malformed effects fail loud, pre-commit.** A bad payload (a non-vector value, a non-path entry, an unknown axis) aborts the transition with `:rf.error/classification-effect-shape` before any `:db` commit — a typo can't silently disable your protection. The classification rides the runtime-db partition (`[:rf.runtime/elision …]`), so it also **walks back atomically with the frame** on a `restore-epoch` revert — a reverted secret is still a classified secret.

There is **no frame `:sensitive {:app-db …}` annotation** and **no top-level `:large` frame key** — both are rejected fail-loud with `:rf.error/bad-frame-classification` (they were the EP-0015 model and are gone). And **no schema prop** classifies a durable app-db path — a `reg-app-schema` slot describes shape and drives validation, not egress. The event is app-db's definition site; that is the one route.

## 2. Classify transient payloads on the registration

Values that flow *through* the cascade — event args, fx/cofx values, a subscription's output — are owned by the registration that introduces their shape. Declare the sensitive / large paths there, relative to the payload:

```clojure
(rf/reg-event :auth/sign-in
  {:sensitive [[:password]]}        ;; path into the event arg-map
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request    {:method :post
                         :url    "/api/login"
                         :body   {:email email :password password}}
            ;; the :decode schema owns response-body classification:
            :decode     [:map
                         [:user-id :string]
                         [:token {:sensitive? true} :string]]
            :on-success [:auth/signed-in]
            :on-failure [:auth/sign-in-failed]}]]}))

(rf/reg-sub :partner/api-token {:sensitive [[]]}    ;; empty path → the whole sub output
  (fn [db _] (get-in db [:tenant :partner-api-key])))
```

The same metadata key works on **every** registration that introduces a payload shape — not just events and subs:

```clojure
;; a coeffect classifies the value it injects, relative to that value
(rf/reg-cofx :session {:sensitive [[:token]]}
  (fn [cofx] (assoc cofx :session {:user "alice" :token (read-token)})))

;; an effect classifies paths into its own fx-input map
(rf/reg-fx :rf.ws/send {:sensitive [[:auth]]} ws-send-handler)
```

The handler body sees `password` verbatim, because handlers need real values to do their work. Only the *observable shadow* is projected: the dispatched-event trace and the HTTP record ship `:password` as `:rf/redacted`. Paths index into the registration's primary shape — the event arg-map, the fx-input map, the cofx-injected value, the sub output; an empty path `[[]]` marks the whole shape, and a mark at a missing slot is a silent no-op (payload shapes evolve, and the mark waits patiently for the slot to reappear). A *malformed* mark (a non-vector path) is the loud case — it's rejected at registration with `:rf.error/bad-classification` (a flow's bad output marks raise `:rf.error/flow-bad-marks`), so the typo surfaces when you reload, not in a production log six weeks later.

> **Positional args are not path-addressable — a secret in one egresses RAW.** A path like `[:password]` reaches into the event's **arg-map** (`[:auth/sign-in {:password "…"}]`), so the registration mark can name it. A positionally-passed secret (`[:auth/sign-in "alice" "hunter2"]`) has **no** stable named path: a positional index is not path-addressable, so there is nothing for `:sensitive` to classify. Under the fail-open contract that means the secret is **not redacted at egress** — it ships RAW into every trace and error sink (the dispatched-event trace, `:event/db-changed`, and the `:event` slot of a `:rf.error/handler-exception` record). This is a known structural limitation, not a bug: redaction is path-based, and only the arg-map (`(second event)`) is reachable by a path.
>
> So when an event carries a secret, **pass a map payload and classify the key** — `[:auth/sign-in {:user "alice" :token "hunter2"}]` with `{:sensitive [[:token]]}` on the registration. The positional form is fine for non-sensitive args; reserve it for data you would not mind seeing in a trace.

The `:auth/signed-in` handler then stores the response token at `[:auth :token]` — the path step 1 classified. Notice the shape of fail-open here: the **HTTP reply** is redacted by its `:decode` schema (a transient payload), and the **durable copy** in app-db is redacted by step 1's path classification. Those are two separate declarations for the same secret on two different surfaces; there is no propagation that carries one to the other. Classify each surface the secret crosses — the wire it arrives on, *and* the slot it comes to rest in.

### HTTP carriers: header and query-param names

There's one more transient HTTP surface, and it's the one people forget: the **request** side. An `Authorization: Bearer …` header or a `?shop_token=…` query param is a secret travelling in the request, and managed HTTP records the request shape. These aren't classified by path into the body — they're classified by **name**, on the `:rf.http/managed` registration's `:carriers` block:

```clojure
;; extend the built-in carrier denylist with your app's own secret-bearing names
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team" "X-Stripe-Signature"]
              :query-params ["shop_token"]}}
  managed-http-handler)
```

The framework ships an **immutable built-in denylist** — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, and the usual suspects — that no app can remove. Your `:carriers` block **unions** onto it; it never replaces a built-in name. The one subtraction valve is query-params, which accept a `{:include … :except …}` map so you can stop redacting a *harmless* routing/pagination name in your own dev trace (effective policy `(defaults − except) ∪ include`):

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:query-params {:include ["shop_token"]   ;; extend defaults
                             :except  ["token"]}}}      ;; subtract a non-secret default
  managed-http-handler)
```

Carriers are **process-global** (one registration, not per-frame), and a malformed `:carriers` block fails loud with `:rf.error/bad-classification`. Full carrier reference: [Spec 014 §HTTP carriers](../../../spec/014-HTTPRequests.md#http-carriers-ep-0025).

## 3. Subsystem data: declare it on the subsystem, projection-relative

Some data lives inside a runtime subsystem — a machine's `:data`, a resource's fetched data or params. You don't own its absolute storage path (the subsystem mints that, per instance), so you declare `:sensitive` / `:large` **relative to the instance's shape** on the subsystem definition. The framework lowers the declaration into the registry per instance (at spawn / fetch) and drops it on teardown:

```clojure
;; a machine declares its own sensitive / large :data slots, projection-relative
;; to one actor snapshot's :data. The [:schemas :data] schema still VALIDATES
;; :data; it no longer classifies it for snapshot egress — that is the
;; declaration below.
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :payment :token]]
   :large     [[:data :payment :receipt-pdf]]
   :schemas   {:data [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]}
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {:spawn {:src :checkout/charge :on-done :done}}
             :done       {}}})

;; a resource declares its own statically-known sensitive / large fields
(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

That `[:data :payment :token]` slot redacts in every machine trace — the before/after of a transition, snapshots, guard inputs — for every spawned actor instance, with no per-instance author code. Rename the slot in the declaration and the classification moves with it. One declaration on the type, applied to every instance: this is the payoff for declaring at the definition site rather than at each egress.

The same shape covers **four** subsystems, each with its own projection root — the framework re-roots your projection-relative path to that instance's absolute runtime path and drops the declaration on teardown:

| Subsystem | Projection root (what your path is relative to) | Lowered at | Dropped at |
|---|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | actor spawn / first-boot | actor destroy (any cause) |
| `reg-resource` | the entry's `:params` / `:data` | params at scoped-key mint; data when the fetch lands | entry eviction |
| `reg-mutation` | one work row's `:params` | work creation | work completion |
| `reg-route` | the current route's `:query` / `:params` | route activation | route change / deactivation |

So a route that carries a token in its query string (`?reset_token=…`) classifies it on the route definition:

```clojure
(rf/reg-route :password-reset
  {:path "/reset" :sensitive [[:query :reset-token]]})
```

A malformed subsystem declaration fails loud at registration under its own per-subsystem error id — `reg-machine` raises `:rf.error/invalid-machine-classification`, a bad resource spec folds into `:rf.error/invalid-resource-spec`.

> **The schema prop is a *different* axis — validation-failure traces, not durable egress.** A `:sensitive?` / `:large?` prop on a `[:schemas :data]` slot still does exactly one thing: it redacts that slot in the schema's own **validation-failure trace** (the schema produces that record, so it owns its egress shape). It does **not** classify the durable `:data` for snapshot egress — that's the projection-relative `:sensitive` / `:large` declaration above. The same `:sensitive?` prop on an HTTP request's `:decode` schema *is* the right and only route for the *transient* response body (step 2). The rule underneath: schemas own *transient* and *validation-failure* products; durable state is the effect (step 1) or the subsystem declaration (here).

This trips people up, so it's worth pinning the difference between `:sensitive` and `:sensitive?` once and for all. `:sensitive` (no `?`) names a *collection of paths* — a classification effect, a registration mark, a subsystem declaration. `:sensitive?` (with `?`) is a *yes/no Malli prop on one schema slot*, surviving only for validation-failure-trace redaction and for the schema-owned *transient* products (HTTP `:decode` body, resource params). Because `:sensitive` is a path collection, it's never spelled `:sensitive false` — you *clear* the sensitive axis with `:clear-sensitive`, you don't set it false. The `:large` / `:large?` pair carries the identical distinction. Three durable owners, no overlap:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable `app-db` state | the **event** | `:sensitive` / `:large` classification effects (step 1) |
| Subsystem instance data (machine `:data`, resource data/params, route query) | the **subsystem definition** | projection-relative `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route` (step 3) |
| Transient payloads (event args, fx/cofx, sub outputs, HTTP bodies, header/query carriers) | the **registration** / its `:decode` schema | `:sensitive` / `:large` path vectors, `:sensitive?` props on the `:decode` schema, or the `:carriers` block (step 2) |

**Classification does not propagate.** A sub or flow that *reads* a sensitive input does not auto-classify its output. If you derive a secret to a new path — through a sub, a flow, a rendered field — classify *that* path. A sensitive flow output is just a classified db path. There is no `:rf.egress/output-sensitivity` declassification claim (it's gone, silently ignored if present) and no value-match "same value redacted everywhere" engine. Path in, path out, every time.

> **The size backstop is the one thing that *does* fire without a declaration.** The large axis has a safety net the sensitive axis doesn't: an *oversized* value auto-elides at egress even at a path you never classified `:large`, governed by `:rf.size/threshold-bytes` (default 16384, overridable via `(rf/configure! {:elision {…}})`). That keeps a surprise 5MB blob from flooding a trace. It is **not** a secrecy backstop, though — a small secret sails straight through. Sensitive is strictly opt-in by path.

## 4. Before you wire an off-box shipper

Production observation records route by the frame's `:observability` policy. There are **two** streams — `:handled-events` (one production-safe record per event processed) and `:errors` (production-survivable error records). Name a sink id and the boundary's profile for each, then register the concrete function:

```clojure
(rf/reg-frame :app/main
  {:observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability
                     :opts {:service "checkout-spa" :env "prod"}}]
                   :errors
                   [{:sink :my-app.sinks/sentry
                     :rf.egress/profile :rf.egress/off-box-observability
                     :opts {:service "checkout-spa" :env "prod"}}]}
   :initial-events [[:auth/init]]})         ;; classifies [:auth :token] (step 1)

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))

(rf/register-observability-sink! :my-app.sinks/sentry
  (fn [projected-record] (sentry/capture projected-record)))
```

The `:errors` stream deserves special attention here: an error record can carry the offending `:event` vector *and* a frame-classified app-db slice, so it's the most likely place a secret surfaces if you classified only your happy path. Both streams project under the **same** frame classification, so step 1's `[:auth :token]` is redacted in an error record exactly as it is in a handled-event. To retire a sink — at teardown, or when swapping implementations — there's `(rf/unregister-observability-sink! :my-app.sinks/datadog)`; re-registering the same id simply replaces it.

Verify these before the first record ships:

- **Every secret's *path* has a declaration.** Walk the three-row table for each one, on every surface the secret crosses (durable app-db, transient payload, subsystem). That declaration is the whole defence; the contract is fail-open, so an undeclared path leaks with no second backstop.
- **The sink does no redaction.** If you find yourself writing a scrub inside the sink function, a declaration is missing upstream. Fix the owner, not the sink.
- **Direct reads project app-side, with the frame known.** A reach into live state that bypasses the trace pipeline — `rf/app-db-value`, `rf/sub-cache`, an MCP `get-path` — is *not* auto-projected. If you ship the result off-box, run it through `rf/project-egress` yourself, naming the frame:

    ```clojure
    (rf/project-egress (rf/app-db-value :app/main [:auth])
      {:frame :app/main :path [:auth] :rf.egress/profile :rf.egress/off-box-tool})
    ```

  Omit the frame and the projection **fails closed** — it redacts the whole value rather than guess an ambient scope. (`project-egress` is the record-level primitive; `rf/elide-wire-value` is the lower-level walker it delegates to for bare tree-shaped values — you rarely call it directly.)
- **The off-box default omits event args entirely.** A projected handled-event record carries the frame, event id, status, timing, and effect keys — but there's no `:event` slot at all. Confirm it at the REPL with the same primitive the runtime uses:

    ```clojure
    (rf/project-egress
      {:kind     :rf.observe/handled-event
       :frame    :app/main
       :event-id :auth/sign-in
       :event    [:auth/sign-in {:password "hunter2"}]}
      {:rf.egress/profile :rf.egress/off-box-observability})
    ;; => {:kind :rf.observe/handled-event :frame :app/main
    ;;     :event-id :auth/sign-in ...}   ;; no :event slot
    ```

- **Know the fail-closed properties.** A frame with no `:observability` policy routes nothing. An unresolvable frame routes nothing — never a default frame. An unknown egress profile is rejected with `:rf.error/unknown-egress-profile` (the enum is closed). A throwing sink is isolated from its siblings, so one bad shipper can't take the others down. (Fail-*closed* here is the projector's posture on an unknown frame/profile; fail-*open* is the classification posture on an undeclared path — different surfaces, different defaults, and worth keeping straight in your head.)

### Which profile for which boundary

`:rf.egress/profile` takes a value from a **closed six-member enum** — you pick the *boundary*, not a remembered set of booleans. The two off-box profiles are the ones a shipper cares about; the rest cover dev panels, trusted-local reveal, SSR, and server error responses:

| Profile | Boundary |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb): redact sensitive, elide large, omit raw `:event` args |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire: redact sensitive, elide large, include shape digests so a tool can reason about structure without content |
| `:rf.egress/local-redacted` | on-box dev-UI default: suppress sensitive, may show size indicators |
| `:rf.egress/local-raw` | trusted local operator opt-in: include sensitive + large (subject to size caps) |
| `:rf.egress/ssr-hydration` | the projection applied *after* the SSR allowlist (defence-in-depth) |
| `:rf.egress/public-error` | client-safe server error responses; never internal raw values |

Beneath the profiles sit the advanced `:rf.size/*` override flags (`:rf.size/include-sensitive?`, `:rf.size/include-large?`, `:rf.size/include-digests?`, `:rf.size/threshold-bytes`) — a profile resolves to a floor and an explicit flag overlays it. You rarely reach for them; the profile is the public choice.

> **Exceptions are the one gap.** Projection walks known data shapes. It can't un-concatenate a secret out of an `ex-message` string, and it can't guess which `ex-data` keys are sensitive. So in handlers that read secrets, throw the *category*, never the value:
>
> ```clojure
> ;; ANTI-PATTERN — the email lands in the error record verbatim.
> (throw (ex-info (str "User " email " failed login") {:user/email email}))
>
> ;; PREFERRED — name the category; omit the value.
> (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
> ```
>
> The exception's job is to say *what went wrong*, not to carry the secret that caused it. (The framework ships no `rf/safe-throw` — which `ex-data` keys are sensitive is author knowledge. Its *own* adapter/render diagnostics carry only a shape summary, never the raw value, so the residual here is exclusively your app's throw-sites.)

## 5. Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. (To *dispatch* is to send an event into the system for handling.) The event row carries a magenta redacted marker on its arg map. The `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too. On-box panels render under the local-redacted profile, so your dev view exercises the very same classifications your shipper relies on — you debug against the redacted shape every day, which means you find a missing declaration in development, not in a production log review. Three sentinel shapes to look for:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `:rf/large {:bytes N :head "…"}` — large; drillable on-box, elided off-box. (The underlying wire marker is `:rf.size/large-elided`, carrying `:path` / `:bytes` / `:type` / `:reason` / a `:handle` for re-fetch; the rich form above is the display rendering of it.)
- `:rf/redacted {:bytes N}` — both; sensitive wins, so only the size may show (and the reference suppresses even that — don't depend on `:bytes` riding alongside `:rf/redacted`).

A large marker *may* offer a guarded click-to-expand (a size-confirmed re-fetch via its `:handle`); a redacted marker never may. If you genuinely need to see a sensitive value on-box, that's the trusted-local `:rf.egress/local-raw` opt-in — and revealing it is **itself an audited, trace-visible operator act**, not a quiet global toggle. There is no process-wide `show-sensitive?` switch; visibility is per (tool, frame) pair.

A value you expected scrubbed but that renders raw means the *path* declaration is missing or mis-pathed — or the secret was re-keyed to a path you didn't classify (the fail-open case). Classify the path it actually lives at, and the fix lands on every surface at once: Xray, the epoch ledger, and your off-box shipper all read the one registry you just corrected.

---

You can now:

- classify a secret or a blob at the *path* its owner controls — an event effect, a registration mark, a subsystem declaration, or an HTTP `:carriers` name
- keep handler code working on real values while every observable surface ships sentinels
- route both the `:handled-events` and `:errors` streams to off-box sinks, and verify what each will receive with `rf/project-egress` before wiring it
- project a direct read app-side with the frame known, and spot-check classifications live in Xray, reading the three sentinel shapes
