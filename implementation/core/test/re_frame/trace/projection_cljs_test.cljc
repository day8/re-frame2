(ns re-frame.trace.projection-cljs-test
  "Tests for `re-frame.trace.projection/group-by-event` +
  `domino-bucket`. Pure-data — no fixture, no frame, no router; JVM and
  CLJS run the same suite.

  Per rf2-wvzgd: the projection was lifted from Story's trace panel.
  The original Story version used synthetic op-types (`:rf.fx/do-fx`,
  `:fx`, `:rf.view/render` as op-type rather than operation) and the
  invented `:event/run` operation; this rewrite tracks the
  framework's actual trace surface per Spec 009 §`:op-type`
  vocabulary."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.trace.projection :as p]))

;; ---- domino-bucket --------------------------------------------------------

(deftest domino-bucket-classifies-event-ops
  (testing ":rf.event/dispatched buckets as :event (cascade root)"
    (is (= :event
           (p/domino-bucket {:op-type :rf.event :operation :rf.event/dispatched}))))
  (testing ":rf.event/run-start + :rf.event/run-end both bucket as :handler (cascade run markers)"
    (is (= :handler
           (p/domino-bucket {:op-type :rf.event :operation :rf.event/run-start})))
    (is (= :handler
           (p/domino-bucket {:op-type :rf.event :operation :rf.event/run-end}))))
  (testing ":rf.fx :rf.fx/do-fx buckets as :fx (effects map computed)"
    (is (= :fx
           (p/domino-bucket {:op-type :rf.fx :operation :rf.fx/do-fx}))))
  (testing ":event :rf.event/db-changed lands in :other (not a six-domino slot)"
    (is (= :other
           (p/domino-bucket {:op-type :rf.event :operation :rf.event/db-changed})))))

(deftest domino-bucket-classifies-fx-as-effects
  (testing "every :op-type :rf.fx event — :rf.fx/handled, override-applied, etc. — buckets as :effect"
    (is (= :effect (p/domino-bucket {:op-type :rf.fx :operation :rf.fx/handled})))
    (is (= :effect (p/domino-bucket {:op-type :rf.fx :operation :rf.fx/override-applied})))
    (is (= :effect (p/domino-bucket {:op-type :rf.fx :operation :rf.fx/skipped-on-platform})))))

(deftest domino-bucket-classifies-sub-ops
  (testing "both :rf.sub/run and :rf.sub/create bucket as :sub"
    (is (= :sub (p/domino-bucket {:op-type :rf.sub :operation :rf.sub/run})))
    (is (= :sub (p/domino-bucket {:op-type :rf.sub :operation :rf.sub/create})))))

(deftest domino-bucket-classifies-view-render
  (testing ":op-type :rf.view + :operation :rf.view/render buckets as :render"
    (is (= :render
           (p/domino-bucket {:op-type :rf.view :operation :rf.view/render})))))

(deftest domino-bucket-non-domino-events-fall-through-to-other
  (testing "events outside the six-domino vocabulary land in :other"
    (is (= :other (p/domino-bucket {:op-type :error :operation :rf.error/no-such-handler})))
    (is (= :other (p/domino-bucket {:op-type :warning :operation :rf.warning/missing-doc})))
    (is (= :other (p/domino-bucket {:op-type :rf.machine :operation :rf.machine/transition})))
    (is (= :other (p/domino-bucket {:op-type :rf.frame :operation :rf.frame/created})))
    (is (= :other (p/domino-bucket {:op-type :flow :operation :rf.flow/computed})))
    (is (= :other (p/domino-bucket {:op-type :rf.registry :operation :rf.registry/handler-registered})))))

(deftest domino-bucket-total-on-arbitrary-shapes
  (testing "the classification is total; an unknown op-type/operation pair returns :other"
    (is (= :other (p/domino-bucket {:op-type :totally-made-up :operation :nope})))
    (is (= :other (p/domino-bucket {})))))

;; ---- group-by-event -------------------------------------------------------

(defn- cascade-evs
  "Produce a representative one-cascade event stream."
  ([dispatch-id event-vec]
   (cascade-evs dispatch-id event-vec :rf/default))
  ([dispatch-id event-vec frame-id]
   [{:id 1 :op-type :rf.event    :operation :rf.event/dispatched
     :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v event-vec :frame frame-id}}
    {:id 2 :op-type :rf.event    :operation :rf.event/run-start
     :tags {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-start :frame frame-id}}
    {:id 3 :op-type :rf.event    :operation :rf.event/run-end
     :tags {:rf.trace/dispatch-id dispatch-id :rf.trace/phase :run-end :frame frame-id}}
    {:id 4 :op-type :rf.fx       :operation :rf.fx/do-fx
     :tags {:rf.trace/dispatch-id dispatch-id :frame frame-id}}
    {:id 5 :op-type :rf.fx       :operation :rf.fx/handled
     :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :db :frame frame-id}}
    {:id 6 :op-type :rf.fx       :operation :rf.fx/handled
     :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :dispatch :frame frame-id}}
    {:id 7 :op-type :rf.sub      :operation :rf.sub/run
     :tags {:rf.trace/dispatch-id dispatch-id :rf.sub/id :sub/foo :frame frame-id}}
    {:id 8 :op-type :rf.view     :operation :rf.view/render
     :tags {:rf.trace/dispatch-id dispatch-id :rf.view/render-key [:app/root nil] :frame frame-id}}]))

