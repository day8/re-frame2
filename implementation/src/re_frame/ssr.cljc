(ns re-frame.ssr
  "Server-side rendering and hydration. Per Spec 011.

  Implements:
    - Pure hiccup → HTML emitter (HTML5: void elements self-close
      bare, doctype prefix on demand, full attr/text escaping,
      :tag#id.cls keyword parsing, registered-view resolution via
      the :view registry).
    - :rf/hydrate event with :replace-app-db semantics — the server-
      supplied payload's :rf/app-db replaces the client app-db. The
      conformance harness simulates a hydration-mismatch by feeding a
      :simulated-client-render-hash; the runtime compares against the
      payload's :rf/render-hash and emits :rf.ssr/hydration-mismatch
      with :recovery :warned-and-replaced.
    - :rf.server/* response-shape fx (:rf.server/set-status,
      :rf.server/set-header, :rf.server/append-header,
      :rf.server/set-cookie, :rf.server/delete-cookie,
      :rf.server/redirect) gated by :platforms #{:server} via the
      per-frame :platform predicate. The accumulator lives in app-db
      under the reserved path [:rf/response]; the host adapter consumes
      that slot after drain to build the wire response. Per
      Spec 011 §HTTP response contract.
    - Default error projector (:rf.ssr/default-error-projector)
      mapping :rf.error/no-such-handler / no-such-route to the
      Spec-011 public-error shape.

  Conformance fixtures cover all of the above (ssr/render-to-string,
  ssr/hydrate, ssr/hydration-mismatch, ssr/head-emits, ssr/head-hydration,
  ssr/error-known-mapping, ssr/error-sanitisation, ssr/cookie,
  ssr/redirect, ssr/set-status, fx/platforms)."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]
            #?(:cljs [re-frame.substrate.plain-atom :as plain-atom-cljs])
            #?(:cljs [re-frame.substrate.reagent :as reagent-adapter])))

(defn- escape-html [s]
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;")
      (clojure.string/replace "\"" "&quot;")
      (clojure.string/replace "'" "&#39;")))

(defn- escape-attr [s]
  ;; Less aggressive — attributes don't need < > escaped, but " must
  ;; be escaped if we use double-quoted values. We use double-quoted
  ;; values, so escape " and &.
  (-> (str s)
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "\"" "&quot;")))

(defn- attr-string [attrs]
  (if (empty? attrs)
    ""
    (str " " (clojure.string/join " "
               (map (fn [[k v]]
                      (cond
                        (true? v)  (name k)             ;; boolean attrs
                        (false? v) nil
                        (nil? v)   nil
                        :else      (str (name k) "=\"" (escape-attr v) "\"")))
                    attrs)))))

;; Per HTML5 spec, these elements are void — they self-close and have no
;; closing tag.
(def ^:private void-elements
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; Tag-name parsing for the :div#id.cls syntax. Reagent / Hiccup convention.
(defn- parse-tag-name
  "Split a keyword like :div#main.col-12.bold into [:div {:id \"main\"
  :class \"col-12 bold\"}] components."
  [tag-kw]
  (let [s     (name tag-kw)
        ;; Match: tag-name optionally followed by #id and .class fragments.
        [_ tag id classes]
        #?(:clj  (re-matches #"([^#.]+)(?:#([^.]+))?(.*)" s)
           :cljs (re-matches #"([^#.]+)(?:#([^.]+))?(.*)" s))
        class-list (when (and classes (seq classes))
                     (->> (clojure.string/split classes #"\.")
                          (remove empty?)
                          (clojure.string/join " ")))]
    [tag
     (cond-> {}
       id         (assoc :id id)
       class-list (assoc :class class-list))]))

(defn- merge-class-attrs
  "Merge the class from the tag-name into the attrs map's :class."
  [tag-attrs user-attrs]
  (let [t-class    (:class tag-attrs)
        u-class    (:class user-attrs)
        merged     (cond
                     (and t-class u-class) (str t-class " " u-class)
                     t-class                t-class
                     u-class                u-class)]
    (cond-> (merge tag-attrs (dissoc user-attrs :class))
      merged (assoc :class merged))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

(defn- emit-element [el]
  (cond
    (nil? el)         ""
    (string? el)      (escape-html el)
    (number? el)      (str el)
    (boolean? el)     ""
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head)
        (let [;; Look up registered view first.
              maybe-view (registrar/lookup :view head)]
          (if maybe-view
            (emit-element (apply (:handler-fn maybe-view) (rest el)))
            (let [[tag-name tag-attrs] (parse-tag-name head)
                  [user-attrs children]
                  (if (map? (second el))
                    [(second el) (drop 2 el)]
                    [{} (rest el)])
                  attrs       (merge-class-attrs tag-attrs user-attrs)
                  void?       (contains? void-elements (keyword tag-name))]
              (if void?
                (str "<" tag-name (attr-string attrs) ">")
                (str "<" tag-name (attr-string attrs) ">"
                     (emit-children children)
                     "</" tag-name ">")))))

        (fn? head)
        (emit-element (apply head (rest el)))

        :else (str el)))

    (sequential? el) (emit-children el)
    :else (escape-html el)))

;; ---- render-tree hashing --------------------------------------------------
;;
;; Per Spec 011 §Hydration-mismatch detection: the server hashes the
;; render-tree at SSR time; the client recomputes the hash on first
;; render and compares. Mismatch = the runtime emits
;; :rf.ssr/hydration-mismatch with both hashes. We use FNV-1a 32-bit
;; over the canonical-EDN traversal of the render tree, output as
;; lowercase hex. Same algorithm both sides → byte-identical hashes
;; for byte-identical canonical EDN.

(defn- canonical-edn
  "Print a render-tree node in a stable order. Maps are sorted by key
  string; sequences keep order. Functions and var-references appear
  as their toString — stable enough for trees that re-render the same
  view-fn from the same registry."
  [x]
  (cond
    (map? x)
    (str "{"
         (clojure.string/join
           ","
           (map (fn [[k v]] (str (canonical-edn k) " " (canonical-edn v)))
                (sort-by (comp str key) x)))
         "}")

    (vector? x)
    (str "[" (clojure.string/join " " (map canonical-edn x)) "]")

    (sequential? x)
    (str "(" (clojure.string/join " " (map canonical-edn x)) ")")

    (set? x)
    (str "#{"
         (clojure.string/join " " (sort (map canonical-edn x)))
         "}")

    (fn? x)
    (str "#fn[" (.toString ^Object x) "]")

    :else (pr-str x)))

(defn- fnv-1a-32
  "FNV-1a 32-bit hash of a string. Returns the lowercase-hex form, no
  prefix. Stable on JVM and CLJS — uses unchecked 32-bit multiply on
  both sides (CLJS via Math.imul, JVM via long-multiply-then-mask).
  Output bytes are byte-identical for byte-identical input strings."
  [s]
  (let [offset 2166136261              ;; FNV offset basis
        prime  16777619]               ;; FNV prime
    (loop [i 0
           h offset]
      (if (>= i (count s))
        ;; Convert h to unsigned 32-bit and emit lowercase 8-char hex.
        ;; JS's bitwise ops are 32-bit SIGNED; the `>>> 0` idiom forces
        ;; unsigned. JVM bit-and-with-0xffffffff suffices.
        #?(:clj  (format "%08x" (bit-and h 0xffffffff))
           :cljs (let [u (unsigned-bit-shift-right h 0)
                       hex (.toString u 16)]
                   (.padStart hex 8 "0")))
        (let [c (#?(:clj int :cljs .charCodeAt) (.charAt s i) #?(:cljs 0))
              x (bit-xor h c)
              ;; Guaranteed 32-bit multiply.
              p #?(:clj  (bit-and 0xffffffff
                                  (unchecked-multiply x prime))
                   :cljs (js/Math.imul x prime))]
          (recur (inc i) p))))))

(defn render-tree-hash
  "Per Spec 011 §Hydration-mismatch detection: a stable structural hash
  of the render tree. Deterministic across JVM and CLJS for trees with
  identical canonical-EDN representation."
  [render-tree]
  (fnv-1a-32 (canonical-edn render-tree)))

(defn render-to-string
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML emitter.

  Implements:
    - HTML5 void elements (no closing tag, no self-close slash).
    - :tag#id.cls keyword tag-name parsing (id and class merged into attrs).
    - Boolean attributes (true → bare attr name; false / nil → omitted).
    - Text and attribute escaping (HTML-entity safe).
    - :doctype? opt prepends '<!DOCTYPE html>'.
    - Registered-view resolution (looks up :view kind in the registrar).
    - Var-reference resolution (calls the fn, recurses on the result).
    - :emit-hash? opt embeds 'data-rf-render-hash=<hex>' on the root
      element for client-side mismatch detection (per Spec 011)."
  [render-tree opts]
  (let [body (emit-element render-tree)
        body (if (:emit-hash? opts)
               (let [h (render-tree-hash render-tree)]
                 ;; Inject data-rf-render-hash on the FIRST '<tag' opener
                 ;; — that's the root element. Skip <!DOCTYPE> if present.
                 (clojure.string/replace-first
                   body
                   #"(<[a-zA-Z][^\s>/]*)"
                   (str "$1 data-rf-render-hash=\"" h "\"")))
               body)]
    (if (:doctype? opts)
      (str "<!DOCTYPE html>" body)
      body)))

;; Wire our render-to-string into both adapters so callers using
;; rf/render-to-string (which delegates through the substrate adapter)
;; get this implementation on either runtime. JVM apps use the
;; plain-atom adapter; CLJS apps use the Reagent adapter; both reach
;; this fn via their adapter's :render-to-string slot.
#?(:clj
   (try
     (require 're-frame.substrate.plain-atom)
     ((requiring-resolve 're-frame.substrate.plain-atom/set-hiccup-emitter!)
      render-to-string)
     (catch Throwable _ nil)))

#?(:cljs
   (do
     (plain-atom-cljs/set-hiccup-emitter! render-to-string)
     (reagent-adapter/set-hiccup-emitter! render-to-string)))

;; ---- hydrate event + mismatch detection ----------------------------------
;;
;; Per Spec 011 §The :rf/hydrate event and §Hydration-mismatch detection.
;; The server's payload carries :rf/render-hash; we replace app-db with
;; :rf/app-db AND stash the server hash under [:rf/hydration :server-hash]
;; so verify-hydration! can read it after the client's first render.

(events/reg-event-fx :rf/hydrate
  (fn [{:keys [db]} [_ payload]]
    (let [new-db   (or (:rf/app-db payload) (:app-db payload) db)
          metadata (cond-> {}
                     (:rf/render-hash payload) (assoc :server-hash (:rf/render-hash payload))
                     (:rf/version payload)     (assoc :version     (:rf/version payload)))]
      {:db (cond-> new-db
             (seq metadata) (assoc :rf/hydration metadata))})))

(defn verify-hydration!
  "Per Spec 011 §Hydration-mismatch detection. Called by client code
  after the first render. Compares the post-render hash to the server
  hash stashed during :rf/hydrate; on disagreement emits
  :rf.ssr/hydration-mismatch with :recovery :warned-and-replaced.

  The second arg may be EITHER a render tree (we hash it) OR a
  pre-computed hash string (used by test harnesses that simulate the
  client render).

    (verify-hydration! frame-id render-tree)
    (verify-hydration! frame-id render-tree opts)

  opts may carry :first-diff-path, :failing-id, AND :server-hash.
  The :server-hash opt overrides the [:rf/hydration :server-hash]
  slot in app-db — useful when the user's :rf/hydrate handler doesn't
  populate that slot (e.g. fixture-overridden handlers)."
  ([frame-id tree-or-hash] (verify-hydration! frame-id tree-or-hash {}))
  ([frame-id tree-or-hash {:keys [first-diff-path failing-id server-hash]}]
   (let [db          (frame/frame-app-db-value frame-id)
         server-hash (or server-hash
                         (get-in db [:rf/hydration :server-hash]))
         client-hash (cond
                       (string? tree-or-hash) tree-or-hash
                       tree-or-hash           (render-tree-hash tree-or-hash))]
     (when (and server-hash client-hash (not= server-hash client-hash))
       (let [trace-fn (late-bind/get-fn :trace/emit-error!)]
         (when trace-fn
           (trace-fn :rf.ssr/hydration-mismatch
            (cond-> {:server-hash server-hash
                     :client-hash client-hash
                     :frame       frame-id
                     :failing-id  (or failing-id :rf/hydrate)
                     :reason      (str "Hydration mismatch: server hash '"
                                       server-hash
                                       "' != client hash '"
                                       client-hash
                                       "'. Re-rendering client-side.")
                     :recovery    :warned-and-replaced}
              first-diff-path (assoc :first-diff-path first-diff-path)))))))))

;; ---- HTTP response accumulator + :rf.server/* fx -------------------------
;;
;; Per Spec 011 §HTTP response contract. The runtime owns a per-request
;; response accumulator at [:rf/response] in the request frame's app-db.
;; Standard server-only fx populate the slot during the drain; the host
;; adapter consumes the resolved value after drain to build the wire
;; response.
;;
;; Default shape (Spec 011 §HTTP response contract):
;;
;;   {:status   200
;;    :headers  [["content-type" "text/html; charset=utf-8"]]
;;    :cookies  []
;;    :redirect nil}
;;
;; Internal-only bookkeeping under :rf.server/_status-writes and
;; :rf.server/_redirect-writes records every write so the runtime can
;; emit :rf.warning/multiple-status-set / :rf.warning/multiple-redirects
;; on the second-and-later write while still preserving last-write-wins
;; semantics for the public :status / :redirect slots.

(def response-path
  "App-db path holding the per-request HTTP response accumulator.
  Per Spec 011 §HTTP response contract."
  [:rf/response])

(def ^:private status-writes-key  :rf.server/_status-writes)
(def ^:private redirect-writes-key :rf.server/_redirect-writes)

(defn default-response
  "The default response accumulator. Spec 011 §Status defaults: status 200,
  default content-type text/html for HTML responses, no cookies, no redirect."
  []
  {:status   200
   :headers  [["content-type" "text/html; charset=utf-8"]]
   :cookies  []
   :redirect nil})

(defn- ensure-response
  "Return resp with defaults applied. nil-tolerant — a frame whose app-db
  has never been touched by an :rf.server/* fx still resolves to the
  default response shape."
  [resp]
  (if resp
    (merge (default-response) resp)
    (default-response)))

(defn- swap-response!
  "Mutate the response accumulator in the frame's app-db with f. Returns
  the post-swap response map. No-op when the frame is unknown / destroyed
  (an explicit trace fires elsewhere; we don't double-trace here)."
  [frame-id f]
  (when-let [container (frame/get-frame-db frame-id)]
    (let [before (adapter/read-container container)
          after  (update before :rf/response #(f (ensure-response %)))]
      (adapter/replace-container! container after)
      (:rf/response after))))

(defn- response-of
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
        seen?      (atom false)
        pruned     (vec
                     (keep (fn [[h-name h-val]]
                             (if (= (clojure.string/lower-case (str h-name)) target)
                               (when-not @seen?
                                 (reset! seen? true)
                                 [normalised value])
                               [h-name h-val]))
                           headers))]
    (if @seen?
      pruned
      (conj (vec headers) [normalised value]))))

(defn- append-header-pair
  "Append [name value] to headers — preserves any existing header with the
  same name. Per Spec 011 §Header replacement vs append; required for
  Set-Cookie-style multi-valued headers."
  [headers name value]
  (conj (vec headers) [(str name) value]))

;; ---- standard server-side fx --------------------------------------------

(fx/reg-fx :rf.server/set-status
  {:doc       "Set the HTTP response status. Last-write-wins. A second
write in the same drain emits :rf.warning/multiple-status-set per
[Spec 011 §Multiple-status policy]."
   :platforms #{:server}}
  (fn [{:keys [frame]} status]
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
                        :recovery     :warned-and-replaced}))))))

(fx/reg-fx :rf.server/set-header
  {:doc       "Replace any existing header with the same name (case-
insensitive) and write [name value]. Per Spec 011 §Header replacement
vs append."
   :platforms #{:server}}
  (fn [{:keys [frame]} {:keys [name value]}]
    (swap-response!
      frame
      (fn [r] (update r :headers replace-header name value)))))

(fx/reg-fx :rf.server/append-header
  {:doc       "Append [name value] to headers — preserves any existing
header with the same name. Required for Set-Cookie-style multi-valued
headers. Per Spec 011 §Header replacement vs append."
   :platforms #{:server}}
  (fn [{:keys [frame]} {:keys [name value]}]
    (swap-response!
      frame
      (fn [r] (update r :headers append-header-pair name value)))))

(fx/reg-fx :rf.server/set-cookie
  {:doc       "Add a structured cookie to the :cookies vector. Cookie
attributes are stored as a structured map (RFC 6265 wire-form
serialisation is host-adapter business). Per Spec 011 §Cookie shape."
   :platforms #{:server}}
  (fn [{:keys [frame]} cookie-map]
    (swap-response!
      frame
      (fn [r] (update r :cookies (fnil conj []) cookie-map)))))

(fx/reg-fx :rf.server/delete-cookie
  {:doc       "Sugar over :rf.server/set-cookie with :max-age 0 and an
empty :value. The host adapter materialises the delete-marker semantics
on the wire. Per Spec 011 §Cookie shape."
   :platforms #{:server}}
  (fn [{:keys [frame]} {:keys [name path domain]}]
    (let [cookie (cond-> {:name    name
                          :value   ""
                          :max-age 0}
                   path   (assoc :path   path)
                   domain (assoc :domain domain))]
      (swap-response!
        frame
        (fn [r] (update r :cookies (fnil conj []) cookie))))))

(fx/reg-fx :rf.server/redirect
  {:doc       "Set :redirect on the response accumulator. Defaults
:status to 302 if absent. Multiple writes emit
:rf.warning/multiple-redirects (last-write-wins). Truncates HTML body
per Spec 011 §Redirect precedence — the runtime omits :html / :payload
when :redirect is set; the host adapter emits a status-and-Location
response with no body."
   :platforms #{:server}}
  (fn [{:keys [frame]} redirect-map]
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
                        :recovery       :warned-and-replaced}))))))

(defn get-response
  "Read the resolved response accumulator for a frame. Public surface
  for host adapters that consume the accumulator after drain to build
  the wire response. The internal :rf.server/_status-writes /
  :rf.server/_redirect-writes bookkeeping keys are stripped."
  [frame-id]
  (-> (response-of frame-id)
      (dissoc status-writes-key redirect-writes-key)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.core/render-tree-hash forwards to this ns's render-tree-hash
;; but cannot `:require` re-frame.ssr without a cyclic load order
;; (re-frame.core is the user-facing namespace; re-frame.ssr requires
;; re-frame.events and re-frame.frame which are also re-required by
;; core). Publish the entry point through the late-bind hook registry.

(late-bind/set-fn! :ssr/render-tree-hash render-tree-hash)
