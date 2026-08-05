(ns re-frame.bench.hicasso.ssr.spike-dom-cljs-test
  "THE SSR SPIKE WITNESS — X1(b), X2, X4 and X5 on the HYDRATED dogfood
  screen, in real Chromium (rf2-2rtt6.87).

  Evidence for the P2 sitting. **No verdict is published here and none is
  implied**; the sitting's decider is the operator. X1(a) — determinism —
  is `ssr/spike_cljs_test`'s, because it belongs in Node. X3 —
  reactivity adopted — is the on-demand diagnostic's
  (`adoption_witness_run.cjs`, phase 3), because it is a timing
  measurement and this lane keeps those out of the correctness suite.

  ## Where the server bytes come from, stated plainly

  **From `react-dom/server`, through `ssr/entry.cljs`, in this page.**
  Not a fixture, not a captured client render: [[server!]] calls the
  render entry rf2-2rtt6.86 landed — the per-request gensym frame, the
  `:rf/set-db` snapshot door, `renderToString`, `destroy-frame!` in a
  `finally` — and hydrates the markup it returns.

  The one honest caveat, stated rather than buried: shadow resolves
  `react-dom/server` by the build's target, so a `:browser-test` build
  gets `server.browser.js` where a Node host gets `server.node.js`. Both
  are React's server renderer over the same entry, and the entry is
  target-agnostic (it requires no `node:` module — `ssr/node.cljs` states
  why that rule exists). X1(a) is taken in NODE, on `server.node.js`,
  which is where a real host runs.

  ## Two ways a row here could go green over a live failure

  Both are the hydration door's (`arm1/hydrate_dom_cljs_test`), and both
  are answered with its machinery rather than a second copy —
  `arm1/hydration_support.cljs`:

  1. **React routes a render or effect exception to `reportError`**, so
     `cljs.test` reads zero failures across a component that threw. Every
     hydration here runs with `open-console-capture!` watching `window`'s
     `error` event AND `console.error`.

     **And the window is held open across the whole adoption**, which is
     not a detail. `hydrateRoot` is called plain, so it RETURNS BEFORE
     THE TREE IS ADOPTED — React schedules the hydration render and the
     bodies run in a later turn. A capture that closed when the call
     returned would be open across the call and shut across the render,
     i.e. shut across the only part that can complain.
     [[x2-the-console-capture-can-answer-false]] measures both windows
     and prints them, so the difference is a number rather than an
     argument.
  2. **`act` and `flushSync` are not the browser's schedule.** Nothing
     here pulls the adoption forward; `adopted!` waits on real timers for
     the closer's passive effect and for the entry reap horizon.

  ## Every row that CAN lie is mutation-proved

  A hydration witness's characteristic failure is passing vacuously — a
  console capture that never fires, an identity check over an empty
  selector, a residue read taken after a reset. Each is answered by a row
  whose whole job is to go the other way:

  | Row | What it could lie about | The mutation |
  |---|---|---|
  | X1(b) | the comparator can only say `equal` | [[x1b-the-canonical-comparator-can-answer-false]] |
  | X2 warnings | the capture never fires | [[x2-the-console-capture-can-answer-false]] |
  | X2 identity | a re-created node still looks adopted | [[x2-the-node-identity-check-can-answer-false]] |
  | X2 count | the counter is inferred from renders | [[x2-a-props-equal-re-render-runs-no-body]] |
  | X5 | the residue read is taken on an emptied table | [[x5-the-residue-reading-can-answer-false]] |

  ## The build this drives, and why it can see what it claims

  `:browser-test` — shadow's test target, development optimizations,
  `goog.DEBUG` true — driven by `npm run test:browser` in real Chromium.
  The instrument X2 reads is `runtime/body-runs`, and it is **always
  on**: the bump is an unconditional `(set! (.-bodyRuns rstate) …)` in
  `runtime/run-once`, with no `goog.DEBUG` gate anywhere near it.
  rf2-2rtt6.84 clause 6 chose it that way FOR THIS WITNESS, so the
  reading would survive an `:advanced` / `goog.DEBUG false` bundle where
  a debug-gated counter is dead code the compiler removes. (`rstate`
  carries `^js` and its keys are string literals, so `:infer-externs
  :auto` keeps `.-bodyRuns` off Closure's renamer too — the same spelling
  discipline `mount/adoption-window-closer`'s `displayName` stamp
  documents.) So the adversarial hazard (F6) does not reach the counter
  this row reads: it is present in every build, and this row reads it in
  the one whose runtime it stamps. [[runtime-stamp]] records that stamp
  on every published row.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.dogfood-collector :as collector]
            [re-frame.bench.hicasso.arm1.dogfood-script :as script]
            [re-frame.bench.hicasso.arm1.hydration-support
             :refer [adopted! every-server-node? open-console-capture! server-dom!
                     server-node? stamp-server-nodes!]]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.ssr.entry :as entry]
            [re-frame.bench.hicasso.ssr.fixtures :as fixtures]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::ssr-spike)

