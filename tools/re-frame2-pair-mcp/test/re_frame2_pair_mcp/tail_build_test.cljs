(ns re-frame2-pair-mcp.tail-build-test
  "Unit tests for the `tail-build` MCP tool — specifically its
  probe-value diagnostics.

  The probe-value envelope lets the operator tell:

    - Did the probe form evaluate at all?
    - Did the probe return the same value before / after the reload?
    - Did the probe form raise on every iteration?

  These tests pin the envelope shape so the diagnostic stays
  visible across refactors of the polling loop."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.tail-build :as tail]))

;; ---------------------------------------------------------------------------
;; Stub harness — `cljs-eval-value` is the only nREPL surface tail-build
;; touches. We script it with a seq of values; each call shifts the head.
;; ---------------------------------------------------------------------------

(defn- with-scripted-eval!
  "Install a stub `cljs-eval-value` that resolves successive calls to
  the head of `script*` (an atom holding a vector of values; each call
  shifts the head; an empty queue falls back to the last value so a
  short script can drive arbitrary polling). Run `body-fn` (returning
  a Promise) and restore in `.finally`."
  [script* body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id _form-str]
                (let [s @script*
                      v (if (seq s) (first s) (peek (or (:tail @script*) [])))]
                  (when (seq s) (swap! script* subvec 1))
                  (js/Promise.resolve v)))
               ([_conn _build-id _form-str _opts]
                (let [s @script*
                      v (if (seq s) (first s) (peek (or (:tail @script*) [])))]
                  (when (seq s) (swap! script* subvec 1))
                  (js/Promise.resolve v))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- with-rejecting-eval!
  "Install a stub `cljs-eval-value` that REJECTS every call with an
  Error carrying `err-msg`. Used to drive the `:probe-errored` path —
  the probe form raised on the initial evaluation, so we never even
  enter the polling loop."
  [err-msg body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id _form-str]
                (js/Promise.reject (js/Error. err-msg)))
               ([_conn _build-id _form-str _opts]
                (js/Promise.reject (js/Error. err-msg))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

;; ---------------------------------------------------------------------------
;; Soft delay — no probe supplied; the envelope carries no probe-values.
;; ---------------------------------------------------------------------------

(deftest no-probe-soft-resolves
  (testing "tail-build with no probe resolves with :soft? true and no probe-values"
    (async done
      (-> (tail/tail-build-tool nil (tu/args->js {}))
          (.then (fn [result]
                   (let [edn (tu/extract-edn result)]
                     (is (true? (:ok? edn)))
                     (is (true? (:soft? edn)))
                     (is (not (contains? edn :probe-values))
                         "no probe → no probe-values slot"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Probe changes — success envelope carries :probe-values {:initial :final}.
;; ---------------------------------------------------------------------------

(deftest probe-changes-carries-initial-and-final
  (testing "successful tail-build returns probe-values with distinct initial/final"
    (async done
      (let [script (atom [0 1])] ; first call → 0 (initial), second → 1 (changed)
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)" :wait-ms 1000}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (false? (:soft? edn)))
                               (is (= {:initial 0 :final 1} (:probe-values edn))
                                   "success envelope carries both ends of the comparison")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Probe never changes — timeout envelope carries :probe-values with
;; initial = final, plus the new :note hint.
;; ---------------------------------------------------------------------------

(deftest probe-stuck-timeout-carries-equal-initial-and-final
  (testing "tail-build that times out exposes initial = final under :probe-values"
    (async done
      (let [;; Every call returns 42 → before == now forever → poll loop
            ;; runs until wait-ms elapses.
            script (atom [42 42 42 42 42 42 42 42 42 42 42 42 42 42 42])]
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-stuck-form)"
                                                            :wait-ms 250}))
                    (.then (fn [result]
                             ;; rf2-acckgr regression: a timed-out probe wait
                             ;; is a known-tool failure (:ok? false) and MUST
                             ;; ride as isError: true per spec/003's universal
                             ;; isError rule — not a success carrying bad news.
                             (is (tu/error? result)
                                 "a timed-out tail-build MUST be isError: true")
                             (let [edn (tu/extract-edn result)]
                               (is (false? (:ok? edn)))
                               (is (= :timed-out (:reason edn)))
                               (is (= {:initial 42 :final 42} (:probe-values edn))
                                   "timeout envelope shows the comparison failed to drift")
                               (is (string? (:note edn)))
                               (is (re-find #"probe form returns the same value"
                                            (:note edn))
                                   "new hint distinguishes value-stuck from compile-error")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Probe raises on initial eval — :probe-errored envelope.
;; ---------------------------------------------------------------------------

(deftest probe-rejects-initial-surfaces-probe-errored
  (testing "tail-build with a probe that throws returns :probe-errored, not :timed-out"
    (async done
      (-> (with-rejecting-eval! "Unable to resolve symbol: zorp"
            (fn []
              (-> (tail/tail-build-tool nil (tu/args->js {:probe "(zorp)"
                                                          :wait-ms 250}))
                  (.then (fn [result]
                           ;; rf2-acckgr regression: :probe-errored is a
                           ;; known-tool failure and MUST ride as
                           ;; isError: true, not a masked ok-text success.
                           (is (tu/error? result)
                               "a :probe-errored tail-build MUST be isError: true")
                           (let [edn (tu/extract-edn result)]
                             (is (false? (:ok? edn)))
                             (is (= :probe-errored (:reason edn))
                                 "errored initial eval is its own reason, not :timed-out")
                             (is (re-find #"zorp" (:probe-error edn))
                                 "underlying nREPL error message rides on :probe-error")
                             (is (string? (:note edn)))))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; `:wait-ms` is validated as a positive-millisecond integer
;; BEFORE the poll loop. A non-numeric value (`"bogus"`) would make the
;; `(>= elapsed wait-ms)` comparison `(>= n NaN)` — never true — so a
;; stuck probe would poll the nREPL socket FOREVER. The tool short-circuits
;; to an honest validation error WITHOUT touching nREPL.
;; ---------------------------------------------------------------------------

(deftest bogus-wait-ms-errors-honestly-without-polling
  (testing "a non-numeric :wait-ms returns a validation error, not an unbounded poll"
    (async done
      ;; A rejecting eval-stub would surface as :probe-errored IF the tool
      ;; reached the nREPL call. It must NOT — the validation short-circuits
      ;; first, so this resolves to the validation error regardless of the
      ;; (never-invoked) eval surface.
      (-> (with-rejecting-eval! "should-not-be-called"
            (fn []
              (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)"
                                                          :wait-ms "bogus"}))
                  (.then (fn [result]
                           (is (tu/error? result)
                               "malformed :wait-ms surfaces as :isError true")
                           (let [edn (tu/extract-edn result)]
                             (is (false? (:ok? edn)))
                             (is (= :invalid-numeric-arg (:reason edn)))
                             (is (= "wait-ms" (:arg edn)))
                             (is (not= :probe-errored (:reason edn))
                                 "validation runs BEFORE the nREPL probe eval")))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

(deftest negative-wait-ms-errors-honestly
  (testing "a negative :wait-ms (immediate timeout / negative setTimeout) is rejected"
    (async done
      (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)" :wait-ms -5}))
          (.then (fn [result]
                   (is (tu/error? result))
                   (let [edn (tu/extract-edn result)]
                     (is (= :invalid-numeric-arg (:reason edn)))
                     (is (= "wait-ms" (:arg edn))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))
