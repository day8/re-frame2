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
  and their canonical DOM compared (`rf.bench.hicasso.lane/canonical`, attribute names
  sorted), and the run is fatal on disagreement. A profile of a page that
  is not the acceptance page would be rf2-cvvb7's fault with extra steps.

  ## The arms

  Realized input (`rf.bench.hicasso.front.codec/realize-deep` outside the window) isolates the
  walk from the body's lazy tail; the one lazy arm prices that tail —
  on a mount the `for` seqs realize INSIDE `as-element`, so the
  mount-billed walk includes the card calls and their sub reads.

  | arm           | input    | what it prices |
  |---------------|----------|----------------|
  | `ship-lazy`   | lazy     | the mount-billed walk: interpretation PLUS the body's lazy rf.bench.hicasso.shapes.card/sub work |
  | `ship`        | realized | the shipping walk alone |
  | `local`       | realized | the in-namespace copy — the ablation baseline, and the in-process A/B's OLD arm |
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
  walk EMITS (`rf.bench.hicasso.front.codec/fold-shorthand!`), where the slot is already
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

  ## What `local` is frozen against (the PR #7383 audit)

  `local` is also the OLD arm of the in-process A/B, so the two paths
  the rf2-y1jkm candidate cheapened are held at their pre-candidate
  shape here rather than reached for in the codec:
  [[local-prop-cache]]/[[local-prop-name]] for the prop NAME, and
  [[local-convert-prop-value]] for the prop VALUE. A baseline that calls
  a helper the candidate changed absorbs the candidate and undersells
  it, which is what the merged-PR audit found at `walk-value`. What is
  deliberately NOT frozen, and why, is on
  [[local-convert-prop-value]]'s own docstring; both freezes are pinned
  by `walk_profile_baseline_cljs_test`, which fails if either arm
  re-enters the shipping helper.

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
  5. POSITIVE CONTROL — whether the instrument saw a change its own
     arithmetic predicts, adjudicated per round and printed pass or fail.
     [[tag-cache-floor-row]] prices `parse-raw`'s extra parses out of
     table 4 and requires the arms table to show at least that much;
     [[lazy-tail-direction-row]] requires the one by-construction
     ordering whose margin is big enough to assert. A failure sets
     `window.HICASSO_CONTROL_FAILED` and `run.cjs` exits 1 (rf2-1huc —
     before it, that exit path was dead for this arm, so tables 1-4 were
     guarded against ordering and page fidelity but not against the
     instrument having signal at all). A control whose own prediction
     collapses refuses under a SEPARATE heading rather than passing on a
     bar of zero, which is how the first cut of table 5 failed open
     (merged-PR audit #8149; see [[tag-cache-floor-row]]).

  Owner bead: rf2-y1jkm. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.walk-profile-app/-main."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.front.codec :as rf.bench.hicasso.front.codec]
            [re-frame.bench.hicasso.front.controlled :as rf.bench.hicasso.front.controlled]
            [re-frame.bench.hicasso.front.intent :as rf.bench.hicasso.front.intent]
            [re-frame.bench.hicasso.front.slot :as rf.bench.hicasso.front.slot]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.card :as rf.bench.hicasso.shapes.card]
            [re-frame.bench.hicasso.shapes.large-template :as rf.bench.hicasso.shapes.large-template]
            [re-frame.core :as rf]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; The page twin
;; ---------------------------------------------------------------------------

(def frame-id ::frame)

