# Keep secrets and large things out of traces

A password can appear in several places: an event payload, a coeffect read from
storage, an app-db path, an HTTP body and a subscription result. Classify each owner
so its trace or exported record redacts the value while the application still uses
the real data.

This recipe applies [data classification](../glossary.md#data-classification) to
those boundaries. Raw local epoch snapshots retain state for restoration; they and
other direct reads need `project-egress` before forwarding. Classification does not
erase secrets from memory or make a raw snapshot safe to export.

## Classify a durable secret in app-db

For a token that lives in app-db at a path you own, declare the path once with `reg-classification`, beside the feature's other [registrations](../glossary.md#registration):

```clojure
(rf/reg-classification :auth
  {:doc       "The session token and its refresh token."
   :sensitive [[:auth :token] [:auth :refresh-token]]})
```

`:sensitive` takes a vector of paths, absolute in app-db. Whatever value is at `[:auth :token]` shows as `:rf/redacted` in Xray's App-DB panel, in your off-box sink, and in any epoch you export through `project-egress`, while your handlers still read the real token. Only the copy projected for observers is redacted, never the live value.

The declaration exists before any event runs, so the path is redacted from its first egress, including the trace of the event that first writes a token there. A declaration over an absent path does nothing until a value arrives, so nothing has to run at boot and nothing is re-classified on each write.

The token stays in app-db and is redacted at egress. App-db paths are classified only by `reg-classification`: an [effect map](../glossary.md#effect-map) returning `:sensitive` beside `:db` is refused with `:rf.error/effect-map-shape`, and a frame config carrying `:sensitive` or `:large` is refused at `make-frame`.

### Two axes: sensitive and large

You can say two independent things about a path. `:sensitive` redacts the value; `:large` replaces an oversized value with a size marker, so a 5MB upload doesn't flood a trace:

```clojure
(rf/reg-classification :docs
  {:doc       "Scanned documents: the text can hold PII, the scan is big."
   :sensitive [[:docs :body]]
   :large     [[:docs :scan]]})
```

A path names slots, not positions, so a key under a map or an index into a vector consumes no segment: `[:docs :body]` reaches `[:docs "inv-42" :body]` for every document, however many there are ([Spec 015 §Projection](../../../spec/015-Data-Classification.md#collection-matching)). If a path is declared both, sensitive wins and no size marker is shown, since even the size says something about a secret.

`:sensitive` is a collection of paths, never a flag, so there is no `:sensitive false`. Don't confuse it with `:sensitive?`, the yes/no schema property covered below.

A `reg-classification` is a registration like any other. Registering the same id again replaces its declaration, and `(rf/clear :classification :docs)` removes it. A frame made with `:images` applies the declarations its images select, so put the `reg-classification` in the namespace of the feature that owns the path.

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

;; a coeffect classifies the value it supplies; a credential is never recorded
(rf/reg-cofx :session/current {:recordable? false :sensitive [[:token]]}
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

Carriers apply to the whole process (there is one registration, not one per frame).

## Classify subsystem data on the subsystem

Some data lives inside a runtime subsystem: a [machine](../../machines/glossary.md#machine)'s `:data`, a [resource](../../resources/glossary.md#resource)'s fetched data, a [route](../../routing/glossary.md#route)'s query string. The subsystem decides where each instance is stored, so you declare `:sensitive` / `:large` relative to the instance, on the subsystem definition. Egress applies the declaration to every instance present at that moment, so an instance is classified exactly while it exists, with nothing recorded when it is created or removed when it goes away:

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

Four subsystems work this way. In each, your path is relative to a fixed point inside one instance ([Spec 015 §Subsystem projection-relative classification](../../../spec/015-Data-Classification.md#subsystem-projection-relative-classification)):

| Subsystem | Your path is relative to | Classified while |
|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | the actor exists |
| `reg-resource` | the entry's `:data` | the entry is cached |
| `reg-mutation` | one instance's `:params` / `:scope`, and its `:result` | the instance row exists |
| `reg-route` | the current route's `:query` / `:params` | the route is current |

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

!!! warning "Gotcha: `:sensitive` and `:sensitive?` are different"

    `:sensitive` (no `?`) is a collection of paths: a `reg-classification` declaration, a registration's metadata, or a subsystem declaration. `:sensitive?` (with `?`) is a yes/no property on one schema slot. On a machine's `[:schemas :data]` slot, `:sensitive?` redacts the value only in that schema's validation-failure trace; it doesn't classify the machine's `:data` for snapshots, which needs the `:sensitive` declaration above. On an HTTP `:decode` schema, `:sensitive?` is how you classify the response body. `:large` and `:large?` follow the same rule.

### Where to declare, by owner

| The data is… | Owner | Declare with |
|---|---|---|
| Durable app-db state | the feature that owns the path | `:sensitive` / `:large` in a `reg-classification` beside the feature |
| Subsystem instance data (machine `:data`, resource data, route query) | the subsystem definition | `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route` |
| Transient payloads (event args, fx/cofx values, sub outputs, HTTP bodies, headers and query params) | the registration, or its `:decode` schema | `:sensitive` / `:large` paths, `:sensitive?` on the `:decode` schema, or the `:carriers` block |

A sub or flow that reads a sensitive value does not classify its own output. If you derive a secret into a new path, through a sub or a flow, classify that path too. Classifying a path never changes what a view renders, so the app must omit or mask content it does not want rendered.

!!! warning "The size threshold warns; it doesn't elide"

    An oversized value at a path you never declared `:large` is not elided. When the walker meets one, it emits the `:rf.warning/large-value-unschema'd` warning, controlled by `:rf.egress/threshold-bytes` (default 16384, set with `(rf/configure! {:elision {:rf.egress/threshold-bytes N}})`), and sends the value whole. To keep a large value out of a trace, declare its path `:large`; to keep a secret out, declare it `:sensitive`.

## Wire an off-box shipper

To send production records to Datadog, Sentry, or another monitor, declare them under the frame's `:observability` key. There are two streams: `:handled-events`, one record per processed event, and `:errors`, the [error records](../glossary.md#error-record). Each entry names a sink id and an egress profile (below), and you register the function behind each sink. Vendor settings such as a service name belong in that function, which closes over them:

```clojure
(rf/make-frame
  {:id :rf/default
   :observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]
                   :errors
                   [{:sink :my-app.sinks/sentry
                     :rf.egress/profile :rf.egress/off-box-observability}]}})

;; datadog/send and sentry/capture stand for your vendor SDK calls.
(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [record]
    ;; Already projected: no redaction needed here.
    (datadog/send record {:service "todo-app" :env "prod"})))

(rf/register-observability-sink! :my-app.sinks/sentry
  (fn [record] (sentry/capture record)))
```

The sink never scrubs anything: by the time a record reaches it, every classified slot has already been replaced. If you find yourself writing a scrub inside a sink, a declaration is missing where the data is defined; fix it there.

Both streams are projected under the same frame classification, so `[:auth :token]` is redacted in an error record just as in a handled-event record. Check the `:errors` stream in particular: an error record can carry the failing `:event` and app-db values, so it is where a secret shows up if you classified only the happy path. [Report errors in production](report-errors-in-production.md) covers the sink side in full.

`(rf/unregister-observability-sink! :my-app.sinks/datadog)` removes a sink; registering the same id again replaces it.

### Choosing a profile

A profile names who is about to see the data. A shipper uses one of these two:

| Profile | Boundary |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb): redact sensitive, elide large, omit raw `:event` args |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire: redact sensitive, elide large; each marker's `:path` / `:bytes` / `:type` / `:handle` let a tool reason about structure without content (no digests) |

### Check these before the first record ships

**Direct reads are not projected.** Reading live state directly, with `rf/app-db-value`, a sub-cache snapshot, or an MCP `get-path`, returns the raw value. If you send that off-box yourself, pass it through [`rf/project-egress`](../glossary.md#project-egress) first, naming the frame whose classifications apply:

```clojure
(rf/project-egress (get-in (rf/app-db-value :rf/default) [:auth])
  {:frame :rf/default :path [:auth] :rf.egress/profile :rf.egress/off-box-tool})
```

**Omitting `:frame` is not the same as `:frame nil`.** Without the key, projection uses the frame in the current scope, and a frame that declares nothing redacts nothing, so a secret goes out as is; it redacts everything only when no live frame is in scope. With an explicit `:frame nil`, projection ignores the current scope and redacts the whole value. Use `:frame nil` when projecting one frame's data from code running in another frame, such as a tool showing the inspected app's data. (`re-frame.elision/elide-wire-value` is the lower-level walker `project-egress` uses for plain values; you rarely call it directly.)

**The off-box default omits event args.** A projected handled-event record carries the frame, event id, timestamp, outcome, elapsed time and effect keys, but no `:event` at all. Check it at the REPL:

```clojure
(rf/project-egress
  {:kind     :rf.observe/handled-event
   :frame    :rf/default
   :event-id :auth/sign-in
   :event    [:auth/sign-in {:password "hunter2"}]}
  {:rf.egress/profile :rf.egress/off-box-observability})
;; => {:kind :rf.observe/handled-event :frame :rf/default
;;     :event-id :auth/sign-in ...}   ;; no :event slot
```

!!! warning "Gotcha: with no sink policy, nothing is sent"

    With no `:observability` policy in reach (neither the frame's nor a `configure!` default), records go nowhere. A record whose frame can't be resolved goes only to the process default, with its data redacted. A sink that throws doesn't affect other sinks.

!!! note "Exceptions are the gap"

    Projection works on known data shapes. It can't remove a secret from an `ex-message` string, and it can't know which `ex-data` keys are sensitive. In handlers that handle secrets, throw the category, never the value:

    ```clojure
    ;; Don't do this: the email lands in the error record.
    (throw (ex-info (str "User " email " failed login") {:user/email email}))

    ;; Name the category and leave the value out.
    (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
    ```

    The framework's own adapter and render diagnostics carry only a summary of a value's shape, never the value, so this applies only to your app's own `throw` sites.

## Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. The event row shows a redacted marker on its arg map, and the `:password` slot reads `:rf/redacted`, which can never be expanded. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too.

Xray's panels render under the [`:rf.egress/local-redacted`](#the-other-egress-profiles) profile, so in development you see the same redactions your shipper relies on, and a missing declaration shows up there rather than in a production log.

- `:rf/redacted` for a sensitive value: no type, no size, no way to reveal it. A path declared both sensitive and large also shows as `:rf/redacted`.
- `{:rf.size/large-elided {:path … :bytes … :type … :reason … :handle …}}` for a large value. Xray's diff view shows this map; a tool may display it more compactly, for example as `:rf/large {:bytes N :head "…"}`. On the machine, a tool may offer to load the full value through its `:handle` after confirming the size.

To see a sensitive value in a local tool, the tool uses the trusted-local [`:rf.egress/local-raw`](#the-other-egress-profiles) profile, and revealing a value is itself recorded in the trace. There is no process-wide "show sensitive values" switch.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A value you expected redacted shows raw | Its path isn't classified: the declaration is missing, names the wrong path, or the secret was copied to a path you didn't classify | Classify the path where the value actually lives; Xray, the epoch history and your sinks all pick it up |
| `:rf.error/bad-frame-classification` at `make-frame` | A frame-level `:sensitive` / `:large` key | Declare the app-db paths with `reg-classification` |
| `:rf.error/effect-map-shape` naming `reg-classification`; nothing commits | A handler returned `:sensitive` or `:large` beside `:db` | Move the paths into a `reg-classification` |
| `:rf.error/bad-classification` at registration | A non-vector path in a registration's or a `reg-classification`'s `:sensitive` / `:large`, a `reg-classification` key other than `:sensitive`, `:large` or `:doc`, or a malformed `:carriers` block (a [flow](../glossary.md#flow)'s malformed marks raise `:rf.error/flow-bad-marks`) | Use vectors of keys |
| `:rf.error/invalid-machine-classification` / `:rf.error/resource-bad-spec` at registration | A malformed `:sensitive` / `:large` on `reg-machine` / `reg-resource` | Use vectors of paths relative to the instance |
| `:rf.error/unknown-egress-profile` | A profile outside the built-in six | Use one from [Choosing a profile](#choosing-a-profile) or [the other egress profiles](#the-other-egress-profiles) |

## Advanced

### The other egress profiles

The four profiles a shipper doesn't use cover dev panels, trusted local access, SSR, and server error responses. With the two off-box profiles they make six, and you can't define new ones:

| Profile | Boundary |
|---|---|
| `:rf.egress/local-redacted` | on-box dev-UI default: suppress sensitive, may show size indicators |
| `:rf.egress/local-raw` | trusted local operator opt-in: include sensitive + large (subject to size caps) |
| `:rf.egress/ssr-hydration` | the projection applied *after* the SSR allowlist (defence-in-depth) |
| `:rf.egress/public-error` | client-safe server error responses; never internal raw values |

??? note "Override flags"

    Beneath the profiles are `:rf.egress/*` flags: `:rf.egress/include-sensitive?`, `:rf.egress/include-large?`, `:rf.egress/include-digests?`, and `:rf.egress/threshold-bytes`. A profile sets the defaults and an explicit flag overrides one of them. You rarely need them.

### SSR and hydration: another egress point

With [SSR](../../ssr/concepts.md), the server sends the browser a hydration payload, a serialised slice of app-db that the browser adopts on first render. That payload goes to an untrusted client, so it is an egress point too, and it works by allowlist: you name the state that may be sent, and unlisted state isn't sent even if you never classified it. A frame that renders on the server with no payload policy throws `:rf.error/ssr-missing-payload-policy` rather than sending all of app-db.

Classification applies on top: a sensitive value inside an allowlisted slice is still redacted unless the SSR host permits it by path. That second pass is the `:rf.egress/ssr-hydration` profile, and it doesn't elide large values, because the payload becomes the browser's live state. The payload carries no classification: the client loads the same `reg-classification` declarations as the server, so it redacts the same paths from its first egress.

Sometimes the user's own browser must hold a classified value, such as a CSRF token you keep out of Datadog but the page has to send back. For that, name its path in the SSR host's allowlist, beside the keys: `:payload [:session [:session :csrf]]` sends the session with that one classified value raw. A path permits only the leaf it names, never a whole map, and never permit a long-lived bearer credential. A value the browser itself originated, such as a password being typed, is re-seeded on the client after hydration rather than permitted. The allowlist belongs to the SSR host; see [the SSR concept page](../../ssr/concepts.md#payload--the-fail-closed-allowlist) for its exact shape.

### Classification and time travel: epoch records stay raw

Classification doesn't break [time travel](../glossary.md#time-travel). A stored [epoch](../glossary.md#epoch) keeps the raw value, so `restore-epoch!` puts the real value back. Redaction happens only when an epoch leaves the process: an exported epoch must go through `project-egress` with an off-box profile, like any other record. There is no hook to scrub epochs as they are stored.
