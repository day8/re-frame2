(ns re-frame.bench.hicasso.walk-vs-reagent-app
  "OUR WALK, PUT BESIDE REAGENT'S OWN (rf2-2rtt6.63).

  The mount deficit's attribution chain ends at the runtime hiccup
  interpreter, and stock Reagent is the existence proof that a runtime
  interpreter need not be dear. `rf2-y1jkm` profiled OUR walk against a
  frozen copy of OUR OWN older walk; it never put the two interpreters
  side by side. This entry does, on ONE witness value, in ONE process:

  | arm | walks | input |
  |---|---|---|
  | `hicasso` | `front.codec/as-element` | the plain witness |
  | `slim` | `reagent2.impl.template/as-element` | the plain witness |
  | `reagent` | `reagent.impl.template/as-element` (stock 2.0.1) | the plain witness |
  | `hicasso-native` | `front.codec/as-element` | the page's OWN markup (intent vectors) |

  ## The witness, and why it is link-term free

  The page is `walk_profile_app`'s twin of the acceptance shape — the
  same 1,202-element census page, guarded by that file's own fatal
  canonical-DOM parity gate against the real `lt/page`, so there is one
  twin in the lane and not two. It is **realized once, outside every
  timed window** (`codec/realize-deep`), which is what makes these rows
  immune to the route-link render term `rf2-cno31` is fixing: every
  `route-link` href on the page is synthesised during realisation and is
  a plain string by the time any arm walks it. No timed window here
  contains a `route-url` call.

  **THE PLAIN WITNESS.** Hicasso's markup carries intent VECTORS at its
  71 event positions; Reagent has no such surface and would `clj->js`
  them. An arm doing different work is not an arm (`rf2-2rtt6.62`), so
  the compared value is the realized page with every event position
  replaced by one shared plain function — legal, and identical work, for
  all three interpreters. `hicasso-native` then prices what Hicasso's own
  authoring surface costs ON TOP of that, so the intent term is named
  rather than hidden inside the comparison.

  ## Workload matching is gated, not asserted

  Every arm's element tree is rendered into a fresh container and its
  canonical DOM read (`lane/canonical`, attribute names sorted). The
  three canonicals and the three element counts are reported beside the
  rows: a decomposition of arms whose OUTPUT differs would be a
  decomposition of nothing.

  ## DIAGNOSTIC, not published

  In-page `performance.now` over K whole-page walks per sample. It
  attributes cost BETWEEN interpreters and BETWEEN stages of one walk; it
  is not the clock of record and no figure here is a gate row. Stated per
  the instrument canon so a ratio here cannot be mistaken for a gated
  one.

  ## The three tables

  1. ARMS — ms per whole-page walk, interleaved rounds, min/p50/max, the
     arm-order guard adjudicating, plus ns/element.
  2. STAGES — the same primitive, ours and Reagent's, over the page's own
     literal roster: tag lookup, prop-name lookup, value conversion, the
     whole per-element prop pipeline, the per-element tag hook
     (`controlled/install!` against `input/input-component?`), and child
     dispatch. **Absolute ns/element**, because two-thirds of a mount
     window is shared frame work and a ratio cannot be read against it.
  3. CANDIDATES — costed BEFORE anything is landed. Each is a shape the
     stage table convicts, written here beside the shipping shape and
     timed against it in the same process on the same roster.

  Owner bead: rf2-2rtt6.63. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.walk-vs-reagent-app/-main."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as arm1-mount]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.walk-profile-app :as wp]
            [re-frame.core :as rf]
            [reagent.impl.input :as rinput]
            [reagent.impl.protocols :as rp]
            [reagent.impl.template :as rtpl]
            [reagent2.impl.template :as slim]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

;; ---------------------------------------------------------------------------
;; The plain witness
;; ---------------------------------------------------------------------------

(defn- noop
  "The one handler every event position on the plain witness carries.
  Shared by identity, so no arm pays a distinct allocation for it."
  [_e]
  nil)

(defn- plain-props
  "`m` with every EVENT position's value replaced by [[noop]]. Returns
  `m` itself, by identity, when it holds no event position — which is
  most of the page, so the plain witness shares structure with the
  native one everywhere it can."
  [m]
  (reduce-kv (fn [acc k v]
               (if (and (keyword? k) (intent/event-prop? k))
                 (assoc acc k noop)
                 acc))
             m
             m))

(defn- plainify
  "The realized page with every event position plainified. Hiccup vectors
  are rebuilt (metadata preserved), seqs stay seqs and are realized, and
  everything else comes back by identity."
  [x]
  (cond
    (vector? x)
    (let [head       (nth x 0 nil)
          has-props? (map? (nth x 1 nil))
          head'      (if has-props?
                       [head (plain-props (nth x 1))]
                       [head])]
      (with-meta
        (into head' (map plainify) (subvec x (if has-props? 2 1)))
        (meta x)))

    (seq? x) (doall (map plainify x))
    :else    x))

;; ---------------------------------------------------------------------------
;; Workload matching — one gate, three arms
;; ---------------------------------------------------------------------------

(defn- render-canonical
  "Render one already-built React element into a fresh container and
  answer `[canonical-dom element-count]`. The tree is a value, so this
  runs no interpreter and belongs to no timed window."
  [el]
  (let [c    (arm1-mount/fresh-container!)
        root (react-dom-client/createRoot c)]
    (react-dom/flushSync (fn [] (.render root el)))
    (let [canon (lane/canonical c)
          n     (lane/element-count c)]
      (react-dom/flushSync (fn [] (.unmount root)))
      (.remove c)
      [canon n])))

(defn- first-divergence
  "Index of the first differing character of two canonical strings, with
  40 characters of context from each — the shape a reader can act on."
  [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (cond
        (= i n)              (if (= (count a) (count b))
                               nil
                               {:at i :ours (subs a i (min (count a) (+ i 40)))
                                :theirs (subs b i (min (count b) (+ i 40)))})
        (= (nth a i) (nth b i)) (recur (inc i))
        :else {:at     i
               :ours   (subs a i (min (count a) (+ i 40)))
               :theirs (subs b i (min (count b) (+ i 40)))}))))

