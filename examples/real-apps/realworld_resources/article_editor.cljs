(ns realworld-resources.article-editor
  "Create / edit / delete an article — the RealWorld-on-resources editor.

   This is the busiest page in the app, and a good one to study because it puts
   three distinct re-frame2 ideas to work side by side:

   1. The write is a mutation. `:realworld/save-article` POSTs `/articles` to
      create or PUTs `/articles/:slug` to edit, and declares per-target scoped
      `:invalidates` descriptors — the viewer-relative article tags
      (`[:article slug]` / `[:article-list]`) in `{:from-db :realworld/viewer}`,
      and the session `[:feed]` in `{:from-db :realworld/session}`. So on success
      the detail read, every list showing the article, and the session feed all
      refetch with no further wiring — one mutation reaching across both scopes.
      `:realworld/delete-article` invalidates the same tags. The write lifecycle
      is the mutation INSTANCE, watched through `[:rf/mutation {:instance …}]`;
      there's no `:status` field in app-db.

   2. The can-submit gate is a flow. `:editor/can-submit?` materialises one
      derived fact — 'the draft is valid AND differs from the loaded baseline' —
      into app-db at `[:editor :can-submit?]`. The submit handler then reads it as
      plain data (no subscribing mid-handler), and the submit button reads the same
      value through a plain sub over the flow's `:output-path`. The flow is
      registered ONCE at boot from `:editor/register-flow` (dispatched by
      `:app/initialise`) via `:rf.fx/reg-flow`, so it binds to the boot frame and
      isn't re-registered on every editor entry. See the flow glossary:
      ../../../docs/core/glossary.md#flow.

   3. A navigation `:can-leave` guard. The editor route declares
      `:can-leave [:editor/can-leave?]`; a dirty draft blocks a navigate-away and
      the app shell pops a confirm dialog off the `:rf/pending-navigation` sub (see
      core.cljs). Clean (or just-saved) drafts leave freely. See route guard:
      ../../../docs/routing/glossary.md#route-guard.

   Both continuations this page needs are call-site `:reply-to` targets — the
   very idiom settings.cljs uses — so the view has no off-render reactions at all.
   The save / delete success continuation (navigate to the saved article, or home
   on a delete) is the mutation's `:reply-to [:editor/replied]`; the runtime
   dispatches `[:editor/replied reply]` once, AFTER `:invalidates` staled the lists
   and feed and the instance settled, and the continuation branches save-vs-delete
   on the reply value (save and delete share one instance, so they share one
   continuation). The seed-on-load continuation is the `:realworld/article`
   ensure's `:reply-to [:editor/article-loaded slug]`, the resource-read
   counterpart of a mutation completion continuation: a cache-hit fires it
   immediately, a fetch fires it on settle. The reply carries the slug it was for,
   because per-slug reads are distinct cache entries with independent generations
   — the resource gate suppresses a reply superseded within one entry, but not a
   late reply for a slug you've navigated away from — so the continuation seeds
   only while the editor still targets that slug. Both are declarative, replayable
   causal events, not Form-3 `reagent.ratom/run!` reactions watching a settle. The
   render bodies, as everywhere here, are pure functions of subs that never
   dispatch out of band."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            ;; The flows runtime. Loading it publishes the hooks so the
            ;; `:rf.fx/reg-flow` effect has something to resolve against; without
            ;; it, the effect raises.
            [re-frame.flows]
            [realworld-resources.http :as rh]
            [realworld-shared.schema :as schema])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; DRAFT SHAPE + VALIDATION
;; ============================================================================

(def blank-draft
  {:title "" :description "" :body "" :tagList ""})

(defn draft-from-article [article]
  {:title       (:title article)
   :description (:description article)
   :body        (:body article)
   :tagList     (str/join ", " (:tagList article))})

(defn- parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- editor-slice
  "The form's app-db slice: the draft, the baseline (for dirty-detection), and the
   per-field validation bookkeeping. The submission lifecycle is deliberately not
   here — that's the `:editor/save` mutation instance."
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug              slug
    :draft             baseline
    :baseline          baseline
    :errors            {}
    :touched           #{}
    :submit-attempted? false}))

