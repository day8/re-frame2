(ns re-frame.ssr.multi-root-hydration-cljs-test
  "Multi-root hydration: preflight and the idempotent payload install (Spec 011
  §Hydration preflight). A page is N roots referencing M frames, every root
  boots off the same `__rf_payload`, and the second root to reference a payload
  finds it live and does not re-seed.

  Runs on the JVM and the node runner. Handlers are registered inside each test
  body: in the shared node process a sibling's `registrar/clear-all!` wipes
  ns-load registrations, which would turn a dispatch into a silent no-op."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; Seat the adapter this ns names, and leave the slot cold for the next ns:
;; `init!` with a different adapter already seated raises.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client` frame under an id no other test in this process has used."
  []
  (let [fid (keyword "rf.multiroot" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- payload-for
  "The page-wide payload, built by the shipped assembler; no `:rf/frame-id`, so
  the explicit client `:frame` stands."
  [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

(def ^:private manifest-v1
  {:rf.root/schema-version rf.ssr.manifest/schema-version
   :root-id                :page/shop
   :view-id                :app/shop-root
   :phase                  :server})

(defn- caught-error-id
  [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest installing-the-same-payload-twice-is-indistinguishable-from-once
  (testing "root B, booting second against the same payload, does not re-seed —
            a client mutation made after root A survives — and still reports the
            page as server-rendered"
    (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (let [fid     (fresh-frame!)
          payload (payload-for {:count 7})]
      (is (= payload (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/a})))
      (rf/dispatch-sync [::bump] {:frame fid})
      (is (= {:count 8} (rf/app-db-value fid)))
      (is (= payload (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/b})))
      (is (= {:count 8} (rf/app-db-value fid))))))

(deftest a-conflicting-root-fails-loud-and-leaves-the-installed-payload-untouched
  (testing "a DIFFERENT payload under the same id throws
            :rf.error/frame-payload-conflict before any install"
    (let [fid           (fresh-frame!)
          installed     (payload-for {:count 7})
          _             (rf.ssr.boot/hydrate! {:frame fid :payload installed :root-id :page/a})
          db-before     (rf/app-db-value fid)
          record-before (rf.ssr.install/installed-payload fid)
          arriving      (payload-for {:count 99})
          data          (try (rf.ssr.boot/hydrate! {:frame fid :payload arriving :root-id :page/b})
                             nil
                             (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/frame-payload-conflict
              :payload-id  fid
              :installed   {:digest       (rf.ssr.install/payload-content-digest installed)
                            :installed-by :page/a}
              :arriving    {:digest  (rf.ssr.install/payload-content-digest arriving)
                            :root-id :page/b}
              :recovery    :render-the-page-from-one-response}
             (select-keys data [:rf.error/id :payload-id :installed :arriving :recovery])))
      (is (= db-before (rf/app-db-value fid)) "the seeded frame is untouched")
      (is (= record-before (rf.ssr.install/installed-payload fid)) "the ledger is untouched"))))

;; The render-tree hash prunes nil; a payload digest must not, or each pair
;; below would alias and a second root carrying a different slice would be
;; waved through as :already-installed. One pair per collection branch.

(defn- payload-with-app-db [db]
  {:rf/version 1 :rf/app-db db})

(deftest nil-differences-in-payload-data-are-digest-differences
  (doseq [[a b] [[{:x nil} {}]
                 [{:items [nil 7]} {:items [7]}]
                 [{:s #{nil 1}} {:s #{1}}]]]
    (is (not= (rf.ssr.install/payload-content-digest (payload-with-app-db a))
              (rf.ssr.install/payload-content-digest (payload-with-app-db b)))
        (pr-str a b))))

(deftest the-digest-stays-idempotent-for-genuinely-equal-payloads
  (testing "map insertion order is not content"
    (is (= (rf.ssr.install/payload-content-digest {:rf/app-db {:a nil :b 2} :rf/version 1})
           (rf.ssr.install/payload-content-digest {:rf/version 1 :rf/app-db {:b 2 :a nil}})))))

(deftest preflight-validates-an-explicit-manifest-and-takes-root-id-from-it
  (is (= {:root-id :page/shop :decision :install :manifest manifest-v1}
         (select-keys (rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                                        :payload-id (fresh-frame!)
                                                        :manifest   manifest-v1})
                      [:root-id :decision :manifest]))))

(deftest preflight-rejects-a-value-outside-the-manifest-schema-family
  (let [fid (fresh-frame!)]
    (is (= :rf.error/root-manifest-invalid
           (caught-error-id
            #(rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                                :payload-id fid
                                                :manifest   {:rf.root/schema-version 2}}))))
    (is (nil? (rf.ssr.install/installed-payload fid))
        "the payload was not claimed")))

(deftest an-explicit-root-id-wins-over-the-manifests
  (is (= :page/explicit
         (:root-id (rf.ssr.install/preflight! 'test
                                              {:payload    (payload-for {:count 7})
                                               :payload-id (fresh-frame!)
                                               :manifest   manifest-v1
                                               :root-id    :page/explicit})))))

(deftest releasing-a-claim-lets-a-fresh-lifetime-install-again
  (testing "after release, a DIFFERENT payload installs instead of conflicting"
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7}) :root-id :page/a})
      (rf.ssr.install/release-payload! fid)
      (is (= :install
             (:decision (rf.ssr.install/preflight! 'test
                                                   {:payload    (payload-for {:count 99})
                                                    :payload-id fid
                                                    :root-id    :page/c})))))))
