(ns day8.re-frame2-xray.panels.fresco-skip-semantics-cljs-test
  "`:rf.sub/skip` means ONE thing, and both Fresco derivations have to
  say it.

  ## The defect this namespace exists to keep out

  The advisor and the causal slice are two public results derived from
  ONE window, and they must agree about the same event. `fresco-advisor`
  records a skip as a memo hit — *the single most informative topology
  signal there is*. An advisor that derived `searched?` from recompute
  runs alone would report a retained window holding nothing but one
  tagged skip as `:basis :cap`, say *no search happened*, and tell the
  programmer the window is empty and to **reproduce the interaction** —
  for evidence the window had already retained. A link 2 that collected every
  `:subs` item carrying an `:rf.sub/id` without filtering the operation
  would put the same skip in a roster labelled *subscriptions
  recomputed* under an `evidenced` chip. With exactly one skip, that
  pair reads:

      {:memo-hits 1 :advisor-basis :cap :causal-holds [:a] :causal-evidenced true}

  An advisor that calls a window empty when the evidence was already
  retained is worse than no advisor: it sends the reader back to the
  instrument instead of to the code.

  ## Why the rows below drive BOTH views from ONE window

  Two definitions of *did work happen* produce the disagreement, so there
  is one predicate — `fresco-helpers/sub-recompute?` — and the pin that
  keeps it one is a test that reads both public results off the same
  fixture. A row that only checked the advisor would go green against a
  causal slice that had drifted, and vice versa.

  ## And the path that can bypass the predicate

  Sharing a predicate is not enough if one consumer can decide the answer
  before consulting it. A fold testing `(nil? sid)` FIRST would let
  identity loss short-circuit the operation question entirely and count
  every untagged `:subs` event as a real unnamed RUN — an untagged
  `:rf.sub/skip` would give the advisor `:unnamed-runs 1` and
  `:by-read {}` while link 2 gave `:holds []` and
  `:skipped {:count 1 :sub-ids []}`, and an untagged `:rf.sub/dispose`
  would give the advisor `:unnamed-runs 1` while link 2 reported no
  recompute and no skip at all. The two views would disagree on exactly
  the path where identity is absent.

  An untagged RUN beside a tagged skip is the adjacent case, not that
  one, which is why [[operation-matrix]] is a TABLE: four operations by tagged/untagged, so
  the untagged non-work cells cannot be the ones nobody wrote a row for.

  Pure data → data, so this runs under the JVM target beside the CLJS
  one. The live-runtime claims stay in `fresco_causal_cljs_test`.

  Normative owner: `tools/xray/spec/028-Fresco-Advisor.md`."
  (:require [clojure.string :as string]
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [day8.re-frame2-xray.panels.fresco-advisor :as advisor]
            [day8.re-frame2-xray.panels.fresco-causal :as causal]
            [day8.re-frame2-xray.panels.fresco-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — the producers' own shapes, as `fresco_advisor_cljs_test` builds
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
  mutation row in `fresco_causal_cljs_test` builds too."
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
  ;; Both public results, off one window, in one row — so the two cannot
  ;; drift apart without this failing.
  (let [{:keys [row link2]} (both [[:app/main :a]] skip-only-window)]
    (testing "the advisor classifies observed activity, routed to a change of
              INSTRUMENT — never the empty-window `:cap`"
      (is (= [:memo-hits-only :host-opaque :host-opaque]
             [(get-in row [:class :observed]) (get-in row [:class :basis])
              (get-in row [:advice :refusal :reason])])))

    (testing "and the causal slice reports the skip on `:skipped`, never as a
              recompute, and the surveyed bundle is still evidenced"
      (is (= {:holds [] :skipped {:count 1 :sub-ids [:a]} :evidenced? true}
             (select-keys link2 [:holds :skipped :evidenced?]))))))

(deftest the-two-views-agree-about-every-sub-operation-in-one-bundle
  ;; The shared-predicate pin. One bundle carrying every `:op-type :rf.sub`
  ;; operation Spec 009 routes through the projection's `:subs` slot; the
  ;; advisor's recompute COUNT and the slice's recompute ROSTER must
  ;; describe the same set of events. They are two readings of one
  ;; predicate, so a divergence here is a second predicate.
  (let [windows {:app/main [(bundle 1 :e/tick
                                    [(sub-ev :a 2.0 :rf.sub/run)
                                     (sub-ev :b nil :rf.sub/create)
                                     (sub-ev :c nil :rf.sub/skip)
                                     (sub-ev :d nil :rf.sub/skip)
                                     (sub-ev :e nil :rf.sub/dispose)])]}
        {:keys [row link2]} (both [[:app/main :a] [:app/main :b] [:app/main :c]
                                   [:app/main :d] [:app/main :e]]
                                  windows)]
    ;; `:rf.sub/run` ALONE is work: a create is a REGISTRATION (Spec 009
    ;; §199, §241) and a dispose an eviction, so neither reaches the run
    ;; count, the roster or the memo hits.
    (is (= 1 (get-in row [:axes :frequency :runs])))
    (is (= [:a] (:holds link2)) "and the slice's roster names exactly that one")
    (is (= 2 (get-in row [:axes :frequency :memo-hits])))
    (is (= 2 (get-in link2 [:skipped :count]))
        "the memo hits agree too, on their own field in both views")))

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

  The untagged half is the half a fold testing `(nil? sid)` BEFORE
  `hh/sub-recompute?` would get wrong: identity loss would decide the
  classification and every untagged event would become a run. The two
  untagged non-work rows are therefore the rows this table exists for —
  such a fold would read an untagged `:rf.sub/skip` as one unnamed run
  against link 2's one memo hit, and an untagged `:rf.sub/dispose` as one
  unnamed run against link 2's nothing at all.

  The absolute expectation is written out per row rather than only
  comparing the two views, because two views wrong the same way agree
  perfectly. The untagged RUN row is the control that keeps the table from
  being satisfied by *everything untagged is zero* — it MUST count an
  unnamed run.

  **The untagged CREATE row is not a second such control**:
  `:rf.sub/create` is not in `hh/sub-recompute-operations`, so both create
  rows read all zeros like the dispose rows. The untagged RUN row is
  therefore the single untagged control, and it is load-bearing: deleting
  it would make this whole table satisfiable by a fold that counted
  nothing at all."
  [[:rf.sub/run     true  {:named-runs 1 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/run     false {:named-runs 0 :unnamed-runs 1 :named-skips 0 :unnamed-skips 0}]
   ;; A REGISTRATION IS NOT A RUN. Spec 009 §199 / §241: emitted at
   ;; registration time by `reg-sub` / `reg-runtime-sub` /
   ;; `reg-frame-state-sub`, explicitly NOT a first-reference signal — so
   ;; the body did not run, tagged or untagged, and identity loss changes
   ;; nothing about that. Zeros in both rows, exactly like an eviction.
   [:rf.sub/create  true  {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/create  false {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/skip    true  {:named-runs 0 :unnamed-runs 0 :named-skips 1 :unnamed-skips 0}]
   [:rf.sub/skip    false {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 1}]
   [:rf.sub/dispose true  {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]
   [:rf.sub/dispose false {:named-runs 0 :unnamed-runs 0 :named-skips 0 :unnamed-skips 0}]])

(deftest every-operation-reads-the-same-in-both-views-with-or-without-an-id
  ;; THE MATRIX. One event per cell, both public results off it, each held
  ;; to what the operation IS — an absolute expectation, because two views
  ;; drifting the same way would still agree with each other.
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
            "and link 2's roster must read the same event the same way")))))

(deftest an-untagged-NON-WORK-event-is-uncorrelated-observation-never-work
  ;; The matrix above proves the counts agree; this proves the advisor
  ;; states the right KIND of absence: an untagged skip is an uncorrelated
  ;; OBSERVATION with its own account, and no unnamed-RUN account at all.
  (let [t (advisor/sub-timing
            {:app/main [(bundle 1 :e/tick [(untagged-ev :rf.sub/skip)])]})]
    (is (= {:unnamed-loss nil :unnamed-skip-loss {:reason :uncorrelated :dropped 1}}
           (select-keys t [:unnamed-loss :unnamed-skip-loss])))))

;; ---------------------------------------------------------------------------
;; The three states stay apart — a skip-only window is neither of the others
;; ---------------------------------------------------------------------------

(deftest a-REGISTRATION-only-window-is-CAPPED-and-never-host-opaque
  ;; A `reg-sub` inside a handler scope emits a create into that dispatch's
  ;; bundle. Counted as a recompute it would land as an UNTIMED RUN and send
  ;; the reader to React DevTools over a registration.
  (let [reg-only (:class (:row (both [[:app/main :a]]
                                     {:app/main [(bundle 1 :e [(sub-ev :a nil :rf.sub/create)])]})))]
    (is (= :cap (:basis reg-only))
        "the honest answer is the FREE remedy: reproduce and read again")
    (is (= (:class (:row (both [[:app/main :a]] {:app/main []}))) reg-only)
        (str "a create contributes NOTHING, so a registration-only window reads "
             "exactly like one that retained nothing at all"))))

(deftest the-three-unattributed-states-are-pairwise-DISTINCT
  ;; `:cap`, `:host-opaque`-with-recomputes and `:host-opaque`-with-memo-hits
  ;; are three windows with three remedies; collapsing any two sends a
  ;; reader to the wrong place.
  (let [capped   (:class (:row (both [[:app/main :a]] {:app/main []})))
        searched (:class (:row (both [[:app/main :a]]
                                     {:app/main [(bundle 1 :e [(sub-ev :a 0.05)])]})))
        memo     (:class (:row (both [[:app/main :a]] skip-only-window)))
        all      [capped searched memo]]
    (is (= [[:unattributed :nothing] [:unattributed :recomputes] [:unattributed :memo-hits-only]]
           (mapv (juxt :owner :observed) all))
        "the OWNER is honestly unknown in all three; what was OBSERVED is the
         axis, and it is carried in data")
    (is (= 3 (count (into #{} (map :says) all)))
        "three states, three sentences")
    (is (= [true false false]
           (mapv #(string/includes? (:says %) "reproduce the interaction") all))
        (str "and exactly ONE of them sends the reader to reproduce the "
             "interaction — the one whose window really is empty"))
    (is (= 0 (count (filter #(string/includes? (:says %) "events-retained") all)))
        (str "and none sends the reader to the retention knob: a bigger "
             "buffer cannot fill an empty window"))))

;; ---------------------------------------------------------------------------
;; What holds independently of the skip rules
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
    (is (= {:holds :unknown :loss {:reason :uncorrelated :dropped 1}}
           (select-keys link2 [:holds :loss]))
        (str "work happened and joins to nothing — never `[]` — and the account "
             "is one UNNAMED RUN, not the untagged-skip count added to it"))
    (is (= 1 (get-in link2 [:skipped :count])))))

(deftest a-window-with-both-runs-and-skips-classifies-on-the-runs
  ;; The memo-only arm fires only when there is no recompute at all: a
  ;; boundary that both ran and skipped is a SEARCHED window.
  (let [{:keys [row]}
        (both [[:app/main :a]]
              {:app/main [(bundle 1 :e [(sub-ev :a 0.05)
                                        (sub-ev :a nil :rf.sub/skip)])]})]
    (is (= [:recomputes :host-opaque] ((juxt :observed :basis) (:class row))))))

(deftest an-oscillating-read-set-is-still-a-topology-finding-with-only-skips
  ;; Oscillation is a fact about the entry cache and holds independently
  ;; of the clock, so it outranks the memo-only arm.
  (let [{:keys [row]} (both [[:app/main :a]] skip-only-window :read-orders 4)]
    (is (= :read-topology (:owner (:class row))))
    (is (= :memo-hits-only (:observed (:class row)))
        "and it still records what the window actually held")))
