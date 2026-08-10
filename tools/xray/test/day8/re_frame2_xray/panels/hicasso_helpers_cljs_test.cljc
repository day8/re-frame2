(ns day8.re-frame2-xray.panels.hicasso-helpers-cljs-test
  "The Hicasso tab's pure algebra (rf2-hic-023).

  Two properties carry this suite, and both are about the same thing —
  whether the producer's honesty survives the trip to the screen.

  1. **The five absences are pairwise distinct**, in the word a chip shows
     AND in the testid it renders under. A schema that refuses to encode
     unknown as an empty collection buys nothing if the panel then draws
     `capped` and `uncorrelated` identically, and \"distinct\" is a
     property a suite can check where \"we were careful\" is not.
  2. **The three empties are pairwise distinct.** *Not running Hicasso*,
     *a schema this build cannot parse* and *running with nothing mounted*
     have unrelated remedies, and a reader who cannot tell them apart is
     back where the schema found them.

  Everything else here is the row projections, which are ordinary."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — envelopes shaped exactly as the producer emits them
;; ---------------------------------------------------------------------------

(def ^:private boundary-a {:parent nil :key [[:app/main [:todo 7]]]})
(def ^:private boundary-b {:parent nil :key [[:app/main [:todo 7]]
                                             [:app/main [:user 1]]]})

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

(deftest the-three-empties-are-pairwise-distinct
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
      (is (= 3 (count says) (count (set says))))
      (is (= 3 (count suffixes) (count (set suffixes))))))

  (testing "each sentence names its own remedy rather than a shared stem"
    (is (string/includes? (:says (:absent hh/presence-copy)) "production build"))
    (is (string/includes? (:says (:mismatch hh/presence-copy)) "not taught to parse"))
    (is (string/includes? (:says (:idle hh/presence-copy)) "clean bill of health"))))

;; ---------------------------------------------------------------------------
;; The schema pin
;; ---------------------------------------------------------------------------

(deftest the-schema-pin-is-consumer-owned-and-exact
  (testing "an envelope stamped anything else degrades rather than mis-parses"
    (is (true? (hh/supported? mounted)))
    (is (false? (hh/supported? (assoc mounted :schema :re-frame.hicasso.evidence/v2))))
    (is (false? (hh/supported? (assoc mounted :producer :re-frame/freehand))))
    (is (false? (hh/supported? nil))))
  (testing "an unsupported envelope yields NO rows — never a half-parsed one"
    (doseq [f [hh/mounted-rows hh/attribution-rows hh/intent-rows hh/explain-rows]]
      (is (= [] (f (assoc mounted :schema :re-frame.hicasso.evidence/v2))))
      (is (= [] (f nil))))))

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
                     :intents [{:frame-id :app/main :dispatch-id 41
                                :event-id :todo/toggle :arg-count 1
                                :sub-ids [:todo]}]})
        [row] (hh/intent-rows e)]
    (is (= :todo/toggle (:event-id row)))
    (is (= 1 (:arg-count row)))
    (is (not (contains? row :event))
        "an argument vector must not reach a row — the producer does not send one")))

(deftest explain-rows-keep-the-proven-half-apart-from-the-uncorrelated-half
  (let [with-leads
        (envelope :explain-render
                  {:complete? false
                   :loss {:reason :uncorrelated :dropped :unknown}
                   :explanations [{:boundary boundary-a :frame :app/main
                                   :instances 1 :snapshot 9 :peak-epoch 5
                                   :latest-reads [:todo] :cause :unknown
                                   :loss {:reason :uncorrelated :dropped :unknown}
                                   :candidates [{:dispatch-id 41 :event-id :todo/toggle
                                                 :sub-id :todo}]}]})
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
        (is (= [:todo] (:latest-reads row)))
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
