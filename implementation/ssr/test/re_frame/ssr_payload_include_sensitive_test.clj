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

  One shared rule, `project-app-db-egress`'s 3-arity: the streaming delta and
  the streaming final payload reach it too, so they agree."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
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

;; ---- 1. the permit releases exactly the named leaf -------------------------

(deftest a-permitted-leaf-rides-raw-and-its-sibling-stays-redacted
  (reg-server-frame! session-db session-sensitive)
  (let [db (project session-db [:session] [[:session :csrf]])]
    (is (= "csrf-abc-123" (get-in db [:session :csrf]))
        "the permitted leaf rides raw (the projection alone gives :rf/redacted)")
    (is (= redacted (get-in db [:session :upstream-key]))
        "control: the classified sibling the host did not permit stays redacted")
    (is (= "alice" (get-in db [:session :user]))
        "control: the unclassified sibling is unchanged")
    (is (not (contains? db :private))
        "control: the unallowlisted key is still absent")
    (is (not (.contains (pr-str db) "sk-server-only"))
        "control: the withheld value survives nowhere in the slice"))
  (testing "nil and [] mean no permits — the permit-free projection exactly"
    (is (= (rf.ssr.payload-policy/project-app-db-egress (select-keys session-db [:session]) sframe)
           (project session-db [:session] nil)
           (project session-db [:session] [])))
    (is (= redacted (get-in (project session-db [:session] nil) [:session :csrf])))))

;; ---- 4. the edges ------------------------------------------------------------

(deftest a-permit-never-pierces-a-classified-ancestor
  (reg-server-frame! session-db [[:session]])
  (is (nil? (thrown-data #(project session-db [:session] [[:session :csrf]])))
      "no throw")
  (is (= {:session redacted} (project session-db [:session] [[:session :csrf]]))
      "the classified ancestor stays the scalar sentinel — no partial map is fabricated"))

(deftest permitting-a-collection-releases-its-whole-subtree
  (reg-server-frame! session-db session-sensitive)
  (is (= (:session session-db)
         (:session (project session-db [:session] [[:session]])))
      "an explicit grant of the collection releases its classified descendants too"))

(deftest an-absent-path-creates-nothing
  (reg-server-frame! session-db session-sensitive)
  (let [plain (project session-db [:session] nil)]
    (is (= plain (project session-db [:session] [[:session :nope]]))
        "an absent last coordinate adds no key")
    (is (= plain (project session-db [:session] [[:nope :deeper]]))
        "an absent root coordinate adds no key")
    (is (= plain (project session-db [:session] [[:session :user :deeper]]))
        "a scalar in the way stops the descent")))

(deftest a-present-nil-restores-nil
  (reg-server-frame! {:session {:csrf nil :user "alice"}} [[:session :csrf]])
  (let [db (project {:session {:csrf nil :user "alice"}} [:session] [[:session :csrf]])]
    (is (contains? (:session db) :csrf))
    (is (nil? (get-in db [:session :csrf])))))

(deftest a-vector-coordinate-releases-that-index-only
  (let [items {:items [{:id 0 :token "t0"} {:id 1 :token "t1"} {:id 2 :token "t2"}]}]
    (reg-server-frame! items [[:items 0 :token] [:items 1 :token] [:items 2 :token]])
    (let [db (project items [:items] [[:items 1 :token]])]
      (is (vector? (:items db)))
      (is (= "t1" (get-in db [:items 1 :token])) "index 1 is released")
      (is (= redacted (get-in db [:items 0 :token])) "control: index 0 stays redacted")
      (is (= redacted (get-in db [:items 2 :token])) "control: index 2 stays redacted")
      (is (= [0 1 2] (mapv :id (:items db))) "control: the unclassified ids ride"))))

(deftest a-permit-off-the-allowlist-does-nothing
  (reg-server-frame! session-db (conj session-sensitive [:private :note]))
  (let [db (project session-db [:session] [[:private :note]])]
    (is (not (contains? db :private))
        "the raw value comes from the ALLOWLISTED slice, never the frame")))

(deftest a-dead-frame-stays-whole-redacted
  (testing "an unresolvable frame redacts the whole slice; every permit is inert"
    ;; sframe is deliberately never registered.
    (is (= redacted (project session-db [:session] [[:session :csrf]])))
    (is (= redacted (project session-db [:session] [[:session]])))))

(deftest a-malformed-permit-fails-loud-at-both-arms
  (doseq [bad [[:session :csrf]                ;; the commonest slip: one path, unwrapped
               [[]]                            ;; an empty path
               [[:session :csrf] :user]        ;; one good, one not
               [(list :session :csrf)]         ;; a path is a vector
               :session
               #{[:session :csrf]}]]
    (testing (str "construction arm: " (pr-str bad))
      (let [data (thrown-data #(rf.ssr.payload-policy/validate-policy-opts!
                                 {:payload [:session] :payload-include-sensitive bad}))]
        (is (= :rf.error/ssr-malformed-payload-allowlist (:rf.error/id data)))
        (is (= :payload-include-sensitive (:opt data)))
        (is (= bad (:got data)))))
    (testing (str "runtime arm: " (pr-str bad))
      (let [data (thrown-data #(rf.ssr.payload-policy/project-app-db-egress
                                 {:session {}} sframe bad))]
        (is (= :rf.error/ssr-malformed-payload-allowlist (:rf.error/id data)))
        (is (= :payload-include-sensitive (:opt data))))))
  (testing "the unwrapped slip names its entries"
    (is (= [:session :csrf]
           (:bad-entries (thrown-data #(rf.ssr.payload-policy/validate-policy-opts!
                                         {:payload [:session] :payload-include-sensitive [:session :csrf]}))))))
  (testing "well-formed permits pass the construction arm unchanged"
    (doseq [good [nil [] [[:session :csrf]] [[:items 1 :token] [:user]] '([:a])]]
      (let [opts {:payload [:session] :payload-include-sensitive good}]
        (is (= opts (rf.ssr.payload-policy/validate-policy-opts! opts)) (pr-str good))))))

;; ---- 5. streaming parity: the delta and the final payload agree -------------

(deftest the-streaming-delta-and-the-final-payload-apply-the-same-permit
  (reg-server-frame! session-db session-sensitive)
  (let [opts  {:payload [:session] :payload-include-sensitive [[:session :csrf]]}
        delta (rf/with-frame sframe
                (rf.ssr.streaming/project-delta {:session (:session session-db)} sframe opts))
        final (rf/with-frame sframe
                (rf.ssr.streaming/build-final-payload sframe "h1" (assoc opts :version 1)))]
    (is (= "csrf-abc-123" (get-in delta [:session :csrf]))
        "the streamed delta carries the permitted leaf raw")
    (is (= "csrf-abc-123" (get-in final [:rf/app-db :session :csrf]))
        "the final payload carries the permitted leaf raw")
    (is (= redacted (get-in delta [:session :upstream-key]))
        "control: the delta still redacts the unpermitted sibling")
    (is (= redacted (get-in final [:rf/app-db :session :upstream-key]))
        "control: the final payload still redacts the unpermitted sibling")
    (is (= (:session delta) (get-in final [:rf/app-db :session]))
        "the two streaming halves agree on the slice")))
