(ns realworld-resources.ui-editor
  "The article editor, rendered in NATIVE re-frame.ui (`defview`) — the S3
   ergonomic-proof rendition of `article_editor.cljs`'s view layer.

   This file is ONLY the rendering tier. The page's dataflow — the
   `:realworld/save-article` / `:realworld/delete-article` mutations, the
   `:editor/*` events + subs, and the `:editor/can-submit?` flow — lives in
   `article_editor.cljs` and is UNCHANGED. That is the frozen-adapter boundary
   the S3 stage exercises: the dataflow layer (frames, events, subs, flows) is
   tier-independent, so the very same registrations back both the stock-Reagent
   page and this compiled-view rendition. This view reaches them by keyword,
   requiring nothing but `re-frame.ui`, so it is `.cljc` and renders on the JVM
   structural host (Tier-1) as well as in the browser.

   What the page shows off, now in the new language:

   - **Controlled inputs, the synchronous door.** Every field is a literal
     `:value` co-present with a committed event vector carrying the
     `:rf.ui/value` placeholder — `:on-input [:editor/edit-field :title
     :rf.ui/value]`. The placeholder projects the live DOM value into the
     event at dispatch time and the write drains synchronously inside the DOM
     event, so the caret never jumps. No `(.. % -target -value)` closure, no
     `dispatch` ceremony — the event vector IS the handler.

   - **A committed `ui/event` for the form submit.** The one handler that needs
     the native event (to `preventDefault` the browser's form navigation) is a
     `ui/event`; its body returns the event vector to dispatch. Committed, per-
     site stable, reading the winning commit.

   - **Loading / error / gating as pure reads.** `busy?` and the failure line
     read the `[:rf/mutation {:instance :editor/save}]` sub; the submit button's
     disabled state reads the `:editor/can-submit?` flow output through a plain
     sub. The render is a pure function of subscriptions — it never dispatches
     out of band, and it is not a Form-3 class component.

   The article read is a DATAFLOW concern that the ROUTE owns, exactly as every
   other page here does. `:realworld.editor/edit` declares `:realworld/article`
   as a `:resources` entry (routing.cljs), so the runtime marks it active under
   the route owner `[:route :realworld.editor/edit nav-token]` on entry and
   RELEASES that owner on every route leave — edit→home, edit→profile→settings,
   save→the saved article, edit→new, and edit A→B all release through the one
   framework lifecycle, with no view teardown involved. Seeding the edit draft's
   baseline stays a causal event: `:editor/load-article` (the route's `:on-match`)
   fires an OWNERLESS `:reply-to [:editor/article-loaded slug]` ensure that JOINS
   the route's own load purely to be told when it settles — it mints no owner of
   its own, and it carries the slug so a late reply for an article the editor has
   navigated away from is dropped rather than reseeding a stale draft. The Reagent page released on `:component-will-unmount`; native re-frame.ui
   has no dispatch-at-unmount (a committed dispatcher rejects firing from a
   disconnected view — the leaked-listener law), so this owner CANNOT ride the
   view. It doesn't need to: the read is app-minted by a ROUTE, and a route is a
   causal owner whose end event is route leave (MIG-17 — routes/events/machines
   are the causal owners; this route-minted read's lifetime follows the route, not
   the view site). So the native view owns no owner — there is nothing at the view
   tier to leak — and the resource lifecycle lives in the route's `:resources`,
   released on every exit."
  (:require [re-frame.ui :as ui :refer [defview sub]]))

;; The one stable instance id the editor form watches for the save write — the
;; same value `article_editor.cljs` declares (`save-instance`). Kept as a local
;; literal so this view requires nothing from the `.cljs` dataflow tier.
(def ^:private save-instance :editor/save)

(defview editor-page
  "Create / edit / delete an article — a pure render of the editor subs."
  []
  (let [draft       (sub [:editor/draft])
        slug        (sub [:editor/slug])
        can-submit? (sub [:editor/can-submit?])
        title-err   (sub [:editor/field-error :title])
        desc-err    (sub [:editor/field-error :description])
        body-err    (sub [:editor/field-error :body])
        save        (sub [:rf/mutation {:instance save-instance}])
        editing?    (some? slug)
        busy?       (:pending? save)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when (:error? save)
          [:ul.error-messages {:data-testid "editor-error"}
           [:li "That didn't save. Check your connection and try again."]])
        ;; The submit is the one handler that needs the live native event (to
        ;; stop the browser navigating the form); a `ui/event` runs the body and
        ;; returns the vector to dispatch.
        [:form
         {:data-testid "editor-form"
          :on-submit (ui/event [e] (.preventDefault ^js e) [:editor/submit])}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text" :name "title" :placeholder "Article Title"
             :data-testid "editor-title"
             :value (:title draft) :disabled busy?
             :on-blur [:editor/blur-field :title]
             :on-input [:editor/edit-field :title :rf.ui/value]}]
           (when title-err [:div.error-messages title-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "description"
             :placeholder "What's this article about?"
             :data-testid "editor-description"
             :value (:description draft) :disabled busy?
             :on-blur [:editor/blur-field :description]
             :on-input [:editor/edit-field :description :rf.ui/value]}]
           (when desc-err [:div.error-messages desc-err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows 8 :name "body" :placeholder "Write your article (in markdown)"
             :data-testid "editor-body"
             :value (:body draft) :disabled busy?
             :on-blur [:editor/blur-field :body]
             :on-input [:editor/edit-field :body :rf.ui/value]}]
           (when body-err [:div.error-messages body-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "tags"
             :placeholder "Enter tags (comma-separated)"
             :data-testid "editor-tags"
             :value (:tagList draft) :disabled busy?
             :on-input [:editor/edit-field :tagList :rf.ui/value]}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           {:type "submit" :data-testid "editor-submit"
            ;; The materialised `:editor/can-submit?` flow output (valid AND
            ;; dirty) drives this directly; also disabled while the write is in
            ;; flight.
            :disabled (or busy? (not can-submit?))}
           (if editing? "Update Article" "Publish Article")]
          (when editing?
            [:button.btn.btn-outline-danger.pull-xs-left
             {:type "button" :data-testid "editor-delete" :disabled busy?
              :on-click [:editor/delete]}
             "Delete Article"])]]]]]]))
