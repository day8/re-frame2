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
   Story body code. The `:release` shadow build inherits that elision
   automatically; flip the closure-define in `shadow-cljs.edn` if you
   need a release-flavoured Story build for visual-regression."
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.story           :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Side-effecting requires — register events / subs and
            ;; pull the views ns in so its `reg-view` forms register.
            [{{namespace}}.events]
            [{{namespace}}.subs]
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
  (rdc/render @app-root [views/counter-app]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (js/document.getElementById "app")))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

(defn ^:export init
  "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
   shadow's hot-reload pipeline re-invokes it on each rebuild."
  []
  (rf/init! reagent-adapter/adapter)
  ;; Re-fire the canonical Story vocabulary install on each hot-reload
  ;; — idempotent, and survives a `clear-all!` during dev experiments.
  (story/install-canonical-vocabulary!)
  ;; Seed app-db before the first render so the live-app branch sees a
  ;; populated frame.
  (rf/dispatch-sync [:counter/initialise])
  ;; Wire hash-change so reloading `#/stories` lands on the shell
  ;; without a manual click-through.
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
