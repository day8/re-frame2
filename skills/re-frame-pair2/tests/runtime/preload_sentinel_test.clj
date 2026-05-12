;;;; tests/runtime/preload_sentinel_test.clj
;;;;
;;;; Babashka-runnable structural verification that `preload/re_frame_pair2/
;;;; runtime.cljs` installs the `js/globalThis.__re_frame_pair2_runtime`
;;;; marker at load time.
;;;;
;;;; Why this test exists (rf2-7dvg):
;;;;
;;;;   The MCP server's `discover-app` no longer cljs-eval-injects the
;;;;   runtime; it probes the load-time marker and refuses with
;;;;   `:reason :runtime-not-preloaded` if absent. The probe depends on
;;;;   the preload setting `js/globalThis.__re_frame_pair2_runtime` to a
;;;;   non-nil value. If that side-effect ever regresses (someone removes
;;;;   the `defonce`, or renames the global) every pair2 session breaks
;;;;   the same way: "runtime not preloaded" despite the preload being
;;;;   in place. This structural check fails fast at PR time.
;;;;
;;;; Why a structural test rather than a runtime test:
;;;;
;;;;   The runtime file is CLJS-only; bb can't execute it. We parse the
;;;;   forms and assert that:
;;;;     1. A top-level `defonce` form references both `js/globalThis`
;;;;        and the literal string `"__re_frame_pair2_runtime"`.
;;;;     2. The same form passes `session-id` into the marker so the
;;;;        in-browser ns has a usable handle.
;;;;
;;;; Run:    bb tests/runtime/preload_sentinel_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns preload-sentinel-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [clojure.walk :as walk]))

(def ^:private runtime-cljs-path
  (some (fn [p] (when (.exists (io/file p)) p))
        ["preload/re_frame_pair2/runtime.cljs"
         "skills/re-frame-pair2/preload/re_frame_pair2/runtime.cljs"
         "../preload/re_frame_pair2/runtime.cljs"]))

(when-not runtime-cljs-path
  (binding [*out* *err*]
    (println "ERROR: cannot locate preload/re_frame_pair2/runtime.cljs from"
             (System/getProperty "user.dir")))
  (System/exit 2))

(defn- read-all-forms [^String src]
  (let [pbr (java.io.PushbackReader. (java.io.StringReader. src))]
    (loop [acc []]
      (let [form (try (read {:read-cond :allow :features #{:cljs}} pbr)
                      (catch Exception _ ::eof))]
        (if (= ::eof form) acc (recur (conj acc form)))))))

(def ^:private all-forms
  (read-all-forms (slurp runtime-cljs-path)))

(defn- form-contains? [pred form]
  (let [hit? (atom false)]
    (walk/postwalk
     (fn [node] (when (pred node) (reset! hit? true)) node)
     form)
    @hit?))

(def ^:private sentinel-form
  (some (fn [form]
          (when (and (seq? form)
                     (= 'defonce (first form))
                     (form-contains? #(= "__re_frame_pair2_runtime" %) form))
            form))
        all-forms))

(deftest sentinel-defonce-present
  (is (some? sentinel-form)
      (str "preload/re_frame_pair2/runtime.cljs must contain a top-level "
           "`defonce` form that references the \"__re_frame_pair2_runtime\" "
           "string (the global marker the MCP server probes).")))

(deftest sentinel-references-globalThis
  (is (form-contains? #(= 'js/globalThis %) sentinel-form)
      "Sentinel install must target `js/globalThis` so it's reachable from any context."))

(deftest sentinel-carries-session-id
  (is (form-contains? #(= 'session-id %) sentinel-form)
      "Sentinel must include `session-id` so the in-browser runtime exposes a usable handle."))

(let [{:keys [fail error]} (run-tests 'preload-sentinel-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
