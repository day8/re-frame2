(ns re-frame.router-carried-frame-cljs-test
  "Per rf2-9wa0lf (EP-0002 §Dispatch And Router / Reference Impl Plan §3)
  — the CLJS-side router frame-resolution tier: dispatch under a
  frame-provider (React-context) scope works.

  The JVM companion `re-frame.router-carried-frame-test` pins the
  dynamic-var scope / hold / override / absence matrix. This file pins
  the remaining EP §3 case that is platform-specific: when no dynamic
  `*current-frame*` binding and no explicit `:frame` opt are present, the
  router still resolves a frame from the enclosing frame-provider via the
  React-context tier of `frame/resolve-current-frame` (the
  `:adapter/current-frame` late-bind hook).

  A full React mount + `frame-provider` render is the root/view bead's
  (rf2-69r7ui) territory; here we exercise the router's consumption of
  the React-context tier directly by publishing a context-returning
  `:adapter/current-frame` hook (exactly what an adapter's frame-provider
  installs), then asserting (a) a bare dispatch resolves the provider
  frame, and (b) once the provider context is gone, the same bare
  dispatch raises :rf.error/no-frame-context (no :rf/default floor).

  Naming: `-cljs-test$` opts this file into the node-test build."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]))

;; The provider's "current React-context frame" — a test-controlled
;; stand-in for what a real `frame-provider` render publishes. nil means
;; no enclosing provider.
(def ^:private provider-frame (atom nil))

(defn reset-runtime [test-fn]
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (trace/clear-listeners!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (reset! provider-frame nil)
  ;; Publish a React-context tier that reports the enclosing provider
  ;; frame. `set-fn!` invalidates the sticky `get-fn-cached` slot, so
  ;; `resolve-current-frame` observes the live value each test sets. This
  ;; is the same hook an adapter's frame-provider drives during render.
  (late-bind/set-fn! :adapter/current-frame
                     (fn [] (or frame/*current-frame* @provider-frame)))
  (try
    (test-fn)
    (finally
      ;; Restore the adapter's real hook so sibling suites are unaffected.
      (reset! provider-frame nil)
      (substrate-adapter/dispose-adapter!)
      (substrate-adapter/install-adapter! plain-atom/adapter))))

(use-fixtures :each reset-runtime)

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(deftest dispatch-under-frame-provider-works
  (testing "with no dynamic binding and no explicit frame, the router
            resolves the enclosing frame-provider (React-context) frame"
    (rf/reg-frame :app/provided {:doc "frame the provider supplies"})
    (rf/reg-event :app/inc {:frame :app/provided}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; Simulate being inside a `frame-provider-existing {:frame :app/provided}`
    ;; render: the React-context tier reports the frame, the dynamic var
    ;; is unbound.
    (reset! provider-frame :app/provided)
    (binding [frame/*current-frame* nil]
      (rf/dispatch-sync [:app/inc]))
    (is (= 1 (:n (rf/app-db-value :app/provided)))
        "the dispatch resolved the provider frame and ran the handler")))

(deftest bare-dispatch-without-provider-fails
  (testing "once the provider context is gone (no dynamic binding, no
            provider, no explicit frame) the same bare dispatch raises
            :rf.error/no-frame-context — there is no :rf/default floor"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::no-provider)]
      (reset! provider-frame nil)
      (binding [frame/*current-frame* nil]
        (let [ex (try (rf/dispatch-sync [:app/noop]) nil
                      (catch :default e e))]
          (is (some? ex) "bare dispatch with no provider raised")
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
              "the throw carries :rf.error/no-frame-context")))
      (rf/unregister-listener! :trace ::no-provider)
      (is (empty? (filter #(= :rf.event/dispatched (:operation %)) @recorded))
          "no enqueue — the absence is caught before the registry lookup"))))

(deftest provider-frame-overridden-by-explicit-opt
  (testing "an explicit `{:frame …}` opt still wins over the enclosing
            provider frame (override beats scope)"
    (rf/reg-frame :app/provided {:doc "provider frame"})
    (rf/reg-frame :app/explicit {:doc "explicit override target"})
    (rf/reg-event :app/inc {:frame :app/explicit}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (reset! provider-frame :app/provided)
    (binding [frame/*current-frame* nil]
      (rf/dispatch-sync [:app/inc] {:frame :app/explicit}))
    (is (= 1 (:n (rf/app-db-value :app/explicit)))
        "the explicit override landed, not the provider frame")
    (is (nil? (:n (rf/app-db-value :app/provided)))
        "the provider frame was untouched")))
