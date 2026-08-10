(ns re-frame.hicasso.native-hooks-dom-cljs-test
  "THE TWO NATIVE HOOKS UNDER A REAL REACT (rf2-hic-031).

  `n/use-sub` and `n/use-frame` are React hooks, so they inherit React's
  rules whole, and the characteristic way a hook is wrong is not that it
  reads the wrong value — it is that it reads the RIGHT value while
  quietly rebuilding its subscription on every render, or leaking one
  under StrictMode's double mount, or holding a destroyed frame's ops
  forever. Every one of those is invisible on screen. So no row below
  reads only the DOM.

  ## What each row is for, and the narrowing it is written against

  | row | what it establishes | the one-line narrowing it catches |
  |---|---|---|
  | [[an-islands-read-is-the-runtimes-own-and-xray-sees-it]] | the read builds a real cell under the mounted frame, and the tool tier's projection names it | reading through `subscribe-once` per render — right value, no cell, invisible to Xray |
  | [[a-write-wakes-the-island-that-reads-it-and-nothing-else]] | notification is edge-driven, not store-wide | subscribing to the generation, which repaints every island on every write |
  | [[a-re-render-that-changed-no-read-performs-no-re-subscribe]] | `subscribe` identity is stable, so React never re-subscribes | any per-render `subscribe` closure — the screen stays correct throughout |
  | [[strict-modes-double-mount-acquires-once-and-unmount-releases-exactly]] | acquire is commit-owned and teardown is its exact inverse | acquiring during render, or a cleanup that releases a successor's cells |
  | [[two-frames-are-two-cells-and-an-island-cannot-see-across]] | frames are isolated contexts, on the native side of the fence too | resolving the frame anywhere but the island's own context |
  | [[use-frame-is-stable-across-renders-and-retargets-across-a-reincarnation]] | the hic-013 incarnation rule, both halves | memoising on the frame KEYWORD, which is `=` across a reincarnation |
  | [[a-transition-around-a-write-stays-tear-free-and-is-still-blocking]] | React's external-store ceiling, measured rather than advertised | a docstring that claimed transition-awareness |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## Why the readings are counts and identities, not text

  React will make a broken subscription look correct in the final DOM:
  it re-renders from the model it already has, so a torn-down-and-rebuilt
  subscription and a stable one paint the same pixels. The observables
  here are therefore the ones React cannot forge — the cell table's keys
  and reader lists, the REGISTRATION OBJECT's identity across a
  re-render, and a body-run count — which is
  `roots_frames_support`'s argument applied to a tier it was not written
  for, through the same helpers.

  ## Browser lane

  Every row needs a real document and a real React DOM. `:node-test`
  compiles this namespace too (`cljs-test$` matches `-dom-cljs-test`), and
  each row degrades there to a STATED skip rather than to a false green.
  What can be said without a fiber is said in `native_hooks_cljs_test`."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as support]
            [re-frame.hicasso.tool :as tool]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

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
    :hooks/strict-mode
    :hooks/frame-isolation
    :hooks/incarnation
    :hooks/transition})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; The island, and the two things it reports about itself
;; ---------------------------------------------------------------------------

(defonce ^:private !island-runs
  ;; Body invocations, counted where the body actually runs. The native
  ;; counterpart of `collector/body-runs`, which counts BOUNDARY bodies and
  ;; therefore says nothing about an island.
  (atom 0))

(defonce ^:private !last-ops
  ;; What `use-frame` handed the body on its last run, held by identity.
  (atom nil))

(n/defcomponent ticker
  "One island: one subscription read, the frame-locked ops, and a piece
  of purely local React state.

  The local state is not decoration — it is how a row re-renders the
  island for a reason the runtime knows nothing about, which is the only
  way to ask whether an unchanged read costs a re-subscribe."
  [^js props]
  (swap! !island-runs inc)
  (let [sym               (.-sym props)
        price             (n/use-sub [::price sym])
        ops               (n/use-frame)
        [local set-local] (react/useState 0)]
    (reset! !last-ops ops)
    (n/$ :div nil
         (n/$ :b {:class "price"} (str price))
         (n/$ :i {:class "local"} (str local))
         (n/$ :button {:class    "nudge"
                       :on-click (fn [_] (set-local inc))}
              "nudge")
         (n/$ :button {:class    "commit"
                       :on-click (fn [_]
                                   ((:dispatch-sync ops)
                                    [::set-price sym "from-the-island"]))}
              "commit"))))

