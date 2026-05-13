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
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.save-variant :as save-variant]
            [re-frame.story.ui.state :as state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! [f]
  (story/clear-all!)
  (state/reset-shell-state!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (save-variant/set-open-dialog-fn! nil)
  (f))

(use-fixtures :each reset-all!)

;; ---- snapshot-args -------------------------------------------------------

(deftest snapshot-args-returns-resolved-args
  (testing "snapshot-args delegates to args/resolve-args + returns the merged map"
    (story/reg-story :story.snap {:args {:theme :light}})
    (story/reg-variant :story.snap/v
      {:args {:label "hello" :n 1}
       :events []})
    (let [snap (save-variant/snapshot-args :story.snap/v)]
      (is (= "hello" (:label snap)))
      (is (= 1 (:n snap)))
      (is (= :light (:theme snap)) "story-level args are part of the snapshot"))))

(deftest snapshot-args-includes-cell-overrides
  (testing "cell-overrides supplied as opts override the variant args"
    (story/reg-variant :story.snap/v
      {:args   {:label "before" :keep "yes"}
       :events []})
    (let [snap (save-variant/snapshot-args
                 :story.snap/v
                 {:cell-overrides {:label "after"}})]
      (is (= "after" (:label snap)) "override wins over variant args")
      (is (= "yes"   (:keep snap))  "non-overridden keys come through"))))

(deftest snapshot-args-empty-for-unknown-variant
  (testing "an unknown variant returns an empty map (no throw)"
    (is (= {} (save-variant/snapshot-args :story.nope/missing)))))

;; ---- gen-variant-snippet -------------------------------------------------

(deftest gen-variant-snippet-renders-reg-variant
  (testing "snippet renders the (reg-variant ...) form with :args"
    (let [snip (save-variant/gen-variant-snippet
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
    (let [snip (save-variant/gen-variant-snippet
                 {:variant-id :story.x/y
                  :args       {}})]
      (is (str/includes? snip ":args"))
      (is (str/includes? snip "{}")))))

(deftest gen-variant-snippet-without-extends
  (testing "no :extends → no :extends slot in the form"
    (let [snip (save-variant/gen-variant-snippet
                 {:variant-id :story.x/y
                  :args       {:n 1}})]
      (is (not (str/includes? snip ":extends"))))))

(deftest gen-variant-snippet-includes-doc
  (let [snip (save-variant/gen-variant-snippet
               {:variant-id :story.x/y
                :doc        "captured via Save"
                :args       {:n 1}})]
    (is (str/includes? snip ":doc"))
    (is (str/includes? snip "captured via Save"))))

(deftest gen-variant-snippet-custom-alias
  (let [snip (save-variant/gen-variant-snippet
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
          snippet  (save-variant/gen-variant-snippet
                     {:variant-id :story.x/y :args args})
          args-str (extract-args-map snippet)]
      (is (some? args-str) "extractor found an :args map substring")
      (is (= args (edn/read-string args-str))))))

(deftest gen-variant-snippet-sorted-keys
  (testing "args keys render in sorted order for determinism"
    (let [args   {:z 1 :a 2 :m 3}
          snip   (save-variant/gen-variant-snippet
                   {:variant-id :story.x/y :args args})
          a      (str/index-of snip ":a")
          m      (str/index-of snip ":m")
          z      (str/index-of snip ":z")]
      (is (< a m z) ":a < :m < :z by index in the rendered form"))))

;; ---- default-variant-id --------------------------------------------------

(deftest default-variant-id-uses-source-namespace
  (is (= "story.counter"
         (namespace (save-variant/default-variant-id
                      :story.counter/happy-path 12345))))
  (is (str/starts-with?
        (name (save-variant/default-variant-id
                :story.counter/happy-path 12345))
        "saved-")))

(deftest default-variant-id-nil-for-unqualified
  (is (nil? (save-variant/default-variant-id :unqualified 0)))
  (is (nil? (save-variant/default-variant-id nil 0))))

;; ---- dialog state machine -------------------------------------------------

(deftest open-builds-dialog-state
  (let [s (save-variant/open save-variant/initial-dialog-state
                             :story.x/y
                             {:n 1}
                             1000)]
    (is (true? (:open? s)))
    (is (= :story.x/y (:source-id s)))
    (is (= {:n 1} (:args s)))
    (is (qualified-keyword? (:draft-id s)))))

(deftest close-returns-idle
  (let [opened (save-variant/open save-variant/initial-dialog-state
                                  :story.x/y {:n 1} 0)
        closed (save-variant/close opened)]
    (is (= save-variant/initial-dialog-state closed))))

(deftest set-draft-id-replaces
  (let [s (-> save-variant/initial-dialog-state
              (save-variant/open :story.x/y {} 0)
              (save-variant/set-draft-id :story.x/edited))]
    (is (= :story.x/edited (:draft-id s)))))

;; ---- save-current-as-variant! end-to-end ---------------------------------

(deftest save-current-as-variant!-triggers-callback
  (testing "the impure trigger calls the registered open-dialog callback"
    (story/reg-variant :story.snap/v {:args {:n 7} :events []})
    (state/swap-state! state/select-variant :story.snap/v)
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [source-id args _now-ms]
          (reset! captured {:source-id source-id :args args})))
      (let [result (save-variant/save-current-as-variant!)]
        (is (some? @captured) "the callback fired")
        (is (= :story.snap/v (:source-id @captured)))
        (is (= 7 (-> @captured :args :n)))
        (is (= :story.snap/v (:source-id result)))))))

(deftest save-current-as-variant!-nil-when-no-focus
  (testing "without a focused variant the trigger is a no-op"
    (state/swap-state! state/select-variant nil)
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [_ _ _] (reset! captured :fired)))
      (let [result (save-variant/save-current-as-variant!)]
        (is (nil? result) "no result without a focus")
        (is (nil? @captured) "callback never fires without a focus")))))

