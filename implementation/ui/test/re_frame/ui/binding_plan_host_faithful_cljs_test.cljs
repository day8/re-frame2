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
  (`:binding-units`) is untouched, so every initializer still runs.

  rf2-4xpah completes the real-host acceptance this suite promised: qualified
  slot spellings and the didactic bare-slot miss now render VISIBLE values here
  (the JVM companion pins them at macro level only); the collision defaults
  carry SEQUENCED markers, so the visible winner is attributable to the second
  evaluation rather than merely counted; a three-marker fixture makes the host
  `bes` ORDER distinguishable from written source order; and a throwing default
  proves it escapes the render even when the slot is present.

  What this lane still cannot see is `:advanced`. It runs `:none` with
  `:infer-externs false`, so an authored `^js` group-local hint is
  indistinguishable from a lost one. That half lives in
  `binding_plan_advanced_elision_prod_test`, which runs the same shapes through
  a real Closure `:advanced` release build."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            ["react-dom/server" :as rds]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.runtime :as rt]))

(defn- strip-view-evidence
  "Drop the DEV host-root view-evidence annotation (`data-rf2-source-coord` /
  `data-rf-view`) the compiler stamps on a view's compiler-owned root element
  (rf2-hac8p). This suite's subject is the header binding plan — the bound value,
  comparator slot set, `:or` default side effects — not the host-root annotation;
  its own coverage is the dedicated emit-annotation tests, the parity corpus, and
  `test:elision`."
  [html]
  (str/replace html #"\s+data-rf(?:2-source-coord|-view)=\"[^\"]*\"" ""))

(defn- render [el] (strip-view-evidence (rds/renderToStaticMarkup el)))
(defn- descriptor [id] (reactive/view-descriptor id))
(defn- compare-fn [id] (:compare-fn (descriptor id)))
(defn- prop-slot-keys [id]
  (mapv :key (get-in (descriptor id) [:manifest :prop-slots])))

;; `:or` default side-effect log — proves each HOST binding unit's initializer
;; runs, in authored order, on the real host (not a JVM model).
(defonce ^:private evals (atom []))
(defn- mark! [tag] (swap! evals conj tag) tag)

;; rf2-4xpah — a SEQUENCED marker. Two host units that collide on one local
;; share ONE authored `:or` form, so the host evaluates the same expression
;; twice and identical tags (`["D" "D"]`) prove a COUNT but not an ORDER. The
;; sequence number makes the two evaluations distinguishable, so the visible
;; winner can be attributed to a specific one.
(defonce ^:private eval-seq (atom 0))
(defn- mark-seq! [tag] (mark! (str tag (swap! eval-seq inc))))

(use-fixtures :each {:before (fn [] (reset! evals []) (reset! eval-seq 0))})

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
  [{:keys [ns/x] x :other :or {x (mark-seq! "D")}}]
  [:div.x (str x)])

;; Plain defaulted slot: the `:or` default is `get`'s eager third argument, so it
;; evaluates whether or not the slot is present — the host-faithful count.
(defview eager-default-view
  [{:keys [x] :or {x (mark! "E")}}]
  [:div.x (str x)])

;; rf2-4xpah — DISTINGUISHABLE evaluation order. Written source order is a, b,
;; c, but the host `bes` transformation `dissoc`s the group directive and
;; re-`assoc`s its locals AFTER the surviving explicit entries, so the host binds
;; a, c, b. Three DISTINCT markers make that visible on the real host: an
;; emitter that kept parse order would log ["a" "b" "c"].
(defview interleaved-order-view
  [{a :aa :keys [b] c :cc :or {a (mark! "a") b (mark! "b") c (mark! "c")}}]
  [:div.o (str a b c)])

;; rf2-4xpah — THROW propagation. The `:or` default is `get`'s eager third
;; argument, so a throwing default must escape the render even when the slot is
;; PRESENT. A lazy (absent-only) lowering would swallow it on the present arm.
(defview throwing-default-view
  [{:keys [x] :or {x (throw (ex-info "rf-bp-throwing-default" {}))}}]
  [:div.x (str x)])

;; ---------------------------------------------------------------------------
;; Qualified slot spellings — the REAL-HOST half of the JVM parity
;;
;; `defview_grammar_jvm_test/qualified-key-ref-props-accepted-in-every-keys-
;; spelling` proves all three spellings derive the same qualified slots, but
;; entirely at macro level on the JVM. These are the same three spellings as
;; REAL defviews, rendering VISIBLE values on the real host.
;; ---------------------------------------------------------------------------

(defview qualified-keys-view      [{:keys [acct/key acct/ref]}] [:div.q (str "[" key "|" ref "]")])
(defview qualified-group-view     [{:acct/keys [key ref]}]      [:div.q (str "[" key "|" ref "]")])
(defview qualified-explicit-view  [{k :acct/key r :acct/ref}]   [:div.q (str "[" k "|" r "]")])

