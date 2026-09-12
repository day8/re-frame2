(ns re-frame.story.ui.toolbar-cljs-test
  "Tests for the chrome-level toolbar (rf2-xi9zk).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `toggle-mode` axis semantics,
    `group-modes-by-axis` layout, `rf.story.share/parse-modes-param` URL
    parsing, `rf.story.share/prune-unregistered-modes` registrar-pruning (the
    CLJC PRODUCTION helpers — rf2-96y71s removed the JVM copies that
    used to shadow the live impl), schema additivity for the new
    `:axis` slot.
  - **CLJS-only side-effects**: localStorage round-trip via
    `save-modes-to-storage!` + `load-modes-from-storage`,
    `toggle-mode!` mutation against `shell-state-atom`, the rendered
    hiccup carries chip elements per registered mode."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.schemas :as rf.story.schemas]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.state :as rf.story.ui.state]
            #?@(:cljs [[re-frame.story.ui.cofx :as rf.story.ui.cofx]
                       [re-frame.story.ui.toolbar :as rf.story.ui.toolbar]])))

;; The `browser?` predicate that stood here is gone with the rows it
;; gated (rf2-r51p). It routed between a lane that could not run them and
;; no other lane at all; the dom sibling now routes between two lanes
;; that BOTH load the file, with a visible skip on the node side.

;; rf2-96y71s: the `:active-modes` URL contract lives in ONE place.
;; The pure `modes=` parser and the registrar-pruning helper are now
;; the CLJC PRODUCTION fns `re-frame.story.share/parse-modes-param`
;; and `re-frame.story.share/prune-unregistered-modes` — exercised
;; directly on both runtimes below. The JVM arm no longer inlines
;; copies of the toolbar parser (those copies asserted duplicated code
;; rather than the live impl). The CLJS-only arm tests the live impure
;; toolbar surfaces (localStorage, the Reagent ratom, chip hiccup).

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: toggle-mode axis semantics -----------------------------------

(deftest toggle-mode-flips-untagged
  (testing "an un-axis-tagged mode toggles on / off multi-select"
    ;; No axis-fn lookup — pass a constant nil so toggle-mode treats
    ;; the mode as un-tagged.
    (let [no-axis (fn [_] nil)]
      (is (= [:Mode.app/x]
             (rf.story.ui.state/toggle-mode [] :Mode.app/x no-axis)))
      (is (= [:Mode.app/x :Mode.app/y]
             (rf.story.ui.state/toggle-mode [:Mode.app/x] :Mode.app/y no-axis)))
      (is (= [:Mode.app/y]
             (rf.story.ui.state/toggle-mode [:Mode.app/x :Mode.app/y] :Mode.app/x no-axis))))))

(deftest toggle-mode-single-select-within-axis
  (testing "an axis-tagged mode evicts siblings sharing the axis"
    (let [axis-fn (fn [mid]
                    (case mid
                      :Mode.theme/dark  :theme
                      :Mode.theme/light :theme
                      :Mode.theme/sepia :theme
                      :Mode.vp/mobile   :viewport
                      nil))]
      ;; Start empty → add :dark → :theme axis has only :dark.
      (is (= [:Mode.theme/dark]
             (rf.story.ui.state/toggle-mode [] :Mode.theme/dark axis-fn)))
      ;; :light displaces :dark because they share :theme.
      (is (= [:Mode.theme/light]
             (rf.story.ui.state/toggle-mode [:Mode.theme/dark]
                                :Mode.theme/light axis-fn)))
      ;; :sepia displaces :light.
      (is (= [:Mode.theme/sepia]
             (rf.story.ui.state/toggle-mode [:Mode.theme/light]
                                :Mode.theme/sepia axis-fn)))
      ;; Adding :mobile (different axis) coexists with :sepia.
      (is (= [:Mode.theme/sepia :Mode.vp/mobile]
             (rf.story.ui.state/toggle-mode [:Mode.theme/sepia]
                                :Mode.vp/mobile axis-fn)))
      ;; Toggling :sepia OFF (already active) just removes it.
      (is (= [:Mode.vp/mobile]
             (rf.story.ui.state/toggle-mode [:Mode.theme/sepia :Mode.vp/mobile]
                                :Mode.theme/sepia axis-fn))))))

