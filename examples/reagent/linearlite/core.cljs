(ns linearlite.core
  "A worked example of OPTIMISTIC MUTATION with automatic ROLLBACK.

   A small Linearlite-style issue tracker: a board of issues you create,
   retitle, and move between statuses. Every write applies optimistically — the
   board updates the instant you click, before the request is sent — and is
   committed or rolled back when the reply settles. The runtime owns the
   optimistic apply, the recorded inverse, and the conflict-aware rollback.

   See the guide: [Optimistic writes commit, roll back, or
   reconcile](../../../docs/resources/concepts.md#optimistic-writes-commit-roll-back-or-reconcile).

   The example shows four things:

   - THE WRITE SHOWS IMMEDIATELY. Each mutation declares `:optimistic`, an
     exact-target forward patch over the board: `(fn [params] -> {target
     patch-fn})`. The patch runs before the request is sent, so the new card
     appears / the title changes / the card moves the instant the user acts. The
     runtime owns the cache and shows the optimistic value; the view just reads
     it. There is no app-db issue list and no `:saving?` flag to keep in sync.

   - THE INVERSE IS RECORDED FOR YOU. You write only the forward patch. The
     runtime snapshots each touched entry's value and its revision at apply
     time. A rollback restores exactly the entry that existed, so the undo is
     truthful by construction — never an author-written inverse that can drift.

   - THE REPLY SETTLES DETERMINISTICALLY. An `:ok` reply commits: `:populates`
     overwrites the optimistic guess with the server's authoritative board. An
     `:error` reply rolls back: the recorded value is restored and the
     optimistic change visibly reverts. The verdict keys on recorded facts (the
     generation-acceptance verdict and a per-entry revision), so there is no
     wall-clock race.

   - ROLLBACK IS WHAT YOU SEE. A 'Fail the next write' toggle arms the demo
     backend to answer the next mutation with a 503. The optimistic change
     paints immediately, the request fails, and the runtime rolls the board back
     to its pre-click state — the new card vanishes, the retitled card reverts,
     the moved card snaps back.

   The board is read passively through `[:rf.resource/data …]`. Each in-flight
   write is watched through `[:rf.mutation/state {:instance …}]`, whose derived
   `:optimistic?` flag is true while a live optimistic apply is showing, so the
   board can mark a card as pending.

   THE CONFLICT POLICY. Each mutation names `:on-conflict :invalidate` (the
   default). On a failure rollback where a competing write moved the touched
   entry while ours was in flight, the read path refetches the authoritative
   value rather than restoring a now-stale inverse. (re-frame2's deliberate
   divergence from TanStack Query / SWR's unconditional context restore — see
   the guide section above.)

   IDIOMATIC re-frame2. The board is a single managed resource; every write is a
   named `reg-mutation`; the toggle is an ordinary app-db slice driven by an
   event and read by a sub. The example ships no backend, so it overrides
   `:rf.http/managed` with a canned stub that synthesises the board reply and,
   when armed, the 503, so the whole optimistic lifecycle runs standalone."
  (:require [reagent.dom.client :as rdc]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.views]
            ;; Managed HTTP: the transport every resource and mutation lowers
            ;; its read/write onto. Loading the ns registers the
            ;; `:rf.http/managed` fx. See the guide glossary:
            ;; ../../../docs/resources/glossary.md#managed-http
            [re-frame.http.managed]
            ;; The framework's canned-reply fx that the demo stub delegates to.
            ;; The explicit opt-in for a demo app with no backend.
            [re-frame.http.test-support]
            ;; Resources. Loading the ns registers `reg-resource` / `reg-mutation`
            ;; and the `:rf.resource/*` + `:rf.mutation/*` subs; without it those
            ;; registrations throw.
            [re-frame.resources]
            ;; Routing. With resources also loaded, a route's `:resources` key is
            ;; accepted — route entry ensures the board.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view reg-event reg-sub]]))

;; ============================================================================
;; THE DOMAIN — issues + statuses
;; ============================================================================

