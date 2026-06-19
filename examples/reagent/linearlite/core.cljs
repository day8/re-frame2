(ns linearlite.core
  "Flagship worked example for the EP-0019 OPTIMISTIC MUTATION + ROLLBACK
   primitive ([Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations)).

   A small Linearlite-class issue tracker — a board of issues you CREATE,
   RETITLE, and move between statuses — where every write applies
   OPTIMISTICALLY: the board updates the instant you click, BEFORE the request
   is sent, and is deterministically COMMITTED or ROLLED BACK when the reply
   settles. This is the write counterpart of `examples/reagent/infinite_feed/`'s
   read-side load-more dogfood: there the runtime owned the page accumulation;
   here the runtime owns the optimistic apply, the recorded snapshot inverse,
   the per-entry revision token, and the conflict-aware rollback.

   It dogfoods, in one cohesive app, the four facts the optimistic primitive
   surfaces ([EP-0019](../../../docs/EP/EP-0019-optimistic-mutation-rollback.md)):

   - THE WRITE SHOWS IMMEDIATELY — each mutation declares `:optimistic`, the
     EXACT-target twin of `:patches`: `(fn [params] -> {target patch-fn})`. The
     patch runs at PHASE 1.5, before the request lowers, so the new card
     appears / the title changes / the card moves the instant the user acts.
     The view never mutates app-db itself — there is NO app-db issue list, no
     `:saving?` flag, no manual optimistic copy. The runtime owns the cache.

   - THE INVERSE IS RUNTIME-RECORDED — the author writes only the FORWARD
     patch. The runtime snapshots each touched entry's whole `:before` value
     (verbatim, by structural sharing) and its `:revision` at apply time. The
     author never describes how to undo a change; the inverse is truthful by
     construction (the exact entry that existed, never a reconstruction).

   - THE REPLY SETTLES DETERMINISTICALLY — an accepted `:ok` reply COMMITS
     (`:populates` overwrites the optimistic guess with the server's
     authoritative board), an accepted `:error` reply ROLLS BACK (the recorded
     `:before` is restored verbatim — the optimistic change visibly reverts).
     There is no wall-clock race: the verdict keys on the work-id + generation
     acceptance and the per-entry revision, both canonical recorded facts.

   - ROLLBACK IS WHAT YOU SEE — the demo's headline. A 'Fail the next write'
     toggle arms the demo backend to answer the next mutation with a 503. The
     optimistic change paints immediately, the request fails, and the runtime
     rolls the board back to exactly its pre-click state — the new card
     vanishes, the retitled card reverts, the moved card snaps back — with a
     red banner naming the failure. No manual undo, no app-db bookkeeping.

   The board is read PASSIVELY through `[:rf.resource/value …]` (the resource
   sub family); each in-flight write is watched through the passive
   `[:rf.mutation/state {:instance …}]` view-model — including the derived
   `:optimistic?` flag (Rider 1), true while a live optimistic apply is showing
   between phase 1.5 and settle, so the board can mark a card 'pending, showing
   your optimistic value'.

   THE CONFLICT POLICY. Each mutation names `:on-conflict :invalidate` (the
   default, named here for the dogfood): on a failure rollback where a competing
   write moved the touched entry while ours was in flight, the read path is the
   recovery authority — the runtime refetches the authoritative value rather
   than restoring a now-stale inverse (re-frame2's deliberate divergence from
   TanStack/SWR's unconditional context restore).

   IDIOMATIC re-frame2. The board is a single managed resource; every write is
   a named `reg-mutation`; the toggle is an ordinary app-db slice driven by an
   event and read by a sub. NO raw atoms through views, NO frame-ids as view
   args, no vestigial timing. The example ships no backend, so it overrides
   `:rf.http/managed` with a canned stub (mirroring
   `examples/reagent/infinite_feed/`) that synthesises the board reply and,
   when armed, the 503 — so the whole optimistic lifecycle runs standalone.

   The example tree is test-free (rf2-8cevm); the example-specific composition
   coverage (optimistic-apply, success-commit, failure-rollback) lives in
   `implementation/adapters/reagent/test/re_frame/linearlite_example_cljs_test.cljs`,
   and the optimistic-mutation runtime contract is pinned in
   `implementation/resources/test/`."
  (:require [reagent.dom.client :as rdc]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP ships in day8/re-frame2-http — the single built-in
            ;; resource/mutation transport (Spec 016 §Transport). Loading the
            ;; ns registers the `:rf.http/managed` fx the resource + mutation
            ;; runtime lowers each read/write onto.
            [re-frame.http.managed]
            ;; The framework-shipped canned-success fx the demo stub delegates
            ;; to (Spec 014 §Testing) — the explicit opt-in for a demo app with
            ;; no backend.
            [re-frame.http.test-support]
            ;; Resources ship in day8/re-frame2-resources. Requiring the ns at
            ;; app boot wires the late-bind hooks + registrations (the resource
            ;; sub family AND the mutation runtime + `:rf.mutation/*` subs);
            ;; without it `rf/reg-resource` / `rf/reg-mutation` throw
            ;; :rf.error/resources-artefact-missing.
            [re-frame.resources]
            ;; Routing ships in day8/re-frame2-routing. The resources artefact
            ;; LATE-BINDS its `:resources` route-metadata extension into
            ;; routing, so loading both is what makes a route's `:resources`
            ;; key accepted (Spec 016 §Route integration). Route entry ensures
            ;; the board on entry.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view reg-event reg-sub]]))

