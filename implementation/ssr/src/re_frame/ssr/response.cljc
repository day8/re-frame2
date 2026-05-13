(ns re-frame.ssr.response
  "HTTP response accumulator + handler fns for the six `:rf.server/*`
  server-side fxs. Per Spec 011 §HTTP response contract.

  The runtime owns a per-request response accumulator at `[:rf/response]`
  in the request frame's app-db. Standard server-only fx populate the
  slot during the drain; the host adapter consumes the resolved value
  after drain to build the wire response.

  Default shape (Spec 011 §HTTP response contract):

      {:status   200
       :headers  [[\"content-type\" \"text/html; charset=utf-8\"]]
       :cookies  []
       :redirect nil}

  Internal-only bookkeeping under `:rf.server/_status-writes` and
  `:rf.server/_redirect-writes` records every write so the runtime can
  emit `:rf.warning/multiple-status-set` / `:rf.warning/multiple-redirects`
  on the second-and-later write while still preserving last-write-wins
  semantics for the public `:status` / `:redirect` slots.

  All `reg-fx` calls live in the `re-frame.ssr` façade so a
  `(require 're-frame.ssr :reload)` after `(registrar/clear-all!)`
  re-installs them. This namespace exports handler fns only.

  `get-response` (the read surface that flushes pending error projections
  before reading) lives in `re-frame.ssr.error-listener` because it
  depends on the projector's drain — `response-of` here is the pure
  read used both internally and by the listener module.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [clojure.string]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

(def response-path
  "App-db path holding the per-request HTTP response accumulator.
  Per Spec 011 §HTTP response contract."
  [:rf/response])

(def status-writes-key   :rf.server/_status-writes)
(def redirect-writes-key :rf.server/_redirect-writes)

(defn default-response
  "The default response accumulator. Spec 011 §Status defaults: status 200,
  default content-type text/html for HTML responses, no cookies, no redirect."
  []
  {:status   200
   :headers  [["content-type" "text/html; charset=utf-8"]]
   :cookies  []
   :redirect nil})

(defn ensure-response
  "Return resp with defaults applied. nil-tolerant — a frame whose app-db
  has never been touched by an :rf.server/* fx still resolves to the
  default response shape."
  [resp]
  (if resp
    (merge (default-response) resp)
    (default-response)))

(defn swap-response!
  "Mutate the response accumulator in the frame's app-db with f. Returns
  the post-swap response map. No-op when the frame is unknown / destroyed
  (an explicit trace fires elsewhere; we don't double-trace here)."
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [before (adapter/read-container container)
          after  (update before :rf/response #(f (ensure-response %)))]
      (adapter/replace-container! container after)
      (:rf/response after))))

(defn response-of
  "Read the current response accumulator (with defaults applied)."
  [frame-id]
  (when-let [container (frame/get-frame-db frame-id)]
    (ensure-response (:rf/response (adapter/read-container container)))))

;; ---- header helpers ------------------------------------------------------

(defn- replace-header
  "Replace the (first matching, case-insensitive) header pair with [name value],
  or append if none matched. Subsequent matches are dropped — set-header
  replaces the entire header value per Spec 011 §Header replacement vs append."
  [headers name value]
  (let [normalised (str name)
        target     (clojure.string/lower-case normalised)
        [seen? pruned]
        (reduce
          (fn [[seen acc] [h-name _h-val :as pair]]
            (cond
              (not= (clojure.string/lower-case (str h-name)) target)
              [seen (conj acc pair)]

              seen
              [seen acc]    ;; drop subsequent matches

              :else
              [true (conj acc [normalised value])]))
          [false []]
          headers)]
    (if seen?
      pruned
      (conj pruned [normalised value]))))

(defn- append-header-pair
  "Append [name value] to headers — preserves any existing header with the
  same name. Per Spec 011 §Header replacement vs append; required for
  Set-Cookie-style multi-valued headers."
  [headers name value]
  (conj (vec headers) [(str name) value]))

;; ---- handler fns for the six :rf.server/* fxs ----------------------------

(defn set-status-fx
  "Handler fn for `:rf.server/set-status`. Last-write-wins; multi-write
  emits `:rf.warning/multiple-status-set`."
  [{:keys [frame]} status]
  (let [resp (swap-response!
               frame
               (fn [r]
                 (-> r
                     (update status-writes-key (fnil conj []) status)
                     (assoc :status status))))]
    (when (and resp (> (count (get resp status-writes-key)) 1))
      (let [writes (get resp status-writes-key)]
        (trace/emit! :warning :rf.warning/multiple-status-set
                     {:writes       writes
                      :final-status (last writes)
                      :frame        frame
                      :recovery     :warned-and-replaced})))))

(defn set-header-fx
  "Handler fn for `:rf.server/set-header`. Replaces any existing header
  with the same name (case-insensitive)."
  [{:keys [frame]} {:keys [name value]}]
  (swap-response!
    frame
    (fn [r] (update r :headers replace-header name value))))

(defn append-header-fx
  "Handler fn for `:rf.server/append-header`. Preserves any existing
  header with the same name — required for Set-Cookie-style multi-valued
  headers."
  [{:keys [frame]} {:keys [name value]}]
  (swap-response!
    frame
    (fn [r] (update r :headers append-header-pair name value))))

(defn set-cookie-fx
  "Handler fn for `:rf.server/set-cookie`. Cookie attributes are stored
  as a structured map (RFC 6265 wire-form serialisation is host-adapter
  business)."
  [{:keys [frame]} cookie-map]
  (swap-response!
    frame
    (fn [r] (update r :cookies (fnil conj []) cookie-map))))

(defn delete-cookie-fx
  "Handler fn for `:rf.server/delete-cookie`. Sugar over set-cookie
  with :max-age 0 and an empty :value."
  [{:keys [frame]} {:keys [name path domain]}]
  (let [cookie (cond-> {:name    name
                        :value   ""
                        :max-age 0}
                 path   (assoc :path   path)
                 domain (assoc :domain domain))]
    (swap-response!
      frame
      (fn [r] (update r :cookies (fnil conj []) cookie)))))

(defn redirect-fx
  "Handler fn for `:rf.server/redirect`. Defaults :status to 302 if
  absent. Multiple writes emit `:rf.warning/multiple-redirects`
  (last-write-wins)."
  [{:keys [frame]} redirect-map]
  (let [;; Spec 011 accepts :location, :url, or :to.
        location  (or (:location redirect-map)
                      (:url      redirect-map)
                      (:to       redirect-map))
        status    (or (:status redirect-map) 302)
        normalised (cond-> (assoc redirect-map :status status)
                     location (assoc :location location))
        resp (swap-response!
               frame
               (fn [r]
                 (-> r
                     (update redirect-writes-key (fnil conj []) normalised)
                     (assoc :redirect normalised)
                     ;; Spec 011 §Redirect precedence step 1: the
                     ;; redirect's :status flows through to the
                     ;; response :status so the host adapter writes
                     ;; the redirect status on the wire even if no
                     ;; explicit :rf.server/set-status fired.
                     (assoc :status status))))]
    (when (and resp (> (count (get resp redirect-writes-key)) 1))
      (let [writes (get resp redirect-writes-key)]
        (trace/emit! :warning :rf.warning/multiple-redirects
                     {:writes         writes
                      :final-redirect (last writes)
                      :frame          frame
                      :recovery       :warned-and-replaced})))))
