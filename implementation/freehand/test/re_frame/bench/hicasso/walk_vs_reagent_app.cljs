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
        el-tags #js [] el-props #js [] el-vecs #js []]
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
                  (.push el-props (if has-props? props nil))
                  ;; The WHOLE argv, meta included — the `:key` read prices a
                  ;; `(meta v)` probe, so a reconstructed childless vector
                  ;; (which would carry nil meta) is not the same question.
                  (.push el-vecs argv))
                (when props
                  (doseq [[k v] props]
                    (.push ks k)
                    (.push vs v)))
                (doseq [c (subvec argv (if has-props? 2 1))]
                  (visit-child c))))]
      (visit h))
    {:tags tags :prop-keys ks :prop-vals vs :strings strs :children kids
     :el-tags el-tags :el-props el-props :el-vecs el-vecs}))

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
;; SLIM, DECOMPOSED (rf2-lhdp0)
;; ---------------------------------------------------------------------------
;;
;; The arm rows put `reagent2.impl.template` at 1.73-1.90x stock Reagent, and
;; the stage table names ONE term (`cached-prop-name`, ~2x stock). That term
;; weighs 1,489 prop occurrences over 1,202 elements — nowhere near the whole
;; gap, and rf2-lhdp0 says so in as many words. These rows name the rest.
;;
;; Slim's per-element shapes are `defn-` private, so they are replicated here
;; the way the cache candidates above already are ("written here so every arm
;; is local"). Everything the replicas cannot reach privately is called for
;; real: `slim/parse-tag`, `slim/class-names`, `slim/cached-prop-name`,
;; `slim/convert-prop-value` and `slim/void-tags` are all public.
;;
;; WHAT A ROW HERE IS, AND IS NOT. A `-ship` row is a REPLICA of the shipping
;; shape, not the shipping shape. It is held honest by AGREEMENT — every
;; cheapened variant answers what its `-ship` twin answers, over the page's
;; own literals AND the hostile names it does not carry, checked before any
;; figure is read — so a `ship → cheap` delta names a term and its sign
;; reliably. It is NOT calibrated in absolute terms against the real
;; function, and must not be read as if it were: measured on the first run,
;; `slim-prop-lookup-ship` read 86.0 ns/op where `prop-name-slim` — the same
;; algorithm, but the REAL `slim/cached-prop-name` — read 66.8. Same shape,
;; different call site, 29% apart.
;;
;; So the authority for "did the landed change move the shipping function?"
;; is the STAGE table's own real-function rows (`prop-name-slim`,
;; `convert-props-*`) and the ARM rows, compared across a before run and an
;; after run. These rows are the map that says WHERE to cut; those rows are
;; the verdict on the cut.

