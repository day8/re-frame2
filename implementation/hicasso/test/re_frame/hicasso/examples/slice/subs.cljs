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

(rf/reg-sub ::feed
  {:doc "The list rows, in publication order. Each row is the minimum the
  row view reads, so a body edit does not re-render the feed."}
  (fn [db _]
    (mapv (fn [a] (select-keys a [:slug :title :published? :tags]))
          (db/listed db))))

(rf/reg-sub ::article
  {:doc "One article by slug, or nil for a slug the URL invented."}
  (fn [db [_ slug]] (db/article db slug)))

(rf/reg-sub ::draft
  {:doc "The editable value of one article — the draft, or the article's
  own fields when nobody has typed yet. See db/draft-for."}
  (fn [db [_ slug]] (db/draft-for db slug)))

(rf/reg-sub ::revision
  {:doc "The controlled fields' reset trigger for one article."}
  (fn [db [_ slug]] (db/revision db slug)))

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
  editor's markup asks one question instead of two."}
  :<- [::save]
  (fn [save [_ slug]]
    (if (= slug (:slug save))
      (select-keys save [:status :problem])
      {:status :idle})))

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
