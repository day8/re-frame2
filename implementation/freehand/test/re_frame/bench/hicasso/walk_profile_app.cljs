(ns re-frame.bench.hicasso.walk-profile-app
  "THE INTERPRETER WALK, PROFILED ELEMENT-BY-ELEMENT (rf2-y1jkm).

  Three measurements agree the candidate's mount deficit is the runtime
  hiccup walk — 100% script, scaling with elements-per-boundary, worst on
  the one-boundary 1,202-element census page (rf2-0qj9w, rf2-emvod,
  rf2-2rtt6.56). None of them says WHERE INSIDE THE WALK the time goes.
  This entry answers that: the acceptance shape's own hiccup, walked by
  the shipping codec and by a family of single-phase ablations in one
  process, interleaved, plus a per-call micro table over the page's own
  literal roster and a census of what the page is made of.

  ## DIAGNOSTIC, not published

  The clock here is in-page `performance.now` over K whole-page walks per
  sample. It attributes cost BETWEEN phases of one arm's walk; it is not
  the clock of record and no figure from this file is a gate row. The
  published before/after stays with `census_clock_run.cjs` (raw
  TaskDuration, plumb-tared, same-run donors). Stated per the instrument
  canon so a reader cannot mistake a ratio here for a gated one.

  ## The page under the knife, and the fidelity gate

  The walk needs the page's hiccup as a VALUE, fresh per walk, with the
  ambient frame bound (intent lowering refuses to run outside a boundary
  render, and the page's 141 reads refuse to run outside a body). The
  runtime's public [[re-frame.bench.hicasso.arm1.runtime/render-body]] is
  exactly that door and needs no React, so every sample runs inside it.

  `defview` deliberately hides its body fn, so the page body is a TWIN
  written here: the same chrome forms as
  [[re-frame.bench.hicasso.shapes.large-template/page]], the cards via
  the same [[re-frame.bench.hicasso.shapes.card/card]] calls (69 x 17 =
  1,173 of the 1,202 elements are the card's own markup, byte-for-byte by
  construction). The twin is not trusted: at boot both pages are mounted
  and their canonical DOM compared (`lane/canonical`, attribute names
  sorted), and the run is fatal on disagreement. A profile of a page that
  is not the acceptance page would be rf2-cvvb7's fault with extra steps.

  ## The arms

  Realized input (`codec/realize-deep` outside the window) isolates the
  walk from the body's lazy tail; the one lazy arm prices that tail —
  on a mount the `for` seqs realize INSIDE `as-element`, so the
  mount-billed walk includes the card calls and their sub reads.

  | arm           | input    | what it prices |
  |---------------|----------|----------------|
  | `ship-lazy`   | lazy     | the mount-billed walk: interpretation PLUS the body's lazy card/sub work |
  | `ship`        | realized | the shipping walk alone |
  | `local`       | realized | the faithful in-namespace copy — the ablation baseline, validated against `ship` |
  | `no-create`   | realized | `local` with `react/createElement` swapped for a two-field object mint |
  | `no-props`    | realized | `local` emitting `#js {}` per element — the whole prop pipeline removed |
  | `no-lower`    | realized | `local` with intent lowering stubbed (vectors/maps at any position -> nil, cheaply) |
  | `no-value`    | realized | `local` with `convert-prop-value` -> identity |
  | `no-fold`     | realized | `local` with the shorthand fold onto the emitted object -> identity |
  | `parse-raw`   | realized | `local` parsing every tag fresh — what the tag cache is worth |
  | `no-propless` | realized | `local` with the propless short-circuit removed — every element pays the map path |

  The last two do MORE work than `local`, not less, so they are read as
  benefits rather than as phase costs and are reported on their own lines.

  ## What replaced the `:shorthand-merge` / `:no-short` pair (rf2-2rtt6.70)

  Those two rows priced `merge-shorthand` — a `dissoc`/`assoc` pair that
  rebuilt the attribute map of every element carrying a `#id`/`.class`
  shorthand — against a `local` copy that performed it. rf2-2rtt6.36
  **deleted** that surgery: the shorthand is folded onto the object the
  walk EMITS (`codec/fold-shorthand!`), where the slot is already
  resolved, and the fast lane that existed only to dodge the map copy
  went with it. `convert-props`' three lanes became two.

  Nothing went red, because both arms were local copies. That is exactly
  what made it worth repairing: an instrument whose arms outlive the code
  they ablate keeps printing plausible numbers for a path nobody runs.
  So `local`'s prop pipeline was re-pointed at the shipping shape — the
  propless short-circuit, then convert-then-fold — and the two ablations
  now name the two lanes that actually ship.

  The ablation baseline is written IN THIS NAMESPACE and validated
  against the shipping walk in the same process, for the reason the
  rf2-2rtt6.32 key-walk measurement records: timing a local arm against a
  foreign one compares call conventions as much as phases, and that
  confound was only ever caught by keeping all arms local and checking
  the copy against the shipping fn explicitly.

  Every phase delta is quoted as `local - variant`, so a positive number
  is the phase's cost. The stubs are not free (an object mint, a pair of
  type tests), so each delta is a floor on the phase, not an exact price;
  the stub's shape is stated in the table.

  ## What each table is for

  1. CENSUS — what the 1,202 elements are made of: props per element,
     key/value populations, children populations. The denominator every
     per-element claim needs.
  2. ARMS — whole-page ms per walk, interleaved rounds, min/p50/max, with
     the arm-order guard adjudicating position effects.
  3. PHASES — the subtraction table, ms and ns/element.
  4. MICRO — ns/op for the per-prop and per-tag primitives over the
     page's own literal roster: `cached-prop-name`, `event-prop?` (the
     per-prop regex), the reserved-name set lookup vs a primitive
     comparison chain, `cached-parse` hit vs `parse-tag` fresh,
     `convert-prop-value` over the page's own values, `createElement`.

  Owner bead: rf2-y1jkm. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.walk-profile-app/-main."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as arm1-mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.controlled :as controlled]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.core :as rf]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; The page twin
