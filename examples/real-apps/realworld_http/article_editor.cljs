(ns realworld-http.article-editor
  "Article editor for the RealWorld (Conduit) example.

   Create or edit an article — same form, two modes — and along the way this
   file shows off a state machine, a flow, and tag-driven views all working
   together. Worth a read for:

   - One parallel state machine `:ui/article-editor`, two orthogonal regions
     (`:mode` x `:lifecycle`). See the machines guide on parallel regions:
     ../../../docs/machines/concepts.md#when-the-machine-grows.
   - A form that splits the work cleanly: the draft / errors / touched /
     submit-error data lives in an app-db slice (`:editor`), while the machine
     carries only the state vocabulary. `:editor/dirty?` is just a
     draft-vs-baseline sub — no machine involved. See the forms how-to:
     ../../../docs/core/how-to/build-a-form.md.
   - The view asking the machine yes/no questions through tag queries
     (`@(rf/subscribe [:rf.machine/has-tag? :ui/article-editor :editor/busy])` and
     `… :editor/can-delete`) instead of hand-rolled boolean subs.
   - The view's root: one `case` over `:article-editor/render`, a selector sub
     that reads a render-priority table against the machine's tag union."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; State machines live in their own artefact; we require it to load
            ;; it, which registers the hooks that make `rf/reg-machine` (below)
            ;; and the `:rf/machine` subs resolve. See the machines guide:
            ;; ../../../docs/machines/index.md
            [re-frame.machines]
            ;; Flows live in their own artefact too. The editor registers a
            ;; `:editor/can-submit?` flow (see :editor/initialise below) that
            ;; keeps "valid AND dirty" materialised in app-db. Requiring the ns
            ;; registers the hooks behind the `:rf.fx/reg-flow` effect. See the
            ;; flows guide: ../../../docs/core/flows.md
            [re-frame.flows]
            [realworld-shared.schema :as schema]
            [realworld-http.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def blank-draft
  {:title "" :description "" :body "" :tagList ""})

(defn draft-from-article [article]
  {:title       (:title article)
   :description (:description article)
   :body        (:body article)
   :tagList     (str/join ", " (:tagList article))})

(defn parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn editor-slice
  "The form's app-db slice — draft, baseline, errors, touched,
   submit-attempted?, submit-error. Division of labour: the machine owns the
   state vocabulary, this slice owns the data."
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug              slug
    :draft             baseline
    :baseline          baseline
    :submitted         nil
    :errors            {}
    :touched           #{}
    :submit-attempted? false
    :submit-error      nil}))

