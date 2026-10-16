(ns re-frame.named-delays-test
  "Named `:after` delays (Spec 005 §Value shape). A machine's root `:delays`
  map names delays, `{<keyword> <ms | ISO-8601 string | fn>}`, and an `:after`
  key that is a keyword resolves against it machine-locally, as a `:guard`
  keyword resolves against `:guards`. The name is resolved where every delay
  is resolved — in the timer fx, against the committed snapshot — and the
  keyword, not its value, is the timer's identity: it rides the timer table,
  the elapsed event and the trace rows, so a re-registered definition still
  recognises an in-flight timer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.router :as rf.router]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- waiting-machine
  "A flat machine whose `:waiting` state's one `:after` is the named delay
  `:idle-warning`, declared in the root `:delays` map as `delay`."
  [delay]
  {:initial :idle
   :data    {:idle-warning-ms 250}
   :delays  {:idle-warning delay}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {:idle-warning :warning}}
             :warning {}}})

(defn- registration-refusal
  "The ex-data, plus the message under `::message`, of the error `machine`
  throws at registration, or nil when it registers cleanly."
  [machine]
  (try (rf/reg-machine (keyword "named-delay" (str (gensym "m"))) machine) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) ::message (ex-message e)))))

(defn- arm!
  "Dispatch `event` to `machine-id` with the host clock stubbed, returning the
  `[ms thunk]` of every timer the dispatch armed."
  [machine-id event]
  (let [arms (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk ms] (swap! arms conj [ms thunk]) ::handle)]
      (rf/dispatch-sync [machine-id event]))
    @arms))

(defn- fire!
  "Run a captured timer thunk, routing its dispatch synchronously so the
  firing cascade completes inline (the thunk runs outside any handler)."
  [thunk]
  (let [orig (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! rf.router/dispatch-sync!)
      (thunk)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! orig)))))

(defn- ops [captured op]
  (filterv #(= op (:operation %)) captured))

;; ---- registration --------------------------------------------------------

(deftest a-named-delay-registers-for-each-value-form
  (doseq [[label delay] [["a positive integer" 250]
                         ["an ISO-8601 duration" "PT0.25S"]
                         ["a fn over the snapshot" (fn [{:keys [snapshot]}] (-> snapshot :data :idle-warning-ms))]]]
    (is (nil? (registration-refusal (waiting-machine delay))) label)))

(deftest an-undeclared-name-is-refused-naming-the-key-and-the-declared-names
  (let [refusal (registration-refusal
                  (assoc-in (waiting-machine 250) [:states :waiting :after] {:idle-warnin :warning}))]
    (is (= {:rf.error/id :rf.error/machine-bad-after-delay :slot :after :delay-key :idle-warnin}
           (select-keys refusal [:rf.error/id :slot :delay-key])))
    (is (str/includes? (::message refusal) ":idle-warnin") "the message names the missing key")
    (is (str/includes? (::message refusal) "[:idle-warning]") "and the names :delays declares"))
  (testing "a machine with no :delays map refuses every name"
    (is (= :rf.error/machine-bad-after-delay
           (:rf.error/id (registration-refusal (dissoc (waiting-machine 250) :delays)))))))

(deftest a-malformed-delays-map-is-refused
  (doseq [[label machine] [["a value that is no delay" (waiting-machine 0)]
                           ["the \"5s\" shorthand" (waiting-machine "5s")]
                           ["a vector value" (waiting-machine [:settings/idle-warning-ms])]
                           ["a non-keyword name" (assoc (waiting-machine 250) :delays {"idle-warning" 250})]
                           ["a :delays that is not a map" (assoc (waiting-machine 250) :delays [[:idle-warning 250]])]]]
    (is (= {:rf.error/id :rf.error/machine-bad-after-delay :slot :delays}
           (select-keys (registration-refusal machine) [:rf.error/id :slot]))
        label)))

(deftest delays-below-the-root-is-refused-as-root-only
  (let [refusal (registration-refusal
                  (assoc-in (waiting-machine 250) [:states :waiting :delays] {:idle-warning 250}))]
    (is (= {:rf.error/id :rf.error/machine-unknown-node-key :state :waiting}
           (select-keys refusal [:rf.error/id :state])))
    (is (str/includes? (::message refusal) "root-only"))))

;; ---- runtime ---------------------------------------------------------------

(deftest classify-delay-source-names-a-keyword-key
  (is (= [:literal :literal :fn :named]
         (map rf.machines.transition/classify-delay-source
              [250 "PT1S" (fn [_] 1) :idle-warning]))))

(deftest each-named-value-form-arms-its-ms-and-fires-its-transition
  (doseq [[id label delay] [[:named-delay/literal "a named positive integer" 250]
                            [:named-delay/iso "a named ISO-8601 duration" "PT0.25S"]
                            [:named-delay/fn "a named fn over the snapshot"
                             (fn [{:keys [snapshot]}] (-> snapshot :data :idle-warning-ms))]]]
    (rf/reg-machine id (waiting-machine delay))
    (rf.machines.test-support/with-trace-capture captured
      (let [arms (arm! id [:go])]
        (is (= [250] (mapv first arms)) (str label ": armed at the resolved ms"))
        (is (= [{:delay 250 :delay-key :idle-warning :delay-source :named}]
               (mapv #(select-keys (:tags %) [:delay :delay-key :delay-source])
                     (ops @captured :rf.machine.timer/scheduled)))
            (str label ": /scheduled carries the armed ms, the name and its :named source"))
        (fire! (second (first arms)))
        (is (= :warning (:state (rf.machines.test-support/snapshot id)))
            (str label ": the timer fired its transition"))))))

(deftest the-elapsed-event-carries-the-name
  (rf/reg-machine :named-delay/event (waiting-machine 250))
  (let [[[_ thunk]] (arm! :named-delay/event [:go])
        sent        (atom nil)
        orig        (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! (fn [ev _opts] (reset! sent ev)))
      (thunk)
      (finally (rf.late-bind/set-fn! :router/dispatch! orig)))
    (is (= [:named-delay/event [:rf.machine.timer/after-elapsed :idle-warning 1 [:waiting]]] @sent))))

(deftest re-registering-keeps-an-in-flight-named-timer-live
  ;; The fn identity changes on every evaluation of `waiting-machine`, so the
  ;; re-registered definition shares no delay VALUE with the armed timer —
  ;; only the name, which is what the firing looks up.
  (rf/reg-machine :named-delay/reload (waiting-machine (fn [_] 250)))
  (rf.machines.test-support/with-trace-capture captured
    (let [[[_ thunk]] (arm! :named-delay/reload [:go])]
      (rf/reg-machine :named-delay/reload (waiting-machine (fn [_] 250)))
      (fire! thunk)
      (is (= :warning (:state (rf.machines.test-support/snapshot :named-delay/reload)))
          "the timer fired its transition in the re-registered definition")
      (is (empty? (ops @captured :rf.machine.timer/stale-after))
          "and was not reported stale"))))

(deftest assoc-in-delays-is-the-test-seam
  (rf/reg-machine :named-delay/seam (assoc-in (waiting-machine 60000) [:delays :idle-warning] 5))
  (is (= [5] (mapv first (arm! :named-delay/seam [:go])))
      "the shortened delay arms in place of the declared one"))
