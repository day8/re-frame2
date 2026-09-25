(ns re-frame.story-save-variant-test
  "JVM tests for the save-current-canvas-state-as-variant flow.

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
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
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
  ;; The event-handler tests dispatch
  ;; `:rf.story/save-current-as-variant` ambiently, and that frame-scoped op
  ;; requires a carried frame stamp (EP-0002). Pin the ordinary `:rf/default`
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

;; ---- the eight-slice capture report rides the trigger --------------------

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
  (testing "a source variant declaring slices with no live capture captures them
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

(deftest save-current-as-variant!-reports-the-seed-and-setup-the-source-inherits
  (testing "the DB seed row reads the :db-seed and :setup the saved variant
            runs with, whether the source declares them or inherits them
            through :extends"
    (rf.story.save-variant/set-open-dialog-fn! (fn [& _] nil))
    (let [db-seed-row (fn [variant-id]
                        (->> (rf.story.save-variant/save-current-as-variant!
                               {:variant-id variant-id})
                             :slices
                             (filter #(= :db-seed (:slice %)))
                             first))]
      (testing "a declared :db-seed is captured-as-declared"
        (rf.story/reg-variant :story.seed/declared {:args {:n 1} :db-seed {[:count] 1}})
        (let [row (db-seed-row :story.seed/declared)]
          (is (= :captured-as-declared (:status row)))
          (is (= {[:count] 1} (:value row)))))
      (testing "an inherited :db-seed reads exactly like a declared one"
        (rf.story/reg-variant :story.seed/parent {:args {:n 1} :db-seed {[:count] 1}})
        (rf.story/reg-variant :story.seed/child {:extends :story.seed/parent})
        (let [row (db-seed-row :story.seed/child)]
          (is (= :captured-as-declared (:status row))
              "the inherited seed carries forward via :extends")
          (is (= {[:count] 1} (:value row)) "the row's value is the inherited seed")
          (is (not (str/includes? (:note row) "app-db state is not captured")))))
      (testing "an inherited :setup is named beside the seed, not as the seed"
        (rf.story/reg-variant :story.setup/parent {:args {:n 1} :setup [[:counter/inc]]})
        (rf.story/reg-variant :story.setup/child {:extends :story.setup/parent})
        (let [row (db-seed-row :story.setup/child)]
          (is (= :not-wired (:status row)) ":setup events are not a seed")
          (is (str/includes? (:note row) ":setup events re-run"))))
      (testing "a chain with no seed and no setup stays not-wired"
        (rf.story/reg-variant :story.bare/parent {:args {:n 1}})
        (rf.story/reg-variant :story.bare/child {:extends :story.bare/parent})
        (let [row (db-seed-row :story.bare/child)]
          (is (= :not-wired (:status row)))
          (is (nil? (:value row)))
          (is (str/includes? (:note row) "app-db state is not captured"))
          (is (not (str/includes? (:note row) ":setup"))))))))

(def ^:private cart-route {[:get "/api/cart"] {:reply {:ok {:items []}}}})

(defn- saved-rows
  "The capture report `save-current-as-variant!` builds for `variant-id`,
  keyed by slice."
  [variant-id]
  (rf.story.save-variant/set-open-dialog-fn! (fn [& _] nil))
  (into {} (map (juxt :slice identity))
        (:slices (rf.story.save-variant/save-current-as-variant!
                   {:variant-id variant-id}))))

(deftest save-current-as-variant!-reports-the-slots-the-source-inherits
  (testing "the sub-overrides, network and viewport rows read what the saved
            variant runs with, so a value the source inherits through
            :extends reads exactly like a declared one"
    (rf.story/reg-variant :story.inherit/parent
      {:args          {:n 1}
       :sub-overrides {[:cart/items] [:a]}
       :network       cart-route
       :viewport      :tablet})
    (rf.story/reg-variant :story.inherit/child {:extends :story.inherit/parent})
    (let [rows (saved-rows :story.inherit/child)]
      (is (= :captured-as-declared (-> rows :sub-overrides :status)))
      (is (= {[:cart/items] [:a]} (-> rows :sub-overrides :value)))
      (is (= :captured-as-declared (-> rows :network :status)))
      (is (= cart-route (-> rows :network :value)))
      (is (= :captured-as-declared (-> rows :viewport :status)))
      (is (= :tablet (-> rows :viewport :value)))))
  (testing "a chain that carries none of them stays not-wired"
    (rf.story/reg-variant :story.inherit/bare-parent {:args {:n 1}})
    (rf.story/reg-variant :story.inherit/bare-child {:extends :story.inherit/bare-parent})
    (let [rows (saved-rows :story.inherit/bare-child)]
      (doseq [s [:sub-overrides :network :viewport]]
        (is (= :not-wired (-> rows s :status)) (str s))
        (is (nil? (-> rows s :value)) (str s))))))

(deftest save-current-as-variant!-fx-overrides-row-reads-the-declared-slot
  (testing "a source's :network lowers to a managed-stub fx override in the
            compiled plan, and the row never reports that lowering"
    (rf.story/reg-variant :story.fx/net {:args {:n 1} :network cart-route})
    (is (= {:rf.http/managed :rf.http/managed-test-stub}
           (get-in (rf.story.plan/variant-plan {:extends :story.fx/net :args {:n 1}})
                   [:world :frame :fx-overrides]))
        "control: the compiled world does carry the lowering")
    (let [row (:fx-overrides (saved-rows :story.fx/net))]
      (is (= :not-wired (:status row)))
      (is (nil? (:value row)) "the managed-stub lowering is not an fx override")))
  (testing "an fx override the source inherits is not shown, and the note says so"
    (rf.story/reg-variant :story.fx/parent {:args {:n 1} :fx-overrides {:app/toast :noop}})
    (rf.story/reg-variant :story.fx/child {:extends :story.fx/parent})
    (let [row (:fx-overrides (saved-rows :story.fx/child))]
      (is (= :not-wired (:status row)))
      (is (str/includes? (:note row) ":fx-overrides the source inherits or composes are not shown"))))
  (testing "a declared fx override is captured-as-declared, and the note still
            says inherited or composed ones are not shown"
    (rf.story/reg-variant :story.fx/own {:args {:n 1} :fx-overrides {:app/toast :noop}})
    (let [row (:fx-overrides (saved-rows :story.fx/own))]
      (is (= :captured-as-declared (:status row)))
      (is (= {:app/toast :noop} (:value row)))
      (is (str/includes? (:note row) ":fx-overrides the source inherits or composes are not shown")))))

(defn- reg-composing-source! []
  (rf.story/reg-fragment :fragment.sv/cart
    {:db-seed       {[:count] 7}
     :sub-overrides {[:cart/items] [:a]}
     :network       cart-route})
  (rf.story/reg-variant :story.sv/source {:args {:n 1} :compose [:fragment.sv/cart]}))

(deftest saved-variant-body-carries-the-source-compose
  (testing "the saved body copies the source's :compose, and the snippet prints it"
    (reg-composing-source!)
    (let [body (rf.story.save-variant/saved-variant-body :story.sv/source {:n 1})]
      (is (= {:extends :story.sv/source :compose [:fragment.sv/cart] :args {:n 1}} body))
      (is (str/includes? (rf.story.save-variant/gen-variant-snippet
                           (assoc body :variant-id :story.sv/saved))
                         ":compose [:fragment.sv/cart]"))))
  (testing "the saved form re-registers the world the source composes"
    (reg-composing-source!)
    (let [snippet     (rf.story.save-variant/gen-variant-snippet
                        (assoc (rf.story.save-variant/saved-variant-body
                                 :story.sv/source
                                 (rf.story.save-variant/snapshot-args :story.sv/source))
                               :variant-id :story.sv/saved))
          [_ id body] (edn/read-string snippet)
          world-of    (fn [vid]
                        (select-keys (:world (rf.story.plan/variant-plan vid))
                                     [:db-seed :network :render :frame :setup :fidelity]))]
      (rf.story.registrar/reg-variant* id body)
      (is (= {[:count] 7} (:db-seed (world-of :story.sv/source))) "control: the source seeds")
      (is (= (world-of :story.sv/source) (world-of :story.sv/saved))
          "the saved variant runs the seed, stubs and overrides the source composes")))
  (testing "the capture report reads the composed values the saved variant runs with"
    (reg-composing-source!)
    (let [rows (saved-rows :story.sv/source)]
      (is (= {[:count] 7} (-> rows :db-seed :value)))
      (is (= {[:cart/items] [:a]} (-> rows :sub-overrides :value)))
      (is (= cart-route (-> rows :network :value)))))
  (testing "a source that composes nothing gets no :compose slot"
    (rf.story/reg-variant :story.sv/plain {:args {:n 1}})
    (let [body (rf.story.save-variant/saved-variant-body :story.sv/plain {:n 1})]
      (is (not (contains? body :compose)))
      (is (not (str/includes? (rf.story.save-variant/gen-variant-snippet
                                (assoc body :variant-id :story.sv/saved-plain))
                              ":compose"))))))

(deftest saved-variant-body-leaves-out-a-compose-whose-order-it-cannot-keep
  (testing ":compose runs a fragment's setup BEFORE the source's own, while a
            variant extending the source runs the source's own setup first"
    (rf.story/reg-fragment :fragment.sv/boot {:setup [[:frag/boot]]})
    (rf.story/reg-variant :story.sv/own-setup
      {:args {:n 1} :setup [[:own/boot]] :compose [:fragment.sv/boot]})
    (let [setup-of #(mapv second (get-in (rf.story.plan/variant-plan %) [:world :setup]))]
      (is (= [[:frag/boot] [:own/boot]] (setup-of :story.sv/own-setup))
          "control: the source runs the fragment's setup first")
      (is (= [[:own/boot] [:frag/boot]]
             (setup-of {:extends :story.sv/own-setup
                        :compose [:fragment.sv/boot]
                        :args    {:n 1}}))
          "a copied :compose would run them the other way round"))
    (is (not (contains? (rf.story.save-variant/saved-variant-body :story.sv/own-setup {:n 1})
                        :compose))))
  (testing "the same holds when both carry a script"
    (rf.story/reg-fragment :fragment.sv/click {:script [[:frag/click]]})
    (rf.story/reg-variant :story.sv/own-script
      {:args {:n 1} :script [[:own/click]] :compose [:fragment.sv/click]})
    (is (not (contains? (rf.story.save-variant/saved-variant-body :story.sv/own-script {:n 1})
                        :compose))))
  (testing "a source setup with no fragment setup beside it keeps the copy"
    (rf.story/reg-fragment :fragment.sv/seed {:db-seed {[:count] 1}})
    (rf.story/reg-variant :story.sv/setup-and-seed
      {:args {:n 1} :setup [[:own/boot]] :compose [:fragment.sv/seed]})
    (is (= [:fragment.sv/seed]
           (:compose (rf.story.save-variant/saved-variant-body :story.sv/setup-and-seed {:n 1}))))))

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
