(ns linearlite.core
  "Optimistic writes, with rollback you don't have to write yourself.

   A small Linearlite-style issue tracker: a board of cards you create, retitle,
   and move between statuses. The hard part isn't the board — it's that a server
   write takes time but the user wants the screen to react *now*. So we show the
   change immediately, before the server has agreed to it, and then either keep
   it or take it back when the reply lands. That's an optimistic update, and done
   by hand it's a bug farm. Here the runtime owns the whole move: it applies your
   change up front, remembers exactly what was there before, and puts it back if
   the write fails. You write only the forward step.

   See the guide: [Optimistic writes commit, roll back, or
   reconcile](../../../docs/resources/concepts.md#optimistic-writes-commit-roll-back-or-reconcile).

   Four ideas, and they're the whole example:

   - The write shows immediately, and the view never touches the data. Each
     mutation declares an `:optimistic` patch — `(fn [params] -> {target
     patch-fn})`, a forward change aimed at one exact cache entry. It runs before
     the request is sent, so the new card appears, the title changes, or the card
     jumps columns the instant the user acts. The runtime holds the cache and
     hands the view the optimistic value; the view just reads it. There's no
     app-db issue list and no `:saving?` flag to keep in sync.

   - The inverse is recorded for you. You write only the forward patch. When it
     applies, the runtime snapshots each touched entry — its value and its
     revision. A rollback restores exactly the entry that was there, so the undo
     is correct by construction. No hand-written inverse to drift out of sync
     with reality, which is precisely the bit hand-rolled optimistic code tends
     to get subtly wrong.

   - The reply settles the same way every time. A success (`:ok`) commits:
     `:populates` overwrites the optimistic guess with the server's authoritative
     board. A failure (`:error`) rolls back: the recorded value returns and the
     optimistic change visibly reverts. The verdict reads recorded facts — the
     generation-acceptance verdict (which write this is) plus each entry's
     revision when the patch applied — so no wall-clock race decides who wins.

   - Rollback is the thing you actually watch. A 'Fail the next write' toggle
     arms the demo backend to answer the next mutation with a 503. The optimistic
     change paints, the request fails, and the runtime snaps the board back to
     its pre-click state: the new card vanishes, the retitled card reverts, the
     moved card jumps home.

   The board is read passively through `[:rf.resource/data …]`. Each in-flight
   write is watched through `[:rf.mutation/state {:instance …}]`, whose
   `:optimistic?` flag stays true while that write's optimistic value is still on
   screen — so a card can wear a 'saving…' badge over the value you're hoping
   sticks.

   The conflict policy. Each mutation names `:on-conflict :invalidate` (the
   default). The case it handles: our write fails and wants to roll back, but a
   competing write already moved the same entry while ours was in flight.
   Restoring our recorded inverse now would clobber that newer change with stale
   data, so the read path refetches the authoritative value instead. This is the
   one place re-frame2 deliberately parts ways with TanStack Query / SWR, which
   restore captured context unconditionally — see the guide section above.

   Otherwise it's plain re-frame2. The board is a single managed resource; each
   write is a named `reg-mutation`; the toggle and edit-draft are ordinary app-db
   slices an event writes and a sub reads. No backend ships with the example, so
   it overrides `:rf.http/managed` with a canned stub that synthesises the board
   reply and, when armed, the 503 — and the whole optimistic lifecycle runs
   standalone."
  (:require [reagent.dom.client :as rdc]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Managed HTTP — the one transport every resource and mutation
            ;; lowers its read/write onto. Loading the ns registers the
            ;; `:rf.http/managed` fx. Guide:
            ;; ../../../docs/resources/glossary.md#managed-http
            [re-frame.http.managed]
            ;; The framework's canned-reply fx. Our demo stub delegates to it
            ;; instead of hitting a network — the deliberate opt-in for an app
            ;; that ships no backend.
            [re-frame.http.test-support]
            ;; Resources. Loading the ns registers `reg-resource` /
            ;; `reg-mutation` and the `:rf.resource/*` + `:rf.mutation/*` subs.
            ;; Skip it and those registrations throw.
            [re-frame.resources]
            ;; Routing. With resources also loaded, a route may carry a
            ;; `:resources` key — that's how the board route ensures the board on
            ;; entry.
            [re-frame.routing]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; THE DOMAIN — issues + statuses
;; ============================================================================

(def ^:private statuses
  "The board's columns, left to right. An issue walks across them as work
   progresses, and `change-status` is the optimistic move that carries it."
  [{:id :backlog     :label "Backlog"}
   {:id :in-progress :label "In Progress"}
   {:id :done        :label "Done"}])

(def ^:private status-labels
  (into {} (map (juxt :id :label)) statuses))

;; ============================================================================
;; THE RESOURCE — the whole board is one managed server-state read
;; ============================================================================
;;
;; The entire issue board is a single resource entry: `{:issues [...]}`. The
;; route ensures it on entry; the view reads it passively. Every optimistic
;; write patches this one entry's `:data` in place, and the success reply
;; re-seeds it with the server's authoritative board via `:populates`. The
;; runtime owns the board's value, its freshness, and the recorded inverse that
;; makes rollback honest. It lives in the resource cache — state you don't own,
;; kept where state you don't own belongs, never in app-db.

(rf/reg-resource :linearlite/board
  {:doc            "The whole issue board — `{:issues [...]}` — as one managed
                    server-state read. Optimistic writes patch this entry in
                    place before their request is sent; the success reply
                    re-seeds it with the server's authoritative board."

   ;; One public board in the demo, so the identity params are empty. A real app
   ;; would scope this per-team or per-user and key the team into :params.
   :params-schema  [:map]

   ;; This board is the same for every viewer, so we make the global claim
   ;; explicit — there's no implicit default to fall back on. A per-team board
   ;; would carry a scope resolver here instead. Guide:
   ;; ../../../docs/resources/glossary.md#scope
   :scope          :rf.scope/global

   :stale-after-ms 60000
   :gc-after-ms    (* 5 60 1000)

   ;; A tag on the board, so something external (a server push, say) can
   ;; invalidate the whole thing by tag. The optimistic writes below patch the
   ;; entry directly and re-seed via `:populates` on commit, so they never need
   ;; this coarser hammer. Guide:
   ;; ../../../docs/resources/glossary.md#cache-tag
   :tags           (fn [_params _data] #{[:board]})}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/board"}
     :decode  :json}))

;; ============================================================================
;; THE TARGET — the one cache entry every optimistic write aims at
;; ============================================================================

(def ^:private board-key
  "Where the board lives in the cache: a `{:resource :scope :params}` map. We
   reuse it as both the `:optimistic` patch key and the `:populates` commit key,
   so every write aims at exactly the same entry. Empty `:params` means the one
   public board."
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

(def ^:private board-query
  "What the view's `[:rf.resource/data …]` sub reads. Same board identity
   (resource + scope + params) the route ensured under and the writes patch —
   read and write naming the same place is the whole trick."
  {:resource :linearlite/board :scope :rf.scope/global :params {}})

;; A counter for client-minted ids. The moment an optimistic card appears it
;; needs a stable React key, but the server hasn't named the issue yet — so we
;; hand it a `tmp-N` id to tide it over. On commit the success reply re-seeds the
;; whole board with the server's value (real id and all), so the temporary id
;; never outlives the round trip; a rollback just removes the card that never
;; landed.
(defonce ^:private optimistic-id-seq (atom 0))

(defn- next-issue-id
  "Mint the next client-side placeholder id (`tmp-N`)."
  []
  (str "tmp-" (swap! optimistic-id-seq inc)))

(defn- upsert-issue
  "Add an issue to the board's `:issues`, or replace it if its id is already
   there (keeping its place in line). Pure, and shared by the create + edit
   forward patches so the optimistic shape is written down once."
  [issues issue]
  (if (some #(= (:id %) (:id issue)) issues)
    (mapv #(if (= (:id %) (:id issue)) issue %) issues)
    (conj (vec issues) issue)))

(defn- patch-issue
  "Build a board-`:data` patch that applies `f` to the issue with `id` and
   leaves everything else alone. Forward only: the runtime records the inverse,
   so this never has to describe how to undo itself."
  [id f]
  (fn [data]
    (update data :issues
            (fn [issues]
              (mapv (fn [issue] (if (= id (:id issue)) (f issue) issue))
                    (or issues []))))))

;; ============================================================================
;; THE MUTATIONS — create / edit-title / change-status, all optimistic
;; ============================================================================
;;
;; Each write reads the same three keys. `:optimistic` is the forward patch over
;; the board entry, applied before the request goes out. `:populates` is the
;; commit — on `:ok`, re-seed the board with the server's authoritative value.
;; `:on-conflict :invalidate` is the (default) conflict policy. The runtime fills
;; in the rest itself: it records the inverse and each entry's revision, and the
;; reply settles deterministically.
;;
;; Writes don't retry — reads do, writes don't. A 503 surfaces as `:error` and
;; rolls the optimistic change back, which is exactly the arc the demo's 'fail
;; the next write' toggle puts on display.

(rf/reg-mutation :linearlite/create-issue
  {:doc           "Create an issue (optimistic). POST /api/issues. The new card
                   appears in Backlog the instant you submit; a failure rolls
                   it back out."
   :params-schema [:map [:id :string] [:title :string]]
   :scope         :rf.scope/global
   ;; Forward: append a new Backlog card right away, before the request is sent.
   ;; `:id` is the client-minted placeholder so the card has a stable React key
   ;; until the server names it.
   :optimistic    (fn [{:keys [id title]}]
                    {board-key
                     (fn [data]
                       (update data :issues
                               (fn [issues]
                                 (upsert-issue issues
                                               {:id id :title title :status :backlog
                                                :optimistic? true}))))})
   ;; Commit: the `:ok` reply is the server's whole authoritative board. Re-seed
   ;; the entry with it — the placeholder id gives way to the server's, and the
   ;; optimistic flag clears. Same `{:issues …}` envelope the resource stores.
   :populates     (fn [_params result] {board-key result})
   ;; On conflict (the default): if a rollback is contested, refetch rather than
   ;; restore a now-stale inverse. See the conflict-policy note at the top.
   :on-conflict   :invalidate}
  (fn [{:keys [title]} _ctx]
    {:request {:method :post :url "/api/issues"
               :body   {:title title}}
     :decode  :json}))

(rf/reg-mutation :linearlite/edit-title
  {:doc           "Retitle an issue (optimistic). PUT /api/issues/:id. The title
                   changes the instant you save; a failure puts the old one
                   back."
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
                   you pick it; a failure snaps it home."
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
;; There's no server here. So we override `:rf.http/managed` (the fx every
;; read/write lowers onto) with a small stub that holds the canonical board in a
;; closure and builds the authoritative reply for each read/write. It delegates
;; to the framework's `:rf.http/managed-canned-success` / `-failure` with
;; `:after-ms`, so the reply rides the framework's `:dispatch-later` rather than
;; a raw js/setTimeout — even the fake latency stays trace-visible and
;; time-travel-safe — and the optimistic value gets to paint before the reply
;; arrives.
;;
;; The failure seam. `fail-next-write?` is read from app-db at request time:
;; when it's armed, the stub answers the next WRITE with a 503 and disarms
;; itself. One click, and you've driven the whole optimistic-apply → request →
;; rollback arc.

(def ^:private demo-reply-delay-ms
  "How long the demo stub holds each canned reply back (via `:after-ms` →
   `:dispatch-later`). Small, but not zero — the optimistic value needs a moment
   on screen before the reply lands, or you'd never see it. A demo knob, not a
   production value."
  220)

;; The canonical server board, kept across requests so a committed write shows up
;; in the next read. The stub *is* the demo server, and this atom is its
;; database. A create, a retitle, and a status move each mutate it on success.
(defonce ^:private demo-board
  (atom {:issues [{:id "srv-1" :title "Wire up the optimistic board" :status :in-progress}
                  {:id "srv-2" :title "Render the three status columns" :status :done}
                  {:id "srv-3" :title "Add the create-issue form"       :status :backlog}]}))

(defn- strip-optimistic
  "Scrub the `:optimistic?` marker off every issue. That marker is a view hint
   the optimistic patch adds locally; the authoritative board the server hands
   back is the real thing and carries no such tell, so we clean it off here."
  [board]
  (update board :issues (fn [issues] (mapv #(dissoc % :optimistic?) issues))))

(defn- next-server-id [board]
  (str "srv-" (inc (count (:issues board)))))

(defn- apply-write!
  "Apply a successful write to the canonical demo board and return the new
   authoritative board — the value that becomes the `:populates` payload. Routed
   the way a real server would route it, by method + URL: POST mints a fresh
   server id for the new issue; PUT patches title and/or status by id from the
   request body."
  [method url body]
  (swap! demo-board
         (fn [board]
           (cond
             ;; POST /api/issues — a create. Mint a server id for the new issue.
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
  {:doc       "Demo stand-in for `:rf.http/managed`. Holds the canonical board in
               a closure and builds the authoritative reply for each read/write,
               delegating to `:rf.http/managed-canned-success` / `-failure` with
               `:after-ms`. Reads the `:fail-next-write?` app-db flag at request
               time: when armed, the next WRITE answers a 503 — driving the
               rollback arc — and disarms itself."
   :platforms #{:client}}
  (fn fx-managed-board-demo [frame-ctx args-map]
    (let [req       (:request args-map)
          method    (or (:method req) :get)
          url       (str (:url req))
          body      (:body req)
          write?    (not= method :get)
          ;; The fx context carries the envelope frame as `:frame`. The
          ;; `:fail-next-write?` toggle is an ordinary app-db slice (written by
          ;; `:linearlite/set-fail-next-write`, a plain db-in/db-out handler), so
          ;; read it off the frame's APP-db partition (`:rf.db/app`). Reading
          ;; `:rf.db/runtime` here — where the flag never lives — is why the
          ;; simulated rollback never fired.
          db        (:rf.db/app (rf/frame-state-value (:frame frame-ctx)))
          fail?     (and write? (boolean (:fail-next-write? db)))]
      (if fail?
        ;; Armed: answer the WRITE with a 503 so the optimistic apply rolls back.
        ;; Disarm first (it's a one-shot), then hand off to the canned-failure
        ;; fx — its reply rides `:after-ms` → `:dispatch-later` like any other.
        (let [failure {:kind :rf.http/http-5xx :status 503
                       :message "Simulated server failure (the demo's rollback seam)."}
              stub    (registrar/handler :fx :rf.http/managed-canned-failure)]
          (rf/dispatch [:linearlite/set-fail-next-write false])
          (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :failure failure)))
        ;; Otherwise, success: a read returns the current board; a write mutates
        ;; the canonical board and returns the new authoritative one.
        (let [payload (if write?
                        (apply-write! method url body)
                        (strip-optimistic @demo-board))
              stub    (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))))

;; ============================================================================
;; APP-DB — the demo toggle + the inline edit draft (ordinary slices)
;; ============================================================================
;;
;; The only state app-db carries here is UI chrome: the failure toggle, the
;; new-issue draft, and the id of the card being inline-retitled. The issue
;; board itself is *not* in app-db — the resource owns it. These three are
;; ordinary slices in the usual loop: an event writes, a sub reads, the view
;; reads the sub.

(rf/reg-event :linearlite/set-fail-next-write
  (fn [db [_ on?]] (assoc db :fail-next-write? on?)))

(rf/reg-event :linearlite/set-new-issue-draft
  (fn [db [_ text]] (assoc db :new-issue-draft text)))

(rf/reg-event :linearlite/begin-edit
  (fn [db [_ id title]] (assoc db :editing {:id id :title title})))

(rf/reg-event :linearlite/set-edit-draft
  (fn [db [_ text]] (assoc-in db [:editing :title] text)))

(rf/reg-event :linearlite/cancel-edit
  (fn [db _] (dissoc db :editing)))

(rf/reg-sub :linearlite/fail-next-write? (fn [db _] (boolean (:fail-next-write? db))))
(rf/reg-sub :linearlite/new-issue-draft  (fn [db _] (or (:new-issue-draft db) "")))
(rf/reg-sub :linearlite/editing          (fn [db _] (:editing db)))

;; --- the write events (fire the mutation, then get out of the way) ----------
;;
;; Each write is a thin event: it dispatches `:rf.mutation/execute` with an
;; instance id (per-issue, or per-create) and clears whatever UI slice it was
;; using. That's all it does. The view watches the mutation instance and reads
;; the board passively; the runtime does the real work — apply the optimistic
;; patch, send the request, commit or roll back on the reply.

(rf/reg-event :linearlite/create-issue
  (fn [{:keys [db]} [_ title]]
    (let [tmp-id (next-issue-id)]
      {:db (assoc db :new-issue-draft "")
       :fx [[:dispatch [:rf.mutation/execute
                        {:mutation :linearlite/create-issue
                         :params   {:id tmp-id :title title}
                         :instance [:create tmp-id]
                         :cause    [:user :linearlite/create-issue]}]]]})))

(rf/reg-event :linearlite/commit-edit
  (fn [{:keys [db]} [_ id title]]
    {:db (dissoc db :editing)
     :fx [[:dispatch [:rf.mutation/execute
                      {:mutation :linearlite/edit-title
                       :params   {:id id :title title}
                       :instance [:edit id]
                       :cause    [:user :linearlite/edit-title]}]]]}))

(rf/reg-event :linearlite/change-status
  (fn [_ [_ id status]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation :linearlite/change-status
                       :params   {:id id :status status}
                       :instance [:status id]
                       :cause    [:user :linearlite/change-status]}]]]}))

;; ============================================================================
;; ROUTES — entering the route is what ensures the board read
;; ============================================================================

(rf/reg-route :linearlite.app/board
  {:doc   "The issue board. Entering this route ensures the :linearlite/board
           resource, so the data is on its way before the view asks for it."
   :resources
   [{:resource  :linearlite/board
     :params    (fn [_route] {})
     :blocking? true}]} "/")

(rf/reg-route :rf.route/not-found
  {:doc  "Fallback for unmatched URLs."} "/_404")

;; ============================================================================
;; VIEWS — passive board read + watched mutation instances
;; ============================================================================

(rf/reg-view fail-toggle []
  (let [armed? @(subscribe [:linearlite/fail-next-write?])]
    [:label.fail-toggle {:data-testid "fail-toggle"}
     [:input {:type      "checkbox"
              :checked   armed?
              :on-change #(dispatch [:linearlite/set-fail-next-write
                                     (.. % -target -checked)])}]
     [:span "Fail the next write (simulate a server failure → rollback)"]]))

(rf/reg-view new-issue-form []
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

(rf/reg-view status-picker [{:keys [id status]}]
  ;; Pick a new column. This fires the optimistic change-status mutation: the
  ;; card jumps right away, then commits or rolls back when the reply lands.
  [:select {:data-testid (str "status-" id)
            :value       (name status)
            :on-change   #(dispatch [:linearlite/change-status
                                     id (keyword (.. % -target -value))])}
   (for [{s-id :id s-label :label} statuses]
     ^{:key s-id} [:option {:value (name s-id)} s-label])])

(rf/reg-view issue-card [{:keys [issue editing]}]
  (let [{:keys [id title status optimistic?]} issue
        editing-this? (= id (:id editing))
        ;; Watch this card's in-flight writes, passively. `:optimistic?` stays
        ;; true while an optimistic value is on screen; `:error?` flags the last
        ;; write's failure — by which point the value has already rolled back.
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
       ;; Inline retitle: saving fires the optimistic edit-title mutation.
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

(rf/reg-view board-column [{:keys [status issues editing]}]
  [:div.column {:data-testid (str "column-" (:id status))}
   [:h2 (:label status)]
   (into [:ul.issue-list]
         (for [issue issues]
           ^{:key (:id issue)} [issue-card {:issue issue :editing editing}]))])

(rf/reg-view board-page []
  ;; The route already ensured the board on entry (via its `:resources`
  ;; metadata), so this view just reads the whole thing passively through
  ;; `[:rf.resource/data …]`. Every write patches the cache, so the view
  ;; re-renders with the optimistic value the instant a write fires — and again
  ;; when it commits or rolls back. The view never has to ask for the data; it
  ;; just shows whatever's currently there.
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
       ;; First load, nothing to show yet → a skeleton.
       (:loading? state)
       [:p {:data-testid "board-skeleton"} "Loading the board…"]

       ;; First load failed and we have no board to fall back on → error screen.
       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "board-error"} "Could not load the board."]

       :else
       (into [:div.board {:data-testid "board"}]
             (for [status statuses]
               ^{:key (:id status)}
               [board-column {:status  status
                              :issues  (filterv #(= (:id status) (:status %)) issues)
                              :editing editing}])))]))

(rf/reg-view not-found-page []
  [:div
   [:h1 "Not found"]
   [:p [rf/route-link {:to :linearlite.app/board :data-testid "route-link-board"} "Back to the board"]]])

(rf/reg-view root-view []
  (case @(subscribe [:rf.route/id])
    :linearlite.app/board [board-page]
    :rf.route/not-found   [not-found-page]
    [board-page]))

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; We build the React root lazily inside `run`, not at ns-load, so the example
;; only mounts in a browser. The app frame is created, configured, and provided
;; all in one place: the render root's `frame-provider {:id app-frame …}`. On
;; first mount it creates the frame under `app-frame` and applies the config —
;; `:url-bound? true` so the frame owns the browser URL, and a `:rf.http/managed`
;; override pointing at our canned stub so the whole thing runs standalone. On
;; hot reload it finds that same frame and keeps its app-db. Every dispatch and
;; subscribe inside the tree resolves against that id. Guide:
;; ../../../docs/routing/glossary.md#url-bound
;;
;; Notice there are no `:initial-events`. The board isn't seeded at boot — it's
;; seeded by entering the route. The `:linearlite.app/board` route's `:resources`
;; metadata ensures it, kicked off by the first URL sync `:url-bound? true`
;; performs automatically when the frame is created.

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Frame created and configured right here. First mount builds it under
    ;; `app-frame` and applies the config; hot reload reuses it.
    (rdc/render @react-root
                [rf/frame-provider {:id           app-frame
                                    :doc          "Linearlite optimistic-board demo frame."
                                    :url-bound?   true
                                    :fx-overrides {:rf.http/managed :linearlite.demo/http-stub}}
                 [root-view]])))