;; ---------------------------------------------------------------------------
;; The roster — the page's own literals, weighted as the page uses them
;; ---------------------------------------------------------------------------

(defn- collect-roster
  "Every native tag occurrence, every prop-key occurrence, every prop
  value occurrence, every string child, and the per-element `[tag props]`
  pairs the prop pipeline is asked for. JS arrays, so the micro loops
  index primitively."
  [h]
  (let [tags #js [] ks #js [] vs #js [] strs #js []
        el-tags #js [] el-props #js []]
    (letfn [(visit-child [c]
              (cond (vector? c) (visit c)
                    (string? c) (.push strs c)
                    (seq? c)    (run! visit-child c)
                    :else       nil))
            (visit [argv]
              (let [head       (nth argv 0 nil)
                    has-props? (map? (nth argv 1 nil))
                    props      (when has-props? (nth argv 1))]
                (when (and (or (keyword? head) (symbol? head) (string? head))
                           (not= :<> head))
                  (.push tags head)
                  (.push el-tags head)
                  (.push el-props (if has-props? props nil)))
                (when props
                  (doseq [[k v] props]
                    (.push ks k)
                    (.push vs v)))
                (doseq [c (subvec argv (if has-props? 2 1))]
                  (visit-child c))))]
      (visit h))
    {:tags tags :prop-keys ks :prop-vals vs :strings strs
     :el-tags el-tags :el-props el-props}))

;; ---------------------------------------------------------------------------
;; The timed arms
;; ---------------------------------------------------------------------------

(def ^:private walks-per-sample
  "Whole-page walks inside ONE timing window. Chrome clamps
  `performance.now` to 100 µs; eight ~1,200-element walks hold the window
  in whole milliseconds, so the clamp is percent-level noise."
  8)

(defn- timed-walks
  "One sample: K walks of one already-realized witness under one clock.
  Answers ms for the window."
  [witness walk-one]
  (let [t0 (lane/now-ms)]
    (dotimes [_ walks-per-sample]
      (walk-one witness))
    (- (lane/now-ms) t0)))

(def ^:private sampling {:warmup 4 :samples 10})
(def ^:private rounds 6)

;; ---------------------------------------------------------------------------
;; Stage micro-benches
;; ---------------------------------------------------------------------------

(defn- ns-per-op
  "Loop `f` over `arr` `reps` times; answer ns per element visit. A
  volatile sink defeats dead-code elimination."
  [reps ^js arr f]
  (let [sink (volatile! nil)
        n    (.-length arr)
        t0   (lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget arr i)))))
    (let [ms (- (lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(defn- ns-per-op2
  "[[ns-per-op]] over two parallel arrays — the `[tag props]` pairs the
  prop pipeline takes."
  [reps ^js a ^js b f]
  (let [sink (volatile! nil)
        n    (.-length a)
        t0   (lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget a i) (aget b i)))))
    (let [ms (- (lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(def ^:private micro-reps 200)

(defn- stage-table
  "The same primitive, ours and each donor's, over the page's own roster.
  Every figure ns/op; the element-level rows are ns/ELEMENT."
  [{:keys [^js tags ^js prop-keys ^js prop-vals ^js strings ^js el-tags ^js el-props]}]
  (let [;; Both prop pipelines want a parsed tag. Precomputed here so the
        ;; rows price the PIPELINE and not two different tag lookups.
        h-parsed (let [a #js []]
                   (dotimes [i (.-length el-tags)]
                     (.push a (codec/cached-parse (aget el-tags i))))
                   a)
        r-parsed (let [a #js []]
                   (dotimes [i (.-length el-tags)]
                     (.push a (rtpl/cached-parse nil (name (aget el-tags i)) (aget el-tags i))))
                   a)
        tag-strs (let [a #js []]
                   (dotimes [i (.-length el-tags)]
                     (.push a (.-tag ^js (aget h-parsed i))))
                   a)]
    [;; --- tag lookup -------------------------------------------------
     [:tag-lookup-hicasso (ns-per-op micro-reps tags (fn [t] (codec/cached-parse t)))]
     [:tag-lookup-reagent (ns-per-op micro-reps tags
                                     (fn [t] (rtpl/cached-parse nil (name t) t)))]
     ;; --- prop-name lookup -------------------------------------------
     [:prop-name-hicasso (ns-per-op micro-reps prop-keys (fn [k] (codec/cached-prop-name k)))]
     [:prop-name-slim    (ns-per-op micro-reps prop-keys (fn [k] (slim/cached-prop-name k)))]
     [:prop-name-reagent (ns-per-op micro-reps prop-keys (fn [k] (rtpl/cached-prop-name k)))]
     ;; --- value conversion -------------------------------------------
     [:prop-value-hicasso (ns-per-op micro-reps prop-vals (fn [v] (codec/convert-prop-value v)))]
     [:prop-value-slim    (ns-per-op micro-reps prop-vals (fn [v] (slim/convert-prop-value v)))]
     [:prop-value-reagent (ns-per-op micro-reps prop-vals (fn [v] (rtpl/convert-prop-value v)))]
     ;; --- the whole per-element prop pipeline -------------------------
     [:convert-props-hicasso
      (ns-per-op2 micro-reps el-props h-parsed (fn [p ^js t] (codec/convert-props p t)))]
     [:convert-props-reagent
      (ns-per-op2 micro-reps el-props r-parsed (fn [p ^js t] (rtpl/convert-props p t)))]
     ;; --- the per-element tag hook ------------------------------------
     [:tag-hook-hicasso (ns-per-op micro-reps tag-strs
                                   (fn [t] (controlled/install! t #js {})))]
     [:tag-hook-reagent (ns-per-op micro-reps tag-strs
                                   (fn [t] (rinput/input-component? t)))]
     ;; --- child dispatch ----------------------------------------------
     [:string-child-hicasso (ns-per-op micro-reps strings (fn [s] (codec/as-element s)))]
     [:string-child-slim    (ns-per-op micro-reps strings (fn [s] (slim/as-element s)))]
     [:string-child-reagent (ns-per-op micro-reps strings
                                       (fn [s] (rp/as-element rtpl/class-compiler s)))]
     ;; --- the shared floor --------------------------------------------
     [:create-element-shared
      (let [p #js {:className "x"}]
        (ns-per-op (quot micro-reps 4) tag-strs (fn [t] (react/createElement t p))))]
     ;; --- the per-element `:key` read the walk pays outside the loop ---
     [:key-read-hicasso (ns-per-op micro-reps el-props (fn [p] (:key p)))]
     [:roster-elements (.-length el-tags)]]))

;; ---------------------------------------------------------------------------
;; Candidates — costed here, landed only if this table convicts
;; ---------------------------------------------------------------------------
;;
;; The shipping cache lookup is a three-step: `reserved-name?` (three
;; `===` compares against the JS prototype-poisoning roster), `own-key?`
;; (`Object.prototype.hasOwnProperty.call`), then `unchecked-get`. All
;; three exist because the caches are `#js {}` objects and therefore carry
;; `Object.prototype`: a literal named `__proto__` would poison a write,
;; and an inherited name (`toString`) would falsely hit a read.
;;
;; A cache made with `Object.create(null)` has NO prototype chain at all.
;; `__proto__` on it is an ordinary own property with no setter to invoke,
;; and a lookup can only ever answer an own property — so the whole guard
;; collapses to one `unchecked-get` and an `undefined?` test, and it is
;; STRICTLY SAFER than the guarded version rather than a relaxation of it.
;; The arms below price that collapse over the page's own key roster
;; before anything is landed in the codec.

(defn- mint-name
  "The candidate arms cache a NAME, which is enough to price the lookup
  shape; the shipping cache holds a `PropSlot` whose extra fields are
  minted by the same miss path either way."
  [k]
  (codec/prop-name k))

(def ^:private guarded-cache #js {})
(def ^:private nullproto-cache (js/Object.create nil))
(def ^:private has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- reserved-name? [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

(defn- guarded-lookup
  "The shipping lookup shape, written here so both arms are local."
  [k]
  (let [n (name k)]
    (if (reserved-name? n)
      (mint-name k)
      (if (.call has-own guarded-cache n)
        (unchecked-get guarded-cache n)
        (let [v (mint-name k)]
          (unchecked-set guarded-cache n v)
          v)))))

(defn- nullproto-lookup
  "The candidate: one `unchecked-get` on a prototype-less cache."
  [k]
  (let [n (name k)
        v (unchecked-get nullproto-cache n)]
    (if (undefined? v)
      (let [v' (mint-name k)]
        (unchecked-set nullproto-cache n v')
        v')
      v)))

(def ^:private guarded-tag-cache #js {})
(def ^:private nullproto-tag-cache (js/Object.create nil))

(defn- tag-cache-key [k]
  (if-let [ns' (namespace k)] (str ns' "/" (name k)) (name k)))

(defn- guarded-tag-lookup [t]
  (let [k (tag-cache-key t)]
    (if (reserved-name? k)
      (codec/parse-tag t)
      (if (.call has-own guarded-tag-cache k)
        (unchecked-get guarded-tag-cache k)
        (let [v (codec/parse-tag t)]
          (unchecked-set guarded-tag-cache k v)
          v)))))

(defn- nullproto-tag-lookup [t]
  (let [k (tag-cache-key t)
        v (unchecked-get nullproto-tag-cache k)]
    (if (undefined? v)
      (let [v' (codec/parse-tag t)]
        (unchecked-set nullproto-tag-cache k v')
        v')
      v)))

(defn- candidate-table
  [{:keys [^js tags ^js prop-keys]}]
  ;; Warm both caches before timing: a lookup arm must measure hits.
  (dotimes [i (.-length prop-keys)]
    (guarded-lookup (aget prop-keys i))
    (nullproto-lookup (aget prop-keys i)))
  (dotimes [i (.-length tags)]
    (guarded-tag-lookup (aget tags i))
    (nullproto-tag-lookup (aget tags i)))
  [[:prop-lookup-guarded   (ns-per-op micro-reps prop-keys guarded-lookup)]
   [:prop-lookup-nullproto (ns-per-op micro-reps prop-keys nullproto-lookup)]
   [:tag-lookup-guarded    (ns-per-op micro-reps tags guarded-tag-lookup)]
   [:tag-lookup-nullproto  (ns-per-op micro-reps tags nullproto-tag-lookup)]])

(defn- candidate-agreement
  "The candidate answers what the shipping shape answers, for every
  literal on the page — checked, not asserted, before any figure is
  read."
  [{:keys [^js tags ^js prop-keys]}]
  (let [bad (atom [])]
    (dotimes [i (.-length prop-keys)]
      (let [k (aget prop-keys i)]
        (when-not (= (guarded-lookup k) (nullproto-lookup k))
          (swap! bad conj [:prop k]))))
    (dotimes [i (.-length tags)]
      (let [t   (aget tags i)
            ^js a (guarded-tag-lookup t)
            ^js b (nullproto-tag-lookup t)]
        (when-not (and (= (.-tag a) (.-tag b))
                       (= (.-id a) (.-id b))
                       (= (.-className a) (.-className b)))
          (swap! bad conj [:tag t]))))
    ;; The poisoning literals themselves, which the page does not carry.
    (doseq [n ["__proto__" "prototype" "constructor" "toString" "hasOwnProperty"]]
      (let [k (keyword n)]
        (when-not (= (guarded-lookup k) (nullproto-lookup k))
          (swap! bad conj [:hostile k]))))
    @bad))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- arm-rows [arms readings]
  (into {}
        (map (fn [id]
               (let [xs       (mapcat #(get % id) readings)
                     per-walk (mapv #(/ % walks-per-sample) xs)]
                 [id (lane/summarise per-walk)])))
        (map :id arms)))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (lt/make-frame! wp/frame-id)
          (lt/reseed! wp/frame-id)
          (let [{:keys [elements bytes]} (wp/parity!)]
            (js/console.log (str ";; twin parity OK — " elements " elements, "
                                 bytes " canonical bytes, identical to lt/page"))
            (let [native  (wp/in-body (fn [] (codec/realize-deep (wp/page-hiccup))))
                  plain   (plainify native)
                  roster  (collect-roster plain)
                  arms    [{:id :hicasso        :witness plain
                            :walk (fn [h] (codec/as-element h))}
                           {:id :slim           :witness plain
                            :walk (fn [h] (slim/as-element h))}
                           {:id :reagent        :witness plain
                            :walk (fn [h] (rp/as-element rtpl/class-compiler h))}
                           {:id :hicasso-native :witness native
                            :walk (fn [h] (codec/as-element h))}]
                  ;; ---- workload matching, before any figure ------------
                  canons  (into {}
                                (map (fn [{:keys [id witness walk]}]
                                       [id (render-canonical (walk witness))]))
                                arms)
                  ref-canon (first (get canons :hicasso))
                  parity  (into {}
                                (map (fn [[id [canon n]]]
                                       [id {:elements n
                                            :bytes    (count canon)
                                            :same?    (= canon ref-canon)
                                            :diverges (when-not (= canon ref-canon)
                                                        (first-divergence ref-canon canon))}]))
                                canons)
                  ;; ---- the interleaved rounds --------------------------
                  {:keys [readings samples]}
                  (lane/rounds! arms sampling rounds
                                (fn [{:keys [witness walk]}] (timed-walks witness walk)))
                  rows    (arm-rows arms readings)
                  gv      (lane/guard! samples "walk-vs-reagent arms (in-page ms, diagnostic)")
                  stages  (stage-table roster)
                  cand-bad (candidate-agreement roster)
                  cands   (candidate-table roster)
                  hic     (:p50 (get rows :hicasso))
                  rgt     (:p50 (get rows :reagent))
                  slm     (:p50 (get rows :slim))]

              (lane/record! :walk-vs-reagent-parity parity)
              (lane/record! :walk-vs-reagent-arms
                            (into {} (map (fn [[k v]]
                                            [k (-> v (update :min lane/round4)
                                                   (update :max lane/round4)
                                                   (update :p50 lane/round4))]))
                                  rows))
              (lane/record! :walk-vs-reagent-stages
                            (into {} (map (fn [[k v]] [k (lane/round4 v)])) stages))
              (lane/record! :walk-vs-reagent-candidates
                            {:disagreements cand-bad
                             :ns-per-op (into {} (map (fn [[k v]] [k (lane/round4 v)])) cands)})

              (js/console.log ";; ==== WORKLOAD MATCH (every arm's own DOM, canonical) ====")
              (doseq [{:keys [id]} arms]
                (let [p (get parity id)]
                  (js/console.log (str ";;   " (name id) ": " (:elements p) " elements, "
                                       (:bytes p) " canonical bytes, "
                                       (if (:same? p) "IDENTICAL to hicasso"
                                           (str "DIFFERS — " (pr-str (:diverges p))))))))

              (js/console.log ";; ==== ARMS (ms per whole-page walk; diagnostic in-page clock) ====")
              (js/console.log (str ";;   elements " elements
                                   "  walks/sample " walks-per-sample
                                   "  design " rounds "x(" (:warmup sampling) "+"
                                   (:samples sampling) ")"))
              (doseq [{:keys [id]} arms]
                (let [{:keys [p50 min max]} (get rows id)]
                  (js/console.log (str ";;   " (name id) ": p50 " (fmt p50 4)
                                       " [" (fmt min 4) " - " (fmt max 4) "] ms/walk  ("
                                       (fmt (* 1e6 (/ p50 elements)) 0) " ns/el)"))))
              (js/console.log (str ";;   hicasso/reagent " (fmt (/ hic rgt) 4)
                                   "   hicasso/slim " (fmt (/ hic slm) 4)
                                   "   slim/reagent " (fmt (/ slm rgt) 4)))
              (js/console.log (str ";;   ABSOLUTE per-element delta vs reagent: "
                                   (fmt (* 1e6 (/ (- hic rgt) elements)) 1) " ns/el"))

              (js/console.log ";; ==== STAGES (ns/op over the page's own roster) ====")
              (doseq [[k v] stages]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1))))

              (js/console.log ";; ==== CANDIDATES (costed, not landed) ====")
              (js/console.log (str ";;   agreement failures: " (pr-str cand-bad)))
              (doseq [[k v] cands]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns/op")))

              (when (:refuse? gv)
                (set! (.-HICASSO_GUARD_REFUSED js/window) true))
              (lane/done!)))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