(h/defview host
  "Rung 3: an ordinary boundary body returning a native element. The
  island reaches the frame through the context this boundary's root
  installed, and through nothing else."
  [{:keys [sym]}]
  (n/$ ticker {:sym sym}))

(h/defview strict-host
  "The same island under React's own StrictMode, which double-invokes
  the body and runs mount/unmount/mount over every effect."
  [{:keys [sym]}]
  (n/$ react/StrictMode nil (n/$ ticker {:sym sym})))

(h/defview boundary-reader
  "A BOUNDARY reading the identical key the island reads — the control
  for the shared-entry claim."
  [{:keys [sym]}]
  [:u.boundary (str (h/sub [::price sym]))])

(h/defview shared-host
  "One boundary and one island, reading one key."
  [{:keys [sym]}]
  [:div [boundary-reader {:sym sym}] (n/$ ticker {:sym sym})])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `nil` and not the default: a dynamic-var frame left in ambient scope
     ;; would let a hook that failed to resolve its own frame answer that one
     ;; instead, and the isolation row would read a rendering difference where
     ;; the failure is a frame miss.
     :ambient-frame nil
     ;; The MAP shape, because every row here is `async`.
     :async?        true
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (reset! !island-runs 0)
                      (reset! !last-ops nil)
                      (error-emit/clear-error-listeners!)
                      (collector/reset-runtime!))}))

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
  (frame/frame-incarnation-token frame-kw))

(defn- at [handle sel] (.querySelector ^js (:container handle) sel))
(defn- text-at [handle sel] (some-> (at handle sel) .-textContent))
(defn- click! [handle sel] (.click ^js (at handle sel)) (mount/settle!) nil)

(defn- readers-of [sub-key] (inventory/cell-readers sub-key))

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

  `readers` is stated by the caller rather than assumed to be one,
  because a tree may hold the key more than once — the shared-entry row
  mounts a boundary and an island on one key deliberately — and a wait
  that stopped at the first arrival would let the second commit land
  underneath the assertions."
  [frame-kw hiccup sub-key readers]
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-kw hiccup)]
    (-> (support/wait-until! #(= readers (count (readers-of sub-key))))
        (.then (fn [subscribed?]
                 (when-not subscribed?
                   (throw (ex-info (str "expected " readers
                                        " subscribed reader(s) on " (pr-str sub-key))
                                   {:residue (inventory/residue)})))
                 handle)))))

(defn- skip! [why] (is true (str "a native-hook claim needs a real React DOM — " why)))

(defn- fail-and-finish!
  [done label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | residue " (pr-str (inventory/residue))))
    (when handle (mount/release! handle))
    (done)))

(defn- teardown!
  "Unmount, read the census while it is still exact, then finish the
  release. The order is the load-bearing part: `mount/release!` calls
  `collector/reset-runtime!`, which empties every table BY FIAT, so a
  census taken after it reads zeros whether the teardown released
  anything or not."
  [handle]
  (support/teardown-census! handle))

;; ---------------------------------------------------------------------------
;; W1. The read is the runtime's own, and the tool tier can see it
;; ---------------------------------------------------------------------------

