(ns re-frame.story.ui.xray-embed-mount-fail-cleanup-dom-cljs-test
  "DOM-mount test: `panel-host-component`'s `do-mount!` must not leak an
  orphaned DOM node when the panel's `mount-fn` throws.

  `do-mount!` appends a child `<div>` to the host, then calls
  `(mount-fn container)`; only a successful call registers the container
  in `mounted-ref`, the one place `release!` looks. So the `catch` removes
  the container itself — otherwise every failed mount would leave one
  behind for the panel-host's lifetime.

  Only a real DOM mount runs this class-3 component's lifecycle hooks. Ns
  ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build mounts
  real DOM; `:node-test` also loads it, where the body self-gates on
  `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.xray-embed :as rf.story.ui.xray-embed]))

(def ^:private panel-host-component @#'rf.story.ui.xray-embed/panel-host-component)

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

;; `rdc/render` wraps each call's element in a fresh root component, so a
;; second `rdc/render` would remount the host and never reach
;; `:component-did-update`. The host is rendered ONCE, reading its
;; panel-id from a ratom, and the swap goes through that ratom.
(defn- render-host! [root pid]
  (react-dom/flushSync
    (fn [] (rdc/render root [(fn [] [panel-host-component @pid])]))))

(defn- host-in [mount-node]
  (.querySelector mount-node "[data-rf-xray-panel-host]"))

(deftest mount-fail-leaves-no-orphan-and-the-next-swap-still-mounts
  (testing "a throwing `mount-fn` leaves the host with NO child, and a later
            panel-id swap to a working `mount-fn` still mounts exactly one
            live container through `:component-did-update`"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [attempts (atom [])]
        (with-redefs [rf.story.ui.xray-embed/mount-fn-for
                      (fn [pid]
                        (case pid
                          :epoch  (fn [_container]
                                    (swap! attempts conj pid)
                                    (throw (js/Error. "boom")))
                          :app-db (fn [container]
                                    (let [marker (js/document.createElement "span")]
                                      (.setAttribute marker "data-test" "fake-panel-mounted")
                                      (.appendChild container marker)
                                      (fn unmount! [] nil)))
                          nil))]
          (let [mount-node (make-mount-node!)
                root       (rdc/create-root mount-node)
                pid        (r/atom :epoch)]
            (try
              (render-host! root pid)
              (let [host (host-in mount-node)]
                (is (= [:epoch] @attempts) "precondition: the mount was attempted")
                (is (zero? (.-length (.-children host)))
                    "no orphaned mount-container child survives a throwing mount-fn")
                (reset! pid :app-db)
                (r/flush)
                (is (identical? host (host-in mount-node))
                    "the host survived the swap, so it was an update, not a remount")
                (is (= 1 (.-length (.-children host)))
                    "the successful mount installs exactly one live child container")
                (is (some? (.querySelector host "[data-test=\"fake-panel-mounted\"]"))
                    "the working mount-fn's own marker is present inside it"))
              (finally
                (try (.unmount root) (catch :default _ nil))))))))))
