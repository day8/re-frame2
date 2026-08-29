(ns re-frame.hicasso.evidence-sink-cljs-test
  "THE EVIDENCE SINK SEAM (HD-005), against the PRODUCTION collector.

  `re-frame.hicasso.impl.evidence` is two lines — a holder and a setter —
  and the tap points are the collector's own: `:edges-changed` where a
  commit's `subscribe` records its reads, `:commit` where a flush names
  its dirty cells and readers. Those two event shapes are the seam's
  vocabulary; anything written against them attaches to the fused table
  unchanged.

  ## Why this file exists at all

  The law below was first discharged in the bench tree —
  `re-frame.bench.hicasso.arm1.cell-table-laws-cljs-test`'s HD-005 row —
  against the bench twin's OWN copy of the runtime. The freeze pin that
  once made that green transfer to the shipped collector was retired
  (frozen-sources.edn is down to `front/slot.cljc`), so the production
  seam had NO test that could go red if its tap points drifted from the
  documented vocabulary: not the detached silence, not either event
  shape, not the empty-read-set guard. This file is that law, restated
  against `re-frame.hicasso.impl.evidence/set-evidence-sink!` and the
  production collector doors.

  ## The detached path is the subject, not an arrangement

  The seam's whole design claim is what the DETACHED path costs: one
  deref and one nil test at each tap point, with nothing built when no
  sink listens. A test cannot count a deref, but it can pin the
  behavioural half — with no sink attached the table does its work and
  says nothing, and detaching an attached sink restores that silence —
  which is the half a redesign through a shared `evidence!` door would
  break first."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.evidence :as impl-evidence]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::evidence-sink)

(rf/reg-sub :es/item (fn [db _] (:item db)))

(rf/reg-event :es/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :es/bump (fn [{:keys [db]} _] {:db (update db :item inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness — the same two collector doors React drives
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:es/seed {:item 1}]))
  frame-id)

(defn- mount!
  "Probe `body-fn` through [[collector/render-body]] and COMMIT it —
  `subscribe` is what fires the `:edges-changed` tap, exactly as React's
  passive effect would. Answers `{:stop! … :notified …}`."
  [body-fn]
  (collector/render-body frame-id body-fn {})
  (let [!notified (atom 0)
        stop!     (collector/commit-boundary! (collector/last-reads)
                                              (fn [] (swap! !notified inc)))]
    {:stop! stop! :notified !notified}))

(defn- k [query-v] [frame-id query-v])

;; ---------------------------------------------------------------------------
;; The law
;; ---------------------------------------------------------------------------

(deftest the-evidence-seam-is-detached-by-default-and-attachable-without-redesign
  (seeded!)
  (let [!seen (atom [])]
    (testing "the premise: nothing is attached — the default this whole
              file is about"
      (is (nil? @impl-evidence/!evidence-sink)))

    (testing "with no sink attached the table does its work and says nothing"
      (let [b (mount! (fn [_] (h/sub [:es/item]) nil))]
        (rf/with-frame frame-id (rf/dispatch-sync [:es/bump]))
        (is (= 1 @(:notified b)) "the boundary was really notified — the
                                  silence below is the seam's, not a dead
                                  table's")
        (is (= [] @!seen))
        ((:stop! b))))

    (testing "an attached sink sees the edge change at commit and the
              dirty set at flush, in the documented shapes"
      (impl-evidence/set-evidence-sink! (fn [ev] (swap! !seen conj ev)))
      (try
        (let [b (mount! (fn [_] (h/sub [:es/item]) nil))]
          (rf/with-frame frame-id (rf/dispatch-sync [:es/bump]))
          ;; The message prints only the :event tags — an event's
          ;; :boundary is a live registration whose cells point back at
          ;; it, and stringifying that cycle overflows the stack.
          (is (= 2 (count @!seen))
              (str "one edge event and one commit event, no more: "
                   (mapv :event @!seen)))
          (let [[edges commit] @!seen]
            (is (= :edges-changed (:event edges)))
            (is (= #{(k [:es/item])} (:added edges)))
            (is (= #{} (:dropped edges))
                "this wiring never drops: a narrowed read set is a fresh
                 registration, and the old one's cleanup took its
                 memberships with it")
            (is (some? (:boundary edges)))
            (is (= :commit (:event commit)))
            (is (= #{(k [:es/item])} (:dirty-subs commit)))
            ;; Compared by count + identity rather than as set equality:
            ;; a failing set compare would pr-str the registrations, and
            ;; their cells point back at them.
            (is (= 1 (count (:dirty-boundaries commit))))
            (is (identical? (:boundary edges) (first (:dirty-boundaries commit)))
                "the commit names the SAME registration the edge event
                 carried — the two events join on the boundary, which is
                 what lets a consumer fuse them into one table"))
          ((:stop! b)))
        (finally (impl-evidence/set-evidence-sink! nil))))

    (testing "a boundary that read nothing emits no edge change — the
              seam reports change, not traffic"
      (reset! !seen [])
      (impl-evidence/set-evidence-sink! (fn [ev] (swap! !seen conj ev)))
      (try
        (let [b (mount! (fn [_] [:li "static"]))]
          (is (= [] @!seen))
          ((:stop! b)))
        (finally (impl-evidence/set-evidence-sink! nil))))

    (testing "detaching restores silence"
      (reset! !seen [])
      (impl-evidence/set-evidence-sink! (fn [ev] (swap! !seen conj ev)))
      (impl-evidence/set-evidence-sink! nil)
      (let [b (mount! (fn [_] (h/sub [:es/item]) nil))]
        (rf/with-frame frame-id (rf/dispatch-sync [:es/bump]))
        (is (= 1 @(:notified b)) "the table still works")
        (is (= [] @!seen) "and says nothing again")
        ((:stop! b))))))
