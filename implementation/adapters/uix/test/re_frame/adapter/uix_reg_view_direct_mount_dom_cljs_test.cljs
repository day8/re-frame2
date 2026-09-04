(ns re-frame.adapter.uix-reg-view-direct-mount-dom-cljs-test
  "rf2-oz7wr — the ADVERTISED registry-keyed UIx mount, exercised as a
  consumer writes it.

  `docs/api/re-frame.adapter.uix.md` tells UIx users to reach for
  `rf/reg-view*` for registry-keyed view addressing, and Spec 001
  §`(re-frame.core/view id)` makes `(rf/view id)` the runtime handle for
  what was registered. Composing those two gives
  `($ (rf/view ::row) {…})` — and that form did not work. `reg-view*`
  registered `(with-meta (fn frame-aware-view …) {:contextType …})`, and
  `cljs.core/with-meta` on a fn yields a `MetaFn`: an IFn OBJECT, which
  React rejects as an element type before the registered view renders.

  The pre-existing React-hook coverage could not see it. Every registry-head
  mount in `react-shared-suite` went through a hand-written host component
  that INVOKED the registered value — so those rows proved teardown and
  annotation BELOW the workaround, never the advertised head itself. This
  file mounts the head directly and contains no such host by construction:
  the registered value is only ever handed to `$` as a component type.

  What each row is for:

    - `direct-mount-*` — AC1/AC2/AC3. `($ (rf/view id) props child)` under
      the normal `frame-provider` boundary, with a nested namespaced-keyword
      prop asserted for EXACT equality inside the registered component (the
      losslessness half: a marked UIx head carries the original CLJS props
      on `argv`, an unmarked one would be converted through
      `interpret-attrs` and lose the namespace), a `use-subscribe` +
      `use-frame` hook boundary that must survive a dispatch and re-render,
      and a console/page-error capture that must stay free of both the
      invalid-element-type and the hook-boundary diagnostics.

    - `boot-order-*` — the audit's row. The same direct mount, but the view
      is registered at NS-LOAD, before any adapter is installed, which is the
      order `docs/core/how-to/boot-and-mount-an-app.md` prescribes and the
      order the first fix could not survive: `:adapter/componentize-view` is
      routed, so at registration it declined, and `rf/init!` seats the adapter
      without revisiting existing `:view` slots. The row asserts its own
      premise (no adapter at registration; the reg-time head really was the
      MetaFn) before mounting, so it cannot silently decay into a copy of the
      row above.

    - `native-defui-control-*` — AC5's NON-VACUITY control. The SAME probe
      body, mounted as an unregistered native `defui`, so the harness is
      shown to pass independently of the registry path. When the registry
      row is deliberately broken (restoring the metadata-wrapped head, or
      removing the componentization seam) this row must stay green — that
      is what makes the registry row's red attributable to the seam under
      test rather than to the harness.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  (ns-regexp `-dom-cljs-test$`) discovers it. `:node-test`'s `cljs-test$`
  regex matches too, where every row self-gates on `(browser?)` and no-ops
  cleanly — a real `createRoot` commit is required for any of this to mean
  anything."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

;; The `use-fixtures` call is NOT here. It sits below the ns-load registration
;; further down the file, and the position is load-bearing —
;; `make-reset-runtime-fixture` snapshots the registrar AT CALL TIME as its
;; ns-load baseline (rf2-7hwnu), and the boot-order row's whole premise is a
;; registration that already exists when the fixture is built.

;; ---- DOM gate ladder -------------------------------------------------------
;; Mirrors the shared suite's ladder (its helpers are private), so this file
;; stays self-contained and can be read without cross-referencing core/test.

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- with-browser-act
  "Skip under :node-test (no DOM) and when act() is unreachable; otherwise
  opt into React's act environment and call `(f act-fn)`."
  [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (f act-fn))))))

(defn- capture-render-diagnostics
  "Record `console.error` / `console.warn` messages AND any window
  `error` event raised across `thunk`. Returns the vector of joined
  message strings; restores everything on the way out even if thunk
  throws.

  Both channels matter here. React reports an invalid element type by
  THROWING (which surfaces as a page error), and reports a hook-order or
  invalid-hook-call violation via `console.error` while rendering — so a
  capture of only one of the two would let half the failure mode through
  silently."
  [thunk]
  (let [messages       (atom [])
        original-warn  (.-warn js/console)
        original-error (.-error js/console)
        on-error   (fn [^js e]
                     (swap! messages conj (str (or (.-message e) e))))]
    (.addEventListener js/window "error" on-error)
    (try
      (set! (.-warn js/console)  (fn [& args] (swap! messages conj (apply str args))))
      (set! (.-error js/console) (fn [& args] (swap! messages conj (apply str args))))
      (try
        (thunk)
        (catch :default e
          (swap! messages conj (str "THROWN: " (.-message e)))))
      @messages
      (finally
        (set! (.-warn js/console)  original-warn)
        (set! (.-error js/console) original-error)
        (.removeEventListener js/window "error" on-error)))))

