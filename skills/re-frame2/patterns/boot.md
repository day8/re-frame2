# Pattern — Boot

Application boot as a chained-async sequence — the canonical state-machine shape for "read config → authenticate → load profile → hydrate → resolve route → ready".

Boot composes two **managed external effect** surfaces — state-machine `:spawn` (per-phase actors) and `:rf.http/managed` (per-phase fetches) — so the boot machine reasons about per-phase failure uniformly (umbrella: [`managed-http.md`](managed-http.md)). This leaf names how to *sequence* the phases.

> **Worked example:** `examples/patterns/boot/` ships the canonical machine — `:configuring → :loading-deps → :hydrating → :ready` with one `:spawn`'d loader and a fan-out `:spawn-all` parallel-load step.

## When to load

Reach for it whenever the app needs more than a one-step bootstrap — sequential dependencies between phases, per-phase failure semantics, visible "Loading profile…" progress, per-phase retry, or SSR handoff. A boot graph scattered across N event handlers is invisible; a state machine names the sequence.

For trivial boots (≤3 steps, no error states, no progress UI), use the chained-events form — see *§Simple form*.

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| `reg-frame` `:initial-events` | Atomic entry point — fires `[:app/boot [:rf.machine/start]]` exactly once per frame creation; survives hot-reload. |
| `:spawn` per phase | Each phase spawns its async work (`:rf.http/managed` or a domain child like `:auth/restore-session`) and transitions on `:succeeded` / `:failed`. |
| Consolidated `:entry` action | Per Spec 005, `:entry` is one fn or one registered id — never a vector. To update `:data` AND dispatch, write one action returning `{:data ..., :fx ...}`. |
| `:after` (numeric delay) | Retry-with-backoff between failed phase and re-attempt. |
| Machine snapshot in runtime-db | The boot machine's snapshot lives in the runtime-db partition at `[:rf.runtime/machines :snapshots :app/boot]`; Boot UI reads `:state` and `:data :phase` via the framework `:rf/machine` sub — one writer, one signal. |
| `:rf/server-init` + `:rf/hydrate` (SSR) | Server completes server-meaningful phases; client reads initial state from hydrated snapshot and resumes. |

## Canonical declaration (state-machine form)

`make-machine-handler` lives on `re-frame.machines` (`(:require [re-frame.machines :as machines])`) — it is not on the `re-frame.core` façade. The `reg-machine` / `defmachine` registration macros stay on the `rf/` façade.

```clojure
(rf/reg-event :app/boot
  (machines/make-machine-handler
    {:initial :configuring
     :data    {:phase :configuring :config nil :user nil :error nil :phase-attempt 0}

     :guards
     {:under-retry-limit? (fn [{:keys [data]}] (< (:phase-attempt data) 3))}

     :actions
     {:record-config (fn [{data :data [_ c] :event}] {:data (assoc data :config c)})
      :record-user   (fn [{data :data [_ u] :event}] {:data (assoc data :user u)})
      :record-error  (fn [{data :data [_ e] :event}] {:data (assoc data :error e)})
      :bump-attempt  (fn [{:keys [data]}]            {:data (update data :phase-attempt inc)})

      ;; CONSOLIDATED :entry — :set-phase + :resolve-initial-route in one fn.
      ;; :entry slots accept one fn / id, never a vector; compose by returning
      ;; both :data and :fx from one action.
      :enter-routing
      (fn [{:keys [data]}]
        {:data (assoc data :phase :routing :phase-attempt 0)
         :fx   [[:dispatch [:rf.route/handle-url-change (.. js/window -location -href)]]]})

      :phase-configuring (fn [{d :data}] {:data (assoc d :phase :configuring :phase-attempt 0)})
      :phase-auth        (fn [{d :data}] {:data (assoc d :phase :authenticating)})
      :phase-profile     (fn [{d :data}] {:data (assoc d :phase :loading-profile)})
      :phase-hydrate     (fn [{d :data}] {:data (assoc d :phase :hydrating)})}

     :states
     {:configuring
      {:entry  :phase-configuring
       :spawn {:machine-id :rf.http/managed
                :data       {:request {:method :get :url "/config"} :decode :json}}
       :on     {:succeeded {:target :authenticating :action :record-config}
                :failed    {:target :fatal-error    :action :record-error}}}

      :authenticating
      {:entry  :phase-auth
       :spawn {:machine-id :auth/restore-session
                ;; :spawn :data fn takes ONE context-map arg {:keys [snapshot event]};
                ;; the parent's data lives at (:data snapshot), not the top-level arg.
                :data       (fn [{:keys [snapshot]}] {:auth-url (-> snapshot :data :config :auth-url)})}
       :on     {:succeeded {:target :loading-profile}
                :failed    [{:guard :under-retry-limit? :target :retrying-auth :action :bump-attempt}
                            {:target :auth-failed :action :record-error}]}}

      :retrying-auth {:after {2000 {:target :authenticating}}}

      :loading-profile
      {:entry  :phase-profile
       :spawn {:machine-id :rf.http/managed
                :data       (fn [{:keys [snapshot]}]
                              {:request {:method :get :url (-> snapshot :data :config :profile-url)}
                               :decode  :json})}
       :on     {:succeeded {:target :hydrating :action :record-user}
                :failed    {:target :profile-failed :action :record-error}}}

      :hydrating {:entry :phase-hydrate :on {:hydrate/done {:target :routing}}}
      :routing   {:entry :enter-routing :on {:rf.route/resolved {:target :ready}}}

      :ready          {:meta {:terminal? true}}
      :auth-failed    {:meta {:terminal? true}}
      :profile-failed {:meta {:terminal? true}}
      :fatal-error    {:meta {:terminal? true}}}}))
```

