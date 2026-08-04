(ns re-frame.bench.hicasso.arm1.ambient-refusal-cljs-test
  "AMBIENT `rf/subscribe` / `rf/dispatch` INSIDE A BODY REFUSE (rf2-2rtt6.122).

  The hazard this file closes was real, verified, and fenced by nothing
  but which adapter the host happened to install. Hicasso mounts the
  shared adapter-context Provider regardless, and the UIx / Freehand
  adapters publish a `:adapter/current-frame` reader that reads the
  context slot — so an ambient `rf/subscribe` written inside a boundary
  body RESOLVED the boundary's own frame and quietly succeeded: a
  render-phase sub-cache mutation, ZERO collector edges, and a boundary
  that never re-renders when that subscription moves. That is HD-002
  clause (a)'s forbidden class. Under other configurations the same
  spelling threw. Silence or a throw, decided by a dependency choice
  made elsewhere.

  Core now has a refusal tier (`frame/call-with-ambient-frame-refused`)
  and [[re-frame.bench.hicasso.front.intent/with-frame]] establishes it
  over every Hicasso render extent, so the answer is the same under every
  adapter: a loud `:rf.error/ambient-frame-refused` naming the collector.

  WHY THESE ROWS ARE NOT VACUOUS, which is the only interesting thing
  about testing a refusal. A row asserting \"this throws\" proves nothing
  if the thing would have thrown anyway. So this file PUBLISHES THE
  FRAME ON THE SHARED CONTEXT SLOT itself ([[with-context-frame]]) —
  which is precisely what React does while rendering under a
  `frame-provider`, and precisely the configuration in which the hazard
  used to succeed — and [[the-hazard-configuration-is-live]] proves the
  same read SUCCEEDS one call outside the body. Every refusal row below
  runs under that same publication. Remove the fence and the refusal
  rows go green-to-red as a pair with that control staying green.

  A body run is reached through `rt/render-body` rather than a React
  root on purpose: React routes a render-phase throw to `reportError`,
  where `cljs.test` cannot see it, and a row that cannot see its own
  exception is a row that cannot go red. The real-React half — the
  adapter island rendering under a Hicasso tree, which must keep its
  ambient resolution — is `arm1/ambient_refusal_dom_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::rt122)

(defn- make-frame! []
  (live-frame/make-frame {:id frame-id})
  (frame/replace-app-db! frame-id {:v 7})
  (rf/reg-sub :rt122/v (fn [db _] (:v db)))
  (rf/reg-event :rt122/bump (fn [{:keys [db]} _] {:db (update db :v inc)}))
  frame-id)

(defn- with-context-frame
  "Publish `frame-kw` on the SHARED React frame-context slot for `thunk`'s
  extent, then restore. This is the tier-2 publication React performs
  while rendering under a `frame-provider`, and the UIx / Freehand
  adapters' `:adapter/current-frame` reader reads exactly this slot — so
  running under it reproduces the hazard's own configuration without a
  React root. Same save/restore shape the core adapter suite uses for its
  corrupted-context rows."
  [frame-kw thunk]
  (let [^js ctx  adapter-context/frame-context
        original (.-_currentValue ctx)]
    (set! (.-_currentValue ctx) frame-kw)
    (try (thunk)
         (finally (set! (.-_currentValue ctx) original)))))

(defn- outcome
  "Answer the thrown ex-data, or `::no-throw` with the value — so a row
  that silently succeeds fails with the value it silently produced
  rather than with a bare nil."
  [thunk]
  (try [::no-throw (thunk)]
       (catch :default e (ex-data e))))

(defn- captured-errors
  "The always-on error records `thunk` produced."
  [thunk]
  (let [records (volatile! [])]
    (error-emit/register-error-listener! ::rt122 (fn [r] (vswap! records conj r)))
    (try (thunk)
         (catch :default _ nil)
         (finally (error-emit/unregister-error-listener! ::rt122)))
    @records))

;; ---------------------------------------------------------------------------
;; The control — without it every row below is unfalsifiable
;; ---------------------------------------------------------------------------

(deftest the-hazard-configuration-is-live
  (testing "with the frame published on the context slot, an ambient
           subscribe OUTSIDE a body resolves it and reads — which is both
           the configuration the hazard needed and a legitimate position
           that must keep working"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (is (= f (frame/resolve-current-frame))
              "tier 2 is genuinely answering here; if it were not, every
               refusal row below would be proving nothing")
          (is (= 7 @(rf/subscribe [:rt122/v]))
              "an ambient read outside any Hicasso render extent is
               ordinary, correct re-frame2 and stays that way"))))))

;; ---------------------------------------------------------------------------
;; The refusal, both doors
;; ---------------------------------------------------------------------------

(deftest ambient-subscribe-inside-a-body-refuses-by-name
  (testing "the read that used to succeed silently now names itself"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [data (outcome #(rt/render-body f (fn [_] @(rf/subscribe [:rt122/v]) [:li]) {}))]
            (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
                (str "expected the refusal; got " (pr-str data)))
            (is (= :subscribe (:operation data)))
            (is (= :hicasso (:substrate data))
                "the payload names the substrate that refused")
            (is (= :rt122/v (:event-id data))
                "and the query that tried the door")
            (is (= 're-frame.subs/subscribe (:where data)))))))))

(deftest ambient-dispatch-inside-a-body-refuses-by-name
  (testing "the other ambient door refuses identically — a render-phase
           dispatch resolving the boundary's own frame is the same class
           of silent wrong"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [data (outcome #(rt/render-body f (fn [_] (rf/dispatch [:rt122/bump]) [:li]) {}))]
            (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
                (str "expected the refusal; got " (pr-str data)))
            (is (= :dispatch (:operation data)))
            (is (= :hicasso (:substrate data)))))))))

(deftest the-refusal-says-what-to-write-instead
  (testing "the message is the deliverable: the generic no-frame-context
           advice — establish a scope — is WRONG here, because a body
           always has one, so the payload has to carry the substrate's own
           sentence or the author follows advice that changes nothing"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [data   (outcome #(rt/render-body f (fn [_] @(rf/subscribe [:rt122/v]) [:li]) {}))
                reason (:reason data)]
            (is (string? reason))
            (is (re-find #"collector" reason)
                "it names the surface to use instead")
            (is (re-find #"NOT an absence" reason)
                "and says explicitly that adding another scope is not the fix")
            (is (= :read-through-the-boundary-collector (:recovery data)))
            (is (= 'hicasso/boundary-render (:extent data)))))))))

(deftest the-refusal-rides-the-always-on-axis
  (testing "same ladder as :rf.error/no-frame-context — what it prevents
           is a boundary that quietly stops re-rendering, which has no
           symptom at the point of the mistake and so must survive
           production elision"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [records (captured-errors
                          #(rt/render-body f (fn [_] @(rf/subscribe [:rt122/v]) [:li]) {}))]
            (is (some (fn [r] (= :rf.error/ambient-frame-refused (:error r))) records)
                (str "no always-on record for the refusal: " (pr-str records)))))))))

;; ---------------------------------------------------------------------------
;; The legitimate positions, each proven on its own
;; ---------------------------------------------------------------------------

(deftest a-carried-with-frame-still-carries-inside-a-body
  (testing "the refusal deletes the ambient FIND, never the carrying —
           `with-frame` is an explicitly carried stamp (EP-0002) and it
           answers inside a body exactly as it does outside one"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [seen (volatile! ::unset)]
            (rt/render-body f
                            (fn [_]
                              (rf/with-frame f (vreset! seen @(rf/subscribe [:rt122/v])))
                              [:li])
                            {})
            (is (= 7 @seen)
                "a refusal that also refused a carried stamp would make
                 {:frame id} and with-frame disagree, which is a worse
                 bug than the silence it replaces")))))))

(deftest an-explicit-frame-option-still-resolves-inside-a-body
  (testing "`{:frame <id>}` never consults the ambient resolver at all, so
           it is untouched by construction — pinned because 'untouched by
           construction' is exactly the claim that rots silently"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [seen (volatile! ::unset)]
            (rt/render-body f
                            (fn [_]
                              (vreset! seen @(rf/subscribe [:rt122/v] {:frame f}))
                              [:li])
                            {})
            (is (= 7 @seen))))))))

(deftest the-collector-is-untouched
  (testing "the substrate's own read surface goes through the collector and
           carries its frame explicitly — the refusal must not reach it"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [seen (volatile! ::unset)]
            (rt/render-body f (fn [_] (vreset! seen (rt/sub [:rt122/v])) [:li]) {})
            (is (= 7 @seen) "the read the author is supposed to write")))))))

(deftest the-refusal-unwinds-with-the-body
  (testing "the extent is the body's synchronous run and nothing more. A
           closure minted inside the body and called after it returned —
           the model of every child fiber, host-interop gate and event
           callback — resolves ambiently again. This is the scoping row:
           React renders a child only after the parent's render function
           has returned, so an adapter island under a Hicasso tree never
           runs inside the binding"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [deferred (volatile! nil)]
            (rt/render-body f
                            (fn [_]
                              (vreset! deferred (fn [] @(rf/subscribe [:rt122/v])))
                              [:li])
                            {})
            (is (= f (frame/resolve-current-frame))
                "the binding popped with the body run")
            (is (= 7 (@deferred))
                "and the very same closure that would have refused inside
                 the body resolves once the body has returned")))))))

(deftest an-event-handler-dispatched-from-a-body-intent-is-unaffected
  (testing "handlers run after the render extent has unwound, so ambient
           resolution inside one is ordinary — proven by driving the arm's
           own frame-locked dispatch and reading the result"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (rt/render-body f (fn [_] [:li]) {})
          ((rt/frame-dispatch f) [:rt122/bump])
          (is (= 8 @(rf/subscribe [:rt122/v]))
              "the intent's dispatch landed and an ambient read outside the
               extent still resolves"))))))

;; ---------------------------------------------------------------------------
;; The ordinary path is not disturbed
;; ---------------------------------------------------------------------------

(deftest a-genuinely-frameless-op-still-reports-absence
  (testing "two absences, two errors. With no scope and no published
           context the answer is still :rf.error/no-frame-context — the
           refusal must not colonise the generic case"
    (make-frame!)
    (let [data (outcome (fn [] @(rf/subscribe [:rt122/v])))]
      (is (= :rf.error/no-frame-context (:rf.error/id data))
          (str "expected the untouched absence error; got " (pr-str data))))))

(deftest resolution-outside-any-refusal-is-unchanged
  (testing "the ordinary path pays one nil-test and answers exactly what it
           answered before: tier 1 wins, then tier 2, then nil"
    (let [f (make-frame!)]
      (is (nil? (frame/resolve-current-frame)) "no tier answers")
      (with-context-frame f
        (fn [] (is (= f (frame/resolve-current-frame)) "tier 2 answers")))
      (rf/with-frame f
        (is (= f (frame/resolve-current-frame)) "tier 1 answers"))
      (with-context-frame ::other
        (fn [] (rf/with-frame f
                 (is (= f (frame/resolve-current-frame))
                     "tier 1 still wins over tier 2")))))))
