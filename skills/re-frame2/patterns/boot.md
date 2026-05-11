# Pattern — Boot

Application boot as a chained-async sequence — the canonical state-machine shape for "read config → authenticate → load profile → hydrate → resolve route → ready".

> **Worked example:** `examples/reagent/boot/` ships the canonical Pattern-Boot machine — `:configuring → :loading-deps → :hydrating → :ready` with one `:invoke`'d loader machine and a fan-out `:invoke-all` parallel-load step. Read it alongside this leaf.

## When to use this pattern

Reach for it whenever the app needs more than a one-step bootstrap — sequential dependencies between phases, per-phase failure semantics, visible "Loading profile…" progress, per-phase retry, or an SSR handoff. The boot graph is invisible if scattered across N unrelated event handlers; a state machine names the sequence.

For trivial boots (≤3 steps, no error states, no progress UI), use the simple chained-events form instead — see *§Simple form* below. The canonical state-machine form is for everything past that threshold.

## The re-frame2 features this pattern uses

| Feature | Role here |
|---|---|
| `reg-frame` `:on-create` | Atomic entry point — fires `[:app/boot [:rf/start]]` exactly once per frame creation; survives hot-reload (boot does not re-run on namespace re-eval). |
| `:invoke` per phase | Each phase spawns the phase's async work (e.g. `:http/get` or a domain-specific child machine like `:auth/restore-session`) and transitions on `:succeeded` / `:failed`. |
| Consolidated `:entry` action | Per Spec 005 §State nodes, `:entry` takes one fn or one registered id — never a vector. Where a phase needs to update `:data` AND dispatch a follow-on, write one action that returns `{:data ..., :fx ...}`. |
| `:after` (numeric delay) | Retry-with-backoff between failed phase and re-attempt: `:retrying-auth {:after {2000 {:target :authenticating}}}`. |
| Machine snapshot in `app-db` | The boot machine's `:state` and `:data :phase` are read by the boot UI via subs — one writer, one signal, no parallel progress channel. |
| `:rf/server-init` + `:rf/hydrate` (SSR) | Server completes the server-meaningful phases; the client's machine reads its initial state from the hydrated snapshot and resumes from there. |

## Canonical declaration (state-machine form)

```clojure
(rf/reg-event-fx :app/boot
  (rf/create-machine-handler
    {:initial :configuring
     :data    {:phase :configuring :config nil :user nil
               :error nil :phase-attempt 0}

     :guards
     {:under-retry-limit? (fn [data _] (< (:phase-attempt data) 3))}

     :actions
     {:record-config (fn [data [_ config]] {:data (assoc data :config config)})
      :record-user   (fn [data [_ user]]   {:data (assoc data :user user)})
      :record-error  (fn [data [_ err]]    {:data (assoc data :error err)})
      :bump-attempt  (fn [data _]          {:data (update data :phase-attempt inc)})

      ;; CONSOLIDATED :entry — :set-phase + :resolve-initial-route in one fn.
      ;; :entry slots accept a single fn or registered id, never a vector;
      ;; compose by writing one action that returns both :data and :fx.
      :enter-routing
      (fn [data _]
        {:data (assoc data :phase :routing :phase-attempt 0)
         :fx   [[:dispatch [:rf.route/handle-url-change
                            (.. js/window -location -href)]]]})

      ;; A simpler shape — entry that only updates :data.
      :phase-configuring (fn [d _] {:data (assoc d :phase :configuring :phase-attempt 0)})
      :phase-auth        (fn [d _] {:data (assoc d :phase :authenticating)})
      :phase-profile     (fn [d _] {:data (assoc d :phase :loading-profile)})
      :phase-hydrate     (fn [d _] {:data (assoc d :phase :hydrating)})}

     :states
     {;; Each phase :invokes its async work; success / failure transition the
      ;; machine. The :data fn reads from the boot machine's :data (Pattern-
      ;; AsyncEffect mechanism 2) — no globals, no app-db reach.
      :configuring
      {:entry  :phase-configuring
       :invoke {:machine-id :rf.http/managed
                :data       {:request {:method :get :url "/config"} :decode :json}}
       :on     {:succeeded {:target :authenticating :action :record-config}
                :failed    {:target :fatal-error    :action :record-error}}}

      :authenticating
      {:entry  :phase-auth
       :invoke {:machine-id :auth/restore-session
                ;; spawn-spec :data fn — thread the auth URL out of :data :config
                :data       (fn [{:keys [data]} _]
                              {:auth-url (-> data :config :auth-url)})}
       :on     {:succeeded {:target :loading-profile}
                :failed    [{:guard  :under-retry-limit?
                             :target :retrying-auth
                             :action :bump-attempt}
                            {:target :auth-failed :action :record-error}]}}

      :retrying-auth {:after {2000 {:target :authenticating}}}

      :loading-profile
      {:entry  :phase-profile
       :invoke {:machine-id :rf.http/managed
                ;; URL read from loaded config, not hardcoded.
                :data       (fn [{:keys [data]} _]
                              {:request {:method :get :url (-> data :config :profile-url)}
                               :decode  :json})}
       :on     {:succeeded {:target :hydrating :action :record-user}
                :failed    {:target :profile-failed :action :record-error}}}

      :hydrating
      {:entry :phase-hydrate
       :on    {:hydrate/done {:target :routing}}}

      :routing
      {:entry :enter-routing
       :on    {:rf.route/resolved {:target :ready}}}

      :ready          {:meta {:terminal? true}}
      :auth-failed    {:meta {:terminal? true}}
      :profile-failed {:meta {:terminal? true}}
      :fatal-error    {:meta {:terminal? true}}}}))
```

The frame's `:on-create` dispatches `[:app/boot [:rf/start]]`; the machine self-initialises and runs.

