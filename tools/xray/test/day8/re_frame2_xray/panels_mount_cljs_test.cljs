(ns day8.re-frame2-xray.panels-mount-cljs-test
  "Per-panel standalone-mount tests — rf2-crhr8.

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
  the existing `panels/<panel>_cljs_test.cljs` suites; this file
  pins the MOUNT API contract, not the view contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.trace.projection :as rf.trace.projection]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.panels.app-db-diff :as app-db-diff]
            [day8.re-frame2-xray.panels.app-db-segment-inspector :as segment-inspector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.routing :as routing]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.panels.reactive-panel :as reactive-panel]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; into one owner: plain-atom adapter + the default `:all` reset tier,
  ;; which already includes the trace-collector ring reset the old init
  ;; called a SECOND, redundant time. (`trace-collector` is still required
  ;; for the `seed-trace-for-test!` seeding below.)
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

;; ---- top-level L3-tab panels (7) ---------------------------------------

(deftest mount-epoch-panel-wraps-in-frame-provider-and-delegates-to-adapter
  (testing "rf2-crhr8 + rf2-5gl5r — mount-epoch-panel! installs
            handlers, wraps epoch-panel/Panel-bridge in `[rf/frame-provider
            {:frame :rf/xray} [Panel]]`, delegates to substrate-
            adapter/render, and returns the adapter's unmount fn.
            (Replaces the prior mount-event-detail! coverage; the
            Event/Handler panel was retired alongside rf2-5gl5r.)"
    (let [[capture unmount-sentinel render-stub] (make-render-stub)
          mount-point :mount-point-sentinel]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (let [unmount (panels/mount-epoch-panel! mount-point)]
          (is (= 1 (count @capture))
              "rf.substrate.adapter/render invoked exactly once")
          (is (frame-provider-wrap? (captured-tree capture) epoch-panel/Panel-bridge)
              "tree is wrapped in rf/frame-provider :rf/xray around Panel")
          (is (= mount-point (-> @capture first :mount-point))
              "mount-point passed through unchanged")
          (is (= unmount-sentinel unmount)
              "adapter's unmount fn is returned to the caller"))
        ;; Side-effect — handlers landed.
        (is (some? (rf.registrar/handler :sub :rf.xray/event-bundles))
            "register-xray-handlers! ran as a side-effect of mount")
        (is (some? (rf.frame/frame :rf/xray))
            ":rf/xray frame is registered as a side-effect of mount")))))

(deftest mount-app-db-diff-wraps-in-frame-provider
  ;; rf2-k97c.3 — `Panel-bridge`; see `mount-reactive-panel-…` below.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-app-db-diff! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) app-db-diff/Panel-bridge)))))

(deftest mount-reactive-panel-wraps-in-frame-provider
  ;; rf2-k97c.3 — the expected view is `Panel-bridge`. `Panel` is now a
  ;; Fresco boundary (a React function component) and `render-panel!`
  ;; builds a REAGENT tree, so the bridge is what the mount fn hands it.
  ;; The shape this row pins — frame-provider :rf/xray wrapping the view
  ;; — is unchanged, which is the point: the bridge takes its frame from
  ;; the same React context the provider writes.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-reactive-panel! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) reactive-panel/Panel-bridge)))))

(deftest mount-trace-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-trace! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) trace/Panel-bridge)))))

(deftest mount-machine-inspector-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-machine-inspector! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) machine-inspector/Panel-bridge)))))

(deftest mount-routing-wraps-in-frame-provider
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-routing! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) routing/Panel)))))

;; (rf2-gbz39 — `mount-issues-ribbon-wraps-in-frame-provider` removed
;; alongside the Issues tab + its `mount-issues-ribbon!` entry. Option
;; (c): issues surface inline in the Epoch panel + the L2 event-row
;; pink-wash + the always-on issues ribbon signal — no standalone
;; Issues panel mount fn to cover.)

;; ---- overlay / popup surfaces (3) --------------------------------------

(deftest mount-segment-inspector-wraps-Popup-in-frame-provider
  ;; rf2-k97c.3 — the expected head is `Popup-bridge`. It is still `=` to
  ;; `Popup` (a `def` alias of the same value), so this row would pass
  ;; either way; naming the bridge is what keeps the panel's migration from
  ;; having to reopen this file.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-segment-inspector! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) segment-inspector/Popup-bridge)))))

