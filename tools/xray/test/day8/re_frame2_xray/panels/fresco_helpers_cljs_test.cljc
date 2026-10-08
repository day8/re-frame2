(ns day8.re-frame2-xray.panels.fresco-helpers-cljs-test
  "The Fresco tab's pure algebra.

  Two properties carry this suite, and both are about the same thing —
  whether the producer's honesty survives the trip to the screen.

  1. **The five absences are pairwise distinct**, in the word a chip shows
     AND in the testid it renders under. A schema that refuses to encode
     unknown as an empty collection buys nothing if the panel then draws
     `capped` and `uncorrelated` identically, and \"distinct\" is a
     property a suite can check where \"we were careful\" is not.
  2. **The empties are pairwise distinct.** *Not running Fresco* and *a
     schema this build cannot parse* have unrelated remedies, and a reader
     who cannot tell them apart is back where the schema found them. The
     third empty — *the roster came back empty* — is not one fact but
     one per view (six), since the mounted census's verdict read out
     under Intents would be false. Each view therefore answers for its
     own scope.

  Everything else here is the row projections, which are ordinary."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.fresco-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — envelopes shaped exactly as the producer emits them
;; ---------------------------------------------------------------------------

;; Keys are the PROJECTED shape the producer exports —
;; `[frame-id sub-id projected-query]`, never a raw sub-key.
(def ^:private boundary-a {:parent nil :key [[:app/main :todo [:todo 7]]]})
(def ^:private boundary-b {:parent nil :key [[:app/main :todo [:todo 7]]
                                             [:app/main :user [:user 1]]]})

(defn- envelope [read m]
  (merge {:schema    hh/consumed-evidence-schema
          :producer  hh/consumed-producer
          :read      read
          :complete? true
          :loss      nil}
         m))

(def ^:private todo-row-views
  [{:view "app.views/todo-row"
    :source {:ns 'app.views :file "/src/app/views.cljs" :line 12 :column 1}}])

(def ^:private mounted
  (envelope :mounted-boundaries
            {:boundaries [{:boundary boundary-a :views todo-row-views
                           :instances 3 :read-orders 1 :frame :app/main
                           :reads [{:sub-id :todo :query [:todo 7]
                                    :frame-id :app/main :epoch 4}]}
                          {:boundary {:parent nil :key []} :views :unknown
                           :instances 1 :read-orders 1
                           :frame :unknown :reads []}]
             :generation 12}))

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
  ;; Each sentence is the per-view meaning `027-Fresco-Evidence.md` tabulates.
  (testing "every view has its own copy — a view with none would fall back to silence"
    (is (= (set (map :id hh/sub-modes)) (set (keys hh/empty-copy)))))

  (testing "no two views share a sentence or a testid suffix"
    ;; Counted against the LIVE view list, so a view cannot be added
    ;; carrying another view's sentence and still pass.
    (let [says     (map :says (vals hh/empty-copy))
          suffixes (map :testid-suffix (vals hh/empty-copy))
          n        (count hh/sub-modes)]
      (is (= n (count says) (count (set says))))
      (is (= n (count suffixes) (count (set suffixes))))))

  (testing "each names ITS OWN scope's fact and remedy"
    (let [mounted-says (:says (:mounted hh/empty-copy))]
      (is (string/includes? mounted-says "read edge"))
      (is (string/includes? mounted-says "SUBSCRIPTION")
          "a survey of subscriptions, not a claim that the screen is empty")
      (is (string/includes? mounted-says "re-subscribe")))
    (is (string/includes? (:says (:attribution hh/empty-copy)) "reads nothing"))
    (is (string/includes? (:says (:intents hh/empty-copy)) "CAP"))
    (is (string/includes? (:says (:intents hh/empty-copy))
                          "cannot say whether anything was dispatched"))
    (is (string/includes? (:says (:explain hh/empty-copy)) "no mounted boundary"))
    (is (string/includes? (:says (:advisor hh/empty-copy)) "not a verdict that nothing is hot"))
    (is (string/includes? (:says (:causal hh/empty-copy)) "no boundary to trace")))

  (testing "state-copy resolves the empty per view and everything else view-free"
    (is (= "empty-intents" (:testid-suffix (hh/state-copy :idle :intents))))
    (is (= "absent" (:testid-suffix (hh/state-copy :absent :intents))))
    (is (nil? (hh/state-copy :live :mounted))
        "there is nothing to say when there are rows")))

;; ---------------------------------------------------------------------------
;; The schema pin
;; ---------------------------------------------------------------------------

(deftest the-superseded-v2-shape-is-refused-rather-than-mis-parsed
  ;; The pin is exact: a predecessor stamp is a mismatch, not an older
  ;; dialect, and there is no acceptance path for it.
  (let [v2 (assoc mounted :schema :re-frame.fresco.evidence/v2)]
    (is (= :mismatch (hh/presence v2 false))
        "it renders the mismatch banner, not an empty roster")
    (doseq [f [hh/mounted-rows hh/attribution-rows hh/intent-rows hh/explain-rows]]
      (is (= [] (f v2)) "and every view suppresses its rows rather than half-parsing them"))))

;; ---------------------------------------------------------------------------
;; The row projections
;; ---------------------------------------------------------------------------

(deftest mounted-rows-carry-the-instance-count-the-view-and-the-chips
  (let [[row read-free] (hh/mounted-rows mounted)]
    (is (= 3 (:instances row)))
    (is (= "[:todo 7]" (:label row)))
    (testing "a named row leads with its view and carries no view chip"
      (is (= "app.views/todo-row" (:view-label row)))
      (is (nil? (:view-chip row))))
    (testing "a read-free boundary is labelled, not blanked"
      (is (= "(reads nothing)" (:label read-free)))
      (is (= :unknown (:kind (:frame-chip read-free)))
          "with no reads there is no frame, and the chip says unknown"))
    (testing "an unnamed row renders the unknown chip in the view position, never a blank"
      (is (= :unknown (:kind (:view-chip read-free)))))
    (testing "two views over one edge set are both named, and a name without a source has no title line"
      (let [views [{:view "a/one" :source :unknown} {:view "b/two" :source {:file "f" :line 3}}]]
        (is (= "a/one, b/two" (hh/view-label views)))
        (is (= "b/two — f:3" (hh/views-title views)))))))

(deftest attribution-rows-carry-fan-out-and-readers
  (let [e (envelope :read-attribution
                    {:edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
                              :epoch 4 :fan-out 3
                              :readers [(assoc boundary-a :views todo-row-views)
                                        (assoc boundary-b :views :unknown)]}]})
        [row] (hh/attribution-rows e)]
    (is (= 3 (:fan-out row)))
    (is (= ["[:todo 7]" "[:todo 7] + [:user 1]"] (mapv :label (:readers row))))
    (is (= ["app.views/todo-row" nil] (mapv :view-label (:readers row)))
        "a reader is named where the producer names it, and unnamed where it does not")))

(deftest a-row-key-carries-the-WHOLE-projected-identity
  ;; Attribution rows slugged by sub-id alone, or Why rows slugged without
  ;; the frame, would give two different facts one React key and one testid.
  (testing "the Reads rows differ in frame AND in query, and say so on the row"
    (let [e (envelope :read-attribution
                      {:edges [{:sub-id :row :query [:row 1] :frame-id :frame/a
                                :epoch 1 :fan-out 1 :readers []}
                               {:sub-id :row :query [:row 2] :frame-id :frame/b
                                :epoch 1 :fan-out 1 :readers []}]})
          [a b] (hh/attribution-rows e)]
      (is (not= (:slug a) (:slug b))
          "a sub-id-only slug gives both `row` — one testid over two subscriptions")
      (is (= ["[:row 1]" "[:row 2]"] [(:label a) (:label b)])
          "and a view printing only `:row` would make the rows READ the same too")
      (is (= [:frame/a :frame/b] [(:frame-id a) (:frame-id b)])
          "the frame is on the row for the renderer to print")))

  (testing "the Why rows carry the frame, because two frames' labels are equal"
    (let [ex (fn [frame q]
               {:boundary {:parent nil :key [[frame :row q]]} :views :unknown :frame frame
                :instances 1 :snapshot 9 :peak-epoch 5
                :latest-reads [{:sub-id :row :query q :frame-id frame}]
                :loss {:reason :uncorrelated :dropped :unknown}
                :candidates []})
          [a b] (hh/explain-rows (envelope :explain-render
                                           {:explanations [(ex :frame/a [:row 1])
                                                           (ex :frame/b [:row 1])]}))]
      (is (not= (:slug a) (:slug b)))
      (is (= [:frame/a :frame/b] [(:frame a) (:frame b)])
          "their labels are identical, so the frame is what tells them apart on screen")))

  (testing "a wholly redacted query is named by its registration id and the sentinel"
    (let [row (first (hh/attribution-rows
                       (envelope :read-attribution
                                 {:edges [{:sub-id :todo :query :rf/redacted
                                           :frame-id :app/main :epoch 1
                                           :fan-out 1 :readers []}]})))]
      (is (= ":todo :rf/redacted" (:label row))))))

;; ---------------------------------------------------------------------------
;; The row key is INJECTIVE — the property, not three examples
;; ---------------------------------------------------------------------------

(def ^:private legal-components
  "Every SHAPE a projected identity component legally takes, and the pairs
  of shapes that a lossy encoding folds together.

  The collision this pool exists to catch is found by GENERATING
  identities; asserting only that three fixtures come out distinct is
  true of a constant function on three points. So the guard below is a
  property over a generated space and the hand-written rows underneath it
  are for readability only.

  The pool is chosen so that the three ways a slug can lose information
  are all present and all adjacent: the namespace separator (`:a/b`), an
  in-name hyphen (`:a-b`), and the boundary BETWEEN two components
  (`:a` then `:b/c`). Numbers `1` and `12` sit next to each other for the
  same reason, and `[:a :b]` next to `[[:a] :b]` puts a nesting level
  where a flattening encoding cannot see it."
  [;; keywords: plain, namespaced, hyphenated, and both
   :a :b
   :a/b :b/a
   :a-b
   :a/b-c :a-b/c
   ;; strings — the same three shapes again, where `pr-str` quotes them
   "a" "a-b" "a/b"
   ;; numbers, including a two-digit one adjacent to its digits
   0 1 12
   ;; vectors: empty, single, multiple, hyphenated, and two nestings
   [] [:a] [:a :b] [:a-b] [[:a] :b] [:a [:b]]
   ;; the two sentinels the producer sends in place of a value
   :rf/redacted :unknown])

(def ^:private legal-identities
  "The cross-product of [[legal-components]] in all three projected
  positions — frame, registration id, query. Every element is a legal
  projected identity and no two are equal, so the count IS the number of
  distinct keys the panel must be able to mint."
  (vec (for [frame legal-components
             sub   legal-components
             query legal-components]
         [frame sub query])))

(def ^:private legal-boundary-keys
  "Boundary keys over the same space: the empty key, every identity as a
  one-read key, and every ordered pair drawn from a slice of the space —
  a boundary reads a SET of cells, so the encoding has to survive the
  join between elements as well as the join inside one."
  (into [[]]
        (concat (map vector legal-identities)
                (for [a (take 30 legal-identities)
                      b (take 30 legal-identities)]
                  [a b]))))

(defn- collisions
  "The values of `f` that more than one input produced, with their inputs
  — so a failure NAMES the colliding rows instead of quoting two counts."
  [f xs]
  (->> xs
       (group-by f)
       (filter (fn [[_ ins]] (< 1 (count ins))))
       (map (fn [[out ins]] [out (vec (take 3 ins))]))
       (take 3)
       vec))

(deftest a-row-key-is-INJECTIVE-over-the-whole-legal-identity-space
  ;; Putting the whole projected identity into one string and then passing
  ;; that string through `id-slug`, which replaces every run of
  ;; non-alphanumerics with `-`, would make namespace separators, in-name
  ;; hyphens, component boundaries and collection punctuation all the same
  ;; character — the encoding would REINTRODUCE the collision:
  ;;
  ;;   [:a/b :c :d]  [:a-b :c :d]  [:a :b/c :d]  ->  "a-b-c-d" x3
  ;;
  ;; A React key must be unique and a testid must select one row. Neither
  ;; has to be pretty — the projected query and the frame are printed as
  ;; the row's LABEL, which is where a reader looks.
  (testing "NON-VACUITY: the space really does defeat a lossy slug"
    (is (= 1 (count (distinct (map #(hh/id-slug (pr-str %))
                                   [[:a/b :c :d] [:a-b :c :d] [:a :b/c :d]]))))
        "if these three ever stop colliding under `id-slug` alone, the
         property below has stopped testing anything and the pool needs
         re-choosing"))

  (testing "DISTINCT IDENTITIES YIELD DISTINCT READ SLUGS, over the whole space"
    (is (= (count legal-identities)
           (count (distinct (map hh/read-slug legal-identities))))
        (str "read-slug collisions: " (pr-str (collisions hh/read-slug legal-identities)))))

  (testing "and distinct boundary KEYS yield distinct boundary slugs"
    (let [slug (fn [k] (hh/boundary-slug {:parent nil :key k}))]
      (is (= (count legal-boundary-keys)
             (count (distinct (map slug legal-boundary-keys))))
          (str "boundary-slug collisions: " (pr-str (collisions slug legal-boundary-keys))))
      (is (= "reads-nothing" (slug []))
          "the read-free key keeps its readable name, which the property above
           forbids any key with reads in it to mint")))

  (testing "an intent row key is injective over the same shapes"
    (let [rows (for [event-id  legal-components
                     dispatch  legal-components]
                 {:event-id event-id :dispatch-id dispatch})
          slug (fn [r] (:slug (first (hh/intent-rows (envelope :intents {:intents [r]})))))]
      (is (= (count rows) (count (distinct (map slug rows))))
          (str "intent slug collisions: " (pr-str (collisions slug rows))))))

  (testing "the readable stem survives, so a testid is still greppable"
    (is (string/starts-with? (hh/read-slug [:frame/a :row [:row 1]]) "frame-a-row-row-1-")
        "the stem is the readable slug; the tail behind the last `-` is what
         makes it injective")))

(deftest intent-rows-carry-an-id-and-an-arity-and-no-arguments
  (let [[row] (hh/intent-rows
                (envelope :intents
                          {:intents [{:frames [:app/main] :dispatch-id 41
                                      :event-id :todo/toggle :arg-count 1
                                      :sub-ids [:todo]}]}))]
    (is (= {:dispatch-id 41 :event-id :todo/toggle :arg-count 1
            :frames [:app/main] :sub-ids [:todo]}
           (dissoc row :slug)))))

(deftest explain-rows-keep-the-proven-half-apart-from-the-uncorrelated-half
  (let [with-leads
        (envelope :explain-render
                  {:explanations [{:boundary boundary-a :views todo-row-views :frame :app/main
                                   :instances 1 :snapshot 9 :peak-epoch 5
                                   :latest-reads [{:sub-id :todo :query [:todo 7]
                                                   :frame-id :app/main}]
                                   :loss {:reason :uncorrelated :dropped :unknown}
                                   :candidates [{:dispatch-id 41 :event-id :todo/toggle
                                                 :frame-id :app/main :sub-id :todo}]}]})
        blind
        (envelope :explain-render
                  {:explanations [{:boundary boundary-a :views :unknown :frame :app/main
                                   :instances 1 :snapshot :unknown
                                   :peak-epoch :unknown :latest-reads :unknown
                                   :loss {:reason :cap :dropped :unknown}
                                   :candidates :unknown}]})]
    (testing "with a live window the row is proven AND uncorrelated at once"
      (let [[row] (hh/explain-rows with-leads)]
        (is (true? (:proven? row)))
        (is (= ["[:todo 7]"] (:latest-reads row))
            "the READ, not the bare sub-id — two parameterizations of one sub
             must not both answer `:todo moved`")
        (is (= :uncorrelated (:kind (:cause-chip row))))
        (is (true? (:leads-known? row)))
        (is (= 1 (count (:leads row))))
        (is (= "app.views/todo-row" (:view-label row)) "the Why row is named too")))
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
    (is (= "read :mounted-boundaries · complete" (hh/read-summary mounted))))
  (testing "an incomplete one names its loss and how much"
    (is (= "read :intents · INCOMPLETE · capped — an unknown amount"
           (hh/read-summary (envelope :intents
                                      {:complete? false
                                       :loss {:reason :cap :dropped :unknown}
                                       :intents []})))))
  (testing "no envelope, no summary — the panel renders a presence note instead"
    (is (nil? (hh/read-summary nil)))))

(deftest a-stale-sub-mode-normalises-to-the-default-view
  (is (= :mounted (hh/normalise-sub-mode :nonsense))
      "a stale or hand-dispatched id must land on a view that exists")
  (is (= :explain (hh/normalise-sub-mode :explain))))

;; ---------------------------------------------------------------------------
;; XRAY'S OWN MACHINERY IS NOT APPLICATION EVIDENCE
;; ---------------------------------------------------------------------------
;;
;; Xray's panels are Fresco boundaries, and Fresco's census walks the
;; collector's process-global entry table with no frame filter — so,
;; unfiltered, the Fresco tab would list ITSELF: with one application
;; boundary mounted, the Mounted view would commit two rows, the second
;; `…panels.fresco/Panel · frame :rf/xray · 2 reads`.
;;
;; Each row asserts the identity of the survivors, not just their count,
;; over fixtures two rows deep, so a filter dropping the WRONG row reddens.
;; The frame rows run for the production singleton `:rf/xray` AND a
;; NON-DEFAULT shell frame: a filter asking `(= :rf/xray frame)` is blind to
;; every other shell 008 §Parameterized shell frame-id permits, and only the
;; custom arm reddens against it.

(def ^:private custom-shell-frame
  "A NON-DEFAULT Xray shell frame — the `:frame-id` a testbed mounting N
  shells side by side passes to `shell-view` / `ensure-xray-frame!`."
  ::custom-shell)

(defn- mixed-evidence
  "One turn's four envelopes, each carrying an application row AND one
  owned by the Xray shell seated in `tool-frame`. Two rows deep in every
  roster on purpose: a filter that dropped the WRONG row would satisfy a
  count and fail the identity assertions below."
  [tool-frame]
  {:mounted-boundaries
   (envelope :mounted-boundaries
             {:boundaries [{:boundary boundary-a :views todo-row-views
                            :instances 1 :read-orders 1 :frame :app/main
                            :reads []}
                           {:boundary boundary-b :views :unknown
                            :instances 1 :read-orders 1 :frame tool-frame
                            :reads []}]})
   :read-attribution
   (envelope :read-attribution
             {:edges [{:sub-id :todo :query [:todo 7] :frame-id :app/main
                       :epoch 4 :fan-out 1 :readers []}
                      {:sub-id :rf.xray.fresco/data
                       :query [:rf.xray.fresco/data tool-frame]
                       :frame-id tool-frame :epoch 4 :fan-out 1 :readers []}]})
   :intents
   (envelope :intents
             {:frames [:app/main tool-frame]
              :intents [{:frames [:app/main] :dispatch-id 1
                         :event-id :todo/toggle :arg-count 0 :sub-ids []}
                        {:frames [tool-frame] :dispatch-id 2
                         :event-id :rf.xray.fresco/set-view
                         :arg-count 1 :sub-ids []}
                        {:frames [:app/main tool-frame] :dispatch-id 3
                         :event-id :todo/spans :arg-count 0 :sub-ids []}
                        {:frames [] :dispatch-id 4
                         :event-id :todo/frameless :arg-count 0 :sub-ids []}]})
   :explain-render
   (envelope :explain-render
             {:explanations [{:boundary boundary-a :views todo-row-views
                              :frame :app/main :instances 1
                              :snapshot 1 :peak-epoch 4
                              :latest-reads [] :candidates [] :loss nil}
                             {:boundary boundary-b :views :unknown
                              :frame tool-frame :instances 1
                              :snapshot 1 :peak-epoch 4
                              :latest-reads [] :candidates [] :loss nil}]})})

(deftest the-own-frame-set-is-the-singleton-PLUS-the-shell-being-looked-from
  ;; `:rf/xray` is reserved, so it stays in the set whichever shell is
  ;; looking; the second member is the shell looked from.
  (is (= #{:rf/xray custom-shell-frame} (hh/own-frames custom-shell-frame))))

(deftest xray-own-frame-rows-are-dropped-from-every-roster
  (testing "a boundary seated in an Xray shell's frame is the tool, not the
            application, and none of the four rosters may carry it"
    (doseq [tool-frame [:rf/xray custom-shell-frame]]
      (testing (str "shell frame " tool-frame)
        (let [e (hh/without-own-frame (mixed-evidence tool-frame) (hh/own-frames tool-frame))]
          (is (= [:app/main]
                 (mapv :frame (hh/mounted-rows (:mounted-boundaries e))))
              "the application's boundary survives the census and the shell's
               own does not")
          (is (= [:app/main]
                 (mapv :frame-id (hh/attribution-rows (:read-attribution e))))
              "an edge the shell's own boundary holds is not an application read")
          (is (= [:app/main]
                 (mapv :frame (hh/explain-rows (:explain-render e))))
              "and the Why view drops it too — the four are filtered TOGETHER,
               so a boundary absent from the census cannot still appear in a
               view derived from the same one-turn read")

          (testing "intents drop on EVERY frame, not ANY"
            (is (= [:todo/toggle :todo/spans :todo/frameless]
                   (mapv :event-id (hh/intent-rows (:intents e))))
                "the tool-only dispatch goes; the MIXED one stays, because a
                 dispatch that reached an application frame is the user's
                 whatever else it also touched; and the frameless one stays,
                 because an empty frame set is an absence and not a claim
                 about Xray")))))))

(deftest xray-read-free-chrome-is-not-application-evidence
  ;; A read-free boundary has the key `[]` and the frame `:unknown`, so its
  ;; declared views are what say whose it is.
  (let [chrome   [{:view   "day8.re-frame2-xray.shell/dynamic-chrome"
                   :source {:ns 'day8.re-frame2-xray.shell :file "shell.cljs"
                            :line 2989 :column 1}}]
        layout   [{:view "app.views/layout" :source :unknown}]
        rows     (fn rows
                   ([views] (rows views :unknown))
                   ([views frame]
                    (hh/mounted-rows
                      (:mounted-boundaries
                        (hh/without-own-frame
                          {:mounted-boundaries
                           (envelope :mounted-boundaries
                                     {:boundaries [(first (:boundaries mounted))
                                                   {:boundary {:parent nil :key []} :views views
                                                    :instances 1 :read-orders 1
                                                    :frame frame :reads []}]})}
                          (hh/own-frames :rf/xray))))))]
    (testing "where the frame resolves it is the answer, whatever namespace declared the view"
      (is (= [:app/main :app/main] (mapv :frame (rows chrome :app/main)))))
    (testing "Xray's chrome alone is dropped"
      (is (= [:app/main] (mapv :frame (rows chrome)))))
    (testing "an application's read-free view keeps the row, without Xray's"
      (is (= ["app.views/layout"] (mapv :view (:views (second (rows (into layout chrome))))))))))

(deftest the-own-frame-drop-keeps-an-unresolved-row-and-an-absent-envelope
  (testing "`unknown` is not Xray's frame — the filter is set membership over
            resolved frame ids and nothing cleverer"
    (doseq [tool-frame [:rf/xray custom-shell-frame]]
      (is (= [:app/main :unknown]
             (mapv :frame (hh/mounted-rows
                            (:mounted-boundaries
                              (hh/without-own-frame {:mounted-boundaries mounted}
                                                    (hh/own-frames tool-frame)))))))))
  (testing "an absent envelope passes through untouched — a missing door is not an empty roster"
    (is (nil? (:mounted-boundaries
                (hh/without-own-frame {:mounted-boundaries nil}
                                      (hh/own-frames custom-shell-frame)))))))