(defn page-hiccup
  "The large-template page's body forms, verbatim minus the run counter:
  the same chrome, the same `(rf.bench.hicasso.shapes.card/card slug)` calls, the same tag-pill
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
           (rf.bench.hicasso.shapes.card/card slug))]]
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
  (let [c-real (rf.bench.hicasso.arm1.mount/fresh-container!)
        c-twin (rf.bench.hicasso.arm1.mount/fresh-container!)
        h-real (rf.bench.hicasso.arm1.mount/root! c-real frame-id [rf.bench.hicasso.shapes.large-template/page {}])
        h-twin (rf.bench.hicasso.arm1.mount/root! c-twin frame-id [twin-page {}])
        canon-real (rf.bench.hicasso.lane/canonical c-real)
        canon-twin (rf.bench.hicasso.lane/canonical c-twin)
        n-real (rf.bench.hicasso.lane/element-count c-real)
        n-twin (rf.bench.hicasso.lane/element-count c-twin)
        expected (rf.bench.hicasso.shapes.large-template/element-arithmetic)]
    (rf.bench.hicasso.arm1.mount/unmount! h-real)
    (rf.bench.hicasso.arm1.mount/unmount! h-twin)
    (when-not (and (= canon-real canon-twin)
                   (= expected n-real n-twin))
      (throw (ex-info (str "twin parity FAILED: the profiled page is not the "
                           "acceptance page (real " n-real " twin " n-twin
                           " expected " expected " canonical "
                           (if (= canon-real canon-twin) "agrees" "DISAGREES") ")")
                      {:expected expected :real n-real :twin n-twin})))
    ;; `rf.bench.hicasso.lane/utf8-bytes` and not `count`: `-main` prints this as "canonical
    ;; bytes", and `count` answers UTF-16 code units (rf2-2rtt6.121).
    {:elements n-real :bytes (rf.bench.hicasso.lane/utf8-bytes canon-real)}))

;; ---------------------------------------------------------------------------
;; The body-context door
;; ---------------------------------------------------------------------------

(def ^:private !out (volatile! nil))

(defn in-body
  "Run `f` inside a boundary body context — ambient frame bound, reads
  legal — via the runtime's own public [[rf.bench.hicasso.arm1.runtime/render-body]], and answer
  `(f)`. The trailing `[:span]` is the body's element and is outside
  every timed window."
  [f]
  (rf.bench.hicasso.arm1.runtime/render-body frame-id (fn [_] (vreset! !out (f)) [:span]) {})
  @!out)

;; ---------------------------------------------------------------------------
;; The faithful local walk, with one `mode` switch per phase site
;; ---------------------------------------------------------------------------
;;
;; A copy of front.codec's emission path (native/fragment/boundary arms,
;; the donor's three createElement arities, the single-pass props reduce)
;; with an integer `mode` consulted at the five phase sites. `case` on an
;; int compiles to a JS switch; the full-default instance is validated
;; against `rf.bench.hicasso.front.codec/as-element` in the ARMS table rather than assumed
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
    (rf.bench.hicasso.front.intent/lower-prop k v)))

(declare local-convert-prop-value)

(defn- walk-value [mode v]
  (if (identical? mode M-NO-VALUE) v (local-convert-prop-value v)))

(def ^:private id-slot "id")
(def ^:private class-slot "className")

(defn- walk-fold-shorthand!
  "Fold the tag's `#id`/`.class` shorthand onto the object the walk just
  emitted — `rf.bench.hicasso.front.codec/fold-shorthand!`'s shape, which is private there — or
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
                           (rf.bench.hicasso.front.codec/class-names shorthand declared)))))
      o)))

