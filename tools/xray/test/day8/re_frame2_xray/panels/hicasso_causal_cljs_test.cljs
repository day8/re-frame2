(ns day8.re-frame2-xray.panels.hicasso-causal-cljs-test
  "ONE causal slice on a REAL interaction, with every evidenced link
  mutation-tested (rf2-hic-037).

  ## What a mutation test is for here

  A causal display that renders `evidenced` from a seam it is not actually
  reading is worse than one that renders `unknown`, and on a healthy
  application the two are indistinguishable from the outside. So each of
  the four evidenced links has a row below that BREAKS its seam and
  asserts the link stops being evidenced — degrading to a stated loss,
  never to a confident wrong answer, and never borrowing a neighbouring
  seam to keep its colour.

  Every sabotage carries its positive control in the same row. A sabotage
  that reds a link which was never green proves nothing about the link;
  it proves the fixture was broken. So each row asserts the link IS
  evidenced first, on the same runtime, before breaking anything.

  ## The runtime is real

  Boundaries are mounted through the actual commit seam and the events are
  actual dispatches through the actual router, so the ring below holds
  bundles the framework put there. Two sabotages act on the SEAM'S OWN
  DATA rather than on the runtime — the `:rf.sub/id` tag, and the
  attribution envelope — because those are the seams, and a runtime that
  could be talked out of stamping a trace tag would be a different bead.
  The events they carry are otherwise the runtime's own.

  Normative owner: `tools/xray/spec/028-Hicasso-Advisor.md`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso :as hicasso]
            [day8.re-frame2-xray.panels.hicasso-advisor :as advisor]
            [day8.re-frame2-xray.panels.hicasso-causal :as causal]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]
            [day8.re-frame2-xray.panels.hicasso-reads :as reads]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private app-frame ::causal-app)

(rf/reg-sub :hcaus/left  (fn [db _] (:left db)))
(rf/reg-sub :hcaus/right (fn [db _] (:right db)))
(rf/reg-event :hcaus/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :hcaus/bump (fn [{:keys [db]} _] {:db (update db :left inc)}))

;; See `hicasso_cljs_test`'s fixture note: the UIx adapter is required
;; because Hicasso's cell wiring calls `add-watch` on the substrate's
;; derived value, and the `:once` restore is load-bearing because
;; `install-adapter!` is process-global.
(use-fixtures :once
  (fn [run-tests]
    (run-tests)
    ((xray-test-support/make-xray-runtime-fixture) (fn []))))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:adapter    uix-adapter/adapter
     :post-reset (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- testids [tree]
  (into #{}
        (keep (fn [node]
                (when (and (vector? node) (map? (second node)))
                  (:data-testid (second node)))))
        (hiccup-seq tree)))

(defn- text-of [tree]
  (->> (hiccup-seq tree) (filter string?) (string/join " ")))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:hcaus/seed {:left 1 :right 2}]))
  nil)

(defn- mount!
  "A real boundary, rendered and committed through the real commit seam."
  [body-fn]
  (collector/render-body app-frame body-fn {})
  (collector/commit-boundary! (collector/last-reads) (fn [])))

(defn- interact!
  "One real user interaction: a dispatch through the real router, which
  moves app-db, recomputes the reads that depend on it, and lands in the
  frame's own Spec 009 ring."
  []
  (rf/with-frame app-frame (rf/dispatch-sync [:hcaus/bump])))

(defn- evidence! []
  (reads/evidence))

(defn- windows! [envelopes]
  (or (reads/trace-windows envelopes) {}))

(defn- slice!
  "The slice for the FIRST mounted boundary, on the newest retained
  dispatch — exactly what the panel draws."
  ([] (slice! {}))
  ([{:keys [envelopes windows boundary-key]}]
   (let [e  (or envelopes (evidence!))
         w  (or windows (windows! e))
         bk (or boundary-key
                (get-in e [:mounted-boundaries :boundaries 0 :boundary :key]))]
     (causal/slice {:envelopes e :windows w :boundary-key bk}))))

(defn- link [s id]
  (first (filter #(= id (:id %)) (:links s))))

(defn- show!
  [view]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.hicasso/set-view view])
    (rf/clear-sub-cache! :rf/xray)
    (hicasso/Panel)))

;; ---------------------------------------------------------------------------
;; The positive control — the whole chain, on a real interaction
;; ---------------------------------------------------------------------------

