(ns re-frame.freehand.occurrence-index-cljs-test
  "rf2-xftdv — the minimal CURRENT-OCCURRENCE index, and the one read over it.

  ## Why any live state exists at all

  Every other Freehand tool read is a projection of a value the caller already
  holds, and increment 1's declaration reads answer before anything mounts. The
  question *what is mounted right now* is not like that. An MCP inspector
  ATTACHES LATE, to an application that has been mounting and re-rendering for
  minutes, and the commit seam is fire-and-forget — so a consumer that starts
  listening at attach time can only learn about commits from that instant on.
  That single requirement is what justifies keeping a row per connected
  occurrence, and it is asserted below in the only form that means anything: a
  read taken with NO sink ever installed still sees occurrences that connected
  before the read existed.

  ## What this suite pins, and what it refuses to

  The three DONE WHEN criteria the bead owns — two live occurrences of one view
  are distinguishable, a late reader sees what was already connected, and
  disconnect removes an occurrence and releases its projection — plus the
  property that makes it an INDEX rather than an accumulator: a second commit
  REPLACES a row.

  Nothing here asserts a render count, a batch count, a lifecycle interval, a
  hide-versus-unmount label, or an accumulated union of every target an
  occurrence ever observed. Those are not facts this slice happens not to have
  got to: rf2-drpa3.167 ruled the cumulative per-occurrence accumulator out
  permanently. The rows' own `occurrence-facts` roster is asserted as an
  EQUALITY below so a member cannot be added without a test saying so."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.occurrences :as occurrences]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.tool :as tool]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  ;; The index is process-wide current state, so a suite that mounts
  ;; occurrences starts from a known one rather than from whatever a preceding
  ;; test left connected. The same reason the trace-ring suites clear rings.
  (fn [f] (occurrences/clear!) (f) (occurrences/clear!)))

(v/defview counter
  "A read and static markup — the interpreted paved path, so a commit has a
  real dependency to state."
  [_]
  [:output.count (str (v/sub [::count]))])

(def ^:private fid :rf/default)

(defn- register! [] (rf/reg-sub ::count (fn [db _] (:count db))))
(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- connect!
  "Mint a cell for `view-id`, render `counter` under its candidate, commit, and
  answer the connected cell — one live occurrence."
  ([view-id] (connect! view-id :interpreted))
  ([view-id lowering]
   (let [c    (cell/cell view-id)
         cand (cell/candidate c fid)]
     (cell/with-capture cand (fn [] (t/render [counter {}])))
     (is (= :published (cell/commit! cand lowering)) "the render was current")
     c)))