(defn- seed-slice
  "Fold a settled article read into the editor slice WITHOUT clobbering typing.

   The obvious spelling — replace the whole slice with a fresh
   `(editor-slice slug (draft-from-article article))` — loses every keystroke the
   user made while the read was still in flight, because entering the editor and
   typing is faster than a round trip. `FH-CTRL-013` states that seed law for
   `re-frame.freehand` forms; this app holds its own `:touched` set rather than a
   freehand form, so it spells the same rule by hand.

   The seed is LEAFWISE. A field the user has already touched keeps BOTH its
   draft and its baseline, so their text stays on screen AND stays dirty — the
   save that follows sends what they typed. Every untouched field takes the
   loaded value in both, so the dirty-check compares against what the server
   actually holds. A seeded field also drops the validation error its old (blank)
   value earned; a touched field keeps the error its current value earns.

   With nothing touched — the ordinary load — every field is seeded and the
   result is exactly the whole-slice replacement it replaces."
  [slice slug loaded]
  (let [touched (or (:touched slice) #{})
        seeded  (remove touched (keys loaded))
        seed-in #(reduce (fn [m k] (assoc m k (get loaded k))) % seeded)]
    (-> slice
        (assoc :slug slug)
        (update :draft seed-in)
        (update :baseline seed-in)
        (update :errors #(apply dissoc % seeded)))))

(defn- validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

(defn- article-body [draft]
  {:article {:title       (:title draft)
             :description (:description draft)
             :body        (:body draft)
             :tagList     (parse-tag-list (:tagList draft))}})

(def save-instance
  "The one stable instance id the editor form watches for the save write."
  :editor/save)

;; ============================================================================
;; THE FLOW — :editor/can-submit?
;; ============================================================================
;;
;; This materialises one derived boolean — 'the draft passes client-side
;; validation AND differs from the loaded baseline' — into app-db at
;; `[:editor :can-submit?]`. Why bother with a flow instead of a sub? Because the
;; `:editor/submit` handler wants to read this value as plain app-db data to gate
;; the submit, and a sub would force the handler to subscribe mid-handler, which is
;; awkward. The submit button, meanwhile, reads the very same materialised value
;; through a plain sub over the `:output-path`. One derived fact, two readers, no
;; duplication. See the flow glossary: ../../../docs/core/glossary.md#flow.
;;
;; It's registered ONCE at boot via `:rf.fx/reg-flow` from `:editor/register-flow`
;; (dispatched by `:app/initialise`, not at ns-load and not per route entry), so it
;; binds to the boot frame and lives as long as it — the same lifetime as the
;; `[:editor …]` slice it derives over. The flow's first walk fires on the next
;; drain; a fresh editor starts invalid and clean anyway, so that one-event lag
;; never carries a stale value.

;; This value uses the canonical `[flow-id metadata derive-fn]` triple shared by
;; `reg-flow` and `:rf.fx/reg-flow`, so `[:rf.fx/reg-flow can-submit-flow]`
;; passes the complete registration through unchanged.
(def can-submit-flow
  [:editor/can-submit?
   {:doc    "True when the editor draft is both valid AND dirty (differs from the
             loaded baseline). Materialised into app-db so the submit handler can
             read it as plain data."
    :inputs [[:editor :draft] [:editor :baseline]]
    :output-path [:editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate-draft draft))
          (not= draft baseline)))])

;; ============================================================================
;; THE WRITE — mutations (POST create / PUT edit / DELETE)
;; ============================================================================
;;
;; Saving an article invalidates the article detail and every list/feed that shows
;; it. The mutation declares those tags once, and the runtime takes care of
;; refetching the mounted readers. Create and edit share a single mutation: the
;; `:request` flips POST `/articles` ↔ PUT `/articles/:slug` on whether a `:slug`
;; is present, and the slug-bearing edit path invalidates its own detail entry too.

(rf/reg-mutation :realworld/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug) an
                   article. On success, invalidates the detail + lists + feed."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   ;; The lists (viewer scope) go stale so they re-read with the new article, and
   ;; an edit's slug also stales its own detail entry. A create has no prior slug,
   ;; so it stales only the lists — the new detail is read fresh on navigate
   ;; anyway. The session feed lives in the session scope, so it gets its own
   ;; per-target descriptor naming `{:from-db :realworld/session}`: one mutation,
   ;; reaching across both scopes. Both derived-scope targets resolve against the
   ;; acting author at settle time.
   ;;
   ;; A third descriptor stales the author's OWN `:realworld/author-articles`
   ;; (My Articles) cache, keyed off the reply's `:article :author :username` —
   ;; the fact the decoded result carries, not `params` (a create has no `:slug`
   ;; to key against, and `[:article-list]` above only reaches the viewer feed's
   ;; list-identity tag, which `:realworld/author-articles` never carries — see
   ;; its `:tags` fn in resources.cljs). Without this, a freshly-created article
   ;; is invisible on My Articles until its cached page naturally goes stale.
   :invalidates   (fn [{:keys [slug]} result]
                    [{:scope {:from-db :realworld/viewer}
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}
                     {:scope {:from-db :realworld/viewer}
                      :tags  #{[:author-articles (get-in result [:article :author :username])]}}])}
  (fn [{:keys [slug] :as draft} _ctx]
    {:request {:method (if slug :put :post)
               :url    (rh/full-url (if slug
                                      (str "/articles/" slug)
                                      "/articles"))
               :body   (article-body (select-keys draft [:title :description :body :tagList]))}
     :decode  schema/ArticleResponse}))

(rf/reg-mutation :realworld/delete-article
  {:doc           "Delete an article. DELETE /articles/:slug. Invalidates the
                   detail + lists + feed."
   :params-schema [:map [:slug :string]]
   ;; Viewer-scoped article tags plus the session feed, each in its own scope
   ;; (same shape as :realworld/save-article above).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope {:from-db :realworld/viewer}
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete
               :url    (rh/full-url (str "/articles/" slug))}
     ;; The delete endpoint returns no body, and `:auto` takes a 204/empty in its
     ;; stride.
     :decode  :auto}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :editor/register-flow
  {:doc "Boot-time one-shot: register the `:editor/can-submit?` flow ONCE against
         the boot frame. Dispatched from `:app/initialise` at boot — NOT per route
         entry. `:rf.fx/reg-flow` replaces by id, so re-registering on every
         `/editor` entry wouldn't stack copies — but it WOULD leave the flow
         registered after you leave the editor, quietly recomputing over the
         editor slice for the rest of the session. Registering once at boot (the
         flow lives as long as the frame, like the `[:editor …]` slice it derives
         over) is the honest singleton shape; each route entry then only resets
         the slice. (The http sibling reuses its same-named `:editor/initialise`
         as this boot one-shot; here `:editor/initialise` is the create-route
         `:on-match`, so the boot registration gets its own event.)"}
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/initialise
  {:doc "Create-mode entry (`:realworld.editor/new` `:on-match`). Resets the editor
         slice to a blank draft and clears any leftover save instance. Reaching
         `/editor` from an edit route is a real navigation, so the runtime has
         already released the outgoing edit article owner — the ROUTE owns the
         edit read (`:realworld.editor/edit` `:resources`, routing.cljs) and drops
         it on leave — leaving this event only the slice reset. The
         `:editor/can-submit?` flow is registered ONCE at boot by
         `:editor/register-flow`, not per entry."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]]}))

(rf/reg-event :editor/load-article
  {:doc "Edit-mode entry (`:realworld.editor/edit` `:on-match`). Resets the editor
         slice to a blank draft under the incoming slug, then asks to be told when
         the article read the ROUTE owns settles, so it can seed the draft +
         baseline. The route declares `:realworld/article` as a `:resources` entry
         (routing.cljs), owning it under `[:route :realworld.editor/edit nav-token]`
         and releasing it on every leave; this event fires an OWNERLESS
         `:reply-to [:editor/article-loaded slug]` ensure that JOINS that same read
         — a cache-hit fires the continuation immediately, an in-flight fetch fires
         it on settle — purely to seed. It mints NO owner of its own (nothing here
         to release), and a `/editor/A` -> `/editor/B` re-match is a fresh
         navigation, so the runtime releases the A owner and ensures B on its own —
         this event only re-seeds. The reply target carries the INTENDED slug so the
         continuation can tell WHICH edit it was for: slug A and slug B are distinct
         cache entries with independent generations, so leaving A for B (or new, or
         home) releases A's owner but a best-effort-uncancelled A settle can still
         fire A's continuation late — carrying the slug lets `:editor/article-loaded`
         drop a reply the editor has moved on from. The `:editor/can-submit?` flow
         is registered ONCE at boot by `:editor/register-flow`, not per entry. The
         seeded baseline is what gives the dirty-check something to compare
         against."}
  (fn [{rt :rf.db/runtime :keys [db]} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
            ;; Join the route-owned read to seed the baseline — no owner, so this
            ;; ensure adds no owner and there is nothing to release. The route's
            ;; own `:resources` ownership handles the read's whole lifecycle. The
            ;; slug rides in the reply target so the seed continuation can confirm
            ;; the editor still targets THIS slug before it writes the draft.
            [:dispatch [:rf.resource/ensure
                        {:resource :realworld/article
                         :params   {:slug slug}
                         :cause    [:route-entry :realworld.editor/edit]
                         :reply-to [:editor/article-loaded slug]}]]]})))

(rf/reg-event :editor/article-loaded
  {:doc "The article-read completion continuation (the ensure's
         `:reply-to [:editor/article-loaded slug]` target). It receives the
         intended `slug` and then the canonical reply map as its final args the
         moment the `:realworld/article` read the editor caused settles — a
         cache-hit fires it immediately, a fetch fires it on settle. The resource
         reply gate suppresses a reply superseded WITHIN its own cache entry
         (same key, newer generation), but slug A and slug B are DISTINCT cache
         entries with independent generations: leaving A for B/new/home releases
         A's owner and requests cancellation, yet that cancellation is best-effort,
         so a late A settle can still be accepted for A's own entry and dispatch
         THIS continuation after the editor has moved on. So the seed is
         slug-correlated: it writes the draft only while the current route is still
         `:realworld.editor/edit` targeting the reply's `slug`. That keeps a stale
         A reply from clobbering the draft the editor now shows (an actively-edited
         B, a fresh create draft, or — off the editor entirely — nothing). On `:ok`
         for the still-current slug, seed the editor draft + baseline from the
         loaded article so the dirty-check has a baseline to compare against. That
         seed is LEAFWISE (`seed-slice`): the same-slug race is real too — entering
         edit A and typing before A settles — and a whole-slice replacement would
         discard those keystrokes, so a field the user has already touched keeps
         its own draft and baseline while the rest take the loaded article's. A
         load error surfaces through the read's own state, so there's nothing to do
         here. This is the read counterpart of the mutation `:reply-to` idiom
         settings.cljs uses — a declarative, replayable causal event, not an
         off-render Form-3 reaction watching the read settle."}
  (fn [{rt :rf.db/runtime :keys [db]} [_ slug {:keys [status value]}]]
    ;; Only seed while the editor is STILL on the edit route for THIS slug — the
    ;; framework already tracks the current route, so this reads it (the same
    ;; `:rf.db/runtime` coeffect `:editor/load-article` reads) rather than adding
    ;; any lifecycle machinery.
    (let [current        (get-in rt [:rf.runtime/routing :current])
          still-editing? (and (= :realworld.editor/edit (:route-id current))
                              (= slug (get-in current [:params :slug])))]
      (when (and still-editing? (= :ok status))
        (when-let [article (:article value)]
          {:db (update db :editor seed-slice (:slug article) (draft-from-article article))})))))

;; There is deliberately NO `:editor/release-article` event. The article read is
;; owned by the ROUTE (`:realworld.editor/edit` `:resources`, routing.cljs), so
;; the runtime releases `[:route :realworld.editor/edit nav-token]` on every route
;; leave — there is no app-minted owner for an event to release. This is MIG-17's
;; re-homing doctrine at its cleanest: the causal owner is the route, and route
;; leave is the causal end event.

(rf/reg-event :editor/edit-field
  {:schema [:cat [:= :editor/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field)
        (update-in [:editor :errors] dissoc field))}))

(rf/reg-event :editor/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:editor :touched] (fnil conj #{}) field)}))

(rf/reg-event :editor/submit
  {:doc "Save the article. Reads the `:editor/can-submit?` FLOW output straight off
         app-db — a materialised derived value, consumed as plain data, no
         subscribe ceremony required. Valid-and-dirty fires the save mutation; an
         invalid draft re-runs validation to fill in the per-field error map; and
         valid-but-unchanged is a no-op."}
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft]} (:editor db)
          can-submit? (get-in db [:editor :can-submit?])
          errors      (validate-draft draft)]
      (cond
        ;; Valid but unchanged → nothing to save. The button is already disabled in
        ;; this state; this is just the belt-and-braces no-op for a programmatic
        ;; dispatch.
        (and (not can-submit?) (empty? errors))
        {}

        (seq errors)
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] errors))}

        :else
        {:db (assoc-in db [:editor :submit-attempted?] true)
         :fx [[:dispatch [:rf.mutation/execute
                          {:mutation :realworld/save-article
                           :params   (cond-> (select-keys draft [:title :description :body])
                                       true (assoc :tagList (parse-tag-list (:tagList draft)))
                                       slug (assoc :slug slug))
                           :instance save-instance
                           ;; The save-success continuation is the call-site
                           ;; `:reply-to`, not an off-render reaction. Save and
                           ;; delete share one instance, so they share one
                           ;; continuation that branches on the reply — `:value`
                           ;; carries the saved Article for a save; a delete comes
                           ;; back with no body.
                           :reply-to [:editor/replied]
                           :cause    [:submit :editor/save]}]]]}))))

