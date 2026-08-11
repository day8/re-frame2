(ns re-frame.hicasso.native-abi-dom-cljs-test
  "THE TWO EMBEDDING DIRECTIONS, AND THE HELPERS, UNDER A REAL REACT
  (rf2-hic-032).

  A wrapper that preserves the ABI on its FIRST render and loses it on
  the second is the characteristic defect this bead is about, and no
  amount of reading a data structure catches it: identity, bail-out,
  remount and StrictMode are properties of a fiber. So every row here
  mounts, and every row drives at least one more render or one remount
  past the mount.

  ## What each row is for, and the narrowing it is written against

  | row | what it establishes | the one-line narrowing it catches |
  |---|---|---|
  | [[a-native-parent-mounts-a-view-under-the-frame-it-is-already-in]] | the outward bridge — one root, one frame, no second state owner | a bridge that resolved a frame of its own; the DOM is identical under it |
  | [[two-frames-are-two-cells-across-the-bridge]] | frames are isolated contexts on the outward crossing too | resolving the frame anywhere but the surrounding context |
  | [[the-views-memo-wrapper-survives-the-bridge]] | a fresh-but-equal props map still bails the boundary out | a bridge handing the raw object through — every re-render re-runs the body, and the screen is right throughout |
  | [[a-hicasso-body-hosts-a-native-component-through-the-doors-that-exist]] | the inward door, both spellings, with the native ABI intact at the crossing | a door that allocated a props MAP for the island — the second ABI clause 5 forbids |
  | [[n-memo-bails-out-and-still-carries-its-marker-on-the-second-render]] | the helper across a re-render, which is where a stamp gets lost | copying the marker into a per-render wrapper |
  | [[a-fresh-mint-replaces-the-subtree-and-that-is-the-hmr-contract]] | allocation, never a lookup by name | a helper caching by display name so a component outlives a reload |
  | [[strict-modes-double-mount-crosses-the-bridge-exactly-once]] | acquire is commit-owned on both sides of the crossing | a bridge acquiring during render |
  | [[a-ref-reaches-a-real-dom-node-through-the-memo-helper]] | why there is no third helper | a `forward-ref` helper — the row is green with and without it, and the point is that it is green WITHOUT |
  | [[a-lazy-head-suspends-and-then-names-its-own-component]] | the marker is filled by arrival, in the object minted at declaration | a second marker minted on resolve |
  | [[teardown-across-the-bridge-releases-exactly-what-mount-acquired]] | the bridge adds no ownership of its own | a wrapper holding a cell reference past unmount |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## Browser lane

  `:node-test` compiles this namespace too (`cljs-test$` matches
  `-dom-cljs-test`), and each row degrades there to a STATED skip rather
  than to a false green. What can be said without a fiber is said in
  `re-frame.hicasso.native-abi-cljs-test`."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as support]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private alpha ::alpha)
(def ^:private beta  ::beta)

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

;; ---------------------------------------------------------------------------
;; The roster this file undertakes to reach
;; ---------------------------------------------------------------------------

(def ^:private declared-population
  #{:bridge/outward
    :bridge/two-frames
    :bridge/memo-bail-out
    :bridge/inward
    :helper/memo-across-a-re-render
    :helper/fresh-mint-remounts
    :bridge/strict-mode
    :helper/ref-to-a-node
    :helper/lazy-arrival
    :bridge/teardown})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; The consumer's source — one view, bridged outward; one island, hosted
;; inward
;; ---------------------------------------------------------------------------

(defonce ^:private !view-runs (atom 0))
(defonce ^:private !island-runs (atom 0))
(defonce ^:private !island-props (atom nil))

(h/defview article-card
  "An ordinary boundary. It reads a subscription, so the frame it
  resolved is observable in the cell table rather than only on screen."
  [props]
  (swap! !view-runs inc)
  [:article {:class "card"} (str "#" (:article-id props) " " (h/sub [::label]))])

(def ^:private card
  "THE OUTWARD BRIDGE, declared once at top level beside the view it
  bridges — which is the law, not a habit: it allocates a component."
  (h/as-component article-card))

