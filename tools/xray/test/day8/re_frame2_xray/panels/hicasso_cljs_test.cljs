(ns day8.re-frame2-xray.panels.hicasso-cljs-test
  "The Hicasso tab, over a REAL Hicasso runtime (rf2-hic-023).

  ## The tiny consumer app is real, and that is the point

  Every row below mounts actual boundaries through the actual commit seam
  (`collector/render-body` then `collector/commit-boundary!` — the same
  `subscribe` closure React calls, which is why this is answerable in
  Node), then renders the panel's hiccup over the projection those
  boundaries produced. Nothing is stubbed between the runtime and the
  screen: the acceptance is that the four views ANSWER on a running app,
  and a suite built on hand-written envelopes would demonstrate only that
  the renderer can render.

  The pure algebra has its own suite (`hicasso_helpers_cljs_test.cljc`);
  what is asserted here is the part that needs the runtime.

  ## Byte-for-byte, asserted once rather than twice

  Xray and the AI pair consume the same door and the same bytes. That is
  structural — [[the-seam-reshapes-nothing]] pins Xray's whole chain, from
  the read seam through the subscription the view derefs, as `pr-str`
  identical to what the producer emitted. A consumer that reshaped could
  not be, and two consumers that each merely *validate* an envelope
  separately would drift without either of them noticing.

  ## The loss states are DISTINGUISHED on the page

  [[the-loss-states-render-under-distinct-testids]] drives the runtime
  between two genuinely different states — a retained window and an empty
  one — and asserts the rendered DOM carries different testids in each. An
  assertion that a loss state merely exists in the data would not have
  caught a panel that drew both the same."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso :as hicasso]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]
            [day8.re-frame2-xray.panels.hicasso-reads :as reads]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.tool :as tool]
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private app-frame ::hicasso-tab-app)

(rf/reg-sub :htab/left  (fn [db _] (:left db)))
(rf/reg-sub :htab/right (fn [db _] (:right db)))
(rf/reg-event :htab/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :htab/bump (fn [{:keys [db]} _] {:db (update db :left inc)}))

;; The UIx adapter, not the Xray suite's plain-atom default — and RESTORED
;; at the end of the namespace.
;;
;; It is not a preference. Hicasso's cell wiring calls `add-watch` on the
;; substrate's derived value, and plain-atom's is not `IWatchable`, so a
;; boundary mounted under it throws `No protocol method IWatchable.-add-watch`
;; before any projection exists to assert on. A Hicasso witness needs a
;; reactive substrate, which is why the package's own suites take one too.
;;
;; THE `:once` RESTORE IS LOAD-BEARING, and was measured. `install-adapter!`
;; is process-global and the `:each` fixture never puts back what it found,
;; so leaving UIx installed handed it to every namespace that runs after
;; this one and installs no adapter of its own — 63 failures and 124 errors
;; across the machine-inspector reactivity suites, none of them about
;; anything this bead touches. Restoring the Xray default is what makes
;; taking a different substrate for one namespace a local decision.
;;
;; `:post-reset` is load-bearing for a second reason: the Hicasso runtime's
;; tables are process-global `defonce`s and the core fixture knows nothing
;; about them, so without it a boundary mounted by one test is still in the
;; entry cache for the next and every roster assertion counts a neighbour's
;; rows — measured as a Why-view row whose peak epoch belonged to a cell the
;; previous test had left behind.
;; Fn-form, like the `:each` below it — `cljs.test` refuses a namespace whose
;; `:once` and `:each` fixtures are different types, and every test here is
;; synchronous, so the restore simply follows the body.
(use-fixtures :once
  (fn [run-tests]
    (run-tests)
    ((xray-test-support/make-xray-runtime-fixture) (fn []))))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:adapter    uix-adapter/adapter
     :post-reset (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- testids
  "Every `data-testid` in the rendered tree — the panel's observable
  surface, and the thing a browser assertion selects on."
  [tree]
  (into #{}
        (keep (fn [node]
                (when (and (vector? node) (map? (second node)))
                  (:data-testid (second node)))))
        (hiccup-seq tree)))