(def ^:private todo-count
  "The seeded size, taken from the corpus row rather than chosen here —
  `fixtures/corpus`'s `dogfood-snapshot` is seeded `(dogfood/seed-db 8)`,
  and [[same-snapshot!]] asserts the client frame holds exactly that."
  8)

(def ^:private base-boundaries
  "The dogfood screen's boundaries that are not rows, named rather than
  counted: `screen`, `head`, `new-item`, `filters`, `todo-list`
  (`arm1/dogfood_collector.cljs`). `filter-button` is a plain function
  call and mints no boundary of its own (HD-016), which is why three
  filters cost nothing here."
  5)

(def ^:private boundary-count
  "Every boundary body one render of this screen runs."
  (+ base-boundaries todo-count))

(def ^:private reaper-horizon-ms
  "One macrotask, with room: the arm arms the cell reaper at 0 ms and the
  entry reaper at its 4 ms horizon (rf2-2rtt6.84), so 8 clears both."
  8)

(def ^:private nothing-retained
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fixtures/register!)
                      (rt/reset-runtime!)
                      (rt/reset-body-runs!))}))

(defn- skip! [why]
  (is true (str "an SSR spike row needs a real React DOM — " why)))

(defn runtime-stamp
  "The runtime a row was taken on, carried BESIDE the row.

  Deliberately NOT `lane/runtime-label`: that one reports
  `:optimizations :advanced` as a LITERAL, which is true of every arm in
  the bench lane and false here. A stamp that names the wrong build is
  worse than no stamp."
  []
  {:build         :browser-test
   :optimizations :none
   :goog-debug    ^boolean goog/DEBUG
   :user-agent    (when (exists? js/navigator) (.-userAgent js/navigator))})

(defn- report! [label m]
  (js/console.log (str ";; HICASSO SSR SPIKE " label " "
                       (pr-str (assoc m :runtime (runtime-stamp)))))
  m)

;; ---------------------------------------------------------------------------
;; The two sides of every row
;; ---------------------------------------------------------------------------

(defn- same-snapshot!
  "Seed the CLIENT frame with the very value the corpus row hands the
  server, and assert it landed. `seed-db` is the one place the shape is
  written out, so `the same snapshot` is a checked claim on both sides
  rather than two call sites that happen to agree today."
  []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id todo-count)
  (dogfood/reseed! frame-id todo-count)
  (is (= (dogfood/seed-db todo-count) (rf/app-db-value frame-id))
      "the client frame holds exactly the snapshot the server rendered")
  frame-id)