(defn seed-slice
  "Fold a loaded article into the editor slice WITHOUT clobbering typing.

   The obvious spelling — replace the whole slice with a fresh
   `(editor-slice slug (draft-from-article article))` — loses every keystroke the
   user made while the GET was still in flight, because entering the editor and
   typing is faster than a round trip. `FH-CTRL-013` states that seed law for
   `re-frame.freehand` forms; this app holds its own `:touched` set rather than a
   freehand form, so it spells the same rule by hand.

   The seed is LEAFWISE. A field the user has already touched keeps BOTH its
   draft and its baseline, so their text stays on screen AND stays dirty — the
   save that follows sends what they typed. Every untouched field takes the
   loaded value in both, so the dirty-check compares against what the server
   actually holds, and drops the validation error its old (blank) value earned.

   With nothing touched — the ordinary load — every field is seeded and the
   result is exactly the whole-slice replacement it replaces. Note that
   `:editor/submit-success` deliberately does NOT go through here: a completed
   save rebases the whole form to clean, touched fields included."
  [slice slug loaded]
  (let [touched (or (:touched slice) #{})
        seeded  (remove touched (keys loaded))
        seed-in #(reduce (fn [m k] (assoc m k (get loaded k))) % seeded)]
    (-> slice
        (assoc :slug slug)
        (update :draft seed-in)
        (update :baseline seed-in)
        (update :errors #(apply dissoc % seeded)))))

(defn still-editing?
  "Does a reply for `slug` still belong to the slice we are about to write?

   The editor slice holds ONE article at a time, and `[:editor :slug]` names
   which. `:editor/load-article` stamps it on entry to `/editor/:slug`;
   `:editor/reset` nils it on entry to `/editor`. So comparing the slug the
   reply was REQUESTED for against the slug the slice currently targets answers
   exactly the question a late reply raises: is this still my article?

   Why the correlation is needed at all — the two gates are independent, and
   neither substitutes for the other:

   - `seed-slice` (above) is the LEAFWISE gate. It decides WHICH FIELDS a reply
     may overwrite, and it protects a field the user has touched. It is no help
     across articles: a reply for A lands on B's slice with every field
     untouched relative to B's baseline, so the merge would take all of A's
     values quite happily.
   - This is the CORRELATION gate. It decides WHETHER THE REPLY BELONGS TO THIS
     SCREEN at all, before any field is considered.

   And the `:request-id` does not stand in for it. `[:editor/load-article slug]`
   makes A's and B's requests DISTINCT ids, so managed HTTP's same-id supersede
   (Spec 014 §`:request-id` (internal)) never fires between them and A's reply
   is delivered in full. Collapsing the id to a bare `:editor/load-article`
   would suppress A when B is lowered, but only then — leaving `/editor/A` for
   `/editor` (create) or for any non-editor page lowers no superseding request,
   so the late A reply would still arrive. Supersede is an optimisation;
   correlating the reply is the correctness boundary, which is why the app owns
   it (Spec 014: navigation staleness for a plain managed request is the app's,
   not the fx's).

   The resources twin spells the same rule against the ROUTE
   (`realworld-resources.article-editor` `:editor/article-loaded`), because
   there the ROUTE owns the article read. Here the EVENT owns the request and
   stamps the slice in the same handler that lowers it, so the slice's own slug
   is the tighter fact — and reading it needs no runtime coeffect."
  [db slug]
  (= slug (get-in db [:editor :slug])))

(defn validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

;; ============================================================================
;; THE FLOW — :editor/can-submit?
;; ============================================================================
;;
;; This is the example's worked flow, and it answers a deceptively small
;; question: should the Submit button be live? `:editor/can-submit?` keeps a
;; derived boolean — "the draft validates AND differs from the loaded
;; baseline" — sitting in app-db at `[:editor :can-submit?]`, recomputed
;; whenever its inputs move.
;;
;; Why a flow rather than a plain sub? Because `:editor/submit` needs to read
;; this gate as ordinary app-db data, with a `get-in`. A flow makes the
;; derived value just another fact in app-db; a sub would drag the handler
;; into `subscribe`-mid-handler territory, which is exactly the kind of thing
;; that turns a tidy handler into a knot. And the view that disables the button
;; reads the very same value through a plain sub over the flow's
;; `:output-path` — one source of truth, two readers. See the flows guide on
;; when a derivation earns a place in app-db:
;; ../../../docs/core/flows.md#when-a-derivation-earns-app-db
;;
;; The flow registers per-frame via `:rf.fx/reg-flow` from `:editor/initialise`
;; (below) — dispatched ONCE at boot from `:app/initialise`, not on every
;; editor entry — so it binds as a singleton to whichever frame the app booted
;; on (the default one in the browser, or each throwaway frame a headless
;; fixture spins up) and lives as long as that frame. Per-route entry runs the
;; lighter `:editor/reset` (slice wipe only), so no fresh flow registration
;; leaks on each visit.

;; This value uses the canonical `[flow-id metadata derive-fn]` triple shared by
;; `reg-flow` and `:rf.fx/reg-flow`, so `[:rf.fx/reg-flow can-submit-flow]`
;; passes the complete registration through unchanged.
(def can-submit-flow
  [:editor/can-submit?
   {:doc    "True when the editor draft is both valid AND dirty (it differs from
             the loaded baseline). Kept in app-db so the submit handler can read
             it as plain data."
    :inputs [[:editor :draft] [:editor :baseline]]
    :output-path [:editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate-draft draft))
          (not= draft baseline)))])

(defn article-body [draft]
  {:article {:title       (:title draft)
             :description (:description draft)
             :body        (:body draft)
             :tagList     (parse-tag-list (:tagList draft))}})

;; ============================================================================
;; THE MACHINE — :ui/article-editor  (one machine, two regions)
;; ============================================================================
;;
;; Two independent questions about the editor, so two regions:
;;
;;   :mode      — are we creating or editing? :create is the /editor route
;;                (POST to /articles); :edit is /editor/:slug (PUT to
;;                /articles/:slug, plus a Delete button). The :edit state also
;;                lights the :editor/can-delete tag, so the view can ask "show
;;                the Delete button?" as a tag question instead of reaching into
;;                the region.
;;
;;   :lifecycle — where the form is in its life: :idle | :loading |
;;                :submitting | :saved | :error. :loading and :submitting both
;;                fly the :editor/busy tag, which is how the view greys out the
;;                inputs without needing a separate `:submitting?` sub.
;;
;; As always with a parallel machine, every event reaches every region — so the
;; region event names stay distinct to avoid crosstalk, and each region treats
;; `:reset` as a self-target.

(rf/defmachine editor-machine
  {:type :parallel

   :regions
   {;; ---- :mode region — create vs edit ----
    :mode
    {:initial :create
     :states
     {:create
      ;; The /editor route. POST on submit. No Delete button.
      {:tags #{:mode/create}
       :on   {:use-edit   :edit
              :use-create :create
              :reset      :create}}

      :edit
      ;; The /editor/:slug route. PUT on submit. Delete button visible.
      {:tags #{:mode/edit :editor/can-delete}
       :on   {:use-create :create
              :use-edit   :edit
              :reset      :create}}}}

    ;; ---- :lifecycle region — the form lifecycle ----
    :lifecycle
    {:initial :idle
     :states
     {:idle
      {:tags #{:lifecycle/idle}
       :on   {:fetch-started  :loading
              :submit-started :submitting
              :reset          :idle}}

      :loading
      ;; Edit mode's initial article fetch in flight. Inputs disable
      ;; via the :editor/busy tag.
      {:tags #{:lifecycle/loading :editor/busy}
       :on   {:fetch-succeeded :idle
              :fetch-failed    :error
              :reset           :idle}}

      :submitting
      ;; Save in flight (POST or PUT, decided by :mode) or destructive
      ;; delete in flight. Inputs disable via the :editor/busy tag.
      {:tags #{:lifecycle/submitting :editor/busy}
       :on   {:submit-succeeded :saved
              :submit-failed    :idle
              :reset            :idle}}

      :saved
      ;; A blink-and-you'll-miss-it state. :editor/submit-success navigates
      ;; straight to the article detail page, so we're barely here — but it's
      ;; in the table so the lifecycle is honestly complete.
      {:tags #{:lifecycle/saved}
       :on   {:reset :idle}}

      :error
      ;; This is the LOAD-failed state specifically. A submit failure sends you
      ;; back to :idle to try again; a load failure lands here so the page can
      ;; raise an error banner. (The actual submit-error message lives in the
      ;; editor slice — this state is just the page-level render gate.)
      {:tags #{:lifecycle/error}
       :on   {:fetch-started  :loading
              :submit-started :submitting
              :reset          :idle}}}}}})

(rf/reg-machine :ui/article-editor editor-machine)

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :editor/initialise
  {:doc "Boot-time one-shot: register the `:editor/can-submit?` flow ONCE
         against the frame (a singleton keyed to the fixed `[:editor …]`
         slice), and wipe the slice + machine to a clean start. Dispatched
         once from `:app/initialise` at boot — NOT per route entry.

         Why register the flow here and not on the route's `:on-match`?
         `:rf.fx/reg-flow` replaces by id, so re-registering on every
         `/editor` entry doesn't stack copies — but it DOES leave the flow
         registered after you leave the editor, quietly recomputing against
         the editor slice for the rest of the session. Registering it once at
         boot (the flow lives as long as the frame, like the slice it derives
         over) is the honest singleton shape; each route entry then just
         RESETS the slice via `:editor/reset` (below), which does not touch
         the flow registry."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]
          [:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/reset
  {:doc "Per-entry slice reset — the route `:on-match` handler for `/editor`.
         Wipes the editor slice and machine back to a clean start WITHOUT
         re-registering the `:editor/can-submit?` flow (that singleton was
         registered once at boot by `:editor/initialise`). Splitting the
         per-entry reset from the one-time flow registration is what stops
         the flow leaking a fresh registration on every editor visit."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]]}))

(rf/reg-event :editor/load-article
  {:doc "Pull an existing article into the editor for editing. The house
         data-fetch retry policy applies. Broadcasts `:use-edit` so the :mode
         region flips to edit, and `:fetch-started` so the :lifecycle region
         moves to :loading while the fetch is out.

         Both reply targets CARRY THE REQUESTED SLUG. A GET is slower than a
         click, so the reader can be on `/editor/B` (or `/editor`, or a page
         with no editor on it at all) by the time A comes back; the slug rides
         along so `still-editing?` can tell the reply apart from the slice it
         would otherwise land on. See that helper for why neither `seed-slice`
         nor the `:request-id` covers this."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  ;; The route lives in runtime-db.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx [[:dispatch [:ui/article-editor [:use-edit]]]
            [:dispatch [:ui/article-editor [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/articles/" slug)
                          :decode     schema/ArticleResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:editor/load-article slug]
                          :on-success [:editor/loaded slug]
                          :on-failure [:editor/load-failed slug]})]]})))

(rf/reg-event :editor/loaded
  {:doc "The GET's `:on-success`, carrying the slug it was requested for.

         Two gates, in order, and they answer different questions:

         1. CORRELATION (`still-editing?`) — is this reply still for the
            article the slice holds? A reply for A that arrives after the
            reader moved to `/editor/B` is dropped on the floor: it must not
            rewrite B's draft, B's baseline or B's slug, and it must not tell
            the machine B's fetch succeeded.

         2. LEAFWISE SEED (`seed-slice`) — of the fields this reply MAY write,
            which does it actually get? Not the ones the user has touched: the
            round trip is slower than the first keystroke, so a whole-slice
            replacement would discard what they typed."}
  (fn [{:keys [db]} [_ slug {:keys [value]}]]
    (when (still-editing? db slug)
      (let [article (:article value)
            draft   (draft-from-article article)]
        {:db (update db :editor seed-slice (:slug article) draft)
         :fx [[:dispatch [:ui/article-editor [:fetch-succeeded]]]]}))))

(rf/reg-event :editor/load-failed
  {:doc "The GET's `:on-failure`, correlated exactly as `:editor/loaded` is —
         a late failure for A must not raise an error banner over B's draft or
         push B's lifecycle region into :error while B's own fetch is still
         out."}
  (fn [{:keys [db]} [_ slug {:keys [error]}]]
    (when (still-editing? db slug)
      {:db (assoc-in db [:editor :submit-error] (rh/failure->message error))
       :fx [[:dispatch [:ui/article-editor [:fetch-failed]]]]})))

(rf/reg-event :editor/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field))}))

(rf/reg-event :editor/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:editor :touched] (fnil conj #{}) field)}))

(rf/reg-event :editor/submit
  {:doc "Save the article — POST to create, PUT to edit. No retry; it's one
         submission per click, and any error is surfaced so the user gets to
         decide whether to try again.

         It checks the `:mode` region to pick POST vs PUT, broadcasts
         `:submit-started` into the lifecycle region, and lets the
         `:on-success` / `:on-failure` replies broadcast
         `:submit-succeeded` / `:submit-failed`.

         The gate is the `:editor/can-submit?` flow output, read straight off
         app-db as plain data. Valid-but-unchanged → the flow is false and the
         whole submit is a harmless no-op; an invalid draft re-runs validation
         to fill in the per-field error map for display. So the button being
         disabled and the handler bailing out agree, by construction."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  ;; The machine snapshot lives in runtime-db.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug draft]} (:editor db)
          mode        (get-in rt [:rf.runtime/machines :snapshots :ui/article-editor :state :mode])
          ;; Flow output — read as plain app-db data.
          can-submit? (get-in db [:editor :can-submit?])
          errors      (validate-draft draft)]
      (cond
        ;; Valid but unchanged → nothing to save. The button is already
        ;; disabled in this state (via the :editor/can-submit? sub), so this
        ;; branch is the belt-and-braces guard for a programmatic dispatch that
        ;; sidesteps the button entirely.
        (and (not can-submit?) (empty? errors))
        {}

        (seq errors)
        ;; Validation failed on the client. Flip :submit-attempted? so every
        ;; per-field error shows, even on fields the user never touched. The
        ;; whole-form prompt sits in `:errors :_form`; transport failures take
        ;; a different door — `:submit-error`, on the HTTP path.
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] (assoc errors :_form "Please fix the highlighted fields."))
                 (assoc-in [:editor :submit-error] nil))}

        :else
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :submitted] draft)
                 (assoc-in [:editor :errors] {})
                 (assoc-in [:editor :submit-error] nil))
         :fx [[:dispatch [:ui/article-editor [:submit-started]]]
              [:rf.http/managed
               (rh/request {:method     (if (= mode :edit) :put :post)
                            :path       (if (= mode :edit)
                                          (str "/articles/" slug)
                                          "/articles")
                            :body       (article-body draft)
                            :decode     schema/ArticleResponse
                            :on-success [:editor/submit-success]
                            :on-failure [:editor/submit-error]})]]}))))

