(ns re-frame.hicasso.tool-reads-cljs-test
  "THE FOUR READS, AGAINST A LIVE RUNTIME — what the tool door projects,
  what it refuses to carry, and what it says when it does not know.

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
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.evidence :as rf.hicasso.evidence]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.tool :as rf.hicasso.tool]
            [re-frame.interop :as rf.interop]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

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

;; Two DECLARED views, for the naming rows: `defview` stamps the
;; `"<ns>/<sym>"` name on the body and hands its coordinate to the error
;; ledger, and `codec/retained-body` is the kit's route from the minted
;; head back to that body, so the harness can render it through the same
;; seam as an anonymous fn.
(rf.hicasso/defview named-probe [_] (rf.hicasso/sub [:tr/left]) nil)
(rf.hicasso/defview twin-probe  [_] (rf.hicasso/sub [:tr/left]) nil)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

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

(defn- mount-in!
  "Render `body-fn` as a boundary in `fid` and COMMIT it — the entry claims
  its reference exactly as React's passive effect would. Answers the
  unsubscribe React would hold."
  [fid body-fn]
  (rf.hicasso.impl.collector/render-body fid body-fn {})
  (rf.hicasso.impl.collector/commit-boundary! (rf.hicasso.impl.collector/last-reads) (fn [])))

(defn- mount! [body-fn] (mount-in! frame-id body-fn))

(defn- sub-key
  "A RAW cell key — what the runtime's own tables are keyed by."
  [query-v]
  [frame-id query-v])

(defn- boundary-read-key
  "One element of an EXPORTED boundary key: frame, registration id, and the
  projected query. Distinct from [[sub-key]] on purpose — the raw key is what the
  runtime holds, this is what the door is allowed to say."
  ([query-v] (boundary-read-key frame-id query-v))
  ([fid query-v] [fid (first query-v) query-v]))

(defn- envelopes
  "All four reads, in one turn."
  []
  {:mounted-boundaries (rf.hicasso.tool/read-mounted-boundaries)
   :read-attribution   (rf.hicasso.tool/read-read-attribution)
   :intents            (rf.hicasso.tool/read-intents)
   :explain-render     (rf.hicasso.tool/explain-render)})

