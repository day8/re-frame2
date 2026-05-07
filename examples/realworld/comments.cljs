(ns example.realworld.comments
  "Comments on an article — list, post, delete.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - :comments slice with the Pattern-RemoteData lifecycle shape
     (:status / :data / :error / :loaded-at / :attempt). The :data is a
     vector of Comment maps.
   - :comments/load event (called from :route/article's :on-match).
   - :comments/loaded, :comments/load-failed.
   - :comment-form slice with the Pattern-Forms shape; one field, :body.
   - :comment-form/submit event — POST /articles/:slug/comments,
     optimistically appends to :comments :data.
   - :comment/delete event — DELETE /articles/:slug/comments/:id,
     optimistically removes; rollback on error.
   - article-page view: combines the article body, the comment form, and
     the comment list.
   - Headless tests: load + render; post + optimistic append; delete +
     optimistic remove with rollback on failure.

   Pattern references:
   - docs/specification/Pattern-RemoteData.md  — slice + events
   - docs/specification/Pattern-Forms.md       — comment form

   API endpoints (from the RealWorld spec):
   - GET    /articles/:slug/comments        — list
   - POST   /articles/:slug/comments        — create  (body: {:comment {:body \"...\"}})
   - DELETE /articles/:slug/comments/:id    — delete")

(def ^:private stub :stub)
