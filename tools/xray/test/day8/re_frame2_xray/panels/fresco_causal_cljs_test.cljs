(ns day8.re-frame2-xray.panels.fresco-causal-cljs-test
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

  Normative owner: `tools/xray/spec/028-Fresco-Advisor.md`."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.fresco :as fresco]
            [day8.re-frame2-xray.panels.fresco-advisor :as advisor]
            [day8.re-frame2-xray.panels.fresco-causal :as causal]
            [day8.re-frame2-xray.panels.fresco-helpers :as hh]
            [day8.re-frame2-xray.panels.fresco-reads :as reads]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            ;; rf2-k97c.3 / rf2-a38l — the codec's own hiccup→element door,
            ;; so a key is graded at the renderer that actually ships it
            ;; rather than at the hiccup a reader hopes it means.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [reagent.core :as r]))

(def ^:private app-frame ::causal-app)

(rf/reg-sub :hcaus/left  (fn [db _] (:left db)))
(rf/reg-sub :hcaus/right (fn [db _] (:right db)))
;; PARAMETERIZED, which is the whole point of it: `[:hcaus/item 1]` and
;; `[:hcaus/item 2]` are two CELLS of ONE registration, and the shape every
;; per-cell claim below needs. The cell table keys on the raw sub-key, so
;; the runtime really builds two — the rows assert that rather than assume
;; it, because a fixture that built one cell would satisfy a per-cell
;; assertion vacuously.
(rf/reg-sub :hcaus/item  (fn [db [_ n]] (get-in db [:items n])))
(rf/reg-event :hcaus/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :hcaus/bump (fn [{:keys [db]} _] {:db (update db :left inc)}))
(rf/reg-event :hcaus/bump-item
  (fn [{:keys [db]} [_ n]] {:db (update-in db [:items n] inc)}))

;; See `fresco_cljs_test`'s fixture note: the UIx adapter is required
;; because Fresco's cell wiring calls `add-watch` on the substrate's
;; derived value, and the `:once` restore is load-bearing because
;; `install-adapter!` is process-global.
(use-fixtures :once
  (fn [run-tests]
    (run-tests)
    ((xray-test-support/make-xray-runtime-fixture) (fn []))))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:adapter    rf.adapter.uix/adapter
     :post-reset (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

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
    (rf/dispatch-sync [:hcaus/seed {:left 1 :right 2 :items {1 10 2 20}}]))
  nil)

(defn- mount!
  "A real boundary, rendered and committed through the real commit seam."
  [body-fn]
  (rf.fresco.impl.collector/render-body app-frame body-fn {})
  (rf.fresco.impl.collector/commit-boundary! (rf.fresco.impl.collector/last-reads) (fn [])))

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
  "The slice for the FIRST mounted boundary, on the dispatch the spine is
  focused on — exactly what the panel draws. With no `:focus` that is the
  newest retained dispatch, as it was before rf2-y8doi.26."
  ([] (slice! {}))
  ([{:keys [envelopes windows boundary-key focus]}]
   (let [e  (or envelopes (evidence!))
         w  (or windows (windows! e))
         bk (or boundary-key
                (get-in e [:mounted-boundaries :boundaries 0 :boundary :key]))]
     (causal/slice {:envelopes e :windows w :boundary-key bk :focus focus}))))

(defn- retained-dispatch-ids
  "Every numeric `:dispatch-id` the windows hold, ascending and distinct.
  Non-numeric ids are the producer's unjoinable sentinel and cannot
  anchor a slice, so `newest-dispatch` skips them and so does this."
  [windows]
  (vec (sort (distinct (filter number? (map :dispatch-id (mapcat val windows)))))))

(defn- boundary-keys
  [envelopes]
  (mapv #(get-in % [:boundary :key])
        (get-in envelopes [:mounted-boundaries :boundaries])))

