(ns re-frame.story-save-variant-test
  "JVM tests for the save-current-canvas-state-as-variant flow (rf2-one3t).

  Pure-data coverage: the args-snapshot helper, the EDN code-gen
  (`gen-variant-snippet`), the dialog state-machine transitions, and the
  default-id derivation. Mirrors the cljs-test arm in
  `story_save_variant_cljs_test.cljs`.

  ## Coverage layers

  - `snapshot-args` — pure args-resolution against the live registrar +
    shell-state cell-overrides.
  - `gen-variant-snippet` — codegen output is `read-string`-able EDN
    with the expected `(reg-variant <id> {:extends ... :args {...}})`
    shape.
  - Dialog state machine (`open` / `close` / `set-draft-id`) — pure
    transitions JVM-testable in isolation.
  - `:rf.story/save-current-as-variant` event handler — registered via
    `install-canonical-event-handlers!` and dispatchable through the
    standard re-frame router."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.save-variant :as rf.story.save-variant]
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! [f]
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf.story.save-variant/set-open-dialog-fn! nil)
  ;; EP-0002 (rf2-bd4div) — the event-handler tests dispatch
  ;; `:rf.story/save-current-as-variant` ambiently; that frame-scoped op
  ;; now requires a carried frame stamp. Pin the ordinary `:rf/default`
  ;; frame (registered just above) as the established scope for the test
  ;; body so the dispatch lands on a real frame rather than raising
  ;; :rf.error/no-frame-context.
  (rf/with-frame :rf/default
    (f)))

(use-fixtures :each reset-all!)

;; ---- snapshot-args -------------------------------------------------------

(deftest snapshot-args-returns-resolved-args
  (testing "snapshot-args delegates to args/resolve-args + returns the merged map"
    (rf.story/reg-story :story.snap {:args {:theme :light}})
    (rf.story/reg-variant :story.snap/v
      {:args {:label "hello" :n 1}
       :setup []})
    (let [snap (rf.story.save-variant/snapshot-args :story.snap/v)]
      (is (= "hello" (:label snap)))
      (is (= 1 (:n snap)))
      (is (= :light (:theme snap)) "story-level args are part of the snapshot"))))

(deftest snapshot-args-includes-cell-overrides
  (testing "cell-overrides supplied as opts override the variant args"
    (rf.story/reg-variant :story.snap/v
      {:args   {:label "before" :keep "yes"}
       :setup []})
    (let [snap (rf.story.save-variant/snapshot-args
                 :story.snap/v
                 {:cell-overrides {:label "after"}})]
      (is (= "after" (:label snap)) "override wins over variant args")
      (is (= "yes"   (:keep snap))  "non-overridden keys come through"))))

(deftest snapshot-args-empty-for-unknown-variant
  (testing "an unknown variant returns an empty map (no throw)"
    (is (= {} (rf.story.save-variant/snapshot-args :story.nope/missing)))))

;; ---- gen-variant-snippet -------------------------------------------------

(deftest gen-variant-snippet-renders-reg-variant
  (testing "snippet renders the (reg-variant ...) form with :args"
    (let [snip (rf.story.save-variant/gen-variant-snippet
                 {:variant-id :story.counter/saved
                  :extends    :story.counter/happy-path
                  :args       {:label "hi" :n 3}})]
      (is (str/includes? snip "reg-variant"))
      (is (str/includes? snip ":story.counter/saved"))
      (is (str/includes? snip ":extends"))
      (is (str/includes? snip ":story.counter/happy-path"))
      (is (str/includes? snip ":args"))
      (is (str/includes? snip ":label"))
      (is (str/includes? snip "\"hi\""))
      (is (str/includes? snip ":n"))
      (is (str/includes? snip "3")))))

(deftest gen-variant-snippet-empty-args
  (testing "snippet with empty args renders an empty map literal"
    (let [snip (rf.story.save-variant/gen-variant-snippet
                 {:variant-id :story.x/y
                  :args       {}})]
      (is (str/includes? snip ":args"))
      (is (str/includes? snip "{}")))))

(deftest gen-variant-snippet-without-extends
  (testing "no :extends → no :extends slot in the form"
    (let [snip (rf.story.save-variant/gen-variant-snippet
                 {:variant-id :story.x/y
                  :args       {:n 1}})]
      (is (not (str/includes? snip ":extends"))))))

(deftest gen-variant-snippet-includes-doc
  (let [snip (rf.story.save-variant/gen-variant-snippet
               {:variant-id :story.x/y
                :doc        "captured via Save"
                :args       {:n 1}})]
    (is (str/includes? snip ":doc"))
    (is (str/includes? snip "captured via Save"))))

