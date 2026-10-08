(ns re-frame.thrown-error-message-conformance-cljs-test
  "The thrown-error human-message contract (Spec 009 §The thrown-error shape):
  `(ex-message e)` leads with a human sentence, never the bare stringified
  `:rf.error/…` keyword, and trails a `[:rf.error/<id>]` token; `:rf.error/id`
  is the machine discriminator.

  This suite pins the central builder and the two conformance predicates other
  suites assert with. `scripts/check_thrown_error_messages.py` is the
  whole-tree sweep over framework `(ex-info …)` sites.

  Dual-runtime `.cljc`: `:node-test` and `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as rf.error]
            [re-frame.flows.topo :as rf.flows.topo]))

(def ^:private no-adapter-reason
  "rf/init! cannot continue because no adapter is installed; require an adapter ns and install it before boot.")

(deftest keyword-only-message?-rejects-bare-keyword-strings
  (doseq [[message expected] [[":rf.error/no-adapter-installed" true]
                              [(str no-adapter-reason " [:rf.error/no-adapter-installed]") false]]]
    (is (= expected (rf.error/keyword-only-message? message)) message)))

(deftest message-has-id-token?-detects-the-trailing-token
  (doseq [[message expected] [["human sentence here. [:rf.error/no-adapter-installed]" true]
                              [":rf.error/no-adapter-installed" false]]]
    (is (= expected (rf.error/message-has-id-token? message)) message)))

(deftest thrown-ex-info-derives-the-canonical-shape
  (let [e (rf.error/thrown-ex-info :rf.error/no-adapter-installed 'rf/init! no-adapter-reason)]
    (is (= {:rf.error/id :rf.error/no-adapter-installed
            :where       'rf/init!
            :recovery    :no-recovery
            :reason      no-adapter-reason}
           (ex-data e)))
    (testing "the message leads with the human sentence and trails the token
              (asserted by pattern: the message is stable in meaning, not bytes)"
      (is (re-find #"^rf/init! cannot continue" (ex-message e)))
      (is (re-find #"\[:rf\.error/no-adapter-installed\]$" (ex-message e))))))

(deftest thrown-ex-info-honours-recovery-and-extra-slots
  (is (= {:rf.error/id :rf.error/flow-bad-id
          :where       'rf/reg-flow
          :recovery    :fix-registration
          :reason      ":id must be a keyword"
          :flow        {:id "not-a-kw"}
          :bad-key     :id}
         (ex-data (rf.error/thrown-ex-info
                    :rf.error/flow-bad-id
                    'rf/reg-flow
                    ":id must be a keyword"
                    {:recovery :fix-registration
                     :extra    {:flow {:id "not-a-kw"} :bad-key :id}})))))

(deftest flow-cycle-emits-conformant-throw
  ;; A real `throw-error!` site: two flows forming a dependency cycle.
  (let [e (try (rf.flows.topo/topo-sort
                 {:a {:id :a :inputs [[:b]] :derive identity :output-path [:a]}
                  :b {:id :b :inputs [[:a]] :derive identity :output-path [:b]}})
               (catch #?(:clj Throwable :cljs :default) e e))]
    (is (= :rf.error/flow-cycle (:rf.error/id (ex-data e))))
    (is (rf.error/message-has-id-token? (ex-message e)))))
