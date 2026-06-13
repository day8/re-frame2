# Keep secrets and large things out of traces

Your login form just put `{:password "hunter2"}` into an event vector. re-frame2's observability rides one wire — events, `app-db` snapshots, HTTP records — feeding Xray, the epoch ledger, and any production shipper you wire up ([one wire, every tool](../concepts/observability.md)). This page is the handful of declarations that keep the password, the token, and the 5MB upload off that wire.

If you know Sentry, the instinct is `beforeSend` — a scrub function in each consumer, run just before shipping. re-frame2 deliberately inverts that: classify data **once, at its owner**, and the **framework** applies it at every boundary it owns. There is no `beforeSend` to write, and no Nth consumer left to forget one.

The model is three layers. **Classification names facts** — *this path is sensitive, this slot is large* — written as data, colocated with whatever owns the data's shape: the frame, a schema, or a registration. Classification is inert on its own; your handlers always see real values.

**Projection applies those facts at a trust boundary.** That is the framework's job, not yours and not the sink's. When a record is about to cross a boundary, the runtime projects it under the owning frame's classification, substituting sentinels at the classified slots. How strict depends on *which boundary*, named by an egress profile — a closed six-member enum (`:rf.egress/off-box-observability` for hosted monitoring, `:rf.egress/local-redacted` for on-box dev panels, …); the profile matrix is in [Spec 015](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional), the wider posture-by-surface matrices in [Spec 009](../../../spec/009-Instrumentation.md#production-debugging-what-remains).

**Sink policy routes the projected records.** A sink — your Datadog forwarder, a Sentry client — receives records that are *already safe* and never scrubs anything itself. The takeaway, quotable: **you declare; the framework projects; sinks consume already-safe records.** One caveat: this is leak prevention, not a security boundary — your app still owns auth, encryption, and transport. Where the framework can't be sure, it fails closed: redact rather than leak.

## 1. Declare durable app-db secrets on the frame

State that *lives* in `app-db` — tokens, partner keys, big blobs — is owned by the frame:

```clojure
(rf/reg-frame :app/main
  {:sensitive {:app-db [[:auth :token] [:auth :refresh-token]]}
   :large     {:app-db [[:documents :csv-upload]]}
   :on-create [:app/init]})
```

A path declared both sensitive and large redacts as sensitive — even "there's a 5MB blob here" says too much about a secret. Malformed paths fail loudly at registration, not silently at leak time. Two rules: a `reg-app-schema` slot prop does **not** classify app-db (the frame is the one owner of durable app-db privacy), and re-registering a frame replaces its policy wholesale — keep one declaration (step 4 grows this map).

## 2. Declare transient payloads on the registration

Values that flow *through* the cascade — event args, sub outputs, flow outputs — are owned by the registration that introduces their shape:

```clojure
(rf/reg-event-fx :auth/sign-in
  {:sensitive [[:password]]}        ;; paths into the event arg-map
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

The handler body sees `password` verbatim — handlers need real values. Only the *observable shadow* is projected: the dispatched-event trace and the HTTP record ship `:password` as `:rf/redacted` (the off-box production record goes further — step 4). The `:auth/signed-in` handler then stores the response token at `[:auth :token]` — the path step 1 already declared frame-sensitive. (An empty path `[[]]` marks the whole payload — e.g. a token-returning sub's output.)

## 3. Declare schema-owned slots with `:sensitive?` / `:large?`

Where data's natural home already *is* a schema — a machine's `:data-schema`, a resource's `:data-schema` / `:params-schema`, an HTTP request's `:decode` schema (above) — a per-slot boolean prop is the one and only route:

```clojure
(rf/reg-machine :checkout/payment
  {:data-schema
   [:map
    [:payment [:map
               [:token       {:sensitive? true} :string]
               [:receipt-pdf {:large? true}     :bytes]]]]
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {:spawn {:src :checkout/charge :on-done :done}}
             :done       {}}})
```

That `:token` slot redacts in every machine trace — transition before/after, snapshots, guard inputs — and the receipt PDF elides the same way; rename a slot and the classification moves with it. Fail-closed bites here too: a response body with **no** `:decode` schema is whole-sensitive off-box, so a forgotten schema redacts rather than leaks.

`:sensitive` (frame, registration) names a *collection of paths*; `:sensitive?` (schema slot) is a *yes/no about one slot*. Three owners, no overlap:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable frame-wide `app-db` state | the frame | `:sensitive` / `:large` path maps on `reg-frame` |
| Owner-local schema'd data (machine `:data`, resource data/params, HTTP bodies) | the schema | per-slot `:sensitive?` / `:large?` props |
| Transient payloads (event args, sub/flow outputs) | the registration | `:sensitive` / `:large` path vectors |

A sub or flow that *reads* a sensitive input inherits sensitivity by default; declassifying a safe derivation (a hash, a count) is an explicit, auditable claim — [Spec 015 §Derived sensitivity](../../../spec/015-Data-Classification.md#derived-sensitivity).

## 4. Before you wire an off-box shipper

Production observation records route by the frame's `:observability` policy — name a sink id and the boundary's profile, then register the concrete fn:

```clojure
(rf/reg-frame :app/main                 ;; the step-1 map, grown
  {:sensitive     {:app-db [[:auth :token] [:auth :refresh-token]]}
   :large         {:app-db [[:documents :csv-upload]]}
   :observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]}
   :on-create     [:app/init]})

(rf/reg-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

Verify four things before the first record ships:

- **Every secret has its owner's declaration** — walk the three-row table for each one. The declaration is the whole defence.
- **The sink does no redaction.** If you're writing a scrub inside the sink fn, a declaration is missing upstream — fix the owner, not the sink.
- **The off-box default omits event args entirely.** A projected handled-event record carries frame, event id, status, timing, and effect keys — no `:event` slot at all. Confirm at the REPL with the same primitive the runtime uses:

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

- **Know the fail-closed properties.** A frame with no `:observability` policy routes nothing; an unresolvable frame routes nothing (never a default frame); a throwing sink is isolated from its siblings.

> **Watch out: exceptions are the one gap.** Projection walks known data shapes; it can't un-concatenate a secret out of an `ex-message` string or guess which `ex-data` keys are sensitive. In handlers that read secrets, throw the *category*, never the value: `(ex-info "Invalid credentials" {:reason :invalid-credentials})`.

## 5. Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. The event row carries a magenta redacted marker on its arg map; the `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too: on-box panels render under the local-redacted profile, so the dev view exercises the same classifications your shipper relies on. Three sentinel shapes to look for:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `:rf/large {:bytes N :head "…"}` — large; drillable on-box, elided off-box.
- `:rf/redacted {:bytes N}` — both; sensitive wins, only the size may show.

A value you expected scrubbed rendering raw means the owner declaration is missing or mis-pathed — fix it at the owner and the fix lands on every surface at once.

---

You can now:

- classify a secret or a blob at its one owner — frame, schema, or registration
- keep handler code working on real values while every observable surface ships sentinels
- verify what an off-box sink will receive with `rf/project-egress` before wiring it
- spot-check classifications live in Xray, and read the three sentinel shapes

**Next:** route the error stream to a monitor in [Report errors in production](report-errors-in-production.md), or step back to the model in [Observability: one wire, every tool](../concepts/observability.md).
