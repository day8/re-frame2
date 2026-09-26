# Keep secrets and large things out of traces

When your login form dispatches `[:auth/sign-in {:password "hunter2"}]`, the password is data in the [event](../glossary.md#event), and events, [app-db](../glossary.md#app-db) snapshots, and HTTP records all go onto the [trace stream](../glossary.md#trace-stream) ([Observability](../observability.md)). [Xray](../glossary.md#xray) shows it, the [epoch](../glossary.md#epoch) history keeps it, and a sink forwarding records to Datadog or Sentry can send it off the machine. This recipe keeps passwords, tokens, and large blobs out of all of those, while your handlers still see the real values.

You declare a *path* as sensitive (or large) once, where the data's shape is defined, and the framework redacts whatever is at that path wherever it leaves the runtime to be observed: a dev panel, the epoch history, an off-box monitor. This page calls that point *egress*. The mechanism is [data classification](../glossary.md#data-classification); the sections below apply it to each kind of data owner.

!!! note "This is hygiene, not a security boundary, and it fails open"

    The framework keeps secrets off its own observability output; your app still owns auth, encryption, and transport. A path you never classify is sent as is. The framework doesn't track a secret value as it moves, so a copy at a new path (a re-keyed value, a rendered field) is sent as is until you classify that path too.

??? info "Coming from Sentry?"

    Instead of a `beforeSend` scrub function in each consumer, you classify data once where its shape is defined, and the framework applies it at every boundary it owns, so no consumer can forget.

## Classify a durable secret in app-db

For a token that lives in app-db at a path you own, return a classification effect from an [event handler](../glossary.md#event-handler), alongside `:db` in the same [effect map](../glossary.md#effect-map):

```clojure
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token]]}))
```

`:sensitive` takes a vector of paths. From then on, whatever value is at `[:auth :token]` shows as `:rf/redacted` in Xray's App-DB panel, the epoch history, and your off-box sink, while your handlers still read the real token. Only the copy projected for observers is redacted, never the live value.

The path is classified before any token exists there, since `:auth/init` only seeds an empty map. A classification over an absent path does nothing until a value arrives, so you don't re-classify on every write.

Run `:auth/init` from the frame's `:initial-events`, so the classification is in place before any value can be observed:

```clojure
(rf/make-frame
  {:id :app/main
   :initial-events [[:auth/init]]})
```

The token stays in app-db and is redacted at egress. App-db paths are classified only by events: a frame-level `:sensitive` or `:large` key throws `:rf.error/bad-frame-classification`.

??? info "For JavaScript developers"

    A [frame](../glossary.md#frame) always starts with `app-db = {}`, and `:initial-events` is how you set it up; there is no `:initial-db` or `:on-create`. To seed raw state, make `[:rf/set-db {…}]` the first step ([Frames](../frames.md)).

### Two axes: sensitive and large

You can say two independent things about a path. `:sensitive` redacts the value; `:large` replaces an oversized value with a size marker, so a 5MB upload doesn't flood a trace. Each has an inverse:

```clojure
:sensitive       [[path] …]   ; redact at egress
:large           [[path] …]   ; size marker at egress
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

`:clear-sensitive` undoes `:sensitive` and leaves the large axis alone, and vice versa. If a path is declared both, sensitive wins and no size marker is shown, since even the size says something about a secret.

When a secret's path is only known at runtime, classify it in the handler that writes it:

```clojure
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))
```

You rarely need `:clear-*`: when a value goes away, nothing is left to redact. Clear a path only when it is reused for non-secret data:

```clojure
;; a path that held PII is overwritten with sanitised content — un-classify it
(rf/reg-event :doc/sanitised
  (fn [{:keys [db]} [_ doc-id clean]]
    {:db              (assoc-in db [:docs doc-id :body] clean)
     :clear-sensitive [[:docs doc-id :body]]}))
```

`:sensitive` is a collection of paths, never a flag: clear it with `:clear-sensitive`, not `:sensitive false`. Don't confuse it with `:sensitive?`, the yes/no schema property covered below.

??? note "How classification is stored"

    Classification effects are applied together with the `:db` write, at the [commit](../glossary.md#commit), rather than as a later `:fx`. They are stored in the framework's [runtime-db](../glossary.md#runtime-db) partition rather than in app-db. So a path classified in an event is redacted from its very first egress, and a `restore-epoch!` revert rolls the classification back with the rest of the frame's state. A malformed effect (a non-vector value, an unknown axis) aborts the transition before commit with `:rf.error/classification-effect-shape`.

## Classify a transient payload on the registration

Some secrets never rest in app-db: they pass through event args, effect or coeffect values, or a [subscription](../glossary.md#subscription)'s output during a [pipeline run](../glossary.md#run). The [registration](../glossary.md#registration) that defines the payload's shape owns them, so declare the sensitive paths in its metadata, relative to the payload:

```clojure
(rf/reg-event :auth/sign-in
  {:sensitive [[:password]]}        ;; path into the event arg-map
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request    {:method :post
                         :url    "/api/login"
                         :body   {:email email :password password}}
            :sensitive? true    ;; redact the request body in HTTP traces
            ;; the :decode schema classifies the response body:
            :decode     [:map
                         [:user-id :string]
                         [:token {:sensitive? true} :string]]
            :on-success [:auth/signed-in]
            :on-failure [:auth/sign-in-failed]}]]}))
