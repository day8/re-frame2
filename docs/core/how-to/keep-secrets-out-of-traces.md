# Keep secrets and large things out of traces

Your login form just dispatched `[:auth/sign-in {:password "hunter2"}]`.

In re-frame2 an [event](../glossary.md#event) is *just data* — an inert vector recording that something happened — so that password is now plain data sitting in your system. And re-frame2 is unusually observable. Events, snapshots of [app-db](../glossary.md#app-db), and HTTP records all flow down one in-process feed — the [trace stream](../glossary.md#trace-stream) — and every tool drinks from that one feed ([one wire, every tool](../observability.md)). [Xray](../glossary.md#xray), the dev inspector, reads it live. The [epoch](../glossary.md#epoch) history — the before/after record each event leaves behind, for [time-travel](../glossary.md#time-travel) debugging — keeps it. And any **shipper** — a [listener](../glossary.md#listener) you register to forward records to a hosted monitor like Datadog or Sentry — can carry it off-box.

That's a feature most of the time and a problem exactly once: when the data on the wire is a password, a token, or a 5MB upload. This page is the short list of declarations that keep those off the wire — **without ever hiding them from your own handler code**.

Whenever a value leaves the wire for somewhere it can be *observed* — a dev panel, the saved epoch history, an off-box monitor — it crosses what we'll call an **observation boundary** (or just *egress*: the point where data exits the runtime to be looked at). And the whole page fits in one sentence: **you name a *path* as sensitive once, where the data is defined, and the framework redacts whatever lives at that path everywhere it crosses an observation boundary.** That's it. That's the framework's [data classification](../glossary.md#data-classification) capability, and the rest of this page is that sentence applied to each kind of owner, one declaration at a time.

!!! note "This is hygiene, not a security boundary — and it is fail-open"

    The framework keeps secrets off its *own* observability wire; your app still owns auth, encryption, and transport. The contract is **fail-open**: a path you never classify ships raw. That's the bargain — convenient leak-prevention, not a guarantee. The framework does no *taint-tracking* (it never follows a secret value around to redact every copy), so a secret you copy to a new path — a re-keyed value, a rendered field — ships raw until you classify *that* path too.

??? info "Coming from Sentry?"

    The instinct is `beforeSend`: a scrub function you write into each consumer, run just before shipping. re-frame2 does the opposite. You classify data **once, at the owner of its shape**, and the framework applies it at every boundary it owns. There's no `beforeSend` to write — which means there's no Nth consumer left to forget one.

## Classify a durable secret in app-db

Start with the most common case: a token that *lives* in app-db at a path you own. You classify it by returning a **classification effect** from an [event handler](../glossary.md#event-handler) — right alongside `:db`, in the same [effect map](../glossary.md#effect-map):

```clojure
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token]]}))
```

What just happened: `:sensitive` takes a vector of paths and marks each one. From now on, whatever value comes to rest at `[:auth :token]` is redacted to `:rf/redacted` everywhere it would cross an observation boundary — the App-DB panel in Xray, the saved epoch history, your off-box shipper. Your handlers still read the real token. What gets redacted is only the **observable shadow** of your data: the copy the framework projects onto the wire for tools to look at, separate from the live value your code actually runs on. That term does a lot of work on this page — hold onto it.

Notice we classified the path **before any token exists there** — `:auth/init` just seeds an empty map. That's the safe, common pattern: a classification over an absent path is a harmless no-op that quietly redacts whatever later occupies the path. You don't re-classify on every write.

Wire `:auth/init` to run at frame creation with `:initial-events`, so the classification is in place before any value can reach an observation boundary:

```clojure
(rf/make-frame
  {:id :app/main
   :initial-events [[:auth/init]]})
```

!!! note "Classify at the boundary"

    re-frame2 lets the secret live where it naturally belongs and redacts it *at the boundary* — you don't keep a token out of traces by keeping it out of app-db. There is no frame-level `:sensitive {:app-db …}` annotation and no top-level `:large` frame key (both fail loud with `:rf.error/bad-frame-classification`). The event is app-db's definition site; that is the one route.

??? info "For JavaScript developers"

    `:initial-events` is the only frame-init surface — there is no `:initial-db` and no `:on-create`. A [frame](../glossary.md#frame) always starts with `app-db = {}`; seeding it is itself an event (`[:rf/set-db {…}]`) dispatched as the first init step. See [the frames concept page](../frames.md).

### Two axes: sensitive and large

There are actually two independent things you can say about a path. `:sensitive` redacts the value; `:large` replaces an oversized value with a size marker so a 5MB upload doesn't flood a trace. Each has a clearing inverse:

```clojure
:sensitive       [[path] …]   ; redact at egress
:large           [[path] …]   ; size marker at egress
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

The axes never touch each other — `:clear-sensitive` is the precise inverse of `:sensitive`, like `dissoc` to `assoc`, and leaves the large axis alone. And if a path is declared *both*, **sensitive wins**: no size marker is emitted, because even "there's a 5MB blob here" says too much about a secret.

When a secret's path is only *discovered at runtime*, classify it in the handler that writes it:

```clojure
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))
```

You reach for `:clear-*` rarely — when a value disappears its redaction effectively disappears with it, so clearing earns its keep only when a *path is reused* for non-secret data:

```clojure
;; a path that held PII is overwritten with sanitised content — un-classify it
(rf/reg-event :doc/sanitised
  (fn [{:keys [db]} [_ doc-id clean]]
    {:db              (assoc-in db [:docs doc-id :body] clean)
     :clear-sensitive [[:docs doc-id :body]]}))
```

One mistake to dodge: `:sensitive` is a *path collection*, never a flag. You *clear* the sensitive axis with `:clear-sensitive`; you never set `:sensitive false`. (And don't confuse it with `:sensitive?`, the yes/no schema prop — that's a different axis, covered later.)

??? note "Going deeper"

    The classification effects are applied **with the `:db` write** — a frame-state transform at the [commit](../glossary.md#commit) point, not a later `:fx`. They ride the [runtime-db](../glossary.md#runtime-db) partition — the framework's `:rf.db/runtime` state beside your app-db — in its `:rf.runtime/elision` registry, not app-db, which buys two properties for free. First, a path classified in an event is redacted from its *very first* egress (no race against the value landing). Second, because the registry lives in revertible runtime-db, a classification **walks back atomically with the frame** on a `restore-epoch` revert — a reverted secret is still a classified secret. Malformed effects (a non-vector value, an unknown axis) abort the transition pre-commit with `:rf.error/classification-effect-shape`, so a typo can't silently disable your protection.

## Classify a transient payload on the registration

Not every secret lives in app-db. Some *flow through* the [event pipeline](../introduction.md) — the fixed, ordered run one dispatch sets off — passing through event args, fx/cofx values, or a [subscription](../glossary.md#subscription)'s output, and never coming to rest at an app-db path. These are owned by the [registration](../glossary.md#registration) that introduces their shape. Declare the sensitive paths right there, in the registration map, relative to the payload:

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
```

The handler body still sees `password` verbatim — handlers need real values to work. Only the *observable shadow* is projected: the dispatched-event trace and the HTTP record ship `:password` as `:rf/redacted`. Paths index into the registration's primary shape (here the event arg-map); an empty path `[[]]` marks the whole shape, and a mark at a missing slot is a silent no-op (payload shapes evolve; the mark waits patiently for the slot to reappear).

(That phrase "the registration's primary shape" means the main payload each kind of registration introduces: for an event it's the arg-map — the second element of the event vector, the `{…}` after the event id — and for the others it's the value they produce, as the next examples show.)

The same `:sensitive` metadata key works on **every** registration that introduces a payload shape — not just events:

```clojure
;; the whole sub output is sensitive
(rf/reg-sub :partner/api-token {:sensitive [[]]}
  (fn [db _] (get-in db [:tenant :partner-api-key])))

;; a coeffect classifies the value it injects, relative to that value
(rf/reg-cofx :session {:sensitive [[:token]]}
  (fn [cofx] (assoc cofx :session {:user "alice" :token (read-token)})))

;; an effect classifies paths into its own fx-input map
(rf/reg-fx :rf.ws/send {:sensitive [[:auth]]} ws-send-handler)
```

!!! warning "Gotcha — a positional secret ships raw"

    A path like `[:password]` reaches into the event's **arg-map** (`[:auth/sign-in {:password "…"}]`), so the mark can name it. A positionally-passed secret (`[:auth/sign-in "alice" "hunter2"]`) has **no** stable named path — a positional index isn't addressable, so there's nothing for `:sensitive` to classify. Under fail-open that means it ships RAW into every trace and error sink. This is a structural limitation, not a bug: redaction is path-based, and only the arg-map (`(second event)`) is reachable. **So when an event carries a secret, pass a map payload and classify the key.** (The glossary [recommends a map payload over positional args](../glossary.md#event) anyway — this is one more reason.) The positional form is fine for data you wouldn't mind seeing in a trace.

!!! warning "Gotcha — a subscription's query vector is a cache key, so treat it like a URL"

    That `{:sensitive [[]]}` on `:partner/api-token` classifies what the sub **computes**. It says nothing about the vector you *call it with* — and a query vector is never redacted, on purpose.

    A query vector is **identity**: it's the key your subscription is cached under, the key used to decide a recompute can be skipped, and the endpoint of an edge in the reactive graph. A value doing that much work is visible to every layer that touches the cache — the trace stream, the epoch history, Xray, your shipper — so redacting it in one place couldn't contain it, and the framework doesn't pretend otherwise. It ships raw on **every** slot that carries it, including the always-on error records that survive into production.

    So pass **identifiers, not secrets** — the same instinct that stops you putting a password in a URL:

    ```clojure
    ;; PREFERRED — an id in the vector; the secret stays in classified app-db.
    (rf/reg-sub :patient/record {:sensitive [[:ssn]]}
      (fn [db [_ patient-id]] (get-in db [:patients patient-id])))

    (rf/subscribe [:patient/record patient-id])   ;; ✓ an id
    ;; (rf/subscribe [:patient/record ssn])       ;; ✗ rides raw on every trace slot
    ```

    The body reads the secret out of app-db (redacted by *its* path) and the sub's own `:sensitive` redacts the **output**. This is the positional gotcha above, one surface over: a query argument *is* a positional argument, unreachable by a path-based mark for exactly the same reason.

Note the `:decode` schema in the sign-in example: `[:token {:sensitive? true} :string]` classifies the *response body*. That's a second, separate declaration for the same secret — the **HTTP reply** is redacted by its `:decode` schema, and the **durable copy** that `:auth/signed-in` later stores at `[:auth :token]` is redacted by the path classification from the first section. There's no propagation carrying one to the other. **Classify each surface the secret crosses** — the wire it arrives on, *and* the slot it comes to rest in.

And while a mark at a *missing* slot is a silent no-op, a *malformed* mark is loud: a non-vector path is rejected at *registration* with `:rf.error/bad-classification` (a [flow](../glossary.md#flow)'s bad output marks raise `:rf.error/flow-bad-marks`), so the typo surfaces when you reload — not in a production log six weeks later.

### HTTP carriers: redact by header and query-param name

There's one transient HTTP surface people forget: the **request** side. An `Authorization: Bearer …` header or a `?shop_token=…` query param is a secret travelling in the request, and [managed HTTP](../../resources/glossary.md#managed-http) records the request shape. These aren't classified by path into the body — they're classified by **name**, on the `:rf.http/managed` registration's `:carriers` block:

```clojure
;; extend the built-in carrier denylist with your app's own secret-bearing names
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team" "X-Stripe-Signature"]
              :query-params ["shop_token"]}}
  managed-http-handler)
```

The framework ships an **immutable built-in denylist** — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, and the usual suspects — that no app can remove. Your `:carriers` block **unions** onto it. The one subtraction valve is query-params, which accept a `{:include … :except …}` map so you can stop redacting a *harmless* routing name in your own dev trace (effective policy `(defaults − except) ∪ include`):

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:query-params {:include ["shop_token"]   ;; extend defaults
                             :except  ["token"]}}}      ;; subtract a non-secret default
  managed-http-handler)
```

Carriers are **process-global** (one registration, not per-frame), and a malformed `:carriers` block fails loud with `:rf.error/bad-classification`.

## Classify subsystem data on the subsystem

Some data lives *inside* a runtime subsystem — a [machine](../../machines/glossary.md#machine)'s `:data`, a [resource](../../resources/glossary.md#resource)'s fetched data or params, a [route](../../routing/glossary.md#route)'s query string. You don't own its absolute storage path (the subsystem mints that fresh for each instance), so you declare `:sensitive` / `:large` **relative to the instance's shape**, right on the subsystem definition. Each time an instance is created (a machine spawns, a resource fetches), the framework translates your shape-relative declaration into a concrete path in the classification registry for that instance, and removes it again when the instance is torn down:

```clojure
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :payment :token]]
   :large     [[:data :payment :receipt-pdf]]
   :schemas   {:data [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]}
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {:spawn {:machine-id :checkout/charge :on-done :done}}
             :done       {}}})
