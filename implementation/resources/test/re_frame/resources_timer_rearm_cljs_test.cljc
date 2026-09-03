(ns re-frame.resources-timer-rearm-cljs-test
  "Sibling-timer preservation across a PARTIAL timer re-arm (rf2-3fc89f.10).

  The resource freshness-timer family has three kinds — `:stale`, `:gc`,
  `:poll` — that share one host side table (`re-frame.resources.timers`). Two
  distinct scheduling operations write it:

    - FULL-SETTLEMENT RECONCILE — a successful load / mutation settle
      reconciles ALL declared kinds: it arms the kinds carrying a positive
      delay and CANCELS the kinds whose policy is absent (so a hot-reload that
      dropped a policy leaves no lingering timer).
    - PARTIAL RE-ARM — a poll tick re-arms ONLY `:poll`; a GC skip
      (`:has-owner` / `:in-flight`) re-arms ONLY `:gc`. A partial re-arm MUST
      leave the SIBLING kinds it does not name untouched.

  Before rf2-3fc89f.10 the scheduling effect overloaded a nil per-kind delay
  to mean BOTH \"preserve this sibling\" (partial re-arm) AND \"disarm this
  kind\" (full settlement), so a poll re-arm silently cancelled the entry's GC
  reaper (and a GC skip silently cancelled the entry's poll). The fix makes the
  two operations explicit: the effect carries a `:timers` map keyed by kind —
  a PRESENT key is reconciled (positive ⇒ arm, nil ⇒ cancel), an ABSENT key is
  PRESERVED. These JVM+CLJS unit tests pin that contract at the timer substrate
  (`schedule-timers-handler` + `timer-table`) and end-to-end through the poll /
  GC re-check events. Per Spec 016 §Stale and GC scheduling / §Polling."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events (incl. the internal poll-fired / gc-fired events +
   ;; the timer fxs) and the test-support reset hook that clears timer-table.
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.timers :as rf.resources.timers]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; ---- capturing transport (deterministic) ----------------------------------
;;
;; We do NOT override :rf.resource/schedule-timers — this suite drives the REAL
;; timer side table so it can prove sibling handles survive a partial re-arm.
;; We DO override the HTTP fxs with capturing no-ops so a poll-tick refetch does
;; not touch a real endpoint, and arm long (never-firing) delays so no wall-
;; clock timer fires during the test.

(def ^:private long-ms 1000000)

(defn- capturing-fixture [f]
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _work-id] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

;; ---- helpers --------------------------------------------------------------

(def ^:private frame-id :rf/default)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value frame-id)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- tkey [scoped-key kind] [frame-id (rf.resources.state/key-id scoped-key) kind])
(defn- timer-handle [scoped-key kind] (get @rf.resources.timers/timer-table (tkey scoped-key kind)))
(defn- armed? [scoped-key kind] (contains? @rf.resources.timers/timer-table (tkey scoped-key kind)))

(defn- article-spec [overrides]
  (merge {:scope         :rf.scope/from-caller
          :params-schema [:map [:slug :string]]}
         overrides))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx] {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- ensure! [resource scope slug owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope scope :params {:slug slug} :owner owner}]))

(defn- succeed! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- poll-fired!
  ([scoped-key] (poll-fired! scoped-key false))
  ([scoped-key hidden?]
   (rf/dispatch-sync [:rf.resource.internal/poll-fired
                      {:resource/key scoped-key :hidden? hidden?}])))

(defn- gc-fired! [scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]))

;; ===========================================================================
;; SUBSTRATE — the schedule-timers fx handler reconciles ONLY the named kinds
;; ===========================================================================

(defn- reconcile! [scoped-key timers-map]
  (rf.resources.timers/schedule-timers-handler
    nil {:frame-id frame-id :resource/key scoped-key :timers timers-map :server? false}))

