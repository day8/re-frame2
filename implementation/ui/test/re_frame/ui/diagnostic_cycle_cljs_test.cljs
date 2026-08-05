(ns re-frame.ui.diagnostic-cycle-cljs-test
  "THE DIAGNOSTIC PATH ITSELF THREW — the `re-frame.ui` CLIENT-RUNTIME sites
  (rf2-q9q9y).

  Same defect as its three predecessors, one tier down. `cljs.core`'s printer
  descends into a foreign JS value through exactly two `pr-writer-impl`
  branches — `object?` (own enumerable keys) and `array?` (elements) — and
  NEITHER carries a seen-set, so a cyclic foreign object graph makes `pr-str`
  raise `RangeError: Maximum call stack size exceeded`. React 19's
  `createContext` returns an object whose `Provider` key points back at the
  context itself, so `ctx.Provider` IS such a graph, and leaking one into a
  slot / child / handler position is an ordinary authoring mistake.

  WHAT MAKES THIS GROUP DIFFERENT FROM ITS PREDECESSORS. `re-frame.ssr.emit`,
  `re-frame.ssr.ui-tree` and `re-frame.ui.semantic` are server / test-tier
  surfaces. These are the CLIENT RUNTIME — `ui/slot`, `ui/render`'s dynamic
  child check, `ui/->react`, and the committed-event vocabulary in
  `re-frame.ui.events`. An author hits them in the browser, in dev, on the
  path their own mistake took.

  ## How these rows OBSERVE a stack overflow

  A `RangeError` raised inside a diagnostic is an ordinary synchronous JS
  throw. Every row calls through [[outcome]], which returns a MAP —
  `{:returned …}` or `{:threw <name> :error-id … :message …
  :ex-data-printable? …}` — and asserts on that map, so a regression fails
  with `\"RangeError\"` in its own failure text rather than aborting the var.

  ## The two halves, at every site

  `error/pr-form` on the message half; `error/safe-form` on the `:extra`
  ex-data half. BOTH matter: a cyclic value that merely rides out in
  `{:extra {:value v}}` explodes at a DOWNSTREAM logger / error projector /
  trace sink — someone else's boundary rather than the thrower's.

  [[the-fixtures-are-genuinely-cyclic]] is the non-vacuity control. Without
  it every row below could pass on an acyclic fixture and prove nothing."
  (:require ["react" :as react]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ui.events :as events]
            [re-frame.ui.runtime :as runtime]
            [re-frame.ui.tree :as tree]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- self-referential-object
  "A plain JS object holding a reference to ITSELF. No React: the defect is a
  property of a foreign object graph, and this is the smallest value that has
  it."
  []
  (let [o #js {"tag" "cyclic"}]
    (unchecked-set o "self" o)
    o))

(defn- self-referential-array
  "The ARRAY half of the same crossing — `pr-str`'s other descending branch."
  []
  (let [a #js ["cyclic"]]
    (.push a a)
    a))

(def ^:private corpus-context
  "A real React context, so the rows below carry the shape that was reported
  rather than a model of it."
  (react/createContext "unset"))

(def ^:private provider
  "`ctx.Provider` — the value an author most plausibly leaks into a slot,
  a child position, or a handler prop."
  (.-Provider corpus-context))

(def ^:private acyclic-object
  "The ACYCLIC control. A plain JS object `pr-str` renders in full and
  terminates on: every row that pins byte-identity uses this."
  #js {"theme" "dark" "level" 3})

;; ---------------------------------------------------------------------------
;; Observation
;; ---------------------------------------------------------------------------

(defn- outcome
  "Run `f` and describe what happened as DATA. On a throw the map carries the
  host error NAME (so a stack overflow shows up as `\"RangeError\"` in the
  failure text), the framework error id, the message, and whether the ex-data
  survives `pr-str` — which is the ex-data half of the defect.

  THE RETURN VALUE IS RECORDED AS A BOOLEAN, NEVER AS ITSELF, and that is
  load-bearing rather than tidiness. Several sites here RETURN their input
  (`check-key!` does; a guard that stopped firing would too), so a `:returned`
  slot would hold the cyclic fixture — and every failure message below
  `pr-str`s the outcome map. A regression would then raise the very
  `RangeError` under test FROM THE ASSERTION, producing a red that looks
  exactly like the product defect while the product is fine. Keeping only
  `:returned?` makes the outcome map safe to print by construction, so the
  failure text stays trustworthy under precisely the conditions it exists for."
  [f]
  (try
    (do (f) {:returned? true})
    (catch :default e
      (let [data (ex-data e)]
        {:threw    (.-name e)
         :error-id (:rf.error/id data)
         :message  (ex-message e)
         :ex-data-printable?
         (try (string? (pr-str data)) (catch :default _ false))}))))

(defn- rejected-with
  "Assert `f` threw the framework error `id` — with the whole outcome map in
  the failure text, and with the ex-data proven printable at the same time."
  [id label f]
  (let [o (outcome f)]
    (is (= id (:error-id o))
        (str label " must throw " id "; got " (pr-str o)))
    (is (true? (:ex-data-printable? o))
        (str label "'s ex-data must survive pr-str at a downstream sink; got "
             (pr-str (dissoc o :message))))
    o))

(defn- warning-outcome
  "`check-key!` DIAGNOSES BY `js/console.warn`, not by throwing, so its
  crossing is observed by capturing the warn arguments. The `pr-str` call is
  an ARGUMENT to `console.warn`, so an overflow still propagates out of
  `check-key!` as an ordinary throw — [[outcome]] catches that, and
  `:warned` carries the joined arguments when it does not."
  [f]
  (let [seen     (atom [])
        original js/console.warn]
    (set! js/console.warn (fn [& args] (swap! seen conj (str/join " " args))))
    (let [o (try (outcome f) (finally (set! js/console.warn original)))]
      (assoc o :warned @seen))))

;; ---------------------------------------------------------------------------
;; The `re-frame.ui.events` committed-callback seam
;;
;; `commit!`'s three-argument arity is the documented host-agnostic test seam:
;; it supplies the frame-op bundle directly, so a committed `ui/event` site can
;; be invoked without a React host.
;; ---------------------------------------------------------------------------

(def ^:private inert-frame-ops
  {:frame nil :dispatch (fn [& _]) :dispatch-sync (fn [& _])})

(defn- committed-event-callback
  "Publish one `ui/event` site whose body returns `result`, commit it, and
  return the stable committed callback. Invoking it runs the outcome
  classification in `invoke-site!` — the `:else` arm is the site under test."
  [result]
  (let [owner   (events/make-owner ::cycle-probe)
        cb      (atom nil)
        capture (nth (events/with-capture
                       owner nil
                       (fn []
                         (reset! cb (events/event-handler
                                     "evt-0" (fn [_] result) 0 nil))
                         nil))
                     1)]
    (events/commit! owner capture inert-frame-ops)
    @cb))

;; ---------------------------------------------------------------------------
;; The control
;; ---------------------------------------------------------------------------

(deftest the-fixtures-are-genuinely-cyclic
  (testing "THE NON-VACUITY CONTROL. `pr-str` is what every message site below
           called, and on these values it recurs until the stack blows. If any
           row here ever goes green-by-termination, the fixture has stopped
           being cyclic and the whole file is measuring nothing."
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-object)))))
        "a hand-built self-referential JS object defeats cljs.core/pr-str")
    (is (= "RangeError" (:threw (outcome #(pr-str (self-referential-array)))))
        "and so does a cycle reached through a JS array")
    (is (= "RangeError" (:threw (outcome #(pr-str provider))))
        "and so does a real React 19 context provider")
    (is (= "RangeError" (:threw (outcome #(pr-str [:p provider]))))
        "and so does a MIXED chain — persistent vector, foreign object —
         which is the shape that reaches the guards keyed on `coll?`")
    (is (= "RangeError" (:threw (outcome #(pr-str #js {"held" [:p provider]}))))
        "and so does the other direction of the same mixed chain — foreign
         object, persistent vector, foreign object — because the printer
         crosses between the two freely, which is why the detector has to")
    (is (= "RangeError" (:threw (outcome #(pr-str {:event [:x provider]}))))
        "and so does a handler options map holding one inside its :event")
    (is (identical? provider (.-Provider provider))
        "because React 19's ctx.Provider IS the context object, and that
         object carries a Provider key pointing back at itself — the cycle
         in one line")
    (is (string? (pr-str [:p acyclic-object]))
        "while the ACYCLIC control prints fine, which is what makes it a
         control for the byte-identity rows")))

(deftest string-coercion-cannot-save-the-duplicate-key-sites
  (testing "Both duplicate-key diagnostics coerce the key through
           `rules/js-string-coerce` BEFORE comparing, and that coercion is
           `(str v)` — bounded on a foreign value. So a cyclic key survives
           coercion, two of them COLLIDE at the same coerced string, and the
           diagnostic that fires then prints the raw key. This is why the
           duplicate-key sites are reachable at all."
    (is (string? (str provider))
        "(str provider) is bounded — it does not descend the object graph")
    (is (= (str provider) (str (react/createContext "other")))
        "two distinct foreign values coerce to the SAME key string, so a
         keyed list carrying two of them is a genuine duplicate")))

;; ---------------------------------------------------------------------------
;; re-frame.ui.runtime — the client runtime (all four sites reach a browser)
;; ---------------------------------------------------------------------------

(deftest runtime-slot-rejects-a-cyclic-value-with-its-own-error
  (testing "`slot-ready?` routes anything that is neither nil nor a marked
           `ui/render-fn` to `invalid-slot!`, which prints it. A React
           context provider is exactly such a value and passing one to a
           `ui/slot` is an ordinary authoring mistake."
    (doseq [[label v] [["a provider in a slot"            provider]
                       ["a hand-built cyclic object"      (self-referential-object)]
                       ["a cyclic array"                  (self-referential-array)]
                       ["a mixed chain in a slot"         #js {"held" [:p provider]}]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(runtime/slot-ready? v)))))

(deftest runtime-dynamic-child-rejects-a-cyclic-value-with-its-own-error
  (testing "`child`'s guard is `(or (seq? x) (coll? x) (keyword? x)
           (symbol? x))`, so a RAW foreign object does not reach the throw —
           but a CLJS collection HOLDING one does, and `[:p ctx.Provider]` is
           precisely the mixed chain the shared walker exists for."
    (doseq [[label v] [["a vector holding a provider"     [:p provider]]
                       ["a list holding a provider"       (list provider)]
                       ["a map holding a provider"        {:ctx provider}]
                       ["a seq holding a cyclic array"    (seq [(self-referential-array)])]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(runtime/child v)))))

(deftest runtime-react-export-rejects-a-cyclic-value-with-its-own-error
  (testing "`->react-component`'s guard is `(when-not (or (fn? view)
           (object? view)))`, so a BARE provider passes it (it is an
           `object?`) and never reaches the throw — the bead flagged this
           site as lower-confidence for exactly that reason. Two shapes DO
           reach it: a hiccup vector, which the message itself anticipates
           ('not its id keyword and not a rendered form'), and a JS ARRAY,
           whose constructor is `js/Array` so `object?` is false."
    (doseq [[label v] [["a hiccup vector holding a provider" [:p provider]]
                       ["a cyclic JS array"                  (self-referential-array)]
                       ["a map holding a provider"           {:ctx provider}]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(runtime/->react-component v)))))

(deftest runtime-react-export-still-passes-a-bare-foreign-object
  (testing "The counterpart measurement: a bare `object?` value is NOT
           rejected, so this site's cyclic exposure is the vector / array
           shapes above and nothing else. Pinning it keeps a future widening
           of the guard from silently re-opening an unmeasured crossing."
    (is (nil? (:error-id (outcome #(runtime/->react-component provider))))
        "a bare provider passes the guard rather than reaching the throw")))

(deftest runtime-duplicate-key-warning-survives-a-cyclic-key
  (testing "`check-key!` prints the offending `:key` into a
           `js/console.warn`. The key coerces through `(str v)` — bounded —
           so two cyclic keys collide and the warning fires with the RAW key,
           which is where `pr-str` used to blow the stack."
    (let [k (self-referential-object)
          o (warning-outcome
             (fn [] (let [seen (js-obj)]
                      (runtime/check-key! seen k)
                      (runtime/check-key! seen k))))]
      (is (nil? (:threw o))
          (str "the duplicate-key warning must not overflow; got "
               (pr-str (dissoc o :warned))))
      (is (true? (:returned? o))
          "and `check-key!` still returns its key, warning being a side effect")
      (is (= 1 (count (:warned o)))
          (str "exactly one duplicate warning fires; got " (pr-str (:warned o))))
      (is (str/includes? (first (:warned o)) "duplicate key")
          (str "and it is the duplicate-key warning; got " (pr-str (:warned o))))
      (is (str/includes? (first (:warned o)) "#js {…cyclic…}")
          (str "with the cyclic key replaced by the fixed token; got "
               (pr-str (:warned o)))))))

;; ---------------------------------------------------------------------------
;; re-frame.ui.tree — the JVM-emitter substrate, measured on its CLJS arm
;; ---------------------------------------------------------------------------

(deftest tree-slot-rejects-a-cyclic-value-with-its-own-error
  (testing "The `tree` twin of `runtime/invalid-slot!` — same didactic error,
           same crossing, a different host arm."
    (doseq [[label v] [["a provider in a slot"       provider]
                       ["a cyclic array"             (self-referential-array)]
                       ["a mixed chain in a slot"    #js {"held" [:p provider]}]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(tree/slot-ready? v)))))

(deftest tree-handler-classification-rejects-a-cyclic-value-with-its-own-error
  (testing "`classify-event` reaches its throw for a `v` that is not
           nil / vector / map / fn — i.e. a foreign object in a handler slot."
    (doseq [[label v] [["a provider as a handler"    provider]
                       ["a cyclic array as handler"  (self-referential-array)]
                       ["a hand-built cyclic object" (self-referential-object)]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(tree/classify-event v)))))

(deftest tree-keyed-run-rejects-a-cyclic-key-and-a-cyclic-row
  (testing "`keyed-run` crosses TWICE — the duplicate-key arm prints the raw
           `:key`, and the lost-key arm rides the whole offending ROW out in
           `:extra` without printing it. The second is the ex-data-only half
           of the defect: nothing overflows at the thrower, and then a
           downstream sink `pr-str`s the ex-data and does."
    (rejected-with :rf.error/ui-duplicate-key
                   "two cyclic keys colliding under string coercion"
                   #(tree/keyed-run [{:key provider} {:key provider}]))
    (rejected-with :rf.error/ui-tree-malformed
                   "a foreign object where a keyed row belongs"
                   #(tree/keyed-run [provider]))
    (rejected-with :rf.error/ui-tree-malformed
                   "a cyclic array where a keyed row belongs"
                   #(tree/keyed-run [(self-referential-array)]))))

;; ---------------------------------------------------------------------------
;; re-frame.ui.events — the committed-event vocabulary (client runtime)
;; ---------------------------------------------------------------------------

(deftest event-body-outcome-rejects-a-cyclic-return-with-its-own-error
  (testing "A committed `ui/event` body NAMES its outcome: a vector
           dispatches, nil dispatches nothing, anything else is the didactic
           diagnostic — which prints what the body returned. An author whose
           body returns a foreign value gets that diagnostic, and it used to
           overflow instead."
    (doseq [[label result] [["a body returning a provider"     provider]
                            ["a body returning a cyclic array" (self-referential-array)]
                            ["a body returning a mixed chain"  #js {"held" [:p provider]}]]]
      (let [cb (committed-event-callback result)]
        (rejected-with :rf.error/ui-tree-malformed label #(cb #js {}))))))

(deftest dynamic-handler-rejects-a-cyclic-value-with-its-own-error
  (testing "`dynamic-handler` crosses at THREE arms, and all three throw
           BEFORE `capture-or-throw!` runs, so each is reachable directly."
    ;; the :else arm — a foreign object in a handler position
    (doseq [[label v] [["a provider as a dynamic handler" provider]
                       ["a cyclic array as one"           (self-referential-array)]]]
      (rejected-with :rf.error/ui-tree-malformed label
                     #(events/dynamic-handler "site-0" v nil)))

    ;; the options-map arm — an unknown key, cycle reachable through the map
    (rejected-with :rf.error/ui-tree-malformed
                   "an options map whose unknown key holds a provider"
                   #(events/dynamic-handler "site-0" {:event [:x] :bad provider} nil))

    ;; the capture/passive arm — message carries no value, `:extra` does
    (rejected-with :rf.error/ui-tree-malformed
                   "a :capture options map whose :event holds a provider"
                   #(events/dynamic-handler
                     "site-0" {:event [:x provider] :capture true} nil))))

;; ---------------------------------------------------------------------------
;; The acyclic path, byte for byte
;; ---------------------------------------------------------------------------

(deftest an-acyclic-diagnostic-is-byte-identical
  (testing "The expectation embeds `cljs.core/pr-str`'s OWN output, so these
           rows fail the moment a message stops being what `pr-str` produced
           before this fix existed. The acyclic foreign object keeps its
           CONTENTS in the message — eliding every foreign value (what the
           hash walk does, and rightly) would have cost the diagnostic exactly
           the information it exists to carry."
    ;; runtime/invalid-slot!
    (let [o (outcome #(runtime/slot-ready? acyclic-object))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "a ui/slot received " (pr-str acyclic-object)))
          (str "slot value printed byte-identically to pr-str; got "
               (pr-str (:message o)))))

    ;; runtime/child
    (let [v [:p acyclic-object]
          o (outcome #(runtime/child v))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "a dynamic child produced " (pr-str v)))
          (str "child printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; runtime/->react-component
    (let [v [:p acyclic-object]
          o (outcome #(runtime/->react-component v))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "but received " (pr-str v)))
          (str "view printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; runtime/check-key!
    (let [o (warning-outcome
             (fn [] (let [seen (js-obj)]
                      (runtime/check-key! seen acyclic-object)
                      (runtime/check-key! seen acyclic-object))))]
      (is (str/includes? (first (:warned o)) (pr-str acyclic-object))
          (str "key printed byte-identically to pr-str; got " (pr-str (:warned o)))))

    ;; tree/invalid-slot!
    (let [o (outcome #(tree/slot-ready? acyclic-object))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "a ui/slot received " (pr-str acyclic-object)))
          (str "slot value printed byte-identically to pr-str; got "
               (pr-str (:message o)))))

    ;; tree/classify-event
    (let [o (outcome #(tree/classify-event acyclic-object))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o)
                         (str "a dynamic handler expression produced "
                              (pr-str acyclic-object)))
          (str "handler printed byte-identically to pr-str; got "
               (pr-str (:message o)))))

    ;; tree/keyed-run, the duplicate-key arm
    (let [o (outcome #(tree/keyed-run [{:key acyclic-object} {:key acyclic-object}]))]
      (is (= :rf.error/ui-duplicate-key (:error-id o)))
      (is (str/includes? (:message o) (str "duplicate key " (pr-str acyclic-object)))
          (str "key printed byte-identically to pr-str; got " (pr-str (:message o)))))

    ;; events, the ui/event body outcome
    (let [cb (committed-event-callback acyclic-object)
          o  (outcome #(cb #js {}))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o)
                         (str "a (ui/event …) body produced " (pr-str acyclic-object)))
          (str "result printed byte-identically to pr-str; got "
               (pr-str (:message o)))))

    ;; events/dynamic-handler, the :else arm
    (let [o (outcome #(events/dynamic-handler "site-0" acyclic-object nil))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o)
                         (str "a dynamic handler expression produced "
                              (pr-str acyclic-object)))
          (str "handler printed byte-identically to pr-str; got "
               (pr-str (:message o)))))

    ;; events/dynamic-handler, the options-map arm
    (let [v {:event [:x] :bad acyclic-object}
          o (outcome #(events/dynamic-handler "site-0" v nil))]
      (is (= :rf.error/ui-tree-malformed (:error-id o)))
      (is (str/includes? (:message o) (str "got " (pr-str v)))
          (str "options map printed byte-identically to pr-str; got "
               (pr-str (:message o)))))))

(deftest the-ex-data-value-slot-survives-a-downstream-sink
  (testing "`:value` is the slot a tool reads to see the offending value. It
           must still BE that value when the value is printable, and must be
           printable when it is not — the two halves of the ex-data crossing."
    (let [data (try (runtime/slot-ready? acyclic-object) nil
                    (catch :default e (ex-data e)))]
      (is (identical? acyclic-object (:value data))
          "an acyclic value rides out IDENTICAL — safe-form returned its input"))

    (let [data (try (runtime/slot-ready? provider) nil
                    (catch :default e (ex-data e)))]
      (is (string? (pr-str data))
          "a cyclic value's ex-data is printable at a downstream sink")
      (is (str/includes? (pr-str (:value data)) "#js {…cyclic…}")
          "and the cycle inside it was replaced by the fixed token"))

    (let [v    [:p provider]
          data (try (runtime/child v) nil
                    (catch :default e (ex-data e)))]
      (is (string? (pr-str data))
          "a mixed chain's ex-data is printable too")
      (is (str/includes? (pr-str (:value data)) "#js {…cyclic…}")
          "with the cycle elided INSIDE the surviving vector")
      (is (str/includes? (pr-str (:value data)) ":p")
          "and the acyclic part of the same value still readable"))

    (let [data (try (tree/keyed-run [provider]) nil
                    (catch :default e (ex-data e)))]
      (is (string? (pr-str data))
          "the lost-key arm's `:row` survives a downstream sink — the
           ex-data-only crossing, where nothing overflowed at the thrower"))

    (let [data (try (events/dynamic-handler
                     "site-0" {:event [:x provider] :capture true} nil)
                    nil
                    (catch :default e (ex-data e)))]
      (is (string? (pr-str data))
          "and so does the capture/passive arm, whose message never printed
           the value at all"))))

;; ---------------------------------------------------------------------------
;; The success paths are untouched
;; ---------------------------------------------------------------------------

(deftest the-valid-paths-still-work
  (testing "The crossing is on the FAILURE path only — nothing about a valid
           slot, child, handler or keyed run moves."
    (is (false? (runtime/slot-ready? nil)))
    (is (true? (runtime/slot-ready? (runtime/render-fn (fn []) 0))))
    (is (= "hi" (runtime/child "hi")))
    (is (= 42 (runtime/child 42)))
    (is (false? (tree/slot-ready? nil)))
    (is (true? (tree/slot-ready? (tree/render-fn (fn []) 0))))
    (is (= [:evt 1] (tree/classify-event [:evt 1])))
    (is (nil? (tree/classify-event nil)))
    (is (= 2 (count (tree/keyed-run [{:key "a"} {:key "b"}]))))
    (is (fn? (committed-event-callback nil))
        "a body returning nil is the no-dispatch case, not a diagnostic")))
