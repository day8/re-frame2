(ns re-frame.recipes.async-nav
  "THREE ASYNC RECIPES, AS ONE SMALL APPLICATION (rf2-hic-054).

  An article list and an article editor, written on the shipped public
  doors and nothing else. It adds no namespace to any artefact and
  changes no runtime: every line here is what a consumer would write.

  ## The three recipes, and the door each is written on

  1. **Settle-merge and reply correlation** — [[article-arrived]].
     A load that seeds a form must survive the user typing while it is
     in flight. The reply names WHICH article it answers, the handler
     drops one addressed at an article the editor no longer holds, and
     the reply it does accept is merged FIELD BY FIELD against
     `[:editor :touched]`. Per-field, not per-slice: the corpus defect
     this recipe exists for is a whole-map `assoc` of the accepted
     payload, which is correct until somebody types.

  2. **Per-instance mutation status with optimistic rollback** —
     [[toggle-favourite]] and the `::favourite` mutation. The write
     carries an `:optimistic` plan, so the star flips before the
     request is sent and the runtime rolls the cache back if the write
     is rejected. Its status is read per INSTANCE — `[:rf/mutation
     {:instance [::favourite slug]}]` — so two rows saving at once
     cannot read each other's pending, error or optimistic flags.

  3. **The dirty-navigation guard** — [[can-leave?]] and the `:can-leave`
     slot on [[editor-route]]. One boolean sub, read positively, over
     the SAME draft-versus-baseline comparison the editor's own dirty
     badge reads. A blocked attempt parks in `:rf/pending-navigation`
     and the confirm UI is ordinary state-driven view code.

  ## Why the three sit in one application

  They are not three independent tricks; recipe 3's guard reads the
  state recipe 1's merge produces. A settle-merge that clobbered a
  touched field would leave the draft equal to the baseline, so the
  guard would let the user walk away from work that had already been
  silently discarded — the two defects composing into a third that
  neither witness alone would catch. Written apart, that composition
  has no home.

  ## What is deliberately NOT here

  **The runtime's own stale fence is not re-witnessed.** A reply
  superseded within one mutation instance never reaches `:reply-to` at
  all, and that is the runtime's, proved in
  `re-frame.hicasso.examples.forms.l0-cljs-test` and in the typeahead's
  `a-late-reply-cannot-clobber-a-newer-term`. What [[article-arrived]]
  guards is the half the runtime explicitly does not: CROSS-ENTRY
  lateness, a reply for an article the editor has since navigated away
  from.

  **The optimistic apply/settle mechanism is not re-witnessed either.**
  `re-frame.resources`'s own `resources-optimistic-apply-cljs-test` /
  `-settle-` / `-validation-` suites own the commit / rollback /
  reconcile contract and its `:on-conflict` enum. This application
  shows the CONSUMER composition — status read per instance, two rows
  in flight at once, the rollback visible in the same read the view
  already had — and asserts nothing about the mechanism the artefact
  suites already pin.

  ## Standing answer, not a stop-gap

  `rf2-hic-050` returned STOP on committed-read resource demand
  (`docs/design/hicasso/product/resource-demand-verdict.md`), so these
  recipes are the standing answer for acquiring and releasing resources
  against read liveness rather than the residual one. Their future
  shape is therefore an ordinary evolution of these doors, not a
  holding pattern awaiting a demand mechanism: a reopen needs new
  evidence of a kind the `rf2-hic-044` witness could not supply."
  (:require [re-frame.core :as rf]
            ;; The managed-HTTP fx surface (Spec 014). Required for its
            ;; registrations: recipe 1's load lowers through it.
            [re-frame.http.managed]
            ;; Side-effecting: registers the `:rf.mutation/*` events, the
            ;; passive `:rf/mutation` / `:rf/resource` subscriptions, and
            ;; the resource cache this application's recipe 2 reads.
            [re-frame.resources]
            [re-frame.routing :as routing])
  ;; The `reg-view` MACRO rather than `reg-view*`, and the reason is the
  ;; whole of recipe 3's confirm UI. An `:on-*` handler runs LATER — on the
  ;; user's click, on a fresh JS stack, after the render that built it has
  ;; committed — by which time the frame scope has unwound and the
  ;; `frame-provider`'s React context has been popped. A fully-qualified
  ;; `rf/dispatch` from a click resolves NO frame and raises
  ;; `:rf.error/no-frame-context`; nothing lands. The macro injects a
  ;; `dispatch` / `subscribe` pair captured at RENDER time, which is what
  ;; survives that boundary — and it is the spelling
  ;; `docs/routing/how-to/guard-unsaved-changes.md` already teaches.
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The routes
;; ---------------------------------------------------------------------------
;;
;; `/rf2-async-nav` is this witness's own leading segment (TESTING.md §Test
;; authoring policy) — no other namespace in the repository claims it. Routes
;; are registered from a FUNCTION rather than at namespace load for the two
;; reasons `re-frame.routing-conduct-dom-cljs-test` gives: a reset fixture
;; rolls a load-time registration back before the first row runs, and route
;; PATHS are process-global in the shared node bundle.

