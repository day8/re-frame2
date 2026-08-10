(ns re-frame.hicasso.hframe-cljs-test
  "`h/frame` — THE AUTHOR-FACING FRAME READ (rf2-841vn).

  The design is tracked at `docs/design/hicasso/studio/hframe-design.md`,
  ruled BUILD. This file is its node half: everything the primitive does
  that is not React's is answerable here, and the `-dom` sibling
  ([[re-frame.hicasso.hframe-dom-cljs-test]]) then proves React drives
  *this* seam rather than re-proving what the seam does.

  ## The one thing worth saying about why these rows are not vacuous

  Two of them are claims of ABSENCE — that `h/frame` is not a tracked
  read, and that it never enters core's ambient resolution chain — and an
  absence proved by a green row that would have been green anyway is not
  proof. So each is paired with the positive reading that would have
  moved: [[the-frame-read-registers-no-edge]] takes the read set of a body
  that reads a subscription and shows adding `h/frame` leaves it
  identical, and [[the-frame-read-does-not-go-through-cores-ambient-chain]]
  asserts core's own resolver answering **nil** in the very extent where
  `h/frame` answers — which is the refusal (rf2-2rtt6.122) doing its job
  one line away from the value being read.

  The CARRY under refusal — `rf/capture-frame` inside a body — is a fact
  about the refusal rather than about this primitive (rf2-hnrww), and the
  package's refusal witness is the bead that owns it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.todo-support :as todo]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; `:ambient-frame nil` for the reason the refusal suite gives: the
     ;; fixture's default leaves a dynamic-var stamp in scope, and tier 1
     ;; wins over everything — so a row claiming core's chain answers
     ;; nothing inside a body would pass for the wrong reason.
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(def ^:private frame-a ::hframe-a)
(def ^:private frame-b ::hframe-b)

(defn- frames! []
  (todo/make-frame! frame-a 3)
  (todo/make-frame! frame-b 3)
  (todo/reseed! frame-a 3)
  (todo/reseed! frame-b 3)
  nil)

(defn- outcome
  "The thrown ex-data, or `::no-throw` with the value — so a row that
  silently succeeds fails with what it silently produced."
  [thunk]
  (try [::no-throw (thunk)]
       (catch :default e (ex-data e))))

(defn- read-in-body
  "Run `body-fn` as a boundary body for `frame-kw` and answer whatever it
  put in the volatile it was handed. `collector/render-body` is the
  shell's own fence minus React, which contributes nothing to any claim
  here."
  [frame-kw body-fn]
  (let [seen (volatile! ::unset)]
    (collector/render-body frame-kw (fn [_] (body-fn seen) [:li]) {})
    @seen))

(defn- with-context-frame
  "Publish `frame-kw` on the SHARED React frame-context slot for `thunk`'s
  extent, then restore — the tier-2 publication React performs while
  rendering under a `frame-provider`, and what the installed adapter's
  `:adapter/current-frame` reader reads.

  Only one row needs it, and that row needs it badly: without the slot
  published, core's resolver answers nil inside a body *anyway*, so
  [[the-frame-read-does-not-go-through-cores-ambient-chain]] would be
  green against a tree with no refusal in it at all."
  [frame-kw thunk]
  (let [^js ctx  adapter-context/frame-context
        original (.-_currentValue ctx)]
    (set! (.-_currentValue ctx) frame-kw)
    (try (thunk)
         (finally (set! (.-_currentValue ctx) original)))))

;; ---------------------------------------------------------------------------
;; W1 — it answers the rendering boundary's frame
;; ---------------------------------------------------------------------------

(deftest the-frame-read-answers-the-rendering-boundarys-frame
  (frames!)
  (testing "the whole primitive, in one line: inside a body it is the id
           keyword of the boundary React is rendering"
    (is (= frame-a (read-in-body frame-a (fn [seen] (vreset! seen (intent/hframe)))))))

  (testing "and it FOLLOWS the boundary — the same body run for a second
           frame answers the second frame. A read that was constant per
           process, or per first-mount, would pass the row above and be
           useless to the case the primitive exists for: one reusable view
           mounted under N frames"
    (is (= frame-b (read-in-body frame-b (fn [seen] (vreset! seen (intent/hframe))))))))

;; ---------------------------------------------------------------------------
;; W3 — outside a render extent it is loud, and the text is layer-routed
;; ---------------------------------------------------------------------------

(deftest the-frame-read-outside-a-render-extent-is-loud
  (frames!)
  (testing "no extent, no answer — never a guessed frame and never nil"
    (let [data (outcome intent/hframe)]
      (is (= :rf.error/hicasso-frame-outside-boundary (:rf.error/id data))
          (str "expected the loud error; got " (pr-str data)))
      (is (= :read-the-frame-inside-a-boundary-render (:recovery data)))
      (is (= 'front.intent/hframe (:where data)))))

  (testing "and the message ROUTES BY LAYER rather than saying 'do it
           inside a body'. The overwhelmingly common way to reach this
           error is mis-layered async work, whose fix is not to move the
           call — it is to move the work"
    (let [reason (:reason (outcome intent/hframe))]
      (is (string? reason))
      (is (re-find #"EVENT layer" reason)
          "the first thing it names is where async work belongs")
      (is (re-find #":dispatch-later" reason)
          "with the framework's own spelling for a delay")
      (is (re-find #"capture-frame <frame-id>" reason)
          "and the scope-free door for code that already knows its frame")))

  (testing "the same read inside a body is fine, so the row above is
           pinning the boundary of the extent rather than a broken call"
    (is (= frame-a (read-in-body frame-a (fn [seen] (vreset! seen (intent/hframe))))))))

;; ---------------------------------------------------------------------------
;; W4 — inside a render callback it answers the SUPPLYING boundary
;; ---------------------------------------------------------------------------

(deftest a-render-callback-reads-the-supplying-boundarys-frame
  (frames!)
  (testing "rf2-2rtt6.74's owner rule, reached through `h/frame`. A render
           callback is minted during the supplying boundary's body and
           INVOKED later by a foreign component — so the frame it must
           answer is the owner's. This is the position that makes the
           choice of `intent/*frame*` over the runtime's own render slot
           load-bearing: the runtime slot is nil here, because the foreign
           render runs outside the render pass"
    (let [callback (volatile! nil)
          seen     (volatile! ::unset)]
      ;; Lowered at a NON-event position inside frame-a's body, which is
      ;; what gives it the render contract.
      (collector/render-body frame-a
                             (fn [_]
                               (vreset! callback
                                        (intent/lower-prop
                                          :render-row
                                          (intent/callback (fn [] (vreset! seen (intent/hframe))))))
                               [:li])
                             {})
      (is (some? @callback) "precondition: the render position lowered a wrapper")

      (testing "invoked from outside any extent at all — the foreign
               component's own render"
        (is (not (collector/rendering?))
            "the premise, asserted rather than described: the runtime's own
             render slot is EMPTY at the moment the callback runs, so a
             `h/frame` built on `rstate.frame` would answer nothing here.
             This is design §3's load-bearing sentence, made measurable")
        (@callback)
        (is (= frame-a @seen)))

      (testing "and invoked while a DIFFERENT frame is the ambient one, it
               still answers the owner's. A foreign component has no frame
               of its own and frames are isolated contexts, so the
               supplying boundary is the only frame that can own this call"
        (vreset! seen ::unset)
        (intent/with-frame frame-b (collector/frame-dispatch frame-b) (fn [] (@callback)))
        (is (= frame-a @seen)
            "the invoker's frame must not colonise the owner's callback")))))

;; ---------------------------------------------------------------------------
;; W5 (the edge half) — not a tracked read
;; ---------------------------------------------------------------------------

(deftest the-frame-read-registers-no-edge
  (frames!)
  (testing "a body whose ONLY read is `h/frame` records no sub-key at all.
           Reads become edges because they are sub-KEYS; the frame is not
           one — it is render-constant per boundary and resolved once by
           the shell"
    (collector/render-body frame-a (fn [_] [:li (str (intent/hframe))]) {})
    (is (= [] (vec (collector/reads-of (collector/last-reads))))))

  (testing "and adding it to a READING body changes the read set not at
           all — same keys, same order. The control is the point: the
           first row alone would be green for a body that read nothing
           for any reason"
    (collector/render-body frame-a
                           (fn [_] [:li (str (collector/sub [:hicasso.todo/done? 0]))])
                           {})
    (let [without (vec (collector/reads-of (collector/last-reads)))]
      (collector/render-body frame-a
                             (fn [_]
                               [:li (str (intent/hframe))
                                (str (collector/sub [:hicasso.todo/done? 0]))])
                             {})
      (let [with (vec (collector/reads-of (collector/last-reads)))]
        (is (= 1 (count without)) "precondition: the control body read exactly one key")
        (is (= without with)
            (str "the frame read must contribute nothing to the collector: "
                 (pr-str without) " vs " (pr-str with)))))))

;; ---------------------------------------------------------------------------
;; It is not core's ambient chain, which is what makes it deterministic
;; ---------------------------------------------------------------------------

(deftest the-frame-read-does-not-go-through-cores-ambient-chain
  (frames!)
  (testing "the two answers taken in the SAME extent, one line apart:
           core's resolver says 'no ambient frame' — rf2-2rtt6.122's
           refusal, doing exactly its job — while `h/frame` answers the
           boundary's id. That gap is the whole reason this primitive is
           deterministic where the ambient chain was not: it reads the
           runtime's own binding and never enters the resolution chain, so
           no adapter, renderer or hook publication can change its answer.

           THE CONTEXT SLOT IS PUBLISHED FOR THE WHOLE ROW, and it has to
           be. Without it core answers nil inside a body for the boring
           reason that it would answer nil anywhere, and the row would be
           green against a tree carrying no refusal at all — the vacuity
           the refusal suite's own control row exists to rule out"
    (let [seen (volatile! ::unset)]
      (with-context-frame frame-a
        (fn []
          (is (= frame-a (frame/resolve-current-frame))
              "control: tier 2 is genuinely answering out here, so the nil
               below is the withdrawal and not an empty chain")
          (collector/render-body frame-a
                                 (fn [_]
                                   (vreset! seen {:core   (frame/resolve-current-frame)
                                                  :hframe (intent/hframe)})
                                   [:li])
                                 {})))
      (is (nil? (:core @seen))
          "core's ambient resolver is withdrawn for the extent")
      (is (= frame-a (:hframe @seen))
          "and `h/frame` answers anyway"))))

;; ---------------------------------------------------------------------------
;; The composition — the ruled carry spelling (design §2(3))
;; ---------------------------------------------------------------------------

(deftest the-composed-carry-fires-into-its-own-frame-after-the-render
  (frames!)
  (testing "`(rf/capture-frame (h/frame))` is the ruled spelling, and this
           is what it buys: a closure built during a body and fired long
           after the render's dynamic extent has unwound still dispatches
           into the boundary's own frame. Two frames side by side, one
           body, so a capture that reached for an ambient frame at FIRE
           time — or captured a process-wide one — lands in the wrong
           place and this row says so"
    (let [carry (fn [frame-kw]
                  (let [held (volatile! nil)]
                    (collector/render-body frame-kw
                                           (fn [_]
                                             (vreset! held (rf/capture-frame (intent/hframe)))
                                             [:li])
                                           {})
                    @held))
          a     (carry frame-a)
          b     (carry frame-b)]
      (is (= frame-a (:frame a)))
      (is (= frame-b (:frame b)))

      (testing "and each dispatches into ITS OWN frame, after the extent
               has gone"
        (is (nil? intent/*frame*) "precondition: no render extent is live here")
        ((:dispatch-sync a) [:hicasso.todo/toggle 0])
        (is (true? @(rf/subscribe [:hicasso.todo/done? 0] {:frame frame-a})))
        (is (false? @(rf/subscribe [:hicasso.todo/done? 0] {:frame frame-b}))
            "frames are isolated contexts — the sibling must not have moved")))))

;; ---------------------------------------------------------------------------
;; The configuration where the ambient carry used to answer the WRONG frame
;; ---------------------------------------------------------------------------

(deftest a-carried-outer-scope-refuses-rather-than-answering-the-wrong-frame
  (frames!)
  (testing "FOUND while grounding the SSR rows and pinned as a DEFECT
           (rf2-nqj22); this row is its inversion, not a new claim.
           rf2-2rtt6.122's refusal withdrew the ambient FIND and never the
           CARRYING, so a tier-1 stamp — an enclosing `rf/with-frame` — still
           answered inside a body. Core was behaving exactly as EP-0002
           specifies: frame identity is carried, and that stamp WAS carried.

           But the boundary renders a different frame, and everything else in
           the body targets THAT one: the collector's reads, the lowered
           intents, the presence tray. One body, two frames, chosen by which
           spelling the author reached for, and silent. Frames are ISOLATED
           contexts, so the extent now declares its own frame to core and a
           mismatched stamp refuses instead of quietly winning.

           `h/frame` and the composed carry are unchanged — they read the
           boundary's own binding and never enter core's chain at all, which
           is exactly what the primitive is for"
    (let [seen (volatile! ::unset)]
      (rf/with-frame frame-b
        (collector/render-body frame-a
                               (fn [_]
                                 (vreset! seen {:ambient  (outcome #(rf/capture-frame))
                                                :composed (rf/capture-frame (intent/hframe))
                                                :hframe   (intent/hframe)})
                                 [:li])
                               {}))
      (is (= :rf.error/ambient-frame-refused (:rf.error/id (:ambient @seen)))
          (str "the ambient carry must refuse rather than answer the ENCLOSING
                scope; got " (pr-str (:ambient @seen))))
      (is (= frame-b (:carried-frame (:ambient @seen)))
          "naming the stamp that was carried")
      (is (= frame-a (:extent-frame (:ambient @seen)))
          "and the frame the boundary is rendering")
      (is (= frame-a (:hframe @seen))
          "while the boundary is unambiguously rendering under its own frame")
      (is (= frame-a (:frame (:composed @seen)))
          "and the composed spelling carries the boundary's — which is the
           whole of what `h/frame` buys here, and is the spelling that was
           already correct before the refusal reached this case"))))

;; ---------------------------------------------------------------------------
;; W9 — an event-time read is not silently wrong
;; ---------------------------------------------------------------------------

(deftest a-frame-read-inside-an-event-handler-raises
  (frames!)
  (testing "the router binds CORE's scope for a handler, not the runtime's,
           so a handler that reaches for `h/frame` has no render extent and
           gets the loud error rather than a stale or guessed frame. This
           is the mis-layering the error text routes: the handler already
           has its frame, in its ctx"
    (let [caught (volatile! ::unset)]
      (rf/reg-event :hicasso.hframe/at-event-time
                    (fn [_ctx _e]
                      (vreset! caught (outcome intent/hframe))
                      {}))
      (rf/with-frame frame-a (rf/dispatch-sync [:hicasso.hframe/at-event-time]))
      (is (= :rf.error/hicasso-frame-outside-boundary (:rf.error/id @caught))
          (str "expected the loud error inside the handler; got " (pr-str @caught)))))

  (testing "and CORE's scope WAS live inside that same handler the whole
           time — so the row above pins that the two scopes are different
           things, not that the dispatch happened to be frameless"
    (let [seen (volatile! ::unset)]
      (rf/reg-event :hicasso.hframe/core-scope-at-event-time
                    (fn [_ctx _e]
                      (vreset! seen (frame/resolve-current-frame))
                      {}))
      (rf/with-frame frame-a (rf/dispatch-sync [:hicasso.hframe/core-scope-at-event-time]))
      (is (= frame-a @seen)
          "the router binds core's per-handler scope; the runtime's render
           binding is a different var with a different extent"))))
