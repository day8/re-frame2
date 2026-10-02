(ns day8.re-frame2-xray.views.edn-inspector-popup-cljs-test
  "Unit tests for the edn-inspector popup overlay.

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
  4. **Per-mount isolation** — the embedded widget's `:panel-id` is
     derived from the popup's mount-id, so two popups inspecting the
     same value have independent expansion state. (A mount-id is the
     opening caller's to mint.)
  5. **Close affordances** — Esc key handler dispatches
     `:close-top`; backdrop click + ✕ button both invoke
     `close-fn`; caller-supplied `:on-close` overrides the
     default rf-dispatch.

  Pure-data unit tests; no DOM mount, which is the default shape
  for Xray/Story tests."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector-popup :as edn-inspector-popup]))

;; Fresh re-frame runtime per test so dispatch-sync against the
;; registered popup handlers lands on a clean slot every time.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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

(deftest stack-math
  (testing "push-entry appends a new id, raises an existing one to the
            TOP, and tolerates a nil stack"
    (are [expected stack id] (= expected (edn-inspector-popup/push-entry stack id))
      ["a"]         []            "a"
      ["a" "b"]     ["a"]         "b"
      ["a" "b" "c"] ["a" "b"]     "c"
      ["b" "c" "a"] ["a" "b" "c"] "a"
      ["a" "c" "b"] ["a" "b" "c"] "b"
      ["a"]         nil           "a"))
  (testing "pop-entry removes the id, is a no-op when the id is missing,
            and tolerates a nil stack"
    (are [expected stack id] (= expected (edn-inspector-popup/pop-entry stack id))
      ["a" "c"] ["a" "b" "c"] "b"
      []        ["a"]         "a"
      ["a" "b"] ["a" "b"]     "missing"
      []        nil           "a"))
  (testing "top-entry peeks the last id"
    (are [expected stack] (= expected (edn-inspector-popup/top-entry stack))
      nil []
      nil nil
      "a" ["a"]
      "c" ["a" "b" "c"]))
  (testing "z-index increases with stack position so deeper popups paint
            above earlier ones, and tolerates nil (the indexOf-returns-(-1)
            case)"
    (are [expected pos] (= expected (edn-inspector-popup/z-index-for pos))
      2147483640 0
      2147483641 1
      2147483645 5
      2147483640 nil)))

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

(deftest reopen-with-same-id-raises-it-and-replaces-its-payload
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

(deftest popup-chrome-title-echoes-the-caller-title-or-defaults
  (let [title-of (fn [opts]
                   (find-attr (edn-inspector-popup/popup-chrome
                                {:mount-id    "m1"
                                 :value       42
                                 :opts        opts
                                 :positioning :fixed
                                 :stack-pos   0})
                              :data-testid
                              "rf-xray-edn-inspector-popup-title-m1"))
        custom   (title-of {:title "Custom title"})]
    (is (some? custom) "title node renders")
    (is (some #{"Custom title"} (flatten custom))
        "title text echoes caller-supplied :title")
    (is (some #{"Inspect"} (flatten (title-of {})))
        "no :title → default 'Inspect' label")))

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

(deftest popup-chrome-respects-modal-positioning
  (let [backdrop-of (fn [positioning]
                      (find-attr (edn-inspector-popup/popup-chrome
                                   {:mount-id    "m1"
                                    :value       42
                                    :opts        {}
                                    :positioning positioning
                                    :stack-pos   0})
                                 :data-testid
                                 "rf-xray-edn-inspector-popup-backdrop-m1"))
        absolute    (backdrop-of :absolute)]
    (is (= "absolute" (-> absolute second :style :position))
        ":absolute positioning confines backdrop to parent cell")
    (is (= "absolute" (-> absolute second :data-rf-xray-modal-positioning))
        "positioning marker exposed for instrumentation")
    (is (= "fixed" (-> (backdrop-of :fixed) second :style :position))
        ":fixed positioning spans viewport (production default)")))

;; =========================================================================
;; close affordances
;; =========================================================================

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

(deftest handle-keydown-closes-the-top-popup-on-escape-only
  ;; Esc → :close-top, so the topmost popup closes and layered popups
  ;; beneath survive. Any other key dispatches nothing and bubbles to the
  ;; global keybindings (palette / etc.).
  (doseq [[k expected] [["Escape" [:rf.xray.edn-inspector-popup/close-top]]
                        ["Enter"  nil]]]
    (let [captured (atom nil)]
      (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                       (reset! captured event-v))]
        (edn-inspector-popup/handle-keydown
          #js {:key k
               :preventDefault  (fn [])
               :stopPropagation (fn [])})
        (is (= expected @captured)
            (str k " dispatches " (pr-str expected)))))))

;; =========================================================================
;; per-mount isolation — embedded widget panel-id is mount-scoped
;; =========================================================================

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
;;
;; `edn-inspector-popup-stack` is an
;; `rf.fresco/as-component` bridge and answers an interop vector, not a
;; tree to walk. The markup is `popup-stack-tree`, a pure fn of the values
;; the boundary reads.
;;
;; The helper below reproduces the boundary's gate and reads EXACTLY —
;; same gate, same order, same query vectors — so every row here asserts
;; on the hiccup the boundary renders, and a boundary that stopped reading
;; one of these slots would diverge from its own test helper rather than
;; silently agreeing with it.
;;
;; It is deliberately the AMBIENT `rf/subscribe`, because these rows run
;; in the node lane with no React commit at all. What the boundary's own
;; read resolves to — the frame React context names, not the ambient one
;; — is the browser lane's subject.

(defn- popup-stack-tree
  "The hiccup the stack boundary renders: nil while the stack is
  empty, the container otherwise."
  []
  (let [stack @(rf/subscribe [edn-inspector-popup/stack-slot])]
    (when (seq stack)
      (edn-inspector-popup/popup-stack-tree
        {:stack       stack
         :entries     @(rf/subscribe [edn-inspector-popup/entries-slot])
         :positioning @(rf/subscribe [:rf.xray/modal-positioning])}))))

(deftest stack-view-renders-one-entry-per-open-popup
  (edn-inspector-popup/install!)
  (rf/reg-sub :rf.xray/modal-positioning (fn [_ _] :fixed))
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m1" {:value 1 :opts {:title "A"}}])
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                     "m2" {:value 2 :opts {:title "B"}}])
  (let [tree (popup-stack-tree)]
    (is (some? tree) "stack view renders when at least one popup is open")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-stack"))
        "container carries the stack testid")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-backdrop-m1"))
        "m1 chrome present")
    (is (some? (find-attr tree :data-testid
                          "rf-xray-edn-inspector-popup-backdrop-m2"))
        "m2 chrome present")
    (is (= 2 (-> tree second :data-rf-popup-count))
        "popup-count attribute reflects stack depth")))