## Simple form — chained events

For ≤3 phases, no error states, no progress UI:

```clojure
(rf/reg-event-fx :app/init      (fn [_ _]         {:fx [[:dispatch [:config/load]]]}))
(rf/reg-event-fx :config/load   (fn [_ _]         {:fx [[:rf.http/managed
                                                          {:request {:url "/config"} :decode :json
                                                           :on-success [:config/loaded]
                                                           :on-failure [:app/init-failed]}]]}))
(rf/reg-event-fx :config/loaded (fn [{:keys [db]} [_ config]]
                                  {:db (assoc db :config config)
                                   :fx [[:dispatch [:app/ready]]]}))
(rf/reg-event-db :app/ready     (fn [db _] (assoc db :app/booted? true)))
```

Each link is a Pattern-AsyncEffect interaction. Past three links, scatter wins; switch to the state-machine form.

## Variations

**The retry-ownership boundary.** Mixed transport-vs-semantic retry — e.g. an auth machine that handles 401-then-refresh-then-retry — sits at the boundary between `:rf.http/managed` `:retry` (transport: backoff + attempt count) and machine-level transitions (semantic: refresh-then-loop-back-to `:loading-me`). Both compose: the machine's `:invoke` spawns a managed request whose `:retry` slot handles 5xx; the failure event the machine sees has already been through the transport loop. See `patterns/managed-http.md` for the boundary details and worked example.

**Boot UI — single signal.** A view subscribes to `[:app.boot/state]` and `[:app.boot/phase]` and renders against the snapshot:

```clojure
(rf/reg-sub :app.boot/phase (fn [db _] (get-in db [:rf/machines :app/boot :data :phase])))
(rf/reg-sub :app.boot/state (fn [db _] (get-in db [:rf/machines :app/boot :state])))
```

No parallel `:loading?` flag; the machine's state IS the UI signal.

**SSR handoff.** Server-side `:rf/server-init` runs the server-meaningful phases (config, session-from-request, route resolve, server-side fetches). Client-only phases (`:hydrating` from `localStorage`, `:ws/connect`) are skipped via `:platforms #{:client}` on the relevant fxs. The server's last act is to write `[:rf/machines :app/boot :state] = :hydrating` (or `:routing`) into hydrated `app-db`; the client's machine restores into that state per Spec 005 restore semantics and resumes from there. No double-fetch of `/config`.

**Re-boot.** Rare but supported — dispatch an event the machine handles from any state (or via a wildcard parent `:on`): `:auth.session/expired {:target :authenticating}`. Most apps re-load the page on session expiry instead.

**Parameters — the canonical seam.** The boot machine is the ONLY place that reads host globals (`/config` endpoint, build-time env vars, restored session). Once captured into `:data :config`, subsequent phases thread values forward via Pattern-AsyncEffect mechanism 1 (event payload) or mechanism 2 (spawn-spec `:data` fn). Downstream machines never reach into a global from inside an action body.

## Anti-patterns

- **Boot logic in a view's `:on-mount`.** Ties boot to the view tree; not headless-testable; runs at the wrong time relative to hydration.
- **Boot via top-level `(do ...)` side effects at namespace load.** No tracing, no error handling, no progress UI, and hot-reload re-runs it.
- **One giant `:app/init` event handler doing five things.** That's the chained-events form scaled past usefulness; the sequence is invisible. Use the machine.
- **`:entry [:set-phase :resolve-route]`.** `:entry` is singular — one fn or one registered id, never a vector. Consolidate into one action that returns the full `{:data ..., :fx ...}` effects map.
- **Always starting in `:configuring` under SSR.** Re-fetches config the server already loaded. Make the machine's initial state read from the hydrated snapshot.
- **Mixing boot logic with running-app logic.** Boot states should be terminal-distinct (`:ready` is the handoff). Don't reuse `:loading-profile` to mean "the user clicked refresh on their profile page."

## Worked example

`examples/reagent/boot/` — the canonical Pattern-Boot worked example. The `:app/boot` machine cycles through `:configuring → :loading-deps → :hydrating → :ready` (with `:failed` as a terminal sibling). The `:configuring` state `:invoke`s a single reusable `:boot/loader` child for `/config`; the `:loading-deps` state fans out THREE parallel `:boot/loader` children via `:invoke-all` for routes / flags / user; the `:hydrating` state applies the four staged payloads to top-level app-db slices via one `:enter-hydrating` action and self-transitions to `:ready`. See `examples/reagent/boot/boot.cljs` for the spec and `examples/reagent/boot/schema.cljs` for the boundary schemas.

A narrower instance — single-purpose flow machine, same shape — also lives at `examples/reagent/login/core.cljs` (auth flow as a state machine using `create-machine-handler` and `:invoke`).

## Pointers

- Full pattern doc, including the auth-machine worked example for the retry-ownership boundary and SSR handoff details → SKILL-REDIRECT.md → *Pattern — Boot*.
- Frame `:on-create` semantics (atomic entry point) → SKILL-REDIRECT.md → *EP — Frames (002)*.
- State-machine substrate (`:invoke`, `:after`, restore semantics) → SKILL-REDIRECT.md → *EP — State machines (005)*.
- SSR `:rf/server-init` and hydration handoff → SKILL-REDIRECT.md → *EP — SSR (011)*.
- The retry-ownership boundary → `patterns/managed-http.md` + SKILL-REDIRECT.md → *EP — HTTP requests (014)*.

---

*Derived from `examples/reagent/boot/boot.cljs` (the canonical Pattern-Boot machine — `:configuring`/`:loading-deps`/`:hydrating`/`:ready`/`:failed`) and Pattern-Boot in the spec @ main `89bd9c3`. Re-verify if the boot example's state set or hydration shape changes.*
