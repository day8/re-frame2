(ns day8.re-frame2-xray.panels.reactive-flow-graph
  "Pure layout for the Views panel's left → right REACTIVE FLOW graph
  (spec/021 §3.2 · Figma `design-reference/xray_devtools_reference.cljs`,
  the `views-panel` component).

  The Views panel renders the reactive event-bundle as a node-and-edge
  graph. The event-bundle is a DAG flowing left → right:

      col 0     app-db        — a single source node at the far left
      col 1     Level-1 subs  — extractors (read app-db directly)
      col 2..N  Level-2+ subs — derived (`:inputs` composition), each in the
                                column one past its deepest input, so every
                                sub→sub edge runs rightward
      col N+1   views         — the right-most focus; each (rerendered)

  ## Why a pure layout ns

  Mirrors `chart/timing-waterfall`: a pure data → geometry function
  (`layout`) that the view ns renders as inline-SVG hiccup, and a
  `clojure -M:test` suite exercises on the JVM without a browser. The
  layout owns ALL geometry (node x/y/w/h, edge endpoints, the changed/
  unchanged edge style) so the renderer is a flat positional walk and
  the structure is unit-testable apart from SVG paint.

  This is a XRAY-NATIVE SVG graph — NOT xyflow. The Machine panel's
  xyflow integration (spec/021 §6) is a separate, interactive,
  draggable surface; the reactive-flow graph is a static cause/timing
  snapshot per the spec + reference, so it stays pure-SVG.

  ## Edge encoding (spec/021 §3.2 · spec/022 — colour/edge, NOT glyphs)

  - **changed / recomputed** node → its outgoing edges are SOLID,
    accent-coloured, arrow-headed, and PROPAGATE downstream.
  - **unchanged / short-circuited** node → its edges are DASHED, dim,
    and visually CUT (downstream did not re-run). Each `:edge` carries
    `:changed?` so the renderer picks the stroke/dash/marker.

  ## Data-availability constraints (spec/021 §3.5)

  1. app-db → Level-1 is a PLAIN fan-out (re-frame subs read app-db
     imperatively; no per-path edges) — one source node, one edge per
     Level-1 sub.
  2. The sub→view edges come from the substrate's per-view deref-set:
     each sub INSTANCE (concrete query-v) routes to the view instances
     whose `:deref-subs` hold it, and a row with no
     query-v falls back to the registration-level `:sub-readers` map
     (`{sub-id [view-id ...]}`). A sub instance with edges to ≥2 view
     instances is SHARED — its node carries `:shared-count`, that edge count.
  3. `:rf.view/triggered-by` (the per-view cause sub) + `:rf.view/
     elapsed-ms` (render timing) ride each view row; the
     view node carries them through for the renderer's cause + timing
     labels.

  Pure data → geometry; JVM-portable (`.cljc`)."
  (:require [clojure.string :as str]))

;; ---- geometry constants ------------------------------------------------

(def node-w
  "Default node width (px). Views are wider to fit the
  `(rerendered)  ← cause · timing` sub-label."
  120)

(def view-node-w 200)
(def node-h 30)
(def view-node-h 40)

(def ^:private row-gap 14)            ; vertical gap between sibling nodes in a column
(def ^:private col-gap 70)            ; horizontal gap between columns
(def ^:private pad-x 20)              ; left/right canvas padding
(def ^:private pad-y 16)              ; top canvas padding
(def ^:private appdb-w 80)

(def label-font-size
  "Font size (px) of a node's name label."
  11)

(def meta-font-size
  "Font size (px) of a view node's `(rerendered)` cause/timing line."
  9)

(def mono-advance
  "One glyph's advance, in ems, that a label is budgeted at. Every face in
  the mono stack (JetBrains Mono, SF Mono, Menlo) sets 0.6em, so a mono
  label budgeted at it fits exactly; the Inter meta line is narrower than
  that on average, so for it the budget is conservative."
  0.6)

(def ^:private label-inset 3)          ; px kept clear each side of a label

;; Column x-origins. app-db sits at pad-x; each subsequent column starts
;; after the previous column's node width + the inter-column gap. Sub
;; columns use `node-w`; views use `view-node-w`.
(defn- sub-col-x
  "x-origin of sub column `depth` (1 = Level-1)."
  [depth]
  (+ pad-x appdb-w col-gap (* (dec depth) (+ node-w col-gap))))

;; ---- pure helpers ------------------------------------------------------

(defn- id->str
  [id]
  (cond
    (nil? id)     ""
    (keyword? id) (str id)
    :else         (pr-str id)))

