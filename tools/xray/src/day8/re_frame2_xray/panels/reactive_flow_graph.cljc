(ns day8.re-frame2-xray.panels.reactive-flow-graph
  "Pure layout for the Views panel's left → right REACTIVE FLOW graph
  (rf2-ad7zx.6 · spec/021 §3.2 · Figma `design-reference/xray_devtools_reference.cljs`,
  the `views-panel` component).

  The Views panel renders the reactive event-bundle as a node-and-edge graph,
  not the prior three stacked tables. The event-bundle is a DAG flowing
  left → right across four columns:

      col 0  app-db        — a single source node at the far left
      col 1  Level-1 subs  — extractors (read app-db directly)
      col 2  Level-2+ subs — derived (`:inputs` composition; OPTIONAL layer)
      col 3  views         — the right-most focus; each (rerendered)

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
     whose `:deref-subs` hold it (rf2-3x7nj.24.3), and a row with no
     query-v falls back to the registration-level `:sub-readers` map
     (`{sub-id [view-id ...]}`). A sub read by ≥2 views is SHARED — its
     node carries `:shared-count`.
  3. `:rf.view/triggered-by` (the per-view cause sub) + `:rf.view/
     elapsed-ms` (render timing) ride each view row (rf2-8wrzz.1); the
     view node carries them through for the renderer's cause + timing
     labels.

  Pure data → geometry; JVM-portable (`.cljc`)."
  (:require [clojure.string :as str]))

;; ---- geometry constants ------------------------------------------------

(def node-w
  "Default node width (px). Views are a touch wider to fit the
  `(rerendered)` sub-label."
  120)

(def view-node-w 140)
(def node-h 30)
(def view-node-h 40)

(def ^:private row-gap 14)            ; vertical gap between sibling nodes in a column
(def ^:private col-gap 70)            ; horizontal gap between columns
(def ^:private pad-x 20)              ; left/right canvas padding
(def ^:private pad-y 16)              ; top canvas padding
(def ^:private appdb-w 80)

;; Column x-origins. app-db sits at pad-x; each subsequent column starts
;; after the previous column's widest node + the inter-column gap. The
;; Level-1 / Level-2 columns use `node-w`; views use `view-node-w`.
(def ^:private col-x
  {:appdb pad-x
   :l1    (+ pad-x appdb-w col-gap)
   :l2    (+ pad-x appdb-w col-gap node-w col-gap)
   :view  (+ pad-x appdb-w col-gap node-w col-gap node-w col-gap)})

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
  identity (rf2-3x7nj.24.3). A repeat of one identity in a column (the
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

(defn- col-nodes
  "Build the node maps for one sub column (`:l1` / `:l2`). Each row is the
  panel's sub-row map (`{:sub-id :query-v? :changed? :inputs?
  :input-query-vs? :coord? :readers?}`). `shared` is the set of sub-ids
  read by ≥2 views (the shared-sub set).

  rf2-3x7nj.24.3 — a row is one INSTANCE of a registration: three list
  rows reading `[:todo/by-id 1]` … `[:todo/by-id 3]` are three rows
  sharing `:sub-id :todo/by-id`. `:id` stays the registration id (label,
  testid, source link); `:instance` is the concrete query-v (or the bare
  `[sub-id]` shape when the row carries none) and keys the node and its
  edges, and a parameterized instance is labelled by its query-v so the
  boxes can be told apart."
  [rows kind canvas-mid shared]
  (let [x (get col-x kind)
        n (count rows)]
    (keyed
      (for [[i row] (map-indexed vector rows)
            :let [sid (:sub-id row)
                  qv  (:query-v row)]]
        {:kind        kind
         :id          sid
         :instance    (or qv [sid])
         :query-v     qv
         :slug        (slug sid)
         :label       (if (and (vector? qv) (next qv)) (pr-str qv) (id->str sid))
         :changed?    (boolean (:changed? row))
         :inputs      (vec (:inputs row))
         :input-query-vs (:input-query-vs row)
         :readers     (vec (:readers row))
         :coord       (:coord row)
         :shared-count (when (contains? shared sid)
                         (count (:readers row)))
         :x           x
         :y           (stack-y i node-h canvas-mid n)
         :w           node-w
         :h           node-h}))))

(defn- view-nodes
  "Build the view-column node maps. `view-rows` are render rows
  (`:action` ∈ #{:mount :rerender}; unmounts list separately). Each
  carries `:triggered-by` + `:elapsed-ms` (rf2-8wrzz.1) through for the
  renderer's cause + timing labels.

  rf2-3x7nj.24.3 — one row per rendered INSTANCE: `:instance` is its
  `:render-key` (`[view-id token]`, or `[view-id i]` without one) and
  `:deref-subs` its own read-set, which is what routes each sub
  instance's edge to the view instance that read it."
  [view-rows canvas-mid]
  (let [x (get col-x :view)
        n (count view-rows)]
    (keyed
      (for [[i row] (map-indexed vector view-rows)
            :let [vid (:view-id row)]]
        {:kind         :view
         :id           vid
         :instance     (or (:render-key row) [vid i])
         :deref-subs   (:deref-subs row)
         :slug         (slug vid)
         :label        (id->str vid)
         :action       (:action row)
         :triggered-by (:triggered-by row)
         :elapsed-ms   (:elapsed-ms row)
         :reason       (:reason row)
         :x            x
         :y            (stack-y i view-node-h canvas-mid n)
         :w            view-node-w
         :h            view-node-h}))))

;; ---- public layout -----------------------------------------------------

(defn shared-sub-set
  "Set of sub-ids read by TWO OR MORE views this event-bundle — the shared-
  subscription set. Reads each sub row's `:readers` (the views that
  deref it, from `:sub-readers` rf2-y23uw). Pure."
  [sub-rows]
  (into #{}
        (comp (filter (fn [r] (> (count (:readers r)) 1)))
              (map :sub-id))
        sub-rows))

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

  Nodes are INSTANCES (rf2-3x7nj.24.3): `:id` is the registration id,
  `:key` the unique instance key the renderer uses as the React key, and
  an edge's `:from-key` / `:to-key` name the instances it joins.

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
        shared    (shared-sub-set (concat l1-rows l2-rows))
        ;; Tallest column drives the canvas height so every column
        ;; centres on one mid-line.
        max-rows  (max 1 (count l1-rows) (count l2-rows) (count v-rows))
        canvas-h  (+ (* 2 pad-y)
                     (max node-h
                          (- (* max-rows (+ view-node-h row-gap)) row-gap)))
        canvas-mid (/ canvas-h 2.0)
        appdb     {:x (get col-x :appdb)
                   :y (- canvas-mid (/ node-h 2.0))
                   :w appdb-w :h node-h}
        l1        (col-nodes l1-rows :l1 canvas-mid shared)
        l2        (col-nodes l2-rows :l2 canvas-mid shared)
        views     (view-nodes v-rows canvas-mid)
        ;; rf2-3x7nj.24.3 — edges resolve to INSTANCES. Keying nodes by
        ;; registration id kept only the LAST instance per id, so every
        ;; edge into a list of one view landed on its last box. l1 + l2
        ;; share the sub-id key space; each id maps to all its instances.
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
        edges (vec (concat appdb-edges sub-sub-edges sub-view-edges))]
    {:width   (+ (get col-x :view) view-node-w pad-x)
     :height  canvas-h
     :appdb   appdb
     :nodes   {:l1 l1 :l2 l2 :view views}
     :edges   edges
     :empty?  (and (empty? l1-rows) (empty? l2-rows) (empty? v-rows))}))
