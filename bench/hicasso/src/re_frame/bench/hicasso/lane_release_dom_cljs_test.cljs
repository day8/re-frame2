(ns re-frame.bench.hicasso.lane-release-dom-cljs-test
  "`rf.bench.hicasso.lane/release!`'s claim that a NORMAL RETURN is not proof of release,
  and the census extension that lets a family count its own references
  (rf2-2rtt6.2, second audit).

  The recorded fault: an arm's `:unmount` returns normally without
  unmounting its React root. The container is removed regardless, so the
  root lives on, standing on a DETACHED tree — outside the body-children
  count, and (for a ratom arm) outside the frame's sub-cache census too,
  because its cursor reactions watch a namespace-level atom. The landed
  instrument passed `residue-after-bulk` with exactly this fault planted.

  These are correctness tests of the instrument, not benchmarks: no clock
  is read for a figure. The real-arm, end-to-end proof is the bounded
  mutation run recorded on the bead; what is pinned here is the two
  mechanisms' behaviour, cheaply and deterministically, in the build every
  PR runs.

  Runtime: these are DOM claims. The file carries the `-dom-cljs-test`
  suffix so `:browser-test` runs it for real, and every test degrades to
  a stated skip under `:node-test`, which is the posture the other `*-dom`
  suites keep."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]))

(def ^:private off-browser
  "no DOM on this runtime — the claim is a DOM read taken at release, and
  :browser-test is where it is asked")

(defn- populated-mount
  "A mount whose container holds a page, with the arm's unmount under the
  test's control: `unmount!` receives the container and decides whether
  the release actually happens — which is the whole variable under test."
  [unmount!]
  (let [c (js/document.createElement "div")]
    (set! (.-innerHTML c) "<ul><li>live</li></ul>")
    (.appendChild js/document.body c)
    {:arm       {:id :under-test :unmount (fn [_handle] (unmount! c))}
     :handle    ::root
     :container c}))

;; ---------------------------------------------------------------------------
;; The normal-return non-release — recorded, and fatal at adjudication
;; ---------------------------------------------------------------------------

(deftest a-normally-returning-non-release-is-recorded-and-fatal
  (testing "an unmount that returns normally leaving its page standing is
           recorded at the site and adjudicated as a teardown failure —
           the exact shape the second audit planted and watched pass"
    (if-not (rf.bench.hicasso.lane/browser?)
      (is true off-browser)
      (do (rf.bench.hicasso.lane/drain-teardown-failures!) ;; a clean slate for the claim
          (rf.bench.hicasso.lane/release! (populated-mount (fn [_c] nil)))
          (is (thrown-with-msg? js/Error #"teardown FAILED"
                (rf.bench.hicasso.lane/assert-teardown-clean! "the no-op release"))
              "a normal return must not pass for a release")))))

(deftest a-release-that-emptied-its-container-is-clean
  (testing "the same path records nothing when the unmount actually
           releases — the check reads the page, not the promise"
    (if-not (rf.bench.hicasso.lane/browser?)
      (is true off-browser)
      (do (rf.bench.hicasso.lane/drain-teardown-failures!)
          (rf.bench.hicasso.lane/release! (populated-mount (fn [c] (.replaceChildren c))))
          (is (nil? (rf.bench.hicasso.lane/assert-teardown-clean! "the real release"))
              "an emptied container is the release, observed")))))

(deftest a-throwing-unmount-is-recorded-once
  (testing "a throw is already the record — the container read does not
           stack a second failure onto the same release"
    (if-not (rf.bench.hicasso.lane/browser?)
      (is true off-browser)
      (do (rf.bench.hicasso.lane/drain-teardown-failures!)
          (rf.bench.hicasso.lane/release! (populated-mount (fn [_c] (throw (js/Error. "boom")))))
          (let [fs (rf.bench.hicasso.lane/drain-teardown-failures!)]
            (is (= 1 (count fs)) "one release, one record")
            (is (= "boom" (:error (first fs)))
                "and the record carries the throw, not the container count"))))))

;; ---------------------------------------------------------------------------
;; The census extension — a family's own counter is held to the equality
;; ---------------------------------------------------------------------------

(deftest the-census-counts-what-the-caller-names
  (testing "a family whose references live outside the frame cache and the
           body supplies its own counter, and the residue assertion
           refuses on it by the same equality"
    (if-not (rf.bench.hicasso.lane/browser?)
      (is true off-browser)
      (let [live     (atom 0)
            counters {:family-refs (fn [] @live)}
            baseline (rf.bench.hicasso.lane/residue ::no-such-frame counters)]
        (is (= 0 (:family-refs baseline))
            "the caller's counter is part of the reading")
        (swap! live inc) ;; the surviving root's reference
        (is (thrown-with-msg? js/Error #"RESIDUE"
              (rf.bench.hicasso.lane/assert-residue! baseline ::no-such-frame
                                    "the row" counters))
            "one live reference outside both built-in counters must refuse")
        (reset! live 0)
        (is (= baseline (rf.bench.hicasso.lane/assert-residue! baseline ::no-such-frame
                                              "the row" counters))
            "and back at baseline the same census passes")))))
