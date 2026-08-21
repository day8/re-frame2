(ns re-frame.hicasso.native-abi-dom-cljs-test
  "THE TWO EMBEDDING DIRECTIONS, AND THE HELPERS, UNDER A REAL REACT.

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
  | [[a-uix-parent-mounts-the-same-view-under-the-frame-it-is-already-in]] | the third named parent, on a real fiber, over the SAME minted bridge | UIx's own carrier ABI reaching the bridge — `:argv` where the props should be |
  | [[two-frames-are-two-cells-across-the-bridge]] | frames are isolated contexts on the outward crossing too | resolving the frame anywhere but the surrounding context |
  | [[the-views-memo-wrapper-survives-the-bridge]] | a fresh-but-equal props map still bails the boundary out | a bridge handing the raw object through — every re-render re-runs the body, and the screen is right throughout |
  | [[a-hicasso-body-hosts-a-native-component-through-the-doors-that-exist]] | the inward door, both spellings, with the native ABI intact at the crossing | a door that allocated a props MAP for the island — the second ABI clause 5 forbids |
  | [[two-frames-are-two-cells-through-the-inward-door-as-well]] | frames are isolated contexts on the INWARD crossing too, which is a different mechanism from the outward one | an island resolving one frame for both roots — one key, four readers, two plausible screens |
  | [[n-memo-bails-out-and-still-carries-its-marker-on-the-second-render]] | the helper across a re-render, which is where a stamp gets lost | copying the marker into a per-render wrapper |
  | [[a-fresh-mint-replaces-the-subtree-and-that-is-the-hmr-contract]] | allocation, never a lookup by name | a helper caching by display name so a component outlives a reload |
  | [[strict-modes-double-mount-crosses-the-bridge-exactly-once]] | acquire is commit-owned on both sides of the crossing | a bridge acquiring during render |
  | [[a-ref-reaches-a-real-dom-node-through-the-memo-helper]] | why there is no third helper | a `forward-ref` helper — the row is green with and without it, and the point is that it is green WITHOUT |
  | [[a-lazy-head-suspends-and-then-names-its-own-component]] | the marker is filled by arrival, in the object minted at declaration | a second marker minted on resolve |
  | [[teardown-across-the-bridge-releases-exactly-what-mount-acquired]] | the bridge adds no ownership of its own | a wrapper holding a cell reference past unmount |
  | [[a-consumer-built-root-hydrates-a-bridged-subtree-with-no-framework-reporter]] | HS-21's mismatch-attribution half, measured as a BOUNDARY with its control beside it | a claim that attribution is missing when in truth the harness never diverged |
  | [[the-declared-population-was-actually-exercised]] | the roster, asserted rather than described | a row that started returning early |

  ## HS-21's mismatch attribution, and why it stops at the consumer's root

  Checkpoint 3 recorded row 5's *mismatch attribution* clause as unmet for
  the outward bridge, and it is worth writing down that this is a SCOPE
  rather than a gap.

  A bridged subtree is a child of a root the CONSUMER built with their own
  `createElement` and adopts with their own `hydrateRoot`. Spec 011's
  Hydration-mismatch detection carries the consequence already, in its
  native-adoption section and in as many words: the framework reporter
  rides the package's own hydrate path, and *\"hydrating via the
  substrate-native renderer directly (`uix.dom/hydrate-root`, react-dom
  `hydrateRoot`) bypasses the reporter and falls back to React's default
  (silent) handling\"*. `onRecoverableError` is a ROOT option, and a root
  the package did not open takes none of ours.

  So attribution is scoped to roots the package adopts, and the boundary is
  a consequence of who owns the root rather than an omission in the bridge.
  Nothing here argues that: the row below MEASURES it, and does so with the
  package's own door as the control, so a zero can never be read as a
  harness that failed to diverge.

  What this file does NOT settle is HS-21's disposition row and row 5's
  required-result sentence — `implementation/hicasso/spec/dispositions.md`
  is the ledger keeper's, and a witness may not amend the row it witnesses.

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
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

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
    :bridge/outward-uix
    :bridge/two-frames
    :bridge/memo-bail-out
    :bridge/inward
    :bridge/inward-two-frames
    :helper/memo-across-a-re-render
    :helper/fresh-mint-remounts
    :bridge/strict-mode
    :helper/ref-to-a-node
    :helper/lazy-arrival
    :bridge/teardown
    :bridge/no-reporter})

