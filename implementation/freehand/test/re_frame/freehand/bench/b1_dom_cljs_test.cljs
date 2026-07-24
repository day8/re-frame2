(ns re-frame.freehand.bench.b1-dom-cljs-test
  "B1's DOM row — the half of D021's equal-output claim that needed a real
  browser.

  D021's B1 row asks for equal tree AND equal DOM over one finite
  template, both lowerings. [[re-frame.freehand.bench.b1]] has always
  gated the tree half: it renders both arms every iteration and requires
  their structural output to be equal, on both hosts. The DOM half had no
  instrument — the compiled tier carried no browser lowering, so a
  compiled declaration handed to the React emitter was refused by name
  and there was no compiled DOM to compare an interpreted one against.

  There is now. This file mounts the SAME two arms the workload measures
  — [[re-frame.freehand.bench.b1.interpreted/template]] and its
  `{:compiled true}` twin — through `v/mount` into real hosts at both
  fixture sizes, and compares what the browser actually built.

  ## What is gated here, and what is not

  Everything below is DETERMINISTIC and gates. `innerHTML` equality, an
  element count against written arithmetic, and the structural parity the
  workload already gates are exact properties of a correct run,
  reproducible on any host. Nothing here reads a clock: B1's timings are
  the workload's, they are published as evidence, and a DOM row that grew
  a duration assertion would be the folklore threshold D021 forbids.

  ## Why `innerHTML`, and why it can fail

  `innerHTML` is the browser's own serialisation of everything a mount
  produced — tags, nesting, order, attribute names and values, composed
  classes and text. Comparing it compares the whole page rather than the
  handful of selectors a reader thought to check. It is also why
  [[the-dom-comparison-can-fail]] exists: a comparison nobody has watched
  answer `false` is not evidence that two things agree.

  ## One mount at a time

  Every mount below is awaited before the next begins. Two overlapping
  React `act` boundaries are not a supported state — React says so — and
  a page assembled inside one would be evidence about act's queue rather
  than about two lowerings. Each arm also mounts under its own
  `:disambiguator`, because a root-id is page-unique identity and two
  occurrences of one view are two occurrences.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b1 :as b1]
            [re-frame.freehand.bench.b1.compiled :as compiled]
            [re-frame.freehand.bench.b1.interpreted :as interpreted]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]))

;; ---------------------------------------------------------------------------
;; The fixture sizes — parameters, not language constants
;; ---------------------------------------------------------------------------

(def ^:private stress-rows
  "The stress size, in D021's 10³-node band and the size B1's registered
  stress workload renders."
  250)

(def ^:private small-rows
  "The small realistic case D021 requires beside every stress case."
  6)

(defn expected-elements
  "The exact number of ELEMENTS the template mounts for `rows`.

  Stated as arithmetic rather than measured, so the count gates against a
  written expectation instead of against whatever the mount happened to
  produce. Three for the skeleton — the section, the heading and the list
  — then four per row: the row, its index, its label and its badge. Text
  is a DOM node but not an element, which is the one place this differs
  from [[re-frame.freehand.bench.b1/expected-nodes]]."
  [rows]
  (+ 3 (* 4 rows)))

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- mount-arm!
  "Mount `view` over `rows` under `tag`, answering a promise of
  `{:container … :mounted …}`. A rejected mount takes its own host node
  back out of the document, so a failure leaves nothing behind for the
  next suite to trip over."
  [view rows tag]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (-> (act #(v/mount [view {:rows rows}] container {:disambiguator tag}))
        (.then (fn [mounted] {:container container :mounted mounted}))
        (.catch (fn [e] (.remove container) (js/Promise.reject e))))))

(defn- release! [arms]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (doseq [{:keys [container mounted]} arms]
    (when (some? mounted)
      (.unmount (.-react-root ^root/Root mounted)))
    (.remove container))
  nil)

(defn- with-mounts!
  "Mount each `[view rows tag]` of `specs` IN TURN, hand the mounted arms
  to `f`, then release every one of them and call `done` — whatever
  happened. The release is in a `finally` rather than on the happy path
  because a leaked live root is page-global state, and the suite that
  inherits it fails for a reason nobody can read off its own source."
  [specs f done]
  (let [arms (atom [])]
    (-> (reduce (fn [p [view rows tag]]
                  (.then p (fn [_]
                             (-> (mount-arm! view rows tag)
                                 (.then (fn [arm] (swap! arms conj arm) nil))))))
                (js/Promise.resolve nil)
                specs)
        (.then (fn [_] (f @arms)))
        (.catch (fn [e] (is false (str "a B1 arm's mount rejected: " e))))
        (.finally (fn [] (release! @arms) (done))))))

