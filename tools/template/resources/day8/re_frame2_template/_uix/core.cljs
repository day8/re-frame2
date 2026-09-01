(ns {{namespace}}.core
  "Entry point (UIx substrate). Boots the UIx adapter, seeds app-db, and
   mounts the root view."
  (:require [uix.core             :refer [$]]
            [uix.dom              :as uix-dom]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema :as schema]
            [{{namespace}}.views  :as views]))

;; Namespace load does no DOM work — `mount!` creates the root lazily, so this
;; namespace also loads cleanly in a test or Node host with no `js/document`.
;; `defonce` keeps ONE React root for the life of the page: React must not get a
;; second `create-root` for a live DOM node.
(defonce ^:private react-root (atom nil))

(def app-frame :rf/default)

;; `mount!` is browser setup: create the root once, then render the view tree
;; inside the frame-root. `^:dev/after-load` is shadow-cljs's cue to re-run it
;; after each successful hot reload, so your edited views re-render into the
;; same root and the same frame.
;;
;; THIS HOOK IS WHAT MAKES HOT RELOAD WORK. shadow's `:browser` target does NOT
;; re-run the module `:init-fn` after a reload — it loads the new code and calls
;; the `^:dev/after-load` hooks, and with none configured it says so in the
;; console ("reloading code but no :after-load hooks are configured!") and the
;; page keeps rendering the OLD view. See `docs/core/how-to/boot-and-mount-an-app.md`.
;;
;; `frame-root` ENSURES the app frame: it creates `:rf/default` the first time
;; (running the `:initial-events` seed synchronously, so the initial render sees
;; the seeded app-db), and REUSES the live frame WITHOUT re-seeding on every
;; later render — so a reload leaves your app-db exactly as you left it.
;;
;; The schema registration is here rather than in `init` for the same reason
;; the render is: registration is a fn call, not a load-time side effect, so
;; reloading schema.cljs re-evaluates `CounterDb` and re-registers nothing.
;; Running it here — the one path both boot and every reload take — is what
;; makes an edited schema validate the live frame instead of the boot-time
;; value. It replaces the entry for the same (frame, path) in place, and on
;; the first call it still runs BEFORE `frame-root` creates the frame, so the
;; `:initial-events` seed is validated from the very first write.
(defn ^:dev/after-load mount! []
  (schema/register-schema!)
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (uix-dom/create-root el)))
    (uix-dom/render-root
      ($ uix-adapter/frame-root {:id             app-frame
                                 :initial-events [[:counter/initialise]]}
         ($ views/counter-app))
      @react-root)))

(defn ^:export init
  "Called ONCE by shadow-cljs (see :init-fn in shadow-cljs.edn) when the
   bundle loads. Process setup only — `mount!` above owns the browser side
   and is what a hot reload re-runs."
  []
  ;; `init!` installs the adapter but does not create a frame — the
  ;; `frame-root` element in `mount!` ensures it.
  (rf/init! uix-adapter/adapter)
  ;; Schemas validate shape; they do not classify durable app-db egress.
  ;; Classify a path from the event that writes it by returning `:sensitive`
  ;; or `:large` alongside `:db`:
  ;;   (rf/reg-event :auth/init
  ;;     (fn [{:keys [db]} _]
  ;;       {:db        (assoc db :auth {})
  ;;        :sensitive [[:auth :token]]
  ;;        :large     [[:documents :upload]]}))
  ;; See the README's privacy section for HTTP carriers and response bodies.
  ;;
  ;; `mount!` attaches the app-db schema before it renders — see its comment
  ;; above for why that belongs on the reload path rather than here.
  (mount!))