;; ---------------------------------------------------------------------------

(def frame-id ::frame)

(defn page-hiccup
  "The large-template page's body forms, verbatim minus the run counter:
  the same chrome, the same `(card/card slug)` calls, the same tag-pill
  `for`. Must run inside a body context (the reads). The parity gate
  below is what makes this a copy rather than a claim."
  []
  (let [your-feed? (sub [:conduit/your-feed?])]
    [:div.home-page
     [:div.banner
      [:div.container
       [:h1.logo-font "conduit"]
       [:p "A place to share your knowledge."]]]
     [:div.container.page
      [:div.row
       [:div.col-md-9
        [:div.feed-toggle
         [:ul.nav.nav-pills.outline-active
          [:li.nav-item
           [:a.nav-link {:href        "#"
                         :data-testid "your-feed-tab"
                         :class       (when your-feed? "active")
                         :on-click    [:re-frame.hicasso/prevent [:conduit/show-your-feed]]}
            "Your Feed"]]
          [:li.nav-item
           [:a.nav-link {:href        "#"
                         :data-testid "global-feed-tab"
                         :class       (when-not your-feed? "active")
                         :on-click    [:re-frame.hicasso/prevent [:conduit/show-global-feed]]}
            "Global Feed"]]]]
        [:div.article-list {:data-testid "article-list"}
         (for [slug (sub [:conduit/slugs])]
           (card/card slug))]]
       [:div.col-md-3
        [:div.sidebar
         [:p "Popular Tags"]
         [:div.tag-list {:data-testid "tag-list"}
          (for [tag (sub [:conduit/tags])]
            [:a.tag-pill.tag-default {:key         tag
                                      :href        "#"
                                      :data-testid (str "tag-" tag)}
             tag])]]]]]]))

(defview twin-page
  "The twin as a mountable boundary — the parity gate's arm."
  [_]
  (page-hiccup))

(defn parity!
  "Mount the real page and the twin side by side; throw unless their
  canonical DOM agrees and matches the arithmetic. Fatal, before any
  clock."
  []
  (let [c-real (arm1-mount/fresh-container!)
        c-twin (arm1-mount/fresh-container!)
        h-real (arm1-mount/root! c-real frame-id [lt/page {}])
        h-twin (arm1-mount/root! c-twin frame-id [twin-page {}])
        canon-real (lane/canonical c-real)
        canon-twin (lane/canonical c-twin)
        n-real (lane/element-count c-real)
        n-twin (lane/element-count c-twin)
        expected (lt/element-arithmetic)]
    (arm1-mount/unmount! h-real)
    (arm1-mount/unmount! h-twin)
    (when-not (and (= canon-real canon-twin)
                   (= expected n-real n-twin))
      (throw (ex-info (str "twin parity FAILED: the profiled page is not the "
                           "acceptance page (real " n-real " twin " n-twin
                           " expected " expected " canonical "
                           (if (= canon-real canon-twin) "agrees" "DISAGREES") ")")
                      {:expected expected :real n-real :twin n-twin})))
    {:elements n-real :bytes (count canon-real)}))

