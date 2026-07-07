(ns todomvc.core
  "Where the app comes to life: install the adapter, mount the view, stand up
  the frame and seed it.

  Everything else leans on this wiring. `init!` picks the reactive substrate
  (here, Reagent). The render root is a `frame-provider {:id …}`: on first
  mount it creates the app frame, marks it `:url-bound?` so the frame owns the
  address bar, and declares `:url-strategy rf/hash-url-strategy` so the router
  speaks hash URLs (`#/active`) — because TodoMVC's URLs are hash-based. The
  frame is seeded once via `:initial-events`. In re-frame2 the URL is just an
  input and navigation is just an event; the router's hash strategy handles the
  `#` on both sides, so this app uses `route-link` / `rf.route/navigate` like
  every other example. See docs/core/frames.md."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

;; The id of the app frame. The render root creates it with `:url-bound? true`,
;; which is how a frame declares "I own the URL".
;; See docs/routing/glossary.md (url-bound?).
(def app-frame :rf/default)

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
;;
;; `:url-strategy rf/hash-url-strategy` is the one line that makes this a hash
;; app. It tells the router to encode `route-url` output as `#/active` at the
;; egress points (link hrefs, pushState) and decode `window.location.hash` back
;; to a path on the way in. `route-url` and `match-url` stay path-form; only the
;; browser address-bar shape changes.

(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    (rdc/render @react-root
                ;; The seed is just `[:todo/initialise]` — it folds the saved
                ;; todos (via the `:todo.storage/todos` coeffect in db.cljs)
                ;; into app-db. The initial URL sync is handled by
                ;; `install-url-listener!` in `boot!`, which reads the current
                ;; hash and dispatches `:rf.route/handle-url-change` for us.
                [rf/frame-provider {:id             app-frame
                                    :doc            "TodoMVC demo frame."
                                    :url-bound?     true
                                    :url-strategy   routing/hash-url-strategy
                                    :initial-events [[:todo/initialise]]}
                 [views/root-view]])))

;; ---- Boot ------------------------------------------------------------------
;;
;; `boot!` runs once at shadow's :init-fn, before the first render, and has just
;; two jobs: install the Reagent adapter, and wire up the URL-change listener.
;;
;; `install-url-listener!` is the framework's boot seam. Given the app's
;; strategy — here the hash strategy — it installs the matching browser
;; listener (`hashchange` for a hash app; `popstate` for a history app),
;; decodes each change to a path, and dispatches `:rf.route/handle-url-change`
;; to the URL-owning frame; the install also syncs the current URL into the
;; frame's route slice so a deep link or a refresh lands on the right route.
;; It's idempotent, so a repeated `run` or a hot reload never stacks up
;; duplicate listeners. No hand-rolled `hashchange` adapter — the strategy
;; absorbs it.
;;
;; We pass the strategy EXPLICITLY (the 1-arity) rather than let the seam read
;; it off the frame: the URL-owning frame is created by the `frame-provider`
;; during render, whose React effect may register it just after `mount!`
;; returns — so pinning the strategy here keeps the listener kind independent
;; of that timing.
;; See docs/routing/concepts.md#the-browser-is-just-another-event-source.

(defn- boot! []
  (rf/init! reagent-adapter/adapter)
  (mount!)
  (rf/install-url-listener! routing/hash-url-strategy))

(defn run []          ; shadow :init-fn — runs once, at page load
  (boot!))
