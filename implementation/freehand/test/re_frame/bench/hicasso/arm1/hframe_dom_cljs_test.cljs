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
