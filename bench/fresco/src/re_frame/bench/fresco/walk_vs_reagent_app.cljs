(ns re-frame.bench.fresco.walk-vs-reagent-app
  "OUR WALK, PUT BESIDE REAGENT'S OWN.

  The mount deficit's attribution chain ends at the runtime hiccup
  interpreter, and stock Reagent is the existence proof that a runtime
  interpreter need not be dear. `walk_profile_app` profiles OUR walk
  against an in-namespace copy of an older walk of ours; it does not put
  the two interpreters side by side. This entry does, on ONE witness value, in ONE process:

  | arm | walks | input |
  |---|---|---|
  | `fresco` | `front.codec/as-element` | the plain witness |
  | `reagent` | `reagent.impl.template/as-element` (stock 2.0.1) | the plain witness |
  | `fresco-native` | `front.codec/as-element` | the page's OWN markup (intent vectors) |

  ## The witness, and why it is link-term free

  The page is `walk_profile_app`'s twin of the acceptance shape — the
  same 1,202-element census page, guarded by that file's own fatal
  canonical-DOM parity gate against the real `rf.bench.fresco.shapes.large-template/page`, so there is one
  twin in the lane and not two. It is **realized once, outside every
  timed window** (`rf.bench.fresco.front.codec/realize-deep`), which is what makes these rows
  immune to the route-link render term: every
  `route-link` href on the page is synthesised during realisation and is
  a plain string by the time any arm walks it. No timed window here
  contains a `route-url` call.

  **THE PLAIN WITNESS.** Fresco's markup carries intent VECTORS at its
  71 event positions; Reagent has no such surface and would `clj->js`
  them. An arm doing different work is not an arm, so
  the compared value is the realized page with every event position
  replaced by one shared plain function — legal, and identical work, for
  both interpreters. `fresco-native` then prices what Fresco's own
  authoring surface costs ON TOP of that, so the intent term is named
  rather than hidden inside the comparison.

  ## Workload matching is gated, not asserted

  Every arm's element tree is rendered into a fresh container and its
  canonical DOM read (`rf.bench.fresco.lane/canonical`, attribute names sorted). The
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
     (`rf.bench.fresco.front.controlled/install!` against `input/input-component?`), and child
     dispatch. **Absolute ns/element**, because two-thirds of a mount
     window is shared frame work and a ratio cannot be read against it.
  3. CANDIDATES — costed BEFORE anything ships. Each is a shape the
     stage table convicts, written here beside the shipping shape and
     timed against it in the same process on the same roster.

  Driver: `run.cjs` with
  FRESCO_INIT_FN=re-frame.bench.fresco.walk-vs-reagent-app/-main."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.mount :as rf.bench.fresco.arm1.mount]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.controlled :as rf.bench.fresco.front.controlled]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.bench.fresco.front.slot :as rf.bench.fresco.front.slot]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.bench.fresco.shapes.large-template :as rf.bench.fresco.shapes.large-template]
            [re-frame.bench.fresco.walk-profile-app :as rf.bench.fresco.walk-profile-app]
            [re-frame.core :as rf]
            [reagent.impl.input :as rinput]
            [reagent.impl.protocols :as rp]
            [reagent.impl.template :as rtpl]
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
               (if (and (keyword? k) (rf.bench.fresco.front.intent/event-prop? k))
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
  (let [c    (rf.bench.fresco.arm1.mount/fresh-container!)
        root (react-dom-client/createRoot c)]
    (react-dom/flushSync (fn [] (.render root el)))
    (let [canon (rf.bench.fresco.lane/canonical c)
          n     (rf.bench.fresco.lane/element-count c)]
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
  need it. Fresco's intent lowering refuses to run with no ambient
  dispatch (`front.intent/require-dispatch`), so the native arm has no
  choice; running the donor inside the same door keeps the three arms on
  ONE call convention, because arms on different conventions compare the
  conventions as much as the walks. The clock starts
  INSIDE the door, so the door itself is never in the window."
  [witness walk-one]
  (rf.bench.fresco.walk-profile-app/in-body
    (fn []
      (let [t0 (rf.bench.fresco.lane/now-ms)]
        (dotimes [_ walks-per-sample]
          (walk-one witness))
        (- (rf.bench.fresco.lane/now-ms) t0)))))

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
        t0   (rf.bench.fresco.lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget arr i)))))
    (let [ms (- (rf.bench.fresco.lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(defn- ns-per-op2
  "[[ns-per-op]] over two parallel arrays — the `[tag props]` pairs the
  prop pipeline takes."
  [reps ^js a ^js b f]
  (let [sink (volatile! nil)
        n    (.-length a)
        t0   (rf.bench.fresco.lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget a i) (aget b i)))))
    (let [ms (- (rf.bench.fresco.lane/now-ms) t0)]
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
                     (.push a (rf.bench.fresco.front.codec/cached-parse (aget el-tags i))))
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
     [:tag-lookup-fresco (ns-per-op micro-reps tags (fn [t] (rf.bench.fresco.front.codec/cached-parse t)))]
     [:tag-lookup-reagent (ns-per-op micro-reps tags
                                     (fn [t] (rtpl/cached-parse nil (name t) t)))]
     ;; --- prop-name lookup -------------------------------------------
     [:prop-name-fresco (ns-per-op micro-reps prop-keys (fn [k] (rf.bench.fresco.front.codec/cached-prop-name k)))]
     [:prop-name-reagent (ns-per-op micro-reps prop-keys (fn [k] (rtpl/cached-prop-name k)))]
     ;; --- value conversion -------------------------------------------
     [:prop-value-fresco (ns-per-op micro-reps prop-vals (fn [v] (rf.bench.fresco.front.codec/convert-prop-value v)))]
     [:prop-value-reagent (ns-per-op micro-reps prop-vals (fn [v] (rtpl/convert-prop-value v)))]
     ;; --- the whole per-element prop pipeline -------------------------
     [:convert-props-fresco
      (ns-per-op2 micro-reps el-props h-parsed (fn [p ^js t] (rf.bench.fresco.front.codec/convert-props p t)))]
     [:convert-props-reagent
      (ns-per-op2 micro-reps el-props r-parsed (fn [p ^js t] (rtpl/convert-props p t)))]
     ;; --- the per-element tag hook ------------------------------------
     [:tag-hook-fresco (ns-per-op micro-reps tag-strs
                                   (fn [t] (rf.bench.fresco.front.controlled/install! t #js {})))]
     [:tag-hook-reagent (ns-per-op micro-reps tag-strs
                                   (fn [t] (rinput/input-component? t)))]
     ;; --- child dispatch ----------------------------------------------
     [:string-child-fresco (ns-per-op micro-reps strings (fn [s] (rf.bench.fresco.front.codec/as-element s)))]
     [:string-child-reagent (ns-per-op micro-reps strings
                                       (fn [s] (rp/as-element rtpl/class-compiler s)))]
     ;; --- the shared floor --------------------------------------------
     [:create-element-shared
      (let [p #js {:className "x"}]
        (ns-per-op (quot micro-reps 4) tag-strs (fn [t] (react/createElement t p))))]
     ;; --- the per-element `:key` read the walk pays outside the loop ---
     [:key-read-fresco (ns-per-op micro-reps el-props (fn [p] (:key p)))]
     [:roster-elements (.-length el-tags)]
     [:roster-props    (.-length prop-keys)]
     [:roster-children (.-length children)]
     [:roster-strings  (.-length strings)]]))

;; ---------------------------------------------------------------------------
;; Candidates — costed here, shipped only if this table convicts
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
  (rf.bench.fresco.front.codec/->PropSlot (rf.bench.fresco.front.slot/prop-name k) (reserved-name? n) false false false))

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
    (if (instance? rf.bench.fresco.front.codec/PropSlot v)
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
      (rf.bench.fresco.front.codec/parse-tag t)
      (if (.call has-own guarded-tag-cache k)
        (unchecked-get guarded-tag-cache k)
        (let [v (rf.bench.fresco.front.codec/parse-tag t)]
          (unchecked-set guarded-tag-cache k v)
          v)))))

(defn- nullproto-tag-lookup [t]
  (let [k (tag-cache-key t)
        v (unchecked-get nullproto-tag-cache k)]
    (if (undefined? v)
      (let [v' (rf.bench.fresco.front.codec/parse-tag t)]
        (when-not (reserved-name? k) (unchecked-set nullproto-tag-cache k v'))
        v')
      v)))

(defn- instcheck-tag-lookup [t]
  (let [k (tag-cache-key t)
        v (unchecked-get instcheck-tag-cache k)]
    (if (instance? rf.bench.fresco.front.codec/ParsedTag v)
      v
      (let [v' (rf.bench.fresco.front.codec/parse-tag t)]
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
   ;; and the string half alone, which is where the stage table puts the gap
   [:dispatch-str-ship        (ns-per-op micro-reps strings dispatch-ship)]
   [:dispatch-str-stringfirst (ns-per-op micro-reps strings dispatch-string-first)]])

;; No `^js` hint: these read DEFTYPE fields, whose names the compiler
;; munges (`js-name` -> `js_name`, `reserved?` -> `reserved_QMARK_`). The
;; codec reads them the same way, through its own `^PropSlot` hint.
(defn- slot= [^rf.bench.fresco.front.codec/PropSlot a ^rf.bench.fresco.front.codec/PropSlot b]
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
;; THE INTENT SURFACE, DECOMPOSED
;; ---------------------------------------------------------------------------
;;
;; The `fresco-native` − `fresco` gap is the only figure on this page
;; that prices the authoring surface, and the arms earn that reading
;; honestly: the two witnesses differ at EVENT POSITIONS and nowhere else.
;; What the arm row cannot say is WHICH event positions, and this page
;; carries two populations of them that cost nothing like each other:
;;
;;   - the AUTHOR-WRITTEN intent vectors — `:on-click [:conduit/favorite
;;     …]` on each card's favourite button, `[::h/prevent …]` on the two
;;     feed toggles. This is the affordance the P2 fork is about.
;;   - one `[::h/navigate {…}]` per `route-link`, MINTED by route-link
;;     rather than written by anybody, and §1 counts 207 links here.
;;
;; [[plainify]] replaces both with the shared [[noop]], so BOTH are inside
;; the gap, and the second population is three times the size of the
;; first. The namespace docstring's statement that these rows are immune
;; to the route-link render term is true of the `route-url` SYNTHESIS — that
;; happens once, at `realize-deep`, outside every window — and is not true
;; of the navigate vector's LOWERING, which `rf.bench.fresco.front.codec/as-element` performs
;; inside every timed window of the native arm and never on the plain one.
;;
;; So the rows below split the gap by carrier and price each against what
;; the plain arm pays at the SAME key. Every one of them runs inside the
;; body door: lowering an intent with no ambient dispatch is
;; `:rf.error/fresco-intent-outside-boundary` by construction.

(defn- collect-intent-roster
  "The NATIVE witness's event positions, split by carrier. The plain arm's
  value at every one of them is [[noop]] by construction, so the plain
  column needs no second walk — it is the same key with the same handler
  the plain witness carries."
  [h]
  (let [nav-ks #js [] nav-vs #js [] nav-maps #js []
        int-ks #js [] int-vs #js []
        other-ks #js [] other-vs #js []]
    (letfn [(visit-child [c]
              (cond (vector? c) (visit c)
                    (seq? c)    (run! visit-child c)
                    :else       nil))
            (visit [argv]
              (let [has-props? (map? (nth argv 1 nil))
                    props      (when has-props? (nth argv 1))]
                (when props
                  (doseq [[k v] props]
                    (when (and (keyword? k) (rf.bench.fresco.front.intent/event-prop? k))
                      (cond
                        (rf.bench.fresco.front.intent/navigate-head? v) (do (.push nav-ks k)
                                                      (.push nav-vs v)
                                                      (.push nav-maps (nth v 1 nil)))
                        (vector? v)               (do (.push int-ks k) (.push int-vs v))
                        :else                     (do (.push other-ks k)
                                                      (.push other-vs v))))))
                (doseq [c (subvec argv (if has-props? 2 1))]
                  (visit-child c))))]
      (visit h))
    {:nav-keys nav-ks :nav-vals nav-vs :nav-maps nav-maps
     :intent-keys int-ks :intent-vals int-vs
     :other-keys other-ks :other-vals other-vs}))

;; The one term inside navigate lowering that is neither a closure nor a
;; field read: `unwrap-navigate`'s closed-grammar check. It is written
;; here rather than reached for, exactly as [[guarded-lookup]] is, so the
;; two shapes are local and comparable.

(def ^:private navigate-key-set
  "`front.intent/navigate-keys`, verbatim — the closed roster HD-027
  names."
  #{:frame :payload :native? :veto})

(defn- navmap-keyset-shipping
  "The key-SET check, and not the shipping one — the name marks the side
  a delta is measured FROM, as on the `-ship` rows below: build the map's
  key SET and compare it to the roster, one `PersistentHashSet` per link
  per render. The twin beside it is what ships. Kept as the losing half
  of a costed pair, which is this lane's practice."
  [m]
  (= navigate-key-set (when (map? m) (set (keys m)))))

(defn- navmap-keyset-scan
  "The same CLOSED grammar with nothing allocated, and what
  `front.intent/unwrap-navigate` asks. The count is what closes it — four
  presence tests alone are fail-open on a fifth key; four presence tests
  AND a count of four admit exactly the same maps the set equality
  admits. The copy is LOCAL, because a copy that a change to the shipping
  fn cannot reach is the positive control the arm rows are read against."
  [m]
  (and (map? m)
       (== 4 (count m))
       (contains? m :frame)
       (contains? m :payload)
       (contains? m :native?)
       (contains? m :veto)))

(def ^:private hostile-nav-maps
  "The shapes a closed grammar must refuse — a fifth key, each missing
  key, four wrong keys, the non-maps — beside the one map it admits; the
  candidate must answer the shipping shape on every one of them before
  either figure is read."
  [{:frame :f :payload [:e] :native? false :veto nil}
   {:frame :f :payload [:e] :native? false}                       ; missing :veto
   {:frame :f :payload [:e] :native? false :veto nil :extra 1}    ; a fifth key
   {:frame :f :payload [:e] :veto nil}                            ; missing :native?
   {:payload [:e] :native? false :veto nil}                       ; missing :frame
   {:frame :f :native? false :veto nil}                           ; missing :payload
   {:a 1 :b 2 :c 3 :d 4}                                          ; four, all wrong
   {}
   nil
   [:frame :payload :native? :veto]
   "not a map"])

(defn- intent-agreement
  "The key-set candidate answers what the shipping shape answers, for
  every navigate map the page actually mints AND for the hostile roster —
  checked before any figure is read."
  [{:keys [^js nav-maps]}]
  (let [bad (atom [])]
    (dotimes [i (.-length nav-maps)]
      (let [m (aget nav-maps i)]
        (when-not (= (navmap-keyset-shipping m) (navmap-keyset-scan m))
          (swap! bad conj [:page-navmap i]))))
    (doseq [m hostile-nav-maps]
      (when-not (= (navmap-keyset-shipping m) (navmap-keyset-scan m))
        (swap! bad conj [:hostile-navmap m])))
    @bad))

(def ^:private intent-roster-rows
  "The rows of [[intent-table]] that are COUNTS and not ns figures."
  #{:roster-navigate-positions :roster-intent-positions
    :roster-other-event-positions})

(defn- intent-table
  "Ours at an event position, by carrier, against the plain arm's own
  value at the same key. ns/POSITION — multiply by the roster count and
  divide by 1,202 to read it against the arm rows' ns/element."
  [{:keys [^js nav-keys ^js nav-vals ^js nav-maps
           ^js intent-keys ^js intent-vals ^js other-keys]}]
  (let [plain-of (fn [^js ks] (let [a #js []]
                                (dotimes [_ (.-length ks)] (.push a noop))
                                a))
        nav-plain (plain-of nav-keys)
        int-plain (plain-of intent-keys)]
    [[:roster-navigate-positions     (.-length nav-keys)]
     [:roster-intent-positions       (.-length intent-keys)]
     [:roster-other-event-positions  (.-length other-keys)]
     ;; --- the navigate carrier: minted by route-link, 1 per link -------
     [:lower-navigate-native (ns-per-op2 micro-reps nav-keys nav-vals rf.bench.fresco.front.intent/lower-prop)]
     [:lower-navigate-plain  (ns-per-op2 micro-reps nav-keys nav-plain rf.bench.fresco.front.intent/lower-prop)]
     ;; --- the author-written carrier -----------------------------------
     [:lower-intent-native   (ns-per-op2 micro-reps intent-keys intent-vals rf.bench.fresco.front.intent/lower-prop)]
     [:lower-intent-plain    (ns-per-op2 micro-reps intent-keys int-plain rf.bench.fresco.front.intent/lower-prop)]
     ;; --- the closed-grammar check inside the navigate carrier ---------
     [:navmap-keyset-shipping (ns-per-op micro-reps nav-maps navmap-keyset-shipping)]
     [:navmap-keyset-scan     (ns-per-op micro-reps nav-maps navmap-keyset-scan)]]))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- arm-rows [arms readings]
  (into {}
        (map (fn [id]
               (let [xs       (mapcat #(get % id) readings)
                     per-walk (mapv #(/ % walks-per-sample) xs)]
                 [id (rf.bench.fresco.lane/summarise per-walk)])))
        (map :id arms)))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.fresco.lane/leave-act-environment!)
  (rf.bench.fresco.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf.bench.fresco.shapes.large-template/make-frame! rf.bench.fresco.walk-profile-app/frame-id)
          (rf.bench.fresco.shapes.large-template/reseed! rf.bench.fresco.walk-profile-app/frame-id)
          (let [{:keys [elements bytes]} (rf.bench.fresco.walk-profile-app/parity!)]
            (js/console.log (str ";; twin parity OK — " elements " elements, "
                                 bytes " canonical bytes, identical to rf.bench.fresco.shapes.large-template/page"))
            (let [native  (rf.bench.fresco.walk-profile-app/in-body (fn [] (rf.bench.fresco.front.codec/realize-deep (rf.bench.fresco.walk-profile-app/page-hiccup))))
                  plain   (plainify native)
                  roster  (collect-roster plain)
                  arms    [{:id :fresco        :witness plain
                            :walk (fn [h] (rf.bench.fresco.front.codec/as-element h))}
                           {:id :reagent        :witness plain
                            :walk (fn [h] (rp/as-element rtpl/class-compiler h))}
                           {:id :fresco-native :witness native
                            :walk (fn [h] (rf.bench.fresco.front.codec/as-element h))}]
                  ;; ---- workload matching, before any figure ------------
                  ;; The tree is built inside the body door (the native
                  ;; arm's lowering needs it) and RENDERED outside it, so
                  ;; no React commit happens inside a body context.
                  canons  (into {}
                                (map (fn [{:keys [id witness walk]}]
                                       [id (render-canonical
                                             (rf.bench.fresco.walk-profile-app/in-body (fn [] (walk witness))))]))
                                arms)
                  ref-canon (first (get canons :fresco))
                  parity  (into {}
                                (map (fn [[id [canon n]]]
                                       ;; `rf.bench.fresco.lane/utf8-bytes` and not `count`:
                                       ;; printed below as "canonical bytes",
                                       ;; and `count` answers UTF-16 code
                                       ;; units.
                                       [id {:elements n
                                            :bytes    (rf.bench.fresco.lane/utf8-bytes canon)
                                            :same?    (= canon ref-canon)
                                            :diverges (when-not (= canon ref-canon)
                                                        (first-divergence ref-canon canon))}]))
                                canons)
                  ;; ---- the interleaved rounds --------------------------
                  {:keys [readings samples]}
                  (rf.bench.fresco.lane/rounds! arms sampling rounds
                                (fn [{:keys [witness walk]}] (timed-walks witness walk)))
                  rows    (arm-rows arms readings)
                  gv      (rf.bench.fresco.lane/guard! samples "walk-vs-reagent arms (in-page ms, diagnostic)")
                  stages  (stage-table roster)
                  cand-bad (candidate-agreement roster)
                  cands   (candidate-table roster)
                  ;; The intent roster is the NATIVE witness's, and every
                  ;; row of its table lowers — so the whole table runs
                  ;; inside the body door.
                  iroster  (collect-intent-roster native)
                  intent-bad (intent-agreement iroster)
                  intents  (rf.bench.fresco.walk-profile-app/in-body (fn [] (intent-table iroster)))
                  hic     (:p50 (get rows :fresco))
                  rgt     (:p50 (get rows :reagent))]

              (rf.bench.fresco.lane/record! :walk-vs-reagent-parity parity)
              (rf.bench.fresco.lane/record! :walk-vs-reagent-arms
                            (into {} (map (fn [[k v]]
                                            [k (-> v (update :min rf.bench.fresco.lane/round4)
                                                   (update :max rf.bench.fresco.lane/round4)
                                                   (update :p50 rf.bench.fresco.lane/round4))]))
                                  rows))
              (rf.bench.fresco.lane/record! :walk-vs-reagent-stages
                            (into {} (map (fn [[k v]] [k (rf.bench.fresco.lane/round4 v)])) stages))
              (rf.bench.fresco.lane/record! :walk-vs-reagent-candidates
                            {:disagreements cand-bad
                             :ns-per-op (into {} (map (fn [[k v]] [k (rf.bench.fresco.lane/round4 v)])) cands)})
              (rf.bench.fresco.lane/record! :walk-vs-reagent-intent
                            {:disagreements intent-bad
                             :ns-per-op (into {} (map (fn [[k v]] [k (rf.bench.fresco.lane/round4 v)])) intents)})

              (js/console.log ";; ==== WORKLOAD MATCH (every arm's own DOM, canonical) ====")
              (doseq [{:keys [id]} arms]
                (let [p (get parity id)]
                  (js/console.log (str ";;   " (name id) ": " (:elements p) " elements, "
                                       (:bytes p) " canonical bytes, "
                                       (if (:same? p) "IDENTICAL to fresco"
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
              (js/console.log (str ";;   fresco/reagent " (fmt (/ hic rgt) 4)))
              (js/console.log (str ";;   ABSOLUTE per-element delta vs reagent: "
                                   (fmt (* 1e6 (/ (- hic rgt) elements)) 1) " ns/el"))

              (js/console.log ";; ==== STAGES (ns/op over the page's own roster) ====")
              (doseq [[k v] stages]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1))))

              (js/console.log ";; ==== CANDIDATES (costed, not landed) ====")
              (js/console.log (str ";;   agreement failures: " (pr-str cand-bad)))
              (doseq [[k v] cands]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns/op")))

              (js/console.log ";; ==== INTENT SURFACE DECOMPOSED ====")
              (js/console.log (str ";;   agreement failures: " (pr-str intent-bad)))
              (doseq [[k v] intents]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1)
                                     (when-not (contains? intent-roster-rows k) " ns/op"))))
              ;; The arm row is ns/ELEMENT over the whole page; these are
              ;; ns/POSITION over a roster of a few hundred. Carried onto
              ;; the arm row's units here so the two are readable together
              ;; and the reader is not asked to do it.
              (let [m       (into {} intents)
                    per-el  (fn [delta n] (/ (* delta n) elements))
                    nav-n   (:roster-navigate-positions m)
                    int-n   (:roster-intent-positions m)
                    nav-d   (- (:lower-navigate-native m) (:lower-navigate-plain m))
                    int-d   (- (:lower-intent-native m) (:lower-intent-plain m))
                    nav-el  (per-el nav-d nav-n)
                    int-el  (per-el int-d int-n)
                    keyset-el (per-el (- (:navmap-keyset-shipping m)
                                         (:navmap-keyset-scan m))
                                      nav-n)]
                (js/console.log (str ";;   -> navigate carrier: " (fmt nav-d 1)
                                     " ns/position x " nav-n " links / " elements
                                     " elements = " (fmt nav-el 1) " ns/element"))
                (js/console.log (str ";;   -> authored intents:  " (fmt int-d 1)
                                     " ns/position x " int-n " positions / " elements
                                     " elements = " (fmt int-el 1) " ns/element"))
                (js/console.log (str ";;   -> named carriers sum: " (fmt (+ nav-el int-el) 1)
                                     " ns/element; OBSERVED arm gap "
                                     (fmt (* 1e6 (/ (- (:p50 (get rows :fresco-native)) hic)
                                                    elements))
                                          1)
                                     " ns/element"))
                (js/console.log (str ";;   -> of the navigate carrier, the closed-grammar key-set "
                                     "check is " (fmt keyset-el 1) " ns/element")))

              (when (:refuse? gv)
                (set! (.-FRESCO_GUARD_REFUSED js/window) true))
              (rf.bench.fresco.lane/done!)))))
      (.catch (fn [e]
                (rf.bench.fresco.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.fresco.lane/done!)))))
