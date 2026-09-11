(ns day8.re-frame2-xray.spine-filters-fresco-boundary-dom-cljs-test
  "Real-DOM witnesses for the two shell-root SPINE-FILTER bridges —
  `spine-filters/RowContextMenu` and `spine-filters/Modal` (rf2-3du3,
  correcting the evidence gap merged-PR audit #9665 found in rf2-k97c.3).

  ## The gap this file closes

  rf2-k97c.3 turned both surfaces into Fresco BOUNDARIES behind their
  existing public bridge names. Their production observation and their
  captured-frame dispatch both changed, and the mute row now has to
  render inside a Fresco tree. But every test that moved with them drives
  a test-owned copy of the gate and the reads, calling the pure
  `row-context-menu-tree` / `dialog-tree` directly. Nothing mounted a
  bridge, and nothing executed either boundary. A broken real gate or a
  nil boundary body could therefore leave the whole node lane green.

  This file is the missing half, and it asks for BOTH things the audit
  named — that the boundary MOUNTS, and that a dispatch from INSIDE it
  REACHES the frame the tree named:

    W1  RowContextMenu mounts, commits real menu DOM, and its Mute item
        — clicked outside any render scope — lands on the mounted
        instance frame while a second live frame stays exactly as it was.
        The menu then closes from its OWN subscription.

    W2  Modal mounts with muted ids and commits real rows through the
        keyed native fragment, an Unmute click updates the LIVE surface
        in place, and a deaf control says that update means liveness.

    W3  Unmount releases every read both boundaries take, after the
        collector's documented grace, and reopening does not accumulate.

  ## Why the node lane cannot make any of these claims

  `expand-tree` INVOKES a fn head, so `[x …]` and `(x …)` expand to the
  same value and the node lane is structurally blind to head legality.
  It is blind twice over here: the doors it drives pass the boundary's
  reads in as ARGUMENTS, so the gate and the `rf.fresco/sub` calls that
  are the actual subject of rf2-k97c.3 never run at all. Only a committed
  DOM can answer, which is why these rows are `-dom-cljs-test`.

  ## The mount is the SHELL's mount

  `shell.cljs` mounts both bridges as plain hiccup HEADS, as siblings
  inside the shell's `[rf/frame-provider {:frame frame-id}]` (`:3193`
  and `:3199`). [[mount!]] does exactly that and nothing else — no
  wrapper, no second call. Every assertion after the mount reads
  `container.querySelector…`, i.e. the DOM React committed on its own.

  ## Frames: two private ones, and NEVER `:rf/xray`

  `:rf/xray` is the production singleton, shared with every other suite
  on the page, so a control frame named `:rf/xray` could be moved by a
  neighbour between the mount and the assertion. Both frames here are
  private to this ns. That also makes the positive half a direct witness
  for rf2-nesy9's frame-CARRYING dispatch: the boundaries captured
  `{:frame :rf/xray}` literals before it, and under such a literal the
  mounted frame below would read nothing and W1 would redden.

  `shell-view`'s `:frame-id` opt (rf2-lnluk) is what makes a named
  non-default instance frame the production shape rather than a test
  contrivance — N shells mount side by side, each fully isolated.

  ## Substrate: the Reagent adapter, deliberately

  The family Xray already supports, because the claim is that the
  boundary is INDIFFERENT to it. `:ambient-frame nil` is load-bearing:
  tier 1 of the frame resolver is the dynamic var, so an ambient frame
  would SHADOW the React-context tier W1's targeting row is about, and
  that row would pass while measuring nothing.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's regex
  also matches, so it LOADS under Node — where every row short-circuits
  through [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- frames --------------------------------------------------------------

(def ^:private instance-frame
  "The Xray instance this suite mounts. A NAMED NON-DEFAULT frame, per
  the ns docstring — the shape `shell-view`'s `:frame-id` opt ships."
  ::instance)

(def ^:private competing-frame
  "A SECOND live Xray instance — the competing-frame control. A mute
  performed in the mounted instance must move nothing here. It is seeded
  with a mute of its own so the control is demonstrably READABLE: an
  assertion that this frame is untouched has to be able to fail."
  ::competing)

;; ---- the ids, as literals -------------------------------------------------
;;
;; Every assertion below compares against one of these LITERAL sets
;; rather than against a value re-read through the machinery under test.
;; A door that compares what it read to what the boundary also read
;; agrees with itself under a revert — both sides go nil, they match, and
;; the gate goes green on the defect.

(def ^:private muted-id
  "The id W1's Mute item mutes, and W2's first seeded row."
  :probe.spine/clock-tick)

(def ^:private second-id
  "W2's other seeded row. Sorts AFTER `muted-id` under `sort-by str`,
  which is the order `list-section` renders in."
  :probe.spine/mouse-move)

(def ^:private competing-id
  "Pre-seeded on the competing frame — see [[competing-frame]]."
  :probe.spine/heartbeat)

;; ---- the boundaries' reads ------------------------------------------------

(def ^:private menu-q  [:rf.xray/row-context-menu])
(def ^:private open-q  [:rf.xray/mute-manager-open?])
(def ^:private muted-q [:rf.xray/muted-event-ids])
(def ^:private pos-q   [:rf.xray/modal-positioning])

(def ^:private boundary-reads
  "Every query the two boundaries issue between them, in the order they
  issue them: `RowContextMenuView` takes the first, `ModalView` gates on
  the second and takes the last two INSIDE that gate. The sub-cache is
  keyed by the query vector itself, so these values ARE the cache keys."
  [menu-q open-q muted-q pos-q])

;; W2's DEAF lever. It writes a key on the instance's own app-db that NO
;; sub in either boundary's read set consults, so the world moves and
;; nothing either boundary watches is invalidated. Without it, W2's
;; repaint would be evidence that a commit happened — not that the
;; boundary is live.
(rf/reg-event ::write-unwatched-slot
  (fn [{:keys [db]} [_ n]]
    {:db (assoc db ::probe n)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about;
                      ;; a neighbour's boundary left in the entry cache
                      ;; would make W3's release row read a residue that
                      ;; is not this suite's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather
;; than an omission. A Fresco boundary is NOT in Reagent's render queue —
;; its update is scheduled by the collector through React — so draining
;; Reagent's queue commits nothing of these boundaries', and a row
;; written that way reads a DOM that has not moved and reports a live
;; boundary as dead. The mount is committed with `flushSync` (React's own
;; door) and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after the world
  moves is a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

;; ---- setup / seeding, through the PRODUCTION events ----------------------
;;
;; Every seed below dispatches a real registered `:rf.xray/*` event. There
;; is no `-for-test` override seam in this subsystem and none is wanted:
;; the open state the boundaries gate on is exactly what the shell's own
;; right-click handler and ribbon indicator produce.

(defn- setup!
  "Register Xray's handlers — which fans out to `spine-filters/install!`,
  the source of every sub and event below — and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id instance-frame})
  (rf/make-frame {:id competing-frame})
  nil)

(defn- open-menu! [frame event-id]
  (rf/dispatch-sync [:rf.xray/open-row-context-menu
                     {:event-id event-id :x 120 :y 240}]
                    {:frame frame}))

(defn- mute! [frame event-id]
  (rf/dispatch-sync [:rf.xray/mute-event-id event-id] {:frame frame}))

(defn- open-manager! [frame]
  (rf/dispatch-sync [:rf.xray/open-mute-manager] {:frame frame}))

(defn- mount!
  "Mount `views` as plain hiccup HEADS, siblings inside a
  `frame-provider` scoping `frame` — the exact shape `shell.cljs` uses at
  `:3193` / `:3199`. Committed synchronously: React 19's `root.render` is
  otherwise async and the first assertion would run against an empty
  container."
  [frame views]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root (into [rf/frame-provider {:frame frame}]
                               (map vector views)))))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- readers --------------------------------------------------------------

(defn- q [container sel] (.querySelector container sel))

(defn- testid-sel [id] (str "[data-testid=\"" id "\"]"))

(defn- menu-node      [c] (q c (testid-sel "rf-xray-row-context-menu")))
(defn- menu-backdrop  [c] (q c (testid-sel "rf-xray-row-context-menu-backdrop")))
(defn- menu-mute-btn  [c] (q c (testid-sel "rf-xray-row-context-menu-mute")))
(defn- dialog-node    [c] (q c (testid-sel "rf-xray-mute-manager-dialog")))
(defn- list-node      [c] (q c (testid-sel "rf-xray-mute-manager-list")))
(defn- empty-node     [c] (q c (testid-sel "rf-xray-mute-manager-empty")))
(defn- row-node       [c id] (q c (testid-sel (str "rf-xray-mute-manager-row-" id))))
(defn- unmute-btn     [c id] (q c (testid-sel (str "rf-xray-mute-manager-unmute-" id))))

(defn- element-children
  "The element children of `node` as a vector. Used to prove the keyed
  native fragment contributed no DOM node of its own."
  [node]
  (let [kids (.-children node)]
    (mapv #(.item kids %) (range (.-length kids)))))

(defn- muted-of
  "The frame's OWN `:muted-event-ids` app-db slot, read through the
  public value accessor.

  This is an INDEPENDENT INSTRUMENT on purpose: it does not go through
  the sub layer the boundary reads, so a boundary that read nothing and a
  reader that read nothing cannot agree with each other. `nil` means the
  slot was never written, which is distinguishable from the `#{}` a
  clear leaves behind."
  [frame-id]
  (:muted-event-ids (rf/app-db-value frame-id)))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- released?
  "True once the instance frame holds NO reference for any read either
  boundary takes."
  []
  (every? #(zero? (ref-count-of instance-frame %)) boundary-reads))

;; ===========================================================================
;; W1 — the row context menu mounts, and its Mute reaches the named frame
;; ===========================================================================

(deftest w1-row-context-menu-bridge-mounts-and-mutes-into-the-named-frame
  (testing "rf2-3du3 — `spine-filters/RowContextMenu` mounted as the
            shell's hiccup head commits the real menu DOM, and the Mute
            item clicked from OUTSIDE any render scope lands on the frame
            the enclosing `frame-provider` named — not on a second live
            instance, and not on a `{:frame :rf/xray}` literal, which
            would leave the mounted frame empty. The menu then closes
            from its own subscription."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; The competing instance is given a mute of its own BEFORE the
        ;; mount, so the negative half below is measured with an
        ;; instrument demonstrably able to see this frame's slot.
        (mute! competing-frame competing-id)
        (open-menu! instance-frame muted-id)
        (let [{:keys [container root]} (mount! instance-frame
                                               [spine-filters/RowContextMenu])]
          ;; ---- the MOUNT half -------------------------------------------
          (is (some? (menu-node container))
              "the menu committed a real DOM root under React — the Fresco
               boundary behind the public bridge name really ran its body,
               which no node-lane row can witness because the door it
               drives is handed the read as an argument")
          (is (some? (menu-backdrop container))
              "and the outside-click backdrop committed alongside it, so
               the boundary rendered the WHOLE `[:<>]` fragment rather
               than its first child")
          (is (= (str muted-id)
                 (.getAttribute (menu-node container) "data-rf-xray-event-id"))
              (str "the committed menu carries the seeded event-id, so the
                    boundary's `rf.fresco/sub` resolved the open state
                    rather than painting a default shell. Expected "
                   (pr-str (str muted-id))))
          (is (some? (menu-mute-btn container))
              "and the Mute item is on screen to be clicked")

          ;; ---- the read landed in the frame the tree named ---------------
          (is (pos? (ref-count-of instance-frame menu-q))
              (str "the boundary's read " (pr-str menu-q) " holds a
                    reference in the MOUNTED frame's sub-cache. Cache keys: "
                   (pr-str (keys (cache-of instance-frame)))))
          (is (zero? (ref-count-of competing-frame menu-q))
              "and NOT in the competing instance's — a boundary that
               inherited an ambient scope instead of reading React context
               would put it there")

          ;; ---- the DISPATCH half, outside any render scope ---------------
          (.click (menu-mute-btn container))
          (-> (rf.test-support/poll-until
                #(= #{muted-id} (muted-of instance-frame))
                {:label "the Mute item's dispatch reached the mounted frame"})
              (.then
                (fn [_]
                  (is (= #{muted-id} (muted-of instance-frame))
                      (str "THE MUTE LANDED ON THE MOUNTED INSTANCE. The
                            boundary captured its frame at render time and
                            dispatched into it (rf2-nesy9); under the
                            `{:frame :rf/xray}` literal this replaced, this
                            frame would still read nil. Expected "
                           (pr-str #{muted-id}) ", got "
                           (pr-str (muted-of instance-frame))))
                  (is (= #{competing-id} (muted-of competing-frame))
                      (str "and the COMPETING instance is exactly as it was
                            — still holding its own seeded mute and nothing
                            else. A leak would read "
                           (pr-str #{competing-id muted-id}) ". Got "
                           (pr-str (muted-of competing-frame))))
                  ;; ---- closes from its own subscription ------------------
                  (rf.test-support/poll-until
                    #(nil? (menu-node container))
                    {:label "the menu closed from its own subscription"})))
              (.then
                (fn [_]
                  (is (nil? (menu-node container))
                      "the menu item's `close!` invalidated the boundary's
                       own read and the surface left the DOM — the boundary
                       is live, not a one-shot paint")
                  (is (nil? (menu-backdrop container))
                      "and the backdrop went with it")))
              (.catch (fn [e]
                        (is false (str "W1 never settled: " (.-message e)
                                       " — instance " (pr-str (muted-of instance-frame))
                                       ", competing " (pr-str (muted-of competing-frame))
                                       ", DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W2 — the modal mounts real rows, and an Unmute updates the live surface
;; ===========================================================================

(deftest w2-modal-bridge-commits-real-rows-and-unmutes-in-place
  (testing "rf2-3du3 — `spine-filters/Modal` mounted as the shell's
            hiccup head commits the real muted-row DOM through the keyed
            native fragment, and an Unmute click updates the LIVE surface
            in place from the boundary's own subscription. The deaf
            control is what makes that update mean liveness rather than a
            commit that had simply not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (mute! competing-frame competing-id)
        (mute! instance-frame muted-id)
        (mute! instance-frame second-id)
        (open-manager! instance-frame)
        (let [{:keys [container root]} (mount! instance-frame
                                               [spine-filters/Modal])
              list-el (list-node container)]
          ;; ---- the MOUNT half -------------------------------------------
          (is (some? (dialog-node container))
              "the manager committed its real dialog under React — the
               boundary's gate read `:rf.xray/mute-manager-open?` true and
               its body ran")
          (is (nil? (empty-node container))
              "NON-VACUITY: this is the populated branch, not the
               empty-state one the boundary paints with no muted ids")
          (is (some? list-el)
              "and the list itself committed")
          (is (some? (row-node container muted-id))
              (str "the row for " (pr-str muted-id) " is real DOM"))
          (is (some? (row-node container second-id))
              (str "and so is the row for " (pr-str second-id)))
          (is (some? (unmute-btn container muted-id))
              "with a live per-row Unmute affordance")

          ;; ---- the NATIVE FRAGMENT KEYS (rf2-a38l), which only a real
          ;;      commit can witness -----------------------------------------
          (let [kids (element-children list-el)]
            (is (= 2 (count kids))
                (str "the list has EXACTLY the two seeded rows as element
                      children. `mute-row` is CALLED inside a keyed
                      `[:<> {:key …}]` fragment, and a fragment that had
                      degraded into a real DOM node — or a key Fresco's
                      codec dropped, collapsing the rows — would not read
                      2 here. Got " (count kids)))
            (is (= ["LI" "LI"] (mapv #(.-tagName %) kids))
                "and both children are the `<li>` rows THEMSELVES, so the
                 keyed fragment contributed no wrapper element of its own —
                 the whole point of keying a fragment rather than the row")
            (is (= [(str "rf-xray-mute-manager-row-" muted-id)
                    (str "rf-xray-mute-manager-row-" second-id)]
                   (mapv #(.getAttribute % "data-testid") kids))
                "in `sort-by str` order, which is the order the shipped
                 `list-section` renders"))

          ;; ---- phase 2: the world moves, and the boundary is deaf ---------
          (rf/dispatch-sync [::write-unwatched-slot 1] {:frame instance-frame})
          (-> (settle)
              (.then
                (fn [_]
                  (is (= 2 (count (element-children (list-node container))))
                      "CONTROL: given a full settling window, a write to an
                       app-db slot neither boundary's read set consults
                       commits nothing. A modal that repainted here would
                       make phase 3 pass for a reason that is not liveness")
                  ;; ---- phase 3: a real action on a real row -------------
                  (.click (unmute-btn container muted-id))
                  (rf.test-support/poll-until
                    #(nil? (row-node container muted-id))
                    {:label "the unmuted row left the live surface"})))
              (.then
                (fn [_]
                  (is (= #{second-id} (muted-of instance-frame))
                      (str "the Unmute dispatch landed on the MOUNTED frame
                            and removed exactly one id. Expected "
                           (pr-str #{second-id}) ", got "
                           (pr-str (muted-of instance-frame))))
                  (is (= #{competing-id} (muted-of competing-frame))
                      (str "and the competing instance is untouched. Got "
                           (pr-str (muted-of competing-frame))))
                  (is (nil? (row-node container muted-id))
                      "the unmuted row is gone from the DOM")
                  (is (some? (row-node container second-id))
                      "the other row is still there — the surface UPDATED
                       rather than being torn down")
                  (is (identical? list-el (list-node container))
                      "and it is the SAME <ul> node: React reconciled the
                       live tree in place, so the update did not arrive by
                       the modal being remounted from scratch, which would
                       not be liveness")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — instance " (pr-str (muted-of instance-frame))
                                       ", DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — unmount releases every read of both boundaries; reopen does not grow
;; ===========================================================================

(deftest w3-unmount-releases-both-boundaries-reads-and-reopen-does-not-grow
  (testing "rf2-3du3 — unmounting the two bridges releases ALL FOUR
            subscription references the boundaries hold between them, and
            mounting them again returns to the SAME counts rather than
            higher ones.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls
            rather than reading once: the collector gives a cell whose
            last reader unmounts one macrotask of grace, so that a keyed
            reorder which unmounts and remounts a row within a single turn
            reuses the reaction instead of rebuilding it. A synchronous
            assertion would report a LEAK against a collector behaving
            exactly as documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; Both surfaces OPEN, so `ModalView`'s two gated reads are taken
        ;; as well as its gate — a closed manager would make the release
        ;; assertion vacuous for `muted-q` and `pos-q`.
        (mute! instance-frame muted-id)
        (open-manager! instance-frame)
        (open-menu! instance-frame muted-id)
        ;; The starting point is polled, not asserted: a neighbouring
        ;; row's teardown grace may still be in flight when this begins.
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]}
                      (mount! instance-frame [spine-filters/Modal
                                              spine-filters/RowContextMenu])
                      mounted (mapv #(ref-count-of instance-frame %)
                                    boundary-reads)]
                  (is (some? (dialog-node container))
                      "PRECONDITION: the manager really is on screen")
                  (is (some? (menu-node container))
                      "PRECONDITION: and so is the row menu")
                  (is (every? pos? mounted)
                      (str "the mount took a reference for every one of the
                            boundaries' reads — otherwise the release below
                            is vacuous. Got " (pr-str mounted) " for "
                           (pr-str boundary-reads)))
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released every read"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released them COMPLETELY,
                                    within the collector's grace macrotask.
                                    Cache: "
                                   (pr-str (keys (cache-of instance-frame)))))
                          ;; ---- reopen: the same counts, not higher ----
                          (let [{c2 :container r2 :root}
                                (mount! instance-frame
                                        [spine-filters/Modal
                                         spine-filters/RowContextMenu])
                                remounted (mapv #(ref-count-of instance-frame %)
                                                boundary-reads)]
                            (is (= mounted remounted)
                                (str "reopening returns to the SAME reference
                                      counts " (pr-str mounted) " rather than
                                      accumulating — accumulation across
                                      open/close cycles is the signature of a
                                      release the substrate's own reaction
                                      lifecycle cannot see. Got "
                                     (pr-str remounted)))
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released them too"}))))))))
            (.then (fn [_] (is (released?)
                               "and the second unmount releases them too")))
            (.catch (fn [e]
                      (is false (str "W3 poll timed out: " (.-message e)))
                      nil))
            (.then (fn [_] (done))))))))