```

The handler still sees the real `password`; the event trace shows it as `:rf/redacted`. The request body is a separate record: the per-request `:sensitive? true` redacts it in the HTTP traces. Paths are relative to the registration's payload: for an event, the arg-map (the map after the event id); for the others, the value they produce. An empty path `[[]]` marks the whole payload, and a path that doesn't exist in a given payload is ignored.

The same `:sensitive` key works on the other registrations that define a payload:

```clojure
;; the whole sub output is sensitive
(rf/reg-sub :partner/api-token {:sensitive [[]]}
  (fn [db _] (get-in db [:tenant :partner-api-key])))

;; a coeffect classifies the value it supplies
(rf/reg-cofx :session/current {:sensitive [[:token]]}
  (fn [] {:user "alice" :token (read-token)}))

;; an effect classifies paths into its argument map
(rf/reg-fx :app.ws/send {:sensitive [[:auth]]}
  (fn [_ctx {:keys [auth message]}]
    (ws-send! auth message)))
```

!!! warning "Gotcha: a positional secret is sent as is"

    `[:password]` names a key in the event's arg-map (`[:auth/sign-in {:password "…"}]`). A secret passed positionally (`[:auth/sign-in "alice" "hunter2"]`) has no path, so `:sensitive` can't reach it and it appears unredacted in every trace and error record. When an event carries a secret, pass a map and classify the key, as the glossary [recommends for events generally](../glossary.md#event).

!!! warning "Gotcha: never put a secret in a subscription's query vector"

    `{:sensitive [[]]}` on `:partner/api-token` classifies what the sub *returns*, not the vector you call it with. A query vector is the sub's identity and cache key, visible to every layer that touches the cache, so it is never redacted, including in production error records. Pass identifiers, not secrets, as you would in a URL:

    ```clojure
    ;; An id in the vector; the secret stays in classified app-db.
    (rf/reg-sub :patient/record {:sensitive [[:ssn]]}
      (fn [db [_ patient-id]] (get-in db [:patients patient-id])))

    (rf/subscribe [:patient/record patient-id])   ;; an id: fine
    ;; (rf/subscribe [:patient/record ssn])       ;; a secret: appears in every trace
    ```

The `:decode` schema in the sign-in example, `[:token {:sensitive? true} :string]`, classifies the *response body*. It is separate from the path classification in the first section, which covers the copy `:auth/signed-in` later stores at `[:auth :token]`; nothing carries one to the other. Classify each place the secret passes through: the reply it arrives in and the path where it is stored.

A path that doesn't exist is ignored, but a malformed one is not: a non-vector path is rejected at registration with `:rf.error/bad-classification` (a [flow](../glossary.md#flow)'s malformed output marks raise `:rf.error/flow-bad-marks`).

### HTTP carriers: redact by header and query-param name

Secrets also travel in requests: an `Authorization: Bearer …` header or a `?shop_token=…` query param, and [managed HTTP](../../resources/glossary.md#managed-http) records the request. These are classified by name, in a `:carriers` block on the `:rf.http/managed` registration. Re-register the effect with the stock handler and your block:

```clojure
;; (:require [re-frame.http.managed :as http-managed])
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team" "X-Stripe-Signature"]
              :query-params ["shop_token"]}}
  http-managed/managed-handler)