(n/defcomponent bridging-parent
  "A native parent that renders the bridged view and can re-render itself
  for a reason the runtime knows nothing about. The local state is what
  makes the bail-out row possible: without it there is no way to drive a
  second render whose props are equal."
  [^js props]
  (let [[local set-local] (react/useState 0)]
    (n/$ :div nil
         (n/$ :button {:class "nudge" :on-click (fn [_] (set-local inc))} "nudge")
         (n/$ :i {:class "local"} (str local))
         ;; A LITERAL props map, lowered by the native macro into React's
         ;; own slot names and decoded back by the bridge — so this row
         ;; also drives the two fences' round trip end to end.
         (n/$ card {:article-id (.-articleId props)}))))

(h/defview outward-host
  "Rung 3: a boundary returning the native parent, which is how the
  outward bridge is reached from a root without a second root."
  [{:keys [article-id]}]
  (n/$ bridging-parent {:article-id article-id}))

(h/defview strict-outward-host
  [{:keys [article-id]}]
  (n/$ react/StrictMode nil (n/$ bridging-parent {:article-id article-id})))

(defn- island-body
  "The island's body, held apart from any mint so a row can allocate two
  components over ONE body — which is what a module reload does."
  [^js props]
  (swap! !island-runs inc)
  (reset! !island-props props)
  (n/$ :b {:class "island"} (str (.-label props) "/" (n/use-sub [::label]))))

(def ^:private hot-cell (n/component "app/hot-cell" :client-only island-body))
(def ^:private memo-cell (n/memo hot-cell))

(h/defhost cell-host memo-cell)

(h/defview inward-host
  "The inward door, both spellings in one body: the named `defhost`
  crossing and the one-off `[:>]` escape.

  `:tick` is what makes the bail-out row possible and it is not
  decoration. A boundary compares its complete props map, so re-rendering
  this root with the SAME props bails the boundary itself out and nothing
  below it runs — the row would then be measuring a body that never
  executed. `:tick` moves, the body runs, and the two crossings beneath
  it are the thing under test."
  [{:keys [tick]}]
  [:div
   [:span.tick (str tick)]
   [cell-host {:label "hosted"}]
   [:> hot-cell {:label "raw"}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (reset! !view-runs 0)
                      (reset! !island-runs 0)
                      (reset! !island-props nil)
                      (collector/reset-runtime!))}))

(defn- seat!
  [frame-kw label]
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [::seed label]))
  nil)

(defn- at [handle sel] (.querySelector ^js (:container handle) sel))
(defn- text-at [handle sel] (some-> (at handle sel) .-textContent))
(defn- click! [handle sel] (.click ^js (at handle sel)) (mount/settle!) nil)

(defn- label-key [frame-kw] [frame-kw [::label]])
(defn- readers-of [sub-key] (inventory/cell-readers sub-key))

(defn- mount-live!
  "Mount `hiccup` under `frame-kw` and return only once `sub-key` has
  exactly `readers` subscribed readers — a commit-owned fact, so a row
  that started before it would be measuring a tree that had not yet
  joined the runtime."
  [frame-kw hiccup sub-key readers]
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-kw hiccup)]
    (-> (support/wait-until! #(= readers (count (readers-of sub-key))))
        (.then (fn [subscribed?]
                 (when-not subscribed?
                   (throw (ex-info (str "expected " readers " reader(s) on "
                                        (pr-str sub-key))
                                   {:residue (inventory/residue)})))
                 handle)))))

(defn- fail-and-finish!
  [done label handle]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | residue " (pr-str (inventory/residue))))
    (when handle (mount/release! handle))
    (done)))

;; ---------------------------------------------------------------------------
;; 1. The outward bridge
;; ---------------------------------------------------------------------------

(deftest a-native-parent-mounts-a-view-under-the-frame-it-is-already-in
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "seed")
        (-> (mount-live! alpha [outward-host {:article-id 7}] (label-key alpha) 1)
            (.then
              (fn [handle]
                (testing "the view painted, inside the parent's own DOM and
                          with the props the native macro lowered and the
                          bridge decoded — `:article-id` out, `articleId`
                          across, `:article-id` back"
                  (is (= "#7 seed" (text-at handle ".card"))))

                (testing "and its read built ONE cell, under the frame the
                          surrounding root installed. Narrowing caught: a
                          bridge resolving a frame of its own — the paint
                          above is identical under it, and this key would
                          name a different frame or no cell would exist at
                          all"
                  (is (= #{(label-key alpha)} (support/cell-keys)))
                  (is (= 1 (count (readers-of (label-key alpha))))))

                (testing "a write through the surrounding frame reaches the
                          bridged view, so the crossing is a place in the
                          application rather than an island of its own
                          state"
                  (rf/with-frame alpha (rf/dispatch-sync [::relabel "moved"]))
                  (mount/settle!)
                  (is (= "#7 moved" (text-at handle ".card"))))

                (exercised! :bridge/outward)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "outward bridge" nil)))))))

