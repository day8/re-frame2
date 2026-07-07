(ns re-frame.story.ui.xray-embed-mount-fail-cleanup-dom-cljs-test
  "DOM-mount regression for rf2-cmjly3 finding 5: `panel-host-component`'s
  `do-mount!` used to leak an orphaned DOM node whenever the panel's
  `mount-fn` threw.

  ## The bug

  `do-mount!` created a child `<div>`, `.appendChild`'d it onto the host,
  then called `(mount-fn container)`. Only AFTER that call succeeded did it
  `reset!` `mounted-ref` to `{:unmount ... :container container}` — the
  ONLY place `release!` (called on the next panel-id swap, or on
  `:component-will-unmount`) looks to find something to tear down. If
  `mount-fn` threw, the `catch` only logged a warning; the already-appended
  container was never removed from the DOM and never registered in
  `mounted-ref`, so it was never cleaned up — every failed mount left
  behind an orphaned node (plus whatever partial DOM/listener side effects
  the throwing `mount-fn` made before throwing), accumulating for the
  panel-host's entire lifetime.

  ## The fix

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
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.story :as story]
            [re-frame.story.ui.xray-embed :as xray-embed]))

;; `panel-host-component` is `defn-` in xray_embed.cljs; the established
;; Story-test seam for reaching a private fn is the var-quote (e.g.
;; `viewport-toggle-app-db-dom-cljs-test`'s `framed-canvas`).
(def ^:private panel-host-component @#'xray-embed/panel-host-component)

;; ---- fixture ---------------------------------------------------------------

(defn- reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! reagent-adapter/adapter)
       (catch :default _ nil))
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- browser gate -----------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

;; ---- the regression ---------------------------------------------------

(deftest mount-fail-does-not-leak-orphan-container
  (testing "rf2-cmjly3 finding 5: when `mount-fn` throws inside
            `do-mount!`, the appended child container is removed from the
            DOM rather than orphaned — the panel-host `<div>` ends up with
            NO children (pre-fix: one leaked `<div
            data-rf-xray-panel-mount>` per failed mount, accumulating for
            the panel-host's lifetime)"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (with-redefs [xray-embed/mount-fn-for
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
  (testing "rf2-cmjly3 finding 5 — no regression: after a failed mount, a
            subsequent panel-id swap to a WORKING mount-fn still mounts
            normally (the cleanup does not corrupt `mounted-ref` for the
            next swap). Both mount-fns are stubbed directly (rather than
            delegating to a real Xray panel mount-fn) so the test only
            exercises the panel-host's do-mount!/release! contract, not
            Xray's own mount internals."
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (with-redefs [xray-embed/mount-fn-for
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
