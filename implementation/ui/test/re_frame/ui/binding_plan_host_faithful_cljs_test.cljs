(ns re-frame.ui.binding-plan-host-faithful-cljs-test
  "rf2-iiacq — REAL compiled-CLJS evidence for the defview header binding plan.

  The `*_jvm_test` companion pins the plan against `clojure.core/destructure` and
  models the CLJS primitives (`unchecked-get`/`undefined?`) on the JVM. This suite
  does NOT model: it defines REAL `defview`s — so the actual `parse-header` →
  `emit-cljs` header lowering, comparator and manifest all run through the CLJS
  compiler — then COMPILES and EXECUTES them under node (`react-dom/server`). What
  it observes is the host itself: the visible bound value, the generated memo
  comparator's slot set, the published manifest `:prop-slots`, and `:or` default
  side effects firing per host binding unit.

  The correctness defect it locks (origin/main b0e20ccd): a NESTED or partially
  overlapping header pattern (`{[x] :other :keys [ns/x]}`) whose earlier unit's
  every local is reclaimed by a later unit left a DEAD `:other` slot in the
  DERIVED projection — the memo comparator, the manifest `:prop-slots`, the
  CLOSED-`:props` set. `collapse-entries` compared whole `:pattern` values, so a
  vector `[x]` shadowed by a symbol `x` slipped through. The fix sweeps by the
  LOCALS each pattern binds (`bp/pattern-locals`); the executable plan
  (`:binding-units`) is untouched, so every initializer still runs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ["react-dom/server" :as rds]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as rt]))

(defn- render [el] (rds/renderToStaticMarkup el))
(defn- descriptor [id] (reactive/view-descriptor id))
(defn- compare-fn [id] (:compare-fn (descriptor id)))
(defn- prop-slot-keys [id]
  (mapv :key (get-in (descriptor id) [:manifest :prop-slots])))

;; `:or` default side-effect log — proves each HOST binding unit's initializer
;; runs, in authored order, on the real host (not a JVM model).
(defonce ^:private evals (atom []))
(defn- mark! [tag] (swap! evals conj tag) tag)

(use-fixtures :each {:before (fn [] (reset! evals []))})

;; ---------------------------------------------------------------------------
;; Fixtures — REAL defviews compiled through the production header lowering
;; ---------------------------------------------------------------------------

;; The bead's exact defect shape: the nested explicit `[x] <- :other` binds a
;; local `x` that the qualified group local `x <- :ns/x` fully reclaims. `:other`
;; contributes NO final visible local → it is a dead derived slot.
(defview nested-shadow-view
  [{[x] :other :keys [ns/x]}]
  [:div.x (str x)])

;; Partial overlap: `[a b] <- :other` and `a <- :ns/a`. The group reclaims `a`,
;; but `b` is STILL a final visible local reading `:other` — so `:other` must
;; SURVIVE as a live slot, and the `[a b] <- :other` initializer must run.
(defview partial-overlap-view
  [{[a b] :other :keys [ns/a]}]
  [:div [:span.a (str a)] [:span.b (str b)]])

;; Qualified-group-name collision carrying an `:or` default. Derived slots
;; collapse to the winner `:ns/x`, but BOTH host units bind the local `x`, so the
;; default's eager get-arg runs ONCE PER UNIT — executable plan is retained.
(defview collision-default-view
  [{:keys [ns/x] x :other :or {x (mark! "D")}}]
  [:div.x (str x)])

;; Plain defaulted slot: the `:or` default is `get`'s eager third argument, so it
;; evaluates whether or not the slot is present — the host-faithful count.
(defview eager-default-view
  [{:keys [x] :or {x (mark! "E")}}]
  [:div.x (str x)])

;; ---------------------------------------------------------------------------
;; The derived-slot defect — comparator + manifest, on the real host
;; ---------------------------------------------------------------------------

(deftest nested-shadow-drops-the-dead-slot-from-the-real-comparator
  (testing "the compiled memo comparator ignores the fully-shadowed :other slot"
    (let [cmp (compare-fn ::nested-shadow-view)]
      (is (fn? cmp))
      ;; :ns/x is the ONLY declared slot: props differing only in :other compare
      ;; EQUAL (no repaint). On origin/main :other was a dead declared slot, so
      ;; this returned false — a spurious re-render — and this assertion was RED.
      (is (true? (cmp (js-obj "ns/x" 1 "other" 1)
                      (js-obj "ns/x" 1 "other" 2)))
          ":other is not a declared slot — differing :other does not repaint")
      ;; the live slot still drives the comparator
      (is (false? (cmp (js-obj "ns/x" 1) (js-obj "ns/x" 2)))
          ":ns/x is the live slot — a change repaints")))
  (testing "the published manifest declares only the final visible-local slot"
    (is (= [:ns/x] (prop-slot-keys ::nested-shadow-view))
        "manifest :prop-slots carries no dead :other")))

