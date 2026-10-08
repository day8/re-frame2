(ns re-frame.root-slot-refusal-test
  "Registration refuses every machine-ROOT key that no runtime consumer reads
  at the root, with `:rf.error/machine-root-slot-not-supported`, per Spec 005
  §State nodes (the machine root) and Conventions §No silent swallow.

  Refused on a flat AND a parallel root: `:spawn-all`, `:always`,
  `:choice`, `:final?`, `:output-key`, `:error?`, `:deep?`,
  `:default-target`. Refused on a flat root only: `:on-done` (a parallel
  root's action-only `:on-done` is its supported completion signal).

  Root `:entry` / `:exit` refs are held to the same action-form and
  resolution checks a state's are. A flat root's `:after` / `:timeout` keep
  their own refusal, pinned in `root_after_non_parallel_test.clj`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- refusal
  "The ex-data of the registration refusal for `machine`, or nil when it
  validates."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) ::message (ex-message e)))))

(def ^:private flat-root
  {:initial :a
   :states  {:a {:on {:go :b}}
             :b {:final? true}}})

(def ^:private parallel-root
  {:type    :parallel
   :regions {:x {:initial :x1 :states {:x1 {:on {:fin :x2}} :x2 {:final? true}}}
             :y {:initial :y1 :states {:y1 {}}}}})

(def ^:private refused-everywhere
  "One well-formed value per key refused on every root."
  {:spawn-all      {:children        [{:id :one :machine-id :rs/worker}]
                    :join            :all
                    :on-all-complete [:done]}
   :always         {:action (fn [_] nil)}
   :choice         [{:target :a}]
   :final?         true
   :output-key     :result
   :error?         true
   :deep?          true
   :default-target :a})

;; ---- refused keys ----------------------------------------------------------

(deftest flat-root-refuses-each-unread-key
  (doseq [k [:always :on-done]]
    (is (= {:rf.error/id :rf.error/machine-root-slot-not-supported :offending-keys [k]}
           (select-keys (refusal (assoc flat-root k (get refused-everywhere k :b)))
                        [:rf.error/id :offending-keys]))
        (str k))))

(deftest parallel-root-refuses-each-unread-key
  (is (= {:rf.error/id :rf.error/machine-root-slot-not-supported :offending-keys [:always]}
         (select-keys (refusal (assoc parallel-root :always (:always refused-everywhere)))
                      [:rf.error/id :offending-keys]))))

(deftest refusal-message-names-the-substitute
  (let [msg (fn [root] (::message (refusal (assoc root :spawn-all (:spawn-all refused-everywhere)))))]
    (is (str/includes? (msg flat-root) "compound"))
    (is (str/includes? (msg parallel-root) "region"))))

;; ---- controls: the slots the root reads register ---------------------------

;; ---- root :entry / :exit refs are checked like a state's -------------------

(deftest root-entry-and-exit-refs-are-checked
  (doseq [[expected machine] [[:rf.error/machine-unresolved-action (assoc flat-root :entry :nowhere)]
                              [:rf.error/machine-unresolved-action (assoc parallel-root :exit :nowhere)]
                              [:rf.error/machine-bad-action-form
                               (assoc flat-root :actions {:log (fn [_] nil)} :exit [:log :log])]]]
    (is (= expected (:rf.error/id (refusal machine))))))