```

That `[:data :payment :token]` slot now redacts in **every** machine trace — the before/after of a [transition](../../machines/glossary.md#transition), [snapshots](../../machines/glossary.md#snapshot), [guard](../../machines/glossary.md#guard) inputs — for every spawned actor instance, with zero per-instance author code. Rename the slot in the declaration and the classification moves with it. One declaration on the *type*, applied to every instance: that's the payoff for declaring at the definition site rather than at each egress.

The same shape covers **four** subsystems. Each has its own **projection root** — the spot inside one instance that your path is measured *from*, so you only ever write the part of the path you own. The framework joins your relative path onto that instance's real runtime location, and drops the declaration when the instance goes away:

| Subsystem | Projection root (what your path is relative to) | Translated to a real path at | Dropped at |
|---|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | actor spawn / first-boot | actor destroy (any cause) |
| `reg-resource` | the entry's `:params` / `:data` | params at scoped-key mint; data when the fetch lands | entry eviction |
| `reg-mutation` | one work row's `:params` | work creation | work completion |
| `reg-route` | the current route's `:query` / `:params` | route activation | route change / deactivation |

So a route carrying a token in its query string (`?reset_token=…`) classifies it on the route definition, and a resource declares its own statically-known fields:

```clojure
(rf/reg-route :password-reset
  {:sensitive [[:query :reset-token]]}
  "/reset")