(defonce ^:private !exercised (atom #{}))

(defn- exercised! [mechanism] (swap! !exercised conj mechanism) nil)

;; ---------------------------------------------------------------------------
;; The consumer's source — one view, bridged outward; one island, hosted
;; inward
;; ---------------------------------------------------------------------------

(defonce ^:private !view-runs (atom 0))
(defonce ^:private !island-runs (atom 0))
(defonce ^:private !island-props (atom nil))

(defonce ^:private !bridged-props
  ;; What the bridged view's body was last handed, held WHOLE. The paint
  ;; can only show what the body chose to print, and `#7` is what a body
  ;; reading `:article-id` prints — but it is also what a body would print
  ;; if the props arrived under some other key and the id happened to be
  ;; reachable anyway. The UIx row asserts the KEY SET, which is the only
  ;; reading that separates those. `defonce` takes no docstring.
  (atom nil))

(h/defview article-card
  "An ordinary boundary. It reads a subscription, so the frame it
  resolved is observable in the cell table rather than only on screen."
  [props]
  (swap! !view-runs inc)
  (reset! !bridged-props props)
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

(defui uix-bridging-parent
  "A UIx parent that renders the very same bridged view.

  The third of the three parents the law names — *Hicasso-native, UIx and
  raw React parents can render Hicasso* — and until this row it was the
  unlanded one. `h/as-component` appeared in exactly two test files and
  under neither did a `defui` sit above it: the native parent bridges on a
  fiber above, and the raw React parent bridges in the NODE lane only,
  where `renderToStaticMarkup` means there is no fiber, no commit and so
  no cell to count.

  It renders [[card]] itself rather than minting a bridge of its own, and
  that is the point rather than economy: ONE minted component reached from
  three parents, so a difference the rows find is a difference between the
  PARENTS and not between three bridges that happened to agree.

  ## What the crossing actually turns on, which is NOT the spelling

  `uix/$` reaches a non-UIx component through `react-component-element`,
  which camelises keys with `uix.compiler.attributes/dash-to-camel` and
  passes values by identity. That rule sends BOTH `:article-id` and
  `:articleId` to the same `articleId` slot, so the bridge cannot tell
  those two authorings apart and a row written against the spelling would
  be asserting nothing — measured, not assumed: planting `:articleId` here
  leaves every assertion in the row green.

  What the crossing does turn on is WHICH ABI arrives. UIx has two, and
  picks between them on the `uix-component?` flag: a `defui` is handed the
  carrier `#js {:argv props}`, while an ordinary React function is handed
  real React props. The minted bridge is an ordinary function and carries
  no flag, so it must receive the second. If it ever received the first,
  the bridge would decode `argv` and the body would be handed a props map
  whose only key is `:argv` — and the `#7` in the paint would go missing
  in a way that reads like a data problem rather than an ABI one. The key
  set below is what names it."
  [{:keys [article-id]}]
  (uix/$ :div
         (uix/$ :i {:class "uix-parent"} "uix")
         (uix/$ card {:article-id article-id})))

(h/defview uix-outward-host
  "The UIx parent, reached from a Hicasso root — the same rung 3 shape
  [[outward-host]] has, so the two rows differ by the parent and by
  nothing else."
  [{:keys [article-id]}]
  (uix/$ uix-bridging-parent {:article-id article-id}))

(defn- island-body
  "The island's body, held apart from any mint so a row can allocate two
  components over ONE body — which is what a module reload does."
  [^js props]
  (swap! !island-runs inc)
  (reset! !island-props props)
  (let [label (n/use-sub [::label])]
    (n/$ :b {:class "island"} (str (.-label props) "/" label))))

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
                      (reset! !bridged-props nil)
                      (collector/reset-runtime!))}))

