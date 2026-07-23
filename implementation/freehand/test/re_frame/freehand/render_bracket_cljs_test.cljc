(ns re-frame.freehand.render-bracket-cljs-test
  "`t/with-render` — the bracket that makes a STATE-READING view testable
  through the public structural surface.

  The blessed structural verb walks a body but opens no render, and `v/sub`
  is legal only during an active declared render (Spec 006 §The subscription
  law). So `t/render` worked on props-only views and refused every view that
  reads state — which is most of an application — and the substrate's own
  suites reached past the door for the two INTERNAL functions that supply
  the missing render.

  This suite is the door's proof, and it is deliberately four-sided:

    * the bead's verbatim repro renders, AS WRITTEN, inside the bracket;
    * the bracket PUBLISHES NOTHING — no ref-count, no cache node — which is
      what makes it safe to render as often as a test likes;
    * `t/render` alone still refuses, and the refusal now NAMES the bracket
      instead of telling the author to rewrite the view under test;
    * the bracket takes NO frame, so frame scope stays the programmer's
      ordinary `rf/with-frame` bracket — the one law this surface has about
      frames, unduplicated.

  Both hosts, from one file: the shell, the walk and the bracket are all
  `.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)
(def ^:private other-fid :render-bracket/other)
(def ^:private query [:app/n])

(defn- ref-count
  "The sub-cache ref-count for `q` in frame `f`, or nil when no cache node
  exists. The ownership question, asked of the REAL cache."
  [f q]
  (:ref-count (get @(:sub-cache (frame/frame f)) q)))

(defn- register! []
  (rf/reg-sub :app/n (fn [db _] (:n db))))

(defn- seed! [f n] (frame/replace-app-db! f {:n n}))

;; The bead's verbatim reproduction — a one-line view that reads state.
(v/defview counter [_] [:span (v/sub [:app/n])])

(v/defview props-only [{:keys [label]}] [:span label])

(defn- shown [tree] (t/text (t/find tree #(= :span (:tag %)))))

;; ---------------------------------------------------------------------------
;; The repro, rendered
;; ---------------------------------------------------------------------------

(deftest the-bracket-renders-a-state-reading-view-as-written
  (testing "the reported repro — `(v/defview counter [_] [:span (v/sub
            [:app/n])])` — renders through the PUBLIC surface inside the
            bracket, with no change to the view and no reach past the door."
    (register!)
    (seed! fid 3)
    (is (= "3" (shown (t/with-render (t/render [counter {}]))))
        "the view reads state and the tree carries what it read")
    (testing "and a fresh render reads state afresh"
      (seed! fid 4)
      (is (= "4" (shown (t/with-render (t/render [counter {}]))))
          "non-vacuous: the render really re-read the frame, it did not
           replay a captured value"))))

(deftest the-bracket-answers-the-body-value
  (testing "it is an ordinary bracket: the body's value comes back, several
            body forms are evaluated in order, and a body that renders
            nothing reactive is not a special case."
    (register!)
    (seed! fid 1)
    (is (= 42 (t/with-render 41 42))
        "several forms evaluate in order and the LAST value is answered")
    (is (= "hello" (shown (t/with-render (t/render [props-only {:label "hello"}]))))
        "a props-only view renders inside the bracket exactly as outside it")
    (is (= [1 1]
           (t/with-render [(v/sub query) (v/sub query)]))
        "two reads in one bracket both resolve — the bracket is a render,
         not a one-shot")))

;; ---------------------------------------------------------------------------
;; It publishes nothing
;; ---------------------------------------------------------------------------

(deftest the-bracket-publishes-nothing
  (testing "the render the bracket opens is never committed, which is the
            shell's own abandoned-render path: the reads resolve and probe
            but acquire nothing, so no ref-count, no cache node and no
            disposal obligation survives the bracket."
    (register!)
    (seed! fid 5)
    (is (nil? (ref-count fid query)) "no cache node exists before the render")
    (is (= "5" (shown (t/with-render (t/render [counter {}]))))
        "non-vacuous: the render really ran and really read the query")
    (is (nil? (ref-count fid query))
        "and it owns nothing afterwards — an abandoned candidate publishes nothing")
    (testing "however many times it is rendered"
      (dotimes [_ 5] (t/with-render (t/render [counter {}])))
      (is (nil? (ref-count fid query))
          "repeated renders accumulate no ownership"))))

(deftest the-capture-closes-with-the-bracket
  (testing "the render is scoped to the bracket's SYNCHRONOUS extent — a
            read that escapes it is refused exactly as one that never
            entered, so a test cannot leave a render open behind it."
    (register!)
    (seed! fid 6)
    (is (false? (cell/observing?)) "no render is active before")
    (is (true? (t/with-render (cell/observing?))) "one is active inside")
    (is (false? (cell/observing?)) "and none is active after")
    (is (= :rf.error/view-read-outside-render
           (conf/caught-id #(v/sub query)))
        "a read after the bracket has closed is refused")))

;; ---------------------------------------------------------------------------
;; render alone still refuses — and the refusal names the bracket
;; ---------------------------------------------------------------------------

(deftest render-alone-still-refuses-a-state-reading-view
  (testing "the bracket is the door, not a widening of `v/sub`: the read law
            is unchanged, so `t/render` on a state-reading view is still the
            typed refusal."
    (register!)
    (seed! fid 7)
    (is (= :rf.error/view-read-outside-render
           (conf/caught-id #(t/render [counter {}])))
        "no candidate, no read")
    (is (= "7" (shown (t/with-render (t/render [counter {}]))))
        "non-vacuous: the same call inside the bracket succeeds, so the
         refusal above is the missing render and nothing else")))

(deftest the-refusal-names-the-bracket
  (testing "the diagnostic's advice is the recovery a TEST can actually
            take. It used to name only the frame-explicit one-shot read —
            i.e. it told the author to rewrite the view under test, which is
            testing something other than the view."
    (register!)
    (seed! fid 8)
    (let [message (try (t/render [counter {}]) nil
                       (catch #?(:clj Throwable :cljs :default) e (ex-message e)))]
      (is (some? message) "non-vacuous: a diagnostic was actually raised")
      (is (str/includes? message "t/with-render")
          "it names the bracket, spelled the way a test writes it")
      (is (str/includes? message "one-shot read")
          "and still names the one-shot read, for the REPL / timer / callback
           callers whose recovery that remains"))))

;; ---------------------------------------------------------------------------
;; Frame scope stays the ordinary bracket
;; ---------------------------------------------------------------------------

(deftest the-bracket-takes-no-frame
  (testing "there is no frame option on this surface, and the bracket does
            not add one: it binds no frame of its own, so a render inside
            `rf/with-frame` resolves in THAT frame and the two brackets
            compose without either knowing about the other."
    (register!)
    (live-frame/make-frame {:id other-fid})
    (seed! fid 100)
    (seed! other-fid 200)
    (is (= "100" (shown (t/with-render (t/render [counter {}]))))
        "with no frame pinned, the read resolves in the ambient default frame")
    (is (= "200" (rf/with-frame other-fid
                   (shown (t/with-render (t/render [counter {}])))))
        "pinned to another frame, the SAME view reads that frame's state")
    (is (= "200" (t/with-render
                   (rf/with-frame other-fid
                     (shown (t/render [counter {}])))))
        "and the two nest in either order — neither bracket owns the other")))