(deftest mount-cancellation-cascade-side-panel-wraps-SidePanel
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-cancellation-cascade-side-panel! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture)
                                cancellation-cascade/SidePanel)))))

(deftest mount-cancellation-cascade-popover-wraps-Popover
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-cancellation-cascade-popover! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture)
                                cancellation-cascade/Popover)))))

;; ---- inline content surface (managed-fx) -------------------------------

(deftest mount-managed-fx-wraps-ManagedFxList
  ;; rf2-fcy5 — the expected view is `ManagedFxList-bridge`, for the reason
  ;; `mount-reactive-panel-…` records above: `ManagedFxList` is now a Fresco
  ;; boundary and `render-panel!` builds a REAGENT tree. The shape this row
  ;; pins — frame-provider :rf/xray wrapping the view — is unchanged.
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-managed-fx! :mount-point)
      (is (frame-provider-wrap? (captured-tree capture) panels/ManagedFxList-bridge)))))

;; ---- full-shell mount --------------------------------------------------

(deftest mount-shell-renders-shell-view-without-extra-wrapper
  (testing "rf2-crhr8 — mount-shell! delegates the full 4-layer shell.
            The shell-view itself installs its own scope provider
            (`frame-provider`, per the shell docstring) so the
            mount fn renders [shell-view {:mode :inline}] directly — no
            outer wrapper."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-shell! :mount-point)
        (let [tree (captured-tree capture)]
          (is (vector? tree))
          (is (map? (second tree)))
          (is (= :inline (:mode (second tree))))
          ;; The shell mounts via a reg-view-registered view; the
          ;; captured render-tree's first element is that view fn (not
          ;; a provider — the shell installs its own scope provider).
          (is (not= rf/frame-provider (first tree))
              "mount-shell! does NOT add an outer frame-provider — the
               shell-view contains its own scope provider per spec/007
               §The 4-layer chrome"))))))

(deftest mount-shell-supports-mode-opt
  (let [[capture _ render-stub] (make-render-stub)]
    (with-redefs [rf.substrate.adapter/render render-stub]
      (panels/mount-shell! :mount-point {:mode :overlay})
      (is (= :overlay (-> (captured-tree capture) second :mode))))))

;; ---- rf2-lffg — the full-shell embed forwards its own-frame opt --------
;;
;; `008-Embedding-Contract.md` §Embed props inventory publishes exactly two
;; host-visible props on the full-shell embed, and `:frame` is one of them:
;; the frame the shell's frame-provider wraps — Xray's OWN frame, distinct
;; from the inspected host target the frame-picker chooses. `shell-view`
;; has taken that axis as its `:frame-id` opt since rf2-lnluk de-singletoned
;; the shell, and `two-instance-isolation-cljs-test` pins the isolation it
;; buys at the data layer by binding the two frames directly.
;;
;; `mount-shell!` was the one seam that never carried the option across: it
;; read `:mode` out of `opts` and dropped `:frame` on the floor, so every
;; embed — however many, however addressed — resolved to the default
;; `:rf/xray` and shared one app-db. The source-level singleton guard
;; (`frame_singleton_guard_test.clj`) cannot see this: there is no literal
;; `:rf/xray` to find, only a caller option that is never read.
;;
;; These deftests sit at the PUBLIC full-shell boundary — through
;; `mount-shell!`, never `shell-view` directly — which is precisely the
;; surface the existing coverage stepped around.

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
  (testing "rf2-lffg — two full shells mounted through `mount-shell!` into
            separate roots with DISTINCT `:frame` options each receive the
            own frame they asked for. Pre-fix both trees carried no
            `:frame-id` at all, so `shell-view` defaulted both to
            `shell/default-frame-id` and the two embeds collided."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-shell! :mount-a {:frame embed-cell-a})
        (panels/mount-shell! :mount-b {:frame embed-cell-b}))
      (is (= 2 (count @capture))
          "both mounts delegated to the substrate adapter")
      (is (= embed-cell-a (shell-frame-id capture 0))
          "shell A carries the own frame its caller requested")
      (is (= embed-cell-b (shell-frame-id capture 1))
          "shell B carries ITS own frame — not A's, and not the default")
      (is (not= (shell-frame-id capture 0) (shell-frame-id capture 1))
          "two requested own frames stay two")
      (is (= :mount-a (-> @capture (nth 0) :mount-point))
          "mount-points are untouched by the frame plumbing")
      (is (= :mount-b (-> @capture (nth 1) :mount-point)))
      (is (some? (rf.frame/frame embed-cell-a))
          "the requested own frame is SEATED — `shell-view`'s subscribes
           and its captured dispatchers need a frame to land in, and the
           embed contract does not ask the host to pre-seat one")
      (is (some? (rf.frame/frame embed-cell-b))))))

(deftest mount-shell-frame-opt-defaults-and-leaves-mode-intact
  (testing "rf2-lffg — omitting `:frame` still selects the documented
            default own frame (`shell/default-frame-id`), and threading the
            new axis does not disturb `:mode`."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-shell! :mount-default)
        (panels/mount-shell! :mount-overlay {:mode :overlay})
        (panels/mount-shell! :mount-both {:mode  :overlay
                                          :frame embed-cell-a}))
      (is (= shell/default-frame-id (shell-frame-id capture 0))
          "omitted :frame shares the documented default")
      (is (= :inline (-> @capture (nth 0) :tree second :mode))
          "and the default :mode is unchanged")
      (is (= shell/default-frame-id (shell-frame-id capture 1))
          ":mode alone does not disturb the own-frame default")
      (is (= :overlay (-> @capture (nth 1) :tree second :mode)))
      (is (= embed-cell-a (shell-frame-id capture 2))
          "both opts travel together")
      (is (= :overlay (-> @capture (nth 2) :tree second :mode))))))