(defn- seat!
  [frame-kw label]
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [::seed label]))
  nil)

(defn- at [handle sel] (.querySelector ^js (:container handle) sel))
(defn- text-at [handle sel] (some-> (at handle sel) .-textContent))
(defn- click! [handle sel] (.click ^js (at handle sel)) (mount/settle!) nil)

(defn- island-texts
  "The text of every island in `handle`'s container, in document order.

  `array-seq` and not `map` over the `NodeList` directly: a `NodeList` is
  array-LIKE and ES6-iterable but implements no ClojureScript protocol, so
  `count` and `seq` throw on it rather than answering."
  [handle]
  (mapv #(.-textContent ^js %)
        (array-seq (.querySelectorAll ^js (:container handle) ".island"))))

(defn- label-key [frame-kw] [frame-kw [::label]])
(defn- readers-of [sub-key] (inventory/cell-readers sub-key))

(defonce ^:private !minted
  ;; Every root a row has minted through `mount-live!`, oldest first.
  ;; Emptied by `release-minted!` on the row's single trailing step.
  ;; `defonce` takes no docstring, hence the comment.
  (atom []))

(defn- release-minted!
  "Release every root this row minted, and forget them. Rides the single
  trailing step, which BOTH arms reach, so the teardown is written once
  and runs once per path; `mount/release!` is idempotent, so a row whose
  success path already tore its root down pays nothing here.

  **Why the rejection arm cannot do this itself.**
  [[mount-live!]] mints its root SYNCHRONOUSLY and hands it over only
  once the wait succeeds, so a rejection reaches the arm with a live root
  the arm has no name for — the wait's own deadline is one such path, and
  a throw anywhere in the row body above is another. Reporting and
  finishing there leaves a mounted React root and its container standing
  in the document for the NEXT namespace to inherit, which is the very
  contamination this teardown exists to prevent, arriving by a second
  door."
  []
  (run! mount/release! @!minted)
  (reset! !minted [])
  nil)

