(ns re-frame2-pair-mcp.cljs-eval-value-test
  "Unit tests for `nrepl/cljs-eval-value`, which unwraps shadow's
  string-encoded `cljs-eval` reply (`{:results [\"<edn>\"] ...}`) into the
  value every runtime read returns. Most of the suite stubs this fn, so these
  tests stub the layer below it, `nrepl/cljs-eval`, and run it for real. The
  compile-error branch is exercised end to end by `eval_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- with-stubbed-cljs-eval!
  "Stub `nrepl/cljs-eval` to resolve `resp` (both arities: CLJS calls the
  arity slots directly), run the Promise-returning `body-fn`, and restore."
  [resp body-fn]
  (let [orig nrepl/cljs-eval
        stub (fn
               ([_conn _build _form] (js/Promise.resolve resp))
               ([_conn _build _form _opts] (js/Promise.resolve resp)))]
    (set! nrepl/cljs-eval stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-cljs-eval! stub orig))))))

(defn- fresh-conn [] (nrepl/make-conn 0 "127.0.0.1"))

(deftest peeks-last-results-entry
  ;; shadow's clean reply carries a blank :err, which must not read as a failure.
  (async done
    (-> (with-stubbed-cljs-eval!
          {:value "{:results [\"1\" \"2\" \"99\"] :err \"\" :ns cljs.user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= 99 v) "the last :results entry is the value")
                 (done))))))

(deftest blank-value-resolves-nil
  (async done
    (-> (with-stubbed-cljs-eval! {:value ""}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (nil? v) "an empty :value resolves to nil")
                 (done))))))

(deftest ex-rejects-with-error
  ;; :ex wins over a :value shadow also returned.
  (async done
    (-> (with-stubbed-cljs-eval!
          {:ex "class clojure.lang.ExceptionInfo" :err "Unable to resolve symbol: foo"
           :value "{:results [\"42\"] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "foo")))
        (.then (fn [_]
                 (is false "an :ex response MUST reject, not resolve"))
               (fn [err]
                 (is (= "nREPL eval error: class clojure.lang.ExceptionInfo — Unable to resolve symbol: foo"
                        (.-message err))
                     "the rejection carries the :ex text and the :err detail")))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; Tags. An app can print a value under a tag only its own reader registry
;; knows; the decoder keeps it as an inert `tagged-literal` rather than
;; failing the whole reply to its raw string. Standard readers still apply,
;; nothing is registered globally, and the reader-eval tag `#=` is refused.
;; ---------------------------------------------------------------------------

(defn- printed-result
  "shadow's outer reply carrying `inner`, the runtime's printed value."
  [inner]
  {:value (pr-str {:results [inner] :ns 'cljs.user})})

(defn- with-quiet-stderr
  "Run the Promise-returning `body-fn` with `console.error` silenced: the
  decoder logs a parse failure."
  [body-fn]
  (let [orig-err (.-error js/console)]
    (set! (.-error js/console) (fn [& _] nil))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! (.-error js/console) orig-err))))))

(deftest application-tags-decode-as-inert-tagged-literals
  (async done
    (-> (with-stubbed-cljs-eval!
          (printed-result (str "{:at #instant \"2026-01-01T00:00:00Z\""
                               " :outer #app/outer {:inner #app/inner [1 :k/v]}"
                               " :quoted \"#=(not-a-tag)\"}"))
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= {:at     (tagged-literal 'instant "2026-01-01T00:00:00Z")
                         :outer  (tagged-literal 'app/outer {:inner (tagged-literal 'app/inner [1 :k/v])})
                         :quoted "#=(not-a-tag)"}
                        v)
                     "unknown tags keep tag and form at every depth; `#=` inside a string is plain data")))
        (.then (fn [_] (done))))))

(deftest standard-reader-tags-keep-their-readers
  (async done
    (-> (with-stubbed-cljs-eval!
          (printed-result (str "{:t #inst \"2026-01-01T00:00:00.000-00:00\""
                               " :u #uuid \"8e55e886-374f-4cf6-9c12-09ea4611a749\"}"))
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= {:t (js/Date. 1767225600000) :u (uuid "8e55e886-374f-4cf6-9c12-09ea4611a749")} v)
                     "#inst reads to a Date and #uuid to a UUID")
                 (is (thrown? js/Error (cljs.reader/read-string "#instant \"2026-01-01T00:00:00Z\""))
                     "the inert fallback is per read: the global reader registry is unchanged")))
        (.then (fn [_] (done))))))

(deftest reader-eval-tag-is-refused
  (async done
    (-> (with-quiet-stderr
          (fn []
            (with-stubbed-cljs-eval! (printed-result "{:x #=(js/alert 1)}")
              (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))))
        (.then (fn [v]
                 (is (= "{:x #=(js/alert 1)}" v)
                     "a #= form is refused and the reply falls back to its raw string")))
        (.then (fn [_] (done))))))

(deftest malformed-edn-beside-a-tag-still-falls-back
  (async done
    (-> (with-quiet-stderr
          (fn []
            (with-stubbed-cljs-eval! (printed-result "{:at #instant \"2026-01-01T00:00:00Z\" :b [1 2")
              (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))))
        (.then (fn [v]
                 (is (= "{:at #instant \"2026-01-01T00:00:00Z\" :b [1 2" v)
                     "malformed data falls back to its raw string — a tag does not mask it")))
        (.then (fn [_] (done))))))