(deftest toggle-mode-resolves-axis-via-registrar
  (testing "the 2-arity (no axis-fn) resolves via the live registrar"
    (rf.story/reg-mode :Mode.t/dark  {:axis :theme :args {:theme :dark}})
    (rf.story/reg-mode :Mode.t/light {:axis :theme :args {:theme :light}})
    (is (= [:Mode.t/dark]  (rf.story.ui.state/toggle-mode [] :Mode.t/dark)))
    (is (= [:Mode.t/light] (rf.story.ui.state/toggle-mode [:Mode.t/dark]
                                              :Mode.t/light)))))

(deftest clear-active-modes-empties
  (testing "clear-active-modes drops every entry"
    (is (= []
           (:active-modes
             (rf.story.ui.state/clear-active-modes {:active-modes
                                        [:Mode.a/x :Mode.a/y]}))))))

;; ---- pure: schema additivity --------------------------------------------

(deftest mode-schema-accepts-axis
  (testing ":rf/mode schema accepts the optional :axis keyword"
    (is (nil? (rf.story.schemas/validate :mode {:args {:theme :dark}}))
        "no axis: still valid")
    (is (nil? (rf.story.schemas/validate :mode {:axis :theme
                                       :args {:theme :dark}}))
        "axis present: valid")
    (is (some? (rf.story.schemas/validate :mode {:axis "theme"
                                        :args {:theme :dark}}))
        "axis must be a keyword")))

;; ---- pure: group-modes-by-axis ------------------------------------------

(deftest group-modes-by-axis-orders
  (testing "axis groups sort by axis-name; un-axed bucket sits in its
            own explicit `:unaxed` slot (no sentinel keyword)"
    (let [{:keys [axes unaxed]}
          (rf.story.ui.state/group-modes-by-axis
            {:Mode.vp/mobile {:axis :viewport}
             :Mode.t/dark    {:axis :theme}
             :Mode.t/light   {:axis :theme}
             :Mode.misc/x    {}
             :Mode.misc/a    {}})]
      ;; :theme < :viewport alphabetically.
      (is (= [:theme :viewport] (mapv first axes)))
      (is (= [:Mode.t/dark :Mode.t/light] (second (nth axes 0))))
      (is (= [:Mode.vp/mobile]            (second (nth axes 1))))
      ;; Un-axed modes land in their own slot, alphabetically sorted.
      (is (= [:Mode.misc/a :Mode.misc/x] unaxed)))))

(deftest group-modes-by-axis-empty-unaxed-when-all-tagged
  (testing "every mode tagged → :unaxed slot is empty (still present)"
    (let [{:keys [axes unaxed]}
          (rf.story.ui.state/group-modes-by-axis
            {:Mode.t/dark  {:axis :theme}
             :Mode.t/light {:axis :theme}})]
      (is (= [:theme] (mapv first axes)))
      (is (= [] unaxed)))))

;; ---- pure: URL parsing (the CLJC production helper) ---------------------
;;
;; rf2-96y71s: these exercise `rf.story.share/parse-modes-param` directly — the
;; SAME fn `rf.story.share/parse-params` (and thus the url-state hydrator) uses.
;; No JVM copy to drift out of sync.

(deftest parse-modes-param-roundtrip
  (testing "single qualified mode id"
    (is (= [:Mode.app/dark]
           (rf.story.share/parse-modes-param "Mode.app/dark"))))
  (testing "comma-separated list of ids"
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (rf.story.share/parse-modes-param "Mode.app/dark,Mode.app/mobile"))))
  (testing "whitespace around commas survives"
    (is (= [:Mode.app/a :Mode.app/b]
           (rf.story.share/parse-modes-param " Mode.app/a , Mode.app/b "))))
  (testing "blank input → nil"
    (is (nil? (rf.story.share/parse-modes-param "")))
    (is (nil? (rf.story.share/parse-modes-param "   "))))
  (testing "unqualified ids parse without a namespace"
    (is (= [:bare] (rf.story.share/parse-modes-param "bare"))))
  (testing "printed-keyword form (`:ns/name`) from a hand-copied URL"
    (is (= [:Mode.app/dark]
           (rf.story.share/parse-modes-param ":Mode.app/dark")))))

;; ---- pure: prune-unregistered-modes (the CLJC production helper) --------
;;
;; rf2-96y71s: `rf.story.share/prune-unregistered-modes` is the single registrar-
;; pruning helper; the toolbar's `prune-unregistered` closes the live
;; registrar predicate over it. Tested here with an injected set so the
;; pure logic runs on both runtimes without the registrar.

