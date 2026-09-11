(ns day8.re-frame2-xray.panels.epoch-machine-mount-instance-id-dom-cljs-test
  "TWO STANDALONE `mount-epoch-panel!` MOUNTS IN ONE FRAME, AND AN EPOCH
  PANEL BESIDE A MACHINE INSPECTOR SHARING ONE CASCADE, read off real React
  commits (rf2-3ymg).

  ## The claim, and why it needs a DOM

  rf2-k97c.3 replaced thirteen inline EDN heads in `panels/epoch/view` with
  `ei/edn-inspector-view`, each carrying a `:mount-id` composed from a
  LOGICAL site — `\"epoch/dispatch-event\"` is a CONSTANT, and the machine
  cascade's three are a role plus a step ordinal. Those separate the roles
  within ONE panel and cannot separate two live mounts, which is a different
  question.

  TWO collisions follow, and the second is the sharper one:

    * two standalone Epoch panels over one focused epoch in one frame
      compose IDENTICAL mount-ids, role for role; and
    * an Epoch panel and a MACHINE INSPECTOR displaying the same machine
      cascade compose identical mount-ids too — because they render it
      through the SAME `machine-cascade-mini-pipeline`, off the SAME
      `projection/machine-cascade-rows`, which is the whole point of
      rf2-g2axio's extraction. The Machine Inspector is a GUEST in the
      Epoch panel's id namespace and had no way to say so.

  A row asserting an opts key was THREADED would pass while both mounts
  still collided, which is the failure one level up. So nothing here reads
  the opts map, the captured tree or the props. The mounts are made through
  the PUBLIC facades into real containers, and every assertion reads
  `container.querySelector` — the DOM React committed on its own — the
  widget's own per-mount store, or the frame's app-db width slot.

  ## The two observables, and the SECOND is the one the defect breaks

  `data-rf-mount-id` is stamped by the edn-inspector widget on every
  committed container, carrying the BARE `:mount-id` the panel composed. It
  is not decoration: `edn-inspector/container-ref-for` memoises the ref
  callback on `[frame-id mount-id]`, `measure-and-dispatch!` writes the
  measured width to `:rf.xray.edn-inspector/widths` under that same bare
  string, and `release-mount!` clears BOTH when a mount detaches.

  [[two-named-epoch-mounts-each-keep-their-own-observer-and-width]] is the
  half a distinctness row would miss. Under the shared identity the SECOND
  mount never installs a ResizeObserver at all (`container-ref-for` hands
  back the memoised callback, whose mount arm is guarded on
  `(nil? (:observer entry))`), and `release-mount!` then tears the SHARED
  entry down — and clears the SHARED width — when EITHER mount detaches,
  leaving the survivor on screen with no observer and no width.

  ## WHY THE TWO PANELS ARE FIXED BY DIFFERENT MEANS, and that is the shape
  ## finding rather than a copy of the Trace landing's

  ONE qualifier, on the `:mount-id` ALONE — every one of the thirteen sites
  passes a stable `:site-id` alongside it, and the widget's `effective-id`
  is `(or site-id mount-id)`, so expansion and zoom are keyed by the
  site-id and DO NOT READ THE MOUNT-ID AT ALL. That much this surface
  shares with Trace, and [[two-named-epoch-mounts-share-their-disclosure-identity]]
  pins the half that must NOT move.

  What it does NOT share is where the qualifier comes from. The two-Epoch
  collision is between two mounts of ONE panel, which nothing inside the
  panel can tell apart, so the CALLER names them — the optional
  `:instance-id` the three shipped panels already take. The
  Epoch-vs-Machine-Inspector collision is between two DIFFERENT panels, and
  which panel is rendering is STATICALLY KNOWN: no caller should have to
  work around a shared helper's id namespace, and a caller embedding one of
  each cannot see the collision to work around it. So the Machine
  Inspector's own normaliser never answers nil — it names ITSELF, and the
  caller's `:instance-id` qualifies further on top.
  [[epoch-and-machine-inspector-do-not-share-cascade-mount-ids]] mounts one
  of each with NO opts at all and is the row that states it.

  ## The negative control IS the defect, and it is a row rather than a note

  [[two-unnamed-epoch-mounts-still-collide]] mounts two Epoch panels with no
  `:instance-id` and asserts the id sets are IDENTICAL. It carries two
  claims at once: the instrument can see a collision (so the disjointness
  above is separation and not silence), and omitting the opt leaves every
  Epoch id byte-for-byte what it always was — which is every call site in
  this tree today.

  ## Substrate: the Reagent adapter, and the mounts are the PUBLIC ones

  `mount-epoch-panel!` and `mount-machine-inspector!` delegate to
  `rf.substrate.adapter/render`, so the installed adapter is what renders.
  Nothing here reaches past the facades to build a tree of its own — the
  things under test are the mount fns.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex also matches, so it LOADS under Node — where every row
  short-circuits through [[browser?]] and reports the skip rather than
  passing silently. A green node lane is therefore NOT evidence about this
  file; `npm run test:browser` is. The node lane cannot see a
  ResizeObserver at all, and `expand-tree` INVOKES a fn head, so it cannot
  grade head legality here either."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as string]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; READ-ONLY, and only for the per-mount store's public test
            ;; surface (`lifecycle-key`, `mount-state-held`) and the public
            ;; width-slot key. The widget's own lifecycle rows live in
            ;; `views/edn_inspector_mount_state_cljs_test`; what W2 below
            ;; asserts is that two STANDALONE MOUNTS hand that store two
            ;; distinct keys, each with its own observer and its own width.
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W2 is an `async` row, and `cljs.test` refuses
     ;; a FUNCTION fixture in any namespace that carries one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about, and
                      ;; both panels' roots are Fresco boundaries — a
                      ;; neighbour's entry left in the cache would make these
                      ;; rows read a residue that is not theirs.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the seeded epoch ----------------------------------------------------

(def ^:private machine-id :auth/login)

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(def ^:private fixture-history
  "ONE epoch carrying BOTH a dispatch and a machine macrostep, so the one
  seeding drives both panels: the Epoch panel projects a DISPATCH step (the
  constant `epoch/dispatch-event` mount-id) and — because a
  `:rf.machine/transition` classifies the handler `:reg-machine` — a HANDLER
  step whose `:machine {:cascade …}` renders through the shared
  mini-pipeline, while the Machine Inspector renders that SAME cascade off
  `:rf.xray/machine-focused-epoch-cascade`, which calls the SAME
  `projection/machine-cascade-rows` over the SAME `:trace-events`."
  [{:epoch-id      1
    :dispatch-id   "d-1"
    :event         [:auth/submit]
    :trigger-event [:auth/submit]
    :trace-events
    [{:id 1 :time 10 :operation :rf.event/dispatch
      :tags {:event [:auth/submit] :rf.trace/dispatch-id "d-1"}}
     {:id 2 :time 20 :operation :rf.machine/transition
      :tags {:machine-id machine-id
             :before     {:state :idle :data {}}
             :after      {:state :authing :data {}}
             :event      [:auth/submit]
             :rf.trace/dispatch-id "d-1"}}]}])

;; ---- the seeded DIAGNOSTICS epoch (rf2-1ar7) ------------------------------
;;
;; A SECOND fixture rather than seeds bolted onto `fixture-history`, and that
;; is deliberate. W1-W5 grade the ORDINARY payload mounts, and three of them
;; count ids or compare whole id sets; folding diagnostics into their epoch
;; would move those counts for reasons that have nothing to do with what they
;; assert. The rows below seed their own epoch and leave the five above
;; reading exactly what they read before.
;;
;; rf2-3ymg threaded the qualifier to every step that mounts an inspector and
;; recorded `:interceptor` as a step that mounts none. It mounts none on a
;; CLEAN cascade — the step is conditional on a throw — and one per throwing
;; row carrying `ex-data`. The three sites this epoch reaches are the three
;; that still dropped the argument at the merge commit of #9680:
;;
;;   * the INTERCEPTOR row's exception card, whose `ex-data` disclosure mounts
;;     under `epoch/error-ex-data/<testid-base>`. Its whole chain
;;     (`render-interceptor-step` → `interceptor-row-view` → `error-blocks`)
;;     had no instance parameter at all.
;;   * the PER-ROW subscription violation explainer, reached through
;;     `subscriptions-table`'s `:row-extras` — which held the instance and
;;     used it one line above, in the value cell.
;;   * the STEP-LEVEL subscription violation explainer at the foot of
;;     `render-subscriptions-step`.

(def ^:private throwing-interceptor-id :audit/log)
(def ^:private violating-sub-id :cart/preview)

(def ^:private diagnostics-history
  "ONE epoch that RENDERS all three diagnostics, so two mounts of it compose
  three qualified ids each.

  Three shape requirements, each measured rather than assumed:

    * the interceptor exception must carry a real `ex-info`. `error-block-
      details` mounts the inspector only `(when (seq ex-data*))`, so a bare
      message throws the card without the mount this row is about.
    * the subscription row must carry `:rf.sub/value-changed? true`. The
      SUBSCRIPTIONS step's filter mode defaults to `:changed`, so an
      unchanged row is filtered out of `visible-rows` BEFORE
      `subscriptions-table` sees it and its inline violation never renders.
    * the two violations split per-row vs step-level ON `:failing-id` alone.
      `attach-to-sub-row` attaches to the row whose `:sub-id` matches, and
      falls back to the step-level bucket when none does — so the second
      violation names a sub that recomputed nowhere in this cascade, which
      is the real shape it models (an indirect recompute outside the
      cascade's surfaced rows).

  The throw is `:after` rather than `:before`: a `:before` throw aborts the
  chain, and `mark-skipped-handler` then stamps the HANDLER and SIDE EFFECTS
  steps `:skipped`. Nothing here grades those, but an `:after` throw is the
  cascade in which the subscriptions actually ran, which is the one that
  carries all three diagnostics at once."
  [{:epoch-id      1
    :dispatch-id   "d-1"
    :event         [:cart/set "bad"]
    :trigger-event [:cart/set "bad"]
    :trace-events
    [{:id 1 :time 10 :operation :rf.event/dispatch
      :tags {:event [:cart/set "bad"] :rf.trace/dispatch-id "d-1"}}
     ;; INTERCEPTOR step — one throwing row, carrying ex-data so the
     ;; disclosure mounts an inspector.
     {:id 2 :time 20 :operation :rf.error/interceptor-exception
      :tags {:failing-id        throwing-interceptor-id
             :phase             :after
             :exception-message "audit interceptor threw"
             :exception         (ex-info "audit interceptor threw"
                                         {:rf.xray.test/interceptor
                                          throwing-interceptor-id})}}
     ;; SUBSCRIPTIONS step — one CHANGED row, so it survives the default
     ;; `:changed` filter and the table renders its `:row-extras`.
     {:id 3 :time 30 :operation :rf.sub/run
      :tags {:rf.sub/id             violating-sub-id
             :rf.sub/query-v        [violating-sub-id]
             :rf.sub/value-changed? true
             :rf.sub/prev-value     1
             :rf.sub/value          "bad"}}
     ;; PER-ROW violation — `:failing-id` MATCHES the row's `:sub-id`.
     {:id 4 :time 40 :operation :rf.error/schema-validation-failure
      :tags {:where             :sub-return
             :failing-id        violating-sub-id
             :path              [:cart :preview]
             :value             "bad"
             :explain-humanized {:preview ["should be an int"]}}}
     ;; STEP-LEVEL violation — `:failing-id` matches NO row, so
     ;; `attach-to-sub-row` falls back to the step-level bucket.
     {:id 5 :time 50 :operation :rf.error/schema-validation-failure
      :tags {:where             :sub-return
             :failing-id        :cart/indirect
             :path              [:cart :indirect]
             :value             "bad"
             :explain-humanized {:indirect ["should be an int"]}}}]}])

;; ---- the ids those three diagnostics compose, WRITTEN OUT -----------------
;;
;; LITERALS, not values re-derived from the same render the rows read. A door
;; that compares what it read against something composed the same way agrees
;; with itself under the revert — both sides go unqualified together, the
;; comparison still holds, and the row passes ON the defect. These strings
;; are what the panel must produce, stated independently of it.
;;
;; Each tail is composed by the source as `(str "epoch/violation-explain/"
;; step-key "/" idx)` — `step-key` is a KEYWORD and `str` keeps its leading
;; colon, which is why `:subscriptions` appears here with one. The
;; step-level id below is verbatim the one rf2-1ar7 names as its worked
;; example of the collision.

(def ^:private interceptor-ex-data-site
  "epoch/error-ex-data/rf-xray-epoch-error-interceptor-row-0-0")

(def ^:private sub-row-violation-site
  "epoch/violation-explain/:sub-row-preview/0")

(def ^:private sub-step-violation-site
  "epoch/violation-explain/:subscriptions/0")

(def ^:private diagnostic-sites
  [interceptor-ex-data-site sub-row-violation-site sub-step-violation-site])

(defn- qualified
  "The id a mount named `instance` composes for `site` — `inspector-mount-id`
  puts the instance OUTERMOST. Spelled out here rather than called out of the
  source: this is the test's own statement of the contract."
  [instance site]
  (str instance "/" site))

(defn- setup!
  "Register Xray's handlers plus the test-override seam the fixtures write
  through, then seed the machine registry, the epoch ring and the focus.

  The Machine Inspector needs the registered-machines / definitions
  overrides to paint its focused-event section at all; the Epoch panel needs
  neither and reads the same history and focus. Both panels default to the
  `:rf/xray` frame, which is what makes the collision reachable.

  `ensure-xray-frame!` RUNS FIRST, BEFORE the seeding, and that ordering is
  a measured requirement rather than tidiness. These rows mount through the
  PUBLIC facades, and `panels/render-panel!` routes every mount through
  `ensure-xray-handlers-installed!` → `mount/ensure-xray-frame!`, whose
  FIRST-MOUNT HOOK TABLE (`::seed-trace-and-target-frame`,
  `::reset-transient-filters`, `::hydrate-static-mode`, …) writes the very
  slots seeded here. Seeded first and mounted second, those hooks land ON
  TOP and both panels paint an empty state — measured: every `mount-ids`
  read came back `[]` with the panel roots committed, so the controls fired
  rather than the claims. `ensure-xray-frame!` is idempotent, so running it
  here leaves the facade's own call a no-op and the seed is what the panels
  read. The sibling suites do not meet this because they mount the registry
  head under a `frame-provider` directly and never reach the facade.

  rf2-1ar7 — the one-argument arity takes the history to seed, so the
  diagnostics rows below run the SAME facade path W1-W5 do rather than a
  second one of their own. The focus is taken FROM that history rather than
  written as a constant beside it: a fixture whose `:epoch-id` drifted from a
  hard-coded focus paints an empty panel, and an empty panel reads as
  disjoint ids rather than as a broken seed."
  ([] (setup! fixture-history))
  ([history]
   (registry/register-xray-handlers!)
   (xray-test-support/install-test-overrides!)
   (mount/ensure-xray-frame! :rf/xray)
   (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test
                      [machine-id]]
                     {:frame :rf/xray})
   (rf/dispatch-sync [:rf.xray/set-machine-definitions-override-for-test
                      {machine-id fixture-definition}]
                     {:frame :rf/xray})
   (rf/dispatch-sync [:rf.xray/set-epoch-history-for-test history]
                     {:frame :rf/xray})
   (rf/dispatch-sync [:rf.xray/set-focus-epoch-id-for-test
                      (:epoch-id (first history))]
                     {:frame :rf/xray})
   nil))

;; ---- mounting, and reading the DOM back ----------------------------------

(defn- mount-with!
  "Mount `panel-mount-fn` through the PUBLIC facade with `opts`, committed
  synchronously.

  `react-dom/flushSync` rather than a Reagent queue drain: both panels' root
  views are Fresco boundaries, which are not in Reagent's render queue at
  all, so draining that queue commits nothing of theirs and a row written
  that way reads a container that never moved."
  [panel-mount-fn opts]
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    (let [unmount (react-dom/flushSync
                    (fn [] (panel-mount-fn container opts)))]
      {:container container :unmount unmount})))