;; ---------------------------------------------------------------------------
;; The body-context door
;; ---------------------------------------------------------------------------

(def ^:private !out (volatile! nil))

(defn in-body
  "Run `f` inside a boundary body context — ambient frame bound, reads
  legal — via the runtime's own public [[rt/render-body]], and answer
  `(f)`. The trailing `[:span]` is the body's element and is outside
  every timed window."
  [f]
  (rt/render-body frame-id (fn [_] (vreset! !out (f)) [:span]) {})
  @!out)

;; ---------------------------------------------------------------------------
;; The faithful local walk, with one `mode` switch per phase site
;; ---------------------------------------------------------------------------
;;
;; A copy of front.codec's emission path (native/fragment/boundary arms,
;; the donor's three createElement arities, the single-pass props reduce)
;; with an integer `mode` consulted at the five phase sites. `case` on an
;; int compiles to a JS switch; the full-default instance is validated
;; against `codec/as-element` in the ARMS table rather than assumed
;; equivalent.

(def ^:const M-FULL 0)
(def ^:const M-NO-CREATE 1)
(def ^:const M-NO-PROPS 2)
(def ^:const M-NO-LOWER 3)
(def ^:const M-NO-VALUE 4)
(def ^:const M-NO-FOLD 5)
(def ^:const M-PARSE-RAW 6)
(def ^:const M-NO-PROPLESS 7)

(declare walk-el)

