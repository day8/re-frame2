(ns day8.re-frame2-xray.filters.edit-popup-fresco-boundary-dom-cljs-test
  "Real-DOM witnesses for the filter edit-popup bridge — `filters/Modal`
  (rf2-d9ln), the last shell-root modal to become a Fresco boundary.

  ## The gap this file closes

  rf2-d9ln turned `filters/Modal` into a BOUNDARY behind its existing
  public bridge name. Its production observation changed — three ambient
  `@(rf/subscribe …)` reads inside `popup-view` became `rf.fresco/sub`
  calls in the boundary — and so did its dispatch, which is now captured
  with `(:dispatch (rf/capture-frame))` rather than injected lexically by
  `reg-view`. But every node-lane row that moved with it drives a
  test-owned copy of the gate and the reads through
  `test-helpers.modal-trees/edit-popup-tree`, calling the pure
  `popup-view` directly. Nothing mounts the bridge and nothing executes
  the boundary, so a broken real gate or a nil boundary body would leave
  the whole node lane green.

  This file is the missing half, and it is the edit-popup sibling of
  `spine_filters_fresco_boundary_dom_cljs_test` (rf2-3du3), which closed
  exactly this gap for the two spine-filter bridges:

    W1  Modal mounts as the shell's own hiccup head, its gate reads
        `:rf.xray/edit-popup-open?` TRUE, and the boundary body commits
        the real dialog DOM — with a CLOSED instance beside it proving
        the gate is a gate rather than an unconditional paint.

    W2  The header ✕, clicked from OUTSIDE any render scope, lands on the
        frame the enclosing `frame-provider` named — not on a second live
        instance, and not on a `{:frame :rf/xray}` literal — and the
        surface then repaints from the boundary's OWN subscription.

  ## Why the node lane cannot make either claim

  `expand-tree` INVOKES a fn head, so `[Modal]` and `(Modal)` expand to
  the same value and the node lane is structurally blind to head
  legality. It is blind twice over here: the door it drives passes the
  boundary's reads in as ARGUMENTS, so the gate and the `rf.fresco/sub`
  calls that are the actual subject of rf2-d9ln never run at all. Only a
  committed DOM can answer, which is why these rows are `-dom-cljs-test`.

  ## The mount is the SHELL's mount

  `shell.cljs` mounts this bridge as a plain hiccup HEAD inside the
  shell's `[rf/frame-provider {:frame frame-id}]` (`:3161`). [[mount!]]
  does exactly that and nothing else — no wrapper, no second call. Every
  assertion after the mount reads `container.querySelector…`, i.e. the
  DOM React committed on its own.

  ## Frames: two private ones, and NEVER `:rf/xray`

  `:rf/xray` is the production singleton, shared with every other suite
  on the page, so a control frame named `:rf/xray` could be moved by a
  neighbour between the mount and the assertion. Both frames here are
  private to this ns. That also makes W2 a direct witness for rf2-nesy9's
  frame-CARRYING dispatch: under a `{:frame :rf/xray}` literal the
  mounted frame below would never close and W2 would redden.

  ## Substrate: the Reagent adapter, deliberately

  The family Xray already supports, because the claim is that the
  boundary is INDIFFERENT to it. `:ambient-frame nil` is load-bearing:
  tier 1 of the frame resolver is the dynamic var, so an ambient frame
  would SHADOW the React-context tier W2 is about, and that row would
  pass while measuring nothing.

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
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- frames --------------------------------------------------------------

(def ^:private instance-frame
  "The Xray instance this suite mounts. A NAMED NON-DEFAULT frame, per
  the ns docstring — the shape `shell-view`'s `:frame-id` opt ships."
  ::instance)

(def ^:private competing-frame
  "A SECOND live Xray instance — W2's competing-frame control. A close
  performed in the mounted instance must move nothing here. It is opened
  too, so the control is demonstrably READABLE: an assertion that this
  frame is still open has to be able to fail."
  ::competing)

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
                      ;; would make these rows read a residue that is not
                      ;; this suite's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. A Fresco
  boundary is NOT in Reagent's render queue (its update is scheduled by
  the collector through React), so draining Reagent's queue would commit
  nothing of this boundary's and would read a DOM that has not moved."
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
;; the open state the boundary gates on is exactly what the shell's own
;; pill and right-click handlers produce.

(defn- setup!
  "Register Xray's handlers — which fans out to `filters/install!`, the
  source of every sub and event below — and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id instance-frame})
  (rf/make-frame {:id competing-frame})
  nil)

(defn- open-popup! [frame]
  (rf/dispatch-sync [:rf.xray/open-edit-popup {:source :add :mode :in}]
                    {:frame frame}))