(defn- text-of [tree]
  (->> (hiccup-seq tree) (filter string?) (string/join " ")))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:htab/seed {:left 1 :right 2}]))
  nil)

(defn- mount!
  "A real boundary, rendered and committed. Answers React's cleanup."
  [body-fn]
  (collector/render-body app-frame body-fn {})
  (collector/commit-boundary! (collector/last-reads) (fn [])))

(defn- refresh!
  "Drop Xray's sub cache so `:rf.xray.hicasso/data` recomputes against the
  runtime as it stands NOW.

  The tab is a live projection of process-global Hicasso tables rather
  than of Xray's app-db, so a held reaction has nothing to invalidate it
  when a boundary mounts. In the running panel the `:rf.xray/trace-buffer`
  tick does this job; a Node witness that never runs the trace collector
  drops the cache instead."
  []
  (rf/clear-sub-cache! :rf/xray))

(defn- show!
  "Select `view`, refresh, and render the panel."
  [view]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.hicasso/set-view view])
    (refresh!)
    (hicasso/Panel)))

(defn- render-now
  "Render the panel the way the RUNNING shell renders it — no `refresh!`.

  Every other row here reaches the runtime through `show!`, and `show!`
  drops Xray's sub cache first. That is a harness mechanism; the shipped
  panel has no such thing. This one renders against whatever the held
  reaction currently answers, which is the only way to ask whether the tab
  is a live projection or a screenshot of one."
  []
  (rf/with-frame :rf/xray (hicasso/Panel)))

(defn- tick-trace!
  "One trace-buffer tick, delivered the way the collector delivers it.

  `trace-collector/refresh-trace-rings!` dispatches
  `:rf.xray/sync-trace-buffer` with its snapshot on every coalesced task
  drain; `:rf.xray/trace-buffer` reads the slot that dispatch writes
  (rf2-43koh). Dispatching it directly is therefore the collector's own
  seam, not a stand-in for it."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                       [{:id 1 :op-type :rf.event
                         :operation :rf.event/dispatched :tags {}}]])))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest the-tab-registers-as-a-dynamic-l4-tab
  (setup!)
  (let [tab (panel-registry/tab-by-id :dynamic :hicasso)]
    (is (some? tab) "the Hicasso tab must be in the Dynamic tab registry")
    (is (= "Hicasso" (:label tab)))
    (is (fn? (:panel tab))))
  (testing "and the focus mirror knows it — a drifting mirror fails the build"
    (is (contains? focus/valid-panels :hicasso))
    (is (= (panel-registry/tab-ids-for-mode :dynamic) focus/valid-panels))))

;; ---------------------------------------------------------------------------
;; THE HONEST EMPTIES — three states, three renderings
;; ---------------------------------------------------------------------------