(rf/reg-resource :user-profile
  {:sensitive     [[:data :ssn]]
   :large         [[:data :avatar-bytes]]
   :scope         {:from-db :app/session}
   :params-schema [:map [:user-id :string]]}
  (fn [{:keys [user-id]} _ctx]
    {:request {:method :get :url (str "/api/users/" user-id)}}))
```

A malformed subsystem declaration fails loud at registration under its own per-subsystem error id — `reg-machine` raises `:rf.error/invalid-machine-classification`, a bad resource spec folds into `:rf.error/resource-bad-spec`.

!!! warning "Gotcha"

    These trip people up, so pin the difference once. `:sensitive` (no `?`) names a *collection of paths* — a classification effect, a registration mark, or a subsystem declaration. `:sensitive?` (with `?`) is a *yes/no Malli prop on one schema slot*. A `:sensitive?` prop on a `[:schemas :data]` slot redacts that slot only in the schema's own **validation-failure trace** — it does **not** classify the durable `:data` for snapshot egress (that's the projection-relative declaration above). The same `:sensitive?` prop on an HTTP `:decode` schema *is* the right and only route for the *transient* response body. The rule underneath: **schemas own transient and validation-failure products; durable state is the effect or the subsystem declaration.** The `:large` / `:large?` pair carries the identical distinction.

### The whole ownership map, on one page

Three durable owners, no overlap. When you have a secret, this is the table to walk:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable app-db state | the **event** | `:sensitive` / `:large` classification effects |
| Subsystem instance data (machine `:data`, resource data/params, route query) | the **subsystem definition** | projection-relative `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route` |
| Transient payloads (event args, fx/cofx, sub outputs, HTTP bodies, header/query carriers) | the **registration** / its `:decode` schema | `:sensitive` / `:large` path vectors, `:sensitive?` props on the `:decode` schema, or the `:carriers` block |

??? note "Going deeper — classification does not propagate"

    A sub or flow that *reads* a sensitive input does **not** auto-classify its output. If you derive a secret to a new path — through a sub, a flow, a rendered field — classify *that* path. A sensitive flow output is just a classified db path. There's no value-match "same value redacted everywhere" engine and no declassification claim that travels with a derived value. Path in, path out, every time. The design picks the simplest thing that works: record the path, redact at egress. No taint, no propagation, no magic.

!!! warning "The size threshold *warns*; it does not elide"

    It is tempting to think the large axis has a safety net the sensitive axis lacks — that an *oversized* value is auto-elided at egress even at a path you never classified `:large`. **It is not.** When the walker meets a large string at an undeclared path it emits the `:rf.warning/large-value-unschema'd` advisory — a *nudge* to go classify the path — governed by `:rf.size/threshold-bytes` (default 16384, overridable via `(rf/configure! {:elision {:rf.size/threshold-bytes N}})`), and then **forwards the value raw**. The threshold is a warning signal, not a cap. To actually keep an oversized value out of a trace you must declare the path `:large`; to keep a *secret* out you must declare it `:sensitive`. Nothing auto-protects a value you never classified — classification is strictly opt-in by path, on both axes.

