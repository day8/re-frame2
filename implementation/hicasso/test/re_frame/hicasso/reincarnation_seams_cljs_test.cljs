(ns re-frame.hicasso.reincarnation-seams-cljs-test
  "THE THREE SEAMS THAT BYPASS THE PINNED AMBIENT DISPATCHER, measured
  across a same-public-id reincarnation (rf2-q9cf, follow-up to rf2-x874).

  `reincarnation_routing_cljs_test` establishes the contract for the one
  path rf2-x874 repaired: the ambient dispatch a boundary body lowers into
  its callbacks. Three operations in this arm reach a frame WITHOUT that
  closure, and each is a candidate for the same fault:

  | seam | the call | what it carries across the gap |
  |---|---|---|
  | `h/boundary`'s `:on-error` report | `collector/dispatch!`, in `impl.boundary/report!` | the frame keyword, read from React context AT CATCH TIME |
  | the internal mount witness door | `collector/dispatch!`, in `impl.mount/dispatch!` | `(:frame handle)` — a keyword, retained for the root's whole life |
  | `::h/navigate` | routing's `:routing/activate-link!`, in `impl.intent/navigate-handler` | the frame keyword, closed over at RENDER and resolved at CLICK |

  ## The axis, and why \"it resolves late\" is not the fault

  rf2-x874 is routinely mis-stated as *late resolution is wrong*. It is
  not. `impl.collector/dispatch!`'s own docstring draws the line, and
  section 1 measures it: a caller handing a BARE KEYWORD is naming an
  ADDRESS and gets the frame at that address, now; a lowered callback
  holds a CAPABILITY minted under one incarnation and must keep it for
  life. The defect was a capability wearing an address's clothes — a
  closure that stood for *this boundary's button* while resolving like a
  keyword somebody had just typed.

  So the question each seam is asked is not \"does it resolve late?\" but
  **\"is what it retains a capability, or an address?\"** — and, for an
  address, whether the frame it names is the same one the surrounding
  runtime is reading from at that instant. A seam whose reads and writes
  disagree is exactly the \"perfect markup above dead controls\" symptom
  rf2-x874 deleted; a seam whose reads and writes are both
  address-directed has no disagreement to have.

  ## Why these observables and not rendered markup

  For the reason the sibling file states at length: reads are
  address-directed so a fresh body paints correctly, `commit-basis` ties
  so React schedules no re-render, and the repair arrives a macrotask
  later. Rendered output is green in both directions. Every row here
  therefore asserts on the incarnation token, the memo row's identity, the
  successor's app-db, and the always-on `:rf.error/frame-destroyed`
  corpus record — the only observable that separates *refused* from
  *silently delivered to the wrong frame*.

  ## The verdict this file records

  All three seams are SAFE, and none of them is safe by accident:

  - the boundary report **retains nothing** — it re-reads its frame from
    React context on every call, measured by making one instance report
    into two successive incarnations (section 2);
  - the mount witness door retains an **address**, and the root it names
    reads that same address, measured side by side (section 3);
  - `::h/navigate` retains an address too, and routing rules it so
    deliberately (`activate-link!`: *the dispatch always lands on the
    CURRENTLY-committed frame (retarget-safe)*). Section 4 measures what
    pinning it would cost, and the answer is that the link goes dead —
    rf2-x874's warm branch, reintroduced.

  Sections 2 and 4 each end with the counterfactual measured rather than
  argued, because \"it did not reproduce\" and \"it cannot happen\" are
  different claims and only the second closes the audit."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.boundary :as boundary]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing.link :as routing-link]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::seams)

;; Registered above `use-fixtures`, as the sibling suite's are and for the same
;; reason: the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is evaluated. `:seams/mark` writes a distinctive leaf so
;; a write that reached the WRONG incarnation is unmistakable in that
;; incarnation's app-db rather than merely absent from the right one, and both
;; handlers resolve in EVERY incarnation (the registry is global) — which is
;; what makes an absent mark a real refusal rather than an unresolved miss.
(rf/reg-event :seams/seed (fn [_ [_ who]] {:db {:who who}}))
(rf/reg-event :seams/mark (fn [{:keys [db]} [_ tag]] {:db (assoc db :marked tag)}))
(rf/reg-sub   :seams/who  (fn [db _] (:who db)))

;; `:async? true` — `cljs.test` hard-errors on a fn-form fixture in a namespace
;; containing an `(async done …)` test, and section 4's end-to-end row is only
;; observable across the router's queue drain.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (collector/reset-runtime!)
                      (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Harness — deliberately the sibling suite's, so the two files are comparable
;; line for line
;; ---------------------------------------------------------------------------

(defn- incarnate!
  "Seat a FIRST incarnation under the fixed public id and answer its exact
  token. Each `testing` block is an independent scenario but the fixture is
  per-`deftest`, so preconditions are established here rather than inherited:
  any live predecessor is destroyed and the arm's frame memo is emptied, which
  is the state the arm is genuinely in when a page first mounts."
  [who]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (when (frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
  (collector/reset-runtime!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:seams/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- reincarnate!
  "Destroy the live incarnation and seat a fresh one under the same public id,
  seeded with a DIFFERENT value so a read cannot confuse the two."
  [who]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:seams/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- render!
  "Leave the arm's memo row describing the incarnation live NOW — what a
  boundary's render does, and the posture rf2-x874's warm branch is about.

  **Every row below that models a predecessor calls this, and the reason is
  not thoroughness — it is that WARM is the only posture these three seams can
  actually be in.** A boundary reports an error it caught below itself, so it
  rendered. A root has a handle because `root!` rendered it. A link is clicked
  because it was painted. Unlike a lowered callback — which can be retained
  from a body nobody ever interacted with, making COLD the ordinary case there
  — none of these seams is reachable without its incarnation having rendered
  first, so a row that measured them cold would be measuring a state the
  runtime cannot be in when the seam fires. It would also be a row the
  pre-rf2-x874 mechanism passes, which is the same mistake in its detectable
  form."
  []
  (collector/frame-dispatch frame-id))

(defn- marked [] (:marked (rf/app-db-value frame-id)))

(defn- with-refusals
  "Run `thunk` and collect the always-on corpus `:rf.error/frame-destroyed`
  records it fans. Answers `{:result … :refusals [<record>…]}`.

  Axis 1 (`error-emit`) rather than the dev trace deliberately: it survives
  `-Dre-frame.debug=false`, so these assertions hold under the production
  posture too. Gensym'd listener key, unregistered on the way out."
  [thunk]
  (let [seen (atom [])
        k    (keyword "rf2-q9cf" (name (gensym "refusal")))]
    (error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (error-emit/unregister-error-listener! k)))))

;; ---------------------------------------------------------------------------
;; 1. THE AXIS — the two doors in `impl.collector`, twenty lines apart, and the
;;    property that tells them apart
;; ---------------------------------------------------------------------------

;; Everything below is a placement on this axis, so it is measured first. The
;; two doors are `frame-dispatch` (the memoised closure a body lowers, pinned to
;; one incarnation at mint) and `dispatch!` (the same closure ACQUIRED AND
;; APPLIED in one act, so the acquisition happens at the caller's `now`). The
;; second is not a weaker version of the first; it is the documented
;; address-directed door, and the whole audit turns on the difference being
;; deliberate rather than residual.

(deftest the-arm-has-two-doors-and-only-one-of-them-carries-a-capability
  (testing "a RETAINED closure is a capability — minted under A, it is still
            A's after A dies, and core refuses it"
    (incarnate! "A")
    (let [on-click (collector/frame-dispatch frame-id)]
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(on-click [:seams/mark :capability]))]
        (is (nil? (marked))
            "the successor's app-db is untouched by a predecessor-era closure")
        (is (= 1 (count refusals)) "and the drop is loud")
        (is (= frame-id (:frame (first refusals))))
        (is (= :seams/mark (:event-id (first refusals)))))))

  (testing "a BARE KEYWORD is an address — `dispatch!` acquires the row at the
            moment of the call, so it names the frame live at that moment. This
            is not the defect wearing a different hat: nothing was minted
            earlier, so there is no earlier incarnation for the write to belong
            to"
    (incarnate! "A")
    (render!)                                         ; leave a WARM row for A
    (reincarnate! "B")
    (let [{:keys [refusals]} (with-refusals
                               #(collector/dispatch! frame-id [:seams/mark :address]))]
      (is (= :address (marked))
          "the write lands in the LIVE incarnation, even from a warm row that
           described the predecessor a line ago — the row is replaced by the
           lookup, which is rf2-x874's lazy replacement doing its job")
      (is (empty? refusals) "and nothing is refused, because nothing was pinned")))

  (testing "the two doors therefore answer differently about the same id in the
            same state, which is what makes `capability or address?` a real
            question to ask of a seam rather than a form of words"
    (incarnate! "A")
    (let [retained (collector/frame-dispatch frame-id)]
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(retained [:seams/mark :retained]))]
        (is (nil? (marked)))
        (is (= 1 (count refusals))))
      (collector/dispatch! frame-id [:seams/mark :fresh])
      (is (= :fresh (marked))))))

;; ---------------------------------------------------------------------------
;; 2. SEAM 1 — `h/boundary`'s `:on-error` report (`impl.boundary/report!`)
;; ---------------------------------------------------------------------------

;; `report!` is reached from `componentDidCatch` and from nowhere else
;; (boundary.cljs states and measures that separately), so the seam's whole
;; retention question is: what does the class hold between being mounted and
;; being asked to report?
;;
;; The rows below invoke the REAL `componentDidCatch` off the shipped
;; prototype, with a `this` carrying the two slots it reads — `props.rfProps`
;; and `context`. Nothing is paraphrased: `report!`, `props-of`, `frame-of` and
;; the `collector/dispatch!` call are the shipped ones. What the synthetic
;; `this` buys is CONTROL of the thing under test — a real React mount would
;; fix the context value and the timing, and the fault hypothesis is precisely
;; about a report arriving when the context names a different incarnation than
;; it did at mount.

(defn- boundary-instance
  "A `this` carrying exactly the two slots `report!` reads: the ClojureScript
  props the codec's boundary hand-off stashes under `rfProps`, and the
  `contextType` value React assigns from the closest frame provider. A frame
  keyword IS the raw context value — `adapter-context/context-value->current-frame`
  classifies it as the enclosing provider's frame."
  [on-error]
  #js {:props   #js {"rfProps" {:on-error on-error}}
       :context frame-id})

(defn- catch!
  "Hand `this` a caught error the way React's commit phase does."
  [this]
  (.call (.-componentDidCatch (.-prototype boundary/boundary))
         this
         (js/Error. "rf2-q9cf")
         #js {"componentStack" ""}))

(deftest the-boundary-report-retains-no-frame-it-re-reads-one-every-time
  ;; The measurement that answers the seam. ONE instance, unchanged across a
  ;; reincarnation — same object, same props, same context value, because the
  ;; public id does not move when the incarnation does. If the class held
  ;; anything from its first report, the second would go where the first went.
  (let [this (boundary-instance [:seams/mark :report])]
    (incarnate! "A")
    (render!)
    (let [{:keys [refusals]} (with-refusals #(catch! this))]
      (is (= :report (marked))
          "the report reaches the frame its context names")
      (is (empty? refusals) "and nothing is refused"))

    (reincarnate! "B")
    (is (nil? (marked)) "sanity: the successor starts unmarked")

    (let [{:keys [refusals]} (with-refusals #(catch! this))]
      (is (= :report (marked))
          "the SAME instance reports into the SUCCESSOR — so the class carries
           no frame, no bundle and no closure from its first report; it reads
           `this.context` when React calls it and never before")
      (is (empty? refusals)
          "and there is no refusal to have, because nothing predecessor-era
           crossed the seam"))))

(deftest the-boundary-report-is-minted-at-catch-time-not-at-mount
  ;; The complement of the row above, from the other side: a fn `:on-error`
  ;; takes no frame at all, so the two shapes the seam supports differ only in
  ;; whether they consult a frame — never in WHEN.
  (testing "a function `:on-error` never touches a frame, so a reincarnation
            cannot reach it"
    (incarnate! "A")
    (render!)
    (let [calls (atom 0)
          this  (boundary-instance (fn [_e] (swap! calls inc)))]
      (catch! this)
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(catch! this))]
        (is (= 2 @calls) "called once per catch, in both incarnations")
        (is (empty? refusals)))))

  (testing "and an absent `:on-error` dispatches nothing at all, so the seam
            is not even reached — stated because a green row above must not be
            green for want of a handler"
    (incarnate! "A")
    (let [this (boundary-instance nil)]
      (catch! this)
      (is (nil? (marked))
          "no :on-error, no write — the mark in the rows above is this seam's
           doing and not the harness's"))))

(deftest NEGATIVE-CONTROL-pinning-the-boundary-report-would-silence-it
  ;; The counterfactual for seam 1, measured rather than argued. The change the
  ;; audit declined is "capture the boundary's frame when it mounts and report
  ;; through that bundle". This is that alternative, built out of the documented
  ;; seam — `rf/capture-frame` taken while the mounting incarnation is live, the
  ;; way `impl.frames/mint-row` takes it — and run against the same catch.
  ;;
  ;; It reproduces nothing useful and destroys something real: the application's
  ;; `:on-error` never runs for an error that just happened on screen.
  ;; boundary.cljs: "a boundary that quietly does not catch is worse than none".
  (testing "a report pinned at MOUNT is refused after a reincarnation — the
            failure is not a wrong write, it is no report at all"
    (incarnate! "A")
    (let [pinned (rf/capture-frame frame-id)]        ; what a mount-time pin holds
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals
                                 #((:dispatch-sync pinned) [:seams/mark :pinned-report]))]
        (is (nil? (marked))
            "the pinned report does not reach the frame the boundary is
             currently mounted under")
        (is (= 1 (count refusals))
            "it is refused — which for an ERROR REPORT means the failure is
             swallowed by the very component whose job is to surface it")
        (is (= :seams/mark (:event-id (first refusals)))))))

  (testing "while the shipped seam, on the identical transition and from the
            same warm row, delivers — so the row above is a cost this design
            pays nothing for"
    (incarnate! "A")
    (render!)
    (let [this (boundary-instance [:seams/mark :shipped])]
      (reincarnate! "B")
      (catch! this)
      (is (= :shipped (marked))
          "address-directed, and therefore still able to report"))))

;; ---------------------------------------------------------------------------
;; 3. SEAM 2 — the internal mount witness door (`impl.mount/dispatch!`)
;; ---------------------------------------------------------------------------

;; `impl.mount/dispatch!` is three lines: `(collector/dispatch! (:frame handle)
;; event)`, `(settle!)`, `nil`. It reads ONE slot off the handle — `:frame`, a
;; public keyword — and consults neither `:root` nor `:container`, so a handle
;; literal carrying that slot is the door's whole input. It is not exported by
;; `re-frame.hicasso` (the public door publishes `root!` and `release!` and no
;; dispatch), which is what makes it the arm's stand-in for "an application
;; calls the address-directed door with a frame id".
;;
;; The handle IS retained — `root!` answers it once and the caller holds it for
;; the root's whole life — so this is the one seam where a keyword genuinely
;; survives a reincarnation inside a value the runtime handed out. The rows
;; measure that it therefore reaches the successor, and then measure the thing
;; that makes reaching the successor CORRECT: the root that handle names reads
;; the successor too.

(deftest the-mount-witness-door-retains-an-address-and-the-root-reads-that-address
  (testing "a handle minted under A dispatches into B after the reincarnation,
            from the WARM row A's own render left behind — the posture a real
            root is always in, and the one the pre-rf2-x874 mechanism got wrong
            in the other direction"
    (incarnate! "A")
    (render!)
    (let [handle {:frame frame-id}]                  ; the slot `dispatch!` reads
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(mount/dispatch! handle [:seams/mark :handle]))]
        (is (= :handle (marked))
            "the retained handle reaches the LIVE incarnation")
        (is (empty? refusals)))))

  (testing "and the ROOT that handle names reads the live incarnation too —
            which is what makes the line above correct rather than a second
            instance of rf2-x874. A body re-run under the same public id after
            the reincarnation already answers the successor's value, because
            the read path is address-directed on the public id. Pin this door
            and the root would WRITE a frame it cannot READ: perfect markup
            above dead controls, which is the exact symptom rf2-x874 deleted"
    (incarnate! "A")
    (is (= "A" (collector/render-body frame-id (fn [_] (collector/sub [:seams/who])) {})))
    (reincarnate! "B")
    (is (= "B" (collector/render-body frame-id (fn [_] (collector/sub [:seams/who])) {}))
        "the root's reads moved to the successor with nothing asked of them"))

  (testing "the handle carries a KEYWORD and not a capability — there is no
            bundle, no closure and no token in it for an incarnation to be
            recorded in"
    (incarnate! "A")
    (let [handle {:frame frame-id :root ::opaque :container ::opaque}]
      (is (keyword? (:frame handle)))
      (is (= frame-id (:frame handle))
          "the same value before and after any reincarnation, because a public
           id names an ADDRESS and addresses do not move"))))

;; ---------------------------------------------------------------------------
;; 4. SEAM 3 — `::h/navigate` (`impl.intent/navigate-handler`)
;; ---------------------------------------------------------------------------

;; The one seam where a frame keyword is closed over at RENDER and resolved at
;; CLICK — structurally the shape rf2-x874 repaired. It is nonetheless the
;; deliberate semantics, and not Hicasso's to change: `route-link` "restates NO
;; routing law", and routing's own `activate-link!` names the behaviour in
;; terms — *`:frame render-frame` is an explicit dispatch opt … so the dispatch
;; always lands on the CURRENTLY-committed frame (retarget-safe)*. Core agrees
;; from the other end: `router/dispatch!` carries
;; `:rf.frame/expected-incarnation` only for a `capture-frame` op and states it
;; is "nil for every ordinary / address-directed dispatch".
;;
;; The hook is SET EXPLICITLY in every row below and restored afterwards. It
;; must be: `re-frame.routing` publishes `:routing/activate-link!` at ns-load,
;; so in the consolidated `:node-test` build the real one is already installed
;; while in the focused `:node-test-hicasso` build it is not. A row that let the
;; ambient binding decide would measure a different thing in each build.

(defn- with-activate-link
  "Run `thunk` with `f` published at `:routing/activate-link!`, restoring
  whatever was there before — nil included."
  [f thunk]
  (let [previous (late-bind/get-fn :routing/activate-link!)]
    (late-bind/set-fn! :routing/activate-link! f)
    (try (thunk)
         (finally (late-bind/set-fn! :routing/activate-link! previous)))))

(defn- lower-navigate
  "Lower one `[::h/navigate {…}]` under the ambient binding a boundary body
  runs — `impl.collector/run-once`'s, spelled out — and answer the closure the
  browser would call. The map is exactly what `route-link` mints."
  [tag]
  (intent/with-frame frame-id (collector/frame-dispatch frame-id)
    (fn []
      (intent/lower-prop :on-click
                         [intent/navigate-head {:frame   frame-id
                                                :payload [:seams/mark tag]
                                                :native? false
                                                :veto    nil}]))))

(defn- click-event []
  #js {:button           0
       :metaKey          false
       :ctrlKey          false
       :shiftKey         false
       :altKey           false
       :defaultPrevented false
       :preventDefault   (fn [] js/undefined)})

(deftest navigate-hands-routing-an-address-and-nothing-incarnation-shaped
  ;; What crosses the Hicasso/routing seam is the whole of Hicasso's exposure
  ;; here, so it is measured directly rather than inferred from the outcome.
  (let [seen (atom [])
        rec  (fn [_e _veto frame payload _native?] (swap! seen conj [frame payload]) nil)]
    (with-activate-link rec
      (fn []
        (incarnate! "A")
        (let [under-a (lower-navigate :nav)]
          (reincarnate! "B")
          ((lower-navigate :nav) (click-event))       ; lowered under B
          (under-a (click-event))                     ; lowered under A, fired under B
          (let [[[frame-b _] [frame-a _]] @seen]
            (is (= 2 (count @seen)) "both closures reached routing")
            (is (keyword? frame-a)
                "what a RETAINED navigate closure hands routing is a keyword —
                 an address; there is no bundle, no token and no closure in it
                 for an incarnation to be recorded in")
            (is (= frame-id frame-a))
            (is (= frame-b frame-a)
                "and it is the SAME value the closure lowered under the
                 successor hands over, so the two are indistinguishable at the
                 seam: `::h/navigate` carries nothing that could tell them
                 apart even if routing wanted to")))))))

(deftest navigate-through-real-routing-reaches-the-live-incarnation
  ;; The consequence, through routing's REAL `activate-link!` rather than a
  ;; paraphrase of it — the caller-veto / modifier / native-anchor law and the
  ;; `router/dispatch!` opts are the shipped ones.
  (async done
    (with-activate-link routing-link/activate-link!
      (fn []
        (incarnate! "A")
        (let [on-click (lower-navigate :navigated)]
          (reincarnate! "B")
          (is (nil? (marked))
              "sanity: the successor starts unmarked, so the assertion below
               has something to measure")
          (on-click (click-event))
          (letfn [(poll [n]
                    (cond
                      (some? (marked))
                      (do (is (= :navigated (marked))
                              "a link rendered under the PREDECESSOR navigates
                               the LIVE app — routing's ruled retarget-safety,
                               and the reason this seam must not be pinned")
                          (done))

                      (zero? n)
                      (do (is false "the navigate dispatch never drained")
                          (done))

                      :else (js/setTimeout #(poll (dec n)) 0)))]
            (poll 50)))))))

(deftest NEGATIVE-CONTROL-pinning-navigate-would-leave-a-dead-link
  ;; The counterfactual for seam 3, measured. The change the audit declined is
  ;; "capture the frame at render and navigate through that bundle" — i.e. treat
  ;; the navigate map's `:frame` as a capability. Built out of the documented
  ;; seam (`rf/capture-frame` at render, the way `impl.frames/mint-row` takes
  ;; it) and fired at the same click.
  ;;
  ;; It is rf2-x874's WARM branch, reintroduced on purpose: an anchor the
  ;; successor has just painted, whose click does nothing. The audit's stopping
  ;; rule asks whether a seam CAN revive a dead incarnation; here the answer is
  ;; that pinning it would instead kill a live one.
  (testing "a navigate pinned at render is refused after a reincarnation — the
            anchor is on the screen and the click writes nothing"
    (incarnate! "A")
    (let [pinned (rf/capture-frame frame-id)]
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals
                                 #((:dispatch-sync pinned) [:seams/mark :pinned-nav]))]
        (is (nil? (marked)) "the live app is not navigated")
        (is (= 1 (count refusals)) "the click is refused")
        (is (= :seams/mark (:event-id (first refusals)))))))

  (testing "and the shipped seam hands routing an address on the identical
            transition, so the row above is a cost with no purchase"
    (let [seen (atom nil)]
      (with-activate-link (fn [_e _veto frame _payload _native?] (reset! seen frame) nil)
        (fn []
          (incarnate! "A")
          (let [on-click (lower-navigate :nav)]
            (reincarnate! "B")
            (on-click (click-event))
            (is (= frame-id @seen)
                "the address, unchanged — and routing decides from there")))))))
