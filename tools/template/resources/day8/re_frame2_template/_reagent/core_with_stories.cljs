(ns {{namespace}}.core
  "Entry point — hash-routed between the live counter app and the
   Story playground.

   Two surfaces share `#app`, one root at a time:

   - `#/`        → the live counter (`views/counter-app`).
   - `#/stories` → the Story shell (`story/mount-shell!`).

   We tear one root down before mounting the other so React owns the
   target DOM node exclusively. The live-app root lives in `app-root`
   below; the Story shell allocates and owns its own root internally
   via `rdc/create-root` inside `mount-shell!`.

   Per IMPL-SPEC §6.5 / Stage 8: under `:advanced` with
   `:closure-defines {re-frame.story.config/enabled? false}`, every
   `reg-*` form in `{{namespace}}.stories` elides to `nil` and
   `mount-shell!` short-circuits — the production bundle carries no
   Story body code.

   IMPORTANT — elision is OPT-IN, not automatic. `re-frame.story.config/
   enabled?` defaults to `true`, and `shadow-cljs.edn` does NOT set the
   closure-define for you. So a plain `npx shadow-cljs release app` SHIPS
   the Story shell, the `#/stories` route, and every registration to
   production. To elide Story from the release bundle, add the
   `:release` compiler-options block to `shadow-cljs.edn`'s `:app`
   build:

     :release {:compiler-options
               {:closure-defines {re-frame.story.config/enabled? false}}}

   (Leave it OUT — keep `enabled?` true in release — only when you want
   a release-flavoured Story build for visual-regression.)"
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.story           :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Side-effecting requires — register events / subs and
            ;; pull the views ns in so its `reg-view` forms register.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            ;; Frame-local schema registration — called from `init` under the
            ;; app's frame scope (see register-schema! below).
            [{{namespace}}.schema     :as schema]
            [{{namespace}}.views      :as views]
            ;; Loading the stories ns fires the Story `reg-*` calls.
            [{{namespace}}.stories]))

;; -- Live-app root --------------------------------------------------------

(defonce ^:private app-root (atom nil))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (js/document.getElementById "app")))))

(defn- tear-down-app-root! []
  (when-let [r @app-root]
    (try (rdc/unmount r) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-app! []
  (story/unmount-shell!)
  (ensure-app-root!)
  ;; EP-0002 carried-frame invariant: wrap the live-app render in a
  ;; `frame-provider` so the in-tree dispatch/subscribe in `counter-app`
  ;; resolve to the app's `:rf/default` frame (registered in `init`).
  (rdc/render @app-root [rf/frame-provider {:frame :rf/default} [views/counter-app]]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (js/document.getElementById "app")))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

;; -- hashchange listener (hot-reload-safe) --------------------------------
;;
;; `init` runs on first boot AND on every shadow hot-reload. The closure
;; identity of `on-hash-change!` changes on each rebuild, so a naive
;; `(.addEventListener … on-hash-change!)` in `init` would accumulate one
;; stale listener per rebuild — every subsequent route change would then
;; fire the mount switch N times (repeated React root teardown/recreate,
;; flicker, a growing listener leak). We hold the *installed* listener in
;; a `defonce` atom that survives reloads, and remove the previous one
;; before adding the new one — so exactly one hashchange listener is ever
;; wired, pointing at the current code.
(defonce ^:private hash-listener (atom nil))

(defn- install-hash-listener! []
  (when-let [prev @hash-listener]
    (.removeEventListener js/window "hashchange" prev))
  (.addEventListener js/window "hashchange" on-hash-change!)
  (reset! hash-listener on-hash-change!))

(defn ^:export init
  "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
   shadow's hot-reload pipeline re-invokes it on each rebuild."
  []
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002 carried-frame invariant (Spec 002 §Frame target resolution):
  ;; `init!` installs the adapter only — the runtime never synthesises a
  ;; frame from absence. Register `:rf/default` as the app frame, then run
  ;; frame-local boot work (schema attach + seed dispatch) inside its scope.
  ;; The live-app render wraps `counter-app` in a `frame-provider` (see
  ;; mount-app!).
  (rf/reg-frame :rf/default {})
  ;; The canonical Story vocabulary auto-installs on the first `reg-*`
  ;; call in `{{namespace}}.stories` (loaded via the :require above)
  ;; — no explicit `(story/install-canonical-vocabulary!)`
  ;; call needed. The hook is idempotent and survives a `clear-all!`
  ;; during dev experiments.
  ;; Seed app-db before the first render so the live-app branch sees a
  ;; populated frame.
  (rf/with-frame :rf/default
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise]))
  ;; Wire hash-change so reloading `#/stories` lands on the shell
  ;; without a manual click-through. `install-hash-listener!` removes any
  ;; listener a previous hot-reload installed before adding this one, so
  ;; reloads never accumulate stale listeners (defonce atom holds the
  ;; current one).
  (install-hash-listener!)
  (on-hash-change!))
