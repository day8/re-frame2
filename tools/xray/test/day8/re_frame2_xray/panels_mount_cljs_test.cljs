(ns day8.re-frame2-xray.panels-mount-cljs-test
  "Per-panel standalone-mount tests.

  Pins the contract: every public `panels/mount-<panel>!` fn renders
  its panel in isolation. No shell, no siblings, no shell-owned
  chrome state. The mount fn:

    1. Installs Xray's handlers (idempotent).
    2. Registers `:rf/xray` (idempotent).
    3. Wraps the panel view in `[rf/frame-provider {:frame _} [Panel]]`.
    4. Delegates to `rf.substrate.adapter/render` with the wrapped tree.
    5. Returns the adapter's unmount fn.

  ## Test strategy

  We stub `rf.substrate.adapter/render` so the test can capture the
  rendered tree (the wrapped hiccup) without booting an actual
  substrate. Each test:

    - Calls the mount fn against a sentinel mount-point.
    - Asserts the captured tree has the canonical
      `[rf/frame-provider {:frame :rf/xray} [Panel]]` shape.
    - Asserts the substrate-adapter render was invoked exactly once.
    - Asserts the returned value is the (stubbed) unmount fn — the
      host's lifecycle anchor.

  Per-panel render-correctness (the actual view body) is covered by
  the `panels/<panel>_cljs_test.cljs` suites; this file
  pins the MOUNT API contract, not the view contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is the one reset owner: plain-atom adapter
  ;; + the default `:all` reset tier, which includes the trace-collector
  ;; ring reset. (`trace-collector` is required for the
  ;; `seed-trace-for-test!` seeding below.)
  (xray-test-support/make-xray-runtime-fixture))

;; ---- render-stub helper -------------------------------------------------

(defn- make-render-stub
  "Build a stub for `rf.substrate.adapter/render` that captures every
  invocation. Returns `[capture-atom unmount-fn render-fn]` —

    - `capture-atom` — `[{:tree _ :mount-point _ :opts _} ...]`
    - `unmount-fn` — a sentinel fn the stub returns; tests assert the
      mount fn passes it through unchanged.
    - `render-fn` — the stub itself; pass to `with-redefs`."
  []
  (let [capture  (atom [])
        unmount  (fn unmount-stub [] :unmount-called)
        render-fn (fn [tree mount-point opts]
                    (swap! capture conj {:tree tree
                                         :mount-point mount-point
                                         :opts opts})
                    unmount)]
    [capture unmount render-fn]))

(defn- captured-tree
  "Helper — return the first captured render tree."
  [capture]
  (-> @capture first :tree))

(defn- frame-provider-wrap?
  "True when the captured tree is the canonical
  `[rf/frame-provider {:frame :rf/xray} [Panel]]` shape."
  [tree expected-panel-view]
  (and (vector? tree)
       (= rf/frame-provider (first tree))
       (= {:frame :rf/xray} (second tree))
       (vector? (nth tree 2))
       (= expected-panel-view (first (nth tree 2)))))

;; ---- L3-tab panels + overlay / popup surfaces --------------------------

(deftest mount-epoch-panel-wraps-in-frame-provider-and-delegates-to-adapter
  (let [[capture unmount-sentinel render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (let [unmount (panels/mount-epoch-panel! :mount-point-sentinel)]
        (is (= [1 true :mount-point-sentinel unmount-sentinel]
               [(count @capture)
                (frame-provider-wrap? (captured-tree capture) epoch-panel/Panel-bridge)
                (-> @capture first :mount-point)
                unmount])
            "one render of the wrapped Panel at the host's mount-point, and the
             adapter's unmount fn handed back")))))

(deftest every-other-panel-mount-wraps-its-view-in-the-frame-provider
  ;; The remaining L3-tab panels and the two overlay / popup surfaces.
  ;; Where a panel's `Panel` is a Fresco boundary (a React function
  ;; component) the expected view is its `Panel-bridge`: `render-panel!`
  ;; builds a REAGENT tree, so the bridge is what the mount fn hands it.
  ;; The shape each row pins is frame-provider :rf/xray wrapping the view,
  ;; because the bridge takes its frame from the same React context the
  ;; provider writes.
  (doseq [[label mount! view]
          [["app-db-diff"       panels/mount-app-db-diff!       app-db-diff/Panel-bridge]
           ["reactive"          panels/mount-reactive-panel!    reactive-panel/Panel-bridge]
           ["trace"             panels/mount-trace!             trace/Panel-bridge]
           ["machine-inspector" panels/mount-machine-inspector! machine-inspector/Panel-bridge]
           ["routing"           panels/mount-routing!           routing/Panel]
           ["cancellation-cascade side panel"
            panels/mount-cancellation-cascade-side-panel! cancellation-cascade/SidePanel]
           ["cancellation-cascade popover"
            panels/mount-cancellation-cascade-popover!    cancellation-cascade/Popover]]]
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (mount! :mount-point)
        (is (frame-provider-wrap? (captured-tree capture) view)
            (str label ": frame-provider :rf/xray wraps the panel's view"))))))

;; ---- inline content surface (managed-fx) -------------------------------

(deftest mount-managed-fx-wraps-ManagedFxList
  ;; The expected view is `ManagedFxList-bridge`, for the reason the table
  ;; above records: `ManagedFxList` is a Fresco boundary and
  ;; `render-panel!` builds a REAGENT tree. The shape this row pins is
  ;; frame-provider :rf/xray wrapping the view.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-managed-fx! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) panels/ManagedFxList-bridge)))))

