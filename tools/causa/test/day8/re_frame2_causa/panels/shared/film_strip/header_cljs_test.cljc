(ns day8.re-frame2-causa.panels.shared.film-strip.header-cljs-test
  "Tests for the shared film-strip header (rf2-h7nqh).

  Per `tools/causa/spec/021-Dynamic-Panel-Designs.md` §2.5 + §17.1.5
  + §17.2 + §17.1.3.

  Component is pure hiccup — these tests walk the rendered tree by
  `data-testid` and never mount a DOM, so the suite runs on the JVM
  side too (cljc)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.shared.film-strip.header :as fs]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- tiny hiccup walker ------------------------------------------------
;;
;; We deliberately avoid pulling in `re-frame.test-helpers` for these
;; tests — the component is small + pure, the walker stays tiny too.

(defn- walk
  "Walk `tree` depth-first yielding every hiccup vector node."
  [tree]
  (cond
    (and (vector? tree) (keyword? (first tree)))
    (cons tree (mapcat walk (rest tree)))

    (sequential? tree)
    (mapcat walk tree)

    :else
    nil))

(defn- find-by-testid
  "Return the first hiccup vector under `tree` whose attrs carry
  `:data-testid == testid`, or nil."
  [tree testid]
  (some (fn [node]
          (when-let [a (second node)]
            (when (and (map? a) (= testid (:data-testid a)))
              node)))
        (walk tree)))

(defn- attrs [node] (second node))

;; ---- §2.5 + §17.1.5 — both buttons render with stable testids ---------

(deftest header-renders-both-buttons-with-stable-testids
  (testing "rf2-h7nqh — every L4 panel mounts the same film-strip;
            the testids are stable + panel-scoped so per-panel tests
            can anchor without forking the visual contract."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")
          next (find-by-testid tree "rf-causa-event-film-strip-next")]
      (is (some? prev) "prev button renders with rf-causa-<panel>-film-strip-prev testid")
      (is (some? next) "next button renders with rf-causa-<panel>-film-strip-next testid")
      (is (= :button (first prev)))
      (is (= :button (first next))))))

(deftest header-panel-id-flows-into-every-testid
  (testing "rf2-h7nqh — swap the panel-id, every emitted testid swaps
            with it. No hard-coded panel name leaks through."
    (let [tree (fs/header {:panel-id  "issues"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})]
      (is (some? (find-by-testid tree "rf-causa-issues-film-strip")))
      (is (some? (find-by-testid tree "rf-causa-issues-film-strip-prev")))
      (is (some? (find-by-testid tree "rf-causa-issues-film-strip-next")))
      (is (nil?  (find-by-testid tree "rf-causa-event-film-strip-prev"))
          "no Event-flavoured testid leaks through Issues' header"))))

(deftest header-button-labels-carry-the-canonical-glyphs
  (testing "rf2-h7nqh — `◀ Prev` and `Next ▶` glyphs per §17.1.5
            iconography. Glyphs are deliberate (consistent with the
            Causa iconography convention)."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")
          next (find-by-testid tree "rf-causa-event-film-strip-next")]
      (is (= "◀ Prev" (last prev)))
      (is (= "Next ▶" (last next))))))

;; ---- §17.1.3 — palette: enabled chevron rides :text-secondary ----------

(deftest enabled-buttons-ride-the-canonical-text-secondary-token
  (testing "rf2-h7nqh — §17.1.3 binds the chevron to `:text-secondary`
            in the default state (hover swap is a CSS concern; this
            test pins the base state)."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")
          next (find-by-testid tree "rf-causa-event-film-strip-next")]
      (is (= (:text-secondary tokens/tokens)
             (get-in (attrs prev) [:style :color]))
          "prev chevron rides the canonical :text-secondary hex")
      (is (= (:text-secondary tokens/tokens)
             (get-in (attrs next) [:style :color])))
      (is (= "pointer" (get-in (attrs prev) [:style :cursor]))
          "enabled chevron carries a pointer cursor"))))

;; ---- §17.2 — disabled state (no handler, no tabindex, dim token) -------

(deftest disabled-prev-demotes-to-text-tertiary-and-strips-handler
  (testing "rf2-h7nqh — §17.2 disabled row: foreground at
            `:text-tertiary`, cursor `not-allowed`, tabindex
            removed, no handler. (At start of L2 spine.)"
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly :should-not-fire)
                           :next-fn   (constantly nil)
                           :has-prev? false
                           :has-next? true})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")
          a    (attrs prev)]
      (is (= (:text-tertiary tokens/tokens)
             (get-in a [:style :color]))
          "disabled chevron demotes to :text-tertiary")
      (is (= "not-allowed" (get-in a [:style :cursor])))
      (is (true? (:disabled a))
          "HTML `disabled` attribute set so click events fizzle at the DOM")
      (is (= "true" (:aria-disabled a)))
      (is (= -1 (:tab-index a)) "tabindex removed per §17.2")
      (is (nil? (:on-click a))
          "no handler attached — disabled buttons MUST NOT fire prev-fn"))))

