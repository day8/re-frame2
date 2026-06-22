# Keep secrets and large things out of traces

Your login form just put `{:password "hunter2"}` into an event vector — and an event in re-frame2 is just the data that describes something that happened, so that password is now sitting in plain data. Here's why that matters: re-frame2 sends all its observability over one wire. Events, snapshots of `app-db` (your app's single state map), and HTTP records all travel the same path. That one wire feeds Xray, the epoch ledger, and any production shipper you wire up ([one wire, every tool](../concepts/observability.md)). So this page is the short list of declarations that keep the password, the token, and the 5MB upload off that wire.

Coming from Sentry? The instinct is `beforeSend`: a scrub function in each consumer, run just before shipping. re-frame2 does the opposite. You classify data **once, at the owner of its shape**, and the framework applies it at every boundary it owns. There's no `beforeSend` to write, which means there's no Nth consumer left to forget one.

!!! note "This is hygiene, not a security boundary — and it is fail-open"

    The framework only keeps secrets off its *own* observability wire; your app still owns auth, encryption, and transport. And the contract is **fail-open**: a path you never classify ships raw. That's the bargain — convenient leak-prevention, not a guarantee. Classify the *path* a secret lives at, and the framework redacts whatever occupies it; forget a path and it leaks. There's no taint-tracking and no propagation, so a secret you copy to a new path (a re-keyed value, a rendered field) ships raw until you classify *that* path too.

The model has three layers, and they build on each other. The first is classification. You name facts as data: this *path* is sensitive, this slot is large. You write the fact next to whatever owns the data's shape — an event handler (for durable `app-db` paths), a registration (for transient payloads), or a subsystem definition (for machine / resource data). (A registration is the call where you tell re-frame2 about a handler, the function that runs your logic; a frame is an isolated instance of your app's state and event machinery.) Classification does nothing on its own. Your handlers always see the real values.

The second layer is projection. The framework applies your facts at a trust boundary — that's its job, not yours and not the sink's. When a record is about to cross a boundary, the runtime projects it under the owning frame's classification and substitutes sentinels at the classified slots. How strict it gets depends on which boundary, named by an egress profile. The profile is a closed six-member enum (`:rf.egress/off-box-observability` for hosted monitoring, `:rf.egress/local-redacted` for on-box dev panels, and so on). The profile matrix is in [Spec 015](../../../spec/015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional). The wider posture-by-surface matrices are in [Spec 009](../../../spec/009-Instrumentation.md#production-debugging-what-remains).

The third layer is sink policy, which routes the projected records. A sink is your Datadog forwarder or a Sentry client — it receives records that are already safe, and it never scrubs anything itself. **You declare; the framework projects; sinks consume already-safe records.**

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

Wire `:auth/init` to run at frame creation — `:initial-events [[:auth/init]]` on `reg-frame` is the preferred home, so the classification is in place before any off-box egress. The effects are applied **with the `:db` write** (a frame-state transform at the commit point, not a later `:fx`), so a path classified in an event is redacted from its *first* egress; a classification made earlier trivially covers it. Three things worth holding onto:

- **Classification is value-independent.** Classify a path *before* any value exists there — the common, safe pattern. The classification redacts whatever later occupies the path; over an absent path it's a harmless no-op. You don't re-classify per write.
- **Sensitive wins over large.** A path declared both redacts as sensitive, because even "there's a 5MB blob here" says too much about a secret. No size marker (whose `:path` / `:bytes` would leak structure) is emitted.
- **Malformed effects fail loud, pre-commit.** A bad payload (a non-vector value, a non-path entry, an unknown axis) aborts the transition with `:rf.error/classification-effect-shape` before any `:db` commit — a typo can't silently disable your protection.

There is **no frame `:sensitive {:app-db …}` annotation** and **no schema prop** that classifies a durable app-db path — a `reg-app-schema` slot describes shape and drives validation, not egress. The event is app-db's definition site; that is the one route.

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

The handler body sees `password` verbatim, because handlers need real values to do their work. Only the *observable shadow* is projected: the dispatched-event trace and the HTTP record ship `:password` as `:rf/redacted`. Paths index into the registration's primary shape — the event arg-map, the fx-input map, the sub output; an empty path `[[]]` marks the whole shape, and a mark at a missing slot is a silent no-op (payload shapes evolve).

!!! warning "Positional args are not path-addressable — a secret in one egresses RAW"

    A path like `[:password]` reaches into the event's **arg-map** (`[:auth/sign-in {:password "…"}]`), so the registration mark can name it. A positionally-passed secret (`[:auth/sign-in "alice" "hunter2"]`) has **no** stable named path: a positional index is not path-addressable, so there is nothing for `:sensitive` to classify. Under the fail-open contract that means the secret is **not redacted at egress** — it ships RAW into every trace and error sink (the dispatched-event trace, `:event/db-changed`, and the `:event` slot of a `:rf.error/handler-exception` record). This is a known structural limitation, not a bug: redaction is path-based, and only the arg-map (`(second event)`) is reachable by a path.

    **When an event carries a secret, pass a map payload and classify the key** — `[:auth/sign-in {:user "alice" :token "hunter2"}]` with `{:sensitive [[:token]]}` on the registration. The positional form is fine for non-sensitive args; reserve it for data you would not mind seeing in a trace.

The `:auth/signed-in` handler then stores the response token at `[:auth :token]` — the path step 1 classified. Note the boundary of fail-open: the **HTTP reply** is redacted by its `:decode` schema (a transient payload), and the **durable copy** in app-db is redacted by step 1's path classification. Those are two separate declarations for the same secret on two different surfaces; there is no propagation that carries one to the other. Classify each surface the secret crosses.

