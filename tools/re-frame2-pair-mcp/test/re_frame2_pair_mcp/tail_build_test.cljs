(ns re-frame2-pair-mcp.tail-build-test
  "Unit tests for the `tail-build` MCP tool: the caller's pre-edit
  `:baseline` against the probe samples, in the real reload orderings,
  and the `:probe-values` diagnostics each outcome carries."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.tail-build :as tail]))

(defn- samples
  "A probe eval yielding `vs` in turn, the last one repeating."
  [vs]
  (let [q (atom vs)]
    (fn []
      (let [[v & more] @q]
        (when more (reset! q (vec more)))
        (js/Promise.resolve v)))))

(defn- rejects
  "A probe eval that throws `msg` on every call."
  [msg]
  (fn [] (js/Promise.reject (js/Error. msg))))

(defn- tail!
  "Run tail-build on `args` with `probe` stubbing the nREPL eval, hand the
  result to `check`, then finish the async test."
  [probe args check done]
  (let [orig nrepl/cljs-eval-value
        stub (fn ([_ _ _] (probe)) ([_ _ _ _] (probe)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (tail/tail-build-tool nil (tu/args->js args))))
        (.finally (fn [] (tu/restore-eval! stub orig)))
        (.then check)
        (.catch (fn [e] (is false (str "rejected: " (.-message e)))))
        (.then (fn [_] (done))))))

(defn- edn-without [r & ks]
  (apply dissoc (tu/extract-edn r) ks))

;; ---------------------------------------------------------------------------
;; Success: the first sample that differs from the pre-edit baseline.
;; ---------------------------------------------------------------------------

(deftest probe-changes-carries-baseline-initial-and-final
  ;; Slow reload: the first sample still equals the baseline, a later one differs.
  (async done
    (tail! (samples [0 1]) {:probe "(some-form)" :baseline "0" :wait-ms 1000}
           (fn [r]
             (is (= {:ok? true :soft? false :probe-values {:baseline "0" :initial 0 :final 1}}
                    (edn-without r :t))))
           done)))

(deftest reload-landed-before-first-sample-is-success
  ;; Fast reload: every sample is already the new value. A self-baseline
  ;; (the first sample as its own baseline) would time out here.
  (async done
    (tail! (samples [1]) {:probe "(some-form)" :baseline "0" :wait-ms 3000}
           (fn [r]
             (is (= {:ok? true :soft? false :probe-values {:baseline "0" :initial 1 :final 1}}
                    (edn-without r :t))))
           done)))

(deftest string-baseline-without-quotes-still-polls
  ;; A string probe captured without its quotes matches through its `str`
  ;; rendering, so the equal first sample is not an instant false success.
  (async done
    (tail! (samples ["abc" "abd"]) {:probe "(some-form)" :baseline "abc" :wait-ms 1000}
           (fn [r]
             (is (= {:ok? true :soft? false :probe-values {:baseline "abc" :initial "abc" :final "abd"}}
                    (edn-without r :t))))
           done)))

;; ---------------------------------------------------------------------------
;; Failures — each `:ok? false` rides isError.
;; ---------------------------------------------------------------------------

(deftest probe-stuck-on-baseline-times-out
  ;; The control for the fast-reload success: a stable value must still time out.
  (async done
    (tail! (samples [42]) {:probe "(some-stuck-form)" :baseline "42" :wait-ms 250}
           (fn [r]
             (is (tu/error? r))
             (is (= {:ok?          false
                     :reason       :timed-out
                     :timed-out?   true
                     :probe-values {:baseline "42" :initial 42 :final 42}}
                    (edn-without r :note))))
           done)))

(deftest probe-rejects-initial-surfaces-probe-errored
  (async done
    (tail! (rejects "Unable to resolve symbol: zorp")
           {:probe "(zorp)" :baseline "0" :wait-ms 250}
           (fn [r]
             (is (tu/error? r))
             (is (= {:ok? false :reason :probe-errored :probe-error "Unable to resolve symbol: zorp"}
                    (edn-without r :note))
                 "an initial eval that throws is its own reason, not :timed-out"))
           done)))

(deftest probe-without-baseline-is-refused
  ;; A post-edit self-baseline cannot tell "already reloaded" from "never changed".
  (async done
    (tail! (rejects "should-not-be-called") {:probe "(some-form)" :wait-ms 500}
           (fn [r]
             (is (tu/error? r))
             (is (= {:ok? false :reason :missing-baseline} (edn-without r :note))))
           done)))

(deftest baseline-without-probe-is-refused
  (async done
    (tail! (rejects "should-not-be-called") {:baseline "0"}
           (fn [r]
             (is (tu/error? r))
             (is (= {:ok? false :reason :baseline-without-probe} (edn-without r :note))))
           done)))

(deftest bogus-wait-ms-errors-honestly-without-polling
  ;; `(>= elapsed NaN)` is never true, so an unvalidated "bogus" would poll
  ;; forever. The eval stub throws, so reaching nREPL would read :probe-errored.
  (async done
    (tail! (rejects "should-not-be-called")
           {:probe "(some-form)" :baseline "0" :wait-ms "bogus"}
           (fn [r]
             (is (tu/error? r))
             (is (= {:ok? false :reason :invalid-numeric-arg :arg "wait-ms"}
                    (select-keys (tu/extract-edn r) [:ok? :reason :arg]))))
           done)))

;; ---------------------------------------------------------------------------
;; `--no-eval` refuses a probe (corpus fixture `:tail-build/disabled-via-no-eval`)
;; but the no-probe soft delay evaluates nothing, so it stays available.
;; ---------------------------------------------------------------------------

(deftest no-probe-soft-delay-survives-no-eval
  (async done
    (let [prev (eval-cljs/eval-allowed-enabled?)]
      (eval-cljs/set-eval-allowed! false)
      (-> (tail/tail-build-tool nil (tu/args->js {}))
          (.then (fn [r]
                   (is (= {:ok? true :soft? true} (edn-without r :t :note)))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e)))))
          (.then (fn [_]
                   (eval-cljs/set-eval-allowed! prev)
                   (done)))))))