;; ---- full-shell mount --------------------------------------------------

;; ---- the full-shell embed forwards its own-frame opt -------------------
;;
;; `008-Embedding-Contract.md` §Embed props inventory publishes exactly two
;; host-visible props on the full-shell embed, and `:frame` is one of them:
;; the frame the shell's frame-provider wraps — Xray's OWN frame, distinct
;; from the inspected host target the frame-picker chooses. `shell-view`
;; takes that axis as its `:frame-id` opt, and
;; `two-instance-isolation-cljs-test` pins the isolation it buys at the
;; data layer by binding the two frames directly.
;;
;; `mount-shell!` carries the option across. Reading only `:mode` out of
;; `opts` would drop `:frame` on the floor, so every embed — however many,
;; however addressed — would resolve to the default `:rf/xray` and share
;; one app-db. The source-level singleton guard
;; (`frame_singleton_guard_test.clj`) cannot see that: there would be no
;; literal `:rf/xray` to find, only a caller option that is never read.
;;
;; These deftests sit at the PUBLIC full-shell boundary — through
;; `mount-shell!`, never `shell-view` directly — which is precisely the
;; surface the data-layer isolation test steps around.

(def ^:private embed-cell-a :review/xray-a)
(def ^:private embed-cell-b :review/xray-b)

(defn- shell-frame-id
  "The `:frame-id` prop the n'th captured `[shell/shell-view {…}]` tree
  carries — the shell's own frame."
  [capture n]
  (-> @capture (nth n) :tree second :frame-id))

(defn- read-in-frame [frame-id query]
  (rf/with-frame frame-id (rf/subscribe-once query)))

(defn- dispatch-in-frame! [frame-id event-v]
  (rf/with-frame frame-id (rf/dispatch-sync event-v)))

(deftest mount-shell-forwards-the-frame-opt-as-the-shells-own-frame-id
  ;; Without the forwarding both trees would carry no `:frame-id` and both
  ;; embeds would collide on `shell/default-frame-id`.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-shell! :mount-a {:frame embed-cell-a})
      (panels/mount-shell! :mount-b {:frame embed-cell-b}))
    (is (= [:mount-a :mount-b] (mapv :mount-point @capture)))
    (is (= [embed-cell-a embed-cell-b] [(shell-frame-id capture 0) (shell-frame-id capture 1)])
        "each shell carries the own frame its caller requested")
    (is (= [true true] [(some? (rf.frame/frame embed-cell-a)) (some? (rf.frame/frame embed-cell-b))])
        "and each is SEATED: the embed contract does not ask the host to pre-seat one")))

(deftest mount-shell-frame-opt-defaults-and-leaves-mode-intact
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-shell! :mount-default)
      (panels/mount-shell! :mount-overlay {:mode :overlay})
      (panels/mount-shell! :mount-both {:mode  :overlay
                                        :frame embed-cell-a}))
    (is (= [[shell/default-frame-id :inline]
            [shell/default-frame-id :overlay]
            [embed-cell-a :overlay]]
           (mapv #((juxt :frame-id :mode) (second (:tree %))) @capture))
        "the frame defaults, and the frame and mode opts travel independently")))

