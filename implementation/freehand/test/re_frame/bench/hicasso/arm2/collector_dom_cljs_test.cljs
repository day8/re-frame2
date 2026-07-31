(ns re-frame.bench.hicasso.arm2.collector-dom-cljs-test
  "SURFACE B UNDER A COMMIT-TIME RE-RUN MODEL (rf2-2rtt6.10).

  Operator ruling, 2026-07-31: only the **ambient collector** surface
  clears the ergonomics bar — `sub` as an ordinary function call, legal
  in a conditional, in a loop, and inside an inlined helper, with the
  runtime diffing the edge set after the body returns. Grouped `use-subs`
  is out.

  Arm 2 re-runs dirty boundaries *inside the commit* rather than through
  React, so the surface has to be shown working under that model
  specifically. This file shows it, one shape at a time, and then shows
  the two things the ruling did **not** waive:

  - **the conditional read is an edge-set diff, not a ledger** — a body
    that stops reading a key drops exactly that edge and nothing else,
    and a body that starts reading one adds exactly that edge;
  - **no candidate ledger exists to kill** — the whole post-body
    reconciliation is two set differences, and the index's own state is
    the only place a read is recorded.

  ## Why this is easier here than under React, stated as a test

  [[a-body-run-is-never-abandoned]] is the assertion that carries the
  tournament finding: in this arm a body runs because the commit ran it,
  and the patch lands in the same synchronous extent. There is no
  scheduler that can discard the run, so there is no window in which a
  read is a *candidate* rather than a fact. The front half still carries
  the abandoned-render guard — it is written for the arm that needs it —
  and this arm exercises it only from the teardown direction."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private off-browser "no DOM on this runtime — the collector is exercised through a mounted body")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(def ^:private frame-id ::collector)

(defn- with-mounted
  "Seed a dogfood frame, mount `element`, run `f` with the container."
  [element f]
  (rt/reset-runtime!)
  (dogfood/make-frame! frame-id 5)
  (let [c (container!)
        teardown (rt/mount-root! {:container c :frame frame-id :element element})]
    (try (f c) (finally (teardown) (.remove c) (rt/reset-runtime!)))))

(defn- edges-of-only-boundary
  "The one mounted boundary's edge set."
  []
  (let [snap (idx/snapshot)]
    (first (vals (:b->subs snap)))))

;; ---------------------------------------------------------------------------
;; The three shapes the surface exists for
;; ---------------------------------------------------------------------------

(deftest a-read-inside-a-conditional-is-legal-and-indexed
  (if-not (browser?)
    (is true off-browser)
    (let [v (rt/view ::cond-view
                     (fn [_]
                       (let [f (rt/sub [:dogfood/filter])]
                         [:div.cond
                          (when (= :done f)
                            [:span.extra (str (rt/sub [:dogfood/remaining]))])])))]
      (with-mounted [v {}]
        (fn [c]
          (testing "the branch is not taken, so its read never happened"
            (is (= #{[:dogfood/filter]} (edges-of-only-boundary)))
            (is (nil? (.querySelector c "span.extra"))))
          (testing "taking the branch adds exactly that edge"
            (rt/dispatch! [:dogfood/set-filter :done])
            (is (= #{[:dogfood/filter] [:dogfood/remaining]} (edges-of-only-boundary)))
            (is (some? (.querySelector c "span.extra"))))
          (testing "leaving it drops exactly that edge — law 4, through a real re-run"
            (rt/dispatch! [:dogfood/set-filter :all])
            (is (= #{[:dogfood/filter]} (edges-of-only-boundary)))
            (is (empty? (idx/readers-of (idx/snapshot) [:dogfood/remaining]))
                "and nothing is left reading the key the body stopped reading")))))))

(deftest a-read-inside-a-loop-is-legal-and-indexed
  (if-not (browser?)
    (is true off-browser)
    (let [v (rt/view ::loop-view
                     (fn [_]
                       [:ul.loop
                        (for [id (rt/sub [:dogfood/visible-ids])]
                          [:li {:key id} (:title (rt/sub [:dogfood/todo id]))])]))]
      (with-mounted [v {}]
        (fn [c]
          (is (= 5 (.-length (.querySelectorAll c "li"))))
          (is (= (into #{[:dogfood/visible-ids]} (map (fn [i] [:dogfood/todo i])) (range 5))
                 (edges-of-only-boundary))
              "one edge per iteration, collected as the loop ran")
          (testing "a shorter list drops the edges the loop stopped visiting"
            (rt/dispatch! [:dogfood/remove 4])
            (is (= (into #{[:dogfood/visible-ids]} (map (fn [i] [:dogfood/todo i])) (range 4))
                   (edges-of-only-boundary)))))))))

(deftest a-read-inside-an-inlined-helper-is-legal-and-indexed
  (testing "the helper is an ordinary function with no runtime privileges —
           it is not a hook, not a boundary, and takes no context"
    (if-not (browser?)
      (is true off-browser)
      (let [title-of (fn [id] (:title (rt/sub [:dogfood/todo id])))
            v (rt/view ::helper-view
                       (fn [_] [:div.helper (title-of 2) (title-of 3)]))]
        (with-mounted [v {}]
          (fn [c]
            (is (= "todo 2todo 3" (.-textContent (.querySelector c "div.helper"))))
            (is (= #{[:dogfood/todo 2] [:dogfood/todo 3]} (edges-of-only-boundary))
                "both helper reads reached the index")))))))

;; ---------------------------------------------------------------------------
;; What the ruling did not waive
;; ---------------------------------------------------------------------------

(deftest the-reconciliation-is-two-set-differences-and-nothing-else
  (testing "HD-002(b): the allowed edge-diff operation, not the forbidden ledger"
    (if-not (browser?)
      (is true off-browser)
      (let [!reads (atom [[:dogfood/filter]])
            v (rt/view ::switching
                       (fn [_] [:div.sw (str (mapv rt/sub @!reads))]))]
        (with-mounted [v {}]
          (fn [_c]
            (is (= #{[:dogfood/filter]} (edges-of-only-boundary)))
            ;; Swap the read set wholesale and force a re-run.
            (reset! !reads [[:dogfood/remaining] [:dogfood/visible-ids]])
            (rt/dispatch! [:dogfood/set-filter :done])
            (is (= #{[:dogfood/remaining] [:dogfood/visible-ids]} (edges-of-only-boundary))
                "the new set replaced the old one in one diff")
            (is (empty? (idx/readers-of (idx/snapshot) [:dogfood/filter]))
                "the dropped key has no reader left — no residue, no ledger entry")))))))

(deftest a-body-run-is-never-abandoned
  (testing "the tournament finding: there is no window in which a read is a
           CANDIDATE rather than a fact, because the commit runs the body and
           patches the DOM in one synchronous extent"
    (if-not (browser?)
      (is true off-browser)
      (let [runs   (atom 0)
            v      (rt/view ::counting
                            (fn [_]
                              (swap! runs inc)
                              [:div.count (str (rt/sub [:dogfood/remaining]))]))]
        (with-mounted [v {}]
          (fn [c]
            (is (= 1 @runs) "one run at mount")
            (is (= "5" (.-textContent (.querySelector c "div.count")))
                "and the DOM already carries its result")
            (rt/dispatch! [:dogfood/toggle 0])
            (is (= 2 @runs) "one run per commit that dirties it — never a discarded extra")
            (is (= "4" (.-textContent (.querySelector c "div.count")))
                "and the patch landed in the same extent as the run")))))))

(deftest a-read-for-an-unmounted-boundary-records-nothing
  (testing "the one direction from which this arm can reach the front half's
           abandoned-render guard: teardown"
    (if-not (browser?)
      (is true off-browser)
      (let [v (rt/view ::doomed (fn [_] [:div.doomed (str (rt/sub [:dogfood/remaining]))]))]
        (rt/reset-runtime!)
        (dogfood/make-frame! frame-id 5)
        (let [c        (container!)
              teardown (rt/mount-root! {:container c :frame frame-id :element [v {}]})
              id       (first (keys (:b->subs (idx/snapshot))))]
          (teardown)
          (idx/record-reads! id #{[:dogfood/remaining]})
          (is (empty? (idx/readers-of (idx/snapshot) [:dogfood/remaining]))
              "a read arriving after unmount is ignored, so unmount is final")
          (.remove c)
          (rt/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; The surface, at a glance
;; ---------------------------------------------------------------------------

(deftest the-surface-needs-no-declaration-before-the-body
  (testing "there is no query collection to hand over, no hook to keep in a
           fixed position, and no rule about where a read may appear"
    (if-not (browser?)
      (is true off-browser)
      (let [v (rt/view ::free
                       (fn [{:keys [n]}]
                         [:div.free
                          (when (pos? n) [:i (str (rt/sub [:dogfood/remaining]))])
                          (for [id (range n)]
                            [:b {:key id} (:title (rt/sub [:dogfood/todo id]))])
                          [:em (name (rt/sub [:dogfood/filter]))]]))]
        (with-mounted [v {:n 3}]
          (fn [c]
            (is (= 3 (.-length (.querySelectorAll c "b"))))
            (is (= "all" (.-textContent (.querySelector c "em"))))
            (is (= 5 (count (edges-of-only-boundary)))
                "five reads from three syntactic positions, all indexed")))))))

(deftest reading-through-the-frame-and-reading-through-the-collector-agree
  (if-not (browser?)
    (is true off-browser)
    (let [v (rt/view ::agree (fn [_] [:div.agree (str (rt/sub [:dogfood/remaining]))]))]
      (with-mounted [v {}]
        (fn [c]
          (rt/dispatch! [:dogfood/toggle 2])
          (is (= (str (count (remove (fn [i] (get-in (rf/app-db-value frame-id) [:todos i :done?]))
                                     (:order (rf/app-db-value frame-id)))))
                 (.-textContent (.querySelector c "div.agree")))
              "the collector's value is the frame's value, not a stale copy"))))))