(deftest prune-unregistered-modes-drops-stale
  (testing "ids not present in the registrar are dropped"
    (let [registered? #{:Mode.app/dark :Mode.app/light}]
      (is (= [:Mode.app/dark]
             (rf.story.share/prune-unregistered-modes
               [:Mode.app/dark :Mode.app/sepia] registered?)))))
  (testing "nil / empty modes coll yields []"
    (is (= [] (rf.story.share/prune-unregistered-modes nil (constantly true))))
    (is (= [] (rf.story.share/prune-unregistered-modes [] (constantly true)))))
  (testing "every id stale → empty vector (not nil)"
    (is (= [] (rf.story.share/prune-unregistered-modes
                [:Mode.app/gone :Mode.app/also-gone] #{})))))

;; ---- pure: dispatch-console visibility (rf2-qpvk) ------------------------

(deftest dispatch-console-visible-resolution
  (testing "rf2-qpvk: ONE rule for the toolbar chip and the RHS panel —
            the user toggle wins, then the variant body, then the story
            body, then false"
    (rf.story/reg-story :story.dc-opt-in {:doc "probe" :dispatch-console? true})
    (rf.story/reg-variant :story.dc-opt-in/inherits {:doc "probe"})
    (rf.story/reg-variant :story.dc-opt-in/opts-out {:doc "probe" :dispatch-console? false})
    (rf.story/reg-story :story.dc-default {:doc "probe"})
    (rf.story/reg-variant :story.dc-default/plain {:doc "probe"})
    (let [visible? rf.story.ui.state/dispatch-console-visible?
          s        rf.story.ui.state/default-shell-state
          on       (assoc-in s [:panel-visibility :dispatch-console] true)
          off      (assoc-in s [:panel-visibility :dispatch-console] false)]
      (is (true?  (visible? s :story.dc-opt-in/inherits))
          "a story-body opt-in with no user toggle is visible")
      (is (false? (visible? s :story.dc-opt-in/opts-out))
          "a variant-body false overrides the story's opt-in")
      (is (false? (visible? s :story.dc-default/plain))
          "nothing declared is hidden (the opt-in default)")
      (is (false? (visible? off :story.dc-opt-in/inherits))
          "the user toggle OFF beats the story opt-in")
      (is (true?  (visible? on :story.dc-default/plain))
          "the user toggle ON beats the default")
      (is (false? (visible? on nil))
          "no focused variant is never visible"))))

#?(:cljs
   (deftest cljs-dispatch-chip-reads-effective-visibility
     (testing "rf2-qpvk: under a story-body `:dispatch-console? true` opt-in
               the chip renders PRESSED — agreeing with the panel that is
               showing — and its first click hides the panel instead of
               writing true to a panel already open"
       (rf.story/reg-story :story.dc-chip {:doc "probe" :dispatch-console? true})
       (rf.story/reg-variant :story.dc-chip/v {:doc "probe"})
       (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.dc-chip/v)
       (let [chip-attrs (fn []
                          (->> (tree-seq coll? seq (rf.story.ui.toolbar/toolbar-strip))
                               (filter map?)
                               (filter #(= "story-toolbar-dispatch-console" (:data-test %)))
                               first))
             attrs      (chip-attrs)]
         (is (= "true" (:aria-pressed attrs))
             "the chip reads pressed under the story opt-in")
         (is (true? (rf.story.ui.state/dispatch-console-visible?
                      (rf.story.ui.state/get-state) :story.dc-chip/v))
             "and the panel's rule agrees")
         ((:on-click attrs) nil)
         (is (false? (get-in (rf.story.ui.state/get-state)
                             [:panel-visibility :dispatch-console]))
             "the first click writes the negation of the EFFECTIVE state")
         (is (= "false" (:aria-pressed (chip-attrs)))
             "after which the chip reads un-pressed")))))

;; ---- CLJS-only: live toolbar surfaces ----------------------------------
;;
;; The localStorage / `js/window` surfaces only exist under CLJS.

;; `cljs-storage-roundtrip` MOVED to
;; `re-frame.story.ui.toolbar-storage-dom-cljs-test` under rf2-r51p, with
;; the two hydrate rows below it. Each was guarded by
;; `(when (browser?) ...)` here, and this namespace ends `-cljs-test`, so
;; `:browser-test` never loaded them while `:node-test` — which has no
;; `window.localStorage` — skipped every body: they ran in neither lane.

#?(:cljs
   (deftest cljs-toggle-writes-shell-state
     (testing "toggle-mode! writes the new vector through to shell-state-atom"
       (rf.story/reg-mode :Mode.app/x {:args {:k 1}})
       (rf.story/reg-mode :Mode.app/y {:args {:k 2}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/x] (:active-modes (rf.story.ui.state/get-state))))
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/y)
       (is (= [:Mode.app/x :Mode.app/y] (:active-modes (rf.story.ui.state/get-state))))
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/y] (:active-modes (rf.story.ui.state/get-state)))))))