(defn- server!
  "The bytes an SSR route would deliver for the dogfood screen, from
  `ssr/entry/render` — `react-dom/server` over the real request path.

  Answers `{:html … :body-runs …}`. There is no `:render-hash` — an
  adoption-tier root carries none (rf2-2rtt6.91), and nothing here ever
  read it. `:body-runs` is the
  server's OWN boundary-body count, read before the runtime is reset:
  `renderToString` runs every body exactly once too, so a row that
  asserts it is a row that would notice the server rendering a different
  screen from the one it hydrates.

  `opts` is merged over the corpus row, which is how the mutation proofs
  ask for DIFFERENT bytes without inventing a second request shape."
  ([] (server! nil))
  ([opts]
   (rt/reset-runtime!)
   (rt/reset-body-runs!)
   (let [{:keys [html]} (entry/render (merge (fixtures/row "dogfood-snapshot") opts))
         runs (rt/body-runs)]
     ;; The server render is over before anything is hydrated, so its
     ;; counter is read here and the runtime the hydration is measured on
     ;; starts empty.
     (rt/reset-runtime!)
     (rt/reset-body-runs!)
     {:html html :body-runs runs})))

(defn- hydrate-and-adopt!
  "Put `html` on the page, stamp every node, adopt it, and answer a
  promise of

      {:handle :container
       :adopted?         did the closer's passive effect run inside budget
       :window-open?     was the adoption window still open when
                         `hydrate-root!` RETURNED (it must be — the door
                         calls `hydrateRoot` plain)
       :sync-warnings    complaints visible at that return
       :warnings         complaints across the WHOLE adoption}

  The console capture spans the adoption rather than the call. See the
  namespace docstring. `capture-opts` reaches
  `open-console-capture!` — one caller sets `:swallow-uncaught?`, and it
  is the row that manufactures the fault."
  ([html] (hydrate-and-adopt! html nil))
  ([html capture-opts]
   (let [container (stamp-server-nodes! (server-dom! html))
        {:keys [captured close!]} (open-console-capture! capture-opts)
         handle    (mount/hydrate-root! container frame-id [collector/screen {}])
         at-return (count @captured)
         open?     (rt/adopting?)]
     (-> (adopted!)
         (.then (fn [ok]
                  (close!)
                  {:handle        handle
                   :container     container
                   :adopted?      ok
                   :window-open?  open?
                   :sync-warnings at-return
                   :warnings      @captured}))))))

(defn- client-only!
  "A cold, ordinary client mount of the same screen on the same frame —
  the control every parity row is stated against."
  []
  (mount/root! (mount/fresh-container!) frame-id [collector/screen {}]))

;; ===========================================================================
;; X1(b) — CANONICAL-DOM PARITY
;; ===========================================================================

(deftest x1b-the-hydrated-dom-equals-the-client-only-dom
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        ;; The CONTROL goes first, on the freshest frame. If it went last a
        ;; parity miss could be read either as a hydration difference or as
        ;; residue from the mount before it.
        (same-snapshot!)
        (let [cold      (client-only!)
              cold-dom  (lane/canonical (:container cold))
              cold-runs (rt/body-runs)]
          (mount/release! cold)
          (same-snapshot!)
          (let [{:keys [html body-runs]} (server!)]
            (-> (hydrate-and-adopt! html)
                (.then
                  (fn [{:keys [handle container adopted? warnings]}]
                    (try
                      (is (true? adopted?) "adoption completed")
                      (is (= [] warnings)
                          (str "React reported nothing on either channel: "
                               (pr-str warnings)))
                      (let [hydrated-dom (lane/canonical container)]
                        (is (= cold-dom hydrated-dom)
                            "**canonical-DOM parity.** The page React adopted
                             from the server's bytes is the page a cold client
                             mount builds on the same snapshot — attribute
                             names sorted, so this compares the DOM and not
                             two serialisers' insertion orders. Comment nodes
                             are skipped by the comparator, which is what
                             makes React's SSR text separators a non-issue
                             here rather than a tolerance the row had to be
                             given")
                        (is (re-find #"<ul class=\"list\"" hydrated-dom)
                            "and the page really is the dogfood screen — a
                             list and a controlled field — so the equality
                             above is an equality of something")
                        (is (= todo-count
                               (.-length (.querySelectorAll container ".row"))))
                        ;; `lane/utf8-bytes` and not `count`, which answers
                        ;; UTF-16 code units (rf2-2rtt6.121).
                        (report! "X1b" {:canonical-bytes  (lane/utf8-bytes hydrated-dom)
                                        :rows             todo-count
                                        :server-body-runs body-runs
                                        :cold-body-runs   cold-runs}))
                      (finally (mount/release! handle) (done))))))))))))

