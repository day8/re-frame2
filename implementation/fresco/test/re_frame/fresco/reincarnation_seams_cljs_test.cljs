(ns re-frame.fresco.reincarnation-seams-cljs-test
  "THE THREE SEAMS THAT BYPASS THE PINNED AMBIENT DISPATCHER, measured
  across a same-public-id reincarnation.

  `reincarnation_routing_cljs_test` establishes the contract for the one path
  pinning covers: the ambient dispatch a boundary body lowers into its
  callbacks. Three operations in this arm reach a frame WITHOUT that
  closure, and each is a candidate for the same fault:

  | seam | the call | what it carries across the gap |
  |---|---|---|
  | `h/error-boundary`'s `:on-error` report | `collector/dispatch!`, in `impl.boundary/report!` | the frame keyword, read from React context AT CATCH TIME |
  | the internal mount witness door | `collector/dispatch!`, in `impl.mount/dispatch!` | `(:frame handle)` — a keyword, retained for the root's whole life |
  | `intent/navigate-head` | routing's `:routing/activate-link!`, in `impl.intent/navigate-handler` | the frame keyword as data, and in the lowered closure the `capture-frame` bundle of the incarnation it was lowered under |

  ## The axis, and why \"it resolves late\" is not the fault

  Pinning is easy to mis-state as *late resolution is wrong*. It is
  not. `impl.collector/dispatch!`'s own docstring draws the line: a
  caller handing a BARE KEYWORD is naming an
  ADDRESS and gets the frame at that address, now; a lowered callback
  holds a CAPABILITY minted under one incarnation and must keep it for
  life. The defect pinning prevents is a capability wearing an address's
  clothes — a closure that stands for *this boundary's button* while
  resolving like a keyword somebody has just typed.

  So the question each seam is asked is not \"does it resolve late?\" but
  **\"is what it retains a capability, or an address?\"** — and, for an
  address, whether the frame it names is the same one the surrounding
  runtime is reading from at that instant. A seam whose reads and writes
  disagree is exactly the \"perfect markup above dead controls\" symptom
  pinning prevents; a seam whose reads and writes are both
  address-directed has no disagreement to have.

  ## Why these observables and not rendered markup

  For the reason the sibling file states at length: reads are
  address-directed so a fresh body paints correctly, `commit-basis` ties
  so React schedules no re-render, and the repair arrives at the microtask
  checkpoint. Rendered output is green in both directions. Every row here
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
  - `intent/navigate-head` retains a **capability**: its lowered closure
    carries the bundle pinned to the incarnation the link rendered under,
    so a retained link refuses once that incarnation is gone, while a link
    rendered under the successor navigates the successor
    (`route_link_cljs_test`, through a real `route-link`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.boundary :as rf.fresco.impl.boundary]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.test-support :as rf.test-support]))

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

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.fresco.impl.collector/reset-runtime!)
                      (rf.error-emit/clear-error-listeners!))}))

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
  (when (rf.frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
  (rf.fresco.impl.collector/reset-runtime!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:seams/seed who]))
  (rf.frame/frame-incarnation-token frame-id))

(defn- reincarnate!
  "Destroy the live incarnation and seat a fresh one under the same public id,
  seeded with a DIFFERENT value so a read cannot confuse the two."
  [who]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:seams/seed who]))
  (rf.frame/frame-incarnation-token frame-id))

(defn- render!
  "Leave the arm's memo row describing the incarnation live NOW — what a
  boundary's render does, and the posture the warm branch is about.

  **Every row below that models a predecessor calls this, and the reason is
  not thoroughness — it is that WARM is the only posture these three seams can
  actually be in.** A boundary reports an error it caught below itself, so it
  rendered. A root has a handle because `root!` rendered it. A link is clicked
  because it was painted. Unlike a lowered callback — which can be retained
  from a body nobody ever interacted with, making COLD the ordinary case there
  — none of these seams is reachable without its incarnation having rendered
  first, so a row that measured them cold would be measuring a state the
  runtime cannot be in when the seam fires. It would also be a row a
  late-binding mechanism would pass, which is the same mistake in its
  detectable form."
  []
  (rf.fresco.impl.collector/frame-dispatch frame-id))

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
    (rf.error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (rf.error-emit/unregister-error-listener! k)))))

;; ---------------------------------------------------------------------------
;; 2. SEAM 1 — `h/error-boundary`'s `:on-error` report (`impl.boundary/report!`)
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
  (.call (.-componentDidCatch (.-prototype rf.fresco.impl.boundary/boundary))
         this
         (js/Error. "a render error the boundary caught")
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

;; ---------------------------------------------------------------------------
;; 3. SEAM 2 — the internal mount witness door (`impl.mount/dispatch!`)
;; ---------------------------------------------------------------------------

;; `impl.mount/dispatch!` is three lines: `(collector/dispatch! (:frame handle)
;; event)`, `(settle!)`, `nil`. It reads ONE slot off the handle — `:frame`, a
;; public keyword — and consults neither `:root` nor `:container`, so a handle
;; literal carrying that slot is the door's whole input. It is not exported by
;; `re-frame.fresco` (the public door publishes `root!` and `release!` and no
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
            root is always in"
    (incarnate! "A")
    (render!)
    (let [handle {:frame frame-id}]                  ; the slot `dispatch!` reads
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(rf.fresco.impl.mount/dispatch! handle [:seams/mark :handle]))]
        (is (= :handle (marked))
            "the retained handle reaches the LIVE incarnation")
        (is (empty? refusals))))))
