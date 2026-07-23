(ns re-frame.freehand.shell-cljs-test
  "FH-SUB-001 … FH-SUB-007 — the atomic shell: one selected commit, one
  bundle.

  Seven laws, seven fixtures, six of them the named hazards a speculative
  renderer has to survive — an abandoned render, a hot reload across a
  live cell, a frame retarget, a key reorder, a disconnect, and a capture
  reached from the wrong thread.

  Every row runs over the REAL observation port and the REAL sub-cache
  (the plain-atom adapter), on the JVM and in ClojureScript from one
  fixture apiece. Host honesty: plain-atom derived values are not
  watchable, so a value that moves in the render→commit gap is caught by
  the commit's own evidence comparison rather than by a watch — exactly
  the headless contract, and the reason that comparison covers RETAINED
  handles and not merely staged ones.

  The one genuinely host-shaped law is the last: capture is
  same-render-thread only, and the thread a read arrives on is a question
  only the JVM can ask. Its ClojureScript arm asserts what a
  single-threaded host CAN answer — that a read outside any active render
  is refused — and says which half it is proving."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.events :as events]
            [re-frame.live-frame :as live-frame]
            [re-frame.router :as router]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)

(defn- sub-cache [f] (:sub-cache (frame/frame f)))
(defn- entry     [f q] (get @(sub-cache f) q))
(defn- ref-count [f q] (:ref-count (entry f q)))
(defn- reaction  [f q] (:reaction (entry f q)))
(defn- seed!     [f db] (frame/replace-app-db! f db))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render!
  "One render pass of `c` bound to frame `f`: read `queries` in order and
  return THIS render's candidate. Nothing is published — the candidate is
  a value the caller holds, and dropping it is how a render is
  abandoned."
  [c f queries]
  (let [cand (cell/candidate c f)]
    (cell/with-capture cand (fn [] (mapv cell/observe! queries)))
    cand))

(defn- render+commit! [c f queries]
  (cell/commit! (render! c f queries)))

(defn- dispatched
  "Run `thunk` with the router's dispatch entry point captured, and answer
  the `[event opts]` pairs it received. The committed dispatcher is the
  ONE place the shell injects the frame, so capturing here reads the
  destination directly instead of racing an asynchronous drain."
  [thunk]
  (let [seen (atom [])]
    (with-redefs [router/dispatch! (fn ([e] (swap! seen conj [e {}]))
                                     ([e opts] (swap! seen conj [e opts])))]
      (thunk))
    @seen))

(defn- mock-fn
  "Wrap a single-argument observation-port mock `f` as a genuinely
  multi-arity replacement (calling `f` on the FIRST argument at both
  arities). A bare single-arity `(fn [x] …)` closure lacks the
  `cljs$core$IFn$_invoke$arity$N` methods ClojureScript's `:static-fns`
  dispatch calls the port vars through — including the two-argument
  `obs/acquire!` — so a `with-redefs` replacement must carry them, which a
  multi-arity fn does. The JVM reaches the same value through ordinary var
  indirection."
  [f]
  (fn ([a] (f a)) ([a _b] (f a))))

;; ===========================================================================
;; FH-SUB-001 — the selected commit publishes ONE bundle
;; ===========================================================================

(def sub-001 (conf/fixture :FH-SUB-001))

(deftest fh-sub-001-a-render-owns-nothing-and-the-commit-owns-everything
  (testing "Per FH-SUB-001: a render RESOLVES and PROBES and acquires
            nothing — no ref-count, no watch, no cache node. The selected
            commit is what publishes the frame, the dependencies, the
            event site table and the evidence, and it publishes them
            together."
    (let [{:keys [a b]} (:queries sub-001)]
      (rf/reg-sub (first a) (fn [db _] (:a db)))
      (rf/reg-sub (first b) (fn [db _] (:b db)))
      (seed! fid (:db sub-001))
      (let [c    (cell/cell :shell/panel)
            cand (render! c fid [a b])
            _    (events/site (cell/candidate-events cand)
                              (cell/next-site-key! cand)
                              [:panel/clicked])
            before (:before-commit sub-001)]
        (testing "before the commit the render owns nothing at all"
          (is (= (:lifecycle before) (cell/lifecycle c)))
          (is (= (:dependency-count before) (count (cell/dependencies c))))
          (is (= (:events before) (events/lifecycle (cell/events-owner c))))
          (is (= (:ref-count before) (ref-count fid a)))
          (is (nil? (cell/committed-frame c))))
        (let [after (:after-commit sub-001)]
          (is (= :published (cell/commit! cand)))
          (testing "and after it the whole bundle is live"
            (is (= (:lifecycle after) (cell/lifecycle c)))
            (is (= (:dependency-count after) (count (cell/dependencies c))))
            (is (= (:events after) (events/lifecycle (cell/events-owner c))))
            (is (= (:ref-count after) (ref-count fid a)))
            (is (= (:ref-count after) (ref-count fid b)))
            (is (= fid (cell/committed-frame c)))
            (is (= [a b] (mapv :query (:observations (cell/evidence c))))
                "the evidence records the dependencies in render order")
            (is (every? :owned? (:observations (cell/evidence c)))
                "and reports ownership honestly")))))))

