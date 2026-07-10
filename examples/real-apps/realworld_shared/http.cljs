(ns realworld-shared.http
  "The transport-neutral HTTP contract shared by both RealWorld examples
   (`realworld_http/` and `realworld_resources/`): Conduit query encoding, the
   read-fetch retry policy, the failure-taxonomy projection, and the pagination
   constant + arithmetic.

   None of this is part of the architecture comparison the two examples exist to
   draw — a `?limit=&offset=` query string is encoded the same way and a 5xx
   projects to the same sentence whether the read came from a managed-HTTP event
   or a `reg-resource`. So it lives here, in one place, and each app builds its
   OWN request builders / URLs / resource + mutation registrations on top (those
   genuinely differ between the two architectures and stay local).

   The RealWorld spec lives at
   https://github.com/gothinkster/realworld/tree/main/api."
  (:require [clojure.string :as str]))

;; ============================================================================
;; QUERY ENCODING
;; ============================================================================

(defn query-string
  "Assemble a `?k=v&k2=v2…` query string from a map of query params, with every
   value URL-encoded via `js/encodeURIComponent` so a tag, username, or other
   filter value carrying a reserved query character (`&`, `=`, `#`, a space,
   …) reaches the API as that literal value instead of corrupting the query
   string it's embedded in. Nil-valued params are dropped, and an empty (or
   all-nil) map yields `\"\"`, not a bare `\"?\"`."
  [params]
  (let [pairs (for [[k v] params :when (some? v)]
                (str (name k) "=" (js/encodeURIComponent (str v))))]
    (if (seq pairs)
      (str "?" (str/join "&" pairs))
      "")))

;; ============================================================================
;; PAGINATION (official RealWorld spec — limit/offset query params)
;; ============================================================================
;;
;; Conduit paginates every article list — global, tag-filtered, the
;; authenticated feed, and a profile's authored / favorited lists — the classic
;; database way, with two query params:
;;
;;   ?limit=<page-size>&offset=<rows-to-skip>
;;
;; Alongside the page it hands back `articlesCount`: the GRAND total, the number
;; of articles matching the query BEFORE the limit/offset window — not the size
;; of this page. That distinction is the whole game, because the page count
;; comes from the grand total, not from what one page happened to return. The
;; canonical Conduit frontend draws a 1-indexed page-number control and works
;; out the page count as `Math.ceil(articlesCount / limit)` at a fixed page size
;; of 10.
;;
;; Both apps mirror that shape, with one ergonomic twist: the ROUTE carries a
;; human-friendly 1-indexed `?page=N` (so Back/Forward and bookmarking just
;; work), and each app's request builder translates it into the wire's 0-based
;; `offset` via `page->limit-offset`. Humans count from 1; the wire counts from
;; 0; this is the one place that seam lives.

(def page-size
  "Articles per page: 10, to match the canonical Conduit frontend (which
   hardcodes `Math.ceil(articlesCount / 10)`)."
  10)

(defn page->limit-offset
  "Turn a 1-indexed UI `:page` (nil → page 1) into the Conduit `{:limit :offset}`
   query pair the wire wants (`offset = (page - 1) * limit`). A stray nil or
   sub-1 page — someone hand-typing `?page=0` into the URL bar — is clamped up to
   page 1, because page zero is not a thing. Pure, and the one place page
   arithmetic lives: a page is just another canonical-params key, so there's no
   reason to do this maths twice."
  [page]
  (let [p (max 1 (or page 1))]
    {:limit  page-size
     :offset (* (dec p) page-size)}))

(defn page-count
  "How many pages `articles-count` items fill at the fixed `page-size`:
   `ceil(articles-count / page-size)`, but never less than 1 — an empty list is
   still one page, it's just an empty one. Mirrors the official frontend's
   `Math.ceil(articlesCount / 10)`."
  [articles-count]
  (max 1 (long (js/Math.ceil (/ (or articles-count 0) page-size)))))

;; ============================================================================
;; RETRY POLICY
;; ============================================================================

(def data-fetch-retry
  "The house retry policy for read-only fetches — lists, profiles, article
   detail, comments, tags, the feed. It retries the failures that a second try
   might fix (transport blips, 5xx, timeouts) and leaves 4xx alone, because a
   rejected request shape doesn't get any more valid the second time you send
   it. Three attempts total, with exponential backoff and a dash of jitter so a
   thundering herd doesn't all retry on the same beat.

   Writes get no retry policy: login, register, settings, and every mutation are
   one submission per click — if the server 5xxes, that surfaces as an error and
   the user decides whether to click again. Silently re-POSTing someone's form
   behind their back is how you end up with two of everything."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; ============================================================================
;; FAILURE PROJECTION
;; ============================================================================

(defn failure->message
  "Turn a failure map (the classified `:rf.http/*` map that rides under
   `:error` on a `{:status :error :error {...}}` reply — or the inner
   `:failure` a resource `:error` / `:refresh-error` and a mutation `:error`
   carry) into a sentence a human can actually read. Conduit reports 4xx
   validation problems as `{:errors {:body [\"...\"]}}` (or `{:errors {<field>
   [\"...\"]}}`), so we surface the server's own words when they're there, and
   fall back to a friendly category-based message keyed off the closed
   `:rf.http/*` taxonomy when they aren't — because \"Network error — please try
   again\" beats a stack trace every time."
  [failure]
  (let [body     (:body failure)
        ;; The :body might be raw response text, or a JSON-shaped failure map
        ;; arriving via the `:rf.http/accept-failure` path. Try either shape.
        body-msg (cond
                   (and (map? body) (-> body :errors :body first)) (-> body :errors :body first)
                   (and (map? body) (-> body :errors first))       (let [[k v] (first (:errors body))]
                                                                     (str (name k) ": " (first v)))
                   (string? body) body
                   :else nil)]
    (or body-msg
        (case (:kind failure)
          :rf.http/transport      "Network error — please try again."
          :rf.http/timeout        "Request timed out."
          :rf.http/http-4xx       (str "Request rejected (status " (:status failure) ").")
          :rf.http/http-5xx       (str "Server error (status " (:status failure) ").")
          :rf.http/decode-failure "Couldn't parse server response."
          :rf.http/accept-failure (or (-> failure :detail :message) "Unexpected response shape.")
          :rf.http/aborted        "Request cancelled."
          (:message failure))
        "Request failed.")))