(deftest two-frames-are-two-cells-across-the-bridge
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "A")
        (seat! beta "B")
        (-> (mount-live! alpha [outward-host {:article-id 1}] (label-key alpha) 1)
            (.then (fn [a]
                     (-> (mount-live! beta [outward-host {:article-id 2}]
                                      (label-key beta) 1)
                         (.then (fn [b] [a b])))))
            (.then
              (fn [[a b]]
                (testing "one view, two frames, two paints — each reading
                          its own frame's value through the bridge"
                  (is (= "#1 A" (text-at a ".card")))
                  (is (= "#2 B" (text-at b ".card"))))

                (testing "and TWO cells, differing only in their frame.
                          Narrowing caught: a bridge that resolved one
                          frame for both — there would be ONE key here and
                          two visually plausible subtrees above"
                  (is (= #{(label-key alpha) (label-key beta)}
                         (support/cell-keys))))

                (testing "a write to one frame moves one screen. Frames are
                          isolated contexts, and the outward crossing is
                          not a hole in that"
                  (rf/with-frame alpha (rf/dispatch-sync [::relabel "A'"]))
                  (mount/settle!)
                  (is (= "#1 A'" (text-at a ".card")))
                  (is (= "#2 B" (text-at b ".card"))))

                (exercised! :bridge/two-frames)
                (support/teardown-census! a)
                (support/teardown-census! b)
                (done)))
            (.catch (fail-and-finish! done "two frames across the bridge" nil)))))))

(deftest the-views-memo-wrapper-survives-the-bridge
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "seed")
        (-> (mount-live! alpha [outward-host {:article-id 7}] (label-key alpha) 1)
            (.then
              (fn [handle]
                (let [after-mount @!view-runs]
                  (testing "the parent re-renders for a reason of its own —
                            local React state — and hands the bridge a
                            FRESH props object carrying an equal value"
                    (click! handle ".nudge")
                    (is (= "1" (text-at handle ".local"))
                        "the parent really did re-render"))

                  (testing "and the view's body did not run again. The
                            boundary's stable memo wrapper compares the
                            complete props map by `=`, and the bridge hands
                            it a map — so a fresh object with equal
                            contents still bails out.

                            Narrowing caught: a bridge passing React's raw
                            props object through to the shell. `Object.is`
                            is false on every render, the body re-runs
                            every time, and the screen is correct
                            throughout"
                    (is (= after-mount @!view-runs)))

                  (testing "a CHANGED prop crosses, which is the control
                            without which the row above is satisfied by a
                            bridge that never re-renders anything"
                    (mount/render! handle [outward-host {:article-id 8}])
                    (is (= "#8 seed" (text-at handle ".card")))
                    (is (< after-mount @!view-runs))))

                (exercised! :bridge/memo-bail-out)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "memo across the bridge" nil)))))))

;; ---------------------------------------------------------------------------
;; 2. The inward door
;; ---------------------------------------------------------------------------

(deftest a-hicasso-body-hosts-a-native-component-through-the-doors-that-exist
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "in")
        ;; Two islands, one key: the wait is for both readers, so neither
        ;; crossing can be measured before it has joined the runtime.
        (-> (mount-live! alpha [inward-host {:tick 0}] (label-key alpha) 2)
            (.then
              (fn [handle]
                (testing "both doors mounted the island and both islands
                          read the SURROUNDING frame — the named `defhost`
                          crossing and the one-off `[:>]` escape. There is
                          no third door and neither of these two needed to
                          learn that a native tier exists"
                  (let [cells (.querySelectorAll ^js (:container handle) ".island")]
                    (is (= 2 (.-length cells)))
                    (is (= "hosted/in" (.-textContent (aget cells 0))))
                    (is (= "raw/in" (.-textContent (aget cells 1))))))

                (testing "and the crossing handed the island the ONE ABI: a
                          raw JavaScript props object under React's own
                          slot names, values by identity. Narrowing
                          caught: a door allocating a ClojureScript map for
                          the island — it renders identically for a body
                          written against a map, and it is the second ABI
                          clause 5 forbids"
                  (let [props @!island-props]
                    (is (not (map? props)))
                    (is (= "raw" (unchecked-get props "label")))))

                (testing "one key, two readers — the two islands share the
                          runtime's own cell rather than each building a
                          private one"
                  (is (= #{(label-key alpha)} (support/cell-keys)))
                  (is (= 2 (count (readers-of (label-key alpha))))))

                (exercised! :bridge/inward)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "inward door" nil)))))))

