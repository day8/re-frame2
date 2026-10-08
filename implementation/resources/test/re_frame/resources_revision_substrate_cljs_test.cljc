(ns re-frame.resources-revision-substrate-cljs-test
  "The per-entry `:revision` write identity the optimistic-rollback settle
  protocol checks for conflicts. Every authoritative durable entry write bumps
  it unconditionally (including an `=`-data freshness settle), a load start
  does not, and an owner attach/release bumps only when the owner set changes."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]))

(defn- loaded-entry
  ([] (loaded-entry nil))
  ([sk]
   (-> (if sk
         (rf.resources.state/empty-entry :conduit/article sk)
         (rf.resources.state/empty-entry :conduit/article))
       (rf.resources.state/entry-succeeded {:data {:n 1} :loaded-at 1 :stale-at 2 :tags #{}}))))

(defn- start-load [entry work-id]
  (rf.resources.state/entry-start-load
    entry {:generation 5 :work-id work-id :request-id "r" :owner :o}))

;; ---- authoritative writes bump unconditionally -----------------------------

(deftest entry-succeeded-bumps-revision-even-on-equal-data
  ;; A refetch returning equal data keeps the :data identity but re-stamps
  ;; freshness, which a rollback could clobber.
  (let [loaded    (-> (rf.resources.state/empty-entry :conduit/article)
                      (rf.resources.state/entry-succeeded {:data {:n 1} :loaded-at 100
                                                           :stale-at 200 :tags #{[:a]}}))
        resettled (rf.resources.state/entry-succeeded
                    loaded {:data {:n 1} :loaded-at 999 :stale-at 1200 :tags #{[:a]}})]
    (is (= 1 (:revision loaded)))
    (is (identical? (:data loaded) (:data resettled)))
    (is (= [999 2] ((juxt :loaded-at :revision) resettled)))))

(deftest patch-entry-bumps-revision-even-on-equal-data
  (let [base    (loaded-entry)
        patched (rf.resources.mutation-runtime/patch-entry base (fn [d _r] d) :ignored-result
                                                           {:clock-ms 50 :stale-at 60})]
    (is (identical? (:data base) (:data patched)))
    (is (= [50 (inc (:revision base))] ((juxt :loaded-at :revision) patched))))
  (testing "a patch of an entry with no usable data is a no-op"
    (let [empty (rf.resources.state/empty-entry :conduit/article)]
      (is (= empty (rf.resources.mutation-runtime/patch-entry empty (fn [_d _r] {:x 1}) :r
                                                              {:clock-ms 1 :stale-at 2}))))))

(deftest populate-entry-bumps-revision
  (let [sk    (rf.resources.state/scoped-resource-key :rf.scope/global :conduit/article {})
        fresh (rf.resources.mutation-runtime/populate-entry nil :conduit/article {:v 1}
                                                            {:clock-ms 5 :stale-at 9 :tags #{[:t]}
                                                             :scoped-key sk})
        re    (rf.resources.mutation-runtime/populate-entry fresh :conduit/article {:v 1}
                                                            {:clock-ms 77 :stale-at 88 :tags #{[:t]}})]
    (is (= [{:v 1} 1] ((juxt :data :revision) fresh)))
    (is (identical? (:data fresh) (:data re)))
    (is (= [77 2] ((juxt :loaded-at :revision) re)))))

;; ---- failure and cancel settles bump ---------------------------------------
;; Otherwise a snapshot taken while an attempt was in flight would stand at the
;; same revision after the settle, and a rollback would resurrect the in-flight
;; :current-work over the settled state.

(deftest entry-failed-bumps-revision-on-a-background-refresh-failure
  (let [loaded   (loaded-entry)
        fetching (start-load loaded [:w 1])
        rec-rev  (rf.resources.state/entry-revision fetching)
        settled  (rf.resources.state/entry-failed fetching {:error {:kind :rf.http/http-5xx}})]
    (is (= (:revision loaded) rec-rev) "a load start does not move :revision")
    (is (= [:loaded {:kind :rf.http/http-5xx} nil (inc rec-rev)]
           ((juxt :status :refresh-error :current-work :revision) settled)))
    (is (true? (rf.resources.mutation-runtime/optimistic-conflict? settled rec-rev)))))

(deftest entry-failed-bumps-revision-on-a-first-load-failure
  (let [loading (start-load (rf.resources.state/empty-entry :conduit/article) [:w 2])
        rec-rev (rf.resources.state/entry-revision loading)
        settled (rf.resources.state/entry-failed loading {:error {:kind :rf.http/http-4xx}})]
    (is (= [:error nil nil (inc rec-rev)]
           ((juxt :status :data :current-work :revision) settled)))))

(deftest entry-page-failed-bumps-revision-on-a-load-more-failure
  (let [feed     (-> (rf.resources.state/empty-infinite-entry :conduit/feed)
                     (rf.resources.state/entry-append-page {:page [:a :b] :page-param nil
                                                            :loaded-at 1 :stale-at 2}))
        fetching (start-load feed [:w 3])
        rec-rev  (rf.resources.state/entry-revision fetching)
        settled  (rf.resources.state/entry-page-failed fetching {:error {:kind :rf.http/timeout}})]
    (is (= [[[:a :b]] {:kind :rf.http/timeout} nil (inc rec-rev)]
           ((juxt :data :page-error :current-work :revision) settled)))))

(deftest optimistic-rollback-does-not-resurrect-a-settled-in-flight-snapshot
  ;; An optimistic apply snapshots an in-flight entry and preserves its
  ;; :current-work; the refetch then settles. The rollback must detect the
  ;; conflict and invalidate, not restore the in-flight :before.
  (let [sk       (rf.resources.state/scoped-resource-key :rf.scope/global :conduit/article {:slug "w"})
        fetching (start-load (loaded-entry sk) [:w 7])
        observed (rf.resources.state/entry-revision fetching)
        applied  (rf.resources.mutation-runtime/apply-optimistic-patch
                   fetching (fn [d] (assoc d :n 99)) :conduit/article
                   {:clock-ms 5 :stale-at 9 :scoped-key sk})
        recorded (rf.resources.mutation-runtime/record-optimistic-entry
                   sk fetching :patch observed (rf.resources.state/entry-revision applied))
        settled  (rf.resources.state/entry-failed applied {:error {:kind :rf.http/http-5xx}})
        disp     (rf.resources.mutation-runtime/rollback-entry-disposition settled recorded :invalidate)]
    (is (= [:fetching [:w 7]] ((juxt :status :current-work) (:before recorded))) "precondition")
    (is (= [:w 7] (:current-work applied)) "the apply left the refetch live")
    (is (= [(inc observed) (+ 2 observed)] [(:applied-revision recorded) (:revision settled)]))
    (is (= {:conflict? true :disposition :invalidate} (select-keys disp [:conflict? :disposition])))))

;; ---- the conflict comparison -----------------------------------------------

(deftest optimistic-conflict-detects-a-competing-authoritative-write
  (testing "the apply's own bump is not a conflict; the baseline is post-apply"
    (let [sk       (rf.resources.state/scoped-resource-key :rf.scope/global :conduit/article {:slug "a"})
          loaded   (loaded-entry sk)
          observed (rf.resources.state/entry-revision loaded)
          applied  (rf.resources.mutation-runtime/apply-optimistic-patch
                     loaded (fn [d] (assoc d :n 99)) :conduit/article
                     {:clock-ms 5 :stale-at 9 :scoped-key sk})
          recorded (rf.resources.mutation-runtime/record-optimistic-entry
                     sk loaded :patch observed (rf.resources.state/entry-revision applied))]
      (is (= (inc observed) (:applied-revision recorded)))
      (is (false? (rf.resources.mutation-runtime/optimistic-conflict? applied (:applied-revision recorded))))))
  (testing "a competing write past the baseline is a conflict, even on equal data"
    (let [loaded (loaded-entry)
          competed (rf.resources.state/entry-succeeded
                     loaded {:data {:n 1} :loaded-at 500 :stale-at 600 :tags #{}})]
      (is (true? (rf.resources.mutation-runtime/optimistic-conflict?
                   competed (rf.resources.state/entry-revision loaded))))))
  (testing "a tombstoning remove is compared exactly as a patch is"
    (let [sk       (rf.resources.state/scoped-resource-key :rf.scope/global :conduit/article {:slug "r"})
          loaded   (loaded-entry sk)
          tomb     (rf.resources.mutation-runtime/apply-optimistic-remove loaded)
          recorded (rf.resources.mutation-runtime/record-optimistic-entry sk loaded :remove)]
      (is (= [(inc (rf.resources.state/entry-revision loaded)) (rf.resources.state/entry-revision tomb)]
             [(:applied-revision recorded) (:applied-revision recorded)]))
      (is (false? (rf.resources.mutation-runtime/optimistic-conflict? tomb (:applied-revision recorded))))
      (is (true? (rf.resources.mutation-runtime/optimistic-conflict?
                   (rf.resources.state/bump-revision tomb) (:applied-revision recorded))))))
  (testing "a remove of an absent key: still-absent is unmoved, a re-created key conflicts"
    (let [baseline (:applied-revision
                     (rf.resources.mutation-runtime/record-optimistic-entry
                       (rf.resources.state/scoped-resource-key :rf.scope/global :conduit/article {:slug "r"})
                       rf.resources.mutation-runtime/absent-snapshot :remove))]
      (is (zero? baseline))
      (is (false? (rf.resources.mutation-runtime/optimistic-conflict? nil baseline)))
      (is (true? (rf.resources.mutation-runtime/optimistic-conflict? (loaded-entry) baseline)))
      ;; A bare revision-0 entry reads as unmoved: a first load that has only
      ;; just created the entry must not be invalidated before it loads; the
      ;; :absent restore arm protects that live read instead.
      (is (false? (rf.resources.mutation-runtime/optimistic-conflict?
                    (rf.resources.state/empty-entry :conduit/article) baseline))))))

;; ---- owner writes bump only when the owner set changes ---------------------
;; Without the gate every re-ensure of a present owner would bump :revision,
;; and any in-flight optimistic mutation would see a phantom conflict.

(deftest attach-owner-bumps-revision-only-when-a-new-owner-lands
  (let [e1 (rf.resources.state/attach-owner (rf.resources.state/empty-entry :conduit/article) :owner/a)]
    (is (= [#{:owner/a} 1] ((juxt :active-owners :revision) e1)))
    (testing "re-attaching a present owner writes nothing"
      (let [e2 (rf.resources.state/attach-owner e1 :owner/a)]
        (is (identical? (:active-owners e1) (:active-owners e2)))
        (is (= 1 (:revision e2)))))
    (testing "a nil owner is a no-op"
      (is (= e1 (rf.resources.state/attach-owner e1 nil))))))

(deftest detach-owner-bumps-revision-only-when-the-owner-was-present
  (let [e1 (rf.resources.state/attach-owner (rf.resources.state/empty-entry :conduit/article) :owner/a)]
    (is (= [#{} 2] ((juxt (comp set :active-owners) :revision)
                    (rf.resources.state/detach-owner e1 :owner/a))))
    (testing "releasing an absent owner writes nothing"
      (let [e2 (rf.resources.state/detach-owner e1 :owner/absent)]
        (is (= [(:active-owners e1) 1] ((juxt :active-owners :revision) e2)))))
    (is (nil? (rf.resources.state/detach-owner nil :owner/a)))))
