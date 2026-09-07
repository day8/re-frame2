(ns re-frame.hicasso.hooks-island-dom-cljs-test
  "THE TWO HOOKS UNDER A REAL REACT, THROUGH THE CROSSING.

  `n/use-sub` and `n/use-frame` are React hooks, so they inherit React's
  rules whole, and the characteristic way a hook is wrong is not that it
  reads the wrong value — it is that it reads the RIGHT value while
  quietly rebuilding its subscription on every render, or leaking one
  under StrictMode's double mount, or holding a destroyed frame's ops
  forever. Every one of those is invisible on screen. So no row below
  reads only the DOM.

  The island is an ORDINARY React function component — and, on two rows,
  a UIx `defui` behind the plain-function shim every crossing into UIx
  needs — mounted through `h/defhost` under `{:server :render}`, which
  is the spelling the rf2-6c12m.3 ruling leaves an author: a programmer
  who crosses into React writes React, and reaches Hicasso state through
  these two hooks.

  ## What each row is for, and the narrowing it is written against

  | row | what it establishes | the one-line narrowing it catches |
  |---|---|---|
  | [[an-islands-read-is-the-runtimes-own-and-xray-sees-it]] | the read builds a real cell under the mounted frame, and the tool tier's projection names it | reading through `subscribe-once` per render — right value, no cell, invisible to Xray |
  | [[a-write-wakes-the-island-that-reads-it-and-nothing-else]] | notification is edge-driven, not store-wide | subscribing to the generation, which repaints every island on every write |
  | [[a-re-render-that-changed-no-read-performs-no-re-subscribe]] | `subscribe` identity is stable, so React never re-subscribes — both arms | any per-render `subscribe` closure — the screen stays correct throughout |
  | [[strict-modes-double-mount-acquires-once-and-unmount-releases-exactly]] | acquire is commit-owned and teardown is its exact inverse | acquiring during render, or a cleanup that releases a successor's cells |
  | [[two-frames-are-two-cells-and-an-island-cannot-see-across]] | frames are isolated contexts on the far side of the crossing — both arms | resolving the frame anywhere but the island's own context |
  | [[a-live-with-frame-around-the-mount-does-not-reach-the-island]] | rf2-kuky.62's ONE rule on the SHIPPED path: `mount!` commits inside `flushSync`, so a `with-frame` really is live while the body runs | a dynamic-var tier — which wins only under a synchronous flush, so the same tree would resolve two different frames depending on how it was driven |
  | [[two-reads-in-one-island-are-two-cells]] | `n` calls are `n` subscriptions, React's own arithmetic | a hook that folded a component's reads into one cell and lost one of them |
  | [[use-frame-is-stable-across-renders-and-retargets-across-a-reincarnation]] | the hic-013 incarnation rule, both halves | memoising on the frame KEYWORD, which is `=` across a reincarnation |
  | [[a-transition-around-a-write-stays-tear-free-and-is-still-blocking]] | React's external-store ceiling, measured rather than advertised | a docstring that claimed transition-awareness |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## Why the readings are counts and identities, not text

  React will make a broken subscription look correct in the final DOM:
  it re-renders from the model it already has, so a torn-down-and-rebuilt
  subscription and a stable one paint the same pixels. The observables
  here are therefore the ones React cannot forge — the cell table's keys
  and reader lists, the REGISTRATION OBJECT's identity across a
  re-render, and a body-run count — which is `roots_frames_support`'s
  argument applied through the same helpers.

  ## What is NOT here

  `<Activity>`, a post-commit `<Suspense>` and an abandoned attempt are
  React's own schedule, and `activity_suspense_dom_cljs_test` and
  `kernel_commit_owns_dom_cljs_test` make those statements about the
  boundary shell. `n/use-sub` mints from the very entry cache a boundary
  body uses, so an island inherits them by construction; the ruling
  keeps the focused proof — isolation, lifecycle, teardown, Xray — and
  not a second copy of the React-feature rows.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`), and
  each row degrades there to a STATED skip rather than to a false green.
  What can be said without a fiber is said in `hooks_island_cljs_test`."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.native :as rf.hicasso.native]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.tool :as rf.hicasso.tool]
            [re-frame.test-support :as rf.test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ;; W5d mounts a root of its own, because every published door
            ;; here commits inside `flushSync` and the row's whole subject
            ;; is the SCHEDULED commit those doors deliberately do not take.
            ["react-dom/client" :as react-dom-client]))

(def ^:private alpha ::alpha)
(def ^:private beta  ::beta)

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; registration written below it is erased before the first row runs.

(rf/reg-sub ::price     (fn [db [_ sym]] (get-in db [:prices sym])))
(rf/reg-sub ::elsewhere (fn [db _] (:elsewhere db)))

(rf/reg-event ::seed (fn [_ [_ prices]] {:db {:prices prices :elsewhere 0}}))
(rf/reg-event ::set-price
              (fn [{:keys [db]} [_ sym v]] {:db (assoc-in db [:prices sym] v)}))
(rf/reg-event ::touch-elsewhere
              (fn [{:keys [db]} _] {:db (update db :elsewhere inc)}))

;; ---------------------------------------------------------------------------
;; The roster this file undertakes to reach
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  "A row that starts returning early, or a mechanism that stops being
  driven, fails the last deftest instead of quietly shrinking the
  evidence."
  #{:hooks/mounted-read
    :hooks/selective-wake
    :hooks/no-resubscribe
    :hooks/no-resubscribe-uix
    :hooks/strict-mode
    :hooks/frame-isolation
    :hooks/frame-isolation-uix
    :hooks/context-only-resolution
    :hooks/scheduled-resolution
    :hooks/two-cells
    :hooks/incarnation
    :hooks/transition})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; The island, and the two things it reports about itself
;; ---------------------------------------------------------------------------

(defonce ^:private !island-runs
  ;; Body invocations, counted where the body actually runs. The island
  ;; counterpart of `runtime/body-runs`, which counts BOUNDARY bodies and
  ;; therefore says nothing about an island.
  (atom 0))

(defonce ^:private !last-ops
  ;; What `use-frame` handed the body on its last run, held by identity.
  (atom nil))

(defn- ticker
  "One island: one subscription read, the frame-locked ops, and a piece
  of purely local React state. Raw React, the whole way down.

  The local state is not decoration — it is how a row re-renders the
  island for a reason the runtime knows nothing about, which is the only
  way to ask whether an unchanged read costs a re-subscribe."
  [^js props]
  (swap! !island-runs inc)
  (let [sym               (.-sym props)
        price             (rf.hicasso.native/use-sub [::price sym])
        ops               (rf.hicasso.native/use-frame)
        [local set-local] (react/useState 0)]
    (reset! !last-ops ops)
    ;; The class on the ROOT node is what the rows read; the `.price`,
    ;; `.local`, `.nudge` and `.commit` names are shared with the UIx arm.
    (react/createElement "div" #js {:className "island"}
      (react/createElement "b" #js {:className "price"} (str price))
      (react/createElement "i" #js {:className "local"} (str local))
      (react/createElement "button" #js {:className "nudge"
                                         :onClick   (fn [_] (set-local inc))}
        "nudge")
      (react/createElement "button" #js {:className "commit"
                                         :onClick   (fn [_]
                                                      ((:dispatch-sync ops)
                                                       [::set-price sym "from-the-island"]))}
        "commit"))))

(defui uix-ticker
  "The same island as a UIx `defui`: the same read, the same ops, the
  same local state, the same DOM."
  [{:keys [sym]}]
  (swap! !island-runs inc)
  (let [price             (rf.hicasso.native/use-sub [::price sym])
        ops               (rf.hicasso.native/use-frame)
        [local set-local] (uix/use-state 0)]
    (reset! !last-ops ops)
    (uix/$ :div {:class "island"}
           (uix/$ :b {:class "price"} (str price))
           (uix/$ :i {:class "local"} (str local))
           (uix/$ :button {:class "nudge" :on-click (fn [_] (set-local inc))} "nudge")
           (uix/$ :button {:class    "commit"
                           :on-click (fn [_] ((:dispatch-sync ops)
                                              [::set-price sym "from-the-island"]))}
                  "commit"))))

(defn- uix-arm
  "The plain React shim every crossing into UIx needs: UIx's ABI is a
  carrier object its own `uix/$` builds, so a `defui` reached through any
  other door receives props it cannot read."
  [^js props]
  (uix/$ uix-ticker {:sym (.-sym props)}))

(defn- two-reads
  "An island reading TWO keys — the shape `n/use-sub`'s docstring prices
  at two cells, and the row below measures."
  [^js props]
  (let [price (rf.hicasso.native/use-sub [::price (.-sym props)])
        other (rf.hicasso.native/use-sub [::elsewhere])]
    (react/createElement "div" #js {:className "two"}
      (react/createElement "b" #js {:className "price"} (str price))
      (react/createElement "i" #js {:className "other"} (str other)))))

;; The crossings. `{:server :render}` so the element type React reconciles
;; on is the author's own function with nothing in between — the declared
;; arm the parity floor is stated over — and so the same tree is one tree
;; on every lane.
(rf.hicasso/defhost ticker-host    ticker    {:server :render})
(rf.hicasso/defhost uix-host-arm   uix-arm   {:server :render})
(rf.hicasso/defhost two-reads-host two-reads {:server :render})
(rf.hicasso/defhost strict-mode    react/StrictMode {:server :render})

(rf.hicasso/defview host
  "Rung 3's neighbour: an ordinary boundary body crossing into a raw
  React island through the named door. The island reaches the frame
  through the context this boundary's root installed, and through
  nothing else."
  [{:keys [sym]}]
  [ticker-host {:sym sym}])

(rf.hicasso/defview uix-host
  "The same page with the UIx arm in the island's place."
  [{:keys [sym]}]
  [uix-host-arm {:sym sym}])

(rf.hicasso/defview strict-host
  "The raw island under React's own StrictMode, which double-invokes
  the body and runs mount/unmount/mount over every effect. StrictMode
  is a foreign component too, so it crosses through the same door."
  [{:keys [sym]}]
  [strict-mode [ticker-host {:sym sym}]])

(rf.hicasso/defview two-reads-page
  [{:keys [sym]}]
  [two-reads-host {:sym sym}])

(rf.hicasso/defview boundary-reader
  "A BOUNDARY reading the identical key the island reads — the control
  for the shared-entry claim."
  [{:keys [sym]}]
  [:u.boundary (str (rf.hicasso/sub [::price sym]))])

(rf.hicasso/defview shared-host
  "One boundary and one island, reading one key."
  [{:keys [sym]}]
  [:div [boundary-reader {:sym sym}] [ticker-host {:sym sym}]])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; `nil` and not the default: a dynamic-var frame left in ambient scope
     ;; would let a hook that failed to resolve its own frame answer that one
     ;; instead, and the isolation row would read a rendering difference where
     ;; the failure is a frame miss.
     :ambient-frame nil
     ;; The MAP shape, because every row here is `async`.
     :async?        true
     :init-fn       (fn []
                      (rf.hicasso.roots-frames-support/leave-act-environment!)
                      (reset! !island-runs 0)
                      (reset! !last-ops nil)
                      (rf.error-emit/clear-error-listeners!)
                      (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- price-key [frame-kw sym] [frame-kw [::price sym]])

(defn- seat!
  "Create `frame-kw` and seed it. Answers the incarnation token, so a row
  that claims a reincarnation can prove one happened."
  [frame-kw prices]
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [::seed prices]))
  (rf.frame/frame-incarnation-token frame-kw))

(defn- query-node [handle selector]
  (.querySelector ^js (:container handle) selector))
(defn- text-at [handle selector]
  (some-> (query-node handle selector) .-textContent))
(defn- click! [handle selector]
  (.click ^js (query-node handle selector))
  (rf.hicasso.impl.mount/settle!)
  nil)

(defn- readers-of [sub-key] (rf.hicasso.test.runtime/cell-readers sub-key))

(defonce ^:private !minted
  ;; Every root a row has minted through `mount-live!`, oldest first.
  ;; Emptied by `release-minted!` on the row's single trailing step.
  (atom []))

(defn- release-minted!
  "Release every root this row minted, and forget them. Rides the single
  trailing step, which BOTH arms reach, so the teardown is written once
  and runs once per path; `mount/release!` is idempotent, so a row whose
  success path already tore its root down pays nothing here — and the
  `teardown!` assertions below stay exactly where they are, because they
  are the act under test rather than a cleanup.

  The rejection arm cannot do this itself: [[mount-live!]] mints its root
  SYNCHRONOUSLY and hands it over only once the wait succeeds, so a
  rejection reaches the arm with a live root the arm has no name for."
  []
  (run! rf.hicasso.impl.mount/release! @!minted)
  (reset! !minted [])
  nil)

(defn- mount-live!
  "Mount `hiccup` under `frame-kw` and return only once `sub-key` has
  exactly `readers` readers — which is to say, once every holder in the
  tree is provably SUBSCRIBED.

  `useSyncExternalStore` calls `subscribe` from a passive effect React
  flushes after the commit, and an island that has not reached it cannot
  be notified by anything — so a row that started before it would be
  measuring an unsubscribed component and would stay green through a hook
  that never subscribed at all. The wait is on the CELL's reader list,
  which only the commit can populate.

  Enrols the root in [[!minted]] the instant it exists, because from that
  instant until [[release-minted!]] runs there is a live root on the page
  and this promise is the only thing that could ever name it."
  [frame-kw hiccup sub-key readers]
  (let [container (rf.hicasso.impl.mount/fresh-container!)
        handle    (rf.hicasso.impl.mount/root! container frame-kw hiccup)]
    (swap! !minted conj handle)
    (-> (rf.hicasso.roots-frames-support/wait-until! #(= readers (count (readers-of sub-key))))
        (.then (fn [subscribed?]
                 (when-not subscribed?
                   (throw (ex-info (str "expected " readers
                                        " subscribed reader(s) on " (pr-str sub-key))
                                   {:residue (rf.hicasso.test.runtime/residue)})))
                 handle)))))

(defn- skip! [why] (is true (str "a hook claim needs a real React DOM — " why)))

(defn- report-failure!
  "Reports a rejection against `label`; it does NOT finish the row, and it
  does not tear anything down — [[release-minted!]] on the trailing step
  owns that, for both arms and for roots this one could never name.

  `done` hands `cljs.test/run-block` a continuation that runs the WHOLE
  remainder of the run synchronously, so a `.catch` sitting downstream of
  the step that finished the row claims whatever a LATER namespace throws
  and calls `done` a SECOND time. Every chain below therefore reports here
  and finishes on a single trailing step, with nothing after it."
  [label]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | residue " (pr-str (rf.hicasso.test.runtime/residue))))
    nil))

(defn- teardown!
  "Unmount, read the census while it is still exact, then finish the
  release. The order is the load-bearing part: `mount/release!` calls
  `collector/reset-runtime!`, which empties every table BY FIAT, so a
  census taken after it reads zeros whether the teardown released
  anything or not."
  [handle]
  (rf.hicasso.roots-frames-support/teardown-census! handle))

;; ---------------------------------------------------------------------------
;; W1. The read is the runtime's own, and the tool tier can see it
;; ---------------------------------------------------------------------------

(deftest an-islands-read-is-the-runtimes-own-and-xray-sees-it
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [shared-host {:sym "AAPL"}] k 2)
            (.then
              (fn [handle]
                (testing "the island painted the value, and so did the boundary
                          beside it — the same key, read through the two
                          different doors"
                  (is (= "191" (text-at handle ".price")))
                  (is (= "191" (text-at handle ".boundary"))))

                (testing "and there is exactly ONE cell, under the frame the
                          root installed, with TWO readers. Narrowing caught: a
                          hook that read through `subscribe-once` per render —
                          the paint above is identical under it and there would
                          be one reader here, or none"
                  (is (= #{k} (rf.hicasso.roots-frames-support/cell-keys)))
                  (is (= 2 (count (readers-of k)))))

                (testing "and TWO read-set entries exist, which is the honest
                          count: the one-key entry the boundary and the island
                          SHARE, and the empty one `shared-host` claimed — a
                          body that reads nothing still mints and claims an
                          entry, and that is what makes the tool tier's census
                          complete"
                  (is (= 2 (:entries (rf.hicasso.test.runtime/residue)))))

                (testing "and `re-frame.hicasso.tool`'s mounted-boundary
                          projection — hic-023's, the one Xray consumes — NAMES
                          the read, without knowing that hooks exist. That is
                          the whole return on routing the hook through the
                          runtime's tables instead of beside them.

                          `:read-orders 1` is the discriminating field.
                          Narrowing caught: a private entry cache for hooks —
                          the cell, the two readers and `:instances` are all
                          unchanged under it, because two entries with equal
                          key sets group into one row; what doubles is the
                          number of entries folded into that row"
                  (let [projection (rf.hicasso.tool/read-mounted-boundaries)
                        row        (first (filter (fn [r]
                                                    (some #(= ::price (:sub-id %))
                                                          (:reads r)))
                                                  (:boundaries projection)))]
                    (is (= :mounted-boundaries (:read projection)))
                    (is (some? row) "the projection names no read of ::price")
                    (is (= alpha (:frame row)))
                    (is (= alpha (:frame-id (first (:reads row)))))
                    (is (= 2 (:instances row))
                        "one edge set, two holders — the runtime keys a
                         boundary by what it reads, and an island reading what
                         a boundary reads is indistinguishable to it")
                    (is (= 1 (:read-orders row)))))

                (exercised! :hooks/mounted-read)
                (testing "teardown releases every membership the mount took"
                  (is (= rf.hicasso.roots-frames-support/released (teardown! handle))))
                nil))
            (.catch (report-failure! "W1 mounted read"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W2. A write wakes its readers and nobody else
;; ---------------------------------------------------------------------------

(deftest a-write-wakes-the-island-that-reads-it-and-nothing-else
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (testing "a write to the key the island reads repaints it"
                  (rf.hicasso.impl.mount/dispatch! handle [::set-price "AAPL" 204])
                  (is (= "204" (text-at handle ".price"))))

                (let [runs (deref !island-runs)]
                  (testing "a write to a key NOTHING in the tree reads runs no
                            island body at all. Narrowing caught: a hook
                            subscribed to the generation, or to the store as a
                            whole — it repaints correctly on the row above and
                            wakes on every write in the application here, which
                            is the cost the whole cell table exists to avoid"
                    (rf.hicasso.impl.mount/dispatch! handle [::touch-elsewhere])
                    (is (= runs (deref !island-runs)))
                    (is (= "204" (text-at handle ".price")))))

                (testing "and the island's own dispatch — `:dispatch-sync` off
                          the ops map, fired from a real click through React's
                          event system — moves the same app-db the rest of the
                          page reads"
                  (click! handle ".commit")
                  (is (= "from-the-island" (text-at handle ".price")))
                  (is (= "from-the-island"
                         (rf/with-frame alpha @(rf/subscribe [::price "AAPL"])))))

                (exercised! :hooks/selective-wake)
                (is (= rf.hicasso.roots-frames-support/released (teardown! handle)))
                nil))
            (.catch (report-failure! "W2 selective wake"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W3. THE characteristic failure: a re-render that changed no read
;; ---------------------------------------------------------------------------

(defn- no-resubscribe-row!
  "Mount `view` under `alpha`, drive three re-renders from the island's
  OWN React state and one from a write, and answer a promise resolved
  once the registration and the residue have been read on both.

  This is the row the design is FOR. A hook that mints its `subscribe`
  closure per render is correct on screen forever: React tears the
  subscription down and rebuilds it after every single re-render,
  releasing and re-acquiring the cell, and the value is right the whole
  time. Only the registration's IDENTITY says so."
  [view mechanism]
  (let [k (price-key alpha "AAPL")]
    (seat! alpha {"AAPL" 191})
    (-> (mount-live! alpha [view {:sym "AAPL"}] k 1)
        (.then
          (fn [handle]
            (let [reg-at-mount (first (readers-of k))
                  runs         (deref !island-runs)
                  residue      (rf.hicasso.test.runtime/residue)]

              (testing "three re-renders driven by the island's OWN React
                        state — a state bump behind a real click, which the
                        runtime knows nothing about and cannot have moved a
                        read"
                (click! handle ".nudge")
                (click! handle ".nudge")
                (click! handle ".nudge")
                (is (= "3" (text-at handle ".local")))
                (is (= 3 (- (deref !island-runs) runs))
                    "the body really did run three more times, which is
                     what makes the readings below a test of anything"))

              (testing "and the registration React holds is the IDENTICAL
                        object. Narrowing caught: an inline `subscribe`
                        closure, or a `useCallback` keyed on a CLJS vector
                        (which is never `Object.is`-stable, so its deps
                        array rebuilds every render) — either makes this a
                        different object three times over, and nothing on
                        screen changes"
                (is (true? (identical? reg-at-mount (first (readers-of k))))
                    "React holds a DIFFERENT registration, so the
                     subscription was torn down and rebuilt"))

              (testing "so nothing was released and re-acquired: one cell,
                        one membership, one boundary, one edge, one entry —
                        the numbers the mount established, unmoved"
                (is (= residue (rf.hicasso.test.runtime/residue))))

              (testing "the same holds across a re-render the RUNTIME
                        caused. A write moves the value, React re-renders
                        the island, and the read set is what it was — so
                        React is never handed a new `subscribe` and the
                        commit does no work"
                (rf.hicasso.impl.mount/dispatch! handle [::set-price "AAPL" 204])
                (is (= "204" (text-at handle ".price")))
                (is (true? (identical? reg-at-mount (first (readers-of k))))
                    "React holds a DIFFERENT registration, so the
                     subscription was torn down and rebuilt")
                (is (= residue (rf.hicasso.test.runtime/residue))))

              (exercised! mechanism)
              (is (= rf.hicasso.roots-frames-support/released (teardown! handle)))
              nil))))))

(deftest a-re-render-that-changed-no-read-performs-no-re-subscribe
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (no-resubscribe-row! host :hooks/no-resubscribe)
          (.catch (report-failure! "W3 no re-subscribe"))
          (.then (fn [_] (release-minted!) (done)))))))

(deftest a-uix-island-re-rendered-for-its-own-reasons-performs-no-re-subscribe
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (no-resubscribe-row! uix-host :hooks/no-resubscribe-uix)
          (.catch (report-failure! "W3 no re-subscribe, UIx arm"))
          (.then (fn [_] (release-minted!) (done)))))))

;; ---------------------------------------------------------------------------
;; W4. StrictMode's double mount, and exact teardown
;; ---------------------------------------------------------------------------

(deftest strict-modes-double-mount-acquires-once-and-unmount-releases-exactly
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [strict-host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (testing "StrictMode really is engaged — the body ran more than
                          once for one mount, which is the premise the rest of
                          this row rests on and which a production React build
                          would silently remove"
                  (is (< 1 (deref !island-runs))
                      (str "the island body ran " (deref !island-runs)
                           " time(s); StrictMode double-invokes render")))

                (testing "and after mount → unmount → mount over every effect,
                          there is exactly ONE reader on ONE cell. Narrowing
                          caught: acquiring during the render — the ownership
                          state machine's one prohibition — which under a
                          double-invoked render acquires twice and reads 2 here
                          while painting perfectly"
                  (is (= #{k} (rf.hicasso.roots-frames-support/cell-keys)))
                  (is (= 1 (count (readers-of k))))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (dissoc (rf.hicasso.test.runtime/residue) :entries))))

                (testing "the island is live, not merely tidy — a subscription
                          torn down by StrictMode's first cleanup and never
                          rebuilt would satisfy every count above and repaint
                          nothing"
                  (rf.hicasso.impl.mount/dispatch! handle [::set-price "AAPL" 204])
                  (is (= "204" (text-at handle ".price"))))

                (testing "and unmount releases EXACTLY what mount acquired,
                          read between the unmount and the reset. Narrowing
                          caught: a cleanup that released by key rather than by
                          the cells it acquired — after a reap and rebuild it
                          would release a successor's and leave its own"
                  (is (= rf.hicasso.roots-frames-support/released (teardown! handle))))

                (exercised! :hooks/strict-mode)
                (-> (rf.hicasso.roots-frames-support/quiesced!)
                    (.then (fn [_]
                             (testing "past the reapers the tables are empty"
                               (is (= {:cells 0 :cell-refs 0 :boundaries 0
                                       :edges 0 :entries 0}
                                      (rf.hicasso.test.runtime/residue))))
                             nil)))))
            (.catch (report-failure! "W4 StrictMode"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W5. Frames are isolated contexts — on the far side of the crossing too
;; ---------------------------------------------------------------------------

(defn- isolation-row!
  "One island source, two frames, one query — and a promise resolved once
  both pages have been read."
  [view mechanism]
  (let [ka (price-key alpha "AAPL")
        kb (price-key beta "AAPL")]
    (seat! alpha {"AAPL" "alpha-price"})
    (seat! beta  {"AAPL" "beta-price"})
    (-> (mount-live! alpha [view {:sym "AAPL"}] ka 1)
        (.then (fn [a] (.then (mount-live! beta [view {:sym "AAPL"}] kb 1)
                              (fn [b] #js [a b]))))
        (.then
          (fn [^js pair]
            (let [a (aget pair 0)
                  b (aget pair 1)]
              (testing "one island source, two frames, one query — TWO
                        cells, differing only in their frame, one reader
                        each. Narrowing caught: any frame resolution that
                        is not the island's own React context — a module
                        global, a dynamic var, a `:rf/default` floor —
                        every one of which produces ONE key here and two
                        visually plausible subtrees"
                (is (= #{ka kb} (rf.hicasso.roots-frames-support/cell-keys)))
                (is (= 1 (count (readers-of ka))))
                (is (= 1 (count (readers-of kb)))))

              (testing "and each island painted its own frame's value"
                (is (= "alpha-price" (text-at a ".price")))
                (is (= "beta-price"  (text-at b ".price"))))

              (testing "a write in one frame moves that frame's island and
                        leaves the other exactly where it was — the
                        isolation claim as a REPAINT rather than as a count"
                (rf.hicasso.impl.mount/dispatch! a [::set-price "AAPL" "alpha-moved"])
                (is (= "alpha-moved" (text-at a ".price")))
                (is (= "beta-price"  (text-at b ".price"))))

              (exercised! mechanism)
              (rf.hicasso.impl.mount/unmount! a)
              (is (= rf.hicasso.roots-frames-support/released (teardown! b)))
              (rf.hicasso.impl.mount/release! (assoc a :root nil))
              nil))))))

(deftest two-frames-are-two-cells-and-an-island-cannot-see-across
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (isolation-row! host :hooks/frame-isolation)
          (.catch (report-failure! "W5 frame isolation"))
          (.then (fn [_] (release-minted!) (done)))))))

(deftest a-uix-island-cannot-see-across-frames-either
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (isolation-row! uix-host :hooks/frame-isolation-uix)
          (.catch (report-failure! "W5 frame isolation, UIx arm"))
          (.then (fn [_] (release-minted!) (done)))))))

;; ---------------------------------------------------------------------------
;; W5c. The one hook frame-resolution rule, on the SHIPPED synchronous path
;; ---------------------------------------------------------------------------

(defn- dynamic-scope-row!
  "rf2-kuky.62 — a hook resolves from the React context the boundary above
  it installed and from nothing else, asserted where a dynamic-var tier
  could actually have won.

  WHY THIS ROW IS IN THE DOM LANE AT ALL, given the node lane already
  states the rule (`hooks_island_cljs_test`, the `with-frame` row): this is
  the SHIPPED path. `mount/root!` commits inside `flushSync`
  (`impl/mount.cljs`), so an island body runs on the caller's own stack in
  production, not only under a server renderer or an `act()`. A rule that
  held for `renderToStaticMarkup` and not here would be a rule about the
  test harness.

  The seeding is the discriminator: `alpha` and `beta` answer the same
  query with different values, `beta` is the frame the boundary names, and
  `alpha` is the one a live `with-frame` names around the mount. One cell,
  keyed to beta, is the reading — a dynamic-var tier would key it to alpha,
  and both trees would look perfectly plausible on screen."
  [view mechanism]
  (let [ka (price-key alpha "AAPL")
        kb (price-key beta  "AAPL")]
    (seat! alpha {"AAPL" "alpha-price"})
    (seat! beta  {"AAPL" "beta-price"})
    (-> (rf/with-frame alpha
          ;; The binding is live across `mount-live!`'s SYNCHRONOUS half —
          ;; the container, the root and the flushSync commit — which is
          ;; exactly the window in which the island body runs.
          (mount-live! beta [view {:sym "AAPL"}] kb 1))
        (.then
          (fn [b]
            (testing "the island read the BOUNDARY's frame, not the frame the
                      live with-frame named on the stack that mounted it"
              (is (= "beta-price" (text-at b ".price")))
              (is (= #{kb} (rf.hicasso.roots-frames-support/cell-keys))
                  "and it built exactly one cell, keyed to the boundary's frame —
                   a dynamic-var tier would have keyed it to alpha")
              (is (empty? (readers-of ka))
                  "alpha has no reader at all: nothing in this tree ever
                   resolved to it"))

            (testing "`use-frame`'s bundle is locked to the same frame, and
                      stays so once the scope has unwound — the ops map is a
                      HOLD, so a dispatch through it later is still beta's"
              (is (= beta (:frame @!last-ops)))
              ((:dispatch-sync @!last-ops) [::set-price "AAPL" "beta-moved"])
              (rf.hicasso.impl.mount/settle!)
              (is (= "beta-moved" (text-at b ".price")))
              (is (= "alpha-price" (get-in (rf/app-db-value alpha) [:prices "AAPL"]))
                  "and alpha — the frame the with-frame named — was never
                   written to"))

            (exercised! mechanism)
            (is (= rf.hicasso.roots-frames-support/released (teardown! b)))
            nil)))))

(deftest a-live-with-frame-around-the-mount-does-not-reach-the-island
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (-> (dynamic-scope-row! host :hooks/context-only-resolution)
          (.catch (report-failure! "W5c hook frame resolution is context-only"))
          (.then (fn [_] (release-minted!) (done)))))))

;; ---------------------------------------------------------------------------
;; W5d. The same rule on the SCHEDULED path — the mode no other row can see
;; ---------------------------------------------------------------------------
;;
;; W5c above and the node lane's `with-frame` row both render the island on
;; the calling stack — `flushSync` there, `renderToStaticMarkup` here — which
;; is the only shape in which a dynamic-var tier could ever WIN, and so the
;; right harness for an adversarial precedence contest. It is the wrong
;; harness for the other half of what rf2-kuky.61 ruled: *the same component
;; tree resolves the same frame under either scheduling mode*. The merged-PR
;; audit of #9427 found that half unwitnessed on both hook families.
;;
;; This row is it, for the NATIVE pair. The island is mounted on a plain
;; concurrent root with the act environment off, so `render` SCHEDULES and
;; returns; the body runs a host task later, by which time the `with-frame`
;; around the mount has unwound. `!island-runs` is the recording: it is still
;; zero on the line after `render` returns, inside the scope, and positive
;; once the row has waited — so everything read afterwards was read outside
;; the bound extent.
;;
;; WHY THE ROW INSTALLS A PERSISTENT AMBIENT FRAME. A `with-frame` cannot
;; reach a scheduled body at all — that is the point — so on its own it
;; leaves the body with NO dynamic frame, and both legs below would pass
;; under a dynamic-var-first rule just as readily. A real ambient scope on
;; this schedule is the persistent kind (`set!` on the root var, which is
;; precisely what `make-reset-runtime-fixture`'s async form does, for the
;; same reason: its `:before` returns long before an async body resumes).
;; So the row installs one naming `alpha`, and it is the adversary — a hook
;; that consulted the dynamic tier would key its cell to alpha in leg 1 and
;; would not refuse at all in leg 2.

(defn- scheduled-root!
  "A concurrent root whose UNCAUGHT render errors land in `sink`.

  Leg 2's island throws out of its own hook, and a SCHEDULED render has no
  caller's stack to throw on: React surfaces it on its own task, where the
  page — and so the browser runner — would see an unhandled error. React
  19's `createRoot` option is the supported interception point, and here it
  is also the READING, because `impl/error/fail!` throws rather than
  emitting, so there is no listener axis to read the refusal off."
  [container sink]
  (react-dom-client/createRoot
    container
    #js {:onUncaughtError (fn [e _info] (when (nil? @sink) (reset! sink e)))}))

(defn- scheduled-resolution-row!
  "Both legs, on one persistent ambient scope. `roots` and `containers`
  collect what the row mints so the single trailing step can tear it down
  on either arm — this row mints its own roots, so `release-minted!` (which
  knows only about `mount-live!`'s handles) cannot see them."
  [roots containers]
  (let [ka  (price-key alpha "AAPL")
        kb  (price-key beta  "AAPL")
        new-root! (fn [sink]
                    (let [c (rf.hicasso.impl.mount/fresh-container!)
                          r (scheduled-root! c sink)]
                      (swap! containers conj c)
                      (swap! roots conj r)
                      [c r]))]
    (seat! alpha {"AAPL" "alpha-price"})
    (seat! beta  {"AAPL" "beta-price"})
    ;; THE ADVERSARY, installed after both frames exist so neither
    ;; `make-frame` ran under a synthetic ambient scope.
    (set! rf.frame/*current-frame* alpha)
    (let [[container root] (new-root! (atom nil))
          runs-in-extent   (atom nil)]
      (reset! !island-runs 0)
      (reset! !last-ops nil)
      (rf/with-frame alpha
        (.render ^js root
          (rf.hicasso.impl.mount/provider beta (react/createElement ticker #js {:sym "AAPL"})))
        (reset! runs-in-extent @!island-runs))
      (-> (rf.hicasso.roots-frames-support/wait-until! #(= 1 (count (readers-of kb))))
          (.then
            (fn [subscribed?]
              (testing "the render really did run LATE: the island body had not
                        run when `render` returned inside the `with-frame`, and
                        it had by the time the row read anything. Without this
                        leg the row is indistinguishable from W5c"
                (is (zero? @runs-in-extent)
                    (str "React SCHEDULED the mount rather than committing it on "
                         "the calling stack; body runs inside the scope: "
                         @runs-in-extent))
                (is (pos? @!island-runs))
                (is (= alpha rf.frame/*current-frame*)
                    "and the dynamic tier is not empty while it runs — it names
                     alpha, so a hook that consulted it had an answer available"))

              (is (true? subscribed?)
                  (str "the island subscribed under the BOUNDARY's frame. Cell keys "
                       (pr-str (rf.hicasso.roots-frames-support/cell-keys))
                       ", residue " (pr-str (rf.hicasso.test.runtime/residue))))

              (testing "one cell, keyed to the frame the boundary named — the
                        same reading W5c takes, now on the schedule a consumer's
                        own concurrent root actually uses"
                (is (= #{kb} (rf.hicasso.roots-frames-support/cell-keys)))
                (is (= 1 (count (readers-of kb))))
                (is (empty? (readers-of ka))
                    "alpha has no reader at all, although it is the frame the
                     persistent ambient scope names")
                (is (= "beta-price"
                       (some-> (.querySelector ^js container ".price") .-textContent))))

              (testing "`use-frame` is locked to the same frame on this schedule,
                        and a dispatch through its bundle moves that frame only"
                (is (= beta (:frame @!last-ops)))
                ((:dispatch-sync @!last-ops) [::set-price "AAPL" "beta-moved"])
                (rf.hicasso.impl.mount/settle!)
                (is (= "beta-moved" (get-in (rf/app-db-value beta) [:prices "AAPL"])))
                (is (= "alpha-price" (get-in (rf/app-db-value alpha) [:prices "AAPL"]))))

              ;; Leg 1's tree goes away before leg 2 mints its own, so the
              ;; cell census leg 2 reads is about leg 2.
              (.unmount ^js root)
              nil))))))

(defn- scheduled-refusal-leg!
  "Leg 2: the same schedule with NO boundary above the island. The
  persistent ambient scope still names `alpha`, and the hook still
  refuses — which is what separates *context ONLY* from *context FIRST*
  on the scheduled path, exactly as the node lane's frameless row does on
  the synchronous one."
  [roots containers]
  (let [ka   (price-key alpha "AAPL")
        sink (atom nil)
        c    (rf.hicasso.impl.mount/fresh-container!)
        r    (scheduled-root! c sink)
        runs-in-extent (atom nil)]
    (swap! containers conj c)
    (swap! roots conj r)
    (reset! !island-runs 0)
    (rf/with-frame alpha
      (.render ^js r (react/createElement ticker #js {:sym "AAPL"}))
      (reset! runs-in-extent @!island-runs))
    (-> (rf.hicasso.roots-frames-support/wait-until! #(some? @sink))
        (.then
          (fn [refused?]
            (is (zero? @runs-in-extent)
                "scheduled, not committed: nothing ran inside the bound extent")
            (is (true? refused?)
                (str "the scheduled render refused. Cell keys "
                     (pr-str (rf.hicasso.roots-frames-support/cell-keys))
                     ", island body runs " @!island-runs))
            (let [data (ex-data @sink)]
              (testing "and it refused with the ruled error, named by the NATIVE
                        hook — a dynamic-var tier would have answered alpha here
                        instead, on a schedule where nobody would see it"
                (is (= :rf.error/no-frame-context (:rf.error/id data))
                    (str "saw " (pr-str data)))
                (is (= 're-frame.hicasso.native/use-sub (:where data)))))
            (is (not (contains? (rf.hicasso.roots-frames-support/cell-keys) ka))
                "and nothing was built under the frame the ambient scope named")
            (exercised! :hooks/scheduled-resolution)
            nil)))))

(deftest a-scheduled-render-that-outlives-the-scope-still-reads-the-boundarys-frame
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [ambient-was rf.frame/*current-frame*
            roots       (atom [])
            containers  (atom [])]
        (-> (scheduled-resolution-row! roots containers)
            (.then (fn [_] (scheduled-refusal-leg! roots containers)))
            (.catch (report-failure! "W5d scheduled render is context-only"))
            (.then (fn [_]
                     (run! (fn [r] (try (.unmount ^js r) (catch :default _ nil))) @roots)
                     (run! (fn [c] (try (.remove ^js c) (catch :default _ nil))) @containers)
                     (set! rf.frame/*current-frame* ambient-was)
                     (release-minted!)
                     (done))))))))

;; ---------------------------------------------------------------------------
;; W5b. Two calls are two cells
;; ---------------------------------------------------------------------------

(deftest two-reads-in-one-island-are-two-cells
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [ka (price-key alpha "AAPL")
            ke [alpha [::elsewhere]]]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [two-reads-page {:sym "AAPL"}] ka 1)
            (.then
              (fn [handle]
                (testing "two `n/use-sub` calls in one component are two
                          subscriptions: two cells, one reader slot on each,
                          where a `defview` body's several `h/sub` reads are
                          ONE. That is React's arithmetic — a store
                          subscription is a hook, so `n` reads cost `n` of
                          them. Narrowing caught: a hook that folded a
                          component's reads into one entry and dropped one"
                  (is (= #{ka ke} (rf.hicasso.roots-frames-support/cell-keys)))
                  (is (= 1 (count (readers-of ka))))
                  (is (= 1 (count (readers-of ke))))
                  (is (= "191" (text-at handle ".price")))
                  (is (= "0" (text-at handle ".other"))))

                (testing "and both are live: a write to either key repaints
                          the island through its own cell"
                  (rf.hicasso.impl.mount/dispatch! handle [::touch-elsewhere])
                  (is (= "1" (text-at handle ".other")))
                  (is (= "191" (text-at handle ".price")))
                  (rf.hicasso.impl.mount/dispatch! handle [::set-price "AAPL" 204])
                  (is (= "204" (text-at handle ".price")))
                  (is (= 1 (count (readers-of ka))))
                  (is (= 1 (count (readers-of ke)))))

                (exercised! :hooks/two-cells)
                (is (= rf.hicasso.roots-frames-support/released (teardown! handle)))
                nil))
            (.catch (report-failure! "W5b two cells"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W6. `use-frame`, both halves of the incarnation rule
;; ---------------------------------------------------------------------------

(defn- destroyed-frame-complaints
  "Collect the always-on `:rf.error/frame-destroyed` corpus records raised
  while `thunk` runs. Axis 1 (`error-emit`) rather than the dev trace,
  because the refusal this row is about is always on."
  [thunk]
  (let [seen (atom [])
        k    ::destroyed-listener]
    (rf.error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try (thunk) @seen
         (finally (rf.error-emit/unregister-error-listener! k)))))

(deftest use-frame-is-stable-across-renders-and-retargets-across-a-reincarnation
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" "predecessor"})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (let [ops-1   (deref !last-ops)
                      token-1 (rf.frame/frame-incarnation-token alpha)]

                  (testing "reference stability: three re-renders that changed
                            no frame hand back the IDENTICAL map, which is what
                            makes it safe in `useEffect` deps and as a memoised
                            child's prop"
                    (click! handle ".nudge")
                    (click! handle ".nudge")
                    (click! handle ".nudge")
                    (is (identical? ops-1 (deref !last-ops))))

                  ;; The reincarnation. Same public id, different object — and
                  ;; the runtime learns of it through the cell the frame's
                  ;; teardown disposed, which is what re-renders the island.
                  (rf/destroy-frame! alpha)
                  (let [token-2 (seat! alpha {"AAPL" "successor"})]
                    (testing "the premise: a different incarnation under one
                              public id"
                      (is (not (identical? token-1 token-2))))

                    (-> (rf.hicasso.roots-frames-support/wait-until! #(= "successor" (text-at handle ".price")))
                        (.then
                          (fn [corrected?]
                            (is (true? corrected?)
                                (str "the island never observed the successor; "
                                     "it reads " (pr-str (text-at handle ".price"))))

                            (let [ops-2 (deref !last-ops)]
                              (testing "the ops map RETARGETED. Narrowing
                                        caught, and it is a shipping
                                        implementation: a `useRef` memo keyed
                                        on the resolved frame by `=` — the UIx
                                        adapter's `use-frame` — passes the
                                        stability block above and fails here,
                                        because a frame keyword is `=` across a
                                        reincarnation and the hook would hand
                                        out the destroyed incarnation's bundle
                                        for the rest of the mount"
                                (is (not (identical? ops-1 ops-2)))
                                (is (= alpha (:frame ops-2))))

                              (testing "and the fresh bundle WRITES the
                                        successor"
                                ((:dispatch-sync ops-2) [::set-price "AAPL" "live"])
                                (is (= "live" (rf/with-frame alpha
                                                @(rf/subscribe [::price "AAPL"])))))

                              (testing "while the bundle a callback captured
                                        before the transition refuses — loudly,
                                        once, through the always-on corpus —
                                        rather than silently writing whoever
                                        occupies the address now. That silent
                                        write is the failure rf2-hic-013
                                        repaired, and it is the reason the
                                        memo is keyed on an incarnation"
                                (let [seen (destroyed-frame-complaints
                                             #((:dispatch-sync ops-1)
                                               [::set-price "AAPL" "from-the-dead"]))]
                                  (is (= 1 (count seen)))
                                  (is (= "live" (rf/with-frame alpha
                                                  @(rf/subscribe [::price "AAPL"])))))))

                            (exercised! :hooks/incarnation)
                            (is (= rf.hicasso.roots-frames-support/released (teardown! handle)))
                            nil))
                        ;; The inner chain finishes NOTHING and tears nothing
                        ;; down — it is returned into the outer one below.
                        (.catch (report-failure! "W6 incarnation")))))))
            (.catch (report-failure! "W6 incarnation"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; W7. The external-store ceiling, measured
;; ---------------------------------------------------------------------------

(deftest a-transition-around-a-write-stays-tear-free-and-is-still-blocking
  ;; React's `useSyncExternalStore` documentation is explicit that an
  ;; external store's mutations cannot be non-blocking Transition updates
  ;; and that React may restart such a transition as blocking. The lane
  ;; note (`lanes/react-compatibility-notes.md`) rules that Hicasso TEST
  ;; tear-freedom under `startTransition` and DOCUMENT the blocking
  ;; fallback honestly rather than advertise transition-awareness. This is
  ;; the test half; `n/use-sub`'s docstring is the documented half.
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (react/startTransition
                  (fn [] (rf.hicasso.impl.collector/dispatch! alpha [::set-price "AAPL" 204])))
                (rf.hicasso.impl.mount/settle!)
                (testing "the paint agrees with app-db — no tear. A hook that
                          returned a value captured independently of the epoch
                          `getSnapshot` reports could disagree here, and React
                          would have no way to know"
                  (is (= "204" (text-at handle ".price")))
                  (is (= 204 (rf/with-frame alpha @(rf/subscribe [::price "AAPL"])))))

                (testing "and the subscription was not rebuilt by the
                          transition"
                  (is (= 1 (count (readers-of k))))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (dissoc (rf.hicasso.test.runtime/residue) :entries))))

                (exercised! :hooks/transition)
                (is (= rf.hicasso.roots-frames-support/released (teardown! handle)))
                nil))
            (.catch (report-failure! "W7 transition"))
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test reaches none of the mechanisms")
    (is (= declared-population (deref !exercised))
        (str "declared but never reached: "
             (pr-str (set/difference declared-population (deref !exercised)))))))
