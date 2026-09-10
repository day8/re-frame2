(ns day8.re-frame2-xray.static.interceptors.panel
  "Top-level Interceptors sub-tab for Xray's Static surface.

  ## Pure-browse verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Interceptors sub-tab is a flat catalogue of every interceptor
  surfaced through registered events. An `:interceptors` chain entry is
  one of two shapes (EP-0022, Spec 002 §Interceptor references):

    - an INLINE interceptor VALUE — a map carrying `:id`, optional
      `:before`/`:after` fns, and the framework `:rf/default?` marker for
      auto-wrappers.
    - a REFERENCE into the `:interceptor` registrar — a bare keyword id
      (`:my/logging`) or a parameterized `[id arg]` 2-vector
      (`[:rf.interceptor/path [:cart]]`). The runtime resolves refs to
      executable values at chain assembly; this catalogue surfaces them
      by their AUTHORED form (the keyword / vector the event was
      registered with) and ENRICHES each with the registered descriptor
      (its `:before?`/`:after?`/`:doc`) when the id is registered.

      ┌───────────────────────────────────────────────────┐
      │ Interceptors — header + descriptive prose         │
      ├───────────────────────────────────────────────────┤
      │ Search: [_______________]           14 interceptors│
      ├───────────────────────────────────────────────────┤
      │ ▸ :rf/event-handler        before [default]       │
      │ ▸ :my/logging              before/after           │
      │ ▸ :rf.interceptor/path     ref · [:cart]          │
      └───────────────────────────────────────────────────┘

  ## Data source

  Walks `(rf/registrations {:source :store :kind :event})` and harvests the
  `:interceptors` chain from each entry; collapses by `:id` so an
  interceptor that appears on many chains shows up once with the count
  of chains it appears on. A REFERENCE entry contributes its referenced
  id (a `[id arg]` ref contributes the head keyword); an inline value
  with no `:id` (rare — `reg-interceptor` requires one) falls under
  `::unnamed`. Reference rows are enriched by resolving the authored ref
  through `(rf/handler-meta {:source :store :kind :interceptor :id id})` — the `:interceptor`
  registrar kind reg-interceptor populates (EP-0022) — so the catalogue
  shows the ref AND its resolved before/after hooks + doc.

  ## Pure-browse — no simulate

  Per the bead body: 'Pure-browse (no simulate-input — interceptors
  are composition; simulate fires through the handler-level simulate
  above).' The Events panel's hermetic simulate is the only simulate
  affordance in the Static surface.

  ## State slots (all under `:rf.xray.static.interceptors/*`)

    - `:rf.xray.static.interceptors/query`    — search input value.

  ## Public surface

  - `Panel`        — the tab's root. Since rf2-k97c.3 an
                     `rf.fresco/defview` BOUNDARY — a real React function
                     component, not an `rf/reg-view`.
  - `panel-tree`   — the whole body, as a pure fn of the read's VALUE and
                     a frame-bound dispatcher. `Panel` is the read plus a
                     call to this.
  - `install!`     — idempotent install for the subs, events and the L4
                     tab registration."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.catalogue :as catalogue]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn- default-resolve-ref
  "Resolve an authored interceptor reference (a bare keyword or `[id arg]`
  2-vector) to its registered descriptor metadata via the HOST app's
  `:interceptor` registrar — the kind reg-interceptor populates (EP-0022).
  Returns the metadata map (carrying the `:rf/interceptor-descriptor` slot) or
  nil when the id is not registered / the runtime can't answer. Pure-read,
  fail-soft: a browse catalogue must never throw on an unregistered or
  hot-reloaded ref.

  Read via `rf/handler-meta` with `{:source :store …}` (the SOURCE-STORE read, which never
  consults a bound image generation), NOT `{:frame …}`: this runs inside the
  interceptors `registry` sub COMPUTATION, and Xray seats in its OWN
  image-loaded `:rf/xray` frame, so the sub build binds the registrar to Xray's
  image generation — a bare read would resolve through Xray's OWN image and the
  ref enrichment would lose the host app's interceptor descriptors. See
  spec/API.md §Public registrar query API."
  [icpt-id]
  (rf/handler-meta {:source :store :kind :interceptor :id icpt-id}))

