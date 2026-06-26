(ns todomvc.core
  "Entry point: install the adapter, register and seed the frame, mount the view.

  Demonstrates the boot wiring the other files lean on — `init!` (installs the
  Reagent adapter; it does NOT create a frame), `reg-frame` with `:url-bound?`
  (this frame owns the address bar) and `:initial-events` (the frame seeds itself
  at creation), and the hash → route adapter that turns a `#/active` URL change
  into a `:rf.route/handle-url-change` event. \"The URL is an input; navigation
  is an event.\""
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

;; EP-0002: under the carried invariant the runtime never synthesises a frame
;; from absence, and URL ownership is an EXPLICIT declaration — the app frame is
;; registered with `:url-bound? true` so it owns the URL (Spec 012 §Multi-frame
;; routing), and the render is wrapped in `frame-provider`.
(def app-frame :rf/default)

;; Named handler so the listener install is idempotent: a repeated `boot!`
;; (a co-required test host invoking `run` twice) must not stack duplicate
;; `hashchange` listeners. We remove-then-add the same Var so the registration
;; is deduped even when the Var is redefined on reload.
;;
;; EP-0002: the URL-change dispatch is targeted at the URL-owning `app-frame`
;; with an explicit `{:frame app-frame}` — NOT a frameless `(rf/dispatch …)`,
;; which would raise `:rf.error/no-frame-context`. This example is HASH-based
;; (`#/active`), so it keeps its own `hashchange` listener rather than the
;; framework's popstate-driven `rf/install-history-listener!`; targeting the
;; owner frame is the same contract that listener implements (Spec 012
;; §popstate drives the URL-owner frame).
(defn- on-hashchange [_]
  (rf/dispatch [:rf.route/handle-url-change (current-path)] {:frame app-frame}))

;; ---- Mount -----------------------------------------------------------------
;;
;; The React root is held in a defonce atom and materialised lazily inside
;; `mount!` (not at ns-load) per examples/TESTING.md §Example mount-isolation:
;; ns-load must produce no DOM side effects, so co-required example namespaces
;; don't race `create-root` onto the shared `#app`. `defonce` keeps the root
;; across hot reloads (React 18 rejects a second `create-root` on a live node),
;; and `mount!` is `^:dev/after-load` — shadow re-invokes it on every reload to
;; re-render the (possibly edited) views WITHOUT re-running `boot!`, so app-db
;; and the URL listener survive the reload.

(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:frame app-frame}
                 [views/root-view]])))

;; ---- Boot ------------------------------------------------------------------
;;
;; `boot!` runs once, at shadow's :init-fn. `init!` installs the adapter (it does
;; NOT create the frame); `reg-frame` registers the URL-owning app frame and
;; seeds it via `:initial-events`, fired synchronously into the freshly-created
;; frame: `[:todo/initialise]` folds the saved todos — supplied by the registered
;; `:todo.storage/todos` recordable coeffect (db.cljs) — into app-db, then
;; `[:rf.route/handle-url-change …]` resolves the initial URL. Seeding through
;; `:initial-events` is what removes the manual `with-frame` / `dispatch-sync`
;; dance and the dispatch-site coeffect plumbing.

(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame
    {:doc            "TodoMVC demo frame."
     :url-bound?     true
     :initial-events [[:todo/initialise]
                      [:rf.route/handle-url-change (current-path)]]})
  (doto js/window
    (.removeEventListener "hashchange" on-hashchange)
    (.addEventListener "hashchange" on-hashchange)))

(defn run []          ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