;; ---------------------------------------------------------------------------
;; 3. The helpers, across a second render and a remount
;; ---------------------------------------------------------------------------

(deftest n-memo-bails-out-and-still-carries-its-marker-on-the-second-render
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "in")
        (-> (mount-live! alpha [inward-host {:tick 0}] (label-key alpha) 2)
            (.then
              (fn [handle]
                (let [after-mount @!island-runs]
                  (testing "the hosting body re-runs — `:tick` moved, so the
                            boundary above did not bail out and both
                            crossings were re-created — and the memoised
                            island bails out while the un-memoised one
                            beside it re-renders. The second island is the
                            control: without it a helper that broke the
                            whole subtree would read the same"
                    (mount/render! handle [inward-host {:tick 1}])
                    (is (= "1" (text-at handle ".tick"))
                        "the hosting body really did re-run")
                    (is (= (inc after-mount) @!island-runs)
                        "exactly one of the two islands re-ran"))

                  (testing "and the marker is still on the memo record after
                            the render that used it. Narrowing caught: a
                            helper that stamped a per-render wrapper — the
                            first read is green, every later one is nil,
                            and nothing on screen changes"
                    (let [m (n/marker memo-cell)]
                      (is (some? m))
                      (is (= "app/hot-cell" (unchecked-get m "name")))
                      (is (= "client-only" (unchecked-get m "server"))))))

                (exercised! :helper/memo-across-a-re-render)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "memo across a re-render" nil)))))))

(deftest a-fresh-mint-replaces-the-subtree-and-that-is-the-hmr-contract
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      ;; The reload, performed: the SAME body function, allocated a second
      ;; time, exactly as re-evaluating a module does.
      (let [reloaded (n/memo (n/component "app/hot-cell" :client-only island-body))]
        (seat! alpha "in")
        ;; The SAME crossing on both renders — the `[:>]` escape shares one
        ;; gate type for every component on the page — so the only thing
        ;; that changes between them is the mint. Swapping the crossing
        ;; itself would remount for a reason that is not the one under
        ;; test.
        (-> (mount-live! alpha [:> memo-cell {:label "one"}] (label-key alpha) 1)
            (.then
              (fn [handle]
                (let [before (at handle ".island")]
                  (testing "the reloaded mint is a DIFFERENT element type
                            under the same name, which is the contract:
                            minting is allocation, never a lookup"
                    (is (not (identical? memo-cell reloaded)))
                    (is (= "app/hot-cell"
                           (unchecked-get (n/marker reloaded) "name"))))

                  (testing "so React replaces the subtree rather than
                            updating it — a clean remount across a save,
                            which is the designed conduct and not a fault.
                            Narrowing caught: a helper caching by display
                            name to `preserve` identity across a reload; it
                            would keep this node and quietly contradict the
                            recorded contract"
                    (mount/render! handle [:> reloaded {:label "two"}])
                    (let [after (at handle ".island")]
                      (is (= "two/in" (.-textContent after)))
                      (is (not (identical? before after))))))

                (exercised! :helper/fresh-mint-remounts)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "fresh mint" nil)))))))

(deftest strict-modes-double-mount-crosses-the-bridge-exactly-once
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "strict")
        (-> (mount-live! alpha [strict-outward-host {:article-id 3}]
                         (label-key alpha) 1)
            (.then
              (fn [handle]
                (testing "React's double invoke and mount/unmount/mount over
                          every effect leaves ONE cell with ONE reader
                          through the bridge. Narrowing caught: a bridge
                          acquiring during render rather than at commit —
                          the extra acquisition has no matching release"
                  (is (= "#3 strict" (text-at handle ".card")))
                  (is (= #{(label-key alpha)} (support/cell-keys)))
                  (is (= 1 (count (readers-of (label-key alpha))))))

                (exercised! :bridge/strict-mode)
                (support/teardown-census! handle)
                (done)))
            (.catch (fail-and-finish! done "strict mode" nil)))))))

