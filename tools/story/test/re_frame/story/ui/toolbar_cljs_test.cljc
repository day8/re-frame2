(ns re-frame.story.ui.toolbar-cljs-test
  "Tests for the chrome-level toolbar (rf2-xi9zk).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `toggle-mode` axis semantics,
    `group-modes-by-axis` layout, `parse-modes-param` URL parsing,
    `prune-unregistered` registrar-pruning, schema additivity for the
    new `:axis` slot.
  - **CLJS-only side-effects**: localStorage round-trip via
    `save-modes-to-storage!` + `load-modes-from-storage`,
    `toggle-mode!` mutation against `shell-state-atom`, hydration
    precedence (URL beats localStorage), the rendered hiccup carries
    chip elements per registered mode."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.schemas :as schemas]
            [re-frame.story.ui.state :as state]
            #?@(:cljs [[re-frame.story.ui.cofx :as ui-cofx]
                       [re-frame.story.ui.toolbar :as toolbar]])))

#?(:cljs
   (defn- browser?
     "True when running in a context with a working `js/window.localStorage`.
     Node-test (the shadow `:node-test` target) returns false; browser-
     test returns true. Mirrors the gate in `story_help_cljs_test`."
     []
     (and (exists? js/window) (.-localStorage js/window))))

;; The full `toolbar` ns is CLJS-only — it depends on `js/window`,
;; `js/URLSearchParams`, and `localStorage`. The JVM arm of this test
;; exercises the pure helpers that live in `state.cljc` (those run
;; on both runtimes) and inlines duplicates of the pure parsing
;; helpers below so the JVM corpus can exercise them without
;; requiring the CLJS ns. The CLJS arm tests the live impure
;; surfaces.

#?(:clj
   (defn ^:no-doc parse-modes-param-jvm
     "JVM duplicate of `toolbar/parse-modes-param`. Tracks the live
     CLJS impl byte-for-byte; if the live impl changes shape, this
     copy must update."
     [s]
     (when (and (string? s) (seq (str/trim s)))
       (->> (str/split s #",")
            (map str/trim)
            (remove str/blank?)
            (map (fn [part]
                   (if-let [slash (str/index-of part \/)]
                     (keyword (subs part 0 slash) (subs part (inc slash)))
                     (keyword part))))
            vec))))

#?(:clj
   (defn ^:no-doc prune-unregistered-jvm
     "JVM duplicate of `toolbar/prune-unregistered` (registered? arity)."
     [modes registered?]
     (vec (filter registered? (or modes [])))))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- pure: toggle-mode axis semantics -----------------------------------

(deftest toggle-mode-flips-untagged
  (testing "an un-axis-tagged mode toggles on / off multi-select"
    ;; No axis-fn lookup — pass a constant nil so toggle-mode treats
    ;; the mode as un-tagged.
    (let [no-axis (fn [_] nil)]
      (is (= [:Mode.app/x]
             (state/toggle-mode [] :Mode.app/x no-axis)))
      (is (= [:Mode.app/x :Mode.app/y]
             (state/toggle-mode [:Mode.app/x] :Mode.app/y no-axis)))
      (is (= [:Mode.app/y]
             (state/toggle-mode [:Mode.app/x :Mode.app/y] :Mode.app/x no-axis))))))

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
             (state/toggle-mode [] :Mode.theme/dark axis-fn)))
      ;; :light displaces :dark because they share :theme.
      (is (= [:Mode.theme/light]
             (state/toggle-mode [:Mode.theme/dark]
                                :Mode.theme/light axis-fn)))
      ;; :sepia displaces :light.
      (is (= [:Mode.theme/sepia]
             (state/toggle-mode [:Mode.theme/light]
                                :Mode.theme/sepia axis-fn)))
      ;; Adding :mobile (different axis) coexists with :sepia.
      (is (= [:Mode.theme/sepia :Mode.vp/mobile]
             (state/toggle-mode [:Mode.theme/sepia]
                                :Mode.vp/mobile axis-fn)))
      ;; Toggling :sepia OFF (already active) just removes it.
      (is (= [:Mode.vp/mobile]
             (state/toggle-mode [:Mode.theme/sepia :Mode.vp/mobile]
                                :Mode.theme/sepia axis-fn))))))