(rf/reg-event :editor/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [article (:article value)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (editor-slice (:slug article) draft))
       :fx [[:dispatch [:ui/article-editor [:use-edit]]]
            [:dispatch [:ui/article-editor [:submit-succeeded]]]
            [:dispatch [:rf.route/navigate {:to :realworld.article/show :params {:slug (:slug article)}}]]]})))

(rf/reg-event :editor/submit-error
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message error))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

(rf/reg-event :editor/delete
  {:doc "Delete the article. No retry — destructive, one click. Broadcasts
         `:submit-started` to push the lifecycle region into :submitting (which
         flies the :editor/busy tag, so the form locks while it's working)."}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:editor :slug])]
      {:fx [[:dispatch [:ui/article-editor [:submit-started]]]
            [:rf.http/managed
             (rh/request {:method     :delete
                          :path       (str "/articles/" slug)
                          ;; A successful DELETE comes back empty (a 204), so
                          ;; `:auto` is the right call — it shrugs at an empty
                          ;; body instead of trying to parse one.
                          :decode     :auto
                          :on-success [:editor/delete-success]
                          :on-failure [:editor/delete-error]})]]})))

(rf/reg-event :editor/delete-success
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]
          [:dispatch [:rf.route/navigate {:to :realworld/home}]]]}))

