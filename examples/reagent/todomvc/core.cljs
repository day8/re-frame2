(ns todomvc.core
  "Entry point: install the adapter, mount the view, ensure-and-seed the frame.

  Demonstrates the boot wiring the other files lean on — `init!` (installs the
  Reagent adapter; it does NOT create a frame), the `frame-provider {:id …}`
  ensure form (creates the URL-owning app frame on first mount, with `:url-bound?`
  so it owns the address bar, and seeds it once via `:initial-events`), and the
  hash → route adapter that turns a `#/active` URL change into a
  `:rf.route/handle-url-change` event. \"The URL is an input; navigation is an
  event.\""
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
;; from absence, and URL ownership is an EXPLICIT declaration — the render root's
;; `frame-provider {:id app-frame …}` ensures the app frame with `:url-bound?
;; true` so it owns the URL (Spec 012 §Multi-frame routing).
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
;; re-render the (possibly edited) views. The render root's `frame-provider
;; {:id app-frame …}` ENSURES the frame: it creates and seeds it once on first
;; mount, then REUSES it on reload WITHOUT re-seeding, so app-db survives the
;; reload (and the URL listener, installed in `boot!`, survives too).

(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :doc            "TodoMVC demo frame."
                                    :url-bound?     true
                                    :initial-events [[:todo/initialise]
                                                     [:rf.route/handle-url-change (current-path)]]}
                 [views/root-view]])))

;; ---- Boot ------------------------------------------------------------------
;;
;; `boot!` runs once, at shadow's :init-fn, BEFORE the first render. It installs
;; the adapter (`init!` does NOT create the frame) and installs the `hashchange`
;; listener. The frame itself is created and seeded by the render root's
;; `frame-provider {:id app-frame …}` ensure form (see `mount!`): its
;; `:initial-events` fire once into the freshly-created frame — `[:todo/initialise]`
;; folds the saved todos (supplied by the registered `:todo.storage/todos`
;; recordable coeffect in db.cljs) into app-db, then `[:rf.route/handle-url-change …]`
;; resolves the initial URL. Ensuring + seeding in that one spot removes the
;; manual `with-frame` / `dispatch-sync` dance and the dispatch-site coeffect
;; plumbing.

(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (doto js/window
    (.removeEventListener "hashchange" on-hashchange)
    (.addEventListener "hashchange" on-hashchange)))

(defn run []          ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
