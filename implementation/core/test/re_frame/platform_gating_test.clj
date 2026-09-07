(ns re-frame.platform-gating-test
  "JVM coverage for per-frame platform gating (rf2-pgma, rf2-kuky.77).

  Per Spec 011 §Effect handling on the server: the runtime tracks the
  active platform (`:server` or `:client`) so `reg-fx`/`reg-cofx`
  `:platforms` metadata can gate execution. There is ONE way to say it —
  the frame's own `:platform` config key — over a per-host CONSTANT
  default (`:server` on the JVM, `:client` on CLJS including
  CLJS-on-Node). Nothing is process-wide: `rf.interop/active-platform`
  is a constant, and there is no setter.

  These tests pin two contract points:

    1. An UNTAGGED frame gets the host default — `:server` on the JVM,
       so a `:platforms #{:client}` fx skips.
    2. A frame TAGGED `{:platform :client}` runs that same fx, unchanged
       body and unchanged dispatch. The gate is the load-bearing
       observable: tagging the frame flips the trace shape.

  ## Posture split (rf2-d2841)

  Both contract points are production-real and are asserted WITHOUT a
  posture guard: the untagged default, the per-frame override, and the
  `:platforms` GATE ITSELF (whether the handler body ran). They run in
  the ordinary `clojure -M:test` suite AND in
  `scripts/test-core-prod-gate.sh`.

  `:rf.fx/skipped-on-platform` is a bare `trace/emit!` warning (fx.cljc
  §handle-one-fx) with no always-on twin, so under the real gate nothing is
  emitted BY DESIGN. Its assertions are kept verbatim inside a
  `(when rf.interop/debug-enabled? …)` arm marked `rf2-d2841` — including the
  negative on the tagged-client leg, which over an empty ring would pass
  whether the gate passed or skipped."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
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
  "Register the shared `:platforms #{:client}` fx + the event that emits
  it. Returns the `fired?` atom the fx body sets — the production-visible
  half of \"the gate passed\"."
  []
  (let [fired? (atom false)]
    (rf/reg-fx :platform-gating-test/browser-only
      {:platforms #{:client}}
      (fn [_ _] (reset! fired? true)))
    (rf/reg-event :platform-gating-test/save
      (fn [_ _] {:fx [[:platform-gating-test/browser-only {}]]}))
    fired?))

;; ---- 1. Untagged frame → host default -------------------------------------

(deftest untagged-frame-uses-the-host-default-platform
  (testing "`rf.interop/active-platform` is the per-host CONSTANT :server on
            the JVM, and an untagged frame inherits it — so a
            :platforms #{:client} fx skips with :rf.fx/skipped-on-platform"
    (is (= :server (rf.interop/active-platform))
        "JVM hosts default to :server per Spec 011 §Effect handling on the server")
    (let [traces (collect-traces! ::untagged)
          fired? (register-browser-only-fx!)]
      (rf/make-frame {:id :platform-gating-test/untagged})
      (is (nil? (:platform (:config (rf.frame/frame :platform-gating-test/untagged))))
          "no :platform key on the frame — it rides the host default")

      (rf/with-frame :platform-gating-test/untagged
        (rf/dispatch-sync [:platform-gating-test/save]))
      (rf/unregister-listener! :trace ::untagged)

      (is (false? @fired?)
          "the :client-only fx did NOT run — the untagged frame is :server on the JVM")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      ;; `:rf.fx/skipped-on-platform` is a bare `trace/emit!` warning with no
      ;; always-on twin; the SKIP it reports is asserted above and runs in
      ;; both postures.
      (when rf.interop/debug-enabled?
        (let [skips (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces)]
          (is (= 1 (count skips))
              "exactly one :rf.fx/skipped-on-platform trace")
          (is (= :server (get-in (first skips) [:tags :rf.fx/platform]))
              ":rf.fx/platform stamp matches the resolved platform"))))))

;; ---- 2. Frame-tagged :client overrides the host default -------------------

(deftest frame-tagged-client-allows-client-only-fx
  (testing "a frame tagged {:platform :client} runs a :platforms #{:client} fx
            that the host default would have skipped — same fx body, same
            dispatch, different FRAME"
    (let [traces (collect-traces! ::tagged-client)
          fired? (register-browser-only-fx!)]
      (rf/make-frame {:id :platform-gating-test/client :platform :client})
      (is (= :client (:platform (:config (rf.frame/frame :platform-gating-test/client))))
          "the frame carries :platform :client (singular keyword — one platform per frame)")

      (rf/with-frame :platform-gating-test/client
        (rf/dispatch-sync [:platform-gating-test/save]))
      (rf/unregister-listener! :trace ::tagged-client)

      (is (true? @fired?)
          "the :client-only fx ran because the FRAME's platform is :client")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). A NEGATIVE
      ;; over the trace ring: under `-Dre-frame.debug=false` the ring is empty
      ;; by design, so `empty?` would pass whether the gate passed or skipped.
      ;; The production-visible half of "the gate passed" is `@fired?` above.
      (when rf.interop/debug-enabled?
        (let [skips (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces)]
          (is (empty? skips)
              "no :rf.fx/skipped-on-platform trace — the gate passed"))))))

;; ---- 3. The two frames coexist in ONE process ------------------------------

(deftest per-frame-platform-is-isolated-not-process-wide
  (testing "two frames in the SAME process resolve different platforms —
            the whole point of deleting the host-wide marker"
    (rf/make-frame {:id :platform-gating-test/iso-untagged})
    (rf/make-frame {:id :platform-gating-test/iso-client :platform :client})
    (rf/make-frame {:id :platform-gating-test/iso-server :platform :server})
    (let [platform-of (fn [id]
                        (or (:platform (:config (rf.frame/frame id)))
                            (rf.interop/active-platform)))]
      (is (= :server (platform-of :platform-gating-test/iso-untagged))
          "untagged → the JVM host default")
      (is (= :client (platform-of :platform-gating-test/iso-client))
          "explicitly tagged :client wins over the host default")
      (is (= :server (platform-of :platform-gating-test/iso-server))
          "explicitly tagged :server matches the host default and is still explicit"))))
