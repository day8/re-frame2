;;;; tests/runtime/recorder_test.clj
;;;;
;;;; Babashka-runnable structural verification of the signal recorder in
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; Why a structural test rather than a runtime test:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is CLJS-only (loaded into the
;;;; consumer app via shadow-cljs `:devtools :preloads`) and the recorder
;;;; leans on `requestAnimationFrame` / `document.activeElement` /
;;;; reactive subscriptions — none of which exist under bb. The MCP
;;;; wire-shape contract is unit-tested at
;;;; `tools/re-frame2-pair-mcp/test/.../record_test.cljs`; the LIVE rAF /
;;;; dedup / teardown semantics are exercised by the form running in a
;;;; real tab. What we pin HERE is the source-level contract that solves
;;;; the three footguns the bead named, so a refactor can't silently drop
;;;; one:
;;;;
;;;;   1. change-dedup — the sampler tick appends only on a structural
;;;;      change against the per-signal last value (`not=` against
;;;;      `last-values`), so a steady signal yields one baseline entry,
;;;;      not one-per-frame.
;;;;   2. teardown — the rAF driver self-cancels (reads the tick's boolean
;;;;      to decide whether to reschedule) AND `stop-recording!` /
;;;;      `read-recording {:stop true}` call `cancelAnimationFrame`.
;;;;   3. rAF timing — the sampler runs inside `requestAnimationFrame`
;;;;      (with a `next-tick` fallback when rAF is absent), never a busy
;;;;      loop.
;;;;
;;;; Plus the load-bearing invariants:
;;;;   - READ-ONLY: the recorder never dispatches / resets / writes the DOM.
;;;;   - ring cap: a forgotten recording can't grow unboundedly.
;;;;   - stop conditions: :ms / :changes / a predicate are all handled.
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
;; The recorder fns must all exist.
;; ---------------------------------------------------------------------------

(deftest recorder-fns-defined
  (doseq [sym '[sample-one-signal sample-signals start-recording!
                read-recording stop-recording! recording-info]]
    (is (some? (defn-form sym))
        (str "recorder fn " sym " must be defined in runtime.cljs"))))

;; ---------------------------------------------------------------------------
;; Footgun #1 — change-dedup. The sampler tick appends only on a change
;; against the per-signal last value.
;; ---------------------------------------------------------------------------

(deftest sampler-tick-dedups-against-last-values
  (let [form (defn-form 'recording-sampler-tick!)]
    (is (some? form))
    (is (mentions-sym? form 'last-values)
        "tick must compare against the per-signal last-values map (dedup)")
    (is (mentions-sym? form 'not=)
        "tick must use structural not= to detect a change")
    (is (mentions-sym? form 'keep-indexed)
        "tick must only emit entries for signals that actually changed")))

;; ---------------------------------------------------------------------------
;; Footgun #2 — teardown. The driver self-cancels; stop paths cancel rAF.
;; ---------------------------------------------------------------------------

(deftest driver-self-cancels-on-stop
  (let [drive (defn-form 'drive-recording!)
        tick  (defn-form 'recording-sampler-tick!)]
    (is (some? drive))
    ;; The driver reschedules only when the tick says keep-running — the
    ;; tick returns false at the stop condition, so the loop ends itself.
    (is (mentions-sym? drive 'recording-sampler-tick!)
        "driver must read the tick's keep-running boolean")
    (is (form-contains? #(= % :stopped) tick)
        "tick must flip status to :stopped at the stop condition")))

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
;; Footgun #3 — rAF timing. The driver schedules via requestAnimationFrame
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
;; Ring cap — a forgotten recording can't grow unboundedly.
;; ---------------------------------------------------------------------------

(deftest sampler-tick-applies-ring-cap
  (let [tick (defn-form 'recording-sampler-tick!)]
    (is (mentions-sym? tick 'max-entries)
        "tick must honour the max-entries cap")
    (is (mentions-sym? tick 'subvec)
        "tick must trim from the front (drop-oldest) when over the cap")))

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
    (is (form-contains? #(= % :no-signals) start)
        "start-recording! must refuse an empty signal-set")
    ;; The refusal for app-db/sub signals routes through the shared enriched
    ;; builder `ambiguous-frame-error`; the contract (refuse an unresolvable
    ;; frame) is carried by that call rather than a bare `:ambiguous-frame`
    ;; keyword.
    (is (mentions-sym? start 'ambiguous-frame-error)
        "start-recording! must refuse an unresolvable frame via ambiguous-frame-error")))

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
    (doseq [mutator ["pair-dispatch" "replace-app-db!" "app-db-reset!"
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
