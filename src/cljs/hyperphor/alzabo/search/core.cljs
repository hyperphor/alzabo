(ns hyperphor.alzabo.search.core
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [cljs.reader :as reader]
            [goog.object :as gobj]
            [hyperphor.alzabo.render :as render]
            [hyperphor.alzabo.search.graph :as graph]
            [hyperphor.alzabo.search.lexicon :as lex]))

;; Set by schema.js, see hyperphor.alzabo.html/schema-js
(defn get-schema []
  (reader/read-string (gobj/get js/window "alzaboSchema")))

(defn- single-page? []
  (some? (graph/by-id "az-detail")))

(defn- href
  "Link to a kind or enum: hash route on the index, static page elsewhere."
  [kind]
  (if (single-page?) (str "#" (name kind)) (str (name kind) ".html")))

(defn- navigate! [kind]
  (if (single-page?)
    (set! (.-hash js/location) (name kind))
    (set! (.-href js/location) (str (name kind) ".html"))))

(defn- choice-target
  "Kind or enum a search result refers to."
  [[_ target]]
  target)

;;; Highlighting follows hover, then search focus, then the selected kind

(defn- highlight-fx [{:keys [schema adjacency hover focus selected] :as db}]
  (let [kind? #(get-in schema [:kinds %])]
    {:db db
     ::highlight [adjacency (first (filter kind? [hover focus selected])) (when (kind? selected) selected)]}))

(rf/reg-fx
 ::highlight
 (fn [[adjacency center selected]]
   (graph/highlight! adjacency center selected)))

(rf/reg-fx
 ::show-detail
 (fn [kind]
   (when-let [side (.querySelector js/document ".az-side")]
     (if kind
       (.add (.-classList side) "az-has-detail")
       (.remove (.-classList side) "az-has-detail"))
     (set! (.-scrollTop side) 0))
   (when kind (graph/reveal! kind))))

(rf/reg-event-db
 :initialize
 (fn [_ _]
   (let [schema (get-schema)]
     {:user-string ""
      :schema schema
      :lexicon (lex/merged-dict schema)
      :adjacency (render/adjacency schema)
      :choices []})))

(rf/reg-event-db
 :user-string-change
 (fn [db [_ user-string]]
   (assoc db
          :user-string user-string
          :choices (lex/lookup user-string (:lexicon db))
          :cursor nil)))

(rf/reg-event-fx
 :hover
 (fn [{:keys [db]} [_ kind]]
   (highlight-fx (assoc db :hover kind))))

(rf/reg-event-fx
 :focus
 (fn [{:keys [db]} [_ kind]]
   (highlight-fx (assoc db :focus kind))))

(rf/reg-event-fx
 :move-cursor
 (fn [{:keys [db]} [_ delta]]
   (let [n (count (:choices db))]
     (if (zero? n)
       {:db db}
       (let [cursor (mod (+ (or (:cursor db) (if (pos? delta) -1 0)) delta) n)]
         (highlight-fx (assoc db
                              :cursor cursor
                              :focus (choice-target (nth (:choices db) cursor)))))))))

(rf/reg-event-fx
 :clear-search
 (fn [{:keys [db]} _]
   (highlight-fx (assoc db :user-string "" :choices [] :cursor nil :focus nil))))

(rf/reg-event-fx
 :select
 (fn [{:keys [db]} [_ kind]]
   (-> (highlight-fx (assoc db :selected kind))
       (assoc ::show-detail kind))))

(rf/reg-sub :user-string (fn [db _] (:user-string db)))
(rf/reg-sub :schema (fn [db _] (:schema db)))
(rf/reg-sub :choices (fn [db _] (:choices db)))
(rf/reg-sub :cursor (fn [db _] (:cursor db)))
(rf/reg-sub :selected (fn [db _] (:selected db)))

(defn re-substitute
  "Modeled on re-seq, but returns a list based on substrings, with subfn applied to substrings that match re"
  [re s subfn]
  (let [match-data (re-find re s)
        match-idx (.search s re)
        match-str (if (coll? match-data) (first match-data) match-data)
        post-idx (+ match-idx (max 1 (count match-str)))
        post-match (subs s post-idx)]
    (if match-data
      (lazy-seq (cons (subs s 0 match-idx)
                      (cons (subfn match-data)
                            (when (<= post-idx (count s))
                              (re-substitute re post-match subfn)))))
      (list post-match))))

(defn- regex-quote [s]
  (.replace s (js/RegExp. "[.*+?^${}()|[\\]\\\\]" "g") "\\$&"))

(defn highlight-name
  [name typed]
  (when name
    (into [] (cons :span (re-substitute (re-pattern (str "(?i)\\b" (regex-quote typed))) name (fn [match] [:b match]))))))

;;; Also used for enum links, so slightly misnamed
(defn kind-link
  [kind & [body]]
  [:a {:href (href kind)
       :on-click #(rf/dispatch [:clear-search])}
   (or body
       (name kind))])

(defn render-item
  [[type & params] user-string]
  (case type
    kind [:div "Kind " (kind-link (first params) (highlight-name (name (first params)) user-string))]
    prop [:div "Property " (highlight-name (name (second params)) user-string) " of kind "
          (kind-link (first params))
          [:div.doc (highlight-name (get-in @(rf/subscribe [:schema]) [:kinds (first params) :fields (second params) :doc]) user-string)]]
    enum [:div "Enumeration " (kind-link (first params) (highlight-name (name (first params)) user-string))]
    enum-value [:div "Value "
                (highlight-name (name (second params)) user-string)
                " (" (highlight-name (str (nth params 2)) user-string)
                ") of enumeration "
                (kind-link (first params))]))

(defn- on-search-key [e]
  (case (.-key e)
    "ArrowDown" (do (.preventDefault e) (rf/dispatch [:move-cursor 1]))
    "ArrowUp" (do (.preventDefault e) (rf/dispatch [:move-cursor -1]))
    "Escape" (rf/dispatch [:clear-search])
    "Enter" (let [choices @(rf/subscribe [:choices])
                  choice (nth choices (or @(rf/subscribe [:cursor]) 0) nil)]
              (when choice
                (rf/dispatch [:clear-search])
                (navigate! (choice-target choice))))
    nil))

(defn ui
  []
  [:div.az-search
   [:div
    "search: "
    [:input {:value @(rf/subscribe [:user-string])
             :on-key-down on-search-key
             :on-change (fn [e]
                          (rf/dispatch
                           [:user-string-change (-> e .-target .-value)]))}]]
   (let [user-string @(rf/subscribe [:user-string])
         choices @(rf/subscribe [:choices])
         cursor @(rf/subscribe [:cursor])]
     [:div.popup
      [:div.popuptext {:style {:visibility (if (empty? user-string) "hidden" "visible")}
                       :on-mouse-leave #(rf/dispatch [:focus nil])}
       (if (empty? choices)
         [:div [:h3 "No results"]]
         [:table
          [:tbody
           (for [[i def] (map-indexed vector choices)]
             ^{:key def}
             [:tr {:class (when (= i cursor) "az-cursor")
                   :on-mouse-enter #(rf/dispatch [:focus (choice-target def)])}
              [:td (render-item def user-string)]])]])]])])

(defn detail-pane
  []
  (let [selected @(rf/subscribe [:selected])
        schema @(rf/subscribe [:schema])
        ctx {:schema schema :href #(str "#" (name %)) :index-href "#"}]
    (cond (get-in schema [:kinds selected]) (render/kind-page ctx selected)
          (get-in schema [:enums selected]) (render/enum-page ctx selected)
          :else nil)))

(defn- hash-kind
  "Kind or enum named by the location hash, if it names one."
  [schema]
  (let [h (subs (.-hash js/location) 1)
        k (when (seq h) (keyword (js/decodeURIComponent h)))]
    (when (or (get-in schema [:kinds k]) (get-in schema [:enums k]))
      k)))

(defn- install-index!
  "Wire the graph and kind lists on the index page to re-frame: hover highlighting, hash-routed clicks."
  []
  (let [svg (graph/by-id "az-graph")
        lists (.querySelector js/document ".az-lists")
        hovered (atom nil)
        hover! (fn [kind] (when (not= kind @hovered)
                            (reset! hovered kind)
                            (rf/dispatch [:hover kind])))
        route! #(rf/dispatch [:select (hash-kind @(rf/subscribe [:schema]))])]
    (when svg
      (graph/install-pan-zoom! svg)
      (.addEventListener svg "mouseover" #(hover! (graph/element-kind (.-target %))))
      (.addEventListener svg "mouseleave" #(hover! nil))
      (.addEventListener svg "click"
                         (fn [e]
                           (when-let [kind (graph/element-kind (.-target e))]
                             (.preventDefault e)
                             (navigate! kind)))))
    (when lists
      (.addEventListener lists "mouseover"
                         (fn [e]
                           (let [kind (graph/element-kind (.-target e))]
                             (when (and kind (not= kind @hovered)) (graph/reveal! kind))
                             (hover! kind))))
      (.addEventListener lists "mouseleave" #(hover! nil))
      (.addEventListener lists "click"
                         (fn [e]
                           (when-let [a (.closest (.-target e) "a.kind[data-kind]")]
                             (.preventDefault e)
                             (navigate! (keyword (.getAttribute a "data-kind")))))))
    (.addEventListener js/window "hashchange" route!)
    (reagent/render [detail-pane] (graph/by-id "az-detail"))
    (route!)))

(defn ^:export run
  []
  (rf/dispatch-sync [:initialize])
  (reagent/render [ui] (graph/by-id "app"))
  (when (single-page?)
    (install-index!)))
