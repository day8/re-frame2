;;;; tests/runtime/registrar_describe_test.clj
;;;;
;;;; Babashka-runnable structural pin (rf2-l7vnd) that
;;;; `registrar-describe` strips the raw `:handler-fn` value from its
;;;; return before handing it to the MCP wire.
;;;;
;;;; Why this test exists:
;;;;
;;;; `re-frame.core/handler-meta` carries a `:handler-fn` slot whose
;;;; value is a raw Function. `pr-str` of a Function emits
;;;; `#object[Function "..."]` — unreadable EDN on the MCP wire. Before
;;;; rf2-l7vnd the MCP server's `read-edn-safe` then fell back to the
;;;; raw string, and the `handler-meta` tool surfaced
;;;; `{:ok? false :reason :unexpected-shape :value "{...}"}` — a
;;;; "failed" envelope with the actual handler data embedded as a
;;;; string. Every operator hit this on every `handler-meta` call.
;;;;
;;;; The fix is to drop `:handler-fn` server-side so the wire EDN is
;;;; clean by construction. The hash (`:handler-fn-hash`) is the
;;;; wire-friendly substitute consumers actually use.
;;;;
;;;; Run: bb tests/runtime/registrar_describe_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(ns registrar-describe-test
 (:require [clojure.java.io :as io]
 [clojure.test :refer [deftest is run-tests]]
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

(def ^:private registrar-describe-form
 (some (fn [form]
 (when (and (seq? form)
 (= 'defn (first form))
 (= 'registrar-describe (second form)))
 form))
 all-forms))

(deftest registrar-describe-defn-present
 (is (some? registrar-describe-form)
 (str "preload/re_frame2_pair/runtime.cljs must define `registrar-describe` "
 "(the runtime surface the MCP `handler-meta` tool calls).")))

(deftest registrar-describe-dissocs-handler-fn
 (is (form-contains? (fn [node]
 (and (seq? node)
 (= 'dissoc (first node))
 (some #(= :handler-fn %) (rest node))))
 registrar-describe-form)
 (str "registrar-describe MUST `(dissoc :handler-fn)` before returning so the "
 "MCP wire EDN stays readable. The raw Function ref emits "
 "`#object[Function ...]` under pr-str, which the MCP read-edn-safe "
 "cannot parse — surfacing `:unexpected-shape` with the real payload "
 "buried as a string. See bead rf2-l7vnd.")))

(deftest registrar-describe-still-carries-handler-fn-hash
 (is (form-contains? (fn [node] (= :handler-fn-hash node))
 registrar-describe-form)
 (str "registrar-describe must still emit `:handler-fn-hash` — the wire-"
 "friendly substitute consumers (hot-reload probing, tail-build) "
 "actually use. Dropping it would silently break those flows.")))

(let [{:keys [fail error]} (run-tests 'registrar-describe-test)]
 (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
