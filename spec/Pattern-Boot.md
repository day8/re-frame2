# Pattern — Boot

> **Type:** Pattern
> Application boot as a chained-async sequence — the canonical state-machine form for "read config → authenticate → load profile → hydrate → resolve route → ready". Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **named pattern**, not a Spec. Real SPAs have a multi-step initialisation sequence with dependencies: read config, authenticate / restore session, load user profile or feature flags, hydrate from `localStorage`, resolve routing, connect real-time services, mount UI. Each step depends on the previous; failure at any step is usually fatal or specifically recoverable; the user wants to see progress.

Re-frame2 implies the answer is "chain via dispatched events". Workable for trivial boots; unstructured once the boot graph has more than a few steps. This pattern names the canonical answer along the spectrum from chained events to a dedicated boot state machine.

## What makes booting distinctive

Vs the generic [Pattern-AsyncEffect](Pattern-AsyncEffect.md):

- **Sequential dependencies.** Step N+1 depends on step N. Mostly cannot parallelise.
- **Failure semantics differ.** A failed step usually halts boot; the user sees an error page, not a partial app. Some failures are recoverable with retry; others are fatal.
- **Visible progress.** Users want to see "Loading profile…" then "Connecting…" then "Almost ready…". The boot UI is its own thing distinct from the running-app UI.
- **One-shot semantics.** Booting runs once per app load. Re-booting is unusual but should work (e.g., session expired → re-authenticate → resume).
- **SSR has a parallel concern.** Server-side init via `:rf/server-init` (per [011](011-SSR.md)) covers part of this; client-side boot handles the post-hydration steps.
- **Hot-reload concerns.** In dev, boot must not re-run on every code reload; only on initial app load.

## The simple form — chained events

For trivial boots (one or two steps, no error states, no progress UI), a state machine is overkill. Chain via dispatched events:

```clojure
(rf/reg-event :app/init
  (fn [_ _]
    {:fx [[:dispatch [:config/load]]]}))

(rf/reg-event :config/load
  (fn [_ _]
    {:fx [[:http {:url        "/config"
                  :on-success [:config/loaded]
                  :on-error   [:app/init-failed]}]]}))

(rf/reg-event :config/loaded
  (fn [{:keys [db]} [_ config]]
    {:db (assoc db :config config)
     :fx [[:dispatch [:app/ready]]]}))

(rf/reg-event :app/ready
  (fn [{:keys [db]} _] {:db (assoc db :app/booted? true)}))
```

(`:http` here is a placeholder for a user-supplied fx; the framework ships `:rf.http/managed` — see [014-HTTPRequests](014-HTTPRequests.md).)

Each step is a Pattern-AsyncEffect interaction. The frame's `:initial-events` fire `:app/init` (per [002 §`reg-frame` is atomic](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)), the chain runs to completion, the UI renders.

Use this form when the boot graph is **3 steps or fewer**, has **no error states**, and the UI does **not** show per-phase progress.

## The state-machine canonical form

Once the boot graph has more than a few steps, error states, retries, or visible progress, the chained-events form scatters boot logic across N unrelated event handlers — invisible as a sequence. The canonical form is **a single state machine that owns the boot sequence**.

The boot machine composes the locked machine substrate: hierarchical states for grouping phases, machine-scoped `:guards` / `:actions` (per [005 §Registration — the machine IS the event handler](005-StateMachines.md)), `:spawn` for spawning each phase's async work (per [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)), `:after` for retry backoff (per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) — no new substrate.

### Standard boot states

| State | Meaning |
|---|---|
| `:configuring` | Reading static config (URLs, feature flags, build info). |
| `:authenticating` | Restoring session token; refreshing if needed. |
| `:loading-profile` | Fetching user profile, preferences, feature flags scoped to the user. |
| `:hydrating` | Applying any client-side persistent state (`localStorage`, IndexedDB cache). |
| `:routing` | Resolving the initial route — including auth-gated redirects. |
| `:ready` | Boot complete. The running-app UI takes over. (Terminal.) |
| `:auth-failed` / `:profile-failed` / `:network-error` / `:fatal-error` | Per-phase or terminal error states. |
| `:retrying-auth` / `:retrying-profile` | Recovery states with `:after` backoff before re-attempt. |