(deftest mount-shell-instances-hold-independent-own-frame-state
  ;; The `with-frame` reads stand in for the two frame-provider scopes the
  ;; mounted shells establish. Were both mounts to resolve to one frame,
  ;; B's row would read back A's values.
  (let [[_ _ render-stub] (make-render-stub)
        state (fn [frame-id]
                [(read-in-frame frame-id [:rf.xray/selected-tab])
                 (read-in-frame frame-id [:rf.xray/mode])
                 (:dispatch-id (read-in-frame frame-id [:rf.xray/focus]))])]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-shell! :mount-a {:frame embed-cell-a})
      (panels/mount-shell! :mount-b {:frame embed-cell-b}))
    (dispatch-in-frame! embed-cell-b [:rf.xray/select-tab :trace])
    (dispatch-in-frame! embed-cell-b [:rf.xray/set-mode :static])
    (dispatch-in-frame! embed-cell-b [:rf.xray/focus-event :b-cascade :rf/default])
    (dispatch-in-frame! embed-cell-a [:rf.xray/select-tab :app-db])
    (dispatch-in-frame! embed-cell-a [:rf.xray/focus-event :a-cascade :rf/default])
    (is (= [[:app-db :dynamic :a-cascade] [:trace :static :b-cascade]]
           [(state embed-cell-a) (state embed-cell-b)]))))

;; ---- the per-panel mount SEATS the frame it PROVIDES -------------------
;;
;; `opts {:frame …}` overrides the default `:rf/xray` the frame-provider
;; wraps (008-Embedding-Contract.md §State isolation) — a host can choose a
;; different frame for the React-context tier, e.g. a Story variant frame.
;; The deftest below pins that override on the WRAPPER and on what sits one
;; line above it: `render-panel!` must seat the SAME frame it anchors the
;; provider at. Seating `shell/default-frame-id` (the ZERO arity of
;; `ensure-xray-handlers-installed!`) while anchoring the provider at
;; `opts :frame` would put two different frames in play on an override,
;; and since no panel view opens an inner provider of its own — every
;; panel's docstring says its isolation comes from the ENCLOSING one — the
;; panel body's whole `:rf.xray/*` surface would resolve into a frame
;; nothing had seated or seeded.
;;
;; So this row is deliberately NOT taken off the captured tree: the stubbed
;; `adapter/render` observes the wrapper, which passes either way. It reads
;; the frame REGISTRY, which the real `ensure-xray-handlers-installed!` wrote
;; on the way past the stub — the same assertion shape the `mount-shell!`
;; rows above use.

(def ^:private panel-cell-frame :review/xray-panel-cell)

(deftest mount-panel-seats-the-own-frame-it-provides
  (testing "a per-panel mount given an explicit `:frame` seats
            THAT frame. Seating `shell/default-frame-id` while providing
            the override would make the first row below read nil and land
            the panel's subscribes in an empty frame."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point {:frame panel-cell-frame}))
      (is (= panel-cell-frame (:frame (second (captured-tree capture))))
          "and it is the SAME frame the provider anchors — seated and
           provided are one value by construction")
      (is (some? (read-in-frame panel-cell-frame [:rf.xray/selected-tab]))
          "and the seed hooks ran in it, so a panel body's `:rf.xray/*`
           subscribe resolves there rather than reading an empty frame"))))

;; ---- contract — instance-id opt ---------------------------------------
;;
;; THE EVIDENCE THAT TWO NAMED MOUNTS ARE ACTUALLY SEPARATED IS NOT HERE. It
;; is `panels/app_db_diff_mount_instance_id_dom_cljs_test`, which mounts two
;; of them for real and reads `data-rf-mount-id` / `data-rf-site-id` off the
;; committed DOM, plus the widget's per-mount store across an unmount. These
;; rows pin the MOUNT-API half in the file that owns the mount API, and they
;; are deliberately not written as "the opts key was threaded": nothing below
;; reads the opts map or the captured props. Each row takes the ELEMENT the
;; mount delivered and APPLIES it — which is what the substrate does to a
;; Reagent form-1 element — so the real `Panel-bridge` runs and what is
;; asserted is the props the boundary is mounted with.

(defn- delivered-element
  "The element `render-panel!` put inside the frame-provider — the hiccup the
  substrate is asked to render, taken off the captured tree rather than
  rebuilt here."
  [capture]
  (nth (captured-tree capture) 2))

(defn- delivered-by
  "Mount the app-db panel through the public facade with `opts`; return the
  element it delivered to the substrate."
  [opts]
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-app-db-diff! :mount-point opts))
    (delivered-element capture)))

(defn- crossed
  "Apply `el`'s head to its arguments — what a substrate does with a Reagent
  form-1 element — and return the props map the resulting `[:> Component
  props]` mounts the boundary with. The real bridge runs, so this is what the
  panel RECEIVES rather than what the caller passed."
  [el]
  (nth (apply (first el) (rest el)) 2))

(deftest mount-app-db-diff-instance-id-reaches-the-boundary
  (testing "`mount-app-db-diff!`'s `:instance-id` opt reaches the
            Fresco boundary's props, across the real `Panel-bridge`. This is
            the mount door onto `Panel`'s `:instance-id` prop: a caller that
            MOUNTS passes opts and never props, so without it the standalone
            embed could not name an instance at all."
    (is (= {:instance-id "left"}
           (crossed (delivered-by {:instance-id "left"})))
        "the name the mount was given is the name the boundary is mounted with")
    (is (= {:instance-id "left/panel"}
           (crossed (delivered-by {:instance-id :left/panel})))
        "AND A NAMESPACE SURVIVES, which is why the bridge tokenises.
         `cljs.core/name` drops it, so passed through raw, `:left/panel`
         and `:right/panel` would both reach the boundary as \"panel\" —
         two mounts this opt deliberately names apart sharing one
         `:mount-id`, one width slot and one `:site-id`. This is the mount
         door onto that prop, so it is the door that has to carry it")
    (is (= {:instance-id "right"}
           (crossed (delivered-by {:frame :my-app/cart :instance-id "right"})))
        "and it composes with `:frame` rather than replacing it"))

  (testing "the `:frame` opt is untouched by `:instance-id`."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-app-db-diff! :mount-point
                                   {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "the frame-provider still wraps the frame the host named")))))