(defn- classify-entry
  "Classify one `:interceptors` chain entry (EP-0022, Spec 002 §Interceptor
  references) into a normalised descriptor map carrying:

    :id        — the interceptor id (the keyword head for a `[id arg]` ref;
                 `(:id entry)` for an inline value; `::unnamed` when absent).
    :ref?      — true when the entry is a REFERENCE (keyword / `[id arg]`).
    :authored  — the AUTHORED ref form (the keyword or vector) for a ref;
                 nil for an inline value.
    :arg       — the factory arg for a parameterized `[id arg]` ref.
    :before? :after? :default? :doc — surfaced from the inline value, or
                 (for a ref) resolved from its registered descriptor via
                 `resolve-ref-fn`.

  `resolve-ref-fn` maps an interceptor id → its registered metadata map (or
  nil); inline values do not call it."
  [entry resolve-ref-fn]
  (cond
    ;; INLINE interceptor value — a map. Checked FIRST: an inline value
    ;; is a map, never a keyword or [id arg] vector.
    (rf.interceptor-registry/interceptor-value? entry)
    {:id       (or (:id entry) ::unnamed)
     :ref?     false
     :authored nil
     :arg      nil
     :before?  (boolean (:before entry))
     :after?   (boolean (:after entry))
     :default? (boolean (:rf/default? entry))
     :doc      (:doc entry)}

    ;; REFERENCE — bare keyword or [id arg] 2-vector. Surface the authored
    ;; form and enrich from the registered descriptor when resolvable.
    (rf.interceptor-registry/interceptor-ref? entry)
    (let [vector-ref? (vector? entry)
          icpt-id     (if vector-ref? (first entry) entry)
          arg         (when vector-ref? (second entry))
          meta        (resolve-ref-fn icpt-id)
          descriptor  (:rf/interceptor-descriptor meta)]
      {:id       icpt-id
       :ref?     true
       :authored entry
       :arg      arg
       ;; A resolved descriptor carries the executable hooks; a `:factory`
       ;; descriptor's hooks are built per-arg, so report it as a factory
       ;; (both before+after possible) rather than guessing a hook shape.
       :before?  (boolean (when (map? descriptor) (:before descriptor)))
       :after?   (boolean (when (map? descriptor) (:after descriptor)))
       :factory? (boolean (when (map? descriptor) (:factory descriptor)))
       :default? (boolean (:rf/default? meta))
       :doc      (:doc meta)})

    ;; Structurally-malformed entry (neither a value nor a ref). Surface it
    ;; under ::unnamed rather than dropping it — a browse catalogue should
    ;; not silently swallow a registrar shape it doesn't recognise.
    :else
    {:id ::unnamed :ref? false :authored nil :arg nil
     :before? false :after? false :default? false :doc nil}))

