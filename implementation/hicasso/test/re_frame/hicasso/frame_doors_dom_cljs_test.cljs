(ns re-frame.hicasso.frame-doors-dom-cljs-test
  "THE PURE FRAME DOORS AGAINST A REAL REACT ROOT.

  The node sibling (`re-frame.hicasso.frame-doors-cljs-test`) settles what
  the seam does. The claims left over are each about REACT rather than
  about the doors, which is why they are asserted against a live
  `createRoot` rather than reasoned about:

  1. **They answer under the shell, and follow the frame** (W1). The
     shell resolves its frame through `useContext` and declares it to
     core; `rf/current-frame-id` answers from that declaration, and a
     shell that answered differently would make the door a property of
     the shell.
  2. **The documented trap, made green, plus the isolation law** (W2).
     ONE reusable view, mounted under TWO frames, each instance building
     `(rf/capture-frame)` and firing it from a `setTimeout` after its
     render has unwound. This is the case the seam exists for and the
     case nothing else can spell: `with-frame` and `{:frame …}` both
     presuppose knowing the id, which a reusable view does not.
  3. **The hook ledger does not move**. Counted at React's own dispatcher
     by the same probe the ≤2-hook budget uses. The ledger is a hard
     fence; a read that spent a hook would be a finding rather than a
     price.
  4. **StrictMode's double-invoke is not additive** (W6) — the same value
     on both runs, and no second edge, entry or registration.
  5. **The `[:>]` value-first door dispatches through a plain closure
     over the capture** (W7). An ordinary function crosses by identity
     and carries no frame of its own, so the frame has to be put into it
     by hand, in the body, where `(rf/capture-frame)` knows it.
  6. **A render callback invoked during a FOREIGN render answers the
     supplying boundary** — and its discipline travels with it. The
     foreign component renders under the boundary's own React context, so
     an ambient read there would RESOLVE through tier 2 if the callback
     did not re-establish the refusal; the refused read is what makes the
     answered frame evidence of the wrapper rather than of the context.

  ## The mutation witness for W7

  Lock both captures to one frame — `(rf/capture-frame frame-a)` in
  place of `(rf/capture-frame)` in `escape-picker` — and
  `the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture`
  goes red on the second click, which toggles the first frame back off
  and leaves the pair reading `false`/`false`. That is why the row
  clicks BOTH crossings: one click alone is green under a capture that
  resolved one frame for both.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.hook-probe :as rf.hicasso.hook-probe]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.todo-support :as rf.hicasso.todo-support]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The hook-ledger fixture's reason, and it bites harder here: the
     ;; default leaves a dynamic-var frame stamp in scope, which tier 1
     ;; would answer — so an isolation miss could read as a rendering
     ;; difference rather than as the failure it is.
     :ambient-frame nil
     ;; The MAP shape, because W2 is `async`: the whole point of that row
     ;; is a closure firing from a real macrotask, long after the render's
     ;; dynamic extent has unwound, and `cljs.test` refuses an async test
     ;; under a fn-form fixture outright — "Async tests require fixtures to
     ;; be specified as maps. Testing aborted." — which aborts the WHOLE
     ;; browser run at this namespace, not just this file.
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(def ^:private frame-a ::doors-dom-a)
(def ^:private frame-b ::doors-dom-b)

(def ^:private todos 4)

(defn- skip! [why] (is true (str "this claim needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the hook count is "
                 "UNWITNESSED on this build. A gate nobody has watched fire is "
                 "not evidence — fix "
                 (pr-str 're-frame.hicasso.hook-probe)
                 " rather than reading this as a pass.")))

(defn- outcome
  "The thrown ex-data, or `::no-throw` with the value."
  [thunk]
  (try [::no-throw (thunk)]
       (catch :default e (ex-data e))))

(defn- frames! []
  (rf.hicasso.checkpoint-support/leave-act-environment!)
  (rf.hicasso.todo-support/make-frame! frame-a todos)
  (rf.hicasso.todo-support/make-frame! frame-b todos)
  (rf.hicasso.todo-support/reseed! frame-a todos)
  (rf.hicasso.todo-support/reseed! frame-b todos)
  nil)

;; ---------------------------------------------------------------------------
;; The body under test
;; ---------------------------------------------------------------------------

(defn- printing-body
  "Prints the frame `rf/current-frame-id` answered, and reads one
  subscription beside it so the boundary is an ordinary reading boundary
  rather than a degenerate one."
  [{:keys [id]}]
  [:span.row {:data-frame (str (rf/current-frame-id))
              :data-done  (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? id]))}])

