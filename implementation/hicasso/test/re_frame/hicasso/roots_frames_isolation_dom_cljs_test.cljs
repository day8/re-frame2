(ns re-frame.hicasso.roots-frames-isolation-dom-cljs-test
  "TWO LIVE ROOTS, TWO FRAMES — and nothing crosses between them
  (rf2-hic-012).

  The bead's first and fourth deliverables: two roots dispatching,
  subscribing and unmounting independently with zero cross-frame reads or
  dispatches, and a root whose teardown FAILS unable to strand the other
  root's state.

  ## What is actually at risk

  Almost everything this runtime holds is one-per-page. The cell table,
  the read-set entry cache, the scratch buffer, the render-state object,
  the frame-op memos, the generation counter: one each, for the whole
  process (`impl.collector`, `impl.frames`, `impl.generation`;
  `impl.inventory/retained-inventory` enumerates them). Isolation between
  two roots is therefore not a property React provides — React knows
  nothing about any of those tables — it is a property of how they are
  KEYED. A sub-key is `[frame-kw query-v]`, and that qualification is the
  entire mechanism.

  ## So the DOM is not where these rows are read

  A boundary that resolved the wrong frame still renders a value, and
  React still repairs the tree to something plausible by the end of the
  event. Read at `.-textContent` after the dust settles, a total
  isolation failure can look like a working page. Every row below is
  therefore taken on one of three observables React cannot forge — the
  cell table's KEY SET, the reader count per key, and the monotone
  body-run counter — with the DOM read afterwards as corroboration only.
  `re-frame.hicasso.roots-frames-support` states each choice and why.

  ## Frames are isolated contexts

  That is the standing rule, not a convenience of this file: a
  subscription must not reach into another frame, and a multi-frame
  witness mounts ONE app into N isolated frames rather than passing
  frame-ids around as view arguments. [[panel]] below therefore takes no
  frame argument at all. It is one view, mounted twice; the only thing
  that differs between the two mounts is the root each one sits under."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(def ^:private label-q [::label])
(def ^:private count-q [::count])

;; Registered ABOVE `use-fixtures`, deliberately — the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED, so a
;; `reg-sub` written below it is erased before the first row runs and every
;; screen renders nothing.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-sub ::count (fn [db _] (:count db)))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label :count 0}}))
(rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `nil` and not the default: the default leaves a dynamic-var frame
     ;; stamp in ambient scope, and a boundary that failed to resolve its
     ;; own frame could then answer that one instead — an isolation miss
     ;; would read as a rendering difference rather than as the failure it
     ;; is. This suite makes its own frames.
     :ambient-frame nil
     ;; The MAP shape, because rows below are `async`. `cljs.test` refuses
     ;; an async test under a fn-form fixture outright — "Async tests
     ;; require fixtures to be specified as maps. Testing aborted." — and
     ;; that aborts the whole browser run at this namespace, not just this
     ;; file.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app — ONE view, mounted twice
;; ---------------------------------------------------------------------------

(h/defview panel
  "The whole app. Reads two subscriptions and carries one intent, and
  takes no frame argument: the frame arrives from the root it is mounted
  under, through the substrate's single internal React context.

  `data-frame` renders the frame the boundary itself resolved. It is
  corroboration and never the primary reading — a constant would satisfy
  a single-frame row, which is why the rows below assert it against BOTH
  frames or not at all."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag :data-frame (str (h/hframe))}
   [:span.label (h/sub label-q)]
   [:span.count (str (h/sub count-q))]
   [:button.bump {:on-click [::bump]} "bump"]])