(rf/reg-event :editor/delete-error
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message error))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor/slice
  (fn [db _] (:editor db)))

(rf/reg-sub :editor/draft :<- [:editor/slice] (fn [editor _] (:draft editor)))
(rf/reg-sub :editor/errors :<- [:editor/slice] (fn [editor _] (:errors editor)))
(rf/reg-sub :editor/submit-error :<- [:editor/slice]
  (fn [editor _] (:submit-error editor)))

(rf/reg-sub :editor/field-error
  {:doc "The validation error for one field, or nil while we keep mum. Same
         courtesy as every other form here: nothing shown until the field is
         touched or the user has tried to submit. See the forms how-to:
         ../../../docs/core/how-to/build-a-form.md"}
  :<- [:editor/slice]
  (fn [editor [_ field]]
    (when (or (:submit-attempted? editor)
              (contains? (:touched editor) field))
      (get-in editor [:errors field]))))

(rf/reg-sub :editor/form-error
  {:doc "The one-line, whole-form nudge (the :_form key under :errors) —
         set when a submit attempt tripped client-side validation."}
  :<- [:editor/slice]
  (fn [editor _]
    (when (:submit-attempted? editor)
      (get-in editor [:errors :_form]))))
(rf/reg-sub :editor/dirty?
  :<- [:editor/slice]
  (fn [editor _]
    (not= (:draft editor) (:baseline editor))))

(rf/reg-sub :editor/can-submit?
  {:doc "Reads the `:editor/can-submit?` flow's output at its app-db
         :output-path. A flow's output is just ordinary app-db state, so you
         read it with a plain sub over the path — there's no special
         flow-sub-id to learn. This one drives the submit button's `:disabled`.
         It's nil until the flow's first computation lands, which the
         `(boolean ...)` wrapper tidies into a plain false."}
  (fn [db _]
    (boolean (get-in db [:editor :can-submit?]))))

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _]
    (not dirty?)))