(deftest the-chain-is-seven-links-and-only-its-prefix-is-evidenced
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        s       (slice!)]
    (is (= 7 (:total s)))
    (is (= [:event :subs-recomputed :values-changed :boundaries-notified
            :bodies-run :react-commit :paint]
           (mapv :id (:links s)))
        "Spec SN §10's chain, in its own order")

    (testing "links 1-4 are evidenced on a real interaction"
      (doseq [id [:event :subs-recomputed :values-changed :boundaries-notified]]
        (is (true? (:evidenced? (link s id)))
            (str id " must be evidenced on a healthy run — otherwise every "
                 "sabotage below proves only that the fixture is broken"))))

    (testing "links 5-7 are host-opaque, always, and each names its own authority"
      (doseq [id [:bodies-run :react-commit :paint]]
        (let [l (link s id)]
          (is (false? (:evidenced? l)))
          (is (= :host-opaque (:basis l)))
          (is (hh/unknown? (:holds l)))
          (is (string? (:authority l)))))
      (is (= 3 (count (into #{} (map #(:says (link s %)))
                            [:bodies-run :react-commit :paint])))
          (str "three different absences with three different authorities — a "
               "reader deciding what to open next needs to know which one "
               "they are missing")))

    (testing "the envelope never claims the chain from its prefix"
      (is (false? (:complete? s)))
      (is (= :uncorrelated (:reason (:loss s)))))
    (release)))

(deftest the-2-to-3-join-is-UNCORRELATED-even-when-both-links-are-solid
  ;; THE FINDING. A sub really recomputed and a cell's epoch really moved,
  ;; and nothing joins them: an epoch stamp carries no dispatch id and
  ;; Hicasso's commit seam records no cascade id. A chain of green links
  ;; with the join left implicit is exactly the adjacency-as-cause the
  ;; producer refuses one layer down.
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        s       (slice!)]
    (is (true? (:evidenced? (link s :subs-recomputed))))
    (is (true? (:evidenced? (link s :values-changed))))
    (is (= :uncorrelated (get-in (link s :values-changed) [:joins :status]))
        "two solid facts, joined by nothing")
    (is (= :evidenced (get-in (link s :subs-recomputed) [:joins :status]))
        (str "while the 1→2 join IS evidenced — the ring GROUPS by "
             "dispatch-id, so that join is the storage rather than an "
             "inference"))
    (testing "and the summary counts links and joins separately"
      (let [summary (causal/slice-summary s)]
        (is (string/includes? summary "links evidenced"))
        (is (string/includes? summary "joins evidenced")
            (str "a reader who conflates the two reads four green links as a "
                 "proven cause"))))
    (release)))

;; ---------------------------------------------------------------------------
;; MUTATION 1 — the event seam: Spec 009's retained ring
;; ---------------------------------------------------------------------------

(deftest breaking-the-ring-stops-the-event-link-being-evidenced
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        before  (slice!)]
    (is (true? (:evidenced? (link before :event)))
        "POSITIVE CONTROL — the link is green before the sabotage")
    (is (not (hh/unknown? (get-in (link before :event) [:holds :event-id]))))

    ;; SABOTAGE: release the ring. The dispatch happened; the seam that
    ;; carried it no longer holds it.
    (trace-tooling/clear-trace-buffer! app-frame)
    (let [after (slice!)
          l     (link after :event)]
      (is (false? (:evidenced? l)) "the link must stop being evidenced")
      (is (= :cap (:basis l)))
      (is (hh/unknown? (:holds l))
          "and must not fall back to an event id from anywhere else")
      (is (string/includes? (:says l) "capped window")
          "a capped window, never a dispatch that did not happen")

      (testing "and link 2 degrades with it rather than inventing a roster"
        (is (hh/unknown? (:holds (link after :subs-recomputed))))
        (is (= :cap (get-in (link after :subs-recomputed) [:joins :status])))))
    (release)))

;; ---------------------------------------------------------------------------
;; MUTATION 2 — the sub-recompute seam: the `:rf.sub/id` tag
;; ---------------------------------------------------------------------------

