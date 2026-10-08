(ns re-frame2-pair-mcp.watch-until-test
  "Unit tests for the watch-until tool's argument validation and poll form.
  The tool's hold / timeout / missing-pred wiring is pinned in `record_test`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]))

(deftest non-numeric-timeout-ms-rejected
  ;; Coerced to the 30s default instead, a bad deadline would block the call
  ;; for 30s and hide the caller's mistake. The conn has no socket, so a
  ;; validation that did not short-circuit would fail with another reason.
  (async done
    (-> (watch-until/watch-until-tool
          (nrepl/make-conn 0 "127.0.0.1")
          (tu/args->js {:signals    "[{:app-db [:upload :status]}]"
                        :pred       "{:signal 0 :equals :done}"
                        :timeout-ms "bogus"}))
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= {:ok? false :reason :invalid-numeric-arg :arg "timeout-ms"}
                        (select-keys (tu/extract-edn r) [:ok? :reason :arg])))
                 (done))))))

(deftest watch-form-applies-pred-to-sample
  ;; Regression: `(boolean ((<pred-fn>) sample))` called the pred with no
  ;; args and then called its boolean; the poll loop swallowed the TypeError,
  ;; so every live watch timed out with `:last-sample nil`.
  (let [form (watch-until/watch-form [{:app-db [:upload :status]}]
                                     :rf/default
                                     (record/pred-source {:signal 0 :equals :done})
                                     nil)]
    (is (str/includes? form "(boolean ((fn [sample]")
        "the pred fn literal is applied directly inside the boolean")
    (is (str/includes? form ") sample))")
        "the sample rides as the pred fn's argument")
    (is (str/includes? form "{:held? held? :sample sample")
        "the poll answers the :held? / :sample pair the tool's loop reads")))
