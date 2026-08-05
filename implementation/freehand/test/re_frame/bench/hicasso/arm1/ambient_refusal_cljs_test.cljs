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
  ambient resolution — is `arm1/ambient_refusal_dom_cljs_test`.

  THE DOOR HAS THREE CONSUMERS AND THIS FILE ONCE COVERED TWO
  (rf2-hnrww). Ambient `subscribe`, ambient `dispatch` — and
  `rf/capture-frame`, Spec 002's *one public carry primitive*, whose
  0-arity resolves ambiently because it means to CAPTURE rather than to
  read or to dispatch. Its behaviour inside a body was a consequence of
  the refusal rather than a contract; the closing section below makes it
  one, together with each legitimate carry spelling proved individually."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
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

;; ---------------------------------------------------------------------------
;; THE CARRY — the door's THIRD consumer (rf2-hnrww; the design's W10,
;; INVERTED)
;; ---------------------------------------------------------------------------
;;
;; THE ROW PLANNED HERE SAID THE OPPOSITE, and that is worth recording
;; rather than quietly fixing. rf2-2rtt6.118's witness list pinned "the
;; accidental door": 0-arity `rf/capture-frame` inside a Hicasso body
;; SUCCEEDS on the dominant configurations — through the raw React-context
;; read the UIx and Freehand adapters publish — recorded so that a future
;; change to the accident would be seen rather than silent.
;; **rf2-2rtt6.122 IS that change.** Built as designed, the row would
;; assert a behaviour the tree no longer has, so it is inverted: the carry
;; refuses, and each spelling that still carries is proved on its own.
;;
;; THE ERROR ID IS NOT REPEATED IN THESE ROWS, deliberately.
;; `:rf.error/ambient-frame-refused` is PROVISIONAL (rf2-k0rbk may rename
;; it), so a carry's refusal is pinned as *the same id an ambient read
;; gets*, taken live from [[refusal-id]]. The literal is anchored ONCE in
;; this file — by `ambient-subscribe-inside-a-body-refuses-by-name` above —
;; so a rename touches one row rather than five, and these rows go on
;; saying the thing that is actually interesting: that a carry and a read
;; refuse identically.

(defn- refusal-id
  "The id core raises for an ambient READ refused inside a body. Taken
  live, so it is the anchor rather than a second copy of the spelling."
  [f]
  (:rf.error/id
    (outcome #(rt/render-body f (fn [_] @(rf/subscribe [:rt122/v]) [:li]) {}))))

(deftest a-carry-inside-a-body-refuses-exactly-as-a-read-does
  (testing "`(rf/capture-frame)` in a body is the platform keystone
           resolving ambiently, so the extent refuses it — under the same
           context publication that makes the read rows non-vacuous, and
           with the operation naming the carry rather than a read"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [data (outcome #(rt/render-body f (fn [_] (rf/capture-frame) [:li]) {}))]
            (is (= (refusal-id f) (:rf.error/id data))
                (str "a carry must refuse with the SAME id a read does; got "
                     (pr-str data)))
            (is (= :capture-frame (:operation data))
                "and the payload must say which door was tried, or the advice
                 cannot be read against the right recovery")
            (is (= :hicasso (:substrate data)))
            (is (= 'hicasso/boundary-render (:extent data)))
            (is (= 're-frame.core/capture-frame (:where data)))))))))

(deftest the-refusal-never-consults-the-adapter-which-is-why-it-is-adapter-independent
  (testing "the claim 'it refuses under EVERY adapter' cannot be settled by
           trying adapters — Spec 006 allows one per process. It is settled
           structurally: under refusal the resolver collapses to the CARRIED
           tier, which is exactly the `:clj` branch, so the
           `:adapter/current-frame` hook — the only part of the chain that
           differs between adapters — is not reached at all. Counted here
           rather than reasoned about"
    (let [f        (make-frame!)
          original (late-bind/get-fn :adapter/current-frame)
          calls    (volatile! 0)]
      (is (some? original)
          "precondition: an adapter has published the hook, so a zero below
           means 'not consulted' rather than 'nothing to consult'")
      (late-bind/set-fn! :adapter/current-frame
                         (fn [] (vswap! calls inc) (original)))
      (try
        (with-context-frame f
          (fn []
            (vreset! calls 0)
            (outcome #(rt/render-body f (fn [_] (rf/capture-frame) [:li]) {}))
            (is (zero? @calls)
                "the refused carry never asked the adapter")

            (vreset! calls 0)
            (is (= f (frame/resolve-current-frame))
                "control: one call outside the body")
            (is (pos? @calls)
                "and THAT read did ask it — so the zero above is the tier
                 being withdrawn, not a hook that is never called")))
        (finally (late-bind/set-fn! :adapter/current-frame original))))))

(deftest the-refusal-tells-a-carry-what-to-write
  (testing "rf2-hnrww's first half. The advice was written for a read and a
           dispatch — 'read through the collector, dispatch through an
           intent' — and an author who arrived through `rf/capture-frame`
           is doing neither, so it named nothing they could write. A carry
           is a third thing and the sentence has to say so"
    (let [f (make-frame!)]
      (with-context-frame f
        (fn []
          (let [data   (outcome #(rt/render-body f (fn [_] (rf/capture-frame) [:li]) {}))
                reason (:reason data)]
            (is (string? reason))
            (is (re-find #"CARRYING" reason)
                "the third door is named as a third door")
            (is (re-find #"capture-frame <frame-id>" reason)
                "and the 1-arity — the spelling that never consults the
                 resolver — is the recovery it points at")
            (is (re-find #"\(rf/capture-frame \(h/frame\)\)" reason)
                "with the composed spelling written out, which is the
                 sentence an author can paste")))))))

;; ---------------------------------------------------------------------------
;; The legitimate CARRY positions, each proven on its own
;; ---------------------------------------------------------------------------

(deftest the-one-arity-carry-still-carries-inside-a-body
  (testing "`(rf/capture-frame <id>)` never consults the resolver, so the
           refusal cannot touch it. That is the honest recovery available
           to an author who already knows the id, and it is asserted all
           the way through to a dispatch that lands after the extent is
           gone — a capture that merely constructed would prove nothing"
    (let [f    (make-frame!)
          held (volatile! nil)]
      (with-context-frame f
        (fn []
          (rt/render-body f (fn [_] (vreset! held (rf/capture-frame f)) [:li]) {})))
      (is (= f (:frame @held)))
      (is (nil? frame/*ambient-frame-refusal*) "the extent has unwound")
      ((:dispatch-sync @held) [:rt122/bump])
      (is (= 8 @(rf/subscribe [:rt122/v] {:frame f}))
          "the carried api dispatched into its own frame"))))

(deftest the-composed-carry-is-refusal-immune
  (testing "the ruled spelling — `(rf/capture-frame (h/frame))`. It is the
           answer for the author the 1-arity cannot serve: a reusable view
           mounted under N frames, which does not know its own id. `h/frame`
           supplies the id the carrying spellings presuppose, and it reads
           the arm's own binding rather than core's chain, so the
           composition never reaches the refused tier at all"
    (let [f    (make-frame!)
          held (volatile! nil)]
      (with-context-frame f
        (fn []
          (rt/render-body f
                          (fn [_]
                            (vreset! held (rf/capture-frame (intent/hframe)))
                            [:li])
                          {})))
      (is (= f (:frame @held)))
      ((:dispatch-sync @held) [:rt122/bump])
      (is (= 8 @(rf/subscribe [:rt122/v] {:frame f}))))))
