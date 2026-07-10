(ns re-frame.routing.history
  "Browser-history listener (popstate → url-owner frame) for re-frame2
  routing.

  Per Spec 012 §URL changes are events / §Multi-frame routing. The
  runtime does not own a window `popstate` listener directly — a
  `:url-bound? true` frame's lifecycle owns it: the
  listener installs the moment the URL-owning frame is created (or
  re-registered) and is removed when that frame is destroyed. Nothing
  imperative for an application to call. Browser changes dispatch
  synchronously to the explicitly declared URL owner; there is no default-
  frame fallback for an absent owner.

  `on-frame-registered!` resolves the URL owner AT (RE-)REGISTRATION TIME
  via `url-owner-frame-id` and, when the just-(re)registered frame IS the
  owner, (re)installs `install-url-listener!`. Each installed callback
  resolves its dispatch target again when it fires. The strategy itself is
  selected at install time, so changing URL ownership or strategy is a frame-
  registration operation. This is symmetric with `:rf.nav/push-url`: the
  same explicitly declared owner drives both directions.

  The listener is one of the URL-strategy consult points.
  `install-url-listener!` resolves the URL owner's `:url-strategy` (default
  `history-url-strategy`) and
  installs THAT strategy's browser listener — `popstate` for a history
  app, `hashchange` for a hash app — decoding each browser-driven change
  to a path-form URL before dispatching `:rf.route/handle-url-change`.

  `on-frame-registered!` installs from `re-frame.frame/reg-frame`'s
  POST-CREATE lifecycle hook (`:routing/on-frame-registered!`, fired AFTER
  the frame container exists and, on first registration, after
  `:initial-events` ran — the registrar's OWN `:frame` registration hook,
  `re-frame.routing.url-bound/check-url-bound-exclusivity!`, fires too
  early: `registrar/register!` runs BEFORE the frame container is created,
  so an install from there would dispatch the initial sync into a
  not-yet-live frame) and `re-frame.routing/release-routing-host-caches!`
  removes it from `frame/destroy-frame!`'s teardown when the destroyed
  frame was the URL owner. A losing duplicate `:url-bound? true`
  registration (`id` is NOT the resolved owner —
  `re-frame.routing.url-bound` already emitted
  `:rf.error/duplicate-url-binding`) never reaches `install-url-listener!`
  at all — it must never install a second, wrong listener.

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [re-frame.router :as router]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.strategy :as strategy]))

(defn current-url
  "Read the current browser URL as an app-relative string
  `pathname + search + hash`. Returns `\"/\"` on the JVM or when no
  `window.location` is available. This is a public query, not a listener-
  installation step.

  This is the PATH-FORM projection of a HISTORY-strategy app's URL (it is
  `history-url-strategy`'s `:decode`). A hash app decodes differently —
  the lifecycle listener uses the URL owner's strategy `:decode`; this
  function specifically exposes the history strategy's projection."
  []
  (strategy/history-decode))

#?(:cljs
   (defonce ^:private history-listener-atom
     ;; Holds the installed listener TEARDOWN thunk so a re-install
     ;; (`install-url-listener!` called twice — e.g. hot-reload) tears the
     ;; prior listener down rather than stacking listeners. The strategy's
     ;; `:install-listener!` returns the teardown; we hold it here.
     (atom nil)))

