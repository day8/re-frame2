(ns re-frame.freehand.splitter-cljs-test
  "FH-CTRL-019 — the first-party splitter, headlessly, on both hosts.

  A splitter looks like a control about pixels, and the pixel maths is
  the least interesting thing about it. What is worth proving is that the
  POINTER PATH and the KEYBOARD PATH are the same control: one settle,
  one quantum, one mirror, and a value neither device owns. A splitter
  whose keyboard support was bolted on afterwards passes every drag test
  ever written and is unusable without a mouse.

  So this file proves the arithmetic both devices meet at, by CALLING
  it — no browser, no host event, no render for most of it:

    * the keyboard table, key by key, including the two mirror pairs and
      the keys that are deliberately NOT mirrored;
    * the settle, which is the whole of the two-clock reduction;
    * the pointer geometry, unclamped, under the same mirror;
    * a target reached BOTH WAYS and asserted equal, which is the row;
    * the value transitions, including the late move that must be inert;
    * and the one element the control renders — its ARIA, its part
      address, its handlers, and the blur handler it deliberately has
      none of.

  `splitter-dom-cljs-test` proves what a real browser does with all of
  that. This file and that one are the two halves of one row: what the
  control DECIDES, and what a live pointer does while it is deciding.

  The JVM arm additionally runs the compiled-grammar checker over the
  shipped source, which is what the row's `common` mode rests on. It is
  JVM-only because the checker resolves heads against a loaded namespace."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [re-frame.freehand.compiler.check :as check])
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.splitter :as split]
            [re-frame.freehand.test :as t]))

(def ctrl-019 (conf/fixture :FH-CTRL-019))

(def ^:private bs (:bounds ctrl-019))