The frame's `:initial-events` dispatch `[:app/boot [:rf.machine/start]]` — the reserved creation marker that births the machine directly into its `:initial` state (`:configuring`) and runs that state's entry-cascade. The marker is a pure init-kick (it is never fed into an `:on` map as a trigger), so the machine self-initialises and starts working at birth — there is no idle parking spot waiting on the marker.

## Simple form — chained events

For ≤3 phases, no error states, no progress UI:

```clojure
(rf/reg-event :app/init      (fn [_ _] {:fx [[:dispatch [:config/load]]]}))
(rf/reg-event :config/load   (fn [_ _] {:fx [[:rf.http/managed
                                               {:request {:url "/config"} :decode :json
                                                :on-success [:config/loaded]
                                                :on-failure [:app/init-failed]}]]}))
(rf/reg-event :config/loaded (fn [{:keys [db]} [_ c]]
                               {:db (assoc db :config c)
                                :fx [[:dispatch [:app/ready]]]}))
(rf/reg-event :app/ready     (fn [{:keys [db]} _] {:db (assoc db :app/booted? true)}))
```

Each link is a Pattern-AsyncEffect interaction. Past three links, scatter wins; switch to the machine.

## Variations

**The retry-ownership boundary.** Mixed transport-vs-semantic retry — e.g. auth machine handling 401-then-refresh-then-retry — sits at the boundary between `:rf.http/managed :retry` (transport: backoff + attempt count) and machine transitions (semantic: refresh-then-loop-back-to `:loading-me`). Both compose. See `patterns/managed-http.md`.

**Boot UI — single signal.** The snapshot lives in the **runtime-db** partition, so derive from the framework `:rf/machine` sub (which reads runtime-db) rather than reaching into a partition layer-1 subs don't see:

```clojure
(rf/reg-sub :app.boot/phase :<- [:rf/machine :app/boot] (fn [snap _] (get-in snap [:data :phase])))
(rf/reg-sub :app.boot/state :<- [:rf/machine :app/boot] (fn [snap _] (:state snap)))
```

No parallel `:loading?` flag — the machine's state IS the UI signal.

**SSR handoff.** `:rf/server-init` runs server-meaningful phases; client-only phases (`:hydrating` from `localStorage`, `:ws/connect`) are skipped via `:platforms #{:client}`. The server's machine snapshot rides the hydration payload's serializable runtime-db projection (`[:rf.runtime/machines :snapshots :app/boot :state] = :hydrating` or `:routing`); the client installs it as part of the frame-state on `:rf/hydrate` and resumes per Spec 005. No double-fetch of `/config`.

**Re-boot.** Dispatch a wildcard parent event: `:auth.session/expired {:target :authenticating}`. Most apps reload the page on session expiry.

**Final-state handoff (`:final?` / `:on-done`).** When boot is `:spawn`'d by an outer coordinator (SSR shell, embedding host, test harness), mark `:ready` as `:final?` — parent receives `:on-done` synchronously, optional `:output-key` carrying e.g. the loaded `:user`. The standalone-singleton form (canonical above) must NOT use `:final?` — auto-destroy takes the snapshot and the `[:app.boot/*]` subs with it; keep `:ready` as ordinary terminal (`:meta {:terminal? true}`) when the app subscribes post-handoff. See `../references/state-machines/spawn.md` §Final states.

**Parameters — the canonical seam.** The boot machine is the ONLY place that reads host globals (`/config` endpoint, build-time env vars, restored session). Once captured into `:data :config`, downstream phases thread values forward via Pattern-AsyncEffect (event payload or spawn-spec `:data` fn). Downstream machines never reach into a global.

## Anti-patterns

- **Boot logic in a view's `:on-mount`.** Ties boot to the view tree; not headless-testable; runs at the wrong time relative to hydration.
- **Top-level `(do ...)` at namespace load.** No tracing, no error handling, no progress UI; hot-reload re-runs it.
- **One giant `:app/init` handler doing five things.** The sequence becomes invisible. Use the machine.
- **`:entry [:set-phase :resolve-route]`.** `:entry` is singular. Consolidate into one action returning `{:data ..., :fx ...}`.
- **Always starting in `:configuring` under SSR.** Re-fetches what the server already loaded. Read initial state from the hydrated snapshot.
- **Mixing boot with running-app logic.** Boot states should be terminal-distinct (`:ready` is the handoff). Don't reuse `:loading-profile` for "user clicked refresh on profile page".

## Worked example

`examples/patterns/boot/` — canonical Pattern-Boot. `:app/boot` cycles `:configuring → :loading-deps → :hydrating → :ready` (`:failed` terminal sibling). `:configuring` `:spawn`s one `:boot/loader` for `/config`; `:loading-deps` fans out three parallel loaders via `:spawn-all`; `:hydrating` applies staged payloads via one `:enter-hydrating` action and self-transitions to `:ready`. See `examples/patterns/boot/boot.cljs` + `schema.cljs`. A narrower single-purpose-flow instance lives at `examples/core/login/core.cljs`.

## Pointers

SKILL-REDIRECT.md → *Pattern — Boot* (auth-machine retry-ownership, SSR handoff), *EP — Frames (002)* (`:initial-events`), *EP — State machines (005)* (substrate), *EP — SSR (011)*, *EP — HTTP requests (014)*. `:final?` / `:on-done` / `:output-key` → `../references/state-machines/spawn.md` §Final states. Retry-ownership boundary → `patterns/managed-http.md`.

---

*Derived from `examples/patterns/boot/boot.cljs` and Pattern-Boot in the spec @ main `89bd9c3`.*