(deftest mount-fns-without-an-instance-name-deliver-exactly-what-they-did
  (testing "an UNNAMED app-db mount delivers the bare
            `[Panel-bridge]` element, not `[Panel-bridge {}]`. The 0-arity
            is the shape the shell's `[(:panel tab)]` and every standalone
            call site in this tree take, and it is what keeps their composed
            ids free of any instance token."
    (is (= [app-db-diff/Panel-bridge] (delivered-by nil))
        "no opts at all")
    (is (= {} (crossed (delivered-by nil)))
        "and the bridge's 0-arity mounts the boundary with no props"))

  (testing "`:instance-id` is SOME PANELS' opt, not the surface's.
            Only a panel whose view takes the prop may be handed a props map;
            handing one to a view that takes none is an arity error, not an
            ignored key, so every other mount fn must deliver a bare
            element even when its caller sets the opt.

            A panel is picked here for being a CURRENT member of the no-prop
            group, not for being a permanent one, so the pick moves as panels
            take the prop. `mount-trace!`, `mount-epoch-panel!` and
            `mount-machine-inspector!` take it, asserted in
            [[mount-trace-instance-id-reaches-the-boundary]],
            [[mount-epoch-panel-instance-id-reaches-the-boundary]] and
            [[mount-machine-inspector-instance-id-reaches-the-boundary]].

            The pick is `mount-reactive-panel!`, a sharp witness:
            `reactive-panel/Panel-bridge` is declared 0-arity ONLY, so a
            props map here is the arity error this row names rather than a
            silently ignored key."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-reactive-panel! :mount-point {:instance-id "left"})
        (is (= [reactive-panel/Panel-bridge] (delivered-element capture))
            "the reactive mount delivers its view with no props")))))

(defn- trace-delivered-by
  "Mount the Trace panel through the public facade with `opts`; return the
  element it delivered to the substrate."
  [opts]
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-trace! :mount-point opts))
    (delivered-element capture)))