(deftest partial-overlap-keeps-the-slot-a-visible-local-still-reads
  (testing "the comparator DOES compare :other — b is a final visible local"
    (let [cmp (compare-fn ::partial-overlap-view)]
      (is (false? (cmp (js-obj "ns/a" 1 "other" [7 8])
                       (js-obj "ns/a" 1 "other" [7 9])))
          ":other stays live because b <- :other survives the collapse")
      (is (false? (cmp (js-obj "ns/a" 1 "other" [7 8])
                       (js-obj "ns/a" 2 "other" [7 8])))
          ":ns/a is live too")))
  (testing "manifest keeps both live slots"
    (is (= [:other :ns/a] (prop-slot-keys ::partial-overlap-view))
        "a partially overlapping pattern keeps the slot its surviving local reads")))

;; ---------------------------------------------------------------------------
;; Host-faithful bound VALUE — the shadowed initializer is dead, the later wins
;; ---------------------------------------------------------------------------

(deftest nested-shadow-binds-the-host-winning-value
  (testing "present :ns/x wins over the shadowed [x] <- :other read"
    (is (= "<div class=\"x\">1</div>"
           (render (rt/jsx2 nested-shadow-view (js-obj "ns/x" 1 "other" [9]))))
        "x resolves to :ns/x (1), never the destructured :other (9)"))
  (testing "absent :ns/x -> nil, never the dead :other value"
    (let [html (render (rt/jsx2 nested-shadow-view (js-obj "other" [9])))]
      (is (= "<div class=\"x\"></div>" html))
      (is (not (str/includes? html "9"))
          "the shadowed [x] <- :other binding is dead — its 9 never surfaces"))))

(deftest partial-overlap-binds-both-live-locals-host-faithfully
  ;; a <- :ns/a (group reclaims it); b <- (second :other). Both initializers run.
  (is (= "<div><span class=\"a\">1</span><span class=\"b\">8</span></div>"
         (render (rt/jsx2 partial-overlap-view
                          (js-obj "ns/a" 1 "other" [7 8]))))
      "a is the group's :ns/a; b reads the executed [a b] <- :other second slot"))

;; ---------------------------------------------------------------------------
;; Executable plan retained — every host initializer runs (real CLJS side effects)
;; ---------------------------------------------------------------------------

(deftest collision-runs-both-unit-defaults-though-slots-collapse
  (testing "the derived projection collapses to the single winning slot"
    (is (= [:ns/x] (prop-slot-keys ::collision-default-view))
        "manifest declares only :ns/x — the dead :other is dropped"))
  (testing "yet BOTH host binding units execute the eager default, in order"
    (reset! evals [])
    (is (= "<div class=\"x\">1</div>"
           (render (rt/jsx2 collision-default-view (js-obj "ns/x" 1 "other" 2))))
        "present: :ns/x wins")
    (is (= ["D" "D"] @evals)
        "the default's eager get-arg runs once per unit (x<-:other, x<-:ns/x)")
    (reset! evals [])
    (is (= "<div class=\"x\">D</div>"
           (render (rt/jsx2 collision-default-view (js-obj))))
        "absent: the winning unit falls to its default")
    (is (= ["D" "D"] @evals)
        "both units still evaluate their default — executable plan is retained")))

(deftest eager-default-runs-once-present-and-absent
  (testing "present slot: the eager get-arg default STILL evaluates once"
    (reset! evals [])
    (is (= "<div class=\"x\">5</div>"
           (render (rt/jsx2 eager-default-view (js-obj "x" 5)))))
    (is (= ["E"] @evals)
        "the default is get's eager third arg — evaluated even when present"))
  (testing "absent slot: the default evaluates once and becomes the value"
    (reset! evals [])
    (is (= "<div class=\"x\">E</div>"
           (render (rt/jsx2 eager-default-view (js-obj)))))
    (is (= ["E"] @evals))))
