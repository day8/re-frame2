(ns re-frame.hicasso.examples.navigation.views
  "THE NAVIGATION WITNESS'S VIEWS — two panes, on the public door
  (rf2-hic-042).

  Everything reached for is `h/…`: `defview`, `sub`, `route-link`, the
  `::h/value` marker. No `impl` namespace and no `re-frame.core`; a view
  neither dispatches nor subscribes directly, because an intent is a
  vector and a read is `h/sub`.

  ## The two attributes that make the conduct measurable

  `:data-route-heading` marks the ONE element per pane a completed
  navigation focuses, and `:tab-index -1` is what makes focusing it
  possible: a heading is not natively focusable, so without the tabindex
  `.focus()` leaves `<body>` holding focus and nothing on screen says so.
  `-1` rather than `0` on purpose — the heading is a **programmatic**
  focus target, not a stop on the Tab order, and adding a Tab stop nobody
  asked for would be a regression to every keyboard user in exchange for
  the same recipe.

  ## Why the shell is four thousand pixels tall

  Because scroll restoration is not observable on a page that cannot
  scroll. `window.scrollTo` on a short document is a no-op and
  `window.scrollY` reads back 0 — which is also what a correct scroll to
  top reads — so a scroll witness written against a short page is green
  whether the mechanism works or not. The height is on the SHELL rather
  than on a pane, so both routes are equally tall and a restored offset
  cannot be silently clamped away by arriving at a shorter page."
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.navigation.events :as events]
            [re-frame.hicasso.examples.navigation.routes :as routes]
            [re-frame.hicasso.examples.navigation.subs :as subs]))

;; ---------------------------------------------------------------------------
;; The feed
;; ---------------------------------------------------------------------------

(h/defview feed-page
  "The list. Keyed by slug, because a keyed list whose key is its index
  reuses the wrong row the moment the order changes."
  [_]
  (let [rows (h/sub [::subs/feed])]
    [:section.feed
     [:h2.pane-heading {:tab-index -1 :data-route-heading "true"} "Articles"]
     [:ul.article-list
      (for [{:keys [slug title]} rows]
        [:li.article-row {:key slug}
         ;; CALLED, not written as a head — `h/route-link` is a plain
         ;; function, because a link is not a unit of re-render. The href
         ;; and the click decision come whole from routing; this body
         ;; never sees a URL.
         (h/route-link {:to routes/article :params {:slug slug} :class "article-link"}
                       title)])]]))

;; ---------------------------------------------------------------------------
;; The article
;; ---------------------------------------------------------------------------

(h/defview article-page
  "One article and its title editor. The slug comes from the URL through
  routing's own subscription, so the address bar and the page cannot
  disagree."
  [_]
  (let [slug    (:slug (h/sub [:rf.route/params]))
        article (h/sub [::subs/article slug])]
    [:section.article
     [:h2.pane-heading {:tab-index -1 :data-route-heading "true"}
      (if article (:title article) "No such article")]
     (h/route-link {:to routes/feed :class "back"} "Back to the list")
     (when article
       [:form.editor {:on-submit [::events/save {:slug slug}]}
        [:label {:for "navigation-title"} "Title"]
        [:input#navigation-title.field-title
         {:type     "text"
          :value    (h/sub [::subs/title slug])
          :on-input [::events/edit slug ::h/value]}]
        [:button.save {:type "submit" :disabled (not (h/sub [::subs/dirty?]))}
         "Save"]])]))

;; ---------------------------------------------------------------------------
;; The blocked-navigation region — the dirty-leave WIRING POINT
;; ---------------------------------------------------------------------------

(h/defview leave-prompt
  "What a refused leave looks like on screen.

  A `:can-leave` guard that answers false does not cancel the
  navigation — it PARKS it. Routing writes one resumable pending value
  and dispatches `:rf.route/navigation-blocked`, and the application
  decides what to do with it. This region is that decision at its
  smallest: name the block, offer to resume it, and read the pending id
  back out of routing's own subscription rather than keeping a second
  copy of it.

  The prompt itself — its wording, its dismissal, whether it is a dialog
  — is rf2-hic-054's recipe. What lands here is the wiring point: where
  the pending value is read, and which event resumes it."
  [_]
  (when-let [pending (h/sub [:rf/pending-navigation])]
    [:div.leave-prompt {:role "alert"}
     "You have unsaved changes."
     [:button.leave-anyway
      {:type "button" :on-click [:rf.route/continue (:id pending)]}
      "Leave anyway"]]))

;; ---------------------------------------------------------------------------
;; The shell
;; ---------------------------------------------------------------------------

(h/defview app
  "The whole application: whichever pane the route selects, plus the
  blocked-leave region. See the namespace docstring on the height."
  [_]
  (let [route (h/sub [:rf.route/id])]
    [:main.navigation {:style {:min-height "4000px"}}
     [leave-prompt {}]
     (if (= route routes/article)
       [article-page {}]
       ;; Before the first navigation resolves there is no route at all,
       ;; and a page that rendered nothing there would be a blank screen
       ;; with no explanation. The list is the honest default.
       [feed-page {}])]))