(defn- mount-epoch! [opts] (mount-with! panels/mount-epoch-panel! opts))
(defn- mount-machines! [opts] (mount-with! panels/mount-machine-inspector! opts))

(defn- unmount!
  "Tear one mount down inside `flushSync` so React's ref-detach has RUN by
  the time the next line reads the per-mount store. A bare unmount schedules
  it."
  [{:keys [container unmount]}]
  (react-dom/flushSync (fn [] (unmount)))
  (.remove container))

(defn- mount-ids
  "Every `data-rf-mount-id` in `container`'s committed DOM, in document
  order. Reads the DOM React wrote — nothing here reproduces a panel."
  [container]
  (->> (.querySelectorAll container "[data-rf-mount-id]")
       (js/Array.from)
       (array-seq)
       (mapv #(.getAttribute % "data-rf-mount-id"))))

(defn- site-ids
  "Every `data-rf-site-id` in `container`'s committed DOM. The LOGICAL
  identity, which this bead must leave alone."
  [container]
  (->> (.querySelectorAll container "[data-rf-site-id]")
       (js/Array.from)
       (array-seq)
       (mapv #(.getAttribute % "data-rf-site-id"))))

(defn- cascade-mount-ids
  "The subset of `container`'s mount-ids the SHARED mini-pipeline composed.
  Named by the role segment rather than by a whole literal, because the step
  ordinal rides the tail and the qualifier rides elsewhere — what this
  selects is `which renderer emitted it`, which is the axis the cross-panel
  row is about."
  [container]
  (filterv #(string/includes? % "machine-cascade") (mount-ids container)))

(defn- widths
  "The frame's measured-width slot — a map of bare `mount-id` → px."
  []
  (or (rf/subscribe-once [ei/widths-slot] {:frame :rf/xray}) {}))

;; ===========================================================================
;; W1 — two NAMED standalone Epoch mounts compose two disjoint sets of ids
;; ===========================================================================

(deftest two-named-epoch-mounts-compose-disjoint-inspector-mount-ids
  (testing "rf2-3ymg — `mount-epoch-panel!` given two different
            `:instance-id`s mounts two panels whose committed DOM carries two
            disjoint sets of `data-rf-mount-id`, in the ONE `:rf/xray` frame
            they both default to. Each id composes the widget's lifecycle key
            AND its measured-width slot key, so disjointness here is
            disjointness of both."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount-epoch! {:instance-id "left"})
            right (mount-epoch! {:instance-id "right"})]
        (try
          (let [ids-l (mount-ids (:container left))
                ids-r (mount-ids (:container right))]
            ;; ---- controls, taken from the target ------------------------
            (is (some? (.querySelector (:container left)
                                       "[data-testid=\"rf-xray-epoch-panel\"]"))
                "control: the left mount committed the Epoch panel at all, so
                 an empty intersection below means separation and not a panel
                 that rendered an empty state and mounted no inspector")
            (is (seq ids-l)
                (str "control: the panel committed at least one inspector — "
                     "so the sets below are sets. left=" (pr-str ids-l)))
            (is (= (count ids-l) (count ids-r))
                "naming an instance changes the ids, never the rows — two
                 mounts of the same focused epoch over the same steps")

            ;; ---- the claim ---------------------------------------------
            (is (nil? (some (set ids-l) ids-r))
                (str "no mount-id survives from one standalone mount to the "
                     "other — the store's lifecycle key and the measured "
                     "width slot are both derived from this string. left="
                     (pr-str ids-l) " right=" (pr-str ids-r)))
            (is (every? #(string/includes? % "left") ids-l)
                (str "each id carries the name THIS mount was given, rather "
                     "than a per-render nonce or a shared string: "
                     (pr-str ids-l))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W2 — the lifecycle half: each mount owns an observer AND a width, and keeps
;;      both when its sibling goes
;; ===========================================================================

(deftest two-named-epoch-mounts-each-keep-their-own-observer-and-width
  (testing "rf2-3ymg — two named standalone Epoch mounts hold two entries in
            the widget's per-mount store, each with its OWN ResizeObserver and
            its OWN entry in the frame's measured-width slot, and unmounting
            one releases ONLY its own. Under the shared identity the second
            mount never installs an observer at all, and `release-mount!`
            tears the shared entry down AND clears the shared width when
            either detaches — so the survivor is left on screen unobserved and
            unmeasured, which a distinctness row alone cannot see."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [left  (mount-epoch! {:instance-id "left"})
              right (mount-epoch! {:instance-id "right"})
              id-l  (first (mount-ids (:container left)))
              id-r  (first (mount-ids (:container right)))]
          (is (some? id-l)
              "control: the left mount committed a widget, so the store keys
               below name something that really mounted")
          (is (not= id-l id-r)
              "the two mounts composed two mount-ids")

          ;; ---- the observer half -------------------------------------
          ;; These two are LIVENESS controls rather than the discriminating
          ;; rows, and saying so matters: under the shared identity both
          ;; lookups resolve to the SAME entry, so both pass while the defect
          ;; is fully present. What bites before the unmount is `not=` above;
          ;; what bites after it is the survivor row below.
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "the left mount installed its own ResizeObserver")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r))
                         :observer)
              "and so did the right — two live mounts, two observers. Under
               the shared identity the second element's ref callback finds an
               observer already on the entry and installs none")

          ;; ---- the width half ----------------------------------------
          ;; DRIVEN through the slot's own public event rather than read off a
          ;; real measurement, and that is a finding rather than a shortcut.
          ;; The headless payload container measures `clientWidth` 0, so
          ;; `measure-and-dispatch!`'s `(pos? w)` guard need never fire and
          ;; the slot can be EMPTY on a perfectly healthy mount; meanwhile the
          ;; ResizeObserver's own later callback DOES land real widths,
          ;; asynchronously. So a row asserting a measured VALUE is red for a
          ;; reason that is not this defect, and one asserting a specific
          ;; value races a real measurement that may overwrite it. The slot is
          ;; public for exactly this, and `:rf.xray.edn-inspector/set-width`
          ;; is the very event the observer dispatches, under the very id the
          ;; widget composes. These rows therefore assert on KEY PRESENCE,
          ;; which is what the defect actually moves: WHICH KEY
          ;; `release-mount!` clears when a sibling detaches.
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-l 640]
                            {:frame :rf/xray})
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-r 480]
                            {:frame :rf/xray})
          (is (= 2 (count (select-keys (widths) [id-l id-r])))
              (str "two live mounts hold TWO width slots — under the shared "
                   "identity both writes land on ONE key. slot="
                   (pr-str (widths))))

          (unmount! right)

          ;; ---- the survivor keeps both -------------------------------
          (is (nil? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r)))
              "the detached mount is gone")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "and the mount STILL ON SCREEN is still observed — releasing the
               survivor's entry is what the shared key did, and it left a live
               node with no observer and no width updates")
          ;; The store entry is dropped by a synchronous `swap!` but the width
          ;; is cleared by a DISPATCH, which is queued — the slot still reads
          ;; its pre-unmount value on the line straight after
          ;; `flushSync(unmount)`. So poll for the clear to land, then read the
          ;; survivor. Under the shared identity the two ids are one string, so
          ;; the poll below succeeds on the SURVIVOR's own key being cleared,
          ;; and the row after it is what reports that.
          (-> (rf.test-support/poll-until
                (fn [] (nil? (get (widths) id-r)))
                {:label "release-mount! cleared the detaching mount's width"})
              (.then
                (fn [_]
                  (is (some? (get (widths) id-l))
                      (str "and the survivor's width is STILL in the frame's "
                           "slot — `release-mount!` clears the width under the "
                           "DETACHING mount's id, which under the shared "
                           "identity is the survivor's own. slot="
                           (pr-str (widths))))))
              (.catch
                (fn [e]
                  (is false (str "the width clear never landed: "
                                 (.-message e) " slot=" (pr-str (widths))))
                  nil))
              (.then (fn [_] (unmount! left) (done)))))))))

;; ===========================================================================
;; W3 — the CROSS-PANEL half, and it takes NO opts at all
;; ===========================================================================

(deftest epoch-and-machine-inspector-do-not-share-cascade-mount-ids
  (testing "rf2-3ymg — an Epoch panel and a Machine Inspector displaying the
            SAME machine cascade, both mounted with NO opts, compose disjoint
            cascade mount-ids. They render that cascade through one shared
            `machine-cascade-mini-pipeline` off one
            `projection/machine-cascade-rows`, so before the repair both
            emitted `epoch/machine-cascade-transition-delta/<step>` for the
            same step and shared one lifecycle entry, one ResizeObserver and
            one width slot across two DIFFERENT panels.

            NO `:instance-id` is passed, deliberately: which panel is
            rendering is statically known, a caller embedding one of each
            cannot see this collision, and the Machine Inspector is the guest
            in the Epoch panel's id namespace — so it names itself."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_       (setup!)
            epoch   (mount-epoch! nil)
            machine (mount-machines! nil)]
        (try
          (let [cas-e (cascade-mount-ids (:container epoch))
                cas-m (cascade-mount-ids (:container machine))]
            ;; ---- controls, taken from the target ------------------------
            (is (some? (.querySelector (:container machine)
                                       "[data-testid=\"rf-xray-machine-event-handler-mini-pipeline\"]"))
                "control: the Machine Inspector committed the SHARED
                 mini-pipeline host, so an empty intersection below is
                 separation and not a panel that painted an empty state")
            (is (seq cas-e)
                (str "control: the Epoch panel committed cascade inspectors "
                     "too — both surfaces are rendering the shared cascade, "
                     "which is what makes the collision reachable. epoch="
                     (pr-str cas-e)))
            (is (seq cas-m)
                (str "control: and so did the Machine Inspector. machines="
                     (pr-str cas-m)))

            ;; ---- the claim ---------------------------------------------
            (is (nil? (some (set cas-e) cas-m))
                (str "no cascade mount-id is shared between the two panels, "
                     "with neither caller having named anything. epoch="
                     (pr-str cas-e) " machines=" (pr-str cas-m)))
            (is (every? #(string/includes? % "machine-inspector") cas-m)
                (str "and the Machine Inspector's cascade ids say WHICH panel "
                     "rendered them — it is a guest in the Epoch panel's id "
                     "namespace and names itself. machines=" (pr-str cas-m))))
          (finally
            (unmount! machine)
            (unmount! epoch)))))))

;; ===========================================================================
;; W4 — the LOGICAL identity must NOT move
;; ===========================================================================

(deftest two-named-epoch-mounts-share-their-disclosure-identity
  (testing "rf2-3ymg — the bound on this repair, as a row. Naming two mounts
            qualifies the PHYSICAL identity only: the `:site-id` that keys
            expansion and zoom (rf2-pvsxs) is IDENTICAL across the two panels,
            so two views of the same epoch still open and close together and
            an operator's expansion choices still survive a tab
            leave-and-return. A repair that qualified the site-id too would
            pass W1, W2 and W3 and silently change this."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount-epoch! {:instance-id "left"})
            right (mount-epoch! {:instance-id "right"})]
        (try
          (let [sites-l (site-ids (:container left))
                sites-r (site-ids (:container right))]
            (is (seq sites-l)
                (str "control: the left mount committed a site-id at all — "
                     "all thirteen sites pass one, so an equality below is a "
                     "measurement rather than two empty vectors agreeing. "
                     "left=" (pr-str sites-l)))
            (is (= sites-l sites-r)
                (str "the LOGICAL identity is shared across the two mounts, "
                     "deliberately. left=" (pr-str sites-l)
                     " right=" (pr-str sites-r)))
            (is (some? (.querySelector (:container right)
                                       "[data-testid=\"rf-xray-epoch-dispatch-event\"]"))
                "and the panel's own row testids are untouched by the
                 qualifier — they are the STEP's identity, not the
                 inspector's"))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W5 — the negative control: unnamed Epoch mounts collide, and are unchanged
;; ===========================================================================

(deftest two-unnamed-epoch-mounts-still-collide
  (testing "rf2-3ymg — the defect verbatim, kept as a row. Two standalone
            Epoch mounts with NO `:instance-id` present the SAME mount-ids,
            which is what makes W1's disjointness a measurement rather than a
            coincidence; and the ids they present carry no instance segment at
            all, so every existing single-mount call site composes exactly
            what it always did."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            a     (mount-epoch! nil)
            b     (mount-epoch! {})
            named (mount-epoch! {:instance-id "left"})]
        (try
          (let [ids-a (mount-ids (:container a))
                ids-b (mount-ids (:container b))
                ids-n (mount-ids (:container named))]
            (is (seq ids-a)
                "control: both unnamed mounts committed widgets")
            (is (= ids-a ids-b)
                (str "two unnamed mounts present the SAME ids — so the "
                     "instrument W1 uses CAN see a collision, and its "
                     "disjointness is a measurement rather than silence. a="
                     (pr-str ids-a)))
            (is (some #(= "epoch/dispatch-event" %) ids-a)
                (str "and they are byte-for-byte the ids this panel composed "
                     "before rf2-3ymg — the DISPATCH step's is a CONSTANT, "
                     "which is the sharpest statement of both halves: it "
                     "collides between two mounts, and it must not move for "
                     "one. a=" (pr-str ids-a)))
            (is (= (count ids-a) (count ids-n))
                (str "a named mount commits the same widgets, differing only "
                     "in what they are called. unnamed=" (pr-str ids-a)
                     " named=" (pr-str ids-n)))
            (is (nil? (some (set ids-a) ids-n))
                (str "and no id survives from the unnamed pair to the named "
                     "mount — naming qualifies every one of them. unnamed="
                     (pr-str ids-a) " named=" (pr-str ids-n)))
            (is (= ids-a (mount-ids (:container a)))
                "re-reading the same container is stable — these are
                 identities, not per-render nonces"))
          (finally
            (unmount! named)
            (unmount! b)
            (unmount! a)))))))

;; ===========================================================================
;; W6 — the INTERCEPTOR row's ex-data disclosure (rf2-1ar7)
;; ===========================================================================

(deftest two-named-epoch-mounts-qualify-the-interceptor-ex-data-mount
  (testing "rf2-1ar7 — two named Epoch mounts showing the SAME interceptor
            exception compose two disjoint `epoch/error-ex-data/…` mount-ids.

            This is the path rf2-3ymg missed entirely rather than passed
            wrongly: `render-interceptor-step` → `interceptor-row-view` →
            `error-blocks` carried NO instance parameter, so there was
            nothing for the dispatcher to hand down. The card's `ex-data`
            disclosure mounts an `ei/edn-inspector-view` whose `:mount-id` is
            composed from the step-key and the row ordinal ALONE — both of
            which are identical across two mounts of one epoch."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup! diagnostics-history)
            left  (mount-epoch! {:instance-id "left"})
            right (mount-epoch! {:instance-id "right"})]
        (try
          (let [ids-l (mount-ids (:container left))
                ids-r (mount-ids (:container right))]
            ;; ---- controls, taken from the target ------------------------
            (is (some? (some-> (:container left)
                               (.querySelector
                                 "[data-testid=\"rf-xray-epoch-step-interceptor\"]")))
                "control: the INTERCEPTOR step rendered at all. It is
                 CONDITIONAL on a throw, so a fixture that failed to project
                 one would leave the assertions below comparing two empty
                 sets and passing")
            (is (some? (some-> (:container left)
                               (.querySelector
                                 "[data-testid=\"rf-xray-epoch-error-interceptor-row-0-0-ex-data\"]")))
                "control: and the row's ex-data disclosure is present — the
                 inspector mounts only `(when (seq ex-data*))`, so a throw
                 carrying no ex-data would render the card and no mount")

            ;; ---- the claim, against LITERALS ----------------------------
            (is (some #(= (qualified "left" interceptor-ex-data-site) %) ids-l)
                (str "the left mount composed the id written out in this "
                     "file, not merely something different from the right's. "
                     "expected=" (qualified "left" interceptor-ex-data-site)
                     " left=" (pr-str ids-l)))
            (is (some #(= (qualified "right" interceptor-ex-data-site) %) ids-r)
                (str "and the right mount composed its own. expected="
                     (qualified "right" interceptor-ex-data-site)
                     " right=" (pr-str ids-r)))
            (is (nil? (some #(= interceptor-ex-data-site %)
                            (concat ids-l ids-r)))
                (str "and NEITHER still presents the unqualified id — an "
                     "EQUALITY test, because the qualified ids contain the "
                     "unqualified one as a substring. unqualified="
                     interceptor-ex-data-site " left=" (pr-str ids-l)
                     " right=" (pr-str ids-r))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W7 — both SUBSCRIPTION violation explainers, per-row and step-level
;; ===========================================================================

(deftest two-named-epoch-mounts-qualify-both-subscription-violation-mounts
  (testing "rf2-1ar7 — two named Epoch mounts rendering the SAME subscription
            schema violations compose disjoint explainer mount-ids, on BOTH
            the per-row and the step-level path.

            The two failed differently and are therefore both rows here.
            `subscriptions-table` HELD the instance — the value cell one line
            above uses it — and its `:row-extras` callback took
            `violation-blocks`' two-argument arity anyway. The step-level call
            at the foot of `render-subscriptions-step` had `(:instance ctx)`
            available and omitted it. That second one is the bead's own worked
            example: both named panels produced
            `epoch/violation-explain/:subscriptions/0`."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup! diagnostics-history)
            left  (mount-epoch! {:instance-id "left"})
            right (mount-epoch! {:instance-id "right"})]
        (try
          (let [ids-l (mount-ids (:container left))
                ids-r (mount-ids (:container right))]
            ;; ---- controls, taken from the target ------------------------
            (is (some? (some-> (:container left)
                               (.querySelector
                                 "[data-testid=\"rf-xray-epoch-violations-sub-row-preview\"]")))
                "control: the PER-ROW violation block rendered. It rides the
                 table's `:row-extras`, which only runs for a VISIBLE row —
                 the default `:changed` filter drops an unchanged one before
                 the table sees it")
            (is (some? (some-> (:container left)
                               (.querySelector
                                 "[data-testid=\"rf-xray-epoch-violations-subscriptions\"]")))
                "control: and the STEP-LEVEL block rendered — its violation
                 names a sub that recomputed nowhere in this cascade, which
                 is what makes `attach-to-sub-row` fall back to the
                 step-level bucket rather than attaching to a row")

            ;; ---- the claim, against LITERALS ----------------------------
            (doseq [[who ids] [["left" ids-l] ["right" ids-r]]]
              (is (some #(= (qualified who sub-row-violation-site) %) ids)
                  (str "the " who " mount qualified the PER-ROW explainer. "
                       "expected=" (qualified who sub-row-violation-site)
                       " ids=" (pr-str ids)))
              (is (some #(= (qualified who sub-step-violation-site) %) ids)
                  (str "the " who " mount qualified the STEP-LEVEL explainer. "
                       "expected=" (qualified who sub-step-violation-site)
                       " ids=" (pr-str ids))))

            (is (nil? (some #{sub-row-violation-site sub-step-violation-site}
                            (concat ids-l ids-r)))
                (str "and neither mount still presents either unqualified "
                     "explainer id — the step-level one is verbatim what "
                     "rf2-1ar7 reported both named panels producing. left="
                     (pr-str ids-l) " right=" (pr-str ids-r)))
            (is (nil? (some (set ids-l) ids-r))
                (str "taken whole, the two mounts share no id at all. left="
                     (pr-str ids-l) " right=" (pr-str ids-r))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W8 — the lifecycle half for a DIAGNOSTIC mount: the survivor keeps both
;; ===========================================================================

(deftest diagnostic-mounts-each-keep-their-own-observer-and-width
  (testing "rf2-1ar7 — the half a distinctness row passes over, asserted on a
            DIAGNOSTIC mount rather than an ordinary payload one.

            Distinctness alone is satisfied by any two different strings. What
            the shared id actually costs is physical: the second mount's ref
            callback finds an observer already on the entry and installs
            NONE, and `release-mount!` then tears the shared entry down — and
            clears the shared width — when EITHER holder detaches, leaving the
            panel still on screen unobserved and unmeasured. W2 pins this for
            the dispatch-event mount; this row pins it for the step-level
            violation explainer, which is the mount rf2-1ar7 is about."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup! diagnostics-history)
        (let [left  (mount-epoch! {:instance-id "left"})
              right (mount-epoch! {:instance-id "right"})
              id-l  (qualified "left" sub-step-violation-site)
              id-r  (qualified "right" sub-step-violation-site)]
          ;; Controls: the two ids are LITERALS, so a fixture that stopped
          ;; rendering the step-level explainer would leave every assertion
          ;; below reading an absent key — which is why presence is asserted
          ;; before anything is concluded from it.
          (is (some #(= id-l %) (mount-ids (:container left)))
              (str "control: the left mount really committed the step-level "
                   "explainer under the id this row keys on. expected=" id-l
                   " ids=" (pr-str (mount-ids (:container left)))))
          (is (some #(= id-r %) (mount-ids (:container right)))
              (str "control: and so did the right. expected=" id-r))

          ;; ---- the observer half -------------------------------------
          ;; Liveness rather than discrimination, exactly as in W2: under the
          ;; shared identity both lookups resolve to the SAME entry and both
          ;; pass with the defect fully present. The survivor row below is
          ;; what bites.
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "the left mount's explainer installed its own ResizeObserver")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r))
                         :observer)
              "and so did the right's — two live mounts, two observers")

          ;; ---- the width half ----------------------------------------
          ;; Driven through the slot's own public event, for the reason W2
          ;; records: the headless container measures `clientWidth` 0, so the
          ;; real measurement path need never fire and a row asserting a
          ;; measured VALUE would be red for a reason that is not this defect.
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-l 640]
                            {:frame :rf/xray})
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-r 480]
                            {:frame :rf/xray})
          (is (= 2 (count (select-keys (widths) [id-l id-r])))
              (str "two live explainer mounts hold TWO width slots — under "
                   "the shared identity both writes land on one key. slot="
                   (pr-str (widths))))

          (unmount! right)

          ;; ---- the survivor keeps both -------------------------------
          (is (nil? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r)))
              "the detached mount's entry is gone")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "and the mount STILL ON SCREEN is still observed — releasing the
               survivor's entry is exactly what the shared key did")
          (-> (rf.test-support/poll-until
                (fn [] (nil? (get (widths) id-r)))
                {:label "release-mount! cleared the detaching explainer's width"})
              (.then
                (fn [_]
                  (is (some? (get (widths) id-l))
                      (str "and the survivor's width is STILL in the frame's "
                           "slot — `release-mount!` clears under the DETACHING "
                           "mount's id, which under the shared identity is the "
                           "survivor's own. slot=" (pr-str (widths))))))
              (.catch
                (fn [e]
                  (is false (str "the width clear never landed: "
                                 (.-message e) " slot=" (pr-str (widths))))
                  nil))
              (.then (fn [_] (unmount! left) (done)))))))))

;; ===========================================================================
;; W9 — the negative control: unnamed diagnostic mounts collide, unchanged
;; ===========================================================================

(deftest two-unnamed-epoch-mounts-still-collide-on-the-diagnostic-ids
  (testing "rf2-1ar7 — the defect verbatim, kept as a row, and the thing that
            makes W6-W8 measurements rather than silence.

            Two UNNAMED Epoch mounts rendering the same three diagnostics
            present the SAME ids, and those ids are byte-for-byte the
            unqualified sites this file writes out — so the instrument can see
            a collision, and the single-mount default that every call site in
            this tree uses composes exactly what it always did. W5 states both
            halves for the ordinary payload mounts; this is the diagnostics'."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup! diagnostics-history)
            a (mount-epoch! nil)
            b (mount-epoch! {})]
        (try
          (let [ids-a (mount-ids (:container a))
                ids-b (mount-ids (:container b))]
            (is (seq ids-a)
                "control: both unnamed mounts committed widgets")
            (is (= ids-a ids-b)
                (str "two unnamed mounts present the SAME ids — so the "
                     "instrument W6 and W7 use CAN see a collision. a="
                     (pr-str ids-a)))
            (doseq [site diagnostic-sites]
              (is (some #(= site %) ids-a)
                  (str "the unnamed mount composes the unqualified " site
                       " — this is the collision rf2-1ar7 reported, and it is "
                       "also the identity that must not move for a single "
                       "mount. a=" (pr-str ids-a)))))
          (finally
            (unmount! b)
            (unmount! a)))))))
