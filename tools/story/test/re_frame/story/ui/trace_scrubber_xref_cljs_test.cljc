(ns re-frame.story.ui.trace-scrubber-xref-cljs-test
  "Trace × scrubber cross-frame correlation, scrub-on-story-load default,
  and nav-between-stories scenarios (rf2-3dciu).

  Pairs with the existing `re-frame.story-scrubber-xref-test` (JVM-only,
  pure helper coverage of cascade-id resolution, filter, highlight
  predicate). This namespace pins the per-frame isolation +
  navigation behaviour spec/012 §Selection state +
  spec/015 §trace × scrubber cross-reference call out as
  Deferred under bd:rf2-3dciu:

  - **Cross-frame trace correlation** — two variants in adjacent frames
    each have their own history; scrubbing frame A's epoch must NOT
    affect frame B's trace filter. The pure cross-ref helpers take a
    `history` vector + an `epoch-id`; the per-frame isolation is the
    callers' responsibility — typically the `selections` ratom keyed
    by variant-id. The contract pinned here: running both helpers
    against two independent history vectors yields independent results
    AND the per-frame selection key set is disjoint.

  - **Scrub-on-story-load default** — a freshly-selected variant
    initialises with no scrub in flight. The cross-ref helpers' nil-cap
    branch is the identity (every cascade visible, no highlight). This
    is the spec/012 §default state: opening a story renders the trace
    panel unfiltered. We pin the helper's behaviour under the canonical
    'fresh load' inputs (cap=nil, selected-cascade-id=nil).

  - **Nav scenarios — switching variants preserves per-frame state**
    — when the shell navigates from variant A to variant B and back to
    A, the cross-ref helpers against A's history yield the SAME visible
    set as before the detour. The selections ratom shape (a map keyed
    by variant-id) means switching does not clobber the prior frame's
    selection; pinning the data-shape contract is enough — the CLJS
    `selections` ratom + `select-epoch!` mutations are JVM-unreachable.

  Pure data → data; both arms run on JVM (clojure -M:test) and CLJS
  (npm run test:cljs)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.story.ui.scrubber-xref :as xref]))

;; ---- fixture data --------------------------------------------------------
;;
;; Two independent frame histories — frame A (variant :story.a/v) and
;; frame B (variant :story.b/v). Each has two epochs with non-overlapping
;; dispatch-ids so a scrub in one frame is recognisable in the other's
;; data.

(def ^:private frame-a-cascade-1
  {:dispatch-id 100
   :event       [:a/init]
   :handler     {:id 2 :tags {:dispatch-id 100}}
   :fx          {:id 3 :tags {:dispatch-id 100}}
   :effects     [{:id 4 :tags {:dispatch-id 100}}]
   :subs        []
   :renders     [{:id 5 :tags {:dispatch-id 100}}]
   :other       []})

(def ^:private frame-a-cascade-2
  {:dispatch-id 110
   :event       [:a/inc]
   :handler     {:id 6 :tags {:dispatch-id 110}}
   :fx          {:id 7 :tags {:dispatch-id 110}}
   :effects     [{:id 8 :tags {:dispatch-id 110}}]
   :subs        []
   :renders     [{:id 9 :tags {:dispatch-id 110}}]
   :other       []})

(def ^:private frame-b-cascade-1
  {:dispatch-id 200
   :event       [:b/init]
   :handler     {:id 22 :tags {:dispatch-id 200}}
   :fx          {:id 23 :tags {:dispatch-id 200}}
   :effects     [{:id 24 :tags {:dispatch-id 200}}]
   :subs        []
   :renders     [{:id 25 :tags {:dispatch-id 200}}]
   :other       []})

(def ^:private frame-b-cascade-2
  {:dispatch-id 210
   :event       [:b/inc]
   :handler     {:id 26 :tags {:dispatch-id 210}}
   :fx          {:id 27 :tags {:dispatch-id 210}}
   :effects     [{:id 28 :tags {:dispatch-id 210}}]
   :subs        []
   :renders     [{:id 29 :tags {:dispatch-id 210}}]
   :other       []})