(deftest save-current-as-variant!-variant-id-override
  (testing "an explicit :variant-id overrides the shell's focus"
    (story/reg-variant :story.snap/override {:args {:n 42} :events []})
    (state/swap-state! state/select-variant nil)
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [source-id args _]
          (reset! captured {:source-id source-id :args args})))
      (save-variant/save-current-as-variant! {:variant-id :story.snap/override})
      (is (= :story.snap/override (:source-id @captured)))
      (is (= 42 (-> @captured :args :n))))))

;; ---- :rf.story/save-current-as-variant event handler ---------------------

(deftest event-handler-is-registered
  (testing "install-canonical-vocabulary! registers the save-as-variant event"
    (is (some? (registrar/handler :event
                save-variant/id-save-current-as-variant))
        "the :rf.story/save-current-as-variant handler is in the registry")))

(deftest event-handler-triggers-callback
  (testing "dispatching :rf.story/save-current-as-variant runs the save flow"
    (story/reg-variant :story.event/v {:args {:n 9} :events []})
    (state/swap-state! state/select-variant :story.event/v)
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [source-id args _]
          (reset! captured {:source-id source-id :args args})))
      (rf/dispatch-sync [save-variant/id-save-current-as-variant])
      (is (= :story.event/v (:source-id @captured)))
      (is (= 9 (-> @captured :args :n))))))

(deftest event-handler-honors-payload-opts
  (testing "the event payload's :variant-id overrides the focused variant"
    (story/reg-variant :story.event/explicit {:args {:n 11} :events []})
    (state/swap-state! state/select-variant nil)
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [source-id args _]
          (reset! captured {:source-id source-id :args args})))
      (rf/dispatch-sync [save-variant/id-save-current-as-variant
                         {:variant-id :story.event/explicit}])
      (is (= :story.event/explicit (:source-id @captured)))
      (is (= 11 (-> @captured :args :n))))))

;; ---- end-to-end ----------------------------------------------------------

(deftest end-to-end-snapshot-to-snippet
  (testing "the full snapshot→snippet cycle produces a reg-variant form"
    (story/reg-story :story.counter {:args {:theme :dark}})
    (story/reg-variant :story.counter/happy-path
      {:args {:label "Counter" :n 0}
       :events []})
    (state/swap-state! state/select-variant :story.counter/happy-path)
    ;; Capture via the impure trigger; harvest snapshot from the callback.
    (let [captured (atom nil)]
      (save-variant/set-open-dialog-fn!
        (fn [source-id args _]
          (reset! captured {:source-id source-id :args args})))
      (save-variant/save-current-as-variant!)
      (let [snippet  (save-variant/gen-variant-snippet
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
