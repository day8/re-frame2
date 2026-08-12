(ns day8.re-frame2-xray.panels.hicasso-advisor-cljs-test
  "The advisor's algebra — ranking, classification, and the refusal
  (rf2-hic-037).

  Pure data → data, so this namespace runs under the JVM target beside the
  CLJS one. The claims that need a live runtime are in
  `hicasso_causal_cljs_test`.

  ## The load-bearing row is a PAIR

  [[the-advisor-never-recommends-a-native-route-from-this-evidence]] asserts
  a property over every classification the classifier can emit, and it
  would be equally true of a `recommend` that answered `:measure-first`
  unconditionally — which is a stub, not a finding.
  [[the-native-ladder-is-real-and-the-refusal-is-about-the-EVIDENCE]] is
  its non-vacuity control: handed an owner the door cannot measure, the
  same table returns rungs 3, 4 and 5 with their steps. Read together they
  say the thing that matters — the refusal comes from the instruments, not
  from a missing arm."
  (:require [clojure.string :as string]
            #?(:cljs [cljs.reader])
            #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [day8.re-frame2-xray.panels.hicasso-advisor :as advisor]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; Fixtures — envelopes and windows in the producers' own shapes
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

(defn- attribution-envelope
  [edges]
  {:schema    hh/consumed-evidence-schema
   :producer  hh/consumed-producer
   :read      :read-attribution
   :scope     :read-edges
   :basis     :observation
   :complete? true
   :loss      nil
   :edges     edges})

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
  "One `:rf.sub/run` trace event, in Spec 009's own shape."
  ([sub-id ms] (sub-ev sub-id ms :rf.sub/run))
  ([sub-id ms op]
   {:op-type   :rf.sub
    :operation op
    :tags      (cond-> {:rf.sub/id sub-id}
                 (number? ms) (assoc :rf.sub/elapsed-ms ms))}))

(defn- bundle
  [dispatch-id event-id subs]
  {:dispatch-id dispatch-id
   :event       [event-id]
   :subs        subs})

;; ---------------------------------------------------------------------------
;; The timing digest
;; ---------------------------------------------------------------------------

(deftest an-untimed-recompute-is-UNKNOWN-and-never-zero
  ;; Spec 009 puts `:rf.sub/elapsed-ms` on the reactive recompute path and
  ;; NOT on the pure `compute-sub` form. A fold that read the missing tag
  ;; as zero would report a subscription it never timed as instantaneous —
  ;; and the reader would then rank it LAST, which is the loudest possible
  ;; version of the `unknown is never zero` mistake.
  (let [t (advisor/sub-timing {:app/main [(bundle 1 :e [(sub-ev :slow nil)
                                                        (sub-ev :slow nil)])]})
        e (get-in t [:by-read [:app/main :slow]])]
    (is (= 2 (:runs e)) "the runs really happened and are counted")
    (is (nil? (:timed-runs e)) "none of them carried a duration")
    (is (= 2 (:untimed-runs e)))
    (is (nil? (:elapsed-ms e)) "no duration was summed"))

  (testing "and the boundary's time axis states unknown, with the reason"
    (let [adv (advisor/advise
                {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :slow]])])}
                (advisor/sub-timing {:app/main [(bundle 1 :e [(sub-ev :slow nil)])]}))
          ax  (get-in (first (:rows adv)) [:axes :time])]
      (is (hh/unknown? (:ms ax)) "unknown, not 0.0")
      (is (= :uncorrelated (:reason (:loss ax)))
          (str "the recomputes are observed and it is the DURATION that joins "
               "to nothing — reporting a cap would send the reader to the "
               "retention knob, which is not the remedy")))))

(deftest a-memo-hit-is-counted-and-never-summed-as-work
  (let [t (advisor/sub-timing
            {:app/main [(bundle 1 :e [(sub-ev :a 2.0)
                                      (sub-ev :a nil :rf.sub/skip)
                                      (sub-ev :a nil :rf.sub/skip)])]})
        e (get-in t [:by-read [:app/main :a]])]
    (is (= 1 (:runs e)) "a skip is not a run")
    (is (= 2.0 (:elapsed-ms e)) "and contributes no time")
    (is (= 2 (:memo-hits e))
        (str "counted, because a read whose runs are mostly skips is "
             "well-placed and one that runs every time is not — the single "
             "most informative topology signal there is"))))

