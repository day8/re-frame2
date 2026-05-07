(ns example.realworld.article-editor
  "Article editor — create or edit an article.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - :editor slice with the Pattern-Forms shape (:draft, :status, :errors,
     :touched, :submit-error). Draft fields: title, description, body,
     tagList (a delimited string in the input, parsed to a vector on submit).
   - :editor/initialise event — seeds the slice for a new article.
   - :editor/load-article event — for the /editor/:slug route; fetches the
     existing article (POST/GET /articles/:slug), seeds :draft from it.
   - :editor/edit-field, :editor/blur-field events.
   - :editor/submit event — validates draft via the Article schema, dispatches
     POST /articles (new) or PUT /articles/:slug (edit).
   - :editor/submit-success event — navigates to the new article's
     /article/:slug.
   - :editor/submit-error event.
   - :editor/delete event — DELETE /articles/:slug, navigate home on success.
   - :editor/can-leave? sub — returns false when :editor.dirty? is true.
     Wired to the routes via :can-leave; the pending-nav protocol shows
     the confirm dialog (per Spec 012 §Navigation blocking).
   - editor-page view rendering the form.
   - Headless tests covering: new-article happy path; load-then-edit
     happy path; navigation blocked on dirty; navigation continues on
     :route/continue.

   Pattern references:
   - docs/specification/Pattern-Forms.md   — form slice & seven events
   - docs/specification/012-Routing.md     — navigation blocking
   - docs/specification/Pattern-RemoteData.md — for the load step

   API endpoints (from the RealWorld spec):
   - GET    /articles/:slug      — fetch one
   - POST   /articles            — create   (body: {:article {...}})
   - PUT    /articles/:slug      — update
   - DELETE /articles/:slug      — delete")

;; Marker for tooling: this namespace is intentionally otherwise empty.
(def ^:private stub :stub)
