# Keep secrets and large things out of traces

Your login form just put `{:password "hunter2"}` into an event vector — and an event in re-frame2 is just the data that describes something that happened, so that password is now sitting in plain data. Here's why that matters: re-frame2 sends all its observability over one wire. Events, snapshots of `app-db` (your app's single state map), and HTTP records all travel the same path. That one wire feeds Xray, the epoch ledger, and any production shipper you wire up ([one wire, every tool](../concepts/observability.md)). So this page is the short list of declarations that keep the password, the token, and the 5MB upload off that wire.

Coming from Sentry? The instinct is `beforeSend`: a scrub function in each consumer, run just before shipping. re-frame2 does the opposite. You classify data **once, at its owner**, and the framework applies it at every boundary it owns. There's no `beforeSend` to write, which means there's no Nth consumer left to forget one.

The model has three layers, and they build on each other. The first is classification. You name facts as data: this path is sensitive, this slot is large. You write the fact next to whatever owns the data's shape — a frame, a schema, or a registration. (A frame, here, is an isolated instance of your app's state and event machinery; a registration is the call where you tell re-frame2 about a handler, the function that runs your logic.) Classification does nothing on its own. Your handlers always see the real values.

The second layer is projection. The framework applies your facts at a trust boundary — that's its job, not yours and not the sink's. When a record is about to cross a boundary, the runtime projects it under the owning frame's classification and substitutes sentinels at the classified slots. How strict it gets depends on which boundary, named by an egress profile. The profile is a closed six-member enum (`:rf.egress/off-box-observability` for hosted monitoring, `:rf.egress/local-redacted` for on-box dev panels, and so on). The profile matrix is in [Spec 015](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional). The wider posture-by-surface matrices are in [Spec 009](../../../spec/009-Instrumentation.md#production-debugging-what-remains).

The third layer is sink policy, which routes the projected records. A sink is your Datadog forwarder or a Sentry client — it receives records that are already safe, and it never scrubs anything itself. **You declare; the framework projects; sinks consume already-safe records.**

!!! note "This is leak prevention, not a security boundary"

    Your app still owns auth, encryption, and transport. The framework only keeps secrets off its own observability wire. Where it can't be sure, it fails closed — it redacts rather than leak.

## 1. Declare durable app-db secrets on the frame

State that *lives* in `app-db` is owned by the frame. That covers tokens, partner keys, and big blobs:

```clojure
(rf/reg-frame :app/main
  {:sensitive {:app-db [[:auth :token] [:auth :refresh-token]]}
   :large     {:app-db [[:documents :csv-upload]]}
   :initial-events [[:app/init]]})
```

A path declared both sensitive and large redacts as sensitive, because even "there's a 5MB blob here" says too much about a secret. Malformed paths fail loudly at registration, not silently at leak time, so a typo can't quietly disable your protection. Two rules worth remembering. First, a `reg-app-schema` slot prop does **not** classify app-db; the frame is the one owner of durable app-db privacy. Second, re-registering a frame replaces its policy wholesale, so keep everything in one declaration — step 4 grows this same map.

## 2. Declare transient payloads on the registration

Values that flow *through* the cascade are owned by the registration that introduces their shape. That covers event args, subscription outputs (a subscription is a derived, read-only view onto app-db), and flow outputs:

```clojure
(rf/reg-event :auth/sign-in
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

The handler body sees `password` verbatim, because handlers need real values to do their work. Only the *observable shadow* is projected: the dispatched-event trace and the HTTP record ship `:password` as `:rf/redacted`. The off-box production record goes further still (step 4). The `:auth/signed-in` handler then stores the response token at `[:auth :token]`, the path step 1 already declared frame-sensitive. (An empty path `[[]]` marks the whole payload — for example, the output of a sub that returns a token.)

## 3. Declare schema-owned slots with `:sensitive?` / `:large?`

Sometimes data's natural home already *is* a schema: a machine's `:data-schema`, a resource's `:data-schema` or `:params-schema`, an HTTP request's `:decode` schema (above). (A machine is a state machine you register to model a workflow; a resource is a declared piece of fetched-and-cached external data.) There, a per-slot boolean prop is the one and only route:

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

That `:token` slot redacts in every machine trace: the before/after of a transition, snapshots, guard inputs. The receipt PDF elides the same way. Rename a slot and the classification moves with it, since it's attached to the schema and not to a path you maintain separately. Fail-closed bites here too: a response body with **no** `:decode` schema is treated as whole-sensitive off-box, so a forgotten schema redacts rather than leaks.

This trips people up, so it's worth pinning down the difference. `:sensitive` (on a frame or registration) names a *collection of paths*. `:sensitive?` (on a schema slot) is a *yes/no about one slot*. Three owners, no overlap:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable frame-wide `app-db` state | the frame | `:sensitive` / `:large` path maps on `reg-frame` |
| Owner-local schema'd data (machine `:data`, resource data/params, HTTP bodies) | the schema | per-slot `:sensitive?` / `:large?` props |
| Transient payloads (event args, sub/flow outputs) | the registration | `:sensitive` / `:large` path vectors |

A sub or flow that *reads* a sensitive input inherits sensitivity by default. Declassifying a safe derivation — a hash, a count — is an explicit, auditable claim, covered in [Spec 015 §Derived sensitivity](../../../spec/015-Data-Classification.md#derived-sensitivity).

## 4. Before you wire an off-box shipper

Production observation records route by the frame's `:observability` policy. Name a sink id and the boundary's profile, then register the concrete function:

```clojure
(rf/reg-frame :app/main                 ;; the step-1 map, grown
  {:sensitive     {:app-db [[:auth :token] [:auth :refresh-token]]}
   :large         {:app-db [[:documents :csv-upload]]}
   :observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]}
   :initial-events [[:app/init]]})

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

Verify four things before the first record ships:

- **Every secret has its owner's declaration** — walk the three-row table for each one. That declaration is the whole defence; there's no second backstop.
- **The sink does no redaction.** If you find yourself writing a scrub inside the sink function, a declaration is missing upstream. Fix the owner, not the sink.
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

- **Know the fail-closed properties.** A frame with no `:observability` policy routes nothing. An unresolvable frame routes nothing — never a default frame. A throwing sink is isolated from its siblings, so one bad shipper can't take the others down.

!!! warning "Exceptions are the one gap"

    Projection walks known data shapes. It can't un-concatenate a secret out of an `ex-message` string, and it can't guess which `ex-data` keys are sensitive. In handlers that read secrets, throw the *category*, never the value: `(ex-info "Invalid credentials" {:reason :invalid-credentials})`.

## 5. Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. (To dispatch is to send an event into the system for handling.) The event row carries a magenta redacted marker on its arg map. The `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too. On-box panels render under the local-redacted profile, so your dev view exercises the very same classifications your shipper relies on. Three sentinel shapes to look for:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `:rf/large {:bytes N :head "…"}` — large; drillable on-box, elided off-box.
- `:rf/redacted {:bytes N}` — both; sensitive wins, so only the size may show.

A value you expected scrubbed but that renders raw means the owner declaration is missing or mis-pathed. Fix it at the owner, and the fix lands on every surface at once.

---

You can now:

- classify a secret or a blob at its one owner — frame, schema, or registration
- keep handler code working on real values while every observable surface ships sentinels
- verify what an off-box sink will receive with `rf/project-egress` before wiring it
- spot-check classifications live in Xray, and read the three sentinel shapes
