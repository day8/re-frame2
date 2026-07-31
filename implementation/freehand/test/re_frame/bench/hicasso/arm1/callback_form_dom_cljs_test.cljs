(ns re-frame.bench.hicasso.arm1.callback-form-dom-cljs-test
  "THE ONE CALLBACK FORM, DRIVEN BY A REAL BROWSER EVENT
  (rf2-2rtt6.35, HD-024).

  `front/intent_cljs_test` proves the position table's algebra against
  stand-in events, which is the right altitude for it — a stand-in makes
  `preventDefault`, the target and the composition signals observable in
  a way a real event does not. What a stand-in cannot show is that React
  routes a genuine click to a closure this runtime minted, that the
  intent it returns drains through the arm's synchronous door, and that
  the echo is on screen. That is this file.

  Three rows, and each one is a row of the position table:

  1. **Event position.** `(hfn [e] …)` at `:on-click`, clicked for real;
     its returned VECTOR reaches app-db and the DOM re-paints in the same
     turn.
  2. **Event position, no intent.** The same form whose body returns
     something that is not a vector: the body runs and nothing is
     dispatched. In the predecessor this is a SECOND form (`v/handler`)
     with its own contract; here it is the same form at the same
     position, and the return value is the only difference.
  3. **Outside every walked position.** The form handed straight into a
     raw `#js` props object and called the way a JavaScript library calls
     it. This is the row that deletes the predecessor's fifth rule: there,
     a roster carrier in that position is a marker OBJECT, so the call
     raises the engine's own `TypeError` \"naming nothing you wrote\".
     Here it is a function, so it simply runs.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview hfn]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-callback-form)

(defonce ^:private !ran (atom 0))

(defn- skip! [why] (is true (str "a callback-form claim needs a real React DOM — " why)))

(defn- fresh! []
  (reset! !ran 0)
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id 3)
  (dogfood/reseed! frame-id 3)
  frame-id)

;; ---------------------------------------------------------------------------
;; The screen — one form at one position, three times
;; ---------------------------------------------------------------------------

(defview toggle-row
  "The row a real click lands on. `hfn` is the ONE form; `:on-click` is
  the position; the returned vector is the contract that position
  imposes. Nothing here names a form: the author wrote a function and
  returned data."
  [{:keys [id]}]
  (let [done? (rt/sub [:dogfood/done? id])]
    [:li.row {:data-id id :data-done (str done?)}
     [:button.toggle
      {:on-click (hfn [e]
                   (swap! !ran inc)
                   ;; A live event: the same form the predecessor would
                   ;; need `v/event` for, reading the DOM event and
                   ;; returning ONE intent.
                   (when (= "toggle" (.. e -target -dataset -role))
                     [:dogfood/toggle id]))
       :data-role "toggle"}
      "toggle"]
     [:button.silent
      ;; The same form, same position, whose body returns something that
      ;; is not a vector. The predecessor spells this `v/handler`.
      {:on-click (hfn [_] (swap! !ran inc) :nothing-to-dispatch)}
      "silent"]]))

(defn- query [handle sel] (.querySelector (:container handle) sel))

;; ---------------------------------------------------------------------------
;; 1 — event position: a returned vector reaches app-db and the DOM
;; ---------------------------------------------------------------------------

(deftest a-real-click-on-the-one-form-dispatches-what-it-returned
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toggle-row {:id 1}])]
        (try
          (is (= "false" (.getAttribute (query handle ".row") "data-done")))
          (.click (query handle ".toggle"))
          (mount/settle!)
          (is (= 1 @!ran) "React routed the click to the closure the runtime minted")
          (is (= "true" (.getAttribute (query handle ".row") "data-done"))
              "and the intent it RETURNED drained through the arm's
               synchronous door, so the echo is on screen in the same turn")
          (.click (query handle ".toggle"))
          (mount/settle!)
          (is (= "false" (.getAttribute (query handle ".row") "data-done"))
              "and again, so this is a working handler and not a one-shot")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — event position: a return that is not a vector dispatches nothing
;; ---------------------------------------------------------------------------

(deftest the-same-form-at-the-same-position-can-return-nothing
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toggle-row {:id 1}])]
        (try
          (.click (query handle ".silent"))
          (mount/settle!)
          (is (= 1 @!ran) "the body ran")
          (is (= "false" (.getAttribute (query handle ".row") "data-done"))
              "and nothing was dispatched — in the predecessor this is a
               different FORM with a different contract; here it is the same
               form, and the return value is the whole of the difference")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3 — outside every walked position, it is a function
;; ---------------------------------------------------------------------------

(deftest a-javascript-library-calling-it-natively-gets-a-real-call
  (testing "the predecessor's fifth rule, deleted. Its roster forms are
            site-owned, and a carrier that reaches a raw `#js` prop is a
            marker object rather than a function — so `props.onPing(…)`
            raises the engine's own TypeError, worded after whatever
            expression it tripped on and naming nothing the author wrote.
            The one form is a function everywhere, so a position Hicasso
            never walks costs the CONTRACT and nothing else."
    (let [cb       (hfn [x] (swap! !ran inc) [:would-have-dispatched x])
          js-props #js {:onPing cb}]
      (reset! !ran 0)
      (is (fn? (.-onPing js-props)))
      (is (= [:would-have-dispatched 3] ((.-onPing js-props) 3)))
      (is (= 1 @!ran))
      (is (true? (intent/callback? cb))
          "and it is still the marked form, so a position that DOES walk it
           would impose that position's contract on the same value"))))