(deftest an-islands-read-is-the-runtimes-own-and-xray-sees-it
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [shared-host {:sym "AAPL"}] k 2)
            (.then
              (fn [handle]
                (testing "the island painted the value, and so did the boundary
                          beside it — the same key, read through the two
                          different doors the two tiers offer"
                  (is (= "191" (text-at handle ".price")))
                  (is (= "191" (text-at handle ".boundary"))))

                (testing "and there is exactly ONE cell, under the frame the
                          root installed, with TWO readers. Narrowing caught: a
                          hook that read through `subscribe-once` per render —
                          the paint above is identical under it and there would
                          be one reader here, or none"
                  (is (= #{k} (support/cell-keys)))
                  (is (= 2 (count (readers-of k)))))

                (testing "and TWO read-set entries exist, which is the honest
                          count: the one-key entry the boundary and the island
                          SHARE, and the empty one `shared-host` claimed — a
                          body that reads nothing still mints and claims an
                          entry, and that is what makes the tool tier's census
                          complete"
                  (is (= 2 (:entries (inventory/residue)))))

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
                  (let [projection (tool/read-mounted-boundaries)
                        row        (first (filter (fn [r]
                                                    (some #(= ::price (:sub-id %))
                                                          (:reads r)))
                                                  (:boundaries projection)))]
                    (is (= :mounted-boundaries (:scope projection)))
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
                  (is (= support/released (teardown! handle))))
                (done)))
            (.catch (fail-and-finish! done "W1 mounted read" nil)))))))

;; ---------------------------------------------------------------------------
;; W2. A write wakes its readers and nobody else
;; ---------------------------------------------------------------------------

(deftest a-write-wakes-the-island-that-reads-it-and-nothing-else
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (testing "a write to the key the island reads repaints it"
                  (mount/dispatch! handle [::set-price "AAPL" 204])
                  (is (= "204" (text-at handle ".price"))))

                (let [runs (deref !island-runs)]
                  (testing "a write to a key NOTHING in the tree reads runs no
                            island body at all. Narrowing caught: a hook
                            subscribed to the generation, or to the store as a
                            whole — it repaints correctly on the row above and
                            wakes on every write in the application here, which
                            is the cost the whole cell table exists to avoid"
                    (mount/dispatch! handle [::touch-elsewhere])
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
                (is (= support/released (teardown! handle)))
                (done)))
            (.catch (fail-and-finish! done "W2 selective wake" nil)))))))

;; ---------------------------------------------------------------------------
;; W3. THE characteristic failure: a re-render that changed no read
;; ---------------------------------------------------------------------------

(deftest a-re-render-that-changed-no-read-performs-no-re-subscribe
  ;; This is the row the design is FOR. A hook that mints its `subscribe`
  ;; closure per render is correct on screen forever: React tears the
  ;; subscription down and rebuilds it after every single re-render,
  ;; releasing and re-acquiring the cell, and the value is right the whole
  ;; time. Only the registration's IDENTITY says so.
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (let [reg-at-mount (first (readers-of k))
                      runs         (deref !island-runs)
                      residue      (inventory/residue)]

                  (testing "three re-renders driven by the island's OWN React
                            state — a `useState` bump behind a real click,
                            which the runtime knows nothing about and cannot
                            have moved a read"
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
                    (is (= residue (inventory/residue))))

                  (testing "the same holds across a re-render the RUNTIME
                            caused. A write moves the value, React re-renders
                            the island, and the read set is what it was — so
                            React is never handed a new `subscribe` and the
                            commit does no work"
                    (mount/dispatch! handle [::set-price "AAPL" 204])
                    (is (= "204" (text-at handle ".price")))
                    (is (true? (identical? reg-at-mount (first (readers-of k))))
                        "React holds a DIFFERENT registration, so the
                         subscription was torn down and rebuilt")
                    (is (= residue (inventory/residue))))

                  (exercised! :hooks/no-resubscribe)
                  (is (= support/released (teardown! handle)))
                  (done))))
            (.catch (fail-and-finish! done "W3 no re-subscribe" nil)))))))

;; ---------------------------------------------------------------------------
;; W4. StrictMode's double mount, and exact teardown
;; ---------------------------------------------------------------------------

