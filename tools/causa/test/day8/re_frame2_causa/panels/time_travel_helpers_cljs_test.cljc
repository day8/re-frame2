(ns day8.re-frame2-causa.panels.time-travel-helpers-cljs-test
  "Pure-data tests for Causa's Time Travel scrubber panel helpers
  (Phase 3, rf2-t53ze).

  ## Why the `.cljc` + `_cljs_test` naming

  The file ends in `_cljs_test.cljc` so:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  Same dual-target pattern as `filter_vocab_consumer_cljs_test.cljc`.

  ## What's under test

  Each contract is asserted against the pure-data fns in
  `time-travel-helpers`; no Reagent, no DOM. The view-side wiring is
  exercised in `time_travel_cljs_test.cljs` against the live Causa
  frame.

    1. **Pin captures the 4-tuple.** `pin-from-epoch` lifts the four
       slots off an `:rf/epoch-record` (per spec §What a pin captures).
    2. **Pin store enforces the 32-pin cap.** `pin-snapshot` drops the
       oldest pin when count exceeds cap; surfaces `:overflow?` true
       and `:dropped-pin` for the toast.
    3. **Pins survive ring-buffer eviction.** `chip-state` returns
       `:attached false` when the pin's `:epoch-id` is no longer in
       `history`; the pin's `:frame-db` is still present so
       `reset-frame-db!` is still callable (the detached-chip
       affordance per spec §Pins on the scrubber).
    4. **`find-pin` + `find-epoch-in-history` + step nav.** Lookup
       and step-by-N navigation are total over the empty/missing
       cases; no nil-pointer crashes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]))

;; ---- fixture data --------------------------------------------------------

(defn- epoch
  "Build a minimal `:rf/epoch-record` fixture. `dispatch-id` rides on
  the first trace event's `:tags :dispatch-id` per rf2-g6ih4 — the
  same projection the cascade-resolver walks."
  ([epoch-id db-after dispatch-id]
   {:epoch-id     epoch-id
    :frame        :rf/default
    :committed-at 0
    :event-id     :user/login
    :trigger-event [:user/login]
    :db-before    {}
    :db-after     db-after
    :trace-events (if dispatch-id
                    [{:id 1 :op-type :event :operation :event/dispatched
                      :tags {:dispatch-id dispatch-id :event [:user/login]}}]
                    [])}))

;; ---- (1) pin captures the 4-tuple ---------------------------------------

(deftest pin-from-epoch-captures-four-slots
  (testing "pin-from-epoch lifts :epoch-id, :frame-db, :dispatch-id, :label"
    (let [rec (epoch :e-1 {:user/name "ada"} 100)
          pin (h/pin-from-epoch rec "before-login")]
      (is (= :e-1                (:epoch-id pin))    ":epoch-id from record")
      (is (= {:user/name "ada"}  (:frame-db pin))    ":frame-db from :db-after")
      (is (= 100                 (:dispatch-id pin)) ":dispatch-id from trace-events")
      (is (= "before-login"      (:label pin))       ":label from caller"))))

(deftest pin-from-epoch-handles-synthetic-epoch
  (testing "an epoch with empty :trace-events (synthetic from reset-frame-db!)
            still produces a pin; :dispatch-id is nil"
    (let [rec (epoch :e-syn {:reset? true} nil)
          pin (h/pin-from-epoch rec "synthetic")]
      (is (= :e-syn (:epoch-id pin)))
      (is (= {:reset? true} (:frame-db pin)))
      (is (nil? (:dispatch-id pin)) "no dispatch-id when trace-events is empty"))))

(deftest pin-from-epoch-nil-record-returns-nil
  (testing "pin-from-epoch is total over nil — callers no-op rather than
            store a degenerate pin"
    (is (nil? (h/pin-from-epoch nil "any-label")))))