(def ^:private frame-a-epoch-1
  {:epoch-id      1
   :frame         :story.a/v
   :trigger-event [:a/init]
   :trace-events  [{:id 2 :tags {:dispatch-id 100}}
                   {:id 5 :tags {:dispatch-id 100}}]})

(def ^:private frame-a-epoch-2
  {:epoch-id      2
   :frame         :story.a/v
   :trigger-event [:a/inc]
   :trace-events  [{:id 6 :tags {:dispatch-id 110}}
                   {:id 9 :tags {:dispatch-id 110}}]})

(def ^:private frame-b-epoch-1
  {:epoch-id      3
   :frame         :story.b/v
   :trigger-event [:b/init]
   :trace-events  [{:id 22 :tags {:dispatch-id 200}}
                   {:id 25 :tags {:dispatch-id 200}}]})

(def ^:private frame-b-epoch-2
  {:epoch-id      4
   :frame         :story.b/v
   :trigger-event [:b/inc]
   :trace-events  [{:id 26 :tags {:dispatch-id 210}}
                   {:id 29 :tags {:dispatch-id 210}}]})

(def ^:private history-a [frame-a-epoch-1 frame-a-epoch-2])
(def ^:private history-b [frame-b-epoch-1 frame-b-epoch-2])

(def ^:private cascades-a [frame-a-cascade-1 frame-a-cascade-2])
(def ^:private cascades-b [frame-b-cascade-1 frame-b-cascade-2])

;; ===========================================================================
;; rf2-3dciu — cross-frame trace correlation
;;
;; Spec/012 §Selection state — selections are per-frame. The pure helpers
;; operate on the (cascade-vector, history, selected-epoch) triple. Per-
;; frame isolation lives at the caller (the `selections` defonce ratom
;; keyed by variant-id). The contract this test pins:
;;
;;   Scrubbing frame A's epoch yields a cap derived from frame A's
;;   history; applying that cap to frame B's cascades is GARBAGE — the
;;   cap event-ids are from a different process-monotonic slice. The
;;   shell MUST therefore look up frame A's selected-epoch ONLY against
;;   frame A's history, and frame B's against frame B's. This is the
;;   property the per-frame ratom enforces.
;; ===========================================================================

