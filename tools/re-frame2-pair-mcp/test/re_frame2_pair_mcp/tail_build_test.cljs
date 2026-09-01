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
;; Slow reload — the first sample still equals the pre-edit baseline; a later
;; poll differs. Success envelope carries :probe-values
;; {:baseline :initial :final}.
;; ---------------------------------------------------------------------------

(deftest probe-changes-carries-baseline-initial-and-final
  (testing "slow-reload ordering: first sample = baseline, later poll differs → ok"
    (async done
      (let [script (atom [0 1])] ; first call → 0 (= baseline), second → 1 (changed)
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)"
                                                            :baseline "0"
                                                            :wait-ms 1000}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (false? (:soft? edn)))
                               (is (= {:baseline "0" :initial 0 :final 1} (:probe-values edn))
                                   "success envelope carries the caller's baseline and both observed ends")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Fast reload (rf2-1f60u) — the reload lands BEFORE the first sample, so
;; every sample is already the new value. The pre-edit baseline makes this
;; recognizable as SUCCESS on the very first sample; the pre-fix self-baseline
;; contract returned :timed-out here.
;; ---------------------------------------------------------------------------

(deftest reload-landed-before-first-sample-is-success
  (testing "fast-reload ordering: first and all samples already the new value → immediate ok"
    (async done
      (let [start  (js/Date.now)
            script (atom [1 1])] ; the pre-edit value 0 is never observed
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)"
                                                            :baseline "0"
                                                            :wait-ms 3000}))
                    (.then (fn [result]
                             (let [edn     (tu/extract-edn result)
                                   elapsed (- (js/Date.now) start)]
                               (is (true? (:ok? edn))
                                   "a reload that landed before the first probe is a SUCCESS, not :timed-out")
                               (is (false? (:soft? edn)))
                               (is (= {:baseline "0" :initial 1 :final 1} (:probe-values edn))
                                   "the envelope shows why: the first sample already differs from the baseline")
                               (is (< elapsed 2000)
                                   "recognized on the first sample — no waiting out the deadline")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Argument contract — :probe requires :baseline (and :baseline requires
;; :probe). The racy post-edit self-baseline shape is refused, not silently
;; accepted.
;; ---------------------------------------------------------------------------

(deftest probe-without-baseline-is-refused
  (testing "a probe with no pre-edit baseline errors with :missing-baseline before touching nREPL"
    (async done
      (-> (with-rejecting-eval! "should-not-be-called"
            (fn []
              (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)" :wait-ms 500}))
                  (.then (fn [result]
                           (is (tu/error? result))
                           (let [edn (tu/extract-edn result)]
                             (is (false? (:ok? edn)))
                             (is (= :missing-baseline (:reason edn))
                                 "the racy self-baseline shape is refused with its own reason")
                             (is (re-find #"BEFORE" (:note edn))
                                 "the note carries the capture-before-edit recipe")))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

(deftest baseline-without-probe-is-refused
  (testing "a baseline with no probe errors with :baseline-without-probe"
    (async done
      (-> (tail/tail-build-tool nil (tu/args->js {:baseline "0"}))
          (.then (fn [result]
                   (is (tu/error? result))
                   (let [edn (tu/extract-edn result)]
                     (is (false? (:ok? edn)))
                     (is (= :baseline-without-probe (:reason edn))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

;; ---------------------------------------------------------------------------
;; Printed-form matching — the canonical capture is pr-str, and the plain str
;; rendering is also accepted so a string-valued probe captured without its
;; quotes polls honestly instead of false-succeeding on its first sample.
;; ---------------------------------------------------------------------------

(deftest string-baseline-without-quotes-still-polls
  (testing "an unquoted string baseline matches the equal first sample (str rendering) and succeeds on the real change"
    (async done
      (let [script (atom ["abc" "abd"])]
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)"
                                                            :baseline "abc"
                                                            :wait-ms 1000}))
                    (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= "abc" (get-in edn [:probe-values :initial]))
                                   "the equal first sample matched via str rendering — no instant false success")
                               (is (= "abd" (get-in edn [:probe-values :final]))
                                   "success came from the genuine change")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Scripted workflow witness (rf2-1f60u AC 7) — the ACTUAL order: capture F0,
;; edit/reload to F1 BEFORE invoking tail-build, then wait. An atom stands in
;; for the running app's probe-visible state; the capture is taken from it
;; exactly as the skill instructs (pre-edit, printed form), the "edit + fast
;; reload" lands before the first sample, and tail-build must still return ok.
;; Reverting the baseline-aware comparison makes this red (:timed-out).
;; ---------------------------------------------------------------------------

(deftest workflow-capture-edit-reload-then-tail-build
  (testing "capture F0 → edit+reload to F1 → tail-build recognizes the landed reload"
    (async done
      (let [runtime  (atom 0)                 ;; the app's probe-visible state
            f0       (pr-str @runtime)        ;; 1. capture the baseline PRE-edit
            _        (reset! runtime 1)       ;; 2. edit + reload land BEFORE the first sample
            orig     nrepl/cljs-eval-value
            stub     (fn
                       ([_conn _build-id _form-str] (js/Promise.resolve @runtime))
                       ([_conn _build-id _form-str _opts] (js/Promise.resolve @runtime)))]
        (set! nrepl/cljs-eval-value stub)
        (-> (tail/tail-build-tool nil (tu/args->js {:probe "(app/probe)"
                                                    :baseline f0
                                                    :wait-ms 3000}))
            (.then (fn [result]
                     (let [edn (tu/extract-edn result)]
                       (is (true? (:ok? edn))
                           "the reload landed before the first sample and is still recognized")
                       (is (= 1 (get-in edn [:probe-values :final]))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.finally (fn [] (tu/restore-eval! stub orig)))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; Probe never leaves the baseline — timeout envelope carries :probe-values
;; with baseline = initial = final, plus the :note hint. This is the
;; non-vacuity control for the baseline contract: the fast-reload success
;; must not make every stable value a success.
;; ---------------------------------------------------------------------------

(deftest probe-stuck-on-baseline-times-out
  (testing "tail-build whose samples never leave the baseline times out honestly"
    (async done
      (let [;; Every call returns 42 = baseline → poll loop runs until
            ;; wait-ms elapses.
            script (atom [42 42 42 42 42 42 42 42 42 42 42 42 42 42 42])]
        (-> (with-scripted-eval! script
              (fn []
                (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-stuck-form)"
                                                            :baseline "42"
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
                               (is (= {:baseline "42" :initial 42 :final 42} (:probe-values edn))
                                   "timeout envelope shows the samples never left the caller's baseline")
                               (is (string? (:note edn)))
                               (is (re-find #"cannot discriminate this edit"
                                            (:note edn))
                                   "hint names the non-discriminating-probe cause and points at a source-derived fingerprint")))))))
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
                                                          :baseline "0"
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
                                                          :baseline "0"
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
      (-> (tail/tail-build-tool nil (tu/args->js {:probe "(some-form)" :baseline "0" :wait-ms -5}))
          (.then (fn [result]
                   (is (tu/error? result))
                   (let [edn (tu/extract-edn result)]
                     (is (= :invalid-numeric-arg (:reason edn)))
                     (is (= "wait-ms" (:arg edn))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))
