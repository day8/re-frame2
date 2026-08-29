(ns re-frame.bench.hicasso.arm1.lifecycle-dom-cljs-test
  "STRICTMODE, THE HMR BODY SWAP, AND A REAL ERROR BOUNDARY
  (rf2-2rtt6.41).

  validation.md's `:lifecycle/strict-abandoned-teardown-hmr` row names
  four things; rf2-2rtt6.9 witnessed the abandoned render (the generation
  fence suite) and the teardown (the standing zero-residue assertion).
  The other two are here, together with the `:foreign/…-error-boundary`
  half that HD-020(c) owes — the runtime's internal class-based
  [[re-frame.bench.hicasso.arm1.boundary/boundary]], which did not exist
  until this bead.

  ## StrictMode is CLAIMED correct by construction, so it is PROVED

  `runtime/run-once` resets the scratch and both provenance flags
  **unconditionally**, and the runtime's docstring says in two places
  that this is what makes StrictMode's double-invoke correct rather than
  additive. HD-002's ownership table is blunter still — of the
  `COMMITTED → COMMITTED` transition (StrictMode's mount / unmount /
  mount of effects) it says *\"idempotent because the index is set-valued
  — but **prove it with a witness, do not assume it**\"*.

  So the row below asserts the double-invoke actually happened before it
  asserts anything about the result. A StrictMode witness on a build where
  React did not double-invoke is a green gate over nothing, and that is
  the failure mode this bead exists to close elsewhere too.

  ## What an HMR body swap is, concretely

  `defview` expands to a `def` of `mint-view!`'s product, so re-evaluating
  a view's form mints a **new React component**. That — not a mutated
  closure — is what shadow-cljs's reload hands React, and it is what is
  swapped below: same root, same container, same frame, same app-db, a
  different component in the tree. HD-021(c)'s minimum is that the first
  four survive, that the changed body is used, and that no subscription
  leaks; all five are read here.

  Runtime: `-dom-cljs-test`; under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.boundary :refer [boundary]]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; Registered ABOVE `use-fixtures`, deliberately. The reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; EVALUATED and restores that baseline before every test — so a `reg-sub`
;; written below it is erased before the first row runs.

(rf/reg-sub :life/cell (fn [db [_ i]] (get-in db [:cells i] "")))
(rf/reg-sub :life/a (fn [db _] (:a db)))
(rf/reg-sub :life/b (fn [db _] (:b db)))
(rf/reg-sub :life/boom? (fn [db _] (:boom? db)))
(rf/reg-sub :life/attempt (fn [db _] (:attempt db)))

(rf/reg-event :life/seed
  (fn [_ [_ n]]
    {:db {:cells   (into {} (map (fn [i] [i (str "v" i)])) (range n))
          :a       "alpha"
          :b       "beta"
          :boom?   false
          :attempt 0
          :errors  []}}))

(rf/reg-event :life/set-cell
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:cells i] v)}))

(rf/reg-event :life/set (fn [{:keys [db]} [_ k v]] {:db (assoc db k v)}))

