(ns re-frame.hicasso.examples.typeahead.events
  "THE TYPEAHEAD'S MODEL TIER — and the ceremony census, in the source
  (rf2-hic-044).

  Ordinary re-frame2: every handler is `(fn [coeffects event-v] →
  effect-map)`, nothing here knows a view substrate exists, and the whole
  file is L0. What makes it a WITNESS rather than another example is the
  census markers.

  ## The census markers, and why they are in the source

  The resource-demand criteria pre-registered at `afbb58febc`
  (`docs/design/hicasso/product/resource-demand-criteria.md`) settle C1 —
  *ceremony removed, counted* — by a CENSUS: every contiguous region of
  this witness whose only job is to keep resource liveness correlated with
  read liveness, counted once, cited by file and line, and classified
  exactly once as OWNERSHIP, POLICY or DOMAIN.

  A census a human transcribes into a report is a census that rots on the
  next commit. So each region is delimited here, in the code it describes,
  by an opening and a closing comment marker, and
  [[re-frame.hicasso.examples.typeahead.census]] reads them off this file
  at macro-expansion time — that namespace holds the grammar, and holds it
  rather than this docstring because a marker written in prose here would
  be SCANNED like any other and would count a site that does not exist.
  The report's table is generated from the same read, so a site deleted,
  added or re-classified moves the published count on the next compile
  rather than on the next time somebody remembers. `l0-cljs-test` pins
  that count.

  ## What OWNERSHIP means here, and what it does not

  The three classes are the criteria's, not this file's, and the boundary
  between OWNERSHIP and POLICY is the one every row turns on. Two
  different things share the word *supersession*:

  - **A late reply must not overwrite newer state.** That is POLICY —
    stale-reply suppression — and the criteria keep it explicit under
    demand as well. It is the guard in [[::suggestions]].
  - **Work for a parameter nobody reads any more should stop.** That is
    OWNERSHIP — release on parameter change — and it is [[release-search]]
    called from [[::typed]].

  They are independent, and the witness shows it: remove the second and
  the first still produces the right screen. What is lost is only the
  request, which is the C2 class *orphaned in-flight request after a
  parameter change*.

  ## The release that could not be written

  There are three intents in this file that end a suggestion read, and
  each carries its release. There is a fourth way a read ends — the
  boundary UNMOUNTS — and no handler here can carry that one, because the
  public door offers application code no unmount signal at all (the
  boundary shell is frozen at two hooks; `h/reg-state` mints a
  subscription and a setter and nothing else). The site is absent rather
  than wrong, so it is not a census row; it is C2's *demand outlives the
  read that wanted it*, and `demand-dom-cljs-test` exhibits it on the
  shipped path with no mutation at all.

  ## Two event shapes, and the seam is not the author's choice

  [[::typed]] takes a POSITIONAL argument because it carries `::h/value`,
  which `impl.intent/materialize` substitutes at the intent vector's top
  level only. Everything else takes the canonical trailing map. This is
  the rf2-hic-025 authoring report's first finding, met again here."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.examples.typeahead.db :as db]
            [re-frame.hicasso.examples.typeahead.service :as service]))

(def debounce-ms
  "How long the field waits for the typing to stop. Small, because the
  witness's debounce rows are decided by the GENERATION guard rather than
  by a duration: a burst dispatched in one turn arms one timer per
  keystroke and exactly one of them is still current when it fires."
  20)

(def service-delay-ms
  "How long the stand-in service takes to answer. Every row that needs a
  reply waits on the reply's arrival, never on this number."
  20)

(defn detail-token
  "The request token for one row's detail. Keyed by the resource's
  parameter, because that is the only thing both the acquire site and the
  release site can compute independently."
  [id]
  [::detail id])

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed
  {:doc "Install the starting app-db. The frame's `:initial-events` step."}
  (fn [_ _] {:db db/seed}))

;; ---------------------------------------------------------------------------
;; The one factored correlation
;; ---------------------------------------------------------------------------

(defn release-search
  "The `:fx` that stops work on the suggestion request, or `nil` when
  there is none out.

  Factored because three intents need it and a copy in each is how the
  third one comes to be missing. Factoring is the right engineering
  answer and it does not remove the ceremony: the author must still
  REMEMBER TO CALL IT at every intent that can end the read, and nothing
  in the language, the linter or the type of the handler can tell them
  they missed one. That is what the census's four OWNERSHIP release rows —
  one definition and three invocations — are counting."
  [db]
  ;; CENSUS O1 | OWNERSHIP | release | the definition: abandon the request the model believes is out
  (when-some [{:keys [token]} (db/in-flight db)]
    [[::service/abandon {:token token}]])
  ;; /CENSUS O1
  )

