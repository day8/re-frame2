(ns re-frame.freehand.controlled-cljs-test
  "FH-INPUT-001, FH-INPUT-002 — the controlled-input door and the two
  lanes it selects between.

  Two laws, and they are deliberately separate. The first is a PURE
  question — is this final-normalized site inside the door? — answered by
  one predicate that both execution modes ask, so a table can pin it
  exactly, near-miss by near-miss. The second is what membership does:
  which dispatcher a site fires through, into which frame, and how far
  the synchronous flush reaches.

  Neither of them is the browser proof. Caret position, IME composition
  and dropped characters are DOM behaviours, and a structural assertion
  cannot see one; those live in `controlled-input-dom-cljs-test`, which
  mounts real fields in a real browser. What runs here is the part that
  is honestly host-neutral, and it runs identically on the JVM and in
  ClojureScript from one fixture apiece."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.events :as events]
            [re-frame.live-frame :as live-frame]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ===========================================================================
;; FH-INPUT-001 — the exact door predicate
;; ===========================================================================

(def input-001 (conf/fixture :FH-INPUT-001))

(deftest fh-input-001-the-door-predicate-is-exact
  (testing "Per FH-INPUT-001: one predicate over five final-normalized
            facts. Every row inside the door has all five; every row
            outside it is one fact short, and says which one — because a
            door that is merely approximately right is worse than no
            door at all."
    (is (seq (:door-rows input-001)) "the fixture's door table loaded")
    (is (some :door? (:door-rows input-001)) "and it carries rows INSIDE the door")
    (is (some #(false? (:door? %)) (:door-rows input-001)) "and rows outside it")
    (doseq [{:keys [note site] expected :door?} (:door-rows input-001)]
      (is (= expected (controlled/door? site)) note))))

(deftest fh-input-001-the-near-misses-differ-by-exactly-one-fact
  (testing "Per FH-INPUT-001: a near-miss is not merely 'a row that
            answers false'. It is a row that would be INSIDE the door if
            its one named fact were corrected — which is what makes it
            evidence that the fact is load-bearing rather than decoration."
    (let [misses (filterv :differs (:door-rows input-001))]
      (is (<= 2 (count misses)) "the fixture carries at least two near-misses")
      (doseq [{:keys [note site differs]} misses]
        (is (false? (controlled/door? site)) note)
        (let [corrected (case differs
                          :slot        (assoc site :slot "onInput")
                          :role        (assoc site :role :event-vector)
                          :tag         (assoc site :tag :input)
                          :controlled? (assoc site :controlled? true)
                          :capture?    (dissoc site :capture?)
                          :passive?    (dissoc site :passive?))]
          (is (true? (controlled/door? corrected))
              (str note " — correcting " differs " puts it INSIDE the door, "
                   "which is what makes that fact load-bearing")))))))

(deftest fh-input-001-before-input-is-outside-the-door
  (testing "Per FH-INPUT-001: `beforeinput` fires BEFORE the DOM
            mutation, so the target's value is not generally the
            candidate value. It is outside by ruling, not by omission —
            and it stays a perfectly ordinary event site."
    (is (not (controlled/door-slot? "onBeforeInput")))
    (is (false? (controlled/door? {:tag :input :controlled? true
                                   :slot "onBeforeInput" :role :event-vector})))
    (is (= #{"onInput" "onChange"} controlled/door-slots)
        "the door attribute roster is exactly two, and closed")))

(deftest fh-input-001-the-door-reads-normalized-slots
  (testing "Per FH-INPUT-001: both walks project an attribute key onto
            the prop they actually write, so the door judges the SLOT. A
            namespaced or string spelling of `:value` reaches React's
            `value` and makes the node controlled exactly as `:value`
            does; judging the authored keyword would leave every other
            spelling outside the door."
    (is (seq (:slot-rows input-001)) "the fixture's slot table loaded")
    (doseq [{:keys [note key slot]} (:slot-rows input-001)]
      (is (= slot (controlled/prop-slot key)) note))
    (is (seq (:controlled-rows input-001)) "the fixture's controlled table loaded")
    (doseq [{:keys [note keys] expected :controlled?} (:controlled-rows input-001)]
      (is (= expected (controlled/controlled-props? keys)) note))))

(deftest fh-input-001-both-modes-ask-in-one-vocabulary
  (testing "Per FH-INPUT-001: the compiled analyzer names a handler's
            class in its own vocabulary, and the door reads roster roles.
            The bridge is TOTAL — an unmapped classification answers
            `:dynamic`, which no door roster admits, so a site whose
            class no static fact pins is never silently admitted."
    (is (seq (:compiled-role-rows input-001)) "the fixture's role table loaded")
    (doseq [{:keys [classification role note]} (:compiled-role-rows input-001)]
      (is (= role (controlled/compiled-role classification))
          (or note (str classification " names " role))))
    (is (not (contains? controlled/synchronous-outcomes :dynamic))
        "a runtime-classified site is not statically inside the door")))

;; ===========================================================================
;; FH-INPUT-002 — the two lanes, and the scope of the synchronous one
;; ===========================================================================

(def input-002 (conf/fixture :FH-INPUT-002))

(def ^:private frames    (:frames input-002))
(def ^:private edited    (:edited frames))
(def ^:private bystander (:bystander frames))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- register! []
  (rf/reg-sub (first (:query input-002)) (fn [db _] (:field db)))
  (rf/reg-event (:event input-002) (fn [{:keys [db]} [_ v]] {:db (assoc db :field v)})))

(defn- render+commit!
  "One render of `c` bound to frame `f`: read the fixture's query, record
  ONE event site carrying `element` as its door context, and commit. The
  candidate is a value this function holds and publishes exactly once —
  the same shape every other shell suite uses."
  [c f element]
  (let [cand (cell/candidate c f)]
    (cell/with-capture cand (fn [] (cell/observe! (:query input-002))))
    (events/site (cell/candidate-events cand)
                 (cell/next-site-key! cand)
                 [(:event input-002) :re-frame.freehand/value]
                 events/payload-map
                 element)
    (cell/commit! cand)
    (get (events/committed-sites (cell/events-owner c))
         0)))

(defn- proxy-for [c f element]
  (:proxy (render+commit! c f element)))

(defn- lanes
  "Run `thunk` with BOTH router entry points captured, and answer the
  `[lane event opts]` triples it reached. Capturing the entry points is
  what makes lane selection a deterministic observation on either host,
  rather than a race against a drain."
  [thunk]
  (let [seen (atom [])]
    (with-redefs [router/dispatch!      (fn ([e] (swap! seen conj [:async e {}]))
                                          ([e o] (swap! seen conj [:async e o])))
                  router/dispatch-sync! (fn ([e] (swap! seen conj [:sync e {}]))
                                          ([e o] (swap! seen conj [:sync e o])))]
      (thunk))
    @seen))

(deftest fh-input-002-the-door-selects-the-synchronous-lane
  (testing "Per FH-INPUT-002: a site inside the door fires through the
            SYNCHRONOUS dispatcher and a site outside it through the
            ordinary batched one — both bound to the exact frame the
            commit published. The two sites below carry the same event,
            the same frame and the same payload, and differ in one door
            fact, so the lane is the only thing the assertion can be
            reading."
    (register!)
    (make-frame! edited (:seed input-002))
    (let [typed   (:typed input-002)
          payload {:re-frame.freehand/value typed}
          door    (proxy-for (cell/cell :input/door) edited (:door-site input-002))
          batched (proxy-for (cell/cell :input/batched) edited (:batched-site input-002))
          {:keys [door-lane batched-lane dispatch-opts]} (:expected input-002)]
      (is (= [[door-lane [(:event input-002) typed]
               (assoc dispatch-opts :frame edited)]]
             (lanes #(door payload)))
          "the door site dispatches synchronously into its committed frame")
      (is (= [[batched-lane [(:event input-002) typed]
               (assoc dispatch-opts :frame edited)]]
             (lanes #(batched payload)))
          "one fact outside the door and the same intent takes the ordinary lane"))))

(deftest fh-input-002-the-round-trip-completes-before-the-call-returns
  (testing "Per FH-INPUT-002: the point of the synchronous lane is that
            application state has already moved when the native listener
            resumes. Read the frame's app-db immediately after the proxy
            call — no drain to wait for, no checkpoint to reach."
    (register!)
    (make-frame! edited (:seed input-002))
    (let [door (proxy-for (cell/cell :input/door) edited (:door-site input-002))]
      (door {:re-frame.freehand/value (:typed input-002)})
      (is (= (get-in input-002 [:expected :edited-frame-app-db])
             (frame/frame-app-db-value edited))
          "the edit round-tripped inside the call"))))

(deftest fh-input-002-the-synchronous-flush-is-frame-scoped
  (testing "Per FH-INPUT-002: the flush closes the pending window for the
            cells observing the EDITED frame, and leaves every other
            frame's pending work exactly where it was. Both cells are
            marked before the keystroke, so the assertion reads the
            scope and nothing else — one advances, one stays pending."
    (register!)
    (make-frame! edited (:seed input-002))
    (make-frame! bystander (:seed input-002))
    (let [ca   (cell/cell :input/edited-cell)
          cb   (cell/cell :input/bystander-cell)
          door (proxy-for ca edited (:door-site input-002))
          _    (render+commit! cb bystander nil)
          _    (cell/mark-dirty! ca :test/seeded)
          _    (cell/mark-dirty! cb :test/seeded)
          ra   (cell/revision ca)
          rb   (cell/revision cb)
          {:keys [edited-cell bystander-cell]} (:expected input-002)]
      (is (cell/observes-frame? ca edited) "the edited cell observes the edited frame")
      (is (cell/observes-frame? cb bystander) "and the bystander observes the other one")
      (is (not (cell/observes-frame? cb edited))
          "which is exactly what keeps it out of this flush's scope")
      (door {:re-frame.freehand/value (:typed input-002)})
      (is (= (:dirty? edited-cell) (cell/dirty? ca)))
      (is (= (:revision-advanced? edited-cell) (> (cell/revision ca) ra))
          "the edited frame's cell advanced inside the keystroke")
      (is (= (:dirty? bystander-cell) (cell/dirty? cb)))
      (is (= (:revision-advanced? bystander-cell) (> (cell/revision cb) rb))
          "and the other frame's pending work was NOT forced to settle"))))

(deftest fh-input-002-a-boundary-with-no-frame-has-no-door
  (testing "Per FH-INPUT-002: with no frame in scope there is nothing to
            scope a flush to, and a keystroke that force-settles every
            root in the process is a WIDER promise than batching, not a
            narrower one. Such a site takes the ordinary lane, where a
            firing site meets re-frame's own no-frame diagnostic."
    (register!)
    (let [c    (cell/cell :input/frameless)
          cand (cell/candidate c nil)
          site (do (events/site (cell/candidate-events cand)
                                (cell/next-site-key! cand)
                                [(:event input-002) :re-frame.freehand/value]
                                events/payload-map
                                (:door-site input-002))
                   (cell/commit! cand)
                   (get (events/committed-sites (cell/events-owner c)) 0))]
      (is (true? (:door site)) "the site is still inside the door by predicate")
      (is (= [[:async [(:event input-002) (:typed input-002)] {:source :ui}]]
             (lanes #((:proxy site) {:re-frame.freehand/value (:typed input-002)})))
          "and with no frame to scope, it takes the ordinary ambient lane"))))

;; ---------------------------------------------------------------------------
;; The door rides the committed plan, so a re-commit can move it
;; ---------------------------------------------------------------------------

(deftest fh-input-002-the-door-verdict-is-recommitted-not-frozen
  (testing "Per FH-INPUT-002: the verdict rides the committed site plan,
            exactly as the body does. An element that stops being
            controlled stops taking the synchronous lane on its very next
            commit — and not one callback identity moves, which is the
            property that keeps a memoized consumer memoized."
    (register!)
    (make-frame! edited (:seed input-002))
    (let [c     (cell/cell :input/retargeted)
          first (render+commit! c edited (:door-site input-002))
          again (render+commit! c edited (assoc (:door-site input-002)
                                                :controlled? false))]
      (is (true? (:door first)))
      (is (false? (:door again)) "the element stopped being controlled")
      (is (identical? (:proxy first) (:proxy again))
          "and the site kept the exact callback it had")
      (is (= [[:async [(:event input-002) (:typed input-002)]
               {:frame edited :source :ui}]]
             (lanes #((:proxy again) {:re-frame.freehand/value (:typed input-002)})))))))

;; A `v/event` body that answers nil dispatches nothing, so there is
;; nothing to round-trip and no flush to force.
(deftest fh-input-002-a-nil-outcome-needs-no-flush
  (testing "Per FH-INPUT-002: `nil` means no dispatch. A door site whose
            body declines to produce an intent must not force a drain or
            a flush for a state change that never happened."
    (register!)
    (make-frame! edited (:seed input-002))
    (let [c    (cell/cell :input/declines)
          cand (cell/candidate c edited)
          _    (events/site (cell/candidate-events cand)
                            (cell/next-site-key! cand)
                            (v/event [_] nil)
                            events/payload-map
                            (:door-site input-002))
          _    (cell/commit! cand)
          site (get (events/committed-sites (cell/events-owner c)) 0)]
      (is (true? (:door site)) "a v/event site is inside the door")
      (is (= [] (lanes #((:proxy site) {:re-frame.freehand/value "x"})))
          "and a nil outcome reaches neither lane"))))
