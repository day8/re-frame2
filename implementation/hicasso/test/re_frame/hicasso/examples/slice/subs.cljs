(ns re-frame.hicasso.examples.slice.subs
  "THE SLICE'S SUBSCRIPTIONS — including its strings and its theme tokens
  (rf2-hic-025).

  Every read a view makes goes through here, and that is the §7 i18n /
  theming claim in one sentence: **a translated string and a theme token
  are subscriptions, so switching either is an ordinary event and the
  re-render that follows is the ordinary re-render.** There is no
  provider, no context, no `IntlProvider`, no theme object threaded down
  the tree and no adapter subsystem — [[t]] and [[token]] are two
  layer-2 subs over one layer-1 read each.

  ## Why `h/reg-state` is here rather than in `events`

  [[re-frame.hicasso/reg-state]] registers a sub AND an event under one
  concern keyword; it is a naming convention with refusals, not a
  feature. It lives beside the hand-written subs because that is where a
  reader looks for *what can this page read* — and because the pair it
  mints is exactly the pair the two files below would otherwise both
  hold half of.

  It is the ONE place this namespace touches the Hicasso door. The
  `re-frame.core` subs above it know nothing about a view substrate."
  (:refer-clojure :exclude [t])
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.slice.db :as db]
            [re-frame.hicasso.examples.slice.i18n :as i18n]))

;; ---------------------------------------------------------------------------
;; Layer 1 — the raw reads
;; ---------------------------------------------------------------------------

(rf/reg-sub ::locale (fn [db _] (:locale db)))
(rf/reg-sub ::theme  (fn [db _] (:theme db)))
(rf/reg-sub ::save   (fn [db _] (:save db)))
(rf/reg-sub ::digest (fn [db _] (:digest db)))

(rf/reg-sub ::listed
  {:doc "Every article, in publication order, projected to the fields one
  feed row reads. Layer 1 over `app-db` and the input both halves of
  pagination chain from — the page's rows and the page COUNT are two
  questions about the same list, and reading it twice would make them two
  answers that can drift."}
  (fn [db _]
    (mapv (fn [a] (select-keys a [:slug :title :published? :tags]))
          (db/listed db))))

;; ---------------------------------------------------------------------------
;; Layer 2 — i18n and theming, as ordinary derived reads
;; ---------------------------------------------------------------------------

(rf/reg-sub ::t
  {:doc "The sentence for a string key, in the frame's current locale."}
  :<- [::locale]
  (fn [locale [_ k]] (i18n/t locale k)))

(rf/reg-sub ::token
  {:doc "The value of a theme token, under the frame's current theme."}
  :<- [::theme]
  (fn [theme [_ k]] (i18n/token theme k)))

;; ---------------------------------------------------------------------------
;; Layer 2 — the feed and the article
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Layer 2 — pagination, whose only input from outside `app-db` is the URL
;; (rf2-hic-074)
;; ---------------------------------------------------------------------------

(rf/reg-sub ::page
  {:doc "The page the URL asks for, RAW — `nil` on any route that declares
  no `?page=`, and whatever number a user typed on the one that does. It
  is separated from [[::current-page]] so the difference between *what was
  asked for* and *what exists* stays visible: the pager highlights the
  page it is showing, and only the clamp decides which that is."}
  :<- [:rf.route/query]
  (fn [query _] (:page query)))

(rf/reg-sub ::page-count
  {:doc "How many pages the feed has."}
  :<- [::listed]
  (fn [rows _] (db/page-count rows)))

(rf/reg-sub ::current-page
  {:doc "The page actually on screen — the URL's number brought inside the
  range the data has. `/slice?page=900` shows the last page rather than an
  empty one, because a URL is user input."}
  :<- [::listed]
  :<- [::page]
  (fn [[rows page] _] (db/clamp-page rows page)))

(rf/reg-sub ::feed
  {:doc "The rows THIS PAGE shows, in publication order. Each row is the
  minimum the row view reads, so a body edit does not re-render the feed.

  The name did not change when pagination arrived, and neither did the
  question the feed page asks: *what rows do I render?* Which is why the
  page number never reaches a view — the pager reads it to draw itself,
  and the list reads rows."}
  :<- [::listed]
  :<- [::current-page]
  (fn [[rows page] _] (db/page-rows rows page)))

;; ---------------------------------------------------------------------------
;; Layer 2 — the digest, the runtime-selected content region
;; ---------------------------------------------------------------------------

(rf/reg-sub ::digest-blocks
  {:doc "The digest's blocks, as they arrived. The region renders them by
  kind and the shape of the region follows the data."}
  :<- [::digest]
  (fn [digest _] (:blocks digest)))

(rf/reg-sub ::digest-loading?
  {:doc "Is a reload of the digest in flight? The retry control reads it,
  so a second click cannot queue a second request."}
  :<- [::digest]
  (fn [digest _] (= :loading (:status digest))))

(rf/reg-sub ::article
  {:doc "One article by slug, or nil for a slug the URL invented."}
  (fn [db [_ slug]] (db/article db slug)))

(rf/reg-sub ::draft
  {:doc "The editable value of one article — the draft, or the article's
  own fields when nobody has typed yet. See db/draft-for."}
  (fn [db [_ slug]] (db/draft-for db slug)))

(rf/reg-sub ::dirty?
  {:doc "Has this article been edited since it was last saved or discarded?"}
  (fn [db [_ slug]] (db/dirty? db slug)))

;; ---------------------------------------------------------------------------
;; Layer 3 — the save region, projected for the view
;; ---------------------------------------------------------------------------

(rf/reg-sub ::save-state
  {:doc "What the editor's status region should say about one article.

  `{:status :idle|:saving|:failed|:saved :problem <keyword or nil>}` —
  and the projection is what keeps the VIEW free of the stale-reply rule:
  a save belonging to another article reads as `:idle` here, so the
  editor's markup asks one question instead of two.

  BOTH keys are always present. `select-keys` was the first spelling and
  it answered `{:status :saving}` in flight and `{:status :failed
  :problem …}` on refusal — two shapes for one read, so a caller
  comparing whole values has to know which branch it is in. A closed
  shape costs one nil."}
  :<- [::save]
  (fn [save [_ slug]]
    (if (= slug (:slug save))
      {:status (:status save) :problem (:problem save)}
      {:status :idle :problem nil})))

;; ---------------------------------------------------------------------------
;; Per-instance widget state — the sugar, used once
;; ---------------------------------------------------------------------------

(def tags-open?
  "Whether one feed row has its tags revealed.

  `h/reg-state` mints the sub and the setter together under `[:ui
  ::tags-open? slug]`, which is the whole reason to reach for it: the
  bug it deletes is the one where every row on the page shares a single
  `[:ui :tags-open?]` flag and they all open at once. The instance key
  is the article's slug — a domain id, which is the rule the sugar
  teaches and does not police."
  (h/reg-state ::tags-open? {:default false}))
