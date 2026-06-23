# Keep secrets and large things out of traces

Your login form just dispatched `[:auth/sign-in {:password "hunter2"}]`. In re-frame2 an event is *just data* — a vector describing something that happened — so that password is now plain data sitting in your system. And re-frame2 is unusually observable: events, snapshots of `app-db`, and HTTP records all flow down a single internal stream — call it **the wire** — and every tool drinks from that one wire ([one wire, every tool](../concepts/observability.md)). Xray (the dev inspector) reads it live; the **epoch history** (a saved before/after record of every event, for time-travel debugging) keeps it; and any **shipper** — a function you register to forward records to a hosted monitor like Datadog or Sentry — can carry it off-box.

That is a feature most of the time and a problem exactly once: when the data on the wire is a password, a token, or a 5MB upload. This page is the short list of declarations that keep those off the wire — **without ever hiding them from your own handler code**.

Whenever a value leaves the wire for somewhere it can be *observed* — a dev panel, the saved epoch history, an off-box monitor — it crosses what we'll call an **observation boundary** (or just *egress*, the point where data exits the runtime to be looked at). The whole idea of this page fits in one sentence: **you name a *path* as sensitive once, where the data is defined, and the framework redacts whatever lives at that path everywhere it crosses an observation boundary.** That's it. We'll build it up one declaration at a time.

> **This is hygiene, not a security boundary — and it is fail-open.** The framework keeps secrets off its *own* observability wire; your app still owns auth, encryption, and transport. And the contract is **fail-open**: a path you never classify ships raw. That's the bargain — convenient leak-prevention, not a guarantee. The framework does no *taint-tracking* (it never follows a secret value around to redact every copy of it), so a secret you copy to a new path — a re-keyed value, a rendered field — ships raw until you classify *that* path too.

> **Coming from Sentry?** The instinct is `beforeSend`: a scrub function you write into each consumer, run just before shipping. re-frame2 does the opposite. You classify data **once, at the owner of its shape**, and the framework applies it at every boundary it owns. There's no `beforeSend` to write — which means there's no Nth consumer left to forget one.

## Classify a durable secret in app-db

Start with the most common case: a token that *lives* in `app-db` at a path you own. You classify it by returning a **classification effect** from a handler — right alongside `:db`, in the same event:

```clojure
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token]]}))
```

What just happened: `:sensitive` takes a vector of paths and marks each one. From now on, whatever value comes to rest at `[:auth :token]` is redacted to `:rf/redacted` everywhere it would cross an observation boundary — the App-DB panel in Xray, the saved epoch history, your off-box shipper. Your handlers still read the real token. What gets redacted is only the *observable shadow* of your data: the copy the framework projects onto the wire for tools to look at, which is separate from the live value your code actually runs on.

Notice we classified the path **before any token exists there** — `:auth/init` just seeds an empty map. That's the safe, common pattern: a classification over an absent path is a harmless no-op that quietly redacts whatever later occupies the path. You don't re-classify on every write.

Wire `:auth/init` to run at frame creation with `:initial-events`, so the classification is in place before any value can reach an observation boundary:

```clojure
(rf/reg-frame :app/main
  {:initial-events [[:auth/init]]})
```

> **From re-frame v1.** v1 had no classification model — every event vector and every bit of `app-db` flowed into `re-frame-10x` raw, and keeping a token out of a trace meant not putting it in `app-db` at all. re-frame2 lets the secret live where it naturally belongs and redacts it *at the boundary*. If you tried the EP-0015 preview: the frame `:sensitive {:app-db …}` annotation and the top-level `:large` frame key are **gone** (both now fail loud with `:rf.error/bad-frame-classification`). The event is app-db's definition site; that is the one route.

> **For JavaScript developers.** `:initial-events` is the only frame-init surface — there is no `:initial-db` and no `:on-create`. Seeding `app-db` is itself an event (`[:rf/set-db {…}]`) dispatched as the first init step. See [the frames concept page](../concepts/frames.md).

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

> **Gotcha — `:sensitive` is a *path collection*, never `:sensitive false`.** You *clear* the sensitive axis with `:clear-sensitive`; you never set `:sensitive false`. (Don't confuse it with `:sensitive?`, the yes/no schema prop — that's a different axis, covered later.)

> **Going deeper.** The classification effects are applied **with the `:db` write** — a frame-state transform at the commit point, not a later `:fx`. They ride the runtime-db partition — the framework's `:rf.db/runtime` state beside your app-db — in its `:rf.runtime/elision` registry, not app-db, which buys two properties for free. First, a path classified in an event is redacted from its *very first* egress (no race against the value landing). Second, because the registry lives in revertible runtime-db, a classification **walks back atomically with the frame** on a `restore-epoch` revert — a reverted secret is still a classified secret. Malformed effects (a non-vector value, an unknown axis) abort the transition pre-commit with `:rf.error/classification-effect-shape`, so a typo can't silently disable your protection.

## Classify a transient payload on the registration