(def ^:private statuses
  "The board columns, in order. An issue moves left to right through these as
   work progresses; `change-status` is the optimistic move."
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
;; write patches THIS one entry's `:data` in place, and the success reply
;; re-seeds it with the server's authoritative board via `:populates`. The
;; runtime owns the board value, its freshness, and the recorded inverse that
;; makes rollback truthful. The board lives in the resource cache, not app-db.

(rf/reg-resource :linearlite/board
  {:doc            "The whole issue board — `{:issues [...]}` — as one managed
                    server-state read. Optimistic writes patch this entry in
                    place before their request is sent; the success reply
                    re-seeds it with the server's authoritative board."

   ;; One public board in the demo (a real app would scope per-team / per-user
   ;; and key the team in :params); identity params are empty.
   :params-schema  [:map]

   ;; Public board: the same for every viewer, so the scope is the explicit
   ;; global claim (there is no implicit default). A per-team board would carry
   ;; a scope resolver instead. Guide:
   ;; ../../../docs/resources/glossary.md#scope
   :scope          :rf.scope/global

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)

   ;; A board tag so an external change (a server push, say) can invalidate the
   ;; whole board by tag. The optimistic writes below patch the entry directly
   ;; and re-seed via `:populates` on commit, so they need no coarse
   ;; invalidation. Guide: ../../../docs/resources/glossary.md#cache-tag
   :tags           (fn [_params _data] #{[:board]})}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/board"}
     :decode  :json}))

;; ============================================================================
;; THE EXACT-TARGET — every optimistic write patches THIS key
;; ============================================================================