(rf.hicasso/defview context-row
  "The shell: the frame arrives through `useContext`."
  [props]
  (printing-body props))

(defn- frame-attr [handle]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute "data-frame")))

;; ---------------------------------------------------------------------------
;; W1 — the same answer under the shell, following the frame
;; ---------------------------------------------------------------------------

(deftest the-identity-door-answers-under-the-shell
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (testing "the shell"
        (let [handle (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-a [context-row {:id 0}])]
          (try (is (= (str frame-a) (frame-attr handle)))
               (finally (rf.hicasso.impl.mount/release! handle)))))

      (testing "and it FOLLOWS the frame — mounted under a second frame it
               answers the second. A constant would have passed the row
               above"
        (let [handle (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b [context-row {:id 0}])]
          (try (is (= (str frame-b) (frame-attr handle)))
               (finally (rf.hicasso.impl.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; The hook ledger does not move
;; ---------------------------------------------------------------------------

(deftest the-identity-door-spends-no-hook
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.hicasso.hook-probe/install!)
      (unwitnessed!)
      (do
        (frames!)
        (testing "the ≤2-hook shell is a hard fence, so this is counted at
                 React's own dispatcher rather than declared. A boundary
                 reading `rf/current-frame-id` costs exactly what the
                 ledger declares — the frame is render-constant per
                 boundary and already declared to core, so there is
                 nothing for a hook to hold"
          (let [handle (volatile! nil)
                names  (rf.hicasso.hook-probe/record!
                         (fn [] (vreset! handle
                                         (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!)
                                                      frame-a [context-row {:id 0}]))))]
            (rf.hicasso.impl.mount/release! @handle)
            (is (= ["useContext" "useSyncExternalStore"] names)
                (str "hooks React was asked for: " (pr-str names)))
            (is (= (count rf.hicasso.test.runtime/shell-hook-ledger) (count names))
                "and the declared ledger is still the measured one")))))))

;; ---------------------------------------------------------------------------
;; W2 — the documented trap made green, and the isolation law
;; ---------------------------------------------------------------------------

(def ^:private !captures
  "Where each mounted instance parks the capture it built. Keyed by the
  frame it captured — so an instance that captured the WRONG frame
  overwrites its sibling's slot and the row goes red on the count before
  it ever gets to the dispatch."
  (atom {}))

(rf.hicasso/defview reusable
  "ONE view, mounted under N frames — the case the seam exists for. It
  does not know its own frame id, which is exactly why neither
  `rf/with-frame` nor a `{:frame …}` opt can serve it: both presuppose
  the id. `(rf/capture-frame)` — the same spelling every other adapter
  writes — captures the boundary's declared frame and carries it out of
  the render."
  [{:keys [id]}]
  (let [{:keys [frame] :as api} (rf/capture-frame)]
    (swap! !captures assoc frame api)
    [:span.row {:data-frame (str frame)
                :data-done  (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? id]))}]))