Not every secret lives in `app-db`. Some *flow through* the [event cascade](../concepts/events-and-the-cascade.md) — the fixed ordered run one dispatch sets off — passing through event args, fx/cofx values, or a subscription's output, and never coming to rest at an app-db path. These are owned by the **registration** that introduces their shape. Declare the sensitive paths right there, in the registration map, relative to the payload:

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

> **Gotcha — positional args are not path-addressable; a secret in one egresses RAW.** A path like `[:password]` reaches into the event's **arg-map** (`[:auth/sign-in {:password "…"}]`), so the mark can name it. A positionally-passed secret (`[:auth/sign-in "alice" "hunter2"]`) has **no** stable named path — a positional index is not addressable, so there is nothing for `:sensitive` to classify. Under fail-open that means it ships RAW into every trace and error sink. This is a structural limitation, not a bug: redaction is path-based, and only the arg-map (`(second event)`) is reachable. **So when an event carries a secret, pass a map payload and classify the key.** The positional form is fine for data you wouldn't mind seeing in a trace.

Note the `:decode` schema in the sign-in example: `[:token {:sensitive? true} :string]` classifies the *response body*. That's a second, separate declaration for the same secret — the **HTTP reply** is redacted by its `:decode` schema, and the **durable copy** that `:auth/signed-in` later stores at `[:auth :token]` is redacted by the path classification from the first section. There is no propagation that carries one to the other. **Classify each surface the secret crosses** — the wire it arrives on, *and* the slot it comes to rest in.

