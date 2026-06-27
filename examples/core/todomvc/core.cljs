(ns todomvc.core
  "Where the app comes to life: install the adapter, mount the view, stand up
  the frame and seed it.

  Everything else leans on this wiring. `init!` picks the reactive substrate
  (here, Reagent). The render root is a `frame-provider {:id …}`: on first
  mount it creates the app frame, marks it `:url-bound?` so the frame owns the
  address bar, and seeds it once via `:initial-events`. A small hash listener
  turns a `#/active` URL change into a `:rf.route/handle-url-change` event —
  because in re-frame2 the URL is just an input, and navigation is just an
  event. See docs/guide/concepts/frames.md."
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
;; TodoMVC speaks hash URLs (#/, #/active, #/completed), but the router thinks
;; in path-strings. So this little adapter is the translator between them: drop
;; the '#', then dispatch :rf.route/handle-url-change with what's left. The
;; routes in events.cljs match the result.
;; See docs/routing/concepts.md#move-2-navigation-is-an-event.

(defn- hash->path
  "Turn window.location.hash (e.g. \"#/active\", \"#/completed\") into a route
  path. An empty hash means \"/\"."
  [hash]
  (let [stripped (-> hash
                     (str/replace #"^#" "")
                     (str/replace #"^/+" "/"))]
    (if (str/blank? stripped) "/" stripped)))

(defn- current-path []
  (hash->path (.. js/window -location -hash)))

;; The id of the app frame. The render root creates it with `:url-bound? true`,
;; which is how a frame declares "I own the URL".
;; See docs/routing/glossary.md (url-bound?).
(def app-frame :rf/default)

;; Hand each URL change to the frame that owns the URL. The dispatch names
;; `app-frame` outright via `{:frame app-frame}` — out here, outside the view
;; tree, there's no ambient frame for a bare `(rf/dispatch …)` to resolve
;; against.
;;
;; Why a named Var and not a lambda? So the install stays idempotent. `boot!`
;; removes then re-adds this exact Var, so a repeated `run` or a hot reload
;; never quietly stacks up duplicate `hashchange` listeners. (A path-based app
;; would call `rf/install-history-listener!` instead of listening on
;; `hashchange`, but the URL change still reaches its owner frame the same way.)
;; See docs/routing/concepts.md#the-browser-is-just-another-event-source.
(defn- on-hashchange [_]
  (rf/dispatch [:rf.route/handle-url-change (current-path)] {:frame app-frame}))

;; ---- Mount -----------------------------------------------------------------
;;
;; The React root lives in a `defonce` atom, created lazily on the first
;; `mount!`. The `defonce` is deliberate: React lets you call `create-root` on a
;; node exactly once, so we make one root and reuse it across every hot reload.
;; We also hold off creating it until `mount!` runs, rather than at ns-load,
;; because loading a namespace should never poke the DOM. And `mount!` is
;; `^:dev/after-load`, which is shadow's cue to re-run it on each reload so your
;; edited views actually re-render.
;;
;; The render root is a `frame-provider {:id app-frame …}`. First mount creates
;; the app frame and runs `:initial-events`; every reload after that reuses the
;; same frame and skips the seed, so your todos survive a code change.

(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                ;; The seed, in order: `[:todo/initialise]` folds the saved
                ;; todos (via the `:todo.storage/todos` coeffect in db.cljs)
                ;; into app-db, then `[:rf.route/handle-url-change …]` resolves
                ;; whatever URL we landed on.
                [rf/frame-provider {:id             app-frame
                                    :doc            "TodoMVC demo frame."
                                    :url-bound?     true
                                    :initial-events [[:todo/initialise]
                                                     [:rf.route/handle-url-change (current-path)]]}
                 [views/root-view]])))

;; ---- Boot ------------------------------------------------------------------
;;
;; `boot!` runs once at shadow's :init-fn, before the first render, and has just
;; two jobs: install the Reagent adapter, and wire up the `hashchange` listener.
;; It deliberately does NOT touch the frame — that's the render root's job, over
;; in `mount!`.

(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (doto js/window
    (.removeEventListener "hashchange" on-hashchange)
    (.addEventListener "hashchange" on-hashchange)))

(defn run []          ; shadow :init-fn — runs once, at page load
  (boot!)
  (mount!))
