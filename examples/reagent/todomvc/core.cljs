(ns todomvc.core
  "Entry point: install the adapter, mount the view, create and seed the frame.

  This is the boot wiring the other files lean on. `init!` installs the Reagent
  adapter. The render root is a `frame-provider {:id …}`: on first mount it
  creates the app frame, marks it `:url-bound?` so it owns the address bar, and
  seeds it once via `:initial-events`. A small hash listener turns a `#/active`
  URL change into a `:rf.route/handle-url-change` event. The URL is an input;
  navigation is an event."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

;; ---- hash -> path adapter -------------------------------------------------
;;
;; TodoMVC uses hash-based URLs (#/, #/active, #/completed). The router matches
;; path-strings, so this little adapter strips the '#' and dispatches
;; :rf.route/handle-url-change. The registered routes in events.cljs then match
;; the result. See docs/routing/concepts.md#move-2-navigation-is-an-event.

(defn- hash->path
  "Convert window.location.hash (e.g. \"#/active\", \"#/completed\") to a route
  path. An empty hash maps to \"/\"."
  [hash]
  (let [stripped (-> hash
                     (str/replace #"^#" "")
                     (str/replace #"^/+" "/"))]
    (if (str/blank? stripped) "/" stripped)))

(defn- current-path []
  (hash->path (.. js/window -location -hash)))

;; The id of the app frame. The render root creates it with `:url-bound? true`,
;; which declares that this frame owns the URL.
;; See docs/routing/glossary.md (url-bound?).
(def app-frame :rf/default)

;; Deliver each URL change to the frame that owns the URL. The dispatch names
;; `app-frame` explicitly via `{:frame app-frame}`; a frameless `(rf/dispatch …)`
;; has no frame to resolve against.
;;
;; Using a named Var keeps the listener install idempotent: `boot!` removes then
;; re-adds the same Var, so a repeated `run` or a reload never stacks duplicate
;; `hashchange` listeners. A hash-based app listens on `hashchange`; a path-based
;; app would call `rf/install-history-listener!` instead, and the URL change
;; reaches the owner frame the same way. See
;; docs/routing/concepts.md#the-browser-is-just-another-event-source.
(defn- on-hashchange [_]
  (rf/dispatch [:rf.route/handle-url-change (current-path)] {:frame app-frame}))

;; ---- Mount -----------------------------------------------------------------
;;
;; The React root lives in a `defonce` atom, created lazily on the first
;; `mount!`. The `defonce` reuses the one root across hot reloads, which React
;; requires (a second `create-root` on a live node is rejected). Creating it in
;; `mount!` rather than at ns-load keeps namespace load free of DOM side effects.
;; `mount!` is `^:dev/after-load`, so shadow re-runs it on every reload to
;; re-render the edited views.
;;
;; The render root is a `frame-provider {:id app-frame …}`. On the first mount it
;; creates the app frame and runs `:initial-events`; on reload it reuses the same
;; frame without re-seeding, so app-db survives.

(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                ;; `:initial-events` seed the new frame, in order:
                ;; `[:todo/initialise]` folds the saved todos (from the
                ;; `:todo.storage/todos` coeffect in db.cljs) into app-db, then
                ;; `[:rf.route/handle-url-change …]` resolves the initial URL.
                [rf/frame-provider {:id             app-frame
                                    :doc            "TodoMVC demo frame."
                                    :url-bound?     true
                                    :initial-events [[:todo/initialise]
                                                     [:rf.route/handle-url-change (current-path)]]}
                 [views/root-view]])))

;; ---- Boot ------------------------------------------------------------------
;;
;; `boot!` runs once at shadow's :init-fn, before the first render. It does two
;; things: installs the Reagent adapter and installs the `hashchange` listener.
;; The frame is created and seeded later, by the render root's `frame-provider
;; {:id app-frame …}` (see `mount!`).

(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (doto js/window
    (.removeEventListener "hashchange" on-hashchange)
    (.addEventListener "hashchange" on-hashchange)))

(defn run []          ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