Each phase uses `:spawn` to spawn the async work; transitions on success or failure; entry actions update the progress UI; the whole sequence is one inspectable, testable, traceable state machine.

### Worked example — six-state boot

```clojure
(rf/reg-event :app/boot
  {:doc "Application boot: config → auth → profile → hydrate → route → ready."}
  (rf/make-machine-handler
    {:initial :configuring
     :data    {:config nil :user nil :error nil :phase-attempt 0}

     :guards
     {:has-session-token?
      (fn [_ctx] (some? (.getItem js/localStorage "auth/token")))

      :under-retry-limit?
      (fn [{:keys [data]}] (< (:phase-attempt data) 3))}

     :actions
     {:set-phase
      ;; Update visible-progress slice in :data so the boot UI can render.
      (fn [{:keys [data] [_ phase] :event}]
        {:data (assoc data :phase phase :phase-attempt 0)})

      :record-config
      (fn [{:keys [data] [_ config] :event}]
        {:data (assoc data :config config)})

      :record-user
      (fn [{:keys [data] [_ user] :event}]
        {:data (assoc data :user user)})

      :bump-attempt
      (fn [{:keys [data]}]
        {:data (update data :phase-attempt inc)})

      :record-error
      (fn [{:keys [data] [_ err] :event}]
        {:data (assoc data :error err)})

      :resolve-initial-route
      ;; Reads :route from URL and seeds the :route slice (per Spec 012).
      (fn [_ctx]
        {:fx [[:dispatch [:rf.route/handle-url-change (.. js/window -location -href)]]]})

      :enter-routing
      ;; Compound entry action for :routing — :set-phase + :resolve-initial-route
      ;; in one fn. (Per [005 §State nodes] :entry takes one fn or one
      ;; registered id, never a vector.)
      (fn [{:keys [data]}]
        {:data (assoc data :phase :routing :phase-attempt 0)
         :fx   [[:dispatch [:rf.route/handle-url-change (.. js/window -location -href)]]]})}

     :states
     {;; :configuring runs an explicit Pattern-AsyncEffect instance: the boot
      ;; machine :spawns a child :http/get actor whose :data carries the URL.
      ;; The actor fetches /config and :dispatch-replys :succeeded with the body;
      ;; :record-config writes it into the boot machine's :data for downstream
      ;; states to thread into their own work.
      :configuring
      {:entry  :set-phase
       :spawn {:machine-id :http/get
                :data       {:url "/config"}
                :on-spawn   (fn [{:keys [data id]}] (assoc data :pending id))}
       :on     {:succeeded {:target :authenticating
                            :action :record-config}
                :failed    {:target :fatal-error
                            :action :record-error}}}

      ;; Subsequent states read from :data :config and thread the values into
      ;; the next phase's :spawn spawn-spec or dispatched event.
      :authenticating
      {:entry  :set-phase
       :spawn {:machine-id :auth/restore-session
                ;; Spawn-spec :data fn — read the auth URL out of the boot
                ;; machine's :data :config (per Pattern-AsyncEffect mechanism 2).
                ;; The :data fn receives the parent's context map; read its
                ;; :data from the :snapshot slot (per 005 §Declarative :spawn).
                :data       (fn [{:keys [snapshot]}]
                              {:auth-url (-> snapshot :data :config :auth-url)})
                :on-spawn   (fn [{:keys [data id]}] (assoc data :pending id))}
       :on     {:succeeded {:target :loading-profile}
                :failed    [{:target :retrying-auth
                             :guard  :under-retry-limit?
                             :action :bump-attempt}
                            {:target :auth-failed
                             :action :record-error}]}}

      :retrying-auth
      {:after {2000 {:target :authenticating}}}

      :loading-profile
      {:entry  :set-phase
       :spawn {:machine-id :http/get
                ;; Read the profile URL from the loaded config rather than
                ;; hardcoding it — the boot machine threads host config in
                ;; via the spawn-spec :data fn.
                :data       (fn [{:keys [snapshot]}]
                              {:url (-> snapshot :data :config :profile-url)})
                :on-spawn   (fn [{:keys [data id]}] (assoc data :pending id))}
       :on     {:succeeded {:target :hydrating
                            :action :record-user}
                :failed    {:target :profile-failed
                            :action :record-error}}}

      :hydrating
      {:entry :set-phase
       :on    {:hydrate/done {:target :routing}}}

      :routing
      {:entry :enter-routing
       ;; If the running app needs the WebSocket URL from config, the :ready
       ;; transition threads it into the connection machine's :connect event
       ;; (per Pattern-AsyncEffect mechanism 1):
       ;;   {:fx [[:dispatch [:ws/connection
       ;;                     [:ws/connect {:url        (-> data :config :ws-url)
       ;;                                   :auth-token (-> data :session :token)}]]]]}
       :on    {:rf.route/resolved {:target :ready}}}

      :ready          {:meta {:terminal? true}}
      :auth-failed    {:meta {:terminal? true}}
      :profile-failed {:meta {:terminal? true}}
      :fatal-error    {:meta {:terminal? true}}}}))
```

