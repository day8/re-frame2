(ns re-frame.testbed.story-host
  "Hosts a live app and the Story shell on one `#app` node.

  The consumer owns frame/image boot and supplies the live root view. This
  namespace owns only the React-root handoff and hash routing. `defonce`
  handles keep those resources stable across hot reload.

  Source-file resolution is NOT this namespace's job. The dev server's
  `POST /__rf-open-in-editor` endpoint
  (`re-frame.testbed.open-in-editor-server`) resolves a classpath-relative
  source coordinate against the live JVM source paths at request time, so a
  repository testbed needs no project-root configuration at all. A host that
  wants one anyway — an external or non-shadow host relying on the client's
  `editor://` URI fallback — calls `story/configure!` itself."
  (:require [reagent.dom.client :as rdc]
            [re-frame.story :as story]))

;; The live app and the Story shell each own their own React root on the
;; same node, one at a time. Each mount path tears down the other owner first.

(defonce ^:private app-root (atom nil))

;; The listener reads the current view instead of closing over a pre-reload one.
(defonce ^:private root-view* (atom nil))

;; Hot reload changes a top-level function's JavaScript identity. Retain the
;; exact installed handle so the next run can remove it before adding another.
(defonce ^:private hash-listener* (atom nil))

(defn- app-node []
  (js/document.getElementById "app"))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (app-node)))))

(defn- tear-down-app-root! []
  (when-let [r @app-root]
    (try (rdc/unmount r) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-app! []
  (story/unmount-shell!)
  (ensure-app-root!)
  (rdc/render @app-root [@root-view*]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (app-node)))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

(defn mount-with-hash-routing!
  "Mount `root-view` for normal hashes and the Story shell for `#/stories...`.

  Call after the consumer has completed frame/image boot. Repeated calls
  replace the listener and are safe across hot reload.

  Story configuration belongs to the consumer: this host neither reads nor
  writes `:rf.story/project-root`."
  [root-view]
  (reset! root-view* root-view)
  ;; The previous function may have a different pre-reload identity.
  (when-let [prev @hash-listener*]
    (.removeEventListener js/window "hashchange" prev))
  (.addEventListener js/window "hashchange" on-hash-change!)
  (reset! hash-listener* on-hash-change!)
  (on-hash-change!))