(rf/reg-event :editor/replied
  {:doc "The save / delete completion continuation (the `:reply-to` target). It
         receives the canonical reply map as its final arg, observed AFTER the
         mutation's `:invalidates` staled the lists and feed and the instance
         settled. On a successful SAVE (`:value` carries the saved `{:article …}`),
         re-seed the editor from the saved article so the draft reads as clean —
         that way the `:can-leave` guard won't block — clear the instance, and
         navigate to the article detail. On a successful DELETE (no `:article` in
         the reply value), clear the slice and instance and head home. Both
         continuations NAVIGATE away from the edit route, so the runtime releases
         the route-owned article read on the way out — neither branch releases an
         owner by hand (there is none; the route owns the read, routing.cljs). On
         `:error` there's nothing to do here; the form already shows it off the
         instance state."}
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (cond
      (not= :ok status) {}

      (:article value)
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
              [:dispatch [:rf.route/navigate {:to :realworld.article/show :params {:slug (:slug article)}}]]]})

      :else
      {:db (assoc db :editor (editor-slice))
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
            ;; Navigating home leaves the edit route, so the runtime releases the
            ;; route-owned `:realworld/article` read; the delete's `[:article slug]`
            ;; invalidation then reaches an unowned entry that GC reclaims — no
            ;; orphaned owner, no refetch of the just-deleted slug.
            [:dispatch [:rf.route/navigate {:to :realworld/home}]]]})))

