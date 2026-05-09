(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the ssr artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the ssr artefact IS on the classpath here (the test ns
  requires `re-frame.ssr`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-uo7v (ssr split), and the
  prose at the call sites in `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            ;; Loading ssr registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.ssr]))

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

(deftest render-to-string-raises-when-ssr-artefact-missing
  (testing "rf/render-to-string raises :rf.error/ssr-artefact-missing when the :ssr/render-to-string hook is nil"
    (with-hook-as-nil :ssr/render-to-string
      (fn []
        (let [thrown (try (rf/render-to-string [:div])
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "render-to-string throws when the ssr artefact is absent")
          (is (= ":rf.error/ssr-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'render-to-string (:where data))
                "ex-data carries :where = 'render-to-string")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest render-tree-hash-raises-when-ssr-artefact-missing
  (testing "rf/render-tree-hash raises :rf.error/ssr-artefact-missing when the :ssr/render-tree-hash hook is nil"
    (with-hook-as-nil :ssr/render-tree-hash
      (fn []
        (let [thrown (try (rf/render-tree-hash [:div])
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "render-tree-hash throws when the ssr artefact is absent")
          (is (= ":rf.error/ssr-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'render-tree-hash (:where data))
                "ex-data carries :where = 'render-tree-hash")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest reg-error-projector-raises-when-ssr-artefact-missing
  (testing "rf/reg-error-projector (macro) raises :rf.error/ssr-artefact-missing when the :ssr/reg-error-projector hook is nil"
    ;; The macro forwards to -reg-error-projector which performs the
    ;; late-bind lookup at runtime. Flipping the hook at runtime is
    ;; enough to surface the absent-artefact branch.
    (with-hook-as-nil :ssr/reg-error-projector
      (fn []
        (let [thrown (try (rf/reg-error-projector :probe/projector
                                                  (fn [_trace-event] {}))
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-error-projector throws when the ssr artefact is absent")
          (is (= ":rf.error/ssr-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            ;; The :where stamped here is the bare 'reg-error-projector
            ;; symbol — the macro forwards to the plain-fn delegate
            ;; `-reg-error-projector`, which is where the late-bind
            ;; check (and ex-info) lives. The fn does not syntax-quote
            ;; through a macro layer, so :where is the bare symbol.
            (is (= 'reg-error-projector (:where data))
                "ex-data carries :where = 'reg-error-projector")
            (is (= :probe/projector (:id data))
                "ex-data carries :id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

(deftest project-error-raises-when-ssr-artefact-missing
  (testing "rf/project-error raises :rf.error/ssr-artefact-missing when the :ssr/project-error hook is nil"
    (with-hook-as-nil :ssr/project-error
      (fn []
        (let [thrown (try (rf/project-error :rf/default {})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "project-error throws when the ssr artefact is absent")
          (is (= ":rf.error/ssr-artefact-missing" (.getMessage thrown))
              "the documented error category appears in the message")
          (let [data (ex-data thrown)]
            (is (= 'project-error (:where data))
                "ex-data carries :where = 'project-error")
            (is (= :rf/default (:frame data))
                "ex-data carries :frame from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
