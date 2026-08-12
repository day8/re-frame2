(ns re-frame.hicasso.examples.typeahead.service
  "THE STAND-IN SEARCH SERVICE — the network, and nothing else
  (rf2-hic-044).

  A typeahead is a witness about resources, so it needs something that
  behaves like one: a request that takes time to answer, that can be
  ABANDONED before it answers, and that answers whether or not anybody is
  still listening. That is this namespace. A real application deletes it,
  writes `day8/re-frame2-http` in its place, and changes nothing above:
  the handlers in [[re-frame.hicasso.examples.typeahead.events]] emit an
  effect and take a reply as an event either way.

  It is deliberately NOT part of the ceremony census. Nothing here keeps
  resource liveness correlated with read liveness — it cannot, because it
  has never heard of a read. What it does is make the correlation
  OBSERVABLE, which is why the census's excluded-region list names this
  whole file rather than any line in it.

  ## Three effects, one table

      [::search  {:token … :term … :delay … :on-ok … :on-fail …}]
      [::detail  {:token … :id   … :delay … :on-ok …}]
      [::abandon {:token …}]

  [[!in-flight]] is the request table every HTTP client has: one entry per
  REQUEST, keyed by the token its caller minted, holding the handle
  cancellation needs. It is not a per-read structure and could not become
  one — it has no idea what a read is — which is the distinction the
  report's C5 section turns on.

  ## `setTimeout`, not a promise

  The same reason the slice's stand-in server gives: a promise-based stub
  can be driven by neither of the two waiting mechanisms this repository
  supports at once. A timer's reply arrives through `rf/dispatch`, exactly
  as a real HTTP reply does, so every row that waits for one waits on the
  condition through `re-frame.test-support/poll-until`.

  ## The log is the network's, not the application's

  [[requests]] answers every request the application ISSUED, in order.
  That number is the one a resource witness is really about — a debounce
  that does not debounce shows up here and nowhere else — and it belongs
  beside the socket rather than in `app-db`, where it would be
  application state the application never reads."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The corpus — the server's data, not the application's
;; ---------------------------------------------------------------------------

(def catalogue
  "What the service can find. Small, and chosen so that two prefixes
  overlap (`ca`) and one is answered by nothing (`zz`), because an empty
  result is a state the panel has to render."
  [{:id "cat"    :name "Cataract"   :blurb "A clouding of the lens."}
   {:id "canid"  :name "Canid"      :blurb "The dog family."}
   {:id "cavil"  :name "Cavil"      :blurb "To raise trivial objections."}
   {:id "dote"   :name "Dote"       :blurb "To be foolishly fond."}
   {:id "dowel"  :name "Dowel"      :blurb "A headless cylindrical pin."}])

(defn- matches
  "Every catalogue row whose name starts with `term`, case-insensitively.
  The server's own query; the application never runs it."
  [term]
  (let [t (str/lower-case (str/trim (or term "")))]
    (filterv #(str/starts-with? (str/lower-case (:name %)) t) catalogue)))

;; ---------------------------------------------------------------------------
;; The wire
;; ---------------------------------------------------------------------------

(defonce ^:private !requests
  ;; Every request issued, in order. Append-only within a run; the witness
  ;; clears it between rows through `reset-log!`.
  (atom []))

(defonce ^:private !in-flight
  ;; token -> the platform timer handle that will deliver its reply. The
  ;; request table, and the whole of what cancellation needs.
  (atom {}))

(defn requests
  "Every request the application issued since the last [[reset-log!]], in
  order, as `{:kind :search|:detail, :token …, :param …}`."
  []
  @!requests)

(defn outstanding
  "The tokens of requests that have been issued and neither answered nor
  abandoned. **The residue reading**: a token still here after the read
  that wanted it has gone is work the page is paying for and nobody is
  waiting on."
  []
  (set (keys @!in-flight)))

(defn reset-log!
  "Clear the log and abandon everything still armed. A witness's own
  between-rows reset — an application never calls it."
  []
  (doseq [[_ handle] @!in-flight] (js/clearTimeout handle))
  (reset! !in-flight {})
  (reset! !requests [])
  nil)

(defn- serve!
  "Record the request, arm its reply, and remember the handle so that
  [[::abandon]] can reach it."
  [kind token param delay reply-fn]
  (swap! !requests conj {:kind kind :token token :param param})
  (let [handle (js/setTimeout
                 (fn []
                   (swap! !in-flight dissoc token)
                   (reply-fn))
                 delay)]
    (swap! !in-flight assoc token handle))
  nil)

(rf/reg-fx ::search
  {:doc       "Ask the service for the rows matching a term."
   :platforms #{:client}}
  (fn [ctx {:keys [token term delay on-ok on-fail]}]
    ;; `ctx` carries `:frame`, so the reply goes back to the frame the
    ;; request came from and to no other. The stub holds no captured
    ;; frame, no closure over a root and no global.
    (serve! :search token term delay
            (fn []
              (let [rows (matches term)]
                (if (= "zzz" (str/lower-case (str/trim term)))
                  ;; One term the service refuses, so the witness has a
                  ;; failure path that is the SERVER's rather than a
                  ;; client-side validation.
                  (rf/dispatch [on-fail {:token token :term term :problem :problem/service-down}]
                               {:frame (:frame ctx)})
                  (rf/dispatch [on-ok {:token token :term term :rows rows}]
                               {:frame (:frame ctx)})))))))

(rf/reg-fx ::detail
  {:doc       "Ask the service for one row's detail."
   :platforms #{:client}}
  (fn [ctx {:keys [token id delay on-ok]}]
    (serve! :detail token id delay
            (fn []
              (let [row (first (filter #(= id (:id %)) catalogue))]
                (rf/dispatch [on-ok {:token token :id id :row row}]
                             {:frame (:frame ctx)}))))))

(rf/reg-fx ::abandon
  {:doc       "Drop a request the application no longer wants an answer to.
               `AbortController` in a real client; `clearTimeout` here."
   :platforms #{:client}}
  (fn [_ {:keys [token]}]
    (when-some [handle (get @!in-flight token)]
      (js/clearTimeout handle)
      (swap! !in-flight dissoc token))
    nil))
