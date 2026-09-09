(ns re-frame.hicasso.frame-doors-cljs-test
  "THE PURE FRAME DOORS INSIDE A BODY — `rf/current-frame-id` and
  zero-arity `rf/capture-frame`.

  spec/002-Frames.md §The refusal tier: a refusing render extent may
  still expose its declared frame to the pure identity and capture
  doors, while stateful ambient operations remain refused. Hicasso
  declares its boundary's frame as `:extent-frame`
  (`impl.intent/with-frame`), so the two core doors answer the rendering
  boundary's frame inside a body and inside a render callback that body
  supplied — with no substrate verb of its own. `h/hframe` is retired
  (naming-ledger row 18, rf2-t32wg).

  This file is the node half: everything the seam does that is not
  React's is answerable here, and the `-dom` sibling
  (`re-frame.hicasso.frame-doors-dom-cljs-test`) proves React drives
  *this* seam rather than re-proving what the seam does.

  ## Why these rows are not vacuous

  Two claims are ABSENCES — that an identity read is not a tracked read,
  and that core's READER is still withdrawn inside the body — and an
  absence proved by a green row that would have been green anyway is not
  proof. So each is paired with the positive reading that would have
  moved: `the-identity-read-registers-no-edge` takes the read set of a
  body that reads a subscription and shows adding the identity read
  leaves it identical, and `the-door-answers-where-the-reader-is-withdrawn`
  publishes the frame on the context slot, shows core's reader answering
  nil in the very extent where the door answers, and shows the same
  reader answering one line outside it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.todo-support :as rf.hicasso.todo-support]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; `:ambient-frame nil` for the reason the refusal suite gives: the
     ;; fixture's default leaves a dynamic-var stamp in scope, and tier 1
     ;; wins over everything — so a row claiming the door answered the
     ;; EXTENT's frame would pass for the wrong reason.
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(def ^:private frame-a ::doors-a)
(def ^:private frame-b ::doors-b)

(defn- frames! []
  (rf.hicasso.todo-support/make-frame! frame-a 3)
  (rf.hicasso.todo-support/make-frame! frame-b 3)
  (rf.hicasso.todo-support/reseed! frame-a 3)
  (rf.hicasso.todo-support/reseed! frame-b 3)
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
    (rf.hicasso.impl.collector/render-body frame-kw (fn [_] (body-fn seen) [:li]) {})
    @seen))

(defn- with-context-frame
  "Publish `frame-kw` on the SHARED React frame-context slot for `thunk`'s
  extent, then restore — the tier-2 publication React performs while
  rendering under a `frame-provider`, and what the installed adapter's
  `:adapter/current-frame` reader reads. Without it core's reader answers
  nil inside a body *anyway*, and the row that needs it would be green
  against a tree with no refusal in it at all."
  [frame-kw thunk]
  (let [^js ctx  rf.adapter.context/frame-context
        original (.-_currentValue ctx)]
    (set! (.-_currentValue ctx) frame-kw)
    (try (thunk)
         (finally (set! (.-_currentValue ctx) original)))))

;; ---------------------------------------------------------------------------
;; Both doors answer the rendering boundary's frame
;; ---------------------------------------------------------------------------

(deftest the-doors-answer-the-rendering-boundarys-frame
  (frames!)
  (testing "the whole seam, in two lines: inside a body the identity door
           is the id keyword of the boundary being rendered, and the
           capture door captures an api locked to it"
    (is (= frame-a (read-in-body frame-a (fn [seen] (vreset! seen (rf/current-frame-id))))))
    (is (= frame-a (read-in-body frame-a (fn [seen] (vreset! seen (:frame (rf/capture-frame))))))))

  (testing "and they FOLLOW the boundary — the same body run for a second
           frame answers the second frame. A read that was constant per
           process, or per first-mount, would pass the rows above and be
           useless to the case the doors exist for: one reusable view
           mounted under N frames"
    (is (= frame-b (read-in-body frame-b (fn [seen] (vreset! seen (rf/current-frame-id))))))
    (is (= frame-b (read-in-body frame-b (fn [seen] (vreset! seen (:frame (rf/capture-frame)))))))))