(defn- exploding-cleanup-component
  "A foreign React component whose unmount cleanup THROWS.

  The manufactured teardown failure the containment row needs, placed
  where a real one would be: in a consumer's own effect cleanup, run by
  React during the root's deletion. React routes a commit-phase error to
  the root's uncaught-error handling rather than out of `.unmount`, so
  the row captures it on the window channel and asserts it arrived —
  a containment row whose failure never happened would be vacuous."
  [_props]
  (react/useEffect
    (fn arm-the-charge []
      (fn detonate []
        (throw (js/Error. "rf2-hic-012 manufactured teardown failure"))))
    #js [])
  nil)

(h/defhost exploder exploding-cleanup-component {:ssr :render})

(h/defview fragile-panel
  "[[panel]]'s tree with the charge attached. Same subscriptions, same
  intent, so the two roots stay comparable."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag :data-frame (str (h/hframe))}
   [:span.label (h/sub label-q)]
   [:span.count (str (h/sub count-q))]
   [:button.bump {:on-click [::bump]} "bump"]
   [exploder {}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh!
  "Two frames, seeded differently, and an empty runtime.

  The two labels differ so a cross-frame READ is legible in the markup as
  well as in the tables; the counts start equal so a cross-frame DISPATCH
  is legible as a difference that appears."
  []
  (sup/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf/with-frame frame-b (rf/dispatch-sync [::seed "beta"]))
  (collector/reset-runtime!)
  (collector/reset-body-runs!)
  nil)

(defn- text-at [handle sel]
  (some-> (.querySelector (:container handle) sel) .-textContent))

(defn- attr-at [handle sel a]
  (some-> (.querySelector (:container handle) sel) (.getAttribute a)))

(defn- keys-for
  "The live sub-keys belonging to `frame-kw`."
  [frame-kw]
  (into #{} (filter (fn [[f _]] (= frame-kw f))) (sup/cell-keys)))

;; ---------------------------------------------------------------------------
;; W1 — the cell table splits by frame
;; ---------------------------------------------------------------------------

(deftest two-roots-under-two-frames-split-the-cell-table
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (mount/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
          b (mount/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
      (try
        (testing "one view, two frames, two reads each — four cells, and the
                  frame is what tells them apart"
          (is (= #{[frame-a label-q] [frame-a count-q]
                   [frame-b label-q] [frame-b count-q]}
                 (sup/cell-keys))
              (str "the cell table must be keyed by (frame, query); got "
                   (pr-str (sup/cell-keys))))
          (is (= #{frame-a frame-b} (sup/cell-frames))
              "both frames must appear — a runtime that resolved one frame
               for both mounts would show one"))

        (testing "and each key is read by exactly ONE boundary — the shape a
                  leak destroys, because a leak is two readers on one key
                  rather than one reader on each of two"
          (is (= [1 1 1 1]
                 [(sup/readers-of [frame-a label-q]) (sup/readers-of [frame-a count-q])
                  (sup/readers-of [frame-b label-q]) (sup/readers-of [frame-b count-q])])
              (str "reader counts: " (pr-str (inventory/residue)))))

        (testing "two frame-op bundles, not one — `capture-frame` pins an
                  incarnation, and the memo is keyed by frame"
          (is (= 2 (:frames (inventory/stats)))))

        (testing "the markup corroborates, and it corroborates on BOTH frames
                  — a constant would satisfy either one alone"
          (is (= "alpha" (text-at a ".label")))
          (is (= "beta"  (text-at b ".label")))
          (is (= (str frame-a) (attr-at a ".panel" "data-frame")))
          (is (= (str frame-b) (attr-at b ".panel" "data-frame"))))

        (finally (mount/release! a) (mount/release! b))))))

;; ---------------------------------------------------------------------------
;; W2 — a dispatch moves one frame, and re-runs one body
;; ---------------------------------------------------------------------------

(deftest a-dispatch-is-frame-local-in-bodies-and-in-state
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (mount/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
          b (mount/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
      (try
        (testing "one dispatch into frame A re-runs exactly one body"
          (let [ran (sup/body-runs-delta! (fn [] (mount/dispatch! a [::bump])))]
            (is (= 1 ran)
                (str "a commit in one frame must notify only that frame's
                      readers; " ran " bodies ran"))))

        (testing "and only frame A's state moved"
          (is (= "1" (text-at a ".count")))
          (is (= "0" (text-at b ".count"))))

        (testing "the counter can read 2, so `1` above was a discrimination
                  and not a ceiling — both frames are exercised, which is
                  what stops this row passing vacuously"
          (let [ran (sup/body-runs-delta!
                      (fn [] (mount/dispatch! a [::bump])
                             (mount/dispatch! b [::bump])))]
            (is (= 2 ran)))
          (is (= "2" (text-at a ".count")))
          (is (= "1" (text-at b ".count"))))

        (finally (mount/release! a) (mount/release! b))))))

;; ---------------------------------------------------------------------------
;; W3 — the shipped intent path, driven by a real DOM click
;; ---------------------------------------------------------------------------

(deftest a-real-click-dispatches-into-its-own-root-and-no-other
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (let [_ (fresh!)
          a (mount/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
          b (mount/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
      (try
        (testing "a click on root A's button — the shipped intent path, on a
                  real discrete browser event — reaches frame A alone"
          (let [ran (sup/body-runs-delta!
                      (fn [] (.click (.querySelector (:container a) ".bump"))))]
            (is (= 1 ran) "one body ran"))
          (is (= "1" (text-at a ".count")))
          (is (= "0" (text-at b ".count"))))

        (testing "and root B's button reaches frame B alone — BOTH crossings
                  are clicked, so an intent that had resolved one
                  last-rendered frame for both would show here"
          (.click (.querySelector (:container b) ".bump"))
          (mount/settle!)
          (is (= "1" (text-at a ".count")))
          (is (= "1" (text-at b ".count"))))

        (finally (mount/release! a) (mount/release! b))))))

;; ---------------------------------------------------------------------------
;; W4 — independent unmount
;; ---------------------------------------------------------------------------

(deftest unmounting-one-root-releases-its-keys-and-leaves-the-other-live
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (let [_ (fresh!)
            a (mount/root! (mount/fresh-container!) frame-a [panel {:tag "a"}])
            b (mount/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])]
        ;; `unmount!` and NOT `release!`: `release!` resets the runtime, so
        ;; every count below would read zero whether the teardown released
        ;; anything or not.
        (mount/unmount! a)
        (-> (sup/quiesced!)
            (.then
              (fn [_]
                (try
                  (testing "frame A's keys are gone and frame B's are untouched"
                    (is (= #{} (keys-for frame-a))
                        (str "frame A's cells should be reaped; got "
                             (pr-str (keys-for frame-a))))
                    (is (= #{[frame-b label-q] [frame-b count-q]} (keys-for frame-b))
                        (str "frame B's cells must survive its sibling's
                              teardown; got " (pr-str (keys-for frame-b))))
                    (is (= #{frame-b} (sup/cell-frames))))

                  (testing "and the survivor is LIVE, not merely present — it
                            still re-runs its body and still moves its DOM"
                    (let [ran (sup/body-runs-delta! (fn [] (mount/dispatch! b [::bump])))]
                      (is (= 1 ran)))
                    (is (= "1" (text-at b ".count")))
                    (is (= "beta" (text-at b ".label"))))

                  (testing "and tearing the survivor down leaves nothing at all"
                    (is (= sup/released (sup/teardown-census! b))))
                  (finally
                    (mount/release! a)
                    (mount/release! b)
                    (done))))))))))

;; ---------------------------------------------------------------------------
;; W5 — a failing teardown is contained
;; ---------------------------------------------------------------------------

(deftest a-root-whose-teardown-throws-cannot-strand-its-siblings-state
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (let [_ (fresh!)
            a (mount/root! (mount/fresh-container!) frame-a [fragile-panel {:tag "a"}])
            b (mount/root! (mount/fresh-container!) frame-b [panel {:tag "b"}])
            ;; `:swallow-uncaught?` is legitimate at exactly this call site
            ;; and nowhere else in these suites: the error below is
            ;; MANUFACTURED by this row and asserted on by this row.
            {:keys [captured close!]} (sup/open-console-capture! {:swallow-uncaught? true})]
        (is (= #{frame-a frame-b} (sup/cell-frames))
            "precondition: both roots are live before the charge goes off")
        (mount/unmount! a)
        (-> (sup/quiesced!)
            (.then
              (fn [_]
                (close!)
                (try
                  (testing "premise: the teardown really did fail"
                    (is (some #(re-find #"manufactured teardown failure" %) @captured)
                        (str "the cleanup's throw must reach a complaint channel,
                              or this row is asserting containment of nothing; got "
                             (pr-str @captured))))

                  (testing "the surviving root's state is intact — its keys, its
                            reader counts, and the frame-op bundle it dispatches
                            through"
                    (is (= #{[frame-b label-q] [frame-b count-q]} (keys-for frame-b)))
                    (is (= [1 1] [(sup/readers-of [frame-b label-q])
                                  (sup/readers-of [frame-b count-q])]))
                    (is (contains? (sup/cell-frames) frame-b)))

                  (testing "and it is still LIVE — a dispatch still re-runs its
                            body and still moves its DOM, which a stranded
                            runtime could not do"
                    (let [ran (sup/body-runs-delta! (fn [] (mount/dispatch! b [::bump])))]
                      (is (= 1 ran)))
                    (is (= "1" (text-at b ".count"))))

                  (testing "and the failing root took its own keys with it — the
                            error was in a consumer's cleanup, downstream of
                            React's own release of the boundary"
                    (is (= #{} (keys-for frame-a))
                        (str "got " (pr-str (keys-for frame-a)))))
                  (finally
                    (mount/release! a)
                    (mount/release! b)
                    (done))))))))))
