(ns re-frame.freehand.bench.b10-two-clock-dom-cljs-test
  "B10's rows — DC-09's two-clock operating envelope, measured.

  This file is MEASUREMENT, not a law. DC-09 asks for numbers and for a
  recommendation about whether new vocabulary is warranted; it does not
  ask for a new invariant, and the deterministic property the numbers sit
  on top of — a controlled input drops nothing while its frame is busy —
  is already rostered as FH-INPUT-003, whose own suite says in as many
  words that the latency distributions under contention 'belong to the
  measurement spine'. This is that spine. No `FH-…` id is minted here and
  nothing enrols in the conformance roster.

  What DOES gate here is the instrument. Every deterministic fact the
  numbers depend on is asserted before any figure is quoted:

    * the fixture's arithmetic (300 cells, the ladder, the two config
      shapes and their leaf counts);
    * C2 — each mode's PREDICTED DOM mutation count against the count a
      `MutationObserver` measured;
    * the flatness of the host-only arm across the sibling ladder, which
      is what proves the ladder does not leak into the arm that must not
      feel it;
    * zero dropped keystrokes and an intact caret at every rung, before
      a single latency is discussed — DC-09's acceptance criterion 2, in
      that order deliberately.

  The distributions themselves are `:status :evidence` under D021 and
  cannot gate: `re-frame.freehand.bench.measure` refuses to construct a
  duration measurement carrying a threshold at all.

  Runtime: these figures are CHROME. Nothing here is quoted for Node or
  for the JVM, and no share measured here transfers to either as an
  absolute."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b10-two-clock :as b10]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.test-support :as test-support]))

(def ^:private rounds 4)
(def ^:private sampling {:warmup 3 :samples 10})
(def ^:private burn-ms 4.0)
(def ^:private drive-ms 700)

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture))
             (h/leave-act-environment!))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? [] (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip [] (is true "a real browser is required — the browser job runs this row"))

;; ===========================================================================
;; The fixture is what it says it is
;; ===========================================================================