(deftest pin-from-epoch-eager-frame-db-capture
  (testing "the pin's :frame-db is the value the epoch had at pin time;
            mutating the epoch record after the fact must not change the
            pin's snapshot (eager capture per spec §What a pin captures)"
    (let [db1  {:counter 0}
          rec  (epoch :e-1 db1 1)
          pin  (h/pin-from-epoch rec "v0")]
      ;; Simulate a later epoch with a different db; the pin's :frame-db
      ;; must still reference the original value.
      (is (= db1 (:frame-db pin)) "pin captures the value, not a reference"))))

;; ---- (2) pin store + cap enforcement ------------------------------------

(deftest pin-snapshot-appends-when-under-cap
  (testing "appending under cap returns no overflow and grows the per-frame
            pin vector"
    (let [pin1 {:epoch-id :e-1 :frame-db {} :dispatch-id 1 :label "a"}
          {:keys [store overflow? dropped-pin]}
          (h/pin-snapshot {} :rf/default pin1)]
      (is (= [pin1] (get store :rf/default)))
      (is (false? overflow?))
      (is (nil? dropped-pin)))))

(deftest pin-snapshot-default-label-when-blank
  (testing "blank / nil :label resolves to pin-<n> at insertion time"
    (let [p1 (h/pin-from-epoch (epoch :e-1 {} 10) nil)
          {store-1 :store added-1 :added-pin}
          (h/pin-snapshot {} :rf/default p1)]
      (is (= "pin-1" (:label added-1))
          "first unnamed pin defaults to pin-1")
      (is (= "pin-1" (:label (first (get store-1 :rf/default)))))
      ;; Add a second unnamed pin — next number, not collision.
      (let [p2 (h/pin-from-epoch (epoch :e-2 {} 20) "")
            {store-2 :store added-2 :added-pin}
            (h/pin-snapshot store-1 :rf/default p2)]
        (is (= "pin-2" (:label added-2)) "second unnamed pin → pin-2")
        (is (= ["pin-1" "pin-2"]
               (mapv :label (get store-2 :rf/default))))))))

(deftest pin-snapshot-cap-default-32
  (testing "the default cap is 32 per spec §Pin store capacity"
    (is (= 32 h/default-pin-cap))))

(deftest pin-snapshot-cap-enforced-drops-oldest
  (testing "adding the (cap+1)th pin drops the OLDEST pin and surfaces
            :overflow? true with :dropped-pin set"
    (let [cap   3
          pins  (mapv (fn [i]
                        {:epoch-id    (keyword (str "e-" i))
                         :frame-db    {:n i}
                         :dispatch-id i
                         :label       (str "pin-" i)})
                      (range 1 5)) ; pins 1..4 — the 4th overflows cap=3
          step  (fn [acc pin]
                  (h/pin-snapshot (:store acc) :rf/default pin cap))
          ;; Reduce pins through pin-snapshot. Use the same shape as the
          ;; per-call return value for the seed.
          out   (reduce step
                        {:store {} :overflow? false :dropped-pin nil}
                        pins)
          final-pins (get-in out [:store :rf/default])]
      (is (= 3 (count final-pins))
          "vector stays bounded at cap")
      (is (= [:e-2 :e-3 :e-4]
             (mapv :epoch-id final-pins))
          "oldest pin (:e-1) dropped; newest three retained in order")
      (is (true? (:overflow? out))
          ":overflow? surfaces on the cap-breaching call")
      (is (= :e-1 (:epoch-id (:dropped-pin out)))
          ":dropped-pin names the evicted pin"))))