(rf/reg-event :life/record-error
  (fn [{:keys [db]} [_ err]]
    {:db (update db :errors conj (or (ex-message err) (str err)))}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-lifecycle)
(def ^:private reads 5)

(defn- skip! [why] (is true (str "a lifecycle claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id :initial-events [[:life/seed reads]]})
  (rf/with-frame frame-id (rf/dispatch-sync [:life/seed reads]))
  frame-id)

(defn- db [] (rf/app-db-value frame-id))

(defn- teardown-census!
  "Unmount the root, read the live-reference census, and THEN finish the
  release. The order is the entire load-bearing part, and it is not
  fussiness: `mount/release!` calls `runtime/reset-runtime!`, which
  disposes every cell and empties the index **by fiat** — so a census
  taken after it reads all zeros whatever the teardown did or did not
  release. Measured: with `make-subscribe`'s cleanup `release-cell!`
  deleted, this reading goes red and a post-`release!` reading stays
  green.

  Three counts, because those three are exact the instant React's unmount
  returns. `:cells` and `:entries` belong to the reaper, one macrotask
  later, and asserting them here would be asserting a schedule rather
  than a release."
  [handle]
  (react-dom/flushSync (fn [] (.unmount (:root handle))))
  (let [census (select-keys (rt/residue) [:cell-refs :boundaries :edges])]
    ;; `:root nil` so the arm's teardown door does the rest — reset the
    ;; runtime, drop the container — without unmounting a root that is
    ;; already gone.
    (mount/release! (assoc handle :root nil))
    census))

(def ^:private released
  "What [[teardown-census!]] must read after any clean teardown."
  {:cell-refs 0 :boundaries 0 :edges 0})

;; ---------------------------------------------------------------------------
;; 1 — StrictMode
;; ---------------------------------------------------------------------------

(def ^:private !runs (atom 0))

(defview reader
  "One boundary, `n` reads inside a `for`. The counter is what makes the
  double-invoke a measured premise rather than an assumed one."
  [{:keys [n]}]
  (swap! !runs inc)
  [:ul.reads
   (for [i (range n)]
     [:li.read {:key i :data-i i} (str (rt/sub [:life/cell i]))])])

(defn- strict-root!
  "`mount/root!`, wrapped in `React.StrictMode`. Written here rather than
  in the shared mount door because StrictMode is this row's variable and
  every other suite in the arm must keep measuring the ordinary tree."
  [container frame-kw hiccup]
  (let [root (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root (react/createElement
                             react/StrictMode nil
                             (mount/provider frame-kw (codec/as-element hiccup))))))
    {:root root :frame frame-kw :container container}))

(defn- read-texts [handle]
  (mapv #(.-textContent %) (array-seq (.querySelectorAll (:container handle) ".read"))))

(deftest strictmodes-double-invoke-is-not-additive
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (reset! !runs 0)
      (let [handle (strict-root! (mount/fresh-container!) frame-id [reader {:n reads}])
            census (volatile! nil)]
        (try
          (testing "the premise: React really did invoke the body twice"
            (is (= 2 @!runs)
                (str "StrictMode double-invokes the render in development. "
                     "If this reads 1 the build is not double-invoking and "
                     "every assertion below is a green gate over nothing — "
                     "fix the build, do not relax this")))
          (testing "ONE read-set entry, not two — the scratch is reset by
                   overwrite at the top of every body, so the second
                   invocation resolves the SAME set rather than appending to
                   the first's"
            (is (= 1 (:entries (rt/stats)))
                "a reset guarded by \"if empty\" would concatenate the two
                 renders' reads and mint a second entry of twice the length"))
          (testing "and the COMMITTED → COMMITTED transition is idempotent —
                   StrictMode mounts the effect, tears it down and mounts it
                   again, and the index is set-valued"
            (let [{:keys [boundaries edges cells cell-refs]} (rt/stats)]
              (is (= 1 boundaries) "one boundary, not two registrations")
              (is (= reads edges) (str "one edge per read, not " (* 2 reads)))
              (is (= reads cells))
              (is (= reads cell-refs)
                  "one reference per cell — a double-mounted effect that did
                   not release would read twice this")))
          (testing "the page is right, and a later write still reaches it"
            (is (= (mapv #(str "v" %) (range reads)) (read-texts handle)))
            (mount/dispatch! handle [:life/set-cell 2 "moved"])
            (is (= "moved" (nth (read-texts handle) 2)))
            (is (= "v1" (nth (read-texts handle) 1)) "and its neighbour did not"))
          (finally (vreset! census (teardown-census! handle))))
        (is (= released @census)
            "and a StrictMode tree releases exactly as an ordinary one does —
             read after React's unmount and before the reset door")))))

;; ---------------------------------------------------------------------------
;; 2 — the HMR body swap
;; ---------------------------------------------------------------------------
;;
;; Two separately minted components, because that is what re-evaluating a
;; `defview` form produces: `defview` expands to `(def v (mint-view! …))`,
;; so a reload hands React a NEW component rather than a mutated closure.
;; They read different subscriptions on purpose — that is what makes "no
;; subscriptions leak" a reading rather than a hope.

(def ^:private before
  (rt/mint-view! "hmr/before" (fn [_] [:div.hmr {:data-v "1"} (str (rt/sub [:life/a]))])))

(def ^:private after
  (rt/mint-view! "hmr/after" (fn [_] [:div.hmr {:data-v "2"} (str (rt/sub [:life/b]))])))

(defn- hmr-node [handle] (.querySelector (:container handle) ".hmr"))

(deftest an-hmr-body-swap-keeps-the-root-the-frame-and-app-db
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [container (mount/fresh-container!)
            handle    (mount/root! container frame-id [before {}])
            root      (:root handle)
            db-before (db)
            census    (volatile! nil)]
        (try
          (is (= "1" (.getAttribute (hmr-node handle) "data-v")))
          (is (= "alpha" (.-textContent (hmr-node handle))))
          (is (= 1 (:edges (rt/stats))) "one edge, on the OLD body's read")

          ;; The swap. Same root object, same container, same frame.
          (mount/render! handle [after {}])

          (testing "HD-021(c)'s four survivals"
            (is (identical? root (:root handle)) "the root survived")
            (is (identical? container (:container handle)) "and the container")
            (is (= frame-id (:frame handle)) "and the frame")
            (is (= db-before (db)) "and app-db is untouched by the swap"))
          (testing "the changed body is the one that renders"
            (is (= "2" (.getAttribute (hmr-node handle) "data-v")))
            (is (= "beta" (.-textContent (hmr-node handle)))))
          (testing "and no subscription leaks across the swap"
            (let [{:keys [boundaries edges cell-refs]} (rt/stats)]
              (is (= 1 boundaries) "the old body's registration is gone")
              (is (= 1 edges) "and so is its edge")
              (is (= 1 cell-refs)
                  "exactly one reference is held — the new body's. The old
                   cell's object may outlive this line by one macrotask (the
                   reaper's grace window) but it holds NO reference, which is
                   the standing assertion")))
          (testing "the swapped-in body is really wired, not merely painted"
            (mount/dispatch! handle [:life/set :b "beta-moved"])
            (is (= "beta-moved" (.-textContent (hmr-node handle)))))
          (finally (vreset! census (teardown-census! handle))))
        (is (= released @census)
            "and the swapped tree releases everything it took")))))

;; ---------------------------------------------------------------------------
;; 3 — a real error boundary
;; ---------------------------------------------------------------------------

(defview risky
  "Throws from the render phase when the model says so — which is where a
  React error boundary is the only thing that can catch."
  [_]
  (when (rt/sub [:life/boom?])
    (throw (ex-info "the body threw" {:planted true})))
  [:p.ok "ok"])

(defview guarded
  "The boundary as an author writes it: three keys, hiccup children, and
  the reset key read from the model like any other value."
  [_]
  [boundary {:fallback  [:p.fallback "caught"]
             :reset-key (rt/sub [:life/attempt])
             :on-error  [:life/record-error]}
   [risky {}]])

(defn- errors [] (:errors (db)))
(defn- has? [handle sel] (some? (.querySelector (:container handle) sel)))

(deftest the-boundary-catches-reports-once-and-resets
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (rf/with-frame frame-id (rf/dispatch-sync [:life/set :boom? true]))
      (let [handle (mount/root! (mount/fresh-container!) frame-id [guarded {}])
            census (volatile! nil)]
        (try
          (testing "the throw is caught and the fallback is on screen"
            (is (has? handle ".fallback"))
            (is (not (has? handle ".ok")) "the child that threw rendered nothing"))
          (testing ":on-error fired exactly once, into the frame the boundary
                   is mounted under"
            (is (= ["the body threw"] (errors))
                "once — not once per render attempt, and not once per commit
                 of the fallback"))
          (testing "the render that threw registered NOTHING — HD-002's
                   RENDERING → ∅ row, which needs no cleanup because it never
                   did anything that would need cleaning"
            (let [{:keys [boundaries edges]} (rt/stats)]
              (is (= 1 boundaries)
                  "only the host boundary committed; the thrower's
                   registration does not exist")
              (is (= 1 edges) "and its one edge is the host's reset-key read")))

          (testing "a changed :reset-key retries the child — and a failure
                   after a reset is a NEW failure, so it reports again"
            (mount/dispatch! handle [:life/set :attempt 1])
            (is (has? handle ".fallback") "it threw again, so the fallback stands")
            (is (= ["the body threw" "the body threw"] (errors))))

          (testing "and once the cause is gone the child renders"
            (rf/with-frame frame-id (rf/dispatch-sync [:life/set :boom? false]))
            (mount/dispatch! handle [:life/set :attempt 2])
            (is (has? handle ".ok") "the retry succeeded")
            (is (not (has? handle ".fallback")))
            (is (= 2 (count (errors))) "with nothing further reported")
            (is (= 2 (:boundaries (rt/stats)))
                "and the child that now renders holds its own registration"))
          (finally (vreset! census (teardown-census! handle))))
        (is (= released @census)
            "a tree that caught a throw still releases everything it took")))))

(deftest the-boundary-reports-once-under-strictmode
  (testing "WHERE THE ONCE-PER-FAILURE GUARANTEE ACTUALLY COMES FROM.
           StrictMode runs the failing render TWICE — the body throws
           twice — and React still calls `componentDidCatch` once. So an
           application's failure channel gets ONE record however many times
           React re-ran the render that produced it, and it gets it because
           `report!` is wired to that one lifecycle rather than because
           anything counts.

           An instance flag gating the report used to sit in
           `arm1/boundary`; removing it left every row green, so it was a
           line nothing observed and it is gone. What reds this row is
           reporting from a lifecycle React runs more than once — moving
           `report!` into `render` is the mutation, and StrictMode is why
           it is visible"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (rf/with-frame frame-id (rf/dispatch-sync [:life/set :boom? true]))
        (let [handle (strict-root! (mount/fresh-container!) frame-id [guarded {}])]
          (try
            (is (has? handle ".fallback") "the throw was caught")
            (is (= ["the body threw"] (errors))
                "ONE record, not one per invocation of the render that threw")
            (finally (mount/release! handle))))))))

(deftest a-function-fallback-sees-the-error
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (rf/with-frame frame-id (rf/dispatch-sync [:life/set :boom? true]))
      (let [view   (rt/mint-view!
                     "boundary/fn-fallback"
                     (fn [_] [boundary {:fallback (fn [e] [:p.fallback (ex-message e)])}
                              [risky {}]]))
            handle (mount/root! (mount/fresh-container!) frame-id [view {}])]
        (try
          (is (= "the body threw"
                 (.-textContent (.querySelector (:container handle) ".fallback")))
              "the fallback may be a function of the error — the one thing
               beyond static hiccup this boundary offers, and it is why
               `:fallback` is not simply a child")
          (finally (mount/release! handle)))))))

(deftest the-boundary-spends-no-shell-hook
  (testing "HD-020(b)'s ≤2 is the boundary SHELL's budget, and
           `h/error-boundary` is a class — it calls no hooks at all. Counted at
           React's own dispatcher, on a page that wraps one Hicasso
           boundary in another"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (if-not (probe/install!)
        (is false (str "React's internals slot was not found, so this claim is "
                       "UNWITNESSED on this build — fix the probe rather than "
                       "reading this as a pass"))
        (do
          (fresh!)
          (let [container (mount/fresh-container!)
                handle    (volatile! nil)
                names     (probe/record!
                            (fn [] (vreset! handle (mount/root! container frame-id
                                                                [guarded {}]))))]
            (mount/release! @handle)
            (is (= ["useContext" "useSyncExternalStore"
                    "useContext" "useSyncExternalStore"]
                   names)
                (str "two Hicasso boundaries, two hooks each, and the class "
                     "between them contributes none: " (pr-str names)))))))))
