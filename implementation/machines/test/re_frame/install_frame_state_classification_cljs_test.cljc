(ns re-frame.install-frame-state-classification-cljs-test
  "A persisted machine installed with `[:rf/install-frame-state saved]` keeps
  its privacy classification.

  The documented save keeps only `:rf.runtime/machines`. A machine's
  `:sensitive` / `:large` declaration lives on its registered definition and
  is lowered per actor into the frame's elision registry
  (`:rf.runtime/elision`), which the save leaves behind. So the install
  RE-DERIVES each restored actor's claims from the destination's registered
  machines, singleton and spawned alike; a saved blob carries no privacy
  policy of its own.

  What must hold after the install:

    - the destination registry claims each restored actor's `[:data :token]`
      under that actor's own machine owner, and nothing else of its `:data`;
    - egress projection of the restored runtime-db redacts the token and
      ships the public sibling as it is;
    - a claim the destination already held for something else survives;
    - installing the same payload again leaves the registry unchanged.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/machines` (JVM) and under the node
  runner (`npm run test:cljs`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:cljs [cljs.reader])
            [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame on `platform` under an id no other test in this process has used."
  [platform]
  (let [fid (keyword "rf.install-cls" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- round-trip
  "Serialise `v` to a string and read it back — what a localStorage save and
  load does to it."
  [v]
  #?(:clj  (read-string (pr-str v))
     :cljs (cljs.reader/read-string (pr-str v))))

(def ^:private classified
  "One declaration shared by the singleton and the spawned child: a secret
  token beside a public label."
  {:sensitive [[:data :token]]
   :initial   :idle
   :data      {:token "saved-secret" :label "public"}
   :states    {:idle {}}})

(defn- reg-machines! []
  (rf/reg-machine :cls/singleton classified)
  (rf/reg-machine :cls/child classified)
  (rf/reg-machine :cls/parent
    {:initial :idle
     :states  {:idle {:on {:open :open}}
               :open {:spawn {:machine-id :cls/child}}}})
  (rf/reg-event :cls/classify-password
    (fn [{:keys [db]} _]
      {:db (assoc-in db [:account :password] "hunter2") :sensitive [[:account :password]]})))

(defn- token-path [actor-id]
  [:rf.runtime/machines :snapshots actor-id :data :token])

(defn- label-path [actor-id]
  [:rf.runtime/machines :snapshots actor-id :data :label])

(defn- registry [frame-id]
  (get (rf.machines.test-support/runtime-db frame-id) :rf.runtime/elision))

(defn- owners
  "The owner set `frame-id`'s registry holds for `path` on the sensitive axis."
  [frame-id path]
  (get-in (registry frame-id) [:sensitive-declarations path]))

(defn- persisted-machines!
  "Boot the singleton and spawn the child on a live source frame, and return
  what the guide's save loop keeps: `:rf.runtime/machines` read through
  `frame-state-value`, round-tripped through `pr-str` / `read-string`."
  []
  (let [src (fresh-frame! :client)]
    (rf/dispatch-sync [:cls/singleton [:rf.machine/noop]] {:frame src})
    (rf/dispatch-sync [:cls/parent [:open]] {:frame src})
    (let [machines (get-in (rf/frame-state-value src) [:rf.db/runtime :rf.runtime/machines])
          child    (get-in machines [:spawned :cls/parent [:open]])]
      (is (keyword? child) "precondition: the parent spawned its child")
      (is (= #{{:source :machine :actor-id :cls/singleton}}
             (owners src (token-path :cls/singleton)))
          "precondition: the source frame classified the singleton's token")
      (is (= #{{:source :machine :actor-id child}}
             (owners src (token-path child)))
          "precondition: the source frame classified the child's token")
      (let [saved (round-trip machines)]
        (is (= machines saved) "precondition: the machines subtree round-trips as data")
        (is (not (contains? saved :rf.runtime/elision))
            "precondition: the saved blob carries no privacy policy")
        [saved child]))))

;; ---------------------------------------------------------------------------
;; The loop
;; ---------------------------------------------------------------------------

(deftest an-installed-machine-keeps-its-classification
  (testing "installing a persisted machines subtree re-derives each restored
            actor's classification from the registered machines, keeps the
            destination's unrelated claim, and redacts at egress"
    (reg-machines!)
    (let [[saved child] (persisted-machines!)
          dst           (fresh-frame! :client)
          install!      #(rf/dispatch-sync [:rf/install-frame-state
                                            {:rf.db/runtime {:rf.runtime/machines saved}}]
                                           {:frame dst})]
      (rf/dispatch-sync [:cls/classify-password] {:frame dst})
      (is (= #{{:source :effect}} (owners dst [:account :password]))
          "precondition: the destination holds an unrelated claim")

      (install!)

      (testing "the restored values are back"
        (is (= "saved-secret" (:token (rf.machines.test-support/machine-data dst :cls/singleton))))
        (is (= "saved-secret" (:token (rf.machines.test-support/machine-data dst child)))))

      (testing "the registry claims each restored actor's token under its own owner"
        (is (= #{{:source :machine :actor-id :cls/singleton}}
               (owners dst (token-path :cls/singleton))))
        (is (= #{{:source :machine :actor-id child}}
               (owners dst (token-path child))))
        (is (nil? (owners dst (label-path :cls/singleton))) "the public sibling is unclaimed")
        (is (nil? (owners dst (label-path child))) "the public sibling is unclaimed"))

      (testing "the destination's unrelated claim survives"
        (is (= #{{:source :effect}} (owners dst [:account :password]))))

      (testing "egress redacts the restored tokens and keeps the public labels"
        (let [out (rf/project-egress (rf.machines.test-support/runtime-db dst) {:frame dst})]
          (is (= :rf/redacted (get-in out (token-path :cls/singleton))))
          (is (= :rf/redacted (get-in out (token-path child))))
          (is (= "public" (get-in out (label-path :cls/singleton))))
          (is (= "public" (get-in out (label-path child))))
          (is (not (str/includes? (pr-str out) "saved-secret")) "no restored secret egresses"))
        (is (= :rf/redacted
               (get-in (rf/project-egress (rf/app-db-value dst) {:frame dst}) [:account :password]))
            "the unrelated claim still redacts"))

      (testing "installing the same payload again leaves the registry as it was"
        (let [before (registry dst)]
          (install!)
          (is (= before (registry dst))))))))

(deftest an-install-on-a-server-frame-keeps-its-classification
  (testing "a server frame arms no `:after` timer, but a restored actor is
            still classified before anything projects it for the wire"
    (reg-machines!)
    (let [[saved child] (persisted-machines!)
          dst           (fresh-frame! :server)]
      (rf/dispatch-sync [:rf/install-frame-state {:rf.db/runtime {:rf.runtime/machines saved}}]
                        {:frame dst})
      (is (= #{{:source :machine :actor-id :cls/singleton}}
             (owners dst (token-path :cls/singleton))))
      (is (= #{{:source :machine :actor-id child}}
             (owners dst (token-path child)))))))