```

The built-in denylist (`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, and similar) can't be reduced; your names are added to it. Query params alone also accept an `{:include … :except …}` map, so you can stop redacting a harmless built-in name in your own traces (the result is the defaults minus `:except`, plus `:include`):

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:query-params {:include ["shop_token"]   ;; add to the defaults
                             :except  ["token"]}}}     ;; remove a non-secret default
  http-managed/managed-handler)
```

Carriers apply to the whole process (there is one registration, not one per frame). A malformed `:carriers` block throws `:rf.error/bad-classification`.

## Classify subsystem data on the subsystem

Some data lives inside a runtime subsystem: a [machine](../../machines/glossary.md#machine)'s `:data`, a [resource](../../resources/glossary.md#resource)'s fetched data or params, a [route](../../routing/glossary.md#route)'s query string. The subsystem decides where each instance is stored, so you declare `:sensitive` / `:large` relative to the instance, on the subsystem definition. When an instance is created (a machine spawns, a resource fetches), the framework registers the concrete path for it, and removes it when the instance goes away:

```clojure
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :payment :token]]
   :large     [[:data :payment :receipt-pdf]]
   :schemas   {:data [:map [:payment [:map [:token :string] [:receipt-pdf :any]]]]}
   :initial   :collecting
   :states    {:collecting {:on {:submit :charging}}
               :charging   {:on {:charged :done}}
               :done       {}}})
```

`[:data :payment :token]` is now redacted in every machine trace, including [transition](../../machines/glossary.md#transition) before/after, [snapshots](../../machines/glossary.md#snapshot), and [guard](../../machines/glossary.md#guard) inputs, for every instance of the machine, with no per-instance code.

Four subsystems work this way. In each, your path is relative to a fixed point inside one instance, and the framework adds the declaration when the instance appears and drops it when the instance goes away:

| Subsystem | Your path is relative to | Added at | Dropped at |
|---|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | actor spawn / first-boot | actor destroy (any cause) |
| `reg-resource` | the entry's `:params` / `:data` | params at scoped-key mint; data when the fetch lands | entry eviction |
| `reg-mutation` | one work row's `:params` | work creation | work completion |
| `reg-route` | the current route's `:query` / `:params` | route activation | route change / deactivation |

A route with a token in its query string (`?reset_token=…`) classifies it on the route definition, and a resource declares its own known fields:

```clojure
(rf/reg-route :password-reset
  {:sensitive [[:query :reset-token]]}
  "/reset")

(rf/reg-resource :user-profile
  {:sensitive     [[:data :ssn]]
   :large         [[:data :avatar-bytes]]
   :scope         {:from-db :app/session}      ;; a resolver registered with reg-resource-scope
   :params-schema [:map [:user-id :string]]}
  (fn [{:keys [user-id]} _ctx]
    {:request {:method :get :url (str "/api/users/" user-id)}}))
```

A malformed subsystem declaration throws at registration: `reg-machine` raises `:rf.error/invalid-machine-classification`, and a bad resource declaration raises `:rf.error/resource-bad-spec`.

!!! warning "Gotcha: `:sensitive` and `:sensitive?` are different"

    `:sensitive` (no `?`) is a collection of paths: a classification effect, a registration's metadata, or a subsystem declaration. `:sensitive?` (with `?`) is a yes/no property on one schema slot. On a machine's `[:schemas :data]` slot, `:sensitive?` redacts the value only in that schema's validation-failure trace; it doesn't classify the machine's `:data` for snapshots, which needs the `:sensitive` declaration above. On an HTTP `:decode` schema, `:sensitive?` is how you classify the response body. `:large` and `:large?` follow the same rule.

### Where to declare, by owner

| The data is… | Owner | Declare with |
|---|---|---|
| Durable app-db state | the event that writes it | `:sensitive` / `:large` classification effects |
| Subsystem instance data (machine `:data`, resource data/params, route query) | the subsystem definition | `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route` |
| Transient payloads (event args, fx/cofx values, sub outputs, HTTP bodies, headers and query params) | the registration, or its `:decode` schema | `:sensitive` / `:large` paths, `:sensitive?` on the `:decode` schema, or the `:carriers` block |

A sub or flow that reads a sensitive value does not classify its own output. If you derive a secret into a new path, through a sub, a flow, or a rendered field, classify that path too.

!!! warning "The size threshold warns; it doesn't elide"

    An oversized value at a path you never declared `:large` is not elided. When the walker meets one, it emits the `:rf.warning/large-value-unschema'd` warning, controlled by `:rf.egress/threshold-bytes` (default 16384, set with `(rf/configure! {:elision {:rf.egress/threshold-bytes N}})`), and sends the value whole. To keep a large value out of a trace, declare its path `:large`; to keep a secret out, declare it `:sensitive`.