(def list-route ::list)
(def editor-route ::editor)

(def list-url "/rf2-async-nav/articles")

(defn editor-url [slug] (str "/rf2-async-nav/articles/" slug "/edit"))

(def root-id
  "The element this application mounts into, and the scope its focus
  recipe would be confined to. Named here so a browser row and the
  application agree on one string."
  "rf2-async-nav-root")

;; ---------------------------------------------------------------------------
;; The model
;; ---------------------------------------------------------------------------

(def editable-fields
  "The editor's fields, as a set. The settle-merge walks the ACCEPTED
  PAYLOAD rather than this set, so a server that grows a field seeds it
  without an edit here; the set exists so the merge can be asserted
  against a closed roster instead of against whatever a fixture
  happened to send."
  #{:title :body})

(defn blank-editor
  "The editor slice for `slug` before anything has arrived.

  `:touched` is a SET of field keys, and it is the whole of recipe 1.
  It is written by [[edit]] — the user typing — and read by
  [[settle-merge]]. Nothing else writes it, and in particular an
  arriving payload never clears it: the fact that the user has touched
  a field outlives the load that was in flight when they did."
  [slug]
  {:slug     slug
   :draft    {}
   :baseline {}
   :touched  #{}})

(defn settle-merge
  "Merge an accepted `payload` into `draft`, leaf by leaf, skipping every
  field in `touched`. THE recipe.

  The one-line version of the defect this replaces is
  `(assoc db :editor (editor-slice payload))` — a whole-slice write of
  the accepted reply. It is correct on every load that beats the user
  to the keyboard, which is most of them in development and rather
  fewer of them on a slow connection, and when it is wrong it discards
  keystrokes with nothing on screen to say so.

  Field-wise rather than `merge`-wise for the same reason: `(merge
  draft payload)` lets the payload win every key it carries, which is
  precisely the fields the user has been typing into.

  PURE — a function of three values, so the rule can be read and tested
  without a runtime anywhere near it."
  [draft payload touched]
  (reduce-kv (fn [acc field value]
               (if (contains? touched field)
                 acc
                 (assoc acc field value)))
             draft
             payload))

(defn dirty?
  "Is there unsaved work in `editor`? The ONE definition — the guard sub,
  the badge and the save button all call this rather than each
  recomputing the comparison, so they cannot drift (the R-A6 failure,
  in its navigation form)."
  [editor]
  (not= (:draft editor) (:baseline editor)))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed
  {:doc "Install the starting app-db — the frame's `:initial-events` step."}
  (fn [_ _] {:db {:editor (blank-editor nil)}}))

(rf/reg-event ::open-editor
  {:doc "Open the editor on `slug` and ask the server for the article.

         The slice is reset FIRST, so an editor opened on a second
         article never shows the first one's draft for the frame before
         the reply lands, and so [[article-arrived]] has a current slug
         to correlate against from the moment the request goes out.

         The reply target carries the slug. That is recipe 1's first
         half and it costs one element in a vector: a reply that cannot
         say which request it answers is a reply the receiver cannot
         refuse (R-C2)."}
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc db :editor (blank-editor slug))
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            ;; `:request-id` is per-slug, so re-opening the SAME article
            ;; supersedes its own earlier request inside the runtime and
            ;; the older reply never arrives here at all. Cross-slug
            ;; lateness is what the handler below still has to guard.
            :request-id [::article slug]
            :on-success [::article-arrived slug]
            :on-failure [::article-failed slug]}]]}))