(deftest group-by-event-one-cascade-six-buckets
  (testing "a representative cascade reduces to one record with the six
            domino slots populated"
    (let [evs (cascade-evs 100 [:user/login {:id 42}])
          [c & more] (p/group-by-event evs)]
      (is (empty? more) "single cascade yields one record")
      (is (= 100 (:dispatch-id c)))
      (is (= :rf/default (:frame c)))
      (is (= [:user/login {:id 42}] (:event c)) ":event slot is the dispatched event vector")
      (is (some? (:handler c)) ":handler slot is populated by the :run-* emit")
      (is (some? (:fx c))      ":fx slot is the :rf.fx/do-fx emit")
      (is (= 2 (count (:effects c))) "both :rf.fx/handled events land in :effects")
      (is (= 1 (count (:subs c))))
      (is (= 1 (count (:renders c))))
      (is (= [] (:other c))    ":other is empty for a clean cascade"))))

(deftest group-by-event-multiple-cascades-sorted-by-first-id
  (testing "with two cascades interleaved, group-by-event emits them in
            emission order (lowest :id first)"
    (let [a (cascade-evs 200 [:a])
          b (mapv #(update % :id + 100) (cascade-evs 300 [:b]))
          ;; interleave: ids in :a are 1-8; :b are 101-108
          evs (interleave a b)
          cs  (p/group-by-event evs)]
      (is (= 2 (count cs)))
      (is (= 200 (:dispatch-id (first cs))))
      (is (= 300 (:dispatch-id (second cs)))))))

(deftest group-by-event-keeps-same-dispatch-id-separate-by-frame
  (testing "dispatch-id is frame-scoped, so same id in two frames yields two records"
    (let [a  (cascade-evs 10 [:counter/a-inc] :counter/a)
          b  (mapv #(update % :id + 100)
                   (cascade-evs 10 [:counter/b-inc] :counter/b))
          cs (p/group-by-event (concat a b))]
      (is (= 2 (count cs)))
      (is (= [[:counter/a 10] [:counter/b 10]]
             (mapv (juxt :frame :dispatch-id) cs)))
      (is (= [[:counter/a-inc] [:counter/b-inc]]
             (mapv :event cs))))))

;; ---- group-by-event-with-events -------------------------------------------

(deftest group-by-event-with-events-attaches-raw-events
  (testing "each record carries its raw composing events under :trace-events
            (the slim group-by-event record plus the raw events)"
    (let [evs        (cascade-evs 100 [:user/login {:id 42}])
          [c & more] (p/group-by-event-with-events evs)]
      (is (empty? more) "single cascade yields one record")
      ;; The slim record is identical to group-by-event's output…
      (is (= (first (p/group-by-event evs))
             (dissoc c :trace-events))
          ":trace-events is purely ADDITIVE — the slim shape is unchanged")
      ;; …plus the :trace-events slot holds exactly the composing events.
      (is (= (vec evs) (:trace-events c))
          ":trace-events is the vector of raw events that composed the cascade")
      (is (= 100 (:dispatch-id c)))
      (is (= :rf/default (:frame c))))))

(deftest group-by-event-with-events-isolates-trace-events-by-frame
  ;; rf2-1we9fa defect 1 — the drain-time projection. Two frames sharing a
  ;; SINGLE dispatch-id (the portable trace contract guarantees dispatch-id
  ;; uniqueness only WITHIN a frame; per-frame/per-realm id allocation
  ;; under EP-0010 / EP-0013 makes such collisions live). Each frame's
  ;; bundle MUST carry ONLY its own frame's raw :trace-events — NOT the
  ;; union of both frames' events. The prior consumer keyed :trace-events
  ;; by :rf.trace/dispatch-id ALONE, which merged both frames' events into
  ;; each bundle. group-by-event-with-events keys by [frame dispatch-id],
  ;; so the events stay isolated.
  (testing "same dispatch-id across two frames ⇒ two bundles, each holding
            only its own frame's :trace-events"
    (let [a   (cascade-evs 10 [:counter/a-inc] :counter/a)
          b   (mapv #(update % :id + 100)
                    (cascade-evs 10 [:counter/b-inc] :counter/b))
          ;; Interleave so a naive dispatch-id-only group-by could not rely
          ;; on input ordering to separate the frames.
          cs  (p/group-by-event-with-events (interleave a b))
          by-frame (into {} (map (juxt :frame identity)) cs)
          ca  (get by-frame :counter/a)
          cb  (get by-frame :counter/b)]
      (is (= 2 (count cs)) "two frames sharing dispatch-id 10 ⇒ two bundles")
      (is (= #{:counter/a :counter/b} (set (keys by-frame))))
      ;; The load-bearing assertion: NO foreign-frame events leak in.
      (is (= (vec a) (:trace-events ca))
          "frame :counter/a's bundle holds ONLY frame :counter/a events")
      (is (= (vec b) (:trace-events cb))
          "frame :counter/b's bundle holds ONLY frame :counter/b events")
      (is (every? #(= :counter/a (get-in % [:tags :frame]))
                  (:trace-events ca))
          "no frame :counter/b event leaks into frame :counter/a's bundle")
      (is (every? #(= :counter/b (get-in % [:tags :frame]))
                  (:trace-events cb))
          "no frame :counter/a event leaks into frame :counter/b's bundle"))))

(deftest group-by-event-with-events-drops-nothing
  (testing ":trace-events across all records accounts for every input event"
    (let [evs (concat (cascade-evs 1 [:a] :frame/x)
                      (cascade-evs 2 [:b] :frame/y))
          cs  (p/group-by-event-with-events evs)]
      (is (= (count evs)
             (reduce + 0 (map (comp count :trace-events) cs)))
          "every input event lands in exactly one bundle's :trace-events"))))

(deftest group-by-event-events-without-dispatch-id-land-in-ungrouped
  (testing "events emitted outside any drain (registry-time, frame
            lifecycle) carry no :rf.trace/dispatch-id and group under :ungrouped"
    (let [evs [{:id 1 :op-type :rf.frame :operation :rf.frame/created :tags {:frame :app}}
               {:id 2 :op-type :rf.registry :operation :rf.registry/handler-registered
                :tags {:kind :event :id :user/login}}]
          cs (p/group-by-event evs)]
      (is (= 1 (count cs)))
      (is (= :ungrouped (:dispatch-id (first cs))))
      (is (= 2 (count (:other (first cs))))
          "frame and registry events both flow through :other"))))

(deftest group-by-event-error-and-warning-events-ride-along-in-other
  (testing "an error fired inside a cascade lands in that cascade's
            :other slot, not as its own cascade (per rf2-g6ih4
            :rf.trace/dispatch-id rides every event)"
    (let [evs (conj (cascade-evs 400 [:foo])
                    {:id 100 :op-type :error :operation :rf.error/handler-exception
                     :tags {:rf.trace/dispatch-id 400 :event-id :foo}})
          [c] (p/group-by-event evs)]
      (is (= 400 (:dispatch-id c)))
      (is (= 1 (count (:other c)))
          "the error event lands in :other, not a sibling :ungrouped cascade"))))

(deftest group-by-event-handler-slot-takes-last-event-emit
  (testing "when the chain emits :rf.event/run-start + :rf.event/run-end
            the :handler slot ends up as the :run-end event (reduce
            overwrites)"
    (let [evs (cascade-evs 500 [:foo])
          [c] (p/group-by-event evs)]
      (is (= :rf.event/run-end (get-in c [:handler :operation])))
      (is (= :run-end (get-in c [:handler :tags :rf.trace/phase]))))))

(deftest group-by-event-empty-input-yields-empty-output
  (is (= [] (p/group-by-event [])))
  (is (= [] (p/group-by-event nil))))

(deftest group-by-event-shape-is-stable
  (testing "every cascade record carries the documented keys with
            collection-shaped defaults"
    (let [[c] (p/group-by-event [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                                  :tags {:rf.trace/dispatch-id 1 :rf.event/v [:e]}}])]
      (is (= #{:dispatch-id :parent-dispatch-id :frame :event :dispatched
               :handler :fx :effects :subs :renders :other}
             (set (keys c))))
      (is (vector? (:effects c)))
      (is (vector? (:subs c)))
      (is (vector? (:renders c)))
      (is (vector? (:other c))))))