## Wire an off-box shipper

Now send the production records off-box — to Datadog, Sentry, wherever you watch prod. You set this up per frame, under an `:observability` key. There are **two** streams you can route: `:handled-events` (one production-safe record per event processed) and `:errors` (production-survivable [error records](../glossary.md#error-record)). For each, you name a **sink** (an id for the destination), the **profile** (which boundary it's crossing — more on profiles just below), then register the concrete function that does the sending:

```clojure
(rf/make-frame
  {:id :app/main
   :observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability
                     :opts {:service "checkout-spa" :env "prod"}}]
                   :errors
                   [{:sink :my-app.sinks/sentry
                     :rf.egress/profile :rf.egress/off-box-observability
                     :opts {:service "checkout-spa" :env "prod"}}]}
   :initial-events [[:auth/init]]})         ;; classifies [:auth :token]

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))

(rf/register-observability-sink! :my-app.sinks/sentry
  (fn [projected-record] (sentry/capture projected-record)))
```

The shape to internalise: **you declare; the framework projects; the sink consumes already-safe records.** The sink never scrubs anything itself — by the time a record reaches it, every classified slot is already a sentinel. If you ever catch yourself writing a scrub inside a sink function, a declaration is missing *upstream*; fix the owner, not the sink.

Both streams project under the **same** frame classification, so the `[:auth :token]` you classified at the start is redacted in an error record exactly as it is in a handled-event. The `:errors` stream deserves a second look: an error record can carry the offending `:event` vector *and* a frame-classified app-db slice, so it's the most likely place a secret surfaces if you classified only your happy path.

To retire a sink — at teardown, or when swapping implementations — there's `(rf/unregister-observability-sink! :my-app.sinks/datadog)`; re-registering the same id replaces it.

### Choosing a profile

A profile names the *boundary* the data is crossing — *who is about to look at this?* — rather than making you remember the right combination of on/off flags for each destination. You pick one value from a **closed set of six** (closed meaning the framework defines them all; you can't invent a new one). The two off-box profiles are the ones a shipper cares about; the rest cover dev panels, trusted-local reveal, SSR, and server error responses:

| Profile | Boundary |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb): redact sensitive, elide large, omit raw `:event` args |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire: redact sensitive, elide large, include shape digests so a tool can reason about structure without content |
| `:rf.egress/local-redacted` | on-box dev-UI default: suppress sensitive, may show size indicators |
| `:rf.egress/local-raw` | trusted local operator opt-in: include sensitive + large (subject to size caps) |
| `:rf.egress/ssr-hydration` | the projection applied *after* the SSR allowlist (defence-in-depth) |
| `:rf.egress/public-error` | client-safe server error responses; never internal raw values |

The table above is the working set; each profile names how far data travels and resolves to a floor the flags below can override.

??? note "Going deeper"

    Beneath the profiles sit advanced `:rf.size/*` override flags — `:rf.size/include-sensitive?`, `:rf.size/include-large?`, `:rf.size/include-digests?`, `:rf.size/threshold-bytes`. A profile resolves to a *floor* and an explicit flag overlays it. You rarely reach for them; the profile is the public choice.

### Two things to verify before the first record ships

**Direct reads are not auto-projected.** Everything so far has relied on the framework *projecting* your data — applying the classifications to produce a safe copy — automatically as it crosses a boundary. But a direct grab of live state that sidesteps the normal observation path — `rf/app-db-value`, a sub-cache snapshot, an MCP `get-path` — hands you the raw value, with no projection applied. If you ship that result off-box yourself, run it through [`rf/project-egress`](../glossary.md#project-egress) first, naming the frame so it knows whose classifications to apply:

```clojure
(rf/project-egress (get-in (rf/app-db-value :app/main) [:auth])
  {:frame :app/main :path [:auth] :rf.egress/profile :rf.egress/off-box-tool})
```

Omit the frame and the projection **fails closed** — it redacts the whole value rather than guess an ambient scope. (`project-egress` is the record-level primitive; `rf/elide-wire-value` is the lower-level walker it delegates to for bare tree-shaped values — you rarely call it directly.)

**Confirm the off-box default omits event args entirely.** A projected handled-event record carries the frame, event id, status, timing, and effect keys — but no `:event` slot at all. Check it at the REPL with the same primitive the runtime uses:

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

!!! warning "Gotcha — fail-open and fail-closed live on different surfaces"

    *Fail-open* is the **classification** posture: an undeclared path ships raw. *Fail-closed* is the **projector's** posture on an unknown frame/profile: a frame with no `:observability` policy routes nothing; an unresolvable frame routes nothing (never a default frame); an unknown profile is rejected with `:rf.error/unknown-egress-profile` (the enum is closed); a throwing sink is isolated from its siblings. Different surfaces, different defaults — worth keeping straight in your head.

??? note "Going deeper — exceptions are the one gap"

    Projection walks *known data shapes*. It can't un-concatenate a secret out of an `ex-message` string, and it can't guess which `ex-data` keys are sensitive. So in handlers that read secrets, throw the *category*, never the value:

    ```clojure
    ;; ANTI-PATTERN — the email lands in the error record verbatim.
    (throw (ex-info (str "User " email " failed login") {:user/email email}))

    ;; PREFERRED — name the category; omit the value.
    (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
    ```

    The exception's job is to say *what went wrong*, not to carry the secret that caused it. (There's no `rf/safe-throw` — which `ex-data` keys are sensitive is author knowledge. The framework's *own* adapter/render diagnostics carry only a shape summary, never the raw value, so the residual here is exclusively your app's throw-sites.)

## Advanced

Two surfaces that aren't part of the everyday flow but are worth knowing exist: the **hydration payload** (one more egress boundary, if you render on the server) and the **epoch ledger** (where classification meets time-travel).

### SSR and hydration — a fourth egress boundary

If you run [SSR](../../ssr/concepts.md), the server hands the client a **hydration payload** — a serialised slice of app-db the browser adopts on first render. That's production egress to an untrusted client, so it's a boundary the same classification guards. But SSR is **allowlist-first**, which is the stronger posture: it asks *"which state is allowed to cross?"*, not *"which leaves are sensitive?"*. You name the slice that may ship; **unlisted state does not cross even if you never classified it**, and a frame that renders on the server with no payload policy at all **fails closed** (`:rf.error/ssr-missing-payload-policy`) rather than shipping app-db whole.

Classification then composes on top as **defence-in-depth**: a sensitive child of an allowlisted slice is *still redacted* unless the SSR policy explicitly permits it. The `:rf.egress/ssr-hydration` profile from the table above is exactly the projection applied **after** the allowlist — never a parallel mechanism. (The per-frame classification registry is itself kept off the hydration wire — a classified path can embed a sensitive id like `[:by-id "user-secret" :token]`, so the declaration keys *are* sensitive structure — and the client rebuilds its own identical registry from the same app image on mount.) The allowlist opt lives on the SSR host surface, not here; see [the SSR concept page](../../ssr/concepts.md) for its exact shape.

### Classification and time-travel — epoch records stay raw

Classifying a path does **not** break [time-travel](../glossary.md#time-travel). An [epoch](../glossary.md#epoch) is causal replay material, so the framework keeps the **raw** value in the stored record — mutating it at rest would corrupt the replay contract, and a `restore-epoch` revert is meant to put the *real* value back. Redaction is applied only when an epoch **leaves** the box: an off-box epoch export must go through `project-egress` under an off-box profile, exactly like any other record. There is no storage-side `:redact-fn` hook — egress redacts, storage stays raw, and the two diverge on purpose so a redacted-frame epoch still replays the genuine value locally.

The everyday consequence: your dev [epoch ledger](../glossary.md#epoch) and the on-box Xray diff show real values (you're a trusted local operator under `:rf.egress/local-redacted`/`local-raw`), while the same epoch *shipped* to a hosted monitor is projected. Don't reach for a scrub-on-store hook — there isn't one, and you don't want one.

## Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. The event row carries a magenta redacted marker on its arg map; the `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too.

On-box panels render under the `:rf.egress/local-redacted` profile, so your dev view exercises the *very same* classifications your shipper relies on. You debug against the redacted shape every day — which means you find a missing declaration in development, not in a production log review.

Three sentinel shapes to recognise:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `{:rf.size/large-elided {:path … :bytes … :type … :reason … :handle …}}` — large; that map is what Xray's diff view actually shows you, drillable on-box and elided off-box. (A tool *may* render it compactly as `:rf/large {:bytes N :head "…"}` — treat that as a display form of the same marker.)
- `:rf/redacted {:bytes N}` — both; sensitive wins, so only the size *may* show (and the reference suppresses even that — don't depend on `:bytes` riding alongside `:rf/redacted`).

A large marker *may* offer a guarded click-to-expand (a size-confirmed re-fetch via its `:handle`); a redacted marker never may. If you genuinely need to see a sensitive value on-box, that's the trusted-local `:rf.egress/local-raw` opt-in — and revealing it is **itself an audited, trace-visible operator act**, not a quiet global toggle. There is no process-wide `show-sensitive?` switch; visibility is per (tool, frame) pair.

A value you expected scrubbed but that renders raw means the *path* declaration is missing or mis-pathed — or the secret was re-keyed to a path you didn't classify (the fail-open case). Classify the path it actually lives at, and the fix lands on every surface at once: Xray, the epoch ledger, and your off-box shipper all read the one registry you just corrected.