;; ============================================================================
;; THE DOMAIN — issues + statuses
;; ============================================================================

(def ^:private statuses
  "The board columns, in order. A Linearlite-style issue moves left→right
   through these as work progresses; `change-status` is the optimistic move."
  [{:id :backlog     :label "Backlog"}
   {:id :in-progress :label "In Progress"}
   {:id :done        :label "Done"}])

(def ^:private status-labels
  (into {} (map (juxt :id :label)) statuses))

;; ============================================================================
;; THE RESOURCE — the board is ONE managed server-state read
;; ============================================================================
;;
;; The whole issue board is a single resource entry: `{:issues [...]}`. The
;; route ensures it on entry; the view reads it passively. Every optimistic
;; write patches THIS one entry's `:data` in place (the exact-target
;; `:optimistic` twin of `:patches`), and the success reply re-seeds it with
;; the server's authoritative board via `:populates`. There is no app-db issue
;; list — the runtime owns the board value, its freshness, and the snapshot
;; inverse that makes rollback truthful.

(rf/reg-resource :linearlite/board
  {:doc            "The whole issue board — `{:issues [...]}` — as one managed
                    server-state read. Optimistic writes patch this entry in
                    place before their request is sent; the success reply
                    re-seeds it with the server's authoritative board."

   ;; One public board in the demo (a real app would scope per-team / per-user
   ;; and key the team in :params); identity params are empty.
   :params-schema  [:map]

   ;; Public board: the same for every viewer → the explicit, auditable global
   ;; claim (Spec 016 §Scope resolution — there is no implicit default). A
   ;; per-team board would carry a scope resolver instead.
   :scope          :rf.scope/global

   :request        (fn [_params _ctx]
                     {:request {:method :get :url "/api/board"}
                      :decode  :json})

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)

   ;; A board tag so a write COULD invalidate the whole board by tag. The
   ;; optimistic writes below patch the entry directly (immediate) and re-seed
   ;; via :populates on commit, so they need no coarse invalidation; the tag is
   ;; kept so a future server-push / external change can stale the board.
   :tags           (fn [_params _data] #{[:board]})})

;; ============================================================================
;; THE EXACT-TARGET — every optimistic write patches THIS key
;; ============================================================================

(def ^:private board-key
  "The single map-form exact resource target the board lives under — the same
   `{:resource :scope :params}` shape the route ensured it under, reused as the
   `:optimistic` / `:populates` key. (Empty :params ⇒ the one public board.)"
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(def ^:private board-query
  "The query map the view's `[:rf.resource/data …]` sub reads — the board
   identity (resource + scope + params), the SAME identity the route ensured
   under and the writes patch."
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

;; A monotone counter for client-minted optimistic ids (a demo seam). A NEW
;; issue gets a stable `tmp-N` id the moment its optimistic card appears, so it
;; has a stable React key before the server assigns its own. The success reply
;; re-seeds the whole board with the server's value (carrying the server id), so
;; the temporary id never leaks past commit; a rollback simply removes the
;; never-committed card.
(defonce ^:private optimistic-id-seq (atom 0))

(defn- next-issue-id
  "Mint the next client-side optimistic id (`tmp-N`)."
  []
  (str "tmp-" (swap! optimistic-id-seq inc)))

(defn- upsert-issue
  "Add or replace an issue in the board's `:issues` vector by id. Pure — shared
   by the create + edit forward patches so the optimistic shape is declared
   once. A new id appends; an existing id is replaced in place (order kept)."
  [issues issue]
  (if (some #(= (:id %) (:id issue)) issues)
    (mapv #(if (= (:id %) (:id issue)) issue %) issues)
    (conj (vec issues) issue)))

(defn- patch-issue
  "Return a board-`:data` patch fn that applies `f` to the issue with `id`,
   leaving the rest of the board untouched. The runtime records the inverse, so
   this fn never describes how to undo itself."
  [id f]
  (fn [data]
    (update data :issues
            (fn [issues]
              (mapv (fn [issue] (if (= id (:id issue)) (f issue) issue))
                    (or issues []))))))

;; ============================================================================
;; THE MUTATIONS — create / edit-title / change-status, all OPTIMISTIC
;; ============================================================================
;;
;; Each write declares `:optimistic` (the exact-target forward patch over the
;; board entry, applied at phase 1.5 before the request), `:populates` (the
;; commit — re-seed the board with the server's authoritative value on :ok),
;; and `:on-conflict :invalidate` (the default rollback conflict policy, named
;; for the dogfood). The runtime records the snapshot inverse + each entry's
;; revision itself; the reply settles deterministically (commit / rollback).
;;
;; Writes do NOT retry (reads-retry / writes-don't, Spec 014) — a 503 surfaces
;; as `:error` and rolls the optimistic change back, which is exactly the
;; behaviour the demo's 'fail the next write' toggle exhibits.

(rf/reg-mutation :linearlite/create-issue
  {:doc           "Create an issue (optimistic). POST /api/issues. The new card
                   appears in Backlog the instant you submit; a failure rolls
                   it back out."
   :params-schema [:map [:id :string] [:title :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [title]} _ctx]
                    {:request {:method :post :url "/api/issues"
                               :body   {:title title}}
                     :decode  :json})
   ;; FORWARD: append a new Backlog card to the board immediately (phase 1.5).
   ;; `:id` is the client-minted optimistic id so the card has a stable React
   ;; key before the server assigns one.
   :optimistic    (fn [{:keys [id title]}]
                    {board-key
                     (fn [data]
                       (update data :issues
                               (fn [issues]
                                 (upsert-issue issues
                                               {:id id :title title :status :backlog
                                                :optimistic? true}))))})
   ;; COMMIT: the :ok reply is the server's whole authoritative board; re-seed
   ;; the board entry with it (Spec 016 §Populate is an authoritative load), so
   ;; the temporary id is replaced by the server's and the optimistic flag
   ;; clears. Same `{:issues …}` envelope the resource stores.
   :populates     (fn [_params result] {board-key result})
   ;; ROLLBACK conflict policy (the default, named for the dogfood): on a
   ;; contested rollback, the read path refetches rather than restoring a stale
   ;; inverse.
   :on-conflict   :invalidate})

(rf/reg-mutation :linearlite/edit-title
  {:doc           "Retitle an issue (optimistic). PUT /api/issues/:id. The title
                   changes the instant you commit the edit; a failure reverts
                   it to the prior title."
   :params-schema [:map [:id :string] [:title :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [id title]} _ctx]
                    {:request {:method :put :url (str "/api/issues/" id)
                               :body   {:title title}}
                     :decode  :json})
   :optimistic    (fn [{:keys [id title]}]
                    {board-key (patch-issue id #(assoc % :title title :optimistic? true))})
   :populates     (fn [_params result] {board-key result})
   :on-conflict   :invalidate})

(rf/reg-mutation :linearlite/change-status
  {:doc           "Move an issue to another status (optimistic). PUT
                   /api/issues/:id. The card jumps to the new column the instant
                   you click; a failure snaps it back to its prior column."
   :params-schema [:map [:id :string] [:status :keyword]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [id status]} _ctx]
                    {:request {:method :put :url (str "/api/issues/" id)
                               :body   {:status status}}
                     :decode  :json})
   :optimistic    (fn [{:keys [id status]}]
                    {board-key (patch-issue id #(assoc % :status status :optimistic? true))})
   :populates     (fn [_params result] {board-key result})
   :on-conflict   :invalidate})

;; ============================================================================
;; DEMO BACKEND — a canned :rf.http/managed override (no server ships)
;; ============================================================================
;;
;; This example ships no backend, so it overrides `:rf.http/managed` (the fx
;; resources + mutations lower every read/write onto) with a stub that holds
;; the canonical board in a closure and synthesises the authoritative reply for
;; each read/write. It delegates to the framework-shipped
;; `:rf.http/managed-canned-success` / `-failure` with `:after-ms` so the reply
;; rides framework `:dispatch-later` (tape-visible, time-travel-safe, NOT raw
;; js/setTimeout) — letting the optimistic value paint before the reply lands.
;;
;; THE FAILURE SEAM. `fail-next-write?` is read from app-db at request time:
;; when armed, the stub answers the next WRITE with a 503 (via
;; `:rf.http/managed-canned-failure`) and disarms — so a single click drives
;; the whole optimistic-apply → request → rollback arc the demo headlines.
;; Mirrors examples/reagent/infinite_feed/core.cljs's per-request stub.

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via `:after-ms` →
   `:dispatch-later` — tape-visible, NOT raw js/setTimeout). Small but non-zero
   so the optimistic value is visibly painted before the reply lands. A
   demo-seam knob, not a production value."
  220)

;; The canonical server board the stub maintains across requests, so a committed
;; write persists into the next read (the stub IS the demo server). A NEW write
;; minting a server id, a retitle, and a status move all mutate this atom on a
;; successful write so the next board read reflects them.
(defonce ^:private demo-board
  (atom {:issues [{:id "srv-1" :title "Wire up the optimistic board" :status :in-progress}
                  {:id "srv-2" :title "Render the three status columns" :status :done}
                  {:id "srv-3" :title "Add the create-issue form"       :status :backlog}]}))

(defn- strip-optimistic
  "Drop the demo-only `:optimistic?` marker from issues so the AUTHORITATIVE
   server board the stub returns never carries it (the marker is a view hint
   the optimistic patch adds; the committed value is clean)."
  [board]
  (update board :issues (fn [issues] (mapv #(dissoc % :optimistic?) issues))))

(defn- next-server-id [board]
  (str "srv-" (inc (count (:issues board)))))

(defn- apply-write!
  "Mutate the canonical demo board for a successful write, returning the new
   authoritative board (the `:populates` payload). Routed by method + URL:
   POST mints a server id for the new issue; PUT patches title and/or status by
   id from the request body."
  [method url body]
  (swap! demo-board
         (fn [board]
           (cond
             ;; POST /api/issues — create. Mint a server id for the new issue.
             (and (= method :post) (str/ends-with? url "/api/issues"))
             (update board :issues
                     (fn [issues]
                       (conj (vec issues)
                             {:id (next-server-id board) :title (:title body) :status :backlog})))

             ;; PUT /api/issues/:id — edit title and/or move status.
             (and (= method :put) (re-find #"/api/issues/([^/?]+)$" url))
             (let [id (second (re-find #"/api/issues/([^/?]+)$" url))]
               (update board :issues
                       (fn [issues]
                         (mapv (fn [issue]
                                 (if (= id (:id issue))
                                   (cond-> issue
                                     (contains? body :title)  (assoc :title (:title body))
                                     (contains? body :status) (assoc :status (:status body)))
                                   issue))
                               issues))))

             :else board)))
  (strip-optimistic @demo-board))

(rf/reg-fx :linearlite.demo/http-stub
  {:doc       "Demo override for `:rf.http/managed`: maintains the canonical
               board in a closure and synthesises the authoritative reply for
               each read/write, delegating to
               `:rf.http/managed-canned-success` / `-failure` with `:after-ms`.
               Reads the `:fail-next-write?` app-db flag at request time: when
               armed, the next WRITE answers a 503 (driving the rollback arc)
               and disarms."
   :platforms #{:client}}
  (fn fx-managed-board-demo [frame-ctx args-map]
    (let [req       (:request args-map)
          method    (or (:method req) :get)
          url       (str (:url req))
          body      (:body req)
          write?    (not= method :get)
          ;; The fx context carries the envelope frame as `:frame` (EP-0002 —
          ;; the same stamp the canned-stub bodies read). Read the demo seam
          ;; flag off that frame's runtime db.
          db        (rf/runtime-db-value (:frame frame-ctx))
          fail?     (and write? (boolean (:fail-next-write? db)))]
      (if fail?
        ;; Armed failure: answer the WRITE with a 503 so the optimistic apply
        ;; rolls back. Disarm the seam, then delegate to the canned-failure fx
        ;; (the reply rides `:after-ms` → `:dispatch-later`).
        (let [failure {:kind :rf.http/http-5xx :status 503
                       :message "Simulated server failure (the demo's rollback seam)."}
              stub    (registrar/handler :fx :rf.http/managed-canned-failure)]
          (rf/dispatch [:linearlite/set-fail-next-write false])
          (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :failure failure)))
        ;; Success: a read returns the current board; a write mutates the
        ;; canonical board and returns the new authoritative board.
        (let [payload (if write?
                        (apply-write! method url body)
                        (strip-optimistic @demo-board))
              stub    (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))))

;; ============================================================================
;; APP-DB — the demo seam + the inline title-edit draft (ordinary slices)
;; ============================================================================
;;
;; The ONLY app-db state this example carries is demo/UI chrome — the
;; failure-seam toggle, the new-issue draft, and the id of the issue being
;; inline-retitled. The issue board itself is NOT in app-db (the resource owns
;; it). These are ordinary slices: an event writes, a sub reads, the view reads
;; the sub. No raw atoms, no frame-ids as view args.

(reg-event :linearlite/set-fail-next-write
  (fn [db [_ on?]] (assoc db :fail-next-write? on?)))

(reg-event :linearlite/set-new-issue-draft
  (fn [db [_ text]] (assoc db :new-issue-draft text)))

(reg-event :linearlite/begin-edit
  (fn [db [_ id title]] (assoc db :editing {:id id :title title})))

(reg-event :linearlite/set-edit-draft
  (fn [db [_ text]] (assoc-in db [:editing :title] text)))

(reg-event :linearlite/cancel-edit
  (fn [db _] (dissoc db :editing)))

(reg-sub :linearlite/fail-next-write? (fn [db _] (boolean (:fail-next-write? db))))
(reg-sub :linearlite/new-issue-draft  (fn [db _] (or (:new-issue-draft db) "")))
(reg-sub :linearlite/editing          (fn [db _] (:editing db)))

;; --- the write events (dispatch the mutation, watch the instance) -----------
;;
;; Each write is a thin event that dispatches `:rf.mutation/execute` with a
;; per-issue (or per-create) instance id, then resets the relevant UI slice.
;; The view never touches the board — it watches the mutation instance and
;; reads the board resource passively; the runtime applies the optimistic
;; patch, sends the request, and commits / rolls back on the reply.

(reg-event :linearlite/create-issue
  (fn [{:keys [db]} [_ title]]
    (let [tmp-id (next-issue-id)]
      {:db (assoc db :new-issue-draft "")
       :fx [[:dispatch [:rf.mutation/execute
                        {:mutation :linearlite/create-issue
                         :params   {:id tmp-id :title title}
                         :instance [:create tmp-id]
                         :cause    [:user :linearlite/create-issue]}]]]})))

(reg-event :linearlite/commit-edit
  (fn [{:keys [db]} [_ id title]]
    {:db (dissoc db :editing)
     :fx [[:dispatch [:rf.mutation/execute
                      {:mutation :linearlite/edit-title
                       :params   {:id id :title title}
                       :instance [:edit id]
                       :cause    [:user :linearlite/edit-title]}]]]}))

(reg-event :linearlite/change-status
  (fn [_ [_ id status]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation :linearlite/change-status
                       :params   {:id id :status status}
                       :instance [:status id]
                       :cause    [:user :linearlite/change-status]}]]]}))

;; ============================================================================
;; ROUTES — route entry ensures the board (the route OWNS the read)
;; ============================================================================

(rf/reg-route :linearlite.app/board
  {:doc   "The issue board — ensures the :linearlite/board resource on entry."
   :path  "/"
   :resources
   [{:resource  :linearlite/board
     :params    (fn [_route] {})
     :blocking? true}]})

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."
   :path "/_404"})

;; ============================================================================
;; VIEWS — passive board read + watched mutation instances
;; ============================================================================

(reg-view fail-toggle []
  (let [armed? @(subscribe [:linearlite/fail-next-write?])]
    [:label.fail-toggle {:data-testid "fail-toggle"}
     [:input {:type      "checkbox"
              :checked   armed?
              :on-change #(dispatch [:linearlite/set-fail-next-write
                                     (.. % -target -checked)])}]
     [:span "Fail the next write (simulate a server failure → rollback)"]]))

(reg-view new-issue-form []
  (let [draft @(subscribe [:linearlite/new-issue-draft])]
    [:form.new-issue {:data-testid "new-issue-form"
                      :on-submit   (fn [e]
                                     (.preventDefault e)
                                     (when-not (str/blank? draft)
                                       (dispatch [:linearlite/create-issue (str/trim draft)])))}
     [:input {:type        "text"
              :placeholder "New issue title…"
              :data-testid "new-issue-input"
              :value       draft
              :on-change   #(dispatch [:linearlite/set-new-issue-draft (.. % -target -value)])}]
     [:button {:type :submit :data-testid "new-issue-submit"} "Add issue"]]))

(reg-view status-picker [{:keys [id status]}]
  ;; Move the card to another column. Dispatches the optimistic change-status
  ;; mutation; the card jumps immediately, then commits / rolls back.
  [:select {:data-testid (str "status-" id)
            :value       (name status)
            :on-change   #(dispatch [:linearlite/change-status
                                     id (keyword (.. % -target -value))])}
   (for [{s-id :id s-label :label} statuses]
     ^{:key s-id} [:option {:value (name s-id)} s-label])])

(reg-view issue-card [{:keys [issue editing]}]
  (let [{:keys [id title status optimistic?]} issue
        editing-this? (= id (:id editing))
        ;; Watch this card's in-flight writes passively. `:optimistic?` (the
        ;; derived Rider-1 flag) is true while a live optimistic apply is
        ;; showing between phase 1.5 and settle; `:error?` flags the last
        ;; write's failure (the optimistic value has already rolled back).
        edit-state    @(subscribe [:rf.mutation/state {:instance [:edit id]}])
        status-state  @(subscribe [:rf.mutation/state {:instance [:status id]}])
        create-state  @(subscribe [:rf.mutation/state {:instance [:create id]}])
        pending?      (or optimistic?
                          (:optimistic? edit-state) (:optimistic? status-state)
                          (:optimistic? create-state))
        errored?      (or (:error? edit-state) (:error? status-state) (:error? create-state))]
    [:li.issue-card {:data-testid (str "issue-" id)
                     :class       (str (when pending? "pending ") (when errored? "errored"))}
     (if editing-this?
       ;; Inline retitle: commit dispatches the optimistic edit-title mutation.
       [:form.edit-title {:data-testid (str "edit-form-" id)
                          :on-submit   (fn [e]
                                         (.preventDefault e)
                                         (when-not (str/blank? (:title editing))
                                           (dispatch [:linearlite/commit-edit id (str/trim (:title editing))])))}
        [:input {:type        "text"
                 :data-testid (str "edit-input-" id)
                 :auto-focus  true
                 :value       (:title editing)
                 :on-change   #(dispatch [:linearlite/set-edit-draft (.. % -target -value)])}]
        [:button {:type :submit :data-testid (str "edit-save-" id)} "Save"]
        [:button {:type :button :on-click #(dispatch [:linearlite/cancel-edit])} "Cancel"]]
       [:div.issue-row
        [:span.issue-title {:data-testid (str "title-" id)
                            :on-click    #(dispatch [:linearlite/begin-edit id title])}
         title]
        (when pending? [:span.badge {:data-testid (str "pending-" id)} "saving…"])
        (when errored? [:span.badge.error {:data-testid (str "errored-" id)} "failed — reverted"])])
     [:div.issue-actions
      [status-picker {:id id :status status}]]]))

(reg-view board-column [{:keys [status issues editing]}]
  [:div.column {:data-testid (str "column-" (:id status))}
   [:h2 (:label status)]
   (into [:ul.issue-list]
         (for [issue issues]
           ^{:key (:id issue)} [issue-card {:issue issue :editing editing}]))])

(reg-view board-page []
  ;; The board was ensured by THIS route's `:resources` metadata on entry. The
  ;; view reads the WHOLE board passively via `[:rf.resource/data …]`; every
  ;; write patches the runtime cache, so this view re-renders with the
  ;; optimistic value the instant a write fires, then again on commit/rollback.
  (let [board   @(subscribe [:rf.resource/data board-query])
        state   @(subscribe [:rf.resource/state board-query])
        editing @(subscribe [:linearlite/editing])
        issues  (:issues board)]
    [:div.linearlite
     [:header
      [:h1 "Linearlite — optimistic issue board"]
      [:p "Every write applies " [:strong "optimistically"] " (the board updates
           the instant you click, before the request is sent) and is committed
           or rolled back when the reply settles. Arm the toggle to simulate a
           server failure and watch the optimistic change revert — the runtime
           records the inverse, so rollback is automatic."]
      [fail-toggle]
      [new-issue-form]]
     (cond
       ;; First load (route entry), no usable board yet → skeleton.
       (:loading? state)
       [:p {:data-testid "board-skeleton"} "Loading the board…"]

       ;; First-load failure with no usable board → full error screen.
       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "board-error"} "Could not load the board."]

       :else
       (into [:div.board {:data-testid "board"}]
             (for [status statuses]
               ^{:key (:id status)}
               [board-column {:status  status
                              :issues  (filterv #(= (:id status) (:status %)) issues)
                              :editing editing}])))]))

(reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :linearlite.app/board :data-testid "route-link-board"} "Back to the board"]]])

(reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :linearlite.app/board [board-page]
    :rf.route/not-found   [not-found-page]
    [board-page]))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; The React root is materialised lazily inside `run` (not at ns-load) per
;; examples/TESTING.md §Example mount-isolation convention. EP-0002: the app
;; establishes its frame explicitly (`reg-frame`), declares `:url-bound? true`
;; so it owns the browser URL, overrides `:rf.http/managed` with the canned
;; board stub (the example runs standalone), and wraps the render in a
;; `frame-provider` so every in-tree dispatch/subscribe resolves to it.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame
    {:doc          "Linearlite optimistic-board demo frame."
     :url-bound?   true
     :fx-overrides {:rf.http/managed :linearlite.demo/http-stub}})
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [root-view]])))
