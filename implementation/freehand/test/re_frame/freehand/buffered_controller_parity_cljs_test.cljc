(ns re-frame.freehand.buffered-controller-parity-cljs-test
  "FH-CTRL-006 … FH-CTRL-010, applicability `common` — the RENDERED half.

  The laws in `buffered-controller-cljs-test` bind BOTH execution modes,
  and almost nothing about the fence could differ between them:
  `v/controller-revision` and `v/controller-current?` are ordinary
  functions, the second of them called from a `reg-sub` no view mode ever
  sees, and the record is ordinary frame data. What promotion COULD move
  is the SHAPE the pilot renders — so a JVM row over there already asserts
  that the compiled tier's own analyzer accepts the control's shape
  unchanged.

  This suite closes the remaining gap, which is the difference between
  ACCEPTED and EQUAL. It renders the promoted twin
  ([[re-frame.freehand.buffered-views-compiled/buffered-field]]) beside the
  interpreted control and compares what a buffered control actually
  produces: the value it displays, the three intents it carries
  (`:on-input`, `:on-blur`, `:on-click`), and the POSITION the generation
  occupies inside each of them — at the three states the fence
  distinguishes.

  ## The three states, and why those three

  - **idle** — the caller's baseline, no record. The generation the
    control renders is the caller's current one.
  - **drafting** — a live draft, stamped with the generation the render
    that produced it displayed.
  - **after a same-value rejection** — the caller refuses the draft and
    stands by the value it already had. Value-equality is provably blind
    to this (the two baselines are `=`); the generation is not. It is the
    case the whole design exists for, so it is the case parity has to
    cover.

  ## Only the CONTROL is promoted

  The pilot's caller reads `(v/sub [:invoice/amount id])` inside a `for`,
  which the compiled grammar refuses as `:rf.ui.compile/sub-in-loop` — a
  read inside a loop is not a finite lexical reactive site. Its catalogued
  recovery is `[:extract-declared-child :keep-interpreted]`, and the pilot
  already did the first half: the control IS the extracted declared child.
  So the compiled arm here is an INTERPRETED caller driving a COMPILED
  child, which is the composition the grammar is designed to produce. The
  last row asserts that refusal directly, so the asymmetry is evidence
  rather than an omission.

  ## Why the render goes through a capture

  Same reason as the laws themselves: `v/sub` is legal only during an
  active declared render, and the structural surface `t/render` walks a
  body but is not a host. [[render!]] composes the two — one candidate,
  one walk, and NO commit — so every assertion here is also a statement
  that rendering a buffered control in either mode publishes nothing."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.walk :as walk]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [re-frame.freehand.compiler.check :as check])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.buffered-controller-cljs-test :as pilot]
            [re-frame.freehand.buffered-views-compiled :as compiled]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The compiled arm's caller — the pilot's own, with ONE symbol changed
;; ---------------------------------------------------------------------------

(v/defview compiled-caller
  "`buffered-controller-cljs-test/invoice-form`, with one symbol changed:
  the control it names is the promoted twin. Everything else — the props,
  the `for`, the `:key`, the two reads — is the caller's own text.

  INTERPRETED, deliberately and permanently. Promoting it is what the
  compiled grammar refuses (`:rf.ui.compile/sub-in-loop`), and rewriting
  the caller to satisfy the grammar would change the pilot the interpreted
  laws render — which is the drift this whole arrangement exists to avoid."
  [{:keys [ids]}]
  [:form
   (for [id ids]
     [compiled/buffered-field {:key       id
                               :control   [:invoice id :amount]
                               :value     (v/sub [:invoice/amount id])
                               :reset-key (v/sub [:invoice/amount-revision id])
                               :on-commit [:invoice/amount-committed id]}])])

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ctrl-007 (conf/fixture :FH-CTRL-007))

(def ^:private fid :rf/default)

(def ^:private interpreted-caller-id
  :re-frame.freehand.buffered-controller-cljs-test/invoice-form)

(def ^:private interpreted-control-id
  :re-frame.freehand.buffered-controller-cljs-test/buffered-field)

