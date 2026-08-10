(ns re-frame.hicasso.tool-reads-cljs-test
  "THE FOUR READS, AGAINST A LIVE RUNTIME — what the tool door projects,
  what it refuses to carry, and what it says when it does not know
  (rf2-hic-023).

  ## The observable is the runtime's own tables, never a rendered page

  Every row here drives real boundaries through the real commit seam
  (`collector/render-body` then `collector/commit-boundary!` — the same
  `subscribe` closure React calls, which is why this is answerable in
  Node) and then asserts what the projection SAYS about them. A read
  attributed to the wrong boundary paints an identical page, so the page
  is not the witness; the read-set entry cache, the cell reader lists and
  the epoch stamps are.

  ## The privacy witness is a SEEDED VALUE, and it is not vacuous

  [[the-cells-really-hold-the-secret]] runs first and proves the hazard is
  real: the cell for a subscription holds a live reaction whose deref IS
  the application value, so the state these projections read from is
  demonstrably carrying the seeded secret. Only then does
  [[no-read-carries-a-value]] assert the secret appears in none of the
  four envelopes. Without the first row the second would pass against a
  runtime that had never seen the value at all.

  Both arms were shown red by perturbing the producer and restored; the
  PR body carries the verbatim output.

  ## Determinism, because byte-for-byte is a claim about bytes

  Xray and Pair consume ONE door with no consumer discriminator, so the
  bytes they receive are equal iff the door is deterministic over one
  runtime state. [[every-read-is-deterministic]] pins that: two calls in
  one quiescent turn must `pr-str` identically, which a roster ordered by
  a hash map's seq would not.

  Nothing under `src/` is changed by this suite."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.tool :as tool]
            [re-frame.interop :as interop]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private frame-id ::tool-reads)

(def ^:private the-secret
  "A value that exists nowhere else in this process, so finding it in a
  projection is proof of egress rather than a coincidence of spelling."
  "RF2-HIC-023-SEEDED-SECRET-b7f1c93a")

(rf/reg-sub :tr/token (fn [db _] (:token db)))
(rf/reg-sub :tr/left  (fn [db _] (:left db)))
(rf/reg-sub :tr/right (fn [db _] (:right db)))
(rf/reg-sub :tr/row   (fn [db [_ i]] (get-in db [:rows i])))

