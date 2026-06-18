(ns re-frame.init-platform-test
  "JVM coverage for `re-frame.core/init-platform` (rf2-pgma).

  Per Spec 011 §Effect handling on the server: the runtime tracks the
  active platform (`:server` or `:client`) so `reg-fx`/`reg-cofx`
  `:platforms` metadata can gate execution. JVM hosts default to
  `:server`; `init-platform` is the public setter that flips the
  host-wide marker at boot (for the CLJS-on-Node SSR case, or for
  JVM-runnable tests that simulate `:client`).

  These tests pin three contract points:

    1. Default marker is `:server` on the JVM (rebound by the reset
       fixture so cross-test bleed doesn't corrupt the read).
    2. `init-platform` toggles the marker; subsequent `:platforms`
       gating reflects the new value (the gate is the load-bearing
       observable — flipping the marker should flip the trace shape).
    3. Invalid input raises `:rf.error/invalid-platform`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; Per-test JVM default — explicitly :server so a sibling test that
  ;; flipped the marker doesn't bleed across.
  (rf/init-platform :server)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- 1. JVM default --------------------------------------------------------

(deftest jvm-default-platform-is-server
  (testing "`interop/active-platform` defaults to :server on the JVM"
    (is (= :server (interop/active-platform))
        "JVM hosts default to :server per Spec 011 §Effect handling on the server")))

;; ---- 2. init-platform toggles fx gating ------------------------------------

(deftest init-platform-flip-to-client-allows-client-only-fx
  (testing "after (init-platform :client) a :platforms #{:client} fx runs
            instead of skipping — same fx body, same dispatch, different
            host-wide marker"
    (let [traces (collect-traces! ::ip-client)
          fired? (atom false)]
      (rf/reg-fx :init-platform-test/browser-only
        {:platforms #{:client}}
        (fn [_ _] (reset! fired? true)))
      (rf/reg-event :init-platform-test/save
        (fn [_ _] {:fx [[:init-platform-test/browser-only {}]]}))

      (rf/init-platform :client)
      (is (= :client (interop/active-platform))
          "the marker flipped to :client")

      (rf/dispatch-sync [:init-platform-test/save])
      (rf/unregister-listener! :trace ::ip-client)

      (is (true? @fired?)
          "the :client-only fx ran because the active platform is now :client")
      (let [skips (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces)]
        (is (empty? skips)
            "no :rf.fx/skipped-on-platform trace — the gate passed")))))

(deftest init-platform-flip-to-server-skips-client-only-fx
  (testing "with the default (init-platform :server) marker, a
            :platforms #{:client} fx skips with :rf.fx/skipped-on-platform —
            this is the JVM/SSR default contract, pinned here so a future
            change to the host-wide marker default is caught"
    (let [traces (collect-traces! ::ip-server)
          fired? (atom false)]
      (rf/reg-fx :init-platform-test/browser-only
        {:platforms #{:client}}
        (fn [_ _] (reset! fired? true)))
      (rf/reg-event :init-platform-test/save
        (fn [_ _] {:fx [[:init-platform-test/browser-only {}]]}))

      ;; reset-runtime already set :server; assert defensively.
      (is (= :server (interop/active-platform)))

      (rf/dispatch-sync [:init-platform-test/save])
      (rf/unregister-listener! :trace ::ip-server)

      (is (false? @fired?)
          "the :client-only fx did NOT run on :server")
      (let [skips (filter #(= :rf.fx/skipped-on-platform (:operation %)) @traces)]
        (is (= 1 (count skips))
            "exactly one :rf.fx/skipped-on-platform trace")
        (is (= :server (get-in (first skips) [:tags :rf.fx/platform]))
            ":rf.fx/platform stamp matches the active marker")))))

;; ---- 3. Validation ---------------------------------------------------------

(deftest init-platform-rejects-invalid-input
  (testing "non-keyword / non-{:server :client} arg raises :rf.error/invalid-platform"
    (doseq [bad [nil :unknown "client" 42 {}]]
      (let [ex (try (rf/init-platform bad) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex)
            (str "(rf/init-platform " (pr-str bad) ") raised"))
        (is (= :rf.error/invalid-platform (:rf.error/id (ex-data ex))))
        (is (= :no-recovery (:recovery (ex-data ex))))
        (is (= 'rf/init-platform (:where (ex-data ex))))
        (is (= bad (:received (ex-data ex)))))))
  (testing "the marker is unchanged after a rejected call"
    (rf/init-platform :server)
    (try (rf/init-platform :nope) (catch Exception _))
    (is (= :server (interop/active-platform))
        "rejected calls do not mutate the marker")))

;; ---- 4. Idempotence + symmetry --------------------------------------------

(deftest init-platform-is-idempotent-and-symmetric
  (testing "calling init-platform with the same value twice is a no-op
            beyond the second reset; flipping back and forth always
            lands on the requested value"
    (rf/init-platform :server)
    (rf/init-platform :server)
    (is (= :server (interop/active-platform)))
    (rf/init-platform :client)
    (is (= :client (interop/active-platform)))
    (rf/init-platform :server)
    (is (= :server (interop/active-platform)))))

;; ---- 5. Independence from init! -------------------------------------------

(deftest init-platform-is-independent-of-init!
  (testing "init-platform does not touch adapter state — calling it before
            init! (or with no adapter installed) still updates the marker.
            init! and init-platform are sibling boot calls per the API."
    ;; Tear down so we can test the no-adapter-installed precondition.
    (rf/destroy-adapter!)
    (is (nil? (rf/current-adapter))
        "no adapter installed for this stage of the test")
    (rf/init-platform :client)
    (is (= :client (interop/active-platform))
        "init-platform works without an adapter installed — they are independent boot calls")
    ;; Restore the test fixture's invariant for the next test.
    (rf/init! plain-atom/adapter)
    (rf/init-platform :server)))