;; =========================================================================
;; the popup FORWARDS its opts
;; =========================================================================
;;
;; `popup-affordance-button` in `views.edn-inspector` forwards the
;; originating mount's whole opts map into the open payload, and
;; `popup-chrome` forwards it on to the embedded widget — `:zoomable?`,
;; `:card?`, `:header`, `:added?`, `:before` and whatever else the
;; caller passed. A popup that handed the widget a freshly-built map of
;; a few known keys would drop the rest on the floor, so a value popped
;; out PRECISELY BECAUSE it was cramped would arrive stripped of the
;; affordances the cramped mount had.
;;
;; `:inspector` is the seam these tests drive: `popup-chrome` takes a
;; 3-arg `(fn [mount-id value opts])` for the embedded widget's head,
;; so a capturing stub reads exactly what the widget would have been
;; handed.

(defn- forwarded-opts
  "The opts map `popup-chrome` hands the embedded widget, captured
  through the `:inspector` seam."
  [opts]
  (let [captured (atom ::never-called)]
    (edn-inspector-popup/popup-chrome
      {:mount-id    "m1"
       :value       {:foo :bar}
       :opts        opts
       :positioning :fixed
       :stack-pos   0
       :inspector   (fn [_mount-id _value widget-opts]
                      (reset! captured widget-opts)
                      [:span "stub"])})
    @captured))

(deftest popup-forwards-arbitrary-widget-opts
  (let [out (forwarded-opts {:zoomable? true
                             :card? true
                             :header "Payload"
                             :added? true
                             :max-inline-width 120})]
    (is (map? out) "the inspector seam was called with an opts map")
    (is (true? (:zoomable? out))
        ":zoomable? reaches the widget")
    (is (true? (:card? out))     ":card? reaches the widget")
    (is (= "Payload" (:header out)) ":header reaches the widget")
    (is (true? (:added? out))    ":added? reaches the widget")
    (is (= 120 (:max-inline-width out))
        "an explicitly-passed width still wins")))

(deftest popup-defers-to-the-widgets-own-expansion-ceiling
  ;; The other half: the popup passes no `:default-expanded-depth` of
  ;; its own, so the widget applies its own ceiling (8). A popup-specific
  ;; shallower depth would make the ROOMY popup auto-expand LESS than the
  ;; cramped inline mount it was opened from — exactly backwards.
  (let [out (forwarded-opts {})]
    (is (nil? (:default-expanded-depth out))
        "no caller value — the popup passes no depth, so the widget
         applies its own ceiling"))
  (let [out (forwarded-opts {:default-expanded-depth 3})]
    (is (= 3 (:default-expanded-depth out))
        "a caller who DOES ask for a depth still gets it")))

(deftest popup-never-offers-to-open-itself-in-a-popup
  (is (false? (:popup-affordance? (forwarded-opts {})))
      "forced false by default")
  (is (false? (:popup-affordance? (forwarded-opts {:popup-affordance? true})))
      "and forced false even when the caller asks for it — a popup
       inside a popup is not a thing"))

(deftest popup-isolates-expansion-state-from-the-mount-it-came-from
  ;; Two keys are deliberately NOT forwarded as-is, and both exist to
  ;; keep the popup's expansion/zoom state off the panel underneath
  ;; (which almost certainly mounts the same value at the same path).
  (testing ":panel-id is REPLACED with one derived from the mount-id"
    (let [out (forwarded-opts {:panel-id :rf.xray/app-db})]
      (is (= :rf.xray.edn-inspector-popup/app-db-m1 (:panel-id out))
          "derived from the caller's panel-id AND the mount-id")))
  (testing ":site-id is DROPPED, and has to be"
    ;; The widget keys expansion and zoom on `(or site-id mount-id)`,
    ;; so a forwarded `:site-id` would out-rank the derived panel-id
    ;; above and silently re-collide the popup with its origin.
    (let [out (forwarded-opts {:site-id "app-db-top" :zoomable? true})]
      (is (nil? (:site-id out))
          ":site-id does not reach the widget")
      (is (true? (:zoomable? out))
          "and dropping it does not disturb its neighbours — the control"))))

(deftest popup-forwards-nil-opts-safely
  (let [out (forwarded-opts nil)]
    (is (map? out) "a nil opts map still produces a map")
    (is (false? (:popup-affordance? out)))
    (is (= :rf.xray.edn-inspector-popup/anon-m1 (:panel-id out))
        "and falls back to the default panel-id")))