(rf/reg-event :tr/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :tr/bump (fn [{:keys [db]} _] {:db (update db :left inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness — a real frame, real boundaries, the real commit seam
;; ---------------------------------------------------------------------------

(defn- seeded!
  "A frame with a known db, the secret among it."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:tr/seed {:token the-secret :left 1 :right 2
                                 :rows [:a :b :c]}]))
  frame-id)

(defn- mount!
  "Render `body-fn` as a boundary and COMMIT it — the entry claims its
  reference exactly as React's passive effect would. Answers the
  unsubscribe React would hold."
  [body-fn]
  (collector/render-body frame-id body-fn {})
  (collector/commit-boundary! (collector/last-reads) (fn [])))

(defn- k [query-v] [frame-id query-v])

(defn- envelopes
  "All four reads, in one turn."
  []
  {:mounted-boundaries (tool/read-mounted-boundaries)
   :read-attribution   (tool/read-read-attribution)
   :intents            (tool/read-intents)
   :explain-render     (tool/explain-render)})

(defn- boundary-keys [envelope]
  (into #{} (map (comp :key :boundary)) (:boundaries envelope)))

;; ---------------------------------------------------------------------------
;; The suite states its own basis
;; ---------------------------------------------------------------------------

(deftest this-build-has-the-reads-enabled
  (testing "every assertion below is about a DEV build; the production arm is separate"
    (is (true? interop/debug-enabled?))))

;; ---------------------------------------------------------------------------
;; Read 1 — mounted boundaries
;; ---------------------------------------------------------------------------

(deftest the-mounted-roster-counts-every-committed-boundary
  (seeded!)
  (testing "nothing mounted is an EMPTY roster on a basis that CAN see — a clean bill"
    (let [e (tool/read-mounted-boundaries)]
      (is (= evidence/schema (:schema e)))
      (is (= evidence/producer (:producer e)))
      (is (= :mounted-boundaries (:read e)))
      (is (= :observation (:basis e)))
      (is (true? (:complete? e)))
      (is (nil? (:loss e)))
      (is (= [] (:boundaries e)))))

  (let [a (mount! (fn [_] (h/sub [:tr/left]) nil))
        b (mount! (fn [_] (h/sub [:tr/left]) nil))
        c (mount! (fn [_] (h/sub [:tr/left]) (h/sub [:tr/right]) nil))]
    (testing "two boundaries with one edge set are ONE row with :instances 2"
      (let [e    (tool/read-mounted-boundaries)
            rows (into {} (map (juxt (comp :key :boundary) identity)) (:boundaries e))]
        (is (= 2 (count rows)) "one row per DISTINCT edge set")
        (is (= 2 (:instances (get rows [(k [:tr/left])]))))
        (is (= 1 (:instances (get rows [(k [:tr/left]) (k [:tr/right])]))))
        (is (= frame-id (:frame (get rows [(k [:tr/left])]))))))

    (testing "a boundary that read NOTHING is still counted — the cell table cannot see it"
      (let [d (mount! (fn [_] nil))
            e (tool/read-mounted-boundaries)
            row (first (filter #(= [] (:key (:boundary %))) (:boundaries e)))]
        (is (some? row) "the read-free boundary must appear")
        (is (= 1 (:instances row)))
        (is (= [] (:reads row)) "its read roster is an honest survey result, not a loss")
        (is (= evidence/unknown (:frame row))
            "with no reads there is no frame to name, and the row says so explicitly")
        (d)))

    (testing "an unmounted boundary leaves the roster"
      (a) (b) (c)
      (is (= [] (:boundaries (tool/read-mounted-boundaries)))))))

(deftest the-mounted-roster-names-what-it-cannot-know
  (seeded!)
  (let [release (mount! (fn [_] (h/sub [:tr/left]) nil))
        e       (tool/read-mounted-boundaries)
        row     (first (:boundaries e))]
    (testing "a row states :view and :source as the explicit unknown, never omits them"
      (is (= evidence/unknown (:view row)))
      (is (= evidence/unknown (:source row))))
    (testing ":naming is a projection of its own, incomplete on an :opaque basis"
      (let [n (:naming e)]
        (is (= :opaque (:basis n)))
        (is (false? (:complete? n)))
        (is (= {:reason :opaque :dropped evidence/unknown} (:loss n)))
        (is (string? (:why n)) "the absence explains itself to the reader")))
    (testing ":host is a SECOND projection — commit and paint are React's"
      (let [hp (:host e)]
        (is (= :host-opaque (:basis hp)))
        (is (false? (:complete? hp)))
        (is (= evidence/unknown (:commit hp)))
        (is (= evidence/unknown (:paint hp)))
        (is (= evidence/unknown (:attempt-outcome hp)))))
    (testing "the roster's OWN completeness is untouched by either — two claims, not one"
      (is (true? (:complete? e)))
      (is (nil? (:loss e))))
    (release)))

;; ---------------------------------------------------------------------------
;; Read 2 — read attribution
;; ---------------------------------------------------------------------------

(deftest attribution-is-the-reverse-edge-itself
  (seeded!)
  (let [a (mount! (fn [_] (h/sub [:tr/left]) nil))
        b (mount! (fn [_] (h/sub [:tr/left]) nil))
        c (mount! (fn [_] (h/sub [:tr/left]) (h/sub [:tr/right]) nil))
        e (tool/read-read-attribution)
        by-sub (into {} (map (juxt :sub-id identity)) (:edges e))]
    (testing "the envelope is exact — this read prints a table"
      (is (= :read-attribution (:read e)))
      (is (= :read-edges (:scope e)))
      (is (true? (:complete? e)))
      (is (nil? (:loss e))))
    (testing ":fan-out is the cell's own reader-slot count"
      (is (= 3 (:fan-out (:tr/left by-sub))))
      (is (= 1 (:fan-out (:tr/right by-sub))))
      (is (= (count (inventory/cell-readers (k [:tr/left])))
             (:fan-out (:tr/left by-sub)))
          "the projection must not derive a number the table already holds"))
    (testing ":readers are the SAME keys the mounted roster states — the rosters join"
      (let [mounted (boundary-keys (tool/read-mounted-boundaries))
            readers (into #{} (map :key) (:readers (:tr/left by-sub)))]
        (is (= #{[(k [:tr/left])] [(k [:tr/left]) (k [:tr/right])]} readers))
        (is (every? mounted readers)
            "every reader key must resolve in the mounted roster, with no correlation step")))
    (testing "a key nothing holds is ABSENT — not a subscription with zero readers"
      (is (nil? (:tr/row by-sub))))
    (a) (b) (c)))

;; ---------------------------------------------------------------------------
;; Read 3 — the intent stream
;; ---------------------------------------------------------------------------

(deftest the-intent-stream-is-spec-009s-window-and-is-always-capped
  (seeded!)
  (let [release (mount! (fn [_] (h/sub [:tr/left]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (let [e (tool/read-intents)]
      (testing "a ring is a cap, so this read never claims completeness"
        (is (= :intents (:read e)))
        (is (false? (:complete? e)))
        (is (= {:reason :cap :dropped evidence/unknown} (:loss e)))
        (is (= [frame-id] (:frames (:scope e)))))
      (testing "the dispatched events are there, oldest first"
        (is (pos? (count (:intents e))))
        (is (some #(= :tr/bump (:event-id %)) (:intents e)))
        (is (not-any? #(contains? % :event) (:intents e))
            "no row carries an event VECTOR — an id and an arity, and nothing else"))
      (testing "whether a run BEGAN at markup is opaque, and stated as its own projection"
        (let [o (:origin e)]
          (is (= :opaque (:basis o)))
          (is (false? (:complete? o)))
          (is (= evidence/unknown (:intent-origin o))))))
    (release)))

;; ---------------------------------------------------------------------------
;; Read 4 — explain-render
;; ---------------------------------------------------------------------------

(deftest explain-render-separates-the-proven-half-from-the-uncorrelated-half
  (seeded!)
  (let [release (mount! (fn [_] (h/sub [:tr/left]) (h/sub [:tr/right]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (let [e  (tool/explain-render)
          ex (first (:explanations e))]
      (testing "the envelope's own loss is :uncorrelated, structurally and forever"
        (is (= :explain-render (:read e)))
        (is (false? (:complete? e)))
        (is (= {:reason :uncorrelated :dropped evidence/unknown} (:loss e))))
      (testing "PROVEN: the reads whose values moved most recently, off the epoch stamps"
        (is (= [:tr/left] (:latest-reads ex))
            "only :tr/left was written, so only its cell was re-stamped")
        (is (number? (:peak-epoch ex)))
        (is (number? (:snapshot ex))))
      (testing "UNCORRELATED: the cause is unknown, and candidates are labelled leads"
        (is (= evidence/unknown (:cause ex)))
        (is (= {:reason :uncorrelated :dropped evidence/unknown} (:loss ex)))
        (is (some #(= :tr/bump (:event-id %)) (:candidates ex))
            "the run that recomputed :tr/left is offered as a lead"))
      (testing "whether the boundary then RAN is React's — the host projection says so"
        (is (= :host-opaque (:basis (:host e))))))
    (release)))

(deftest an-empty-window-is-cap-and-a-live-window-is-uncorrelated
  (testing "the two loss reasons are DIFFERENT and a reader can drive between them"
    (seeded!)
    (let [release (mount! (fn [_] (h/sub [:tr/left]) nil))]
      (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
      (let [looked (first (:explanations (tool/explain-render)))]
        (is (= :uncorrelated (:reason (:loss looked))))
        (is (vector? (:candidates looked))
            "with runs retained, the search really ran and its result is a vector"))

      (trace-tooling/clear-trace-buffer! frame-id)
      (let [blind (first (:explanations (tool/explain-render)))]
        (is (= :cap (:reason (:loss blind)))
            "with the window empty, no search happened — a different reason")
        (is (= evidence/unknown (:candidates blind))
            "and the leads state the explicit unknown, never an [] that reads as none"))
      (release))))

;; ---------------------------------------------------------------------------
;; THE PRIVACY WITNESS — the hazard is real, and the projections refuse it
;; ---------------------------------------------------------------------------

(deftest the-cells-really-hold-the-secret
  (testing "NON-VACUITY: the state these projections read from is carrying the seed"
    (seeded!)
    (let [release (mount! (fn [_] (h/sub [:tr/token]) nil))]
      (is (= the-secret @(inventory/cell-reaction (k [:tr/token])))
          (str "the cell's live reaction must deref to the seeded secret — if it "
               "does not, every no-egress assertion below is passing against a "
               "runtime that never saw the value"))
      (release))))

(defn- leaked?
  "Does the seeded secret appear anywhere in `v`'s printed form?"
  [v]
  (str/includes? (pr-str v) the-secret))

(deftest no-read-carries-a-value
  (seeded!)
  (let [release (mount! (fn [_] (h/sub [:tr/token]) (h/sub [:tr/left]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (doseq [[read-name envelope] (envelopes)]
      (is (not (leaked? envelope))
          (str "the " read-name " envelope carried the seeded secret")))
    (release)))

(deftest the-query-projector-is-really-invoked-and-fails-closed
  (testing "a read whose frame is gone redacts the WHOLE query rather than shipping it"
    (seeded!)
    (let [release (mount! (fn [_] (h/sub [:tr/row 0]) nil))
          live    (tool/read-mounted-boundaries)]
      (is (= [:tr/row 0] (:query (first (:reads (first (:boundaries live))))))
          "with the frame alive and nothing declared, the query rides as itself")
      (rf/destroy-frame! frame-id)
      (let [dead (tool/read-mounted-boundaries)
            row  (first (:boundaries dead))]
        (is (= :rf/redacted (:query (first (:reads row))))
            (str "with the frame destroyed no policy is reachable, so "
                 "`elide-wire-value` fails closed — a query walked under NO policy "
                 "would be every argument shipped verbatim"))
        (is (= :tr/row (:sub-id (first (:reads row))))
            "the registration id is not application data and still rides"))
      (release))))

;; ---------------------------------------------------------------------------
;; ONE DOOR, DETERMINISTIC — the byte-for-byte precondition
;; ---------------------------------------------------------------------------

(deftest every-read-is-deterministic
  (seeded!)
  (let [a (mount! (fn [_] (h/sub [:tr/left]) nil))
        b (mount! (fn [_] (h/sub [:tr/right]) (h/sub [:tr/left]) nil))
        c (mount! (fn [_] (h/sub [:tr/row 2]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (testing "two calls over one runtime state print identically"
      (doseq [[read-name f] [[:mounted-boundaries tool/read-mounted-boundaries]
                             [:read-attribution   tool/read-read-attribution]
                             [:intents            tool/read-intents]
                             [:explain-render     tool/explain-render]]]
        (is (= (pr-str (f)) (pr-str (f)))
            (str read-name " must be byte-identical across two calls — a roster "
                 "ordered by a hash map's seq is not"))))
    (a) (b) (c)))

(deftest no-read-takes-a-consumer-discriminator
  (testing "ONE door: Xray and Pair cannot be handed different shapes"
    (doseq [[read-name f] [[:mounted-boundaries tool/read-mounted-boundaries]
                           [:read-attribution   tool/read-read-attribution]
                           [:intents            tool/read-intents]
                           [:explain-render     tool/explain-render]]]
      (is (zero? (.-length f))
          (str read-name " must take no argument at all — an audience, a profile "
               "or a verbosity parameter is how two consumers come to hold two "
               "different schemas")))))

;; ---------------------------------------------------------------------------
;; The production arm — this side of rf2-hic-024's proof
;; ---------------------------------------------------------------------------

(deftest every-read-answers-nil-when-the-debug-define-is-off
  (seeded!)
  (let [release (mount! (fn [_] (h/sub [:tr/left]) nil))]
    (with-redefs [interop/debug-enabled? false]
      (doseq [[read-name f] [[:mounted-boundaries tool/read-mounted-boundaries]
                             [:read-attribution   tool/read-read-attribution]
                             [:intents            tool/read-intents]
                             [:explain-render     tool/explain-render]]]
        (is (nil? (f))
            (str read-name " must answer nil with the debug define off"))))
    (testing "and the guard is not one-way — the same read answers again with it on"
      (is (some? (tool/read-mounted-boundaries))))
    (release)))
