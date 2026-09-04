;;;; tests/runtime/dry_run_sink_pin_test.clj
;;;;
;;;; Babashka-runnable STRUCTURAL PIN for the dry-run no-effect guarantee in
;;;; `preload/re_frame2_pair/runtime.cljs` (rf2-j538f7.39).
;;;;
;;;; Why a structural pin rather than a live runtime test:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is CLJS-only (loaded via
;;;; shadow-cljs `:devtools :preloads`) so it does not run under bb. The
;;;; BEHAVIOURAL guarantee — an image-only inline fx and every reject-tier
;;;; reserved fx are RECORDED but never EXECUTED — is proven at the core
;;;; executor by
;;;; `implementation/core/test/re_frame/dry_run_effect_sink_cljs_test.cljc`
;;;; (runs on `clojure -M:test` AND `npm run test:cljs`). What we pin HERE is
;;;; the source-level contract that `dispatch-dry-run` uses that executor
;;;; SINK and does NOT regress to the old process-global fx enumeration —
;;;; AC4's structural pin: "a regression/structural pin fails if dry-run
;;;; returns to `(rf/registrations :fx)` coverage inference."
;;;;
;;;; Run: bb tests/runtime/dry_run_sink_pin_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-sink-pin-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(def ^:private defn-form rt/defn-named)
(def ^:private form-contains? rt/form-contains?)

(defn- mentions-sym? [form needle]
  (form-contains? #(= % needle) form))

(defn- mentions-name-substr? [form substr]
  (form-contains? #(and (symbol? %) (str/includes? (str %) substr)) form))

;; ---------------------------------------------------------------------------
;; The old process-global enumeration helpers MUST be gone (AC4). If any of
;; these reappears, dry-run has regressed to inferring "no effect" from a
;; registrar snapshot — the very inference that missed image-only inline fx
;; and could not suppress the reject-tier reserved fx.
;; ---------------------------------------------------------------------------

(deftest enumeration-helpers-are-deleted
  (doseq [sym '[registered-fx-ids build-dry-run-overrides
                dry-run-recordings record-fx!]]
    (is (nil? (defn-form sym))
        (str "the old override-enumeration helper `" sym "` must be DELETED — "
             "dry-run's no-effect guarantee is now executor-sited, not "
             "registrar-enumerated"))))

;; ---------------------------------------------------------------------------
;; dispatch-dry-run must BIND the framework effect sink, and must NOT infer
;; coverage from the process-global :fx registrar.
;; ---------------------------------------------------------------------------

(deftest dispatch-dry-run-binds-the-effect-sink
  (let [form (defn-form 'dispatch-dry-run)]
    (is (some? form) "dispatch-dry-run must be defined")
    (is (mentions-sym? form 'binding)
        "dispatch-dry-run must establish a dynamic binding around dispatch-sync")
    (is (mentions-sym? form 'rf.fx/*effect-sink*)
        (str "dispatch-dry-run must bind re-frame.fx/*effect-sink* — spelled "
             "`rf.fx/*effect-sink*` at the use site, since the preload requires "
             "`[re-frame.fx :as rf.fx]` under the canonical require-alias "
             "dialect (rf2-7sx1) — the universal effect-executor sink, and the "
             "structural no-effect guarantee"))
    (is (mentions-sym? form 'rf/dispatch-sync)
        "dispatch-dry-run must run the cascade via dispatch-sync inside the sink")))

(deftest dispatch-dry-run-does-not-enumerate-the-registrar
  (let [form (defn-form 'dispatch-dry-run)]
    (is (not (mentions-name-substr? form "registrations"))
        (str "dispatch-dry-run must NOT read (rf/registrations :fx) — coverage "
             "is no longer inferred from the process-global registrar (AC4)"))
    (is (not (mentions-sym? form 'build-dry-run-overrides))
        "dispatch-dry-run must not build a per-fx override map")))

;; ---------------------------------------------------------------------------
;; Caller :fx-overrides is REJECTED loudly (AC5) — an override can no longer
;; influence a hypothetical resolution without executing a handler, so the
;; sink-based dry-run refuses it rather than silently ignoring it.
;; ---------------------------------------------------------------------------

(deftest dispatch-dry-run-rejects-caller-fx-overrides
  (let [form (defn-form 'dispatch-dry-run)]
    (is (form-contains? #(= % :fx-overrides-unsupported) form)
        (str "dispatch-dry-run must reject a caller :fx-overrides with the "
             "loud :fx-overrides-unsupported reason (AC5)"))
    (is (form-contains? #(= % :fx-overrides) form)
        "dispatch-dry-run must inspect the caller :fx-overrides opt to reject it")))

(let [{:keys [fail error]} (run-tests 'dry-run-sink-pin-test)]
  (System/exit (if (pos? (+ fail error)) 1 0)))
