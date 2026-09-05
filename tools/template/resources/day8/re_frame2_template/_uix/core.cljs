(ns {{namespace}}.core
  "Entry point: installs the UIx adapter and mounts the app."
  (:require [uix.core             :refer [$]]
            [uix.dom              :as uix-dom]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as rf.adapter.uix]
            ;; Requiring these installs their registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views :as views]))

;; One React root for the life of the page: React must not get a second
;; `create-root` for a live DOM node, and a hot reload has to render into
;; the root that already owns #app.
(defonce ^:private react-root (atom nil))

(def app-frame :rf/default)

;; `mount!` is the ^:dev/after-load hook. shadow-cljs calls it after every
;; successful hot reload — it does NOT re-run `init` — so this is what
;; re-renders your edited views. `frame-root` creates the app frame the
;; first time, running `:initial-events` synchronously so the first render
;; sees the seeded app-db, and reuses the live frame without re-seeding on
;; every later render: a reload leaves app-db exactly as you left it.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (uix-dom/create-root el)))
    (uix-dom/render-root
      ($ rf.adapter.uix/frame-root {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
         ($ views/counter-app))
      @react-root)))

;; Called ONCE by shadow-cljs (:init-fn in shadow-cljs.edn) when the bundle
;; loads. `init!` installs the adapter; it does not create a frame — the
;; `frame-root` element in `mount!` does.
(defn ^:export init []
  (rf/init! rf.adapter.uix/adapter)
  (mount!))