(def ^:private board-key
  "The exact resource target the board lives under: a `{:resource :scope
   :params}` map, reused as the `:optimistic` and `:populates` key. Empty
   `:params` means the one public board."
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(def ^:private board-query
  "The query map the view's `[:rf.resource/data …]` sub reads — the same board
   identity (resource + scope + params) the route ensured under and the writes
   patch."
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

;; A counter for client-minted optimistic ids. A new issue gets a stable `tmp-N`
;; id the moment its optimistic card appears, so it has a stable React key before
;; the server assigns its own. The success reply re-seeds the whole board with
;; the server's value (carrying the server id), so the temporary id never leaks
;; past commit; a rollback simply removes the never-committed card.
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
   leaving the rest of the board untouched. Forward only — the runtime records
   the inverse, so this fn never describes how to undo itself."
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
;; Each write declares `:optimistic` (the forward patch over the board entry,
;; applied before the request), `:populates` (the commit — re-seed the board
;; with the server's authoritative value on `:ok`), and `:on-conflict
;; :invalidate` (the default conflict policy). The runtime records the inverse
;; and each entry's revision itself; the reply settles deterministically.
;;
;; Writes do NOT retry (reads retry, writes don't) — a 503 surfaces as `:error`
;; and rolls the optimistic change back, which is what the demo's 'fail the next
;; write' toggle exercises.

(rf/reg-mutation :linearlite/create-issue
  {:doc           "Create an issue (optimistic). POST /api/issues. The new card
                   appears in Backlog the instant you submit; a failure rolls
                   it back out."
   :params-schema [:map [:id :string] [:title :string]]
   :scope         :rf.scope/global
   ;; FORWARD: append a new Backlog card to the board immediately, before the
   ;; request is sent. `:id` is the client-minted optimistic id so the card has
   ;; a stable React key before the server assigns one.
   :optimistic    (fn [{:keys [id title]}]
                    {board-key
                     (fn [data]
                       (update data :issues
                               (fn [issues]
                                 (upsert-issue issues
                                               {:id id :title title :status :backlog
                                                :optimistic? true}))))})
   ;; COMMIT: the `:ok` reply is the server's whole authoritative board; re-seed
   ;; the board entry with it, so the temporary id is replaced by the server's
   ;; and the optimistic flag clears. Same `{:issues …}` envelope the resource
   ;; stores.
   :populates     (fn [_params result] {board-key result})
   ;; ROLLBACK conflict policy (the default): on a contested rollback, the read
   ;; path refetches rather than restoring a stale inverse.
   :on-conflict   :invalidate}
  (fn [{:keys [title]} _ctx]
    {:request {:method :post :url "/api/issues"
               :body   {:title title}}
     :decode  :json}))

(rf/reg-mutation :linearlite/edit-title
  {:doc           "Retitle an issue (optimistic). PUT /api/issues/:id. The title
                   changes the instant you commit the edit; a failure reverts
                   it to the prior title."
   :params-schema [:map [:id :string] [:title :string]]
   :scope         :rf.scope/global
   :optimistic    (fn [{:keys [id title]}]
                    {board-key (patch-issue id #(assoc % :title title :optimistic? true))})
   :populates     (fn [_params result] {board-key result})
   :on-conflict   :invalidate}
  (fn [{:keys [id title]} _ctx]
    {:request {:method :put :url (str "/api/issues/" id)
               :body   {:title title}}
     :decode  :json}))

(rf/reg-mutation :linearlite/change-status
  {:doc           "Move an issue to another status (optimistic). PUT
                   /api/issues/:id. The card jumps to the new column the instant
                   you click; a failure snaps it back to its prior column."
   :params-schema [:map [:id :string] [:status :keyword]]
   :scope         :rf.scope/global
   :optimistic    (fn [{:keys [id status]}]
                    {board-key (patch-issue id #(assoc % :status status :optimistic? true))})
   :populates     (fn [_params result] {board-key result})
   :on-conflict   :invalidate}
  (fn [{:keys [id status]} _ctx]
    {:request {:method :put :url (str "/api/issues/" id)
               :body   {:status status}}
     :decode  :json}))

;; ============================================================================
;; DEMO BACKEND — a canned :rf.http/managed override (no server ships)
;; ============================================================================
;;
;; This example ships no backend, so it overrides `:rf.http/managed` (the fx
;; every read/write lowers onto) with a stub that holds the canonical board in a
;; closure and synthesises the authoritative reply for each read/write. It
;; delegates to the framework's `:rf.http/managed-canned-success` / `-failure`
;; with `:after-ms`, so the reply rides framework `:dispatch-later` (not a raw
;; js/setTimeout — it stays trace-visible and time-travel-safe) and the
;; optimistic value paints before the reply lands.
;;
;; THE FAILURE SEAM. `fail-next-write?` is read from app-db at request time:
;; when armed, the stub answers the next WRITE with a 503 and disarms, so a
;; single click drives the whole optimistic-apply → request → rollback arc.

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via `:after-ms` →
   `:dispatch-later`). Small but non-zero so the optimistic value is visibly
   painted before the reply lands. A demo-seam knob, not a production value."
  220)

;; The canonical server board the stub maintains across requests, so a committed
;; write persists into the next read (the stub IS the demo server). A create, a
;; retitle, and a status move all mutate this atom on a successful write, so the
;; next board read reflects them.
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
          ;; The fx context carries the envelope frame as `:frame`. Read the
          ;; demo seam flag off that frame's runtime db.
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
;; the sub.

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
;; The view watches the mutation instance and reads the board resource
;; passively; the runtime applies the optimistic patch, sends the request, and
;; commits / rolls back on the reply.

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
   :resources
   [{:resource  :linearlite/board
     :params    (fn [_route] {})
     :blocking? true}]} "/")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."} "/_404")

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
        ;; Watch this card's in-flight writes passively. `:optimistic?` is true
        ;; while a live optimistic apply is showing; `:error?` flags the last
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
;; The React root is materialised lazily inside `run`, not at ns-load, so the
;; example mounts only in a browser. The app frame is created, configured, and
;; provided in one spot: the render root's `frame-provider {:id app-frame …}`.
;; On first mount it creates the frame under `app-frame` and applies the config:
;; `:url-bound? true` so it owns the browser URL, and a `:rf.http/managed`
;; override pointing at the canned board stub so the example runs standalone. On
;; hot reload it reuses that same frame and keeps its app-db. Every in-tree
;; dispatch and subscribe resolves against that id. Guide:
;; ../../../docs/routing/glossary.md#url-bound
;;
;; The board is seeded by route entry, not at boot: the `:linearlite.app/board`
;; route's `:resources` metadata ensures it, driven by the initial URL sync
;; `rf/install-history-listener!` performs — hence no `:initial-events`.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/install-history-listener!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; The app frame is created and configured here. First mount creates it
    ;; under `app-frame` and applies the config; hot reload reuses it.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Linearlite optimistic-board demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :linearlite.demo/http-stub}}
                 [root-view]])))