(use-fixtures :each
  ;; The boundary cache and the live-root registry are process-global, so
  ;; a boundary left over from an earlier suite — or an earlier run of
  ;; this file — could hand React a component minted for a different
  ;; declaration generation and make an equality below true for the wrong
  ;; reason.
  {:before (fn [] (root/reset-registry!) (fr/reset-boundaries!))
   :after  (fn [] (root/reset-registry!) (fr/reset-boundaries!))})

;; ===========================================================================
;; Equal tree AND equal DOM — D021's B1 row, both halves
;; ===========================================================================

(defn- equal-output-at!
  "Mount both arms at `rows` and assert D021's B1 equal-output row over
  them: the same structural tree, the same element count, and the same
  DOM."
  [rows done]
  (with-mounts!
    [[interpreted/template rows :interpreted]
     [compiled/template rows :compiled]]
    (fn [[i c]]
      (let [i-html (.-innerHTML (:container i))
            c-html (.-innerHTML (:container c))]
        (is (true? (:B1/structural-parity (b1/observe {:rows rows})))
            (str "the tree half at " rows
                 " rows: both arms rendered the same structural tree"))
        (is (= (expected-elements rows)
               (.-length (.querySelectorAll (:container i) "*")))
            (str "the interpreted mount built the " (expected-elements rows)
                 " elements the template's arithmetic predicts"))
        (is (= (expected-elements rows)
               (.-length (.querySelectorAll (:container c) "*")))
            "and so did the compiled mount")
        (is (= i-html c-html)
            (str "the DOM half at " rows
                 " rows: the browser built the same page from both lowerings"))
        (is (pos? (count i-html))
            "and it built a page, rather than nothing at all")))
    done))

(deftest the-b1-template-mounts-as-the-same-dom-in-both-lowerings-at-stress
  (testing "D021's B1 row, whole, at the stress size the registered
            workload renders: one boundary over a finite 10³-node
            template produces the same structural tree AND the same real
            DOM interpreted as compiled. The two arms are the same
            declaration plus `{:compiled true}` — `compiled-source-delta-jvm-test`
            reads both files and proves it — so a difference here would
            be promotion changing the page, which is the one thing B1's
            timing comparison is not allowed to be hiding."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done (equal-output-at! stress-rows done)))))

(deftest the-b1-template-mounts-as-the-same-dom-in-both-lowerings-at-small
  (testing "The same row at the small realistic size D021 requires beside
            every stress case, so equal output is not a property of one
            synthetic extreme."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done (equal-output-at! small-rows done)))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-dom-comparison-can-fail
  (testing "The row above is `=` over two `innerHTML` strings, and a
            comparison nobody has watched answer false is not evidence
            that two things agree. So: the same arm at two DIFFERENT
            sizes, through the same mount path, compared the same way. It
            must disagree — and if it ever does not, every equality in
            this file is passing for a reason that has nothing to do with
            the lowerings."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (with-mounts!
          [[compiled/template small-rows :one-size]
           [compiled/template (inc small-rows) :another-size]]
          (fn [[a b]]
            (is (not= (.-innerHTML (:container a)) (.-innerHTML (:container b)))
                "one row's difference is visible to the comparison the
                 equal-output row is made with"))
          done)))))

(deftest the-two-arms-really-are-two-lowerings
  (testing "Equal DOM is only a claim about promotion if the arms were
            promoted. One is declared `{:compiled true}` and one is not,
            and they carry the same authored template — the element
            arithmetic above is written once and gates both mounts, which
            is the check that they are the same template stated as a
            number rather than as a comment."
    (is (= :interpreted (:lowering (v/describe interpreted/template)))
        "the reference arm is genuinely interpreted")
    (is (= :compiled (:lowering (v/describe compiled/template)))
        "and the compared arm is genuinely compiled")
    (is (not= (:view-id (v/describe interpreted/template))
              (:view-id (v/describe compiled/template)))
        "they are two declarations, in two namespaces, as the workload requires")
    (is (= (+ 3 (* 4 stress-rows)) (expected-elements stress-rows))
        "and the element arithmetic is arithmetic, not a recorded observation")))
