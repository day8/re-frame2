# HTTP trace redaction

An article app may send credentials and receive an authentication token. Declare
which values are sensitive so HTTP traces can show the request's progress
without recording those values.

```clojure
;; cf. examples/core/login/model.cljc — sensitive login request
(ns app.article-auth
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.schemas]))

(def LoginResponse
  [:map
   [:token {:sensitive? true} :string]
   [:user-id :int]])

(rf/reg-event :auth/login
  {:sensitive [[:password]]}
  (fn [_ [_ credentials]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/api/login"
                         :body credentials :request-content-type :json}
            :sensitive? true
            :decode     LoginResponse
            :on-success [:auth/logged-in]
            :on-failure [:auth/login-failed]}]]}))

(rf/reg-event :auth/logged-in
  {:sensitive [[:value :token]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:auth :token] (:token value))
             (assoc-in [:auth :status] :authenticated))
     :sensitive [[:auth :token]]}))

(rf/reg-event :auth/login-failed
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :status] :error)}))
```

Each [event registration](../core/how-to/keep-secrets-out-of-traces.md#classify-a-transient-payload-on-the-registration)
classifies its own arguments; HTTP redaction does not carry over to them. Managed
HTTP appends the success envelope to `[:auth/logged-in]`, so the token's event
path is `[:value :token]`.

`:sensitive? true` redacts the request body, params and all URL query values,
and the response payload, in HTTP traces. `LoginResponse` also marks the token
by field: when that schema is used without a whole-request flag, the token is
redacted while `:user-id` remains visible. Your receiving handler gets the real
token. Its `:sensitive` effect classifies the copy stored in app-db: a response's
classification does not automatically apply to the values your handler writes.

Add `day8/re-frame2-schemas` and require `re-frame.schemas` when using schema
marks. A request with marks but without that artefact is refused before sending
with `:rf.error/schemas-artefact-missing`.

## Headers and URL parameters

Managed HTTP already redacts sensitive header names, including `Authorization`
and `Cookie`, in every HTTP trace. No per-request flag is needed. It also
redacts values of known secret URL parameters, such as `access_token` and
`api_key`, while preserving other parameters and the endpoint's address.
Matching is case-insensitive.

For names specific to your API, add them once on the effect registration:

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Article-Token"]
              :query-params ["article_token"]}}
  re-frame.http.managed/managed-handler)
```

This keeps the shipped handler and adds the names to its built-in redaction
lists. Register it after requiring `re-frame.http.managed`, before issuing
requests. The [classification reference](../api/re-frame.http.md#privacy-and-classification)
lists the built-in names and exact declaration forms.

## Response bodies

Use a Malli schema in its EDN vector form to mark individual response fields.
`:sensitive?` replaces a field with `:rf/redacted`; `:large?` replaces it with a
size marker. Unmarked siblings remain visible unless the request's
`:sensitive?` flag redacts the whole payload. Field marks also apply when the
request is not flagged.

A keyword decoder such as `:json`, a decoder function, a registry-keyword
schema reference or a compiled schema does not expose field marks to the
classification walker. The response's shape is then unknown, so the body is
omitted from exports rather than sent outside the app unclassified.

## Local traces and exports

A non-2xx response keeps its raw error body for the app to inspect. It never
passes through `:decode`, so its fields have no schema classification. HTTP
exports therefore omit that body; a local dev trace may still contain it.
Decode an API's structured error in the failure handler when needed, and
classify any secret you then store in app-db.

[Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) covers
classification on events and durable state, along with export policy. HTTP
classification applies to dev traces and is removed with tracing in production.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/schemas-artefact-missing` and no request | The decode schema has marks, but the schemas artefact is not loaded | Add `day8/re-frame2-schemas` and require `re-frame.schemas` |
| `:rf.error/bad-classification` naming `:rf.http/managed` | Its `:carriers` declaration is malformed | Use vectors of names under `:headers` and `:query-params`, as above |
| A token is redacted in HTTP traces but visible in app-db history | The receiving handler wrote it to an unclassified path | Return `:sensitive` for each stored path |
| An error body is missing from an export | The body has no schema classification | Inspect it locally; exports omit raw error bodies |
