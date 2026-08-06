(ns re-frame.bench.hicasso.arm1.hframe-dom-cljs-test
  "`h/frame` AGAINST A REAL REACT ROOT (rf2-841vn).

  The node sibling (`arm1/hframe_cljs_test`) settles what the primitive
  does. Four claims are left over, and each is a claim about REACT rather
  than about the read, which is why they are asserted against a live
  `createRoot` rather than reasoned about:

  1. **It answers under BOTH shell variants** (W1). The context shell
     resolves its frame through `useContext`; the frame-prop shell takes
     it as an ordinary prop and spends one hook fewer. `h/frame` reads
     neither — it reads the ambient the runtime binds either way — and
     that identity is the thing worth pinning, because a variant that
     answered differently would make the read a property of the shell.
  2. **The documented trap, made green, plus the isolation law** (W2).
     ONE reusable view, mounted under TWO frames, each instance building
     `(rf/capture-frame (h/frame))` and firing it from a `setTimeout`
     after its render has unwound. This is the case the primitive exists
     for and the case nothing else in the arm can spell: `with-frame` and
     `{:frame …}` both presuppose knowing the id, which a reusable view
     does not.
  3. **The hook ledger does not move** (W5's other half). Counted at
     React's own dispatcher by the same probe the ≤2-hook budget uses, on
     both shells. The ledger is a hard fence; a read that spent a hook
     would be a finding rather than a price.
  4. **StrictMode's double-invoke is not additive** (W6) — the same value
     on both runs, and no second edge, entry or registration.
  5. **The `[:>]` value-first door dispatches through a plain closure
     over the capture** (W7, rf2-zllp8). The escape carries no
     declaration, so its callback roster is EMPTY by construction and
     both spellings that would otherwise carry the frame for the author
     refuse at the prop — an intent vector is
     `:rf.error/hicasso-host-undeclared-callback` and an `h/fn` is
     `:rf.error/hicasso-host-unclaimed-callback`. What crosses is an
     ordinary function, by identity, and an ordinary function carries no
     frame. This is the edge `h/frame` names in its own docstring as the
     one it exists for: *\"a value-first callback on a foreign
     component\"*.

  ## The mutation witness for W7

  Lock both captures to one frame — `(rf/capture-frame frame-a)` in
  place of `(rf/capture-frame (h/frame))` in
  [[escape-picker]] — and
  [[the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture]]
  goes red on the second click, which toggles the first frame back off
  and leaves the pair reading `false`/`false`. That is why the row
  clicks BOTH crossings: one click alone is green under a capture that
  resolved one frame for both. Give the escape's roster a contract at
  `:on-pick` and the premise rows go red before anything mounts; hand
  the crossing a marked `h/fn` that survives, and the second one does.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
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
     ;; browser run at this namespace, not just this file. Same reason
     ;; `arm1/generation_fence_dom_cljs_test` takes the map shape.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-a ::hframe-dom-a)
(def ^:private frame-b ::hframe-dom-b)

(def ^:private todos 4)

(defn- skip! [why] (is true (str "this claim needs a real React DOM — " why)))

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the hook count is "
                 "UNWITNESSED on this build. A gate nobody has watched fire is "
                 "not evidence — fix "
                 (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                 " rather than reading this as a pass.")))

(defn- frames! []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-a todos)
  (dogfood/make-frame! frame-b todos)
  (dogfood/reseed! frame-a todos)
  (dogfood/reseed! frame-b todos)
  nil)

;; ---------------------------------------------------------------------------
;; The two variants of ONE body — the frame-prop suite's own construction
;; ---------------------------------------------------------------------------

(defn- printing-body
  "Prints the frame `h/frame` answered, and reads one subscription beside
  it so the boundary is an ordinary reading boundary rather than a
  degenerate one."
  [{:keys [id]}]
  [:span.row {:data-frame (str (intent/hframe))
              :data-done  (str (rt/sub [:dogfood/done? id]))}])

(defview context-row
  "The incumbent shell: the frame arrives through `useContext`."
  [props]
  (printing-body props))

(def frame-prop-row
  "The challenger shell: the frame arrives as `rfFrame` on the element,
  and the shell spends one hook fewer."
  (rt/mint-frame-prop-view! "hframe-frame-prop-row" printing-body))

(defn- frame-attr [handle]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute "data-frame")))

;; ---------------------------------------------------------------------------
;; W1 — the same answer under both shells
;; ---------------------------------------------------------------------------