(deftest gen-variant-snippet-custom-alias
  (let [snip (rf.story.save-variant/gen-variant-snippet
               {:variant-id :story.x/y
                :alias      "rf"
                :args       {}})]
    (is (str/includes? snip "rf/reg-variant"))))

(defn- extract-args-map
  "Walk balanced braces after the `:args` token to extract the args-map
  substring from the generated snippet."
  [snippet]
  (let [start (str/index-of snippet ":args")
        after (subs snippet start)
        open  (str/index-of after "{")]
    (loop [i (inc open) depth 1]
      (cond
        (or (nil? i) (>= i (count after)))
        nil

        (zero? depth)
        (subs after open i)

        :else
        (let [c (.charAt ^String after i)]
          (case c
            \{ (recur (inc i) (inc depth))
            \} (recur (inc i) (dec depth))
            (recur (inc i) depth)))))))

(deftest gen-variant-snippet-args-roundtrip
  (testing "the rendered :args map reads back as the original map"
    (let [args     {:label "alice" :n 42 :tags #{:a :b} :nested {:k 1}}
          snippet  (rf.story.save-variant/gen-variant-snippet
                     {:variant-id :story.x/y :args args})
          args-str (extract-args-map snippet)]
      (is (some? args-str) "extractor found an :args map substring")
      (is (= args (edn/read-string args-str))))))

(deftest gen-variant-snippet-sorted-keys
  (testing "args keys render in sorted order for determinism"
    (let [args   {:z 1 :a 2 :m 3}
          snip   (rf.story.save-variant/gen-variant-snippet
                   {:variant-id :story.x/y :args args})
          a      (str/index-of snip ":a")
          m      (str/index-of snip ":m")
          z      (str/index-of snip ":z")]
      (is (< a m z) ":a < :m < :z by index in the rendered form"))))

;; ---- default-variant-id --------------------------------------------------

(deftest default-variant-id-uses-source-namespace
  (is (= "story.counter"
         (namespace (rf.story.save-variant/default-variant-id
                      :story.counter/happy-path 12345))))
  (is (str/starts-with?
        (name (rf.story.save-variant/default-variant-id
                :story.counter/happy-path 12345))
        "saved-")))

(deftest default-variant-id-nil-for-unqualified
  (is (nil? (rf.story.save-variant/default-variant-id :unqualified 0)))
  (is (nil? (rf.story.save-variant/default-variant-id nil 0))))

;; ---- dialog state machine -------------------------------------------------

(deftest open-builds-dialog-state
  (let [s (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                             :story.x/y
                             {:n 1}
                             1000)]
    (is (true? (:open? s)))
    (is (= :story.x/y (:source-id s)))
    (is (= {:n 1} (:args s)))
    (is (qualified-keyword? (:draft-id s)))))

(deftest close-returns-idle
  (let [opened (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/y {:n 1} 0)
        closed (rf.story.save-variant/close opened)]
    (is (= rf.story.save-variant/initial-dialog-state closed))))

(deftest set-draft-id-replaces
  (let [s (-> rf.story.save-variant/initial-dialog-state
              (rf.story.save-variant/open :story.x/y {} 0)
              (rf.story.save-variant/set-draft-id :story.x/edited))]
    (is (= :story.x/edited (:draft-id s)))))

;; ---- save-current-as-variant! end-to-end ---------------------------------

(deftest save-current-as-variant!-triggers-callback
  (testing "the impure trigger calls the registered open-dialog callback"
    (rf.story/reg-variant :story.snap/v {:args {:n 7} :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.snap/v)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [source-id args _now-ms _violations & _]
          (reset! captured {:source-id source-id :args args})))
      (let [result (rf.story.save-variant/save-current-as-variant!)]
        (is (some? @captured) "the callback fired")
        (is (= :story.snap/v (:source-id @captured)))
        (is (= 7 (-> @captured :args :n)))
        (is (= :story.snap/v (:source-id result)))))))

;; ---- rf2-ba86n.6: the eight-slice capture report rides the trigger -------

