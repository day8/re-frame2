(ns re-frame2-pair.pure-test
  "Node-test coverage for `re-frame2-pair.pure` — the genuinely-pure core the
   SHIPPED preload (`re-frame2-pair.runtime`) delegates to. These tests exercise
   the EXACT code the runtime calls (runtime `:require`s this ns and threads the
   live gates into these fns), so they replace the retired Babashka mirrors that
   re-derived the algorithms and could silently drift from the shipped preload
   (rf2-etsj8p).

   Run: `npm run test:pure` from tests/fixture/
        (`shadow-cljs compile pure-test && node target/pure-test.js`)."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame2-pair.pure :as pure]))

;; ===========================================================================
;; Multi-frame operating-frame resolution (multi-frame ambiguity matrix)
;; ===========================================================================

(deftest reserved-tool-frame-predicate
  (testing ":rf/* tool frames are reserved; :rf/default and user frames are not"
    (is (true?  (pure/reserved-tool-frame? :rf/xray)))
    (is (true?  (pure/reserved-tool-frame? :rf/ssr)))
    (is (false? (pure/reserved-tool-frame? :rf/default))
        ":rf/default is an ordinary app frame id, not a tool frame")
    (is (false? (pure/reserved-tool-frame? :stories)))
    (is (false? (pure/reserved-tool-frame? :rf-default))
        "un-namespaced lookalike is a user frame")
    (is (false? (pure/reserved-tool-frame? "rf/xray"))
        "a non-keyword id is never a tool frame")))

(deftest app-frame-ids-excludes-tool-frames-retains-default
  (is (= [:rf/default] (pure/app-frame-ids [:rf/default :rf/xray]))
      ":rf/xray excluded; :rf/default retained")
  (is (= [:my-app] (pure/app-frame-ids [:my-app :rf/xray :rf/ssr])))
  (is (= [:rf/default :stories] (pure/app-frame-ids [:rf/default :stories :rf/xray]))
      "order mirrors the input frame-ids")
  (is (= [] (pure/app-frame-ids [:rf/xray]))
      "only tool frames -> no app frames"))

(deftest resolve-operating-frame-tiers
  (testing "override -> selected -> sole app frame -> nil"
    (is (nil? (pure/resolve-operating-frame nil nil [])))
    (is (= :rf/default (pure/resolve-operating-frame nil nil [:rf/default]))
        "the sole app frame auto-resolves")
    (is (nil? (pure/resolve-operating-frame nil nil [:rf/default :stories]))
        "two app frames -> ambiguous (nil), never a silent :rf/default guess")
    (is (= :stories (pure/resolve-operating-frame nil :stories [:rf/default :stories]))
        "session pin resolves ambiguity")
    (is (= :rf/default (pure/resolve-operating-frame :rf/default :stories [:rf/default :stories]))
        "explicit override beats the session pin")
    (is (= :ghost (pure/resolve-operating-frame :ghost nil []))
        "an explicit override returns even if unregistered (resolver is naive)")))

(deftest ambiguous-frame-envelope-shape
  (let [env (pure/ambiguous-frame-envelope :dispatch [:rf/default :stories] nil
                                           {:event [:cart/apply-coupon "SPRING25"]})]
    (is (false? (:ok? env)))
    (is (= :ambiguous-frame (:reason env)) ":reason is the sole machine discriminator")
    (is (= :dispatch (:operation env)))
    (is (= [:rf/default :stories] (:available-frames env)))
    (is (nil? (:selected-frame env)))
    (is (= [:cart/apply-coupon "SPRING25"] (:event env)) "extra context rides through")
    (is (re-find #"select-frame!|frame" (:hint env)) "hint names the fix")))

;; ===========================================================================
;; Call-time id validation (read-sub validation matrix)
;; ===========================================================================

(deftest levenshtein-edit-distance
  (is (= 0 (pure/levenshtein "abc" "abc")))
  (is (= 1 (pure/levenshtein "abc" "abd")))
  (is (= 3 (pure/levenshtein "" "abc")))
  (is (= 3 (pure/levenshtein "abc" ""))))

(deftest nearest-ids-ranks-by-edit-distance
  (let [known [:current-user :cart/total :cart/items]]
    (is (some #(= :current-user %) (pure/nearest-ids :current-userr known))
        "a typo ranks its real target among the nearest")
    (is (>= 3 (count (pure/nearest-ids :x known))) "capped at 3 by default")
    (is (= 1 (count (pure/nearest-ids :x known 1))) "n bounds the result")))

(deftest validate-against-known-hit-and-miss
  (testing "a registered id validates"
    (is (= {:ok? true :kind :sub :id :current-user}
           (pure/validate-against-known :sub :current-user [:current-user :cart/total]))))
  (testing "an unknown id fails with :unknown-id + :nearest (no silent swallow)"
    (let [r (pure/validate-against-known :sub :current-userr [:current-user :cart/total])]
      (is (false? (:ok? r)))
      (is (= :unknown-id (:reason r)))
      (is (some #(= :current-user %) (:nearest r)))
      (is (= 2 (:known-count r)))
      (is (re-find #"did you mean" (:hint r)))))
  (testing "an empty registrar reports :known-count 0 with a distinct hint"
    (let [r (pure/validate-against-known :sub :anything [])]
      (is (false? (:ok? r)))
      (is (= 0 (:known-count r)))
      (is (re-find #"nothing is registered" (:hint r))))))

;; ===========================================================================
;; Streaming subscriptions — routing, budget/eviction, drain, privacy
;; ===========================================================================

(deftest topic-sugar-base-filters
  (is (= {:op-type :rf.fx} (pure/compose-trace-filter :fx nil)))
  (is (= {:op-type :error} (pure/compose-trace-filter :error nil)))
  (is (= {} (pure/compose-trace-filter :trace nil)))
  (is (= {:op-type :error :event-id :user/login}
         (pure/compose-trace-filter :fx {:op-type :error :event-id :user/login}))
      "the user filter overrides the topic base on conflict"))

(deftest trace-matches-axes
  (testing "event-id axis"
    (is (pure/trace-matches? {:event-id :user/login}
                             {:tags {:rf.trace/event-id :user/login}}))
    (is (not (pure/trace-matches? {:event-id :user/login}
                                  {:tags {:rf.trace/event-id :user/logout}}))))
  (testing "severity reads :op-type"
    (is (pure/trace-matches? {:severity :error} {:op-type :error}))
    (is (not (pure/trace-matches? {:severity :error} {:op-type :info}))))
  (testing ":between window (inclusive)"
    (is (pure/trace-matches? {:between [100 200]} {:time 150}))
    (is (pure/trace-matches? {:between [100 200]} {:time 100}))
    (is (not (pure/trace-matches? {:between [100 200]} {:time 99}))))
  (testing "frame axis reads the canonical [:tags :frame] via trace-event-frame"
    (is (pure/trace-matches? {:frame :stories} {:tags {:frame :stories}}))
    (is (not (pure/trace-matches? {:frame :stories} {:frame :stories}))
        "a stray top-level :frame is not the raw-event shape")))

(deftest frameless-event-predicate
  (is (pure/frameless-event? {:tags {:registry-kind :event}}))
  (is (not (pure/frameless-event? {:tags {:rf.trace/dispatch-id 1}}))))

;; ---------------------------------------------------------------------------
;; `event-byte-size` — the ruler the streaming byte budget is enforced against
;; ---------------------------------------------------------------------------
;;
;; DISCRIMINATING FIXTURES (rf2-2rtt6.135). All three `pr-str` to the SAME 47
;; UTF-16 code units and to THREE different UTF-8 byte lengths, so the
;; defective `(count (pr-str ev))` answers 47 for all three while the honest
;; ruler separates them. `astral-40` additionally separates code POINTS (20)
;; from code UNITS (40). Built from `fromCodePoint` so this source file stays
;; pure ASCII and no re-encoding can mangle the surrogate pair.

(def ^:private em-dash   (.fromCodePoint js/String 0x2014)) ;; 1 unit / 1 point / 3 bytes
(def ^:private g-clef    (.fromCodePoint js/String 0x1D11E)) ;; 2 units / 1 point / 4 bytes

(def ^:private ascii-40  (apply str (repeat 40 "x")))
(def ^:private emdash-40 (apply str (repeat 40 em-dash)))
(def ^:private astral-40 (apply str (repeat 20 g-clef)))

(defn- ev [s] {:s s})

(deftest event-byte-size-counts-utf8-bytes-not-code-units
  (testing "the fixtures are genuinely indistinguishable to `count`"
    (is (= 40 (count ascii-40) (count emdash-40) (count astral-40))
        "one code-unit length across all three")
    (is (= 20 (.-length (js/Array.from astral-40)))
        "astral-40 is 20 code POINTS in 40 code UNITS")
    (is (= 47 (count (pr-str (ev ascii-40)))
             (count (pr-str (ev emdash-40)))
             (count (pr-str (ev astral-40))))
        "the defective (count (pr-str ev)) answers 47 for all three"))
  (testing "UTF-8 bytes separate them"
    ;; ASCII stays green under an under-count -- the failure is specific to
    ;; non-ASCII, which is what makes this a discriminator and not a smoke alarm.
    (is (= 47 (pure/event-byte-size (ev ascii-40)))  "ASCII: bytes == code units")
    (is (= 127 (pure/event-byte-size (ev emdash-40))) "40 x 3-byte em-dash + 7 ASCII")
    (is (= 87 (pure/event-byte-size (ev astral-40)))  "20 x 4-byte astral + 7 ASCII"))
  (testing "pr-str failure still falls back to 0 rather than blowing up enqueue"
    (is (= 0 (pure/event-byte-size (reify IPrintWithWriter
                                     (-pr-writer [_ _ _] (throw (js/Error. "nope")))))))))

(deftest byte-budget-evicts-sooner-on-multi-byte-payload
  ;; The behaviour change rf2-2rtt6.135 accepts, measured. One cap, one
  ;; code-unit length, two payloads: the honest ruler holds 6 ASCII events and
  ;; only 2 em-dash events. Under the old ruler BOTH held 6 -- the queue was
  ;; sitting on 762 true bytes against a 300-byte budget.
  (let [cap  300
        fill (fn [payload]
               (reduce #(pure/enqueue %1 "s" %2)
                       {"s" (pure/make-subscription
                              "s" {:topic :trace
                                   :max-buffered-events 1000
                                   :max-buffered-bytes  cap}
                              0)}
                       (repeat 20 (ev payload))))]
    (is (= 6 (count (get-in (fill ascii-40) ["s" :queue])))
        "6 x 47 bytes = 282, inside the 300-byte cap")
    (is (= 2 (count (get-in (fill emdash-40) ["s" :queue])))
        "2 x 127 bytes = 254; a 3rd would be 381 -- evicts at a THIRD the depth")
    (is (= :max-buffered-bytes (get-in (fill emdash-40) ["s" :overflow-reason])))))

(defn- trace-sub
  "A trace-topic subscription map for `sub-id` with the given budgets."
  [sub-id opts]
  (pure/make-subscription sub-id (merge {:topic :trace} opts) 0))

(deftest make-subscription-shape-and-defaults
  (let [sub (trace-sub "s" {})]
    (is (= :trace (:topic sub)))
    (is (= [] (:queue sub)))
    (is (= 0 (:queue-bytes sub)))
    (is (= pure/default-max-buffered-events (:max-buffered-events sub)))
    (is (= pure/default-max-buffered-bytes  (:max-buffered-bytes sub)))
    (is (= {:op-type :rf.fx} (:compiled-filter (pure/make-subscription "f" {:topic :fx} 0)))
        "the compiled filter carries the topic base-filter")))

(deftest dispatch-trace-routes-by-filter-and-frameless-gate
  (let [subs {"s1" (trace-sub "s1" {:filter {:op-type :error}})}
        ev1 {:op-type :error :tags {:rf.trace/event-id :user/login :rf.trace/dispatch-id 1}}
        ev2 {:op-type :info  :tags {:rf.trace/event-id :user/login :rf.trace/dispatch-id 1}}
        ev3 {:op-type :error :tags {:rf.trace/event-id :user/logout :rf.trace/dispatch-id 2}}
        subs' (-> subs
                  (pure/dispatch-trace-to-subs ev1)
                  (pure/dispatch-trace-to-subs ev2)
                  (pure/dispatch-trace-to-subs ev3))]
    (is (= [ev1 ev3] (get-in subs' ["s1" :queue]))
        "only the :op-type :error events land; :info filtered out")))

(deftest event-bundle-topics-reject-frameless-frameless-topic-accepts-them
  (let [cascade  {:op-type :rf.event :tags {:rf.trace/dispatch-id 1}}
        frameless {:op-type :rf.registry :tags {:registry-kind :event}}]
    (testing "cascade-bundle topic ignores frameless events"
      (let [subs (-> {"t" (trace-sub "t" {})}
                     (pure/dispatch-trace-to-subs cascade)
                     (pure/dispatch-trace-to-subs frameless))]
        (is (= [cascade] (get-in subs ["t" :queue])))))
    (testing ":frameless topic accepts only frameless events"
      (let [subs (-> {"f" (pure/make-subscription "f" {:topic :frameless} 0)}
                     (pure/dispatch-trace-to-subs cascade)
                     (pure/dispatch-trace-to-subs frameless))]
        (is (= [frameless] (get-in subs ["f" :queue])))))))

(deftest overflow-count-budget-drops-oldest-reports-events-reason
  (let [subs (reduce #(pure/dispatch-trace-to-subs %1 %2)
                     {"s" (trace-sub "s" {:max-buffered-events 2 :max-buffered-bytes 100000000})}
                     (map #(hash-map :id % :tags {:rf.trace/dispatch-id %}) (range 1 5)))]
    (is (= 2 (count (get-in subs ["s" :queue]))) "queue trimmed to the count cap")
    (is (= [{:id 3 :tags {:rf.trace/dispatch-id 3}}
            {:id 4 :tags {:rf.trace/dispatch-id 4}}]
           (get-in subs ["s" :queue])) "newest survive; oldest evicted")
    (is (= 2 (get-in subs ["s" :dropped-events])))
    (is (= :max-buffered-events (get-in subs ["s" :overflow-reason])))))

(deftest overflow-bytes-budget-drops-oldest-reports-bytes-reason
  (let [fat (apply str (repeat 300 "x"))
        e1  {:id 1 :payload fat :tags {:rf.trace/dispatch-id 1}}
        cap (int (* (pure/event-byte-size e1) 1.2))
        subs (reduce #(pure/dispatch-trace-to-subs %1 %2)
                     {"s" (trace-sub "s" {:max-buffered-events 1000 :max-buffered-bytes cap})}
                     (map #(hash-map :id % :payload fat :tags {:rf.trace/dispatch-id %})
                          (range 1 4)))]
    (is (= 1 (count (get-in subs ["s" :queue]))) "byte cap fits one fat event")
    (is (pos? (get-in subs ["s" :dropped-events])))
    (is (= :max-buffered-bytes (get-in subs ["s" :overflow-reason])))))

(deftest drain-returns-events-and-resets
  (let [subs (-> {"s" (trace-sub "s" {})}
                 (pure/dispatch-trace-to-subs {:op-type :info :tags {:rf.trace/dispatch-id 1}})
                 (pure/dispatch-trace-to-subs {:op-type :info :tags {:rf.trace/dispatch-id 2}}))
        [env subs'] (pure/drain subs "s")]
    (is (false? (:gone? env)))
    (is (= 2 (count (:events env))))
    (is (= :trace (:topic env)) "the drain envelope carries the topic for bundle projection")
    (is (empty? (get-in subs' ["s" :queue])) "the queue resets on drain")
    (let [[env2 _] (pure/drain subs' "s")]
      (is (empty? (:events env2)) "a second drain is empty"))))

(deftest drain-unknown-sub-is-gone
  (let [[env subs'] (pure/drain {} "phantom")]
    (is (true? (:gone? env)))
    (is (= [] (:events env)))
    (is (= {} subs'))))

(deftest unsubscribe-removes-and-reports-existence
  (let [[r1 subs1] (pure/unsubscribe {"s" (trace-sub "s" {})} "s")]
    (is (true? (:existed? r1)))
    (is (not (contains? subs1 "s")))
    (let [[r2 subs2] (pure/unsubscribe {} "phantom")]
      (is (false? (:existed? r2)))
      (is (= {} subs2)))))

(deftest epoch-topic-routing
  (let [subs {"e" (pure/make-subscription "e" {:topic :epoch :filter {:event-id :cart/add}} 0)
              "t" (trace-sub "t" {})}
        subs' (-> subs
                  (pure/dispatch-trace-to-subs {:op-type :info :tags {:rf.trace/dispatch-id 1}})
                  (pure/dispatch-epoch-to-subs pure/epoch-matches? {:event-id :cart/add :effects []}))]
    (is (= 1 (count (get-in subs' ["t" :queue]))) "trace event -> trace sub only")
    (is (= 1 (count (get-in subs' ["e" :queue]))) "epoch record -> epoch sub only")))

(deftest streaming-drop-privacy-posture
  (testing "default posture drops :sensitive? true events"
    (is (true?  (pure/streaming-drop? false {:sensitive? true})))
    (is (false? (pure/streaming-drop? false {:sensitive? false})))
    (is (false? (pure/streaming-drop? false {})) "absent flag is not sensitive"))
  (testing "opt-in lets sensitive events through"
    (is (false? (pure/streaming-drop? true {:sensitive? true})))))

;; ===========================================================================
;; Epoch timing + matcher (streaming timing matrix)
;; ===========================================================================

(deftest epoch-elapsed-ms-spans-run-start-to-last-run-end
  (let [run (fn [op t] {:op-type :rf.event :operation op :time t})]
    (is (= 150 (pure/epoch-elapsed-ms
                {:trace-events [(run :rf.event/run-start 1000) (run :rf.event/run-end 1150)]})))
    (is (nil? (pure/epoch-elapsed-ms {:trace-events [(run :rf.event/run-end 1000)]}))
        "no run-start -> nil")
    (is (nil? (pure/epoch-elapsed-ms {:trace-events [(run :rf.event/run-start 1000)]}))
        "no run-end -> nil")
    (testing "spans FIRST run-start to LAST run-end across nested dispatches"
      (is (= 300 (pure/epoch-elapsed-ms
                  {:trace-events [(run :rf.event/run-start 1000) (run :rf.event/run-end 1050)
                                  (run :rf.event/run-start 1060) (run :rf.event/run-end 1300)]}))))))

(deftest parse-timing-pred-number-and-string-forms
  (is ((pure/parse-timing-pred 100) 100) "number N is sugar for >= N")
  (is (not ((pure/parse-timing-pred 100) 50)))
  (is ((pure/parse-timing-pred ">100") 101))
  (is (not ((pure/parse-timing-pred ">100") 100)))
  (is ((pure/parse-timing-pred "<=50") 50))
  (is (not ((pure/parse-timing-pred "<=50") 51)))
  (is (nil? (pure/parse-timing-pred "not-a-number")) "unparseable -> nil (predicate absent)"))

(deftest epoch-matches-composes-keys
  (let [slow {:event-id :cart/add
              :trace-events [{:op-type :rf.event :operation :rf.event/run-start :time 1000}
                             {:op-type :rf.event :operation :rf.event/run-end   :time 1150}]}]
    (is (pure/epoch-matches? {:event-id :cart/add} slow))
    (is (not (pure/epoch-matches? {:event-id :cart/remove} slow)))
    (is (pure/epoch-matches? {:effects :http} {:effects [{:fx-id :http} {:fx-id :db}]}))
    (is (pure/epoch-matches? {:event-id-prefix :cart} slow) "prefix via str on both sides")
    (is (pure/epoch-matches? {:timing-ms 100} slow) "slow epoch clears >=100")
    (is (not (pure/epoch-matches? {:timing-ms ">100"}
                                  {:event-id :x :trace-events []}))
        "an epoch with no timing bracket does not clear a numeric threshold")
    (is (pure/epoch-matches? {:event-id :cart/add :timing-ms 100} slow)
        "keys AND-compose")))

;; ===========================================================================
;; Pair-epoch attribution (queued-correct, frame-qualified, bounded)
;; ===========================================================================
;;
;; These pins exercise the EXACT logic `re-frame2-pair.runtime`'s assembled-
;; epoch listener (`attribute-pair-epoch!`) and `last-pair-epoch` delegate to.
;; They demonstrate the three defects the old bare-id / sync-only attribution
;; carried (rf2-3fc89f.39): a queued pair epoch never attributed, a bare id
;; colliding across frames, and unbounded growth.

(defn- dispatched-epoch
  "An assembled epoch record carrying the `:rf.event/dispatched` origin trace
   the listener classifies on."
  [frame epoch-id origin]
  {:frame frame :epoch-id epoch-id
   :trace-events [{:operation :rf.event/dispatched
                   :tags {:rf.event/origin origin}}]})

(deftest origin-matches-reads-the-dispatched-trace
  (let [pair (:trace-events (dispatched-epoch :rf/default "ep-1" :pair))
        app  (:trace-events (dispatched-epoch :rf/default "ep-1" :app))]
    (is (true?  (pure/origin-matches? :pair pair)))
    (is (false? (pure/origin-matches? :pair app)) "an :app cascade is not pair-origin")
    (is (false? (pure/origin-matches? :pair []))  "no dispatched trace -> false")
    (is (false? (pure/origin-matches? :pair nil)))
    (is (false? (pure/origin-matches? :pair
                  [{:operation :rf.event/run-start :tags {:rf.event/origin :pair}}]))
        "origin rides on :rf.event/dispatched, not an arbitrary trace op")))

(deftest pair-origin-predicate-classifies-records
  (is (true?  (pure/pair-origin? (dispatched-epoch :rf/default "ep-1" :pair))))
  (is (false? (pure/pair-origin? (dispatched-epoch :rf/default "ep-1" :app)))
      "a non-pair epoch is never pair-origin, even for an identical event vector")
  (is (false? (pure/pair-origin? {:frame :rf/default :epoch-id "ep-1"}))
      "a trace-elided record does not RE-classify as pair (attribution is remembered, not re-derived)"))

(deftest epoch-matches-origin-axis-routes-through-origin-matches
  (let [rec {:frame :rf/default
             :trace-events (:trace-events (dispatched-epoch :rf/default "ep-1" :pair))}]
    (is (pure/epoch-matches? {:origin :pair} rec))
    (is (not (pure/epoch-matches? {:origin :app} rec)))))

(deftest attribute-pair-epoch-attributes-queued-and-frame-qualifies
  (testing "a queued pair epoch is attributed when it settles at the listener"
    (let [store (pure/attribute-pair-epoch {} (dispatched-epoch :A "ep-1" :pair)
                                           #{:A} #{"ep-1"})]
      (is (= {:A #{"ep-1"}} store))
      (is (= "ep-1" (:epoch-id (pure/pick-pair-epoch (get store :A)
                                                     [(dispatched-epoch :A "ep-1" :pair)])))
          "last-pair-epoch surfaces the queued record")))
  (testing "a non-pair epoch is not attributed"
    (is (= {} (pure/attribute-pair-epoch {} (dispatched-epoch :A "ep-1" :app)
                                         #{:A} #{"ep-1"}))))
  (testing "two frames sharing an epoch-id stay isolated"
    ;; frame A dispatches pair epoch "ep-7"; frame B has its own non-pair "ep-7"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "ep-7" :pair) #{:A :B} #{"ep-7"})
                    (pure/attribute-pair-epoch (dispatched-epoch :B "ep-7" :app) #{:A :B} #{"ep-7"}))]
      (is (= #{"ep-7"} (get store :A)))
      (is (nil? (get store :B)) "frame B never inherits A's attribution")
      (is (nil? (pure/pick-pair-epoch (get store :B #{})
                                      [(dispatched-epoch :B "ep-7" :app)]))
          "querying frame B never matches a pair dispatch from frame A"))))

(deftest attribute-pair-epoch-is-bounded-and-cleans-up
  (testing "bounded to the live ring — rolled-over ids are pruned"
    (let [depth 8
          store (reduce (fn [s i]
                          (let [ring-ids (into #{} (map #(str "ep-" %))
                                              (range (max 0 (- i (dec depth))) (inc i)))]
                            (pure/attribute-pair-epoch s (dispatched-epoch :A (str "ep-" i) :pair)
                                                       #{:A} ring-ids)))
                        {}
                        (range 1000))]
      (is (= depth (count (get store :A)))
          "the attribution set never exceeds the live ring depth across 1000 dispatches")))
  (testing "destroyed-frame state is removed"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-1" :pair) #{:A :Z} #{"a-1"})
                    (pure/attribute-pair-epoch (dispatched-epoch :Z "z-1" :pair) #{:A :Z} #{"z-1"}))]
      (is (= #{:A :Z} (set (keys store))))
      ;; :Z is destroyed; the next settle on :A drops :Z's entry.
      (let [store' (pure/attribute-pair-epoch store (dispatched-epoch :A "a-2" :pair)
                                              #{:A} #{"a-1" "a-2"})]
        (is (= #{:A} (set (keys store'))) "unregistered frame :Z is pruned"))))
  (testing "a frame whose set empties is dropped, never left as an empty entry"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-1" :pair) #{:A} #{"a-1"})
                    ;; a-1 rolls out of the ring; a NON-pair a-2 settles -> set empties
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-2" :app) #{:A} #{"a-2"}))]
      (is (not (contains? store :A)) "no empty #{} entry retained"))))

(deftest pick-pair-epoch-newest-first-and-survives-elision
  (let [ring [(dispatched-epoch :A "ep-1" :pair)
              (dispatched-epoch :A "ep-2" :app)
              (dispatched-epoch :A "ep-3" :pair)]]
    (is (= "ep-3" (:epoch-id (pure/pick-pair-epoch #{"ep-1" "ep-3"} ring)))
        "walks the ring backward — newest attributed epoch wins")
    (is (nil? (pure/pick-pair-epoch #{} ring)) "no attributed ids -> nil"))
  (testing "survives trace elision — attribution is remembered, not re-parsed"
    ;; ep-1's raw :trace-events were elided for ring age; it is still attributed.
    (let [aged-ring [{:frame :A :epoch-id "ep-1"}          ; trace elided
                     (dispatched-epoch :A "ep-2" :app)]]
      (is (= "ep-1" (:epoch-id (pure/pick-pair-epoch #{"ep-1"} aged-ring)))))))

;; ===========================================================================
;; Cascade / consequence projection (cascade outcome + redaction matrices)
;; ===========================================================================

(deftest db-diff-summary-depth-1-delta
  (is (= {:added-paths [[:b]] :removed-paths [] :changed-paths [[:a]]}
         (pure/db-diff-summary {:a 1} {:a 2 :b 3})))
  (is (= {:added-paths [] :removed-paths [] :changed-paths []}
         (pure/db-diff-summary {:a 1} {:a 1})))
  (is (= {:added-paths [] :removed-paths [] :changed-paths [[]]}
         (pure/db-diff-summary 1 2) )
      "non-map, non-equal -> the whole-db changed marker [[]]"))

(deftest outcome-tier-three-tier-projection
  (is (= :ok (pure/outcome-tier :ok)))
  (is (= :blocked (pure/outcome-tier :halted-depth)))
  (is (= :blocked (pure/outcome-tier :halted-destroy)))
  (is (= :error (pure/outcome-tier :halted-handler-exception)))
  (is (= :ok (pure/outcome-tier :some-unknown-cause)) "unknown defaults to :ok"))

(def ^:private thrown-action-epoch
  ;; Rung 11: a :* wildcard machine action THROWS; the epoch settles :ok, no
  ;; db-change, no fx — a contained throw riding the trace stream.
  {:epoch-id 11 :frame :machine-epochs :outcome :ok
   :db-before {:baseline 5} :db-after {:baseline 5} :effects []
   :trace-events [{:operation :rf.machine/action-ran :tags {:machine-id :fuse/box}}
                  {:operation :rf.error/machine-action-exception
                   :tags {:machine-id :fuse/box :action-id :blow-fuse
                          :exception-message "fuse blew"}}]})

(deftest cascade-summary-contained-throw-forces-error
  (let [summary (pure/cascade-summary thrown-action-epoch false)]
    (is (= :error (:outcome summary)) "a contained throw forces :outcome :error, not :ok")
    (is (seq (:errors summary)))
    (let [err (first (:errors summary))]
      (is (= :rf.error/machine-action-exception (:operation err)))
      (is (= :fuse/box (:machine-id err)))
      (is (= :blow-fuse (:action-id err)))
      (is (= "fuse blew" (:message err))))))

(deftest cascade-summary-benign-no-op-stays-ok
  (let [summary (pure/cascade-summary
                 {:epoch-id 7 :frame :f :outcome :ok
                  :db-before {:x 1} :db-after {:x 1} :effects []
                  :trace-events [{:operation :rf.event/run-end}]}
                 false)]
    (is (= :ok (:outcome summary)))
    (is (nil? (:errors summary)))))

(deftest consequence-from-summary-throw-is-not-a-no-op
  (let [summary (pure/cascade-summary thrown-action-epoch false)
        conseq  (pure/consequence-from-summary {:ok? true :cascade-summary summary})]
    (is (false? (:no-op? conseq)) "a thrown-action epoch is NOT a no-op")
    (is (= :error (:outcome conseq))))
  (testing "a genuine quiescent cascade is a visible no-op"
    (let [summary (pure/cascade-summary
                   {:epoch-id 1 :frame :f :outcome :ok
                    :db-before {:x 1} :db-after {:x 1} :effects [] :trace-events []}
                   false)
          conseq  (pure/consequence-from-summary {:ok? true :cascade-summary summary})]
      (is (true? (:no-op? conseq)))
      (is (false? (:db-changed? conseq))))))

(deftest cascade-summary-event-vector-fail-closed-and-opt-in
  (let [epoch {:epoch-id 42 :event-id :auth/login
               :trigger-event [:auth/login {:username "ada" :password "hunter2"}]
               :frame :rf/default :rf.epoch/sensitive? true
               :db-before {} :db-after {:auth {:user "ada"}} :effects []}]
    (testing "gate OFF (allow-raw-state? false): head retained, args redacted"
      (let [summary (pure/cascade-summary epoch false)]
        (is (= [:auth/login :rf/redacted] (:event-vector summary)))
        (is (not (clojure.string/includes? (pr-str summary) "hunter2"))
            "the password literal appears nowhere")
        (is (true? (:sensitive? summary)))))
    (testing "gate ON (allow-raw-state? true): raw vector rides through"
      (is (= [:auth/login {:username "ada" :password "hunter2"}]
             (:event-vector (pure/cascade-summary epoch true)))))
    (testing "fail-close fires even for a NON-declared-sensitive epoch"
      (let [summary (pure/cascade-summary
                     {:epoch-id 99 :event-id :login :trigger-event [:login "topsecret"]
                      :frame :rf/default :db-before {} :db-after {:auth {:ok true}} :effects []}
                     false)]
        (is (= [:login :rf/redacted] (:event-vector summary)))
        (is (not (clojure.string/includes? (pr-str summary) "topsecret")))
        (is (nil? (:sensitive? summary)) "no :sensitive? annotation, yet args still redact")))))

(deftest redact-sensitive-event-vector-edge-shapes
  (is (nil? (pure/redact-sensitive-event-vector nil false false)) "nil-preserving")
  (is (= :rf/redacted (pure/redact-sensitive-event-vector "scalar" false false))
      "a degenerate non-vector redacts wholesale")
  (is (= [:only-head] (pure/redact-sensitive-event-vector [:only-head] false false))
      "a head-only vector has no args to redact"))

(deftest restore-cascade-projection-shape
  (let [target {:epoch-id 5 :event-id :auth/login
                :trigger-event [:auth/login {:password "hunter2"}]
                :frame :rf/default :rf.epoch/sensitive? true
                :db-after {:auth {:user "ada"}}
                :effects [{:fx-id :http :coord "app:1:1"} {:fx-id :db}]}
        {:keys [cascade-summary unreplayable-effects]}
        (pure/restore-cascade-projection {} target :rf/default 5 false)]
    (is (true? (:restore? cascade-summary)))
    (is (= [:auth/login :rf/redacted] (:event-vector cascade-summary))
        "the restored trigger-event fails closed under the OFF gate")
    (is (= [{:fx-id :http :coord "app:1:1"} {:fx-id :db}] unreplayable-effects)
        "every fx the original cascade fired is unreplayable")))

;; ===========================================================================
;; Snapshot scope + orient assembly (snapshot/orient matrix)
;; ===========================================================================

(deftest resolve-snapshot-frames-scopes
  (is (= [:rf/default :stories]
         (pure/resolve-snapshot-frames :app [:rf/default :stories :rf/xray] [:rf/default :stories]))
      ":app -> app frames (tool frames excluded)")
  (is (= [:rf/default :stories :rf/xray]
         (pure/resolve-snapshot-frames :all [:rf/default :stories :rf/xray] [:rf/default :stories]))
      ":all -> every registered frame")
  (is (= [:stories]
         (pure/resolve-snapshot-frames [:stories] [:rf/default :stories :rf/xray] [:rf/default :stories]))
      "an explicit vector is honoured verbatim (a tool frame named explicitly opts in)")
  (is (= [:app-db :sub-cache :machines :epochs :traces] pure/all-snapshot-slices)))

(deftest top-keys-sorted-or-nil
  (is (= [:cart :route :user] (pure/top-keys {:user 2 :cart 1 :route 3})))
  (is (nil? (pure/top-keys [1 2 3])) "a non-map app-db value has no top-keys"))

(deftest assemble-orient-summary-shape
  (let [r (pure/assemble-orient
           {:debug-enabled? true
            :all-frames [:rf/default :rf/xray]
            :operating-frame :rf/default
            :ambiguous-frame? false
            :runtime-instance-id "abc"
            :app-fids [:rf/default]
            :app-db-top-keys {:rf/default [:cart :route :user]}
            :registry {:basis :process
                       :counts {:event 2 :sub 2 :fx 1}
                       :events [:cart/add :cart/checkout]
                       :subs [:cart/total :current-user]
                       :fx [:http]}
            :machines [:checkout]})]
    (is (true? (:ok? r)))
    (is (= 2 (get-in r [:liveness :frame-count])))
    (is (= 1 (get-in r [:liveness :app-frame-count])))
    (is (= [:rf/default] (get-in r [:frames :app])))
    (is (= [:rf/default :rf/xray] (get-in r [:frames :all])) "tool frame in :all")
    (is (= [:cart :route :user] (get-in r [:app-db-top-keys :rf/default])))
    (is (= 2 (get-in r [:registry :counts :event])))
    (is (= [:checkout] (:machines r)))))

;; ===========================================================================
;; Hash-cache bedrock invariant (app-db-hash cache matrix)
;; ===========================================================================
;;
;; `re-frame2-pair.runtime/app-db-hash` caches `(hash app-db)` per frame and the
;; MCP precheck compares those integers to decide a cache hit in ONE bencode
;; round-trip. That is only correct because structurally-EQUAL CLJS persistent
;; maps hash to the SAME integer. This pins that bedrock invariant so a future
;; runtime change that keyed the cache on `identical?` (or otherwise broke the
;; value-equality contract) fails here fast.

(deftest structurally-equal-maps-hash-equal
  (is (= (hash {:cart {:items 3}}) (hash {:cart {:items 3}}))
      "value-equal, freshly-constructed maps hash to the same integer")
  (is (not= (hash {:cart {:items 3}}) (hash {:cart {:items 99}}))
      "a differing value rotates the hash — the precheck's cache-miss signal"))