(deftest the-fixture-is-what-it-says-it-is
  (testing "Arithmetic over the fixture, so a reader can check every
            premise the tables rest on without re-running anything. The
            widest rung must be a PARTIAL update — a 200-sibling crossing
            that happened to move the whole page would be measuring
            something else entirely."
    (is (= 300 b10/cells-n))
    (is (= [0 1 10 50 200] b10/sibling-ladder))
    (is (= [30 60 120 240] b10/rate-ladder))
    (is (< (inc (apply max b10/sibling-ladder)) b10/cells-n)
        "the widest rung leaves cells untouched, so it is a partial update")
    (is (= 12 (count (:encoding (b10/build-config :vega 0))))
        "vega-shaped: one encoding channel per declared channel")
    (is (= 96 (get-in b10/config-shapes [:vega :leaves]))
        "and eight leaves per channel, which is what the equality walk costs")
    (is (= 900 (count (b10/build-config :spread 0)))
        "spread-shaped: one flat entry per declared cell")
    (is (= (b10/build-config :spread 0) (b10/build-config :spread 0))
        "two builds at the same generation are EQUAL — the fresh-equal arm
         has something to be equal to")
    (is (not (identical? (b10/build-config :spread 0) (b10/build-config :spread 0)))
        "and are NOT identical — so the arm really does move the reference")
    (is (not= (b10/build-config :spread 0) (b10/build-config :spread 1))
        "and a generation apart they differ — the one-leaf arm really changes a leaf")))

;; ===========================================================================
;; C2 — the size control: predicted DOM mutations against measured
;; ===========================================================================

(deftest c2-every-mode-moves-exactly-the-nodes-its-arithmetic-predicts
  (testing "The positive control on SIZE. Each mode's mutation count is
            computed from the fixture — `k + 1` for a crossing that
            dirties k siblings beside the leaf, one attribute for the
            host clock, and NONE for a fresh-but-equal write — and
            compared against what a MutationObserver actually saw. A
            sibling knob that does not dirty what it claims, or an
            equality arm that is fast because it did nothing, fails here
            rather than in a conclusion."
    (if-not (browser?) (skip)
      (async done
        (let [mnt (b10/mount! :vega)]
          (try
            (doseq [k b10/sibling-ladder
                    mode [:host-only :fresh-equal :one-leaf]]
              (let [arm (b10/sibling-mode mode k)
                    ;; one unmeasured warm crossing, then the counted one
                    _   ((:run! arm) (:container mnt) (:observer mnt))
                    r   ((:run! arm) (:container mnt) (:observer mnt))]
                (is (:ok? r)
                    (str mode " at k=" k ": the crossing was verified at the DOM"))
                (is (= (:predicted-mutations arm) (get-in r [:mutations :total]))
                    (str mode " at k=" k ": predicted "
                         (:predicted-mutations arm) " DOM mutations, measured "
                         (get-in r [:mutations :total])
                         " " (pr-str (get-in r [:mutations :by-type]))))))
            (finally (b10/release! mnt) (done))))))))

;; ===========================================================================
;; C1 — the duration control
;; ===========================================================================

(deftest c1-a-window-of-computed-duration-reads-its-own-duration
  (testing "The positive control on DURATION, and the machine's own
            contention meter. A busy-wait of exactly 4.00 ms is timed
            through the same window every figure in this file is timed
            through. The overshoot is clock quantisation plus whatever
            the operating system took away, and it is published beside
            the tables so a contended round is read as a contended round
            rather than as a slow substrate.

            The assertion is deliberately loose — this is EVIDENCE about
            the machine, and a tight bound would be a flake on a box
            running other agents. What it must not do is read BELOW the
            predicted duration, which would mean the window is not
            measuring what it brackets."
    (if-not (browser?) (skip)
      (async done
        (let [mnt (b10/mount! :vega)
              arm (b10/burn-arm burn-ms)]
          (try
            (let [rs (vec (for [_ (range 12)] ((:run! arm) (:container mnt) (:observer mnt))))
                  s  (m/summarise (mapv :total-ms rs))
                  ov (- (:p50 s) burn-ms)]
              (is (>= (:min s) burn-ms)
                  (str "no sample read below its own predicted " burn-ms
                       " ms; min was " (:min s)))
              (is (zero? (count (remove :ok? rs))))
              (h/publish! "B10 C1 / duration control"
                          {:control        :control-burn
                           :predicted-ms   burn-ms
                           :measured       s
                           :p50-overshoot-ms (/ (js/Math.round (* ov 1000)) 1000.0)
                           :note (str "overshoot is instrument quantisation plus machine "
                                      "contention; every B10 table is read on this scale")}))
            (finally (b10/release! mnt) (done))))))))

;; ===========================================================================
;; M1 — the sibling ladder, in both orders
;; ===========================================================================

(defn- ladder-round!
  [mnt ks]
  (into {}
        (map (fn [k]
               [k (b10/run-arms! mnt
                                 [(b10/sibling-mode :host-only k)
                                  (b10/sibling-mode :fresh-equal k)
                                  (b10/sibling-mode :one-leaf k)
                                  (b10/burn-arm burn-ms)]
                                 sampling)]))
        ks))

(deftest m1-the-semantic-crossing-costs-what-the-ladder-says
  (testing "The service time of ONE semantic crossing, as a function of
            how many siblings the application chose to dirty with it.
            This is the whole envelope: at an offered rate R the semantic
            clock has 1000/R ms per event, and the envelope is where the
            crossing's cost crosses that budget.

            Half the rounds walk the ladder ASCENDING and half
            DESCENDING, and both are published. A wide arm inflating its
            successor is a documented hazard here and it is only visible
            from the two orders side by side."
    (if-not (browser?) (skip)
      (async done
        (let [mnt (b10/mount! :vega)]
          (try
            (let [up   (vec (for [_ (range (/ rounds 2))]
                              (ladder-round! mnt b10/sibling-ladder)))
                  down (vec (for [_ (range (/ rounds 2))]
                              (ladder-round! mnt (reverse b10/sibling-ladder))))
                  all  (into up down)
                  unv  (reduce + (for [r all, [_ arms] r, [_ s] arms] (:unverified s)))
                  tot  (reduce + (for [r all, [_ arms] r, [_ s] arms] (:n s)))
                  host (for [r all, [_ arms] r] (get-in arms [:host-only :total-ms :p50]))]
              (is (zero? unv)
                  (str "every measured crossing was read back out of the DOM inside its own
                        window — " unv " unverified of " tot))
              ;; The ladder must not leak into the arm that never crosses.
              (is (< (- (apply max host) (apply min host)) 1.0)
                  (str "the host-only arm is FLAT across the ladder — spread "
                       (- (apply max host) (apply min host)) " ms over "
                       (count host) " readings. A host clock that felt the sibling knob
                       would mean the ladder is contaminating an arm that never crosses."))
              (h/publish!
                "B10 M1 / sibling ladder"
                (b10/record
                  :B10/sibling-ladder
                  "service time of one semantic crossing against dirty-sibling count"
                  {:cells        b10/cells-n
                   :ladder       b10/sibling-ladder
                   :config-shape :vega
                   :arms         [:host-only :fresh-equal :one-leaf :control-burn]
                   :crossing     :dispatch-sync
                   :unverified   unv
                   :of           tot
                   :measurement-method
                   (str "t0; react-dom/flushSync around rf/dispatch-sync, with t1 taken "
                        "INSIDE the flush the instant the dispatch returns; t2 after the "
                        "flush. :event-ms is t1-t0 (router, interceptors, reducer, app-db "
                        "install, subscription notification); :commit-ms is t2-t1 (React "
                        "render, commit, DOM mutation). The crossing is then read back out "
                        "of the DOM — or, for the fresh-equal arm whose whole point is that "
                        "the page must NOT move, out of the store, by proving the reference "
                        "moved and the value did not. Arm order rotates on the sample index; "
                        "the LADDER is walked ascending in half the rounds and descending in "
                        "the other half, both published. A control-burn arm of computed "
                        "4.00 ms duration rides in every cell as the machine's contention "
                        "meter.")}
                  (assoc sampling :rounds rounds)
                  {:ascending  up
                   :descending down}))
              (h/publish! "B10 M1 / derived envelope"
                          {:note (str "budget at rate R is 1000/R ms; the envelope is the "
                                      "largest R whose budget exceeds the crossing's p95")
                           :budgets-ms (into {} (map (fn [r] [r (/ 1000.0 r)])) b10/rate-ladder)
                           :p95        (into {} (map (fn [[k arms]]
                                                       [k (into {} (map (fn [[id s]]
                                                                          [id (get-in s [:total-ms :p95])]))
                                                                arms)]))
                                             (first all))}))
            (finally (b10/release! mnt) (done))))))))

;; ===========================================================================
;; M2 — equality cost, at two config shapes
;; ===========================================================================

(deftest m2-what-a-behavior-config-costs-when-it-did-not-change
  (testing "The behavior-config axis: stable-reference against
            fresh-but-`rf=`-equal against one-leaf-changed, at a
            Vega-shaped nested spec and a Spread-shaped flat range. The
            difference between the first two is the equality cost — what
            an application pays for handing a behavior a freshly built
            config that says the same thing — and the number is only
            meaningful beside the leaf counts the fixture test pins.

            The behavior's own `:update` call count is recorded alongside,
            because whether Freehand elides an update whose config is
            unchanged is a question this fixture ANSWERS by counting
            rather than asserts by reading the runtime.

            Each shape runs at BOTH read sites — the config subscription
            read in the view that also builds the 300 cells, and read one
            boundary down inside the behavior's own panel. The pair is
            here because a first pass found a config change an order of
            magnitude more expensive than a cell change while moving
            FEWER DOM nodes, and the obvious explanation deserved a
            measurement rather than a sentence."
    (if-not (browser?) (skip)
      (async done
        (let [out (atom {})]
          (try
            (doseq [shape   [:vega :spread]
                    read-at [:parent :leaf]]
              (let [mnt (b10/mount! shape read-at)]
                (try
                  (b10/reset-behavior-log!)
                  (let [before (b10/behavior-calls)
                        rs     (vec (for [_ (range rounds)]
                                      (b10/run-arms!
                                        mnt
                                        [(b10/config-mode :stable shape)
                                         (b10/config-mode :fresh-equal shape)
                                         (b10/config-mode :one-leaf shape)
                                         (b10/burn-arm burn-ms)]
                                        sampling)))
                        after  (b10/behavior-calls)]
                    (swap! out assoc [shape read-at]
                           {:rounds rs
                            :behavior-updates (- (:update after) (:update before))
                            :leaves (get-in b10/config-shapes [shape :leaves])})
                    (doseq [r rs [id s] r]
                      (is (zero? (:unverified s))
                          (str shape "/" read-at "/" id ": "
                               (:unverified s) " unverified of " (:n s)))))
                  (finally (b10/release! mnt)))))
            (h/publish!
              "B10 M2 / config equality"
              (b10/record
                :B10/config-equality
                "cost of one behavior-config crossing by equality class and config shape"
                {:shapes       (into {} (map (fn [[k v]] [k (select-keys v [:leaves :channels])]))
                                     b10/config-shapes)
                 :modes        [:stable :fresh-equal :one-leaf]
                 :read-sites   [:parent :leaf]
                 :crossing     :dispatch-sync
                 :cells        b10/cells-n
                 :measurement-method
                 (str "the same window as M1, at both read sites — :parent reads the config "
                      "subscription in the view that also builds the 300 cells, :leaf reads "
                      "it one boundary down inside the behavior's own panel; nothing else "
                      "differs between them. "
                      "the same window as M1. :stable re-installs the value already in "
                      "app-db — identical, not merely equal — and is verified by identity; "
                      ":fresh-equal installs a freshly built map of the same contents and is "
                      "verified by the reference moving while the value does not; :one-leaf "
                      "installs one differing in exactly one leaf. The behavior's :update "
                      "call count across the whole run is published beside the timings.")}
                (assoc sampling :rounds rounds)
                @out))
            (finally (done))))))))

;; ===========================================================================
;; M3 — the driven arm: offered against observed against accepted
;; ===========================================================================

(deftest m3-the-offered-rate-is-not-the-observed-rate
  (testing "The envelope, driven rather than derived. A timer offers a
            semantic event every 1000/R ms through the ORDINARY
            asynchronous `rf/dispatch` — what a pointer handler calls, and
            the only crossing that CAN build a backlog, since a
            dispatch-sync one is finished before the next offer is made.

            Four things are counted separately and none of them is
            assumed equal to another: what the application OFFERED, what
            the timer was asked for (computed, not measured), what the
            reducer APPLIED, and what the DOM committed. The gap between
            the first two is the browser refusing the rate; the gap
            between the second and third is the framework refusing it.
            They are different failures and DC-09 asks for them apart."
    (if-not (browser?) (skip)
      (async done
        (let [mnt  (b10/mount! :vega)
              runs (atom [])
              plan (for [k [0 200], r b10/rate-ladder] [k r])]
          (-> (reduce (fn [p [k r]]
                        (.then p (fn [_]
                                   (-> (b10/drive! mnt r k drive-ms)
                                       (.then (fn [rec] (swap! runs conj rec) nil))))))
                      (js/Promise.resolve nil)
                      plan)
              (.then
                (fn [_]
                  (doseq [{:keys [rate siblings offered expected applied]} @runs]
                    (is (pos? offered)
                        (str "rate " rate " / k=" siblings ": the driver ran at all"))
                    (is (<= applied offered)
                        (str "rate " rate " / k=" siblings
                             ": the reducer cannot apply more than was offered ("
                             applied " applied, " offered " offered)")))
                  (h/publish!
                    "B10 M3 / driven rates"
                    (b10/record
                      :B10/driven-rates
                      "offered against browser-observed against accepted against committed"
                      {:rates       b10/rate-ladder
                       :ladder      [0 200]
                       :duration-ms drive-ms
                       :cells       b10/cells-n
                       :crossing    :dispatch-async
                       :measurement-method
                       (str "setInterval at 1000/R ms for " drive-ms " ms, each tick issuing "
                            "an ordinary asynchronous rf/dispatch. :expected is computed "
                            "(floor(duration x rate / 1000)) and is NOT a measurement; "
                            ":offered counts driver invocations; :applied is incremented "
                            "inside the reducer; :mutations counts DOM records; "
                            ":peak-backlog is the largest offered-minus-applied ever seen at "
                            "an offer; :tail-ms is from the last offer to the page showing "
                            "the last generation, polled at 4 ms with a 2000 ms ceiling; "
                            ":latency-ms is the offer-to-DOM distribution read from the "
                            "MutationObserver callback that carried each generation.")}
                      {:warmup 0 :samples (count @runs)}
                      {:runs @runs}))
                  nil))
              (.catch (fn [e] (is false (str "M3 rejected: " e))))
              (.finally (fn [] (b10/release! mnt) (done)))))))))

;; ===========================================================================
;; M4 — the controlled field, correctness FIRST
;; ===========================================================================

(def ^:private typed-string
  "Thirty characters, so a dropped one is visible as a length AND as a
  mismatch rather than only as a length."
  "the quick brown fox jumps over")

(deftest m4-a-controlled-field-drops-nothing-at-any-rung-of-the-ladder
  (testing "DC-09 acceptance criterion 2, in the order it is written:
            no drops and an intact caret BEFORE latency is discussed.
            FH-INPUT-003 already gates this property at a dozen
            contending siblings; what is new here is the LADDER —
            0, 1, 10, 50 and 200 siblings dirtied by every single
            keystroke — and the per-keystroke distribution beside it.

            Keystrokes are appended through the node's own prototype
            setter and delivered as real bubbling `input` events, so
            React's value tracker sees exactly what it sees for a user.
            A character the synchronous round trip failed to land is a
            character React erases at the end of the discrete event, so a
            drop shows up as a SHORTER string, not a late one."
    (if-not (browser?) (skip)
      (async done
        (let [out (atom {})]
          (try
            (doseq [k b10/sibling-ladder]
              (let [mnt (b10/mount! :vega)
                    c   (:container mnt)]
                (try
                  (rf/dispatch-sync [:b10/contend k] {:frame b10/frame-id})
                  (ms/live!)
                  (let [node (b10/field-node c)
                        _    (.focus node)
                        lat  (vec (for [ch (seq typed-string)]
                                    (let [t0 (m/now-ms)]
                                      (ms/keystroke! node (str ch))
                                      (- (m/now-ms) t0))))]
                    (is (= typed-string (.-value node))
                        (str "k=" k ": no character was dropped — the field holds "
                             (pr-str (.-value node))))
                    (is (= [(count typed-string) (count typed-string)] (ms/caret node))
                        (str "k=" k ": the caret is at the end of what was typed"))
                    (is (= (str (count typed-string)) (b10/cell-text c 0))
                        (str "k=" k ": the leaf settled on the last keystroke's state"))
                    (is (= (str (count typed-string)) (b10/cell-text c k))
                        (str "k=" k ": and so did the furthest contending sibling"))
                    (when (< (inc k) b10/cells-n)
                      (is (not= (str (count typed-string)) (b10/cell-text c (inc k)))
                          (str "k=" k ": and the cell BEYOND the rung did not move")))
                    (swap! out assoc k {:keystrokes (count typed-string)
                                        :per-keystroke-ms (m/summarise lat)}))
                  (finally (b10/release! mnt)))))
            (h/publish!
              "B10 M4 / controlled field under contention"
              (b10/record
                :B10/controlled-field
                "per-keystroke cost of the synchronous controlled round trip by sibling count"
                {:cells      b10/cells-n
                 :ladder     b10/sibling-ladder
                 :typed      typed-string
                 :keystrokes (count typed-string)
                 :crossing   :controlled-input-door
                 :measurement-method
                 (str "each keystroke APPENDS one character through HTMLInputElement's own "
                      "prototype value setter and fires a real bubbling InputEvent, outside "
                      "React's act environment, so the controlled-input door takes the "
                      "synchronous round trip it takes for a user. The clock brackets the "
                      "dispatchEvent call, which is the whole round trip: listener, event, "
                      "reducer, subscription, render, commit and React's own end-of-event "
                      "value restoration. Correctness is asserted at the DOM — the field's "
                      "value, the caret, the leaf, the furthest contending sibling and the "
                      "cell beyond the rung — BEFORE any latency is read.")}
                {:warmup 0 :samples (count typed-string) :rounds 1}
                @out))
            (finally (done))))))))
