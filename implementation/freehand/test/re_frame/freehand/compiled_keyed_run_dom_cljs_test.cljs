(ns re-frame.freehand.compiled-keyed-run-dom-cljs-test
  "The compiled tier's DUPLICATE-KEY proof, in a real browser — and the one
  class of key that only the browser lowering could ever have got wrong.

  The structural tier proves a list site's keys with a Clojure set
  (`node/keyed-run`). The compiled browser lowering has to prove the same
  thing per row, at the site, with no Clojure collection in the emitted
  code — and that is where a JS object used as a map is a trap: an object
  inherits `toString`, `constructor`, `hasOwnProperty` and `__proto__` from
  `Object.prototype`, so a membership test that asks `key in obj` answers
  YES for a key nothing has put there. The FIRST row of a list keyed by any
  of those names would then be refused as a duplicate.

  Those are ordinary domain identifiers — a permissions table keyed by verb,
  a column set keyed by field name, an object graph keyed by property. A
  view keyed on them renders perfectly under `node/keyed-run`, renders
  perfectly in every structural test, and dies when someone mounts it. So
  the assertions here are the ones the structural tier cannot make: the
  poison names accepted at a real compiled `for` site in a real browser, a
  repeat of each still refused there, and the two tiers agreeing row for
  row.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly — the declarations
  themselves still load, and the cross-tier agreement row still runs."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand :as v]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.react :as fr]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fr/reset-boundaries!)
                      (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; The poison roster. Every one of these is an inherited `Object.prototype`
;; name AND a plausible domain key — which is the entire reason this file
;; exists rather than a note in a docstring.
;; ---------------------------------------------------------------------------

(def ^:private inherited-names
  ["toString" "constructor" "hasOwnProperty" "__proto__"
   "valueOf" "isPrototypeOf" "propertyIsEnumerable" "toLocaleString"])

;; ---------------------------------------------------------------------------
;; The declarations. Module-level, because `{:compiled true}` is a
;; macro-expansion fact and a declaration cannot close over a test's locals —
;; so this is exactly the `for` site a real application writes.
;; ---------------------------------------------------------------------------

(v/defview poison-list
  "A compiled keyed list site whose row keys come from data. The `for` is
  the emitted duplicate-key check's only home."
  {:compiled true}
  [{:keys [ids]}]
  [:ul#poison
   (for [k ids]
     [:li {:key k :data-key (str k)} (str k)])])

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- rendered-keys
  "The `data-key` of every row the list site actually put on screen, in
  document order."
  [container]
  (mapv #(.getAttribute % "data-key")
        (array-seq (.querySelectorAll container "#poison > li"))))

(defn- render-ids!
  "Mount the compiled list over `ids` under a real error boundary, and
  answer a promise of `{:keys [...] :error <thrown or nil>}`.

  The boundary is what keeps a refusal from reaching the window: a render
  throw with nothing above it re-throws at the host and fails the whole
  browser run. The always-on egress carries the OPAQUE exception, so the
  refusal can be named by its own diagnostic id rather than by the fact
  that something, somewhere, went wrong."
  [ids]
  (let [[container root] (mount!)
        egress           (atom [])]
    (error-emit/register-error-listener! ::recorder (fn [r] (swap! egress conj r)))
    (-> (act #(.render root
                       (fr/element
                         [v/error-boundary {:fallback [:p#fb "refused"] :reset-key 1}
                          [poison-list {:ids ids}]])))
        (.then (fn [_]
                 (let [out {:keys  (rendered-keys container)
                            :error (:exception (first @egress))
                            :fallback? (some? (.querySelector container "#fb"))}]
                   (error-emit/unregister-error-listener! ::recorder)
                   (teardown! container root)
                   out))
               (fn [e]
                 (error-emit/unregister-error-listener! ::recorder)
                 (teardown! container root)
                 (js/Promise.reject e))))))

(defn- refusal-id [error*]
  (:rf.error/id (ex-data error*)))

;; ===========================================================================
;; The first occurrence of an inherited name is a key like any other
;; ===========================================================================

(deftest an-inherited-object-name-is-a-legal-first-key-at-a-compiled-list-site
  (testing "A compiled `for` site keyed by `toString`, `constructor`,
            `hasOwnProperty`, `__proto__` and their siblings mounts and
            renders every row. Nothing has put those keys in the seen table
            — they are inherited from `Object.prototype`, and only a seen
            table that is an object would ever say otherwise. A test using
            ordinary keys cannot see this at all: the defect fires only on
            names an object already answers for."
    (if-not (browser?)
      (skip! "the browser job owns the mounted keyed-run assertions")
      (async done
        (-> (render-ids! inherited-names)
            (.then (fn [{:keys [keys error fallback?]}]
                     (is (nil? error)
                         (str "no row was refused. Saw: "
                              (pr-str (some-> error refusal-id))))
                     (is (false? fallback?)
                         "so the boundary's fallback is not on screen")
                     (is (= inherited-names keys)
                         "and every poison-keyed row rendered, in order")))
            ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
            ;; the whole remainder of the run synchronously, so a `.catch`
            ;; downstream of it would claim a later namespace's throw as this
            ;; row's and fire `done` a second time.
            (.catch (fn [e] (is false (str "the poison-keyed mount rejected: " e)) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; A repeat of one is still a duplicate
;; ===========================================================================

(deftest a-repeated-inherited-name-is-still-refused-at-the-list-site
  (testing "The correction is a prototype-free seen table, not a hole in the
            rule. The SECOND occurrence of an inherited name is a duplicate
            key and the site refuses it by its own diagnostic id — not by
            some generic failure, and not at the wrong end of the pipeline as
            a React console warning."
    (if-not (browser?)
      (skip! "the browser job owns the mounted keyed-run assertions")
      (async done
        (letfn [(step [remaining]
                  (if-some [nm (first remaining)]
                    (-> (render-ids! [nm nm])
                        (.then (fn [{:keys [error fallback?]}]
                                 (is (= :rf.error/ui-duplicate-key (refusal-id error))
                                     (str "a repeated " (pr-str nm)
                                          " is refused as a duplicate key. Saw: "
                                          (pr-str (refusal-id error))))
                                 (is (true? fallback?)
                                     (str "and the boundary contained it — " nm))
                                 (step (rest remaining)))))
                    (js/Promise.resolve nil)))]
          (-> (step inherited-names)
              ;; The rejection handler sits UPSTREAM of the single trailing
              ;; `done` (rf2-qpns): `done` runs the whole remainder of the run
              ;; synchronously, so a `.catch` after it claims a foreign throw
              ;; as this row's and fires `done` a second time.
              (.catch (fn [e]
                        (is false (str "the duplicate sweep rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; React's string coercion is still the comparison
;; ===========================================================================

(deftest react-string-equivalent-keys-still-collide-at-a-compiled-list-site
  (testing "Keys compare after React's own string coercion, so `1` and `\"1\"`
            are ONE key. A prototype-free table must not become a table that
            compares raw values: the structural tier's rule is the rule."
    (if-not (browser?)
      (skip! "the browser job owns the mounted keyed-run assertions")
      (async done
        (-> (render-ids! [1 "1"])
            (.then (fn [{:keys [error]}]
                     (is (= :rf.error/ui-duplicate-key (refusal-id error))
                         "1 and \"1\" are one key at the compiled site")
                     (render-ids! [1 2 "3"])))
            (.then (fn [{:keys [keys error]}]
                     (is (nil? error)
                         "non-vacuous: keys that differ AFTER coercion do not collide")
                     (is (= ["1" "2" "3"] keys) "and all three rendered")))
            ;; Reports and RELEASES, as above.
            (.catch (fn [e] (is false (str "the coercion sweep rejected: " e)) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; The two tiers agree, row for row (runs on every host)
;; ===========================================================================

(deftest the-structural-tier-reaches-the-same-verdict-on-every-poison-key
  (testing "Agreement is the point. `node/keyed-run` accepts each inherited
            name as a first key and refuses a repeat of it with the same
            diagnostic id the compiled site raises — so a view that renders
            structurally renders in a browser, which is exactly what the
            object-as-map seen table broke."
    (is (some? (node/keyed-run (mapv (fn [k] {:key k}) inherited-names)))
        "the structural tier accepts the whole poison roster")
    (doseq [nm inherited-names]
      (let [thrown (try
                     (node/keyed-run [{:key nm} {:key nm}])
                     nil
                     (catch :default e (:rf.error/id (ex-data e))))]
        (is (= :rf.error/ui-duplicate-key thrown)
            (str "and refuses a repeated " (pr-str nm) " by the same id"))))))
