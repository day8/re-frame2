(ns day8.re-frame2-xray.static.interceptors.panel
  "Top-level Interceptors sub-tab for Xray's Static surface (rf2-o5f5f.6).

  ## Pure-browse verb

  Per Lock #15 (two-verbs-two-homes — browse-all lives in Static) the
  Interceptors sub-tab is a flat catalogue of every interceptor
  surfaced through registered events. An `:interceptors` chain entry is
  one of two shapes (EP-0022, Spec 002 §Interceptor references):

    - an INLINE interceptor VALUE — a map carrying `:id`, optional
      `:before`/`:after` fns, and the framework `:rf/default?` marker for
      auto-wrappers (rf2-twt7m); the additive-window legacy shape.
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

  Walks `(rf/registrations :event)` and harvests the
  `:interceptors` chain from each entry; collapses by `:id` so an
  interceptor that appears on many chains shows up once with the count
  of chains it appears on. A REFERENCE entry contributes its referenced
  id (a `[id arg]` ref contributes the head keyword); an inline value
  with no `:id` (rare — `reg-interceptor` requires one) falls under
  `::unnamed`. Reference rows are enriched by resolving the authored ref
  through `(rf/handler-meta :interceptor id)` — the `:interceptor`
  registrar kind reg-interceptor populates (EP-0022) — so the catalogue
  shows the ref AND its resolved before/after hooks + doc.

  ## Pure-browse — no simulate

  Per the bead body: 'Pure-browse (no simulate-input — interceptors
  are composition; simulate fires through the handler-level simulate
  above).' The Events panel's hermetic simulate is the only simulate
  affordance in the Static surface.

  ## State slots (all under `:rf.xray.static.interceptors/*`)

    - `:rf.xray.static.interceptors/query`    — search input value."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interceptor-registry :as icpt-reg]
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.static.shared.search-box :as search-box]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale mono-stack sans-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn- default-resolve-ref
  "Resolve an authored interceptor reference (a bare keyword or `[id arg]`
  2-vector) to its registered descriptor metadata via the HOST app's
  `:interceptor` registrar — the kind reg-interceptor populates (EP-0022).
  Returns the metadata map (carrying the `:rf/interceptor-descriptor` slot) or
  nil when the id is not registered / the runtime can't answer. Pure-read,
  fail-soft: a browse catalogue must never throw on an unregistered or
  hot-reloaded ref.

  Read via `host-registry/handler-meta` (the generation-bypassing process-global
  form), NOT a bare `(rf/handler-meta :interceptor id)`: this runs inside the
  interceptors `registry` sub COMPUTATION, and Xray seats in its OWN
  image-loaded `:rf/xray` frame, so the sub build binds the registrar to Xray's
  image generation — a bare read would resolve through Xray's OWN image and the
  ref enrichment would lose the host app's interceptor descriptors. See
  `day8.re-frame2-xray.host-registry`."
  [icpt-id]
  (host-registry/handler-meta :interceptor icpt-id))

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
    ;; INLINE interceptor value — the additive-window legacy shape. Checked
    ;; FIRST: an inline value is a map, never a keyword or [id arg] vector.
    (icpt-reg/interceptor-value? entry)
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
    (icpt-reg/interceptor-ref? entry)
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
  "Project the interceptor rows for the browse from the `(rf/registrations
  :event)` map — collapses each event's `:interceptors` chain into a flat,
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

;; ---- header --------------------------------------------------------------

(defn- header
  []
  ;; rf2-6xezz — Mike-direction 2026-05-21: panel-name heading scrubbed.
  [:div {:data-testid "rf-xray-static-interceptors-header"
         :style       {:padding "4px 16px"}}])

;; ---- search box ----------------------------------------------------------

(defn- search-box
  [query total filtered?]
  ;; rf2-nesy9 — render-time frame capture (rendered inside the
  ;; interceptors Panel reg-view), not a `:rf/xray` literal. rf2-1keg3 —
  ;; the flex-row markup lives in the shared `search-box` component.
  (let [frame (rf/current-frame-id)]
    [search-box/search-box
     {:testid-prefix   "rf-xray-static-interceptors"
      :dispatch        (fn [ev] (rf/dispatch ev {:frame frame}))
      :set-query-event :rf.xray.static.interceptors/set-query
      :placeholder     "interceptor-id or doc…"
      :value           query
      :count-noun      "interceptor"
      :total           total
      :filtered?       filtered?
      :count-min-width "90px"}]))

;; ---- row -----------------------------------------------------------------

(defn- interceptor-row
  [{:keys [id ref? authored arg factory? before? after? default? chain-count
           doc] :as _row}]
  (let [id-text (pr-str id)
        row-id  (if (and (> (count id-text) 0) (= \: (first id-text)))
                  (subs id-text 1)
                  id-text)]
    ;; rf2-mq8wk — list semantics. Interceptor rows are non-interactive
    ;; catalogue entries (no row-level dispatch), so `role=listitem` is
    ;; the right shape rather than `role=button`.
    [:li {:data-testid (str "rf-xray-static-interceptors-row-" row-id)
          :role        "listitem"
          :style       {:display       "block"
                        :padding       "6px 12px"
                        :font-family   mono-stack
                        :font-size     "12px"
                        :color         (:text-primary tokens)
                        :background    "transparent"
                        :border-left   "2px solid transparent"
                        :border-radius "2px"
                        :line-height   "18px"}}
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
        doc])]))

;; ---- root view -----------------------------------------------------------

(rf/reg-view Panel
  "Static Interceptors panel root view. Subscribes to the
  interceptors composite + search slot.

  Per rf2-in6l2 `reg-view`-registered so subscribes resolve to
  `:rf/xray`."
  []
  (let [data @(rf/subscribe [:rf.xray.static.interceptors/tab-data])
        {:keys [silent? interceptors total filtered? query]} data]
    [:section {:data-testid "rf-xray-static-interceptors"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      (:body type-scale)}}
     (header)
     (cond
       silent?
       (search-box/empty-state "rf-xray-static-interceptors" "interceptor")

       :else
       [:<>
        (search-box query total filtered?)
        (if (empty? interceptors)
          (search-box/empty-filtered "rf-xray-static-interceptors" "interceptor" query)
          (into [:ul {:data-testid "rf-xray-static-interceptors-list"
                      :role        "list"
                      :style       {:list-style     "none"
                                    :margin         "8px 0 0 0"
                                    :padding        "0 8px"
                                    :flex           1
                                    :overflow       "auto"
                                    :display        "flex"
                                    :flex-direction "column"
                                    :gap            "2px"}}]
                (for [row interceptors]
                  ^{:key (pr-str (:id row))}
                  [interceptor-row row])))])]))

;; ---- production value source ---------------------------------------------
;;
;; The raw value the production data sub reads. Shared with the
;; test-override seam (`install-test-overrides!` below) so the override
;; branch lives in ONE place (the seam), not duplicated (rf2-e8330v).

(defn- registry-value
  "The event-chain registry off the HOST app's `:event` registrar — each entry
  carries its interceptor chain. The test seam overrides this map directly.

  Read via `host-registry/registrations` (the generation-bypassing process-global
  form), NOT a bare `(rf/registrations :event)`: this runs inside the
  `:rf.xray.static.interceptors/registry` sub COMPUTATION, and Xray seats in its
  OWN image-loaded `:rf/xray` frame, so the sub build binds the registrar to
  Xray's image generation — a bare read would resolve through Xray's OWN image
  and the interceptor browse would lose the host app's event chains. See
  `day8.re-frame2-xray.host-registry`."
  []
  (try (host-registry/registrations :event)
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
  ;; into it via `install-test-overrides!` (rf2-e8330v / xxo3zz F3).

  ;; ---- production data sub ---------------------------------------------

  (rf/reg-sub :rf.xray.static.interceptors/registry
    :<- [:rf.xray/trace-buffer]
    (fn [_buffer _query]
      (registry-value)))

  ;; ---- view-facing composite -------------------------------------------

  (rf/reg-sub :rf.xray.static.interceptors/tab-data
    :<- [:rf.xray.static.interceptors/registry]
    :<- [:rf.xray.static.interceptors/query]
    (fn [[registrations-map query] _query]
      (project-data registrations-map query)))

  ;; rf2-2moh1 — register the Static Interceptors tab. Contiguous order:
  ;; machines 0 · routes 1 · schemas 2 · flows 3 · interceptors 4.
  (panel-registry/reg-l4-tab!
    {:id    :interceptors
     :label "Interceptors"
     :mnem  "i"
     :modes #{:static}
     :order 4
     :panel Panel})

  nil)

;; ---- test-only override seam (rf2-e8330v / xxo3zz F3) ---------------------

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
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray.static.interceptors/registry-override]
    (fn [[_buffer override] _query]
      (or override (registry-value))))
  nil)