(defn- key-reading
  "The mounted boundary key whose SOLE read is the cell `query` names.

  A boundary key is a vector of `[frame-id sub-id projected-query]`
  triples, so this is an exact match on a one-element key — which is what
  makes the per-cell rows below about a boundary that genuinely reads one
  cell and nothing else."
  [keys* query]
  (first (filter #(and (= 1 (count %)) (= query (nth (first %) 2))) keys*)))

(defn- link [s id]
  (first (filter #(= id (:id %)) (:links s))))

(defn- show!
  "rf2-k97c.3 — `Panel` is an `rf.fresco/defview` now, so it is a React
  component rather than a callable answering hiccup. `panel-tree` is the
  projection the boundary calls; these rows drive it with the values
  taken from the two subs the boundary reads."
  [view]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.fresco/set-view view])
    (rf/clear-sub-cache! :rf/xray)
    (fresco/panel-tree
      {:selected @(rf/subscribe [:rf.xray.fresco/view])
       :data     @(rf/subscribe [:rf.xray.fresco/data])
       :frame    (rf/current-frame-id)})))

;; ---------------------------------------------------------------------------
;; The positive control — the whole chain, on a real interaction
;; ---------------------------------------------------------------------------

(deftest the-chain-is-seven-links-and-only-its-prefix-is-evidenced
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
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
  ;; Fresco's commit seam records no cascade id. A chain of green links
  ;; with the join left implicit is exactly the adjacency-as-cause the
  ;; producer refuses one layer down.
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
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
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
        _       (interact!)
        before  (slice!)]
    (is (true? (:evidenced? (link before :event)))
        "POSITIVE CONTROL — the link is green before the sabotage")
    (is (not (hh/unknown? (get-in (link before :event) [:holds :event-id]))))

    ;; SABOTAGE: release the ring. The dispatch happened; the seam that
    ;; carried it no longer holds it.
    (rf.trace.tooling/clear-trace-buffer! app-frame)
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
  (let [release   (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
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
  (let [reading (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
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
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
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
;; LINK 4 IS KEYED ON THE CELL (rf2-y8doi.26)
;; ---------------------------------------------------------------------------

(deftest one-cells-readers-are-notified-and-never-its-SIBLING-CELLS-readers
  ;; Link 4 keyed its reverse-edge lookup on `[frame-id sub-id]`, so one
  ;; moved cell matched EVERY parameterization of its registration and the
  ;; link named all of their readers. On a list of `[:todo/by-id n]` rows
  ;; that is every row on the page reported as notified by a commit that
  ;; touched one of them — and `docs/core/fresco/16-diagnostics.md` teaches
  ;; a reader to hunt exactly that signature, so the defect FABRICATED the
  ;; evidence rather than merely inflating a count.
  ;;
  ;; Two boundaries, one registration, two cells. Boundary A reads cell 1
  ;; and nothing else, so its `:latest-reads` names cell 1 and can name
  ;; nothing else — which is what makes this a claim about the KEY rather
  ;; than about which cell happened to move.
  (setup!)
  (let [a     (mount! (fn [_] (rf.fresco/sub [:hcaus/item 1]) nil))
        b     (mount! (fn [_] (rf.fresco/sub [:hcaus/item 2]) nil))
        _     (interact!)
        e     (evidence!)
        w     (windows! e)
        keys* (boundary-keys e)
        key-1 (key-reading keys* [:hcaus/item 1])
        key-2 (key-reading keys* [:hcaus/item 2])
        cells (filterv #(= :hcaus/item (:sub-id %))
                       (get-in e [:read-attribution :edges]))]

    (testing "NON-VACUITY — the runtime really built TWO cells of ONE registration"
      (is (= 2 (count cells))
          (str "one registration, two queries, two cells in the table. With "
               "one cell the per-cell assertion below would pass on the "
               "defective key too, which is the whole failure mode of the "
               "fixture this row replaces"))
      (is (= #{:hcaus/item} (into #{} (map :sub-id) cells))
          "and both really are the SAME registration")
      (is (some? key-1) "boundary A is in the census, reading cell 1 alone")
      (is (some? key-2) "boundary B is in the census, reading cell 2 alone")
      (is (not= key-1 key-2)
          (str "and the two boundaries are distinct — if the egress policy "
               "had elided the argument both would project to one key and "
               "there would be nothing here to keep apart"))
      (is (= 2 (count (into #{} (map :query) cells)))
          "the two cells carry two distinct projected queries"))

    (let [l (link (slice! {:envelopes e :windows w :boundary-key key-1})
                  :boundaries-notified)]
      (is (true? (:evidenced? l))
          "POSITIVE CONTROL — the link is green, so the counts below are a real set")
      (is (= [[:hcaus/item 1]] (mapv :query (:cells (:holds l))))
          (str "ONE cell matched: the one this boundary's moved read names. "
               "Both cells would be the registration-keyed answer"))
      (is (= [key-1] (mapv :key (:readers (:holds l))))
          (str "so boundary B — which reads a cell this commit did not move "
               "— is NOT reported as notified. It was the reverse edge's "
               "answer under the old key, printed under the name of the one "
               "link that says who re-runs because of this"))
      (is (= [:frame-id :sub-id :query] (get-in l [:joins :on]))
          "and the link states the CELL as what it joined on")
      (is (= :evidenced (get-in l [:joins :status]))))

    (testing "and boundary B's own slice names B, symmetrically"
      (let [l (link (slice! {:envelopes e :windows w :boundary-key key-2})
                    :boundaries-notified)]
        (is (= [key-2] (mapv :key (:readers (:holds l))))
            (str "the mirror row: an implementation that simply narrowed to "
                 "the FIRST matching cell would pass the row above and fail "
                 "this one"))))
    (a)
    (b)))

;; ---------------------------------------------------------------------------
;; THE WALKED DISPATCH IS THE SPINE'S (Spec 018 §6, rf2-y8doi.26)
;; ---------------------------------------------------------------------------

(deftest the-walked-dispatch-is-the-SPINE-S-focus-when-the-ring-holds-it
  ;; Spec 018 §6's atomicity contract: *No panel maintains its own
  ;; selection state; no panel reads `(peek history)`.* `newest-dispatch`
  ;; is `(peek ring)` under another name, so the ONE view whose job is
  ;; "one dispatch, walked" re-pointed itself on every application
  ;; dispatch while the reader was reading it.
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
        _       (interact!)
        _       (interact!)
        e       (evidence!)
        w       (windows! e)
        ids     (retained-dispatch-ids w)
        oldest  (first ids)
        newest  (last ids)]
    (testing "NON-VACUITY — the ring holds more than one dispatch to choose between"
      (is (<= 2 (count ids))
          (str "with one retained dispatch every branch below answers the "
               "same id and the test would pass without discriminating"))
      (is (not= oldest newest)))

    (is (= newest (get-in (slice! {:envelopes e :windows w}) [:scope :dispatch-id]))
        "with no focus, the newest retained dispatch — unchanged behaviour")

    (is (= oldest (get-in (slice! {:envelopes e :windows w :focus {:dispatch-id oldest}})
                          [:scope :dispatch-id]))
        (str "with the spine pinned to the older dispatch, THAT is the one "
             "walked — the reader clicked it and this is the view whose "
             "whole subject is one dispatch"))

    (testing "and a focus the ring has EVICTED falls back rather than drawing an all-capped slice"
      (let [evicted (inc (reduce max ids))
            s       (slice! {:envelopes e :windows w :focus {:dispatch-id evicted}})]
        (is (= newest (get-in s [:scope :dispatch-id])))
        (is (true? (:evidenced? (link s :event)))
            (str "a focus is a selection, not a claim about the window — "
                 "honouring a pin the ring cannot serve would show seven "
                 "capped links and blame the instrument"))))

    (testing "and `walked-dispatch` says the same thing on its own"
      (is (= newest (causal/walked-dispatch w nil)))
      (is (= newest (causal/walked-dispatch w {})))
      (is (= oldest (causal/walked-dispatch w {:dispatch-id oldest :mode :retro}))))
    (release)))

;; ---------------------------------------------------------------------------
;; LINK 3 STATES ITS OVERLAP WITH LINK 2 — AND STILL REFUSES THE JOIN
;; ---------------------------------------------------------------------------

(deftest link-3-counts-its-overlap-with-link-2-without-claiming-a-join
  ;; Two solid adjacent facts with nothing linking them is this
  ;; namespace's finding, and it stays the finding. What was missing is
  ;; any statement of how the two rosters RELATE — the reader had two
  ;; lists and no way to tell "these coincide exactly" from "these share
  ;; nothing", which are very different situations.
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
        _       (interact!)
        s       (slice!)
        l3      (link s :values-changed)]
    (is (true? (:evidenced? l3)) "POSITIVE CONTROL")
    (is (string/includes? (:says l3) "an OVERLAP and not a join")
        (str "the sentence has to disown the join in its own words — a bare "
             "count beside two rosters reads as the link the 2→3 join "
             "explicitly does not have"))
    (is (string/includes? (:says l3) "CELLS")
        (str "and name the grain mismatch: link 2 names registrations "
             "because Spec 009's ring tags `:rf.sub/id` and no query, so a "
             "read whose registration ran need not be the cell that ran"))

    (testing "and the join itself is untouched — still uncorrelated, still its own row"
      (is (= :uncorrelated (get-in l3 [:joins :status])))
      (is (string/includes? (get-in l3 [:joins :says]) "CANNOT be joined")))

    (testing "the count is a MEASUREMENT — it moves when the rosters disagree"
      (let [e       (evidence!)
            w       (windows! e)
            ;; The runtime's own window with link 2's roster renamed to a
            ;; registration this boundary does not read. Link 3's moved
            ;; reads are untouched, so only the overlap may move.
            renamed (into {}
                          (map (fn [[fid bundles]]
                                 [fid (mapv (fn [b]
                                              (update b :subs
                                                      (fn [evs]
                                                        (mapv #(assoc-in % [:tags :rf.sub/id]
                                                                         :entirely/unrelated)
                                                              evs))))
                                            bundles)]))
                          w)
            hit       (slice! {:envelopes e :windows w})
            says-hit  (:says (link hit :values-changed))
            says-miss (:says (link (slice! {:envelopes e :windows renamed}) :values-changed))]
        (is (some #{:hcaus/left} (:holds (link hit :subs-recomputed)))
            (str "NON-VACUITY: link 2 really names this boundary's own "
                 "registration on the unmodified window, so the two sentences "
                 "below are 1-of-1 against 0-of-1 and not 0 against 0"))
        (is (not= says-hit says-miss)
            (str "a count that reads the same whether or not the two rosters "
                 "share anything is not a measurement — and this is the "
                 "exact shape of assertion that would have caught it"))))
    (release)))

;; ---------------------------------------------------------------------------
;; The loss states reach the SCREEN
;; ---------------------------------------------------------------------------

(deftest every-unevidenced-link-renders-a-distinguishable-loss-on-the-page
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
        _       (interact!)
        tree    (show! :causal)
        ids     (testids tree)]
    (is (contains? ids "rf-xray-fresco-causal"))
    (doseq [id ["event" "subs-recomputed" "values-changed" "boundaries-notified"
                "bodies-run" "react-commit" "paint"]]
      (is (contains? ids (str "rf-xray-fresco-causal-link-" id))
          (str "link " id " must render")))

    (testing "the three host-opaque links carry the host-opaque chip, by testid"
      (doseq [id ["bodies-run" "react-commit" "paint"]]
        (is (contains? ids (str "rf-xray-fresco-causal-link-" id "-loss-host-opaque"))
            (str id " must render its loss under a testid a browser assertion "
                 "can select — `…-loss-host-opaque` can never match "
                 "`…-loss-cap`"))))

    (testing "the join is its own row, never folded into the link's own basis"
      (is (contains? ids "rf-xray-fresco-causal-link-values-changed-join"))
      (is (string/includes? (text-of tree) "CANNOT be joined")))

    (testing "and a capped ring renders the cap chip instead"
      (rf.trace.tooling/clear-trace-buffer! app-frame)
      (let [ids' (testids (show! :causal))]
        (is (contains? ids' "rf-xray-fresco-causal-link-event-loss-cap"))
        (is (not (contains? ids' "rf-xray-fresco-causal-link-event-loss-host-opaque"))
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
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) (rf.fresco/sub [:hcaus/right]) nil))
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
        (is (contains? ids "rf-xray-fresco-advisor"))
        (is (contains? ids (str "rf-xray-fresco-advice-" (:slug row))))
        (is (contains? ids (str "rf-xray-fresco-advice-" (:slug row) "-class")))
        (is (contains? ids (str "rf-xray-fresco-advice-" (:slug row) "-route")))
        (is (contains? ids "rf-xray-fresco-advisor-unmeasured"))
        (doseq [c ["lowering" "react" "layout"]]
          (is (contains? ids (str "rf-xray-fresco-advisor-unmeasured-" c))
              (str c " must be named on the page as unmeasured — the reason "
                   "the top row is not a verdict")))
        (is (string/includes? (text-of tree) "React DevTools"))))
    (release)))

(deftest the-advisor-and-the-slice-are-taken-in-ONE-turn
  ;; The two views are derivations of the same one-turn read. A slice
  ;; drawn from a second turn could describe a boundary the ranking no
  ;; longer holds.
  (setup!)
  (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
        _       (interact!)
        data    (rf/with-frame :rf/xray
                  (rf/clear-sub-cache! :rf/xray)
                  @(rf/subscribe [:rf.xray.fresco/data]))
        top     (first (get-in data [:advice :rows]))]
    (is (some? top))
    (is (= (:key (:boundary top)) (get-in data [:slice :scope :boundary]))
        "the slice is about the boundary the advisor ranked first")
    (release)))

;; ---------------------------------------------------------------------------
;; THE KEYS REACH REACT (rf2-k97c.3 RULING 2, regraded at the renderer)
;; ---------------------------------------------------------------------------
;;
;; The key sweep found ZERO `^{:key …}` / `with-meta` sites in this panel —
;; rf2-a38l had already converted both to KEYED FRAGMENTS, and rf2-k97c.3
;; moved them one step further: the two row fns are CALLS now, not hiccup
;; heads (a plain fn in head position is HD-016 `:rf.error/fresco-bad-head`),
;; so each returned `:li` has its own props map and carries its own key.
;;
;; That move is exactly the kind a hiccup-level assertion cannot grade, so
;; these rows read the key off a real ELEMENT from BOTH doors.
;;
;; HEAD + ATTRS, NOT THE WHOLE NODE. The codec lowers children EAGERLY, so
;; calling it on a whole `:li` walks a subtree this file has no business
;; grading. `(subvec node 0 2)` is the tree's existing idiom for this
;; (`machine_inspector_view_cljs_test`), and both doors read a key from
;; exactly the same place — the attribute map at index 1 — so dropping the
;; children changes nothing about the answer. Both sides are subvec'd here
;; because there is no metadata in play: with a meta-vs-attrs key the
;; Reagent door would have to see the WHOLE node, since `subvec` drops
;; vector metadata and both sides would read nil for different reasons.

(defn- reagent-key [node] (.-key (r/as-element (subvec node 0 2))))
(defn- fresco-key  [node] (.-key (rf.fresco.impl.codec/as-element (subvec node 0 2))))

(defn- li-nodes
  "Every `:li` in the rendered tree, in order."
  [tree]
  (into [] (filter #(and (vector? %) (= :li (first %)))) (hiccup-seq tree)))

(deftest advisor-and-causal-rows-reach-react-with-a-key-on-both-doors
  (testing "rf2-k97c.3 — every ranked-boundary row and every causal link
            commits a non-nil React key through FRESCO's codec as well as
            through Reagent. Fresco's codec reads `(:key props)` and vector
            metadata NOWHERE, so a key that regressed to reader meta would
            read nil on the Fresco door here while Reagent — which honours
            both — went on answering, which is precisely why the pair is
            graded rather than one side."
    (setup!)
    (let [release (mount! (fn [_] (rf.fresco/sub [:hcaus/left]) nil))
          _       (interact!)]
      (doseq [[view label] [[:advisor "advisor"] [:causal "causal"]]]
        (testing label
          (let [rows (li-nodes (show! view))]
            (is (seq rows)
                (str "NON-VACUITY: the " label " view rendered at least one "
                     "<li>, so the key assertions below are about real rows "
                     "rather than an empty roster"))
            (doseq [node rows]
              (is (some? (fresco-key node))
                  (str label " row reached React with a key through FRESCO's "
                       "codec. node head+attrs: " (pr-str (subvec node 0 2))))
              (is (= (reagent-key node) (fresco-key node))
                  (str "and BOTH doors agree on it — an attrs-map key is read "
                       "identically by each, which a metadata key is not. node "
                       "head+attrs: " (pr-str (subvec node 0 2))))))))
      (release))))
