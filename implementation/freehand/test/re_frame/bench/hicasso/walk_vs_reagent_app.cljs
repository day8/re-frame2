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
  (reduce-kv (fn [acc k _v]
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
  (let [tags #js [] ks #js [] vs #js [] strs #js [] kids #js []
        el-tags #js [] el-props #js []]
    (letfn [(visit-child [c]
              (.push kids c)
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
    {:tags tags :prop-keys ks :prop-vals vs :strings strs :children kids
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
  Answers ms for the window.

  **Every arm runs inside the body door**, including the two that do not
  need it. Hicasso's intent lowering refuses to run with no ambient
  dispatch (`front.intent/require-dispatch`), so the native arm has no
  choice; running the donors inside the same door keeps the four arms on
  ONE call convention, which is the confound `rf2-2rtt6.32` recorded and
  the only reason that measurement's error was caught. The clock starts
  INSIDE the door, so the door itself is never in the window."
  [witness walk-one]
  (wp/in-body
    (fn []
      (let [t0 (lane/now-ms)]
        (dotimes [_ walks-per-sample]
          (walk-one witness))
        (- (lane/now-ms) t0)))))

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
  [{:keys [^js tags ^js prop-keys ^js prop-vals ^js strings ^js children
           ^js el-tags ^js el-props]}]
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
     [:roster-elements (.-length el-tags)]
     [:roster-props    (.-length prop-keys)]
     [:roster-children (.-length children)]
     [:roster-strings  (.-length strings)]]))

;; ---------------------------------------------------------------------------
;; Candidates — costed here, landed only if this table convicts
;; ---------------------------------------------------------------------------
;;
;; The shipping cache lookup is a three-step, and every step of it is on
;; the HIT path — the path a mount takes 1,202 times for tags and ~1,075
;; times for props: `reserved-name?` (three `===` compares against the JS
;; prototype-poisoning roster), `own-key?`
;; (`Object.prototype.hasOwnProperty.call`), then `unchecked-get`. All
;; three exist because the caches are `#js {}` objects and therefore carry
;; `Object.prototype`: a literal named `__proto__` would poison a WRITE,
;; and an inherited name (`toString`, `constructor`) would falsely hit a
;; READ.
;;
;; Both candidates below move the write guard to the MISS path, where it
;; runs once per distinct literal for the life of the build instead of
;; once per element per mount, and answer the false-hit problem in the
;; lookup itself. They differ in how:
;;
;;   nullproto  the cache is `Object.create(null)` — no prototype chain,
;;              so a lookup can only ever answer an own property and the
;;              test is `undefined?`. Strictly safer than the guard it
;;              replaces. RISK, which is why it is costed and not
;;              assumed: V8 starts `Object.create(null)` objects in
;;              DICTIONARY mode, and a dictionary-mode lookup can be
;;              dearer than the fast-mode lookup plus its guards.
;;   instcheck  the cache stays a `#js {}` in fast mode and the hit is
;;              validated by TYPE — nothing on `Object.prototype` is a
;;              `ParsedTag` or a `PropSlot`, so `instance?` rejects every
;;              inherited name in one test.

(def ^:private guarded-cache #js {})
(def ^:private nullproto-cache (js/Object.create nil))
(def ^:private instcheck-cache #js {})
(def ^:private has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- reserved-name? [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

(defn- mint-slot
  "All three arms cache the same value shape the codec caches, so the
  rows price the LOOKUP and not two different payloads."
  [k n]
  (codec/->PropSlot (codec/prop-name k) (reserved-name? n) false false false))

(defn- guarded-lookup
  "The shipping lookup shape, written here so every arm is local."
  [k]
  (let [n (name k)]
    (if (reserved-name? n)
      (mint-slot k n)
      (if (.call has-own guarded-cache n)
        (unchecked-get guarded-cache n)
        (let [v (mint-slot k n)]
          (unchecked-set guarded-cache n v)
          v)))))

(defn- nullproto-lookup [k]
  (let [n (name k)
        v (unchecked-get nullproto-cache n)]
    (if (undefined? v)
      (let [v' (mint-slot k n)]
        (when-not (reserved-name? n) (unchecked-set nullproto-cache n v'))
        v')
      v)))

(defn- instcheck-lookup [k]
  (let [n (name k)
        v (unchecked-get instcheck-cache n)]
    (if (instance? codec/PropSlot v)
      v
      (let [v' (mint-slot k n)]
        (when-not (reserved-name? n) (unchecked-set instcheck-cache n v'))
        v'))))

(def ^:private guarded-tag-cache #js {})
(def ^:private nullproto-tag-cache (js/Object.create nil))
(def ^:private instcheck-tag-cache #js {})

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
        (when-not (reserved-name? k) (unchecked-set nullproto-tag-cache k v'))
        v')
      v)))

(defn- instcheck-tag-lookup [t]
  (let [k (tag-cache-key t)
        v (unchecked-get instcheck-tag-cache k)]
    (if (instance? codec/ParsedTag v)
      v
      (let [v' (codec/parse-tag t)]
        (when-not (reserved-name? k) (unchecked-set instcheck-tag-cache k v'))
        v'))))

;; The second candidate the stage table convicts: `as-element`'s child
;; dispatch. Ours asks `nil? → false? → vector? → string? → …`; stock
;; Reagent asks ONE `js-val?` (`goog/typeOf x !== "object"`) and returns
;; a string on the first branch. The seven predicates are mutually
;; exclusive, so their ORDER is free to change and cannot change an
;; answer — the question is only what each population pays. Both arms
;; below run the real cond over the page's real child roster and return
;; a tag rather than an element, so the figure is the DISPATCH and not
;; the walk beneath it.

(defn- dispatch-ship [x]
  (cond (nil? x) 0 (false? x) 0 (vector? x) 1 (string? x) 2 (number? x) 3
        (seq? x) 4 (true? x) 5 :else 6))

(defn- dispatch-string-first [x]
  (cond (nil? x) 0 (false? x) 0 (string? x) 2 (vector? x) 1 (number? x) 3
        (seq? x) 4 (true? x) 5 :else 6))

(defn- warm-candidates! [^js tags ^js prop-keys]
  (dotimes [i (.-length prop-keys)]
    (let [k (aget prop-keys i)]
      (guarded-lookup k) (nullproto-lookup k) (instcheck-lookup k)))
  (dotimes [i (.-length tags)]
    (let [t (aget tags i)]
      (guarded-tag-lookup t) (nullproto-tag-lookup t) (instcheck-tag-lookup t))))

(defn- candidate-table
  [{:keys [^js tags ^js prop-keys ^js children ^js strings]}]
  (warm-candidates! tags prop-keys)
  [[:prop-lookup-guarded   (ns-per-op micro-reps prop-keys guarded-lookup)]
   [:prop-lookup-nullproto (ns-per-op micro-reps prop-keys nullproto-lookup)]
   [:prop-lookup-instcheck (ns-per-op micro-reps prop-keys instcheck-lookup)]
   [:tag-lookup-guarded    (ns-per-op micro-reps tags guarded-tag-lookup)]
   [:tag-lookup-nullproto  (ns-per-op micro-reps tags nullproto-tag-lookup)]
   [:tag-lookup-instcheck  (ns-per-op micro-reps tags instcheck-tag-lookup)]
   ;; the whole child population, in the proportions the page has it
   [:dispatch-all-ship        (ns-per-op micro-reps children dispatch-ship)]
   [:dispatch-all-stringfirst (ns-per-op micro-reps children dispatch-string-first)]
   ;; and the string half alone, which is where the stage table put the gap
   [:dispatch-str-ship        (ns-per-op micro-reps strings dispatch-ship)]
   [:dispatch-str-stringfirst (ns-per-op micro-reps strings dispatch-string-first)]])

;; No `^js` hint: these read DEFTYPE fields, whose names the compiler
;; munges (`js-name` -> `js_name`, `reserved?` -> `reserved_QMARK_`). The
;; codec reads them the same way, through its own `^PropSlot` hint.
(defn- slot= [^codec/PropSlot a ^codec/PropSlot b]
  (and (= (.-js-name a) (.-js-name b))
       (= (.-reserved? a) (.-reserved? b))))

(defn- tag= [a b]
  (and (= (.-tag a) (.-tag b))
       (= (.-id a) (.-id b))
       (= (.-className a) (.-className b))))

(defn- candidate-agreement
  "Each candidate answers what the shipping shape answers, for every
  literal on the page AND for the five hostile names the page does not
  carry — checked, not asserted, before any figure is read."
  [{:keys [^js tags ^js prop-keys ^js children]}]
  (warm-candidates! tags prop-keys)
  (let [bad (atom [])]
    (dotimes [i (.-length children)]
      (let [c (aget children i)]
        (when-not (= (dispatch-ship c) (dispatch-string-first c))
          (swap! bad conj [:dispatch i]))))
    (dotimes [i (.-length prop-keys)]
      (let [k (aget prop-keys i)
            g (guarded-lookup k)]
        (when-not (slot= g (nullproto-lookup k)) (swap! bad conj [:prop :nullproto k]))
        (when-not (slot= g (instcheck-lookup k)) (swap! bad conj [:prop :instcheck k]))))
    (dotimes [i (.-length tags)]
      (let [t (aget tags i)
            g (guarded-tag-lookup t)]
        (when-not (tag= g (nullproto-tag-lookup t)) (swap! bad conj [:tag :nullproto t]))
        (when-not (tag= g (instcheck-tag-lookup t)) (swap! bad conj [:tag :instcheck t]))))
    (doseq [n ["__proto__" "prototype" "constructor" "toString" "hasOwnProperty"
               "valueOf" "isPrototypeOf"]]
      (let [k (keyword n)
            g (guarded-lookup k)]
        (when-not (slot= g (nullproto-lookup k)) (swap! bad conj [:hostile :nullproto k]))
        (when-not (slot= g (instcheck-lookup k)) (swap! bad conj [:hostile :instcheck k]))
        (let [gt (guarded-tag-lookup k)]
          (when-not (tag= gt (nullproto-tag-lookup k))
            (swap! bad conj [:hostile-tag :nullproto k]))
          (when-not (tag= gt (instcheck-tag-lookup k))
            (swap! bad conj [:hostile-tag :instcheck k])))))
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
                  ;; The tree is built inside the body door (the native
                  ;; arm's lowering needs it) and RENDERED outside it, so
                  ;; no React commit happens inside a body context.
                  canons  (into {}
                                (map (fn [{:keys [id witness walk]}]
                                       [id (render-canonical
                                             (wp/in-body (fn [] (walk witness))))]))
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