(deftest a-ref-reaches-a-real-dom-node-through-the-memo-helper
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (let [cell     (react/createRef)
            forwards (n/memo
                       (n/component "app/ref-cell" :client-only
                                    (fn [^js props]
                                      (n/$ :input {:class "reffed"
                                                   :ref   (.-ref props)}))))]
        (seat! alpha "refs")
        (let [container (mount/fresh-container!)
              handle    (mount/root! container alpha [:> forwards {:ref cell}])]
          (testing "the ref reached the DOM node, through a memo record and
                    with no helper between the author and it. React 19
                    hands a function component its ref as an ordinary prop,
                    which is why the helper surface is two rather than
                    three — this row is green WITHOUT a `forward-ref`
                    helper, and that is its point"
            (is (some? (.-current cell)))
            (is (= "INPUT" (.-tagName (.-current cell))))
            (is (identical? (at handle ".reffed") (.-current cell))))

          (testing "and the marker rode across the memo unharmed"
            (is (= "app/ref-cell" (unchecked-get (n/marker forwards) "name"))))

          (exercised! :helper/ref-to-a-node)
          (mount/release! handle)
          (done))))))

(deftest a-lazy-head-suspends-and-then-names-its-own-component
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (let [arrived  (n/component "app/arrived" :client-only
                                  (fn [^js props]
                                    (n/$ :b {:class "arrived"} (.-label props))))
            head     (n/lazy (fn [] (js/Promise.resolve arrived)))
            marker-0 (n/marker head)
            shell    (n/component
                       "app/suspense-shell" :client-only
                       (fn [^js _props]
                         (n/$ react/Suspense
                              {:fallback (n/$ :i {:class "waiting"} "…")}
                              (n/$ head {:label "late"}))))]
        (seat! alpha "lazy")
        (let [container (mount/fresh-container!)
              handle    (mount/root! container alpha [:> shell {}])]
          (testing "before arrival the head is already a native head, and
                    the one field that cannot be known yet says so"
            (is (some? marker-0))
            (is (nil? (unchecked-get marker-0 "name"))))

          (-> (support/wait-until! #(some? (at handle ".arrived")))
              (.then
                (fn [ok?]
                  (is ok? "the lazy component never arrived")
                  (testing "the component mounted where the fallback stood"
                    (is (= "late" (text-at handle ".arrived")))
                    (is (nil? (at handle ".waiting"))))

                  (testing "and the SAME marker object now names it.
                            Narrowing caught: a second marker minted on
                            resolve — a seam that read the head while it was
                            loading would hold a stale one forever"
                    (is (identical? marker-0 (n/marker head)))
                    (is (= "app/arrived" (unchecked-get marker-0 "name")))
                    (is (= "client-only" (unchecked-get marker-0 "server"))))

                  (exercised! :helper/lazy-arrival)
                  (mount/release! handle)
                  (done)))
              (.catch (fail-and-finish! done "lazy arrival" handle))))))))

;; ---------------------------------------------------------------------------
;; 4. Teardown, and the roster
;; ---------------------------------------------------------------------------

(deftest teardown-across-the-bridge-releases-exactly-what-mount-acquired
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "bye")
        (-> (mount-live! alpha [outward-host {:article-id 9}] (label-key alpha) 1)
            (.then
              (fn [handle]
                (let [census (support/teardown-census! handle)]
                  (testing "unmounting the root through the bridge leaves
                            nothing behind. The census is read BEFORE the
                            fixture's page-wide reset, which empties every
                            table by fiat and would read zeros whether the
                            teardown released anything or not.

                            Narrowing caught: a bridge wrapper retaining a
                            cell reference — the screen is gone and the
                            runtime still holds the subscription"
                    (is (= {:cell-refs 0 :boundaries 0 :edges 0} census))))

                (exercised! :bridge/teardown)
                (done)))
            (.catch (fail-and-finish! done "teardown" nil)))))))

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (support/skip! ":node-test has no React DOM, so no mechanism was reached")
    (is (= declared-population @!exercised)
        (str "unreached: " (pr-str (set/difference declared-population
                                                   @!exercised))))))