;; The didactic bare-slot shape: the local is name-stripped to `id`, but the
;; slot READ is the qualified "acct/id" verbatim — a bare "id" prop is a
;; DIFFERENT slot and binds nothing.
(defview qualified-id-view [{:keys [acct/id]}] [:div.id (str "[" id "]")])

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
    (reset! evals []) (reset! eval-seq 0)
    (is (= "<div class=\"x\">1</div>"
           (render (rt/jsx2 collision-default-view (js-obj "ns/x" 1 "other" 2))))
        "present: :ns/x wins")
    (is (= ["D1" "D2"] @evals)
        "the default's eager get-arg runs once per unit (x<-:other, x<-:ns/x)")
    (reset! evals []) (reset! eval-seq 0)
    (is (= "<div class=\"x\">D2</div>"
           (render (rt/jsx2 collision-default-view (js-obj))))
        (str "absent: the winner is the SECOND evaluation — the later "
             "x <- :ns/x unit — not the first. Identical markers could not "
             "tell these apart; had the units been emitted in the reverse "
             "order the visible value would read D1"))
    (is (= ["D1" "D2"] @evals)
        "both units still evaluate their default — executable plan is retained")))

;; ---------------------------------------------------------------------------
;; rf2-4xpah — the remaining real-host `:or` semantics: distinguishable ORDER
;; and THROW propagation
;; ---------------------------------------------------------------------------

(deftest defaults-evaluate-in-host-bes-order-not-source-order
  (testing "all slots present: every eager default still runs, in host bes order"
    (reset! evals [])
    (is (= "<div class=\"o\">ABC</div>"
           (render (rt/jsx2 interleaved-order-view
                            (js-obj "aa" "A" "b" "B" "cc" "C")))))
    (is (= ["a" "c" "b"] @evals)
        "host order: explicit entries keep their places, the group's local is re-assoc'd last")
    (is (not= ["a" "b" "c"] @evals)
        "written source order a,b,c would be the regression"))
  (testing "all slots absent: the same order, and each default becomes its value"
    (reset! evals [])
    (is (= "<div class=\"o\">abc</div>"
           (render (rt/jsx2 interleaved-order-view (js-obj)))))
    (is (= ["a" "c" "b"] @evals))))

(deftest throwing-default-propagates-even-when-the-slot-is-present
  ;; React logs a render error before rethrowing; silence it so the throw under
  ;; test is the only signal on a green run.
  (let [orig js/console.error]
    (set! js/console.error (fn [& _] nil))
    (try
      (testing "present slot: the eager get-arg default throws anyway"
        (is (thrown-with-msg?
             ExceptionInfo #"rf-bp-throwing-default"
             (render (rt/jsx2 throwing-default-view (js-obj "x" 5))))
            "a lazy absent-only lowering would render 5 and swallow this"))
      (testing "absent slot: the default throws as it is selected"
        (is (thrown-with-msg?
             ExceptionInfo #"rf-bp-throwing-default"
             (render (rt/jsx2 throwing-default-view (js-obj))))))
      (finally (set! js/console.error orig)))))

;; ---------------------------------------------------------------------------
;; rf2-4xpah — qualified slot spellings + the didactic bare-slot failure, on the
;; REAL host (the JVM half is macro-level only)
;; ---------------------------------------------------------------------------

(deftest every-qualified-spelling-reads-the-same-qualified-slots-on-the-real-host
  (let [props (js-obj "acct/key" "K" "acct/ref" "R")]
    (doseq [[label view] [["bare :keys with qualified symbols" qualified-keys-view]
                          [":acct/keys group"                  qualified-group-view]
                          ["explicit {local :acct/key} entry"  qualified-explicit-view]]]
      (is (= "<div class=\"q\">[K|R]</div>" (render (rt/jsx2 view props)))
          (str label
               " reads slots \"acct/key\"/\"acct/ref\" verbatim — the qualified "
               "names are legal precisely because they are NOT the reserved "
               "bare :key/:ref React slots"))))
  (testing "the qualified spellings declare the qualified slots, never bare ones"
    (is (= [:acct/key :acct/ref] (prop-slot-keys ::qualified-keys-view)))
    (is (= [:acct/key :acct/ref] (prop-slot-keys ::qualified-group-view)))
    (is (= [:acct/key :acct/ref] (prop-slot-keys ::qualified-explicit-view)))))

(deftest a-bare-prop-is-a-different-slot-and-binds-nothing
  (testing "the qualified slot binds the name-stripped local"
    (is (= "<div class=\"id\">[7]</div>"
           (render (rt/jsx2 qualified-id-view (js-obj "acct/id" 7))))))
  (testing "a BARE \"id\" prop is a different slot — didactically, nothing binds"
    (is (= "<div class=\"id\">[]</div>"
           (render (rt/jsx2 qualified-id-view (js-obj "id" 7))))
        "the name-strip renames the LOCAL, never the lookup slot")
    (is (= [:acct/id] (prop-slot-keys ::qualified-id-view))
        "and the manifest declares the qualified slot, so a bare :id is undeclared")))

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
