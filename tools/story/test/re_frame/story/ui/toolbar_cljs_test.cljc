(ns re-frame.story.ui.toolbar-cljs-test
  "The chrome-level toolbar: `toggle-mode` axis semantics, the chip
  layout, the `modes=` parser and registrar pruning (the CLJC production
  helpers, on both runtimes), the dispatch-console visibility rule, and on
  CLJS the rendered strip. Mode persistence lives in the two
  `toolbar-*-dom-cljs-test` namespaces."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.state :as rf.story.ui.state]
            #?@(:cljs [[re-frame.story.registrar :as rf.story.registrar]
                       [re-frame.story.ui.cofx :as rf.story.ui.cofx]
                       [re-frame.story.ui.toolbar :as rf.story.ui.toolbar]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: toggle-mode axis semantics -----------------------------------

(deftest toggle-mode-flips-untagged
  (testing "an un-axis-tagged mode multi-selects"
    (is (= [:Mode.app/x :Mode.app/y]
           (rf.story.ui.state/toggle-mode [:Mode.app/x] :Mode.app/y (fn [_] nil))))))

(deftest toggle-mode-single-select-within-axis
  (let [axis-fn (fn [mid]
                  (case mid
                    :Mode.theme/dark  :theme
                    :Mode.theme/light :theme
                    :Mode.vp/mobile   :viewport
                    nil))]
    (testing "a mode evicts the sibling sharing its axis"
      (is (= [:Mode.theme/light]
             (rf.story.ui.state/toggle-mode [:Mode.theme/dark] :Mode.theme/light axis-fn))))
    (testing "a mode on another axis coexists"
      (is (= [:Mode.theme/dark :Mode.vp/mobile]
             (rf.story.ui.state/toggle-mode [:Mode.theme/dark] :Mode.vp/mobile axis-fn))))
    (testing "toggling an active mode removes it"
      (is (= [:Mode.vp/mobile]
             (rf.story.ui.state/toggle-mode [:Mode.theme/dark :Mode.vp/mobile]
                                            :Mode.theme/dark axis-fn))))))

(deftest toggle-mode-resolves-axis-via-registrar
  (testing "the 2-arity (no axis-fn) resolves via the live registrar"
    (rf.story/reg-mode :Mode.t/dark  {:axis :theme :args {:theme :dark}})
    (rf.story/reg-mode :Mode.t/light {:axis :theme :args {:theme :light}})
    (is (= [:Mode.t/light] (rf.story.ui.state/toggle-mode [:Mode.t/dark]
                                                          :Mode.t/light)))))

;; ---- pure: group-modes-by-axis ------------------------------------------

(deftest group-modes-by-axis-orders
  (testing "axis groups sort by axis-name; un-axed modes sit in their own
            sorted `:unaxed` slot"
    (is (= {:axes   [[:theme [:Mode.t/dark :Mode.t/light]]
                     [:viewport [:Mode.vp/mobile]]]
            :unaxed [:Mode.misc/a :Mode.misc/x]}
           (rf.story.ui.state/group-modes-by-axis
             {:Mode.vp/mobile {:axis :viewport}
              :Mode.t/dark    {:axis :theme}
              :Mode.t/light   {:axis :theme}
              :Mode.misc/x    {}
              :Mode.misc/a    {}})))))

;; ---- pure: URL parsing (the CLJC production helper) ---------------------

(deftest parse-modes-param-reads-wire-tokens
  (are [s expected] (= expected (rf.story.share/parse-modes-param s))
    "Mode.app/dark,Mode.app/mobile" [:Mode.app/dark :Mode.app/mobile]
    " Mode.app/a , Mode.app/b "     [:Mode.app/a :Mode.app/b]
    "   "                           nil
    "bare"                          [:bare]
    ;; the printed-keyword form, from a hand-copied URL
    ":Mode.app/dark"                [:Mode.app/dark]))

;; ---- pure: prune-unregistered-modes (the CLJC production helper) --------

(deftest prune-unregistered-modes-drops-stale
  (testing "ids not present in the registrar are dropped"
    (is (= [:Mode.app/dark]
           (rf.story.share/prune-unregistered-modes
             [:Mode.app/dark :Mode.app/sepia] #{:Mode.app/dark :Mode.app/light})))))

;; ---- pure: dispatch-console visibility ----------------------------------

(deftest dispatch-console-visible-resolution
  (testing "ONE rule for the toolbar chip and the RHS panel —
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
     (testing "under a story-body `:dispatch-console? true` opt-in
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
         ((:on-click attrs) nil)
         (is (false? (get-in (rf.story.ui.state/get-state)
                             [:panel-visibility :dispatch-console]))
             "the first click writes the negation of the EFFECTIVE state")
         (is (= "false" (:aria-pressed (chip-attrs)))
             "after which the chip reads un-pressed")))))

;; ---- CLJS-only: the rendered strip ---------------------------------------

#?(:cljs
   (deftest cljs-toolbar-strip-empty-state
     (testing "toolbar-strip renders the no-modes placeholder when registry is empty"
       (rf.story.registrar/clear-kind! :mode)
       (let [hiccup (rf.story.ui.toolbar/toolbar-strip)
             flat   (->> (tree-seq coll? seq hiccup)
                         (filter string?))]
         (is (some #(re-find #"no modes registered" %) flat))))))

#?(:cljs
   (deftest cljs-toolbar-strip-is-a-labelled-toolbar-landmark
     (testing "the strip renders as a `<header role=\"toolbar\"
               aria-label=\"Story modes\">` landmark (spec/010)"
       (let [[tag attrs] (rf.story.ui.toolbar/toolbar-strip)]
         (is (= [:header "toolbar" "Story modes"]
                [tag (:role attrs) (:aria-label attrs)]))))))

;; ---- cofx + sub registration --------------------------------------------

#?(:cljs
   (deftest cljs-active-args-deep-merges
     (testing "toggle-mode! writes the active set through to shell state, and
               :story/active-args deep-merges every active mode's :args"
       (rf.story/reg-mode :Mode.app/x {:args {:a 1 :nest {:p 1}}})
       (rf.story/reg-mode :Mode.app/y {:args {:b 2 :nest {:q 2}}})
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
       (rf.story.ui.toolbar/toggle-mode! :Mode.app/y)
       (is (= {:a 1 :b 2 :nest {:p 1 :q 2}}
              (rf.story.ui.cofx/active-args-snapshot))))))
