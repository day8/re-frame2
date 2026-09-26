(ns re-frame.root-slot-refusal-test
  "Registration refuses every machine-ROOT key that no runtime consumer reads
  at the root, with `:rf.error/machine-root-slot-not-supported`, per Spec 005
  §State nodes (the machine root) and Conventions §No silent swallow.

  Refused on a flat AND a parallel root: `:spawn-all`, `:always`,
  `:choice`, `:final?`, `:output-key`, `:error?`, `:deep?`,
  `:default-target`. Refused on a flat root only: `:on-done` (a parallel
  root's action-only `:on-done` is its supported completion signal).

  Controls: a parallel root's `:after`, `:timeout` / `:on-timeout` and
  action-only `:on-done` register; a child's `:invoke` typo keeps
  `:rf.error/machine-unknown-node-key`. Root `:entry` / `:exit` refs are held
  to the same action-form and resolution checks a state's are. The honoured
  root `:entry` / `:exit` / `:tags` / `:spawn` register throughout
  `root_lifecycle_test.clj` and `root_spawn_test.clj`, and a flat root's
  `:after` / `:timeout` keep their own
  `:rf.error/machine-non-parallel-root-after-not-supported`, pinned in
  `root_after_non_parallel_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  (doseq [[k v] (assoc refused-everywhere :on-done :b)]
    (testing (str "flat root " k)
      (let [d (refusal (assoc flat-root k v))]
        (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id d)))
        (is (= [k] (:offending-keys d)) "ex-data names the offending root key")))))

(deftest parallel-root-refuses-each-unread-key
  (doseq [[k v] refused-everywhere]
    (testing (str "parallel root " k)
      (let [d (refusal (assoc parallel-root k v))]
        (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id d)))
        (is (= [k] (:offending-keys d)))))))

(deftest refusal-message-names-the-substitute
  (testing "a flat root :spawn-all names the root :spawn and the compound wrapper"
    (let [msg (::message (refusal (assoc flat-root :spawn-all (:spawn-all refused-everywhere))))]
      (is (str/includes? msg ":spawn-all"))
      (is (str/includes? msg "declare :spawn on the root"))
      (is (str/includes? msg "compound"))))
  (testing "a parallel root :spawn-all names the root :spawn and the single-state region"
    (let [msg (::message (refusal (assoc parallel-root :spawn-all (:spawn-all refused-everywhere))))]
      (is (str/includes? msg "declare :spawn on the root"))
      (is (str/includes? msg "region")))))

;; ---- controls: the slots the root reads register ---------------------------

(deftest parallel-root-completion-and-deadline-slots-register
  (is (nil? (refusal (assoc parallel-root :on-done {:action (fn [_] nil)})))
      "an action-only parallel-root :on-done is the supported completion signal")
  (is (nil? (refusal (assoc parallel-root :after {1000 {:target [:x :x2]}})))
      "a parallel root's :after is the supported machine-lifetime timer")
  (is (nil? (refusal (assoc parallel-root :timeout 1000 :on-timeout {:target [:x :x2]})))
      "a parallel root's :timeout lowers onto that :after"))

(deftest child-typo-keeps-the-unknown-node-key-refusal
  (is (= :rf.error/machine-unknown-node-key
         (:rf.error/id (refusal (assoc-in flat-root [:states :a :invoke] {:machine-id :rs/worker}))))))

;; ---- root :entry / :exit refs are checked like a state's -------------------

(deftest root-entry-and-exit-refs-are-checked
  (testing "an unresolved keyword ref"
    (is (= :rf.error/machine-unresolved-action
           (:rf.error/id (refusal (assoc flat-root :entry :nowhere)))))
    (is (= :rf.error/machine-unresolved-action
           (:rf.error/id (refusal (assoc parallel-root :exit :nowhere))))))
  (testing "a vector of actions"
    (is (= :rf.error/machine-bad-action-form
           (:rf.error/id (refusal (assoc flat-root
                                         :actions {:log (fn [_] nil)}
                                         :exit    [:log :log]))))))
  (testing "a resolving keyword ref registers"
    (is (nil? (refusal (assoc flat-root :actions {:log (fn [_] nil)} :entry :log :exit :log))))))