(defn collect-interceptors
  "Walk every registered event's `:interceptors` chain and return a
  vector of row maps. Collapses by `:id` so an interceptor that appears
  on many chains lands as one row with `:chain-count` set.

  EP-0022 ref-aware: a chain entry may be an INLINE interceptor value OR a
  REFERENCE (bare keyword / `[id arg]`) into the `:interceptor` registrar.
  References are surfaced by their authored form (`:ref?`/`:authored`/`:arg`)
  and enriched with the registered descriptor's hooks + doc; inline values
  flow through as before. `resolve-ref-fn` (default `default-resolve-ref`)
  maps an interceptor id → its registered metadata map (or nil) so the pure
  helper stays testable without a live registrar."
  ([registrations-map] (collect-interceptors registrations-map default-resolve-ref))
  ([registrations-map resolve-ref-fn]
   (let [pairs (for [[event-id meta] registrations-map
                     entry          (or (:interceptors meta) [])]
                 [(classify-entry entry resolve-ref-fn) event-id])
         by-id (reduce
                 (fn [acc [info event-id]]
                   (-> acc
                       (update-in [(:id info) :sample] #(or % info))
                       (update-in [(:id info) :event-ids]
                                  (fnil conj #{}) event-id)))
                 {}
                 pairs)]
     (->> by-id
          (map (fn [[icpt-id {:keys [sample event-ids]}]]
                 {:id          icpt-id
                  :ref?        (boolean (:ref? sample))
                  :authored    (:authored sample)
                  :arg         (:arg sample)
                  :factory?    (boolean (:factory? sample))
                  :before?     (boolean (:before? sample))
                  :after?      (boolean (:after? sample))
                  :default?    (boolean (:default? sample))
                  :chain-count (count event-ids)
                  :doc         (:doc sample)}))
          (sort-by (fn [{:keys [id]}] (pr-str id)))
          vec))))

(defn- row-haystack [{:keys [id doc authored]}]
  (str/lower-case
    (str (pr-str id) " "
         (or doc "") " "
         ;; EP-0022 — a reference row is findable by its authored form too
         ;; (e.g. searching the factory arg `[:cart]`).
         (if (some? authored) (pr-str authored) ""))))

(defn filter-rows
  [rows query]
  (search-box/filter-rows row-haystack rows query))

(defn project-data
  "Project the interceptor rows for the browse from the `(rf/registrations {:source :store :kind :event})` map — collapses each event's `:interceptors` chain into a flat,
  per-id catalogue and filters by `query`."
  [registrations-map query]
  (let [rows     (collect-interceptors registrations-map)
        silent?  (empty? rows)
        filtered (filter-rows rows query)]
    {:silent?      silent?
     :interceptors filtered
     :total        (count rows)
     :filtered?    (not= (count rows) (count filtered))
     :query        query}))

;; ---- search box ----------------------------------------------------------

(defn- search-box
  ;; CALLED, never used as a hiccup head (rf2-k97c.3). `search-box/search-box`
  ;; is a plain fn, and a plain function in head position is a loud error
  ;; inside a Fresco body by design; applying it renders the identical
  ;; markup. The flex-row chrome still lives in the shared component.
  ;;
  ;; `dispatch` arrives from the boundary rather than being captured here.
  ;; The keystroke dispatch is an OUT-OF-RENDER affordance — it fires after
  ;; render unwinds, when the ambient frame is gone — so it must be bound to
  ;; a frame at render time; `Panel` binds it once with `rf/capture-frame`
  ;; and threads it down. nil is legal for a mount that never types.
  [dispatch query total filtered?]
  (search-box/search-box
    {:testid-prefix   "rf-xray-static-interceptors"
     :dispatch        dispatch
     :set-query-event :rf.xray.static.interceptors/set-query
     :placeholder     "interceptor-id or doc…"
     :value           query
     :count-noun      "interceptor"
     :total           total
     :filtered?       filtered?
     :count-min-width "90px"}))

;; ---- row -----------------------------------------------------------------

(defn- interceptor-row
  ;; Non-interactive catalogue entry (no row-level dispatch); the shared
  ;; `catalogue-row` owns the `role=listitem` `li` chrome.
  [{:keys [id ref? authored arg factory? before? after? default? chain-count
           doc] :as _row}]
  (let [id-text (pr-str id)
        row-id  (if (and (> (count id-text) 0) (= \: (first id-text)))
                  (subs id-text 1)
                  id-text)]
    (catalogue/catalogue-row
     {:testid (str "rf-xray-static-interceptors-row-" row-id)}
     [:div {:style {:display     "flex"
                    :align-items "baseline"
                    :gap         "8px"}}
      [:span {:style {:color       (:accent tokens)
                      :font-weight 500
                      :flex        1}}
       id-text]
      [:span {:title (str (cond
                            (and before? after?) "both before AND after"
                            before?              "before-only"
                            after?               "after-only"
                            :else                "neither hook")
                          " hook")
              :style {:color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size "10px"}}
       (cond
         factory?             "factory"
         (and before? after?) "before/after"
         before?              "before"
         after?               "after"
         :else                "—")]
      ;; EP-0022 — a REFERENCE row carries a "ref" badge so an author can
      ;; tell a by-reference chain entry from an inline interceptor value at
      ;; a glance; a parameterized `[id arg]` ref also shows its arg.
      (when ref?
        [:span {:data-testid (str "rf-xray-static-interceptors-ref-" row-id)
                :title (str "by-reference chain entry (resolved at chain "
                            "assembly from " (pr-str authored) ")")
                :style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "9px"
                        :padding "1px 4px"
                        :border (str "1px solid " (:text-tertiary tokens))
                        :border-radius "2px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
         "ref"])
      (when (and ref? (some? arg))
        [:span {:data-testid (str "rf-xray-static-interceptors-arg-" row-id)
                :title "factory reference arg"
                :style {:color (:text-tertiary tokens)
                        :font-family mono-stack
                        :font-size "10px"}}
         (pr-str arg)])
      (when default?
        [:span {:data-testid (str "rf-xray-static-interceptors-default-" row-id)
                :title "Framework-emitted auto-wrapper (rf2-twt7m)"
                :style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "9px"
                        :padding "1px 4px"
                        :border (str "1px solid " (:text-tertiary tokens))
                        :border-radius "2px"
                        :text-transform "uppercase"
                        :letter-spacing "0.5px"}}
         "default"])
      [:span {:title (str "appears on " chain-count " chain(s)")
              :style {:color (:text-tertiary tokens)
                      :font-family mono-stack
                      :font-size "10px"
                      :padding "0 4px"
                      :background (:bg-3 tokens)
                      :border (str "1px solid " (:border-subtle tokens))
                      :border-radius "3px"}}
       (str "x" chain-count)]]
     (when doc
       [:div {:style {:margin-left "12px"
                      :margin-top  "2px"
                      :color       (:text-secondary tokens)
                      :font-family sans-stack
                      :font-style  "italic"
                      :font-size   "11px"}}
        doc]))))

;; ---- the body, as a pure fn of the read's value ---------------------------

(defn panel-tree
  "The Static Interceptors tab's WHOLE body, as a pure function of the one
  value [[Panel]] reads — the `:rf.xray.static.interceptors/tab-data`
  composite — and the frame-bound `dispatch` the search box needs.

  SPLIT OUT OF [[Panel]] BY rf2-k97c.3, and the split is `defview`'s own
  documented extract-a-helper spelling rather than an invention. A
  boundary's body may only run inside a React render window, so `(Panel)`
  is no longer a callable that answers hiccup — while the catalogue's
  projection is ordinary data → data and is worth testing in the fast node
  lane. `panel_cljs_test` drives THIS fn with the value it takes from the
  sub directly; the boundary's own behaviour — first paint, liveness,
  frame targeting, evidence isolation, teardown and row identity — is
  `panel_fresco_boundary_dom_cljs_test`'s subject.

  PURE: every helper it calls is a plain fn of its arguments."
  [{:keys [silent? interceptors total filtered? query]} dispatch]
  (catalogue/catalogue-panel
   {:testid     "rf-xray-static-interceptors"
    :noun       "interceptor"
    :query      query
    :silent?    silent?
    :rows       interceptors
    :search     (search-box dispatch query total filtered?)
    ;; THE KEY RIDES ON A KEYED FRAGMENT, not on reader metadata
    ;; (rf2-k97c.3). Fresco's codec reads a literal `:key` from an
    ;; ATTRIBUTE MAP and reads Clojure metadata nowhere, so the
    ;; `^{:key …}` this line used to carry survives Reagent and reaches
    ;; React as NOTHING once the panel renders through the codec — a lost
    ;; key does not fail, it degrades silently into index-based
    ;; reconciliation. The fragment carries the key without adding a DOM
    ;; node, which is what keeps `catalogue-row`'s `li` chrome the shared
    ;; presentational helper it is: the key expression is unchanged, and
    ;; identity stays domain-shaped and local, exactly as
    ;; `catalogue-panel`'s `:row-render` contract asks.
    :row-render (fn [row]
                  [:<> {:key (pr-str (:id row))}
                   (interceptor-row row)])}))

;; ---- root view -----------------------------------------------------------

(rf.fresco/defview Panel
  "The Static Interceptors tab's root — a FRESCO BOUNDARY (rf2-k97c.3),
  not an `rf/reg-view`. Reads the interceptors composite and hands its
  value plus a frame-bound dispatcher to [[panel-tree]].

  The READ is `rf.fresco/sub`, a plain call the shipped collector records
  an edge for — no deref, no reaction owned by the installed adapter, and
  a re-wire that NOTIFIES when the substrate disposes the underlying
  derived value. That is the third of the epic's three couplings, and the
  one a first-paint smoke test cannot see.

  The FRAME the read resolves against comes from React context, which the
  enclosing frame boundary writes — `rf/frame-provider` and
  `rf.fresco/frame-provider` write the SAME context — so this resolves
  `:rf/xray` identically under today's Reagent-rendered Static shell and
  under the Fresco root Xray will own. It never consults
  `:adapter/current-component`, the hook a foreign root cannot answer.

  The DISPATCHER is `(:dispatch (rf/capture-frame))` — core's own door,
  which Fresco's authoring surface deliberately does not duplicate, and
  which answers the boundary's DECLARED frame inside a body. It replaces
  the render-time `(rf/current-frame-id)` capture the `reg-view` body did:
  same guarantee, one call, and it is the spelling every migrated panel
  now uses. The search box's keystroke dispatch therefore still lands on
  THIS Xray instance's frame after render scope unwinds rather than
  leaking to `:rf/default`.

  ONE read and ONE boundary. Boundary count tracks reads and
  head-position use, not file size: `catalogue-panel`, `catalogue-row`,
  `search-box` and `interceptor-row` are all CALLED, never used as a
  hiccup head, so Fresco's \"a plain function in head position is a loud
  error\" rule never meets one and nothing in the interior wants a
  boundary of its own.

  The argument is the ordinary one-props-map vector every `defview`
  takes. This panel reads nothing from props — the L4 registry mounts it
  with none — so it is destructured away."
  [_props]
  (panel-tree (rf.fresco/sub [:rf.xray.static.interceptors/tab-data])
              (:dispatch (rf/capture-frame))))

;; ---- the migration bridge (rf2-k97c.3) -----------------------------------
;;
;; Xray's Static shell is still a `reg-view` tree rendered by the installed
;; adapter. `static/shell.cljs`'s `detail-panel` mounts the active tab as
;; the hiccup head `[(:panel tab)]`, and `panel-registry/reg-l4-tab!`'s
;; `:pre` requires `:panel` to be CALLABLE — neither of which a React
;; component is.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent, UIx or plain JavaScript) mounts UNDER THE FRAME IT IS ALREADY
;; IN, taking the frame from React context rather than from a second root.
;; So there is no second root here, no adapter-kind branch, and no props
;; ABI.
;;
;; BOTH DEFS ARE PRIVATE, and that is a measured property of this panel
;; rather than a default: `Panel` is named nowhere outside this file — the
;; L4 registry is the only consumer, and `install!` below is the only
;; thing that passes the bridge. A panel carrying a standalone `mount-*!`
;; facade needs a PUBLIC bridge instead, because `panels/render-panel!`
;; takes the view to mount as an argument and needs a name to pass.
;;
;; THIS IS SCAFFOLDING WITH A DEFINED END. When the Static shell is itself
;; a Fresco tree, `reg-l4-tab!` takes `Panel` directly, `[:>]` goes, and
;; both defs below are deleted.

(def ^:private Panel-component
  "The React component `Panel` presents as, for a non-Fresco parent.
  Declared once at top level beside the view, as `rf.fresco/as-component`'s
  contract requires — deriving it per render would mint a new component
  type every time and remount the panel on each parent render."
  (rf.fresco/as-component Panel))

(defn ^:private Panel-bridge
  "The callable the L4 tab registry stores. Returns Reagent-shaped hiccup
  interoping to the React component above; the Static shell's enclosing
  `rf/frame-provider` is what puts `:rf/xray` in React context for it."
  []
  [:> Panel-component {}])

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated.

(defn- registry-value
  "The event-chain registry off the HOST app's `:event` registrar — each entry
  carries its interceptor chain. The test seam overrides this map directly.

  Read via `rf/registrations` with `{:source :store …}` (the SOURCE-STORE read, which never
  consults a bound image generation), NOT `{:frame …}`: this runs inside the
  `:rf.xray.static.interceptors/registry` sub COMPUTATION, and Xray seats in its
  OWN image-loaded `:rf/xray` frame, so the sub build binds the registrar to
  Xray's image generation — a bare read would resolve through Xray's OWN image
  and the interceptor browse would lose the host app's event chains. See
  spec/API.md §Public registrar query API."
  []
  (try (rf/registrations {:source :store :kind :event})
       (catch :default _ {})))

;; ---- registrations -------------------------------------------------------

(defn install!
  "Idempotent install for the Static Interceptors panel's subs + events.

  Registers:

    - `:rf.xray.static.interceptors/query`             — search input slot.
    - `:rf.xray.static.interceptors/set-query`         — search setter.
    - `:rf.xray.static.interceptors/registry-override` — test seam.
    - `:rf.xray.static.interceptors/set-registry-override-for-test`
        — test seam setter (payload shape: registrations map).
    - `:rf.xray.static.interceptors/registry`          — production data sub.
    - `:rf.xray.static.interceptors/tab-data`          — view-facing composite."
  []

  ;; ---- UI state ---------------------------------------------------------

  (rf/reg-event :rf.xray.static.interceptors/set-query
    (fn [{:keys [db]} [_ q]]
      {:db (if (or (nil? q) (= "" q))
        (dissoc db :rf.xray.static.interceptors/query)
        (assoc db :rf.xray.static.interceptors/query q))}))

  (rf/reg-sub :rf.xray.static.interceptors/query
    (fn [db _]
      (get db :rf.xray.static.interceptors/query)))

  ;; The test-only override seam (`:rf.xray.static.interceptors/set-
  ;; registry-override-for-test` + the `*-override` sub) is NOT installed
  ;; here — production registration carries no `-for-test` ids. Tests opt
  ;; into it via `install-test-overrides!`.

  ;; ---- production data sub ---------------------------------------------

  (rf/reg-sub :rf.xray.static.interceptors/registry
    {:inputs [[:rf.xray/trace-buffer]]}
    (fn [[_buffer] _query]
      (registry-value)))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.xray.static.interceptors/tab-data
    {:inputs [[:rf.xray.static.interceptors/registry] [:rf.xray.static.interceptors/query]]}
    (fn [[registrations-map query] _query]
      (project-data registrations-map query)))

  ;; Register the Static Interceptors tab. Contiguous order:
  ;; machines 0 · routes 1 · schemas 2 · flows 3 · interceptors 4.
  (panel-registry/reg-l4-tab!
    {:id    :interceptors
     :label "Interceptors"
     :mnem  "i"
     :modes #{:static}
     :order 4
     ;; rf2-k97c.3 — `Panel-bridge`, not `Panel`. `Panel` is now a React
     ;; component (a Fresco boundary) and the Static shell mounts `:panel`
     ;; as a Reagent hiccup head; the bridge is the one line between them
     ;; and goes when the shell is a Fresco tree.
     :panel Panel-bridge})

  nil)

;; ---- test-only override seam --------------------------------------------

(defn install-test-overrides!
  "Install the Static Interceptors panel's test-only override seam — the
  `:rf.xray.static.interceptors/set-registry-override-for-test` event +
  the `*-override` sub, then RE-register the production
  `:rf.xray.static.interceptors/registry` sub to layer the override read
  on top. Tests opt in via `test-support/install-test-overrides!` AFTER
  `register-xray-handlers!`. **Test-only — never call from production.**"
  []
  (rf/reg-event :rf.xray.static.interceptors/set-registry-override-for-test
    (fn [{:keys [db]} [_ ov]]
      {:db (if (nil? ov)
        (dissoc db :rf.xray.static.interceptors/registry-override)
        (assoc db :rf.xray.static.interceptors/registry-override ov))}))
  (rf/reg-sub :rf.xray.static.interceptors/registry-override
    (fn [db _]
      (get db :rf.xray.static.interceptors/registry-override)))

  (rf/reg-sub :rf.xray.static.interceptors/registry
    {:inputs [[:rf.xray/trace-buffer] [:rf.xray.static.interceptors/registry-override]]}
    (fn [[_buffer override] _query]
      (or override (registry-value))))
  nil)
