(ns re-frame.ui.g13.measure-cljs-test
  "Focused proof of the G-13 measurement seam (rf2-muhsq).

  This is the LEXICAL half of G-13's dispatch-to-commit containment proof. It
  replaces a source-text checker that read `dev.cljs`, blanked its strings and
  comments at preserved offsets, delimited the private `timing-cycle!` by the
  private `correctness-cycle!` that had to follow it, and pinned exact forms,
  multiplicities and offsets (rf2-a0i2y). That checker proved the right thing
  and cost the wrong price: renaming a private fn, extracting a helper, or
  writing an equivalent form failed CI.

  The seam owns the interval instead, so the property is now a plain call-order
  property of one small function and is asserted here with a recording clock
  and a recording flush. No source text is read.

  Deliberately requires ONLY `re-frame.ui.g13.measure` — not the G-13 fixture,
  which pulls in React. The seam takes its collaborators as arguments, so there
  is nothing to stand up."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.ui.g13.measure :as measure]))

;; The fixture's per-drain :hot advance. Inlined rather than required from
;; `re-frame.ui.g13.fixture` so this suite stays React-free on the node runner;
;; its only role here is to be a recognisable nonzero delta.
(def queued-writes 8)

(defn- recorder
  "A seam collaborator set that appends a label per call. `hot` is the app-db
  `:hot` stand-in; `work!` advances it, exactly as the real timed dispatch
  does synchronously via `dispatch-sync!`."
  [log hot ticks]
  {:read-hot (fn [] (swap! log conj :read-hot) @hot)
   :now      (fn [] (swap! log conj :clock) (swap! ticks + 10))
   :flush!   (fn [work!]
               (swap! log conj :flush)
               (js/Promise.resolve (work!)))
   :work!    (fn [] (swap! log conj :work) (swap! hot + queued-writes) nil)})

(deftest seam-brackets-the-flushed-work-with-clock-then-witness
  (testing "witness, start, flush(work), end, witness — in exactly that order"
    (async done
      (let [log   (atom [])
            hot   (atom 0)
            ticks (atom 0)]
        (-> (measure/measure-dispatch-to-commit! (recorder log hot ticks))
            (.then
             (fn [{:keys [elapsed-ms pre-hot post-hot]}]
               ;; The WHOLE log is compared, not a subsequence — so this also
               ;; rejects an extra clock reading or a second flush inside the
               ;; seam, the multiplicity the retired regex counts pinned.
               (is (= [:read-hot :clock :flush :work :clock :read-hot] @log)
                   "the work must run inside the flush, the flush between the two
                    clock reads, and both witness reads outside them")
               ;; The opening witness precedes the start timestamp and the
               ;; closing witness follows the end timestamp, so neither read is
               ;; on the clock: elapsed is exactly the two ticks apart.
               (is (= 10 elapsed-ms)
                   "elapsed is end minus start, with no witness read between them")
               (is (= 0 pre-hot) "the opening witness is read before the work")
               (is (= queued-writes (- post-hot pre-hot))
                   "and the closing witness after it, so the delta is the work
                    the measured interval contained")))
            (.catch (fn [e] (is false (str "seam rejected: " e)) nil))
            (.then (fn [_] (done))))))))

(deftest work-hoisted-out-of-the-thunk-collapses-the-delta
  (testing "a caller that does the work BEFORE calling the seam reds the gate"
    ;; THE MUTANT NO CALLER-SIDE WITNESS CAN SEE. While the opening witness was
    ;; read in `timing-cycle!` — above the dispatch it guarded — hoisting the
    ;; dispatch above the start timestamp left post-minus-pre at exactly
    ;; queued-writes while the measured span no longer covered the write epochs.
    ;; Only source order showed it, which is why the source-text checker existed.
    ;;
    ;; The seam owns the read, so the hoisted work now lands BEFORE the opening
    ;; witness and the delta collapses to 0 — which `assertTimedIntervalDidWork`
    ;; rejects. This is the substitution that let the source lexer retire.
    (async done
      (let [hot (atom 0)]
        (swap! hot + queued-writes)                    ;; the hoisted dispatch
        (-> (measure/measure-dispatch-to-commit!
             {:read-hot (fn [] @hot)
              :now      (fn [] 0)
              :flush!   (fn [work!] (js/Promise.resolve (work!)))
              :work!    (fn [] nil)})                  ;; ...leaving an empty thunk
            (.then
             (fn [{:keys [pre-hot post-hot]}]
               (is (= queued-writes pre-hot)
                   "the hoisted work advanced app-db before the seam's own
                    opening witness read")
               (is (= 0 (- post-hot pre-hot))
                   "so the delta is 0 and the runtime containment witness reds")))
            (.catch (fn [e] (is false (str "seam rejected: " e)) nil))
            (.then (fn [_] (done))))))))

(deftest work-deferred-past-the-commit-collapses-the-delta
  (testing "a caller that does the work AFTER the resolved Promise reds the gate"
    (async done
      (let [hot (atom 0)]
        (-> (measure/measure-dispatch-to-commit!
             {:read-hot (fn [] @hot)
              :now      (fn [] 0)
              :flush!   (fn [work!] (js/Promise.resolve (work!)))
              :work!    (fn [] nil)})
            ;; The closing witness was already read inside the seam, so work
            ;; chained on afterwards cannot be credited to the interval.
            (.then (fn [result] (swap! hot + queued-writes) result))
            (.then
             (fn [{:keys [pre-hot post-hot]}]
               (is (= 0 (- post-hot pre-hot))
                   "work deferred past the end timestamp is not in the delta")))
            (.catch (fn [e] (is false (str "seam rejected: " e)) nil))
            (.then (fn [_] (done))))))))