(deftest a-run-with-no-registration-id-is-UNCORRELATED-not-dropped
  ;; An untagged stream must not be able to read as an idle one.
  (let [t (advisor/sub-timing {:app/main [(bundle 1 :e [{:op-type :rf.sub
                                                         :operation :rf.sub/run
                                                         :tags {}}])]})]
    (is (= 1 (:unnamed-runs t)))
    (is (= :uncorrelated (:reason (:unnamed-loss t))))
    (is (= 1 (:dropped (:unnamed-loss t))))))

(deftest the-window-is-scoped-per-FRAME
  ;; Two frames are two applications. Summing frame B's clock into frame
  ;; A's ranking would make an idle boundary look hot because something
  ;; unrelated was busy next door — the same defect `explain-render`
  ;; scopes its leads against (audit #7789).
  (let [t (advisor/sub-timing {:app/a [(bundle 1 :e [(sub-ev :shared 5.0)])]
                               :app/b [(bundle 2 :e [(sub-ev :shared 50.0)])]})]
    (is (= 5.0  (get-in t [:by-read [:app/a :shared] :elapsed-ms])))
    (is (= 50.0 (get-in t [:by-read [:app/b :shared] :elapsed-ms])))
    (is (nil? (get-in t [:by-read [:shared]]))
        "there is no frame-free key to sum into")))

;; ---------------------------------------------------------------------------
;; Ranking — four axes in four units, and the order stated out loud
;; ---------------------------------------------------------------------------

(deftest the-top-three-match-a-hand-profile
  ;; The acceptance row. Three boundaries with deliberately different
  ;; profiles, ranked against a profile computed by hand from the window
  ;; below — not against whatever the implementation happens to answer.
  ;;
  ;;   :hot    reads [:app/main :heavy]  — 3 runs × 4.0 ms = 12.0 ms
  ;;   :middle reads [:app/main :medium] — 2 runs × 1.0 ms =  2.0 ms
  ;;   :cheap  reads [:app/main :light]  — 6 runs × 0.05ms =  0.3 ms
  ;;
  ;; So by time: hot, middle, cheap. By FREQUENCY the order inverts —
  ;; cheap runs twice as often as hot — which is why the roster has to say
  ;; which axis it sorted on.
  (let [windows {:app/main (concat (repeat 3 (bundle 1 :e [(sub-ev :heavy 4.0)]))
                                   (repeat 2 (bundle 2 :e [(sub-ev :medium 1.0)]))
                                   (repeat 6 (bundle 3 :e [(sub-ev :light 0.05)])))}
        adv     (advisor/advise
                  {:mounted-boundaries
                   (mounted-envelope [(boundary [[:app/main :light]])
                                      (boundary [[:app/main :heavy]])
                                      (boundary [[:app/main :medium]])])}
                  (advisor/sub-timing windows))
        ids     (mapv #(get-in % [:attributable :edges 0 :sub-id]) (:rows adv))]
    (is (= [:heavy :medium :light] ids)
        "ranked by attributable subscription time, highest first")
    (is (= [1 2 3] (mapv :rank (:rows adv))))
    (is (= :time (get-in adv [:ordered-by :axis])))

    (testing "and the frequency axis really does disagree, so the sort matters"
      (is (= [3 2 6] (mapv #(get-in % [:axes :frequency :runs]) (:rows adv)))
          (str "the cheapest boundary recomputes twice as often as the "
               "hottest — a roster sorted on the wrong axis would put it "
               "first and be defensible about it")))))

(deftest with-no-clock-the-roster-says-so-rather-than-implying-time
  (let [adv (advisor/advise
              {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :a]])
                                                      (boundary [[:app/main :b]])])}
              (advisor/sub-timing
                {:app/main [(bundle 1 :e [(sub-ev :a nil) (sub-ev :b nil) (sub-ev :b nil)])]}))]
    (is (= :frequency (get-in adv [:ordered-by :axis])))
    (is (string/includes? (get-in adv [:ordered-by :says]) "NOT by time"))
    (is (= [:b :a] (mapv #(get-in % [:attributable :edges 0 :sub-id]) (:rows adv)))
        "ordered by recompute count, the only axis that survived")
    (is (string/includes? (advisor/advice-summary adv) "NOT by time")
        "and the summary line carries it too — this is not a footnote")))

(deftest the-advice-envelope-is-never-complete
  ;; The roster covers every mounted boundary, but the QUESTION is *where
  ;; is the pressure*, and three of its five answers are outside this
  ;; door. A `:complete? true` here would be the schema's own fail-open
  ;; shape, written by the one function best placed to know better.
  (let [adv (advisor/advise
              {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :a]])])}
              (advisor/sub-timing {:app/main [(bundle 1 :e [(sub-ev :a 9.0)])]}))]
    (is (false? (:complete? adv)))
    (is (= :host-opaque (:reason (:loss adv))))
    (is (= advisor/advice-schema (:schema adv)))
    (is (= :re-frame2/xray (:producer adv))
        (str "Xray derived it and Hicasso did not — stamping the producer's "
             "schema would tell a reader Hicasso vouched for a ranking it has "
             "never seen"))))

