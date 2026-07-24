(ns re-frame.freehand.compiled-event-lowering-cljs-test
  "A compiled `(v/event …)` at a DOM handler site DISPATCHES — the two
  halves of one site, held to the same answer.

  A handler site has two readers and they read different things. The
  DOOR reads the compiled CLASSIFICATION (`:ui-event`) through
  `controlled/compiled-role` and decides the lane; the RUNTIME reads the
  VALUE the lowering produced through `events/event-plan` and decides
  what firing does. They agree only if the lowering produces the value
  whose plan role is the role the classification named.

  It did not. `analyze-handler` lowered a whole-handler `v/event` to the
  BARE `(fn [e] …)` the roster callback carries, so `event-plan`
  classified it `:bare-fn` — whose firing arm applies the function and
  THROWS AWAY the event vector it answered. Nothing raised, the build
  succeeded, the door reported `:event`, and the click did nothing at
  all (rf2-berc2). The whole-handler position now lowers to the roster
  constructor the interpreted macro expands to.

  The rows below are deliberately about the ATTRIBUTED RESULT — the
  event vector a fired site really dispatched — because the defect
  raised nothing and rendered fine. A row asserting `no error` passes
  against it. The bare-fn control at the bottom fires the value the
  defect produced and pins that it dispatches NOTHING, so the rows above
  cannot be green for the wrong reason.

  Runs on both hosts: the analyzer is pure and its resolution injected,
  and the site/commit/fire machinery is common. The mounted half — a
  real click on a real button — is
  `re-frame.freehand.compiled-mount-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.events :as events]))

(defn- handler-of
  "The ONE analyzed handler site of `template`."
  [template]
  (first (get-in (ana/analyze (mk-env) template) [:props :events])))

(defn- fired
  "Everything a committed site carrying `value` dispatches when its proxy
  is fired once with `payload`. The REAL path — one candidate, one
  commit, the site's own proxy — not a direct call to the body."
  ([value] (fired value {}))
  ([value payload]
   (let [seen  (atom [])
         owner (events/owner :app/probe)
         cand  (events/candidate owner)
         proxy (events/site cand :on-click value events/payload-map)]
     (events/commit! cand #(swap! seen conj %))
     (proxy payload)
     @seen)))

;; ---------------------------------------------------------------------------
;; The lowering
;; ---------------------------------------------------------------------------

(deftest a-whole-handler-v-event-lowers-to-the-roster-constructor
  (testing "The compiled lowering of `(v/event [e] …)` is the EXACT
            constructor call the interpreted `v/event` macro expands to,
            wherever the form appears — so both front ends hand the
            runtime one value with one classification. Asserted as the
            emitted form, because that form is what the emitters write
            and nothing downstream wraps it."
    (let [h (handler-of '[:button {:on-click (event [e] [:app/clicked])} "x"])]
      (is (= :ui-event (:classification h)))
      (is (= '(re-frame.freehand.events/callback :event (fn [e] [:app/clicked]) 1)
             (:form h))
          "the roster constructor, in the dispatching role, with the declared arity")))

  (testing "`v/handler` lowers to its own role through the same
            constructor, so the value a compiled site carries is the value
            its interpreted twin carries — one spelling, one roster member.
            Its firing behaviour never differed from a bare fn's (both arms
            apply and drop the return), and the structural tree records both
            under the mode-neutral `{:rf.ui/opaque :fn}` member, so nothing
            about the recorded tree moves either. What moves is that there
            is now ONE lowering for the form rather than a bare fn at the
            whole-handler position and the constructor everywhere else."
    (let [h (handler-of '[:button {:on-click (handler [e] (.preventDefault e))} "x"])]
      (is (= :handler (:classification h)))
      (is (= '(re-frame.freehand.events/callback :handler (fn [e] (.preventDefault e)) 1)
             (:form h))))))

;; ---------------------------------------------------------------------------
;; The two halves agree
;; ---------------------------------------------------------------------------

(deftest the-door-role-and-the-runtime-plan-role-are-the-same-role
  (testing "The door is told `:event` by `compiled-role`; the runtime
            reads the lowered VALUE. Both must name the same role, or the
            compiled site takes a lane its own handler cannot honour —
            which is exactly what a bare-fn lowering did, with
            `compiled-role` saying `:event` and `event-plan` saying
            `:bare-fn`."
    (let [lowered (events/callback :event (fn [_] [:app/clicked]) 1)]
      (is (= :event (controlled/compiled-role :ui-event))
          "the door's half, read from the compiled classification")
      (is (= :event (:role (events/event-plan lowered)))
          "the runtime's half, read from the value the lowering produces")
      (is (contains? controlled/synchronous-outcomes (:role (events/event-plan lowered)))
          "so a controlled v/event site really is a synchronous-lane outcome"))
    (is (= :bare-fn (:role (events/event-plan (fn [_] [:app/clicked]))))
        "non-vacuous: the value the defect produced classifies as something else"))

  (testing "The same agreement, read off a real analyzed site rather
            than a hand-built value: the analyzed classification and the
            emitted form's roster role are the same word."
    (let [h (handler-of '[:input {:value v :on-input (event [e] [:form/typed])}])]
      (is (true? (:sync? h)) "a controlled v/event site is inside the door")
      (is (= (controlled/compiled-role (:classification h))
             (second (:form h)))
          "the role the door was told IS the role the emitted constructor carries"))))

;; ---------------------------------------------------------------------------
;; What a fired site actually dispatches
;; ---------------------------------------------------------------------------

(deftest a-lowered-v-event-site-dispatches-the-vector-its-body-answered
  (testing "The attributed result. A committed site carrying the LOWERED
            value dispatches the exact vector the body answered, with the
            closed projection trio materialized from the payload — and
            `nil` dispatches nothing, which is how a v/event declines."
    (is (= [[:app/clicked]]
           (fired (events/callback :event (fn [_] [:app/clicked]) 1)))
        "the body's vector reaches dispatch")
    (is (= [[:form/typed "mike"]]
           (fired (events/callback :event (fn [_] [:form/typed :re-frame.freehand/value]) 1)
                  {:re-frame.freehand/value "mike"}))
        "and its projections materialize from the site's payload")
    (is (= []
           (fired (events/callback :event (fn [_] nil) 1)))
        "a nil answer dispatches nothing, rather than a malformed event"))

  (testing "NON-VACUITY, and the defect itself. The bare `(fn [e] …)` the
            analyzer used to emit is a legal value at the same site — it
            fires, raises nothing, and dispatches NOTHING, because
            `:bare-fn` applies the function for its side effects and
            discards what it answered. Every row above would be green
            against that value if it asserted `no error` instead of the
            vector."
    (is (= [] (fired (fn [_] [:app/clicked])))
        "the shape of the bug: fired, silent, and nothing dispatched")
    (is (= [] (fired (v/handler [_] [:app/clicked])))
        "and v/handler is the SPELLING for that outcome — its return is dropped")))