(deftest fh-sub-001-an-unchanged-site-is-retained-untouched
  (testing "Per FH-SUB-001: the commit kept-check retains an unchanged
            live handle UNTOUCHED — the same handle object, no
            re-acquire — so re-committing an unchanged render is a
            no-op rather than a churn of the node it already owns."
    (let [{:keys [a]} (:queries sub-001)]
      (rf/reg-sub (first a) (fn [db _] (:a db)))
      (seed! fid (:db sub-001))
      (let [c (cell/cell :shell/panel)]
        (render+commit! c fid [a])
        (let [h1 (:handle (get (cell/dependencies c) 0))]
          (render+commit! c fid [a])
          (is (identical? h1 (:handle (get (cell/dependencies c) 0))))
          (is (= (:ref-count (:after-commit sub-001)) (ref-count fid a))
              "one owner, not two"))))))

(deftest fh-sub-001-commit-acquires-before-it-releases
  (testing "Per FH-SUB-001: a reconcile acquires every newly-observed or
            retargeted target BEFORE releasing anything, so a node both
            the old and the new dependency set use never falls through
            its zero-owner disposal edge. The proof is node IDENTITY: a
            release-then-acquire would dispose and rebuild it, and the
            rebuilt node is a different object."
    (let [{:keys [a b]} (:queries sub-001)]
      (rf/reg-sub (first a) (fn [db _] (:a db)))
      (rf/reg-sub (first b) (fn [db _] (:b db)))
      (seed! fid (:db sub-001))
      (let [c (cell/cell :shell/panel)]
        ;; Two sites, both on the SAME query: the shared node has two owners.
        (render+commit! c fid [b b])
        (let [shared (reaction fid b)]
          (is (some? shared))
          ;; Now one site drops and the other retargets onto `a`, which
          ;; means the shared node loses one owner and gains none.
          (render+commit! c fid [b])
          (is (identical? shared (reaction fid b))
              "the shared node survived the reconcile — same object, never rebuilt")
          (is (= (:dependency-count (:narrowed sub-001)) (count (cell/dependencies c))))
          (is (= (:ref-count (:narrowed sub-001)) (ref-count fid b))))))))

;; ===========================================================================
;; FH-SUB-002 — HAZARD 1: an abandoned or failed candidate publishes NOTHING
;; ===========================================================================

(def sub-002 (conf/fixture :FH-SUB-002))

(deftest fh-sub-002-abandoned-renders-publish-nothing
  (testing "Per FH-SUB-002 (hazard: abandoned render): a render the host
            never selects publishes NOTHING — not a ref-count, not a
            watch, not a cache node, and not one live event site. It is
            abandoned by DROPPING its candidate, so it is structurally
            unable to publish; there is no flag anywhere that says so."
    (let [{:keys [query]} sub-002
          retained (:retained sub-002)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c      (cell/cell :shell/panel)
            proxies (atom [])]
        (dotimes [_ (:abandoned-renders sub-002)]
          (let [cand (render! c fid [query])]
            (swap! proxies conj
                   (events/site (cell/candidate-events cand)
                                (cell/next-site-key! cand)
                                [:panel/clicked]
                                events/payload-map))))
        (is (= (:dependency-count retained) (count (cell/dependencies c))))
        (is (= (:ref-count retained) (ref-count fid query))
            "no cache node was materialised by any of the abandoned renders")
        (is (= (:events retained) (events/lifecycle (cell/events-owner c))))
        (is (= :new (cell/lifecycle c)))
        (is (= (:dispatched-events retained) (dispatched (fn [] (doseq [p @proxies] (p {})))))
            "and every proxy an abandoned render minted is inert")))))

