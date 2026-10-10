(ns re-frame.ssr-payload-include-sensitive-test
  "The `:payload-include-sensitive` permit. A host names concrete
  app-db paths whose RAW value may cross to the hydrating browser even though
  the frame classifies them `:sensitive`, e.g. a CSRF synchronizer token the
  client must send back. Classification answers \"keep it out of the tools and
  the monitors\"; the permit answers \"may this user's own browser hold it\" —
  a different question, and only the host knows the answer.

  The rules pinned here (Spec 011 §`:rf/app-db` projection):

    a. the permit applies AFTER the allowlist and the `:rf.egress/ssr-hydration`
       projection, and restores the value from the ALLOWLISTED slice — a
       permit whose root key is off the allowlist does nothing;
    b. it descends only while BOTH the projected node and the raw node are a
       map or a vector holding the next coordinate, and never creates a key or
       a container;
    c. it never pierces a classified ancestor;
    d. permitting a collection releases its whole subtree;
    e. a dead frame's whole-slice `:rf/redacted` is untouched.

  One shared rule: `project-app-db-egress`'s 3-arity."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private sframe :rf.hjz4r/server)

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- reg-server-frame!
  "A server frame whose init event seeds `db` and classifies `sensitive`."
  [db sensitive]
  (rf/reg-event :rf.hjz4r/seed
    (fn [_ _] {:db db :sensitive sensitive}))
  (rf/make-frame {:id sframe :platform :server
                  :initial-events [[:rf.hjz4r/seed]]}))

(def ^:private session-db
  {:session {:csrf "csrf-abc-123" :upstream-key "sk-server-only" :user "alice"}
   :private {:note "off the allowlist"}})

(def ^:private session-sensitive
  [[:session :csrf] [:session :upstream-key]])

(defn- project
  "Allowlist `db` to `payload`, then project it with `permits` — the exact
  order every hydration site uses."
  [db payload permits]
  (rf.ssr.payload-policy/project-app-db-egress
    (rf.ssr.payload-policy/apply-policy db {:payload payload})
    sframe
    permits))

(defn- thrown-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-permitted-leaf-rides-raw-and-its-sibling-stays-redacted
  (reg-server-frame! session-db session-sensitive)
  (is (= {:session {:csrf "csrf-abc-123" :upstream-key redacted :user "alice"}}
         (project session-db [:session] [[:session :csrf]])))
  (testing "nil and [] mean no permits — the permit-free projection exactly"
    (is (= {:session {:csrf redacted :upstream-key redacted :user "alice"}}
           (rf.ssr.payload-policy/project-app-db-egress (select-keys session-db [:session]) sframe)
           (project session-db [:session] nil)
           (project session-db [:session] [])))))

(deftest a-permit-never-pierces-a-classified-ancestor
  (reg-server-frame! session-db [[:session]])
  (is (= {:session redacted} (project session-db [:session] [[:session :csrf]]))
      "the classified ancestor stays the scalar sentinel — no partial map is fabricated"))

(deftest permitting-a-collection-releases-its-whole-subtree
  (reg-server-frame! session-db session-sensitive)
  (is (= (:session session-db)
         (:session (project session-db [:session] [[:session]])))
      "an explicit grant of the collection releases its classified descendants too"))

(deftest an-absent-path-creates-nothing
  (reg-server-frame! session-db session-sensitive)
  (doseq [permit [[:session :nope]           ;; an absent last coordinate
                  [:session :user :deeper]]] ;; a scalar in the way
    (is (= (project session-db [:session] nil)
           (project session-db [:session] [permit]))
        (pr-str permit))))

(deftest a-present-nil-restores-nil
  (reg-server-frame! {:session {:csrf nil :user "alice"}} [[:session :csrf]])
  (is (= {:session {:csrf nil :user "alice"}}
         (project {:session {:csrf nil :user "alice"}} [:session] [[:session :csrf]]))))

(deftest a-vector-coordinate-releases-that-index-only
  (let [items {:items [{:id 0 :token "t0"} {:id 1 :token "t1"} {:id 2 :token "t2"}]}]
    (reg-server-frame! items [[:items 0 :token] [:items 1 :token] [:items 2 :token]])
    (let [db (project items [:items] [[:items 1 :token]])]
      (is (vector? (:items db)))
      (is (= {:items [{:id 0 :token redacted} {:id 1 :token "t1"} {:id 2 :token redacted}]}
             db)))))

(deftest a-permit-off-the-allowlist-does-nothing
  (reg-server-frame! session-db (conj session-sensitive [:private :note]))
  (let [db (project session-db [:session] [[:private :note]])]
    (is (not (contains? db :private))
        "the raw value comes from the ALLOWLISTED slice, never the frame")))

(deftest a-dead-frame-stays-whole-redacted
  ;; sframe is deliberately never registered.
  (is (= redacted (project session-db [:session] [[:session]]))))

(deftest a-malformed-permit-fails-loud-at-both-arms
  (doseq [[bad bad-entries] [[[:session :csrf]         [:session :csrf]]  ;; one path, unwrapped
                             [[[]]                     [[]]]              ;; an empty path
                             [[[:session :csrf] :user] [:user]]           ;; one good, one not
                             [#{[:session :csrf]}      [#{[:session :csrf]}]]]]
    (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist
            :opt         :payload-include-sensitive
            :got         bad
            :bad-entries bad-entries}
           (select-keys (thrown-data #(rf.ssr.payload-policy/validate-policy-opts!
                                        {:payload [:session] :payload-include-sensitive bad}))
                        [:rf.error/id :opt :got :bad-entries]))
        (str "construction arm: " (pr-str bad))))
  (is (= {:rf.error/id :rf.error/ssr-malformed-payload-allowlist :opt :payload-include-sensitive}
         (select-keys (thrown-data #(rf.ssr.payload-policy/project-app-db-egress
                                      {:session {}} sframe #{[:session :csrf]}))
                      [:rf.error/id :opt]))
      "runtime arm")
  (let [opts {:payload [:session] :payload-include-sensitive [[:session :csrf]]}]
    (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts))
        "a well-formed permit passes the construction arm unchanged")))