## 3. Subsystem data: declare it on the subsystem, projection-relative

Some data lives inside a runtime subsystem — a machine's `:data`, a resource's fetched data or params. You don't own its absolute storage path, so you declare `:sensitive` / `:large` **relative to the instance's shape** on the subsystem definition, and the framework lowers the declaration into the registry per instance (at spawn / fetch) and drops it on teardown:

```clojure
;; a machine declares its own sensitive / large :data slots, projection-relative
;; to one actor snapshot's :data. The :data-schema still VALIDATES :data; it no
;; longer classifies it for snapshot egress — that is the declaration below.
(rf/reg-machine :checkout/payment
  {:sensitive   [[:data :payment :token]]
   :large       [[:data :payment :receipt-pdf]]
   :data-schema [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {:spawn {:src :checkout/charge :on-done :done}}
             :done       {}}})

;; a resource declares its own statically-known sensitive / large fields
(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

That `[:data :payment :token]` slot redacts in every machine trace — the before/after of a transition, snapshots, guard inputs — for every spawned actor instance, with no per-instance author code. Rename the slot in the declaration and the classification moves with it.

!!! warning "The schema prop is a *different* axis — validation-failure traces, not durable egress"

    A `:sensitive?` / `:large?` prop on a `:data-schema` slot still does one thing: it redacts that slot in the schema's own **validation-failure trace** (the schema produces that record, so it owns its egress shape). It does **not** classify the durable `:data` for snapshot egress — that's the projection-relative `:sensitive` / `:large` declaration above. The same `:sensitive?` prop on an HTTP request's `:decode` schema is the right and only route for the *transient* response body (step 2). Schemas own *transient* and *validation-failure* products; durable state is the effect (step 1) or the subsystem declaration (here).

This trips people up, so it's worth pinning the difference between `:sensitive` and `:sensitive?`. `:sensitive` (no `?`) names a *collection of paths* — a classification effect, a registration mark, a subsystem declaration. `:sensitive?` (with `?`) is a *yes/no Malli prop on one schema slot*, surviving only for validation-failure-trace redaction and for the schema-owned *transient* products (HTTP `:decode` body, resource params). Three durable owners, no overlap:

| The data is… | Owner | Declare with |
|---|---|---|
| Durable `app-db` state | the **event** | `:sensitive` / `:large` classification effects (step 1) |
| Subsystem instance data (machine `:data`, resource data/params) | the **subsystem definition** | projection-relative `:sensitive` / `:large` on `reg-machine` / `reg-resource` (step 3) |
| Transient payloads (event args, fx/cofx, sub outputs, HTTP bodies) | the **registration** / its `:decode` schema | `:sensitive` / `:large` path vectors, or `:sensitive?` props on the `:decode` schema (step 2) |

**Classification does not propagate.** A sub or flow that *reads* a sensitive input does not auto-classify its output. If you derive a secret to a new path — through a sub, a flow, a rendered field — classify *that* path. A sensitive flow output is just a classified db path. There is no `:rf.egress/output-sensitivity` declassification claim (it's gone, silently ignored if present) and no value-match "same value redacted everywhere" engine.

## 4. Before you wire an off-box shipper

Production observation records route by the frame's `:observability` policy. Name a sink id and the boundary's profile, then register the concrete function:

```clojure
(rf/reg-frame :app/main
  {:observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]}
   :initial-events [[:auth/init]]})         ;; classifies [:auth :token] (step 1)

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

Verify four things before the first record ships:

- **Every secret's *path* has a declaration** — walk the three-row table for each one, on every surface the secret crosses (durable app-db, transient payload, subsystem). That declaration is the whole defence; the contract is fail-open, so an undeclared path leaks with no second backstop.
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

- **Know the fail-closed properties.** A frame with no `:observability` policy routes nothing. An unresolvable frame routes nothing — never a default frame. A throwing sink is isolated from its siblings, so one bad shipper can't take the others down. (Fail-*closed* here is the projector's posture on an unknown frame/profile; fail-*open* is the classification posture on an undeclared path — different surfaces, different defaults.)

!!! warning "Exceptions are the one gap"

    Projection walks known data shapes. It can't un-concatenate a secret out of an `ex-message` string, and it can't guess which `ex-data` keys are sensitive. In handlers that read secrets, throw the *category*, never the value: `(ex-info "Invalid credentials" {:reason :invalid-credentials})`.

## 5. Check the projection in Xray

Dispatch `[:auth/sign-in {:email "a@b.c" :password "hunter2"}]` in a dev build and open Xray. (To dispatch is to send an event into the system for handling.) The event row carries a magenta redacted marker on its arg map. The `:password` slot reads `:rf/redacted` with no reveal affordance — a redacted chip is **never** expandable, in any conformant tool. In the App-DB panel, `[:auth :token]` reads `:rf/redacted` too. On-box panels render under the local-redacted profile, so your dev view exercises the very same classifications your shipper relies on. Three sentinel shapes to look for:

- `:rf/redacted` — sensitive; opaque, no type, no size, no reveal.
- `:rf/large {:bytes N :head "…"}` — large; drillable on-box, elided off-box.
- `:rf/redacted {:bytes N}` — both; sensitive wins, so only the size may show.

A value you expected scrubbed but that renders raw means the *path* declaration is missing or mis-pathed — or the secret was re-keyed to a path you didn't classify (the fail-open case). Classify the path it actually lives at, and the fix lands on every surface at once.

---

You can now:

- classify a secret or a blob at the *path* its owner controls — an event effect, a registration mark, or a subsystem declaration
- keep handler code working on real values while every observable surface ships sentinels
- verify what an off-box sink will receive with `rf/project-egress` before wiring it
- spot-check classifications live in Xray, and read the three sentinel shapes