(deftest outside-a-render-extent-the-doors-report-cores-absence
  (frames!)
  (testing "no extent, no answer — never a guessed frame and never nil.
           The error is core's own `:rf.error/no-frame-context`: Hicasso
           mints no door and therefore no error of its own"
    (let [data (outcome rf/current-frame-id)]
      (is (= :rf.error/no-frame-context (:rf.error/id data))
          (str "expected core's absence error; got " (pr-str data)))))

  (testing "the same read inside a body is fine, so the row above is
           pinning the boundary of the extent rather than a broken call"
    (is (= frame-a (read-in-body frame-a (fn [seen] (vreset! seen (rf/current-frame-id))))))))

;; ---------------------------------------------------------------------------
;; Inside a render callback the doors answer the SUPPLYING boundary
;; ---------------------------------------------------------------------------

(deftest a-render-callback-answers-the-supplying-boundarys-frame
  (frames!)
  (testing "rf2-2rtt6.74's owner rule, reached through the core doors. A
           render callback is minted during the supplying boundary's body
           and INVOKED later by a foreign component — so the frame it must
           answer is the owner's. The wrapper re-establishes the owner's
           whole render context, refusal included, which is what makes the
           doors answer here at all: the runtime's own render slot is
           empty, because the foreign render runs outside the render pass"
    (let [callback (volatile! nil)
          seen     (volatile! ::unset)
          probe    (fn []
                     (vreset! seen {:id      (rf/current-frame-id)
                                    :capture (:frame (rf/capture-frame))
                                    :read    (outcome #(rf/subscribe [:hicasso.todo/done? 0]))}))]
      ;; Lowered at a NON-event position inside frame-a's body, which is
      ;; what gives it the render contract.
      (rf.hicasso.impl.collector/render-body frame-a
                             (fn [_]
                               (vreset! callback
                                        (rf.hicasso.impl.intent/lower-prop :render-row (rf.hicasso.impl.intent/callback probe)))
                               [:li])
                             {})
      (is (some? @callback) "precondition: the render position lowered a wrapper")

      (testing "invoked from outside any extent at all — the foreign
               component's own render"
        (is (not (rf.hicasso.impl.collector/rendering?))
            "the premise, asserted rather than described: the runtime's own
             render slot is EMPTY at the moment the callback runs")
        (@callback)
        (is (= frame-a (:id @seen)))
        (is (= frame-a (:capture @seen)))
        (is (= :rf.error/ambient-frame-refused (:rf.error/id (:read @seen)))
            (str "and the body's discipline travelled with the callback: an
                  ambient read inside it refuses rather than resolving
                  whatever context the foreign component happens to be in;
                  got " (pr-str (:read @seen))))
        (is (= frame-a (:extent-frame (:read @seen)))
            "naming the supplying boundary as the extent's frame"))

      (testing "and invoked while a DIFFERENT boundary's render context is
               ambient, it still answers the owner's. A foreign component
               has no frame of its own and frames are isolated contexts,
               so the supplying boundary is the only frame that can own
               this call"
        (vreset! seen ::unset)
        (rf.hicasso.impl.intent/with-frame frame-b (rf.hicasso.impl.collector/frame-dispatch frame-b) (fn [] (@callback)))
        (is (= frame-a (:id @seen))
            "the invoker's frame must not colonise the owner's callback")
        (is (= frame-a (:capture @seen))))

      (testing "while an enclosing `rf/with-frame` naming a different frame
               is the same ambiguity it is in a body, and refuses the same
               way — the mismatch check runs before the door is admitted"
        (vreset! seen ::unset)
        (let [data (rf/with-frame frame-b (outcome #(@callback)))]
          (is (= ::unset @seen) "the callback did not run to its probe")
          (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
              (str "expected the mismatch refusal; got " (pr-str data)))
          (is (= frame-b (:carried-frame data)))
          (is (= frame-a (:extent-frame data))))))))

;; ---------------------------------------------------------------------------
;; Not a tracked read
;; ---------------------------------------------------------------------------

(deftest the-identity-read-registers-no-edge
  (frames!)
  (testing "a body whose ONLY read is `rf/current-frame-id` records no
           sub-key at all. Reads become edges because they are sub-KEYS;
           the frame is not one — it is render-constant per boundary and
           resolved once by the shell"
    (rf.hicasso.impl.collector/render-body frame-a (fn [_] [:li (str (rf/current-frame-id))]) {})
    (is (= [] (vec (rf.hicasso.test.runtime/reads-of (rf.hicasso.impl.collector/last-reads))))))

  (testing "and adding it to a READING body changes the read set not at
           all — same keys, same order. The control is the point: the
           first row alone would be green for a body that read nothing
           for any reason"
    (rf.hicasso.impl.collector/render-body frame-a
                           (fn [_] [:li (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? 0]))])
                           {})
    (let [without (vec (rf.hicasso.test.runtime/reads-of (rf.hicasso.impl.collector/last-reads)))]
      (rf.hicasso.impl.collector/render-body frame-a
                             (fn [_]
                               [:li (str (rf/current-frame-id))
                                (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? 0]))])
                             {})
      (let [with (vec (rf.hicasso.test.runtime/reads-of (rf.hicasso.impl.collector/last-reads)))]
        (is (= 1 (count without)) "precondition: the control body read exactly one key")
        (is (= without with)
            (str "the identity read must contribute nothing to the collector: "
                 (pr-str without) " vs " (pr-str with)))))))

;; ---------------------------------------------------------------------------
;; The reader is still withdrawn; the admission lives in the requiring door
;; ---------------------------------------------------------------------------

(deftest the-door-answers-where-the-reader-is-withdrawn
  (frames!)
  (testing "the two answers taken in the SAME extent, one line apart:
           core's READER says 'no ambient frame' — rf2-2rtt6.122's refusal,
           doing exactly its job, so every reader-first path (subs/subscribe
           inlines one) is untouched — while the requiring door answers the
           boundary's declared frame. That gap is the seam: the ambient
           FIND is still deleted, and the door is answered from the
           extent's declaration rather than found through any adapter.

           THE CONTEXT SLOT IS PUBLISHED FOR THE WHOLE ROW, and it has to
           be. Without it core answers nil inside a body for the boring
           reason that it would answer nil anywhere, and the row would be
           green against a tree carrying no refusal at all"
    (let [seen (volatile! ::unset)]
      (with-context-frame frame-a
        (fn []
          (is (= frame-a (rf.frame/resolve-current-frame))
              "control: tier 2 is genuinely answering out here, so the nil
               below is the withdrawal and not an empty chain")
          (rf.hicasso.impl.collector/render-body frame-a
                                 (fn [_]
                                   (vreset! seen {:reader (rf.frame/resolve-current-frame)
                                                  :door   (rf/current-frame-id)})
                                   [:li])
                                 {})))
      (is (nil? (:reader @seen))
          "core's ambient reader is withdrawn for the extent")
      (is (= frame-a (:door @seen))
          "and the door answers anyway, from the declaration"))))

;; ---------------------------------------------------------------------------
;; The capture — the case the seam exists for
;; ---------------------------------------------------------------------------

(deftest the-capture-fires-into-its-own-frame-after-the-render
  (frames!)
  (testing "`(rf/capture-frame)` inside a body is the same spelling every
           other adapter writes, and this is what it buys: a closure built
           during a body and fired long after the render's dynamic extent
           has unwound still dispatches into the boundary's own frame. Two
           frames side by side, one body, so a capture that reached for an
           ambient frame at FIRE time — or captured a process-wide one —
           lands in the wrong place and this row says so"
    (let [carry (fn [frame-kw]
                  (let [held (volatile! nil)]
                    (rf.hicasso.impl.collector/render-body frame-kw
                                           (fn [_]
                                             (vreset! held (rf/capture-frame))
                                             [:li])
                                           {})
                    @held))
          a     (carry frame-a)
          b     (carry frame-b)]
      (is (= frame-a (:frame a)))
      (is (= frame-b (:frame b)))

      (testing "and each dispatches into ITS OWN frame, after the extent
               has gone"
        (is (nil? rf.hicasso.impl.intent/*frame*) "precondition: no render extent is live here")
        ((:dispatch-sync a) [:hicasso.todo/toggle 0])
        (is (true? @(rf/subscribe [:hicasso.todo/done? 0] {:frame frame-a})))
        (is (false? @(rf/subscribe [:hicasso.todo/done? 0] {:frame frame-b}))
            "frames are isolated contexts — the sibling must not have moved")))))

;; ---------------------------------------------------------------------------
;; Stateful ambient operations still refuse, one line from an admitted door
;; ---------------------------------------------------------------------------

(deftest stateful-ambient-operations-still-refuse-beside-the-admitted-doors
  (frames!)
  (testing "the rule is a distinction between operations, not a softening
           of the extent: in the same body the capture is admitted while an
           ambient subscribe and an ambient dispatch refuse by name, because
           those are the two that make an edge-less read or a render-phase
           mutation (HD-002 clause (a))"
    (let [seen (read-in-body frame-a
                             (fn [seen]
                               (vreset! seen {:capture  (:frame (rf/capture-frame))
                                              :read     (outcome #(rf/subscribe [:hicasso.todo/done? 0]))
                                              :dispatch (outcome #(rf/dispatch [:hicasso.todo/toggle 0]))})))]
      (is (= frame-a (:capture seen)))
      (is (= :rf.error/ambient-frame-refused (:rf.error/id (:read seen)))
          (str "an ambient read must still refuse; got " (pr-str (:read seen))))
      (is (= :subscribe (:operation (:read seen))))
      (is (= :rf.error/ambient-frame-refused (:rf.error/id (:dispatch seen)))
          (str "and an ambient dispatch; got " (pr-str (:dispatch seen))))
      (is (= :dispatch (:operation (:dispatch seen)))))))

;; ---------------------------------------------------------------------------
;; The configuration where an ambient carry used to answer the WRONG frame
;; ---------------------------------------------------------------------------

(deftest a-carried-outer-scope-refuses-rather-than-answering-the-wrong-frame
  (frames!)
  (testing "FOUND while grounding the SSR rows and pinned as a DEFECT
           (rf2-nqj22). rf2-2rtt6.122's refusal withdrew the ambient FIND
           and never the CARRYING, so a tier-1 stamp — an enclosing
           `rf/with-frame` — still answered inside a body. But the boundary
           renders a different frame, and everything else in the body
           targets THAT one: the collector's reads, the lowered intents,
           the presence tray. One body, two frames, chosen by which
           spelling the author reached for, and silent. Frames are ISOLATED
           contexts, so the extent declares its own frame to core and a
           mismatched stamp refuses instead of quietly winning.

           The admission of the pure doors changes NOTHING here, and this
           row is what says so: the mismatch check runs BEFORE the door is
           offered the extent's frame, so a body under a wrong `with-frame`
           is refused rather than silently repaired to its own frame"
    (let [seen (volatile! ::unset)]
      (rf/with-frame frame-b
        (rf.hicasso.impl.collector/render-body frame-a
                               (fn [_]
                                 (vreset! seen {:capture  (outcome #(rf/capture-frame))
                                                :id       (outcome #(rf/current-frame-id))
                                                :explicit (rf/capture-frame frame-a)})
                                 [:li])
                               {}))
      (doseq [door [:capture :id]]
        (let [data (get @seen door)]
          (is (= :rf.error/ambient-frame-refused (:rf.error/id data))
              (str door " must refuse rather than answer the ENCLOSING scope
                    or repair to the extent's; got " (pr-str data)))
          (is (= frame-b (:carried-frame data)) "naming the stamp that was carried")
          (is (= frame-a (:extent-frame data)) "and the frame the boundary is rendering")))
      (is (= frame-a (:frame (:explicit @seen)))
          "while the 1-arity never consults the resolver and carries as ever")))

  (testing "and a MATCHED enclosing scope is the one thing this must not
           break: the stamp names the frame the extent is rendering, so
           there is no second frame and both doors answer it"
    (let [seen (volatile! ::unset)]
      (rf/with-frame frame-a
        (rf.hicasso.impl.collector/render-body frame-a
                               (fn [_]
                                 (vreset! seen {:capture (:frame (rf/capture-frame))
                                                :id      (rf/current-frame-id)})
                                 [:li])
                               {}))
      (is (= frame-a (:capture @seen)))
      (is (= frame-a (:id @seen))))))