## Wire an off-box shipper

To send production records to Datadog, Sentry, or another monitor, declare them under the frame's `:observability` key. There are two streams: `:handled-events`, one record per processed event, and `:errors`, the [error records](../glossary.md#error-record). Each entry names a sink id and an egress profile (below), and you register the function behind each sink. Vendor settings such as a service name belong in that function, which closes over them:

```clojure
(rf/make-frame
  {:id :app/main
   :observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :errors
                   [{:sink :my-app.sinks/sentry
                     :rf.egress/profile :rf.egress/off-box-observability}]}
   :initial-events [[:auth/init]]})         ;; classifies [:auth :token]

;; datadog/send and sentry/capture stand for your vendor SDK calls.
(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [record]
    ;; Already projected: no redaction needed here.
    (datadog/send record {:service "checkout-spa" :env "prod"})))

(rf/register-observability-sink! :my-app.sinks/sentry
  (fn [record] (sentry/capture record)))
```

The sink never scrubs anything: by the time a record reaches it, every classified slot has already been replaced. If you find yourself writing a scrub inside a sink, a declaration is missing where the data is defined; fix it there.

Both streams are projected under the same frame classification, so `[:auth :token]` is redacted in an error record just as in a handled-event record. Check the `:errors` stream in particular: an error record can carry the failing `:event` and app-db values, so it is where a secret shows up if you classified only the happy path. [Report errors in production](report-errors-in-production.md) covers the sink side in full.

`(rf/unregister-observability-sink! :my-app.sinks/datadog)` removes a sink; registering the same id again replaces it.

### Choosing a profile

A profile names who is about to see the data, instead of a combination of on/off flags. There are six, and you can't define new ones. A shipper uses one of the two off-box profiles; the others cover dev panels, trusted local access, SSR, and server error responses:

| Profile | Boundary |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb): redact sensitive, elide large, omit raw `:event` args |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire: redact sensitive, elide large; each marker's `:path` / `:bytes` / `:type` / `:handle` let a tool reason about structure without content (no digests) |
| `:rf.egress/local-redacted` | on-box dev-UI default: suppress sensitive, may show size indicators |
| `:rf.egress/local-raw` | trusted local operator opt-in: include sensitive + large (subject to size caps) |
| `:rf.egress/ssr-hydration` | the projection applied *after* the SSR allowlist (defence-in-depth) |
| `:rf.egress/public-error` | client-safe server error responses; never internal raw values |

??? note "Override flags"

    Beneath the profiles are `:rf.egress/*` flags: `:rf.egress/include-sensitive?`, `:rf.egress/include-large?`, `:rf.egress/include-digests?`, and `:rf.egress/threshold-bytes`. A profile sets the defaults and an explicit flag overrides one of them. You rarely need them.

### Two things to verify before the first record ships