(defn- mount!
  "Mount `views` as plain hiccup HEADS inside a `frame-provider` scoping
  `frame` — the exact shape `shell.cljs` uses at `:3161`. Committed
  synchronously: React 19's `root.render` is otherwise async and the
  first assertion would run against an empty container."
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
  next line runs. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- readers --------------------------------------------------------------
;;
;; EVERY DOM ACCESSOR HERE IS NIL-TOLERANT, AND THAT IS A REQUIREMENT
;; RATHER THAN A COURTESY. These rows exist to redden when the boundary
;; stops painting, so the absent-node case is their EXPECTED failure mode
;; — and `shadow.test` runs the whole browser lane, and the closing
;; summary, inside ONE `cljs.test/run-block` with no try/catch. A bare
;; `(.click nil)` therefore does not fail a row: it throws uncaught,
;; aborts the run, and every namespace scheduled after this one never
;; executes, with no cljs.test summary at all (rf2-u0j8).

(defn- q [container sel] (some-> container (.querySelector sel)))

(defn- testid-sel [id] (str "[data-testid=" (pr-str id) "]"))

(defn- dialog-node   [c] (q c (testid-sel "rf-xray-edit-popup-dialog")))
(defn- backdrop-node [c] (q c (testid-sel "rf-xray-edit-popup-backdrop")))
(defn- pattern-node  [c] (q c (testid-sel "rf-xray-edit-popup-pattern")))
(defn- mode-in-node  [c] (q c (testid-sel "rf-xray-edit-popup-mode-in")))
(defn- close-btn     [c] (q c (testid-sel "rf-xray-edit-popup-close")))

(defn- click!
  "Click `node`, or do nothing when it is absent, answering whether there
  was anything to click. See the nil-tolerance note above."
  [node]
  (when (some? node)
    (.click node))
  (some? node))

(defn- open-of
  "The frame's OWN `:edit-popup-open?` app-db slot, read through the
  public value accessor.

  This is an INDEPENDENT INSTRUMENT on purpose: it does not go through
  the sub layer the boundary reads, so a boundary that read nothing and a
  reader that read nothing cannot agree with each other."
  [frame-id]
  (:edit-popup-open? (rf/app-db-value frame-id)))

;; ===========================================================================
;; W1 — the bridge mounts, the gate is real, and the boundary paints
;; ===========================================================================

(deftest w1-edit-popup-bridge-mounts-and-paints-behind-a-real-gate
  (testing "rf2-d9ln — `filters/Modal` mounted as the shell's hiccup head
            commits the real edit-popup DOM, which is the claim no
            node-lane row can make: the door it drives is handed the
            boundary's reads as arguments, so neither the gate nor the
            `rf.fresco/sub` calls ever run there. A CLOSED instance
            mounted the same way paints nothing, which is what makes the
            open half evidence about the GATE rather than about an
            unconditional body."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; ---- the CLOSED half, first: the gate must really gate --------
        (let [{:keys [container root]} (mount! competing-frame [filters/Modal])]
          (is (nil? (dialog-node container))
              "a mounted bridge over a CLOSED popup commits NO dialog —
               the boundary's `when` short-circuited on a false
               `:rf.xray/edit-popup-open?`, exactly as the mounted
               `reg-view` returning nil did")
          (is (nil? (backdrop-node container))
              "and no backdrop either, so nothing of the body painted")
          (teardown! root container))

        ;; ---- the OPEN half -------------------------------------------
        (open-popup! instance-frame)
        (let [{:keys [container root]} (mount! instance-frame [filters/Modal])]
          (is (some? (dialog-node container))
              "the popup committed a real dialog under React — the Fresco
               boundary behind the public bridge name really ran its body
               and its gate read TRUE")
          (is (some? (backdrop-node container))
              "and the backdrop committed alongside it, so the boundary
               rendered the whole `modal-chrome` scaffold rather than its
               first child")
          (is (some? (pattern-node container))
              "the pattern input is real DOM — the subtree BELOW the
               boundary rendered too, which is what says every plain-fn
               head inside `popup-view` is a CALL and not an `:invalid`
               head (the inlining rf2-k97c.3 did, now load-bearing)")
          (is (some? (mode-in-node container))
              "and so are the mode radios, reached through `mode-radio`,
               the called helper that comment is about")
          (teardown! root container))
        (done)))))

;; ===========================================================================
;; W2 — the captured dispatch reaches the NAMED frame, and the surface is live
;; ===========================================================================

(deftest w2-edit-popup-close-lands-on-the-named-frame-and-repaints
  (testing "rf2-d9ln — the header ✕ inside the mounted boundary, clicked
            from OUTSIDE any render scope, closes the popup on the frame
            the enclosing `frame-provider` named while a second live
            instance stays open. Under the `{:frame :rf/xray}` literal
            the `reg-view` era's lexical `dispatch` would have produced,
            the mounted frame would never close. The surface then
            repaints from the boundary's OWN subscription, which is the
            liveness half."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; Both instances are opened BEFORE the mount, so the negative
        ;; half below is measured with an instrument demonstrably able to
        ;; see this slot move.
        (open-popup! competing-frame)
        (open-popup! instance-frame)
        (is (true? (open-of instance-frame))
            "PRE-CONDITION: the mounted instance is open")
        (is (true? (open-of competing-frame))
            "PRE-CONDITION: the competing instance is open, so the
             control below can fail")
        (let [{:keys [container root]} (mount! instance-frame [filters/Modal])]
          (is (some? (dialog-node container))
              "PRE-CONDITION: the boundary painted, so there is a ✕ to
               click")
          (is (true? (click! (close-btn container)))
              "the header ✕ is real DOM and was clicked")
          (-> (settle)
              (.then
                (fn [_]
                  (is (false? (open-of instance-frame))
                      "the close landed on the MOUNTED instance's frame —
                       the dispatcher the boundary captured with
                       `(:dispatch (rf/capture-frame))` carries the frame
                       the `frame-provider` named")
                  (is (true? (open-of competing-frame))
                      "and moved NOTHING on the second live instance, so
                       the dispatch was frame-CARRYING rather than
                       broadcast")
                  (is (nil? (dialog-node container))
                      "the surface repainted itself away — the boundary
                       re-rendered from its OWN `rf.fresco/sub` on
                       `:rf.xray/edit-popup-open?`, which is the liveness
                       this file exists to witness")
                  (teardown! root container)
                  (done)))
              (.catch
                (fn [e]
                  (is false (str "settle/assert threw: " e))
                  (teardown! root container)
                  (done)))))))))
