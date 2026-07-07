(ns day8.re-frame2-xray.views.edn-inspector-popup-cljs-test
  "Unit tests for the edn-inspector popup overlay (rf2-s0x6x — phase
  6 of rf2-oqa60).

  ## What's under test

  1. **Pure stack math** — `push-entry`, `pop-entry`, `top-entry`,
     `z-index-for` operate on plain data; idempotent push, in-order
     pop, top-of-stack peek.
  2. **State slot contract** — `:open` writes the stack + entries;
     `:close mount-id` removes that id; `:close-top` removes the
     topmost only; `:close-all` clears both slots. Subscribes
     project `open?`, `top`, `entry` correctly.
  3. **Chrome rendering** — `popup-chrome` emits the canonical
     hiccup shape: backdrop / dialog / header (with close ✕) /
     body containing the wrapped edn-inspector widget.
  4. **Per-mount isolation** — two popup mounts get distinct
     UUIDs; the embedded widget's `:panel-id` is derived from the
     popup's mount-id so two popups inspecting the same value
     have independent expansion state.
  5. **Close affordances** — Esc key handler dispatches
     `:close-top`; backdrop click + ✕ button both invoke
     `close-fn`; caller-supplied `:on-close` overrides the
     default rf-dispatch.

  Pure-data unit tests; no DOM mount. Default for new
  Causa/Story tests per `feedback-causa-story-cljs-unit-tests-not-
  playwright`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.views.edn-inspector-popup :as edn-inspector-popup]))

;; Fresh re-frame runtime per test so dispatch-sync against the
;; registered popup handlers lands on a clean slot every time.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (vector? node)
                (do (swap! out conj node)
                    (doseq [child (rest node)] (walk child)))
                (seq? node) (doseq [c node] (walk c))))]
      (walk tree))
    @out))

(defn- find-attr
  "Return the first node whose attribute-map key `k` equals `v`."
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))
       first))

;; =========================================================================
;; pure stack math
;; =========================================================================

(deftest push-entry-appends-new-id
  (is (= ["a"] (edn-inspector-popup/push-entry [] "a")))
  (is (= ["a" "b"] (edn-inspector-popup/push-entry ["a"] "b")))
  (is (= ["a" "b" "c"] (edn-inspector-popup/push-entry ["a" "b"] "c"))))

(deftest push-entry-raises-existing-id
  (testing "re-pushing an existing id moves it to the TOP of the stack"
    (is (= ["b" "c" "a"] (edn-inspector-popup/push-entry ["a" "b" "c"] "a")))
    (is (= ["a" "c" "b"] (edn-inspector-popup/push-entry ["a" "b" "c"] "b")))))

(deftest push-entry-handles-nil-stack
  (is (= ["a"] (edn-inspector-popup/push-entry nil "a"))))

(deftest pop-entry-removes-id
  (is (= ["a" "c"] (edn-inspector-popup/pop-entry ["a" "b" "c"] "b")))
  (is (= [] (edn-inspector-popup/pop-entry ["a"] "a"))))

(deftest pop-entry-noop-when-missing
  (is (= ["a" "b"] (edn-inspector-popup/pop-entry ["a" "b"] "missing"))))

(deftest pop-entry-handles-nil-stack
  (is (= [] (edn-inspector-popup/pop-entry nil "a"))))

(deftest top-entry-returns-last
  (is (nil? (edn-inspector-popup/top-entry [])))
  (is (nil? (edn-inspector-popup/top-entry nil)))
  (is (= "a" (edn-inspector-popup/top-entry ["a"])))
  (is (= "c" (edn-inspector-popup/top-entry ["a" "b" "c"]))))

(deftest z-index-for-stacks-above-base
  (testing "z-index increases with stack position so deeper popups
            paint above earlier ones"
    (is (= 2147483640 (edn-inspector-popup/z-index-for 0)))
    (is (= 2147483641 (edn-inspector-popup/z-index-for 1)))
    (is (= 2147483645 (edn-inspector-popup/z-index-for 5)))
    ;; Tolerates nil — defensive for the indexOf-returns-(-1) case.
    (is (= 2147483640 (edn-inspector-popup/z-index-for nil)))))

;; =========================================================================
;; state slot constants — pinned so a downstream renamer breaks a test
;; =========================================================================