(rf/reg-event :editor/delete
  {:doc "Delete the article (edit mode only). Fires the delete mutation under the
         same instance the save uses, with the same `:reply-to [:editor/replied]`
         continuation — which branches save-vs-delete on the reply value."}
  (fn [{:keys [db]} _]
    (when-let [slug (get-in db [:editor :slug])]
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation :realworld/delete-article
                         :params   {:slug slug}
                         :instance save-instance
                         :reply-to [:editor/replied]
                         :cause    [:click :editor/delete slug]}]]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor/slice (fn [db _] (:editor db)))
(rf/reg-sub :editor/draft :<- [:editor/slice] (fn [e _] (:draft e)))
(rf/reg-sub :editor/slug  :<- [:editor/slice] (fn [e _] (:slug e)))

(rf/reg-sub :editor/field-error
  {:doc "Per-field validation error — held back until either the first submit
         attempt or the moment the field is touched, whichever comes first."}
  :<- [:editor/slice]
  (fn [e [_ field]]
    (when (or (:submit-attempted? e) (contains? (:touched e) field))
      (get-in e [:errors field]))))

(rf/reg-sub :editor/can-submit?
  {:doc "Reads the `:editor/can-submit?` FLOW output at its app-db `:output-path` —
         a flow's output is just ordinary app-db state, read through a plain sub.
         Drives the submit button. It's nil until the flow's first walk lands,
         which `(boolean …)` tidily normalises to false."}
  (fn [db _]
    (boolean (get-in db [:editor :can-submit?]))))

(rf/reg-sub :editor/dirty?
  :<- [:editor/slice]
  (fn [e _] (not= (:draft e) (:baseline e))))

(rf/reg-sub :editor/can-leave?
  {:doc "The route `:can-leave` guard query. A clean (or just-saved) draft leaves
         freely; a dirty one blocks, and the app shell shows the confirm dialog off
         `:rf/pending-navigation`. See route guard:
         ../../../docs/routing/glossary.md#route-guard."}
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))

;; ============================================================================
;; VIEW  (a pure Form-1 render — no lifecycle, because the view holds no owner)
;; ============================================================================
;;
;; Same shape as settings.cljs. The render is a pure registered `reg-view` that
;; never dispatches out of band. It needs NO Form-3 lifecycle wrapper: the two
;; off-render concerns a lifecycle hook would have carried are both re-homed to
;; the dataflow — the seed-on-load is the route's `:on-match` `:reply-to`
;; continuation (`:editor/article-loaded`), and the article read's teardown is the
;; ROUTE's (`:realworld.editor/edit` `:resources`, routing.cljs), released by the
;; runtime on every route leave. So the view holds no owner and no reaction; it is
;; a pure function of subs. This is exactly what the native `ui_editor.cljc`
;; rendition compiles to, and the shape MIG-17 re-homes a Form-3 editor into.

(reg-view ^{:doc "The article-editor page — a pure function of subs that never
                   dispatches out of band. It owns no article owner: the read is a
                   route `:resource` (routing.cljs), released on every route leave,
                   and the seed-on-load is the route's `:on-match` `:reply-to`
                   [:editor/article-loaded] continuation. The save/delete
                   continuations live in the `:editor/*` events."}
          editor-page []
  (let [draft       @(subscribe [:editor/draft])
        slug        @(subscribe [:editor/slug])
        can-submit? @(subscribe [:editor/can-submit?])
        title-err   @(subscribe [:editor/field-error :title])
        desc-err    @(subscribe [:editor/field-error :description])
        body-err    @(subscribe [:editor/field-error :body])
        save        @(subscribe [:rf/mutation {:instance save-instance}])
        editing?    (some? slug)
        busy?       (:pending? save)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when (:error? save)
          [:ul.error-messages {:data-testid "editor-error"} [:li (rh/failure->message (:error save))]])
        [:form
         {:data-testid "editor-form"
          :on-submit (fn [e] (.preventDefault e) (dispatch [:editor/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text" :name "title" :placeholder "Article Title" :data-testid "editor-title"
             :value (:title draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :title])
             :on-change #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when title-err [:div.error-messages title-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "description" :placeholder "What's this article about?" :data-testid "editor-description"
             :value (:description draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :description])
             :on-change #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when desc-err [:div.error-messages desc-err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows 8 :name "body" :placeholder "Write your article (in markdown)" :data-testid "editor-body"
             :value (:body draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :body])
             :on-change #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when body-err [:div.error-messages body-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)" :data-testid "editor-tags"
             :value (:tagList draft) :disabled busy?
             :on-change #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           {:type "submit" :data-testid "editor-submit"
            ;; Disabled while busy, or while the can-submit? flow is false (draft
            ;; invalid or unchanged) — the flow's materialised output drives this
            ;; directly.
            :disabled (or busy? (not can-submit?))}
           (if editing? "Update Article" "Publish Article")]
          (when editing?
            [:button.btn.btn-outline-danger.pull-xs-left
             {:type "button" :data-testid "editor-delete" :disabled busy?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))