;; ---------------------------------------------------------------------------
;; Classification — four outcomes, driven
;; ---------------------------------------------------------------------------

(defn- classify-with
  "Run the classifier over a window built to a named profile."
  [boundary-row windows]
  (let [adv (advisor/advise {:mounted-boundaries (mounted-envelope [boundary-row])}
                            (advisor/sub-timing windows))]
    (:class (first (:rows adv)))))

(deftest a-dominant-measured-subscription-is-CLASSIFIED-computation
  (let [c (classify-with (boundary [[:app/main :heavy] [:app/main :trivial]])
                         {:app/main [(bundle 1 :e [(sub-ev :heavy 8.0)
                                                   (sub-ev :trivial 0.1)])]})]
    (is (= :computation (:owner c)))
    (is (= :observation (:basis c)) "measured, not derived")
    (is (= :heavy (get-in c [:read :sub-id])))
    (is (nil? (:loss c)))))

(deftest a-spread-of-time-is-not-a-computation-finding
  ;; 55/45 across two reads. The remedy is the read SET, not either member
  ;; of it — so this must not be reported as a hot subscription.
  (let [c (classify-with (boundary [[:app/main :a] [:app/main :b]])
                         {:app/main (repeat 2 (bundle 1 :e [(sub-ev :a 5.5)
                                                            (sub-ev :b 4.5)]))})]
    (is (not= :computation (:owner c))
        (str "neither read holds " advisor/dominance " of the total, so naming "
             "one the owner would be a coin toss printed as a finding"))))

(deftest an-oscillating-read-set-is-a-topology-finding-with-no-clock-at-all
  (let [c (classify-with (boundary [[:app/main :a]] :read-orders 4)
                         {:app/main [(bundle 1 :e [(sub-ev :a nil)])]})]
    (is (= :read-topology (:owner c)))
    (is (= :derivation (:basis c)))
    (is (string/includes? (:says c) "oscillating"))))

(deftest repeated-recomputes-for-negligible-work-are-a-topology-finding
  (let [c (classify-with (boundary [[:app/main :a]])
                         {:app/main (repeat 5 (bundle 1 :e [(sub-ev :a 0.05)]))})]
    (is (= :read-topology (:owner c)))
    (is (= :derivation (:basis c)))
    (is (string/includes? (:says c) "bringing it back"))))

(deftest a-searched-window-that-explains-nothing-is-HOST-OPAQUE-not-CAPPED
  ;; The pair a naive advisor collapses. One remedy is free (raise the
  ;; retention knob); the other is a change of INSTRUMENT. A tool that
  ;; printed one sentence for both would send half its readers to the
  ;; wrong place.
  (let [searched (classify-with (boundary [[:app/main :a]])
                                {:app/main [(bundle 1 :e [(sub-ev :a 0.05)])]})
        capped   (classify-with (boundary [[:app/main :a]]) {:app/main []})]
    (is (= :unattributed (:owner searched)))
    (is (= :host-opaque (:basis searched)))
    (is (= :unattributed (:owner capped)))
    (is (= :cap (:basis capped)))
    (is (not= (:says searched) (:says capped))
        "two states, two sentences")
    (is (string/includes? (:says capped) "absence of evidence"))
    (is (string/includes? (:says searched) "lowering, React or layout"))))