(deftest mount-shell-instances-hold-independent-own-frame-state
  (testing "rf2-lffg — the point of forwarding the option: driving tab,
            mode and focus in the shell mounted at one root leaves the
            shell mounted at the other root untouched. The `with-frame`
            bindings below stand in for the two `[frame-provider {:frame
            frame-id}]` scopes the mounted `shell-view`s establish — the
            same standing-in `two-instance-isolation-cljs-test` does, but
            reached through `mount-shell!` rather than around it. Pre-fix
            both mounts resolved to one frame and every assertion in the
            second half of this deftest read back A's value."
    (let [[_ _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-shell! :mount-a {:frame embed-cell-a})
        (panels/mount-shell! :mount-b {:frame embed-cell-b}))
      ;; Seed B, then drive A across all three axes.
      (dispatch-in-frame! embed-cell-b [:rf.xray/select-tab :trace])
      (dispatch-in-frame! embed-cell-b [:rf.xray/set-mode :static])
      (dispatch-in-frame! embed-cell-b [:rf.xray/focus-event :b-cascade :rf/default])
      (dispatch-in-frame! embed-cell-a [:rf.xray/select-tab :app-db])
      (dispatch-in-frame! embed-cell-a [:rf.xray/focus-event :a-cascade :rf/default])
      (is (= :app-db (read-in-frame embed-cell-a [:rf.xray/selected-tab]))
          "shell A holds its own selected tab")
      (is (= :trace (read-in-frame embed-cell-b [:rf.xray/selected-tab]))
          "shell B's tab did NOT move when A's did")
      (is (= :dynamic (read-in-frame embed-cell-a [:rf.xray/mode]))
          "shell A is still at the default mode")
      (is (= :static (read-in-frame embed-cell-b [:rf.xray/mode]))
          "shell B's mode is its own")
      (is (= :a-cascade (:dispatch-id (read-in-frame embed-cell-a [:rf.xray/focus])))
          "shell A focused its own epoch")
      (is (= :b-cascade (:dispatch-id (read-in-frame embed-cell-b [:rf.xray/focus])))
          "and shell B is STILL focused on its own"))))

;; ---- contract — frame opt --------------------------------------------

(deftest mount-fn-honours-frame-opt-when-host-overrides-default
  (testing "rf2-crhr8 — `opts {:frame ...}` overrides the default
            `:rf/xray` frame the frame-provider wraps around. Pins
            the embedding contract (008-Embedding-Contract.md §State
            isolation) — a host can choose a different frame for the
            React-context tier (e.g. a Story variant frame) and the
            wrapper honours it. The panel's own Xray-state subs still
            target `:rf.xray/*` registrations under whatever frame
            actually carries them."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point {:frame :my-app/cart})
        (let [tree (captured-tree capture)]
          (is (= rf/frame-provider (first tree)))
          (is (= {:frame :my-app/cart} (second tree))
              "explicit :frame opt overrides the default :rf/xray"))))))