(deftest poll-only-rearm-preserves-sibling-stale-and-gc
  (rf.resources.timers/reset-cache!)
  (let [k [:rf.scope/global :tr/combo {:id 1}]]
    ;; a full-settlement reconcile arms all three kinds
    (reconcile! k {rf.resources.timers/stale-kind long-ms rf.resources.timers/gc-kind long-ms rf.resources.timers/poll-kind long-ms})
    (is (armed? k rf.resources.timers/stale-kind) "stale armed")
    (is (armed? k rf.resources.timers/gc-kind) "gc armed")
    (is (armed? k rf.resources.timers/poll-kind) "poll armed")
    (let [stale-h (timer-handle k rf.resources.timers/stale-kind)
          gc-h    (timer-handle k rf.resources.timers/gc-kind)
          poll-h  (timer-handle k rf.resources.timers/poll-kind)]
      ;; a POLL-ONLY partial re-arm names ONLY :poll
      (reconcile! k {rf.resources.timers/poll-kind long-ms})
      (testing "rf2-3fc89f.10 — a poll-only re-arm PRESERVES the sibling stale
                + GC handles (they are not named, so untouched)"
        (is (= stale-h (timer-handle k rf.resources.timers/stale-kind)) "stale handle unchanged")
        (is (= gc-h (timer-handle k rf.resources.timers/gc-kind)) "gc handle unchanged"))
      (testing "the named :poll kind IS replaced (cancel-then-arm)"
        (is (armed? k rf.resources.timers/poll-kind) "poll still armed")
        (is (not= poll-h (timer-handle k rf.resources.timers/poll-kind)) "poll handle replaced")))
    (rf.resources.timers/cancel-for-key! frame-id k)))

(deftest gc-only-rearm-preserves-sibling-stale-and-poll
  (rf.resources.timers/reset-cache!)
  (let [k [:rf.scope/global :tr/combo2 {:id 1}]]
    (reconcile! k {rf.resources.timers/stale-kind long-ms rf.resources.timers/gc-kind long-ms rf.resources.timers/poll-kind long-ms})
    (let [stale-h (timer-handle k rf.resources.timers/stale-kind)
          gc-h    (timer-handle k rf.resources.timers/gc-kind)
          poll-h  (timer-handle k rf.resources.timers/poll-kind)]
      ;; a GC-ONLY partial re-arm names ONLY :gc
      (reconcile! k {rf.resources.timers/gc-kind long-ms})
      (testing "rf2-3fc89f.10 — a GC-only re-arm PRESERVES the sibling stale +
                poll handles"
        (is (= stale-h (timer-handle k rf.resources.timers/stale-kind)) "stale handle unchanged")
        (is (= poll-h (timer-handle k rf.resources.timers/poll-kind)) "poll handle unchanged"))
      (testing "the named :gc kind IS replaced (cancel-then-arm)"
        (is (armed? k rf.resources.timers/gc-kind) "gc still armed")
        (is (not= gc-h (timer-handle k rf.resources.timers/gc-kind)) "gc handle replaced")))
    (rf.resources.timers/cancel-for-key! frame-id k)))

(deftest full-reconcile-cancels-a-kind-whose-policy-was-removed
  ;; A full settlement names ALL declared kinds; a kind carrying a nil delay
  ;; (its policy was dropped by a hot reload) is CANCELLED, not preserved.
  (rf.resources.timers/reset-cache!)
  (let [k [:rf.scope/global :tr/hotreload {:id 1}]]
    (reconcile! k {rf.resources.timers/stale-kind long-ms rf.resources.timers/gc-kind long-ms rf.resources.timers/poll-kind long-ms})
    (is (armed? k rf.resources.timers/poll-kind) "poll armed before reload")
    ;; a later settle where the resource no longer declares a poll policy:
    ;; poll is NAMED with a nil delay ⇒ explicit cancel; stale/gc re-armed.
    (reconcile! k {rf.resources.timers/stale-kind long-ms rf.resources.timers/gc-kind long-ms rf.resources.timers/poll-kind nil})
    (testing "rf2-3fc89f.10 — a NAMED nil-delay kind is CANCELLED (a removed
              policy leaves no lingering timer); the still-declared kinds stay"
      (is (not (armed? k rf.resources.timers/poll-kind)) "poll cancelled (policy removed)")
      (is (armed? k rf.resources.timers/stale-kind) "stale still armed")
      (is (armed? k rf.resources.timers/gc-kind) "gc still armed"))
    (rf.resources.timers/cancel-for-key! frame-id k)))

;; ===========================================================================
;; EVENT LEVEL — a poll tick preserves the GC reaper; a GC skip keeps polling
;; ===========================================================================

