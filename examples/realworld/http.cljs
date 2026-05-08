(ns realworld.http
  "The :http registered fx for the RealWorld example.

   The RealWorld spec lives at:
     https://github.com/gothinkster/realworld/tree/main/api

   Production deployments point at https://api.realworld.io/api; for
   local development the spec ships a Node + Postgres reference backend
   that listens on http://localhost:3000/api.

   Architecture:
   - The :http fx accepts the Pattern-AsyncEffect shape: :method, :url,
     :body, :on-success, :on-error.
   - It auto-injects the Bearer token from the auth slice when present;
     `:auth?` defaults to true for routes that need it.
   - It serialises the :body to JSON; deserialises responses to keywordized
     CLJ data; surfaces non-2xx as :on-error with the parsed error body.
   - It is :platforms #{:server :client} so SSR and the client share the
     same effect surface (per Spec 011)."
  (:require [re-frame.core :as rf]))

;; ============================================================================
;; CONFIG
;; ============================================================================

(def api-base
  "Default API base URL. In production set this from the build/env; for
   local development the realworld reference backend runs on :3000."
  "https://api.realworld.io/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; FX  (CP-3)
;; ============================================================================

(rf/reg-fx :http
  {:doc       "Issue an HTTP request to the RealWorld API. The :body is
               JSON-encoded; responses are JSON-decoded with keywordized
               keys. The current auth token is injected as a Bearer header
               when present (override with :auth? false). On 2xx, dispatches
               :on-success with the parsed body; on non-2xx or transport
               error, dispatches :on-error with the parsed error body."
   :platforms #{:server :client}}
  (fn fx-http [{:keys [frame] :as _m}
               {:keys [method url body on-success on-error auth?]
                :or   {auth? true}}]
    (let [token   (when auth?
                    (some-> (rf/get-frame-db (or frame :rf/default))
                            :auth :token))
          headers (cond-> {"Content-Type" "application/json"
                           "Accept"       "application/json"}
                    token (assoc "Authorization" (str "Token " token)))
          init    #js {:method  (-> method name clojure.string/upper-case)
                       :headers (clj->js headers)
                       :body    (when body (js/JSON.stringify (clj->js body)))}]
      (-> (js/fetch (full-url url) init)
          (.then (fn [resp]
                   (-> (.json resp)
                       (.then (fn [json]
                                (let [data (js->clj json :keywordize-keys true)]
                                  (if (.-ok resp)
                                    (when on-success
                                      (rf/dispatch (conj on-success data) {:frame frame}))
                                    (when on-error
                                      (rf/dispatch (conj on-error data) {:frame frame})))))))))
          (.catch (fn [err]
                    (when on-error
                      (rf/dispatch (conj on-error {:errors {:body [(str err)]}}) {:frame frame}))))))))

