(ns todomvc.core
  "Entry point: install the adapter, register and seed the frame, mount the view.

  Demonstrates the boot wiring the other files lean on — `init!` (installs the
  Reagent adapter; it does NOT create a frame), `reg-frame` with `:url-bound?`
  (this frame owns the address bar), the recordable boot read that seeds app-db
  via `:rf.cofx`, and the hash → route adapter that turns a `#/active` URL change
  into a `:rf.route/handle-url-change` event. \"The URL is an input; navigation
  is an event.\""
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todomvc.db :as db]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

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

;; EP-0002: under the carried invariant the runtime
;; never synthesises a frame from absence, and URL ownership is an EXPLICIT
;; declaration — the app frame is registered with `:url-bound? true` so it owns
;; the URL (Spec 012 §Multi-frame routing), seeds under `with-frame`, and the
;; render is wrapped in a `frame-provider`.
(def app-frame :rf/default)

;; Named handler so the listener install is idempotent: repeated `run`
;; (shadow hot reload, or a co-required test host invoking run twice)
;; must not stack duplicate hashchange listeners, mirroring the
;; `when-not @react-root` mount guard below. We remove-then-add the same
;; Var so the registration is deduped even when the Var is redefined on
;; reload.
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

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {:doc "TodoMVC demo frame." :url-bound? true})
  (rf/with-frame app-frame
    ;; EP-0017: `:todo/initialise` DECLARES the
    ;; `:todo.storage/todos` RECORDABLE+PROVIDED cofx (db.cljs) via
    ;; `:rf.cofx/requires` and folds it into durable app-db. The host read
    ;; happens ONCE here at the boundary; its value rides the boot dispatch
    ;; token as the flat recordable coeffect, so it is recorded and replay /
    ;; epoch-restore re-presents the captured snapshot verbatim rather than
    ;; re-reading whatever localStorage holds then. Tests / replay supply the
    ;; value the same way — `{:rf.cofx {:todo.storage/todos …}}` — never by
    ;; re-registering an ambient supplier.
    (rf/dispatch-sync [:todo/initialise]
                      {:rf.cofx {:todo.storage/todos (db/read-todos-from-storage)}})
    (rf/dispatch-sync [:rf.route/handle-url-change (current-path)]))
  (.removeEventListener js/window "hashchange" on-hashchange)
  (.addEventListener js/window "hashchange" on-hashchange)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [views/root-view]])))