;; ---------------------------------------------------------------------------
;; The field
;; ---------------------------------------------------------------------------

(rf/reg-event ::typed
  {:doc "A keystroke. Positional, because it carries `::h/value`."}
  (fn [{:keys [db]} [_ typed]]
    (let [db'      (-> db
                       (assoc-in [:search :term] typed)
                       (assoc-in [:search :open?] true)
                       (update-in [:search :generation] inc))
          term     (db/wanted db')
          gen      (get-in db' [:search :generation])
          ;; CENSUS O2 | OWNERSHIP | release | the term moved, so the request out for the old one is work nobody reads
          release  (release-search db)
          ;; /CENSUS O2
          ;; CENSUS P1 | POLICY | debounce | one tick armed per keystroke; the guard at the tick decides which survives
          debounce (when (some? term)
                     [[:dispatch-later {:ms    debounce-ms
                                        :event [::search-due {:token gen}]}]])
          ;; /CENSUS P1
          ]
      {:db (cond-> (assoc-in db' [:search :status] (if term :typing :idle))
             ;; Rows for a term the field no longer holds would satisfy a
             ;; later acquire site and paint a stale panel. An application
             ;; drops what it can no longer show, under any mechanism.
             (nil? term) (assoc-in [:search :shown] nil)
             true        (assoc-in [:search :requested] nil))
       :fx (into [] cat [release debounce])})))

(rf/reg-event ::clear
  {:doc "Empty the field. The term stops being readable, so the request
         stops being wanted."}
  (fn [{:keys [db]} _]
    (let [;; CENSUS O3 | OWNERSHIP | release | the parameter became unreadable, so nothing reads the resource
          release (release-search db)
          ;; /CENSUS O3
          ]
      {:db (-> db
               (assoc-in [:search :term] "")
               (assoc-in [:search :shown] nil)
               (assoc-in [:search :requested] nil)
               (assoc-in [:search :status] :idle)
               ;; The reset law (HD-019). Dropping the model value is not
               ;; enough on its own: a controlled field handed a value it
               ;; was already handed sees nothing to do. It would be
               ;; written under any resource mechanism.
               (update-in [:search :revision] inc))
       :fx (into [] cat [release])})))

(rf/reg-event ::focus
  {:doc "The field gained focus, so the panel opens over whatever term is
         already there."}
  (fn [{:keys [db]} _]
    (let [db'  (assoc-in db [:search :open?] true)
          term (db/wanted db')
          gen  (get-in db' [:search :generation])
          ;; CENSUS O4 | OWNERSHIP | acquire | re-opening makes the read live again, so the resource has to be re-checked by hand
          fetch? (and (some? term)
                      (not (db/satisfied? db' term))
                      (nil? (db/in-flight db')))
          ;; /CENSUS O4
          ]
      (if fetch?
        {:db (-> db'
                 (assoc-in [:search :requested] {:token gen :term term})
                 (assoc-in [:search :status] (if (:shown (:search db')) :refreshing :loading)))
         :fx [[::service/search {:token   gen
                                 :term    term
                                 :delay   service-delay-ms
                                 :on-ok   ::suggestions
                                 :on-fail ::search-failed}]]}
        {:db db'}))))

(defn dismiss-fx
  "The whole effect map a dismissal produces — the model half and the
  release beside it.

  A named function rather than an inline handler body because the witness
  demonstrates C2's *missed release on a conditional-false read* by
  registering `(dissoc (dismiss-fx db) :fx)` as a handler of its own. The
  mutation under demonstration is then exactly the release, and no copy of
  the model half exists to drift."
  [db]
  (let [;; CENSUS O5 | OWNERSHIP | release | the panel closed, so the suggestion read is gone
        release (release-search db)
        ;; /CENSUS O5
        ]
    {:db (-> db
             (db/close-panel)
             (assoc-in [:search :requested] nil)
             (assoc-in [:search :status] :idle))
     :fx (into [] cat [release])}))

(rf/reg-event ::dismiss
  {:doc "Escape, or a click outside. The panel closes and its rows leave
         the screen."}
  (fn [{:keys [db]} _] (dismiss-fx db)))

;; ---------------------------------------------------------------------------
;; The suggestion resource
;; ---------------------------------------------------------------------------

(rf/reg-event ::search-due
  {:doc "The debounce tick. Two decisions, and they are different
         questions: is this tick still the current one, and does a read
         still want a term?"}
  (fn [{:keys [db]} [_ {:keys [token]}]]
    (let [;; CENSUS P2 | POLICY | debounce | a tick a newer keystroke superseded fires and does nothing
          superseded? (not (db/current-generation? db token))
          ;; /CENSUS P2
          term        (db/wanted db)
          ;; CENSUS O6 | OWNERSHIP | acquire | issue only if a read is live and is not already answered
          acquire?    (and (some? term)
                           (not (db/satisfied? db term))
                           (nil? (db/in-flight db)))
          ;; /CENSUS O6
          ]
      (if (or superseded? (not acquire?))
        {}
        {:db (-> db
                 (assoc-in [:search :requested] {:token token :term term})
                 ;; :refreshing rather than :loading when there are rows
                 ;; already on screen — REFRESH-WITH-DATA, and the panel
                 ;; keeps painting them. POLICY, and explicit under demand
                 ;; too.
                 (assoc-in [:search :status] (if (:shown (:search db)) :refreshing :loading)))
         :fx [[::service/search {:token   token
                                 :term    term
                                 :delay   service-delay-ms
                                 :on-ok   ::suggestions
                                 :on-fail ::search-failed}]]}))))

(rf/reg-event ::suggestions
  {:doc "The service answered. Take the rows only if this is the answer
         the model is still waiting for."}
  (fn [{:keys [db]} [_ {:keys [token] :as reply}]]
    ;; CENSUS P3 | POLICY | stale-reply-suppression | a reply for a request the model no longer awaits is dropped
    (if (= token (:token (db/in-flight db)))
      {:db (db/take-rows db reply)}
      {})
    ;; /CENSUS P3
    ))

(rf/reg-event ::search-failed
  {:doc "The service refused. Keep whatever is on screen; say why."}
  (fn [{:keys [db]} [_ {:keys [token problem]}]]
    ;; CENSUS P4 | POLICY | stale-reply-suppression | a failure for a request the model no longer awaits is dropped
    (if (= token (:token (db/in-flight db)))
      {:db (-> db
               (assoc-in [:search :requested] nil)
               (assoc-in [:search :status] :failed)
               (assoc-in [:search :problem] problem))}
      {})
    ;; /CENSUS P4
    ))

;; ---------------------------------------------------------------------------
;; The detail resource — parameterised by a chosen id
;; ---------------------------------------------------------------------------

(defn- detail-fx
  "Ask the service for `id`'s detail. Used by the two sites that acquire
  one, so the request's shape is written once."
  [id]
  [[::service/detail {:token (detail-token id)
                      :id    id
                      :delay service-delay-ms
                      :on-ok ::detail-ready}]])

(rf/reg-event ::choose
  {:doc "A suggestion was picked. The panel closes, the detail pane opens
         over the chosen id."}
  (fn [{:keys [db]} [_ {:keys [id]}]]
    (let [previous  (:chosen db)
          known?    (contains? (:details db) id)
          stranded? (and (some? previous)
                         (not= previous id)
                         (= :pending (get-in db [:details previous])))
          ;; CENSUS O7 | OWNERSHIP | release | choosing closes the panel, so the suggestion read is gone
          drop-search (release-search db)
          ;; /CENSUS O7
          ;; CENSUS O8 | OWNERSHIP | release | the detail pane's parameter moved, so the previous id's request is unread
          drop-detail (when stranded?
                        [[::service/abandon {:token (detail-token previous)}]])
          ;; /CENSUS O8
          ;; CENSUS O9 | OWNERSHIP | acquire | the detail pane's read becomes live, unless the cache already answers it
          take-detail (when-not known? (detail-fx id))
          ;; /CENSUS O9
          ]
      {:db (cond-> (-> db
                       (db/close-panel)
                       (assoc-in [:search :requested] nil)
                       (assoc-in [:search :status] :idle)
                       (assoc :chosen id))
             ;; A pending entry for the id being left behind is dropped
             ;; with its request, so a later choose asks again rather than
             ;; waiting forever on an answer that was abandoned.
             stranded?    (update :details dissoc previous)
             (not known?) (assoc-in [:details id] :pending))
       :fx (into [] cat [drop-search drop-detail take-detail])})))

(rf/reg-event ::hover
  {:doc "The pointer crossed a suggestion row. Warm its detail."}
  (fn [{:keys [db]} [_ {:keys [id]}]]
    ;; CENSUS X1 | DOMAIN | prefetch | a demand NO read expresses; it exists under any mechanism because no read set can imply it
    (if (contains? (:details db) id)
      {}
      {:db (assoc-in db [:details id] :pending)
       :fx (detail-fx id)})
    ;; /CENSUS X1
    ))

(rf/reg-event ::detail-ready
  {:doc "A detail arrived. It is filed by id, so a prefetch and a chosen
         read are answered by the same entry and neither can be stale for
         the other."}
  (fn [{:keys [db]} [_ {:keys [id row]}]]
    {:db (assoc-in db [:details id] row)}))