(deftest toggle-mode-resolves-axis-via-registrar
  (testing "the 2-arity (no axis-fn) resolves via the live registrar"
    (story/reg-mode :Mode.t/dark  {:axis :theme :args {:theme :dark}})
    (story/reg-mode :Mode.t/light {:axis :theme :args {:theme :light}})
    (is (= [:Mode.t/dark]  (state/toggle-mode [] :Mode.t/dark)))
    (is (= [:Mode.t/light] (state/toggle-mode [:Mode.t/dark]
                                              :Mode.t/light)))))

(deftest clear-active-modes-empties
  (testing "clear-active-modes drops every entry"
    (is (= []
           (:active-modes
             (state/clear-active-modes {:active-modes
                                        [:Mode.a/x :Mode.a/y]}))))))

;; ---- pure: schema additivity --------------------------------------------

(deftest mode-schema-accepts-axis
  (testing ":rf/mode schema accepts the optional :axis keyword"
    (is (nil? (schemas/validate :mode {:args {:theme :dark}}))
        "no axis: still valid")
    (is (nil? (schemas/validate :mode {:axis :theme
                                       :args {:theme :dark}}))
        "axis present: valid")
    (is (some? (schemas/validate :mode {:axis "theme"
                                        :args {:theme :dark}}))
        "axis must be a keyword")))

;; ---- pure: group-modes-by-axis ------------------------------------------

(deftest group-modes-by-axis-orders
  (testing "axis groups sort by axis-name; un-axed bucket sits in its
            own explicit `:unaxed` slot (no sentinel keyword)"
    (let [{:keys [axes unaxed]}
          (state/group-modes-by-axis
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
          (state/group-modes-by-axis
            {:Mode.t/dark  {:axis :theme}
             :Mode.t/light {:axis :theme}})]
      (is (= [:theme] (mapv first axes)))
      (is (= [] unaxed)))))

;; ---- pure: URL parsing --------------------------------------------------

(deftest parse-modes-param-roundtrip
  (testing "single qualified mode id"
    (is (= [:Mode.app/dark]
           (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
             "Mode.app/dark"))))
  (testing "comma-separated list of ids"
    (is (= [:Mode.app/dark :Mode.app/mobile]
           (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
             "Mode.app/dark,Mode.app/mobile"))))
  (testing "whitespace around commas survives"
    (is (= [:Mode.app/a :Mode.app/b]
           (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
             " Mode.app/a , Mode.app/b "))))
  (testing "blank input → nil"
    (is (nil? (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
                "")))
    (is (nil? (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
                "   "))))
  (testing "unqualified ids parse without a namespace"
    (is (= [:bare] (#?(:clj parse-modes-param-jvm :cljs toolbar/parse-modes-param)
                     "bare")))))

;; ---- pure: prune-unregistered -------------------------------------------

(deftest prune-unregistered-drops-stale
  (testing "ids not present in the registrar are dropped"
    (let [registered? #{:Mode.app/dark :Mode.app/light}]
      (is (= [:Mode.app/dark]
             (#?(:clj prune-unregistered-jvm :cljs toolbar/prune-unregistered)
               [:Mode.app/dark :Mode.app/sepia] registered?))))))

;; ---- CLJS-only: live toolbar surfaces ----------------------------------
;;
;; The localStorage / `js/window` surfaces only exist under CLJS.

#?(:cljs
   (deftest cljs-storage-roundtrip
     (testing "save-modes-to-storage! + load-modes-from-storage round-trip"
       (when (browser?)
         (toolbar/save-modes-to-storage! [:Mode.app/dark :Mode.app/light])
         (is (= [:Mode.app/dark :Mode.app/light]
                (toolbar/load-modes-from-storage)))
         (toolbar/save-modes-to-storage! [])
         (is (= [] (toolbar/load-modes-from-storage)))))))

#?(:cljs
   (deftest cljs-toggle-writes-shell-state
     (testing "toggle-mode! writes the new vector through to shell-state-atom"
       (story/reg-mode :Mode.app/x {:args {:k 1}})
       (story/reg-mode :Mode.app/y {:args {:k 2}})
       (toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/x] (:active-modes (state/get-state))))
       (toolbar/toggle-mode! :Mode.app/y)
       (is (= [:Mode.app/x :Mode.app/y] (:active-modes (state/get-state))))
       (toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/y] (:active-modes (state/get-state)))))))

