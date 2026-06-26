(ns todomvc.core
  "Entry point: install the adapter, mount the view, ensure-and-seed the frame.

  Demonstrates the boot wiring the other files lean on — `init!` installs the
  Reagent adapter; the render root's `frame-provider {:id …}` creates the app
  frame on first mount, marks it `:url-bound?` so it owns the address bar, and
  seeds it once via `:initial-events`; and a hash → route adapter turns a
  `#/active` URL change into a `:rf.route/handle-url-change` event. \"The URL is
  an input; navigation is an event.\""
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

;; ---- hash → Spec 012 path adapter -----------------------------------------
;;
;; TodoMVC uses hash-based URLs (#/, #/active, #/completed); the Spec 012
;; runtime routes path-strings. This tiny host-adapter strips the '#' and
;; dispatches :rf.route/handle-url-change so the registered routes in
;; events.cljs match cleanly. Per Spec 012 §URL changes are events, the
;; runtime updates the runtime-db [:rf.runtime/routing :current] slice from
;; there.

(defn- hash->path
  "Convert window.location.hash (e.g. \"#/active\", \"#/completed\") to a
  Spec 012 path. An empty hash maps to \"/\"."
  [hash]
  (let [stripped (-> hash
                     (str/replace #"^#" "")
                     (str/replace #"^/+" "/"))]
    (if (str/blank? stripped) "/" stripped)))

(defn- current-path []
  (hash->path (.. js/window -location -hash)))

;; The id of the app frame. The render root's `frame-provider {:id app-frame …}`
;; creates it with `:url-bound? true`, which is the explicit declaration that
;; this frame owns the URL (Spec 012 §Multi-frame routing).
(def app-frame :rf/default)

;; A named handler keeps the listener install idempotent: `boot!` removes then
;; re-adds the same Var, so a repeated `run` (e.g. a co-required test host) or a
;; reload that redefines the Var never stacks duplicate `hashchange` listeners.
;;
;; The dispatch targets `app-frame` explicitly via `{:frame app-frame}` — that is
;; the frame that owns the URL. (A frameless `(rf/dispatch …)` would raise
;; `:rf.error/no-frame-context`.) This example is hash-based (`#/active`), so it
;; uses its own `hashchange` listener instead of the framework's popstate-driven
;; `rf/install-history-listener!`; both deliver the URL change to the owner frame
;; the same way (Spec 012 §popstate drives the URL-owner frame).
(defn- on-hashchange [_]
  (rf/dispatch [:rf.route/handle-url-change (current-path)] {:frame app-frame}))

;; ---- Mount -----------------------------------------------------------------
;;
;; The React root lives in a `defonce` atom, created lazily on the first
;; `mount!` rather than at ns-load. Two reasons: ns-load must have no DOM side
;; effects so co-required example namespaces don't race `create-root` onto the
;; shared `#app` (examples/TESTING.md §Example mount-isolation), and `defonce`
;; reuses the one root across hot reloads (React 18 rejects a second
;; `create-root` on a live node). `mount!` is `^:dev/after-load`, so shadow
;; re-runs it on every reload to re-render the edited views.
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