(deftest the-frame-read-answers-under-both-shell-variants
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (testing "the context shell"
        (let [handle (mount/root! (mount/fresh-container!) frame-a [context-row {:id 0}])]
          (try (is (= (str frame-a) (frame-attr handle)))
               (finally (mount/release! handle)))))

      (testing "the frame-prop shell — a different route to the frame, one
               hook fewer, and the SAME answer. `h/frame` reads the ambient
               the runtime binds either way, so a difference here would mean
               the read had become a property of the shell"
        (let [handle (mount/root! (mount/fresh-container!) frame-a [frame-prop-row {:id 0}])]
          (try (is (= (str frame-a) (frame-attr handle)))
               (finally (mount/release! handle)))))

      (testing "and both FOLLOW the frame — mounted under a second frame
               each answers the second. A constant would have passed every
               row above"
        (doseq [[label view] [["context" context-row] ["frame-prop" frame-prop-row]]]
          (let [handle (mount/root! (mount/fresh-container!) frame-b [view {:id 0}])]
            (try (is (= (str frame-b) (frame-attr handle)) label)
                 (finally (mount/release! handle)))))))))

;; ---------------------------------------------------------------------------
;; W5's other half — the hook ledger does not move
;; ---------------------------------------------------------------------------

(deftest the-frame-read-spends-no-hook-on-either-shell
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (do
        (frames!)
        (testing "the ≤2-hook shell is a hard fence, so this is counted at
                 React's own dispatcher rather than declared. A boundary
                 reading `h/frame` costs exactly what the ledger declares —
                 the frame is render-constant per boundary and already
                 resolved, so there is nothing for a hook to hold"
          (let [handle (volatile! nil)
                names  (probe/record!
                         (fn [] (vreset! handle
                                         (mount/root! (mount/fresh-container!)
                                                      frame-a [context-row {:id 0}]))))]
            (mount/release! @handle)
            (is (= ["useContext" "useSyncExternalStore"] names)
                (str "hooks React was asked for: " (pr-str names)))
            (is (= (count rt/shell-hook-ledger) (count names))
                "and the declared ledger is still the measured one")))

        (testing "and one fewer on the frame-fed shell, unchanged — a read
                 that had quietly taken a hook would show up as two here"
          (let [handle (volatile! nil)
                names  (probe/record!
                         (fn [] (vreset! handle
                                         (mount/root! (mount/fresh-container!)
                                                      frame-a [frame-prop-row {:id 0}]))))]
            (mount/release! @handle)
            (is (= ["useSyncExternalStore"] names)
                (str "hooks React was asked for: " (pr-str names)))
            (is (= (count rt/frame-prop-shell-hook-ledger) (count names)))))))))

;; ---------------------------------------------------------------------------
;; W2 — the documented trap made green, and the isolation law
;; ---------------------------------------------------------------------------

(def ^:private !captures
  "Where each mounted instance parks the capture it built. Keyed by the
  frame it believes it is in — so an instance that read the WRONG frame
  overwrites its sibling's slot and the row goes red on the count before
  it ever gets to the dispatch."
  (atom {}))

(defview reusable
  "ONE view, mounted under N frames — the case the primitive exists for.
  It does not know its own frame id, which is exactly why neither
  `rf/with-frame` nor a `{:frame …}` opt can serve it: both presuppose the
  id. `h/frame` supplies it, and `capture-frame`'s 1-arity — which never
  consults the ambient resolver — carries it out of the render."
  [{:keys [id]}]
  (let [f (intent/hframe)]
    (swap! !captures assoc f (rf/capture-frame f))
    [:span.row {:data-frame (str f)
                :data-done  (str (rt/sub [:dogfood/done? id]))}]))

