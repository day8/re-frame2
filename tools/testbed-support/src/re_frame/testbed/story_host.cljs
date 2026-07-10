(ns re-frame.testbed.story-host
  "Hosts a live app and the Story shell on one `#app` node.

  The consumer owns frame/image boot and supplies the live root view. This
  namespace owns only the React-root handoff, hash routing, and optional Story
  source-root configuration. `defonce` handles keep those resources stable
  across hot reload."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.story :as story]
            [re-frame.testbed.config :as testbed-config]))

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

(defn- configure-story-source-root!
  "Configure Story's project root when the consumer declares a source subdir."
  [source-subdir]
  (when (and (string? source-subdir) (not (str/blank? source-subdir)))
    (story/configure!
     {:rf.story/project-root (testbed-config/resolve-source-root source-subdir)})))

(defn- mount-router!
  "Replace the hash listener and render the surface selected by the URL."
  [root-view]
  (reset! root-view* root-view)
  ;; The previous function may have a different pre-reload identity.
  (when-let [prev @hash-listener*]
    (.removeEventListener js/window "hashchange" prev))
  (.addEventListener js/window "hashchange" on-hash-change!)
  (reset! hash-listener* on-hash-change!)
  (on-hash-change!))

(defn mount-with-hash-routing!
  "Mount `root-view` for normal hashes and the Story shell for `#/stories...`.

  Call after the consumer has completed frame/image boot. An optional
  `:source-subdir` configures the open-in-editor root through
  `re-frame.testbed.config`; omit it when the consumer owns Story config.
  Repeated calls replace the listener and are safe across hot reload."
  ([root-view] (mount-with-hash-routing! root-view nil))
  ([root-view {:keys [source-subdir]}]
   (configure-story-source-root! source-subdir)
   (mount-router! root-view)))
