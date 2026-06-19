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

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns registrar-describe-test
 (:require [clojure.test :refer [deftest is run-tests]]
 [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj
;; (rf2-yrpt90). Alias the vars the assertions below use.
(def ^:private all-forms rt/all-forms)
(def ^:private form-contains? rt/form-contains?)

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

(deftest registrar-describe-strips-nested-fns
 (is (form-contains? (fn [node] (= 'strip-fns node))
 registrar-describe-form)
 (str "registrar-describe MUST run `strip-fns` over the meta map so the "
 "resources-artefact kinds (`:resource` / `:mutation` / "
 "`:resource-scope`, EP-0016 / rf2-f8s9g6) serialize cleanly. Their "
 "spec lives under `:rf/resource` / `:rf/mutation` / `:rf/resource-scope` "
 "with NESTED handler fns (`:request` / `:tags` / `:invalidates` / "
 "`:populates` / `:resolve`). Dropping only the top-level `:handler-fn` "
 "leaves those nested handles, so the response still `pr-str`s to "
 "`#object[Function]` and trips the wire codec's `:unserializable` "
 "path — hiding the inspectable structure (a resolver's `:inputs` map "
 "+ `:whole-db?` cost, the EP-0016 disposition-2 promise). See rf2-f8s9g6.")))

(def ^:private strip-fns-form
 ;; `strip-fns` is a private `defn-`, so match both `defn` and `defn-`.
 (some (fn [form]
 (when (and (seq? form)
 (contains? #{'defn 'defn-} (first form))
 (= 'strip-fns (second form)))
 form))
 all-forms))

(deftest strip-fns-defn-present
 (is (some? strip-fns-form)
 (str "preload/re_frame2_pair/runtime.cljs must define `strip-fns` — the "
 "recursive fn→`:rf/fn` sentinel walker registrar-describe uses to keep "
 "nested handler fns off the EDN wire (rf2-f8s9g6).")))

(deftest strip-fns-handles-fn-and-recurses
 (is (and (form-contains? (fn [node] (= 'fn? node)) strip-fns-form)
 (form-contains? (fn [node] (= 'map? node)) strip-fns-form))
 (str "strip-fns must test `fn?` (replace a Function with the sentinel) AND "
 "recurse through `map?` collections (the nested spec slots live under "
 "`:rf/resource-scope` etc.). A flat top-level-only strip would leave the "
 "nested `:resolve` / `:request` fns on the wire.")))

(let [{:keys [fail error]} (run-tests 'registrar-describe-test)]
 (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