#?(:cljs
   (deftest cljs-axis-toggle-single-selects
     (testing "an axis-tagged mode evicts siblings via toggle-mode!"
       (rf.story/reg-mode :Mode.t/dark  {:axis :theme :args {:t :dark}})
       (rf.story/reg-mode :Mode.t/light {:axis :theme :args {:t :light}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.t/dark)
       (is (= [:Mode.t/dark] (:active-modes (rf.story.ui.state/get-state))))
       (rf.story.ui.toolbar/toggle-mode! :Mode.t/light)
       (is (= [:Mode.t/light] (:active-modes (rf.story.ui.state/get-state)))))))

#?(:cljs
   (deftest cljs-reset-clears
     (testing "reset-modes! drops every mode + persists empty"
       (rf.story/reg-mode :Mode.app/x {:args {:k 1}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/x] (:active-modes (rf.story.ui.state/get-state))))
       (rf.story.ui.toolbar/reset-modes!)
       ;; The SHELL-STATE half of this claim runs here. The storage half
       ;; was dead, so rf2-r51p SPLIT the row rather than moving it whole
       ;; — moving it would have taken this live assertion off the node
       ;; lane. See `reset-modes-persists-empty` in
       ;; `re-frame.story.ui.toolbar-storage-dom-cljs-test`.
       (is (= [] (:active-modes (rf.story.ui.state/get-state)))))))

;; The two `hydrate-modes-from-storage!` rows (precedence and stale-id
;; pruning) MOVED to `re-frame.story.ui.toolbar-storage-dom-cljs-test`
;; alongside the round-trip above — same reason, same rf2-r51p.

#?(:cljs
   (deftest cljs-toolbar-strip-renders-chip-per-mode
     (testing "every registered mode produces a chip with a data-toolbar-mode attr"
       (rf.story/reg-mode :Mode.a/x {:args {}})
       (rf.story/reg-mode :Mode.a/y {:args {}})
       ;; toolbar-strip yields a Reagent component tree; chip nodes
       ;; appear as `[chip ...]` references that React resolves on
       ;; render. To assert the hiccup shape without driving React we
       ;; invoke `chip` directly against the registered modes.
       (let [body-x (rf.story.registrar/handler-meta :mode :Mode.a/x)
             body-y (rf.story.registrar/handler-meta :mode :Mode.a/y)
             attrs-x (second (rf.story.ui.toolbar/chip :Mode.a/x body-x false))
             attrs-y (second (rf.story.ui.toolbar/chip :Mode.a/y body-y true))]
         (is (= ":Mode.a/x" (:data-toolbar-mode attrs-x)))
         (is (= ":Mode.a/y" (:data-toolbar-mode attrs-y)))
         (is (= "false" (:aria-pressed attrs-x)))
         (is (= "true"  (:aria-pressed attrs-y)))))))

#?(:cljs
   (deftest cljs-toolbar-strip-empty-state
     (testing "toolbar-strip renders the no-modes placeholder when registry is empty"
       (rf.story.registrar/clear-kind! :mode)
       (let [hiccup (rf.story.ui.toolbar/toolbar-strip)
             flat   (->> (tree-seq coll? seq hiccup)
                         (filter string?))]
         (is (some #(re-find #"no modes registered" %) flat))))))

;; ---- cofx + sub registration --------------------------------------------

#?(:cljs
   (deftest cljs-active-modes-sub-mirrors-state
     (testing ":story/active-modes subscription tracks the shell-state atom"
       (rf.story/reg-mode :Mode.app/x {:args {:k 1}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       ;; The pure snapshot helper mirrors the slot.
       (is (= [:Mode.app/x]
              (rf.story.ui.cofx/active-modes-snapshot))))))

#?(:cljs
   (deftest cljs-active-args-deep-merges
     (testing ":story/active-args deep-merges every active mode's :args"
       (rf.story/reg-mode :Mode.app/x {:args {:a 1 :nest {:p 1}}})
       (rf.story/reg-mode :Mode.app/y {:args {:b 2 :nest {:q 2}}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/y)
       (is (= {:a 1 :b 2 :nest {:p 1 :q 2}}
              (rf.story.ui.cofx/active-args-snapshot))))))