(defn- walk-expand-seq [mode s]
  (let [a #js []]
    (loop [items (seq s)]
      (when items
        (.push a (walk-el mode (first items)))
        (recur (next items))))
    a))

(defn- walk-create
  "The element mint: React's own, or the no-create stub — a two-field
  object, so the delta prices createElement against the cheapest thing
  that still allocates per element."
  [mode component js-props]
  (if (identical? mode M-NO-CREATE)
    #js {:t component :p js-props}
    (react/createElement component js-props)))

(defn- walk-create3 [mode component js-props child]
  (if (identical? mode M-NO-CREATE)
    #js {:t component :p js-props :c child}
    (react/createElement component js-props child)))

(defn- walk-create-n [mode component js-props argv first-child n]
  (if (identical? mode M-NO-CREATE)
    (let [cs #js []]
      (loop [i first-child]
        (when (< i n)
          (.push cs (walk-el mode (nth argv i)))
          (recur (inc i))))
      #js {:t component :p js-props :c cs})
    (let [args #js [component js-props]]
      (loop [i first-child]
        (when (< i n)
          (.push args (walk-el mode (nth argv i)))
          (recur (inc i))))
      (.apply (.-createElement react) nil args))))

(defn- walk-make-element [mode component js-props argv first-child]
  (let [n (count argv)]
    (case (- n first-child)
      0 (walk-create mode component js-props)
      1 (walk-create3 mode component js-props (walk-el mode (nth argv first-child)))
      (walk-create-n mode component js-props argv first-child n))))

(defn- walk-lower
  "Intent lowering, or the no-lower stub. The stub answers nil for a
  vector or map at the position (two type tests, no clj->js) so the
  ablation does not smuggle a `clj->js` of every intent vector into the
  arm it is supposed to be relieving."
  [mode k v]
  (if (identical? mode M-NO-LOWER)
    (if (or (vector? v) (map? v)) nil v)
    (intent/lower-prop k v)))

(defn- walk-value [mode v]
  (if (identical? mode M-NO-VALUE) v (codec/convert-prop-value v)))

(def ^:private id-slot "id")
(def ^:private class-slot "className")

(defn- walk-fold-shorthand!
  "Fold the tag's `#id`/`.class` shorthand onto the object the walk just
  emitted — `codec/fold-shorthand!`'s shape, which is private there — or
  the no-fold stub, which answers the object untouched.

  Asked of the EMITTED object, so there is no spelling left to resolve:
  every key has already been through the canonical slot on its way in.
  Two `undefined?` tests and, on the 924 elements of the census page that
  carry a `.class`, one `class-names` call when a class was also
  declared. The delta therefore prices the fold that ships, not the map
  surgery it replaced."
  [mode ^js o parsed]
  (if (identical? mode M-NO-FOLD)
    o
    (do
      (when-some [id (.-id ^js parsed)]
        (when (undefined? (unchecked-get o id-slot))
          (unchecked-set o id-slot id)))
      (when-some [shorthand (.-className ^js parsed)]
        (let [declared (unchecked-get o class-slot)]
          (unchecked-set o class-slot
                         (if (undefined? declared)
                           shorthand
                           (codec/class-names shorthand declared)))))
      o)))

(def ^:private reserved-names #{"__proto__" "prototype" "constructor"})

;; The local walk carries its OWN copy of the donor's prop-name cache —
;; the string-valued cache the codec shipped when this bead opened — so
;; the `local` arm stays the pre-optimisation walk even after the codec's
;; own cache changes shape. Without this the ablation baseline silently
;; absorbs half the candidate and the in-process A/B undersells it.
(def ^:private local-prop-cache
  (doto #js {}
    (unchecked-set "class" "className")
    (unchecked-set "for" "htmlFor")
    (unchecked-set "charset" "charSet")))

(def ^:private local-has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- local-prop-name [k]
  (if-not (or (keyword? k) (symbol? k))
    (if (string? k) (codec/prop-name k) k)
    (let [n (name k)]
      (if (reserved-names n)
        (codec/prop-name k)
        (if (.call local-has-own local-prop-cache n)
          (unchecked-get local-prop-cache n)
          (let [converted (codec/prop-name k)]
            (unchecked-set local-prop-cache n converted)
            converted))))))

(defn- walk-convert-props
  "The shipping `convert-props`' two lanes, with the phase switches.

  Lane 1, the propless short-circuit: an element with no attribute map
  emits exactly the shorthand's `id`/`className`, so it is built directly
  — no merge, no map iteration, no fold. 567 of the census page's 1,202
  elements take it. `no-propless` removes it, sending them through the
  map path on an empty map, which is what the lane is worth.

  Lane 2: merge a `:&` remainder (by identity when there is none),
  convert the map in one pass with the literal `:key` skipped in-loop
  rather than `dissoc`ed, then fold the shorthand onto the result."
  [mode props parsed]
  (cond
    (identical? mode M-NO-PROPS)
    #js {}

    (and (nil? props) (not (identical? mode M-NO-PROPLESS)))
    (let [o #js {}]
      (when-some [id (.-id ^js parsed)] (unchecked-set o id-slot id))
      (when-some [c (.-className ^js parsed)] (unchecked-set o class-slot c))
      o)

    :else
    (walk-fold-shorthand!
      mode
      (reduce-kv (fn [o k v]
                   (if (keyword-identical? :key k)
                     o
                     (let [n (local-prop-name k)]
                       (when-not (reserved-names n)
                         (unchecked-set o n (walk-value mode
                                              (if (identical? "ref" n)
                                                v
                                                (walk-lower mode k v)))))
                       o)))
                 #js {}
                 (codec/merge-caller (or props {})))
      parsed)))

(defn- walk-parse [mode tag]
  (if (identical? mode M-PARSE-RAW)
    (codec/parse-tag tag)
    (codec/cached-parse tag)))

(defn- walk-native [mode argv]
  (let [parsed     (walk-parse mode (nth argv 0))
        has-props? (map? (nth argv 1 nil))
        props      (if has-props? (nth argv 1) nil)
        ;; nil, not `(or props {})` — the absent attribute map IS the
        ;; first lane, and wrapping it in an empty map is exactly what
        ;; hides the lane from the clock.
        js-props   (walk-convert-props mode props parsed)]
    (controlled/install! (.-tag ^js parsed) js-props)
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (walk-make-element mode (.-tag ^js parsed) js-props argv (if has-props? 2 1))))

(defn- walk-boundary [mode argv]
  (let [has-props? (map? (nth argv 1 nil))
        props      (codec/merge-caller (if has-props? (nth argv 1) {}))
        children   (codec/realize-children argv (if has-props? 2 1))
        body-props (codec/realize-deep (cond-> (dissoc props :key)
                                         children (assoc :children children)))
        head       (nth argv 0)
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    ;; The frame-as-a-prop variant's one emission cost (rf2-2rtt6.39).
    ;; Priced at nothing on THIS page — the census counts zero boundaries
    ;; — and copied anyway, so the arm stays a copy of what ships.
    (when (codec/frame-prop-head? head)
      (unchecked-set js-props "rfFrame" intent/*frame*))
    (walk-create mode head js-props)))

(defn- walk-fragment [mode argv]
  (let [has-props? (map? (nth argv 1 nil))
        props      (if has-props? (nth argv 1) nil)
        js-props   #js {}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (walk-make-element mode (.-Fragment react) js-props argv (if has-props? 2 1))))

(defn- walk-vec [mode argv]
  (let [head (nth argv 0)]
    (cond
      (= :<> head)                 (walk-fragment mode argv)
      (and (or (keyword? head) (symbol? head) (string? head))
           (not (= :<> head)))     (walk-native mode argv)
      (codec/boundary-head? head)  (walk-boundary mode argv)
      :else (throw (ex-info "bad head in local walk" {:head head})))))

(defn- walk-el [mode x]
  (cond
    (nil? x)         nil
    (false? x)       nil
    (vector? x)      (walk-vec mode x)
    (string? x)      x
    (number? x)      x
    (seq? x)         (walk-expand-seq mode x)
    (true? x)        (throw (ex-info "true child in local walk" {}))
    (react/isValidElement x) x
    (keyword? x)     (name x)
    (symbol? x)      (name x)
    :else            x))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(defn- census
  "Walk the realized page counting what it is made of. Pure reading; the
  denominator table."
  [h]
  (let [acc (atom {:vectors 0 :native 0 :boundary 0 :fragment 0
                   :with-props 0 :propless 0 :shorthand-class 0 :shorthand-id 0
                   :props 0 :prop-keys {} :val-types {}
                   :children-str 0 :children-num 0 :children-seq 0 :children-nil 0})
        bump (fn [k] (swap! acc update k inc))
        bump-in (fn [ks k] (swap! acc update-in [ks k] (fnil inc 0)))]
    (letfn [(value-type [v]
              (cond (string? v) :string (fn? v) :fn (vector? v) :vector
                    (map? v) :map (keyword? v) :keyword (number? v) :number
                    (nil? v) :nil :else :other))
            (visit-child [c]
              (cond (nil? c) (bump :children-nil)
                    (false? c) (bump :children-nil)
                    (vector? c) (visit c)
                    (string? c) (bump :children-str)
                    (number? c) (bump :children-num)
                    (seq? c) (do (bump :children-seq) (run! visit-child c))
                    :else nil))
            (visit [argv]
              (bump :vectors)
              (let [head (nth argv 0)]
                (cond
                  (= :<> head) (bump :fragment)
                  (or (keyword? head) (symbol? head) (string? head))
                  (let [p (codec/cached-parse head)]
                    (bump :native)
                    (when (.-className ^js p) (bump :shorthand-class))
                    (when (.-id ^js p) (bump :shorthand-id)))
                  :else (bump :boundary)))
              (let [has-props? (map? (nth argv 1 nil))
                    props (when has-props? (nth argv 1))]
                (if has-props? (bump :with-props) (bump :propless))
                (when props
                  (doseq [[k v] props]
                    (bump :props)
                    (bump-in :prop-keys k)
                    (bump-in :val-types (value-type v))))
                (doseq [c (subvec argv (if has-props? 2 1))]
                  (visit-child c))))]
      (visit h))
    @acc))

;; ---------------------------------------------------------------------------
;; The timed arms
;; ---------------------------------------------------------------------------

(def ^:private walks-per-sample
  "Whole-page walks inside ONE timing window. Chrome clamps
  `performance.now` to 100 us; eight ~1,200-element walks hold the window
  in whole milliseconds, so the clamp is percent-level noise, not the
  signal (the same argument as `lane/mount-batch!`)."
  8)

(defn- fresh-pages
  "K fresh page-hiccup trees. Lazy by construction — building them runs
  no card and reads no per-card sub until something walks them."
  [k]
  (loop [i 0 acc #js []]
    (if (< i k)
      (do (.push acc (page-hiccup)) (recur (inc i) acc))
      acc)))

(defn- realize-pages! [^js pages]
  (dotimes [i (.-length pages)]
    (codec/realize-deep (aget pages i)))
  pages)

(defn- timed-walks
  "One sample: K fresh pages (realized outside the window unless the arm
  is the lazy one), then K walks under one clock. Answers ms for the
  window."
  [realize? walk-one]
  (in-body
    (fn []
      (let [pages (fresh-pages walks-per-sample)
            _     (when realize? (realize-pages! pages))
            t0    (lane/now-ms)]
        (dotimes [i walks-per-sample]
          (walk-one (aget pages i)))
        (- (lane/now-ms) t0)))))

(def ^:private arms
  [{:id :ship-lazy   :realize? false :walk (fn [h] (codec/as-element h))}
   {:id :ship        :realize? true  :walk (fn [h] (codec/as-element h))}
   {:id :local       :realize? true  :walk (fn [h] (walk-el M-FULL h))}
   {:id :no-create   :realize? true  :walk (fn [h] (walk-el M-NO-CREATE h))}
   {:id :no-props    :realize? true  :walk (fn [h] (walk-el M-NO-PROPS h))}
   {:id :no-lower    :realize? true  :walk (fn [h] (walk-el M-NO-LOWER h))}
   {:id :no-value    :realize? true  :walk (fn [h] (walk-el M-NO-VALUE h))}
   {:id :no-fold     :realize? true  :walk (fn [h] (walk-el M-NO-FOLD h))}
   {:id :parse-raw   :realize? true  :walk (fn [h] (walk-el M-PARSE-RAW h))}
   {:id :no-propless :realize? true  :walk (fn [h] (walk-el M-NO-PROPLESS h))}])

(def ^:private sampling {:warmup 4 :samples 10})
(def ^:private rounds 6)

;; ---------------------------------------------------------------------------
;; Micro benches — the per-prop and per-tag primitives
;; ---------------------------------------------------------------------------

(defn- collect-roster
  "The page's own literals, weighted as the page uses them: every native
  tag occurrence, every prop key occurrence, every prop value occurrence.
  JS arrays, so the micro loops index primitively."
  [h]
  (let [tags #js [] keys' #js [] vals' #js []]
    (letfn [(visit-child [c]
              (cond (vector? c) (visit c)
                    (seq? c) (run! visit-child c)
                    :else nil))
            (visit [argv]
              (let [head (nth argv 0)]
                (when (and (or (keyword? head) (symbol? head) (string? head))
                           (not= :<> head))
                  (.push tags head)))
              (let [has-props? (map? (nth argv 1 nil))]
                (when has-props?
                  (doseq [[k v] (nth argv 1)]
                    (.push keys' k)
                    (.push vals' v)))
                (doseq [c (subvec argv (if has-props? 2 1))]
                  (visit-child c))))]
      (visit h))
    {:tags tags :keys keys' :vals vals'}))

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

(def ^:private micro-reps 300)

(defn- micro-table [{:keys [^js tags ^js keys ^js vals]}]
  (let [simple-props #js {:className "x"}
        fixed-names  (let [a #js []]
                       (dotimes [i (.-length keys)]
                         (.push a (name (aget keys i))))
                       a)]
    [[:cached-parse-hit    (ns-per-op micro-reps tags (fn [t] (codec/cached-parse t)))]
     [:parse-tag-fresh     (ns-per-op (quot micro-reps 10) tags (fn [t] (codec/parse-tag t)))]
     [:cached-prop-name    (ns-per-op micro-reps keys (fn [k] (codec/cached-prop-name k)))]
     [:event-prop?-regex   (ns-per-op micro-reps keys (fn [k] (intent/event-prop? k)))]
     [:reserved-set-lookup (ns-per-op micro-reps fixed-names (fn [n] (reserved-names n)))]
     [:reserved-identical  (ns-per-op micro-reps fixed-names
                                      (fn [n] (or (identical? "__proto__" n)
                                                  (identical? "prototype" n)
                                                  (identical? "constructor" n))))]
     [:convert-prop-value  (ns-per-op micro-reps vals (fn [v] (codec/convert-prop-value v)))]
     [:create-element-min  (ns-per-op (quot micro-reps 10) tags
                                      (fn [_] (react/createElement "div" simple-props)))]]))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- arm-rows
  "Fold `lane/rounds!` readings into per-arm p50/min/max (ms per WALK —
  the window is `walks-per-sample` walks)."
  [readings]
  (into {}
        (map (fn [id]
               (let [xs (mapcat #(get % id) readings)
                     per-walk (mapv #(/ % walks-per-sample) xs)]
                 [id (lane/summarise per-walk)])))
        (map :id arms)))

(defn- phase-line [label ship-p50 base p50 elements]
  (let [d (- base p50)]
    (str ";;   " (name label) ": " (fmt p50 4) " ms/walk"
         "  delta " (fmt d 4) " ms"
         "  (" (fmt (* 1e6 (/ d elements)) 0) " ns/el, "
         (fmt (* 100 (/ d ship-p50)) 1) "% of ship)")))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (lt/make-frame! frame-id)
          (lt/reseed! frame-id)
          (let [{:keys [elements bytes]} (parity!)]
            (js/console.log (str ";; twin parity OK — " elements " elements, "
                                 bytes " canonical bytes, identical to lt/page"))
            ;; The census + rosters, from one realized page.
            (let [page (in-body (fn [] (codec/realize-deep (page-hiccup))))
                  cs   (census page)
                  roster (collect-roster page)
                  ;; The interleaved rounds.
                  {:keys [readings samples]}
                  (lane/rounds! arms sampling rounds
                                (fn [{:keys [realize? walk]}]
                                  (timed-walks realize? walk)))
                  rows (arm-rows readings)
                  gv   (lane/guard! samples "walk-profile arms (in-page ms, diagnostic)")
                  ship (:p50 (get rows :ship))
                  local (:p50 (get rows :local))
                  micro (micro-table roster)]
              (lane/record! :walk-profile-census cs)
              (lane/record! :walk-profile-arms
                            (into {} (map (fn [[k v]] [k (-> v (update :min lane/round4)
                                                             (update :max lane/round4)
                                                             (update :p50 lane/round4))])) rows))
              (lane/record! :walk-profile-micro
                            (into {} (map (fn [[k v]] [k (lane/round4 v)])) micro))
              (js/console.log ";; ==== WALK PROFILE (ms per whole-page walk; diagnostic in-page clock) ====")
              (js/console.log (str ";;   elements " elements
                                   "  walks/sample " walks-per-sample
                                   "  design " rounds "x(" (:warmup sampling) "+" (:samples sampling) ")"))
              (doseq [{:keys [id]} arms]
                (let [{:keys [p50 min max]} (get rows id)]
                  (js/console.log (str ";;   " (name id) ": p50 " (fmt p50 4)
                                       " [" (fmt min 4) " - " (fmt max 4) "] ms/walk  ("
                                       (fmt (* 1e6 (/ p50 elements)) 0) " ns/el)"))))
              (js/console.log ";; ==== PHASE DELTAS (local minus ablation; floors, stubs stated in ns docstring) ====")
              (js/console.log (str ";;   copy fidelity: local/ship = " (fmt (/ local ship) 4)))
              (js/console.log (phase-line :lazy-tail ship (:p50 (get rows :ship-lazy)) ship elements))
              (doseq [[label arm-id] [[:create-element :no-create]
                                      [:whole-prop-pipeline :no-props]
                                      [:intent-lowering :no-lower]
                                      [:value-conversion :no-value]
                                      [:shorthand-fold :no-fold]]]
                (js/console.log (phase-line label ship local (:p50 (get rows arm-id)) elements)))
              (js/console.log ";; ==== BENEFITS (arms that do MORE work than local; delta minus local) ====")
              (let [pr' (:p50 (get rows :parse-raw))]
                (js/console.log (str ";;   tag-cache-benefit: parse-raw " (fmt pr' 4)
                                     " ms/walk vs local " (fmt local 4)
                                     " — the cache is worth " (fmt (- pr' local) 4) " ms/walk")))
              (let [np (:p50 (get rows :no-propless))]
                (js/console.log (str ";;   propless-lane-benefit: no-propless " (fmt np 4)
                                     " ms/walk vs local " (fmt local 4)
                                     " — the short-circuit is worth " (fmt (- np local) 4) " ms/walk")))
              (js/console.log ";; ==== MICRO (ns/op over the page's own literal roster) ====")
              (doseq [[k v] micro]
                (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns")))
              (when (:refuse? gv)
                (set! (.-HICASSO_GUARD_REFUSED js/window) true))
              (lane/done!)))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
