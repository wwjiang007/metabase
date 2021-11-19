(ns metabase.analytics.snowplow
  "Functions for sending Snowplow analytics events"
  (:require [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase.config :as config]
            [metabase.models.setting :as setting :refer [defsetting Setting]]
            [metabase.models.user :refer [User]]
            [metabase.public-settings :as public-settings]
            [metabase.util :as u]
            [metabase.util.i18n :as i18n :refer [deferred-tru trs]]
            [toucan.db :as db])
  (:import [com.snowplowanalytics.snowplow.tracker Subject$SubjectBuilder Tracker Tracker$TrackerBuilder]
           [com.snowplowanalytics.snowplow.tracker.emitter BatchEmitter BatchEmitter$Builder Emitter]
           [com.snowplowanalytics.snowplow.tracker.events Unstructured Unstructured$Builder]
           [com.snowplowanalytics.snowplow.tracker.http ApacheHttpClientAdapter ApacheHttpClientAdapter$Builder]
           com.snowplowanalytics.snowplow.tracker.payload.SelfDescribingJson
           org.apache.http.impl.client.HttpClients
           org.apache.http.impl.conn.PoolingHttpClientConnectionManager))

(defsetting analytics-uuid
  (str (deferred-tru "Unique identifier to be used in Snowplow analytics, to identify this instance of Metabase.")
       " "
       (deferred-tru "This is a public setting since some analytics events are sent prior to initial setup."))
  :visibility :public
  :setter     :none
  :getter     #(public-settings/uuid-nonce :analytics-uuid))

(defsetting snowplow-available
  (str (deferred-tru "Boolean indicating whether a Snowplow collector is available to receive analytics events.")
       " "
       (deferred-tru "Should be set via environment variable in Cypress tests or during local development."))
  :type :boolean
  :default config/is-prod?)

(defsetting snowplow-url
  (deferred-tru "The URL of the Snowplow collector to send analytics events to.")
  :default (if config/is-prod?
             "https://sp.metabase.com"
             ;; See the iglu-schema-registry repo for instructions on how to run Snowplow Micro locally for development
             "http://localhost:9095")
  :visibility :public)

(def ^:private emitter
  "Returns an instance of a Snowplow emitter"
  (let [emitter* (delay
                   (let [client (-> (HttpClients/custom)
                                    (.setConnectionManager (PoolingHttpClientConnectionManager.))
                                    (.build))
                         builder (-> (ApacheHttpClientAdapter/builder)
                                     (.httpClient client)
                                     (.url (snowplow-url)))
                         adapter (.build ^ApacheHttpClientAdapter$Builder builder)
                         batch-emitter-builder (-> (BatchEmitter/builder)
                                                   (.bufferSize 1)
                                                   (.httpClientAdapter adapter))]
                     (.build ^BatchEmitter$Builder batch-emitter-builder)))]
     (fn [] @emitter*)))

(def ^:private tracker
  "Returns instance of a Snowplow tracker"
  (let [tracker* (delay
                  (-> (Tracker$TrackerBuilder. ^Emitter (emitter) "sp" "metabase")
                      .build))]
    (fn [] @tracker*)))

(defn- set-subject
  "Create a Subject object for a given user ID, to be included in analytics events"
  [builder user-id]
  (if user-id
    (let [subject (-> (Subject$SubjectBuilder.)
                      (.userId (str user-id))
                      .build)]
      (.subject ^Unstructured$Builder builder subject))
    builder))

(defn- context
  "Common context included in every analytics event"
  []
  (new SelfDescribingJson
       "iglu:com.metabase/instance/jsonschema/1-0-0"
       {"id"             (analytics-uuid),
        "version"        {"tag" (:tag (public-settings/version))},
        "token-features" (m/map-keys name (public-settings/token-features))}))

(defn- normalize-kw
  [kw]
  (-> kw u/snake-key name))

(defn- payload
  "A SelfDescribingJson object containing the provided event data, which can be included as the payload for an
  analytics event"
  [schema version event-kw data]
  (new SelfDescribingJson
       (format "iglu:com.metabase/%s/jsonschema/%s" (normalize-kw schema) version)
       ;; Make sure keywords in payload are converted to strings in snake-case
       (m/map-kv
        (fn [k v] [(normalize-kw k) (if (keyword? v) (normalize-kw v) v)])
        (assoc data :event event-kw))))

(defn- track-event-impl!
  "Wrapper function around the `.track` method on a Snowplow tracker. Can be redefined in tests to instead append
  event data to an in-memory store."
  [tracker event]
  (.track ^Tracker tracker ^Unstructured event))

(def ^:private schema-version
  "The most recent version for each event schema"
  {::account   "1-0-0"
   ::invite    "1-0-0"
   ::dashboard "1-0-0"
   ::database  "1-0-0"})

;; Snowplow analytics interface

(derive ::new-instance-created           ::account)
(derive ::new-user-created               ::account)
(derive ::invite-sent                    ::invite)
(derive ::dashboard-created              ::dashboard)
(derive ::question-added-to-dashboard    ::dashboard)
(derive ::database-connection-successful ::database)
(derive ::database-connection-failed     ::database)

(defn track-event!
  "Send a single analytics event to the Snowplow collector, if tracking is enabled for this MB instance and a collector
  is available."
  [event-kw & [user-id data]]
  (when (and (public-settings/anon-tracking-enabled) (snowplow-available))
    (try
      (let [schema (-> event-kw parents first)
            ^Unstructured$Builder builder (-> (. Unstructured builder)
                                              (.eventData (payload schema (schema-version schema) event-kw data))
                                              (.customContext [(context)]))
            ^Unstructured$Builder builder' (set-subject builder user-id)
            ^Unstructured event (.build builder')]
        (track-event-impl! (tracker) event))
      (catch Throwable e
        (log/debug e (trs "Error sending Snowplow analytics event {0}" event-kw))))))

;; Instance creation timestamp setting.
;; Must be defined after [[track-event!]] since it sends a Snowplow event the first time the setting is read.

(defn- first-user-creation
  "Returns the earliest user creation timestamp in the database"
  []
  (:min (db/select-one [User [:%min.date_joined :min]])))

(defsetting instance-creation
  (deferred-tru "The approximate timestamp at which this instance of Metabase was created, for inclusion in analytics.")
  :visibility :public
  :type       :timestamp
  :setter     :none
  :getter     (fn []
                (when-not (db/exists? Setting :key "instance-creation")
                  ;; For instances that were started before this setting was added (in 0.41.3), use the creation
                  ;; timestamp of the first user. For all new instances, use the timestamp at which this setting
                  ;; is first read.
                  (let [value (or (first-user-creation) (java-time/offset-date-time))]
                    (setting/set-timestamp! :instance-creation value)
                    (track-event! ::new-instance-created)))
                (setting/get-timestamp :instance-creation)))
