(ns org.candelbio.alzabo.core
  (:require [org.candelbio.alzabo.candel :as candel]
            [org.candelbio.alzabo.schema :as schema]
            [org.candelbio.alzabo.config :as config]
            [org.candelbio.alzabo.html :as html]
            [org.candelbio.alzabo.output :as output]
            [org.candelbio.alzabo.datomic :as datomic]
            [org.candelbio.alzabo.datagen :as datagen]
            [org.candelbio.multitool.core :as u]
            [me.raynes.fs :as fs]
            [clojure.data.json :as json]
            )
  (:gen-class))

;;; Note: CLI use is somewhat deprecated; it's expected that Alzabo will be used
;;; more as a library.

(defn- browse-file
  [file]
  (.browse (java.awt.Desktop/getDesktop)
           (.toURI (java.io.File. file))))

;;; Will be in multitool
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn- schema
  []
  (let [schema
        (if (= (config/config :source) :candel)
          (candel/read-schema)
          (schema/read-schema (realize-rel-path @config/config-path (config/config :source))))]
    (config/set! :version (:version schema))
    schema))

;;; New config-file machinery

(defmulti do-command (fn [command args] (keyword command)))

(defmethod do-command :server
  [_ _]
  (schema)                              ;sets version
  (browse-file (config/output-path "index.html")))

(defn write-alzabo
  [schema]
  (output/write-schema schema (config/output-path "alzabo-schema.edn"))) 

(defmethod do-command :documentation
  [_ _] 
  (let [schema (schema)]
    (when (= (config/config :source) :candel)
      ;; write out derived Alzabo schemas
      (write-alzabo schema))
    (html/schema->html schema)))

(defmethod do-command :datomic
  [_ _]
  (let [schema (schema)]
    (write-alzabo schema)
    (output/write-schema (datomic/datomic-schema schema)
                         (config/output-path "datomic-schema.edn"))
    ;; ?
    #_
    (output/write-schema (candel/metamodel schema)
                         (config/output-path "metamodel.edn"))

    ))

(defmethod do-command :generate-data
  [_ args]
  (let [schema-data (schema)
        entity-counts (or (:entity-counts args) {})
        llm-enabled? (get args :llm-enabled? true)
        output-format (get args :output-format "edn")]
    (println "Generating sample data for schema...")
    (let [generated-data (datagen/generate-sample-data schema-data
                                                       :entity-counts entity-counts
                                                       :llm-enabled? llm-enabled?)]
      (case output-format
        "edn" (output/write-schema generated-data (config/output-path "sample-data.edn"))
        "json" (spit (config/output-path "sample-data.json")
                     (json/write-str generated-data))
        (println "Unknown output format:" output-format))
      (println (str "Generated sample data written to "
                    (config/output-path (str "sample-data." output-format)))))))

;;; Split out for testing
(defn main-guts
  [config command]
  (config/set-config! config)
  (do-command command {})
  )
  
;;; Note: this isn't currently used, all the params are in config
(defn keywordize-keys
  [m]
  (u/map-keys read-string m))

(defn -main
  [config command & args]
  (main-guts config command)
  (System/exit 0))

(defn main
  [config command & args]
  (main-guts config command)
  )
