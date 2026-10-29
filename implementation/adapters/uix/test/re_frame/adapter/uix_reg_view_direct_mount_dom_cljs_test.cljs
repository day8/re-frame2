(ns re-frame.adapter.uix-reg-view-direct-mount-dom-cljs-test
  "Registry-keyed mounts preserve CLJS props, children, and frame-bound hooks before or after init."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [with-browser-act]]))

(defn- capture-render-diagnostics
  "Capture console and window errors, restoring both channels even if rendering throws."
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
  "Diagnostic messages matching pattern."
  [pattern messages]
  (filterv #(and (string? %) (re-find pattern %)) messages))

(def ^:private invalid-element-type-re #"(?i)element type is invalid|not a valid (react )?(element|component)")
(def ^:private hook-boundary-re        #"(?i)invalid hook call|hooks can only be called|rendered more hooks|order of Hooks")

(def ^:private probe-frame :rf.uix-direct-mount/frame)
(def ^:private probe-query [:rf.uix-direct-mount/n])

(def ^:private probe-payload {:tenant/id      :tenant/admin
                              :tenant/limits  {:seats 12 :tier :tier/enterprise}})

(def ^:private observed-payload  (atom ::unset))
(def ^:private observed-ops      (atom nil))

(defui probe-body
  "Record the lossless props and held frame operations while exercising both hooks."
  [{:keys [payload children]}]
  (let [n   (rf.adapter.uix/use-sub probe-query)
        ops (rf.adapter.uix/use-frame)]
    (reset! observed-payload payload)
    (reset! observed-ops ops)
    ($ :div {:data-testid "probe"}
       ($ :span {:data-testid "n"} (str n))
       children)))

(def ^:private boot-row-id :rf.uix-direct-mount/boot-row)

(rf/reg-view* boot-row-id probe-body)

(def ^:private adapter-at-registration (rf/current-adapter))
(def ^:private ^js head-at-registration (rf/view boot-row-id))

;; Build the fixture after the ns-load registration so its registrar snapshot includes that view.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(defn- seed-world!
  "Create and seed the probe frame and register its event and subscription."
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
  "Mount the registry head through $, dispatch through its held operations, and return the observations."
  [act-fn head]
  (reset! observed-payload ::unset)
  (reset! observed-ops nil)
  (let [mount-node (.createElement js/document "div")
        react-root (react-dom-client/createRoot mount-node)]
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
  "Assert props, children, frame routing, updates, and diagnostics for the real mount."
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

(deftest direct-mount-of-registered-view-head
  (testing "UIx — ($ (rf/view id) props child) mounts DIRECTLY: lossless CLJS
            props, a real hook boundary, and a re-render on dispatch"
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        (rf/reg-view* :rf.uix-direct-mount/row probe-body)
        (let [head (rf/view :rf.uix-direct-mount/row)]
          (assert-mount-case "registry head" (run-mount-case act-fn head)))))))

(deftest boot-order-registration-yields-a-mountable-head-after-init
  (testing "UIx — a view registered at ns-load, BEFORE rf/init! installed the
            adapter, is still directly mountable through ($ (rf/view id) …)
            once the adapter is in"
    (is (= [nil false] [adapter-at-registration (true? (.-uix-component? head-at-registration))])
        "premise: no adapter at registration, and the reg-time head was the unmarked wrapper")
    (with-browser-act
      (fn [act-fn]
        (seed-world!)
        (let [head (rf/view boot-row-id)]
          (assert-mount-case "boot-order head" (run-mount-case act-fn head)))))))