(deftest mount-trace-instance-id-reaches-the-boundary
  (testing "`mount-trace!`'s `:instance-id` opt reaches the Fresco
            boundary's props, across the real `Panel-bridge`. This is the
            mount door onto the prop the panel reads: a caller that MOUNTS
            passes opts and never props, so without it the standalone embed
            could not name an instance at all.

            The DOM-level claim — that naming two mounts actually separates
            their inspector lifecycle and width — is
            `panels/trace_mount_instance_id_dom_cljs_test`'s. This row is only
            that the opt survives the facade and the crossing, which is the
            half a DOM row cannot localise when it fails."
    (is (= {:instance-id "left"}
           (crossed (trace-delivered-by {:instance-id "left"})))
        "the opt crosses the bridge and arrives as a prop")
    (is (= {:instance-id "left/trace"}
           (crossed (trace-delivered-by {:instance-id :left/trace})))
        "and a KEYWORD keeps its namespace — `[:>]` would convert it
         with `cljs.core/name` and drop the namespace, colliding mounts the
         opt names apart, so the bridge tokenises BEFORE the crossing")
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-trace! :mount-point
                             {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "and it composes with `:frame` rather than replacing it — the
             frame-provider still wraps the frame the host named")))
    (is (= [trace/Panel-bridge] (trace-delivered-by nil))
        "an UNNAMED trace mount delivers the bare `[Panel-bridge]` element,
         not `[Panel-bridge {}]` — the shape the shell's `[(:panel tab)]`
         and every standalone call site in this tree take, and what keeps
         their composed ids free of any instance token")
    (is (= {} (crossed (trace-delivered-by nil)))
        "and the bridge's 0-arity mounts the boundary with no props")))

(defn- epoch-delivered-by
  "Mount the Epoch panel through the public facade with `opts`; return the
  element it delivered to the substrate."
  [opts]
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-epoch-panel! :mount-point opts))
    (delivered-element capture)))

