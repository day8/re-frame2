(ns re-frame.ssr.ring.facade-metadata-test
  "Per rf2-l1qgjw issue 1 — pin that the canonical `re-frame.ssr.ring`
  facade carries real `:doc` + `:arglists` metadata on its public
  FUNCTION vars.

  The facade re-exposes seven sub-namespace fns. A bare `(def alias
  other-ns/fn)` copies the value but DROPS the var metadata, so REPL
  `doc`, editor hover, completion, and the JVM-introspected api-manifest
  all saw empty docs + nil arglists at the very namespace users are told
  to require. `import-fn` (in `re-frame.ssr.ring`) now copies the source
  var's authored `:doc`/`:arglists` onto the façade var. This test is the
  regression tripwire: a future re-export that reverts to a bare `def`
  (or forgets the metadata copy) re-opens the gap, and this fails.

  Data vars are EXCLUDED — `handler-defaults` (a config map) and
  `default-on-error` (a 2-arity fn VALUE held in a `def`, not a `defn`,
  so it carries no `:arglists`). `default-on-error` still gets a doc
  check below since it is a documented public surface."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.ring :as ssr-ring]))

;; The public FUNCTION surface of the facade — every entry MUST carry a
;; non-empty docstring AND a useful :arglists. (Listed explicitly rather
;; than reflected so adding a public fn forces a deliberate decision
;; about its metadata here.)
(def ^:private public-fn-syms
  '[cookie->set-cookie-header
    default-html-shell
    stream-handler
    default-streaming-prefix
    default-streaming-suffix
    ssr-handler
    ssr-middleware])

;; Documented DATA vars — checked for :doc only (no :arglists; they are
;; not fns). `handler-defaults` is a config map; `default-on-error` is a
;; fn value held in a `def`.
(def ^:private public-data-syms
  '[default-on-error
    handler-defaults])

(defn- facade-var [sym]
  (ns-resolve 're-frame.ssr.ring sym))

(deftest facade-public-fns-carry-doc-and-arglists
  (testing "rf2-l1qgjw — every public re-frame.ssr.ring FUNCTION var has a
            non-empty :doc and a non-empty :arglists (the import-fn
            metadata-preserving re-export contract)"
    (doseq [sym public-fn-syms]
      (let [v (facade-var sym)
            m (meta v)]
        (is (some? v) (str "re-frame.ssr.ring/" sym " resolves"))
        (is (and (string? (:doc m)) (seq (:doc m)))
            (str "re-frame.ssr.ring/" sym " has a non-empty :doc "
                 "(a bare `def` alias would drop it) — saw: "
                 (pr-str (:doc m))))
        (is (and (seq (:arglists m))
                 (every? vector? (:arglists m)))
            (str "re-frame.ssr.ring/" sym " has a non-empty :arglists of "
                 "param vectors (a bare `def` alias would drop it) — saw: "
                 (pr-str (:arglists m))))))))

(deftest facade-public-data-vars-carry-doc
  (testing "rf2-l1qgjw — documented public DATA vars (handler-defaults,
            default-on-error) carry a non-empty :doc; they are correctly
            excluded from the :arglists check (not fns)"
    (doseq [sym public-data-syms]
      (let [v (facade-var sym)
            m (meta v)]
        (is (some? v) (str "re-frame.ssr.ring/" sym " resolves"))
        (is (and (string? (:doc m)) (seq (:doc m)))
            (str "re-frame.ssr.ring/" sym " has a non-empty :doc — saw: "
                 (pr-str (:doc m))))))))

(deftest facade-doc-matches-implementation-contract
  (testing "rf2-l1qgjw — the re-exported :doc/:arglists are the source
            var's authored contract (not a stub), so the facade is the
            same documentation surface as the sub-namespace home var"
    ;; Spot-check two representative re-exports against their home vars:
    ;; one cookie fn and one streaming fn. If `import-fn` ever copied an
    ;; empty/placeholder doc instead of the source's, these diverge.
    (is (= (-> #'re-frame.ssr.ring/cookie->set-cookie-header meta :doc)
           (-> (requiring-resolve 're-frame.ssr.ring.cookie/cookie->set-cookie-header)
               meta :doc))
        "cookie->set-cookie-header facade :doc == home var :doc")
    (is (= (-> #'re-frame.ssr.ring/stream-handler meta :arglists)
           (-> (requiring-resolve 're-frame.ssr.ring.streaming/stream-handler)
               meta :arglists))
        "stream-handler facade :arglists == home var :arglists")))