> **A malformed mark is the loud case.** A non-vector path is rejected at *registration* with `:rf.error/bad-classification` (a flow's bad output marks raise `:rf.error/flow-bad-marks`), so the typo surfaces when you reload — not in a production log six weeks later.

### HTTP carriers: redact by header and query-param name

There's one transient HTTP surface people forget: the **request** side. An `Authorization: Bearer …` header or a `?shop_token=…` query param is a secret travelling in the request, and managed HTTP records the request shape. These aren't classified by path into the body — they're classified by **name**, on the `:rf.http/managed` registration's `:carriers` block:

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

Carriers are **process-global** (one registration, not per-frame), and a malformed `:carriers` block fails loud with `:rf.error/bad-classification`. Full reference: [Spec 014 §HTTP carriers](../../../spec/014-HTTPRequests.md#http-carriers-ep-0025).

## Classify subsystem data on the subsystem

Some data lives *inside* a runtime subsystem — a machine's `:data`, a resource's fetched data or params, a route's query string. You don't own its absolute storage path (the subsystem mints that fresh for each instance), so you declare `:sensitive` / `:large` **relative to the instance's shape**, right on the subsystem definition. Each time an instance is created (a machine spawns, a resource fetches), the framework translates your shape-relative declaration into a concrete path in the classification registry for that instance, and removes it again when the instance is torn down:

```clojure
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :payment :token]]
   :large     [[:data :payment :receipt-pdf]]
   :schemas   {:data [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]}
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {:spawn {:src :checkout/charge :on-done :done}}
             :done       {}}})
```

That `[:data :payment :token]` slot now redacts in **every** machine trace — the before/after of a transition, snapshots, guard inputs — for every spawned actor instance, with zero per-instance author code. Rename the slot in the declaration and the classification moves with it. One declaration on the *type*, applied to every instance: that's the payoff for declaring at the definition site rather than at each egress.

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
  {:path "/reset" :sensitive [[:query :reset-token]]})

(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

A malformed subsystem declaration fails loud at registration under its own per-subsystem error id — `reg-machine` raises `:rf.error/invalid-machine-classification`, a bad resource spec folds into `:rf.error/invalid-resource-spec`.

> **Gotcha — `:sensitive` (effect/declaration) vs `:sensitive?` (schema prop).** These trip people up, so pin the difference once. `:sensitive` (no `?`) names a *collection of paths* — a classification effect, a registration mark, or a subsystem declaration. `:sensitive?` (with `?`) is a *yes/no Malli prop on one schema slot*. A `:sensitive?` prop on a `[:schemas :data]` slot redacts that slot only in the schema's own **validation-failure trace** — it does **not** classify the durable `:data` for snapshot egress (that's the projection-relative declaration above). The same `:sensitive?` prop on an HTTP `:decode` schema *is* the right and only route for the *transient* response body. The rule underneath: **schemas own transient and validation-failure products; durable state is the effect or the subsystem declaration.** The `:large` / `:large?` pair carries the identical distinction.

### The whole ownership map, on one page

Three durable owners, no overlap. When you have a secret, this is the table to walk:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable `app-db` state | the **event** | `:sensitive` / `:large` classification effects |
| Subsystem instance data (machine `:data`, resource data/params, route query) | the **subsystem definition** | projection-relative `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route` |
| Transient payloads (event args, fx/cofx, sub outputs, HTTP bodies, header/query carriers) | the **registration** / its `:decode` schema | `:sensitive` / `:large` path vectors, `:sensitive?` props on the `:decode` schema, or the `:carriers` block |

> **Going deeper — classification does not propagate.** A sub or flow that *reads* a sensitive input does **not** auto-classify its output. If you derive a secret to a new path — through a sub, a flow, a rendered field — classify *that* path. A sensitive flow output is just a classified db path. There is no `:rf.egress/output-sensitivity` declassification claim (it's gone, silently ignored if present) and no value-match "same value redacted everywhere" engine. Path in, path out, every time. The design picks the simplest thing that works: record the path, redact at egress. No taint, no propagation, no magic.

> **The size backstop is the one thing that fires without a declaration.** The large axis has a safety net the sensitive axis doesn't: an *oversized* value auto-elides at egress even at a path you never classified `:large`, governed by `:rf.size/threshold-bytes` (default 16384, overridable via `(rf/configure! {:elision {…}})`). That keeps a surprise 5MB blob out of a trace. It is **not** a secrecy backstop — a small secret sails straight through. Sensitive is strictly opt-in by path.

## Wire an off-box shipper

Now send the production records off-box — to Datadog, Sentry, wherever you watch prod. You set this up per frame, under an `:observability` key. There are **two** streams you can route: `:handled-events` (one production-safe record per event processed) and `:errors` (production-survivable error records). For each, you name a **sink** (an id for the destination), the **profile** (which boundary it's crossing — more on profiles just below), then register the concrete function that does the sending:

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

The profile matrix is in [Spec 015](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional); the wider posture-by-surface matrices are in [Spec 009](../../../spec/009-Instrumentation.md#production-debugging-what-remains).

> **Going deeper.** Beneath the profiles sit advanced `:rf.size/*` override flags — `:rf.size/include-sensitive?`, `:rf.size/include-large?`, `:rf.size/include-digests?`, `:rf.size/threshold-bytes`. A profile resolves to a *floor* and an explicit flag overlays it. You rarely reach for them; the profile is the public choice.

### Two things to verify before the first record ships

**Direct reads are not auto-projected.** Everything so far has relied on the framework *projecting* your data — applying the classifications to produce a safe copy — automatically as it crosses a boundary. But a direct grab of live state that sidesteps the normal observation path — `rf/app-db-value`, `rf/sub-cache`, an MCP `get-path` — hands you the raw value, with no projection applied. If you ship that result off-box yourself, run it through `rf/project-egress` first, naming the frame so it knows whose classifications to apply:

```clojure
(rf/project-egress (rf/app-db-value :app/main [:auth])
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

> **Gotcha — fail-open and fail-closed live on different surfaces.** *Fail-open* is the **classification** posture: an undeclared path ships raw. *Fail-closed* is the **projector's** posture on an unknown frame/profile: a frame with no `:observability` policy routes nothing; an unresolvable frame routes nothing (never a default frame); an unknown profile is rejected with `:rf.error/unknown-egress-profile` (the enum is closed); a throwing sink is isolated from its siblings. Different surfaces, different defaults — worth keeping straight in your head.

> **Going deeper — exceptions are the one gap.** Projection walks *known data shapes*. It can't un-concatenate a secret out of an `ex-message` string, and it can't guess which `ex-data` keys are sensitive. So in handlers that read secrets, throw the *category*, never the value:
>
> ```clojure
> ;; ANTI-PATTERN — the email lands in the error record verbatim.
> (throw (ex-info (str "User " email " failed login") {:user/email email}))
>
> ;; PREFERRED — name the category; omit the value.
> (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
> ```
>
> The exception's job is to say *what went wrong*, not to carry the secret that caused it. (There's no `rf/safe-throw` — which `ex-data` keys are sensitive is author knowledge. The framework's *own* adapter/render diagnostics carry only a shape summary, never the raw value, so the residual here is exclusively your app's throw-sites.)

## Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. The event row carries a magenta redacted marker on its arg map; the `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too.

On-box panels render under the `:rf.egress/local-redacted` profile, so your dev view exercises the *very same* classifications your shipper relies on. You debug against the redacted shape every day — which means you find a missing declaration in development, not in a production log review.

Three sentinel shapes to recognise:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `:rf/large {:bytes N :head "…"}` — large; drillable on-box, elided off-box. (The underlying wire marker is `:rf.size/large-elided`, carrying `:path` / `:bytes` / `:type` / `:reason` / a `:handle` for re-fetch; the rich form is the display rendering of it.)
- `:rf/redacted {:bytes N}` — both; sensitive wins, so only the size *may* show (and the reference suppresses even that — don't depend on `:bytes` riding alongside `:rf/redacted`).

A large marker *may* offer a guarded click-to-expand (a size-confirmed re-fetch via its `:handle`); a redacted marker never may. If you genuinely need to see a sensitive value on-box, that's the trusted-local `:rf.egress/local-raw` opt-in — and revealing it is **itself an audited, trace-visible operator act**, not a quiet global toggle. There is no process-wide `show-sensitive?` switch; visibility is per (tool, frame) pair.

A value you expected scrubbed but that renders raw means the *path* declaration is missing or mis-pathed — or the secret was re-keyed to a path you didn't classify (the fail-open case). Classify the path it actually lives at, and the fix lands on every surface at once: Xray, the epoch ledger, and your off-box shipper all read the one registry you just corrected.