(deftest disabled-next-demotes-the-same-way
  (testing "rf2-h7nqh — §17.2 disabled — symmetric for Next (e.g. at
            the end of the L2 spine)."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly :should-not-fire)
                           :has-prev? true
                           :has-next? false})
          next (find-by-testid tree "rf-causa-event-film-strip-next")
          a    (attrs next)]
      (is (= (:text-tertiary tokens/tokens)
             (get-in a [:style :color])))
      (is (true? (:disabled a)))
      (is (nil? (:on-click a))))))

(deftest both-disabled-when-spine-is-empty
  (testing "rf2-h7nqh — empty L2 spine: both buttons render in their
            disabled visual state. Component MUST tolerate the
            zero-epoch case."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? false
                           :has-next? false})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")
          next (find-by-testid tree "rf-causa-event-film-strip-next")]
      (is (true? (:disabled (attrs prev))))
      (is (true? (:disabled (attrs next)))))))

;; ---- handler wiring -----------------------------------------------------

(deftest enabled-prev-button-carries-the-supplied-handler
  (testing "rf2-h7nqh — when has-prev? is true the prev-fn is wired
            to :on-click. Pure-data assertion (no DOM)."
    (let [calls (atom 0)
          tree  (fs/header {:panel-id  "event"
                            :prev-fn   #(swap! calls inc)
                            :next-fn   (constantly nil)
                            :has-prev? true
                            :has-next? true})
          prev  (find-by-testid tree "rf-causa-event-film-strip-prev")
          h     (:on-click (attrs prev))]
      (is (fn? h) "handler attached when has-prev? true")
      (h)
      (is (= 1 @calls) "click invokes the supplied prev-fn"))))

(deftest enabled-next-button-carries-the-supplied-handler
  (testing "rf2-h7nqh — symmetric for next-fn."
    (let [calls (atom 0)
          tree  (fs/header {:panel-id  "event"
                            :prev-fn   (constantly nil)
                            :next-fn   #(swap! calls inc)
                            :has-prev? true
                            :has-next? true})
          next  (find-by-testid tree "rf-causa-event-film-strip-next")
          h     (:on-click (attrs next))]
      (is (fn? h))
      (h)
      (is (= 1 @calls)))))

;; ---- middle slot — optional epoch indicator ---------------------------

(deftest indicator-slot-is-optional-and-omitted-when-not-supplied
  (let [tree (fs/header {:panel-id  "event"
                         :prev-fn   (constantly nil)
                         :next-fn   (constantly nil)
                         :has-prev? true
                         :has-next? true})]
    (is (nil? (find-by-testid tree "rf-causa-event-film-strip-indicator"))
        "no indicator hiccup leaks through when caller omits :indicator")))

(deftest indicator-slot-renders-supplied-hiccup-with-stable-testid
  (testing "rf2-h7nqh — parent passes hiccup; component slots it
            between the two buttons."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true
                           :indicator [:strong "epoch #42"]})
          slot (find-by-testid tree "rf-causa-event-film-strip-indicator")]
      (is (some? slot))
      (is (= [:strong "epoch #42"] (last slot))
          "slot contents are the caller's hiccup verbatim"))))

;; ---- §17.5 stretch — per-panel filter slot ----------------------------

(deftest filter-fn-slot-is-accommodated-but-optional
  (testing "rf2-h7nqh — MVP can ship without; the API just needs to
            accommodate. When omitted the data-attr is absent."
    (let [tree (fs/header {:panel-id  "issues"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})
          root (find-by-testid tree "rf-causa-issues-film-strip")]
      (is (nil? (:data-rf-causa-filter (attrs root)))))))

(deftest filter-fn-when-supplied-marks-the-root-for-test-assertion
  (testing "rf2-h7nqh stretch — Issues panel passes a `:filter-fn`
            (e.g. 'next epoch with ⚠'); the slot surfaces as a
            data-attr so panel-level tests can pin the wiring
            without the component knowing the filter's semantics."
    (let [tree (fs/header {:panel-id  "issues"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true
                           :filter-fn (fn [_epoch] true)})
          root (find-by-testid tree "rf-causa-issues-film-strip")]
      (is (= "active" (:data-rf-causa-filter (attrs root)))))))

;; ---- typography — JetBrains Mono per §17.1.5 ---------------------------

(deftest buttons-ride-the-mono-stack-per-iconography-spec
  (testing "rf2-h7nqh — §17.1.5 binds the chevrons to 12px JetBrains
            Mono. The mono-stack token is the single source for the
            face."
    (let [tree (fs/header {:panel-id  "event"
                           :prev-fn   (constantly nil)
                           :next-fn   (constantly nil)
                           :has-prev? true
                           :has-next? true})
          prev (find-by-testid tree "rf-causa-event-film-strip-prev")]
      (is (= tokens/mono-stack
             (get-in (attrs prev) [:style :font-family]))))))