(def ^:private slim-reserved #{"__proto__" "prototype" "constructor"})

;; Shipping: a PersistentHashSet `contains?` — a string hash plus a hash-map
;; probe for a roster of three. Candidate: the `identical?` chain the codec
;; already ships (prior art: front/codec.cljs `reserved-name?`).
(defn- ^boolean slim-reserved-set? [n] (contains? slim-reserved n))
(defn- ^boolean slim-reserved-chain? [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

;; A null-prototype index DERIVED from the same set. Drift-proof by
;; construction — there is no second roster to keep in step — and it is the
;; shape that scales to the 14-entry void-tag roster, where an `identical?`
;; chain would be 14 compares. `__proto__` is an ordinary own key on a
;; null-prototype object, so the roster indexes itself without special-casing.
(defn- ^js index-of-strings [ss]
  (reduce (fn [o s] (unchecked-set o s true) o) (js/Object.create nil) ss))

(def ^:private slim-reserved-index (index-of-strings slim-reserved))
(defn- ^boolean slim-reserved-idx? [n]
  (true? (unchecked-get slim-reserved-index n)))

;; Shipping resolves `Object.prototype.hasOwnProperty` on EVERY call (two
;; property loads) and then `.call`s it. Hoisted here the way the codec's
;; `has-own` is, so the row prices the guard and not the resolution.
(defn- ^boolean slim-own-key? [obj k]
  (.call (.. js/Object -prototype -hasOwnProperty) obj k))

(defn- slim-cache-key [k]
  (let [n (name k)]
    (if-let [ns* (and (keyword? k) (namespace k))]
      (str ns* "/" n)
      n)))

;; --- the tag cache ---------------------------------------------------------

(def ^:private slim-tag-ship-cache #js {})
(def ^:private slim-tag-np-cache (js/Object.create nil))

(defn- slim-tag-ship [t]
  (let [n (slim-cache-key t)]
    (if (and (not (slim-reserved-set? n))
             (slim-own-key? slim-tag-ship-cache n))
      (aget slim-tag-ship-cache n)
      (let [v (slim/parse-tag t [t])]
        (when-not (slim-reserved-set? n) (aset slim-tag-ship-cache n v))
        v))))

(defn- slim-tag-np [t]
  (let [n (slim-cache-key t)
        v (unchecked-get slim-tag-np-cache n)]
    (if (undefined? v)
      (let [v' (slim/parse-tag t [t])]
        (when-not (slim-reserved-chain? n) (unchecked-set slim-tag-np-cache n v'))
        v')
      v)))

;; --- the prop-name cache ---------------------------------------------------

(def ^:private slim-prop-ship-cache
  (doto #js {} (aset "class" "className") (aset "for" "htmlFor")
               (aset "charset" "charSet")))

(def ^:private slim-prop-np-cache
  (doto (js/Object.create nil)
    (unchecked-set "class" "className")
    (unchecked-set "for" "htmlFor")
    (unchecked-set "charset" "charSet")))

(defn- slim-prop-ship [k]
  (if (or (keyword? k) (symbol? k))
    (let [n (name k)]
      (if (slim-reserved-set? n)
        n
        (if-some [cached (when (slim-own-key? slim-prop-ship-cache n)
                           (aget slim-prop-ship-cache n))]
          cached
          (let [v (slim/cached-prop-name k)]
            (aset slim-prop-ship-cache n v)
            v))))
    k))

(defn- slim-prop-np [k]
  (if (or (keyword? k) (symbol? k))
    (let [n (name k)
          v (unchecked-get slim-prop-np-cache n)]
      (if (undefined? v)
        (let [v' (slim/cached-prop-name k)]
          (when-not (slim-reserved-chain? n) (unchecked-set slim-prop-np-cache n v'))
          v')
        v))
    k))

;; --- the per-element prop-map preamble -------------------------------------
;;
;; Before a single prop is converted, `convert-props` shuffles the persistent
;; map three times: `collapse-class-keys` (up to THREE `contains?` probes),
;; a `:class` normalisation `assoc`, and `set-id-class`'s shorthand merge.
;; The cheapened collapse probes `:className` FIRST — the discriminator that
;; is false on essentially every real element — and so pays ONE probe on the
;; common path instead of two or three. Same answers, fewer questions.

(defn- slim-collapse-ship [props]
  (cond
    (and (contains? props :class) (contains? props :className))
    (-> props
        (assoc :class (slim/class-names (:class props) (:className props)))
        (dissoc :className))

    (contains? props :className)
    (-> props
        (assoc :class (:className props))
        (dissoc :className))

    :else props))

(defn- slim-collapse-cheap [props]
  (if (contains? props :className)
    (if (contains? props :class)
      (-> props
          (assoc :class (slim/class-names (:class props) (:className props)))
          (dissoc :className))
      (-> props
          (assoc :class (:className props))
          (dissoc :className)))
    props))

(defn- slim-set-id-class [props parsed]
  (let [id    (.-id parsed)
        class (.-className parsed)]
    (cond-> props
      (and (some? id) (nil? (:id props)))
      (assoc :id id)

      class
      (assoc :class (slim/class-names class (or (:class props) (:className props)))))))

(defn- slim-preamble [collapse props parsed]
  (let [collapsed  (collapse props)
        class      (:class collapsed)
        normalised (cond-> collapsed class (assoc :class (slim/class-names class)))]
    (slim-set-id-class normalised parsed)))

(defn- slim-preamble-ship  [props parsed] (slim-preamble slim-collapse-ship props parsed))
(defn- slim-preamble-cheap [props parsed] (slim-preamble slim-collapse-cheap props parsed))

;; --- the whole per-element prop pipeline -----------------------------------

(defn- slim-top-prop-conv [reserved? native? o k v]
  (let [k' (slim/cached-prop-name k)]
    (if (and (string? k') (reserved? k'))
      o
      (do (aset o k' (slim/convert-prop-value k v native?)) o))))

(defn- slim-convert-props [preamble reserved? props parsed]
  (let [native?        (string? (.-tag parsed))
        with-shorthand (preamble props parsed)]
    (when (seq with-shorthand)
      (reduce-kv (fn [o k v] (slim-top-prop-conv reserved? native? o k v))
                 #js {} with-shorthand))))

;; Shipping threads `native?` into the reduce through a CLOSURE minted per
;; element — `(fn [o k v] (top-prop-conv native? o k v))`. Decide the rule
;; once per element instead and the reduce takes a static fn, so the mount
;; allocates nothing to carry a boolean it already knows. Costed rather than
;; assumed: V8 allocates closures cheaply and this may be free.
(defn- slim-native-prop-conv  [o k v] (slim-top-prop-conv slim-reserved-chain? true o k v))
(defn- slim-interop-prop-conv [o k v] (slim-top-prop-conv slim-reserved-chain? false o k v))

(defn- slim-convert-props-static [preamble props parsed]
  (let [native?        (string? (.-tag parsed))
        with-shorthand (preamble props parsed)]
    (when (seq with-shorthand)
      (reduce-kv (if native? slim-native-prop-conv slim-interop-prop-conv)
                 #js {} with-shorthand))))

;; --- the per-element `:key` read -------------------------------------------
;;
;; Shipping asks `vector?` twice, reads `meta`, then `nth 0`, a `case`, `nth 1`
;; and a `map?` before the `:key` lookup it wanted. Every call site already
;; knows the props slot, so the specialised form is the read alone.

(defn- slim-key-ship [v]
  (let [k (when (vector? v) (some-> (meta v) :key))]
    (or k
        (case (when (vector? v) (nth v 0 nil))
          (:> :f>) (when (map? (nth v 2 nil)) (:key (nth v 2 nil)))
          :r>      (some-> (nth v 2 nil) (.-key))
          (when (map? (nth v 1 nil)) (:key (nth v 1 nil)))))))

(defn- slim-key-spec [v props]
  (or (some-> (meta v) :key)
      (:key props)))

;; --- the per-element void-tag probe ----------------------------------------

(defn- ^boolean slim-void-set? [t] (contains? slim/void-tags t))
(defn- ^boolean slim-void-case? [t]
  (case t
    ("area" "base" "br" "col" "embed" "hr" "img" "input" "link"
     "meta" "param" "source" "track" "wbr") true
    false))

(def ^:private slim-void-index (index-of-strings slim/void-tags))
(defn- ^boolean slim-void-idx? [t]
  (true? (unchecked-get slim-void-index t)))

(defn- warm-slim! [^js tags ^js prop-keys]
  (dotimes [i (.-length prop-keys)]
    (let [k (aget prop-keys i)] (slim-prop-ship k) (slim-prop-np k)))
  (dotimes [i (.-length tags)]
    (let [t (aget tags i)] (slim-tag-ship t) (slim-tag-np t))))

(defn- slim-table
  [{:keys [^js tags ^js prop-keys ^js el-props ^js el-vecs ^js el-tags]}]
  (warm-slim! tags prop-keys)
  (let [parsed   (let [a #js []]
                   (dotimes [i (.-length el-tags)] (.push a (slim-tag-ship (aget el-tags i))))
                   a)
        tag-strs (let [a #js []]
                   (dotimes [i (.-length el-tags)] (.push a (.-tag (aget parsed i))))
                   a)]
    [;; the two caches, shipping vs null-prototype
     [:slim-prop-lookup-ship (ns-per-op micro-reps prop-keys slim-prop-ship)]
     [:slim-prop-lookup-np   (ns-per-op micro-reps prop-keys slim-prop-np)]
     [:slim-tag-lookup-ship  (ns-per-op micro-reps tags slim-tag-ship)]
     [:slim-tag-lookup-np    (ns-per-op micro-reps tags slim-tag-np)]
     ;; the reserved-key probe, per prop, on the write chokepoint
     [:slim-reserved-set   (ns-per-op micro-reps prop-keys (fn [k] (slim-reserved-set? (name k))))]
     [:slim-reserved-chain (ns-per-op micro-reps prop-keys (fn [k] (slim-reserved-chain? (name k))))]
     [:slim-reserved-idx   (ns-per-op micro-reps prop-keys (fn [k] (slim-reserved-idx? (name k))))]
     ;; the per-element prop-map preamble
     [:slim-preamble-ship  (ns-per-op2 micro-reps el-props parsed slim-preamble-ship)]
     [:slim-preamble-cheap (ns-per-op2 micro-reps el-props parsed slim-preamble-cheap)]
     ;; the WHOLE per-element prop pipeline — the row the hicasso/reagent
     ;; table has and slim did not
     [:slim-convert-props-ship
      (ns-per-op2 micro-reps el-props parsed
                  (fn [p t] (slim-convert-props slim-preamble-ship slim-reserved-set? p t)))]
     [:slim-convert-props-cheap
      (ns-per-op2 micro-reps el-props parsed
                  (fn [p t] (slim-convert-props slim-preamble-cheap slim-reserved-chain? p t)))]
     ;; cheap vs static differ ONLY by the per-element closure.
     [:slim-convert-props-static
      (ns-per-op2 micro-reps el-props parsed
                  (fn [p t] (slim-convert-props-static slim-preamble-cheap p t)))]
     ;; the per-element key read
     [:slim-key-ship (ns-per-op micro-reps el-vecs slim-key-ship)]
     [:slim-key-spec (ns-per-op2 micro-reps el-vecs el-props slim-key-spec)]
     ;; the per-element void probe
     [:slim-void-set  (ns-per-op micro-reps tag-strs slim-void-set?)]
     [:slim-void-case (ns-per-op micro-reps tag-strs slim-void-case?)]
     [:slim-void-idx  (ns-per-op micro-reps tag-strs slim-void-idx?)]]))

(defn- slim-agreement
  "Every cheapened slim variant answers what the shipping shape answers —
  over the page's own literals and over the hostile names it does not carry."
  [{:keys [^js tags ^js prop-keys ^js el-props ^js el-vecs ^js el-tags]}]
  (warm-slim! tags prop-keys)
  (let [bad (atom [])]
    (dotimes [i (.-length prop-keys)]
      (let [k (aget prop-keys i)]
        (when-not (= (slim-prop-ship k) (slim-prop-np k))
          (swap! bad conj [:prop k]))
        (when-not (= (slim-reserved-set? (name k)) (slim-reserved-chain? (name k)))
          (swap! bad conj [:reserved-chain k]))
        (when-not (= (slim-reserved-set? (name k)) (slim-reserved-idx? (name k)))
          (swap! bad conj [:reserved-idx k]))))
    (dotimes [i (.-length tags)]
      (let [t (aget tags i)
            a (slim-tag-ship t) b (slim-tag-np t)]
        (when-not (and (= (.-tag a) (.-tag b)) (= (.-id a) (.-id b))
                       (= (.-className a) (.-className b)))
          (swap! bad conj [:tag t]))))
    ;; the preamble, the whole pipeline and the key read, per element
    (dotimes [i (.-length el-tags)]
      (let [p (aget el-props i)
            t (slim-tag-ship (aget el-tags i))
            v (aget el-vecs i)]
        (when-not (= (slim-preamble-ship p t) (slim-preamble-cheap p t))
          (swap! bad conj [:preamble i]))
        (let [shipped (js/JSON.stringify
                        (slim-convert-props slim-preamble-ship slim-reserved-set? p t))]
          (when-not (= shipped
                       (js/JSON.stringify
                         (slim-convert-props slim-preamble-cheap slim-reserved-chain? p t)))
            (swap! bad conj [:pipeline i]))
          (when-not (= shipped
                       (js/JSON.stringify
                         (slim-convert-props-static slim-preamble-cheap p t)))
            (swap! bad conj [:pipeline-static i])))
        (when-not (= (slim-key-ship v) (slim-key-spec v p))
          (swap! bad conj [:key i]))))
    ;; hostile names the page does not carry
    (doseq [n ["__proto__" "prototype" "constructor" "toString" "hasOwnProperty"
               "valueOf" "isPrototypeOf"]]
      (let [k (keyword n)]
        (when-not (= (slim-prop-ship k) (slim-prop-np k))
          (swap! bad conj [:hostile-prop k]))
        (when-not (= (slim-reserved-set? n) (slim-reserved-chain? n))
          (swap! bad conj [:hostile-reserved-chain n]))
        (when-not (= (slim-reserved-set? n) (slim-reserved-idx? n))
          (swap! bad conj [:hostile-reserved-idx n]))
        (let [a (slim-tag-ship k) b (slim-tag-np k)]
          (when-not (and (= (.-tag a) (.-tag b)) (= (.-id a) (.-id b))
                         (= (.-className a) (.-className b)))
            (swap! bad conj [:hostile-tag k])))))
    ;; the void probe, over every void tag and every tag the page carries
    (doseq [t (concat slim/void-tags ["div" "span" "p" "a" "section" ""
                                      "__proto__" "constructor" "toString"])]
      (when-not (= (slim-void-set? t) (slim-void-case? t))
        (swap! bad conj [:void-case t]))
      (when-not (= (slim-void-set? t) (slim-void-idx? t))
        (swap! bad conj [:void-idx t])))
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
                  slim-bad (slim-agreement roster)
                  slims    (slim-table roster)
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
              (lane/record! :walk-vs-reagent-slim
                            {:disagreements slim-bad
                             :ns-per-op (into {} (map (fn [[k v]] [k (lane/round4 v)])) slims)})

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

              (js/console.log ";; ==== SLIM DECOMPOSED (rf2-lhdp0) ====")
              (js/console.log (str ";;   agreement failures: " (pr-str slim-bad)))
              (doseq [[k v] slims]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns/op")))

              (when (:refuse? gv)
                (set! (.-HICASSO_GUARD_REFUSED js/window) true))
              (lane/done!)))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