(deftest state-slot-keywords-stable
  (is (= :rf.xray.edn-inspector-popup/stack   edn-inspector-popup/stack-slot))
  (is (= :rf.xray.edn-inspector-popup/entries edn-inspector-popup/entries-slot))
  (is (= :rf.xray.edn-inspector-popup/anon    edn-inspector-popup/default-panel-id)))

;; =========================================================================
;; install! + reducers + subs
;; =========================================================================

(deftest install-is-idempotent
  ;; Two installs in a row must not double-register or throw.
  (is (nil? (edn-inspector-popup/install!)))
  (is (nil? (edn-inspector-popup/install!))))

(deftest open-event-pushes-stack-and-stores-payload
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value {:a 1} :opts {:title "First"}}])
  (let [stack   @(rf/subscribe [edn-inspector-popup/stack-slot])
        entries @(rf/subscribe [edn-inspector-popup/entries-slot])]
    (is (= ["m1"] stack))
    (is (= {:value {:a 1} :opts {:title "First"}} (get entries "m1")))))

(deftest open-event-stacks-multiple
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m3" {:value 3 :opts {}}])
  (let [stack @(rf/subscribe [edn-inspector-popup/stack-slot])]
    (is (= ["m1" "m2" "m3"] stack)
        "multiple opens stack in dispatch order")))

(deftest close-event-removes-specific-id
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close "m1"])
  (let [stack   @(rf/subscribe [edn-inspector-popup/stack-slot])
        entries @(rf/subscribe [edn-inspector-popup/entries-slot])]
    (is (= ["m2"] stack) "m1 removed from stack")
    (is (nil? (get entries "m1")) "m1 entry dropped")
    (is (some? (get entries "m2")) "m2 entry preserved")))

(deftest close-top-removes-topmost-only
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close-top])
  (let [stack @(rf/subscribe [edn-inspector-popup/stack-slot])]
    (is (= ["m1"] stack)
        "close-top removed the topmost (m2); m1 still standing")))

(deftest close-top-noop-on-empty-stack
  (edn-inspector-popup/install!)
  ;; No popups open — close-top must not throw.
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close-top])
  (is (empty? (or @(rf/subscribe [edn-inspector-popup/stack-slot]) []))))

(deftest close-all-clears-state
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close-all])
  (let [stack   @(rf/subscribe [edn-inspector-popup/stack-slot])
        entries @(rf/subscribe [edn-inspector-popup/entries-slot])]
    (is (= [] stack))
    (is (= {} entries))))

(deftest open-sub-projects-correctly
  (edn-inspector-popup/install!)
  (is (false? @(rf/subscribe [:rf.xray.edn-inspector-popup/open?]))
      "no popups → :open? false")
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (is (true? @(rf/subscribe [:rf.xray.edn-inspector-popup/open?]))
      "one popup → :open? true"))

(deftest top-sub-tracks-topmost-mount-id
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (is (= "m1" @(rf/subscribe [:rf.xray.edn-inspector-popup/top])))
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (is (= "m2" @(rf/subscribe [:rf.xray.edn-inspector-popup/top]))
      "second open promotes m2 to topmost"))

(deftest entry-sub-returns-specific-payload
  (edn-inspector-popup/install!)
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value {:cart 1} :opts {:title "A"}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value {:user 2} :opts {:title "B"}}])
  (is (= {:value {:cart 1} :opts {:title "A"}}
         @(rf/subscribe [:rf.xray.edn-inspector-popup/entry "m1"])))
  (is (= {:value {:user 2} :opts {:title "B"}}
         @(rf/subscribe [:rf.xray.edn-inspector-popup/entry "m2"]))))

(deftest reopen-with-same-id-preserves-position-and-replaces-payload
  (testing "re-opening m1 raises it to top AND replaces its payload"
    (edn-inspector-popup/install!)
    (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                       "m1" {:value 1 :opts {}}])
    (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                       "m2" {:value 2 :opts {}}])
    (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                       "m1" {:value 99 :opts {:title "raised"}}])
    (let [stack   @(rf/subscribe [edn-inspector-popup/stack-slot])
          entries @(rf/subscribe [edn-inspector-popup/entries-slot])]
      (is (= ["m2" "m1"] stack)
          "m1 raised back to top of stack")
      (is (= 99 (-> entries (get "m1") :value))
          "m1's payload replaced with the new value"))))