(deftest poll-tick-preserves-the-gc-timer
  ;; A resource with BOTH poll + GC policies. A settle arms poll + gc in the
  ;; real side table. A poll tick re-arms poll ONLY — the GC reaper MUST
  ;; survive (before the fix the poll re-arm's nil GC delay cancelled it, so an
  ;; owner-free entry would later never be collected).
  (rf/reg-resource :tre/pg (article-spec {:poll-interval-ms long-ms :gc-after-ms long-ms})
                   article-spec-request)
  (let [scope {:user "u"}
        k (rf.resources.state/scoped-resource-key scope :tre/pg {:slug "w"})]
    (ensure! :tre/pg scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (is (armed? k rf.resources.timers/gc-kind) "GC timer armed on settle")
    (is (armed? k rf.resources.timers/poll-kind) "poll timer armed on settle")
    (let [gc-h (timer-handle k rf.resources.timers/gc-kind)]
      (poll-fired! k) ;; poll tick → background refetch + poll-only re-arm
      (testing "rf2-3fc89f.10 — the GC timer is PRESERVED across a poll tick"
        (is (armed? k rf.resources.timers/gc-kind) "GC timer still armed after the poll tick")
        (is (= gc-h (timer-handle k rf.resources.timers/gc-kind)) "GC handle unchanged (not re-armed)"))
      (testing "the poll timer IS re-armed (cancel-then-arm)"
        (is (armed? k rf.resources.timers/poll-kind) "poll timer re-armed")))))

(deftest gc-skip-while-owned-preserves-the-poll-timer
  ;; A GC timer that fires while the entry is still OWNED skips collection and
  ;; re-arms GC ONLY — the entry's active poll MUST survive (before the fix the
  ;; GC re-arm's nil poll delay cancelled it, silently stopping periodic
  ;; refresh on a still-owned, still-polling entry).
  (rf/reg-resource :tre/gp (article-spec {:poll-interval-ms long-ms :gc-after-ms long-ms})
                   article-spec-request)
  (let [scope {:user "u"}
        k (rf.resources.state/scoped-resource-key scope :tre/gp {:slug "w"})]
    (ensure! :tre/gp scope "w" [:route :r 1])
    (succeed! k {:title "W"})
    (is (armed? k rf.resources.timers/poll-kind) "poll timer armed on settle")
    (is (armed? k rf.resources.timers/gc-kind) "GC timer armed on settle")
    (let [poll-h (timer-handle k rf.resources.timers/poll-kind)]
      (gc-fired! k) ;; entry still owned → GC skip → gc-only re-arm
      (is (some? (entry k)) "entry not collected (still owned)")
      (testing "rf2-3fc89f.10 — the poll timer is PRESERVED across a GC skip"
        (is (armed? k rf.resources.timers/poll-kind) "poll timer still armed after the GC skip")
        (is (= poll-h (timer-handle k rf.resources.timers/poll-kind)) "poll handle unchanged"))
      (testing "the GC timer IS re-armed (cancel-then-arm)"
        (is (armed? k rf.resources.timers/gc-kind) "GC timer re-armed")))))

(deftest poll-tick-then-release-still-collects-the-entry
  ;; The end-to-end consequence the bug broke: on a combined poll+GC resource,
  ;; after a poll tick the GC reaper must still be live so that when the last
  ;; owner later releases, an owner-free + idle GC re-check collects the entry.
  (rf/reg-resource :tre/pgc (article-spec {:poll-interval-ms long-ms :gc-after-ms long-ms})
                   article-spec-request)
  (let [scope {:user "u"}
        k (rf.resources.state/scoped-resource-key scope :tre/pgc {:slug "w"})]
    (ensure! :tre/pgc scope "w" [:app :x 1])
    (succeed! k {:title "W"})
    (poll-fired! k)                 ;; poll tick (background refetch in flight)
    ;; settle the poll refetch so the entry is idle again (owner-free-able)
    (succeed! k {:title "W2"})
    (is (armed? k rf.resources.timers/gc-kind) "GC timer survived the poll tick + resettle")
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (is (empty? (:active-owners (entry k))) "entry owner-free after release")
    (gc-fired! k)                   ;; owner-free + idle → collected
    (testing "rf2-3fc89f.10 — an owner-free + idle GC re-check collects the
              entry (the reaper survived the intervening poll ticks)"
      (is (nil? (entry k)) "entry collected by the surviving GC reaper"))))
