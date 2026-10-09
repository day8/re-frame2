(ns re-frame.platform-gating-test
  "Per-frame platform gating (Spec 011 §Effect handling on the server). A frame's
  `:platform` config key overrides a per-host constant default (`:server` on the
  JVM), and `reg-fx` `:platforms` metadata gates each fx by its frame's platform.

  ## Posture split

  The default, the override and the gate itself (whether the fx body ran) are
  asserted in both postures. `:rf.fx/skipped-on-platform` is a dev-only trace,
  so its assertions sit inside a `(when rf.interop/debug-enabled? …)` arm."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  ;; `init!` seats no `:rf/default`; operations need a carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- register-browser-only-fx!
  "Register a `:platforms #{:client}` fx and the event that emits it. Returns
  the atom the fx body sets."
  []
  (let [fired? (atom false)]
    (rf/reg-fx :platform-gating-test/browser-only
      {:platforms #{:client}}
      (fn [_ _] (reset! fired? true)))
    (rf/reg-event :platform-gating-test/save
      (fn [_ _] {:fx [[:platform-gating-test/browser-only {}]]}))
    fired?))

(deftest untagged-frame-uses-the-host-default-platform
  (is (= :server (rf.interop/active-platform)))
  (let [traces (collect-traces! ::untagged)
        fired? (register-browser-only-fx!)]
    (rf/make-frame {:id :platform-gating-test/untagged})
    (is (nil? (:platform (:config (rf.frame/frame :platform-gating-test/untagged)))))
    (rf/with-frame :platform-gating-test/untagged
      (rf/dispatch-sync [:platform-gating-test/save]))
    (rf/unregister-listener! :trace ::untagged)
    (is (false? @fired?) "the untagged frame is :server, so the :client-only fx skips")
    (when rf.interop/debug-enabled?
      (let [skips (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces)]
        (is (= 1 (count skips)))
        (is (= :server (get-in (first skips) [:tags :rf.fx/platform])))))))

(deftest frame-tagged-client-allows-client-only-fx
  (let [traces (collect-traces! ::tagged-client)
        fired? (register-browser-only-fx!)]
    (rf/make-frame {:id :platform-gating-test/client :platform :client})
    (is (= :client (:platform (:config (rf.frame/frame :platform-gating-test/client)))))
    (rf/with-frame :platform-gating-test/client
      (rf/dispatch-sync [:platform-gating-test/save]))
    (rf/unregister-listener! :trace ::tagged-client)
    (is (true? @fired?) "the frame's own :client platform lets the fx run")
    ;; Over the empty production ring this negative would pass either way.
    (when rf.interop/debug-enabled?
      (is (empty? (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces))))))

(deftest two-frames-in-one-process-gate-by-their-own-platform
  (let [fired?    (register-browser-only-fx!)
        fires-on? (fn [frame-id]
                    (reset! fired? false)
                    (rf/with-frame frame-id
                      (rf/dispatch-sync [:platform-gating-test/save]))
                    @fired?)]
    (rf/make-frame {:id :platform-gating-test/iso-client :platform :client})
    (rf/make-frame {:id :platform-gating-test/iso-untagged})
    ;; client, then untagged (the tag did not leak), then client again (the
    ;; untagged :server did not leak back).
    (is (= [true false true]
           (mapv fires-on? [:platform-gating-test/iso-client
                            :platform-gating-test/iso-untagged
                            :platform-gating-test/iso-client])))))