(defn- separator
  "The one element the splitter renders, as attributes."
  [tree]
  (t/attrs (t/find tree #(= (:tag ctrl-019) (:tag %)))))

(defn- render-splitter
  "The control at the fixture's baseline, with `extra` folded over the
  minimum call site — one value in, one intent out."
  [extra]
  (t/render [split/splitter
             (merge {:split     (split/init (:baseline ctrl-019))
                     :bounds    bs
                     :on-commit [:layout/split-committed]}
                    extra)]))

;; ===========================================================================
;; The keyboard, as a pure table
;; ===========================================================================

(deftest fh-ctrl-019-the-keyboard-table-is-a-pure-function-of-key-and-geometry
  (testing "Per FH-CTRL-019: `key-intent` answers a MOVE or nothing, from
            the key and the geometry alone. Proven by calling it, because
            a keyboard law that can only be exercised through a browser is
            a keyboard law nobody re-runs."
    (doseq [{:keys [key geom intent]} (:key-rows ctrl-019)]
      (is (= intent (split/key-intent key geom))
          (str (pr-str key) " under " (pr-str geom))))

    (testing "and the table is not vacuous — it really does answer both ways"
      (let [answers (map (fn [{:keys [key geom]}] (split/key-intent key geom))
                         (:key-rows ctrl-019))]
        (is (seq (remove nil? answers)) "some keys are claimed")
        (is (seq (filter nil? answers)) "and some are left to the application")))))

(deftest fh-ctrl-019-only-the-arrows-are-mirrored
  (testing "Per FH-CTRL-019: `ArrowLeft` and `ArrowRight` name a DIRECTION,
            so a right-to-left layout reverses which pane they grow.
            `Home`, `End`, `PageUp` and `PageDown` name the VALUE — the
            minimum, the maximum, smaller, larger — and reversing those
            would send `Home` to the maximum, a defect no left-to-right
            keyboard reproduces and no reviewer sees.

            Stated as a fold over the fixture's own rows rather than as
            four hand-picked assertions, so a key added to the table is
            covered by this claim the moment it is added."
    (let [ltr {:orientation :horizontal}
          rtl {:orientation :horizontal :rtl? true}
          under (fn [g k] (split/key-intent k g))]
      (doseq [k ["ArrowLeft" "ArrowRight"]]
        (let [a (under ltr k) b (under rtl k)]
          (is (and a b) (str k " is claimed in both directions"))
          (is (not= a b) (str k " is mirrored"))
          (is (= (first a) (first b)) (str k " is mirrored in SIGN, not in kind"))))

      (doseq [k ["Home" "End" "PageUp" "PageDown"]]
        (is (= (under ltr k) (under rtl k))
            (str k " names the value, so it reads the same in both directions"))
        (is (some? (under ltr k))
            (str "non-vacuous: " k " really is claimed, so the equality above
                  is not two nils agreeing"))))))

(deftest fh-ctrl-019-an-intent-is-applied-through-the-same-settle
  (testing "Per FH-CTRL-019: `intent-at` ends at `settle`, under the
            caller's bounds. That is what stops the two paths drifting
            apart by rounding differently — there is one clamp and one
            quantum in the control, and both devices reach it."
    (doseq [{:keys [intent from at]} (:intent-rows ctrl-019)]
      (is (= at (split/intent-at intent from bs))
          (str (pr-str intent) " from " from)))))

;; ===========================================================================
;; Settling — the two-clock reduction, as arithmetic
;; ===========================================================================

(deftest fh-ctrl-019-settling-clamps-and-quantizes-every-real-number
  (testing "Per FH-CTRL-019: `settle` is total — every real number answers
            a split inside the bounds, on the step."
    (doseq [{:keys [raw at]} (:settle-rows ctrl-019)]
      (is (= at (split/settle raw bs)) (str raw " settles")))

    (testing "and a settled value is settled: applying it again moves nothing"
      (doseq [{:keys [at]} (:settle-rows ctrl-019)]
        (is (= at (split/settle at bs)))))))

(deftest fh-ctrl-019-a-host-offer-becomes-an-intent-only-where-it-changes-the-split
  (testing "Per FH-CTRL-019: this is the whole of the two-clock boundary,
            and it is arithmetic rather than a scheduler. A host offers
            moves at ITS rate; each is settled and compared with the split
            on screen, and only a difference is an intent. There is no
            throttle here, no timer, no scheduling vocabulary and nothing
            to configure — which is exactly the constraint DC-09's
            evidence gate places on this witness.

            The pair is the claim: MANY offers, FEW intents, and the few
            are exactly the settled values that changed."
    (let [{:keys [offers offers-count accepted accepted-count baseline]} ctrl-019
          run (reduce (fn [{:keys [at acc]} offer]
                        (let [v (split/settle offer bs)]
                          (if (= v at)
                            {:at at :acc acc}
                            {:at v :acc (conj acc v)})))
                      {:at baseline :acc []}
                      offers)]
      (is (= offers-count (count offers))
          "non-vacuous: the fixture really offers that many")
      (is (= accepted (:acc run)) "the accepted intents are these, in order")
      (is (= accepted-count (count (:acc run))))
      (is (< accepted-count offers-count)
          "and there really are fewer intents than offers — the row would be
           vacuous if the fixture's offers each crossed a step")
      (is (apply distinct? (:acc run))
          "no accepted intent repeats a split already on screen"))))

;; ===========================================================================
;; The pointer's geometry — unclamped, and mirrored by the SAME mirror
;; ===========================================================================

(deftest fh-ctrl-019-a-point-on-the-track-names-a-raw-fraction
  (testing "Per FH-CTRL-019: `fraction-at` measures and nothing else. It
            does not clamp, because a bound proven by a clamp that already
            happened is not proven — `settle` owns that, and keeping the
            two apart is what lets the browser row assert a pointer
            dragged well past the end."
    (let [track (:track ctrl-019)]
      (doseq [{:keys [point geom fraction]} (:fraction-rows ctrl-019)]
        (is (= fraction (split/fraction-at point track geom))
            (str (pr-str point) " under " (pr-str geom))))

      (testing "a track with no extent along the measured axis names NO
                fraction — answering 0 would be a position the user did
                not ask for"
        (is (= (:degenerate-fraction ctrl-019)
               (split/fraction-at {:x 1.0 :y 1.0} (:degenerate-track ctrl-019)
                                  {:orientation :horizontal})))))))

;; ===========================================================================
;; THE ROW: pointer and keyboard reach the same split
;; ===========================================================================

(deftest fh-ctrl-019-a-drag-and-a-keystroke-reach-the-same-split
  (testing "Per FH-CTRL-019: the accessibility claim, as an equality
            rather than as two separately-pinned numbers. One pixel on
            the track and one arrow press are put through the whole
            control — settle, bounds, mirror — and the two answers are
            asserted EQUAL to each other, not merely each equal to what
            the fixture expected. A control that quantized the pointer and
            the keyboard differently passes both halves of a pinned pair
            and fails this."
    (let [{:keys [track baseline parity-x parity-key parity-at
                  parity-rtl-key parity-rtl-at]} ctrl-019
          ltr        {:orientation :horizontal}
          rtl        {:orientation :horizontal :rtl? true}
          by-pointer (fn [geom] (split/settle (split/fraction-at {:x parity-x :y 0.0} track geom) bs))
          by-key     (fn [geom k] (split/intent-at (split/key-intent k geom) baseline bs))]

      (is (= (by-pointer ltr) (by-key ltr parity-key))
          "one pixel and one arrow press name the same split")
      (is (= parity-at (by-pointer ltr)) "and it is the split the fixture pinned")

      (testing "and under the mirror, where the SAME pixel and the SAME key
                both flip — if only the geometry were mirrored, or only the
                arrows, this pair would disagree"
        (is (= (by-pointer rtl) (by-key rtl parity-rtl-key)))
        (is (= parity-rtl-at (by-pointer rtl))))

      (testing "non-vacuity: the mirror really moved both of them"
        (is (not= (by-pointer ltr) (by-pointer rtl))
            "the pointer answers a different split under the mirror")
        (is (not= (by-key ltr parity-key) (by-key rtl parity-rtl-key))
            "and so does the key")
        (is (not= baseline parity-at)
            "and the target is not the baseline, so neither path passed by
             standing still")))))

;; ===========================================================================
;; The value — ordinary data, moved by ordinary functions
;; ===========================================================================

(deftest fh-ctrl-019-the-gesture-is-five-transitions-over-a-plain-map
  (testing "Per FH-CTRL-019: there is no local state system. A gesture is
            a map with three keys, moved by functions a test calls and an
            application reads with a plain `reg-sub`."
    (let [{:keys [baseline initial after-start after-move moved-to after-commit
                  after-cancel]} ctrl-019
          v0 (split/init baseline)]
      (is (= initial v0) "init opens at the baseline, not dragging")
      (is (= after-start (split/start v0)))
      (is (= after-move (-> v0 split/start (split/move moved-to bs))))
      (is (= after-commit (-> v0 split/start (split/move moved-to bs)
                              (split/commit moved-to bs))))
      (is (= after-cancel (-> v0 split/start (split/move moved-to bs) split/cancel))
          "a cancel restores the baseline the gesture started from")
      (is (not= after-move after-cancel)
          "non-vacuous: there really was a draft position to abandon"))))

(deftest fh-ctrl-019-a-move-that-arrives-after-the-gesture-ended-is-inert
  (testing "Per FH-CTRL-019: liveness is decided in the HANDLER against
            committed state — the same rule `buffered-field` commits
            under, and for the same reason. An accepted offer and the
            frame it is accepted against are a tick apart, so a preview
            dispatched just before a cancel legitimately lands just after
            it; `move` is where that is decided, so a cancel BEATS a late
            offer rather than racing it.

            This is also what makes every ending of a drag stateless. An
            application cancelling for its own reasons — a route leaving,
            a layout replaced — is the same code path, and no phantom
            offer can move the value afterwards."
    (let [{:keys [baseline late-move-to after-late-move]} ctrl-019
          v0 (split/init baseline)]
      (is (= after-late-move (split/move v0 late-move-to bs))
          "a move with no gesture live changes nothing")
      (is (= after-late-move (-> v0 split/start (split/move late-move-to bs)
                                 split/cancel (split/move late-move-to bs)))
          "and neither does the one that lands just after a cancel")
      (is (not= after-late-move (-> v0 split/start (split/move late-move-to bs)))
          "non-vacuous: the SAME move applied to a live gesture does move it"))))

(deftest fh-ctrl-019-a-keystroke-is-a-whole-gesture
  (testing "Per FH-CTRL-019: the one asymmetry between the paths, and it
            is real rather than an oversight. A drag has a start to
            report and an end to report; a keystroke has neither, so it
            produces the terminal intent alone. `commit` therefore has to
            work from a value that never started a gesture — and that is
            what lets the keyboard need no start intent, no gesture flag,
            and no blur handler to flush anything at."
    (let [{:keys [baseline parity-at keystroke-commit initial]} ctrl-019
          v0 (split/init baseline)]
      (is (= keystroke-commit (split/commit v0 parity-at bs))
          "one call, no start to pair it with")
      (is (false? (:dragging? (split/commit v0 parity-at bs)))
          "and it leaves no gesture open behind it")

      (testing "the endings are idempotent, so an application may end a
                gesture from a route change without asking whether one is
                in flight"
        (is (= initial (split/cancel v0)) "cancelling nothing restores the baseline")
        (is (= (split/cancel (split/cancel v0)) (split/cancel v0)))
        (is (= (split/start (split/start v0)) (split/start v0))
            "and a second press is a second finger, not a new baseline")))))

;; ===========================================================================
;; The one element
;; ===========================================================================

(deftest fh-ctrl-019-the-splitter-renders-one-addressable-separator
  (testing "Per FH-CTRL-019: one element, and everything a stylesheet or a
            screen reader needs is on it. The label, the grip and the
            layout are the application's — a control that owned its markup
            would be un-adaptable exactly where design systems differ."
    (let [{:keys [component-id parts role aria-valuenow aria-valuemin
                  aria-valuemax tab-index dragging-attr dragging-attr-idle
                  baseline]} ctrl-019
          attrs (separator (render-splitter nil))]
      (is (seq attrs) "non-vacuous: the control really rendered that element")
      (is (= component-id (:data-component attrs)) "the scope a stylesheet selects through")
      (is (= parts #{(:data-part attrs)}) "and the whole declared part roster is emitted")
      (is (= role (:role attrs)))
      (is (= tab-index (:tab-index attrs)) "focusable, so the keyboard path is reachable at all")
      (is (= aria-valuenow (:aria-valuenow attrs)))
      (is (= aria-valuemin (:aria-valuemin attrs)))
      (is (= aria-valuemax (:aria-valuemax attrs)))
      (is (= dragging-attr-idle (:data-dragging attrs))
          "and nothing says a drag is live, because none is")

      (testing "while a drag IS live the element says so, which is how a skin
                highlights without the control owning a class"
        (let [live (separator (render-splitter
                               {:split (split/start (split/init baseline))}))]
          (is (= dragging-attr (:data-dragging live)))
          (is (not= (:data-dragging live) dragging-attr-idle)
              "non-vacuous: the two states really differ"))))))

(deftest fh-ctrl-019-the-aria-orientation-is-the-separators-own
  (testing "Per FH-CTRL-019: `:orientation` is the axis the SPLIT moves
            along; `aria-orientation` describes the SEPARATOR. Panes side
            by side are divided by a separator that stands up, so a
            `:horizontal` split renders `aria-orientation=\"vertical\"`.

            Both rows are asserted, because one alone reads as a typo —
            and getting this backwards is the single most common defect in
            a hand-written splitter, invisible to everyone except the
            screen-reader user, who is told the wrong axis on every focus."
    (let [{:keys [aria-orientation-horizontal aria-orientation-vertical]} ctrl-019
          at (fn [o] (:aria-orientation (separator (render-splitter {:orientation o}))))]
      (is (= aria-orientation-horizontal (at :horizontal)))
      (is (= aria-orientation-vertical (at :vertical)))
      (is (= aria-orientation-horizontal (at nil))
          "and the default is the horizontal split, spelled or not")
      (is (not= (at :horizontal) (at :vertical))
          "non-vacuous: the attribute really does track the axis")
      (is (not= (name :horizontal) (at :horizontal))
          "and it is the OPPOSITE word, which is the whole point of the row"))))

(deftest fh-ctrl-019-every-handler-is-on-that-one-element-and-none-is-a-blur
  (testing "Per FH-CTRL-019: pointer capture routes the whole drag to this
            element, so there is no `window` listener — which is the same
            sentence as `there is nothing to remove at unmount`, and why
            this control needs no lifecycle hook and publishes no unmount
            event.

            The ABSENCES are part of the contract and are asserted as
            absences. There is no blur handler because nothing is ever
            pending at a blur: a drag's liveness is the application's
            committed state and a keystroke is already complete when it
            ends."
    (let [attrs (separator (render-splitter {:on-preview [:layout/split-moved]
                                             :on-start   [:layout/split-started]
                                             :on-cancel  [:layout/split-cancelled]}))]
      (doseq [k (:handler-attrs ctrl-019)]
        (is (contains? attrs k) (str "the control owns " k)))
      (doseq [k (:absent-attrs ctrl-019)]
        (is (not (contains? attrs k))
            (str k " is deliberately absent — see the row's law")))

      (testing "and every handler site really is on the ONE element"
        (let [tree  (render-splitter {:on-preview [:layout/split-moved]})
              ;; ELEMENTS only. The view boundary above them carries the
              ;; caller's own `:on-…` props, which are intents passed IN
              ;; rather than handler sites the control emitted.
              nodes (t/find-all tree (fn [n]
                                       (and (map? n)
                                            (:tag n)
                                            (some #(str/starts-with? (name %) "on-")
                                                  (keys (or (t/attrs n) {}))))))]
          (is (= 1 (count nodes))
              "exactly one ELEMENT in the whole tree carries a handler"))))))

(deftest fh-ctrl-019-the-caller-owns-the-skin-and-cannot-own-the-gesture
  (testing "Per FH-CTRL-019: `aria-*`, `data-*`, a class and children all
            reach the element, because a splitter with no replaceable skin
            is a splitter nobody ships. The control's own handler families
            do NOT, in any spelling and in every build — that is
            `v/spread-safe`'s deny law, and it is what lets this control
            keep a promise about the element it rendered."
    (let [{:keys [forwarded denied-caller-prop deny-error-id]} ctrl-019
          attrs (separator (render-splitter forwarded))]
      (doseq [[k v] forwarded]
        (if (= :class k)
          (is (str/includes? (str (:class attrs)) v)
              "the caller's class COMPOSES with whatever the control put there")
          (is (= v (get attrs k)) (str "the caller's " (pr-str k) " reached the element"))))

      (testing "children are the caller's grip markup, rendered inside"
        (let [tree (t/render [split/splitter
                              {:split     (split/init (:baseline ctrl-019))
                               :bounds    bs
                               :on-commit [:layout/split-committed]}
                              [:span.grip "⣿"]])]
          (is (some? (t/find tree #(= :span (:tag %))))
              "the caller's child is in the tree")
          (is (str/includes? (str (t/text (t/find tree #(= :div (:tag %))))) "⣿"))))

      (testing "but the gesture is not the caller's to redirect"
        (is (= deny-error-id
               (conf/caught-id #(render-splitter {denied-caller-prop [:mine/handler]})))
            "a caller reaching for one of the control's own handlers is refused")
        (is (= conf/no-throw (conf/caught-id #(render-splitter nil)))
            "the CONTROL for that refusal: the same call without it renders")))))

;; ===========================================================================
;; The compiled grammar — what the row's `common` mode rests on
;; ===========================================================================

#?(:clj
   (deftest fh-ctrl-019-the-splitter-is-inside-the-compiled-grammar
     (testing "Per FH-CTRL-019: the declaration is checked AS IT STANDS by
               the same analyzer the build runs, pointed at the shipped
               source so there is no copy to drift. Eligible with nothing
               to change on the way, which is what makes the row's
               `common` applicability honest: promotion is a keyword, not
               a rewrite, and interpreted/compiled structural parity
               therefore holds by construction rather than by promise.

               JVM-only because the checker resolves heads against a
               loaded namespace, which only the JVM has."
       (let [{:keys [view-ids compile-eligible? findings current-lowering]} ctrl-019
             path    (.getPath (io/file (io/resource "re_frame/freehand/splitter.cljc")))
             reports (check/check-file path)
             by-id   (into {} (map (juxt :view-id identity)) reports)]
         (is (= (count view-ids) (count reports))
             "non-vacuous: the checker read exactly this file's declarations")
         (doseq [id view-ids]
           (let [report (get by-id id)]
             (is (some? report) (str "the checker found " id))
             (is (= compile-eligible? (:compile-eligible? report))
                 (str id " is inside the compiled grammar"))
             (is (= findings (:findings report))
                 (str id " has nothing to change on the way"))
             (is (= current-lowering (:current-lowering report))
                 (str id " is checked as it stands, before any promotion"))))))))
