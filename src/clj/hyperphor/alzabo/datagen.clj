(ns hyperphor.alzabo.datagen
  "Generate example data for Alzabo schemas"
  (:require [hyperphor.alzabo.schema :as schema]
            [hyperphor.multitool.core :as u]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hyperphor.alzabo.llm :as llm])
  (:import [java.time LocalDate LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

;;; Core data generation framework

;;; TODO I hate this, just use an atom or pass an arg. Also not really used. 
(def ^:dynamic *generation-context*
  "Context for data generation including generated entities and configuration"
  {:entities {}           ; Map of kind -> [generated entities]
   :entity-counts {}      ; Map of kind -> target count
   :relationships {}      ; Map of [from-kind to-kind] -> [(from-id to-id)]
   :llm-enabled? true
   :seed 42})

(defn reset-context!
  "Reset the generation context"
  [config]
  (alter-var-root #'*generation-context*
                  (constantly (merge {:entities {}
                                      :entity-counts {}
                                      :relationships {}
                                      :llm-enabled? true
                                      :seed 42}
                                     config))))

(defn add-entity!
  "Add a generated entity to the context"
  [kind entity]
  (alter-var-root #'*generation-context*
                  update-in [:entities kind] (fnil conj []) entity))

(defn get-entities
  "Get all entities of a given kind"
  [kind]
  (get-in *generation-context* [:entities kind] []))


;;; TODO need a better mechanism and should be in schema
(defn object-label
  [obj]
  (or (:name obj)
      (:title obj)))

(defn context-string
  [object]
  (u/expand-template
   "related to the {{kind}} {{label}}"
   (-> object
       (assoc :label (object-label object)))))

(defn add-kind
  [kind obs]
  (map #(assoc % :kind kind) obs))

;;; Data sometimes is wrapped in extraneous stuff
(defn regularize
  [x]
  (if (and (sequential? x)
           (map? (first x)))
    x
    (when (sequential? x)
      (u/some-thing regularize x))))

;;; TODO fix ::keywords
(defn generate-entities
  "Ask LLM to generate entities based on schema.
  context is a related object (eg a Band for generating Songs)
  kind-modifier is text to add to kind/description (eg \"fictional\") 
  "
  [kind schema & {:keys [count context kind-modifier] :as params}]
  (let [params (merge  {:count 10 :kind-modifier ""} params)
        sdef (schema/kind-def schema kind)]
    (assert sdef "Kind not found in schema")
    (->> (u/expand-template
                     "Please give me a list of {{count}} {{kind-modifier}} {{id}} {{description}} {{context-string}} as a list of maps in json format. For each, include the following fields: {{field-list}}. Return a json-formatted list of entities, with no extraneous text"
                     (-> sdef
                         (merge params)
                         (assoc :field-list (str/join ", " (map name (keys (:fields sdef)))))
                         (assoc :context-string (if context (context-string context) ""))
                         ))
        llm/json-query
        ;; TODO clean format (or use structured response), keys, turn "" to nil
        regularize
        (add-kind kind)
        )))
                             
(comment
  (generate-entities :Genre schema))


;;; High-level generation orchestration

(defn determine-generation-order
  "Determine the order to generate entities based on dependencies"
  [schema]
  ;; For now, use a simple heuristic: reference entities first, then others
  (let [kinds (keys (:kinds schema))
        reference-kinds (filter #(get-in schema [:kinds % :reference?]) kinds)
        non-reference-kinds (filter #(not (get-in schema [:kinds % :reference?])) kinds)]
    (concat reference-kinds non-reference-kinds)))

(defn generate-sample-data
  "Generate sample data for an entire schema"
  [schema & {:keys [entity-counts llm-enabled? seed]
             :or {entity-counts {}
                  llm-enabled? true
                  seed 42}}]
  (reset-context! {:llm-enabled? llm-enabled? :seed seed})

  ;; Set default entity counts
  (let [default-counts (into {} (map (fn [kind] [kind 5]) (keys (:kinds schema))))
        final-counts (merge default-counts entity-counts)]

    ;; Generate entities in dependency order
    (doseq [kind (determine-generation-order schema)]
      (let [kind-def (get-in schema [:kinds kind])
            count (get final-counts kind 0)]
        (when (> count 0)
          (println (str "Generating " count " " (name kind) " entities..."))
          (doseq [entity (generate-entities kind kind-def schema count)]
            (add-entity! kind entity)))))

    ;; Return the generated data
    (:entities *generation-context*)))