(defn- mount-live!
  "Mount `hiccup` under `frame-kw` and return only once `sub-key` has
  exactly `readers` subscribed readers — a commit-owned fact, so a row
  that started before it would be measuring a tree that had not yet
  joined the runtime.

  Enrols the root in [[!minted]] the instant it exists, because from that
  instant until [[release-minted!]] runs there is a live root on the page
  and this promise is the only thing that could ever name it."
  [frame-kw hiccup sub-key readers]
  (let [container (mount/fresh-container!)
        handle    (mount/root! container frame-kw hiccup)]
    (swap! !minted conj handle)
    (-> (support/wait-until! #(= readers (count (readers-of sub-key))))
        (.then (fn [subscribed?]
                 (when-not subscribed?
                   (throw (ex-info (str "expected " readers " reader(s) on "
                                        (pr-str sub-key))
                                   {:residue (inventory/residue)})))
                 handle)))))

(defn- report-failure!
  "Reports a rejection against `label`; it does NOT finish the row.

  `done` hands `cljs.test/run-block` a continuation that runs the WHOLE
  remainder of the run synchronously, so a `.catch` sitting downstream of the
  step that finished the row claims whatever a LATER namespace throws, prints
  it against this row's label, and calls `done` a SECOND time — re-forcing
  `run-block`'s unrealized delay and re-running the offending namespace, which
  `run-browser-tests.cjs` promotes to a fatal console match. Every
  chain below therefore reports here and finishes on a single trailing step,
  with nothing after it."
  [label]
  (fn [e]
    (is false (str label " — " (.-message e)
                   " | residue " (pr-str (inventory/residue))))
    nil))

;; Teardown IS hoisted onto those trailing steps, through [[release-minted!]].
;; That the rejection arm never had the handle — `mount-live!` resolves WITH
;; it — is not a reason to leave the teardown on the success path; it is the
;; defect. The root is already on the page when the promise is
;; created, so the arm that cannot name it is the arm that strands it.
;; `a-lazy-head-suspends-and-then-names-its-own-component` holds its handle in
;; an enclosing `let` and hoists that one directly — it says so there.

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
                nil))
            (.catch (report-failure! "outward bridge"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

(deftest a-uix-parent-mounts-the-same-view-under-the-frame-it-is-already-in
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "seed")
        (-> (mount-live! alpha [uix-outward-host {:article-id 7}] (label-key alpha) 1)
            (.then
              (fn [handle]
                (testing "the premise: a UIx parent really did render, and the
                          bridged view is INSIDE its DOM rather than beside it.
                          Without this the assertions below are satisfied by a
                          tree that skipped the parent entirely"
                  (is (= "uix" (text-at handle ".uix-parent")))
                  (is (some? (at handle ".uix-parent + .card"))))

                (testing "the view painted the props UIx lowered and the bridge
                          decoded — `:article-id` out through `dash-to-camel`,
                          `articleId` across, `:article-id` back"
                  (is (= "#7 seed" (text-at handle ".card"))))

                (testing "and the body was handed REACT props, decoded into its
                          own map — not UIx's carrier. Narrowing caught: the
                          bridge reached through UIx's `defui` path, which
                          hands `#js {:argv props}`; the body's key set would
                          be `#{:argv}` and the paint would lose its `#7` in a
                          way that reads like a data problem rather than the
                          ABI one it is.

                          The key set, not the paint, is what separates those:
                          the paint can only show what the body chose to
                          print"
                  (is (= #{:article-id} (set (keys @!bridged-props)))))

                (testing "its read built ONE cell, under the frame the
                          SURROUNDING root installed — the bridge resolved no
                          frame of its own on this parent either. The fixture
                          seats no ambient frame, so a bridge that failed to
                          read the context has nothing to fall back to, and
                          this key is what says which happened"
                  (is (= #{(label-key alpha)} (support/cell-keys)))
                  (is (= 1 (count (readers-of (label-key alpha))))))

                (testing "and a write through the surrounding frame reaches the
                          bridged view, so the crossing is a place in the
                          application rather than an island of its own state
                          — the same sentence the native parent's row ends on"
                  (rf/with-frame alpha (rf/dispatch-sync [::relabel "moved"]))
                  (mount/settle!)
                  (is (= "#7 moved" (text-at handle ".card"))))

                (exercised! :bridge/outward-uix)
                (support/teardown-census! handle)
                nil))
            (.catch (report-failure! "outward bridge under a UIx parent"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "two frames across the bridge"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "memo across the bridge"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "inward door"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

(deftest two-frames-are-two-cells-through-the-inward-door-as-well
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM") (done))
      (do
        (seat! alpha "A")
        (seat! beta "B")
        (-> (mount-live! alpha [inward-host {:tick 0}] (label-key alpha) 2)
            (.then (fn [a]
                     (-> (mount-live! beta [inward-host {:tick 0}] (label-key beta) 2)
                         (.then (fn [b] [a b])))))
            (.then
              (fn [[a b]]
                (testing "one hosting body, two frames, four islands — and each
                          island read the frame of the root it was mounted
                          under, through the door rather than around it"
                  (is (= ["hosted/A" "raw/A"] (island-texts a)))
                  (is (= ["hosted/B" "raw/B"] (island-texts b))))

                (testing "and TWO keys, differing only in their frame, with two
                          readers on each. Narrowing caught: an island
                          resolving one frame for both roots — there would be
                          ONE key here carrying four readers, and both screens
                          would still be full of plausible text.

                          This is the INWARD half of the law's `across two
                          frames`, and it is a different mechanism from the
                          outward half above rather than the same one seen
                          twice: the outward bridge reads the frame in the
                          minted component's own wrapper, while an island
                          reads it through `n/use-sub`'s hook on the far side
                          of a crossing that never names a tier"
                  (is (= #{(label-key alpha) (label-key beta)}
                         (support/cell-keys)))
                  (is (= [2 2]
                         [(count (readers-of (label-key alpha)))
                          (count (readers-of (label-key beta)))])))

                (testing "a write to one frame moves one page's islands and
                          leaves the other's alone. Frames are isolated
                          contexts, and a foreign React subtree is not a hole
                          in that"
                  (rf/with-frame alpha (rf/dispatch-sync [::relabel "A'"]))
                  (mount/settle!)
                  (is (= ["hosted/A'" "raw/A'"] (island-texts a)))
                  (is (= ["hosted/B" "raw/B"] (island-texts b))))

                (exercised! :bridge/inward-two-frames)
                (support/teardown-census! a)
                (support/teardown-census! b)
                nil))
            (.catch (report-failure! "two frames through the inward door"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "memo across a re-render"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "fresh mint"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                nil))
            (.catch (report-failure! "strict mode"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

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
                  nil))
              (.catch (report-failure! "lazy arrival"))
              ;; Unlike the `mount-live!` rows, THIS row holds its handle in
              ;; the enclosing `let` rather than receiving it from the promise,
              ;; so both arms really did release the same one — and the release
              ;; rides the single trailing step: written once, run once per
              ;; path, with the single `done` behind it.
              (.then (fn [_] (mount/release! handle) (done)))))))))

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
                    (is (= support/released census))))

                (exercised! :bridge/teardown)
                nil))
            (.catch (report-failure! "teardown"))
            ;; The single trailing step, which BOTH arms reach: this row's
            ;; roots go down first, and the single `done` is the last act,
            ;; with nothing after it.
            (.then (fn [_] (release-minted!) (done))))))))

;; ---------------------------------------------------------------------------
;; 5. HS-21's mismatch attribution — the boundary, with the door as its control
;; ---------------------------------------------------------------------------

(defn- consumer-element
  "The tree a CONSUMER writes for a bridged view: their own
  `createElement` over the minted [[card]], under the frame provider the
  bridge needs — and nothing else.

  `impl.mount/tree` is deliberately not reached for. That function is what
  wraps a hydrating root in its adoption-window closer and is called only
  from doors that also pass root options; a consumer who has bridged a view
  into a tree of their own calls neither. So this element is the exact
  shape the package cannot install an `onRecoverableError` on, and building
  it by hand is what keeps the row about the consumer's root rather than
  about a door with its options removed."
  [frame-kw article-id]
  (mount/provider
    frame-kw
    (react/createElement "section" #js {:className "consumer"}
                         (react/createElement card #js {:articleId article-id}))))

(defn- relabel!
  "Move `frame-kw`'s label after its server bytes were taken, so the
  client tree disagrees with the DOM it is about to adopt. A TEXT
  divergence specifically: React recovers from those and reports them,
  where an attribute-only divergence is outside `onRecoverableError` by
  React's own contract (Spec 011 §Hydration-mismatch detection), and a row
  built on one would read zero on BOTH arms and prove nothing."
  [frame-kw label]
  (rf/with-frame frame-kw (rf/dispatch-sync [::relabel label]))
  nil)

(deftest a-consumer-built-root-hydrates-a-bridged-subtree-with-no-framework-reporter
  (async done
    (if-not (mount/browser?)
      (do (support/skip! ":node-test has no React DOM, so nothing hydrates") (done))
      (do
        (seat! alpha "server")
        (seat! beta "server")
        (let [;; CONTROL FIRST, and the order is load-bearing: the arm that
              ;; must see a diagnostic runs before the arm that must see
              ;; none, so a zero below can never be a harness that stopped
              ;; diverging.
              control-html      (support/server-html! alpha [article-card {:article-id 7}])
              control-container (support/server-dom! control-html)
              control-watch     (support/watch-mismatches!)
              _                 (relabel! alpha "client")
              ;; MANUFACTURED fault, asserted on — the one call site
              ;; `:swallow-uncaught?` belongs at.
              control-console   (support/open-console-capture! {:swallow-uncaught? true})
              control-handle    (mount/hydrate-root! control-container alpha
                                                     [article-card {:article-id 7}])]
          (-> (support/adopted! control-handle)
              (.then
                (fn [ok]
                  ((:close! control-console))
                  ((:stop! control-watch))
                  (testing "CONTROL — the package's OWN door, the same
                            divergence. `impl.mount/hydrate-root!` opens the
                            root, so the root's options are the package's to
                            set and Spec 011's diagnostic fires with this
                            door's site on it. Without this arm the row below
                            is an absence with no meaning"
                    (is (true? ok) "the adoption completed")
                    (is (= 1 (count @(:seen control-watch)))
                        (str "the door emitted exactly one "
                             ":rf.ssr/hydration-mismatch. Saw: "
                             (pr-str @(:seen control-watch))))
                    (when-let [mm (first @(:seen control-watch))]
                      (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                             (:where (support/tags-of mm)))
                          "tier-discriminated by :where — this door's site"))
                    (is (seq @(:captured control-console))
                        (str "and React itself complained, which is what says
                              the divergence was real: "
                             (pr-str @(:captured control-console)))))
                  (mount/unmount! control-handle)
                  nil))
              (.then
                (fn [_]
                  ;; THE MEASUREMENT. Same view, same divergence, same
                  ;; renderer — a root the consumer opened.
                  (let [html      (react-dom-server/renderToString
                                    (consumer-element beta 7))
                        container (support/server-dom! html)
                        watch     (support/watch-mismatches!)
                        _         (relabel! beta "client")
                        console   (support/open-console-capture! {:swallow-uncaught? true})
                        root      (react-dom-client/hydrateRoot
                                    container (consumer-element beta 7))]
                    (-> (support/wait-until!
                          #(= "#7 client" (some-> (.querySelector container ".card")
                                                  .-textContent)))
                        (.then
                          (fn [recovered?]
                            ((:close! console))
                            ((:stop! watch))
                            (testing "the server's bytes really did say
                                      `server` and the consumer's root really
                                      did adopt them — the premise, so the
                                      zero below is about attribution and not
                                      about a render that never happened"
                              (is (re-find #"#7 server" html)
                                  (str "the bridged view rendered under the
                                        consumer's own createElement — " html))
                              (is (true? recovered?)
                                  "React recovered the text divergence by
                                   replacing it with the client's model")
                              (is (seq @(:captured console))
                                  (str "and complained while doing it: "
                                       (pr-str @(:captured console)))))
                            (testing "**HS-21, measured.** A mismatch inside a
                                      bridged subtree is attributed to NOTHING
                                      the instrumentation stream can see. The
                                      reporter is a ROOT option and this root
                                      is the consumer's, so no Spec 011
                                      diagnostic is emitted — the scope Spec
                                      011 states for direct `hydrateRoot`
                                      hydration, here as a reading rather than
                                      as a sentence.

                                      This row inverts the day the package
                                      grows a door for a consumer-built root;
                                      it is not to be re-pinned by loosening
                                      the assertion"
                              (is (= [] @(:seen watch))
                                  (str "a consumer-built root installs no "
                                       ":rf.ssr/hydration-mismatch reporter. "
                                       "Saw: " (pr-str @(:seen watch)))))
                            (.unmount root)
                            (exercised! :bridge/no-reporter)
                            nil))))))
              (.catch (report-failure! "no-reporter"))
              (.then (fn [_] (release-minted!) (done)))))))))

(deftest the-declared-population-was-actually-exercised
  (if-not (mount/browser?)
    (support/skip! ":node-test has no React DOM, so no mechanism was reached")
    (is (= declared-population @!exercised)
        (str "unreached: " (pr-str (set/difference declared-population
                                                   @!exercised))))))