;; ---- render-priority + :article-editor/render selector ----
;;
;; Same data-driven trick as the home page: a plain vector of {:tag :render}
;; pairs, read in order. `:article-editor/render` looks at the machine's active
;; tags and returns the first :render whose :tag is present, and the editor
;; view's `case` on that keyword is the only place anything branches.
;;
;; The order is the policy, and here the lifecycle region calls the shots:
;; `:error` (load failed) shows the form with an error banner; `:loading`
;; (edit-mode's opening fetch) shows the form with greyed-out inputs; `:saved`
;; is the blink-state right before navigation, listed only so the table's
;; honest. Everything else falls through to `:editing` — the form just being a
;; form.

(def render-priority
  [{:tag :lifecycle/error      :render :error}
   {:tag :lifecycle/loading    :render :loading}
   {:tag :lifecycle/saved      :render :saved}
   {:tag :lifecycle/submitting :render :editing}
   {:tag :lifecycle/idle       :render :editing}])

(rf/reg-sub :article-editor/render
  {:doc "Reduce the `:ui/article-editor` machine's active tags to a single
         render keyword, via the render-priority table. The root view's `case`
         on it is the one and only branch site."}
  :<- [:rf/machine :ui/article-editor]
  (fn sub-editor-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The one editor form, shared by every render-mode. It leans on
                   the machine's tags to adapt: `:editor/busy` greys out the
                   inputs, `:editor/can-delete` reveals the Delete button. One
                   form, two modes, no duplication."}
          editor-form []
  (let [draft        @(subscribe [:editor/draft])
        title-err    @(subscribe [:editor/field-error :title])
        desc-err     @(subscribe [:editor/field-error :description])
        body-err     @(subscribe [:editor/field-error :body])
        form-err     @(subscribe [:editor/form-error])
        submit-error @(subscribe [:editor/submit-error])
        can-submit?  @(subscribe [:editor/can-submit?])
        busy?        @(rf/subscribe [:rf.machine/has-tag? :ui/article-editor :editor/busy])
        can-delete?  @(rf/subscribe [:rf.machine/has-tag? :ui/article-editor :editor/can-delete])]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when form-err
          [:ul.error-messages [:li form-err]])
        (when submit-error
          [:ul.error-messages [:li submit-error]])
        [:form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (dispatch [:editor/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type        "text"
             :name        "title"
             :placeholder "Article Title"
             :value       (:title draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :title])
             :on-change   #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when title-err
             [:div.error-messages title-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :name        "description"
             :placeholder "What's this article about?"
             :value       (:description draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :description])
             :on-change   #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when desc-err
             [:div.error-messages desc-err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows        8
             :name        "body"
             :placeholder "Write your article (in markdown)"
             :value       (:body draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :body])
             :on-change   #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when body-err
             [:div.error-messages body-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :name        "tags"
             :placeholder "Enter tags"
             :value       (:tagList draft)
             :disabled    busy?
             :on-change   #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           ;; Off while a request is in flight, and off while the
           ;; :editor/can-submit? flow is false (nothing valid or nothing
           ;; changed to save).
           {:type "submit" :disabled (or busy? (not can-submit?))}
           (if can-delete? "Update Article" "Publish Article")]
          (when can-delete?
            [:button.btn.btn-outline-danger
             {:type "button"
              :disabled busy?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))

;; ---- per-render-state subviews ----
;;
;; Every render mode delegates to `editor-form`; its tag queries
;; (`:editor/busy`, `:editor/can-delete`) handle the current in-form differences.
;; The wrappers deliberately preserve one named extension point per mode for
;; page-level scaffolding such as a full-page loader or a dedicated load-error
;; layout, while keeping the root `case` stable.

(reg-view ^{:doc "Lifecycle :idle / :submitting — the form being a form
                   (interactive, or busy mid-save)."}
          editor-editing []
  [editor-form])

(reg-view ^{:doc "Lifecycle :loading — edit mode's opening fetch is in flight.
                   The form, with its inputs greyed out (the :editor/busy
                   tag)."}
          editor-loading []
  [editor-form])

(reg-view ^{:doc "Lifecycle :error — the load failed. The form, with the
                   submit-error banner up top."}
          editor-error []
  [editor-form])

(reg-view ^{:doc "Lifecycle :saved — the blink-state after a successful save.
                   :editor/submit-success navigates away at once, so you'll
                   almost never actually see this one."}
          editor-saved []
  [editor-form])

(reg-view editor-page []
  (let [render-mode @(subscribe [:article-editor/render])]
    (case render-mode
      :error   [editor-error]
      :loading [editor-loading]
      :saved   [editor-saved]
      :editing [editor-editing]
      [editor-editing])))