(deftest a-capture-built-in-a-body-fires-into-its-own-frame-after-the-render
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (frames!)
      (reset! !captures {})
      (let [a (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-a [reusable {:id 0}])
            b (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b [reusable {:id 0}])]
        (is (= #{frame-a frame-b} (set (keys @!captures)))
            (str "two mounts of ONE view must have captured two different frames; got "
                 (pr-str (keys @!captures))))
        (is (= (str frame-a) (frame-attr a)))
        (is (= (str frame-b) (frame-attr b)))
        ;; A real macrotask, which is the whole point: the render's dynamic
        ;; extent is long gone by the time these fire, and an ambient read
        ;; taken HERE would find nothing at all.
        (js/setTimeout
          (fn []
            (try
              (is (nil? rf.hicasso.impl.intent/*frame*)
                  "precondition: no render extent is live inside the timeout")
              ((:dispatch-sync (get @!captures frame-a)) [:hicasso.todo/toggle 0])
              (rf.hicasso.impl.mount/settle!)
              (is (= "true" (.getAttribute (.querySelector (:container a) ".row") "data-done"))
                  "the closure dispatched into the frame its own boundary rendered under")
              (is (= "false" (.getAttribute (.querySelector (:container b) ".row") "data-done"))
                  "and the sibling frame did not move — frames are isolated
                   contexts, and a capture that had resolved a process-wide
                   or a last-rendered frame would have moved both")
              (finally
                (rf.hicasso.impl.mount/release! a)
                (rf.hicasso.impl.mount/release! b)
                (done))))
          0)))))

;; ---------------------------------------------------------------------------
;; W6 — StrictMode's double-invoke
;; ---------------------------------------------------------------------------

(def ^:private !runs (atom 0))
(def ^:private !seen (atom []))

(rf.hicasso/defview strict-reader
  "Records the frame `rf/current-frame-id` answered on EVERY body run, so
  the double-invoke is a measured premise rather than an assumed one."
  [{:keys [id]}]
  (swap! !runs inc)
  (swap! !seen conj (rf/current-frame-id))
  [:span.row {:data-done (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? id]))}])

(defn- strict-root!
  "`mount/root!`, wrapped in `React.StrictMode`. Written here rather than
  in the shared mount door because StrictMode is this row's variable, and
  every other suite must keep measuring the ordinary tree."
  [container frame-kw hiccup]
  (let [root (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root (react/createElement
                             react/StrictMode nil
                             (rf.hicasso.impl.mount/provider frame-kw (rf.hicasso.impl.codec/as-element hiccup))))))
    {:root root :frame frame-kw :container container}))

(deftest strictmodes-double-invoke-reads-the-same-frame-and-adds-nothing
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (reset! !runs 0)
      (reset! !seen [])
      (let [handle (strict-root! (rf.hicasso.impl.mount/fresh-container!) frame-a [strict-reader {:id 0}])]
        (try
          (testing "the premise: React really did invoke the body twice"
            (is (= 2 @!runs)
                "if this reads 1 the build is not double-invoking and the
                 assertions below are a green gate over nothing — fix the
                 build, do not relax this"))
          (testing "both invocations read the same frame — the ambient is
                   bound per body RUN, so a second run re-establishes it
                   rather than inheriting a stale or half-unwound one"
            (is (= [frame-a frame-a] @!seen)))
          (testing "and the read adds nothing on the second pass: one entry,
                   one boundary, one edge per read. The identity door
                   appends no sub-key, so a double-invoke cannot double
                   anything it contributes — because it contributes nothing"
            (let [{:keys [entries boundaries edges]} (rf.hicasso.test.runtime/stats)]
              (is (= 1 entries))
              (is (= 1 boundaries))
              (is (= 1 edges) "the one real read, counted once")))
          (finally (rf.hicasso.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; W7 — the `[:>]` value-first door, and the plain closure over the capture
;; ---------------------------------------------------------------------------
;;
;; W2 proves the capture survives a macrotask. This row proves it is the
;; thing that serves the crossing HD-011 made the escape for. `[:>]` is
;; `defhost` with the declaration erased, and a declaration is what a
;; callback contract lives on — so `raw-crossing`'s roster is empty by
;; construction and every slot at this crossing is UNCLAIMED. What remains
;; is a plain function, which carries nothing. The frame has to be put
;; into it by hand, in the body, where it is knowable.

(def ^:private !built
  "The closure each mounted instance BUILT, keyed by the frame its body
  captured. The other half of `!handed`: two atoms rather than one
  because the claim that the door is *value-first* is a claim that these
  are the same object."
  (atom {}))

(def ^:private !handed
  "The `onPick` prop each instance of the foreign component was HANDED,
  keyed by the label it was given — which is the frame its writing
  boundary captured. An instance that never crossed leaves this empty, so
  neither the identity row nor the dispatch rows below can pass
  vacuously."
  (atom {}))

(defn- foreign-picker
  "A stand-in for the component an author reaches for `[:>]` to mount: it
  is handed a callback prop, KEEPS it, and calls it from a DOM handler of
  its own — so the invoker is foreign code running long after our render
  returned, rather than the test reaching into a stash.

  Written with `react/createElement` on purpose. A library component is
  not ours and does not lower through this codec, and a fixture that went
  through the codec would witness our own walk a second time instead of
  the crossing."
  [^js props]
  (let [on-pick (.-onPick props)]
    (swap! !handed assoc (.-label props) on-pick)
    (react/createElement "button"
      #js {:className "pick" :onClick (fn [_e] (on-pick))}
      "pick")))

(rf.hicasso/defview escape-picker
  "`reusable`'s body at the foreign edge: ONE view, mounted under N
  frames, that does not know its own frame id — and now has to hand a
  dispatching closure to a caller it does not control.

  `(rf/capture-frame)` is the whole answer: it captures the boundary's
  declared frame during the body, and the api it returns is still good
  when the foreign component calls back, because every op on it is
  frame-locked."
  [{:keys [id]}]
  (let [{:keys [frame dispatch-sync]} (rf/capture-frame)
        on-pick (fn [] (dispatch-sync [:hicasso.todo/toggle id]))]
    (swap! !built assoc (str frame) on-pick)
    [:span.row {:data-frame (str frame)
                :data-done  (str (rf.hicasso.impl.collector/sub [:hicasso.todo/done? id]))}
     [:> foreign-picker {:label (str frame) :on-pick on-pick}]]))

(defn- refusal-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(defn- done-attr [handle]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute "data-done")))

(defn- pick! [handle]
  (.click (.querySelector (:container handle) ".pick"))
  (rf.hicasso.impl.mount/settle!)
  nil)

(deftest the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (frames!)
      (reset! !built {})
      (reset! !handed {})

      (testing "THE PREMISE, asserted rather than cited. `[:>]` infers the
                contract from the spelling exactly as a native tag does,
                so the two spellings that carry the frame for the author
                both LOWER at this prop — the plain closure is one door of
                three, and W7 measures that one on purpose, because it is
                the door that carries no frame of its own"
        (is (= ::did-not-throw
               (refusal-id #(rf.hicasso.impl.intent/with-frame (fn [_] nil)
                              (fn [] (rf.hicasso.impl.codec/as-element
                                       [:> foreign-picker {:on-pick [:hicasso.todo/toggle 0]}])))))
            "an intent vector at an event-spelled slot lowers rather than
             refusing — under a frame, as at a native tag; outside one it is
             the ordinary outside-boundary refusal, which is the same proof")
        (is (= ::did-not-throw
               (refusal-id #(rf.hicasso.impl.intent/with-frame (fn [_] nil)
                              (fn [] (rf.hicasso.impl.codec/as-element
                                       [:> foreign-picker
                                        {:on-pick (rf.hicasso.impl.intent/callback (fn [] [:hicasso.todo/toggle 0]))}])))))
            "and so does a marked h/event, which takes the event wrapper"))

      (let [a (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-a [escape-picker {:id 0}])
            b (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b [escape-picker {:id 0}])]
        (is (= #{(str frame-a) (str frame-b)} (set (keys @!handed)))
            (str "two mounts of ONE view crossed under two different frames; got "
                 (pr-str (keys @!handed))))
        (testing "and what crossed is the body's own function, by
                  IDENTITY — no wrapper of ours stands between the author
                  and the library, which is what *value-first* names and
                  what keeps React.memo and every handler-identity
                  bail-out working through the escape"
          (doseq [f [frame-a frame-b]]
            (is (identical? (get @!built (str f)) (get @!handed (str f))) (str f))))

        ;; A real macrotask, then the FOREIGN component's own click
        ;; handler: by the time either closure runs, no render extent is
        ;; live and there is no ambient frame anywhere to resolve.
        (js/setTimeout
          (fn []
            (try
              (is (nil? rf.hicasso.impl.intent/*frame*)
                  "precondition: no render extent is live inside the timeout")
              (pick! a)
              (is (= "true" (done-attr a))
                  "the closure the first crossing handed the foreign
                   component dispatched into the frame that wrote it")
              (is (= "false" (done-attr b))
                  "and nothing reached the other frame")
              (pick! b)
              (is (= ["true" "true"] [(done-attr a) (done-attr b)])
                  "and the second crossing's closure into ITS own frame. A
                   capture that had resolved one frame for both would have
                   toggled that one frame twice and left this pair reading
                   false/false — which is why BOTH crossings are clicked")
              (finally
                (rf.hicasso.impl.mount/release! a)
                (rf.hicasso.impl.mount/release! b)
                (done))))
          0)))))

;; ---------------------------------------------------------------------------
;; A render callback invoked during a FOREIGN render answers the supplier
;; ---------------------------------------------------------------------------

(def ^:private !callback-seen
  "What each supplying boundary's render callback observed when the
  foreign component invoked it during ITS render, keyed by the frame the
  identity door answered. Keyed that way so a callback that answered the
  wrong frame overwrites its sibling's slot and the count goes red."
  (atom {}))

(defn- foreign-list
  "A stand-in for a virtualised list: it is handed a `renderRow` prop and
  CALLS it during its own render. Written with `react/createElement`, as
  `foreign-picker` is and for the same reason. This is the position that
  makes the wrapper's refusal binding load-bearing: the foreign render
  runs under the boundary's own React context, so an ambient read here
  would RESOLVE through tier 2 if the callback did not re-establish the
  body's discipline."
  [^js props]
  (let [render-row (.-renderRow props)]
    (react/createElement "ul" #js {:className "list"} (render-row 0))))

(rf.hicasso/defview supplier
  "Supplies a render callback to a foreign component. Inside the callback
  it reads both doors and tries an ambient read, and records all three."
  [_]
  [:div.host
   [:> foreign-list
    {:render-row (rf.hicasso/event [i]
                   (let [id-seen (rf/current-frame-id)]
                     (swap! !callback-seen assoc id-seen
                            {:capture (:frame (rf/capture-frame))
                             :read    (outcome #(rf/subscribe [:hicasso.todo/done? i]))})
                     (rf.hicasso/as-element [:li.row {:data-frame (str id-seen)} (str i)])))}]])

(deftest a-render-callback-invoked-by-a-foreign-render-answers-the-supplying-boundary
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (reset! !callback-seen {})
      (let [a (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-a [supplier {}])
            b (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-b [supplier {}])]
        (try
          (is (= #{frame-a frame-b} (set (keys @!callback-seen)))
              (str "two suppliers under two frames must have answered two
                    different frames inside the callback; got "
                   (pr-str (keys @!callback-seen))))
          (is (= (str frame-a) (frame-attr a)) "and the row rendered under the supplier's frame")
          (is (= (str frame-b) (frame-attr b)))
          (doseq [f [frame-a frame-b]]
            (let [{:keys [capture read]} (get @!callback-seen f)]
              (is (= f capture) (str "the capture inside the callback is locked to " f))
              (is (= :rf.error/ambient-frame-refused (:rf.error/id read))
                  (str "the ambient read inside the callback must REFUSE — it is
                        what proves the doors answered from the re-established
                        binding rather than from the React context the foreign
                        render happens to be under; got " (pr-str read)))
              (is (= f (:extent-frame read)) "naming the supplying boundary as the extent")))
          (finally
            (rf.hicasso.impl.mount/release! a)
            (rf.hicasso.impl.mount/release! b)))))))