(deftest a-capture-built-in-a-body-fires-into-its-own-frame-after-the-render
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (frames!)
      (reset! !captures {})
      (let [a (mount/root! (mount/fresh-container!) frame-a [reusable {:id 0}])
            b (mount/root! (mount/fresh-container!) frame-b [reusable {:id 0}])]
        (is (= #{frame-a frame-b} (set (keys @!captures)))
            (str "two mounts of ONE view must have read two different frames; got "
                 (pr-str (keys @!captures))))
        (is (= (str frame-a) (frame-attr a)))
        (is (= (str frame-b) (frame-attr b)))
        ;; A real macrotask, which is the whole point: the render's dynamic
        ;; extent is long gone by the time these fire, and an ambient read
        ;; taken HERE would find nothing at all.
        (js/setTimeout
          (fn []
            (try
              (is (nil? intent/*frame*)
                  "precondition: no render extent is live inside the timeout")
              ((:dispatch-sync (get @!captures frame-a)) [:dogfood/toggle 0])
              (mount/settle!)
              (is (= "true" (.getAttribute (.querySelector (:container a) ".row") "data-done"))
                  "the closure dispatched into the frame its own boundary rendered under")
              (is (= "false" (.getAttribute (.querySelector (:container b) ".row") "data-done"))
                  "and the sibling frame did not move — frames are isolated
                   contexts, and a capture that had resolved a process-wide
                   or a last-rendered frame would have moved both")
              (finally
                (mount/release! a)
                (mount/release! b)
                (done))))
          0)))))

;; ---------------------------------------------------------------------------
;; W6 — StrictMode's double-invoke
;; ---------------------------------------------------------------------------

(def ^:private !runs (atom 0))
(def ^:private !seen (atom []))

(defview strict-reader
  "Records the frame `h/frame` answered on EVERY body run, so the
  double-invoke is a measured premise rather than an assumed one."
  [{:keys [id]}]
  (swap! !runs inc)
  (swap! !seen conj (intent/hframe))
  [:span.row {:data-done (str (rt/sub [:dogfood/done? id]))}])

(defn- strict-root!
  "`mount/root!`, wrapped in `React.StrictMode`. Written here rather than
  in the shared mount door for the reason `arm1/lifecycle_dom_cljs_test`
  gives: StrictMode is this row's variable, and every other suite in the
  arm must keep measuring the ordinary tree."
  [container frame-kw hiccup]
  (let [root (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root (react/createElement
                             react/StrictMode nil
                             (mount/provider frame-kw (codec/as-element hiccup))))))
    {:root root :frame frame-kw :container container}))

(deftest strictmodes-double-invoke-reads-the-same-frame-and-adds-nothing
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (frames!)
      (reset! !runs 0)
      (reset! !seen [])
      (let [handle (strict-root! (mount/fresh-container!) frame-a [strict-reader {:id 0}])]
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
                   one boundary, one edge per read. `h/frame` appends no
                   sub-key, so a double-invoke cannot double anything it
                   contributes — because it contributes nothing"
            (let [{:keys [entries boundaries edges]} (rt/stats)]
              (is (= 1 entries))
              (is (= 1 boundaries))
              (is (= 1 edges) "the one real read, counted once")))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; W7 — the `[:>]` value-first door, and the plain closure over the capture
;; ---------------------------------------------------------------------------
;;
;; W2 proves the composition survives a macrotask. This row proves it is
;; the ONLY thing that can serve the crossing HD-011 made the escape for.
;; `[:>]` is `defhost` with the declaration erased, and a declaration is
;; what a callback contract lives on — so `raw-crossing`'s roster is
;; empty by construction and every slot at this crossing is UNCLAIMED.
;; The consequence is not a nuance, it is the whole row: the two
;; spellings that carry a frame FOR the author are both refused here, and
;; what remains is a plain function, which carries nothing. The frame has
;; to be put into it by hand, in the body, where it is knowable.

(def ^:private !built
  "The closure each mounted instance BUILT, keyed by the frame its body
  read. The other half of [[!handed]]: two atoms rather than one because
  the claim that the door is *value-first* is a claim that these are the
  same object."
  (atom {}))

(def ^:private !handed
  "The `onPick` prop each instance of the foreign component was HANDED,
  keyed by the label it was given — which is the frame its writing
  boundary read. An instance that never crossed leaves this empty, so
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

(defview escape-picker
  "[[reusable]]'s body at the foreign edge: ONE view, mounted under N
  frames, that does not know its own frame id — and now has to hand a
  dispatching closure to a caller it does not control.

  `(rf/capture-frame (h/frame))` is the whole answer, and each half is
  load-bearing. `h/frame` supplies the id a reusable view cannot name;
  `capture-frame`'s 1-arity never consults the ambient resolver, so the
  handle it returns is still good when the foreign component calls back."
  [{:keys [id]}]
  (let [f (intent/hframe)
        d (rf/capture-frame f)
        on-pick (fn [] ((:dispatch-sync d) [:dogfood/toggle id]))]
    (swap! !built assoc (str f) on-pick)
    [:span.row {:data-frame (str f)
                :data-done  (str (rt/sub [:dogfood/done? id]))}
     [:> foreign-picker {:label (str f) :on-pick on-pick}]]))

(defn- refusal-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(defn- done-attr [handle]
  (some-> (.querySelector (:container handle) ".row") (.getAttribute "data-done")))

(defn- pick! [handle]
  (.click (.querySelector (:container handle) ".pick"))
  (mount/settle!)
  nil)

(deftest the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (frames!)
      (reset! !built {})
      (reset! !handed {})

      (testing "THE PREMISE, asserted rather than cited. `[:>]` is a
                value-first door: its roster is empty by construction, so
                the two spellings that would carry the frame for the
                author both refuse at this very prop. That is what leaves
                the plain closure as the only door rather than one option
                of two — and if the escape ever grew a contract here, this
                row goes red before the mount and the rest of W7 stops
                being the answer to a real question"
        (is (= :rf.error/hicasso-host-undeclared-callback
               (refusal-id #(codec/as-element
                              [:> foreign-picker {:on-pick [:dogfood/toggle 0]}])))
            "an intent vector at an event-spelled slot")
        (is (= :rf.error/hicasso-host-unclaimed-callback
               (refusal-id #(codec/as-element
                              [:> foreign-picker
                               {:on-pick (intent/callback (fn [] [:dogfood/toggle 0]))}])))
            "and a marked h/fn, which asks the position for a contract no
             position here can ever select"))

      (let [a (mount/root! (mount/fresh-container!) frame-a [escape-picker {:id 0}])
            b (mount/root! (mount/fresh-container!) frame-b [escape-picker {:id 0}])]
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
              (is (nil? intent/*frame*)
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
                (mount/release! a)
                (mount/release! b)
                (done))))
          0)))))