(defn- slug
  "CSS-selector-safe testid suffix — id punctuation flattened to `_`."
  [id]
  (when id (str/replace (str id) #"[^a-zA-Z0-9_]" "_")))

(defn- max-chars
  "How many glyphs fit a box `w` px wide at `font-size` px."
  [w font-size]
  (max 1 (int (/ (- w (* 2 label-inset)) (* mono-advance font-size)))))

(defn fit-label
  "`label` as it fits a node `w` px wide at `font-size` px: whole when it
  fits, otherwise its TAIL behind a leading `…`, because the tail is what
  tells two ids apart (`:app/by-id` and `:app/by-name` share their head).
  SVG text does not clip to its box, so an unfitted label paints across
  its neighbours; the renderer carries the whole label as the node's
  tooltip."
  [label w font-size]
  (let [s (str label)
        n (max-chars w font-size)]
    (if (<= (count s) n)
      s
      (str "…" (subs s (- (count s) (dec n)))))))

(defn fit-line
  "`line` as it fits a box `w` px wide at `font-size` px, keeping its HEAD
  with a trailing `…` — for a line that reads left to right, such as a
  view's `(rerendered)  ← cause · timing`."
  [line w font-size]
  (let [s (str line)
        n (max-chars w font-size)]
    (if (<= (count s) n)
      s
      (str (subs s 0 (dec n)) "…"))))

(defn- stack-y
  "Vertical centre-y for the i-th node in a column of `n` nodes, using
  `h` per node, vertically centred around `canvas-mid`. Returns the node
  TOP-y (not centre) so the renderer positions the `<rect>` directly."
  [i h canvas-mid n]
  (let [block-h (- (* n (+ h row-gap)) row-gap)
        top     (- canvas-mid (/ block-h 2.0))]
    (+ top (* i (+ h row-gap)))))

(defn- keyed
  "Stamp each node's `:key` — its React key — from its `:instance`
  identity. A repeat of one identity in a column (the
  same query-v run twice in an epoch) is disambiguated by occurrence, so
  sibling keys are unique by construction."
  [nodes]
  (first
    (reduce (fn [[out seen] node]
              (let [k (pr-str (:instance node))
                    c (get seen k 0)]
                [(conj out (assoc node :key (if (zero? c) k (str k "#" c))))
                 (assoc seen k (inc c))]))
            [[] {}]
            nodes)))

(defn- input-id
  "The registration id a declared input names: a bare id, or the head of
  a query-v."
  [input]
  (if (vector? input) (first input) input))

(defn- row-inputs [row]
  (or (:input-query-vs row) (:inputs row)))

(defn- l2-depths
  "Each Level-2 row's sub column, in row order: one past the deepest sub
  it reads, where a Level-1 sub (or an input not in this bundle) is
  column 1. A chain `:a → :b → :c` therefore lays out across three
  columns rather than drawing `:b → :c` backwards inside one."
  [l2-rows]
  (let [by-id (group-by :sub-id l2-rows)
        memo  (volatile! {})
        depth (fn depth [sid seen]
                (cond
                  (not (contains? by-id sid)) 1
                  ;; a malformed cycle stops rather than recursing for ever
                  (contains? seen sid)        1
                  :else
                  (or (get @memo sid)
                      (let [d (inc (reduce max 1
                                           (for [r  (get by-id sid)
                                                 in (row-inputs r)]
                                             (depth (input-id in) (conj seen sid)))))]
                        (vswap! memo assoc sid d)
                        d))))]
    (mapv (fn [row]
            (inc (reduce max 1 (map #(depth (input-id %) #{(:sub-id row)})
                                    (row-inputs row)))))
          l2-rows)))

(defn- col-nodes
  "Build the node maps for the sub rows `rows` (Level-1 or Level-2), each
  placed at `(col-of i)` and stacked among the rows sharing its column.
  Each row is the panel's sub-row map (`{:sub-id :query-v? :changed?
  :inputs? :input-query-vs? :coord? :readers?}`).

  A row is one INSTANCE of a registration: three list
  rows reading `[:todo/by-id 1]` … `[:todo/by-id 3]` are three rows
  sharing `:sub-id :todo/by-id`. `:id` stays the registration id (label,
  testid, source link); `:instance` is the concrete query-v (or the bare
  `[sub-id]` shape when the row carries none) and keys the node and its
  edges, and a parameterized instance is labelled by its query-v so the
  boxes can be told apart."
  [rows kind col-of canvas-mid]
  (let [cols     (mapv col-of (range (count rows)))
        col-size (frequencies cols)]
    (keyed
      (first
        (reduce
          (fn [[out seen] [i row]]
            (let [col   (nth cols i)
                  slot  (get seen col 0)
                  sid   (:sub-id row)
                  qv    (:query-v row)
                  label (if (and (vector? qv) (next qv)) (pr-str qv) (id->str sid))]
              [(conj out
                     {:kind        kind
                      :id          sid
                      :instance    (or qv [sid])
                      :query-v     qv
                      :slug        (slug sid)
                      :label       label
                      :display-label (fit-label label node-w label-font-size)
                      :changed?    (boolean (:changed? row))
                      :inputs      (vec (:inputs row))
                      :input-query-vs (:input-query-vs row)
                      :readers     (vec (:readers row))
                      :coord       (:coord row)
                      :x           (sub-col-x col)
                      :y           (stack-y slot node-h canvas-mid (get col-size col))
                      :w           node-w
                      :h           node-h})
               (assoc seen col (inc slot))]))
          [[] {}]
          (map-indexed vector rows))))))

(defn- view-nodes
  "Build the view-column node maps. `view-rows` are render rows
  (`:action` ∈ #{:mount :rerender}; unmounts list separately). Each
  carries `:triggered-by` + `:elapsed-ms` through for the
  renderer's cause + timing labels.

  One row per rendered INSTANCE: `:instance` is its
  `:render-key` (`[view-id token]`, or `[view-id i]` without one) and
  `:deref-subs` its own read-set, which is what routes each sub
  instance's edge to the view instance that read it."
  [view-rows x canvas-mid]
  (let [n (count view-rows)]
    (keyed
      (for [[i row] (map-indexed vector view-rows)
            :let [vid (:view-id row)]]
        {:kind         :view
         :id           vid
         :instance     (or (:render-key row) [vid i])
         :deref-subs   (:deref-subs row)
         :slug         (slug vid)
         :label        (id->str vid)
         :display-label (fit-label (id->str vid) view-node-w label-font-size)
         :action       (:action row)
         :triggered-by (:triggered-by row)
         :elapsed-ms   (:elapsed-ms row)
         :reason       (:reason row)
         :x            x
         :y            (stack-y i view-node-h canvas-mid n)
         :w            view-node-w
         :h            view-node-h}))))

;; ---- public layout -----------------------------------------------------

(defn layout
  "Compute the full node + edge geometry for the reactive-flow graph.

  Input is the panel's projected reactive-data slice:

      {:level-1-subs [{:sub-id :query-v? :changed? :coord? :readers?} ...]
       :level-2-subs [{:sub-id :query-v? :changed? :inputs :input-query-vs?
                       :coord? :readers?} ...]
       :view-rows    [{:view-id :render-key? :deref-subs? :action :reason
                       :triggered-by? :elapsed-ms?} ...]}

  Returns:

      {:width   <px>
       :height  <px>
       :appdb   {:x :y :w :h}
       :nodes   {:l1 [node ...] :l2 [node ...] :view [node ...]}
       :edges   [{:from-id :to-id :from-key :to-key :x1 :y1 :x2 :y2
                  :changed? :kind} ...]
       :empty?  <bool>}      ; true when no subs ran AND no views rendered

  Nodes are INSTANCES: `:id` is the registration id,
  `:key` the unique instance key the renderer uses as the React key, and
  an edge's `:from-key` / `:to-key` name the instances it joins. A sub
  node's `:shared-count` counts its own sub→view edges, so it agrees with
  the edges drawn.

  Edge `:kind` ∈ #{:appdb-l1 :sub-sub :sub-view}; `:changed?` drives the
  solid-accent vs dashed-dim/cut styling. View rows with `:action
  :unmount` are excluded from the graph (they list in the UNMOUNTED
  VIEWS section). Pure fn — JVM-runnable."
  [{:keys [level-1-subs level-2-subs view-rows]}]
  (let [l1-rows   (vec (or level-1-subs []))
        l2-rows   (vec (or level-2-subs []))
        ;; unmounts list in their own section — the graph shows the
        ;; live render event-bundle only.
        v-rows    (filterv #(not= :unmount (:action %)) (or view-rows []))
        depths    (l2-depths l2-rows)
        ;; The derived column stays reserved when no Level-2 sub ran, so
        ;; the view column does not jump left between bundles.
        view-x    (sub-col-x (inc (reduce max 2 depths)))
        ;; Tallest column drives the canvas height so every column
        ;; centres on one mid-line.
        max-rows  (reduce max 1 (concat [(count l1-rows) (count v-rows)]
                                        (vals (frequencies depths))))
        canvas-h  (+ (* 2 pad-y)
                     (max node-h
                          (- (* max-rows (+ view-node-h row-gap)) row-gap)))
        canvas-mid (/ canvas-h 2.0)
        appdb     {:x pad-x
                   :y (- canvas-mid (/ node-h 2.0))
                   :w appdb-w :h node-h}
        l1        (col-nodes l1-rows :l1 (constantly 1) canvas-mid)
        l2        (col-nodes l2-rows :l2 depths canvas-mid)
        views     (view-nodes v-rows view-x canvas-mid)
        ;; Edges resolve to INSTANCES. Keying nodes by registration id
        ;; would keep only the LAST instance per id, so every edge into a
        ;; list of one view would land on its last box. l1 + l2 share the
        ;; sub-id key space; each id maps to all its instances.
        subs-of    (group-by :id (concat l1 l2))
        ;; The source instances for one declared input: the instance whose
        ;; identity IS the declared query-v, or — an input known by id
        ;; alone — every instance of that registration.
        sources    (fn [input]
                     (if (vector? input)
                       (filter #(= input (:instance %)) (get subs-of (first input)))
                       (get subs-of input)))
        ;; The view instances a sub instance drives: those whose own
        ;; read-set holds its query-v. Only a row with no query-v falls
        ;; back to the registration-level `:readers` (every instance of a
        ;; reading view).
        targets    (fn [{:keys [query-v readers]}]
                     (if query-v
                       (filter (fn [v]
                                 (some #(= query-v (if (vector? %) % [%]))
                                       (:deref-subs v)))
                               views)
                       (let [rdrs (set readers)]
                         (filter #(contains? rdrs (:id %)) views))))
        ;; right-mid / left-mid anchor of a node (edges run rect→rect).
        rmid      (fn [n] [(+ (:x n) (:w n)) (+ (:y n) (/ (:h n) 2.0))])
        lmid      (fn [n] [(:x n) (+ (:y n) (/ (:h n) 2.0))])
        ;; 1. app-db → each Level-1 sub (plain fan-out; cut if unchanged).
        appdb-edges
        (for [n l1
              :let [[x1 y1] (rmid appdb) [x2 y2] (lmid n)]]
          {:from-id :appdb :to-id (:id n) :to-key (:key n)
           :x1 x1 :y1 y1 :x2 x2 :y2 y2
           :changed? (:changed? n) :kind :appdb-l1})
        ;; 2. input-sub → Level-2 sub (the `:inputs` composition chain).
        ;;    Edge changed when the UPSTREAM input sub changed. The full
        ;;    declared query-vs name the input INSTANCE; the ids are the
        ;;    fallback when a row carries only those.
        sub-sub-edges
        (for [n l2
              input (or (:input-query-vs n) (:inputs n))
              src (sources input)
              :let [[x1 y1] (rmid src) [x2 y2] (lmid n)]]
          {:from-id (:id src) :to-id (:id n)
           :from-key (:key src) :to-key (:key n)
           :x1 x1 :y1 y1 :x2 x2 :y2 y2
           :changed? (:changed? src) :kind :sub-sub})
        ;; 3. sub → view (the reader edge; shared subs fan out to N views).
        ;;    Edge changed when the SUB changed (drove the re-render).
        sub-view-edges
        (for [n (concat l1 l2)
              tgt (targets n)
              :let [[x1 y1] (rmid n) [x2 y2] (lmid tgt)]]
          {:from-id (:id n) :to-id (:id tgt)
           :from-key (:key n) :to-key (:key tgt)
           :x1 x1 :y1 y1 :x2 x2 :y2 y2
           :changed? (:changed? n) :kind :sub-view})
        edges (vec (concat appdb-edges sub-sub-edges sub-view-edges))
        fan   (frequencies (map :from-key sub-view-edges))
        shared (fn [nodes]
                 (mapv #(let [c (get fan (:key %) 0)]
                          (assoc % :shared-count (when (> c 1) c)))
                       nodes))]
    {:width   (+ view-x view-node-w pad-x)
     :height  canvas-h
     :appdb   appdb
     :nodes   {:l1 (shared l1) :l2 (shared l2) :view views}
     :edges   edges
     :empty?  (and (empty? l1-rows) (empty? l2-rows) (empty? v-rows))}))
