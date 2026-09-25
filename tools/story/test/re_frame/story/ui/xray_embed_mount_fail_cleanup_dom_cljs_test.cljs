(ns re-frame.story.ui.xray-embed-mount-fail-cleanup-dom-cljs-test
  "DOM-mount test: `panel-host-component`'s `do-mount!` must not leak an
  orphaned DOM node when the panel's `mount-fn` throws.

  ## The hazard

  `do-mount!` creates a child `<div>`, `.appendChild`s it onto the host,
  then calls `(mount-fn container)`. Only AFTER that call succeeds does it
  `reset!` `mounted-ref` to `{:unmount ... :container container}` — the
  ONLY place `release!` (called on the next panel-id swap, or on
  `:component-will-unmount`) looks to find something to tear down. If
  `mount-fn` throws, the already-appended container is never registered in
  `mounted-ref`, so `release!` never cleans it up. A `catch` that only
  logged would leave every failed mount's orphaned node behind (plus
  whatever partial DOM/listener side effects the throwing `mount-fn` made
  before throwing), accumulating for the panel-host's entire lifetime.

  ## The cleanup

  `container` is created outside the `try` so the `catch` can reach it and
  explicitly remove it from the DOM when `mount-fn` throws, regardless of
  how far the try body got.

  ## Why this needs a REAL DOM mount

  `panel-host-component`'s `do-mount!` calls real `js/document.createElement`
  / `.appendChild` / `.removeChild` — a hiccup-level test (see the sibling
  `xray-embed-e2e-cljs-test`) never invokes this class-3 component's
  lifecycle hooks at all, so it cannot observe the orphan.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it and mounts real DOM via `react-dom/client`; `:node-test`
  also loads it (regex matches the suffix too) where the body self-gates
  on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.xray-embed :as rf.story.ui.xray-embed]))

;; `panel-host-component` is `defn-` in xray_embed.cljs; the established
;; Story-test seam for reaching a private fn is the var-quote (e.g.
;; `viewport-toggle-app-db-dom-cljs-test`'s `framed-canvas`).
(def ^:private panel-host-component @#'rf.story.ui.xray-embed/panel-host-component)

;; ---- fixture ---------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- browser gate -----------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

;; ---- a failed mount leaves no orphan -----------------------------------

(deftest mount-fail-does-not-leak-orphan-container
  (testing "when `mount-fn` throws inside `do-mount!`, the appended child
            container is removed from the DOM rather than orphaned — the
            panel-host `<div>` ends up with NO children (an orphan would be
            one leaked `<div data-rf-xray-panel-mount>` per failed mount,
            accumulating for the panel-host's lifetime)"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (with-redefs [rf.story.ui.xray-embed/mount-fn-for
                    (fn [_pid] (fn [_container] (throw (js/Error. "boom"))))]
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn [] (rdc/render root [panel-host-component :epoch])))
            (let [host (.querySelector mount-node "[data-rf-xray-panel-host]")]
              (is (some? host) "panel-host div rendered")
              (is (zero? (.-length (.-children host)))
                  "no orphaned mount-container child survives a throwing mount-fn"))
            ;; A second failed mount — a panel-id swap re-triggers
            ;; `do-mount!` via `:component-did-update` — must not
            ;; accumulate a second orphan either.
            (react-dom/flushSync
              (fn [] (rdc/render root [panel-host-component :app-db])))
            (let [host (.querySelector mount-node "[data-rf-xray-panel-host]")]
              (is (zero? (.-length (.-children host)))
                  "repeated failed mounts still leave zero orphaned children"))
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))

(deftest mount-success-after-a-prior-failure-still-works
  (testing "after a failed mount, a subsequent panel-id swap to a
            WORKING mount-fn still mounts normally (the cleanup does not corrupt `mounted-ref` for the
            next swap). Both mount-fns are stubbed directly (rather than
            delegating to a real Xray panel mount-fn) so the test only
            exercises the panel-host's do-mount!/release! contract, not
            Xray's own mount internals."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (with-redefs [rf.story.ui.xray-embed/mount-fn-for
                    (fn [pid]
                      (case pid
                        :epoch  (fn [_container] (throw (js/Error. "boom")))
                        :app-db (fn [container]
                                  (let [marker (js/document.createElement "span")]
                                    (.setAttribute marker "data-test" "fake-panel-mounted")
                                    (.appendChild container marker)
                                    (fn unmount! [] nil)))
                        nil))]
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            ;; First mount fails.
            (react-dom/flushSync
              (fn [] (rdc/render root [panel-host-component :epoch])))
            (let [host (.querySelector mount-node "[data-rf-xray-panel-host]")]
              (is (zero? (.-length (.-children host)))
                  "precondition: the failed mount left no child"))
            ;; Swap to a working panel — should mount cleanly.
            (react-dom/flushSync
              (fn [] (rdc/render root [panel-host-component :app-db])))
            (let [host (.querySelector mount-node "[data-rf-xray-panel-host]")]
              (is (= 1 (.-length (.-children host)))
                  "the subsequent successful mount installs exactly one
                   live child container")
              (is (some? (.querySelector host "[data-test=\"fake-panel-mounted\"]"))
                  "the working mount-fn's own marker is present inside it"))
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
