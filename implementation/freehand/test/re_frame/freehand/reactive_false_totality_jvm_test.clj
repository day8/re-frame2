(ns re-frame.freehand.reactive-false-totality-jvm-test
  "SPIKE rf2-3slzz — is the `{:reactive false}` RUNTIME check TOTAL?

  The proposed opt-out lets an author declare a boundary SHELL-FREE: its
  own render performs no `v/sub` read and produces no committed event
  site, so the ViewCell (2,348 bytes of standing heap per boundary) is
  never minted. Nothing PROVES the declaration; the runtime CHECKS it.
  This file is the check's totality evidence on the reactive-READ half.

  ## What is actually under test, and why it needs no new code

  A shell-free boundary is EXACTLY a body evaluated with no render
  candidate open — which is what `re-frame.freehand.cell`'s ambient
  `*render*` already answers `nil` for, and what the compiled tier's
  `:elided` path already does today. So the flag's runtime check is not
  hypothetical: `cell/observe!` consults the candidate BEFORE it resolves
  or probes, and an absent candidate is already
  `:rf.error/view-read-outside-render`. What this file proves is that the
  refusal is TOTAL over the shapes a real body can reach a read through —
  inline, through an ordinary helper, through a helper reached only on a
  branch a later render takes, through a lazily realised child run — and
  that none of it is behind a development gate.

  ## The one hole this file also proves

  `deep-inside-an-open-capture-a-nested-body-donates-its-read` is a
  DELIBERATE failing-shape witness written as a passing assertion: with
  an OUTER candidate open, a nested body's read is silently recorded on
  the outer boundary. On the JVM structural host and inside
  `t/with-render` that is the ordinary, correct behaviour — one walk, one
  candidate — but it is also exactly why the flag's assertion context
  must MASK the outer candidate rather than merely rely on there not
  being one.

  Runs identically under `-Dre-frame.debug=false`: nothing on the path
  reads `interop/debug-enabled?`, and
  `the-refusal-is-not-behind-the-debug-gate` asserts the refusal against
  whatever posture the JVM was launched in."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private fid :rf/default)
(def ^:private outside-render :rf.error/view-read-outside-render)

(defn- seed!
  [db]
  (rf/reg-sub :spike/n (fn [db _] (:n db)))
  (frame/replace-app-db! fid db))

;; ---------------------------------------------------------------------------
;; The bodies. Ordinary declarations and ordinary helpers — every one of
;; them is a shape the compiled analyzer either cannot see through
;; (`via-helper`) or would have to be re-run to see (`later-branch`).
;; ---------------------------------------------------------------------------

(defn- via-helper
  "An ORDINARY defn — no view, no macro, nothing the flag could annotate
  — that performs the read. The capture is dynamic, so where the call
  lexically sits is irrelevant."
  []
  (str (v/sub [:spike/n])))

(defn- via-two-helpers [] (via-helper))

(v/defview inline-read
  "The inline shape."
  [_]
  [:p (str (v/sub [:spike/n]))])

(v/defview helper-read
  "The helper-mediated shape — invisible to any lexical analysis of THIS
  body."
  [_]
  [:p (via-two-helpers)])

(v/defview later-branch-read
  "A props-controlled branch. The first render takes the inert arm; a
  later render with different props takes the reading arm. Nothing about
  the first render can diagnose the second."
  [{:keys [expand?]}]
  [:p (if expand? (via-helper) "inert")])

(v/defview lazy-child-read
  "The read sits inside a lazy seq the WALK realises, not the body — so
  it happens after the body has returned, while the emitter is
  normalising what it got back."
  [_]
  [:ul (for [i [1 2 3]] [:li {:key i} (via-helper)])])

(v/defview inert-parent
  "Reads nothing itself; mounts a reading child. The child is the
  boundary that would need the shell."
  [_]
  [:div [inline-read {}]])

;; ===========================================================================
;; 1 — the single door
;; ===========================================================================

(def ^:private freehand-src
  (some-> (io/resource "re_frame/freehand/cell.cljc") io/file .getParentFile))

(deftest exactly-one-freehand-namespace-reaches-the-observation-port
  (testing "Totality rests on there being ONE reactive-read door. The
            observation port (`re-frame.substrate.observation`) is what a
            reactive read resolves and probes through, and exactly one
            Freehand source file requires it: `cell.cljc`, which is where
            the candidate consultation lives. A second requirer would be a
            second door, and the check would not be total."
    (if (and freehand-src (.isDirectory freehand-src))
      (let [sources (->> (file-seq freehand-src)
                         (filter #(.isFile ^java.io.File %))
                         (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %))))
            requirers (->> sources
                           (filter #(str/includes? (slurp %) "re-frame.substrate.observation"))
                           (mapv #(.getName ^java.io.File %))
                           sort
                           vec)]
        (is (seq sources) "non-vacuous: the source tree was found and read")
        (is (= ["cell.cljc"] requirers)
            "cell.cljc is the ONLY Freehand namespace that reaches the observation port"))
      (is true "src is not on a directory classpath here — claim carried by the browser lane"))))

(deftest the-public-read-verb-is-the-candidate-consulting-function
  (testing "`v/sub` is not a wrapper over the shell — it IS
            `cell/observe!`, the function that consults the ambient
            candidate before it resolves anything. There is no layer at
            which a read could be routed past the check."
    (is (identical? v/sub cell/observe!)
        "the authoring verb and the recorder are one var")))

;; ===========================================================================
;; 2 — every shape of read is refused with no candidate open
;; ===========================================================================

(defn- render-shell-free
  "Render `form` the way a SHELL-FREE boundary would run: no candidate
  open, so `cell/observing?` is false for the whole walk. This is
  literally the compiled `:elided` path's posture, and the posture
  `{:reactive false}` would put an interpreted body in."
  [form]
  (rf/with-frame fid (tree/render form)))

(deftest a-shell-free-body-cannot-read-through-any-shape
  (seed! {:n 7})
  (is (false? (cell/observing?)) "non-vacuous: no candidate is open")
  (testing "INLINE — the shape a lexical analysis would catch anyway"
    (is (= outside-render (conf/caught-id #(render-shell-free [inline-read {}])))))
  (testing "HELPER-MEDIATED, through two ordinary defns — the shape no
            analysis of the body can see"
    (is (= outside-render (conf/caught-id #(render-shell-free [helper-read {}])))))
  (testing "LAZILY REALISED — the read happens while the WALK normalises
            what the body returned, after the body itself has returned"
    (is (= outside-render (conf/caught-id #(render-shell-free [lazy-child-read {}])))))
  (testing "A LATER BRANCH — the first render is clean and publishes
            nothing stale; the render that actually reaches the read is
            the one that fails, which is the whole first-offending-render
            contract"
    (is (map? (render-shell-free [later-branch-read {:expand? false}]))
        "the inert arm renders normally — no false positive")
    (is (= outside-render
           (conf/caught-id #(render-shell-free [later-branch-read {:expand? true}])))
        "and the branch that reads fails at the read, not silently")))

(deftest the-refusal-is-not-behind-the-debug-gate
  (testing "`cell/observe!`'s candidate check is an unconditional `when`,
            and `error/throw-error!` is not gated either — so the refusal
            holds under whatever debug posture this JVM was launched in.
            Run this file a second time with `-Dre-frame.debug=false` and
            the assertion below is the SAME assertion against the OTHER
            value of the gate."
    (seed! {:n 1})
    ;; The posture is READ, not assumed, and it is read from the same
    ;; load-time source `interop` reads — so the second run of this file
    ;; is demonstrably a different posture rather than the same one twice.
    ;; `with-redefs` on the flag would prove nothing here: `debug-enabled?`
    ;; is a `def` evaluated once at namespace load.
    (let [property (System/getProperty "re-frame.debug")]
      (is (= (not= "false" property) (boolean interop/debug-enabled?))
          (str "the live gate matches -Dre-frame.debug=" (pr-str property)
               " — this run is the " (if interop/debug-enabled? "DEV" "PRODUCTION")
               " posture"))
      (is (= outside-render (conf/caught-id #(render-shell-free [helper-read {}])))
          (str "refused with debug-enabled? = " interop/debug-enabled?)))))

;; ===========================================================================
;; 3 — the hole: an OUTER candidate is donated to, not masked
;; ===========================================================================

(deftest a-nested-body-donates-its-read-to-an-open-outer-candidate
  (testing "THE REASON THE FLAG NEEDS A MASKING CONTEXT, not merely an
            absent candidate. The JVM structural host runs a nested view's
            body INLINE, inside the caller's dynamic extent, so one
            `t/with-render` bracket covers the whole tree. A boundary
            declared shell-free and rendered there would find a candidate
            — the OUTER one — and its read would be silently recorded
            against the outer boundary instead of refused. `cell/observe!`
            cannot tell the two apart; only a context that MASKS the outer
            candidate for the duration of the declared-inert body can."
    (seed! {:n 42})
    (let [c    (cell/cell :spike/outer)
          cand (cell/candidate c fid)
          tree (cell/with-capture cand #(tree/render [inert-parent {}]))]
      (is (map? tree) "the nested reading body rendered WITHOUT any refusal")
      (is (= 1 (count (cell/candidate-reads cand)))
          "and its read was recorded against the OUTER boundary's candidate")
      (is (= [[:spike/n]] (mapv :query (vals (cell/candidate-reads cand))))
          "non-vacuous: the donated read is the child's own query"))))
