;;;; tests/runtime/preload_sentinel_test.clj
;;;;
;;;; Babashka-runnable structural verification that `preload/re_frame2_pair/
;;;; runtime.cljs` installs the `js/globalThis.__re_frame2_pair_runtime`
;;;; marker at load time.
;;;;
;;;; Why this test exists:
;;;;
;;;; The MCP server's `discover-app` no longer cljs-eval-injects the
;;;; runtime; it probes the load-time marker and refuses with
;;;; `:reason :runtime-not-preloaded` if absent. The probe depends on
;;;; the preload setting `js/globalThis.__re_frame2_pair_runtime` to a
;;;; non-nil value. If that side-effect ever regresses (someone removes
;;;; the `defonce`, or renames the global) every re-frame2-pair session breaks
;;;; the same way: "runtime not preloaded" despite the preload being
;;;; in place. This structural check fails fast at PR time.
;;;;
;;;; Why a structural test rather than a runtime test:
;;;;
;;;; The runtime file is CLJS-only; bb can't execute it. We parse the
;;;; forms and assert that:
;;;; 1. A top-level `defonce` form references both `js/globalThis`
;;;; and the literal string `"__re_frame2_pair_runtime"`.
;;;; 2. The same form passes `session-id` into the marker so the
;;;; in-browser ns has a usable handle.
;;;;
;;;; Run: bb tests/runtime/preload_sentinel_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns preload-sentinel-test
 (:require [clojure.test :refer [deftest is run-tests]]
 [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj
;; (rf2-yrpt90). Alias the vars the assertions below use.
(def ^:private all-forms rt/all-forms)
(def ^:private form-contains? rt/form-contains?)

(def ^:private sentinel-form
 (some (fn [form]
 (when (and (seq? form)
 (= 'defonce (first form))
 (form-contains? #(= "__re_frame2_pair_runtime" %) form))
 form))
 all-forms))

(deftest sentinel-defonce-present
 (is (some? sentinel-form)
 (str "preload/re_frame2_pair/runtime.cljs must contain a top-level "
 "`defonce` form that references the \"__re_frame2_pair_runtime\" "
 "string (the global marker the MCP server probes).")))

(deftest sentinel-references-globalThis
 (is (form-contains? #(= 'js/globalThis %) sentinel-form)
 "Sentinel install must target `js/globalThis` so it's reachable from any context."))

(deftest sentinel-carries-session-id
 (is (form-contains? #(= 'session-id %) sentinel-form)
 "Sentinel must include `session-id` so the in-browser runtime exposes a usable handle."))

(let [{:keys [fail error]} (run-tests 'preload-sentinel-test)]
 (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