(defn- rows-for
  [view-id]
  (filterv #(= view-id (:view-id %)) (:occurrences (tool/read-mounted-views))))

;; ---------------------------------------------------------------------------
;; 1 — two live occurrences of one view are distinguishable
;; ---------------------------------------------------------------------------

(deftest two-live-occurrences-of-one-view-are-two-rows
  (testing "The fact the whole structure exists for, and the reason it is a
            SECOND index rather than a column on the declaration one: a
            declaration-keyed index holds one row per view id and so cannot
            state this at all. Keyed by the runtime occurrence, two
            simultaneously connected occurrences of one view are two rows with
            one `:view-id` and different `:occurrence` keys."
    (register!)
    (seed! {:count 1})
    (let [a    (connect! ::twice)
          b    (connect! ::twice)
          rows (rows-for ::twice)]
      (is (= 2 (count rows)) "two occurrences, two rows")
      (is (= 2 (count (set (map :occurrence rows))))
          "and their occurrence keys are distinct — the rows are addressable
           separately, which is what makes them distinguishable rather than
           merely countable")
      (is (= #{::twice} (set (map :view-id rows)))
          "both naming the one view they are occurrences of")
      ;; Non-vacuity for the row above: disconnecting ONE leaves the OTHER,
      ;; which an index that had merged them could not do.
      (cell/disconnect! a)
      (is (= 1 (count (rows-for ::twice)))
          "disconnecting one occurrence leaves the other connected")
      (cell/disconnect! b))))

(deftest a-second-commit-replaces-a-row-rather-than-adding-one
  (testing "The difference between an index and an accumulator, asserted over
            the roster's cardinality — the one property an appending store
            could not fake. It is also why a COMPATIBLE hot reload is a
            non-event: the reloaded declaration re-renders the same
            occurrences, and each commit replaces its own row."
    (register!)
    (seed! {:count 0})
    (let [c      (connect! ::replaced)
          first- (first (rows-for ::replaced))]
      ;; A COMPATIBLE reload: the view's body revision advances, every live
      ;; occurrence lands on the same number, and each re-renders and commits.
      (cell/advance-generation! c (inc (cell/generation c)))
      (let [cand (cell/candidate c fid)]
        (cell/with-capture cand (fn [] (t/render [counter {}])))
        (is (= :published (cell/commit! cand :interpreted))))
      (let [rows (rows-for ::replaced)]
        (is (= 1 (count rows)) "one occurrence is still one row after two commits")
        (is (= (:occurrence first-) (:occurrence (first rows)))
            "the same occurrence — a compatible reload does not remount it")
        (is (< (:generation first-) (:generation (first rows)))
            "and the row states the LATEST committed generation — a fact about
             now, never a tally of how many there have been"))
      (cell/disconnect! c))))

;; ---------------------------------------------------------------------------
;; 2 — a late reader sees what was already connected
;; ---------------------------------------------------------------------------

(deftest a-reader-that-did-not-exist-at-commit-time-still-sees-the-occurrence
  (testing "THE requirement that justifies the index. No sink is installed at
            any point here, so nothing observed these commits as they happened;
            the read below is the first thing to ask. It sees both occurrences,
            because the substrate rowed them itself at the commit seam rather
            than waiting for a consumer to attach — which is exactly what a
            sink installed at attach time could not have done."
    (register!)
    (seed! {:count 2})
    (cell/set-evidence-sink! nil)
    (let [a (connect! ::late-a)
          b (connect! ::late-b :compiled)
          seen (:occurrences (tool/read-mounted-views))
          by-id (into {} (map (juxt :view-id identity)) seen)]
      (is (contains? by-id ::late-a) "the first occurrence is current")
      (is (contains? by-id ::late-b) "and so is the second")
      (is (= :compiled (:lowering (get by-id ::late-b)))
          "each row NAMES its lowering rather than leaving a reader to infer it
           from how much its evidence claims")
      (is (= fid (:frame (:commit (get by-id ::late-a))))
          "and the frame the commit bound to, so a reader in one frame's context
           can tell whose occurrence this is")
      (cell/disconnect! a)
      (cell/disconnect! b))))

(deftest the-roster-states-exactly-the-facts-it-says-it-states
  (testing "The `occurrence-facts` idiom increment 1 established for event
            sites: naming what a row states is how an empty-handed row says
            which hand is empty. Asserted as an EQUALITY against the rows
            themselves, so neither side can drift into decoration.

            Two members are statements of ignorance, and that is the point.
            `:root` is `:unknown` because cells carry no root identity and
            cell-to-root attribution is what rf2-drpa3.167 ruled would not be
            built; `:dispatch-id` is nil whenever the commit landed outside a
            cascade, which is the common case and is recorded rather than
            invented."
    (register!)
    (seed! {:count 4})
    (let [c   (connect! ::facts)
          r   (tool/read-mounted-views)
          row (first (rows-for ::facts))]
      (is (= #{:view-id :occurrence :lowering :generation :connection :root
               :dispatch-id :at :commit}
             (:occurrence-facts r))
          "the roster is closed and named")
      (is (= (:occurrence-facts r) (set (keys row)))
          "and a row states exactly those facts — no more, no fewer")
      (is (= :unknown (:root row))
          "root identity is reported UNKNOWN explicitly; an absent key would
           read as `none`, which is the one shape this schema exists to refuse")
      (is (= :connected (:connection row)))
      (is (number? (:at row)) "the commit's clock reading is a single number")
      (cell/disconnect! c))))

(deftest the-read-projects-its-own-honesty
  (testing "The read is a projection like every other Freehand evidence
            surface: an OBSERVATION scoped to the occurrences connected NOW.
            `:complete? true` is a claim about UNDER-reporting and it is exact —
            a cell that never published a selected commit is not connected, so
            it is outside the scope rather than missing from the roster."
    (register!)
    (seed! {:count 0})
    (let [c (connect! ::projected)
          r (tool/read-mounted-views)]
      (is (= :re-frame.freehand.evidence/v1 (:schema r))
          "stamped with the version a consumer validates FIRST")
      (is (= :mounted-views (:read r)) "and with WHICH read answered")
      (is (= :connected-occurrences (:scope r)))
      (is (= :observation (:basis r))
          "written from what renders DID, never from what a grammar could prove")
      (is (true? (:complete? r)))
      (is (nil? (:loss r)))
      (cell/disconnect! c))))

;; ---------------------------------------------------------------------------
;; 3 — disconnect removes the occurrence and releases its projection
;; ---------------------------------------------------------------------------

(deftest disconnect-removes-the-row-and-releases-what-it-held
  (testing "The release seam. `mounted` means CONNECTED NOW, so the row is
            REMOVED rather than flagged: a `:disconnected` row would be a
            reader's false positive, and the committed projection it held goes
            with it — which is why there is no disconnected state to read, no
            interval to report, and no hide-versus-unmount label to invent."
    (register!)
    (seed! {:count 9})
    (let [c (connect! ::released)]
      (is (= 1 (count (rows-for ::released))) "connected, and rowed")
      (is (some? (occurrences/row (:occurrence (first (rows-for ::released)))))
          "the row is reachable by its occurrence key")
      (cell/disconnect! c)
      (is (= [] (rows-for ::released))
          "and after the host's teardown it is gone — absent, not marked")
      (is (= :disconnected (cell/lifecycle c))
          "the cell agrees, which is the fact the index is mirroring"))))

(deftest a-reconnecting-occurrence-rows-itself-again-as-one-row
  (testing "`disconnect!` is deliberately not terminal — StrictMode's mount /
            cleanup / mount and a revealed hidden subtree both RECONNECT the
            same cell. So a reconnect must leave ONE row, not a second one
            beside a stale first: the occurrence key is the same, and the next
            selected commit replaces the row it removed."
    (register!)
    (seed! {:count 3})
    (let [c (connect! ::reconnects)]
      (cell/disconnect! c)
      (is (= [] (rows-for ::reconnects)))
      (let [cand (cell/candidate c fid)]
        (cell/with-capture cand (fn [] (t/render [counter {}])))
        (is (= :published (cell/commit! cand :interpreted))))
      (is (= 1 (count (rows-for ::reconnects)))
          "reconnected, and rowed exactly once")
      (cell/disconnect! c))))

(deftest an-abandoned-render-leaves-no-row
  (testing "The index rows SELECTED commits and nothing else. An abandoned
            candidate published no bundle, so there is no committed state for a
            row to be about — and rowing it would report an occurrence as
            connected when the cell itself is not."
    (register!)
    (seed! {:count 0})
    (let [c    (cell/cell ::abandoned)
          cand (cell/candidate c fid)]
      (cell/with-capture cand (fn [] (t/render [counter {}])))
      ;; Advance the cell's body authority past this candidate — a hot reload in
      ;; the render-to-commit gap — so the commit below is no longer current.
      (cell/advance-generation! c (inc (cell/generation c)))
      (is (= :abandoned (cell/commit! cand :interpreted)))
      (is (= [] (rows-for ::abandoned))
          "nothing published, so nothing is current")
      (is (not= :connected (cell/lifecycle c))
          "and the cell says the same"))))