**Direct reads are not projected.** Reading live state directly, with `rf/app-db-value`, a sub-cache snapshot, or an MCP `get-path`, returns the raw value. If you send that off-box yourself, pass it through [`rf/project-egress`](../glossary.md#project-egress) first, naming the frame whose classifications apply:

```clojure
(rf/project-egress (get-in (rf/app-db-value :app/main) [:auth])
  {:frame :app/main :path [:auth] :rf.egress/profile :rf.egress/off-box-tool})
```

**Omitting `:frame` is not the same as `:frame nil`.** Without the key, projection uses the frame in the current scope, and a frame that declares nothing redacts nothing, so a secret goes out as is; it redacts everything only when no live frame is in scope. With an explicit `:frame nil`, projection ignores the current scope and redacts the whole value. Use `:frame nil` when projecting one frame's data from code running in another frame, such as a tool showing the inspected app's data. (`re-frame.elision/elide-wire-value` is the lower-level walker `project-egress` uses for plain values; you rarely call it directly.)

**The off-box default omits event args.** A projected handled-event record carries the frame, event id, status, timing, and effect keys, but no `:event` at all. Check it at the REPL:

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

!!! warning "Classification fails open; routing fails closed"

    An undeclared path is sent as is. Routing is the opposite: a frame with no `:observability` policy sends nothing, a frame that can't be resolved sends nothing (no default frame is assumed), an unknown profile throws `:rf.error/unknown-egress-profile`, and a sink that throws doesn't affect other sinks.

!!! note "Exceptions are the gap"

    Projection works on known data shapes. It can't remove a secret from an `ex-message` string, and it can't know which `ex-data` keys are sensitive. In handlers that handle secrets, throw the category, never the value:

    ```clojure
    ;; Don't do this: the email lands in the error record.
    (throw (ex-info (str "User " email " failed login") {:user/email email}))

    ;; Name the category and leave the value out.
    (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
    ```

    The framework's own adapter and render diagnostics carry only a summary of a value's shape, never the value, so this applies only to your app's own `throw` sites.

## Advanced

### SSR and hydration: another egress point

With [SSR](../../ssr/concepts.md), the server sends the browser a hydration payload, a serialised slice of app-db that the browser adopts on first render. That payload goes to an untrusted client, so it is an egress point too, and it works by allowlist: you name the state that may be sent, and unlisted state isn't sent even if you never classified it. A frame that renders on the server with no payload policy throws `:rf.error/ssr-missing-payload-policy` rather than sending all of app-db.

Classification applies on top: a sensitive value inside an allowlisted slice is still redacted unless the SSR host permits it by path. That second pass is the `:rf.egress/ssr-hydration` profile, and it doesn't elide large values, because the payload becomes the browser's live state. The classification registry itself is not sent, since a classified path can contain a sensitive id such as `[:by-id "user-secret" :token]`; the client rebuilds its own registry on mount.

Sometimes the user's own browser must hold a classified value, such as a CSRF token you keep out of Datadog but the page has to send back. For that, the SSR host accepts `:payload-include-sensitive [[:session :csrf]]` beside its allowlist: specific app-db paths whose raw value may be sent. Permit individual values rather than whole maps, and never a long-lived bearer credential. A value the browser itself originated, such as a password being typed, is re-seeded on the client after hydration rather than permitted. Both options belong to the SSR host; see [the SSR concept page](../../ssr/concepts.md#payload--the-fail-closed-allowlist) for their exact shape.

### Classification and time travel: epoch records stay raw

Classification doesn't break [time travel](../glossary.md#time-travel). A stored [epoch](../glossary.md#epoch) keeps the raw value, so `restore-epoch!` puts the real value back. Redaction happens only when an epoch leaves the process: an exported epoch must go through `project-egress` with an off-box profile, like any other record. There is no hook to scrub epochs as they are stored.

## Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. The event row shows a redacted marker on its arg map, and the `:password` slot reads `:rf/redacted`, which can never be expanded. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too.

Xray's panels render under the `:rf.egress/local-redacted` profile, so in development you see the same redactions your shipper relies on, and a missing declaration shows up there rather than in a production log.

- `:rf/redacted` for a sensitive value: no type, no size, no way to reveal it. A path declared both sensitive and large also shows as `:rf/redacted`.
- `{:rf.size/large-elided {:path … :bytes … :type … :reason … :handle …}}` for a large value. Xray's diff view shows this map; a tool may display it more compactly, for example as `:rf/large {:bytes N :head "…"}`. On the machine, a tool may offer to load the full value through its `:handle` after confirming the size.

To see a sensitive value in a local tool, the tool uses the trusted-local `:rf.egress/local-raw` profile, and revealing a value is itself recorded in the trace. There is no process-wide "show sensitive values" switch.

If a value you expected to be redacted shows raw, its path isn't classified: the declaration is missing, names the wrong path, or the secret was copied to a path you didn't classify. Classify the path where it actually lives, and Xray, the epoch history, and your sinks all pick up the change.