(deftest group-by-event-surfaces-parent-dispatch-id
  (testing "the :parent-dispatch-id slot is read off the dispatched
            event's :rf.trace/parent-dispatch-id tag — the causal-parent
            link an :fx :dispatch / machine-internal child carries under
            epoch-per-event (Spec 009 §Dispatch correlation)"
    (let [evs [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                :tags {:rf.trace/dispatch-id        20
                       :rf.trace/parent-dispatch-id 10
                       :rf.event/v                  [:form/validate]}}]
          [c] (p/group-by-event evs)]
      (is (= 20 (:dispatch-id c)))
      (is (= 10 (:parent-dispatch-id c))
          "the spawning cascade's id is surfaced on the child cascade"))))

(deftest group-by-event-root-cascade-has-nil-parent
  (testing "a root (user / external) dispatch carries no
            :rf.trace/parent-dispatch-id tag, so :parent-dispatch-id is nil"
    (let [evs [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                :tags {:rf.trace/dispatch-id 10 :rf.event/v [:user/click]}}]
          [c] (p/group-by-event evs)]
      (is (= 10 (:dispatch-id c)))
      (is (nil? (:parent-dispatch-id c))))))

(deftest group-by-event-orders-dispatched-root-only-bundle-by-its-own-id
  (testing "a bundle carrying ONLY the dispatched root (no handler/fx/
            effects/subs/renders/other trace within its run) sorts by
            its own :dispatched event's :id, not by the ##Inf sentinel
            (rf2-yl4c0s — first-id previously destructured :event, the
            bare event VECTOR which carries no :id, instead of
            :dispatched, so a root-only bundle silently fell through to
            the sentinel and sorted LAST regardless of emission order)"
    (let [root-only {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.trace/dispatch-id :root-only :rf.event/v [:root-only]}}
          ;; A full six-domino cascade whose own ids (100-107) are all
          ;; HIGHER than the root-only bundle's id (1) — with the fix,
          ;; the root-only bundle's first-id (1) beats the full
          ;; cascade's first-id (100) and sorts first. Pre-fix, the
          ;; root-only bundle's first-id fell to the ##Inf sentinel and
          ;; sorted AFTER the full cascade instead.
          full      (mapv #(update % :id + 99) (cascade-evs :full-cascade [:full]))
          cs        (p/group-by-event (concat full [root-only]))]
      (is (= 2 (count cs)))
      (is (= [:root-only :full-cascade] (map :dispatch-id cs))
          "the root-only bundle (id 1) sorts before the full cascade (ids 100-107)"))))

(deftest group-by-event-dispatched-slot-carries-full-trace-event
  (testing "the :dispatched slot preserves the full :rf.event/dispatched
            trace so consumers (Xray Event lens) can read top-level
            hoisted slots like :rf.trace/call-site (rf2-twt7m Change 1)
            without scanning the raw buffer"
    (let [evs [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
                :tags {:rf.trace/dispatch-id 42 :rf.event/v [:cart/add-item]}
                :rf.trace/call-site {:file "src/views.cljs" :line 127}
                :source :ui :origin :app}]
          [c] (p/group-by-event evs)]
      (is (some? (:dispatched c))
          ":dispatched slot is populated with the trace event")
      (is (= {:file "src/views.cljs" :line 127}
             (:rf.trace/call-site (:dispatched c)))
          "the call-site (rf2-twt7m Change 1) rides through the projection")
      (is (= :ui (:source (:dispatched c)))
          "the :source slot rides through the projection")
      (is (= [:cart/add-item] (:event c))
          ":event slot still holds the slim event vector"))))
