(ns re-frame2-pair-mcp.cljs-eval-value-test
  "Unit tests for `nrepl/cljs-eval-value` — the value-unwrap seam every
  tool's runtime read flows through (rf2-ynjts.19).

  ## Why this suite exists

  `cljs-eval-value` is the single most load-bearing fn in the artefact:
  every tool that reads from the runtime (`snapshot`, `get-path`,
  `dispatch`, `eval-cljs`, the probe, …) calls it to turn shadow-cljs's
  string-encoded `cljs-eval` response into the actual CLJS value. Yet
  the whole existing corpus STUBS `cljs-eval-value` itself (see
  `test_utils/with-stubbed-eval!`, `eval_cljs_test`, `conformance_test`)
  — so its own unwrap logic was never exercised by a unit test. A
  regression in any of its branches (the `:results` peek, the
  `:ex`-reject, the inner `:err`-reject, the blank→nil short-circuit, or
  the `read-edn-safe` parse-failure fallback) would slip past 707
  green tests because every one of them mocks the very fn under test.

  ## Seam

  `cljs-eval-value` calls the lower-level `nrepl/cljs-eval` and `.then`s
  the resolved combined-response map `{:value :out :err :status :ex}`.
  We `set!` `nrepl/cljs-eval` (one layer below the SUT) to canned
  response maps and assert the unwrapped value / rejection. The real
  `cljs-eval-value` runs unmocked — this is the inverse posture to the
  rest of the corpus.

  ## shadow's response shape

  shadow-cljs's `cljs-eval` API returns a string-encoded EDN map like
  `{:results [\"42\"] :ns user}` in the `:value` slot of the nREPL
  combined response. `cljs-eval-value` reads the outer EDN, peeks the
  LAST `:results` entry (itself an EDN-encoded string), and reads THAT
  to get the value. Two EDN reads, nested."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ---------------------------------------------------------------------------
;; Seam — stub the lower-level `cljs-eval` so the real `cljs-eval-value`
;; runs its unwrap logic against a canned combined-response map.
;; ---------------------------------------------------------------------------

(defn- with-stubbed-cljs-eval!
  "Install a stub `nrepl/cljs-eval` that resolves to `resp` (the combined
  nREPL response map the real fn would build). Run `body-fn` (returns a
  Promise) and restore the original in `.finally` so cleanup outlives
  async resolution. Both 3- and 4-arity are spelled out — CLJS direct-
  arity dispatch calls `...$arity$3` / `...$arity$4`, which a
  rest-arg fn would not expose."
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

;; ---------------------------------------------------------------------------
;; Happy path — the nested-EDN unwrap shadow actually produces.
;; ---------------------------------------------------------------------------

(deftest unwraps-shadow-results-vector-to-value
  ;; shadow wraps the eval result as `{:results ["<edn>"] :ns user}` in
  ;; the :value string. The LAST :results entry is re-read as EDN. A
  ;; scalar 42 rides as the string "42" inside the results vector inside
  ;; the outer map string.
  (async done
    (-> (with-stubbed-cljs-eval! {:value "{:results [\"42\"] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "(+ 40 2)")))
        (.then (fn [v]
                 (is (= 42 v) "scalar unwrapped from the nested :results EDN")
                 (done))))))

(deftest unwraps-nested-collection-value
  ;; A map value round-trips through both EDN reads intact.
  (async done
    (-> (with-stubbed-cljs-eval!
          {:value "{:results [\"{:a 1 :b [2 3]}\"] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= {:a 1 :b [2 3]} v) "nested collection unwrapped")
                 (done))))))

(deftest peeks-last-results-entry
  ;; Multiple :results entries (multi-form eval) — only the LAST is the
  ;; value the caller wants; the earlier ones are intermediate. `peek`
  ;; on the vector returns the final entry.
  (async done
    (-> (with-stubbed-cljs-eval!
          {:value "{:results [\"1\" \"2\" \"99\"] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= 99 v) "last :results entry wins")
                 (done))))))

;; ---------------------------------------------------------------------------
;; Blank value → nil. shadow returns a blank :value for a build with no
;; live runtime; the caller (eval-cljs / probe) discriminates this from a
;; genuine nil via the runtime sentinel preflight, but the unwrap itself
;; collapses blank → nil.
;; ---------------------------------------------------------------------------

(deftest blank-value-resolves-nil
  (async done
    (-> (with-stubbed-cljs-eval! {:value ""}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (nil? v) "empty :value string resolves to nil")
                 (done))))))

(deftest absent-value-resolves-nil
  ;; No :value key at all (e.g. a status-only frame). `(str nil)` is
  ;; blank, so the same short-circuit fires.
  (async done
    (-> (with-stubbed-cljs-eval! {:out "" :err "" :status #{"done"}}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (nil? v) "missing :value resolves to nil")
                 (done))))))

;; ---------------------------------------------------------------------------
;; :ex present → reject. An nREPL-level eval exception (compile error,
;; thrown form) must surface as a rejected Promise, never a resolved nil
;; or partial value — the caller's .catch turns it into the transport
;; error envelope.
;; ---------------------------------------------------------------------------

(deftest ex-rejects-with-error
  (async done
    (-> (with-stubbed-cljs-eval!
          {:ex "class clojure.lang.ExceptionInfo" :err "Unable to resolve symbol: foo"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "foo")))
        (.then (fn [_]
                 (is false "an :ex response MUST reject, not resolve")
                 (done)))
        (.catch (fn [err]
                  (is (instance? js/Error err))
                  (let [m (.-message err)]
                    (is (re-find #"nREPL eval error" m) "structured eval-error prefix")
                    (is (re-find #"ExceptionInfo" m) ":ex text carried into the message")
                    (is (re-find #"Unable to resolve symbol" m)
                        ":err detail appended when present"))
                  (done))))))

(deftest ex-without-err-still-rejects
  ;; :ex present but :err blank — the message omits the " — <err>" suffix
  ;; but still rejects with the :ex text.
  (async done
    (-> (with-stubbed-cljs-eval! {:ex "boom" :err ""}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [_]
                 (is false "an :ex response MUST reject even with blank :err")
                 (done)))
        (.catch (fn [err]
                  (let [m (.-message err)]
                    (is (re-find #"nREPL eval error: boom" m))
                    (is (not (re-find #" — " m)) "no err-suffix when :err is blank"))
                  (done))))))

;; ---------------------------------------------------------------------------
;; Inner :err map → reject. When the OUTER EDN parses to a map carrying
;; a non-blank :err (a shadow-side cljs-eval compile warning/error,
;; distinct from an nREPL :ex), the unwrap rejects rather than returning
;; the error map as a "value". rf2-mvdwv promotes this to a structured
;; ex-info carrying `:reason :rf.error/eval-cljs-compile-error`.
;; ---------------------------------------------------------------------------

(deftest inner-err-map-rejects
  (async done
    (-> (with-stubbed-cljs-eval!
          {:value "{:err \"Cannot infer target type\"}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [_]
                 (is false "an outer map with :err MUST reject")
                 (done)))
        (.catch (fn [err]
                  (is (re-find #"cljs eval compile error/warning: Cannot infer target type"
                               (.-message err))
                      "inner :err surfaced as a distinct cljs-eval compile-error message")
                  (is (= :rf.error/eval-cljs-compile-error (:reason (ex-data err)))
                      "rejection carries the structured compile-error reason")
                  (is (= "Cannot infer target type" (:err (ex-data err)))
                      ":err text rides on the ex-data verbatim")
                  (done))))))

;; ---------------------------------------------------------------------------
;; rf2-mvdwv — an UNRESOLVED SYMBOL is the headline regression. shadow
;; emits an `:eval-compile-warnings` REPL message: the analyzer warning
;; text lands in the response `:err`, yet shadow STILL pushes a "nil" into
;; `:results`. The pre-fix branch order peeked `:results` first, so the
;; "nil" won and the compile warning was swallowed as a silent
;; `{:ok? true :value nil}`. The :err branch now takes precedence so the
;; failure surfaces instead of nil'ing out.
;; ---------------------------------------------------------------------------

(deftest unresolved-symbol-warning-rejects-not-silent-nil
  (async done
    (-> (with-stubbed-cljs-eval!
          ;; The exact shadow shape for an undeclared var: a "nil" result
          ;; sitting alongside the analyzer warning in :err.
          {:value (str "{:results [\"nil\"] "
                       ":err \"WARNING: Use of undeclared Var re-frame.core/frame-db at line 1 <eval>\" "
                       ":ns cljs.user}")}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "re-frame.core/frame-db")))
        (.then (fn [v]
                 (is false (str "an unresolved symbol MUST reject, never resolve "
                                "(got " (pr-str v) ", the silent-nil bug)"))
                 (done)))
        (.catch (fn [err]
                  (is (instance? js/Error err))
                  (is (re-find #"Use of undeclared Var re-frame.core/frame-db"
                               (.-message err))
                      "the analyzer warning text is carried into the rejection")
                  (let [data (ex-data err)]
                    (is (= :rf.error/eval-cljs-compile-error (:reason data))
                        "structured compile-error reason")
                    (is (re-find #"undeclared Var" (:err data))
                        ":err text rides on the ex-data")
                    (is (string? (:hint data)) "a corrective hint is offered"))
                  (done))))))

(deftest clean-results-with-blank-err-still-resolves-value
  ;; A clean eval leaves :err blank ("") — the :err branch must NOT fire
  ;; on a blank :err, so the value still unwraps normally. Pins that the
  ;; rf2-mvdwv precedence flip doesn't hijack the happy path.
  (async done
    (-> (with-stubbed-cljs-eval!
          {:value "{:results [\"42\"] :err \"\" :ns cljs.user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "(+ 40 2)")))
        (.then (fn [v]
                 (is (= 42 v) "a blank :err does not block the value unwrap")
                 (done))))))

;; ---------------------------------------------------------------------------
;; Non-:results map → returned as-is. A :value that parses to a map
;; WITHOUT a :results vector (and without :err) is the value itself —
;; some lower-level callers eval forms whose result is a bare map. The
;; `:else` arm returns `outer` unchanged.
;; ---------------------------------------------------------------------------

(deftest non-results-map-returned-verbatim
  (async done
    (-> (with-stubbed-cljs-eval! {:value "{:custom :shape :n 7}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= {:custom :shape :n 7} v)
                     "outer map without :results returned verbatim")
                 (done))))))

(deftest scalar-outer-value-returned-verbatim
  ;; A :value that parses to a bare scalar (not a map) takes the :else
  ;; arm too and rides back unchanged.
  (async done
    (-> (with-stubbed-cljs-eval! {:value "123"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (= 123 v) "bare scalar outer value returned verbatim")
                 (done))))))

;; ---------------------------------------------------------------------------
;; read-edn-safe fallback — an unparseable :value must NOT throw the
;; whole pipeline. The contract (nrepl/read-edn-safe) is: log to stderr,
;; return the raw string. Exercised here through the public SUT so the
;; private fn's behaviour is pinned without reaching for its var.
;; ---------------------------------------------------------------------------

(deftest unparseable-outer-value-falls-back-to-raw-string
  (async done
    ;; Silence the expected stderr log from read-edn-safe so the
    ;; otherwise-quiet run stays clean (the log is SUT behaviour, not a
    ;; failure).
    (let [orig-err (.-error js/console)]
      (set! (.-error js/console) (fn [& _] nil))
      (-> (with-stubbed-cljs-eval! {:value "#unbalanced ((("}
            (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
          (.then (fn [v]
                   (is (= "#unbalanced (((" v)
                       "unparseable outer EDN falls back to the raw string, never throws")))
          (.finally (fn []
                      (set! (.-error js/console) orig-err)
                      (done)))))))

(deftest unparseable-inner-results-entry-falls-back-to-raw-string
  ;; The OUTER parses fine to a :results vector, but the inner entry is
  ;; itself unparseable EDN. The inner read-edn-safe returns the raw
  ;; string rather than throwing — the value the caller gets is the
  ;; unparseable text, surfaced (not silently dropped).
  (async done
    (let [orig-err (.-error js/console)]
      (set! (.-error js/console) (fn [& _] nil))
      (-> (with-stubbed-cljs-eval!
            {:value "{:results [\"((( nope\"] :ns user}"}
            (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
          (.then (fn [v]
                   (is (= "((( nope" v)
                       "unparseable inner :results entry falls back to its raw string")))
          (.finally (fn []
                      (set! (.-error js/console) orig-err)
                      (done)))))))

;; ---------------------------------------------------------------------------
;; Empty :results vector → nil. `peek` on `[]` is nil; the `when-let`
;; guards the inner read so the call resolves nil rather than reading
;; `(read-edn-safe nil)`.
;; ---------------------------------------------------------------------------

(deftest empty-results-vector-resolves-nil
  (async done
    (-> (with-stubbed-cljs-eval! {:value "{:results [] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [v]
                 (is (nil? v) "empty :results vector resolves to nil")
                 (done))))))

;; ---------------------------------------------------------------------------
;; :ex takes precedence over a present :value — even if shadow somehow
;; returned both, the exception path wins (fail loud over a partial
;; value).
;; ---------------------------------------------------------------------------

(deftest ex-precedence-over-value
  (async done
    (-> (with-stubbed-cljs-eval!
          {:ex "thrown" :err "detail" :value "{:results [\"42\"] :ns user}"}
          (fn [] (nrepl/cljs-eval-value (fresh-conn) :app "form")))
        (.then (fn [_]
                 (is false ":ex must win over a present :value")
                 (done)))
        (.catch (fn [err]
                  (is (re-find #"nREPL eval error: thrown" (.-message err)))
                  (done))))))