(deftest x1b-the-canonical-comparator-can-answer-false
  (testing "**the mutation proof for X1(b).** The comparator is a
           COMPARATOR — it is the lane's fairness gate, used here as a
           judge and never as an emitter — so the row above is only worth
           reading if the same call can say `different`. One toggle on the
           hydrated screen, and it does"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (same-snapshot!)
          (let [{:keys [html]} (server!)]
            (-> (hydrate-and-adopt! html)
                (.then
                  (fn [{:keys [handle container]}]
                    (try
                      (let [before (lane/canonical container)]
                        (mount/dispatch! handle [:dogfood/toggle 0])
                        (is (not= before (lane/canonical container))
                            "the same comparator, the same container, one
                             changed row — and it says so"))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; X2 — ADOPTION IS REAL
;; ===========================================================================

(deftest x2-adoption-is-real
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (same-snapshot!)
        (let [{:keys [html body-runs]} (server!)]
          (-> (hydrate-and-adopt! html)
              (.then
                (fn [{:keys [handle container adopted? window-open? warnings sync-warnings]}]
                  (try
                    (is (true? window-open?)
                        "the window is OPEN the instant `hydrate-root!`
                         returns — `hydrateRoot` was called plain, so it
                         returns before the tree is adopted and the window
                         has to outlive the call")
                    (is (true? adopted?) "the closer's passive effect ran")
                    (is (false? (rt/adopting?)) "and shut the window")

                    (testing "(a) ZERO HYDRATION-MISMATCH WARNINGS, on both
                             the channels React 19 uses, across the WHOLE
                             adoption and not merely across the call"
                      (is (= [] warnings)
                          (str "React reported nothing: " (pr-str warnings))))

                    (testing "(b) NODE IDENTITY PRE-STAMPED SERVER-SIDE AND
                             PRESERVED THROUGH ADOPTION. The stamp is an
                             expando, so it cannot survive re-serialisation:
                             a node still carrying it is the very node the
                             server markup produced, and React REUSED it
                             rather than building a replacement that happens
                             to look alike"
                      (is (every-server-node? container ".row")
                          "every row is the server's own node")
                      (is (server-node? (.querySelector container ".new-input"))
                          "and so is the controlled field")
                      (is (every-server-node? container "*")
                          "and so is every element on the page — no partial
                           adoption, which is what a mismatch on one subtree
                           would look like"))

                    (testing "(c) BODY RUNS EQUAL THE BOUNDARY COUNT EXACTLY
                             ONCE, **counted** in `run-once` where a body is
                             invoked (rf2-2rtt6.84 (6)) rather than inferred
                             from the renders React was asked for"
                      (is (= boundary-count (rt/body-runs))
                          (str "each of the screen's " boundary-count
                               " boundaries ran its body exactly once to "
                               "adopt — read " (rt/body-runs)))
                      (is (= boundary-count body-runs)
                          (str "and the SERVER ran the same " boundary-count
                               " bodies for the same screen, so the two sides "
                               "rendered the same tree — read " body-runs))
                      (is (= boundary-count (inc (:boundaries (rt/stats))))
                          (str "…and the runtime's own census agrees with the
                                roster this row names, so " boundary-count
                               " is not a constant nobody checks. `:boundaries`
                                counts registrations holding at least one CELL
                                READER, and `screen`'s body reads no
                                subscription — it composes the other four — so
                                it retains nothing in that table and is
                                correctly absent (`runtime/stats` says exactly
                                this). Exactly one such boundary on this
                                screen, hence the `inc`; read "
                               (:boundaries (rt/stats))))
                      (is (= boundary-count (:entries (rt/stats)))
                          "**and every one of them is subscribed to an entry
                           still in the cache** — the row the 0 → 4 ms reap
                           horizon exists for")
                      (is (pos? (:cell-refs (rt/stats)))
                          "and the references are real"))

                    (report! "X2" {:body-runs-client (rt/body-runs)
                                   :body-runs-server body-runs
                                   :boundary-count   boundary-count
                                   :sync-warnings    sync-warnings
                                   :warnings         warnings
                                   :stats            (select-keys (rt/stats)
                                                                  [:cells :cell-refs
                                                                   :boundaries :entries])})
                    (finally (mount/release! handle) (done)))))))))))

(deftest x2-the-console-capture-can-answer-false
  (testing "**the mutation proof for X2(a), and a measurement of the
           capture WINDOW itself.** A capture that never fires agrees with
           every page. Hydrate the screen against server bytes rendered
           from a DIFFERENT snapshot — nine to-dos where the client frame
           holds eight — and React must complain.

           The row prints two counts: what was visible when `hydrateRoot`
           RETURNED, and what was visible across the whole adoption. The
           first is the window a synchronous capture would have had. If
           they differ, a synchronous capture cannot see a hydration
           mismatch at all, and that is a fact about every row anywhere
           that asserts `zero warnings` through one.

           **This is the one row in the lane that sets
           `:swallow-uncaught?`**, and it is the row that manufactures the
           fault. React reports a recoverable hydration error through
           `reportError`, which the browser runner treats as a fatal
           uncaught `pageerror` whatever the tally says (rf2-mwx08) — a
           rule this row does not soften, because the error is not
           unasserted here, it IS the assertion"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (same-snapshot!)
          (let [{:keys [html]} (server! {:snapshot (dogfood/seed-db (inc todo-count))})]
            (-> (hydrate-and-adopt! html {:swallow-uncaught? true})
                (.then
                  (fn [{:keys [handle container warnings sync-warnings]}]
                    (try
                      (is (seq warnings)
                          "React reported the mismatch on a channel this
                           witness is watching — so the empty capture in X2(a)
                           is a reading and not a silence")
                      (report! "X2-mutation"
                               {:warnings-across-adoption (count warnings)
                                :warnings-at-call-return  sync-warnings
                                :first                    (first warnings)})
                      ;; And the page repaired itself to the CLIENT's model,
                      ;; which is what tells a reader the capture above
                      ;; reports a real divergence rather than noise.
                      (is (= todo-count (.-length (.querySelectorAll container ".row")))
                          "and the client's model won, as a hydration
                           mismatch's recovery must")
                      (finally (mount/release! handle) (done))))))))))))

(deftest x2-the-node-identity-check-can-answer-false
  (testing "**the mutation proof for X2(b).** `root!` — an ordinary client
           mount — over the SAME stamped server DOM throws the server's
           nodes away and builds its own. Every stamp must be gone. That is
           the difference `hydrate-root!` makes, stated as a measurement
           rather than as a claim about which API was called"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (same-snapshot!)
        (let [{:keys [html]} (server!)
              container (stamp-server-nodes! (server-dom! html))]
          (is (every-server-node? container ".row")
              "the stamps are on before anything mounts")
          (let [handle (mount/root! container frame-id [collector/screen {}])]
            (try
              (is (pos? (.-length (.querySelectorAll container ".row")))
                  "the client mount built the page")
              (is (not (every-server-node? container ".row"))
                  "and NONE of its rows is a server node — a plain mount
                   replaces the DOM, so the identity check in X2(b) is
                   measuring adoption and not merely the presence of markup")
              (finally (mount/release! handle)))))))))

(deftest x2-a-props-equal-re-render-runs-no-body
  (testing "**the mutation proof for X2(c)** (HD-028's rider). The counter
           must be blind to how a render got there: a props-equal re-render
           of the adopted tree bails out at `mint-view!`'s memo, no body
           runs, and the counter says so by NOT moving. An instrument that
           inferred a run from the render React was asked for would move
           here — and would then be unable to tell a genuine adoption from
           a skipped one"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (same-snapshot!)
          (let [{:keys [html]} (server!)]
            (-> (hydrate-and-adopt! html)
                (.then
                  (fn [{:keys [handle container]}]
                    (try
                      (rt/reset-body-runs!)
                      (mount/render! handle [collector/screen {}])
                      (is (zero? (rt/body-runs))
                          (str "a props-equal re-render ran no body; read "
                               (rt/body-runs)))
                      (is (every-server-node? container ".row")
                          "and every row is still the SERVER'S node — the
                           re-render did not tear the adopted tree down,
                           which is what `mount/tree`'s hydrated root shape
                           buys")
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; X4 — THE SCREEN IS ALIVE
;; ===========================================================================

(defn- capture-script!
  "Run the 17-step real-DOM script at `handle` with the frame's `:events`
  stream armed, and answer a promise of `{:intents … :dom canonical}`.

  The listener goes on AFTER the mount and comes off BEFORE the release,
  so neither the seed nor the teardown can leak into the capture. The
  script is `arm1/dogfood_script`'s — the same one
  `arm1_dogfood_dom_cljs_test` drives at the collector and at raw UIx —
  so a claim made here is the same size as the claim made there."
  [handle]
  (js/Promise.
    (fn [resolve]
      (let [log (atom [])]
        (rf/register-listener! :events ::x4
                               (fn [record]
                                 (when (= frame-id (:frame record))
                                   (swap! log conj (:event record)))))
        (script/run-steps!
          (script/interaction-steps handle)
          (fn []
            (let [result {:intents @log :dom (lane/canonical (:container handle))}]
              (rf/unregister-listener! :events ::x4)
              (mount/release! handle)
              (resolve result))))))))

(defn- hydrated-script-run!
  "Adopt the server's page and run the script at it. A promise of the
  capture."
  []
  (same-snapshot!)
  (-> (hydrate-and-adopt! (:html (server!)))
      (.then (fn [{:keys [handle adopted? warnings]}]
               (is (true? adopted?) "adoption completed")
               (is (= [] warnings)
                   (str "and reported nothing: " (pr-str warnings)))
               (capture-script! handle)))))

(deftest x4-the-hydrated-screen-dispatches-the-stated-intents
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      ;; The control first, on the freshest frame — same reasoning as X1(b).
      (do
        (same-snapshot!)
        (-> (capture-script! (client-only!))
            (.then (fn [cold-run]
                     (.then (hydrated-script-run!)
                            (fn [hydrated-run] [cold-run hydrated-run]))))
            (.then
              (fn [[cold-run hydrated-run]]
                (is (= script/interaction-intents (:intents cold-run))
                    "the client-only screen dispatches exactly the stated
                     intents — and nothing for the two composing keystrokes")
                (is (= script/interaction-intents (:intents hydrated-run))
                    "**and so does the HYDRATED screen.** Seventeen real DOM
                     events — clicks, prototype-setter keystrokes, native
                     `keydown`s carrying `isComposing` and `keyCode` — reach
                     every handler site on the screen and both branches of
                     both key-maps, and the vectors that come out at the
                     `:events` port are the ones the script says they should
                     be. Adoption wired the handlers, not just the markup")
                (is (= (:dom cold-run) (:dom hydrated-run))
                    "and after the whole script the two pages are still
                     canonically identical — parity held THROUGH the
                     interactions, not just at adoption")
                (report! "X4" {:steps          17
                               :intents        (count (:intents hydrated-run))
                               :intents-match? (= script/interaction-intents
                                                  (:intents hydrated-run))
                               :dom-match?     (= (:dom cold-run) (:dom hydrated-run))})
                (done)))
            (.catch (fn [e] (is false (str "X4 threw: " e)) (done))))))))

(deftest x4-typing-after-adoption-echoes-in-the-callers-turn
  (testing "**HD-019 on the hydrated path.** Hydration converged nothing —
           `converge!` runs at the end of a change handler and adoption
           fires none — so the field arrives holding exactly what the
           server rendered, and the first keystroke after adoption echoes
           in the caller's own turn, exactly as on the client-only screen"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (same-snapshot!)
          (let [{:keys [html]} (server!)]
            (is (re-find #"class=\"new-input\"" html)
                "the server emitted the controlled field")
            (-> (hydrate-and-adopt! html)
                (.then
                  (fn [{:keys [handle container]}]
                    (try
                      (let [input (.querySelector container ".new-input")]
                        (is (server-node? input)
                            "the field is the server's own node — a remount
                             here would lose focus, selection and any
                             composition in flight")
                        (is (= "" (.-value input))
                            "and it holds what the server rendered: the
                             seeded app-db carries no draft, so hydration
                             converged nothing")
                        (script/type-into! input "milk")
                        (mount/settle!)
                        (is (= "milk" (.-value (.querySelector container ".new-input")))
                            "the echo landed without waiting for a later turn")
                        (is (server-node? (.querySelector container ".new-input"))
                            "and it is STILL the server's node — the echo did
                             not remount the field"))
                      (finally (mount/release! handle) (done))))))))))))

;; ===========================================================================
;; X5 — TEARDOWN CLEAN
;; ===========================================================================

(deftest x5-unmounting-the-hydrated-root-leaves-no-residue
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (same-snapshot!)
        (let [{:keys [html]} (server!)]
          (-> (hydrate-and-adopt! html)
              (.then
                (fn [{:keys [handle adopted?]}]
                  (is (true? adopted?) "adoption completed")
                  ;; Drive it, so the teardown has something to release that
                  ;; the adoption alone did not install.
                  (mount/dispatch! handle [:dogfood/toggle 0])
                  (mount/dispatch! handle [:dogfood/set-filter :all])
                  (is (pos? (:cell-refs (rt/stats)))
                      "the hydrated screen holds references, so the reading
                       below is a reading of something")
                  ;; `unmount!`, never `release!`: `release!` resets the
                  ;; runtime, and a reading taken after that reset is a
                  ;; reading of an emptied table — zero however badly the
                  ;; teardown went (rf2-2rtt6.48).
                  (mount/unmount! handle)
                  (js/setTimeout
                    (fn []
                      (is (= nothing-retained (rt/residue))
                          (str "**React's own cleanup released every edge, "
                               "reference and cached entry on the HYDRATED "
                               "path.** Read " (pr-str (rt/residue))))
                      (report! "X5" {:residue (rt/residue)})
                      (rf/destroy-frame! frame-id)
                      (is (nil? (rf/app-db-value frame-id))
                          "and the frame is destroyable — an adopted root
                           leaves nothing holding it open")
                      (rt/reset-runtime!)
                      (done))
                    reaper-horizon-ms)))))))))

(deftest x5-the-residue-reading-can-answer-false
  (testing "**the mutation proof for X5.** Two hydrated roots, one
           unmounted: the reading X5 takes must NOT be zero, because the
           other root's boundaries are still holding what they acquired.
           This is the property the pre-repair gates lacked — `release!`
           resets the runtime, so it would report zero here too, with a
           whole screen still adopted"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (do
          (same-snapshot!)
          (let [{:keys [html]} (server!)]
            (-> (hydrate-and-adopt! html)
                (.then (fn [survivor]
                         (-> (hydrate-and-adopt! html)
                             (.then (fn [doomed] [survivor doomed])))))
                (.then
                  (fn [[survivor doomed]]
                    (mount/unmount! (:handle doomed))
                    (js/setTimeout
                      (fn []
                        (is (not= nothing-retained (rt/residue))
                            "a live adopted screen is visible to the residue
                             reading")
                        (is (pos? (:cell-refs (rt/residue)))
                            "and it is visible as held references, which is the
                             quantity X5 asserts to be zero")
                        (mount/release! (:handle survivor))
                        (done))
                      reaper-horizon-ms)))
                (.catch (fn [e] (is false (str "the X5 mutation proof threw: " e))
                          (done))))))))))
