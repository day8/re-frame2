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
(rf/reg-event-fx :app/init
  (fn [_ _]
    {:fx [[:dispatch [:config/load]]]}))

(rf/reg-event-fx :config/load
  (fn [_ _]
    {:fx [[:http {:url        "/config"
                  :on-success [:config/loaded]
                  :on-error   [:app/init-failed]}]]}))

(rf/reg-event-fx :config/loaded
  (fn [{:keys [db]} [_ config]]
    {:db (assoc db :config config)
     :fx [[:dispatch [:app/ready]]]}))

(rf/reg-event-db :app/ready
  (fn [db _] (assoc db :app/booted? true)))
```

Each step is a Pattern-AsyncEffect interaction. The frame's `:on-create` fires `:app/init` (per [002 §`reg-frame` is atomic](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)), the chain runs to completion, the UI renders.

Use this form when the boot graph is **3 steps or fewer**, has **no error states**, and the UI does **not** show per-phase progress.

## The state-machine canonical form

Once the boot graph has more than a few steps, error states, retries, or visible progress, the chained-events form scatters boot logic across N unrelated event handlers — invisible as a sequence. The canonical form is **a single state machine that owns the boot sequence**.

The boot machine composes the locked machine substrate: hierarchical states for grouping phases, machine-scoped `:guards` / `:actions` (per [005 §Registration — the machine IS the event handler](005-StateMachines.md)), `:invoke` for spawning each phase's async work (per [005 §Declarative `:invoke`](005-StateMachines.md#declarative-invoke-sugar-over-spawn)), `:after` for retry backoff (per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions)) — no new substrate.

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

Each phase uses `:invoke` to spawn the async work; transitions on success or failure; entry actions update the progress UI; the whole sequence is one inspectable, testable, traceable state machine.

### Worked example — six-state boot

```clojure
(rf/reg-event-fx :app/boot
  {:doc "Application boot: config → auth → profile → hydrate → route → ready."}
  (rf/create-machine-handler
    {:initial :configuring
     :data    {:config nil :user nil :error nil :phase-attempt 0}

     :guards
     {:has-session-token?
      (fn [_ _] (some? (.getItem js/localStorage "auth/token")))

      :under-retry-limit?
      (fn [data _] (< (:phase-attempt data) 3))}

     :actions
     {:set-phase
      ;; Update visible-progress slice in :data so the boot UI can render.
      (fn [data [_ phase]]
        {:data (assoc data :phase phase :phase-attempt 0)})

      :record-config
      (fn [data [_ config]]
        {:data (assoc data :config config)})

      :record-user
      (fn [data [_ user]]
        {:data (assoc data :user user)})

      :bump-attempt
      (fn [data _]
        {:data (update data :phase-attempt inc)})

      :record-error
      (fn [data [_ err]]
        {:data (assoc data :error err)})

      :resolve-initial-route
      ;; Reads :route from URL and seeds the :route slice (per Spec 012).
      (fn [_ _]
        {:fx [[:dispatch [:rf.route/handle-url-change (.. js/window -location -href)]]]})}

     :states
     {;; :configuring runs an explicit Pattern-AsyncEffect instance: the boot
      ;; machine :invokes a child :http/get actor whose :data carries the URL.
      ;; The actor fetches /config and :dispatch-replys :succeeded with the body;
      ;; :record-config writes it into the boot machine's :data for downstream
      ;; states to thread into their own work.
      :configuring
      {:entry  :set-phase
       :invoke {:machine-id :http/get
                :data       {:url "/config"}
                :on-spawn   (fn [d id] (assoc d :pending id))}
       :on     {:succeeded {:target :authenticating
                            :action :record-config}
                :failed    {:target :fatal-error
                            :action :record-error}}}

      ;; Subsequent states read from :data :config and thread the values into
      ;; the next phase's :invoke spawn-spec or dispatched event.
      :authenticating
      {:entry  :set-phase
       :invoke {:machine-id :auth/restore-session
                ;; Spawn-spec :data fn — read the auth URL out of the boot
                ;; machine's :data :config (per Pattern-AsyncEffect mechanism 2).
                :data       (fn [{:keys [data]} _]
                              {:auth-url (-> data :config :auth-url)})
                :on-spawn   (fn [d id] (assoc d :pending id))}
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
       :invoke {:machine-id :http/get
                ;; Read the profile URL from the loaded config rather than
                ;; hardcoding it — the boot machine threads host config in
                ;; via the spawn-spec :data fn.
                :data       (fn [{:keys [data]} _]
                              {:url (-> data :config :profile-url)})
                :on-spawn   (fn [d id] (assoc d :pending id))}
       :on     {:succeeded {:target :hydrating
                            :action :record-user}
                :failed    {:target :profile-failed
                            :action :record-error}}}

      :hydrating
      {:entry :set-phase
       :on    {:hydrate/done {:target :routing}}}

      :routing
      {:entry [:set-phase :resolve-initial-route]
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

The frame's `:on-create` dispatches `[:app/boot [:rf/start]]` (or the equivalent per the host); the machine self-initialises (per [005 §Restore semantics]) and runs.

### Boot UI — reading progress from the snapshot

A view subscribes to the machine snapshot via `sub-machine` (per [005 §Reading the snapshot](005-StateMachines.md)) and renders the visible-progress slice carried in `:data`:

```clojure
(rf/reg-sub :app.boot/phase
  (fn sub-app-boot-phase [db _]
    (get-in db [:rf/machines :app/boot :data :phase])))

(rf/reg-sub :app.boot/error
  (fn sub-app-boot-error [db _]
    (get-in db [:rf/machines :app/boot :data :error])))

(rf/reg-sub :app.boot/state
  (fn sub-app-boot-state [db _]
    (get-in db [:rf/machines :app/boot :state])))

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
       [:button {:on-click #(dispatch [:app/boot [:rf/start]])} "Retry"]]

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

1. The `:configuring` state runs a Pattern-AsyncEffect instance — `:invoke`-spawn an `:http/get` (or read from a host singleton) that lands the config map into the boot machine's `:data`.
2. Subsequent states read values from `:data :config` and thread them into the next phase's spawn-spec `:data` fn (mechanism 2 in [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary)) or into a dispatched event payload (mechanism 1) for machines outside the boot hierarchy.
3. The running app then carries the same values forward — they live in `app-db` once and flow to readers via subs, or to spawned/dispatched machines via the same two mechanisms.

This is mechanism 3 in the canonical menu — *boot reads host config; threads via 1 or 2*. The boot machine is the only place that reads host globals; nothing downstream reaches into a global from inside an action body.

For the full mechanism menu see [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary).

## Standard cross-cutting rules

### SSR composition

Server-side init via `:rf/server-init` (per [011 §Routing and SSR](011-SSR.md#routing-and-ssr) and [011 §Authentication / sessions](011-SSR.md#authentication--sessions)) is the server-side equivalent of boot. The handoff:

- **Server-side** runs the boot phases that are server-meaningful: config, session resolution from the request, route resolution, server-side data fetches. Phases that are client-only (`:hydrating` from `localStorage`, real-time service connections) are skipped — `:platforms #{:client}` on the relevant fxs causes the server's fx resolver to no-op them (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)).
- **Client-side**, after `:rf/hydrate` seeds `app-db` from the server payload, the boot machine starts in a state that reflects what the server already accomplished. The recommended convention: the server's last act is to write `[:rf/machines :app/boot :state] = :hydrating` (or `:routing`) into the hydrated `app-db`; the client's boot machine reads its initial state from the snapshot per [005 §Restore semantics](005-StateMachines.md) and resumes from there.

The two boots compose cleanly because the boot state machine's snapshot is a `app-db` slice — the same hydration channel that carries every other slice.

### Hot-reload — boot does not re-run

In dev, hot-reload re-evaluates `reg-event-fx` forms; surgical `reg-frame` re-registration preserves `app-db` (per [002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update)). The boot machine's snapshot survives; its `:state` is `:ready` (or whichever terminal state it reached); the next dispatch routes via the new handler bodies but does not re-enter `:configuring`.

This matches the locked rule: boot is **one-shot per app load**. Re-running is opt-in via `reset-frame` (which does fire `:on-create` again) or an explicit `[:app/boot [:rf/start]]` re-entry event.

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
- **[Pattern-Forms](Pattern-Forms.md)** — if boot includes a "set up your account" step, a form composes at that state via `:invoke` of a form-owning child machine.
- **[Pattern-WebSocket](Pattern-WebSocket.md)** — "establish real-time connection" is often a late boot phase; the connection machine is `:invoke`d from a boot state.

## Cross-references

- [002-Frames §`reg-frame` is atomic](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) — `:on-create` is the canonical entry point for boot.
- [005-StateMachines.md](005-StateMachines.md) — the substrate; the boot machine uses standard hierarchical / `:invoke` / `:after` mechanics.
- [011-SSR.md](011-SSR.md) — server-side `:rf/server-init` and the hydration handoff.
- [012-Routing.md](012-Routing.md) — the `:routing` boot state delegates to the routing surface.
- [examples/login/core.cljs](../examples/login/core.cljs) — single-purpose flow machine; same shape, narrower scope.