(deftest save-current-as-variant!-carries-slice-report
  (testing "the trigger computes the eight-slice capture report and passes
            it through both the callback and the returned record"
    (rf.story/reg-variant :story.snap/sliced {:args {:n 3} :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.snap/sliced)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [_source-id _args _now-ms _violations slices]
          (reset! captured slices)))
      (let [result (rf.story.save-variant/save-current-as-variant!)
            slices (:slices result)]
        (is (= (set rf.story.save-variant/slice-order)
               (set (map :slice slices)))
            "every canonical slice is classified — none silently dropped")
        (is (= slices @captured)
            "the same report rides the callback's 5th arg")
        (is (= :projectable (:status (first (filter #(= :args (:slice %)) slices))))
            "args is the projectable slice")))))

(deftest save-current-as-variant!-declared-slots-captured-as-declared
  (testing "a source variant declaring the not-yet-wired slices captures them
            as-declared (carried forward via :extends) — honest, not dropped"
    (rf.story/reg-variant :story.snap/declared
      {:args         {:n 1}
       :sub-overrides {[:s] :v}
       :setup       []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.snap/declared)
    (rf.story.save-variant/set-open-dialog-fn! (fn [& _] nil))
    (let [result   (rf.story.save-variant/save-current-as-variant!)
          by-slice (into {} (map (juxt :slice identity)) (:slices result))]
      (is (= :captured-as-declared (-> by-slice :sub-overrides :status))
          "the declared :sub-overrides carry forward as-declared")
      (is (= {[:s] :v} (-> by-slice :sub-overrides :value))))))

(deftest save-current-as-variant!-nil-when-no-focus
  (testing "without a focused variant the trigger is a no-op"
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant nil)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [_ _ _ _] (reset! captured :fired)))
      (let [result (rf.story.save-variant/save-current-as-variant!)]
        (is (nil? result) "no result without a focus")
        (is (nil? @captured) "callback never fires without a focus")))))

(deftest save-current-as-variant!-variant-id-override
  (testing "an explicit :variant-id overrides the shell's focus"
    (rf.story/reg-variant :story.snap/override {:args {:n 42} :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant nil)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [source-id args & _]
          (reset! captured {:source-id source-id :args args})))
      (rf.story.save-variant/save-current-as-variant! {:variant-id :story.snap/override})
      (is (= :story.snap/override (:source-id @captured)))
      (is (= 42 (-> @captured :args :n))))))

;; ---- :rf.story/save-current-as-variant event handler ---------------------

(deftest event-handler-is-registered
  (testing "install-canonical-vocabulary! registers the save-as-variant event"
    (is (some? (rf.registrar/handler :event
                rf.story.save-variant/id-save-current-as-variant))
        "the :rf.story/save-current-as-variant handler is in the registry")))

(deftest event-handler-triggers-callback
  (testing "dispatching :rf.story/save-current-as-variant runs the save flow"
    (rf.story/reg-variant :story.event/v {:args {:n 9} :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.event/v)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [source-id args & _]
          (reset! captured {:source-id source-id :args args})))
      (rf/dispatch-sync [rf.story.save-variant/id-save-current-as-variant])
      (is (= :story.event/v (:source-id @captured)))
      (is (= 9 (-> @captured :args :n))))))

(deftest event-handler-honors-payload-opts
  (testing "the event payload's :variant-id overrides the focused variant"
    (rf.story/reg-variant :story.event/explicit {:args {:n 11} :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant nil)
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [source-id args & _]
          (reset! captured {:source-id source-id :args args})))
      (rf/dispatch-sync [rf.story.save-variant/id-save-current-as-variant
                         {:variant-id :story.event/explicit}])
      (is (= :story.event/explicit (:source-id @captured)))
      (is (= 11 (-> @captured :args :n))))))

;; ---- end-to-end ----------------------------------------------------------

(deftest end-to-end-snapshot-to-snippet
  (testing "the full snapshot→snippet cycle produces a reg-variant form"
    (rf.story/reg-story :story.counter {:args {:theme :dark}})
    (rf.story/reg-variant :story.counter/happy-path
      {:args {:label "Counter" :n 0}
       :setup []})
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.counter/happy-path)
    ;; Capture via the impure trigger; harvest snapshot from the callback.
    (let [captured (atom nil)]
      (rf.story.save-variant/set-open-dialog-fn!
        (fn [source-id args & _]
          (reset! captured {:source-id source-id :args args})))
      (rf.story.save-variant/save-current-as-variant!)
      (let [snippet  (rf.story.save-variant/gen-variant-snippet
                       {:variant-id :story.counter/saved-1
                        :extends    (:source-id @captured)
                        :args       (:args @captured)})
            args-str (extract-args-map snippet)]
        (is (= :story.counter/happy-path (:source-id @captured)))
        (is (= :dark (-> @captured :args :theme)))
        (is (str/includes? snippet "reg-variant"))
        (is (str/includes? snippet ":story.counter/saved-1"))
        (is (str/includes? snippet ":story.counter/happy-path")
            "the source-id rides into :extends")
        (is (= (:args @captured) (edn/read-string args-str))
            "the snapshot args round-trip through the snippet")))))