(deftest a-running-runtime-with-nothing-mounted-is-EMPTY-not-absent
  (setup!)
  (let [tree (show! :mounted)
        ids  (testids tree)]
    (is (contains? ids "rf-xray-hicasso-empty-mounted")
        "Hicasso answered and no boundary holds a read edge — a survey result")
    (is (not (contains? ids "rf-xray-hicasso-absent"))
        "an empty runtime must NOT render as `no Hicasso on this host` — those
         are unrelated facts with unrelated remedies")
    (is (string/includes? (text-of tree) "survey result"))))

(deftest an-empty-roster-says-something-different-in-each-view
  ;; AUDIT #7789, CORRECTNESS 3, on the page. One `:idle` note — "nothing is
  ;; mounted, a clean bill of health" — rendered under all four views. Under
  ;; Intents it told the reader a CAPPED window proved nothing had been
  ;; dispatched. The four empties are now four testids and four sentences,
  ;; and this drives the real panel to prove it.
  (setup!)
  (let [by-view (into {} (map (fn [v] [v (show! v)])) [:mounted :attribution
                                                       :intents :explain])]
    (testing "each view renders its OWN empty testid and no other view's"
      (doseq [[view suffix] [[:mounted     "rf-xray-hicasso-empty-mounted"]
                             [:attribution "rf-xray-hicasso-empty-attribution"]
                             [:intents     "rf-xray-hicasso-empty-intents"]
                             [:explain     "rf-xray-hicasso-empty-explain"]]]
        (let [ids (testids (get by-view view))]
          (is (contains? ids suffix)
              (str view " must render its own empty note"))
          (is (= 1 (count (filter #(string/starts-with? % "rf-xray-hicasso-empty-") ids)))
              (str view " must render exactly one empty note — not a second view's")))))

    (testing "the Intents empty is a CAP, and never a clean bill of health"
      (let [txt (text-of (get by-view :intents))]
        (is (string/includes? txt "CAP"))
        (is (string/includes? txt "cannot say whether anything was dispatched"))
        (is (not (string/includes? txt "clean bill of health"))
            (str "the mounted census's verdict must not be read out here — an "
                 "empty ring is a knob setting, not a finding"))))

    (testing "and the cap's own loss note is rendered even with no rows at all"
      (is (contains? (testids (get by-view :intents)) "rf-xray-hicasso-intents-origin")
          (str "a view that showed its qualifications only when it had rows would "
               "drop them exactly where the reader has least else to go on")))))

(deftest the-mounted-census-does-not-claim-the-screen
  ;; AUDIT #7792. The census cannot distinguish Activity-hidden from
  ;; unmounted, and lists a Suspense-fallback-hidden subtree though it is
  ;; off screen. The panel states that beside the rows rather than leaving
  ;; the reader to supply the word "visible".
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))
        tree    (show! :mounted)]
    (is (contains? (testids tree) "rf-xray-hicasso-mounted-visibility"))
    (is (string/includes? (text-of tree) "SUBSCRIPTION"))
    (is (string/includes? (text-of tree) "Suspense"))
    (release)))

(deftest a-host-without-hicasso-is-ABSENT-not-empty
  (setup!)
  (with-redefs [reads/evidence (constantly {:mounted-boundaries nil
                                            :read-attribution   nil
                                            :intents            nil
                                            :explain-render     nil})]
    (let [ids (testids (show! :mounted))]
      (is (contains? ids "rf-xray-hicasso-absent"))
      (is (not (contains? ids "rf-xray-hicasso-empty-mounted"))))))

(deftest an-unparseable-schema-is-MISMATCH-and-suppresses-rows
  (setup!)
  ;; Real rows, stamped a version this build was not taught. Rows are
  ;; suppressed rather than mis-parsed — a shape read as though it were the
  ;; expected one is worse than no rows at all.
  ;;
  ;; BOTH DIRECTIONS, because the pin is exact rather than a floor. `/v99` is
  ;; a producer that evolved ahead of this build. `/v1` is the SUPERSEDED
  ;; stamp, and it is the one that matters: the #7789 repair moved the wire
  ;; shape and left the stamp behind, so for one increment a v1 envelope
  ;; carrying a v2 shape parsed as exact (audit #7802). There is no v1
  ;; acceptance path — the predecessor mismatches on the page, like anything
  ;; else this build was not taught.
  (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))]
    (doseq [stamp [:re-frame.hicasso.evidence/v1
                   :re-frame.hicasso.evidence/v99]]
      (let [other (assoc (tool/read-mounted-boundaries) :schema stamp)]
        (is (seq (:boundaries other))
            "NON-VACUITY: the envelope being refused really does carry rows")
        (with-redefs [reads/evidence (constantly {:mounted-boundaries other
                                                  :read-attribution   other
                                                  :intents            other
                                                  :explain-render     other})]
          (let [tree (show! :mounted)
                ids  (testids tree)]
            (is (contains? ids "rf-xray-hicasso-mismatch") (str stamp))
            (is (not (contains? ids "rf-xray-hicasso-empty-mounted")) (str stamp))
            (is (string/includes? (text-of tree) "not taught to parse"))))))
    (release)))

;; ---------------------------------------------------------------------------
;; THE FOUR VIEWS ANSWER, ON A RUNNING APP
;; ---------------------------------------------------------------------------

