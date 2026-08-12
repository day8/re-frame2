(ns re-frame.freehand.substrate-dom-cljs-test
  "rf2-vo8fb — the Freehand adapter in a real browser: the claims that only a
  mounted page can make.

  Every row here was unmakeable before this slice, and one of them is the whole
  reason the adapter exists.

  **Automatic re-render.** A Freehand page mounted on `v/adapter` repaints when a
  subscribed value moves, with NOTHING forcing it — no `cell/flush!`, no `act`
  drain, no explicit flush of any kind. That is the `IWatchable` claim end to
  end: app-db moves, the spine's derived value notifies its watch, the
  observation port marks the ViewCell, the cell's own microtask closes the
  pending window, and React commits. On `plain-atom` that chain breaks at the
  first link — its derived value reifies `IDeref` but not `IWatchable`, so the
  port installs no watch and no notification is ever raised. This suite is what
  says the adapter is wired rather than merely shaped.

  **`flush-render!` returns settled.** The synchronous contract verb, driven
  against a live root: after it returns, the DOM already shows what the thunk
  dispatched — read back off `document` in the same turn, with no yield between.

  **Disposal, in two rows with different strengths.** `v/unmount!` on a live
  adapter is the ORDERLY path and the one that destroys a root-owned frame.
  `(rf/destroy-adapter!)` is the safety net: it unmounts every live Freehand root,
  disconnects their ViewCells and releases their frame references before it
  disposes the spine — but it does NOT run a frame's destroy recipe, because core
  closes public delegation before calling the adapter's teardown and a destroy
  recipe reads a container. Both rows are here, and the second says why it is the
  weaker claim rather than leaving a reader to wonder.

  **Dispose then re-init.** A fresh install mounts a clean generation: new roots
  admit, a fresh frame seeds, and the page repaints off the new adapter rather
  than a stale watch left by the old one.

  Rides the browser lane through its `-dom-cljs-test` suffix. It also matches the
  node suites' broader regex, where there is no DOM to mount and it says so
  rather than passing quietly — the headless half is
  `re-frame.freehand.substrate-cljs-test`."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.substrate :as fh-substrate]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
   {:adapter       v/adapter
    :ambient-frame nil
    :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the commit
  rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment. The repaint under test is driven by a
  DEPENDENCY rather than by a render call, and inside an act environment React
  diverts that work to the act queue instead of flushing it where the browser
  would — so the assertion would be about act's drain order rather than about the
  substrate."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- tick!
  "Yield one browser task. Nothing here closes the pending window: the point of
  the automatic-repaint rows is that the CELL's own microtask does, so all a test
  may do is let the browser run."
  []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

(defn- teardown!
  "Destroy the adapter the way a page does — OUTSIDE React's act environment.

  This is not a stylistic preference. `root.unmount()` inside an act environment
  is QUEUED rather than performed, and the drain's `unmount!` releases the root's
  frame BEFORE it calls React. So under `act` the ordering inverts: the frame is
  destroyed and the spine disposed, and only then does the queued unmount render
  the still-mounted subtree — which reads a disposed container
  (`:rf.error/adapter-disposed`) or probes a destroyed frame
  (`:rf.error/frame-destroyed`). Worse, a test that resolves before the queue
  drains abandons the unmount altogether and leaves the root live in a detached
  container, where it surfaces as the NEXT test failing.

  Outside the act environment `root.unmount()` runs synchronously, which is what
  a real `(rf/destroy-adapter!)` does on a real page — so this drives the
  contract rather than act's scheduler."
  []
  (live!)
  (rf/destroy-adapter!)
  (tick!))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

;; ---------------------------------------------------------------------------
;; The application under test. Module-level, because a declaration cannot close
;; over a test's locals.
;; ---------------------------------------------------------------------------

(def ^:private fid :freehand-substrate-dom/frame)

(v/defview counter
  "One read and one committed handler — the smallest view that needs a watchable
  derived value to work at all."
  [{:keys [label]}]
  [:div.counter
   [:span.label label]
   [:output.count (str (v/sub [::count]))]
   [:button.bump {:on-click [::bump]} "+"]])

(v/defview app
  "The mounted root."
  [_]
  [:main#fh-substrate [counter {:label "adapter"}]])

(defn- register! []
  (rf/reg-sub ::count (fn [db _] (:count db)))
  (rf/reg-event ::seed (fn [_ _] {:db {:count 0}}))
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (rf/reg-event ::set  (fn [{:keys [db]} [_ n]] {:db (assoc db :count n)})))

(defn- seed-frame!
  "Create `frame-id` and put `db` in it — the ordinary out-of-band seed a page
  does before it scopes a frame from a root."
  [frame-id db]
  (live-frame/make-frame {:id frame-id})
  (frame/replace-app-db! frame-id db)
  frame-id)

(defn- seed! [db]
  (seed-frame! fid db))

(defn- db-count []
  (:count (frame/frame-app-db-value fid)))

;; ===========================================================================
;; The claim the adapter exists for: an AUTOMATIC repaint
;; ===========================================================================

(deftest a-mounted-freehand-page-repaints-with-nothing-forcing-it
  (testing "init → mount → subscribe → dispatch → re-render, with NO explicit
            flush anywhere. The dispatch moves app-db; the spine's derived value
            is IWatchable so the observation port's watch fires; the port marks
            the ViewCell; the cell's own microtask closes the pending window; and
            React commits. Removing any link leaves the DOM showing the old
            value — which is exactly what `plain-atom` does, because its derived
            value is not watchable and no watch is ever installed."
    (if-not (browser?)
      (skip! "the browser job runs the repaint assertions")
      (async done
        (register!)
        (seed! {:count 41})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [app {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= "41" (text container ".count"))
                           "the first render read the seeded value")
                       (live!)
                       (rf/dispatch-sync [::bump] {:frame fid})
                       (is (= 42 (db-count)) "app-db moved")
                       (tick!)))
              (.then (fn [_]
                       (is (= "42" (text container ".count"))
                           "and the page repainted on its own — the notification
                            chain the adapter installs, end to end")
                       ;; ASYMMETRIC, so it stays put: `@mounted` is only set once
                       ;; the mount resolved, and the rejection arm never had a
                       ;; root to unmount. The container detach both arms DID
                       ;; share rides the single trailing step below.
                       (v/unmount! @mounted)))
              ;; Reports and RELEASES; it never finishes (rf2-fyba). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "automatic-repaint arm rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest a-real-click-through-a-committed-site-repaints-the-page
  (testing "The same chain driven by a real DOM click rather than a test
            dispatch, so the committed event site and the repaint are proven in
            one turn — a proxy React never attached would pass a direct dispatch
            and fail this."
    (if-not (browser?)
      (skip! "the browser job runs the click assertions")
      (async done
        (register!)
        (seed! {:count 0})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [app {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= "0" (text container ".count")))
                       (live!)
                       (.click (.querySelector container ".bump"))
                       (tick!)))
              (.then (fn [_]
                       (is (= 1 (db-count)) "the click reached the frame")
                       (is (= "1" (text container ".count"))
                           "and the page shows what app-db now holds")
                       ;; Success-only, as above.
                       (v/unmount! @mounted)))
              (.catch (fn [e] (is false (str "click arm rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; flush-render! returns with the page SETTLED
;; ===========================================================================

(deftest flush-render-returns-with-the-freehand-dom-settled
  (testing "The synchronous contract verb, against a live root. `cell/mark-dirty!`
            arms a LATER microtask, so the inherited spine verb would return with
            the cell pending and the DOM stale; the override closes the pending
            window inside React's commit boundary. Asserted with NO yield between
            the call returning and the read — that gap is the whole claim."
    (if-not (browser?)
      (skip! "the browser job runs the settling assertions")
      (async done
        (register!)
        (seed! {:count 7})
        (let [container (host-node!)
              mounted   (atom nil)]
          (-> (act #(v/mount [app {}] container {:frame fid}))
              (.then (fn [m]
                       (reset! mounted m)
                       (is (= "7" (text container ".count")))
                       (live!)
                       (fh-substrate/flush-render!
                        (fn [] (rf/dispatch-sync [::set 9] {:frame fid})))
                       (is (= "9" (text container ".count"))
                           "settled BEFORE the call returned — no microtask, no
                            act queue, no yield")
                       (is (= 0 (cell/pending-count))
                           "and the pending window is quiescent, not merely
                            drained-then-refilled")
                       ;; Success-only, as above.
                       (v/unmount! @mounted)))
              (.catch (fn [e] (is false (str "flush-render! arm rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; Disposal — the roots first, then the spine
;; ===========================================================================

(deftest destroying-the-adapter-drains-every-live-freehand-root
  (testing "Freehand roots live in Freehand's own registry, invisible to the
            spine's active-root registry — so without the drain, adapter disposal
            would leave them mounted with live subscriptions over containers it
            had just torn down. Two roots, so the claim is about the whole
            registry rather than one entry: both leave the registry, both
            containers are emptied, and every ViewCell below them is
            disconnected."
    (if-not (browser?)
      (skip! "the browser job runs the disposal assertions")
      (async done
        (register!)
        (seed! {:count 3})
        (let [left  (host-node!)
              right (host-node!)]
          (-> (act #(do (v/mount [app {}] left  {:frame fid})
                        (v/mount [app {}] right {:frame fid :disambiguator :right})))
              (.then (fn [_]
                       (is (= 2 (count (root/live-root-ids)))
                           "two roots are live and both are Freehand's own")
                       (is (= "3" (text left ".count")))
                       (is (= "3" (text right ".count")))
                       (teardown!)))
              (.then (fn [_]
                       (is (= #{} (root/live-root-ids))
                           "every root left the registry — id, container and
                            identifierPrefix claims with it")
                       (is (nil? (text left ".count"))
                           "the left container was emptied by a real unmount")
                       (is (nil? (text right ".count"))
                           "and so was the right")
                       (is (nil? (rf/current-adapter))
                           "and the spine was disposed after the drain, not
                            before it")))
              (.catch (fn [e] (is false (str "disposal arm rejected: " e)) nil))
              (.then (fn [_] (.remove left) (.remove right) (done)))))))))

(deftest unmounting-a-root-destroys-the-frame-it-owned
  (testing "The ORDERLY path, and the one that destroys a frame. A root given a
            `:frame {:id …}` plan ENSURED that frame and owns its lifetime, so
            `v/unmount!` releases the reference and destroys the frame once no
            live root still holds one — on a LIVE adapter, which is what the
            destroy recipe needs. This is the row that proves root-owned frame
            release; the adapter-disposal row below is deliberately the weaker
            claim, and says why."
    (if-not (browser?)
      (skip! "the browser job runs the frame-release assertions")
      (async done
        (register!)
        (let [container (host-node!)
              owned     :freehand-substrate-dom/owned]
          (-> (act #(v/mount [app {}] container
                             {:frame {:id owned :initial-events [[::seed]]}}))
              (.then (fn [m]
                       (is (some? (frame/frame owned))
                           "the root's plan created the frame before React")
                       (is (= "0" (text container ".count"))
                           "and its :initial-events had already seeded app-db by
                            the first render")
                       (is (contains? (:refs (get (root/frame-ledger-snapshot) owned))
                                      (:root-id (root/root-descriptor m)))
                           "the ledger records this root's reference to it")
                       (live!)
                       (v/unmount! m)
                       (tick!)))
              (.then (fn [_]
                       (is (nil? (frame/frame owned))
                           "the last reference went, so the root-owned frame was
                            destroyed")
                       (is (nil? (get (root/frame-ledger-snapshot) owned))
                           "and its ledger row went with it")
                       (is (= #{} (root/live-root-ids)))
                       (is (nil? (text container ".count"))
                           "the container was emptied by a real unmount")))
              (.catch (fn [e] (is false (str "orderly-unmount arm rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest destroying-the-adapter-drains-a-forgotten-root-without-running-a-destroy-recipe
  (testing "The SAFETY NET, and its honest boundary. Core claims the installed
            generation and closes public delegation BEFORE it calls the adapter's
            teardown, so a `read-container` inside that window raises
            `:rf.error/adapter-disposed` — and a frame's destroy recipe reads the
            runtime-db container. So the drain gives back the root's claims and
            its frame REFERENCE and unmounts React, and leaves the frame record to
            the next `rf/init!`. It does not need more than that: the frame's
            containers belong to the adapter being destroyed. Destroying a
            root-owned frame is `v/unmount!`'s job on a live adapter, which is why
            the contract asks an application to unmount its roots first — this
            row is what stops a FORGOTTEN root from holding subscriptions and DOM
            past the adapter's life."
    (if-not (browser?)
      (skip! "the browser job runs the disposal assertions")
      (async done
        (register!)
        (let [container (host-node!)
              owned     :freehand-substrate-dom/forgotten]
          (-> (act #(v/mount [app {}] container
                             {:frame {:id owned :initial-events [[::seed]]}}))
              (.then (fn [_]
                       (is (some? (frame/frame owned)))
                       (is (= "0" (text container ".count")))
                       (teardown!)))
              (.then (fn [_]
                       (is (= #{} (root/live-root-ids))
                           "the forgotten root left the registry — claims released")
                       (is (nil? (text container ".count"))
                           "and React really unmounted it, so its ViewCells are
                            disconnected and its subscriptions released")
                       (is (nil? (get (root/frame-ledger-snapshot) owned))
                           "its frame reference was released and the row went")
                       (is (nil? (rf/current-adapter))
                           "and the spine was disposed after the drain, not before")))
              (.catch (fn [e] (is false (str "forgotten-root drain rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; Dispose, then re-init — a clean generation
;; ===========================================================================

(deftest a-disposed-adapter-re-inits-to-a-clean-generation
  (testing "After a full teardown a fresh `(rf/init! v/adapter)` mounts a page
            that works: the root-id is free again, a fresh frame seeds, and the new
            page repaints off the NEW adapter. A stale watch or a retained registry
            entry surviving the teardown shows up here as a refused mount or a page
            that never moves.

            The second generation gets its OWN frame id on purpose. Whether a frame
            RECORD may outlive the adapter whose containers it holds and be reused
            by the next one is core's question, not this adapter's — and pinning an
            answer to it here would be pinning something this bead does not own."
    (if-not (browser?)
      (skip! "the browser job runs the re-init assertions")
      (async done
        (register!)
        (seed! {:count 1})
        (let [first-container (host-node!)
              second-fid      :freehand-substrate-dom/second-generation]
          (-> (act #(v/mount [app {}] first-container {:frame fid}))
              (.then (fn [m]
                       (is (= "1" (text first-container ".count")))
                       (live!)
                       (v/unmount! m)
                       (teardown!)))
              (.then (fn [_]
                       (is (= #{} (root/live-root-ids)) "clean slate")
                       (.remove first-container)
                       (rf/init! v/adapter)
                       (is (= :rf.adapter/freehand (rf/current-adapter))
                           "the same adapter Var installs again — it is not
                            single-use, only single-install")
                       (seed-frame! second-fid {:count 100})
                       (let [second-container (host-node!)]
                         (-> (act #(v/mount [app {}] second-container {:frame second-fid}))
                             (.then (fn [m]
                                      (is (= "100" (text second-container ".count"))
                                          "the re-mounted page reads through the
                                           fresh adapter's containers")
                                      (live!)
                                      (rf/dispatch-sync [::bump] {:frame second-fid})
                                      (-> (tick!)
                                          (.then (fn [_]
                                                   (is (= "101" (text second-container ".count"))
                                                       "and it repaints — the new
                                                        generation's watch is live")
                                                   ;; Success-only: `m` is what the
                                                   ;; mount resolved with.
                                                   (v/unmount! m))))))
                             (.catch (fn [e] (is false (str "re-init mount rejected: " e)) nil))
                             ;; The inner chain finishes NOTHING. It is returned into
                             ;; the outer one, whose trailing step owns the single
                             ;; `done` — so a rejection in here cannot be claimed by
                             ;; a handler sitting downstream of a `done` that ran.
                             (.then (fn [_] (.remove second-container) nil))))))
              (.catch (fn [e]
                        (is false (str "re-init arm rejected: " e))
                        ;; DEFENSIVE, and therefore failure-only: the success path
                        ;; detaches this container at its own point in the sequence,
                        ;; before the second generation mounts, so this arm is the
                        ;; only one that can be left holding it.
                        (.remove first-container)
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; Non-vacuity
;; ===========================================================================

(deftest the-proof-is-not-vacuous
  (testing "Every row above rests on the adapter under test really being the
            installed one, and on the views really being reactive declarations.
            Without this row a suite that quietly ran on some other adapter — or
            on views whose bodies read nothing — would be green for the wrong
            reason."
    (is (= :rf.adapter/freehand (rf/current-adapter))
        "the fixture installed Freehand's own adapter, not a wrapper's")
    (is (identical? v/adapter (rf/current-adapter-spec)))
    (is (v/view? app) "the root form's head is a declared view")
    (is (v/view? counter))
    (is (= :interpreted (:lowering (v/describe counter)))
        "and it is the interpreted paved path — the compiled twin's own browser
         coverage is compiled-mount-dom-cljs-test's")))