(def ^:private reserved-names #{"__proto__" "prototype" "constructor"})

;; The local walk carries its OWN copy of the donor's prop-name cache —
;; the string-valued cache the codec shipped when this bead opened — so
;; the `local` arm stays the pre-optimisation walk even after the codec's
;; own cache changes shape. Without this the ablation baseline silently
;; absorbs half the candidate and the in-process A/B undersells it.
;; [[local-convert-prop-value]] below is the other half of the same
;; freeze; `walk_profile_baseline_cljs_test` pins both.
;;
;; What is frozen is the CACHE, never the rule. The name each arm
;; computes on a miss is [[re-frame.bench.hicasso.front.slot/prop-name]]
;; on both sides — freezing that too would make the A/B price a
;; difference in answers rather than a difference in lookups.
(def ^:private local-prop-cache
  (doto #js {}
    (unchecked-set "class" "className")
    (unchecked-set "for" "htmlFor")
    (unchecked-set "charset" "charSet")))

(def ^:private local-has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- local-prop-name [k]
  (if-not (or (keyword? k) (symbol? k))
    (if (string? k) (rf.bench.hicasso.front.slot/prop-name k) k)
    (let [n (name k)]
      (if (reserved-names n)
        (rf.bench.hicasso.front.slot/prop-name k)
        (if (.call local-has-own local-prop-cache n)
          (unchecked-get local-prop-cache n)
          (let [converted (rf.bench.hicasso.front.slot/prop-name k)]
            (unchecked-set local-prop-cache n converted)
            converted))))))

(defn- local-nested-map->js
  "The donor's `nested-map->js`, on the frozen name lookup and the frozen
  converter — `:style` and its kin."
  [m]
  (reduce-kv (fn [o k v] (unchecked-set o (local-prop-name k) (local-convert-prop-value v)) o)
             #js {}
             m))

(defn local-convert-prop-value
  "The donor's `convert-prop-value` **in the branch order the codec
  shipped when this bead opened** — before the candidate's `string?`
  fast lane (rf2-y1jkm).

  Frozen here for the reason [[local-prop-cache]] is: `local` is the
  ablation baseline AND the in-process A/B's old arm, and an old arm
  that calls the candidate's own converter absorbs the candidate and
  undersells it. PR #7383's audit named this exact site — the `local`
  walk reached straight into `rf.bench.hicasso.front.codec/convert-prop-value`, which that same
  PR changed, so the quoted old-vs-new figure was measured against a
  baseline the candidate had already reached into.

  The answer is unchanged for every input; only the order of the tests
  is. `walk_profile_baseline_cljs_test` asserts both halves of that —
  that this agrees with the shipping converter value for value, and that
  the baseline arm never enters the shipping one.

  ## What is NOT frozen, and why

  `merge-shorthand` and the map-copying `dissoc` the candidate also
  replaced were **deleted** from the codec by rf2-2rtt6.36, so there is
  no shipping shape left to copy and rf2-2rtt6.70 re-pointed
  [[walk-convert-props]] at the lanes that ship. `rf.bench.hicasso.front.codec/cached-parse`
  likewise keeps the candidate's cheaper reserved-name check, because
  the `parse-raw` benefit line prices the TAG CACHE and needs both its
  arms on one parse implementation. So `local` is the pre-optimisation
  walk in its prop-VALUE and prop-NAME paths, which is what the audit
  asked for, and not a frozen copy of the whole pre-PR codec — which is
  also why the reported `local/ship` line reads as an A/B ratio rather
  than as a fidelity check near 1.0."
  [v]
  (cond
    (fn? v)                       v
    (map? v)                      (local-nested-map->js v)
    (or (keyword? v) (symbol? v)) (name v)
    (coll? v)                     (clj->js v)
    :else                         v))

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
                 (rf.bench.hicasso.front.codec/merge-caller (or props {})))
      parsed)))

(defn- walk-parse [mode tag]
  (if (identical? mode M-PARSE-RAW)
    (rf.bench.hicasso.front.codec/parse-tag tag)
    (rf.bench.hicasso.front.codec/cached-parse tag)))

(defn- walk-native [mode argv]
  (let [parsed     (walk-parse mode (nth argv 0))
        has-props? (map? (nth argv 1 nil))
        props      (if has-props? (nth argv 1) nil)
        ;; nil, not `(or props {})` — the absent attribute map IS the
        ;; first lane, and wrapping it in an empty map is exactly what
        ;; hides the lane from the clock.
        js-props   (walk-convert-props mode props parsed)]
    (rf.bench.hicasso.front.controlled/install! (.-tag ^js parsed) js-props)
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (walk-make-element mode (.-tag ^js parsed) js-props argv (if has-props? 2 1))))