The frame's `:initial-events` dispatch `[:app/boot [:rf.machine/start]]` (or the equivalent per the host); the machine self-initialises (per [005 §Restore semantics]) and runs. `:rf.machine/start` is the **only** reserved creation marker the runtime recognises (xstate parity with `createActor(m).start()`, per [005 §Synthetic creation marker](005-StateMachines.md#synthetic-creation-marker--rfmachinestart)) — there is no `:rf/start`.

### Worked example — the singleton boot machine

The six-state machine above leaves three things implicit that a real migration has to get exactly right. The boot machine is a **singleton** — addressed by its registered id and started once. A handler's wholesale `{:db fresh-map}` replace does not clobber it: the machine's snapshot lives in the framework-owned **runtime-db** partition, a separate slot that a `:db` return does not touch (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)). The boot failures that remain are addressing and ordering, not clobber.

This is the end-to-end recipe.

#### 1. A singleton — addressed by its registered id, NOT `:spawn` / `:system-id`

The boot machine is a **top-level singleton**: there is exactly one of it per app, and you address it by the id you registered it under (`:app/boot`).

```clojure
(rf/reg-machine :app/boot
  {:initial :configuring
   :data    {:config nil :user nil :error nil :phase nil}
   ;; … :guards / :actions / :states exactly as the six-state example above …
   })

;; Dispatch to it by its registered id — the id IS the address:
(rf/dispatch [:app/boot [:rf.machine/start]])     ;; the eager creation kick
(rf/dispatch [:app/boot [:auth.session/expired]]) ;; any later event
```

This is **not** `spawn` and **not** `:system-id`. Those two surfaces (per [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn) and [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id)) exist for **dynamic / child actors** — instances created at runtime (one per row, one per request, one per worker), addressed by a runtime-allocated gensym id or a role-name bound in the per-frame `[:rf.runtime/machines :system-ids]` reverse index (in runtime-db). A boot machine is none of those: there is one, it is known at registration time, and its name is the address. Reach for `:spawn` / `:system-id` only when boot itself spawns child actors (e.g. a per-phase `:http/get`, as the `:configuring` state above does).

#### 2. The eager kick from `:initial-events` — using the correct marker

