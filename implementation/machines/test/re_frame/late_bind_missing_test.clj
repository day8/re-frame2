(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the machines artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the machines artefact IS on the classpath here (the test ns
  requires `re-frame.machines`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-xbtj (machines split), and the
  prose at the call sites in `re-frame.core`.

  Note: only the active machine surfaces (`reg-machine`, `reg-machine*`,
  `create-machine-handler`, `machine-transition`) raise the missing-
  artefact error. The read-only query surfaces (`machines`,
  `machine-meta`, `machine-by-system-id`) deliberately return safe
  defaults — Spec 005 §Querying machines."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading machines registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.machines]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (late-bind/set-fn! hook-key original)))))

(deftest reg-machine-star-raises-when-machines-artefact-missing
  (testing "rf/reg-machine* (plain fn) raises :rf.error/machines-artefact-missing when the :machines/reg-machine hook is nil"
    (with-hook-as-nil :machines/reg-machine
      (fn []
        (let [thrown (try (rf/reg-machine* :probe/machine
                                           {:initial :idle
                                            :states  {:idle {}}})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-machine* throws when the machines artefact is absent")
          (is (= ":rf.error/machines-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'reg-machine* (:where data))
                "ex-data carries :where = 'reg-machine*")
            (is (= :probe/machine (:machine-id data))
                "ex-data carries :machine-id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest reg-machine-macro-raises-when-machines-artefact-missing
  (testing "rf/reg-machine (macro) raises :rf.error/machines-artefact-missing when the :machines/reg-machine hook is nil"
    (with-hook-as-nil :machines/reg-machine
      (fn []
        (let [thrown (try (rf/reg-machine :probe/macro-machine
                                          {:initial :idle
                                           :states  {:idle {}}})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-machine throws when the machines artefact is absent")
          (is (= ":rf.error/machines-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            ;; Per rf2-hoiu the throw lives in
            ;; `re-frame.core-machines/reg-machine` — the sibling-namespace
            ;; fn-form delegate the macro routes through — so `:where`
            ;; is the bare unqualified symbol of the user-facing surface
            ;; (`rf/reg-machine`).
            (is (= 'reg-machine (:where data))
                "ex-data carries :where = 'reg-machine")
            (is (= :probe/macro-machine (:machine-id data))
                "ex-data carries :machine-id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest create-machine-handler-raises-when-machines-artefact-missing
  (testing "rf/create-machine-handler raises :rf.error/machines-artefact-missing when the :machines/create-machine-handler hook is nil"
    (with-hook-as-nil :machines/create-machine-handler
      (fn []
        (let [thrown (try (rf/create-machine-handler {:initial :idle
                                                      :states  {:idle {}}})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "create-machine-handler throws when the machines artefact is absent")
          (is (= ":rf.error/machines-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'create-machine-handler (:where data))
                "ex-data carries :where = 'create-machine-handler")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest machine-transition-raises-when-machines-artefact-missing
  (testing "rf/machine-transition raises :rf.error/machines-artefact-missing when the :machines/machine-transition hook is nil"
    (with-hook-as-nil :machines/machine-transition
      (fn []
        (let [thrown (try (rf/machine-transition {:initial :idle
                                                  :states  {:idle {}}}
                                                 {:state :idle :data {}}
                                                 [:noop])
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "machine-transition throws when the machines artefact is absent")
          (is (= ":rf.error/machines-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'machine-transition (:where data))
                "ex-data carries :where = 'machine-transition")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest read-only-machine-queries-return-safe-defaults
  (testing "Per Spec 005 §Querying machines, the read-only machine
  introspection surfaces return safe defaults when the machines
  artefact is absent — `machines` returns []; `machine-meta` and
  `machine-by-system-id` return nil. This branch must stay distinct
  from the active surfaces' missing-artefact contract."
    (with-hook-as-nil :machines/machines
      (fn []
        (is (= [] (rf/machines))
            "machines returns [] when the machines artefact is absent")))
    (with-hook-as-nil :machines/machine-meta
      (fn []
        (is (nil? (rf/machine-meta :anything))
            "machine-meta returns nil when the machines artefact is absent")))
    (with-hook-as-nil :machines/machine-by-system-id
      (fn []
        (is (nil? (rf/machine-by-system-id :anything))
            "machine-by-system-id returns nil when the machines artefact is absent")))))
