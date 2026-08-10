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

(deftest a-running-runtime-with-nothing-mounted-is-IDLE-not-absent
  (setup!)
  (let [ids (testids (show! :mounted))]
    (is (contains? ids "rf-xray-hicasso-idle")
        "Hicasso answered and nothing is mounted — the one empty that is a
         clean bill of health")
    (is (not (contains? ids "rf-xray-hicasso-absent"))
        "an idle runtime must NOT render as `no Hicasso on this host` — those
         are unrelated facts with unrelated remedies")
    (is (string/includes? (text-of (show! :mounted)) "clean bill of health"))))

(deftest a-host-without-hicasso-is-ABSENT-not-idle
  (setup!)
  (with-redefs [reads/evidence (constantly {:mounted-boundaries nil
                                            :read-attribution   nil
                                            :intents            nil
                                            :explain-render     nil})]
    (let [ids (testids (show! :mounted))]
      (is (contains? ids "rf-xray-hicasso-absent"))
      (is (not (contains? ids "rf-xray-hicasso-idle"))))))

(deftest an-unparseable-schema-is-MISMATCH-and-suppresses-rows
  (setup!)
  ;; A producer that has EVOLVED: real rows, stamped a version this build was
  ;; not taught. Rows are suppressed rather than mis-parsed — an evolved shape
  ;; read as though it were the expected one is worse than no rows at all.
  (let [release  (mount! (fn [_] (h/sub [:htab/left]) nil))
        evolved  (assoc (tool/read-mounted-boundaries)
                        :schema :re-frame.hicasso.evidence/v99)]
    (is (seq (:boundaries evolved))
        "NON-VACUITY: the envelope being refused really does carry rows")
    (with-redefs [reads/evidence (constantly {:mounted-boundaries evolved
                                              :read-attribution   evolved
                                              :intents            evolved
                                              :explain-render     evolved})]
      (let [tree (show! :mounted)
            ids  (testids tree)]
        (is (contains? ids "rf-xray-hicasso-mismatch"))
        (is (not (contains? ids "rf-xray-hicasso-idle")))
        (is (string/includes? (text-of tree) "not taught to parse"))))
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
    (is (not (contains? ids "rf-xray-hicasso-idle")))
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
    (is (contains? ids "rf-xray-hicasso-edge-htab-left"))
    (is (string/includes? txt "fan-out 2") ":htab/left is read by both boundaries")
    (is (string/includes? txt "fan-out 1") ":htab/right by one")
    (is (string/includes? txt "[:htab/left] + [:htab/right]")
        "each edge names the boundaries holding it, by the same key the
         mounted roster uses")
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
        (is (string/includes? txt "lead"))))
    (release)))

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
