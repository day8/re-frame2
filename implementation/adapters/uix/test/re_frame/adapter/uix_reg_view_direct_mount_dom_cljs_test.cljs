(ns re-frame.adapter.uix-reg-view-direct-mount-dom-cljs-test
  "The advertised registry-keyed UIx mount, `($ (rf/view ::row) props
  child)`, handed straight to `$` as a component type. The head must carry
  UIx's component marker, or `$` routes its props through `interpret-attrs`
  and strips keyword namespaces; a nested namespaced prop is asserted EXACTLY,
  and a `use-sub` + `use-frame` boundary must survive a dispatch with no
  invalid-element or hook diagnostic. The `boot-order-*` row registers at
  ns-load, before any adapter is installed — the order
  `docs/core/how-to/boot-and-mount-an-app.md` prescribes — and asserts that
  premise before mounting."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

;; The `use-fixtures` call is NOT here. It sits below the ns-load registration
;; further down the file, and the position is load-bearing —
;; `make-reset-runtime-fixture` snapshots the registrar AT CALL TIME as its
;; ns-load baseline, and the boot-order row's whole premise is a
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

;; The two diagnostics this file guards. `invalid-element-type-re` is what
;; React raises when the head it is handed is not a valid element type.
;; `hook-boundary-re` covers the other failure: a head that mounts but owns no
;; genuine React component boundary makes every hook below it an invalid call.
(def ^:private invalid-element-type-re #"(?i)element type is invalid|not a valid (react )?(element|component)")
(def ^:private hook-boundary-re        #"(?i)invalid hook call|hooks can only be called|rendered more hooks|order of Hooks")

;; ---- the probe body --------------------------------------------------------
;;
;; One native `defui`, mounted through the registry head by both rows. It
;; reads its props off UIx's `argv` channel, so a mount that reached it
;; through JS-prop conversion would arrive with `:tenant/id`'s namespace gone.

(def ^:private probe-frame :rf.uix-direct-mount/frame)
(def ^:private probe-query [:rf.uix-direct-mount/n])

;; The nested CLJS prop under test: a map value carrying a namespaced keyword,
;; which is precisely what `interpret-attrs` mangles.
(def ^:private probe-payload {:tenant/id      :tenant/admin
                              :tenant/limits  {:seats 12 :tier :tier/enterprise}})

(def ^:private observed-payload  (atom ::unset))
(def ^:private observed-ops      (atom nil))

(defui probe-body
  "Calls both hooks, so the mount must own a real React hook boundary, and
  stashes the `use-frame` ops so the driver dispatches through the SAME map —
  one locked to the wrong frame would move a `:n` nothing on screen reads."
  [{:keys [payload children]}]
  (let [n   (rf.adapter.uix/use-sub probe-query)
        ops (rf.adapter.uix/use-frame)]
    (reset! observed-payload payload)
    (reset! observed-ops ops)
    ($ :div {:data-testid "probe"}
       ($ :span {:data-testid "n"} (str n))
       children)))

;; ---- the canonical boot order, captured at ns-load -------------------------
;;
;; Registered at NS-LOAD, so no fixture can have installed an adapter first.
;; `:adapter/componentize-view` is routed, so with no adapter it declines and
;; the slot keeps the bare wrapper, which `init!` never revisits; what must
;; mount is the head `(rf/view id)` hands back after init. The two captures
;; below make that premise checkable rather than assumed.

(def ^:private boot-row-id :rf.uix-direct-mount/boot-row)

(rf/reg-view* boot-row-id probe-body)

(def ^:private adapter-at-registration (rf/current-adapter))
(def ^:private head-at-registration    (rf/view boot-row-id))

;; NOW the fixture — see the note under the ns form for why the order matters.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

;; ---- shared registration + world setup -------------------------------------

(defn- seed-world!
  "Create the frame, register the event + sub, and seed app-db. Returns nil."
  []
  (rf/make-frame {:id probe-frame :doc "direct-mount probe frame"})
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
  (reset! observed-ops nil)
  (let [mount-node (.createElement js/document "div")
        react-root (react-dom-client/createRoot mount-node)]
    ;; Clear the fixture's ambient `:rf/default` dynamic scope so the
    ;; 1-arg `use-sub` resolves through the React-context (provider)
    ;; tier — the shape a real app has.
    (binding [rf.frame/*current-frame* nil]
      (let [mount-diagnostics (capture-render-diagnostics
                                (fn []
                                  (act-fn
                                    (fn []
                                      (.render react-root
                                        ($ rf.adapter.uix/frame-provider {:frame probe-frame}
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
           :frame-ops         frame-ops}
          (finally
            (try (.unmount react-root) (catch :default _ nil))))))))

(defn- assert-mount-case
  "The shared assertion block; `label` names which row's mount produced `facts`."
  [label facts]
  (let [{:keys [diagnostics initial-text updated-text child-text
                observed-payload frame-ops]} facts]
    (is (= {:invalid-element-type [] :hook-boundary [] :payload probe-payload
            :child "kid" :frame probe-frame :initial "1" :updated "2"}
           {:invalid-element-type (matching-messages invalid-element-type-re diagnostics)
            :hook-boundary        (matching-messages hook-boundary-re diagnostics)
            :payload              observed-payload
            :child                child-text
            :frame                (:frame frame-ops)
            :initial              initial-text
            :updated              updated-text})
        (str label ": no invalid-element or hook diagnostic, the nested prop intact,"
             " the trailing child rendered, use-frame on the provider's frame, and"
             " a re-render after a dispatch off its ops"))))

;; ---- the registry path ----------------------------------------------------

(deftest direct-mount-of-registered-view-head
  (testing "UIx — ($ (rf/view id) props child) mounts DIRECTLY: lossless CLJS
            props, a real hook boundary, and a re-render on dispatch"
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        (rf/reg-view* :rf.uix-direct-mount/row probe-body)
        (let [head (rf/view :rf.uix-direct-mount/row)]
          ;; `instance? js/Function` rather than `fn?`, which is also true of
          ;; an IFn OBJECT React rejects as an element type.
          (is (= [true true] [(instance? js/Function head) (true? (.-uix-component? ^js head))])
              "the registered head is a real JS function carrying UIx's component marker")
          (assert-mount-case "registry head" (run-mount-case act-fn head)))))))

;; ---- the canonical boot order: register at ns-load, THEN init! ------------

(deftest boot-order-registration-yields-a-mountable-head-after-init
  (testing "UIx — a view registered at ns-load, BEFORE rf/init! installed the
            adapter, is still directly mountable through ($ (rf/view id) …)
            once the adapter is in"
    ;; Without this premise the row is `direct-mount-of-registered-view-head`
    ;; again under another name.
    (is (= [nil false] [adapter-at-registration (true? (.-uix-component? ^js head-at-registration))])
        "premise: no adapter at registration, and the reg-time head was the unmarked wrapper")
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        ;; No registration here. The fixture has installed UIx; the only thing
        ;; that has happened since ns-load is `rf/init!`.
        (let [head (rf/view boot-row-id)]
          (is (= [true true] [(instance? js/Function head) (true? (.-uix-component? ^js head))])
              "the post-init head is a real JS function carrying UIx's component marker")
          (assert-mount-case "boot-order head" (run-mount-case act-fn head)))))))