;; =========================================================================
;; popup-chrome — hiccup rendering
;; =========================================================================

(deftest popup-chrome-emits-backdrop-and-dialog
  (let [h (edn-inspector-popup/popup-chrome
            {:mount-id    "m1"
             :value       {:a 1 :b 2}
             :opts        {:title "Inspect cart"}
             :positioning :fixed
             :stack-pos   0})]
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-popup-backdrop-m1"))
        "backdrop node carries mount-id-suffixed testid")
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-popup-dialog-m1"))
        "dialog node carries mount-id-suffixed testid")
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-popup-body-m1"))
        "body node carries mount-id-suffixed testid")
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-popup-close-m1"))
        "close button carries mount-id-suffixed testid")))

(deftest popup-chrome-emits-title-node-with-caller-title
  (let [h (edn-inspector-popup/popup-chrome
            {:mount-id    "m1"
             :value       42
             :opts        {:title "Custom title"}
             :positioning :fixed
             :stack-pos   0})
        title-node (find-attr h :data-testid
                              "rf-xray-edn-inspector-popup-title-m1")]
    (is (some? title-node) "title node renders")
    ;; The title text is the third element (after the tag + attrs).
    (is (some #{"Custom title"} (flatten title-node))
        "title text echoes caller-supplied :title")))

(deftest popup-chrome-default-title
  (let [h (edn-inspector-popup/popup-chrome
            {:mount-id    "m1"
             :value       42
             :opts        {}
             :positioning :fixed
             :stack-pos   0})
        title-node (find-attr h :data-testid
                              "rf-xray-edn-inspector-popup-title-m1")]
    (is (some #{"Inspect"} (flatten title-node))
        "no :title → default 'Inspect' label")))

(deftest popup-chrome-embeds-edn-inspector-widget
  (let [h    (edn-inspector-popup/popup-chrome
               {:mount-id    "m1"
                :value       {:foo :bar}
                :opts        {}
                :positioning :fixed
                :stack-pos   0})
        body (find-attr h :data-testid
                        "rf-xray-edn-inspector-popup-body-m1")]
    (is (some? body) "body node renders")
    ;; The body's child is `[ei/edn-inspector value opts]` — find the
    ;; edn-inspector fn reference in the body subtree.
    (let [edn-inspector-call?
          (some (fn [node]
                  (and (vector? node)
                       (fn? (first node))
                       ;; The value at second position is the popup's
                       ;; value (or a wrapper around it).
                       (= (count node) 3)))
                (walk-hiccup body))]
      (is edn-inspector-call?
          "body embeds a fn-as-component call (the edn-inspector widget)"))))

(deftest popup-chrome-uses-aria-dialog-attrs
  (let [h      (edn-inspector-popup/popup-chrome
                 {:mount-id    "m1"
                  :value       42
                  :opts        {}
                  :positioning :fixed
                  :stack-pos   0})
        dialog (find-attr h :data-testid
                          "rf-xray-edn-inspector-popup-dialog-m1")]
    (is (= "dialog" (-> dialog second :role))
        "dialog carries WAI-ARIA role")
    (is (= "true" (-> dialog second :aria-modal))
        "dialog is aria-modal")
    (is (= "rf-xray-edn-inspector-popup-title-m1"
           (-> dialog second :aria-labelledby))
        "dialog labelled by the title node id")))

(deftest popup-chrome-respects-modal-positioning-absolute
  (let [h (edn-inspector-popup/popup-chrome
            {:mount-id    "m1"
             :value       42
             :opts        {}
             :positioning :absolute
             :stack-pos   0})
        backdrop (find-attr h :data-testid
                            "rf-xray-edn-inspector-popup-backdrop-m1")]
    (is (= "absolute"
           (-> backdrop second :style :position))
        ":absolute positioning confines backdrop to parent cell")
    (is (= "absolute"
           (-> backdrop second :data-rf-xray-modal-positioning))
        "positioning marker exposed for instrumentation")))

(deftest popup-chrome-respects-modal-positioning-fixed
  (let [h (edn-inspector-popup/popup-chrome
            {:mount-id    "m1"
             :value       42
             :opts        {}
             :positioning :fixed
             :stack-pos   0})
        backdrop (find-attr h :data-testid
                            "rf-xray-edn-inspector-popup-backdrop-m1")]
    (is (= "fixed"
           (-> backdrop second :style :position))
        ":fixed positioning spans viewport (production default)")))

;; =========================================================================
;; close affordances
;; =========================================================================

(deftest close-fn-default-dispatches-close-event
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                 (reset! captured event-v))]
      (let [f (edn-inspector-popup/close-fn "m1" {})]
        (f)
        (is (= [:rf.xray.edn-inspector-popup/close "m1"] @captured)
            "default close-fn dispatches the :close event for this id")))))