;; ---- rf2-hg3j — the per-panel mount SEATS the frame it PROVIDES --------
;;
;; The deftest above pins the WRAPPER, and the wrapper was always right. What
;; was wrong sat one line above it: `render-panel!` called
;; `ensure-xray-handlers-installed!` at its ZERO arity, which always seats
;; `shell/default-frame-id`, and then anchored the provider at `opts :frame`.
;; On an override those are two different frames, and since no panel view
;; opens an inner provider of its own — every panel's docstring says its
;; isolation comes from the ENCLOSING one — the panel body's whole
;; `:rf.xray/*` surface resolved into a frame nothing had seated or seeded.
;;
;; So this row is deliberately NOT taken off the captured tree: the stubbed
;; `adapter/render` observes the wrapper, which passes either way. It reads
;; the frame REGISTRY, which the real `ensure-xray-handlers-installed!` wrote
;; on the way past the stub. Same assertion shape rf2-lffg used for
;; `mount-shell!`, which has threaded its resolved frame through all along.

(def ^:private panel-cell-frame :review/xray-panel-cell)

(deftest mount-panel-seats-the-own-frame-it-provides
  (testing "rf2-hg3j — a per-panel mount given an explicit `:frame` seats
            THAT frame. Pre-fix `mount-epoch-panel!` seated
            `shell/default-frame-id` and provided the override, so the
            first row below read nil and the panel's subscribes landed in
            an empty frame."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point {:frame panel-cell-frame}))
      (is (some? (rf.frame/frame panel-cell-frame))
          "the requested own frame is SEATED — the mount contract does not
           ask the host to pre-seat one (008 §Frame-provider wraps the shell)")
      (is (= panel-cell-frame (:frame (second (captured-tree capture))))
          "and it is the SAME frame the provider anchors — seated and
           provided are one value by construction")
      (is (some? (read-in-frame panel-cell-frame [:rf.xray/selected-tab]))
          "and the seed hooks ran in it, so a panel body's `:rf.xray/*`
           subscribe resolves there rather than reading an empty frame"))))

;; ---- contract — instance-id opt (rf2-2n8q) ----------------------------
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
  (testing "rf2-2n8q — `mount-app-db-diff!`'s `:instance-id` opt reaches the
            Fresco boundary's props, across the real `Panel-bridge`. This is
            the mount door onto the prop rf2-t3fz gave `Panel`: a caller that
            MOUNTS passes opts and never props, so before this the standalone
            embed could not name an instance at all."
    (is (= {:instance-id "left"}
           (crossed (delivered-by {:instance-id "left"})))
        "the name the mount was given is the name the boundary is mounted with")
    (is (= {:instance-id "left"}
           (crossed (delivered-by {:instance-id :left})))
        "rf2-4bsq — a keyword crosses too, as its TOKEN. `Panel-bridge`
         tokenises the prop with `instance-token` before handing it to
         `[:>]`, because Reagent would otherwise convert the value with
         `cljs.core/name` on the way to React. For `:left` the two agree on
         \"left\", so what the boundary is mounted with is unchanged; what
         moved is WHERE the conversion happens, and that is the whole repair
         — see the namespaced row below")
    (is (= {:instance-id "left/panel"}
           (crossed (delivered-by {:instance-id :left/panel})))
        "rf2-4bsq — AND A NAMESPACE SURVIVES, which is why the tokenising
         moved. `cljs.core/name` drops it, so passed through raw
         `:left/panel` and `:right/panel` both reached the boundary as
         \"panel\" — two mounts this opt had deliberately named apart
         sharing one `:mount-id`, one width slot and one `:site-id`. This is
         the mount door onto that prop, so it is the door that has to carry
         it")
    (is (= {:instance-id "right"}
           (crossed (delivered-by {:frame :my-app/cart :instance-id "right"})))
        "and it composes with `:frame` rather than replacing it"))

  (testing "rf2-2n8q — the `:frame` opt is untouched by the addition."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-app-db-diff! :mount-point
                                   {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "the frame-provider still wraps the frame the host named")))))

(deftest mount-fns-without-an-instance-name-deliver-exactly-what-they-did
  (testing "rf2-2n8q — an UNNAMED app-db mount delivers the bare
            `[Panel-bridge]` element it always delivered, not `[Panel-bridge
            {}]`. The 0-arity is the shape the shell's `[(:panel tab)]` and
            every standalone call site in this tree take today, and it is what
            keeps their composed ids byte-for-byte unchanged."
    (is (= [app-db-diff/Panel-bridge] (delivered-by nil))
        "no opts at all")
    (is (= [app-db-diff/Panel-bridge] (delivered-by {:frame :my-app/cart}))
        "opts carrying no instance name")
    (is (= {} (crossed (delivered-by nil)))
        "and the bridge's 0-arity mounts the boundary with no props"))

  (testing "rf2-2n8q — `:instance-id` is SOME PANELS' opt, not the surface's.
            Only a panel whose view takes the prop may be handed a props map;
            handing one to a view that takes none is an arity error, not an
            ignored key, so every other mount fn must keep delivering a bare
            element even when its caller sets the opt.

            A panel is picked here for being a CURRENT member of the no-prop
            group, not for being a permanent one, so the pick MOVES as panels
            migrate. It has moved twice:

              - rf2-pua3 moved `mount-trace!` to the first group, and the
                trace half is asserted in
                [[mount-trace-instance-id-reaches-the-boundary]] below;
              - rf2-3ymg then moved `mount-epoch-panel!` AND
                `mount-machine-inspector!`, whose halves are asserted in
                [[mount-epoch-panel-instance-id-reaches-the-boundary]] and
                [[mount-machine-inspector-instance-id-reaches-the-boundary]].

            So the pick is now `mount-reactive-panel!`, and it is a sharper
            witness than either predecessor: `reactive-panel/Panel-bridge` is
            declared 0-arity ONLY, so a props map here is the arity error this
            row names rather than a silently ignored key."
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
  (testing "rf2-pua3 — `mount-trace!`'s `:instance-id` opt reaches the Fresco
            boundary's props, across the real `Panel-bridge`. This is the
            mount door onto the prop the panel now reads: a caller that MOUNTS
            passes opts and never props, so before this the standalone embed
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
        "and a KEYWORD keeps its namespace — rf2-4bsq: `[:>]` would convert it
         with `cljs.core/name` and drop the namespace, restoring the very
         collision this removes, so the bridge tokenises BEFORE the crossing")
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-trace! :mount-point
                             {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "and it composes with `:frame` rather than replacing it — the
             frame-provider still wraps the frame the host named")))
    (is (= [trace/Panel-bridge] (trace-delivered-by nil))
        "an UNNAMED trace mount delivers the bare `[Panel-bridge]` element it
         always delivered, not `[Panel-bridge {}]` — the shape the shell's
         `[(:panel tab)]` and every standalone call site in this tree take
         today, and what keeps their composed ids byte-for-byte unchanged")
    (is (= [trace/Panel-bridge] (trace-delivered-by {:frame :my-app/cart}))
        "opts carrying no instance name deliver it too")
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
  (testing "rf2-3ymg — `mount-epoch-panel!`'s `:instance-id` opt reaches the
            Fresco boundary's props, across the real `Panel-bridge`. This is
            the mount door onto the prop the panel now reads: a caller that
            MOUNTS passes opts and never props, so before this the standalone
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
        "and a KEYWORD keeps its namespace — rf2-4bsq: `[:>]` would convert it
         with `cljs.core/name` and drop the namespace, so `:left/epoch` and
         `:right/epoch` would both arrive as \"epoch\" and compose one
         `epoch/epoch/dispatch-event`, restoring the very collision this
         removes. The bridge tokenises BEFORE the crossing for that reason")
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-point
                                   {:frame :my-app/cart :instance-id "right"})
        (is (= {:frame :my-app/cart} (second (captured-tree capture)))
            "and it composes with `:frame` rather than replacing it — the
             frame-provider still wraps the frame the host named")))
    (is (= [epoch-panel/Panel-bridge] (epoch-delivered-by nil))
        "an UNNAMED epoch mount delivers the bare `[Panel-bridge]` element it
         always delivered, not `[Panel-bridge {}]` — the shape the shell's
         `[(:panel tab)]` and every standalone call site in this tree take
         today, and what keeps their composed ids byte-for-byte unchanged")
    (is (= [epoch-panel/Panel-bridge] (epoch-delivered-by {:frame :my-app/cart}))
        "opts carrying no instance name deliver it too")
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
  (testing "rf2-3ymg — `mount-machine-inspector!`'s `:instance-id` opt reaches
            the Fresco boundary's props, across the real `Panel-bridge`.

            THIS PANEL'S CONTRACT DIFFERS FROM EVERY SIBLING'S, which is why
            it gets its own row rather than riding on the epoch one above.
            Element 2 renders through `epoch-view/machine-cascade-mini-
            pipeline`, the SHARED renderer the Epoch panel's handler step uses
            (rf2-g2axio), so this panel composes ids in the EPOCH panel's id
            namespace. `machine-inspector/instance-token` therefore NEVER
            answers nil: it names the panel itself, and a caller's token rides
            BELOW that. The rows below pin both halves of that."
    (is (= {:instance-id "machine-inspector"}
           (crossed (machine-inspector-delivered-by nil)))
        "AN UNNAMED MOUNT IS THE INTERESTING ONE — it crosses carrying this
         panel's OWN name, not `{}`. That is the cross-panel half of rf2-3ymg
         fixed with no caller action, because which panel is rendering is
         statically known and an embedder mounting one Epoch panel and one
         Machine Inspector over the same cascade cannot see the collision to
         work around it. Every sibling panel answers nil here")
    (is (= [machine-inspector/Panel-bridge] (machine-inspector-delivered-by nil))
        "the DELIVERED element is still the bare `[Panel-bridge]` it always
         was, though — what changed is what the bridge mounts the boundary
         with, not what the facade hands the substrate")
    (is (= {:instance-id "machine-inspector/left"}
           (crossed (machine-inspector-delivered-by {:instance-id "left"})))
        "a caller's name rides BELOW the panel's own, so two standalone
         Machine Inspectors are distinct AND one named `left` cannot collide
         with an Epoch panel named `left` either")
    (is (= {:instance-id "machine-inspector/left/machines"}
           (crossed (machine-inspector-delivered-by {:instance-id :left/machines})))
        "and a KEYWORD keeps its namespace — rf2-4bsq, as for every other
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

;; ---- contract — idempotency under repeat mount ------------------------

(deftest repeat-mount-is-idempotent-for-handler-registration
  (testing "rf2-crhr8 — calling mount multiple times is safe; the
            registry's `register-xray-handlers!` sentinel collapses
            repeat installs into a single registration. The substrate
            render is called each time (each mount creates a fresh
            substrate render — that's the host's lifecycle choice;
            the panels ns does not deduplicate)."
    (let [[capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub]
        (panels/mount-epoch-panel! :mount-1)
        (panels/mount-app-db-diff! :mount-2)
        (panels/mount-trace! :mount-3)
        (is (= 3 (count @capture))
            "every mount call delegates to rf.substrate.adapter/render")
        ;; Handlers landed exactly once — :rf.xray/event-bundles is a
        ;; cross-panel primitive registered inside the orchestrator's
        ;; sentinel guard.
        (is (some? (rf.registrar/handler :sub :rf.xray/event-bundles)))))))

;; ---- contract — panel mount routes through mount/ensure-xray-frame! ---
;;
;; Pre-fix `ensure-xray-handlers-installed!` did `(rf/make-frame
;; {:id :rf/xray})` directly. That registered the frame but bypassed the first-mount
;; hook table (rf2-y1saa) — including `::seed-trace-and-target-frame`,
;; the hook that lifts the pre-mount trace-bus buffer into Xray's
;; `:trace-buffer` slot AND seeds `:target-frame` + `:epoch-history` from
;; the head focusable cascade's frame. The result on the Story RHS: a
;; host that dispatched events before any panel was mounted, then mounted
;; a panel, saw empty Event + App-DB panels because the slots the panels
;; subscribe to had never been populated. The fix routes through
;; `mount/ensure-xray-frame!` so every panel-only mount path fires the
;; same hook table the full-shell `open!` runs.

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

(deftest mount-panel-seeds-trace-buffer-from-pre-mount-bus
  (testing "Mounting a panel before the user has opened the full shell
            still runs the first-mount hook table — so the trace-bus
            atom contents land in Xray's `:trace-buffer` slot and the
            panel renders against the host's pre-mount cascades. Pre-fix
            the direct `(rf/make-frame {:id :rf/xray})` bypassed the hook
            table and the slot stayed empty."
    (let [[_capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub
                    rf/epoch-history (fn [_] [])]
        ;; Host dispatched two events on `:cart-frame` while Xray was
        ;; un-mounted — the trace-bus atom accumulated them.
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 1 100 :cart-frame :cart/add-item))
        (trace-collector/seed-trace-for-test!
          (pre-mount-dispatch-event 2 101 :cart-frame :cart/checkout))
        (panels/mount-epoch-panel! :mount-point)
        (rf/with-frame :rf/xray
          (let [buf @(rf/subscribe [:rf.xray/trace-buffer])]
            (is (= 2 (count buf))
                "trace-buffer reflects the pre-mount bus contents — the
                 `::seed-trace-and-target-frame` hook ran on mount.")))))))

(deftest mount-panel-seeds-target-frame-from-head-focusable-cascade
  (testing "Mounting a panel directly (without going through the full
            shell `open!`) seeds `:target-frame` from the head focusable
            cascade's frame — matching the rf2-boyc2 contract the
            full-shell path observes. Pre-fix only `open!` ran the seed
            hook; panel-only mounts left `:target-frame` at
            `defaults/default-target-frame` regardless of pre-mount
            traffic on a non-default frame. That misalignment is the
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

(deftest mount-panel-without-pre-mount-traffic-leaves-target-unselected
  (testing "EP-0002 (rf2-bd4div) — mounting a panel with an empty trace-bus
            + no pre-mount cascades on any frame seeds `:target-frame` from
            `defaults/default-target-frame` = nil = UNSELECTED (the fallback
            branch in `::seed-trace-and-target-frame`). Pins the cold-start
            behaviour: the target stays unselected (the picker prompts a
            choice) rather than chaining to a synthesised `:rf/default`."
    (let [[_capture _ render-stub] (make-render-stub)]
      (with-redefs [rf.substrate.adapter/render render-stub
                    rf/epoch-history (fn [_] [])]
        (panels/mount-trace! :mount-point)
        (rf/with-frame :rf/xray
          (is (= defaults/default-target-frame
                 @(rf/subscribe [:rf.xray/target-frame]))
              "`:target-frame` is the unselected default (nil) when no
               focusable cascade exists.")
          (is (nil? @(rf/subscribe [:rf.xray/target-frame]))
              "explicitly: UNSELECTED is nil, not :rf/default"))))))

;; ---- contract — every public mount fn exists --------------------------

(deftest every-panel-mount-fn-is-public-and-callable
  (testing "rf2-crhr8 — the ten per-panel mount fns + the full-
            shell mount fn are all present + ifn? — defensive guard
            against accidental removal during refactor. (rf2-gbz39 —
            `mount-issues-ribbon!` dropped alongside the removed Issues
            tab; Option (c).)"
    (let [fns [["mount-epoch-panel!"                        panels/mount-epoch-panel!]
               ["mount-app-db-diff!"                        panels/mount-app-db-diff!]
               ["mount-reactive-panel!"                     panels/mount-reactive-panel!]
               ["mount-trace!"                              panels/mount-trace!]
               ["mount-machine-inspector!"                  panels/mount-machine-inspector!]
               ["mount-routing!"                            panels/mount-routing!]
               ["mount-segment-inspector!"                  panels/mount-segment-inspector!]
               ["mount-cancellation-cascade-side-panel!"    panels/mount-cancellation-cascade-side-panel!]
               ["mount-cancellation-cascade-popover!"       panels/mount-cancellation-cascade-popover!]
               ["mount-managed-fx!"                         panels/mount-managed-fx!]
               ["mount-shell!"                              panels/mount-shell!]]]
      (doseq [[sym-name f] fns]
        (is (ifn? f)
            (str sym-name " is callable"))))))
