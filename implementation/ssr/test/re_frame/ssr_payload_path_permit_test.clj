(ns re-frame.ssr-payload-path-permit-test
  "The `:payload` path permit. An allowlist entry is a keyword top-level key or
  a PATH; a path that names a classified LEAF carries that leaf's raw value to
  the hydrating browser, e.g. a CSRF synchronizer token the client must send
  back. Classification answers \"keep it out of the tools and the monitors\";
  the path answers \"may this user's own browser hold it\" — a different
  question, and only the host knows the answer.

  The rules pinned here (Spec 011 §`:rf/app-db` projection):

    a. a path permits exactly the classified leaf it names — a classified
       sibling stays redacted, and a keyword permits nothing;
    b. a named map still redacts its classified descendants, and a path that
       names a classified map releases nothing — a permit is a leaf;
    c. a path never pierces a classified ancestor;
    d. a path selects only through MAPS holding each coordinate: through a
       vector, a scalar or an absent key it selects and permits nothing;
    e. a dead frame's whole-slice `:rf/redacted` is untouched, and the
       whole-app-db opt-in permits nothing;
    f. a malformed entry, and the separate `:payload-include-sensitive` permit
       list, fail loud at both arms.

  One shared rule: `apply-policy` then `project-app-db-egress`'s 3-arity."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private sframe :rf.hjz4r/server)

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- reg-server-frame!
  "A server frame whose init event seeds `db`, with `sensitive` classified."
  [db sensitive]
  (rf/reg-classification :rf.hjz4r/session {:sensitive sensitive})
  (rf/reg-event :rf.hjz4r/seed
    (fn [_ _] {:db db}))
  (rf/make-frame {:id sframe :platform :server
                  :initial-events [[:rf.hjz4r/seed]]}))

(def ^:private session-db
  {:session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}
   :private {:note "off the allowlist"}})

(def ^:private session-sensitive
  [[:session :csrf] [:session :upstream-key]])

(defn- project
  "Select `db` per `payload`, then project it under the frame's classification
  with `payload`'s paths permitting — the exact order every hydration site
  uses."
  [db payload]
  (rf.ssr.payload-policy/project-app-db-egress
    (rf.ssr.payload-policy/apply-policy db {:payload payload})
    sframe
    payload))

(defn- thrown-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) ::message (ex-message e)))))

(deftest a-path-permits-exactly-the-classified-leaf-it-names
  (reg-server-frame! session-db session-sensitive)
  (is (= {:session {:csrf "csrf-abc-123" :upstream-key redacted :user "alice"}}
         (project session-db [:session [:session :csrf]]))
      "public :session data and the permitted leaf cross; the classified sibling stays redacted")
  (testing "entry order does not matter"
    (is (= (project session-db [:session [:session :csrf]])
           (project session-db [[:session :csrf] :session]))))
  (testing "a path alone ships only the value it names"
    (is (= {:session {:csrf "csrf-abc-123"}}
           (project session-db [[:session :csrf]]))))
  (testing "a keyword never permits: the same key selected by keyword alone redacts"
    (is (= {:session {:csrf redacted :upstream-key redacted :user "alice"}}
           (project session-db [:session])
           (rf.ssr.payload-policy/project-app-db-egress
             (select-keys session-db [:session]) sframe)))))

(deftest a-named-map-still-redacts-its-classified-descendants
  (reg-server-frame! session-db session-sensitive)
  (is (= {:session {:csrf redacted :upstream-key redacted :user "alice"}}
         (project session-db [[:session]]))
      "naming the ancestor selects it, and selecting a subtree authorises none of its secrets"))

(deftest a-path-naming-a-classified-map-releases-nothing
  (reg-server-frame! session-db [[:session]])
  (is (= {:session redacted} (project session-db [[:session]]))
      "a permit is a leaf: a classified map stays redacted whole"))

(deftest a-path-never-pierces-a-classified-ancestor
  (reg-server-frame! session-db [[:session]])
  (doseq [payload [[:session [:session :csrf]]
                   [[:session :csrf]]]]
    (is (= {:session redacted} (project session-db payload))
        (str "the classified ancestor stays the scalar sentinel — no partial map is fabricated: "
             (pr-str payload)))))

