(ns day8.re-frame2-causa-mcp.tools.tail-build-test
  "Unit tests for the T-Meta-2 tool `tail-build` (rf2-8xzoe.31).

  Pins:
    - Public surface + registration.
    - Soft-delay mode (no `:probe`) returns `:soft? true`.
    - Probe mode resolves `:ok? true` when probe value changes."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.tail-build :as tb]))

(def ^:private orig-cljs-eval-value nrepl/cljs-eval-value)

(defn- restore-stubs! []
  (set! nrepl/cljs-eval-value orig-cljs-eval-value))

(use-fixtures :each {:after (fn [] (restore-stubs!))})

(defn- payload-of [^js result]
  (-> result (j/get :content) (aget 0) (j/get :text) edn/read-string))

(deftest public-surface-resolvable
  (is (fn? tb/tail-build-tool))
  (is (= "tail-build" (:name tb/descriptor))))

(deftest registered-in-the-catalogue
  (is (some? (registry/handler-for "tail-build"))))

;; --- Soft-delay mode (no probe) ---------------------------------------------

(deftest soft-delay-mode-resolves-with-soft-flag
  (async done
    (-> (tb/tail-build-tool (atom {}) #js {})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (true? (:ok? p)))
                   (is (true? (:soft? p)))
                   (is (number? (:t p)))
                   (is (string? (:note p))))
                 (done))))))

;; --- Probe mode -------------------------------------------------------------

(deftest probe-mode-detects-value-change
  (async done
    (let [call-counter (atom 0)]
      ;; First call returns 1 (the "before" value); subsequent calls
      ;; return 2 (signaling the probe changed → reload landed).
      (set! nrepl/cljs-eval-value
            (fn
              ([_ _ _]   (js/Promise.resolve
                           (if (zero? @call-counter)
                             (do (swap! call-counter inc) 1)
                             2)))
              ([_ _ _ _] (js/Promise.resolve
                           (if (zero? @call-counter)
                             (do (swap! call-counter inc) 1)
                             2)))))
      (-> (tb/tail-build-tool (atom {}) #js {"probe" "(rand-int 100)"
                                              "wait-ms" 2000})
          (.then (fn [r]
                   (let [p (payload-of r)]
                     (is (true? (:ok? p)))
                     (is (false? (:soft? p))))
                   (done)))))))

(deftest probe-mode-times-out-when-value-unchanged
  (async done
    ;; Probe always returns the same value — wait-ms elapses with no
    ;; change → :timed-out.
    (set! nrepl/cljs-eval-value
          (fn
            ([_ _ _]   (js/Promise.resolve 42))
            ([_ _ _ _] (js/Promise.resolve 42))))
    (-> (tb/tail-build-tool (atom {}) #js {"probe" "42" "wait-ms" 200})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (false? (:ok? p)))
                   (is (= :timed-out (:reason p)))
                   (is (true? (:timed-out? p))))
                 (done))))))

(deftest probe-mode-surfaces-eval-error
  (async done
    ;; First eval (the "before" capture) rejects → :probe-failed.
    (set! nrepl/cljs-eval-value
          (fn
            ([_ _ _]   (js/Promise.reject (js/Error. "compile error: probe broken")))
            ([_ _ _ _] (js/Promise.reject (js/Error. "compile error: probe broken")))))
    (-> (tb/tail-build-tool (atom {}) #js {"probe" "(broken-form"
                                            "wait-ms" 200})
        (.then (fn [r]
                 (let [p (payload-of r)]
                   (is (false? (:ok? p)))
                   (is (= :probe-failed (:reason p)))
                   (is (.includes (:message p) "compile error")))
                 (done))))))