A singleton is created **lazily** by default — the initial-entry cascade folds into its first real event (per [005 §When creation happens](005-StateMachines.md#when-creation-happens--eager-start-vs-lazy-first-event)). Boot wants the opposite: the machine must come alive **now**, at app start, so its `:configuring` entry fires and the boot sequence begins. That is the **eager kick** — dispatch the reserved `:rf.machine/start` marker from the frame's `:initial-events`:

```clojure
(rf/reg-event :app/initialise
  {:doc "App entry point. Seeds the initial db, then eager-starts the boot machine."}
  (fn [_ _]
    {:db initial-db                                    ;; (see step 3 — ordering matters)
     :fx [[:dispatch [:app/boot [:rf.machine/start]]]]}))

(rf/reg-frame :app/main
  {:initial-events [[:app/initialise]]
   ;; … views, fx-overrides, etc …
   })
```

`:rf.machine/start` is the reserved synthetic creation marker (per [005 §Synthetic creation marker](005-StateMachines.md#synthetic-creation-marker--rfmachinestart)) — xstate parity with `createActor(m).start()`. It is recognised by the machine runtime and by **nothing else**; there is no `:rf/start`. As a *pure init-kick* it runs the initial macrostep — the initial-entry cascade plus the eventless (`:always`) settle — then **stops**; it is never re-fed as a transition trigger.

#### 3. The wholesale `{:db fresh-map}` seed is safe — never write a `:rf/runtime` app-db root

Under the **two-partition frame contract** (the canonical definition is [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract); machine snapshots live in the runtime-db partition at `[:rf.runtime/machines :snapshots <id>]` per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)), a handler's `:db` return replaces only the app-db partition and cannot touch runtime-db. So the v1 `:initialize-db` idiom — a wholesale `{:db fresh-map}` replace — is safe by construction here: it leaves every live machine snapshot, the route slice, and the rest of runtime-db untouched. There is no clobber to avoid, no ordering constraint on the seed relative to the eager kick, and nothing to preserve across the replace.

```clojure
(rf/reg-event :app/initialise
  (fn [_ _]
    {:db  {:config nil :user nil :ui {:theme :light}}   ;; wholesale app-db reset — runtime-db is untouched
     :fx  [[:dispatch [:app/boot [:rf.machine/start]]]]}))
```

The one boot-time rule: **never put a `:rf/runtime` key in a `:db` value** — a `:db` carrying the retired single-root `:rf/runtime` key is the hard error `:rf.error/legacy-runtime-root` (the contract is owned by [Conventions §The legacy `:rf/runtime` root](Conventions.md#the-legacy-rfruntime-root-hard-error-in-final-form)). Carrying `:rf/runtime` across the replace to preserve the snapshot is both unnecessary (the partition is already untouched) and forbidden (it throws). To seed or replace runtime-db state, emit the reserved `:rf.db/runtime` effect (or use `replace-runtime-db!` / `replace-frame-state!`), never an app-db key. The [MIGRATION guide](../migration/from-re-frame-v1/README.md) makes the same correction for any v1 full-db-replace boot event adopted into v2.

#### 4. The eager kick commits the birth snapshot before the entry `:fx` run

The eager `[:rf.machine/start]` kick **commits the birth snapshot first**, then the initial state's `:entry` `:fx` flow out. The initial macrostep builds the snapshot (initial-entry cascade + `:always` settle), the runtime commits it to `[:rf.runtime/machines :snapshots :app/boot]` in runtime-db, and only then does the entry-dispatched work (the `:configuring` state's `:spawn` of `:http/get`, here) run as ordinary dispatched `:fx`. So by the time any boot-phase effect fires, the snapshot is already durably in runtime-db — there is no window in which a phase effect's reply lands before the machine exists.

Because runtime-db is a separate partition, a boot machine that "never leaves `:configuring`" is **not** a clobbered-snapshot symptom (a `:db` replace cannot drop the snapshot). Look instead at the eager kick (step 2) — an unstarted machine, a wrong marker (`[:start]` instead of `[:rf.machine/start]`), or an `init!`-too-late ordering — and at the phase fx themselves.

### Worked example — auth-machine and the retry-ownership boundary

The auth phase of boot is the canonical demonstration of the **hybrid retry-ownership rule** from [Spec 014 §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry):

- **Transport retry** — function of attempt count + failure category — is owned by `:rf.http/managed` `:retry`. Network errors, 5xx, per-attempt timeouts; "wait `backoff(N)` and try again." Local to the request.
- **Semantic retry** — response-conditional, app-state-conditional, joined-across-requests — is owned by the state machine. 401-then-refresh-then-retry; "if the body says rate-limited, transition to `:cooldown` and re-issue from there"; "if another in-flight request fails first, abandon this one." Cross-request control flow.

Both halves coexist on the same call site: the machine drives `:rf.http/managed` requests, and each managed request configures its own transport-level `:retry`. The two layers compose without overlap — the machine's transition fires only after the transport-level retry loop has either succeeded or fully exhausted its attempts.

The pattern, distilled to a worked sketch:

```clojure
;; ---------- Transport retry — :rf.http/managed handles 5xx + network ----------
;; This is the call site the machine's :spawn spawns. The :retry slot owns
;; "after a 503, wait backoff(N) and try again." That decision is mechanical:
;; failure category + attempt count. No state, no other request, no body
;; matching — pure transport retry.

(rf/reg-event ::fetch-me
  (fn [_ [_ {:keys [token]}]]
    {:fx [[:rf.http/managed
           {:request {:method  :get
                      :url     "/api/me"
                      :headers {"Authorization" (str "Bearer " token)}}
            :decode  MeResponse
            :retry   {:on           #{:rf.http/transport
                                      :rf.http/http-5xx
                                      :rf.http/timeout}
                      :max-attempts 3
                      :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
            :on-success [:auth/me-loaded]
            :on-failure [:auth/transport-failed]}]]}))

;; ---------- Semantic retry — state machine handles 401-vs-200-vs-fatal ------
;; The auth machine routes by outcome:
;;   - :succeeded → done.
;;   - :failed whose :error is {:kind :rf.http/http-4xx :status 401} → :refreshing.
;;     The refresh path itself is a managed request; on success it loops back
;;     to :loading-me — a *semantic* retry of the original /api/me call.
;;   - :failed otherwise (5xx after retries exhausted, network error,
;;     decode-failure) → :login. Transport already retried; now the machine
;;     gives up.
;; The :refreshing → :loading-me transition is the semantic retry: it
;; depends on another request succeeding *first*, which is exactly the case
;; that doesn't fit inside :rf.http/managed's :retry slot.

(rf/reg-event :auth/flow
  (rf/make-machine-handler
    {:initial :loading-me
     :data    {:token nil :user nil :error nil}

     :guards
     ;; rf2-ibksxg — the canonical reply carries the classified :rf.http/* map
     ;; under :error; branch on (:kind error) / (:status error).
     {:got-401? (fn [{[_ {:keys [error]}] :event}]
                  (and (= :rf.http/http-4xx (:kind error))
                       (= 401 (:status error))))
      :token-stale? (fn [{:keys [data]}]
                      (some? (:token data)))}                  ;; refresh viable

     :actions
     {:record-token (fn [{:keys [data] [_ {:keys [token]}] :event}] {:data (assoc data :token token)})
      :record-user  (fn [{:keys [data] [_ {:keys [value]}] :event}] {:data (assoc data :user value)})
      :record-error (fn [{:keys [data] [_ {:keys [error]}] :event}] {:data (assoc data :error error)})}

     :states
     {:loading-me
      {:spawn {:src ::fetch-me
                :data (fn [{:keys [snapshot]}] {:token (-> snapshot :data :token)})}
       :on    {:succeeded {:target :authenticated :action :record-user}
               :failed    [{:guard  :got-401?
                            :target :refreshing
                            :action :record-error}
                           {:target :login                         ;; non-401 → fatal
                            :action :record-error}]}}

      :refreshing
      {:spawn {:src ::refresh-token}
       :on    {:succeeded {:target :loading-me                    ;; semantic retry
                           :action :record-token}
               :failed    {:target :login                         ;; refresh failed → fatal
                           :action :record-error}}}

      :authenticated {:meta {:terminal? true}}
      :login         {:meta {:terminal? true}}}}))
```

The boundary is teachable in three sentences:

1. **`:rf.http/managed` `:retry` retries the same request when nothing else has to change.** Same URL, same headers, same body, same auth — only the wait time and the attempt counter differ. Transport-level.
2. **The state machine retries when something has to change first** — refreshing a token, waiting for another request, conditioning on the response body, conditioning on app state. Semantic-level.
3. **They compose.** The machine's `:spawn` spawns a managed request that itself retries 5xx; once that loop terminates (success or exhaustion), the machine sees a single `:succeeded` / `:failed` event and transitions accordingly. No call site has to choose one layer or the other — every non-trivial request configures both.

#### Refresh-vs-init distinction

The auth flow above runs **after init** — the machine reaches `:loading-me` because the user already has a (possibly stale) token from an earlier session, and `:refreshing` is the answer to "the token expired mid-session." Init is different: there's no token to refresh, so a 401 routes straight to `:login` rather than `:refreshing`. Apps that distinguish the two carry an `:init?` flag in `:data` and gate the `:got-401?` transition on `(and got-401? (not init?))`; init's 401 falls through to `:login`. The transport-level retry of 5xx applies identically in both modes — it doesn't care whether the request is the init fetch or a mid-session refetch.

Cross-references: this section is the worked example referenced from [Spec 014 §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry); the broader research that surfaced the boundary is the Dash8 / rf8 boot-flow study.

### Boot UI — reading progress from the snapshot

A view subscribes to the machine snapshot via the `[:rf/machine <id>]` vector (per [005 §Reading the snapshot](005-StateMachines.md)) and renders the visible-progress slice carried in `:data`:

A view reads the machine snapshot through the framework-shipped `:rf/machine` sub (the `[:rf/machine <id>]` subscription vector, per [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub)), which resolves the snapshot from runtime-db at `[:rf.runtime/machines :snapshots <id>]` — never an app-db path. Derive the visible-progress slices from that snapshot:

```clojure
(rf/reg-sub :app.boot/phase
  :<- [:rf/machine :app/boot]                          ;; the boot snapshot, read from runtime-db
  (fn sub-app-boot-phase [snap _] (get-in snap [:data :phase])))

(rf/reg-sub :app.boot/error
  :<- [:rf/machine :app/boot]
  (fn sub-app-boot-error [snap _] (get-in snap [:data :error])))

(rf/reg-sub :app.boot/state
  :<- [:rf/machine :app/boot]
  (fn sub-app-boot-state [snap _] (:state snap)))

(rf/reg-view boot-screen []
  (let [state @(subscribe [:app.boot/state])
        phase @(subscribe [:app.boot/phase])
        err   @(subscribe [:app.boot/error])]
    (cond
      (= state :ready)
      [running-app-root]

      (#{:auth-failed :profile-failed :fatal-error} state)
      [:div.boot-error
       [:p (str "Couldn't start: " err)]
       [:button {:on-click #(dispatch [:app/boot [:rf.machine/start]])} "Retry"]]

      :else
      [:div.boot-progress
       [:p (case phase
             :configuring     "Loading config…"
             :authenticating  "Signing in…"
             :loading-profile "Loading your profile…"
             :hydrating       "Restoring state…"
             :routing         "Almost ready…"
             "Starting…")]])))
```

The progress UI reads from the same snapshot the machine writes — no parallel signal.

### Parameters

The boot machine is the canonical seam between **host-supplied static config** (a `/config` endpoint, build-time env vars, a host session restored from cookies) and **the running app's dynamic state**. The shape:

1. The `:configuring` state runs a Pattern-AsyncEffect instance — `:spawn`-spawn an `:http/get` (or read from a host singleton) that lands the config map into the boot machine's `:data`.
2. Subsequent states read values from `:data :config` and thread them into the next phase's spawn-spec `:data` fn (mechanism 2 in [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary)) or into a dispatched event payload (mechanism 1) for machines outside the boot hierarchy.
3. The running app then carries the same values forward — they live in `app-db` once and flow to readers via subs, or to spawned/dispatched machines via the same two mechanisms.

This is mechanism 3 in the canonical menu — *boot reads host config; threads via 1 or 2*. The boot machine is the only place that reads host globals; nothing downstream reaches into a global from inside an action body.

For the full mechanism menu see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

## Standard cross-cutting rules

### SSR composition

Server-side init via `:rf/server-init` (per [011 §Routing and SSR](011-SSR.md#routing-and-ssr) and [011 §Authentication / sessions](011-SSR.md#authentication--sessions)) is the server-side equivalent of boot. The handoff:

- **Server-side** runs the boot phases that are server-meaningful: config, session resolution from the request, route resolution, server-side data fetches. Phases that are client-only (`:hydrating` from `localStorage`, real-time service connections) are skipped — `:platforms #{:client}` on the relevant fxs causes the server's fx resolver to no-op them (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)).
- **Client-side**, after `:rf/hydrate` seeds the frame-state container from the server payload, the boot machine starts in a state that reflects what the server already accomplished. The recommended convention: the server's last act is to write `[:rf.runtime/machines :snapshots :app/boot :state] = :hydrating` (or `:routing`) into the hydrated **runtime-db**; the client's boot machine reads its initial state from the snapshot per [005 §Restore semantics](005-StateMachines.md) and resumes from there.

The two boots compose cleanly because the boot state machine's snapshot is a runtime-db slice — and hydration carries both partitions (app-db and runtime-db) of the frame-state container through the same channel.

### Hot-reload — boot does not re-run

In dev, hot-reload re-evaluates `reg-event` forms; surgical `reg-frame` re-registration preserves the frame-state container — both the app-db and runtime-db partitions (per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update)). The boot machine's snapshot (in runtime-db) survives; its `:state` is `:ready` (or whichever terminal state it reached); the next dispatch routes via the new handler bodies but does not re-enter `:configuring`.

This matches the locked rule: boot is **one-shot per app load**. Re-running is opt-in via `reset-frame!` (which re-dispatches the recorded `:initial-events`) or an explicit `[:app/boot [:rf.machine/start]]` re-entry event.

### Re-boot semantics

Some flows want explicit re-boot — session expired, the user logged out and back in, a "switch account" action. The pattern: dispatch a re-entry event the boot machine handles from any state, transitioning back to `:configuring`:

```clojure
;; Inside the machine spec, an :on slot at every state (or a wildcard root :on):
:auth.session/expired {:target :authenticating}
```

Re-boot is rare; it is not the default. Most apps boot once per page load and re-load the page on session expiry.

### Boot vs initial-route resolution

Route resolution is **part of boot** — state `:routing` runs `:rf.route/handle-url-change`, the route's `:on-match` may dispatch further loads, and the machine commits to `:ready` only after the route slice is settled. This keeps "the URL determined what loaded" inside the boot trace, where it is inspectable.

Routes that depend on auth (a "must-be-logged-in" route) work because `:authenticating` has already run by the time `:routing` evaluates. The route's matched-state can read auth from `app-db` and redirect or short-circuit as needed.

## Anti-patterns

- **Putting boot logic in a view's `:on-mount` lifecycle.** Ties boot to the view tree; not headless-testable; runs at the wrong time relative to hydration.
- **Booting via top-level side effects at namespace load.** No error handling; not deterministic; not visible to traces; no progress UI.
- **Mixing boot logic with running-app logic.** Boot states should be terminal-state-distinct; the boot machine commits to `:ready` and the running-app machinery takes over. Don't reuse boot's `:loading-profile` to mean "the user clicked refresh on their profile page".
- **Using a single giant `:app/init` event handler that does five things.** That's the chained-events form scaled past its usefulness; the boot graph is invisible. Pull it into a state machine.
- **Forgetting the SSR handoff.** A boot machine that always starts in `:configuring` re-fetches config the server already loaded. Make the initial state read from the hydrated snapshot.

## Composition with related patterns

- **[Pattern-AsyncEffect](Pattern-AsyncEffect.md)** — each boot phase is an instance of the generic async pattern. The boot machine sequences them.
- **[Pattern-StaleDetection](Pattern-StaleDetection.md)** — if the user navigates or reloads during boot, in-flight replies need stale-detection. Boot state machines naturally provide the epoch via state transitions; stale replies are ignored when the boot has advanced or completed.
- **[Pattern-RemoteData](Pattern-RemoteData.md)** — profile / config / feature-flag fetches are concrete instances; the boot machine drives them and reads their slices to decide success / failure.
- **[Pattern-Forms](Pattern-Forms.md)** — if boot includes a "set up your account" step, a form composes at that state via `:spawn` of a form-owning child machine.
- **[Pattern-WebSocket](Pattern-WebSocket.md)** — "establish real-time connection" is often a late boot phase; the connection machine is `:spawn`d from a boot state.

## Cross-references

- [002-Frames §`reg-frame` is atomic](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) — `:initial-events` is the canonical entry point for boot.
- [005-StateMachines.md](005-StateMachines.md) — the substrate; the boot machine uses standard hierarchical / `:spawn` / `:after` mechanics.
- [011-SSR.md](011-SSR.md) — server-side `:rf/server-init` and the hydration handoff.
- [012-Routing.md](012-Routing.md) — the `:routing` boot state delegates to the routing surface.
- [014-HTTPRequests §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry) — the retry-ownership rule the auth-machine worked example illustrates.
- Boot-as-state-machine study — the Dash8 / rf8 study that surfaced the hybrid retry-ownership boundary.
- [examples/core/login/core.cljs](../examples/core/login/core.cljs) — single-purpose flow machine; same shape, narrower scope.