(deftest fh-sub-002-a-failed-candidate-rolls-back-and-leaves-the-prior-set
  (testing "Per FH-SUB-002 (hazard: abandoned render, failure half): an
            acquisition failure mid-reconcile releases the handles that
            staging already acquired — in reverse order — and leaves the
            PRIOR committed set installed. A partly-owned cell would be a
            cell whose dependencies came from two different renders,
            which is exactly what the bundle exists to prevent."
    (let [{:keys [query other-query doomed-frame]} sub-002
          rollback (:rollback sub-002)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (rf/reg-sub (first other-query) (fn [db _] (:v db)))
      (make-frame! doomed-frame {:v :doomed})
      (seed! fid {:v 1})
      (let [c (cell/cell :shell/panel)]
        ;; A committed prior set of exactly one dependency.
        (render+commit! c fid [query])
        (is (= (:prior-ref-count rollback) (ref-count fid query)))
        ;; A render whose FIRST site is new (so staging really acquires
        ;; it) and whose SECOND resolves a frame that is destroyed before
        ;; the commit runs.
        (let [cand (cell/candidate c fid)]
          (cell/with-capture cand
            (fn []
              [(cell/observe! other-query)
               (rf/with-frame doomed-frame (cell/observe! query))]))
          (frame/destroy-frame! doomed-frame)
          (is (= (:error-id rollback) (conf/caught-id #(cell/commit! cand))))
          (is (= (:prior-dependency-count rollback) (count (cell/dependencies c)))
              "the prior committed set is still installed, untouched")
          (is (= query (:query (get (cell/dependencies c) 0))))
          (is (= (:prior-ref-count rollback) (ref-count fid query)))
          (is (= (:rolled-back-ref-count rollback) (ref-count fid other-query))
              "the staged handle the failed reconcile acquired was released"))))))

(deftest fh-sub-002-a-commit-side-read-failure-releases-every-staged-handle
  (testing "Per FH-SUB-002 (hazard: abandoned render, the commit-side-read
            half): the pre-publication phase is transactional THROUGH the
            commit-side reads, not merely through acquisition. `obs/read` is a
            fail-loud port op that may deref application subscription code, so
            a read that throws before publication must release every handle
            THIS candidate freshly acquired — exactly once, in reverse
            acquisition order — leave the prior committed set installed, and
            rethrow the ORIGINAL failure unwrapped. A candidate that publishes
            nothing must OWN nothing: a stranded handle is a dependency nobody
            will ever release. The port is fully mocked so the failure is
            deterministic on both hosts."
    (let [q1       [:read-fail/a]
          q2       [:read-fail/b]
          acquired (atom [])
          released (atom [])
          boom     (ex-info "commit-side read blew up" {:probe :read})]
      (with-redefs [obs/resolve-target (mock-fn (fn [{:keys [query-v]}] {:query-v query-v}))
                    obs/probe          (mock-fn (fn [_target] {:value :probed}))
                    obs/acquire!       (mock-fn (fn [target]
                                                  (let [h {:handle-for (:query-v target)}]
                                                    (swap! acquired conj h)
                                                    h)))
                    obs/read           (mock-fn (fn [_handle] (throw boom)))
                    obs/release!       (mock-fn (fn [h] (swap! released conj h) nil))]
        (let [c      (cell/cell :shell/panel)
              cand   (cell/candidate c nil)
              caught (atom nil)]
          (cell/with-capture cand (fn [] (mapv cell/observe! [q1 q2])))
          (try (cell/commit! cand)
               (catch #?(:clj Throwable :cljs :default) e (reset! caught e)))
          (is (identical? boom @caught)
              "the original observation failure escapes, unwrapped")
          (is (= 2 (count @acquired)) "staging acquired both fresh handles")
          (is (= 2 (count @released)) "and released exactly two — neither stranded")
          (is (= (vec (rseq (vec @acquired))) @released)
              "every staged handle was released exactly once, in REVERSE
               acquisition order")
          (is (= :new (cell/lifecycle c)) "the cell published nothing")
          (is (= 0 (count (cell/dependencies c))))
          (is (= 0 (cell/generation c)))
          (is (nil? (cell/evidence c))))))))

(deftest fh-sub-002-a-commit-side-read-that-advances-authority-abandons
  (testing "Per FH-SUB-002 (hazard: abandoned render, the re-entrancy half):
            a commit-side `obs/read` can RE-ENTER application subscription code
            that advances the cell's authority — a hot reload of the body
            landing mid-read. The final currency boundary is therefore AFTER
            every commit-side read: a read that advances the body revision
            makes the candidate stale, so it rolls back its fresh staging and
            returns `:abandoned` rather than publishing stale ownership.
            Nothing from that candidate publishes."
    (let [q1       [:reentrant/a]
          acquired (atom [])
          released (atom [])
          c        (cell/cell :shell/panel)]
      (with-redefs [obs/resolve-target (mock-fn (fn [{:keys [query-v]}] {:query-v query-v}))
                    obs/probe          (mock-fn (fn [_target] {:value :probed}))
                    obs/acquire!       (mock-fn (fn [target]
                                                  (let [h {:handle-for (:query-v target)}]
                                                    (swap! acquired conj h)
                                                    h)))
                    ;; The read re-enters and advances the body revision — a hot
                    ;; reload landing mid-read, AFTER the first currency check.
                    obs/read           (mock-fn (fn [_handle]
                                                  (cell/advance-generation! c)
                                                  {:value :probed}))
                    obs/release!       (mock-fn (fn [h] (swap! released conj h) nil))]
        (let [cand (cell/candidate c nil)]
          (cell/with-capture cand (fn [] (cell/observe! q1)))
          (is (= :abandoned (cell/commit! cand))
              "the re-check at the narrowest boundary — now AFTER the reads —
               sees the advanced authority and abandons")
          (is (= 1 (count @acquired)))
          (is (= @acquired @released) "the fresh staging was released")
          (is (= :new (cell/lifecycle c)) "nothing from that candidate published")
          (is (= 0 (count (cell/dependencies c))))
          (is (nil? (cell/evidence c))))))))

;; ===========================================================================
;; FH-SUB-003 — HAZARD 2: hot reload across a LIVE cell
;; ===========================================================================

(def sub-003 (conf/fixture :FH-SUB-003))

(deftest fh-sub-003-a-reload-in-the-render-commit-gap-publishes-nothing
  (testing "Per FH-SUB-003 (hazard: HMR across a live cell): a body
            replaced between render and commit makes that render's
            candidate STALE, and a stale candidate publishes nothing. The
            live cell keeps the dependency set its own body earned; the
            host simply renders again at the new body."
    (let [{:keys [query]} sub-003
          fence (:entry-fence sub-003)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c (cell/cell :shell/panel)]
        (render+commit! c fid [query])
        (let [handle (:handle (get (cell/dependencies c) 0))
              stale  (render! c fid [query query])]
          (cell/advance-generation! c)
          (is (= (:result fence) (cell/commit! stale)))
          (is (= (:generation fence) (cell/generation c)))
          (is (= (:lifecycle fence) (cell/lifecycle c)))
          (is (= (:dependency-count fence) (count (cell/dependencies c)))
              "the stale two-site render published none of its sites")
          (is (identical? handle (:handle (get (cell/dependencies c) 0)))
              "and the live cell's own handle was never touched")
          (is (= (:ref-count fence) (ref-count fid query))))))))

(deftest fh-sub-003-a-reload-during-the-commit-itself-publishes-nothing
  (testing "Per FH-SUB-003 (hazard: HMR across a live cell, the narrow
            window): staging runs cache installs, and those are
            callback-capable — a reload can land INSIDE the commit,
            after currency was first checked. So currency is re-read at
            the narrowest boundary, with nothing callback-capable between
            it and the publish, and a candidate that went stale there
            releases only what it staged."
    (let [{:keys [query]} sub-003
          fence (:publication-fence sub-003)
          runs  (atom 0)
          c     (cell/cell :shell/panel)]
      ;; The body runs twice for a cold site: once for the render's pure
      ;; probe, once for the node the commit installs. Reloading on the
      ;; second run is a reload that lands mid-commit.
      (rf/reg-sub (first query)
                  (fn [db _]
                    (when (= (:reload-on-computation fence) (swap! runs inc))
                      (cell/advance-generation! c))
                    (:v db)))
      (seed! fid {:v 1})
      (let [cand (render! c fid [query])]
        (is (= (:result fence) (cell/commit! cand)))
        (is (= (:generation fence) (cell/generation c)))
        (is (= (:lifecycle fence) (cell/lifecycle c))
            "the cell never became connected on a stale body")
        (is (= (:dependency-count fence) (count (cell/dependencies c))))
        (is (= (:ref-count fence) (ref-count fid query))
            "the handle staging had already acquired was released")))))

;; ===========================================================================
;; FH-SUB-004 — HAZARD 3: frame retarget
;; ===========================================================================

(def sub-004 (conf/fixture :FH-SUB-004))

(deftest fh-sub-004-a-frame-retarget-rebinds-dependencies-and-events-together
  (testing "Per FH-SUB-004 (hazard: frame retarget): a provider retarget
            from A to B rebinds the whole bundle. The dependencies move
            to B — acquired before A's are released — the committed event
            destination moves to B, and not one callback identity
            changes, because retargeting is exactly a re-commit with a
            different frame."
    (let [{:keys [query]} sub-004
          fa (get-in sub-004 [:frames :a])
          fb (get-in sub-004 [:frames :b])
          under-a (:under-a sub-004)
          under-b (:under-b sub-004)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (make-frame! fa (get-in sub-004 [:db :a]))
      (make-frame! fb (get-in sub-004 [:db :b]))
      (let [c        (cell/cell :shell/panel)
            intent   (:intent sub-004)
            render-under
            (fn [f]
              (let [cand (cell/candidate c f)
                    v    (first (cell/with-capture cand (fn [] [(cell/observe! query)])))
                    p    (events/site (cell/candidate-events cand) :on-click intent
                                      events/payload-map)]
                (cell/commit! cand)
                [v p]))
            [va pa] (render-under fa)]
        (is (= (:value under-a) va) "the body read frame A's own state")
        (is (= (:committed-frame under-a) (cell/committed-frame c)))
        (is (= (:frame-id under-a) (:frame-id (first (:observations (cell/evidence c))))))
        (is (= [[intent {:frame (:dispatch-frame under-a) :source :ui}]]
               (dispatched (fn [] (pa {}))))
            "the committed site fires into frame A")
        (let [[vb pb] (render-under fb)]
          (is (= (:value under-b) vb) "and after the retarget, frame B's")
          (is (= (:committed-frame under-b) (cell/committed-frame c)))
          (is (= (:frame-id under-b) (:frame-id (first (:observations (cell/evidence c))))))
          (is (identical? pa pb)
              "the site kept its EXACT proxy across the retarget — a frame change
               reaches every site without churning one callback identity")
          (is (= [[intent {:frame (:dispatch-frame under-b) :source :ui}]]
                 (dispatched (fn [] (pa {}))))
              "and that same proxy now fires into frame B")
          (is (= (:released-ref-count sub-004) (ref-count fa query))
              "frame A's node was released once B's was acquired")
          (is (= (:acquired-ref-count sub-004) (ref-count fb query))))))))

(deftest fh-sub-004-a-destroyed-incarnation-publishes-nothing
  (testing "Per FH-SUB-004 (hazard: frame retarget, the incarnation
            half): a render resolves ONE exact frame incarnation. A
            same-id destroy in the render→commit gap is a different
            frame under a reused name, not the same frame — so the
            candidate is stale and publishes nothing."
    (let [{:keys [query]} sub-004
          fa (get-in sub-004 [:frames :a])]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (make-frame! fa (get-in sub-004 [:db :a]))
      (let [c    (cell/cell :shell/panel)
            cand (render! c fa [query])]
        (frame/destroy-frame! fa)
        (make-frame! fa (get-in sub-004 [:db :a]))
        (is (= (:reincarnation-result sub-004) (cell/commit! cand)))
        (is (= :new (cell/lifecycle c)))
        (is (= 0 (count (cell/dependencies c))))))))

;; ===========================================================================
;; FH-SUB-005 — HAZARD 4: key reorder
;; ===========================================================================

(def sub-005 (conf/fixture :FH-SUB-005))

(deftest fh-sub-005-sibling-occurrences-own-disjoint-bundles
  (testing "Per FH-SUB-005 (hazard: key reorder): each keyed occurrence
            owns its OWN cell, and committing one publishes into that
            cell and no other. Two siblings rendering equal intent get
            two independent event sites, so a reorder can move rows
            around without one row's commit reaching into another's
            ownership."
    (let [{:keys [query-a query-b intent]} sub-005]
      (rf/reg-sub (first query-a) (fn [db _] (:a db)))
      (rf/reg-sub (first query-b) (fn [db _] (:b db)))
      (seed! fid (:db sub-005))
      (let [row-1 (cell/cell :shell/row)
            row-2 (cell/cell :shell/row)
            c1    (cell/candidate row-1 fid)
            c2    (cell/candidate row-2 fid)
            p1    (do (cell/with-capture c1 (fn [] [(cell/observe! query-a)]))
                      (events/site (cell/candidate-events c1) :on-click intent events/payload-map))
            p2    (do (cell/with-capture c2 (fn [] [(cell/observe! query-b)]))
                      (events/site (cell/candidate-events c2) :on-click intent events/payload-map))]
        (cell/commit! c1)
        (testing "committing one occurrence publishes into that occurrence only"
          (is (= :connected (cell/lifecycle row-1)))
          (is (= :new (cell/lifecycle row-2)))
          (is (= 0 (count (cell/dependencies row-2)))))
        (cell/commit! c2)
        (is (not (identical? p1 p2))
            "equal intent at two occurrences gets two independent proxies")
        (is (= [query-a] (mapv :query (:observations (cell/evidence row-1)))))
        (is (= [query-b] (mapv :query (:observations (cell/evidence row-2)))))))))

(deftest fh-sub-005-a-reorder-inside-one-boundary-disposes-neither-node
  (testing "Per FH-SUB-005 (hazard: key reorder): when a reorder makes
            the sites of ONE boundary trade targets, both nodes stay
            owned throughout. The reconcile acquires every retargeted
            site before releasing anything, so neither node passes
            through zero owners — the dispose-then-rebuild churn a
            release-first reconcile would cause on every reorder."
    (let [{:keys [query-a query-b]} sub-005
          reordered (:reordered sub-005)]
      (rf/reg-sub (first query-a) (fn [db _] (:a db)))
      (rf/reg-sub (first query-b) (fn [db _] (:b db)))
      (seed! fid (:db sub-005))
      (let [c (cell/cell :shell/list)]
        (render+commit! c fid [query-a query-b])
        (let [node-a (reaction fid query-a)
              node-b (reaction fid query-b)]
          (is (some? node-a))
          (is (some? node-b))
          ;; The reorder: each site takes the other's target.
          (render+commit! c fid [query-b query-a])
          (is (identical? node-a (reaction fid query-a))
              "node A was never disposed and rebuilt")
          (is (identical? node-b (reaction fid query-b))
              "and neither was node B")
          (is (= (:ref-count reordered) (ref-count fid query-a)))
          (is (= (:ref-count reordered) (ref-count fid query-b)))
          (is (= [query-b query-a] (mapv :query (:observations (cell/evidence c))))
              "and the evidence records the NEW order"))))))

(deftest fh-sub-005-a-reorder-across-occurrences-crosses-no-ownership
  (testing "Per FH-SUB-005 (hazard: key reorder): two live occupants that
            trade targets settle at exactly one owner each, and neither
            cell ends up holding the other's handle. Occurrence cells
            reconcile independently — a keyed list's rows are separate
            boundaries, so what the reorder must not do is CROSS them."
    (let [{:keys [query-a query-b]} sub-005
          reordered (:reordered sub-005)]
      (rf/reg-sub (first query-a) (fn [db _] (:a db)))
      (rf/reg-sub (first query-b) (fn [db _] (:b db)))
      (seed! fid (:db sub-005))
      (let [row-1 (cell/cell :shell/row)
            row-2 (cell/cell :shell/row)]
        (render+commit! row-1 fid [query-a])
        (render+commit! row-2 fid [query-b])
        ;; The reorder: each occupant takes the other's target.
        (render+commit! row-1 fid [query-b])
        (render+commit! row-2 fid [query-a])
        (is (= (:ref-count reordered) (ref-count fid query-a)))
        (is (= (:ref-count reordered) (ref-count fid query-b)))
        (is (= [query-b] (mapv :query (:observations (cell/evidence row-1)))))
        (is (= [query-a] (mapv :query (:observations (cell/evidence row-2)))))
        (is (not (identical? (:handle (get (cell/dependencies row-1) 0))
                             (:handle (get (cell/dependencies row-2) 0))))
            "each occurrence owns its own handle — the owner token is per-boundary,
             so two rows can never share one")))))

;; ===========================================================================
;; FH-SUB-006 — HAZARD 5: disconnect
;; ===========================================================================

(def sub-006 (conf/fixture :FH-SUB-006))

(deftest fh-sub-006-disconnect-releases-the-whole-bundle
  (testing "Per FH-SUB-006 (hazard: disconnect): teardown deactivates
            exactly what the SELECTED commit owned — every dependency
            released on the spot, every callback retired, and the node
            disposed on its zero-owner edge. A retired proxy stays
            callable, because a foreign listener may already hold one,
            and is inert."
    (let [{:keys [query intent]} sub-006
          after (:after-disconnect sub-006)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c    (cell/cell :shell/panel)
            cand (render! c fid [query])
            p    (events/site (cell/candidate-events cand) :on-click intent events/payload-map)]
        (cell/commit! cand)
        (is (= 1 (ref-count fid query)))
        (is (= [[intent {:frame fid :source :ui}]] (dispatched (fn [] (p {})))))
        (cell/disconnect! c)
        (is (= (:lifecycle after) (cell/lifecycle c)))
        (is (= (:dependency-count after) (count (cell/dependencies c))))
        (is (= (:ref-count after) (ref-count fid query))
            "the node disposed on its zero-owner edge")
        (is (= (:events after) (events/lifecycle (cell/events-owner c))))
        (is (= (:dispatched-events after) (dispatched (fn [] (p {}))))
            "and the retired proxy dispatches nothing")))))

(deftest fh-sub-006-a-replayed-commit-reacquires-it-never-resurrects
  (testing "Per FH-SUB-006 (hazard: disconnect): a host may tear an
            effect down and run it AGAIN for the same committed render —
            StrictMode's mount, cleanup, mount, and a hidden subtree
            revealed. That replay must reconnect, and it must reconnect
            from a CLEAN SLATE: the released handle is gone, so the
            commit acquires a fresh one against a freshly built node
            rather than resurrecting ownership it already gave up."
    (let [{:keys [query]} sub-006
          replay (:replayed sub-006)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c    (cell/cell :shell/panel)
            cand (render! c fid [query])]
        (cell/commit! cand)
        (let [h1   (:handle (get (cell/dependencies c) 0))
              node (reaction fid query)]
          (cell/disconnect! c)
          (is (nil? (ref-count fid query)) "the node disposed on its zero-owner edge")
          (is (= (:result replay) (cell/commit! cand)))
          (is (= (:lifecycle replay) (cell/lifecycle c)))
          (is (= (:dependency-count replay) (count (cell/dependencies c))))
          (is (= (:ref-count replay) (ref-count fid query)))
          (is (not (identical? h1 (:handle (get (cell/dependencies c) 0))))
              "a FRESH handle — the released one was never revived")
          (is (not (identical? node (reaction fid query)))
              "against a freshly built node, because the old one was disposed"))))))

(deftest fh-sub-006-a-disconnected-cell-is-dropped-from-the-pending-window
  (testing "Per FH-SUB-006 (hazard: disconnect): a cell marked dirty and
            then disconnected is not flushed. Teardown removes it from
            the open window, so no host listener is notified about a
            boundary that no longer exists."
    (let [c        (cell/cell :shell/panel)
          notified (atom 0)]
      (cell/subscribe c (fn [] (swap! notified inc)))
      (cell/mark-dirty! c {:cause :subscription})
      (is (true? (cell/dirty? c)))
      (cell/disconnect! c)
      (cell/flush!)
      (is (= (:notifications-after-disconnect sub-006) @notified))
      (is (= (:revision-after-disconnect sub-006) (cell/revision c))))))

(deftest fh-sub-006-a-later-render-reconnects-the-same-cell
  (testing "Per FH-SUB-006 (hazard: disconnect): disconnect is not
            terminal. A host that hides and later reveals a subtree
            renders again, and that render reconnects the SAME cell from
            a clean slate — it re-acquires rather than resurrecting the
            ownership it released."
    (let [{:keys [query]} sub-006
          reconnect (:reconnect sub-006)]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c (cell/cell :shell/panel)]
        (render+commit! c fid [query])
        (cell/disconnect! c)
        (is (= (:result reconnect) (render+commit! c fid [query])))
        (is (= (:lifecycle reconnect) (cell/lifecycle c)))
        (is (= (:dependency-count reconnect) (count (cell/dependencies c))))
        (is (= (:ref-count reconnect) (ref-count fid query)))))))

;; ===========================================================================
;; FH-SUB-007 — HAZARD 6: same-thread capture
;; ===========================================================================

(def sub-007 (conf/fixture :FH-SUB-007))

(deftest fh-sub-007-a-read-outside-an-active-render-is-refused
  (testing "Per FH-SUB-007 (hazard: same-thread capture, the common
            half): a reactive read with no active render has no owner. It
            is refused loudly rather than probed and dropped, because a
            read nobody owns is a dependency nobody will ever release."
    (let [{:keys [query]} sub-007]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (is (false? (cell/observing?)))
      (is (= (:outside-render-error-id sub-007)
             (conf/caught-id #(rf/with-frame fid (cell/observe! query))))))))

(deftest fh-sub-007-a-read-inside-the-render-is-recorded-on-that-render
  (testing "Per FH-SUB-007: the positive half — a read on the render's
            own thread is recorded on that render's candidate, in
            document order, and on no other."
    (let [{:keys [query]} sub-007]
      (rf/reg-sub (first query) (fn [db _] (:v db)))
      (seed! fid {:v 1})
      (let [c    (cell/cell :shell/panel)
            cand (cell/candidate c fid)]
        (cell/with-capture cand
          (fn []
            (is (true? (cell/observing?)))
            (cell/observe! query)))
        (is (= (:recorded-sites sub-007) (count (cell/candidate-reads cand))))
        (is (false? (cell/observing?)) "and the capture closes with the render")))))

#?(:clj
   (deftest fh-sub-007-a-conveyed-child-thread-is-refused-before-it-probes
     (testing "Per FH-SUB-007 (hazard: same-thread capture, the JVM
               half): Clojure CONVEYS dynamic bindings into `future` /
               `pmap` / `bound-fn`, so a render body that forked its
               reads would hand every child thread the same
               non-thread-safe capture and their writes would race —
               silently losing sites, which reaches commit as MISSING
               ownership. The candidate carries the thread that opened
               it, INSIDE the conveyed value, so the child compares
               against the originator and disagrees. The refusal happens
               BEFORE the probe, so a forked read performs no observation
               work at all."
       (let [{:keys [query]} sub-007]
         (rf/reg-sub (first query) (fn [db _] (:v db)))
         (seed! fid {:v 1})
         (let [c    (cell/cell :shell/panel)
               cand (cell/candidate c fid)
               forked (atom nil)]
           (cell/with-capture cand
             (fn []
               (cell/observe! query)
               ;; The classification happens ON the child thread, so what is
               ;; asserted is the diagnostic the fork itself saw — not a
               ;; wrapper the join put around it.
               (reset! forked @(future (conf/caught-id #(cell/observe! query))))))
           (is (= (:forked-error-id sub-007) @forked))
           (is (= (:recorded-sites sub-007) (count (cell/candidate-reads cand)))
               "the forked read recorded nothing — the render thread's own site is
                the only one in the capture"))))))

#?(:cljs
   (deftest fh-sub-007-the-single-threaded-host-has-no-fork-to-refuse
     (testing "Per FH-SUB-007 (hazard: same-thread capture, the
               ClojureScript half): this host has ONE thread, so there is
               no conveyance to detect and the guard compiles away
               entirely. What it CAN answer is that the capture is scoped
               to the render — the same law, asked the way a
               single-threaded host can ask it. The JVM arm above proves
               the conveyance refusal."
       (let [{:keys [query]} sub-007]
         (rf/reg-sub (first query) (fn [db _] (:v db)))
         (seed! fid {:v 1})
         (let [c    (cell/cell :shell/panel)
               cand (cell/candidate c fid)
               escaped (atom nil)]
           (cell/with-capture cand (fn [] (cell/observe! query)))
           ;; A callback the render handed to a host API runs LATER, outside
           ;; the render's extent — the single-threaded analogue of a fork.
           (reset! escaped (conf/caught-id #(rf/with-frame fid (cell/observe! query))))
           (is (= (:outside-render-error-id sub-007) @escaped))
           (is (= (:recorded-sites sub-007) (count (cell/candidate-reads cand)))))))))

;; ===========================================================================
;; The shell's own value surface
;; ===========================================================================

(deftest the-observation-port-abi-is-pinned
  (testing "The shell is the observation port's consumer and pins the ABI
            it was written against; a core that predates the node-identity
            axis it compares is a boot error, not a silently-missed
            correction."
    (is (= cell/expected-observation-port-abi obs/port-abi-version))))

(deftest a-declared-view-is-still-a-descriptor
  (testing "The shell adds no second declaration form — a view is the
            same non-callable descriptor F1 declared, mounted the same
            way."
    (is (true? (v/view? (descriptor/declare-view {:view-id         :shell/probe
                                                  :children-policy :optional}))))))