(deftest close-fn-uses-caller-on-close-when-supplied
  (let [called (atom 0)
        f (edn-inspector-popup/close-fn "m1" {:on-close #(swap! called inc)})]
    (f)
    (is (= 1 @called)
        "caller-supplied :on-close fires instead of the default dispatch")
    (f)
    (is (= 2 @called)
        "each close call invokes the caller's :on-close")))

(deftest close-button-on-click-resolves
  ;; The chrome's ✕ button must carry an :on-click. We don't fire it
  ;; (no DOM event obj to pass), only assert the wiring is present.
  (let [h     (edn-inspector-popup/popup-chrome
                {:mount-id    "m1"
                 :value       42
                 :opts        {}
                 :positioning :fixed
                 :stack-pos   0})
        close (find-attr h :data-testid
                         "rf-xray-edn-inspector-popup-close-m1")]
    (is (fn? (-> close second :on-click))
        "close button carries an :on-click handler")))

(deftest backdrop-on-click-closes-via-handler
  ;; Backdrop click closes the popup; we capture the dispatch.
  (let [captured (atom nil)
        h        (edn-inspector-popup/popup-chrome
                   {:mount-id    "m1"
                    :value       42
                    :opts        {}
                    :positioning :fixed
                    :stack-pos   0})
        backdrop (find-attr h :data-testid
                            "rf-xray-edn-inspector-popup-backdrop-m1")
        on-click (-> backdrop second :on-click)]
    (is (fn? on-click) "backdrop carries an :on-click handler")
    (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                 (reset! captured event-v))]
      (on-click #js {:stopPropagation (fn [])})
      (is (= [:rf.xray.edn-inspector-popup/close "m1"] @captured)
          "backdrop click dispatches :close for this popup"))))

(deftest handle-keydown-escape-dispatches-close-top
  ;; Esc key → :close-top event (so the topmost popup closes,
  ;; layered popups beneath survive).
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                 (reset! captured event-v))]
      (edn-inspector-popup/handle-keydown
        #js {:key "Escape"
             :preventDefault  (fn [])
             :stopPropagation (fn [])})
      (is (= [:rf.xray.edn-inspector-popup/close-top] @captured)
          "Esc dispatches :close-top"))))

(deftest handle-keydown-other-keys-bubble
  ;; Non-Esc keys must not dispatch — they bubble to global
  ;; keybindings (palette / etc.).
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                 (reset! captured event-v))]
      (edn-inspector-popup/handle-keydown
        #js {:key "Enter"
             :preventDefault  (fn [])
             :stopPropagation (fn [])})
      (is (nil? @captured)
          "Enter does not dispatch any popup event"))))

;; =========================================================================
;; edn-inspector-popup (form-2 component) — per-mount UUID
;; =========================================================================

(deftest edn-inspector-popup-allocates-mount-id-per-mount
  ;; Two outer calls to the form-2 component (each modelling a
  ;; separate mount) should produce DISTINCT inner fns with distinct
  ;; mount-ids in closure.
  (let [outer-1 (edn-inspector-popup/edn-inspector-popup {:a 1})
        outer-2 (edn-inspector-popup/edn-inspector-popup {:a 1})]
    (is (fn? outer-1) "form-2 outer returns an inner fn")
    (is (fn? outer-2))
    ;; Distinct closures imply distinct mount-ids; we can't peek into
    ;; the closure directly, but the closures themselves must not be
    ;; identical (re-mounting must mint a fresh id).
    (is (not= outer-1 outer-2)
        "two outer calls produce distinct inner fns")))