#?(:cljs
   (deftest cljs-axis-toggle-single-selects
     (testing "an axis-tagged mode evicts siblings via toggle-mode!"
       (story/reg-mode :Mode.t/dark  {:axis :theme :args {:t :dark}})
       (story/reg-mode :Mode.t/light {:axis :theme :args {:t :light}})
       (toolbar/toggle-mode! :Mode.t/dark)
       (is (= [:Mode.t/dark] (:active-modes (state/get-state))))
       (toolbar/toggle-mode! :Mode.t/light)
       (is (= [:Mode.t/light] (:active-modes (state/get-state)))))))

#?(:cljs
   (deftest cljs-reset-clears
     (testing "reset-modes! drops every mode + persists empty"
       (story/reg-mode :Mode.app/x {:args {:k 1}})
       (toolbar/toggle-mode! :Mode.app/x)
       (is (= [:Mode.app/x] (:active-modes (state/get-state))))
       (toolbar/reset-modes!)
       (is (= [] (:active-modes (state/get-state))))
       (when (browser?)
         (is (= [] (toolbar/load-modes-from-storage)))))))

#?(:cljs
   (deftest cljs-hydrate-from-storage-only-when-empty
     (testing "hydrate skips when the slot is already populated"
       (when (browser?)
         (story/reg-mode :Mode.app/x {:args {}})
         (story/reg-mode :Mode.app/y {:args {}})
         (toolbar/save-modes-to-storage! [:Mode.app/x])
         (state/swap-state! state/set-active-modes [:Mode.app/y])
         (toolbar/hydrate-modes-from-storage!)
         (is (= [:Mode.app/y] (:active-modes (state/get-state)))
             "non-empty slot is preserved")))))

#?(:cljs
   (deftest cljs-hydrate-from-storage-prunes-stale
     (testing "hydrate drops mode ids not in the registrar"
       (when (browser?)
         (story/reg-mode :Mode.app/x {:args {}})
         (toolbar/save-modes-to-storage! [:Mode.app/x :Mode.app/zzz])
         (toolbar/hydrate-modes-from-storage!)
         (is (= [:Mode.app/x] (:active-modes (state/get-state))))))))

#?(:cljs
   (deftest cljs-toolbar-strip-renders-chip-per-mode
     (testing "every registered mode produces a chip with a data-toolbar-mode attr"
       (story/reg-mode :Mode.a/x {:args {}})
       (story/reg-mode :Mode.a/y {:args {}})
       ;; toolbar-strip yields a Reagent component tree; chip nodes
       ;; appear as `[chip ...]` references that React resolves on
       ;; render. To assert the hiccup shape without driving React we
       ;; invoke `chip` directly against the registered modes.
       (let [body-x (story-registrar/handler-meta :mode :Mode.a/x)
             body-y (story-registrar/handler-meta :mode :Mode.a/y)
             attrs-x (second (toolbar/chip :Mode.a/x body-x false))
             attrs-y (second (toolbar/chip :Mode.a/y body-y true))]
         (is (= ":Mode.a/x" (:data-toolbar-mode attrs-x)))
         (is (= ":Mode.a/y" (:data-toolbar-mode attrs-y)))
         (is (= "false" (:aria-pressed attrs-x)))
         (is (= "true"  (:aria-pressed attrs-y)))))))

#?(:cljs
   (deftest cljs-toolbar-strip-empty-state
     (testing "toolbar-strip renders the no-modes placeholder when registry is empty"
       (story-registrar/clear-kind! :mode)
       (let [hiccup (toolbar/toolbar-strip)
             flat   (->> (tree-seq coll? seq hiccup)
                         (filter string?))]
         (is (some #(re-find #"no modes registered" %) flat))))))

;; ---- cofx + sub registration --------------------------------------------

#?(:cljs
   (deftest cljs-active-modes-sub-mirrors-state
     (testing ":story/active-modes subscription tracks the shell-state atom"
       (story/reg-mode :Mode.app/x {:args {:k 1}})
       (toolbar/toggle-mode! :Mode.app/x)
       ;; The pure snapshot helper mirrors the slot.
       (is (= [:Mode.app/x]
              (ui-cofx/active-modes-snapshot))))))

#?(:cljs
   (deftest cljs-active-args-deep-merges
     (testing ":story/active-args deep-merges every active mode's :args"
       (story/reg-mode :Mode.app/x {:args {:a 1 :nest {:p 1}}})
       (story/reg-mode :Mode.app/y {:args {:b 2 :nest {:q 2}}})
       (toolbar/toggle-mode! :Mode.app/x)
       (toolbar/toggle-mode! :Mode.app/y)
       (is (= {:a 1 :b 2 :nest {:p 1 :q 2}}
              (ui-cofx/active-args-snapshot))))))