(deftest pin-snapshot-cap-32-end-to-end
  (testing "with the spec's 32-pin cap, the 33rd pin evicts pin #1"
    (let [thirty-two (mapv (fn [i] {:epoch-id    (keyword (str "e-" i))
                                    :frame-db    {:n i}
                                    :dispatch-id i
                                    :label       (str "pin-" i)})
                           (range 1 33)) ; 32 pins
          seed       {:store {} :overflow? false :dropped-pin nil}
          step       (fn [acc pin]
                       (h/pin-snapshot (:store acc) :rf/default pin))
          after-32   (reduce step seed thirty-two)]
      (is (= 32 (count (get-in after-32 [:store :rf/default]))))
      (is (false? (:overflow? after-32))
          "filling to exactly 32 is not an overflow")
      ;; Adding the 33rd:
      (let [thirty-third {:epoch-id :e-33 :frame-db {} :dispatch-id 33
                          :label "pin-33"}
            after-33     (h/pin-snapshot (:store after-32)
                                         :rf/default thirty-third)]
        (is (= 32 (count (get-in after-33 [:store :rf/default])))
            "still capped at 32")
        (is (true? (:overflow? after-33)))
        (is (= :e-1 (:epoch-id (:dropped-pin after-33)))
            "oldest (pin-1 / :e-1) evicted")
        (is (= :e-33 (:epoch-id (last (get-in after-33 [:store :rf/default]))))
            ":e-33 is the newest pin after eviction")))))

;; ---- (3) unpin / rename / find ------------------------------------------

(deftest unpin-snapshot-removes-pin
  (testing "unpin-snapshot drops the named pin; other frames untouched"
    (let [store {:rf/default [{:epoch-id :e-1 :frame-db {} :dispatch-id 1 :label "a"}
                              {:epoch-id :e-2 :frame-db {} :dispatch-id 2 :label "b"}]
                 :rf/other   [{:epoch-id :e-1 :frame-db {} :dispatch-id 1 :label "x"}]}
          out   (h/unpin-snapshot store :rf/default :e-1)]
      (is (= [:e-2] (mapv :epoch-id (get out :rf/default)))
          ":e-1 dropped from :rf/default")
      (is (= [:e-1] (mapv :epoch-id (get out :rf/other)))
          ":rf/other untouched"))))

(deftest unpin-snapshot-noop-on-miss
  (testing "removing a non-existent pin is a no-op"
    (let [store {:rf/default [{:epoch-id :e-1 :frame-db {} :dispatch-id 1 :label "a"}]}]
      (is (= store (h/unpin-snapshot store :rf/default :nope))
          "unpinning a missing id returns the store unchanged"))))

(deftest rename-pin-rewrites-only-label
  (testing "rename rewrites :label; other 4-tuple slots immutable"
    (let [pin   {:epoch-id :e-1 :frame-db {:x 1} :dispatch-id 42 :label "old"}
          store {:rf/default [pin]}
          out   (h/rename-pin store :rf/default :e-1 "new")
          got   (first (get out :rf/default))]
      (is (= "new"   (:label got)))
      (is (= :e-1   (:epoch-id got)))
      (is (= {:x 1} (:frame-db got)))
      (is (= 42     (:dispatch-id got))))))

(deftest find-pin-returns-the-pin-or-nil
  (let [pin   {:epoch-id :e-1 :frame-db {:x 1} :dispatch-id 1 :label "a"}
        store {:rf/default [pin]}]
    (is (= pin (h/find-pin store :rf/default :e-1)))
    (is (nil? (h/find-pin store :rf/default :missing)))
    (is (nil? (h/find-pin store :rf/other   :e-1)))))

;; ---- (4) pins survive ring-buffer eviction ------------------------------

(deftest chip-state-attached-when-epoch-in-history
  (testing "a pin whose :epoch-id is in the current history renders attached
            with the slot index populated"
    (let [history [(epoch :e-1 {} 1)
                   (epoch :e-2 {} 2)
                   (epoch :e-3 {} 3)]
          pin     {:epoch-id :e-2 :frame-db {} :dispatch-id 2 :label "mid"}
          state   (h/chip-state history pin)]
      (is (true? (:attached state)))
      (is (= 1 (:index state)) "0-based index in history vector"))))