(deftest strict-modes-double-mount-acquires-once-and-unmount-releases-exactly
  (async done
    (if-not (mount/browser?)
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
                  (is (= #{k} (support/cell-keys)))
                  (is (= 1 (count (readers-of k))))
                  (is (= {:cells 1 :cell-refs 1 :boundaries 1 :edges 1}
                         (dissoc (inventory/residue) :entries))))

                (testing "the island is live, not merely tidy — a subscription
                          torn down by StrictMode's first cleanup and never
                          rebuilt would satisfy every count above and repaint
                          nothing"
                  (mount/dispatch! handle [::set-price "AAPL" 204])
                  (is (= "204" (text-at handle ".price"))))

                (testing "and unmount releases EXACTLY what mount acquired,
                          read between the unmount and the reset. Narrowing
                          caught: a cleanup that released by key rather than by
                          the cells it acquired — after a reap and rebuild it
                          would release a successor's and leave its own"
                  (is (= support/released (teardown! handle))))

                (exercised! :hooks/strict-mode)
                (-> (support/quiesced!)
                    (.then (fn [_]
                             (testing "past the reapers the tables are empty"
                               (is (= {:cells 0 :cell-refs 0 :boundaries 0
                                       :edges 0 :entries 0}
                                      (inventory/residue))))
                             (done))))))
            (.catch (fail-and-finish! done "W4 StrictMode" nil)))))))

;; ---------------------------------------------------------------------------
;; W5. Frames are isolated contexts — on this side of the fence too
;; ---------------------------------------------------------------------------

(deftest two-frames-are-two-cells-and-an-island-cannot-see-across
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [ka (price-key alpha "AAPL")
            kb (price-key beta "AAPL")]
        (seat! alpha {"AAPL" "alpha-price"})
        (seat! beta  {"AAPL" "beta-price"})
        (-> (mount-live! alpha [host {:sym "AAPL"}] ka 1)
            (.then (fn [a] (.then (mount-live! beta [host {:sym "AAPL"}] kb 1)
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
                    (is (= #{ka kb} (support/cell-keys)))
                    (is (= 1 (count (readers-of ka))))
                    (is (= 1 (count (readers-of kb)))))

                  (testing "and each island painted its own frame's value"
                    (is (= "alpha-price" (text-at a ".price")))
                    (is (= "beta-price"  (text-at b ".price"))))

                  (testing "a write in one frame moves that frame's island and
                            leaves the other exactly where it was — the
                            isolation claim as a REPAINT rather than as a count"
                    (mount/dispatch! a [::set-price "AAPL" "alpha-moved"])
                    (is (= "alpha-moved" (text-at a ".price")))
                    (is (= "beta-price"  (text-at b ".price"))))

                  (exercised! :hooks/frame-isolation)
                  (mount/unmount! a)
                  (is (= support/released (teardown! b)))
                  (mount/release! (assoc a :root nil))
                  (done))))
            (.catch (fail-and-finish! done "W5 frame isolation" nil)))))))

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
    (error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try (thunk) @seen
         (finally (error-emit/unregister-error-listener! k)))))

(deftest use-frame-is-stable-across-renders-and-retargets-across-a-reincarnation
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" "predecessor"})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (let [ops-1   (deref !last-ops)
                      token-1 (frame/frame-incarnation-token alpha)]

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

                    (-> (support/wait-until! #(= "successor" (text-at handle ".price")))
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
                            (is (= support/released (teardown! handle)))
                            (done)))
                        (.catch (fail-and-finish! done "W6 incarnation" handle)))))))
            (.catch (fail-and-finish! done "W6 incarnation" nil)))))))

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
    (if-not (mount/browser?)
      (do (skip! ":node-test has no React DOM") (done))
      (let [k (price-key alpha "AAPL")]
        (seat! alpha {"AAPL" 191})
        (-> (mount-live! alpha [host {:sym "AAPL"}] k 1)
            (.then
              (fn [handle]
                (react/startTransition
                  (fn [] (collector/dispatch! alpha [::set-price "AAPL" 204])))
                (mount/settle!)
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
                         (dissoc (inventory/residue) :entries))))

                (exercised! :hooks/transition)
                (is (= support/released (teardown! handle)))
                (done)))
            (.catch (fail-and-finish! done "W7 transition" nil)))))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (skip! ":node-test reaches none of the mechanisms")
    (is (= declared-population (deref !exercised))
        (str "declared but never reached: "
             (pr-str (set/difference declared-population (deref !exercised)))))))