(rf/reg-event ::article-arrived
  {:doc "An article load settled. RECIPE 1, in full.

         Two gates, in order, and they answer different questions.

         CORRELATION — is this reply about the article the editor is
         holding? The runtime suppresses a reply superseded within one
         `:request-id`; it does not and cannot suppress a reply for a
         DIFFERENT article the user has since navigated away from,
         because that request was never superseded — it was abandoned.
         Comparing the slug the reply carries against the slug the
         slice holds is the receiver's half, and it is one `not=`.

         MERGE — of the fields this reply carries, seed only the ones
         the user has not touched. The baseline takes the payload
         WHOLE regardless, because the baseline is what the server
         said, and a dirty comparison against a half-updated baseline
         would report clean work as dirty forever."}
  (fn [{:keys [db]} [_ slug reply]]
    (let [editor (:editor db)]
      (cond
        (not= slug (:slug editor))
        {}                                  ;; abandoned — the editor moved on

        (not= :ok (:status reply))
        {}                                  ;; addressed by `:on-failure`

        :else
        (let [payload (select-keys (:value reply) editable-fields)]
          {:db (assoc db :editor
                      (-> editor
                          (assoc :baseline payload)
                          (update :draft settle-merge payload (:touched editor))))})))))

(rf/reg-event ::article-failed
  {:doc "The load failed. The editor keeps whatever the user has typed —
         losing a draft to a failed GET would be the clobber defect
         wearing a different hat — and records the failure for the view."}
  (fn [{:keys [db]} [_ slug _reply]]
    (if (= slug (get-in db [:editor :slug]))
      {:db (assoc-in db [:editor :load-failed?] true)}
      {})))

(rf/reg-event ::edit
  {:doc "One keystroke. Writes the field AND marks it touched, in one
         `:db`, because a touch mark landing in a different turn from
         the value it protects is a window in which a settle can still
         clobber."}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in [:editor :draft field] value)
             (update-in [:editor :touched] conj field))}))