(defn- matching-messages
  "Messages matching `pattern`. Fixed-shape helper so each assertion reports the
  offending text rather than a bare false."
  [pattern messages]
  (filterv #(and (string? %) (re-find pattern %)) messages))

;; The two diagnostics this bead is about. `invalid-element-type-re` is what
;; React raises when handed the `MetaFn` — the pre-fix symptom. `hook-boundary-re`
;; covers the failure a repair could introduce instead: a head that mounts but
;; owns no genuine React component boundary makes every hook below it an invalid
;; call.
(def ^:private invalid-element-type-re #"(?i)element type is invalid|not a valid (react )?(element|component)")
(def ^:private hook-boundary-re        #"(?i)invalid hook call|hooks can only be called|rendered more hooks|order of Hooks")

;; ---- the probe body --------------------------------------------------------
;;
;; ONE body, mounted two ways: through the registry head, and (the AC5
;; control) as an unregistered native `defui`. Sharing the body is what makes
;; the control a control — a difference in outcome can only come from the
;; mount path, because nothing else differs.
;;
;; It is a native `defui`, which is the documented UIx idiom and the shape the
;; bead's repro registers. `defui` reads its props off UIx's `argv` channel, so
;; a mount that reached it through JS-prop conversion would arrive with the
;; namespace stripped from `:tenant/id` and the equality assertion below would
;; fail — the losslessness half of the invariant, checked by construction
;; rather than by inspecting the props object.

(def ^:private probe-frame :rf.uix-direct-mount/frame)
(def ^:private probe-query [:rf.uix-direct-mount/n])

;; The nested CLJS prop under test: a map value carrying a namespaced keyword,
;; which is precisely what `interpret-attrs` mangles.
(def ^:private probe-payload {:tenant/id      :tenant/admin
                              :tenant/limits  {:seats 12 :tier :tier/enterprise}})

(def ^:private observed-payload  (atom ::unset))
(def ^:private observed-children (atom ::unset))
(def ^:private observed-ops      (atom nil))

(defui probe-body
  "Receives a nested CLJS prop and a trailing child, and calls BOTH hooks —
  `use-subscribe` for the read and `use-frame` for the frame-locked ops —
  so the mount is proved to own a real React hook boundary rather than
  merely to render.

  The ops map is stashed on a side-channel atom so the driver can dispatch
  through the SAME map the hook handed the component. That is what makes
  the re-render assertion a statement about this mount's frame: an ops map
  captured under a broken boundary would be locked to the wrong frame, and
  the dispatch would move a `:n` nothing on screen is reading."
  [{:keys [payload children]}]
  (let [n   (uix-adapter/use-subscribe probe-query)
        ops (uix-adapter/use-frame)]
    (reset! observed-payload payload)
    (reset! observed-children (some? children))
    (reset! observed-ops ops)
    ($ :div {:data-testid "probe"}
       ($ :span {:data-testid "n"} (str n))
       children)))

;; ---- the CANONICAL BOOT ORDER, captured at ns-load (rf2-oz7wr audit) -------
;;
;; `docs/core/how-to/boot-and-mount-an-app.md` has the registration namespaces
;; load FIRST — every `reg-event` / `reg-sub` / view registration runs as a
;; top-level form — and `run` calls `rf/init!` afterwards. The three forms
;; below are exactly that order, and they run at NS-LOAD so no fixture can
;; have installed an adapter first.
;;
;; This is the order the original fix could not survive. `reg-view*` asked
;; `:adapter/componentize-view` at registration; the hook is ROUTED, so with no
;; adapter installed it declined, the slot kept the `MetaFn`, and `init!` — which
;; only seats the adapter — never revisited it. Every row that installed the
;; adapter BEFORE registering (the two below, and the whole shared suite) sailed
;; past that, which is why the audit reopened the bead.
;;
;; `adapter-at-registration` and `head-at-registration` make the premise
;; CHECKABLE rather than assumed: the row asserts there really was no adapter
;; at registration time, and that the reg-time answer really was the
;; un-mountable `MetaFn`. Without those two, a bundle that happened to install
;; an adapter earlier would turn this row into a second copy of
;; `direct-mount-of-registered-view-head` while still reading as a boot-order
;; witness.

(def ^:private boot-row-id :rf.uix-direct-mount/boot-row)

(rf/reg-view* boot-row-id probe-body)

(def ^:private adapter-at-registration (rf/current-adapter))
(def ^:private head-at-registration    (rf/view boot-row-id))

;; NOW the fixture — see the note under the ns form for why the order matters.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- shared registration + world setup -------------------------------------

(defn- seed-world!
  "Create the frame, register the event + sub, and seed app-db. Returns nil."
  []
  (rf/make-frame {:id probe-frame :doc "rf2-oz7wr direct-mount probe frame"})
  (rf/reg-event :rf.uix-direct-mount/seed (fn [_ _] {:db {:n 1}}))
  (rf/reg-event :rf.uix-direct-mount/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-sub (first probe-query) (fn [db _] (:n db)))
  (rf/dispatch-sync [:rf.uix-direct-mount/seed] {:frame probe-frame})
  nil)

(defn- text-of [^js node testid]
  (some-> node (.querySelector (str "[data-testid='" testid "']")) .-textContent))

(defn- run-mount-case
  "Mount `head` (a UIx component head) under the normal `frame-provider`
  boundary with the probe payload and one trailing child, drive a dispatch,
  and hand the collected facts back as a map.

  `head` is passed STRAIGHT to `$` as the component type. Nothing here
  invokes it — that is the whole point of the file, and it is why the two
  cases can share this driver."
  [act-fn head]
  (reset! observed-payload ::unset)
  (reset! observed-children ::unset)
  (reset! observed-ops nil)
  (let [mount-node (.createElement js/document "div")
        react-root (react-dom-client/createRoot mount-node)]
    ;; Clear the fixture's ambient `:rf/default` dynamic scope so the
    ;; 1-arg `use-subscribe` resolves through the React-context (provider)
    ;; tier — the shape a real app has.
    (binding [frame/*current-frame* nil]
      (let [mount-diagnostics (capture-render-diagnostics
                                (fn []
                                  (act-fn
                                    (fn []
                                      (.render react-root
                                        ($ uix-adapter/frame-provider {:frame probe-frame}
                                           ($ head
                                              {:payload probe-payload}
                                              ($ :em {:data-testid "child"} "kid"))))))))
            initial-text      (text-of mount-node "n")
            frame-ops         @observed-ops
            ;; Dispatch through the ops map `use-frame` handed the mounted
            ;; component. Wrapped in act so React commits the update the
            ;; spine's useSyncExternalStore path schedules — the same
            ;; convention every other UIx DOM row here uses.
            dispatch-diagnostics (capture-render-diagnostics
                                   (fn []
                                     (act-fn
                                       (fn []
                                         (when-let [dispatch-sync (:dispatch-sync frame-ops)]
                                           (dispatch-sync [:rf.uix-direct-mount/inc]))))))
            updated-text          (text-of mount-node "n")]
        (try
          {:diagnostics       (into mount-diagnostics dispatch-diagnostics)
           :initial-text      initial-text
           :updated-text      updated-text
           :child-text        (text-of mount-node "child")
           :observed-payload  @observed-payload
           :children-present? @observed-children
           :frame-ops         frame-ops}
          (finally
            (try (.unmount react-root) (catch :default _ nil))))))))

(defn- assert-mount-case
  "The shared assertion block. `label` names which mount path produced
  `facts` so a failure message says which of the two rows broke."
  [label facts]
  (let [{:keys [diagnostics initial-text updated-text child-text
                observed-payload children-present? frame-ops]} facts]
    (is (empty? (matching-messages invalid-element-type-re diagnostics))
        (str label ": React accepted the value as a component type; got "
             (pr-str (matching-messages invalid-element-type-re diagnostics))))
    (is (empty? (matching-messages hook-boundary-re diagnostics))
        (str label ": the mount owns a real React hook boundary; got "
             (pr-str (matching-messages hook-boundary-re diagnostics))))
    (is (= probe-payload observed-payload)
        (str label ": the nested CLJS prop arrived intact — namespaced keyword"
             " keys AND values, by value equality; got " (pr-str observed-payload)))
    (is (true? children-present?)
        (str label ": the trailing `$` child reached the component; got "
             (pr-str children-present?)))
    (is (= "kid" child-text)
        (str label ": the trailing child rendered into the DOM; got " (pr-str child-text)))
    (is (= probe-frame (:frame frame-ops))
        (str label ": use-frame resolved the SURROUNDING provider's frame, so"
             " the hook read the context this mount established; got "
             (pr-str (:frame frame-ops))))
    (is (= "1" initial-text)
        (str label ": use-subscribe's initial value rendered; got " (pr-str initial-text)))
    (is (= "2" updated-text)
        (str label ": the DOM re-rendered after a dispatch off use-frame's ops"
             " map; got " (pr-str updated-text)))))

;; ---- AC1 / AC2 / AC3 — the registry path ----------------------------------

(deftest direct-mount-of-registered-view-head
  (testing "UIx — ($ (rf/view id) props child) mounts DIRECTLY: lossless CLJS
            props, a real hook boundary, and a re-render on dispatch (rf2-oz7wr)"
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        (rf/reg-view* :rf.uix-direct-mount/row probe-body)
        (let [head (rf/view :rf.uix-direct-mount/row)]
          ;; `fn?` is NOT the discriminator here and would pass either way:
          ;; `cljs.core/MetaFn` implements the `Fn` marker protocol, so
          ;; `(fn? metafn)` is true while React still rejects the object.
          ;; `instance? js/Function` is the property React actually needs.
          (is (instance? js/Function head)
              "the registered head is a real JS function React can use as an
               element type — NOT the MetaFn `with-meta` yields (rf2-oz7wr)")
          (is (true? (.-uix-component? ^js head))
              "and it carries UIx's own component marker, which is what makes
               `$` route props through the lossless `argv` channel instead of
               converting them and dropping keyword namespaces")
          (assert-mount-case "registry head" (run-mount-case act-fn head)))))))

;; ---- the canonical boot order: register at ns-load, THEN init! ------------

(deftest boot-order-registration-yields-a-mountable-head-after-init
  (testing "UIx — a view registered at ns-load, BEFORE rf/init! installed the
            adapter, is still directly mountable through ($ (rf/view id) …)
            once the adapter is in (rf2-oz7wr audit)"
    ;; Premise first. If either of these two fails the row below proves
    ;; nothing — it would just be `direct-mount-of-registered-view-head` again
    ;; under a different name.
    (is (nil? adapter-at-registration)
        (str "premise: no adapter was installed when this ns registered its"
             " view at load time — the canonical boot order; got "
             (pr-str adapter-at-registration)))
    (is (not (instance? js/Function head-at-registration))
        "premise: the reg-time head really was the un-mountable MetaFn, so the
         head the row mounts below can only have come from a re-derivation
         against the adapter `rf/init!` seated afterwards")
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        ;; No registration here. The fixture has installed UIx; the only thing
        ;; that has happened since ns-load is `rf/init!`.
        (let [head (rf/view boot-row-id)]
          (is (instance? js/Function head)
              "the lookup hands back a real JS function React can use as an
               element type, even though registration ran before the adapter
               existed (rf2-oz7wr audit)")
          (is (true? (.-uix-component? ^js head))
              "and it carries UIx's component marker, so `$` still routes props
               through the lossless `argv` channel")
          (is (identical? head (rf/view boot-row-id))
              "the re-derived head is memoized — a second lookup returns the
               SAME object, so React reconciles it as one component type
               instead of remounting the subtree on every render")
          (assert-mount-case "boot-order head" (run-mount-case act-fn head)))))))

;; ---- AC5 — the non-vacuity control ----------------------------------------

(deftest native-defui-control-mounts-independently-of-the-registry
  (testing "UIx — the SAME probe mounted as an unregistered native defui passes
            the identical harness, so the registry row's verdict is about the
            registry path and not about this file (rf2-oz7wr AC5)"
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        (assert-mount-case "native defui control" (run-mount-case act-fn probe-body))))))

;; ---- the registered head keeps its other contracts ------------------------

(deftest registered-head-is-stable-and-still-callable
  (testing "UIx — componentizing the head does not cost the registry its other
            guarantees: instance identity is stable across lookups, and the
            head remains the callable render fn Spec 001 describes (rf2-oz7wr)"
    (seed-world!)
    (rf/reg-view* :rf.uix-direct-mount/stable
                  (fn [props] (React/createElement "div" #js {} (str (:label props)))))
    (let [first-head  (rf/view :rf.uix-direct-mount/stable)
          second-head (rf/view :rf.uix-direct-mount/stable)]
      (is (identical? first-head second-head)
          "two lookups return the SAME object — React reconciles it as one
           component type rather than remounting on every render")
      (let [rendered-element (first-head {:label "hi"})]
        (is (some? rendered-element) "the head is still directly callable (headless invocation)")
        (is (= "div" (.-type ^js rendered-element))
            "and returns the registered view's own React element")))))
