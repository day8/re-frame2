(ns day8.re-frame2-xray.panels.hicasso-helpers-cljs-test
  "The Hicasso tab's pure algebra (rf2-hic-023).

  Two properties carry this suite, and both are about the same thing —
  whether the producer's honesty survives the trip to the screen.

  1. **The five absences are pairwise distinct**, in the word a chip shows
     AND in the testid it renders under. A schema that refuses to encode
     unknown as an empty collection buys nothing if the panel then draws
     `capped` and `uncorrelated` identically, and \"distinct\" is a
     property a suite can check where \"we were careful\" is not.
  2. **The empties are pairwise distinct.** *Not running Hicasso* and *a
     schema this build cannot parse* have unrelated remedies, and a reader
     who cannot tell them apart is back where the schema found them. The
     third empty — *the roster came back empty* — is not one fact but
     four, one per view, and the audit of #7789 found the mounted census's
     verdict being read out under Intents where it was false. Each view
     therefore answers for its own scope.

  Everything else here is the row projections, which are ordinary."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — envelopes shaped exactly as the producer emits them
;; ---------------------------------------------------------------------------

;; Keys are the PROJECTED shape the producer exports —
;; `[frame-id sub-id projected-query]`, never a raw sub-key (audit #7789).
(def ^:private boundary-a {:parent nil :key [[:app/main :todo [:todo 7]]]})
(def ^:private boundary-b {:parent nil :key [[:app/main :todo [:todo 7]]
                                             [:app/main :user [:user 1]]]})
(def ^:private boundary-redacted
  {:parent nil :key [[:app/main :todo :rf/redacted]
                     [:app/main :user :rf/redacted]]})

(defn- envelope [read m]
  (merge {:schema    hh/consumed-evidence-schema
          :producer  hh/consumed-producer
          :read      read
          :scope     :mounted-boundaries
          :basis     :observation
          :complete? true
          :loss      nil}
         m))

(def ^:private mounted
  (envelope :mounted-boundaries
            {:boundaries [{:boundary boundary-a :view :unknown :source :unknown
                           :instances 3 :read-orders 1 :frame :app/main
                           :reads [{:sub-id :todo :query [:todo 7]
                                    :frame-id :app/main :epoch 4}]}
                          {:boundary {:parent nil :key []} :view :unknown
                           :source :unknown :instances 1 :read-orders 1
                           :frame :unknown :reads []}]
             :naming {:basis :opaque :complete? false
                      :loss {:reason :opaque :dropped :unknown}
                      :view :unknown :source :unknown}
             :host   {:basis :host-opaque :complete? false
                      :loss {:reason :host-opaque :dropped :unknown}
                      :commit :unknown :paint :unknown}}))

;; ---------------------------------------------------------------------------
;; THE PROPERTY THIS TAB EXISTS FOR
;; ---------------------------------------------------------------------------

(deftest the-five-absences-are-pairwise-distinct
  (testing "every loss kind the producer can state has a chip"
    (is (= #{:cap :opaque :host-opaque :uncorrelated :unknown}
           (set (keys hh/loss-kinds)))))

  (testing "no two kinds share a word"
    (let [shorts (map :short (vals hh/loss-kinds))]
      (is (= (count shorts) (count (set shorts)))
          (str "two chips print the same word: " (pr-str (sort shorts))))))

  (testing "no two kinds share a sentence"
    (let [says (map :says (vals hh/loss-kinds))]
      (is (= (count says) (count (set says))))))

  (testing "no two kinds share a testid suffix — a browser selector must not collide"
    (let [suffixes (keep (fn [kind] (:testid-suffix (hh/loss-chip kind)))
                         (keys hh/loss-kinds))]
      (is (= 5 (count suffixes)))
      (is (= 5 (count (set suffixes))))))

  (testing "each kind resolves from a producer loss MAP, a bare reason, and a value"
    (is (= :cap (:kind (hh/loss-chip {:reason :cap :dropped 4}))))
    (is (= :uncorrelated (:kind (hh/loss-chip :uncorrelated))))
    (is (= :unknown (:kind (hh/loss-chip nil :unknown))))
    (is (nil? (hh/loss-chip nil))
        "no loss and no unknown value is no chip — an absence marker on a fact
         that is present would be the mirror-image dishonesty"))

  (testing "the dropped account is never blank"
    (is (= "an unknown amount" (hh/dropped-label :unknown)))
    (is (= "4 dropped" (hh/dropped-label 4)))
    (is (= "an unknown amount" (hh/dropped-label nil))
        "an absent :dropped must not render as nothing — that is the shape the
         producer refuses to emit, reintroduced at the last step")))

(deftest the-empties-are-pairwise-distinct
  (testing "presence classifies the four states"
    (is (= :absent   (hh/presence nil true)))
    (is (= :mismatch (hh/presence (assoc mounted :schema :something/else) true)))
    (is (= :mismatch (hh/presence (assoc mounted :producer :someone/else) true))
        "an adapter-neutral schema means the PRODUCER is part of the pin")
    (is (= :idle     (hh/presence mounted true)))
    (is (= :live     (hh/presence mounted false))))

  (testing "no two states share a sentence or a testid"
    (let [says     (map :says (vals hh/presence-copy))
          suffixes (map :testid-suffix (vals hh/presence-copy))]
      (is (= 2 (count says) (count (set says))))
      (is (= 2 (count suffixes) (count (set suffixes))))))

  (testing "each sentence names its own remedy rather than a shared stem"
    (is (string/includes? (:says (:absent hh/presence-copy)) "production build"))
    (is (string/includes? (:says (:mismatch hh/presence-copy)) "not taught to parse"))))

(deftest an-empty-roster-means-something-different-in-each-view
  ;; AUDIT #7789, CORRECTNESS 3. One `:idle` sentence — written for the
  ;; mounted census, "nothing is mounted, a clean bill of health" — was
  ;; shown under all four views. Under Intents it told a reader a CAPPED
  ;; window proved nothing had been dispatched; under Reads it denied the
  ;; existence of mounted boundaries that read nothing.
  (testing "every view has its own copy — a view with none would fall back to silence"
    (is (= (set (map :id hh/sub-modes)) (set (keys hh/empty-copy)))))

  (testing "no two views share a sentence or a testid suffix"
    (let [says     (map :says (vals hh/empty-copy))
          suffixes (map :testid-suffix (vals hh/empty-copy))]
      (is (= 4 (count says) (count (set says))))
      (is (= 4 (count suffixes) (count (set suffixes))))))

  (testing "each names ITS OWN scope's fact and remedy"
    (is (string/includes? (:says (:mounted hh/empty-copy)) "read edge"))
    (is (string/includes? (:says (:attribution hh/empty-copy)) "reads nothing"))
    (is (string/includes? (:says (:intents hh/empty-copy)) "CAP"))
    (is (string/includes? (:says (:explain hh/empty-copy)) "no mounted boundary")))

  (testing "the CAPPED window never claims nothing was dispatched"
    (let [says (:says (:intents hh/empty-copy))]
      (is (string/includes? says "cannot say whether anything was dispatched"))
      (is (not (string/includes? says "clean bill of health"))
          "the mounted census's verdict must not be reused where it is false")))

  (testing "state-copy resolves the empty per view and everything else view-free"
    (is (= "empty-intents" (:testid-suffix (hh/state-copy :idle :intents))))
    (is (= "empty-mounted" (:testid-suffix (hh/state-copy :idle :mounted))))
    (is (= "absent" (:testid-suffix (hh/state-copy :absent :intents))))
    (is (= "absent" (:testid-suffix (hh/state-copy :absent :mounted)))
        "a door that answered nil says the same thing in every view")
    (is (nil? (hh/state-copy :live :mounted))
        "there is nothing to say when there are rows")))

(deftest the-mounted-empty-does-not-claim-the-screen-is-empty
  ;; AUDIT #7792. An Activity-hidden subtree that released its reads leaves
  ;; exactly this census, so the copy may not read as proof that nothing is
  ;; retained above.
  (let [says (:says (:mounted hh/empty-copy))]
    (is (string/includes? says "SUBSCRIPTION"))
    (is (string/includes? says "re-subscribe"))))

;; ---------------------------------------------------------------------------
;; The schema pin
;; ---------------------------------------------------------------------------

(deftest the-schema-pin-is-consumer-owned-and-exact
  (testing "an envelope stamped anything else degrades rather than mis-parses"
    (is (true? (hh/supported? mounted)))
    (is (false? (hh/supported? (assoc mounted :schema :re-frame.hicasso.evidence/v99))))
    (is (false? (hh/supported? (assoc mounted :producer :re-frame/freehand))))
    (is (false? (hh/supported? nil))))
  (testing "an unsupported envelope yields NO rows — never a half-parsed one"
    (doseq [f [hh/mounted-rows hh/attribution-rows hh/intent-rows hh/explain-rows]]
      (is (= [] (f (assoc mounted :schema :re-frame.hicasso.evidence/v99))))
      (is (= [] (f nil))))))

(deftest the-superseded-v1-shape-is-refused-rather-than-mis-parsed
  ;; MERGED-PR AUDIT #7802, RESIDUAL 1. The #7789 repair evolved the wire
  ;; shape — key elements from `[frame-id query]` to
  ;; `[frame-id sub-id projected-query]`, intent rows from a singular
  ;; `:frame-id` to a plural `:frames`, `:latest-reads` from bare sub-ids to
  ;; `{:sub-id :query :frame-id}` maps, plus new host fields — and left the
  ;; stamp at v1. So for one increment this pin accepted, as EXACT, a shape
  ;; it had never been taught: a version that lies, which is worse than no
  ;; version because it turns a loud refusal into a silent misread.
  ;;
  ;; The pin only earns its keep if the predecessor now MISMATCHES. There is
  ;; no v1 acceptance path and no compatibility adapter to route around this.
  (let [v1 (assoc mounted :schema :re-frame.hicasso.evidence/v1)]
    (testing "the pin names the version whose shape this build actually parses"
      (is (= :re-frame.hicasso.evidence/v2 hh/consumed-evidence-schema)
          (str "the wire shape and the stamp move together or the pin is "
               "nominal — change one and this row says so")))

    (testing "NON-VACUITY: the identical envelope under the current stamp parses"
      (is (true? (hh/supported? mounted)))
      (is (= 2 (count (hh/mounted-rows mounted)))
          (str "if this row fails, the refusals below are passing against an "
               "envelope that would have yielded nothing anyway")))

    (testing "the superseded stamp is a MISMATCH, not an older dialect"
      (is (false? (hh/supported? v1)))
      (is (= :mismatch (hh/presence v1 false))
          "and it renders the mismatch banner, not an empty roster"))

    (testing "and every view suppresses its rows rather than half-parsing them"
      (doseq [f [hh/mounted-rows hh/attribution-rows hh/intent-rows hh/explain-rows]]
        (is (= [] (f v1)))))))

;; ---------------------------------------------------------------------------
;; The row projections
;; ---------------------------------------------------------------------------

(deftest mounted-rows-carry-the-instance-count-and-both-chips
  (let [[row read-free] (hh/mounted-rows mounted)]
    (is (= 3 (:instances row)))
    (is (= ":app/main" (hh/format-id :app/main)))
    (is (= "[:todo 7]" (:label row)))
    (is (= :opaque (:kind (:view-chip row)))
        "the view name is opaque, and the row says so on the row")
    (is (nil? (:frame-chip row)) "the frame IS known here, so no chip")
    (testing "a read-free boundary is labelled, not blanked"
      (is (= "(reads nothing)" (:label read-free)))
      (is (= "reads-nothing" (:slug read-free)))
      (is (= :unknown (:kind (:frame-chip read-free)))
          "with no reads there is no frame, and the chip says unknown"))))

(deftest a-label-and-a-testid-are-built-from-the-PROJECTED-key
  ;; AUDIT #7789, CORRECTNESS 1, consumer half. The helpers printed the raw
  ;; query in a label and hashed it into a DOM testid, so an argument the
  ;; producer had projected out of the data arrived on the page anyway.
  ;; They now read the projected key element, and a redacted query is
  ;; rendered as the sentinel it is.
  (testing "a redacted read names its registration id, so two of them stay apart"
    (is (= ":todo :rf/redacted" (hh/read-label [:app/main :todo :rf/redacted])))
    (is (= ":user :rf/redacted" (hh/read-label [:app/main :user :rf/redacted])))
    (is (= "[:todo 7]" (hh/read-label [:app/main :todo [:todo 7]]))
        "an unredacted query still reads as itself"))

  (testing "a wholly redacted boundary is still labelled and still selectable"
    (let [row (first (hh/mounted-rows
                       (assoc mounted :boundaries
                              [{:boundary boundary-redacted :view :unknown
                                :source :unknown :instances 1 :read-orders 1
                                :frame :app/main :reads []}])))]
      (is (= ":todo :rf/redacted + :user :rf/redacted" (:label row)))
      (is (not (string/includes? (:slug row) "hunter"))
          "nothing from an argument may reach a testid")
      (is (not= (hh/boundary-slug boundary-redacted)
                (hh/boundary-slug {:parent nil :key [[:app/main :todo :rf/redacted]]}))
          (str "two redacted boundaries must not collapse to one testid — a "
               "browser assertion selecting one would silently match the other")))))

(deftest attribution-rows-carry-fan-out-and-readers
  (let [e (envelope :read-attribution
                    {:scope :read-edges
                     :edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
                              :epoch 4 :fan-out 3
                              :readers [boundary-a boundary-b]}]})
        [row] (hh/attribution-rows e)]
    (is (= 3 (:fan-out row)))
    (is (= ["[:todo 7]" "[:todo 7] + [:user 1]"] (mapv :label (:readers row))))))

(deftest intent-rows-carry-an-id-and-an-arity-and-no-arguments
  (let [e (envelope :intents
                    {:scope {:frames [:app/main] :retained-runs 2}
                     :complete? false
                     :loss {:reason :cap :dropped :unknown}
                     :intents [{:frames [:app/main] :dispatch-id 41
                                :event-id :todo/toggle :arg-count 1
                                :sub-ids [:todo]}
                               {:frames [:app/main :app/other] :dispatch-id 42
                                :event-id :todo/toggle :arg-count 1
                                :sub-ids [:todo]}]})
        [row second-row] (hh/intent-rows e)]
    (is (= :todo/toggle (:event-id row)))
    (is (= 1 (:arg-count row)))
    (is (= [:app/main] (:frames row))
        "a row names the frames the dispatch reached, not one ring's id")
    (is (not (contains? row :event))
        "an argument vector must not reach a row — the producer does not send one")
    (testing "two runs of ONE event id get two testids"
      (is (not= (:slug row) (:slug second-row))
          (str "the stream is ordered by dispatch and one event id recurs, so an "
               "event-only testid would name several rows at once")))))

(deftest explain-rows-keep-the-proven-half-apart-from-the-uncorrelated-half
  (let [with-leads
        (envelope :explain-render
                  {:complete? false
                   :loss {:reason :uncorrelated :dropped :unknown}
                   :explanations [{:boundary boundary-a :frame :app/main
                                   :instances 1 :snapshot 9 :peak-epoch 5
                                   :latest-reads [{:sub-id :todo :query [:todo 7]
                                                   :frame-id :app/main}]
                                   :cause :unknown
                                   :loss {:reason :uncorrelated :dropped :unknown}
                                   :candidates [{:dispatch-id 41 :event-id :todo/toggle
                                                 :frame-id :app/main :sub-id :todo}]}]})
        blind
        (envelope :explain-render
                  {:complete? false
                   :loss {:reason :uncorrelated :dropped :unknown}
                   :explanations [{:boundary boundary-a :frame :app/main
                                   :instances 1 :snapshot :unknown
                                   :peak-epoch :unknown :latest-reads :unknown
                                   :cause :unknown
                                   :loss {:reason :cap :dropped :unknown}
                                   :candidates :unknown}]})]
    (testing "with a live window the row is proven AND uncorrelated at once"
      (let [[row] (hh/explain-rows with-leads)]
        (is (true? (:proven? row)))
        (is (= ["[:todo 7]"] (:latest-reads row))
            "the READ, not the bare sub-id — see the two-variants row below")
        (is (= :uncorrelated (:kind (:cause-chip row))))
        (is (true? (:leads-known? row)))
        (is (= 1 (count (:leads row))))))
    (testing "with an empty window the leads are UNKNOWN, and the row says which"
      (let [[row] (hh/explain-rows blind)]
        (is (false? (:proven? row)))
        (is (= :cap (:kind (:cause-chip row))))
        (is (false? (:leads-known? row))
            "an [] here would read as `no run recomputed anything`, which is the
             one thing an empty window cannot know")
        (is (= [] (:leads row))
            "rendered as an empty seq so a renderer cannot iterate a keyword —
             `:leads-known?` is what carries the distinction")))))

(deftest two-parameterizations-of-one-sub-do-not-collapse-in-the-why-view
  ;; AUDIT #7789, ERGONOMICS. `:latest-reads` named sub-ids, so eight rows
  ;; of one registered sub all answered ":row moved".
  (let [e (envelope :explain-render
                    {:complete? false
                     :loss {:reason :uncorrelated :dropped :unknown}
                     :explanations [{:boundary boundary-a :frame :app/main
                                     :instances 1 :snapshot 9 :peak-epoch 5
                                     :latest-reads [{:sub-id :row :query [:row 1]
                                                     :frame-id :app/main}
                                                    {:sub-id :row :query [:row 2]
                                                     :frame-id :app/main}]
                                     :cause :unknown
                                     :loss {:reason :uncorrelated :dropped :unknown}
                                     :candidates []}]})
        [row] (hh/explain-rows e)]
    (is (= ["[:row 1]" "[:row 2]"] (:latest-reads row))
        "two reads of one sub read as two, because their queries differ")))

(deftest the-summary-line-states-the-claim-even-when-it-is-good
  (testing "a complete envelope still says so — an absence of bad news is not news"
    (is (string/includes? (hh/read-summary mounted) "complete for this scope")))
  (testing "an incomplete one names its loss and how much"
    (let [s (hh/read-summary (envelope :intents
                                       {:complete? false
                                        :loss {:reason :cap :dropped :unknown}
                                        :intents []}))]
      (is (string/includes? s "INCOMPLETE"))
      (is (string/includes? s "capped"))
      (is (string/includes? s "an unknown amount"))))
  (testing "no envelope, no summary — the panel renders a presence note instead"
    (is (nil? (hh/read-summary nil)))))

(deftest the-four-views-are-the-four-questions
  (is (= [:mounted :attribution :intents :explain] (mapv :id hh/sub-modes)))
  (is (= :mounted hh/default-sub-mode))
  (is (= :mounted (hh/normalise-sub-mode :nonsense))
      "a stale or hand-dispatched id must land on a view that exists")
  (is (= :explain (hh/normalise-sub-mode :explain)))
  (testing "every view says what it asks, so a tooltip is not invented at render"
    (is (every? (comp seq :asks) hh/sub-modes))))