(defn- walk-boundary [mode argv]
  (let [has-props? (map? (nth argv 1 nil))
        props      (rf.bench.hicasso.front.codec/merge-caller (if has-props? (nth argv 1) {}))
        children   (rf.bench.hicasso.front.codec/realize-children argv (if has-props? 2 1))
        body-props (rf.bench.hicasso.front.codec/realize-deep (cond-> (dissoc props :key)
                                         children (assoc :children children)))
        head       (nth argv 0)
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    ;; The frame-as-a-prop variant's one emission cost (rf2-2rtt6.39).
    ;; Priced at nothing on THIS page — the census counts zero boundaries
    ;; — and copied anyway, so the arm stays a copy of what ships.
    (when (rf.bench.hicasso.front.codec/frame-prop-head? head)
      (unchecked-set js-props "rfFrame" rf.bench.hicasso.front.intent/*frame*))
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
      (rf.bench.hicasso.front.codec/boundary-head? head)  (walk-boundary mode argv)
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

(defn walk-arm
  "Run the local walk at `mode` over hiccup `h`. The arms table reaches
  [[walk-el]] directly; this exists so the witness set can run an arm
  from outside the namespace without the modes leaking any further."
  [mode h]
  (walk-el mode h))

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
                  (let [p (rf.bench.hicasso.front.codec/cached-parse head)]
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
  signal (the same argument as `rf.bench.hicasso.lane/mount-batch!`)."
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
    (rf.bench.hicasso.front.codec/realize-deep (aget pages i)))
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
            t0    (rf.bench.hicasso.lane/now-ms)]
        (dotimes [i walks-per-sample]
          (walk-one (aget pages i)))
        (- (rf.bench.hicasso.lane/now-ms) t0)))))

(def ^:private arms
  [{:id :ship-lazy   :realize? false :walk (fn [h] (rf.bench.hicasso.front.codec/as-element h))}
   {:id :ship        :realize? true  :walk (fn [h] (rf.bench.hicasso.front.codec/as-element h))}
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
        t0   (rf.bench.hicasso.lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget arr i)))))
    (let [ms (- (rf.bench.hicasso.lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(def ^:private micro-reps 300)

(defn- micro-table [{:keys [^js tags ^js keys ^js vals]}]
  (let [simple-props #js {:className "x"}
        fixed-names  (let [a #js []]
                       (dotimes [i (.-length keys)]
                         (.push a (name (aget keys i))))
                       a)]
    [[:cached-parse-hit    (ns-per-op micro-reps tags (fn [t] (rf.bench.hicasso.front.codec/cached-parse t)))]
     [:parse-tag-fresh     (ns-per-op (quot micro-reps 10) tags (fn [t] (rf.bench.hicasso.front.codec/parse-tag t)))]
     [:cached-prop-name    (ns-per-op micro-reps keys (fn [k] (rf.bench.hicasso.front.codec/cached-prop-name k)))]
     [:event-prop?-regex   (ns-per-op micro-reps keys (fn [k] (rf.bench.hicasso.front.intent/event-prop? k)))]
     [:reserved-set-lookup (ns-per-op micro-reps fixed-names (fn [n] (reserved-names n)))]
     [:reserved-identical  (ns-per-op micro-reps fixed-names
                                      (fn [n] (or (identical? "__proto__" n)
                                                  (identical? "prototype" n)
                                                  (identical? "constructor" n))))]
     [:convert-prop-value  (ns-per-op micro-reps vals (fn [v] (rf.bench.hicasso.front.codec/convert-prop-value v)))]
     [:create-element-min  (ns-per-op (quot micro-reps 10) tags
                                      (fn [_] (react/createElement "div" simple-props)))]]))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- arm-rows
  "Fold `rf.bench.hicasso.lane/rounds!` readings into per-arm p50/min/max (ms per WALK —
  the window is `walks-per-sample` walks)."
  [readings]
  (into {}
        (map (fn [id]
               (let [xs (mapcat #(get % id) readings)
                     per-walk (mapv #(/ % walks-per-sample) xs)]
                 [id (rf.bench.hicasso.lane/summarise per-walk)])))
        (map :id arms)))

(defn- phase-line [label ship-p50 base p50 elements]
  (let [d (- base p50)]
    (str ";;   " (name label) ": " (fmt p50 4) " ms/walk"
         "  delta " (fmt d 4) " ms"
         "  (" (fmt (* 1e6 (/ d elements)) 0) " ns/el, "
         (fmt (* 100 (/ d ship-p50)) 1) "% of ship)")))

;; ---------------------------------------------------------------------------
;; The positive control (rf2-1huc)
;; ---------------------------------------------------------------------------
;;
;; Until rf2-1huc this arm had none, so `run.cjs`'s `HICASSO_CONTROL_FAILED`
;; exit path was dead here: the run was guarded against ORDERING
;; (`rf.bench.hicasso.lane/guard!`, exit 2) and against PAGE FIDELITY ([[parity!]], fatal) but
;; not against the instrument HAVING SIGNAL AT ALL. A run whose ablations had
;; stopped biting would still print a full table and exit 0.

(defn- round-p50
  "Arm `id`'s p50 in ms per WALK inside ONE round.

  [[arm-rows]] pools every round before summarising, which is the right
  fold for a reported figure and the wrong one for a control: pooling is
  exactly how a good round vouches for a bad one."
  [round id]
  (:p50 (rf.bench.hicasso.lane/summarise (mapv #(/ % walks-per-sample) (get round id)))))

(defn- per-round-delta
  "`hi` minus `lo` in ms per walk, one number per round."
  [readings hi lo]
  (mapv (fn [r] (- (round-p50 r hi) (round-p50 r lo))) readings))

(def ^:private control-slack
  "How far BELOW its predicted floor the tag-cache delta may sit and still
  count as the instrument having seen what it predicts.

  Wider than the arithmetic's own error because the claim is `THE
  INSTRUMENT HAS SIGNAL`, not `THE MODEL IS EXACT`; narrow enough that an
  instrument reading near zero — the failure this exists to catch —
  cannot clear it. Measured headroom is a factor of ~2 (see
  [[tag-cache-floor-row]]), so the bar sits far from both edges."
  0.25)

(defn tag-cache-floor-row
  "THE CONTROL: `parse-raw` must cost at least what the page's own tag
  parses cost, as this run's OWN micro table prices them.

  `parse-raw` differs from `local` at exactly one site — [[walk-parse]]
  calls `rf.bench.hicasso.front.codec/parse-tag` fresh where `local` calls `rf.bench.hicasso.front.codec/cached-parse`
  — and it does so once per native element. The MICRO table times both
  primitives over [[collect-roster]]'s tags, which is that same
  population, element for element. So the extra work `parse-raw` does per
  walk is predicted, before its clock is read, as

      n-tags x (parse-tag-fresh - cached-parse-hit)

  ## Why a FLOOR and not a band

  The bead's candidate was `rf.bench.hicasso.lane/control-verdict` — a two-sided band
  around this prediction. Costed rather than assumed, it does not hold:
  the micro loop is the CHEAPEST possible arrangement of the same calls
  (one warm array, one call site, no allocation surviving the loop),
  while in the walk each fresh parse allocates an object the element then
  keeps. The walk-embedded cost is therefore bounded BELOW by the micro
  cost and not estimated by it, and it measures about twice it — 2.11x on
  this bead's calibration run, 1.81x on the 2026-08-14 re-take. A
  two-sided +/-slack band wide enough to contain 2x has its LOWER edge
  below zero, which is a control that cannot fail. One-sided is what the
  arithmetic actually supports, so one-sided is what this asserts.

  The population equality is checked rather than assumed: a roster that
  is not the walk's parse population makes the prediction per-tag over
  the wrong tags, and the number would still look plausible.

  ## Why EVERY ROUND and not an overlap

  `rf.bench.hicasso.lane/control-verdict` passes a control whose measured range merely
  OVERLAPS the band. Its disagreement with `hd8-rows/positive-control!`'s
  every-round-inside rule was rf2-egdaq, settled on 2026-08-21 as a
  SPLIT: the heap arm's ten published figures were re-adjudicated strict
  and all ten pass; the clock arm REFUSED strict for legs sitting on
  Chrome's 100 µs quantum, under the 2026-07-31 ruling, and that refusal
  stands there. This control inherits neither side of that: it is NEW,
  so it had no published row to re-adjudicate, and its windows are
  whole-page walks well clear of the quantum. It takes the stricter rule
  from birth, the way `hd8-rows` did and for the reason `hd8-rows` gives
  — a control whose worst round is wrong has caught something.

  ## Why a floor of zero or below REFUSES (rf2-1huc, merged-PR audit #8149)

  The first cut of this row asked only `worst >= bar` and shipped FAILING
  OPEN in the one direction its own subject makes reachable. `bar` is
  derived from `fresh - hit`, so if those two primitives CONVERGE the bar
  collapses to zero at the same moment the measured delta does — and
  `>= 0` is cleared by any reading whatever. Converging primitives are
  not an odd corner: they are exactly `parse-raw` having stopped being an
  ablation, which is the thing this row exists to catch. Worse in the
  other direction, a hit priced above a fresh parse puts the bar BELOW
  zero, where the slack widens the band downward instead of narrowing it.
  The audit reproduced both against the compiled function at merge
  825cd611c8; `walk_profile_control_cljs_test` pins both.

  So the prediction is required to STATE something before it is allowed
  to adjudicate anything: `:stated?` is `floor > 0`, and a row that does
  not state a prediction refuses under its own heading rather than
  passing on a vacuous bar. The planted-fault proof could never have
  found this — a mutation of the measured ARM leaves the micro table's
  primitive difference healthy — which is the argument for pinning it by
  arithmetic in the always-on suite instead."
  [readings census ^js tags micro]
  (let [m       (into {} micro)
        fresh   (:parse-tag-fresh m)
        hit     (:cached-parse-hit m)
        n-tags  (.-length tags)
        parses  (:native census)
        floor   (/ (* n-tags (- fresh hit)) 1e6)
        bar     (* floor (- 1.0 control-slack))
        deltas  (per-round-delta readings :parse-raw :local)
        worst   (apply min deltas)
        same?   (= n-tags parses)
        ;; STRICTLY positive, so a NaN micro row refuses here too rather
        ;; than sliding through a comparison that is false either way.
        stated? (pos? floor)]
    {:row        :tag-cache-floor
     :predicted  (rf.bench.hicasso.lane/round4 floor)
     :bar        (rf.bench.hicasso.lane/round4 bar)
     :slack      control-slack
     :stated?    stated?
     :population {:micro-roster n-tags :walk-parses parses}
     :per-round  (mapv rf.bench.hicasso.lane/round4 deltas)
     :worst      (rf.bench.hicasso.lane/round4 worst)
     :ok?        (and same? stated? (>= worst bar))
     :why        (cond
                   (not same?)
                   (str "the micro roster holds " n-tags " tags but the walk parses "
                        parses " — the prediction is per-tag over the roster, so a "
                        "roster that is not the walk's parse population prices the "
                        "wrong thing and no figure in this run is reportable")

                   (not stated?)
                   (str "the micro table prices a fresh parse at " (fmt fresh 1)
                        " ns against a cache hit at " (fmt hit 1) " ns over "
                        n-tags " tags, so the predicted floor is " (fmt floor 4)
                        " ms/walk and this control states no prediction. A floor "
                        "at or below zero predicts that parse-raw costs no more "
                        "than local, which puts the bar where every measurement "
                        "clears it — including the flat one this row exists to "
                        "catch. Converged primitives ARE the tag cache having "
                        "stopped mattering, so this refuses rather than passing "
                        "on a vacuous bar")

                   (>= worst bar)
                   (str n-tags " parses x " (fmt (- fresh hit) 1) " ns fresh-minus-cached "
                        "= floor " (fmt floor 4) " ms/walk, bar " (fmt bar 4)
                        " at -" (fmt (* 100 control-slack) 0) "%; worst round "
                        (fmt worst 4) " — every round clears it")

                   :else
                   (str n-tags " parses x " (fmt (- fresh hit) 1) " ns fresh-minus-cached "
                        "= floor " (fmt floor 4) " ms/walk, bar " (fmt bar 4)
                        "; worst round " (fmt worst 4) " is BELOW it. The walk cannot "
                        "see the cost of work it is demonstrably doing, so no phase "
                        "delta in this run is reportable"))}))

(defn lazy-tail-direction-row
  "The other prediction that needs no clock to state: `ship-lazy` walks
  the same page as `ship` and realizes the body's lazy tail INSIDE the
  window as well, so it does strictly more work, so it must read higher —
  in every round, not on the pooled median.

  `no-propless` is the third arm that does more work by construction and
  it is deliberately NOT asserted here. There is no arithmetic that
  predicts what the propless short-circuit is worth, so the only
  available rule is a bare direction test on a margin measured at ~5% of
  `local` — inside the arm-order guard's own 10% tolerance. A refusal
  that fires on noise is not a control, and this instrument would rather
  carry two checks that bite than three of which one cries."
  [readings]
  (let [deltas (per-round-delta readings :ship-lazy :ship)
        worst  (apply min deltas)]
    {:row       :lazy-tail-direction
     :per-round (mapv rf.bench.hicasso.lane/round4 deltas)
     :worst     (rf.bench.hicasso.lane/round4 worst)
     :ok?       (pos? worst)
     :why       (if (pos? worst)
                  (str "ship-lazy above ship in all " (count deltas)
                       " rounds, worst margin " (fmt worst 4) " ms/walk")
                  (str "ship-lazy did NOT read above ship in every round (worst "
                       (fmt worst 4) " ms/walk) — the lazy arm does strictly more "
                       "work than the eager one by construction, so an inversion "
                       "means the window is not pricing the walk"))}))

(defn control-status
  "The label one control row prints under. THREE outcomes, not two,
  because a refusal that names no cause sends the operator to the wrong
  place: `FAILED` means the arms did not show what the arithmetic
  predicted and the repair is the ARM, while `REFUSED — no prediction`
  means the arithmetic predicted nothing at all and the repair is the
  MICRO TABLE that priced it (rf2-1huc). Rows that state no prediction —
  [[lazy-tail-direction-row]], whose claim is a bare ordering — carry no
  `:stated?` and read as the two-outcome rows they are."
  [{:keys [ok? stated?]}]
  (cond
    ok?              "ok"
    (false? stated?) "REFUSED — no prediction"
    :else            "FAILED"))

(defn- control-report!
  "Print every control row, passing or not. A control quoted only when it
  passes is not a control."
  [rows]
  (js/console.log ";; ==== POSITIVE CONTROL (does this instrument see a change it predicts?) ====")
  (doseq [{:keys [row why] :as r} rows]
    (js/console.log (str ";;   " (name row) ": " (control-status r) " — " why))))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf.bench.hicasso.shapes.large-template/make-frame! frame-id)
          (rf.bench.hicasso.shapes.large-template/reseed! frame-id)
          (let [{:keys [elements bytes]} (parity!)]
            (js/console.log (str ";; twin parity OK — " elements " elements, "
                                 bytes " canonical bytes, identical to rf.bench.hicasso.shapes.large-template/page"))
            ;; The census + rosters, from one realized page.
            (let [page (in-body (fn [] (rf.bench.hicasso.front.codec/realize-deep (page-hiccup))))
                  cs   (census page)
                  roster (collect-roster page)
                  ;; The interleaved rounds.
                  {:keys [readings samples]}
                  (rf.bench.hicasso.lane/rounds! arms sampling rounds
                                (fn [{:keys [realize? walk]}]
                                  (timed-walks realize? walk)))
                  rows (arm-rows readings)
                  gv   (rf.bench.hicasso.lane/guard! samples "walk-profile arms (in-page ms, diagnostic)")
                  ship (:p50 (get rows :ship))
                  local (:p50 (get rows :local))
                  micro (micro-table roster)
                  ;; AFTER the micro table, because the control's prediction
                  ;; is made out of it — this run's own per-tag prices, not a
                  ;; constant carried from a previous one.
                  control [(tag-cache-floor-row readings cs (:tags roster) micro)
                           (lazy-tail-direction-row readings)]]
              (rf.bench.hicasso.lane/record! :walk-profile-census cs)
              (rf.bench.hicasso.lane/record! :walk-profile-arms
                            (into {} (map (fn [[k v]] [k (-> v (update :min rf.bench.hicasso.lane/round4)
                                                             (update :max rf.bench.hicasso.lane/round4)
                                                             (update :p50 rf.bench.hicasso.lane/round4))])) rows))
              (rf.bench.hicasso.lane/record! :walk-profile-micro
                            (into {} (map (fn [[k v]] [k (rf.bench.hicasso.lane/round4 v)])) micro))
              (rf.bench.hicasso.lane/record! :walk-profile-control control)
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
              (control-report! control)
              (when (:refuse? gv)
                (set! (.-HICASSO_GUARD_REFUSED js/window) true))
              ;; `run.cjs` turns this into exit 1. Until rf2-1huc nothing in
              ;; this file ever set it, so that exit was unreachable here.
              (when-not (every? :ok? control)
                (set! (.-HICASSO_CONTROL_FAILED js/window) true))
              (rf.bench.hicasso.lane/done!)))))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))
