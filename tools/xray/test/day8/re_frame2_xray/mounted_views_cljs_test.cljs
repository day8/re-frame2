(ns day8.re-frame2-xray.mounted-views-cljs-test
  "rf2-7gth0 — Xray reads its view instrumentation through the Freehand
  TOOL-TIER door, against REAL Freehand occurrences.

  Every row asserted below comes from a real `re-frame.freehand.cell` commit
  driven through the substrate's own seam, not from a hand-built projection:
  the point of crossing over is that Xray consumes what Freehand actually
  publishes, and a suite that stubbed the read could agree with a shape the
  substrate does not have.

  ## What this suite does NOT assert, and will not be extended to

  There is no ownership test here, and none is missing. The predecessor suite
  spent most of its length on a same-key ABA fence, an ownership-revision
  reactive axis and a foreign-owner never-clobber contract, because the donor
  evidence tier had a single-owner install registry. `re-frame.freehand.tool`
  has no registry: nothing is claimed, nothing can be held against Xray, and
  no read can surface a superseded span's data because there are no spans.
  Deleting those tests is not a coverage loss — it is the removal of assertions
  about machinery that no longer exists.

  Nor does anything here assert a lifetime render count, a batch count, an
  epoch span, a hide-versus-unmount lifecycle label or an accumulated union of
  observed targets. Those are DELIBERATE LOSS (rf2-drpa3.167 ruled the donor
  accumulator out permanently rather than deferring it), and
  [[donor-shaped-lifetime-facts]] pins their absence as an EQUALITY so a later
  change cannot quietly reintroduce one under the old name."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.occurrences :as occurrences]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.tool :as tool]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.mounted-views :as mounted-views]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; THE REAL APP. Requiring it runs a genuine small Freehand
            ;; application's own declarations: `counter` (the interpreted paved
            ;; path) and `counter-compiled` (`{:compiled true}`, the same body),
            ;; plus its `::count` sub and `::bump` event. Nothing in the
            ;; view-sites arm below is authored by this test — it is the app as
            ;; a consumer of the Xray read surface, and it supplies BOTH
            ;; lowerings, which is the axis the donor tier could not state.
            [re-frame.freehand.release-app :as app]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   ;; The occurrence index is process-wide CURRENT STATE, and
                   ;; the trace ring is what `explain-render` folds. A suite
                   ;; that connects occurrences starts from a known one rather
                   ;; than from whatever a preceding test left connected — the
                   ;; same reason Freehand's own suites clear both. The door is
                   ;; Freehand's, not Xray's: Xray installs nothing and so has
                   ;; nothing of its own to reset.
                   (occurrences/clear!)
                   (trace-tooling/clear-trace-rings!))})
  (fn [f]
    (try (f) (finally
               (occurrences/clear!)
               (trace-tooling/clear-trace-rings!)))))

(def ^:private fid :rf/default)

(v/defview probe
  "One read, so a commit has a real dependency for the roster to state and for
  the explanation fold to join on."
  [_]
  [:output.count (str (v/sub [::count]))])

