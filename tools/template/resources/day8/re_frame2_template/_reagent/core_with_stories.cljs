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

   Story elision is opt-in. To remove the shell and registrations from an
   advanced release build, add this to the `:app` build in shadow-cljs.edn:

     :release {:compiler-options
               {:closure-defines {re-frame.story.config/enabled? false}}}

   Leave the define unset when Story is intentionally part of the release."
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.story           :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Requiring these namespaces installs their registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema     :as schema]
            [{{namespace}}.views      :as views]
            ;; Loading the stories namespace installs Story registrations.
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
  ;; `frame-root` ENSURES the app frame: it creates `:rf/default` on the
  ;; first app-branch mount (running the `:initial-events` seed), and REUSES
  ;; the live frame WITHOUT re-seeding on every later mount — counter state
  ;; survives a #/stories round-trip and hot reloads.
  (rdc/render @app-root
              [rf/frame-root {:id             :rf/default
                              :initial-events [[:counter/initialise]]}
               [views/counter-app]]))

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
;; Hot reload creates a new callback identity. Keep the installed callback in
;; a defonce atom so it can be removed before its replacement is registered.
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
  ;; init! installs the adapter but does not create the app frame — the
  ;; live-app branch's `rf/frame-root` (see `mount-app!`) ensures it at
  ;; mount. stories.cljs installs the canonical Story vocabulary on its
  ;; first registration.
  ;;
  ;; Schema attachment is frame-local; it names the app frame explicitly so
  ;; the frame-root `:initial-events` seed is validated from the first write.
  (schema/register-schema!)
  ;; Install the reload-safe route listener before applying the current hash.
  (install-hash-listener!)
  (on-hash-change!))
