(ns day8.re-frame2-causa-mcp.tools.eval-cljs-test
  "Unit tests for the T-Eval-1 tool `eval-cljs` (rf2-8xzoe.29).

  Pins:
    - `--allow-eval` gate — default OFF returns refusal envelope
      without nREPL hit; ON routes through the runtime.
    - Form composition wraps origin binding around the user form so
      synchronous-extent mutations tag `:causa-mcp`.
    - `:missing-form` pre-flight validation.
    - Runtime envelope passthrough + elision counter stamping."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.eval-cljs :as ec]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)
(def ^:private orig-ensure-runtime! probe/ensure-runtime!)
(def ^:private orig-allow-eval? (ec/allow-eval-enabled?))

(defn- restore-all! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value)
  (set! probe/ensure-runtime! orig-ensure-runtime!)
  (ec/set-allow-eval! orig-allow-eval?))

(use-fixtures :each {:after (fn [] (restore-all!))})

(defn- stub-runtime! [runtime-env]
  (set! probe/ensure-runtime! (fn [_ _] (js/Promise.resolve nil)))
  (set! nrepl/cljs-eval-value
        (fn
          ([_ _ _]   (js/Promise.resolve runtime-env))
          ([_ _ _ _] (js/Promise.resolve runtime-env)))))

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? ec/eval-cljs-tool))
  (is (fn? ec/build-form))
  (is (fn? ec/shape-envelope))
  (is (fn? ec/set-allow-eval!))
  (is (fn? ec/allow-eval-enabled?))
  (is (= "eval-cljs" (:name ec/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "eval-cljs"))))

;; --- Launch-flag gate -------------------------------------------------------

(deftest gate-default-off
  (ec/set-allow-eval! false)
  (is (false? (ec/allow-eval-enabled?))))

(deftest gate-toggle
  (ec/set-allow-eval! true)
  (is (true? (ec/allow-eval-enabled?)))
  (ec/set-allow-eval! false)
  (is (false? (ec/allow-eval-enabled?))))

(deftest gate-coerces-truthy-values
  (ec/set-allow-eval! "anything")
  (is (true? (ec/allow-eval-enabled?))
      "non-nil truthy values coerce to true via (boolean ...)")
  (ec/set-allow-eval! nil)
  (is (false? (ec/allow-eval-enabled?))))

;; --- Disabled refusal -------------------------------------------------------

(deftest gate-off-returns-refusal-without-nrepl-hit
  (async done
    (ec/set-allow-eval! false)
    ;; Stub eval to throw if called — must not be hit.
    (set! nrepl/cljs-eval-value
          (fn [& _]
            (throw (js/Error. "nREPL was called despite --allow-eval OFF"))))
    (-> (ec/eval-cljs-tool (atom {}) #js {"form" "(+ 1 2)"})
        (.then (fn [r]
                 (is (true? (j/get r :isError)))
                 (let [p (payload-of r)]
                   (is (= :rf.error/eval-cljs-disabled (:reason p)))
                   (is (string? (:hint p))))
                 (done))))))

;; --- Pre-flight validation --------------------------------------------------

(deftest missing-form-short-circuits
  (async done
    (ec/set-allow-eval! true)
    (-> (ec/eval-cljs-tool (atom {}) #js {})
        (.then (fn [r]
                 (is (= :missing-form (:reason (payload-of r))))
                 (done))))))

(deftest blank-form-short-circuits
  (async done
    (ec/set-allow-eval! true)
    (-> (ec/eval-cljs-tool (atom {}) #js {"form" ""})
        (.then (fn [r]
                 (is (= :missing-form (:reason (payload-of r))))
                 (done))))))

;; --- Form composition -------------------------------------------------------

(deftest build-form-wraps-origin-and-routes-runtime-result-shaper
  (let [form (ec/build-form "(+ 1 2)" {:include-sensitive? false
                                       :include-large? false})]
    (is (string? form))
    (is (.includes form "day8.re-frame2-causa.runtime/eval-form-result"))
    (is (.includes form ":causa-mcp")
        "synchronous-extent origin tag wraps the user form")
    (is (.includes form "(+ 1 2)")
        "user form is inlined verbatim")))

;; --- Enabled happy path -----------------------------------------------------

(deftest happy-path-via-stubs
  (async done
    (ec/set-allow-eval! true)
    (stub-runtime! {:ok? true :value 42})
    (-> (ec/eval-cljs-tool (atom {}) #js {"form" "(* 6 7)"})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (= 42 (:value p))))
                 (done))))))

;; --- Shape envelope ---------------------------------------------------------

(deftest shape-envelope-counts-elided
  (let [shaped (ec/shape-envelope
                 {:ok? true
                  :value {:big {:rf.size/large-elided {:bytes 1000}}}})]
    (is (= 1 (:elided-large shaped)))))

(deftest shape-envelope-failure-passthrough
  (let [env {:ok? false :reason :something :hint "..."}]
    (is (= env (ec/shape-envelope env)))))

(deftest probe-rejection-surfaces-runtime-not-preloaded
  (async done
    (ec/set-allow-eval! true)
    (set! probe/ensure-runtime!
          (fn [_ _]
            (js/Promise.reject
              (ex-info "causa runtime not preloaded"
                       {:reason :runtime-not-preloaded
                        :hint   "setup-hint"}))))
    (-> (ec/eval-cljs-tool (atom {}) #js {"form" "(+ 1 1)"})
        (.then (fn [r]
                 (is (= :runtime-not-preloaded (:reason (payload-of r))))
                 (done))))))