(defn- seed!  [db] (frame/replace-app-db! fid db))
(defn- app-db []   (frame/frame-app-db-value fid))
(defn- send!  [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- record
  [address] (get-in (app-db) [pilot/records-root [pilot/kind address]]))

(defn- commits [] (get (app-db) :re-frame.freehand.buffered-controller-cljs-test/commits 0))

(defn- render!
  "One structural render of `form` under cell `cell-id`'s candidate — one
  walk, and NO commit. `v/sub` needs an active render; nothing is
  published, because a render owns nothing."
  [cell-id form]
  (let [cand (cell/candidate (cell/cell cell-id) fid)]
    (cell/with-capture cand (fn [] (t/render form)))))

(defn- send-intent!
  "Dispatch `event`, with `args` filling the projection positions a live
  payload would. With no args the intent is dispatched exactly as
  rendered."
  [event & args]
  (send! (if (seq args)
           (into (vec (butlast event)) args)
           event)))

(defn- generation-at
  "The POSITION `generation` occupies inside `event`, or nil. Asserted as
  its own fact because it is what makes a stale intent detectable: a
  handler reads the generation POSITIONALLY, so two intents that carried
  the same values in a different order would agree on every equality above
  and disagree about which argument is the fence."
  [event generation]
  (first (keep-indexed (fn [i x] (when (= generation x) i)) event)))

(defn- facts
  "Everything a buffered control's render publishes, for ONE rendered
  occurrence: what it displays, the three intents it carries, and where
  the generation sits inside each."
  [tree generation]
  (let [input  (t/find tree #(= :input (:tag %)))
        button (t/find tree #(= :button (:tag %)))
        ia     (t/attrs input)
        ba     (t/attrs button)]
    {:shown         (:value ia)
     :on-input      (:on-input ia)
     :on-blur       (:on-blur ia)
     :on-click      (:on-click ba)
     :generation-at {:on-input (generation-at (:on-input ia) generation)
                     :on-blur  (generation-at (:on-blur ia) generation)
                     :on-click (generation-at (:on-click ba) generation)}}))

(defn- arms
  "The two arms rendered at the CURRENT state, as comparable facts: the
  pilot's own interpreted caller driving its interpreted control, and a
  caller of the same shape driving the promoted twin."
  [ids generation]
  {:interpreted (facts (render! :my.ui/parity-interpreted
                                [pilot/invoice-form {:ids ids}])
                       generation)
   :compiled    (facts (render! :my.ui/parity-compiled
                                [compiled-caller {:ids ids}])
                       generation)})

(def ^:private id-substitutions
  "The interpreted arm's view ids, rewritten onto the compiled arm's. A
  view id names where a declaration LIVES, and living in another file is
  the only difference these two have."
  {interpreted-caller-id  ::compiled-caller
   interpreted-control-id :re-frame.freehand.buffered-views-compiled/buffered-field})

(defn- as-compiled-ids
  [tree] (walk/postwalk #(get id-substitutions % %) tree))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn pilot/register-library!}))

;; ===========================================================================
;; The parity claim, at the three states the fence distinguishes
;; ===========================================================================

(deftest the-promoted-control-renders-what-the-interpreted-one-renders
  (testing "Per FH-CTRL-006…010, applicability `common`: the promoted
            control displays the same value and carries the same three
            intents — with the generation at the same POSITION inside each
            — as the interpreted one, at idle, while drafting, and after
            the same-value rejection the whole design exists for. The state
            both arms read is one app-db, so what is compared is the
            RENDER and nothing else."
    (let [{:keys [control baseline revision typed displayed-while-editing
                  reasserted-value next-revision displayed-after-rejection]} ctrl-007
          id  (second control)
          ids [id]]
      (seed! {:invoice {id {:amount baseline :amount-revision revision}}})

      (testing "IDLE — the caller's baseline, no record"
        (let [{:keys [interpreted compiled]} (arms ids revision)]
          (is (= baseline (:shown interpreted))
              "non-vacuous: the interpreted arm really rendered the baseline")
          (is (= baseline (:shown compiled))
              "non-vacuous: so did the compiled one — neither arm rendered nothing")
          (is (= 4 (count (:on-input compiled)))
              "non-vacuous: the intents are real event vectors, not nil")
          (is (= interpreted compiled)
              "displayed value and all three intents agree")
          (is (= {:on-input 2 :on-blur 2 :on-click 2} (:generation-at compiled))
              "with the generation at the same position in every intent")))

      (testing "DRAFTING — and the draft is created by the COMPILED arm's
                OWN :on-input, so the promoted intent is proven live rather
                than merely equal-looking"
        (send-intent! (:on-input (:compiled (arms ids revision))) typed)
        (is (= {:reset-key revision :draft typed} (record control))
            "the compiled control's intent wrote the record, stamped with
             the generation its render displayed")
        (let [{:keys [interpreted compiled]} (arms ids revision)]
          (is (= displayed-while-editing (:shown compiled))
              "the compiled arm displays the draft")
          (is (not= baseline displayed-while-editing)
              "non-vacuous: the draft really did move the display off the baseline")
          (is (= interpreted compiled)
              "and the interpreted arm displays and carries exactly the same")
          (is (= {:on-input 2 :on-blur 2 :on-click 2} (:generation-at compiled)))))

      (testing "AFTER A SAME-VALUE REJECTION — the caller refuses the draft
                and stands by the value it already had"
        (let [before (:amount (get-in (app-db) [:invoice id]))]
          (send! [:invoice/baseline-replaced id reasserted-value next-revision])
          (is (= before reasserted-value)
              "non-vacuous: the caller really did reassert an EQUAL value")
          (is (= before (:amount (get-in (app-db) [:invoice id])))
              "so nothing derived from the value could have observed the rejection"))
        (let [{:keys [interpreted compiled]} (arms ids next-revision)]
          (is (= displayed-after-rejection (:shown compiled))
              "the compiled arm shows the caller's baseline again")
          (is (not= displayed-while-editing displayed-after-rejection)
              "non-vacuous: the display actually moved off the draft")
          (is (= interpreted compiled)
              "and the two arms agree about the value AND the three intents")
          (is (= next-revision (nth (:on-input compiled) 2))
              "non-vacuous: the re-rendered intent carries the ADVANCED generation")
          (is (= {:on-input 2 :on-blur 2 :on-click 2} (:generation-at compiled))
              "still at the same position in each")
          (is (= {:reset-key revision :draft typed} (record control))
              "while the superseded record is ineligible, not erased"))))))

(deftest the-promoted-control-denotes-the-same-whole-tree
  (testing "Stated as a WHOLE-TREE equality beside the sampled facts, so a
            change that moved something the facts do not sample — an
            attribute, a child, an element the control renders — could not
            pass quietly. The view-id namespace is the only mechanical
            substitution, and it is not a difference promotion makes: it is
            a difference LIVING IN ANOTHER FILE makes."
    (let [{:keys [control baseline revision typed]} ctrl-007
          id    (second control)
          ids   [id]
          both  (fn [] {:interpreted (render! :my.ui/parity-interpreted
                                              [pilot/invoice-form {:ids ids}])
                        :compiled    (render! :my.ui/parity-compiled
                                              [compiled-caller {:ids ids}])})
          same? (fn [label]
                  (let [{:keys [interpreted compiled]} (both)]
                    (is (= (as-compiled-ids interpreted) compiled)
                        (str label ": the whole tree, not a sampled fact"))
                    (is (not= interpreted compiled)
                        (str label ": non-vacuous — the two trees differ BEFORE the "
                             "id rewrite, so the comparison is doing work"))))]
      (seed! {:invoice {id {:amount baseline :amount-revision revision}}})
      (same? "idle")
      ;; The user drafts between the two passes, so the second comparison is
      ;; over a tree carrying a live draft rather than a repeat of the first.
      (send-intent! (:on-input (t/attrs (t/find (:compiled (both))
                                                #(= :input (:tag %)))))
                    typed)
      (same? "drafting"))))

;; ===========================================================================
;; The intents are LIVE — each of the three, fired from the compiled arm
;; ===========================================================================

(deftest the-promoted-controls-intents-move-the-same-state
  (testing "Equality of two rendered event vectors is only half the claim:
            the compiled arm's intents have to REACH the pilot's
            registrations. Each of the three is fired from the compiled
            arm's own render and the state it moves is asserted — the same
            state the interpreted laws assert about."
    (let [{:keys [control baseline revision typed]} ctrl-007
          id      (second control)
          ids     [id]
          start!  (fn [] (seed! {:invoice {id {:amount baseline :amount-revision revision}}}))
          compiled-input  (fn [] (t/attrs (t/find (render! :my.ui/parity-compiled
                                                           [compiled-caller {:ids ids}])
                                                  #(= :input (:tag %)))))
          compiled-button (fn [] (t/attrs (t/find (render! :my.ui/parity-compiled
                                                           [compiled-caller {:ids ids}])
                                                  #(= :button (:tag %)))))]

      (testing ":on-blur commits the draft the compiled arm's :on-input made"
        (start!)
        (send-intent! (:on-input (compiled-input)) typed)
        (is (= 0 (commits)) "non-vacuous: no caller event has fired yet")
        (send-intent! (:on-blur (compiled-input)))
        (is (= typed (get-in (app-db) [:invoice id :amount]))
            "the caller's domain event received the draft")
        (is (= 1 (commits)) "exactly one caller event")
        (is (nil? (record control)) "and the session was retired"))

      (testing ":on-click cancels it — the Revert affordance, from the
                compiled arm's own button"
        (start!)
        (send-intent! (:on-input (compiled-input)) typed)
        (is (some? (record control)) "non-vacuous: a live draft really existed")
        (send-intent! (:on-click (compiled-button)))
        (is (nil? (record control)) "cancel retired the session")
        (is (= baseline (get-in (app-db) [:invoice id :amount]))
            "and the caller's amount never moved")))))

;; ===========================================================================
;; Non-vacuity: the two arms really are two lowerings
;; ===========================================================================

(deftest the-two-arms-really-are-two-lowerings
  (testing "A parity proof where both sides are interpreted proves nothing,
            so the lowering each declaration reports is asserted before its
            output is trusted. The caller is interpreted on BOTH sides —
            that is deliberate, and the row below says why."
    (is (= :interpreted (:lowering (v/describe pilot/buffered-field)))
        "the pilot's control is declared interpreted")
    (is (= :compiled (:lowering (v/describe compiled/buffered-field)))
        "and the twin is declared compiled")
    (is (= :interpreted (:lowering (v/describe pilot/invoice-form))))
    (is (= :interpreted (:lowering (v/describe compiled-caller)))
        "both callers are interpreted — only the control was promoted"))

  (testing "and the compiled control carries the analysis that makes it
            statically knowable, which the interpreted twin honestly
            reports it has none of"
    (let [m (v/manifest compiled/buffered-field)]
      (is (some? m) "the promoted declaration carries a manifest")
      (is (nil? (v/manifest pilot/buffered-field))
          "and the interpreted one reports none")
      (is (= 1 (count (:subscriptions m)))
          "exactly the one read the body carries")
      (is (= '[:my.ui.field/text k g value] (:query (first (:subscriptions m))))
          "the AUTHORED query, local symbols and all")
      (is (= 3 (count (:events m)))
          "and the three event sites the parity rows compare"))))

;; ===========================================================================
;; Why the caller is NOT promoted — the refusal, asserted
;; ===========================================================================

#?(:clj
   (deftest the-caller-is-left-interpreted-because-the-grammar-refuses-it
     (testing "The asymmetry above is a RULING of the compiled grammar, not
               an omission. Pointed at the pilot's own file, so the
               declaration checked is the one every law renders — there is
               no copy to drift. The control is eligible with nothing to
               say; the caller is refused, because it reads inside a `for`
               and a read inside a loop is not a finite lexical reactive
               site. The refusal's own catalogued recovery is
               `[:extract-declared-child :keep-interpreted]`, and the pilot
               already did the first half — the control IS the extracted
               declared child — so an interpreted caller driving a compiled
               child is the composition the grammar prescribes.

               JVM-only because the checker resolves heads against a loaded
               namespace, which only the JVM has."
       (let [path    (.getPath (io/file (io/resource
                                          "re_frame/freehand/buffered_controller_cljs_test.cljc")))
             by-id   (into {} (map (juxt :view-id identity)) (check/check-file path))
             control (get by-id interpreted-control-id)
             caller  (get by-id interpreted-caller-id)]
         (is (<= 3 (count by-id))
             "non-vacuous: the checker really read this file's declarations")
         (is (true? (:compile-eligible? control))
             "the CONTROL's shape is inside the compiled grammar")
         (is (= [] (:findings control))
             "with nothing to change on the way — promotion is a keyword, not a rewrite")
         (is (false? (:compile-eligible? caller))
             "the CALLER is refused")
         (is (= [:rf.ui.compile/sub-in-loop] (mapv :id (:findings caller)))
             "for exactly one reason, and that reason is the loop")
         (is (= :reactive-site-is-not-finite (:reason (first (:findings caller))))
             "a read inside a loop is not a finite lexical site")
         (is (= [:extract-declared-child :keep-interpreted]
                (:recovery (first (:findings caller))))
             "and the recovery is what this suite does")))))
