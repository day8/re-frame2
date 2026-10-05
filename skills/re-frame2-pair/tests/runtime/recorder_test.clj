;;;; tests/runtime/recorder_test.clj
;;;;
;;;; Babashka-runnable structural verification of the signal recorder in
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; The recorder's behaviour against a real frame — change-dedup (a steady
;;;; signal yields one baseline entry, not one per frame), the driver ending
;;;; itself at a stop condition, and the ring cap — is pinned by
;;;; `tests/fixture/test/re_frame2_pair/runtime_recording_test.cljs`. The MCP
;;;; wire-shape contract is unit-tested at
;;;; `tools/re-frame2-pair-mcp/test/.../record_test.cljs`. What we pin HERE is
;;;; the source-level contract neither of those reaches:
;;;;
;;;;   - teardown: `stop-recording!` / `read-recording {:stop true}` call
;;;;     `cancelAnimationFrame`.
;;;;   - rAF timing: the sampler runs inside `requestAnimationFrame` (with a
;;;;     `next-tick` fallback when rAF is absent), never a busy loop.
;;;;   - READ-ONLY: the recorder never dispatches / resets / writes the DOM.
;;;;   - stop conditions: :ms / :changes / a predicate are all handled, and a
;;;;     recording given no stop defaults to a wall-clock window.
;;;;
;;;; Run: bb tests/runtime/recorder_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns recorder-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private defn-form rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(defn- mentions-sym? [form needle]
  (form-contains? #(= % needle) form))

;; ---------------------------------------------------------------------------
;; The recorder fns must exist. The pins below and the fail-closed pins in
;; dom_readback_redaction_test.clj each read a recorder fn by name and fail
;; when it is missing; `recording-info`, the registry diagnostic, is read
;; nowhere else.
;; ---------------------------------------------------------------------------

(deftest recording-info-is-defined
  (is (some? (defn-form 'recording-info))
      "recorder fn recording-info must be defined in runtime.cljs"))

;; ---------------------------------------------------------------------------
;; Teardown — the stop paths cancel rAF.
;; ---------------------------------------------------------------------------

(deftest stop-paths-cancel-raf
  (let [stop-fn (defn-form 'stop-recording!)
        read-fn (defn-form 'read-recording)]
    (is (mentions-sym? stop-fn 'js/cancelAnimationFrame)
        "stop-recording! must cancel the rAF loop")
    (is (mentions-sym? read-fn 'js/cancelAnimationFrame)
        "read-recording {:stop true} must cancel the rAF loop")
    (is (mentions-sym? stop-fn 'swap!)
        "stop-recording! must drop the recording from the registry")))

;; ---------------------------------------------------------------------------
;; rAF timing — the driver schedules via requestAnimationFrame
;; with a next-tick fallback; it does not busy-loop.
;; ---------------------------------------------------------------------------

(deftest driver-uses-raf-with-fallback
  (let [drive (defn-form 'drive-recording!)]
    (is (mentions-sym? drive 'js/requestAnimationFrame)
        "driver must sample on requestAnimationFrame")
    (is (form-contains? #(and (symbol? %)
                              (str/includes? (str %) "next-tick"))
                        drive)
        "driver must fall back to next-tick when rAF is absent")))

;; ---------------------------------------------------------------------------
;; Stop conditions — :ms, :changes, and a predicate are all evaluated.
;; ---------------------------------------------------------------------------

(deftest sampler-tick-evaluates-all-stop-conditions
  (let [tick (defn-form 'recording-sampler-tick!)]
    (is (form-contains? #(= % :ms) tick) ":ms stop condition handled")
    (is (form-contains? #(= % :changes) tick) ":changes stop condition handled")
    (is (mentions-sym? tick 'pred-fn) "predicate stop condition handled")))

(deftest start-recording-defaults-a-stop-window
  (let [start (defn-form 'start-recording!)]
    ;; A recording with no stop is the forgotten-observer footgun — the
    ;; runtime must default to a wall-clock window.
    (is (mentions-sym? start 'default-recording-stop-ms)
        "start-recording! must default a wall-clock stop when none given")
    ;; Its refusal of an unresolvable frame, through `ambiguous-frame-error`,
    ;; is pinned beside the fail-closed pins in dom_readback_redaction_test.clj.
    (is (form-contains? #(= % :no-signals) start)
        "start-recording! must refuse an empty signal-set")))

;; ---------------------------------------------------------------------------
;; READ-ONLY invariant — the recorder must never mutate the app.
;; ---------------------------------------------------------------------------

(deftest recorder-source-is-read-only
  ;; Scope the scan to the recorder fns (the whole file naturally mentions
  ;; dispatch / reset elsewhere). Concatenate their source and assert no
  ;; mutation host-forms appear.
  (let [recorder-src
        (->> '[sample-one-signal sample-signals recording-sampler-tick!
               drive-recording! start-recording! read-recording
               stop-recording! recording-info]
             (map defn-form)
             (map pr-str)
             (str/join "\n"))]
    (doseq [mutator ["pair-dispatch" "replace-frame-state!" "app-db-reset!"
                     ".dispatchEvent" ".setAttribute" "restore-epoch"
                     ".innerHTML"]]
      (is (not (str/includes? recorder-src mutator))
          (str "recorder must be read-only — found mutator " mutator)))
    (testing "the signal samplers DO read"
      (is (str/includes? recorder-src "app-db-value") "reads app-db")
      (is (str/includes? recorder-src "querySelector") "reads the DOM")
      (is (str/includes? recorder-src "activeElement") "reads focus"))))

(let [{:keys [fail error]} (run-tests 'recorder-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