(deftest mount-epoch-panel-instance-id-reaches-the-boundary
  (testing "`mount-epoch-panel!`'s `:instance-id` opt reaches the
            Fresco boundary's props, across the real `Panel-bridge`. This is
            the mount door onto the prop the panel reads: a caller that
            MOUNTS passes opts and never props, so without it the standalone
            embed could not name an instance at all.

            The DOM-level claim — that naming two mounts actually separates
            their inspector lifecycle and width — is
            `panels/epoch_machine_mount_instance_id_dom_cljs_test`'s. This row
            is only that the opt survives the facade and the crossing, which
            is the half a DOM row cannot localise when it fails."
    (is (= {:instance-id "left"}
           (crossed (epoch-delivered-by {:instance-id "left"})))
        "the opt crosses the bridge and arrives as a prop")
    (is (= {:instance-id "left/epoch"}
           (crossed (epoch-delivered-by {:instance-id :left/epoch})))
        "and a KEYWORD keeps its namespace — `[:>]` would convert it
         with `cljs.core/name` and drop the namespace, so `:left/epoch` and
         `:right/epoch` would both arrive as \"epoch\" and compose one
         `epoch/epoch/dispatch-event`, the collision naming exists to
         prevent. The bridge tokenises BEFORE the crossing for that reason")
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point
                                   {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "and it composes with `:frame` rather than replacing it — the
             frame-provider still wraps the frame the host named")))
    (is (= [epoch-panel/Panel-bridge] (epoch-delivered-by nil))
        "an UNNAMED epoch mount delivers the bare `[Panel-bridge]` element,
         not `[Panel-bridge {}]` — the shape the shell's `[(:panel tab)]`
         and every standalone call site in this tree take, and what keeps
         their composed ids free of any instance token")
    (is (= {} (crossed (epoch-delivered-by nil)))
        "and the bridge's 0-arity mounts the boundary with no props")
    (is (= {} (crossed (epoch-delivered-by {:instance-id ""})))
        "a BLANK string tokenises to nil and mounts with no props, exactly as
         naming no instance does — the facade passes it through (the empty
         string is truthy), so it is the tokeniser that has to refuse it")))

(defn- machine-inspector-delivered-by
  "Mount the Machine Inspector through the public facade with `opts`; return
  the element it delivered to the substrate."
  [opts]
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-machine-inspector! :mount-point opts))
    (delivered-element capture)))

(deftest mount-machine-inspector-instance-id-reaches-the-boundary
  (testing "`mount-machine-inspector!`'s `:instance-id` opt reaches
            the Fresco boundary's props, across the real `Panel-bridge`.

            THIS PANEL'S CONTRACT DIFFERS FROM EVERY SIBLING'S, which is why
            it gets its own row rather than riding on the epoch one above.
            Element 2 renders through `epoch-view/machine-cascade-mini-
            pipeline`, the SHARED renderer the Epoch panel's handler step
            uses, so this panel composes ids in the EPOCH panel's id
            namespace. `machine-inspector/instance-token` therefore NEVER
            answers nil: it names the panel itself, and a caller's token rides
            BELOW that. The rows below pin both halves of that."
    (is (= {:instance-id "machine-inspector"}
           (crossed (machine-inspector-delivered-by nil)))
        "AN UNNAMED MOUNT IS THE INTERESTING ONE — it crosses carrying this
         panel's OWN name, not `{}`. That keeps it apart from the Epoch panel
         with no caller action, because which panel is rendering is
         statically known and an embedder mounting one Epoch panel and one
         Machine Inspector over the same cascade cannot see the collision to
         work around it. Every sibling panel answers nil here")
    (is (= [machine-inspector/Panel-bridge] (machine-inspector-delivered-by nil))
        "the DELIVERED element is the bare `[Panel-bridge]`, though — the
         panel's own name is what the bridge mounts the boundary with, not
         what the facade hands the substrate")
    (is (= {:instance-id "machine-inspector/left"}
           (crossed (machine-inspector-delivered-by {:instance-id "left"})))
        "a caller's name rides BELOW the panel's own, so two standalone
         Machine Inspectors are distinct AND one named `left` cannot collide
         with an Epoch panel named `left` either")
    (is (= {:instance-id "machine-inspector/left/machines"}
           (crossed (machine-inspector-delivered-by {:instance-id :left/machines})))
        "and a KEYWORD keeps its namespace, as for every other
         bridge that tokenises before the crossing")
    (is (= {:instance-id "machine-inspector/left"}
           (crossed (machine-inspector-delivered-by
                      {:instance-id "machine-inspector/left"})))
        "the tokeniser is IDEMPOTENT on its own output, which is what makes
         the boundary's second call after the crossing a no-op rather than a
         `machine-inspector/machine-inspector/left`")
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-machine-inspector!
          :mount-point {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "and it composes with `:frame` rather than replacing it — the
             frame-provider still wraps the frame the host named")))))

;; ---- contract — panel mount routes through mount/ensure-xray-frame! ---
;;
;; `ensure-xray-handlers-installed!` routes through `mount/ensure-xray-frame!`
;; so every panel-only mount path fires the same first-mount hook table the
;; full-shell `open!` runs — including `::seed-trace-and-target-frame`, the
;; hook that lifts the pre-mount trace-bus buffer into Xray's
;; `:trace-buffer` slot AND seeds `:target-frame` + `:epoch-history` from
;; the head focusable cascade's frame. A direct `(rf/make-frame {:id
;; :rf/xray})` would register the frame but bypass that table, so a host
;; that dispatched events before any panel was mounted, then mounted a
;; panel, would see empty Event + App-DB panels on the Story RHS: the slots
;; the panels subscribe to would never be populated.

(defn- pre-mount-dispatch-event
  "Build a trace event matching the shape `event/dispatched` produces.
  Enough for `rf.trace.projection/group-by-event` to bucket it into a cascade
  with `:frame` set so `spine/focusable-head-frame-id` resolves."
  [id dispatch-id frame-id event-id]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id dispatch-id
               :frame       frame-id
               :rf.event/v       [event-id]}})

(deftest mount-panel-seeds-target-frame-from-head-focusable-cascade
  (testing "Mounting a panel directly (without going through the full
            shell `open!`) seeds `:target-frame` from the head focusable
            cascade's frame — matching the contract the full-shell path
            observes. A panel-only mount that skipped the seed hook would
            leave `:target-frame` at `defaults/default-target-frame`
            regardless of pre-mount traffic on a non-default frame — the
            empty-Xray-on-Story-RHS class of bug."
    (let [[_capture _ render-stub] (make-render-stub)
          cart-records [{:epoch-id      :e-1
                         :frame         :cart-frame
                         :db-before     {:cart {:items []}}
                         :db-after      {:cart {:items [{:id 7}]}}
                         :trigger-event [:cart/add-item]
                         :event-id      :cart/add-item
                         :trace-events  []}]]
      (with-redefs [rf.substrate.adapter/render render-stub
                    rf/epoch-history (fn [frame-id]
                                       (case frame-id
                                         :cart-frame cart-records
                                         []))]
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 1 100 :cart-frame :cart/add-item))
        (panels/mount-app-db-diff! :mount-point)
        (rf/with-frame :rf/xray
          (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame]))
              "`:target-frame` seeds from the head focusable cascade's
               `:frame` via the `::seed-trace-and-target-frame` hook.")
          (is (= cart-records @(rf/subscribe [:rf.xray/epoch-history]))
              "`:epoch-history` re-seeds in lockstep from
               `(rf/epoch-history :cart-frame)` per the
               `:rf.xray/set-target-frame` reducer."))))))
