(ns day8.re-frame2-xray.panels.hicasso-skip-semantics-cljs-test
  "`:rf.sub/skip` means ONE thing, and both Hicasso derivations have to
  say it (rf2-hic-037, merged-PR audit #8027).

  ## The defect this namespace exists to keep out

  The advisor and the causal slice are two public results derived from
  ONE window, and they disagreed about the same event. `hicasso-advisor`
  correctly recorded a skip as a memo hit — calling it *the single most
  informative topology signal there is* — but derived `searched?` from
  recompute runs alone, so a retained window holding nothing but one
  tagged skip reported `:basis :cap`, said *no search happened*, and told
  the programmer to **raise `:rf.trace/events-retained`** — enlarge a
  window that had already retained the evidence. Meanwhile
  `hicasso-causal`'s link 2 collected every `:subs` item carrying an
  `:rf.sub/id` without filtering the operation, so the same skip appeared
  in a roster labelled *subscriptions recomputed* under an `evidenced`
  chip.

  Reproduced on main with exactly one skip:

      {:memo-hits 1 :advisor-basis :cap :causal-holds [:a] :causal-evidenced true}

  An advisor that sends a reader to the retention knob when the evidence
  was already retained is worse than no advisor: it sends them to fix the
  instrument instead of the code.

  ## Why the rows below drive BOTH views from ONE window

  Two definitions of *did work happen* is what produced the disagreement,
  so the repair is one predicate — `hicasso-helpers/sub-recompute?` — and
  the pin that keeps it one is a test that reads both public results off
  the same fixture. A row that only checked the advisor would go green
  against a causal slice that had drifted back, and vice versa.

  ## And the path that still bypassed the predicate

  Sharing a predicate is not enough if one consumer can decide the answer
  before consulting it. The advisor's fold tested `(nil? sid)` FIRST, so
  identity loss short-circuited the operation question entirely and every
  untagged `:subs` event was counted as a real unnamed RUN — an untagged
  `:rf.sub/skip` gave the advisor `:unnamed-runs 1` and `:by-read {}`
  while link 2 gave `:holds []` and `:skipped {:count 1 :sub-ids []}`, and
  an untagged `:rf.sub/dispose` gave the advisor `:unnamed-runs 1` while
  link 2 reported no recompute and no skip at all (merged-PR audit #8063).
  The two views still disagreed, on exactly the path where identity is
  absent.

  The first regression here covered an untagged RUN beside a tagged skip
  — the adjacent case, not the failing one — which is why
  [[operation-matrix]] is a TABLE: four operations by tagged/untagged, so
  the untagged non-work cells cannot be the ones nobody wrote a row for.

  Pure data → data, so this runs under the JVM target beside the CLJS
  one. The live-runtime claims stay in `hicasso_causal_cljs_test`.

  Normative owner: `tools/xray/spec/028-Hicasso-Advisor.md`."
  (:require [clojure.string :as string]
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [day8.re-frame2-xray.panels.hicasso-advisor :as advisor]
            [day8.re-frame2-xray.panels.hicasso-causal :as causal]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — the producers' own shapes, as `hicasso_advisor_cljs_test` builds
;; them
;; ---------------------------------------------------------------------------

(defn- mounted-envelope
  [boundaries]
  {:schema     hh/consumed-evidence-schema
   :producer   hh/consumed-producer
   :read       :mounted-boundaries
   :scope      :mounted-boundaries
   :basis      :observation
   :complete?  true
   :loss       nil
   :boundaries boundaries})

(defn- boundary
  "One producer boundary row. `reads` is `[[frame-id sub-id] …]`."
  [reads & {:keys [instances read-orders] :or {instances 1 read-orders 1}}]
  {:boundary    {:parent nil
                 :key    (mapv (fn [[f s]] [f s [s]]) reads)}
   :view        hh/unknown
   :source      hh/unknown
   :instances   instances
   :read-orders read-orders
   :frame       (ffirst reads)
   :reads       (mapv (fn [[f s]] {:sub-id s :query [s] :frame-id f :epoch 1}) reads)})

(defn- sub-ev
  "One `:op-type :rf.sub` trace event, in Spec 009's own shape."
  ([sub-id ms] (sub-ev sub-id ms :rf.sub/run))
  ([sub-id ms op]
   {:op-type   :rf.sub
    :operation op
    :tags      (cond-> {:rf.sub/id sub-id}
                 (number? ms) (assoc :rf.sub/elapsed-ms ms))}))

(defn- untagged-ev
  "The same event with its `:rf.sub/id` gone — the shape a stripped or
  never-tagged emission actually has. `:tags {}` rather than a missing
  `:tags`, because that is what the seam produces and it is the shape the
  mutation row in `hicasso_causal_cljs_test` builds too."
  [op]
  {:op-type :rf.sub :operation op :tags {}})

(defn- bundle
  [dispatch-id event-id subs]
  {:dispatch-id dispatch-id
   :event       [event-id]
   :subs        subs})

(defn- both
  "The two PUBLIC results for one window and one boundary, side by side.

  Deliberately assembled the way the panel assembles them: the advisor
  over the mounted census and the timing digest, the slice over the same
  envelopes and the same windows for the boundary the advisor ranked
  first. A fixture that built the two from two windows could not catch a
  disagreement about one."
  [reads windows & {:keys [read-orders] :or {read-orders 1}}]
  (let [envelopes {:mounted-boundaries
                   (mounted-envelope [(boundary reads :read-orders read-orders)])}
        advice    (advisor/advise envelopes (advisor/sub-timing windows))
        row       (first (:rows advice))
        slice     (causal/slice {:envelopes    envelopes
                                 :windows      windows
                                 :boundary-key (get-in row [:boundary :key])})]
    {:advice advice
     :row    row
     :slice  slice
     :link2  (first (filter #(= :subs-recomputed (:id %)) (:links slice)))}))

;; ---------------------------------------------------------------------------
;; The reproduction, pinned
;; ---------------------------------------------------------------------------

(def ^:private skip-only-window
  "One retained bundle, one tagged memo hit, and nothing else."
  {:app/main [(bundle 1 :e/tick [(sub-ev :a nil :rf.sub/skip)])]})

(deftest a-skip-only-window-is-OBSERVED-activity-in-both-views
  ;; THE REGRESSION. Both public results, off one window, in one row — so
  ;; the two cannot drift apart again without this failing.
  (let [{:keys [row link2]} (both [[:app/main :a]] skip-only-window)
        cls (:class row)]

    (testing "the advisor counts the skip as a memo hit and NOT as a run"
      (is (= 1 (get-in row [:axes :frequency :memo-hits])))
      (is (= 0 (get-in row [:axes :frequency :runs]))
          (str "a skip is not a recompute, and promoting one to make the "
               "classification come out right would be the same lie one "
               "layer down")))

    (testing "and classifies the window as observed activity, never as a cap"
      (is (= :memo-hits-only (:observed cls)))
      (is (= :host-opaque (:basis cls))
          (str "evidence WAS retained — reporting `:cap` said the opposite of "
               "what the window held"))
      (is (not= :cap (:basis cls))))

    (testing "so the remedy is a change of INSTRUMENT, not a bigger ring"
      (let [advice (:advice row)]
        (is (= :measure-first (:route advice)))
        (is (= :host-opaque (get-in advice [:refusal :reason])))
        (is (not (string/includes? (:says cls) "events-retained"))
            (str "the classification must not send the reader to the retention "
                 "knob — the evidence it would be reaching for is already in "
                 "the window"))
        (is (not (string/includes? (:says advice) "events-retained"))
            "and neither must the remedy")))

    (testing "and the causal slice does NOT report the skip as a recompute"
      (is (= [] (:holds link2))
          (str "the roster is recomputes only; the skip belongs to the "
               "`:skipped` field, and a roster holding both is the defect"))
      (is (= 1 (get-in link2 [:skipped :count])))
      (is (= [:a] (get-in link2 [:skipped :sub-ids])))
      (is (true? (:evidenced? link2))
          (str "still evidenced — the bundle WAS surveyed and the survey came "
               "back with no recompute, which is a result rather than a gap"))
      (is (string/includes? (:says link2) "no subscription recomputed"))
      (is (string/includes? (:says link2) "memo hit")))))

(deftest the-two-views-agree-about-every-sub-operation-in-one-bundle
  ;; The shared-predicate pin. One bundle carrying every `:op-type :rf.sub`
  ;; operation Spec 009 routes through the projection's `:subs` slot; the
  ;; advisor's recompute COUNT and the slice's recompute ROSTER must
  ;; describe the same set of events. They are two readings of one
  ;; predicate, so a divergence here is a second predicate having grown
  ;; back.
  (let [windows {:app/main [(bundle 1 :e/tick
                                    [(sub-ev :a 2.0 :rf.sub/run)
                                     (sub-ev :b nil :rf.sub/create)
                                     (sub-ev :c nil :rf.sub/skip)
                                     (sub-ev :d nil :rf.sub/skip)
                                     (sub-ev :e nil :rf.sub/dispose)])]}
        {:keys [row link2]} (both [[:app/main :a] [:app/main :b] [:app/main :c]
                                   [:app/main :d] [:app/main :e]]
                                  windows)]
    (is (= 2 (get-in row [:axes :frequency :runs]))
        "`:rf.sub/run` + `:rf.sub/create` — the two operations that mean work")
    (is (= 2 (count (:holds link2)))
        "and the slice's roster names exactly those two")
    (is (= #{:a :b} (set (:holds link2))))

    (is (= 2 (get-in row [:axes :frequency :memo-hits])))
    (is (= 2 (get-in link2 [:skipped :count]))
        "the memo hits agree too, on their own field in both views")

    (testing "and `:rf.sub/dispose` is neither — an eviction is not a recompute"
      (is (not (contains? (set (:holds link2)) :e))
          (str "reading the `:subs` slot unfiltered put a DISPOSE in a roster "
               "labelled `subscriptions recomputed` as well"))
      (is (zero? (get-in row [:attributable :edges 4 :runs]))
          "and the advisor prices the disposed edge at no work either")
      (is (zero? (get-in row [:attributable :edges 4 :memo-hits]))
          "nor as a memo hit — an eviction is neither"))

    (testing "the recompute count and the roster size are the SAME reading"
      (is (= (get-in row [:axes :frequency :runs]) (count (:holds link2)))
          (str "one predicate, `hicasso-helpers/sub-recompute?`, asked twice — "
               "two definitions of `did work happen` is what produced the "
               "disagreement this row exists to prevent")))))

;; ---------------------------------------------------------------------------
;; The four operations, tagged and untagged — the whole surface, in one table
;; ---------------------------------------------------------------------------

(defn- advisor-counts
  "The advisor fold's window-level reading, in the four quantities link 2
  also states."
  [timing]
  {:named-runs    (reduce + 0 (map #(get (val %) :runs 0) (:by-read timing)))
   :unnamed-runs  (get timing :unnamed-runs 0)
   :named-skips   (reduce + 0 (map #(get (val %) :memo-hits 0) (:by-read timing)))
   :unnamed-skips (get timing :unnamed-skips 0)})

(defn- link2-counts
  "Link 2's reading of the SAME window, in the same four quantities.

  Read off the link's public fields rather than recomputed: `:holds` is
  the named recompute roster (or `:unknown` when work joined to nothing),
  the `:uncorrelated` loss carries the unnamed run count, and `:skipped`
  states the memo hits as a total beside the ids it could name — so the
  untagged skips are exactly the total the ids do not account for."
  [link2]
  (let [holds (:holds link2)
        loss  (:loss link2)
        named-skips (count (get-in link2 [:skipped :sub-ids]))]
    {:named-runs    (if (hh/unknown? holds) 0 (count holds))
     :unnamed-runs  (if (= :uncorrelated (:reason loss)) (:dropped loss) 0)
     :named-skips   named-skips
     :unnamed-skips (- (get-in link2 [:skipped :count]) named-skips)}))

(def ^:private operation-matrix
  "Every `:op-type :rf.sub` operation Spec 009 routes through the
  projection's `:subs` slot, WITH its `:rf.sub/id` and without it, and what
  each one is.

  The untagged half is the half that was wrong (rf2-hic-037, merged-PR
  audit #8063): the advisor's fold tested `(nil? sid)` BEFORE
  `hh/sub-recompute?`, so identity loss decided the classification and
  every untagged event became a run. The two untagged non-work rows are
  therefore the rows this table exists for — an untagged `:rf.sub/skip`
  read as one unnamed run against link 2's one memo hit, and an untagged
  `:rf.sub/dispose` read as one unnamed run against link 2's nothing at
  all.

  The absolute expectation is written out per row rather than only
  comparing the two views, because two views wrong the same way agree
  perfectly. The untagged run and create rows are the controls that keep
  the table from being satisfied by *everything untagged is zero* — they
  MUST still count an unnamed run, which is the rule the repair had to
  leave standing."
  [[:rf.sub/run     true  {:named-runs 1 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/run     false {:named-runs 0 :unnamed-runs 1 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/create  true  {:named-runs 1 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/create  false {:named-runs 0 :unnamed-runs 1 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/skip    true  {:named-runs 0 :unnamed-runs 0 :named-skips 1 :unnamed-skips 0}]
   [:rf.sub/skip    false {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 1}]
   [:rf.sub/dispose true  {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/dispose false {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]])

(deftest every-operation-reads-the-same-in-both-views-with-or-without-an-id
  ;; THE MATRIX. One event per cell, both public results off it, and each
  ;; cell asserted twice: against what the operation IS, and against the
  ;; other view. Either assertion alone is escapable — the first by two
  ;; views drifting together, the second by both drifting the same way.
  (doseq [[op tagged? expected] operation-matrix]
    (testing (str op (if tagged? " with an `:rf.sub/id`" " with NO `:rf.sub/id`"))
      (let [ev      (if tagged? (sub-ev :a nil op) (untagged-ev op))
            windows {:app/main [(bundle 1 :e/tick [ev])]}
            {:keys [advice link2]} (both [[:app/main :a]] windows)
            adv     (advisor-counts (:timing advice))
            l2      (link2-counts link2)]
        (is (= expected adv)
            (str "the advisor's fold must classify the OPERATION first — "
                 "an untagged event is still whatever operation it is"))
        (is (= expected l2)
            "and link 2's roster must read the same event the same way")
        (is (= adv l2)
            (str "one window, two public results, ONE answer to `did work "
                 "happen` — the disagreement is the defect, and identity "
                 "loss is where it survived"))))))

(deftest an-untagged-NON-WORK-event-is-uncorrelated-observation-never-work
  ;; The audit's two reproductions, pinned with their own numbers. The
  ;; matrix above proves the counts agree; this proves the advisor states
  ;; the right KIND of absence about them — a skip that joins to nothing
  ;; is an uncorrelated observation, and an eviction is not an absence at
  ;; all.
  (testing "an untagged `:rf.sub/skip` — was `:unnamed-runs 1`, `:by-read {}`"
    (let [t (advisor/sub-timing
              {:app/main [(bundle 1 :e/tick [(untagged-ev :rf.sub/skip)])]})]
      (is (zero? (:unnamed-runs t))
          "no run happened; the memo answered")
      (is (nil? (:unnamed-loss t))
          "and no work is missing an id, so there is no unnamed-RUN account")
      (is (= 1 (:unnamed-skips t)))
      (is (= :uncorrelated (:reason (:unnamed-skip-loss t)))
          (str "the skip is real and the CELL it held for joins to nothing — "
               "the same uncorrelated state an unnamed run is in, about a "
               "different fact"))
      (is (= 1 (:dropped (:unnamed-skip-loss t))))))

  (testing "an untagged `:rf.sub/dispose` — was `:unnamed-runs 1` against link 2's nothing"
    (let [t (advisor/sub-timing
              {:app/main [(bundle 1 :e/tick [(untagged-ev :rf.sub/dispose)])]})]
      (is (zero? (:unnamed-runs t)))
      (is (zero? (:unnamed-skips t))
          "an eviction is neither work nor a memo hit, tagged or not")
      (is (nil? (:unnamed-loss t)))
      (is (nil? (:unnamed-skip-loss t))
          (str "and it is not an absence either — nothing was dropped, so "
               "there is nothing to account for"))
      (is (= {} (:by-read t))))))

(deftest the-shared-predicate-is-the-one-in-the-shared-algebra
  ;; The predicate is a public var in `hicasso-helpers` precisely so both
  ;; derivations can consult it and a reader can see that they do.
  (is (= #{:rf.sub/run :rf.sub/create} hh/sub-recompute-operations))
  (doseq [op [:rf.sub/run :rf.sub/create]]
    (is (true? (hh/sub-recompute? {:operation op})))
    (is (false? (hh/sub-skip? {:operation op}))))
  (doseq [op [:rf.sub/skip :rf.sub/dispose]]
    (is (false? (hh/sub-recompute? {:operation op}))))
  (is (true? (hh/sub-skip? {:operation :rf.sub/skip})))
  (is (false? (hh/sub-skip? {:operation :rf.sub/dispose}))))

;; ---------------------------------------------------------------------------
;; The three states stay apart — a skip-only window is neither of the others
;; ---------------------------------------------------------------------------

(deftest an-EMPTY-window-still-says-cap-and-still-sends-the-reader-to-the-knob
  ;; The `:cap` sentence is right for a window that retained NOTHING, and
  ;; the repair must not have taken it away — a boundary with no activity
  ;; at all really does need a bigger ring.
  (let [{:keys [row]} (both [[:app/main :a]] {:app/main []})
        cls (:class row)]
    (is (= :nothing (:observed cls)))
    (is (= :cap (:basis cls)))
    (is (string/includes? (:says cls) "events-retained"))
    (is (string/includes? (:says cls) "no recompute and no memo hit")
        "and it now names both absences, because both are absent")))

(deftest the-three-unattributed-states-are-pairwise-DISTINCT
  ;; `:cap`, `:host-opaque`-with-recomputes and `:host-opaque`-with-memo-hits
  ;; are three windows with three remedies. Collapsing any two sends a
  ;; reader to the wrong place, which is the whole failure mode this
  ;; classifier is arranged against.
  (let [capped   (:class (:row (both [[:app/main :a]] {:app/main []})))
        searched (:class (:row (both [[:app/main :a]]
                                     {:app/main [(bundle 1 :e [(sub-ev :a 0.05)])]})))
        memo     (:class (:row (both [[:app/main :a]] skip-only-window)))
        all      [capped searched memo]]
    (is (= [:unattributed :unattributed :unattributed] (mapv :owner all))
        "the OWNER is honestly unknown in all three — that is not the axis")
    (is (= [:nothing :recomputes :memo-hits-only] (mapv :observed all))
        "what was OBSERVED is the axis, and it is carried in data")
    (is (= 3 (count (into #{} (map :says) all)))
        "three states, three sentences")
    (is (= 1 (count (filter #(string/includes? (:says %) "events-retained") all)))
        (str "and exactly ONE of them sends the reader to the retention knob — "
             "the one whose window really is empty"))))

;; ---------------------------------------------------------------------------
;; The repair does not weaken what already held
;; ---------------------------------------------------------------------------

(deftest an-untagged-recompute-is-still-UNKNOWN-and-never-an-empty-roster
  ;; Filtering the roster by operation must not disturb the rule that an
  ;; unnamed RUN states `:unknown` rather than `[]`. A bundle carrying an
  ;; untagged run beside a tagged skip is the case where a careless filter
  ;; would count the skip's absence of a tag as the run's.
  (let [windows {:app/main [(bundle 1 :e/tick
                                    [(untagged-ev :rf.sub/run)
                                     (sub-ev :a nil :rf.sub/skip)])]}
        {:keys [link2]} (both [[:app/main :a]] windows)]
    (is (hh/unknown? (:holds link2))
        "work happened and joins to nothing — never `[]`")
    (is (= :uncorrelated (:reason (:loss link2))))
    (is (= 1 (:dropped (:loss link2)))
        "one UNNAMED RUN, and not the untagged-skip count added to it")
    (is (= 1 (get-in link2 [:skipped :count])))
    (is (string/includes? (:says link2) "join to no subscription"))))

(deftest a-window-with-both-runs-and-skips-classifies-on-the-runs
  ;; The new arm must fire only when there is no recompute at all. A
  ;; boundary that both ran and skipped is a searched window and keeps the
  ;; sentence it had.
  (let [{:keys [row link2]}
        (both [[:app/main :a]]
              {:app/main [(bundle 1 :e [(sub-ev :a 0.05)
                                        (sub-ev :a nil :rf.sub/skip)])]})]
    (is (= :recomputes (:observed (:class row))))
    (is (= :host-opaque (:basis (:class row))))
    (is (string/includes? (:says (:class row)) "the window was searched"))
    (is (= [:a] (:holds link2)))
    (is (= 1 (get-in link2 [:skipped :count])))))

(deftest an-oscillating-read-set-is-still-a-topology-finding-with-only-skips
  ;; Oscillation is a fact about the entry cache and holds independently
  ;; of the clock, so it must still outrank the memo-only arm.
  (let [{:keys [row]} (both [[:app/main :a]] skip-only-window :read-orders 4)]
    (is (= :read-topology (:owner (:class row))))
    (is (= :memo-hits-only (:observed (:class row)))
        "and it still records what the window actually held")))
