(ns hyperphor.alzabo.render
  "Kind/enum page bodies as hiccup data, shared by static pages (clj) and the single-page pane (cljs).
  Also the graph DOM contract (element ids) and graph edges/adjacency derived from a schema."
  (:require [hyperphor.alzabo.schema :as schema]
            [clojure.string :as s]))

;;; Graph DOM contract, see design/alzabo-ui-revamp.md §4.1

(defn node-id [kind] (str "k__" (name kind)))
(defn rel-id [from field to] (str "r__" (name from) "__" (name field) "__" (name to)))
(defn isa-id [child parent] (str "isa__" (name child) "__" (name parent)))
(defn category-class [category] (str "cat__" (name category)))

(defn kind-relations
  "[field target cardinality] for each field of `kind` whose type is a kind; tuple components become field0, field1..."
  [kind {:keys [kinds]}]
  (filter (fn [[_ type _]]
            (get kinds type))
          (mapcat (fn [[fname {:keys [cardinality type]}]]
                    (if (vector? type)
                      (map (fn [tuple-component idx]
                             [(keyword (str (name fname) idx)) tuple-component cardinality])
                           type (range))
                      (list [fname type cardinality])))
                  (get-in kinds [kind :fields]))))

(defn graph-edges
  "Graph edges of `schema` as maps of :id :class (\"rel\" or \"isa\") :from :to, plus :field :cardinality on relations."
  [{:keys [kinds] :as schema}]
  (concat
   (for [kind (keys kinds)
         [field ref cardinality] (kind-relations kind schema)]
     {:id (rel-id kind field ref) :class "rel" :from kind :to ref :field field :cardinality cardinality})
   (for [kind (keys kinds)
         parent (schema/get-parents schema kind)]
     {:id (isa-id kind parent) :class "isa" :from kind :to parent})))

(defn adjacency
  "{kind {:kinds #{neighbor...} :edges #{edge-id...}}} over `graph-edges`."
  [schema]
  (let [add (fn [m k other id]
              (-> m
                  (update-in [k :kinds] (fnil conj #{}) other)
                  (update-in [k :edges] (fnil conj #{}) id)))]
    (reduce (fn [acc {:keys [id from to]}]
              (-> acc (add from to id) (add to from id)))
            {}
            (graph-edges schema))))

;;; Rendering. `ctx` is {:schema :href (fn [kind-or-enum] url) :index-href url}.

(defn- txt
  [x]
  (if (keyword? x) (name x) (str x)))

(def ^:private url-re #"https?:\S*[^\s.,;:)]")
(def ^:private linkify-re #"https?:\S*[^\s.,;:)]|(?:(?!https?:\S*[^\s.,;:)])[\s\S])+")

(defn linkify
  "Seq of strings and [:a] elements for the URLs in `s`."
  [s]
  (when s
    (for [tok (re-seq linkify-re (str s))]
      (if (re-matches url-re tok) [:a {:href tok} tok] tok))))

(defn kind-link
  [{:keys [schema href]} kind]
  [:a.kind {:href (href kind) :data-kind (name kind)}
   (when-let [icon (get-in schema [:kinds kind :icon])] (str icon " "))
   (name kind)])

(defn type-html
  [ctx type]
  (cond (vector? type) (-> [:span "["]
                           (into (interpose " " (map #(type-html ctx %) type)))
                           (conj "]"))
        (keyword? type) (if (get schema/primitives type)
                          (name type)
                          (kind-link ctx type))
        :else (pr-str type)))

(defn field-spec-html
  [ctx {:keys [type min max]}]
  (if (schema/numeric-primitives type)
    (str (name type) (when min (str [min max])))
    (type-html ctx type)))

(defn- boold [b] (if b "yes" ""))

(def kind-columns
  [{:heading "attribute" :display (fn [_ p] [:span.nowrap (txt (:id p))])}
   {:heading "type" :display field-spec-html}
   {:heading "cardinality" :display (fn [_ p] (some-> (:cardinality p) txt))}
   {:heading "unique?" :display (fn [_ p] (boold (:unique? p)))}
   {:heading "required?" :display (fn [_ p] (boold (:required? p)))}
   {:heading "doc" :display (fn [_ p] (into [:span] (linkify (:doc p))))}
   {:heading "examples" :display (fn [_ p]
                                   (when-let [examples (seq (:examples p))]
                                     [:span.examples (s/join ", " examples)]))}])

(def inverse-columns
  [{:heading "from" :display field-spec-html}
   {:heading "attribute" :display (fn [_ p] [:span.nowrap (txt (:id p))])}
   {:heading "cardinality" :display (fn [_ p] (some-> (:cardinality p) txt))}
   {:heading "required?" :display (fn [_ p] (boold (:required? p)))}
   {:heading "doc" :display (fn [_ p] (into [:span] (linkify (:doc p))))}])

(defn- field-table
  [ctx columns fields]
  [:table.table
   [:thead (into [:tr] (for [col columns] [:th (:heading col)]))]
   (into [:tbody]
         (for [[field props] (sort-by (comp txt first) fields)]
           (into [:tr] (for [col columns]
                         [:td ((:display col) ctx (assoc props :id field))]))))])

(defn- backlink
  [{:keys [index-href]}]
  [:a.az-back {:href index-href} "← schema"])

(defn kind-page
  [{:keys [schema] :as ctx} kind]
  (let [{:keys [unique-id label doc icon]} (get-in schema [:kinds kind])
        parents (schema/get-parents schema kind)
        inherited (schema/inherited-fields schema kind)
        all-fields (schema/all-fields schema kind)]
    [:div.container.az-page
     (backlink ctx)
     [:h1 (when icon [:span.az-icon icon]) (name kind)]
     (when doc (into [:div.kind_doc] (linkify doc)))
     (when (seq parents)
       (-> [:div.az-extends [:b "Extends: "]]
           (into (interpose ", " (map #(kind-link ctx %) parents)))))
     [:h3 "Fields"]
     (field-table ctx kind-columns
                  (for [[field props] all-fields]
                    [field (if (contains? inherited field)
                             (update props :doc #(str (or % "") " [inherited]"))
                             props)]))
     [:h3 "Inverse Relations"]
     (field-table ctx inverse-columns (get (schema/inverse-fields schema) kind))
     (when unique-id [:div [:b "Unique ID"] ": " (txt unique-id)])
     (when label [:div [:b "Label"] ": " (txt label)])]))

(defn enum-page
  [{:keys [schema] :as ctx} enum]
  (let [{:keys [values doc]} (get-in schema [:enums enum])]
    [:div.container.az-page
     (backlink ctx)
     [:h1 (name enum)]
     (when doc (into [:div.kind_doc] (linkify doc)))
     [:table.table
      [:thead [:tr [:th "values"] [:th "doc"]]]
      (into [:tbody] (for [[v vdoc] values] [:tr [:td (txt v)] [:td (some-> vdoc txt)]]))]]))