(deftest the-mounted-view-answers-which-boundaries-are-mounted
  (setup!)
  (let [a (mount! (fn [_] (h/sub [:htab/left]) nil))
        b (mount! (fn [_] (h/sub [:htab/left]) nil))
        c (mount! (fn [_] (h/sub [:htab/left]) (h/sub [:htab/right]) nil))
        tree (show! :mounted)
        txt  (text-of tree)
        ids  (testids tree)]
    (is (contains? ids "rf-xray-hicasso-mounted"))
    (is (not (contains? ids "rf-xray-hicasso-empty-mounted")))
    (is (string/includes? txt "2 instances")
        "the two boundaries with one edge set report as one row of two")
    (is (string/includes? txt "[:htab/left]"))
    (is (string/includes? txt "[:htab/left] + [:htab/right]"))
    (testing "the view name is opaque, and the row SHOWS that rather than blanking"
      (is (some #(string/ends-with? % "-view-loss-opaque") ids)))
    (testing "React's half is spelled out once, in full, beneath the roster"
      (is (contains? ids "rf-xray-hicasso-mounted-host"))
      (is (string/includes? txt "React DevTools")))
    (a) (b) (c)))

(deftest the-reads-view-answers-which-boundaries-read-each-subscription
  (setup!)
  (let [a (mount! (fn [_] (h/sub [:htab/left]) nil))
        b (mount! (fn [_] (h/sub [:htab/left]) (h/sub [:htab/right]) nil))
        tree (show! :attribution)
        txt  (text-of tree)
        ids  (testids tree)]
    (is (contains? ids "rf-xray-hicasso-attribution"))
    (is (string/includes? txt "fan-out 2") ":htab/left is read by both boundaries")
    (is (string/includes? txt "fan-out 1") ":htab/right by one")
    (is (string/includes? txt "[:htab/left] + [:htab/right]")
        "each edge names the boundaries holding it, by the same key the
         mounted roster uses")
    (testing "each row carries its WHOLE projected identity in the testid (audit #7802)"
      (let [edge-ids (into #{} (filter #(and (string/starts-with? % "rf-xray-hicasso-edge-")
                                             (not (string/ends-with? % "-readers"))))
                           ids)]
        (is (= 2 (count edge-ids)) "two cells, two testids")
        (is (every? #(string/includes? % "hicasso-tab-app") edge-ids)
            (str "an edge testid must carry the FRAME as well as the sub id — "
                 "frames are isolated contexts, so two frames holding one sub id "
                 "are two rows and not one seen twice"))
        (is (some #(string/includes? % "htab-left") edge-ids))
        (is (some #(string/includes? % "htab-right") edge-ids))))
    (testing "and the frame is on the page, not merely in the testid"
      (is (string/includes? txt (str "frame " (hh/format-id app-frame)))
          "a reader looking at two identically-labelled rows needs the frame"))
    (a) (b)))

(deftest the-intents-view-answers-what-was-dispatched
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))]
    (rf/with-frame app-frame (rf/dispatch-sync [:htab/bump]))
    (let [tree (show! :intents)
          txt  (text-of tree)
          ids  (testids tree)]
      (is (contains? ids "rf-xray-hicasso-intents"))
      (is (string/includes? txt ":htab/bump"))
      (is (string/includes? txt "(not carried)")
          "the panel says the arguments are absent BY DESIGN, so a reader does
           not read the absence as a bug")
      (testing "the summary states the window's cap on every render, good or bad"
        (is (string/includes? txt "capped")))
      (testing "whether a run began at markup is opaque, and the note says so"
        (is (contains? ids "rf-xray-hicasso-intents-origin"))))
    (release)))

(deftest the-why-view-answers-which-reads-changed-and-refuses-to-answer-why
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:htab/left]) (h/sub [:htab/right]) nil))]
    (rf/with-frame app-frame (rf/dispatch-sync [:htab/bump]))
    (let [tree (show! :explain)
          txt  (text-of tree)
          ids  (testids tree)]
      (is (contains? ids "rf-xray-hicasso-explain"))
      (testing "PROVEN: the reads at the boundary's peak epoch, off the stamps"
        (is (some #(string/ends-with? % "-proven") ids))
        (is (string/includes? txt "moved most recently:"))
        (is (string/includes? txt ":htab/left"))
        (is (string/includes? txt "snapshot ")
            "React's own comparison number is on screen, so a reader can see
             what a bail-out would have been decided on")
        (is (not (string/includes? txt "reads nothing"))
            "a boundary WITH reads must never render the read-free phrase"))
      (testing "UNCORRELATED: the cause is a labelled absence, beside a lead count"
        (is (some #(string/ends-with? % "-cause") ids))
        (is (some #(string/ends-with? % "-loss-uncorrelated") ids))
        (is (string/includes? txt "lead")))
      (testing "the FRAME is on the row, and in its testid (audit #7802)"
        (is (string/includes? txt (str "frame " (hh/format-id app-frame)))
            (str "two boundaries reading one query in two frames have the same "
                 "label, so without the frame the reader sees one line twice"))
        (is (some #(and (string/starts-with? % "rf-xray-hicasso-explain-")
                        (string/includes? % "hicasso-tab-app"))
                  ids))))
    (release)))

;; ---------------------------------------------------------------------------
;; AND THEY KEEP ANSWERING AS THE APPLICATION RUNS
;; ---------------------------------------------------------------------------

(deftest the-populated-roster-arrives-on-the-TRACE-TICK-and-not-on-a-cache-clear
  ;; rf2-r98a. The populated arms above are all reached through `show!`,
  ;; and `show!` drops Xray's sub cache first. So every one of them proves
  ;; what the panel renders GIVEN a fresh projection, and none of them
  ;; proves the projection ever refreshes on its own — the tab's liveness
  ;; was a comment on `:rf.xray.hicasso/data` and nothing else.
  ;;
  ;; That is the property a browser row was proposed for, and it does not
  ;; need one. The tab is live because the sub composes off
  ;; `:rf.xray/trace-buffer`, and that signal is an ordinary app-db slot
  ;; written by an ordinary dispatch, so the tick is drivable right here.
  ;;
  ;; The middle assertion is the load-bearing one. Hicasso's tables are
  ;; process-global rather than part of Xray's app-db, so a mount moves
  ;; nothing the held reaction watches — which is precisely why a panel
  ;; wired to no tick at all would sit on an empty roster forever while
  ;; the application it is inspecting mounts boundaries. Without that
  ;; control the last assertion would pass on a reaction that had simply
  ;; never been computed before the tick.
  (setup!)
  (let [empty-testid "rf-xray-hicasso-empty-mounted"]
    (is (contains? (testids (show! :mounted)) empty-testid)
        "the projection is computed and HELD while nothing is mounted")
    (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))]
      (is (contains? (testids (render-now)) empty-testid)
          "NON-VACUITY: a real mount alone leaves the held reaction stale,
           so the tick below is the only thing that can move this roster")
      (tick-trace!)
      (let [ids (testids (render-now))]
        (is (contains? ids "rf-xray-hicasso-mounted")
            "the trace tick re-fired the projection and the roster arrived —
             with no cache clear anywhere in this test")
        (is (not (contains? ids empty-testid))
            "and the empty note is gone, so the roster replaced it rather
             than rendering beside it"))
      (release))))

;; ---------------------------------------------------------------------------
;; THE LOSS STATES ARE DISTINGUISHABLE ON THE PAGE
;; ---------------------------------------------------------------------------

(deftest the-loss-states-render-under-distinct-testids
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))]
    (rf/with-frame app-frame (rf/dispatch-sync [:htab/bump]))

    (testing "a live window renders the UNCORRELATED chip"
      (let [ids (testids (show! :explain))]
        (is (some #(string/ends-with? % "-loss-uncorrelated") ids))
        (is (not-any? #(string/ends-with? % "-cause-loss-cap") ids))))

    (testing "an EMPTY window renders the CAP chip in the same place — a
              different testid, a different word, driven by a real change"
      (trace-tooling/clear-trace-buffer! app-frame)
      (let [tree (show! :explain)
            ids  (testids tree)
            txt  (text-of tree)]
        (is (some #(string/ends-with? % "-loss-cap") ids))
        (is (not-any? #(string/ends-with? % "-cause-loss-uncorrelated") ids))
        (is (string/includes? txt "leads not searched")
            "and the leads are not rendered as an empty list, which would read
             as `nothing recomputed anything`")))
    (release)))

;; ---------------------------------------------------------------------------
;; A SENSITIVE QUERY ARGUMENT REACHES NEITHER THE TEXT NOR A TESTID
;; ---------------------------------------------------------------------------

(def ^:private the-secret
  "A value that exists nowhere else in this process, so finding it in the
  rendered tree is proof of egress rather than a coincidence of spelling."
  "RF2-HIC-023-PANEL-SECRET-4c1e8a07")

(deftest a-sensitive-query-argument-reaches-neither-the-page-nor-a-testid
  ;; AUDIT #7789, CORRECTNESS 1 — the half a data-only assertion cannot
  ;; reach. The producer's key carried raw query arguments, and these
  ;; helpers then PRINTED them in a boundary label and hashed them into a
  ;; DOM testid. So the escape was observable on the page, under a
  ;; `data-testid` a screenshot or a browser assertion would carry off the
  ;; developer's box. An envelope-only control would have missed it, which
  ;; is exactly what happened.
  ;;
  ;; Frame destruction is the forcing function: there the door PROMISES to
  ;; fail closed, so nothing derived from the query may render.
  (setup!)
  (mount! (fn [_] (h/sub [:htab/left the-secret]) nil))
  (rf/with-frame app-frame (rf/dispatch-sync [:htab/bump]))

  (testing "NON-VACUITY: with the frame alive the argument really is on the page"
    (is (string/includes? (text-of (show! :mounted)) the-secret)
        (str "the classification model is fail-open, so an undeclared argument "
             "rides as itself while the frame lives. If this row fails, the "
             "assertions below are passing against a panel that was never "
             "shown the value at all")))

  (rf/destroy-frame! app-frame)
  (doseq [view [:mounted :attribution :explain]]
    (let [tree (show! view)]
      (is (not (string/includes? (text-of tree) the-secret))
          (str "the " view " view rendered a redacted query's argument as text"))
      (is (not-any? #(string/includes? % the-secret) (testids tree))
          (str "the " view " view put a redacted query's argument in a DOM "
               "testid — which is where it leaves the box"))))

  (testing "and the rows are still THERE, so the redaction is not merely an empty page"
    (let [ids (testids (show! :mounted))]
      (is (not (contains? ids "rf-xray-hicasso-empty-mounted"))
          (str "a panel that rendered nothing would pass every assertion above "
               "while proving none of them")))))

;; ---------------------------------------------------------------------------
;; BYTE-FOR-BYTE
;; ---------------------------------------------------------------------------

(deftest the-seam-reshapes-nothing
  (setup!)
  (let [release (mount! (fn [_] (h/sub [:htab/left]) nil))]
    (rf/with-frame app-frame (rf/dispatch-sync [:htab/bump]))
    (testing "the read seam answers the producer's own bytes"
      (doseq [[door seam] [[tool/read-mounted-boundaries reads/mounted-boundaries]
                           [tool/read-read-attribution   reads/read-attribution]
                           [tool/read-intents            reads/intents]
                           [tool/explain-render          reads/explain-render]]]
        (is (= (pr-str (door)) (pr-str (seam)))
            "a seam that reshaped could not be byte-identical to its door")))
    (testing "and so does the subscription the VIEW derefs — the whole chain"
      (refresh!)
      (let [held (rf/with-frame :rf/xray
                   (:envelopes @(rf/subscribe [:rf.xray.hicasso/data])))]
        (is (= (pr-str (tool/read-mounted-boundaries))
               (pr-str (:mounted-boundaries held))))
        (is (= (pr-str (tool/read-read-attribution))
               (pr-str (:read-attribution held))))))
    (release)))

(deftest the-consumer-pin-tracks-the-producer-today-and-detects-a-bump
  (testing "the pin is a LITERAL, not the producer's var — but today they agree"
    (is (= evidence/schema hh/consumed-evidence-schema)
        (str "Xray's pin and the Hicasso producer's schema have diverged. That "
             "is the pin doing its job: teach this build the new shape, then "
             "bump the pin in the same change."))
    (is (= evidence/producer hh/consumed-producer))))
