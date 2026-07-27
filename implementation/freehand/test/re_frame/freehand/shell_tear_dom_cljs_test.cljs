(ns re-frame.freehand.shell-tear-dom-cljs-test
  "Invariant 5 on a WATCHABLE host — the one where the guarantee's worst
  failure has no second channel (`rf2-anmdr`).

  Spec 006 argues invariant 5 from the headless case: on a host whose
  derived values are not watchable, a RETAINED site has no value-movement
  watch, so the commit's own re-read is its only correction. That argument
  is true and it is also the WEAKER half, because it stops applying the
  moment the host does watch. `shell-cljs-test` carries the headless rows
  over the plain-atom adapter, which is exactly that host.

  This file asks the question the plain-atom fixture cannot. Its substrate
  is the React spine — the machinery behind every shipped React-family
  adapter — whose derived values reify `IWatchable`, so a committed site
  really is notified when its source moves. On that host a retained site
  that a commit fails to correct still self-heals one window later, because
  its watch was installed by an EARLIER commit and fires. A STAGED site
  does not: `obs/acquire!` installs its watch DURING the commit that needed
  it, which is after the move, so nothing fired and nothing is pending. The
  boundary paints stale and stays stale until something else moves that
  source — a panel mounting exactly as a permission drops, a user switches,
  a record is redacted.

  Measured side by side by the `rf2-so3io` ablation:

      site      commit    revision advanced?  cell dirty?  published
      retained  shipped   yes                 no           FRESH
      retained  ablated   no                  YES          stale
      staged    shipped   yes                 no           FRESH
      staged    ablated   no                  NO           stale

  The staged row is what this file pins, and the `dirty?` column is why it
  needs this host: on an unwatchable one `dirty?` is trivially false
  everywhere and says nothing. So the first test below establishes that
  this host DOES watch a committed site, and only then does the second read
  a clean cell as evidence that invariant 5 was the only channel there was.

  No DOM and no React tree — the host under test is the SUBSTRATE, and
  mounting a boundary would add nothing this asks about. The file rides the
  browser lane by its `-dom-cljs-test` suffix because the browser is where
  that substrate is the shipped reality; it runs unchanged under the node
  suites, which install the same spine."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.cell :as cell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; Seams. Each test builds its OWN frame: two arms sharing one frame id share
;; its app-db, which silently couples them to each other's order (the fault
;; `rf2-so3io`'s first tear probe hit).
;; ---------------------------------------------------------------------------

(def ^:private q [:tear/v])

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- render!
  "One render pass of `c` bound to frame `f`: probe `q` and answer THIS
  render's candidate. Nothing is acquired and nothing is published — that
  is the commit's job, and the gap between the two is what these rows are
  about."
  [c f]
  (let [cand (cell/candidate c f)]
    (cell/with-capture cand (fn [] (cell/observe! q)))
    cand))

(defn- published [c]
  (:value (first (:observations (cell/evidence c)))))

;; ---------------------------------------------------------------------------
;; The rows
;; ---------------------------------------------------------------------------

(deftest a-committed-site-on-this-host-really-is-watched
  (testing "Host honesty, and the precondition for the row below. The
            React-spine substrate's derived values are watchable, so the
            change watch `obs/acquire!` installs at commit fires when the
            source moves — with no commit involved and no render in
            flight. That is the second channel a RETAINED site has and a
            staged one does not, and without it on the board the next
            test's clean cell would be trivially clean and would say
            nothing at all."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [f (make-frame! :anmdr.watched/frame {:v 1})
          c (cell/cell :shell/watched)]
      (is (= :published (cell/commit! (render! c f))))
      (is (not (cell/dirty? c))
          "a settled committed cell is clean")
      (frame/replace-app-db! f {:v 2})
      (is (cell/dirty? c)
          "and moving its source marked it — the watch this site's own
           commit installed fired, so this host notifies")
      (cell/disconnect! c))))

(deftest invariant-5-a-staged-site-moving-in-the-gap-has-no-second-channel
  (testing "Per invariant 5, on the host that makes the staged case the
            worse case. The site is committed for the FIRST time and its
            source moves between the render's probe and the commit's read.

            Its handle — and the change watch that handle carries — is
            acquired by THIS commit, so the watch is installed after the
            move it would have reported. The test above proves this host
            would have marked the cell had a watch existed; here the cell
            is clean before the commit and clean after it, which is the
            ablation table's staged row: nothing was pending, nothing is
            pending, and a commit that published the render's value would
            have painted stale and stayed stale.

            It does not. The bundle carries what the COMMIT read and the
            revision advanced, so the host corrects before paint — and
            that comparison is the only reason it does."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [f      (make-frame! :anmdr.staged/frame {:v 1})
          c      (cell/cell :shell/staged)
          cand   (render! c f)
          before (cell/revision c)]
      (is (empty? (cell/dependencies c))
          "the site is STAGED, not retained: this cell has no prior
           committed set, so there is no handle to keep and `obs/acquire!`
           must run inside the commit below")
      ;; THE GAP: the source moves after this render probed it and before
      ;; the commit reads it.
      (frame/replace-app-db! f {:v 2})
      (is (not (cell/dirty? c))
          "and the move left NO mark — there was no watch yet to fire, so
           the ordinary notification channel is not going to correct this")
      (is (= :published (cell/commit! cand)))
      (is (= 1 (count (cell/dependencies c)))
          "the commit acquired the handle it published, which is when the
           watch was installed — one move too late to have reported it")
      (is (= 2 (published c))
          "the bundle published what the COMMIT read, not what the render
           probed")
      (is (= (inc before) (cell/revision c))
          "the commit's own comparison advanced the revision, so the host
           corrects before paint")
      (is (not (cell/dirty? c))
          "and the cell is still clean — no window is coming to fix this
           one, which is what makes the comparison above load-bearing
           rather than an optimisation of one")
      (cell/disconnect! c))))