#?(:cljs
   (defn install-url-listener!
     "Install the current URL owner's browser URL-change
     listener, then sync the current URL into that frame's route slice at
     `[:rf.runtime/routing :current]`. This is the single strategy-aware
     engine `on-frame-registered!` drives automatically from the
     `:url-bound?` frame lifecycle — it replaces the hand-rolled `popstate` /
     `hashchange` wiring apps used to write themselves.

     The listener kind is chosen by the URL owner's `:url-strategy`
     (default `history-url-strategy`): a HISTORY app gets a `popstate`
     listener, a HASH app gets a `hashchange` listener. Each browser-driven
     change is DECODED to a PATH-FORM URL by the strategy's `:decode`, then
     dispatched as `:rf.route/handle-url-change` — so the route cascade stays
     path-form regardless of the address-bar form.

     Per Spec 012 §Multi-frame routing the dispatch is targeted at
     `(url-owner-frame-id)` resolved AT FIRE TIME, so it tracks whichever
     single frame currently owns the URL, including `:rf/default` only when it
     explicitly declares `:url-bound? true`. This is the
     inbound (browser → app) counterpart of the outbound `:rf.nav/push-url`
     gate: one owner, both directions. `url-owner-frame-id` returns nil when
     no frame declared `:url-bound? true`; the callback then skips the
     dispatch (installing a listener with no declared owner is a
     routing-config no-op, not a default-frame write).

     The STRATEGY (which browser event, which decode) is resolved from the
     current URL owner's `:url-strategy` at install time; the OWNER (dispatch
     target) is always re-resolved per fire, so Back/Forward keeps tracking
     ownership even if it changes after install.

     Idempotent: re-installing tears down the previously-installed listener
     (hot-reload / re-registration safe — `on-frame-registered!` calls this on
     every (re-)registration where `id` is the resolved owner). Returns
     `nil`. CLJS-only; the JVM half is a no-op (`:require`-able from `.cljc`
     boot code without a reader conditional).

     Both the initial sync and each change dispatch use `dispatch-sync!`: a
     `popstate` / `hashchange` event always fires on the browser's macrotask
     loop, never nested inside a re-frame drain, so the run-to-completion
     update is safe and the slice (and rendered body) restore synchronously
     within the same browser turn — no intermediate paint on the stale route.
     This mirrors the locked routing-history contract (the
     `popstate-via-window-listener-cljs` test dispatches `dispatch-sync`)."
     []
     (let [strat (strategy/url-strategy-for-frame-id (nav-fx/url-owner-frame-id))
           w (when (exists? js/window) js/window)
           decode (:decode strat)
           install-listener! (:install-listener! strat)
           ;; The dispatch targets the EXPLICITLY-declared URL owner resolved
           ;; AT FIRE TIME with the strategy-decoded
           ;; path-form URL. Skips when no owner is declared.
           dispatch-to-owner!
           (fn [path-url]
             (when-let [owner (nav-fx/url-owner-frame-id)]
               (router/dispatch-sync! [:rf.route/handle-url-change path-url]
                                      {:frame owner})))]
       (when w
         ;; Tear down any prior listener (hot-reload / re-registration safe)
         ;; before installing.
         (when-let [teardown @history-listener-atom]
           (try (teardown) (catch :default _ nil)))
         ;; The strategy installs its browser listener and returns a 0-arg
         ;; teardown thunk we hold for the next re-install / removal.
         (reset! history-listener-atom (install-listener! dispatch-to-owner!)))
       ;; Initial sync: hydrate the owner's slice from the current URL so
       ;; a deep link / reload lands on the right route on first paint.
       (dispatch-to-owner! (decode))
       nil)))

#?(:cljs
   (defn remove-url-listener!
     "Tear down the
     browser URL-change listener installed by `install-url-listener!`
     (whichever kind the strategy wired — `popstate` or `hashchange`), via
     the teardown thunk it returned. No-op when none is installed. Called by
     `re-frame.routing/release-routing-host-caches!` (the
     `:routing/on-frame-destroyed!` teardown hook) when the destroyed frame
     was the URL owner, AND published as the `:routing/reset-url-listener!`
     test-isolation hook (the shared `make-reset-runtime-fixture` reset-hook
     table calls it between tests — a raw `frame/frames` reset does not run
     `destroy-frame!`'s teardown chain, so a leftover installed listener
     would otherwise survive into the next test)."
     []
     (when-let [teardown @history-listener-atom]
       (try (teardown) (catch :default _ nil))
       (reset! history-listener-atom nil))
     nil))

#?(:cljs
   (defn on-frame-registered!
     "Post-(re-)registration frame-lifecycle hook. When the
     just-(re)registered frame `id` is the RESOLVED URL owner
     (`url-owner-frame-id`), (re)install its `:url-strategy` browser
     listener via `install-url-listener!` — idempotent, so a re-registration
     rewires cleanly (e.g. a hot-reloaded `:url-strategy` change, or a frame
     that newly opts into `:url-bound? true`).

     A losing duplicate `:url-bound? true` registration (`id` is NOT the
     resolved owner — `re-frame.routing.url-bound` already emitted
     `:rf.error/duplicate-url-binding`) or an ordinary non-url-bound frame is
     a no-op: this must never install a second, wrong listener (Spec 012
     §Multi-frame routing — the existing owner is unchanged).

     Published as the `:routing/on-frame-registered!` late-bind hook,
     invoked by `re-frame.frame/reg-frame` AFTER the frame container exists
     (and, on first registration, after `:initial-events` ran) — the
     registrar's OWN `:frame` registration hook
     (`re-frame.routing.url-bound/check-url-bound-exclusivity!`) fires too
     early for an install: `registrar/register!` runs BEFORE the frame
     container is created, so a listener installed from there would dispatch
     its initial sync into a not-yet-live frame."
     [id]
     (when (= id (nav-fx/url-owner-frame-id))
       (install-url-listener!))))
