# Validate with schemas

An [event handler](../glossary.md#event-handler) that writes `"loading"`, the string, where `:loading`, the keyword, belongs fails silently: the value lands in [app-db](../glossary.md#app-db), flows into a [subscription](../glossary.md#subscription), and shows up three screens later as a blank panel with no clue who wrote it. A registered [schema](../glossary.md#schema) makes that write fail at once, naming the handler, the offending value, and the path.

Most schema checks run only in dev builds and are elided from release builds, so they cost production nothing. A few checks stay in every build; [In production](#in-production-what-goes-what-stays) lists them.

??? info "Coming from Zod?"

    You never call `parse()` at a use site. You *register* a schema against a path or an event id, and the runtime validates at fixed points. At those ordinary checkpoints a schema is a development-time check rather than a production guard; the production guards are covered at the end of the page.

## Your first schema: a slice of app-db

`reg-app-schema` binds a schema to an app-db path, the vector of keys you'd pass to `get-in`:

```clojure
(ns myapp.schema
  (:require [re-frame.core :as rf]
            [re-frame.schemas]))   ;; loads the validator — one require, once per app

(def Todo
  [:map
   [:id    :int]
   [:title [:string {:min 1}]]
   [:done? :boolean]])

(rf/with-frame :app
  (rf/reg-app-schema [:todos] [:maybe [:map-of :int Todo]]))
```

`Todo` is plain data in [Malli](https://github.com/metosin/malli), the default schema language; the common shapes are [below](#the-shapes-youll-actually-write). The `:maybe` lets `[:todos]` stay `nil` until something writes it, because every registered path is checked on every commit ([below](#every-registered-path-is-checked-on-every-commit)).

The registration runs inside `with-frame` because schemas are registered per [frame](../glossary.md#frame), so it has to name one. A bare top-level call raises `:rf.error/no-frame-context`. You can instead pass the frame in a metadata map: `(rf/reg-app-schema [:todos] {:frame :app} [:maybe [:map-of :int Todo]])`. The frame doesn't have to exist yet. Schemas belong to the frame, so `rf/destroy-frame!` drops them: a frame re-created under the same id is unchecked until your `reg-app-schema` forms run again.

After every event handler runs, the runtime validates what the new app-db holds at `[:todos]` before installing it. If the value doesn't conform, the runtime emits `:rf.error/schema-validation-failure`, app-db keeps its pre-event value, and the dispatch is treated as failed.

!!! note "One require wires the validator"

    Requiring `re-frame.schemas` is what installs the Malli validator; you never call it directly. Without that artefact on the classpath, `reg-app-schema` [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/schemas-artefact-missing` rather than recording a schema that checks nothing.

??? info "From re-frame v1"

    The `check-spec-interceptor` from v1's todomvc example is built in, and the vocabulary is `:schema` everywhere. v1's `:spec` metadata key, the `:rf.spec/*` namespace, and the `:spec/at-boundary` interceptor don't exist; `reg-*` metadata doesn't accept `:spec`.

## Watch one catch a bug

A todo's title must never be blank. In this example the rule appears twice, on purpose: the `:todo/add` handler guards it with `str/blank?`, which ships to production, and the schema `[:string {:min 1}]` catches a violation in dev. (Each `reg-event` also carries an event schema, `{:schema [:cat ...]}`; those are covered [further down](#put-a-schema-on-the-event-too).)

```cljs-rf2
(require '[clojure.string :as str]
         '[re-frame.core :as rf]
         '[re-frame.schemas])

(def Todo
  [:map [:id :int] [:title [:string {:min 1}]] [:done? :boolean]])

;; Registered against the :app frame the root below creates.
(rf/reg-app-schema [:todos] {:frame :app} [:maybe [:map-of :int Todo]])

(rf/reg-event :todo/initialise
  {:schema [:cat [:= :todo/initialise]]}
  (fn [{:keys [db]} _]
    {:db (assoc db :todos {1 {:id 1 :title "Buy milk" :done? false}})}))

;; The handler owns the no-blank-titles rule. This guard ships to production.
(rf/reg-event :todo/add
  {:schema [:cat [:= :todo/add] :string]}
  (fn [{:keys [db]} [_ title]]
    (if (str/blank? title)
      {:db db}
      (let [id (inc (apply max 0 (keys (:todos db))))]
        {:db (assoc-in db [:todos id] {:id id :title title :done? false})}))))

(rf/reg-sub :todo/all
  (fn [db _] (vec (vals (:todos db)))))

(rf/reg-view todo-list []
  [:div
   [:button {:on-click #(dispatch [:todo/add "Walk the dog"])} "Add a todo"]
   [:button {:on-click #(dispatch [:todo/add ""])} "Add a blank todo"]
   [:ul (for [{:keys [id title]} @(subscribe [:todo/all])]
          ^{:key id} [:li title])]])

[rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

Click **Add a blank todo**: nothing happens, because the guard stands. Now simulate the bug the schema exists to catch. Delete the guard, keeping only the `let` branch, re-evaluate, and click again. The handler writes a todo with the title `""`, and `[:string {:min 1}]` rejects it. The browser console shows `:rf.error/schema-validation-failure`, and the list on screen is unchanged: the write was rejected before it installed, so app-db never held the bad value. Put the guard back when you're done.

This rejection is a debugging aid, not app behaviour. The schema is elided from production builds, where the unguarded handler would store a blank todo, so the handler keeps its guard. The schema catches, in dev, the day the guard is deleted, refactored wrong, or bypassed by another handler writing the same slice.

## The shapes you'll actually write

A Malli schema is a vector: a keyword naming the kind of shape (`:map`, `:int`, `:enum`, …), an optional properties map (`{:min 0}`), then the nested schemas it is built from. Seven shapes cover most of app-db:

```clojure
[:map [:id :int] [:title :string]]           ;; a map with these keys
[:enum :all :active :done]                    ;; one of a fixed set
[:int {:min 0}]                               ;; a bounded integer
[:string {:min 1}]                            ;; a non-empty string
[:re #".+@.+"]                                ;; a regex-shaped string
[:maybe :string]                              ;; a string, or nil
[:vector Todo]                                ;; a homogeneous vector
```

Shapes compose: a filter setting is an `:enum`, a form draft is a `[:map …]` of strings, a list is a `[:vector Todo]` where `Todo` is itself a `[:map …]`.

Keys in a `[:map …]` are required by default; mark one optional with `[:phone {:optional true} :string]`. Maps are open by default, so extra keys pass and producers can add keys without breaking consumers. Use `{:closed true}` at system boundaries, where you check a payload you don't trust.

Beyond these seven, `[:set …]`, `[:map-of …]`, `[:tuple …]`, `[:or …]`, and `[:fn pred]` cover most remaining needs; the [Malli README](https://github.com/metosin/malli) has the full vocabulary.

## Register a feature's slices at once

`reg-app-schemas` takes a `{path schema}` map and registers every entry in one call:

```clojure
;; Todo and TodoUi are your own schemas.
(rf/with-frame :app
  (rf/reg-app-schemas
   {[:todos]          [:maybe [:map-of :int Todo]]
    [:showing]        [:maybe [:enum :all :active :done]]
    [:todo.ui]        [:maybe TodoUi]
    [:todo.ui :draft] [:maybe :string]}))
```

Paths may nest: a write under `[:todo.ui :draft]` is checked against that schema and against the surrounding `[:todo.ui]` one. The empty path `[]` covers the whole of app-db. [The Conduit example](../../../examples/real-apps/realworld_http) registers every slice that holds server data, and every form draft, this way.

A duplicate path is last-write-wins, and the call returns the vector of paths it registered. Use the singular `reg-app-schema` when a feature has only a path or two, or when registration order matters: the plural form registers in the map's iteration order, which for a large map is not source order.

These paths cover your data only. The framework's [runtime-db partition](../app-db.md) validates itself, and registering a schema against it is an error ([Troubleshooting](#troubleshooting)).

## Every registered path is checked on every commit

Validation is not limited to the paths the committing event touched. After a handler returns `:db`, the runtime checks the candidate app-db at every path registered for the frame and rejects the whole transaction if any one fails. A handler that writes only `[:showing]` is still checked against your `[:todos]` schema.

`get-in` on a path nothing has written returns `nil`, and `nil` is checked like any other value, so a `[:map …]` over an unseeded slice fails. A schema registered before its slice exists rejects every commit until the slice is seeded.

That breaks a common boot shape: `reg-app-schemas` at namespace load, then a boot event that dispatches per-feature `:*/initialise` events. The first seed to commit is checked against every sibling slice that hasn't been seeded, fails on their `nil`s, and is rejected. A rejected transition doesn't run its `:fx`, so the remaining seeds are never dispatched, app-db never leaves `{}`, and each attempt reports a failure at a path the handler never touched.

There are two fixes. Seed every schema'd slice in one commit (a single `:db` write), so no registered path ever reads `nil`. Or wrap each schema in `:maybe` for as long as the slice can legitimately be empty:

```clojure
(rf/with-frame :app
  (rf/reg-app-schemas
   {[:config] [:maybe Config]     ;; nil until the config request returns
    [:flags]  [:maybe Flags]
    [:user]   [:maybe User]
    [:routes] [:maybe Routes]}))
```

The [boot example](../../../examples/patterns/boot/schema.cljs) does this: each slot starts out `nil` until the boot machine fills it, so every registration is `:maybe`. You give up the check that the slice is populated, which is right while it legitimately isn't.

!!! warning "Broken in dev, fine in production"

    App-db validation is dev-only, so an app with this defect works in a release build and is stuck at boot in a dev build. A dead dev app beside a healthy release build usually means a schema registered ahead of its data, not a broken release pipeline.

## Put a schema on the event too

App-db schemas check writes after a handler runs. An event schema rejects bad input before the handler runs. `reg-event` takes an optional metadata map between the id and the handler, and its `:schema` describes the event vector positionally with `[:cat …]`:

```clojure
(rf/reg-event :todo/toggle
  {:schema [:cat [:= :todo/toggle] :int]}
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))
```

The first slot is the event id, pinned with `[:= …]`, then an integer id. Dispatch `[:todo/toggle "1"]` and the check fails with `:where :event`; the handler never runs, and the rest of the [event](../glossary.md#event) queue keeps draining.

## The other three things you can schema

The `:schema` key works on other registration kinds too, with the same failure trace. What differs is the recovery:

```clojure
;; A sub's RETURN value, validated after it computes.
(rf/reg-sub :todo/done-list
  {:schema [:vector Todo]}
  (fn [db _] (filterv :done? (vals (:todos db)))))

;; An fx's ARGUMENT map, validated before the effect handler runs.
(rf/reg-fx :app/notify
  {:schema [:map [:level [:enum :info :error]] [:message :string]]}
  (fn [_ctx {:keys [level message]}]
    (js/console.log (name level) message)))

;; A RECORDABLE coeffect's value, validated as it is supplied or replayed.
(rf/reg-cofx :todo/created-at
  {:recordable? true
   :schema      [:int {:min 0}]}
  (fn [] (js/Date.now)))
```

- **Sub return** (`:where :sub-return`): the failure is reported and the [subscription](../glossary.md#subscription) yields `nil` to its consumer (`:replaced-with-default`), so [views](../glossary.md#view) see no value rather than a bad one. The [pipeline run](../glossary.md#run) continues.
- **Fx args** (`:where :fx-args`): the offending [effect](../glossary.md#effect) is skipped and the others in the same `:fx` vector still run. The trace names the failing effect.
- **Recordable coeffect**: a [recordable coeffect](../glossary.md#coeffect)'s value is saved so the run [replays](../glossary.md#time-travel) identically, so a value that fails its schema would make a later replay rebuild corrupt state ([Coeffects](../coeffects.md) explains the two grades). A mismatch emits `:rf.error/cofx-value-invalid` and throws, halting the run, in every build: the framework refuses to record the value. An ambient coeffect's `:schema` is a dev-only check like the others above.

## Read the failure trace

Every violation is a structured `:rf.error/schema-validation-failure` [trace event](../glossary.md#trace-event) that Xray and agents can query. The tags you'll use:

```clojure
{:operation :rf.error/schema-validation-failure
 :recovery  :no-recovery             ;; what the runtime did next
 :tags {:where      :app-db          ;; :event / :fx-args / :sub-return / :app-db / :machine-data / ...
        :path       [:todos 3 :title] ;; the FAILING LEAF path (root + route to the bad slot)
        :value      ""               ;; the offending value
        :explain    {...}            ;; the validator's explanation (a Malli explain map on CLJS)
        :failing-id :todo/add        ;; the handler / sub / fx that produced it
        :frame      :app             ;; which frame the failure happened in
        :rollback?  true}}           ;; on :app-db — the :db effect was discarded
```

`:path` is the failing leaf: the registered path plus the route into the bad slot, so on a `[:todos]` schema a blank title reports `[:todos 3 :title]`. An `:app-db` trace also carries `:registered-path`, the path you registered, for jumping back to the `reg-app-schema` call. `:explain` is the raw validator output. In dev builds with Malli, tools also receive `:explain-humanized`; a non-Malli validator provides `:explain` only, so tools fall back to it.

In **Xray**, `:event`, `:fx-args`, and `:sub-return` failures appear on the DISPATCH, FX, and SUBSCRIPTIONS steps of the event row. An `:app-db` failure appears on the FX step's `:db` row, and the steps downstream of it are muted because they never ran ([Debug with Xray](../../xray/index.md)).

An `:app-db` rejection also reaches the error stream in a dev build: one record per failing registration, with `:where :app-db`, `:rollback? true`, the `:registered-path`, and a `:reason` naming the type it found there ("got nil"). It goes to your frame's `:observability :errors` sink (or the process default's), and to the console as a red `[re-frame2] :rf.error/schema-validation-failure …` line when no sink handles errors. That tells you which registrations the candidate broke; open the trace or Xray for the leaf `:path`, the `:value`, and the `:explain`. None of this happens in a production build, because the check doesn't run there.

## In production: what goes, what stays

Under an `:advanced` build with `goog.DEBUG=false` ([Configure dev and production builds](configure-dev-and-prod.md)), the checks that assert your own code did what you intended are [elided](../glossary.md#elide): the validator calls, error strings, and redaction code are absent from the bundle. The schemas stay registered, so tools can still read them, but they are never checked. So write schemas freely; they cost production nothing.

Checks the framework relies on to keep its own promises stay in every build. Most of these read a schema you wrote; what decides is what the check is for, not who wrote the schema:

- **A handler's own `:schema` under `:boundary? true`**, for untrusted input (below).
- **A recordable coeffect's `:schema`**, which throws rather than record a value a replay would rebuild corrupt state from ([above](#the-other-three-things-you-can-schema)).
- **A declared route's shape**, checked whenever the schemas artefact is loaded and the route declares a schema.
- **A managed-HTTP `:decode` schema**, which is part of parsing the response; removing it would change what the handler receives.
- **The reserved `:rf.server/*` effects' own arguments.**

To validate untrusted data in production (an HTTP response, a websocket message, a `postMessage` payload), register the handler with `:boundary? true`. Its own `:schema` is then checked in every build:

```clojure
;; A websocket pushes the server's todo list.
(rf/reg-event :todo/synced
  {:schema [:cat [:= :todo/synced] [:map [:todos [:vector Todo]]]]
   :boundary? true}                             ;; check this :schema in every build
  (fn [{:keys [db]} [_ body]]
    {:db (assoc db :todos (into {} (map (juxt :id identity)) (:todos body)))}))
```

The flag doesn't add a check; it keeps the handler's existing `:schema` in production. The check runs before any interceptor, against the event vector as dispatched, so dev and production check the same value at the same point. Registering `:boundary? true` on a handler with no `:schema` throws `:rf.error/at-boundary-missing-schema`. Payloads from outside are checked in production while your other handlers stay free of checks.

A rejected payload is refused in every build: the handler is skipped and nothing reaches app-db. In a release build the refusal still reaches the two always-on streams, with no wiring on your part: one `:rf.error/schema-validation-failure` record with `:source :boundary` on the error stream, and `:status :rejected` on that dispatch's `:handled-events` record ([Report errors in production](report-errors-in-production.md#6-pair-errors-with-its-handled-events-sibling)).

The production error record carries nothing derived from the payload. Its keys are `:error`, `:where`, `:source`, `:event-id`, `:failing-id`, `:schema-id`, `:frame`, `:recovery`, and `:time`: no event vector, value, Malli explanation, or `:reason` text. A boundary payload is untrusted and may carry secrets under keys your schema never anticipated, so the value is omitted rather than redacted. You can count refusals, attribute them, and alert on the rate; to diagnose one, read the dev trace or branch in the handler.

??? info "Coming from TanStack Query?"

    `:boundary? true` is the equivalent of `schema.parse(await res.json())` at the fetch boundary, attached to the handler that receives the payload. The difference: `parse` throws and hands you the offending value at the catch site, while a release build reports a boundary refusal structurally (event id, schema id, frame) without the payload, and the handler is simply skipped. If a bad response needs handling rather than counting, handle it in the handler.

## When a schema earns its keep

Write a schema when it could catch something your tests wouldn't:

- the slice has more than two or three keys, each a chance for a typo;
- a value has the right type but a narrower range: an `:enum` status, a non-negative `:int`, a regex-shaped string;
- the slice is a contract between two features, one writing and one reading;
- an AI agent maintains the slice, since agents read registered schemas to know what to write.

Skip it when the slice is a single scalar: `{:nav/open? true}` doesn't need `[:map [:open? :boolean]]`. Don't register `:any` as a placeholder; it suggests a constraint that isn't there.

Use `[:enum …]` for fixed value sets rather than bare `:keyword`, keep maps open except at boundaries, and keep each schema in the same namespace as the handlers that write its slice.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/app-schema-bad-path` at registration (every build) | The path is a keyword, string or map, not a vector of keys; in `reg-app-schemas`, one bad key rejects the whole batch | Pass a vector, or `[]` for the root |
| `:rf.error/app-schemas-bad-batch` | `reg-app-schemas` got something other than a `{path schema}` map | Pass a map of vector paths |
| `:rf.error/app-schema-runtime-path` | The path starts with `:rf/runtime` or a reserved `:rf.runtime/*` key | Schema your own data only; runtime-db validates itself |
| `:rf.error/malformed-schema` at the first check; the commit is rejected | A broken schema form (a childless `[:vector]`, an unknown op) registers cleanly because Malli validates forms lazily | Fix the form; the frame's other schemas keep validating |
| Every commit rejected, naming a path the handler never touched | A schema registered before its slice was seeded | Seed every slice in one commit, or wrap the schema in `:maybe` ([above](#every-registered-path-is-checked-on-every-commit)) |
| `:rf.schema/violation` warning (Xray Issues panel) after a hot reload | A tightened schema no longer matches the value already in app-db; the warning carries `:pre-reload-schema`, `:post-reload-schema` and `:mismatching-value` | Dispatch the event that rewrites the slice, or reload the page |

## Advanced

### Machine data schemas

If you use [machines](../../machines/concepts.md), a machine's `:data` takes a schema too, declared at `[:schemas :data]` on the machine spec rather than with `reg-app-schema`, because the snapshot lives in [runtime-db](../glossary.md#runtime-db). The runtime checks it at boot and after every transition. A mismatch rolls the whole macrostep back, as an app-db failure does, and reports `:where :machine-data`; it reaches the `:errors` stream too, carrying `:machine-id` and `:phase` instead of `:registered-path`. Narrower machine checks (a rejected `spawn`, a skipped `:rf.machine/update-snapshot` patch) stay on the trace only, because they skip one write rather than discarding the transaction.

```clojure
(rf/reg-machine :todo/editor
  {:initial :idle
   :data    {:title ""}
   :schemas {:data [:map [:title :string]]}   ;; validates :data
   :states  {:idle {}}})
```

### Keep a failing value out of the trace

A validation failure carries the failing value, which is what makes it debuggable, but a credential that fails its schema would then reach every listener, including off-box monitors. Two reserved keys in a schema slot's properties map change what a failure trace carries:

```clojure
(rf/with-frame :app
  (rf/reg-app-schema [:auth]
    [:maybe [:map
             [:user  [:maybe [:map [:email :string] [:username :string]]]]
             [:token {:sensitive? true} [:maybe :string]]]]))   ;; a bad :token fails redacted
```

- **`:sensitive? true`**: when this slot fails, the trace's `:value`, `:explain` (which would repeat the value), and other value-bearing slots are replaced with `:rf/redacted`, and the trace event carries `:sensitive? true` at its top level. The structural tags (`:path`, `:failing-id`, the schema id) remain, so you can still find the slot.
- **`:large? true`**: the value is replaced with a `:rf.size/large-elided` marker instead of putting megabytes into the trace. A slot marked both ways is redacted; sensitive wins, since even the size says something about a secret.

!!! warning "Gotcha: these flags affect only the failure trace"

    `:sensitive?` and `:large?` in a schema control only what a validation-failure trace carries. They don't classify the value for normal traces, epochs, or production records. For that, a handler returns a classification effect alongside `:db` (`{:db … :sensitive [[:auth :token]]}`); see [Keep secrets out of traces](keep-secrets-out-of-traces.md).

### Query your schemas (tools and agents)

Registered schemas can be queried, by you, by tools, and by AI agents: "what shape lives at `[:todos]`?" The registration functions are on `rf/`, but the readers are on `re-frame.schemas`:

```clojure
(require '[re-frame.schemas :as schemas])

(schemas/app-schema-meta {:frame :app :path [:todos]})
;; => {:path [:todos] :schema [:maybe [:map-of :int Todo]] :frame :app :ns ... :line ... :file ...}

(schemas/app-schemas {:frame :app})
;; => {[:todos] {:path [:todos] :schema [...] ...}, ...}   one frame, keyed by path

(update-vals (schemas/app-schemas {:frame :app}) :schema)
;; => {[:todos] [:maybe [:map-of :int Todo]], ...}         the schemas alone

(schemas/app-schemas-digest {:frame :app})
;; => "sha256:abc1234567890def"                                       a hash of the whole set
```

Each reader takes one map with a required `:frame` (a frame-id keyword or a frame value); a call without one raises `:rf.error/no-frame-context`. Event, sub, and fx schemas come from the registrar instead: `(rf/handler-meta {:source :store :kind :event :id :todo/toggle})` returns `{:schema [:cat ...] :doc ... :ns ...}`.

The digest is a deterministic hash of a frame's whole schema set, identical on any runtime for the same schemas. When a server-rendered page [hydrates](../../ssr/glossary.md#hydration), the client compares the server's digest with its own and emits a `:rf.ssr/schema-digest-mismatch` warning if they differ, which means the server and client bundles are out of sync. Agents use the same readers to learn what shape to write before they dispatch, and Malli's `mg/generate` turns a schema into test data.

### Swap the validator (Malli is the default)

The runtime never inspects a `:schema` itself; every check goes through a registered validator function. That is how an app replaces Malli with `clojure.spec`, or turns validation off. Install the validator, explainer, and printer together at boot with `set-schema-fns!` on `re-frame.schemas`:

```clojure
(require '[re-frame.schemas :as schemas])

(schemas/set-schema-fns! {:validate my-validate-fn   ;; (fn [schema value] truthy?)
                          :explain  my-explain-fn    ;; (fn [schema value] explanation)
                          :print    my-print-fn})    ;; (fn [schema-value] canonical-string), feeds the digest

;; Each key is optional; an absent key keeps the current function.
(schemas/set-schema-fns! {:explain my-explain-fn})

;; Disable validation: every check passes.
(schemas/set-schema-fns! {:validate nil})
```

One set of functions is in force per process, and the last call wins. `(schemas/set-schema-fns! schemas/default-schema-fns)` restores the Malli defaults.