(rf/reg-event ::save
  {:doc "Accept the draft locally — the baseline moves to the draft, which
         is what makes the editor clean and releases the guard.

         There is no `:saving?` key and no network here on purpose: the
         guard recipe is about what LEAVING does, and the write's own
         status is recipe 2's subject on a door that already owns it."}
  (fn [{:keys [db]} _]
    {:db (update db :editor #(assoc % :baseline (:draft %)))}))

(rf/reg-event ::save-and-close
  {:doc "Save, then leave without the prompt.

         Saving would make the guard pass anyway; `:bypass-leave? true`
         states the intent, and it is the only bypass there is. It skips
         THIS route's `:can-leave` for THIS navigation — the target's
         `:can-enter` still runs, because an \"enter anyway\" flag would
         be a hole through the auth gate."}
  (fn [{:keys [db]} _]
    {:db (update db :editor #(assoc % :baseline (:draft %)))
     :fx [[:dispatch [:rf.route/navigate {:to            list-route
                                          :bypass-leave? true}]]]}))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the favourite toggle, as a mutation with an optimistic plan
;; ---------------------------------------------------------------------------

(def articles-resource ::articles)
(def favourite-mutation ::favourite)

(defn favourite-instance
  "The mutation INSTANCE id for one row's write.

  Per SLUG, which is the whole of R-C5. A shared instance would make
  two rows in flight at once read each other's `:pending?`, `:error?`
  and `:optimistic?` — every row spinning because any row is, and one
  row's rejection painting an error on its neighbour."
  [slug]
  [::favourite slug])

(defn register-resources!
  "Register the article-list resource and the favourite mutation, and
  answer the mutation id.

  A FUNCTION called from each suite's fixture rather than a namespace-load
  effect. A reset fixture restores the registrar to a baseline it
  captured and the resources artefact clears the mutation kind outright,
  so a load-time registration is not guaranteed to still be there when a
  row runs — and `[:rf.mutation/execute {:mutation <unregistered> …}]`
  mints no instance, issues no request and reports NOTHING (rf2-06lp).
  A witness standing on that luck reads `:idle` and calls it a pass."
  []
  (rf/reg-resource articles-resource
    {:params-schema [:map]
     :scope         :rf.scope/global
     :tags          (fn [_params _data] #{[:article-list]})}
    (fn [_params _ctx]
      {:request {:url "/api/articles"}}))

  (rf/reg-mutation favourite-mutation
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string] [:favourite? :boolean]]
     ;; THE OPTIMISTIC PLAN. It patches the cached list BEFORE the request
     ;; is sent, and the runtime commits, rolls back or reconciles it
     ;; deterministically when the reply settles. `:on-conflict` is left
     ;; at its `:invalidate` default: if a concurrent write landed on the
     ;; entry in between, a blind restore would clobber newer truth, so
     ;; the entry is marked stale and the read path fetches the answer.
     ;;
     ;; The target is a MAP — `{:resource :params :scope}` — and not the
     ;; `[id params]` vector a reader of `[:rf/resource …]` might reach
     ;; for. Optimistic targets run BEFORE the request lowers, so a target
     ;; that could write the cache under a wrong identity is rejected
     ;; outright rather than dropped-and-warned; the vector spelling
     ;; therefore takes the write with it, and the instance reads `:idle`
     ;; afterwards. Measured here, on this file's first node-lane run.
     :optimistic    (fn [{:keys [slug favourite?]}]
                      {{:resource articles-resource
                        :params   {}
                        :scope    :rf.scope/global}
                       (fn [articles]
                         (mapv (fn [a]
                                 (cond-> a
                                   (= slug (:slug a)) (assoc :favourite? favourite?)))
                               articles))})}
    (fn [{:keys [slug favourite?]} _ctx]
      {:request {:method :put
                 :url    (str "/api/articles/" slug "/favourite")
                 :body   {:favourite? favourite?}}}))
  favourite-mutation)

(rf/reg-event ::toggle-favourite
  {:doc "Star or unstar one article. RECIPE 2.

         The write runs under the row's OWN instance and carries no
         completion callback: the reply arrives at a named event because
         `:reply-to` said so. The row's spinner, its error slot and its
         \"showing your change already\" affordance are all projections
         of `[:rf/mutation {:instance …}]`, so there is no boolean in
         `app-db` for the failure branch to forget to clear."}
  (fn [_ [_ slug favourite?]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation favourite-mutation
                       :params   {:slug slug :favourite? favourite?}
                       :instance (favourite-instance slug)
                       :reply-to [::favourite-settled slug]}]]]}))

(rf/reg-event ::favourite-settled
  {:doc "The favourite write settled. Records nothing about success or
         failure — the instance already carries both, and a second copy
         is a second thing to drift. It exists so the reply is
         ADDRESSED: an unaddressed managed reply is silenced, and a
         recipe that silenced its own completion would be teaching a
         write nobody can observe finishing."}
  (fn [{:keys [db]} [_ slug reply]]
    {:db (assoc-in db [:last-settled slug] (:status reply))}))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub ::editor (fn [db _] (:editor db)))

(rf/reg-sub ::draft-field
  (fn [db [_ field]] (get-in db [:editor :draft field] "")))

(rf/reg-sub ::dirty?
  (fn [db _] (dirty? (:editor db))))

(rf/reg-sub ::can-leave?
  {:doc "RECIPE 3, and the whole of it.

         Read POSITIVELY: `true` means leaving is fine. A guard that
         answered the dirty flag directly is the classic polarity bug —
         it reads as though it works and blocks exactly when it should
         allow.

         STRICTLY boolean. `true` allows and `false` blocks; anything
         else fails CLOSED and raises `:rf.error/can-leave-non-boolean`,
         so a sub returning `nil` for \"no editor open\" would deny every
         navigation in the application. `not` guarantees the boolean
         here, which is why it is written rather than `if`."}
  (fn [db _] (not (dirty? (:editor db)))))

;; ---------------------------------------------------------------------------
;; Route registration
;; ---------------------------------------------------------------------------

(defn register-routes!
  "Register the two routes. See the routes comment above for why this is
  a function."
  []
  (routing/reg-route list-route
    {:doc      "The article list."
     :on-match [[::pane-shown]]}
    list-url)
  (routing/reg-route editor-route
    {:doc       "The editor for one article."
     :params    [:map [:slug :string]]
     ;; THE WIRING POINT. One key on the route, naming the sub above.
     :can-leave [::can-leave?]
     :on-match  [[::pane-shown]]}
    (editor-url ":slug"))
  nil)

(rf/reg-event ::pane-shown
  {:doc "The routes' `:on-match`. This application's navigation conduct —
         focus-on-route and scroll restoration — is
         `re-frame.routing-conduct-dom-cljs-test`'s subject and is not
         rebuilt here; the handler exists so the routes carry a real
         `:on-match` drain rather than an empty one."}
  (fn [{:keys [db]} _]
    {:db (update db :panes-shown (fnil inc 0))}))

;; ---------------------------------------------------------------------------
;; The view
;; ---------------------------------------------------------------------------

(def dirty-badge-selector "[data-dirty-badge]")
(def prompt-selector "[data-leave-prompt]")
(def stay-selector "[data-leave-stay]")
(def leave-selector "[data-leave-anyway]")

;; The confirm UI, as ordinary state-driven view code. No `window.confirm`
;; and no `beforeunload`: a blocked attempt is a VALUE in
;; `:rf/pending-navigation`, so this renders nothing at all until there is
;; something pending. Both buttons dispatch the pending's own `:id` — the
;; runtime keys the slot by it, and a stale id is a safe no-op, which is
;; what makes a double-click harmless.
;;
;; `dispatch` and `subscribe` here are the macro's render-time injections;
;; see the `:require-macros` note above for why a qualified `rf/dispatch`
;; would raise from these handlers rather than navigate.
(reg-view leave-prompt []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div {:data-leave-prompt true}
     [:p "You have unsaved changes. Leave anyway?"]
     [:button {:data-leave-stay true
               :on-click #(dispatch [:rf.route/cancel (:id pending)])}
      "Stay"]
     [:button {:data-leave-anyway true
               :on-click #(dispatch [:rf.route/continue (:id pending)])}
      "Discard and leave"]]))

(reg-view app []
  (let [route @(subscribe [:rf.route/id])]
    [:main
     (condp = route
       list-route
       [:h1 {:data-route-heading true :tab-index -1} "Articles"]

       editor-route
       [:div
        [:h1 {:data-route-heading true :tab-index -1} "Editing"]
        [:input {:data-editor-title true
                 :value @(subscribe [::draft-field :title])
                 :on-change #(dispatch [::edit :title (.. % -target -value)])}]
        (when @(subscribe [::dirty?])
          [:span {:data-dirty-badge true} "unsaved"])]

       [:p.no-route "no route"])
     [leave-prompt]
     ;; Tall enough that the page genuinely scrolls, so a row measuring an
     ;; offset is measuring something. A document shorter than the viewport
     ;; answers 0 however far you ask it to scroll.
     [:div {:style {:height "4000px"}}]]))
