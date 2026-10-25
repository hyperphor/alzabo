(ns hyperphor.alzabo.import.turtle
  (:require [hyperphor.multitool.cljcore :as ju]
            [hyperphor.alzabo.schema :as schema])
  (:import [org.apache.jena.rdf.model Model Resource RDFNode Statement]
           [org.apache.jena.riot RDFDataMgr]
           [org.apache.jena.vocabulary RDF RDFS OWL XSD]))

;;; Convert RDFS/OWL ontologies (Turtle, or anything else Jena reads) to Alzabo schemas.
;;; Requires org.apache.jena/jena-arq on the classpath (a :provided dependency of alzabo).
;;; Mapping:
;;;   rdfs:Class / owl:Class           -> kind; rdfs:subClassOf (to classes defined here) -> :extends
;;;   rdf:Property / owl:*Property     -> field on each rdfs:domain class (incl. owl:unionOf); no domain -> dropped
;;;   rdfs:range                       -> xsd primitive, or a kind; classes not defined here -> :string
;;;   owl:FunctionalProperty           -> :cardinality :one, otherwise :many (RDF properties are multi-valued)
;;;   rdfs:comment (else rdfs:label)   -> :doc

(defn- local-name
  [^Resource r]
  (let [uri (.getURI r)]
    (or (second (re-find #"[#/:]([^#/:]+)$" uri)) uri)))

(defn- ->key
  [^Resource r]
  (keyword (local-name r)))

(defn- objects
  [^Model model ^Resource subject property]
  (map (fn [^Statement st] (.getObject st))
       (iterator-seq (.listStatements model subject property nil))))

(defn- subjects-of-type
  [^Model model type]
  (map (fn [^Statement st] (.getSubject st))
       (iterator-seq (.listStatements model nil RDF/type ^RDFNode type))))

(defn- named
  "Filter to URI resources (drops blank nodes and literals)."
  [nodes]
  (filter #(.isURIResource ^RDFNode %) nodes))

(defn- rdf-list
  [^RDFNode node]
  (when (.canAs node org.apache.jena.rdf.model.RDFList)
    (iterator-seq (.iterator ^org.apache.jena.rdf.model.RDFList
                   (.as node org.apache.jena.rdf.model.RDFList)))))

(defn- expand-unions
  "Expand blank-node owl:unionOf class expressions into their member classes."
  [model nodes]
  (mapcat (fn [^RDFNode n]
            (if (.isAnon n)
              (mapcat rdf-list (objects model (.asResource n) OWL/unionOf))
              [n]))
          nodes))

(defn- doc
  [model subject]
  (when-let [lit (first (filter #(.isLiteral ^RDFNode %)
                                (concat (objects model subject RDFS/comment)
                                        (objects model subject RDFS/label))))]
    (.getLexicalForm (.asLiteral ^RDFNode lit))))

(def ^:private xsd-primitives
  (merge
   (zipmap ["string" "normalizedString" "token" "language" "anyURI" "Name" "NCName"] (repeat :string))
   (zipmap ["integer" "int" "long" "short" "byte" "nonNegativeInteger" "positiveInteger"
            "negativeInteger" "nonPositiveInteger" "unsignedInt" "unsignedLong"
            "unsignedShort" "unsignedByte"]
           (repeat :long))
   (zipmap ["decimal" "double" "float"] (repeat :float))
   (zipmap ["date" "dateTime" "dateTimeStamp" "time" "gYear" "gYearMonth"] (repeat :instant))
   {"boolean" :boolean}))

(defn- range-type
  [^Resource r class-keys]
  (let [uri (.getURI r)]
    (cond (.startsWith uri XSD/NS) (get xsd-primitives (local-name r) :string)
          (contains? class-keys (->key r)) (->key r)
          :else :string)))

(defn- classes
  [model]
  (->> [RDFS/Class OWL/Class]
       (mapcat #(subjects-of-type model %))
       named
       distinct))

(defn- properties
  [model]
  (->> [RDF/Property OWL/ObjectProperty OWL/DatatypeProperty OWL/FunctionalProperty]
       (mapcat #(subjects-of-type model %))
       named
       distinct))

(defn- property-field
  [model ^Resource prop class-keys]
  (let [ranges (named (expand-unions model (objects model prop RDFS/range)))
        types (map #(range-type % class-keys) ranges)
        ;; Prefer a kind reference over a primitive when there are multiple ranges
        type (or (first (filter class-keys types)) (first types) :string)
        functional? (.contains ^Model model prop RDF/type ^RDFNode OWL/FunctionalProperty)]
    (cond-> {:type type
             :cardinality (if functional? :one :many)}
      (doc model prop) (assoc :doc (doc model prop)))))

(defn- ontology-title
  [model]
  (some #(doc model %) (subjects-of-type model OWL/Ontology)))

(defn model->schema
  "Convert a Jena Model holding an RDFS/OWL ontology into an Alzabo schema map."
  [^Model model]
  (let [classes (classes model)
        class-keys (set (map ->key classes))
        kinds (into {}
                    (for [c classes
                          :let [k (->key c)
                                parents (->> (objects model c RDFS/subClassOf)
                                             named
                                             (map ->key)
                                             (filter class-keys)
                                             (remove #{k})
                                             distinct
                                             vec)]]
                      [k (cond-> {:fields {} :uri (.getURI ^Resource c)}
                           (doc model c) (assoc :doc (doc model c))
                           (seq parents) (assoc :extends (if (= 1 (count parents)) (first parents) parents)))]))
        kinds (reduce (fn [kinds prop]
                        (let [field (property-field model prop class-keys)
                              domains (->> (objects model prop RDFS/domain)
                                           (expand-unions model)
                                           named
                                           (map ->key)
                                           (filter class-keys)
                                           distinct)]
                          (reduce #(assoc-in %1 [%2 :fields (->key prop)] field) kinds domains)))
                      kinds
                      (properties model))
        title (ontology-title model)]
    (cond-> {:version "1.0" :kinds kinds}
      title (assoc :title title))))

(defn ttl->schema
  "Read an ontology file (Turtle, or any RDF syntax Jena recognizes by extension) into an Alzabo schema."
  [file]
  (-> (RDFDataMgr/loadModel (str file))
      model->schema
      schema/validate-schema))

;;; Convert ontology file to Alzabo schema file
(defn convert
  [ttl alzabo]
  (->> ttl
       ttl->schema
       (ju/schppit alzabo)))

#_
(convert "test/resources/turtle/bible-mini.ttl" "/tmp/bible-mini.edn")