(deftest chip-state-detached-when-epoch-aged-out
  (testing "a pin whose :epoch-id is no longer in history renders detached;
            :frame-db is still present on the pin so reset-frame-db! works"
    (let [pin     {:epoch-id    :e-aged
                   :frame-db    {:user/name "ada" :session :authed}
                   :dispatch-id 42
                   :label       "before-login"}
          history [(epoch :e-100 {} 100)
                   (epoch :e-101 {} 101)]
          state   (h/chip-state history pin)]
      (is (false? (:attached state)))
      (is (nil?   (:index state)))
      (is (= {:user/name "ada" :session :authed} (:frame-db (:pin state)))
          "pin retains :frame-db across history eviction — the value-direct
           handle reset-frame-db! consumes"))))

(deftest chip-states-vector-preserves-order
  (testing "chip-states preserves insertion order (the pin-store vector order)"
    (let [history [(epoch :e-1 {} 1) (epoch :e-2 {} 2)]
          pins    [{:epoch-id :e-1 :frame-db {} :dispatch-id 1 :label "first"}
                   {:epoch-id :e-2 :frame-db {} :dispatch-id 2 :label "second"}
                   {:epoch-id :e-aged :frame-db {} :dispatch-id 99 :label "old"}]
          states  (h/chip-states history pins)]
      (is (= ["first" "second" "old"] (mapv (comp :label :pin) states)))
      (is (= [true true false]        (mapv :attached states))
          "third pin (epoch aged out) renders detached"))))

;; ---- (5) history navigation --------------------------------------------

(deftest find-epoch-in-history-returns-record-or-nil
  (let [history [(epoch :e-1 {} 1) (epoch :e-2 {} 2) (epoch :e-3 {} 3)]]
    (is (= :e-2 (:epoch-id (h/find-epoch-in-history history :e-2))))
    (is (nil? (h/find-epoch-in-history history :missing)))
    (is (nil? (h/find-epoch-in-history history nil)))
    (is (nil? (h/find-epoch-in-history [] :e-1)) "empty history yields nil")))

(deftest epoch-index-and-epoch-id-at-index-are-inverses
  (let [history [(epoch :e-a {} 1) (epoch :e-b {} 2) (epoch :e-c {} 3)]]
    (is (= 1 (h/epoch-index-in-history history :e-b)))
    (is (= :e-b (h/epoch-id-at-index history 1)))
    (is (nil? (h/epoch-id-at-index history 99)))
    (is (nil? (h/epoch-id-at-index history -1)))))

(deftest newest-epoch-id-on-history
  (is (= :e-c
         (h/newest-epoch-id [(epoch :e-a {} 1) (epoch :e-b {} 2) (epoch :e-c {} 3)])))
  (is (nil? (h/newest-epoch-id []))))

(deftest step-epoch-back-and-forward
  (let [history [(epoch :e-a {} 1) (epoch :e-b {} 2) (epoch :e-c {} 3)]]
    (is (= :e-a (h/step-epoch history :e-b -1)) "step back from :e-b")
    (is (= :e-c (h/step-epoch history :e-b  1)) "step forward from :e-b")
    (is (= :e-a (h/step-epoch history :e-a -1)) "step back from oldest clamps")
    (is (= :e-c (h/step-epoch history :e-c  1)) "step forward from newest clamps")
    (is (= :e-c (h/step-epoch history nil   0)) "nil selection steps from newest")
    (is (nil? (h/step-epoch [] :e-x 1))         "empty history yields nil")))

(deftest dispatch-id-from-epoch-walks-trace-events
  (testing "dispatch-id-from-epoch picks the first :dispatch-id-bearing event"
    (is (= 42 (h/dispatch-id-from-epoch (epoch :e-1 {} 42))))
    (is (nil? (h/dispatch-id-from-epoch (epoch :e-1 {} nil)))
        "synthetic epoch (empty :trace-events) yields nil")))