(defn- register! [] (rf/reg-sub ::count (fn [db _] (:count db))))
(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- connect!
  "Commit ONE occurrence of `view-id`, exactly as the substrate does. When
  `dispatch-id` is given the commit runs with that cascade in scope — the only
  way a Freehand commit acquires a correlation. Answers the connected cell."
  ([view-id] (connect! view-id nil))
  ([view-id dispatch-id]
   (let [c    (cell/cell view-id)
         cand (cell/candidate c fid)]
     (cell/with-capture cand (fn [] (t/render [probe {}])))
     (binding [trace/*handler-scope* (when dispatch-id {:dispatch-id dispatch-id})]
       (is (= :published (cell/commit! cand :interpreted))
           "the render was current"))
     c)))

(defn- retain-run!
  "Put ONE retained run into `fid`'s ring — the dequeued event that started it
  and a subscription recompute inside it, both tagged with the dispatch-id and
  the frame, which is what Spec 009 requires before it retains anything."
  [dispatch-id sub-id]
  (trace/emit! :rf.event :rf.event/dispatched
               {:frame fid :rf.trace/dispatch-id dispatch-id :rf.event/v [::bump 1]})
  (trace/emit! :rf.sub :rf.sub/run
               {:frame fid :rf.trace/dispatch-id dispatch-id :rf.sub/id sub-id})
  dispatch-id)

(defn- rows-for [view-id]
  (filterv #(= view-id (:view-id %)) (mounted-views/rows)))

(defn- boot-xray!
  "The production init path: register the Xray handlers, then
  `ensure-xray-frame!` — so the `:rf/xray` frame and the `:rf.xray/*` sub graph
  exist exactly as a real page load builds them."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- xray-rows []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/mounted-views])))

;; ===========================================================================
;; A real Freehand commit → the Xray row → the Views-panel query
;; ===========================================================================

(deftest a-real-commit-surfaces-as-a-connected-occurrence-row
  (boot-xray!)
  (register!)
  (seed! {:count 1})
  (connect! ::live)
  (let [rows (rows-for ::live)
        row  (first rows)]
    (is (= 1 (count rows)))
    (is (= ::live (:view-id row)) "the declaring view")
    (is (some? (:occurrence row)) "the runtime occurrence key")
    (is (= :interpreted (:lowering row)) "the lowering is STATED, never inferred")
    (is (number? (:generation row)) "the LATEST committed generation")
    (is (= :connected (:connection row)))
    (is (= fid (:frame row)) "the frame the commit ran over")
    (is (= [[::count]] (mapv :query (:reads row)))
        "THAT commit's staged reads — the real query, without the value it
         returned")

    (testing "`:root` is the substrate's explicit unknown, not a nil that would
              read as 'no root' — cells do not know their owning root and the
              commit seam carries no root identity"
      (is (= evidence/unknown (:root row)))
      (is (true? (mounted-views/unknown? (:root row)))))

    (testing "the SAME row reaches the Views panel through
              `:rf.xray/mounted-views`"
      (is (= (mounted-views/rows) (xray-rows)))
      (is (= ::live (:view-id (first (xray-rows))))))))

(deftest two-occurrences-of-one-view-are-two-addressable-rows
  (testing "the fact the roster exists for: keyed by the runtime occurrence,
            two simultaneously connected occurrences of one view are two rows
            with one `:view-id` and different occurrence keys — and
            disconnecting one leaves the other, which a merged row could not do"
    (register!)
    (seed! {:count 1})
    (let [a (connect! ::twice)
          b (connect! ::twice)]
      (is (= 2 (count (rows-for ::twice))))
      (is (= 2 (count (set (map :occurrence (rows-for ::twice)))))
          "distinct occurrence keys — addressable separately")
      (cell/disconnect! a)
      (is (= 1 (count (rows-for ::twice))))
      (cell/disconnect! b)
      (is (= [] (rows-for ::twice))
          "a disconnect REMOVES the row — there is no disconnected state to
           read, which is why no row carries a teardown label"))))

;; ===========================================================================
;; The explanation is a JOIN, and its failure to join is REPORTED
;; ===========================================================================

(deftest a-correlated-commit-carries-the-run-that-caused-it
  (register!)
  (seed! {:count 1})
  (let [did (retain-run! 4141 ::count)]
    (connect! ::explained did)
    (let [row (first (rows-for ::explained))]
      (is (true? (:explained? row)) "the run the commit named is still retained")
      (is (nil? (:loss row)) "…so there is nothing to report as lost")
      (is (= did (:dispatch-id row)))
      (is (= ::app/bump (:cause-event-id (:cause row)))
          "the event that started the run — an id, never the event vector")
      (is (contains? (:sub-ids (:cause row)) ::count)
          "…and the subscription it recomputed that this commit reads"))))

(deftest an-uncorrelated-commit-says-so-rather-than-showing-a-blank
  (testing "a Freehand commit usually lands in a post-settle React batch with
            no cascade on the stack, so there was no correlation to record. A
            nil `:cause` presented as complete evidence would assert that
            nothing caused the render; the row names the reason instead."
    (register!)
    (seed! {:count 1})
    (retain-run! 909 ::count)
    (connect! ::uncorrelated)                     ; no dispatch-id in scope
    (let [row (first (rows-for ::uncorrelated))]
      (is (false? (:explained? row)))
      (is (nil? (:cause row)))
      (is (= :uncorrelated (:reason (:loss row)))
          "the reason is the REMEDY — a bigger buffer would not fix this, which
           is why it is not spelled `:cap`")
      (is (= evidence/unknown (:dropped (:loss row)))
          "a window cannot say how many runs it never held"))))

(deftest an-empty-window-is-reported-as-a-cap-not-as-no-cause
  (testing "retention off / nothing dispatched is the window's one knob, so it
            is `:cap` — a different reason and a different remedy from a commit
            that named no run at all."
    (register!)
    (seed! {:count 1})
    (trace-tooling/clear-trace-rings!)
    (connect! ::capped 7777)                      ; correlated, but nothing retained
    (let [row (first (rows-for ::capped))]
      (is (false? (:explained? row)))
      (is (= :cap (:reason (:loss row)))))))

;; ===========================================================================
;; The deliberate losses, pinned as ABSENCE
;; ===========================================================================

(def ^:private donor-shaped-lifetime-facts
  "The row keys the DONOR evidence tier carried and this one does not.

  Pinned as a set so the absence is a test rather than a note. Every member is
  a LIFETIME quantity or a teardown label over a per-occurrence accumulator
  Freehand does not keep and will not keep (rf2-drpa3.167, REPLACE not defer).
  A change that reintroduced one under its old name would be claiming a fact
  the substrate cannot supply, and this assertion is what makes that loud."
  #{:count :batches :first-epoch :latest-epoch :lifecycle
    :targets :targets-exact? :dropped-count :dropped-exact? :causes :root-id})

(deftest no-row-carries-a-donor-shaped-lifetime-fact
  (register!)
  (seed! {:count 1})
  (connect! ::lean)
  (let [row (first (rows-for ::lean))]
    (is (seq row) "non-vacuous — there IS a row to inspect")
    (is (= #{} (into #{} (filter (set (keys row))) donor-shaped-lifetime-facts))
        "no lifetime tally, no interval log, no accumulated target union, and no
         claimed root id — each absent because the substrate has no accumulator
         to derive it from, not because this projection forgot it")))

;; ===========================================================================
;; Schema honesty — the consumer pin is a LITERAL, so a producer bump is a
;; detectable mismatch rather than silent support
;; ===========================================================================

(deftest the-running-schema-is-read-and-recognised
  (register!)
  (seed! {:count 1})
  (connect! ::versioned)
  (let [{:keys [schema supported?]} (mounted-views/schema-status)]
    (is (= evidence/schema schema) "the producer's stamp, read off a real read")
    (is (true? supported?) "…recognised by this Xray build")))

(deftest an-unrecognised-schema-suppresses-rows-and-surfaces-the-mismatch
  (register!)
  (seed! {:count 1})
  (connect! ::stale)
  (is (seq (mounted-views/rows)) "non-vacuity: rows exist before the redef")
  (with-redefs [tool/read-mounted-views
                (fn [] {:schema      :some.other/v9
                        :read        :mounted-views
                        :occurrences [{:view-id ::stale :occurrence {:key 1}}]})]
    (is (= [] (mounted-views/rows))
        "an unrecognised producer schema suppresses rows — degrade, never
         mis-parse an evolved shape as exact")
    (is (= {:schema :some.other/v9 :supported? false}
           (mounted-views/schema-status))
        "…and the status read reports the mismatch honestly")))

(deftest a-producer-bump-alone-does-not-widen-consumer-support
  (testing "the version boundary must be REAL, not nominal. Xray pins the
            schema it understands to a consumer-owned literal, so a producer
            that bumps its OWN `evidence/schema` — and stamps envelopes with the
            new value — does NOT become auto-supported. RED before the fix,
            where support was derived from the producer's var and moving BOTH
            in lockstep accepted the incompatible shape as exact."
    (let [ahead :re-frame.freehand.evidence/v2]
      (is (not= ahead mounted-views/consumed-evidence-schema))
      (with-redefs [evidence/schema ahead
                    tool/read-mounted-views
                    (fn [] {:schema ahead :read :mounted-views :occurrences []})]
        (is (= [] (mounted-views/rows)))
        (is (= {:schema ahead :supported? false} (mounted-views/schema-status))
            "the status reports the mismatch against the CONSUMER pin, not the
             producer var")))))

;; ===========================================================================
;; The declared-site reads, against a REAL application's own declarations
;; ===========================================================================

(def ^:private compiled-view-id :re-frame.freehand.release-app/counter-compiled)
(def ^:private interpreted-view-id :re-frame.freehand.release-app/counter)

(deftest view-sites-project-a-real-compiled-declarations-true-sites
  (testing "the app's `counter-compiled` declaration — its real sub, its real
            event site and the compiler's own capability + ViewCell verdict —
            projects through the tool door, de-duplicated, with an unregistered
            id skipped rather than given a fabricated row"
    (let [[site] (mounted-views/view-sites [compiled-view-id
                                            compiled-view-id
                                            :legacy/unregistered])]
      (is (= compiled-view-id (:view-id site)))
      (is (= :compiled (:lowering site)))
      (is (= :static-proof (:basis site))
          "a compiled body was analysed, so its rosters are proved")
      (is (true? (:complete? site)))
      (is (nil? (:loss site)))
      (is (= :present (:view-cell site)) "the compiler's ViewCell verdict")

      (testing "the app's REAL subscription dependency, read verbatim"
        (let [subs (:subscriptions site)]
          (is (= 1 (count subs)))
          (is (= [::app/count] (:query (first subs))))
          (is (false? (:dynamic? (first subs)))
              "a literal query IS the authored runtime shape")
          (is (string? (:file (:source-coord (first subs))))
              "the site carries the coordinate the panel's [code] chip opens")))

      (testing "the app's REAL event-site intent vector"
        (let [events (:event-sites site)
              e      (first events)]
          (is (= 1 (count events)))
          (is (= :on-click (:prop e)))
          (is (= [::app/bump] (:handler e))
              "a literal handler vector IS its inspectable shape")
          (is (= ::app/bump (:event-id e)))
          (is (contains? (:site-facts site) :classification)
              "the CLOSED set of facts a row states rides the projection")))))

  (is (= [] (mounted-views/view-sites [:legacy/unregistered]))
      "an id no declaration was recorded under yields NO fabricated row"))

(deftest an-interpreted-declaration-reports-that-nobody-looked
  (testing "the arm the projection vocabulary exists for, and the one the donor
            tier could not state at all — its consumer skipped interpreted views
            entirely, so an unanalysed declaration and an analysed one with no
            sites were indistinguishable. An empty roster reported
            `:complete? true :loss nil` would say it found nothing where what it
            means is that it never looked."
    (let [[site] (mounted-views/view-sites [interpreted-view-id])]
      (is (= interpreted-view-id (:view-id site)))
      (is (= :interpreted (:lowering site)))
      (is (= :opaque (:basis site)))
      (is (false? (:complete? site)))
      (is (= :no-static-analysis (:reason (:loss site))))
      (is (= evidence/unknown (:dropped (:loss site))))
      (is (= [] (:subscriptions site))
          "…and the empty roster is empty because nothing was analysed, which
           the three axes above are what make sayable"))))

(deftest the-sites-query-is-keyed-off-the-live-roster
  (testing "the panel's sites section is evidence-keyed: a host with nothing
            connected projects no sites, even though the manifests themselves
            are readable before anything mounts."
    (boot-xray!)
    (is (= [] (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/mounted-view-sites])))
        "nothing connected ⇒ no sites")))

;; ===========================================================================
;; The live-upgrade seam — a schema-3 process gains the Freehand reads
;; ===========================================================================

(deftest the-schema-migration-installs-the-freehand-reads-into-a-live-process
  ;; Model an already-registered process that installed schema 3 under OLDER
  ;; code and is now running THIS code: the umbrella idempotency gate is SET
  ;; (so a subsequent `register-xray-handlers!` no-ops the whole leaf install)
  ;; and none of the three Freehand reads is registered. Without the schema-4
  ;; clause they stay absent until a full page reload, and the Views panel
  ;; renders nothing on a live-upgraded process.
  (boot-xray!)
  (doseq [sid [:rf.xray/mounted-views
               :rf.xray/mounted-views-schema
               :rf.xray/mounted-view-sites]]
    (registrar/unregister! :sub sid))
  (registry/simulate-registration-at-schema! 3)

  (testing "PRECONDITION — the posed process lacks the Freehand reads"
    (is (every? nil? (map #(registrar/handler :sub %)
                          [:rf.xray/mounted-views
                           :rf.xray/mounted-views-schema
                           :rf.xray/mounted-view-sites]))))

  (registry/register-xray-handlers!)

  (testing "the migration installed all three, callable in :rf/xray"
    (is (every? some? (map #(registrar/handler :sub %)
                           [:rf.xray/mounted-views
                            :rf.xray/mounted-views-schema
                            :rf.xray/mounted-view-sites])))
    (rf/make-frame {:id :rf/xray})
    (is (= [] (rf/with-frame :rf/xray @(rf/subscribe [:rf.xray/mounted-views])))
        "…and the roster read answers on the upgraded process")))