(defn- strip-sub-ids
  "The runtime's OWN window with one seam broken: every sub event keeps
  its operation, its duration and its dispatch, and loses the tag that
  says which subscription it was."
  [windows]
  (into {}
        (map (fn [[fid bundles]]
               [fid (mapv (fn [b]
                            (update b :subs
                                    (fn [evs] (mapv #(update % :tags dissoc :rf.sub/id) evs))))
                          bundles)]))
        windows))

(deftest an-untagged-recompute-is-UNKNOWN-and-never-an-empty-roster
  (setup!)
  (let [release   (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _         (interact!)
        envelopes (evidence!)
        windows   (windows! envelopes)
        before    (slice! {:envelopes envelopes :windows windows})]
    (is (true? (:evidenced? (link before :subs-recomputed)))
        "POSITIVE CONTROL")
    (is (seq (:holds (link before :subs-recomputed)))
        "and it really named some subscriptions")

    ;; SABOTAGE: the runtime's own events, minus the one tag that joins a
    ;; run to a subscription.
    (let [after (slice! {:envelopes envelopes :windows (strip-sub-ids windows)})
          l     (link after :subs-recomputed)]
      (is (false? (:evidenced? l)))
      (is (hh/unknown? (:holds l))
          (str "UNKNOWN, never `[]` — work happened and joins to nothing, and "
               "an empty roster would say this dispatch recomputed nothing, "
               "which is the one substitution the evidence schema exists to "
               "refuse"))
      (is (= :uncorrelated (:reason (:loss l))))
      (is (pos? (:dropped (:loss l))) "with a count of what could not be named")

      (testing "and the runs are still reported as having happened"
        (is (string/includes? (:says l) "join to no subscription"))))
    (release)))

;; ---------------------------------------------------------------------------
;; MUTATION 3 — the value-change seam: the cells' epoch stamps
;; ---------------------------------------------------------------------------

(deftest a-boundary-with-no-epoch-cannot-have-values-that-changed
  (setup!)
  (let [reading (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        silent  (mount! (fn [_] nil))
        _       (interact!)
        e       (evidence!)
        w       (windows! e)
        keys*   (mapv #(get-in % [:boundary :key]) (get-in e [:mounted-boundaries :boundaries]))
        reading-key (first (filter seq keys*))
        empty-key   (first (filter empty? keys*))]
    (is (some? reading-key) "the reading boundary is in the census")
    (is (some? empty-key) "and so is the read-free one — it claims an entry too")

    (testing "POSITIVE CONTROL — the reading boundary's values-changed is evidenced"
      (let [l (link (slice! {:envelopes e :windows w :boundary-key reading-key})
                    :values-changed)]
        (is (true? (:evidenced? l)))
        (is (number? (get-in l [:holds :peak-epoch])))))

    (testing "the read-free boundary's is not, and says why in ITS OWN terms"
      (let [s (slice! {:envelopes e :windows w :boundary-key empty-key})
            l (link s :values-changed)]
        (is (false? (:evidenced? l)))
        (is (hh/unknown? (:holds l)))
        (is (string/includes? (:says l) "not a gap in the instrument")
            (str "a boundary that holds no read is a FACT about the boundary, "
                 "and reporting it as a missing instrument would send the "
                 "reader looking for a knob"))

        (testing "and link 4 follows it down rather than naming readers anyway"
          (is (false? (:evidenced? (link s :boundaries-notified))))
          (is (hh/unknown? (:holds (link s :boundaries-notified)))))))
    (reading)
    (silent)))

;; ---------------------------------------------------------------------------
;; MUTATION 4 — the notify seam: the cells' reader arrays
;; ---------------------------------------------------------------------------

(deftest without-the-reverse-edge-the-notified-set-is-UNKNOWN-not-borrowed
  ;; The dangerous substitution. The mounted census carries each
  ;; boundary's own reads, so a plausible-looking notified set could be
  ;; assembled from it — and it would be the FORWARD edge, printed under
  ;; the reverse edge's name.
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        e       (evidence!)
        w       (windows! e)
        before  (slice! {:envelopes e :windows w})]
    (is (true? (:evidenced? (link before :boundaries-notified)))
        "POSITIVE CONTROL")
    (is (seq (get-in (link before :boundaries-notified) [:holds :readers])))
    (is (= :derivation (:basis (link before :boundaries-notified)))
        (str "derived rather than observed even when green — the notify CALL "
             "is not recorded, and the array is read now rather than at "
             "commit time"))

    ;; SABOTAGE: the reverse-edge table is not readable.
    (let [after (slice! {:envelopes (assoc e :read-attribution nil) :windows w})
          l     (link after :boundaries-notified)]
      (is (false? (:evidenced? l)))
      (is (hh/unknown? (:holds l)))
      (is (string/includes? (:says l) "mounted census is NOT substituted"))

      (testing "and the census it could have borrowed from is still right there"
        (is (seq (get-in e [:mounted-boundaries :boundaries]))
            (str "the substitution was AVAILABLE and was not taken — which is "
                 "what makes this row a finding rather than an accident"))))
    (release)))

;; ---------------------------------------------------------------------------
;; The loss states reach the SCREEN
;; ---------------------------------------------------------------------------

(deftest every-unevidenced-link-renders-a-distinguishable-loss-on-the-page
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        tree    (show! :causal)
        ids     (testids tree)]
    (is (contains? ids "rf-xray-hicasso-causal"))
    (doseq [id ["event" "subs-recomputed" "values-changed" "boundaries-notified"
                "bodies-run" "react-commit" "paint"]]
      (is (contains? ids (str "rf-xray-hicasso-causal-link-" id))
          (str "link " id " must render")))

    (testing "the three host-opaque links carry the host-opaque chip, by testid"
      (doseq [id ["bodies-run" "react-commit" "paint"]]
        (is (contains? ids (str "rf-xray-hicasso-causal-link-" id "-loss-host-opaque"))
            (str id " must render its loss under a testid a browser assertion "
                 "can select — `…-loss-host-opaque` can never match "
                 "`…-loss-cap`"))))

    (testing "the join is its own row, never folded into the link's own basis"
      (is (contains? ids "rf-xray-hicasso-causal-link-values-changed-join"))
      (is (string/includes? (text-of tree) "CANNOT be joined")))

    (testing "and a capped ring renders the cap chip instead"
      (trace-tooling/clear-trace-buffer! app-frame)
      (let [ids' (testids (show! :causal))]
        (is (contains? ids' "rf-xray-hicasso-causal-link-event-loss-cap"))
        (is (not (contains? ids' "rf-xray-hicasso-causal-link-event-loss-host-opaque"))
            "two genuinely different window states, two different testids")))
    (release)))

;; ---------------------------------------------------------------------------
;; The advisor, on the same running application
;; ---------------------------------------------------------------------------

(deftest the-advisor-answers-on-a-running-app-and-still-refuses-the-native-ladder
  ;; The end-to-end of the refusal. A real boundary, a real interaction, a
  ;; real ring — and the advice is still not `extract this to native`,
  ;; because nothing on this host measured lowering, React or layout.
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) (h/sub [:hcaus/right]) nil))
        _       (interact!)
        _       (interact!)
        e       (evidence!)
        adv     (advisor/advise e (advisor/sub-timing (windows! e)))
        row     (first (:rows adv))]
    (is (seq (:rows adv)) "the advisor ranked the running census")
    (is (= 1 (:rank row)))
    (is (pos? (get-in row [:axes :read-churn :reads])) "with its reads priced")
    (is (false? (get-in row [:advice :native?]))
        "no native route from this evidence, on a real application")
    (is (contains? #{:computation :read-topology :unattributed}
                   (get-in row [:class :owner])))

    (testing "and the tab renders it, refusal and working loop included"
      (let [tree (show! :advisor)
            ids  (testids tree)]
        (is (contains? ids "rf-xray-hicasso-advisor"))
        (is (contains? ids (str "rf-xray-hicasso-advice-" (:slug row))))
        (is (contains? ids (str "rf-xray-hicasso-advice-" (:slug row) "-class")))
        (is (contains? ids (str "rf-xray-hicasso-advice-" (:slug row) "-route")))
        (is (contains? ids "rf-xray-hicasso-advisor-unmeasured"))
        (doseq [c ["lowering" "react" "layout"]]
          (is (contains? ids (str "rf-xray-hicasso-advisor-unmeasured-" c))
              (str c " must be named on the page as unmeasured — the reason "
                   "the top row is not a verdict")))
        (is (string/includes? (text-of tree) "React DevTools"))))
    (release)))

(deftest the-advisor-and-the-slice-are-taken-in-ONE-turn
  ;; The two views are derivations of the same one-turn read. A slice
  ;; drawn from a second turn could describe a boundary the ranking no
  ;; longer holds.
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:hcaus/left]) nil))
        _       (interact!)
        data    (rf/with-frame :rf/xray
                  (rf/clear-sub-cache! :rf/xray)
                  @(rf/subscribe [:rf.xray.hicasso/data]))
        top     (first (get-in data [:advice :rows]))]
    (is (some? top))
    (is (= (:key (:boundary top)) (get-in data [:slice :scope :boundary]))
        "the slice is about the boundary the advisor ranked first")
    (release)))