(defn- boundary-keys [envelope]
  (into #{} (map (comp :key :boundary)) (:boundaries envelope)))

;; ---------------------------------------------------------------------------
;; The suite states its own basis
;; ---------------------------------------------------------------------------

(deftest this-build-has-the-reads-enabled
  (testing "every assertion below is about a DEV build; the production arm is separate"
    (is (true? rf.interop/debug-enabled?))))

;; ---------------------------------------------------------------------------
;; Read 1 — mounted boundaries
;; ---------------------------------------------------------------------------

(deftest the-mounted-roster-counts-every-committed-boundary
  (seeded!)
  (testing "nothing mounted is an EMPTY roster on a basis that CAN see — a clean bill"
    (let [e (rf.hicasso.tool/read-mounted-boundaries)]
      (is (= rf.hicasso.evidence/schema (:schema e)))
      (is (= rf.hicasso.evidence/producer (:producer e)))
      (is (= :mounted-boundaries (:read e)))
      (is (true? (:complete? e)))
      (is (nil? (:loss e)))
      (is (= [] (:boundaries e)))))

  (testing "a rendered-but-not-yet-committed boundary is OUTSIDE the scope
            — the docstring's own sentence, and until this row nothing
            discriminated refs-gating from entries-not-existing. A
            regression to counting unclaimed entries silently inflates
            the roster `:complete? true` is staked on"
    (rf.hicasso.impl.collector/render-body frame-id (fn [_] (rf.hicasso/sub [:tr/row 0]) nil) {})
    (is (pos? (:entries (rf.hicasso.test.runtime/residue)))
        "the premise: the probe left a REAL entry in the cache")
    (is (= [] (:boundaries (rf.hicasso.tool/read-mounted-boundaries)))
        "which the roster does not count, because no reference claims it"))

  (let [a (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        b (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        c (mount! (fn [_] (rf.hicasso/sub [:tr/left]) (rf.hicasso/sub [:tr/right]) nil))]
    (testing "two boundaries with one edge set are ONE row with :instances 2"
      (let [e    (rf.hicasso.tool/read-mounted-boundaries)
            rows (into {} (map (juxt (comp :key :boundary) identity)) (:boundaries e))]
        (is (= 2 (count rows)) "one row per DISTINCT edge set")
        (is (= 2 (:instances (get rows [(boundary-read-key [:tr/left])]))))
        (is (= 1 (:instances (get rows [(boundary-read-key [:tr/left])
                                        (boundary-read-key [:tr/right])]))))
        (is (= frame-id (:frame (get rows [(boundary-read-key [:tr/left])]))))))

    (testing "a boundary that read NOTHING is still counted — the cell table cannot see it"
      (let [d (mount! (fn [_] nil))
            e (rf.hicasso.tool/read-mounted-boundaries)
            row (first (filter #(= [] (:key (:boundary %))) (:boundaries e)))]
        (is (some? row) "the read-free boundary must appear")
        (is (= 1 (:instances row)))
        (is (= [] (:reads row)) "its read roster is an honest survey result, not a loss")
        (is (= rf.hicasso.evidence/unknown (:frame row))
            "with no reads there is no frame to name, and the row says so explicitly")
        (d)))

    (testing "an unmounted boundary leaves the roster"
      (a) (b) (c)
      (is (= [] (:boundaries (rf.hicasso.tool/read-mounted-boundaries)))))))

(deftest two-read-orders-of-one-edge-set-collapse-to-one-row-that-says-so
  (seeded!)
  (testing "the runtime's entry compare is ORDERED, so `left,right` and
            `right,left` are two live entries — but their edge SETS are
            equal, and the edge set is this door's identity. The row must
            collapse them and SAY it did: `:read-orders` was asserted
            nowhere above 1, so the collapse arm of `entry-rows` could
            rot into duplicate DOM ids for a panel and an ambiguous join
            for a consumer with every existing row green"
    (let [a (mount! (fn [_] (rf.hicasso/sub [:tr/left]) (rf.hicasso/sub [:tr/right]) nil))
          b (mount! (fn [_] (rf.hicasso/sub [:tr/right]) (rf.hicasso/sub [:tr/left]) nil))
          e (rf.hicasso.tool/read-mounted-boundaries)]
      (is (= 2 (:entries (rf.hicasso.test.runtime/residue)))
          "the premise: the runtime really holds TWO entries — without
           this, one row could mean the orders were never distinguished")
      (is (= 1 (count (:boundaries e)))
          "one row per distinct edge set, whatever the read order")
      (let [row (first (:boundaries e))]
        (is (= [(boundary-read-key [:tr/left])
                (boundary-read-key [:tr/right])]
               (:key (:boundary row))))
        (is (= 2 (:instances row)) "both boundaries are counted in it")
        (is (= 2 (:read-orders row))
            "and the row says it folded two orders, which is the honest
             rendering of a collapse the identity demanded"))
      (a) (b))))

(def ^:private named-probe-name "re-frame.hicasso.tool-reads-cljs-test/named-probe")
(def ^:private twin-probe-name  "re-frame.hicasso.tool-reads-cljs-test/twin-probe")

(deftest a-body-with-no-name-leaves-the-views-unknown
  (seeded!)
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        row     (first (:boundaries (rf.hicasso.tool/read-mounted-boundaries)))]
    (testing "a harness fn carries no displayName, so the row states the explicit unknown — never [] and never an absent key"
      (is (contains? row :views))
      (is (= rf.hicasso.evidence/unknown (:views row))))
    (release)))

(deftest the-mounted-roster-names-the-declared-view-that-rendered-it
  (seeded!)
  (let [release (mount! (rf.hicasso.impl.codec/retained-body named-probe))
        e       (rf.hicasso.tool/read-mounted-boundaries)
        row     (first (:boundaries e))
        [v]     (:views row)]
    (testing "the row names the view by the `<ns>/<sym>` defview stamped"
      (is (= 1 (count (:views row))))
      (is (= named-probe-name (:view v))))
    (testing "and its source is the coordinate defview handed the error ledger"
      (is (map? (:source v)))
      (is (= 're-frame.hicasso.tool-reads-cljs-test (:ns (:source v)))
          "the macro captures `:ns` as the namespace SYMBOL, as `reg-view` does")
      (is (pos? (:line (:source v))))
      (is (string? (:file (:source v)))))
    (testing "the attribution readers carry the same name, so the way IN is named too"
      (let [edge (first (filter #(= :tr/left (:sub-id %)) (:edges (rf.hicasso.tool/read-read-attribution))))]
        (is (= [{:view named-probe-name :source (:source v)}]
               (:views (first (:readers edge)))))))
    (testing "and so does the explanation row"
      (is (= (:views row) (:views (first (:explanations (rf.hicasso.tool/explain-render)))))))
    (testing "the roster's own claim is untouched by naming — it is still a complete census"
      (is (true? (:complete? e)))
      (is (nil? (:loss e))))
    (release)))

(deftest two-declared-views-over-one-edge-set-are-one-row-naming-both
  (seeded!)
  (let [a   (mount! (rf.hicasso.impl.codec/retained-body named-probe))
        b   (mount! (rf.hicasso.impl.codec/retained-body twin-probe))
        e   (rf.hicasso.tool/read-mounted-boundaries)
        row (first (:boundaries e))]
    (is (= 1 (count (:boundaries e)))
        "the identity is still the edge set — two views reading one set share one entry")
    (is (= 2 (:instances row)))
    (is (= [named-probe-name twin-probe-name] (mapv :view (:views row)))
        "both names ride on the one row, sorted, so neither view is hidden behind the other")
    (a) (b)))

(deftest a-name-minted-outside-defview-has-no-source
  (seeded!)
  (let [body (fn [_] (rf.hicasso/sub [:tr/left]) nil)]
    ;; `mint-view!` is what stamps the name; a harness stamping it by hand
    ;; stands in for a boundary minted outside the macro — a tool's, or an
    ;; HMR re-registration — which the error ledger never heard about.
    (unchecked-set body "displayName" "erasure.harness/by-hand")
    (let [release (mount! body)
          [v]     (:views (first (:boundaries (rf.hicasso.tool/read-mounted-boundaries))))]
      (is (= "erasure.harness/by-hand" (:view v)))
      (is (= rf.hicasso.evidence/unknown (:source v))
          "no coordinate was declared, and the row says so rather than guessing")
      (release))))

;; The two rows the merged-PR audit of #8758 asked for. A row's `:views`
;; is the roster `entry-rows` claims — the views HOLDING the edge set now —
;; so the name has to ride on the reference: counted where React commits
;; it, uncounted where React releases it, and never written by a render.

(deftest an-unmounted-view-leaves-the-row-its-twin-still-holds
  (seeded!)
  (let [a1  (mount! (rf.hicasso.impl.codec/retained-body named-probe))
        a2  (mount! (rf.hicasso.impl.codec/retained-body named-probe))
        b   (mount! (rf.hicasso.impl.codec/retained-body twin-probe))
        row (fn [] (first (:boundaries (rf.hicasso.tool/read-mounted-boundaries))))]
    (testing "the premise: one row, three holders, both names"
      (is (= 3 (:instances (row))))
      (is (= [named-probe-name twin-probe-name] (mapv :view (:views (row))))))
    (testing "one of two mounted instances unmounting leaves its view named —
              attribution is a count on the reference, not a flag"
      (a1)
      (is (= 2 (:instances (row))))
      (is (= [named-probe-name twin-probe-name] (mapv :view (:views (row))))))
    (testing "the last instance unmounting takes the name with it while the
              twin's reference keeps the row: the row names who holds it NOW"
      (a2)
      (is (= 1 (:instances (row))))
      (is (= [twin-probe-name] (mapv :view (:views (row))))
          "a name that outlives its reference misattributes the twin's row")
      (let [edge (first (filter #(= :tr/left (:sub-id %))
                                (:edges (rf.hicasso.tool/read-read-attribution))))]
        (is (= [twin-probe-name] (mapv :view (:views (first (:readers edge)))))
            "and the attribution reader, named through the same entry, agrees")))
    (b)))

(deftest a-render-react-never-commits-names-no-live-row
  (seeded!)
  (let [b (mount! (rf.hicasso.impl.codec/retained-body twin-probe))]
    (testing "the premise: the twin's row is live and named"
      (is (= [twin-probe-name]
             (mapv :view (:views (first (:boundaries (rf.hicasso.tool/read-mounted-boundaries))))))))
    ;; A speculative render over the SAME edge set: React runs a body it
    ;; then discards — a suspended attempt, an aborted transition,
    ;; StrictMode's first invoke — so this resolves the live entry and no
    ;; `subscribe` ever follows.
    (rf.hicasso.impl.collector/render-body frame-id (rf.hicasso.impl.codec/retained-body named-probe) {})
    (let [row (first (:boundaries (rf.hicasso.tool/read-mounted-boundaries)))]
      (is (= 1 (:instances row)) "nothing committed, so nothing new holds the row")
      (is (= [twin-probe-name] (mapv :view (:views row)))
          "a body React never committed must not be named on a row it never held"))
    (b)))

(deftest the-named-subscribe-is-as-stable-as-the-entrys
  ;; The number that decided the shape of the repair: React re-subscribes
  ;; whenever the `subscribe` it is handed is a new function, so a name
  ;; carried to the commit must not move that identity on any render the
  ;; entry's own would not have moved it on. The wrapper is cached per
  ;; (entry, view), and a fiber's view never changes — so the identity
  ;; moves exactly when the entry does: zero additional re-subscribes.
  (seeded!)
  (let [body (rf.hicasso.impl.codec/retained-body named-probe)
        _    (rf.hicasso.impl.collector/render-body frame-id body {})
        e1   (rf.hicasso.impl.collector/last-reads)
        s1   (.-subscribe rf.hicasso.impl.collector/rstate)
        _    (rf.hicasso.impl.collector/render-body frame-id body {})
        s2   (.-subscribe rf.hicasso.impl.collector/rstate)]
    (is (fn? s1))
    (is (identical? e1 (rf.hicasso.impl.collector/last-reads))
        "the premise: an unchanged read set hits the same entry")
    (is (identical? s1 s2)
        "and the same named `subscribe`, so React does not call it again")
    (is (not (identical? s1 (.-subscribe e1)))
        "it is the wrapper that counts the name, not the entry's own closure")
    (rf.hicasso.impl.collector/render-body frame-id (fn [_] (rf.hicasso/sub [:tr/left]) nil) {})
    (is (identical? (.-subscribe e1) (.-subscribe rf.hicasso.impl.collector/rstate))
        "a body with no name is handed the entry's own closure, as before")
    (rf.hicasso.impl.collector/render-body frame-id (rf.hicasso.impl.codec/retained-body twin-probe) {})
    (is (not (identical? s1 (.-subscribe rf.hicasso.impl.collector/rstate)))
        "a different view over the same entry has its own wrapper — no fiber
         ever changes view, so no fiber pays a re-subscribe for it")))

;; ---------------------------------------------------------------------------
;; Read 2 — read attribution
;; ---------------------------------------------------------------------------

(deftest attribution-is-the-reverse-edge-itself
  (seeded!)
  (let [a (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        b (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        c (mount! (fn [_] (rf.hicasso/sub [:tr/left]) (rf.hicasso/sub [:tr/right]) nil))
        e (rf.hicasso.tool/read-read-attribution)
        by-sub (into {} (map (juxt :sub-id identity)) (:edges e))]
    (testing "the envelope is exact — this read prints a table"
      (is (= :read-attribution (:read e)))
      (is (true? (:complete? e)))
      (is (nil? (:loss e))))
    (testing ":fan-out is the cell's own reader-slot count"
      (is (= 3 (:fan-out (:tr/left by-sub))))
      (is (= 1 (:fan-out (:tr/right by-sub))))
      (is (= (count (rf.hicasso.test.runtime/cell-readers (sub-key [:tr/left])))
             (:fan-out (:tr/left by-sub)))
          "the projection must not derive a number the table already holds"))
    (testing ":readers are the SAME keys the mounted roster states — the rosters join"
      (let [mounted (boundary-keys (rf.hicasso.tool/read-mounted-boundaries))
            readers (into #{} (map :key) (:readers (:tr/left by-sub)))]
        (is (= #{[(boundary-read-key [:tr/left])]
                 [(boundary-read-key [:tr/left]) (boundary-read-key [:tr/right])]}
               readers))
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
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (let [e (rf.hicasso.tool/read-intents)]
      (testing "a ring is a cap, so this read never claims completeness"
        (is (= :intents (:read e)))
        (is (false? (:complete? e)))
        (is (= {:reason :cap :dropped rf.hicasso.evidence/unknown} (:loss e)))
        (is (= [frame-id] (:frames e))))
      (testing "the dispatched events are there, oldest first"
        (is (pos? (count (:intents e))))
        (is (some #(= :tr/bump (:event-id %)) (:intents e)))
        (is (not-any? #(contains? % :event) (:intents e))
            "no row carries an event VECTOR — an id and an arity, and nothing else")))
    (release)))

;; ---------------------------------------------------------------------------
;; Read 4 — explain-render
;; ---------------------------------------------------------------------------

(deftest explain-render-separates-the-proven-half-from-the-uncorrelated-half
  (seeded!)
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) (rf.hicasso/sub [:tr/right]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (let [e  (rf.hicasso.tool/explain-render)
          ex (first (:explanations e))]
      (testing "the envelope's own loss is :uncorrelated, structurally and forever"
        (is (= :explain-render (:read e)))
        (is (false? (:complete? e)))
        (is (= {:reason :uncorrelated :dropped rf.hicasso.evidence/unknown} (:loss e))))
      (testing "PROVEN: the reads whose values moved most recently, off the epoch stamps"
        (is (= [{:sub-id :tr/left :query [:tr/left] :frame-id frame-id}]
               (:latest-reads ex))
            "only :tr/left was written, so only its cell was re-stamped")
        (is (number? (:peak-epoch ex)))
        (is (number? (:snapshot ex))))
      (testing "UNCORRELATED: the row's own loss says the join is missing, and candidates are leads"
        (is (= {:reason :uncorrelated :dropped rf.hicasso.evidence/unknown} (:loss ex)))
        (is (= {:frames [frame-id] :retained-runs (count (rf.trace.tooling/trace-buffer frame-id))}
               (:window ex))
            "the row names the window it searched")
        (is (some #(= :tr/bump (:event-id %)) (:candidates ex))
            "the run that recomputed :tr/left is offered as a lead")))
    (release)))

(deftest an-empty-window-is-cap-and-a-live-window-is-uncorrelated
  (testing "the two loss reasons are DIFFERENT and a reader can drive between them"
    (seeded!)
    (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))]
      (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
      (let [looked (first (:explanations (rf.hicasso.tool/explain-render)))]
        (is (= :uncorrelated (:reason (:loss looked))))
        (is (vector? (:candidates looked))
            "with runs retained, the search really ran and its result is a vector"))

      (rf.trace.tooling/clear-trace-buffer! frame-id)
      (let [blind (first (:explanations (rf.hicasso.tool/explain-render)))]
        (is (= :cap (:reason (:loss blind)))
            "with the window empty, no search happened — a different reason")
        (is (= rf.hicasso.evidence/unknown (:candidates blind))
            "and the leads state the explicit unknown, never an [] that reads as none"))
      (release))))

;; ---------------------------------------------------------------------------
;; THE PRIVACY WITNESS — the hazard is real, and the projections refuse it
;; ---------------------------------------------------------------------------

(deftest the-cells-really-hold-the-secret
  (testing "NON-VACUITY: the state these projections read from is carrying the seed"
    (seeded!)
    (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/token]) nil))]
      (is (= the-secret @(rf.hicasso.test.runtime/cell-reaction (sub-key [:tr/token])))
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
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/token]) (rf.hicasso/sub [:tr/left]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (doseq [[read-name envelope] (envelopes)]
      (is (not (leaked? envelope))
          (str "the " read-name " envelope carried the seeded secret")))
    (release)))

(deftest the-query-projector-is-really-invoked-and-fails-closed
  (testing "a read whose frame is gone redacts the WHOLE query rather than shipping it"
    (seeded!)
    (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/row 0]) nil))
          live    (rf.hicasso.tool/read-mounted-boundaries)]
      (is (= [:tr/row 0] (:query (first (:reads (first (:boundaries live))))))
          "with the frame alive and nothing declared, the query rides as itself")
      (rf/destroy-frame! frame-id)
      (let [dead (rf.hicasso.tool/read-mounted-boundaries)
            row  (first (:boundaries dead))]
        (is (= :rf/redacted (:query (first (:reads row))))
            (str "with the frame destroyed no policy is reachable, so "
                 "`elide-wire-value` fails closed — a query walked under NO policy "
                 "would be every argument shipped verbatim"))
        (is (= :tr/row (:sub-id (first (:reads row))))
            "the registration id is not application data and still rides"))
      (release))))

(deftest a-sensitive-query-argument-never-reaches-a-key-a-reader-or-an-explanation
  ;; AUDIT #7789, CORRECTNESS 1. `projected-query` guarded the `:query`
  ;; FIELD while the boundary KEY was built from raw sub-keys — so the
  ;; argument the projector had just redacted was re-exported under
  ;; `:boundary :key`, again under `:readers`, and again through
  ;; `explain-render`. The shipped witness asserted only `:reads :query`,
  ;; which is precisely why it did not see this.
  ;;
  ;; The forcing function is frame destruction, where the door PROMISES to
  ;; fail closed: no policy is reachable, so nothing derived from the query
  ;; may leave. A seeded secret in ARGUMENT position makes the escape
  ;; observable wherever it happens.
  (testing "with the policy unreachable, the argument is absent from EVERY path out"
    (seeded!)
    ;; No release: the frame is destroyed below, which is the point.
    (mount-in! frame-id (fn [_] (rf.hicasso/sub [:tr/row the-secret]) nil))
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (is (leaked? (rf.hicasso.tool/read-mounted-boundaries))
        (str "NON-VACUITY: with the frame ALIVE and nothing declared, the "
             "argument really is in the state these projections read — the "
             "classification model is fail-open and the query rides as itself. "
             "If this row ever fails, the assertions below are passing against "
             "a boundary that never carried the argument at all"))
    (rf/destroy-frame! frame-id)
    (let [mounted     (rf.hicasso.tool/read-mounted-boundaries)
          attribution (rf.hicasso.tool/read-read-attribution)
          why         (rf.hicasso.tool/explain-render)]
      (is (not (leaked? (map (comp :key :boundary) (:boundaries mounted))))
          "the mounted roster's boundary KEY must not carry the argument")
      (is (not (leaked? mounted))
          "nor may anything else on that envelope")
      (is (not (leaked? (map :readers (:edges attribution))))
          "attribution's :readers keys must not carry it either")
      (is (not (leaked? attribution))
          "nor anything else on the attribution envelope")
      (is (not (leaked? why))
          "and explain-render must not carry it onward — keys, scope or leads"))))

(deftest two-parameterizations-of-one-sub-do-not-collapse
  ;; AUDIT #7789, ERGONOMICS. `:latest-reads` named sub-ids only, so a Why
  ;; row over `[:tr/row 1]` and `[:tr/row 2]` answered ":tr/row moved" —
  ;; one name for two different reads whose projected identity the door
  ;; already held.
  (seeded!)
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/row 1]) (rf.hicasso/sub [:tr/row 2]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (let [ex     (first (:explanations (rf.hicasso.tool/explain-render)))
          latest (:latest-reads ex)]
      (is (= 2 (count latest))
          "two reads of one registered sub are two entries, never one")
      (is (= #{[:tr/row 1] [:tr/row 2]} (into #{} (map :query) latest))
          "and each names its own projected query, which is what tells them apart"))
    (release)))

;; ---------------------------------------------------------------------------
;; FRAME ISOLATION — a second frame must not answer for the first
;; ---------------------------------------------------------------------------

(def ^:private other-frame-id ::tool-reads-other)

(defn- seed-other! []
  (rf/make-frame {:id other-frame-id})
  (rf/with-frame other-frame-id
    (rf/dispatch-sync [:tr/seed {:left 10 :right 20 :rows [:x :y :z]}]))
  other-frame-id)

(deftest explain-render-scopes-its-window-and-its-leads-to-the-boundarys-own-frames
  ;; AUDIT #7789, CORRECTNESS 2. `explain-render` summed retained runs
  ;; GLOBALLY and indexed leads by sub-id ALONE. Two frames registering the
  ;; same sub id — the ordinary case for two mounted apps — therefore let a
  ;; run in B report that A's window had been searched (turning A's honest
  ;; :cap into a false :uncorrelated) and offered B's runs as A's leads.
  (seeded!)
  (seed-other!)
  (let [in-a (mount-in! frame-id       (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        in-b (mount-in! other-frame-id (fn [_] (rf.hicasso/sub [:tr/left]) nil))]
    ;; ASYMMETRIC WINDOWS: B has run, A has not.
    (rf/with-frame other-frame-id (rf/dispatch-sync [:tr/bump]))
    (rf.trace.tooling/clear-trace-buffer! frame-id)
    (let [by-frame (into {} (map (juxt :frame identity))
                         (:explanations (rf.hicasso.tool/explain-render)))
          a        (get by-frame frame-id)
          b        (get by-frame other-frame-id)]
      (is (some? a) "frame A's boundary must be explained")
      (is (some? b) "and so must frame B's")
      (testing "A's window is A's — B's activity cannot claim a search of it"
        (is (= :cap (:reason (:loss a)))
            "A's ring is empty, so nothing was searched for A")
        (is (= rf.hicasso.evidence/unknown (:candidates a))
            "and its leads state the explicit unknown, never an [] nor B's runs"))
      (testing "B's window really was searched, and B keeps its own leads"
        (is (= :uncorrelated (:reason (:loss b))))
        (is (some #(= :tr/bump (:event-id %)) (:candidates b))))
      (testing "a lead names the frame it came from, so the match cannot be on the id alone"
        (is (every? #(= other-frame-id (:frame-id %)) (:candidates b)))))
    (in-a) (in-b))
  (rf/destroy-frame! other-frame-id))

(deftest the-intent-stream-is-one-dispatch-ordered-stream
  ;; AUDIT #7789, CORRECTNESS 2 (second half). The roster concatenated
  ;; whole per-frame rings in FRAME-ID order while the read promised
  ;; dispatch order — so with two frames live the stream asserted a
  ;; sequence that never happened, alphabetically. `:dispatch-id` is
  ;; allocated process-monotonically at queue time, so the true order is
  ;; recoverable and is now used.
  (seeded!)
  (seed-other!)
  ;; A boundary in each frame, so BOTH rings are ones this runtime
  ;; dispatches through and the stream really has two sources to order.
  (let [in-b    (mount-in! other-frame-id (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))]
    ;; Interleave the two frames. `other-frame-id` sorts AFTER frame-id by
    ;; pr-str, so a frame-ordered concatenation cannot reproduce this.
    (rf/with-frame other-frame-id (rf/dispatch-sync [:tr/bump]))
    (rf/with-frame frame-id       (rf/dispatch-sync [:tr/bump]))
    (rf/with-frame other-frame-id (rf/dispatch-sync [:tr/bump]))
    (let [rows (:intents (rf.hicasso.tool/read-intents))
          ids  (mapv :dispatch-id rows)]
      (is (= ids (vec (sort ids)))
          "the stream is ordered by the process-monotonic dispatch id, oldest first")
      (is (apply distinct? ids)
          (str "one dispatch is one row: a run captured in two frames' rings is "
               "merged, never printed twice"))
      (is (every? #(vector? (:frames %)) rows)
          "each row names the frames it reached rather than a single ring's id")
      (is (= #{frame-id other-frame-id} (into #{} (mapcat :frames) rows))
          "both rings really are in the stream — otherwise the order proves nothing"))
    (release) (in-b))
  (rf/destroy-frame! other-frame-id))

;; ---------------------------------------------------------------------------
;; ONE DOOR, DETERMINISTIC — the byte-for-byte precondition
;; ---------------------------------------------------------------------------

(deftest every-read-is-deterministic
  (seeded!)
  (let [a (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))
        b (mount! (fn [_] (rf.hicasso/sub [:tr/right]) (rf.hicasso/sub [:tr/left]) nil))
        c (mount! (fn [_] (rf.hicasso/sub [:tr/row 2]) nil))]
    (rf/with-frame frame-id (rf/dispatch-sync [:tr/bump]))
    (testing "two calls over one runtime state print identically"
      (doseq [[read-name f] [[:mounted-boundaries rf.hicasso.tool/read-mounted-boundaries]
                             [:read-attribution   rf.hicasso.tool/read-read-attribution]
                             [:intents            rf.hicasso.tool/read-intents]
                             [:explain-render     rf.hicasso.tool/explain-render]]]
        (is (= (pr-str (f)) (pr-str (f)))
            (str read-name " must be byte-identical across two calls — a roster "
                 "ordered by a hash map's seq is not"))))
    (a) (b) (c)))

(deftest roster-order-is-a-total-order-and-not-an-insertion-accident
  ;; [[every-read-is-deterministic]] calls each read twice over the SAME
  ;; map instances, and a hash map's seq is order-stable within one
  ;; process — so that row cannot detect the hazard its own message
  ;; names: a roster ordered by iteration order prints identically twice
  ;; and still differs between two processes that built the same state.
  ;; This row builds the same logical population twice, in OPPOSITE
  ;; mount orders, and requires the projected orders to agree — which
  ;; only a real total order over the rows can deliver.
  (let [bodies   [(fn [_] (rf.hicasso/sub [:tr/left]) nil)
                  (fn [_] (rf.hicasso/sub [:tr/right]) nil)
                  (fn [_] (rf.hicasso/sub [:tr/row 0]) nil)
                  (fn [_] (rf.hicasso/sub [:tr/row 1]) nil)
                  (fn [_] (rf.hicasso/sub [:tr/row 2]) nil)]
        orders   (fn []
                   {:roster (mapv (comp :key :boundary)
                                  (:boundaries (rf.hicasso.tool/read-mounted-boundaries)))
                    :edges  (mapv :sub-id (:edges (rf.hicasso.tool/read-read-attribution)))})
        build!   (fn [bs] (seeded!) (mapv mount! bs))
        forward  (let [stops (build! bodies)
                       o     (orders)]
                   (run! #(%) stops)
                   o)
        _        (rf.hicasso.impl.collector/reset-runtime!)
        backward (let [stops (build! (vec (rseq bodies)))
                       o     (orders)]
                   (run! #(%) stops)
                   o)]
    (is (= 5 (count (:roster forward)))
        "non-vacuous: five distinct rows are being ordered")
    (is (= 5 (count (:edges forward)))
        "and five attribution edges — the three :tr/row parameterizations
         stay three cells even though they project one :sub-id")
    (is (= forward backward)
        "two constructions of one logical state must project the same
         BYTES in the same order — insertion order leaking into the
         roster is exactly what `ordered` exists to delete")))

(deftest no-read-takes-a-consumer-discriminator
  (testing "ONE door: Xray and Pair cannot be handed different shapes"
    (doseq [[read-name f] [[:mounted-boundaries rf.hicasso.tool/read-mounted-boundaries]
                           [:read-attribution   rf.hicasso.tool/read-read-attribution]
                           [:intents            rf.hicasso.tool/read-intents]
                           [:explain-render     rf.hicasso.tool/explain-render]]]
      (is (zero? (.-length f))
          (str read-name " must take no argument at all — an audience, a profile "
               "or a verbosity parameter is how two consumers come to hold two "
               "different schemas")))))

;; ---------------------------------------------------------------------------
;; The production arm — this side of the production-erasure proof
;; ---------------------------------------------------------------------------

(deftest every-read-answers-nil-when-the-debug-define-is-off
  (seeded!)
  (let [release (mount! (fn [_] (rf.hicasso/sub [:tr/left]) nil))]
    (with-redefs [rf.interop/debug-enabled? false]
      (doseq [[read-name f] [[:mounted-boundaries rf.hicasso.tool/read-mounted-boundaries]
                             [:read-attribution   rf.hicasso.tool/read-read-attribution]
                             [:intents            rf.hicasso.tool/read-intents]
                             [:explain-render     rf.hicasso.tool/explain-render]]]
        (is (nil? (f))
            (str read-name " must answer nil with the debug define off"))))
    (testing "and the guard is not one-way — the same read answers again with it on"
      (is (some? (rf.hicasso.tool/read-mounted-boundaries))))
    (release)))