(deftest a-path-selects-only-through-maps
  (let [items {:items [{:id 0 :token "t0"} {:id 1 :token "t1"}]}]
    (reg-server-frame! items [[:items :token]])
    (is (= {:items [{:id 0 :token redacted} {:id 1 :token redacted}]}
           (project items [:items [:items 0 :token]]))
        "a path through a vector permits nothing; its classified leaves stay redacted")
    (is (= {} (project items [[:items 0 :token]]))
        "and selects nothing on its own")))

(deftest an-absent-path-creates-nothing
  (reg-server-frame! session-db session-sensitive)
  (doseq [path [[:session :nope]           ;; an absent last coordinate
                [:session :user :deeper]   ;; a scalar in the way
                [:nope :csrf]]]            ;; an absent root key
    (is (= {} (project session-db [path])) (pr-str path))
    (is (= (project session-db [:session])
           (project session-db [:session path]))
        (pr-str path))))

(deftest a-present-nil-restores-nil
  (let [db {:session {:csrf nil :user "alice"}}]
    (reg-server-frame! db [[:session :csrf]])
    (is (= {:session {:csrf nil :user "alice"}}
           (project db [:session [:session :csrf]])))))

(deftest a-dead-frame-stays-whole-redacted
  ;; sframe is deliberately never registered.
  (is (= redacted (project session-db [:session [:session :csrf]]))))

(deftest the-whole-app-db-opt-in-permits-nothing
  (reg-server-frame! session-db session-sensitive)
  (is (= {:session {:csrf redacted :upstream-key redacted :user "alice"}
          :private {:note "off the allowlist"}}
         (project session-db :rf.ssr.payload/whole-app-db))))

(deftest a-malformed-payload-permits-nothing-at-the-projection
  ;; `apply-policy` refuses these first; the projection on its own still
  ;; fails closed, so a caller that skips the policy gains no permit.
  (reg-server-frame! session-db session-sensitive)
  (let [slice (select-keys session-db [:session])]
    (doseq [payload [[[]] ["session" '(:session :csrf)] #{[:session :csrf]} nil]]
      (is (= (rf.ssr.payload-policy/project-app-db-egress slice sframe)
             (rf.ssr.payload-policy/project-app-db-egress slice sframe payload))
          (pr-str payload)))))

(deftest a-malformed-entry-fails-loud-at-both-arms
  (doseq [[bad bad-entries] [[[:session "csrf"]             ["csrf"]]
                             [[[]]                          [[]]]
                             [[:session '(:session :csrf)]  ['(:session :csrf)]]
                             [[:session nil]                [nil]]]]
    (doseq [[arm f] [["construction" #(rf.ssr.payload-policy/validate-policy-opts! {:payload bad})]
                     ["runtime"      #(rf.ssr.payload-policy/apply-policy session-db {:payload bad})]]]
      (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
              :got         bad
              :bad-entries bad-entries}
             (select-keys (thrown-data f) [:rf.error/id :got :bad-entries]))
          (str arm " arm: " (pr-str bad)))))
  (let [opts {:payload [:session [:session :csrf]]}]
    (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts))
        "a well-formed allowlist of keys and paths passes the construction arm unchanged")))

(deftest the-separate-permit-list-is-refused-naming-the-path-form
  (doseq [[opts suggested] [[{:payload [:session] :payload-include-sensitive [[:session :csrf]]}
                             ":payload [:session [:session :csrf]]"]
                            [{:payload [:session] :payload-include-sensitive nil}
                             ":payload [:articles [:session :csrf]]"]
                            [{:payload :rf.ssr.payload/whole-app-db :payload-include-sensitive []}
                             ":payload [:articles [:session :csrf]]"]]]
    (doseq [[arm f] [["construction" #(rf.ssr.payload-policy/validate-policy-opts! opts)]
                     ["runtime"      #(rf.ssr.payload-policy/apply-policy session-db opts)]]]
      (let [data (thrown-data f)]
        (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
                :opt         :payload-include-sensitive
                :replacement :payload}
               (select-keys data [:rf.error/id :opt :replacement]))
            (str arm " arm: " (pr-str opts)))
        (is (str/includes? (str (::message data)) suggested)
            (str arm " arm names the path form: " (pr-str (::message data))))))))