;; ---------------------------------------------------------------------------
;; THE REFUSAL — and its non-vacuity control
;; ---------------------------------------------------------------------------

(def ^:private classification-space
  "Every classification [[advisor/classify]] can emit, driven from real
  windows rather than hand-built maps.

  Built from profiles rather than enumerated as literals, because the
  claim below is about what the CLASSIFIER produces: a hand-written list
  could silently stop covering an arm the classifier grew."
  (let [profiles
        [[(boundary [[:app/main :heavy]])
          {:app/main [(bundle 1 :e [(sub-ev :heavy 8.0)])]}]
         [(boundary [[:app/main :a]] :read-orders 4)
          {:app/main [(bundle 1 :e [(sub-ev :a nil)])]}]
         [(boundary [[:app/main :a]])
          {:app/main (repeat 5 (bundle 1 :e [(sub-ev :a 0.05)]))}]
         [(boundary [[:app/main :a]])
          {:app/main [(bundle 1 :e [(sub-ev :a 0.05)])]}]
         [(boundary [[:app/main :a]]) {:app/main []}]
         [(boundary []) {:app/main []}]
         [(boundary [[:app/main :a] [:app/b :c]])
          {:app/main [(bundle 1 :e [(sub-ev :a 0.4)])]
           :app/b    [(bundle 2 :e [(sub-ev :c 0.4)])]}]]]
    (mapv (fn [[b w]] (classify-with b w)) profiles)))