(deftest cross-frame-scrub-isolates-per-history
  (testing "scrubbing frame A's epoch-1 → cap derived from history-a
            (5); cap derived from history-b for the same epoch-id is
            nil (the epoch-id isn't in history-b). The per-frame
            ratom shape ensures the shell never feeds frame A's cap
            into frame B's filter, but pinning the underlying helper
            contract documents the boundary: caps are not portable
            across histories"
    (let [cap-a-against-a (xref/max-trace-event-id-for-epoch history-a 1)
          cap-a-against-b (xref/max-trace-event-id-for-epoch history-b 1)]
      (is (= 5 cap-a-against-a)
          "frame A's history resolves epoch 1 → cap 5")
      (is (nil? cap-a-against-b)
          "frame B's history has no epoch 1 → nil cap. The per-frame
           selections ratom guarantees this never happens in practice;
           pinning the nil-cap branch documents the safety net"))))

(deftest cross-frame-cascade-id-isolates-per-history
  (testing "frame A's epoch-1 dispatch-id (100) is NOT in frame B's
            history; frame B's epoch-3 dispatch-id (200) is NOT in
            frame A's. Pure-data isolation — the helpers return nil
            when the epoch-id isn't found in the supplied history"
    (is (= 100 (xref/cascade-id-for-epoch history-a 1)))
    (is (nil? (xref/cascade-id-for-epoch history-b 1))
        "frame B's history doesn't carry epoch 1")
    (is (= 200 (xref/cascade-id-for-epoch history-b 3)))
    (is (nil? (xref/cascade-id-for-epoch history-a 3))
        "frame A's history doesn't carry epoch 3")))

(deftest cross-frame-filter-isolates
  (testing "scrubbing frame A to its first epoch (cap 5) filters frame
            A's cascades correctly. The SAME cap applied to frame B's
            cascades drops EVERY frame B cascade (all B cascades' min
            event ids are > 5 because B's process-monotonic stream was
            allocated after A's). Pinning this proves the cross-frame
            leak surface: if the shell ever passed frame A's cap into
            frame B's filter the result would be garbage (every B
            cascade hidden) — visibly wrong"
    (let [visible-a (xref/filter-cascades-up-to cascades-a 5)
          ;; What happens if the shell mis-routed frame A's cap into B?
          ;; The B cascades' min event ids are 22 + 26, both > 5 — so
          ;; every B cascade drops out. This is the wrong behaviour the
          ;; per-frame ratom prevents.
          leaked-b  (xref/filter-cascades-up-to cascades-b 5)]
      (is (= [frame-a-cascade-1] visible-a)
          "frame A scrubbed correctly: only cascade-1 visible")
      (is (= [] leaked-b)
          "every frame B cascade > cap 5 — proves the cap is not
           portable across frames. Visible bug if the shell ever
           leaked."))))

(deftest cross-frame-highlight-disjoint
  (testing "frame A's selected-cascade-id 100 highlights none of frame
            B's cascades (their dispatch-ids are 200 + 210). Symmetric
            in the reverse direction. The pure helper is a == check
            on :dispatch-id — pinning the disjoint id-spaces documents
            why per-frame selection state isolates without further
            framework intervention"
    (let [selected-a 100
          selected-b 200]
      ;; Frame A's selection — only frame-a-cascade-1 matches.
      (is (true?  (xref/cascade-matches-selected-epoch? frame-a-cascade-1 selected-a)))
      (is (false? (xref/cascade-matches-selected-epoch? frame-a-cascade-2 selected-a)))
      (is (false? (xref/cascade-matches-selected-epoch? frame-b-cascade-1 selected-a))
          "frame B's cascades NEVER match frame A's selected-cascade-id
           — distinct id-spaces per spec/012 §Selection state")
      (is (false? (xref/cascade-matches-selected-epoch? frame-b-cascade-2 selected-a)))
      ;; Frame B's selection — only frame-b-cascade-1 matches.
      (is (true?  (xref/cascade-matches-selected-epoch? frame-b-cascade-1 selected-b)))
      (is (false? (xref/cascade-matches-selected-epoch? frame-a-cascade-1 selected-b))))))

;; ===========================================================================
;; rf2-3dciu — scrub-on-story-load default
;;
;; Spec/012 §default state: a freshly-selected variant has no scrub in
;; flight. The trace panel renders unfiltered (every cascade visible);
;; no row carries the selected highlight. Pinning the helper behaviour
;; under the canonical 'no-scrub' inputs.
;; ===========================================================================

(deftest scrub-on-story-load-no-cap-no-highlight
  (testing "a fresh variant load: selected-epoch=nil → cap=nil → every
            cascade visible (the identity branch of filter-cascades-up-to);
            selected-cascade-id=nil → no cascade matches (highlight
            predicate is false for nil). This is the spec/012 §default
            state — opening a story renders the trace panel unfiltered"
    (let [selected-epoch nil
          cap            (xref/max-trace-event-id-for-epoch history-a selected-epoch)
          selected-cid   (xref/cascade-id-for-epoch       history-a selected-epoch)
          visible        (xref/filter-cascades-up-to       cascades-a cap)]
      (is (nil? cap)
          "nil epoch-id → nil cap — the no-scrub branch")
      (is (nil? selected-cid)
          "nil epoch-id → nil selected-cascade-id — no highlight")
      (is (= cascades-a visible)
          "every cascade visible — full unfiltered trace panel")
      (doseq [c cascades-a]
        (is (false? (xref/cascade-matches-selected-epoch? c selected-cid))
            "no cascade carries the highlight in the no-scrub branch")))))

(deftest scrub-on-story-load-empty-history-also-no-scrub
  (testing "a brand-new variant with NO epochs yet (history=[]) behaves
            identically to selected-epoch=nil: every (empty) cascade
            list visible, no highlight, no errors"
    (let [empty-history  []
          empty-cascades []
          selected-epoch nil
          cap            (xref/max-trace-event-id-for-epoch empty-history selected-epoch)
          visible        (xref/filter-cascades-up-to       empty-cascades cap)]
      (is (nil? cap))
      (is (= [] visible)))))

;; ===========================================================================
;; rf2-3dciu — nav scenarios: switching variants preserves per-frame state
;;
;; The selections ratom is a `{variant-id epoch-id}` map. Switching
;; variants reads/writes the slot for the current variant; the prior
;; variant's slot survives. The pure-data contract pinned here: a map
;; that carries two distinct variant slots survives a series of in-place
;; updates against ONE slot — the other slot is untouched.
;; ===========================================================================

(deftest nav-preserves-other-frame-selection
  (testing "the selections ratom shape: switching between variant A and
            B and scrubbing within each leaves each frame's selection
            slot independent. Mutate A's slot → B's slot unchanged.
            Mutate B's slot → A's slot unchanged.

            The CLJS `selections` defonce holds this map; the JVM
            cross-ref helpers don't touch it — we exercise the
            equivalent pure-data shape here so the shape contract is
            JVM-testable. The shell's `select-epoch!` is `(swap!
            selections assoc variant-id epoch-id)` — proven safe by
            the map-update semantics below"
    (let [initial     {}
          after-a-1   (assoc initial   :story.a/v 1)
          after-b-3   (assoc after-a-1 :story.b/v 3)
          after-a-2   (assoc after-b-3 :story.a/v 2)]
      ;; A's first scrub: only A's slot populated.
      (is (= {:story.a/v 1} after-a-1))
      ;; B's first scrub: A's slot survives, B's slot lands beside it.
      (is (= 1 (:story.a/v after-b-3))
          "frame A's selection survives the nav to frame B")
      (is (= 3 (:story.b/v after-b-3)))
      ;; A's second scrub: A's slot updates, B's slot survives.
      (is (= 2 (:story.a/v after-a-2)))
      (is (= 3 (:story.b/v after-a-2))
          "frame B's selection survives the nav back to frame A"))))

(deftest nav-clear-one-frame-leaves-other
  (testing "spec/012 §Selection state — `drop-selection!` (called from
            shell.cljs `teardown-listeners-for-variant!`) drops ONE
            variant's slot. The other frame's selection survives —
            navigating back to it picks up the prior scrub state.
            Map-dissoc semantics pin the contract"
    (let [populated {:story.a/v 5 :story.b/v 7}
          dropped-a (dissoc populated :story.a/v)]
      (is (nil? (:story.a/v dropped-a))
          "frame A's selection is gone after drop-selection!")
      (is (= 7 (:story.b/v dropped-a))
          "frame B's selection survives — the drop is per-variant"))))

(deftest nav-roundtrip-restores-filter-cap
  (testing "spec/012 §Implementation files + nav semantics: scrubbing
            frame A → switching to B → switching back to A produces the
            SAME visible cascade set against A's history. Pin the cross-
            ref helper's determinism under repeated calls so the shell
            can rely on 'compute-on-render' rather than caching"
    ;; Scrub A to its first epoch.
    (let [cap-1     (xref/max-trace-event-id-for-epoch history-a 1)
          visible-1 (xref/filter-cascades-up-to cascades-a cap-1)
          ;; Some unrelated work happens in B (irrelevant to A's helpers).
          _         (xref/max-trace-event-id-for-epoch history-b 3)
          ;; Return to A — same inputs, same outputs.
          cap-2     (xref/max-trace-event-id-for-epoch history-a 1)
          visible-2 (xref/filter-cascades-up-to cascades-a cap-2)]
      (is (= cap-1 cap-2)
          "the cap is deterministic — same history + same epoch-id → same cap")
      (is (= visible-1 visible-2)
          "the visible cascade vector is deterministic — same inputs
           reproduce the same filtered subset"))))
