;;;; tests/runtime/recorder_test.clj
;;;;
;;;; Babashka-runnable structural verification of the signal recorder in
;;;; `preload/re_frame2_pair/runtime.cljs` (rf2-zo4b9).
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

(ns recorder-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure.walk :as walk]))

(def ^:private runtime-cljs-path
  (some (fn [p] (when (.exists (io/file p)) p))
        ["preload/re_frame2_pair/runtime.cljs"
         "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs"
         "../preload/re_frame2_pair/runtime.cljs"]))

(when-not runtime-cljs-path
  (binding [*out* *err*]
    (println "ERROR: cannot locate preload/re_frame2_pair/runtime.cljs from"
             (System/getProperty "user.dir")))
  (System/exit 2))

(defn- read-all-forms [^String src]
  ;; CLJS-tolerant read: the recorder fns sit AFTER the streaming section,
  ;; which uses `#js {...}` reader literals. Clojure's reader has no `js`
  ;; tag, so without a default-data-reader the parse aborts mid-file and
  ;; never reaches the recorder defns. A pass-through default reader keeps
  ;; the parse going (we never evaluate the forms, only walk their shape).
  (binding [*default-data-reader-fn* (fn [_tag v] v)]
    (let [pbr (java.io.PushbackReader. (java.io.StringReader. src))]
      (loop [acc []]
        (let [form (try (read {:read-cond :allow :features #{:cljs}} pbr)
                        (catch Exception _ ::eof))]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(def ^:private src (slurp runtime-cljs-path))
(def ^:private all-forms (read-all-forms src))

(defn- defn-form [sym]
  (some (fn [form]
          (when (and (seq? form)
                     (#{'defn 'defn-} (first form))
                     (= sym (second form)))
            form))
        all-forms))

(defn- form-contains? [pred form]
  (let [hit? (atom false)]
    (walk/postwalk (fn [x] (when (pred x) (reset! hit? true)) x) form)
    @hit?))

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
    (is (form-contains? #(= % :ambiguous-frame) start)
        "start-recording! must refuse an unresolvable frame for app-db/sub signals")))

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
    (doseq [mutator ["pair-dispatch" "reset-frame-db!" "app-db-reset!"
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