(deftest the-advisor-never-recommends-a-native-route-from-this-evidence
  ;; Spec SN §10: *it recommends native extraction only when it addresses
  ;; the measured owner.* Every native rung addresses lowering, hooks,
  ;; vendor behaviour or reconciliation, and this door observes none of
  ;; them — so the honest consequence is that no classification reachable
  ;; from this evidence selects one.
  ;;
  ;; A property over the classifier's whole output, not three fixtures.
  (is (seq classification-space) "the space must be non-empty to test anything")
  (doseq [c classification-space]
    (let [r (advisor/recommend c)]
      (is (false? (:native? r))
          (str "owner " (:owner c) "/" (:basis c) " selected " (:route r)
               ", a native route, from evidence that cannot say whether it "
               "helps"))
      (is (not (contains? #{:direct-element :native-island :native-screen} (:route r))))))

  (testing "and the owners the native rungs answer to are UNREACHABLE from here"
    (is (= #{:computation :read-topology :unattributed}
           (into #{} (map :owner) classification-space))
        (str "if the classifier ever emits :lowering, :react-work or "
             ":react-shaped, this door has grown an instrument it did not "
             "have — and the ladder will route to it"))))

(deftest the-native-ladder-is-real-and-the-refusal-is-about-the-EVIDENCE
  ;; THE NON-VACUITY CONTROL. The row above would be equally true of a
  ;; `recommend` that answered `:measure-first` unconditionally, which is
  ;; a stub. Hand the same table an owner the door cannot measure and it
  ;; answers the ladder — so the refusal is a statement about the
  ;; instruments, not about a missing arm.
  (doseq [[owner route rung] [[:lowering     :direct-element 3]
                              [:react-work   :native-island  4]
                              [:react-shaped :native-screen  5]]]
    (let [r (advisor/recommend {:owner owner :basis :observation :loss nil
                                :says "measured elsewhere"})]
      (is (= route (:route r)))
      (is (= rung (:rung r)))
      (is (true? (:native? r)))
      (is (seq (:working-loop r)) "with its steps attached")
      (is (nil? (:refusal r))))))

(deftest the-refusal-names-the-instrument-for-each-candidate
  ;; A refusal with a next step, not a shrug.
  (let [c (classify-with (boundary [[:app/main :a]])
                         {:app/main [(bundle 1 :e [(sub-ev :a 0.05)])]})
        r (advisor/recommend c)]
    (is (= :measure-first (:route r)))
    (is (= [:direct-element :native-island :native-screen]
           (get-in r [:refusal :refused])))
    (is (= [:lowering :react :layout]
           (mapv :class (get-in r [:refusal :next]))))
    (doseq [n (get-in r [:refusal :next])]
      (is (string? (:authority n)))
      (is (not (string/includes? (:authority n) "Xray"))
          (str "Xray must not be nominated as the authority for a class it "
               "does not measure")))))

(deftest a-computation-owner-is-routed-BELOW-the-view-substrate
  ;; The other half of the same rule. The measured owner here is the
  ;; subscription body, which runs below the boundary — so every native
  ;; route would move the markup and keep the cost.
  (let [c (classify-with (boundary [[:app/main :heavy]])
                         {:app/main [(bundle 1 :e [(sub-ev :heavy 8.0)])]})
        r (advisor/recommend c)]
    (is (= :narrow-the-subscription (:route r)))
    (is (false? (:native? r)))
    (is (string/includes? (:says r) "keep the cost"))))

(deftest a-topology-owner-is-routed-to-rung-2-and-no-further
  (let [c (classify-with (boundary [[:app/main :a]] :read-orders 3)
                         {:app/main [(bundle 1 :e [(sub-ev :a nil)])]})
        r (advisor/recommend c)]
    (is (= :tune-topology (:route r)))
    (is (= 2 (:rung r)))
    (is (false? (:native? r)))
    (is (string/includes? (:says r) "Do not split per element mechanically")
        "the ladder's own warning rides with the advice")))

(deftest a-capped-boundary-is-told-to-widen-the-window-not-to-measure-react
  (let [r (advisor/recommend (classify-with (boundary [[:app/main :a]]) {:app/main []}))]
    (is (= :measure-first (:route r)))
    (is (= :cap (get-in r [:refusal :reason])))
    (is (string/includes? (:says r) "events-retained"))
    (is (not (string/includes? (:says r) "DevTools"))
        (str "a capped window is a knob setting, and sending its reader to "
             "another tool would waste the free remedy"))))

;; ---------------------------------------------------------------------------
;; Fan-out and the shape of the roster
;; ---------------------------------------------------------------------------

(deftest fan-out-is-summed-across-the-cells-a-boundary-reads
  (let [adv (advisor/advise
              {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :a] [:app/main :b]])])
               :read-attribution   (attribution-envelope
                                     [{:sub-id :a :query [:a] :frame-id :app/main
                                       :epoch 1 :fan-out 3 :readers []}
                                      {:sub-id :b :query [:b] :frame-id :app/main
                                       :epoch 1 :fan-out 4 :readers []}
                                      {:sub-id :a :query [:a] :frame-id :other
                                       :epoch 1 :fan-out 99 :readers []}])}
              (advisor/sub-timing {}))]
    (is (= 7 (get-in (first (:rows adv)) [:axes :fan-out :total]))
        "3 + 4 — and never the 99, which is another frame's cell")))

(deftest the-advisor-ADVISES-and-carries-nothing-executable
  ;; The bead's own words: it never rewrites code and never switches
  ;; semantics. Structurally that is a property of the VALUE — advice made
  ;; of readable EDN cannot carry a patch, a thunk or a rewrite, and it
  ;; round-trips through the reader unchanged.
  (let [envelopes {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :a]])])}
        timing    (advisor/sub-timing {:app/main [(bundle 1 :e [(sub-ev :a 4.0)])]})
        adv       (advisor/advise envelopes timing)]
    (is (= adv #?(:clj (read-string (pr-str adv))
                  :cljs (cljs.reader/read-string (pr-str adv))))
        (str "the whole advice round-trips through the reader — so it is "
             "data a reader can inspect, print and diff, and not a closure "
             "that could do something"))
    (testing "and it is deterministic over one turn's evidence"
      (is (= adv (advisor/advise envelopes timing))))
    (testing "and reading it does not disturb what it read"
      (is (= envelopes {:mounted-boundaries (mounted-envelope [(boundary [[:app/main :a]])])})))))

(deftest an-absent-door-yields-an-empty-roster-and-still-states-what-is-unmeasured
  (let [adv (advisor/advise {:mounted-boundaries nil} (advisor/sub-timing {}))]
    (is (= [] (:rows adv)))
    (is (= 3 (count (:unmeasured adv)))
        (str "the three unmeasured classes are stated whether or not there is "
             "a roster — a reader who saw them only when the roster was empty "
             "would read a populated one as complete"))))
