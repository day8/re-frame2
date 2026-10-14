(ns re-frame.fresco.frame-doors-dom-cljs-test
  "THE PURE FRAME DOORS AGAINST A REAL REACT ROOT.

  The node sibling (`re-frame.fresco.frame-doors-cljs-test`) settles what
  the seam does. The claims left over are each about REACT rather than
  about the doors, which is why they are asserted against a live
  `createRoot` rather than reasoned about:

  1. **The documented trap, made green, plus the isolation law** (W7).
     ONE reusable view, mounted under TWO frames, each instance building
     `(rf/capture-frame)` and firing it from a `setTimeout` after its
     render has unwound. This is the case the seam exists for and the
     case nothing else can spell: `with-frame` and `{:frame …}` both
     presuppose knowing the id, which a reusable view does not.
  2. **The hook ledger does not move**. Counted at React's own dispatcher
     by the same probe the ≤2-hook budget uses. The ledger is a hard
     fence; a read that spent a hook would be a finding rather than a
     price.
  3. **StrictMode's double-invoke is not additive** (W6) — the same value
     on both runs, and no second edge, entry or registration.
  4. **The `[:>]` value-first door dispatches through a plain closure
     over the capture** (W7). An ordinary function crosses by identity
     and carries no frame of its own, so the frame has to be put into it
     by hand, in the body, where `(rf/capture-frame)` knows it.

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
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.fresco.hook-probe :as rf.fresco.hook-probe]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.fresco.todo-support :as rf.fresco.todo-support]
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
     ;; The MAP shape, because W7 is `async`: the whole point of that row
     ;; is a closure firing from a real macrotask, long after the render's
     ;; dynamic extent has unwound, and `cljs.test` refuses an async test
     ;; under a fn-form fixture outright — "Async tests require fixtures to
     ;; be specified as maps. Testing aborted." — which aborts the WHOLE
     ;; browser run at this namespace, not just this file.
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private frame-a ::doors-dom-a)
(def ^:private frame-b ::doors-dom-b)

(def ^:private todos 4)

(defn- skip! [why] (is true (str "this claim needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the hook count is "
                 "UNWITNESSED on this build. A gate nobody has watched fire is "
                 "not evidence — fix "
                 (pr-str 're-frame.fresco.hook-probe)
                 " rather than reading this as a pass.")))

(defn- frames! []
  (rf.fresco.checkpoint-support/leave-act-environment!)
  (rf.fresco.todo-support/make-frame! frame-a todos)
  (rf.fresco.todo-support/make-frame! frame-b todos)
  (rf.fresco.todo-support/reseed! frame-a todos)
  (rf.fresco.todo-support/reseed! frame-b todos)
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
              :data-done  (str (rf.fresco.impl.collector/sub [:fresco.todo/done? id]))}])

(rf.fresco/defview context-row
  "The shell: the frame arrives through `useContext`."
  [props]
  (printing-body props))

;; ---------------------------------------------------------------------------
;; The hook ledger does not move
;; ---------------------------------------------------------------------------

(deftest the-identity-door-spends-no-hook
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (rf.fresco.hook-probe/install!)
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
                names  (rf.fresco.hook-probe/record!
                         (fn [] (vreset! handle
                                         (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!)
                                                      frame-a [context-row {:id 0}]))))]
            (rf.fresco.impl.mount/release! @handle)
            (is (= ["useContext" "useSyncExternalStore"] names)
                (str "hooks React was asked for: " (pr-str names)))))))))

;; ---------------------------------------------------------------------------
;; W6 — StrictMode's double-invoke
;; ---------------------------------------------------------------------------

(def ^:private !runs (atom 0))
(def ^:private !seen (atom []))

(rf.fresco/defview strict-reader
  "Records the frame `rf/current-frame-id` answered on EVERY body run, so
  the double-invoke is a measured premise rather than an assumed one."
  [{:keys [id]}]
  (swap! !runs inc)
  (swap! !seen conj (rf/current-frame-id))
  [:span.row {:data-done (str (rf.fresco.impl.collector/sub [:fresco.todo/done? id]))}])

(defn- strict-root!
  "`mount/root!`, wrapped in `React.StrictMode`. Written here rather than
  in the shared mount door because StrictMode is this row's variable, and
  every other suite must keep measuring the ordinary tree."
  [container frame-kw hiccup]
  (let [root (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root (react/createElement
                             react/StrictMode nil
                             (rf.fresco.impl.mount/provider frame-kw (rf.fresco.impl.codec/as-element hiccup))))))
    {:root root :frame frame-kw :container container}))

(deftest strictmodes-double-invoke-reads-the-same-frame-and-adds-nothing
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (reset! !runs 0)
      (reset! !seen [])
      (let [handle (strict-root! (rf.fresco.impl.mount/fresh-container!) frame-a [strict-reader {:id 0}])]
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
            (is (= {:entries 1 :boundaries 1 :edges 1}
                   (select-keys (rf.fresco.test.runtime/stats) [:entries :boundaries :edges]))
                "the one real read, counted once"))
          (finally (rf.fresco.impl.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; W7 — the `[:>]` value-first door, and the plain closure over the capture
;; ---------------------------------------------------------------------------
;;
;; The capture survives a macrotask, and it is the thing that serves the
;; crossing HD-011 made the escape for. `[:>]` is
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

(rf.fresco/defview escape-picker
  "ONE view, mounted under N frames, that does not know its own frame id
  — the case the seam exists for — and has to hand a dispatching closure
  to a caller it does not control.

  `(rf/capture-frame)` is the whole answer: it captures the boundary's
  declared frame during the body, and the api it returns is still good
  when the foreign component calls back, because every op on it is
  frame-locked."
  [{:keys [id]}]
  (let [{:keys [frame dispatch-sync]} (rf/capture-frame)
        on-pick (fn [] (dispatch-sync [:fresco.todo/toggle id]))]
    (swap! !built assoc (str frame) on-pick)
    [:span.row {:data-frame (str frame)
                :data-done  (str (rf.fresco.impl.collector/sub [:fresco.todo/done? id]))}
     [:> foreign-picker {:label (str frame) :on-pick on-pick}]]))

(defn- done-attr [handle]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute "data-done")))

(defn- pick! [handle]
  (.click (.querySelector (:container handle) ".pick"))
  (rf.fresco.impl.mount/settle!)
  nil)

(deftest the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture
  (if-not (rf.fresco.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (frames!)
      (reset! !built {})
      (reset! !handed {})

      (let [a (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) frame-a [escape-picker {:id 0}])
            b (rf.fresco.impl.mount/root! (rf.fresco.impl.mount/fresh-container!) frame-b [escape-picker {:id 0}])]
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
              (is (nil? rf.fresco.impl.intent/*frame*)
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
                (rf.fresco.impl.mount/release! a)
                (rf.fresco.impl.mount/release! b)
                (done))))
          0)))))