(deftest edn-inspector-popup-two-arity-overload
  (testing "[edn-inspector-popup value opts] arity is accepted (D2=a
            two-arg overload convention)"
    (is (fn? (edn-inspector-popup/edn-inspector-popup {:a 1} {:title "Cart"})))))

(deftest edn-inspector-popup-default-arity
  (testing "[edn-inspector-popup value] single-arg arity works"
    (is (fn? (edn-inspector-popup/edn-inspector-popup {:a 1})))))

;; =========================================================================
;; per-mount isolation — embedded widget panel-id is mount-scoped
;; =========================================================================

(deftest popup-chrome-derives-embedded-panel-id-from-mount-id
  ;; The embedded edn-inspector widget should receive a :panel-id that
  ;; incorporates the popup's mount-id so two popups inspecting the
  ;; same value never collide on expansion state.
  (let [h    (edn-inspector-popup/popup-chrome
               {:mount-id    "m-abc"
                :value       {:a 1 :b {:c 2}}
                :opts        {:panel-id :sub-detail}
                :positioning :fixed
                :stack-pos   0})
        body (find-attr h :data-testid
                        "rf-xray-edn-inspector-popup-body-m-abc")
        ;; Walk into the body subtree and find the embedded
        ;; edn-inspector call's opts map (third element of the
        ;; `[fn value opts]` form).
        embedded-call
        (some (fn [node]
                (when (and (vector? node)
                           (fn? (first node))
                           (= 3 (count node))
                           (map? (nth node 2))
                           (contains? (nth node 2) :panel-id))
                  node))
              (walk-hiccup body))]
    (is (some? embedded-call)
        "embedded edn-inspector call resolved with a :panel-id opt")
    (let [embedded-panel-id (:panel-id (nth embedded-call 2))]
      (is (keyword? embedded-panel-id)
          "embedded panel-id is a keyword")
      (is (re-find #"m-abc" (str embedded-panel-id))
          "embedded panel-id includes the popup's mount-id"))))

(deftest popup-chrome-default-panel-id-when-opts-omitted
  ;; If the caller omits :panel-id, the embedded widget still gets a
  ;; mount-id-scoped panel-id derived from default-panel-id.
  (let [h    (edn-inspector-popup/popup-chrome
               {:mount-id    "m-xyz"
                :value       42
                :opts        {}
                :positioning :fixed
                :stack-pos   0})
        body (find-attr h :data-testid
                        "rf-xray-edn-inspector-popup-body-m-xyz")
        embedded-call
        (some (fn [node]
                (when (and (vector? node)
                           (fn? (first node))
                           (= 3 (count node))
                           (map? (nth node 2))
                           (contains? (nth node 2) :panel-id))
                  node))
              (walk-hiccup body))]
    (is (some? embedded-call))
    (let [embedded-panel-id (:panel-id (nth embedded-call 2))]
      (is (re-find #"m-xyz" (str embedded-panel-id))
          "default panel-id still namespaces by mount-id"))))

;; =========================================================================
;; stack view — renders every open entry; closed-state short-circuits
;; =========================================================================

(deftest stack-view-renders-nothing-when-empty
  (edn-inspector-popup/install!)
  ;; Register the modal-positioning sub the stack view subscribes to.
  (rf/reg-sub :rf.xray/modal-positioning (fn [_ _] :fixed))
  (is (nil? (edn-inspector-popup/edn-inspector-popup-stack))
      "closed stack short-circuits to nil"))

(deftest stack-view-renders-one-entry-per-open-popup
  (edn-inspector-popup/install!)
  (rf/reg-sub :rf.xray/modal-positioning (fn [_ _] :fixed))
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {:title "A"}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {:title "B"}}])
  (let [tree (edn-inspector-popup/edn-inspector-popup-stack)]
    (is (some? tree) "stack view renders when at least one popup is open")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-stack"))
        "container carries the stack testid")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-backdrop-m1"))
        "m1 chrome present")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-backdrop-m2"))
        "m2 chrome present")))

(deftest stack-view-marks-popup-count
  (edn-inspector-popup/install!)
  (rf/reg-sub :rf.xray/modal-positioning (fn [_ _] :fixed))
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m3" {:value 3 :opts {}}])
  (let [tree (edn-inspector-popup/edn-inspector-popup-stack)]
    (is (= 3 (-> tree second :data-rf-popup-count))
        "popup-count attribute reflects stack depth")))
