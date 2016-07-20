(ns vide.components
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [clojure.string :as string]
            [reagent.core :as reagent :refer [atom]]
            [clojure.walk :as w]
;;             [reanimated.core :as anim]
            [cljs.reader :refer [read-string]]
            [clojure.set :refer [rename-keys]]
;;             [slurp.core :include-macros true :refer [slurp]]
            [vide.helpers :refer [try-eval try-read do-prn drop-nth find-indices first? firstx]]
            [vide.drawer :refer [childless? get-best-layers coords-from-layers get-activated]]
            [vide.parser :refer [parse-defn-let]]
            [vide.parser2 :refer [model-pipeline parse-defn]]
            ))
;; ----------------------------------------style params----------------------------------------------

(enable-console-print!)

(def node-h 9)
(def node-w 12)
(def spacing-right 22)
(def spacing-down 18)
(def font-size 2)
(def same-edge-spacing (* 0.3 node-w))
(def arrowhead-angle (/ 6.283 20))
(def arrowhead-l (* 0.12 spacing-down))
(def node-style {:fill "white"
                   :stroke "orange"
                   :stroke-width 0.8
                   :opacity 1})
(def node-text "black")
(def arrow-fill "black")

;; ---------------------------------------- node definitions ----------------------------------------------

(def node-defs-atom (atom {}))
(def pretend-code
  (str
    "(defn square [x] (let [y (* x x)] y))"

    "(defn inc-vec [myvec] (let [incd (map inc myvec)] incd))"

;;     "(defn square-vec [myvec] (let [squared (map square myvec)] squared))"
    ;;     "(defn dupe [x] (let [duped [x x]] duped))"

    "(defn incincinc [x] (let [y (+ x x) \n
    a (inc y) \n
    b (inc a) \n
    c (inc b) \n] c))"

    "(defn higher [x y z] (let [a (+ x y)
                           b (repeat 5 z)
                           c  (conj b a)
                           d (map square c)
                           e (reduce + d)
                           u (identity e)] u))"

    "(defn fact [prod n] (if (= n 1)
                                   prod
                                   (let [prod-new  (* prod n)
                                         n-new (dec n)]
                                     (fact prod-new n-new))))"))

(defn load-node-defs []
  (let [code pretend-code
        forms  (read-string (str \( pretend-code \) ))
        defn-let-forms (filter #(= 'defn (first %)) forms) ;; for now this should be all there is
        names (map #(str (second %)) defn-let-forms)
        codes (map str defn-let-forms) ;;that name lol
        compiled-codes  (map try-read codes)
        evald-codes (map try-eval (map #(cons 'fn %) (map #(drop 1 %) compiled-codes)))
        defs (map #(hash-map :code %1 :fn %2) codes
                  evald-codes)
        node-defs (zipmap names defs)]
    (reset! node-defs-atom node-defs)))


(load-node-defs)
;; (do-prn ((get-in @node-defs-atom ["square" :fn]) 2))

;; ---------------------------------------- node history ----------------------------------------------

(def node-history-atom (atom '()))

;; ---------------------------------------- codemirror state ----------------------------------------------

(def cm-atom (atom (js/CodeMirror.
                     (.createElement js/document "div")
                     (clj->js {:mode "clojure"
                               ;;                        :lineNumbers true
                               ;;                        :theme "pastel-on-dark"
                               :cursorHeight 0.85
                               :lineWrapping true
                               :autoMatchParens true
                               }))))
(defn on-cm-update []
  (fn [this]
    (when-let [current-node (first @node-history-atom)]
      (let [new-text (.getValue this)

            compiled (try-read new-text)
            ;;                   [_ func-name args let-form] value
            ;;                   new-node-history (-> @node-history-atom
            ;;                                        (drop-last)
            ;;                                        (vec)
            ;;                                        (conj (str func-name)))
            evald (try-eval (cons 'fn (drop 1  compiled)))
            ]
        (swap! node-defs-atom #(assoc-in % [current-node :code] new-text))
        (when evald (swap! node-defs-atom #(assoc-in % [current-node :fn] evald)))
        (when-let [graph (parse-defn-let (try-read new-text))]
            (do
              (swap! node-defs-atom #(assoc-in % [current-node :graph] graph))
              (doseq [node-name (map :name (vals graph))]
                (when-not #(some #{node-name} %) (keys @node-defs-atom)
                  (swap! node-defs-atom #(assoc-in % [node-name :fn] (try-read node-name)))
                  (prn "added " node-name " to node-defs"))
                ))
          ))
      ;;                                         (reset! node-defs-atom
      ;;                                                 (rename-keys new-node-defs {current-node (str func-name)}))
      ;;                                         (reset! node-history-atom new-node-history))
      )))
(.on @cm-atom
     "change"
     (on-cm-update))
(.setSize @cm-atom "100%" "100%")
(.refresh @cm-atom)

;; ---------------------------------------- given edge values ----------------------------------------------

(def given-values-atom (atom {}))

;; -----------------------------------------functions---------------------------------------------

(defn next-graph [node]
  (do
    (swap! node-history-atom #(conj (remove #{node} %) node))
    (.setValue @cm-atom (get-in @node-defs-atom [node :code]))
    @node-history-atom))

(defn prev-graph []
  (do
    (swap! node-history-atom #(drop 1 %))
    (if-let [current-node (first @node-history-atom)]
      (.setValue @cm-atom (get-in @node-defs-atom [current-node :code]))
      (.setValue @cm-atom ""))))

(defn update-editor [wrapper-id]
  (fn [this]
    (when @cm-atom
      (when-let [node (or (js/document.getElementById wrapper-id)
                          (reagent/dom-node this))]
        (.appendChild node (.getWrapperElement @cm-atom))
        ))))

;; ----------------------------------------basic views----------------------------------------------

(defn node-view [x y node uuid]
  ;; appends the node idx to the focus path to get the correct node
  [:g
   {:on-click (when (@node-defs-atom node)
                #(next-graph node))
    :key uuid}
   [:rect {:width node-w
           :height node-h
           :x x
           :y y
           :style {:fill "white"
                   :stroke "orange"
                   :stroke-width 0.8
                   :opacity 1}}]
   [:text {:x (+ x (* 0.35 font-size))
           :y (+ y (* 1.15 font-size))
           :style {:fill node-text
                   :font-size font-size
                   :font-family "Ubuntu"}}
    node]])

(defn arrowhead-view [x y th col]
  (let [p1 (str x "," y)
        a2 (+ th arrowhead-angle)
        a3 (- th arrowhead-angle)
        x2 (+ x (* arrowhead-l (.sin js/Math a2)))
        y2 (+ y ( * arrowhead-l (.cos js/Math a2)))
        p2 (str x2 "," y2)
        x3 (+ x (* arrowhead-l (.sin js/Math a3)))
        y3 (+ y ( * arrowhead-l (.cos js/Math a3)))
        p3 (str x3 "," y3)]
    [:polygon {:points (str p1 " " p2 " " p3)
               :style {:fill col
                       :opacity 1}}]))

(defn button-view [text callback]
  [:button {:style {:position "absolute"
                    :top 10
                    :left 10
                     :background-color "#f44336"
                    :border "solid #f44336"
                    :border-radius "2px"
                    :color "white"
                    :text-align "center"
                    :text-decoration "none"
                    :display "inline-block"
                    :font-size 14
                    :padding "5px 16px"
                    :font-family "Ubuntu"}
            :on-click callback} text])

(defn dropdown-node [node]
  [:option {:key (gensym "dropdown-")
            :style {:outline "none"}} node])

;; ---------------------------------------- composite views ----------------------------------------------

(defn nodes-dropdown []
  [:select
   {:id "node-select"
    :on-change #(let [node (-> % .-target .-value)]
                  (next-graph node))
    :style {:list-style-type "none"
            :position "absolute"
            :right 10
            :top 10
            :outline "none"
            :background-color "orange"
            :border "solid orange"
            :border-radius "2px"
            :color "white"
            :text-align "left"
            :text-decoration "none"
            :display "inline-block"
            :font-family "Ubuntu"
            ;;             :width "40%"
            :font-size "14px"
            :padding "5px 16px"}}

   (conj (for [node (keys @node-defs-atom)]
           (dropdown-node node))
         [:option {:key "select-label"} "Choose node"])])

(defn edge-freq-view [x1 y1 x2 y2 freq label start]
  (let [bend-y (+ y1 (* 0.2 spacing-down))
        n-spaces (dec freq)
        left-adjust  (* -1 0.5 n-spaces same-edge-spacing)
        col "black"
        word-len (* -0.6 font-size (+ 0.7  (count label)))
;;         value (reaction (@given-values-atom start))
        col (if (get-in @given-values-atom [start :compiled]) "#00ffff" arrow-fill)
        id (gensym (str "edge-"))]
    [:g {:key id
         :id id
         }
     [:line {:x1 x1
             :y1 y1
             :x2 x1
             :y2 bend-y
             :style {:stroke col
                     :stroke-linecap "round"
                     :stroke-width 0.4
                     :opacity 1}}]

     (for [i (range freq)]
       (let [end-x (+ left-adjust x2 (* i same-edge-spacing))
              arrowhead-x (+ (* 0.3 x1)     (* 0.7 end-x))
             arrowhead-y (+ (* 0.3 bend-y) (* 0.7 y2))
             th (+ 3.1416 (.atan js/Math (/ (- end-x x1) (- y2 bend-y))))]
         [:g {:key (gensym (str id "-end-"))}
          [:line {:x1 x1
                  :y1 bend-y
                  :x2 end-x
                  :y2 y2
                  :style {:stroke col
                          :stroke-width 0.4
                          :opacity 1}}]
          (arrowhead-view arrowhead-x arrowhead-y th col)]))

     (when label
       [:text {:x (+ x1 word-len)
               :y (+ (* 0.83 bend-y) (* 0.17 y1))
               :style {:font-size font-size
                       :font-family "Ubuntu"
                       :color col}}
        ;;         (str label " " value)
        label
        ])]))

(defn get-freq-info [edge-freq layers graph-height graph-width]
  (let [[{start :start end :end label :label} freq] edge-freq
        [x1-rel y1-rel] (first (coords-from-layers start layers))
        [x2-rel y2-rel] (first (coords-from-layers end layers))
        x1 (-> x1-rel
               (* spacing-right)
               (+  (* 0.5 node-w))
               )
        y1 (-> y1-rel
               (* spacing-down)
               (+  node-h)
               )
        x2 (-> x2-rel
               (* spacing-right)
               (+ (* 0.5 node-w))
               )
        y2 (-> y2-rel
               (* spacing-down)
               )
        ;;         value (activated start)
        ]
    [x1 y1 x2 y2 freq label start]))

(defn get-literal-nodes [graph]
  (->> graph
       (filter (fn [[uuid {node-name :name edges-in :edges-in}]]
                 (and
                   (empty? edges-in) ;; you cannot have parents if you are literal
                   (or (@node-defs-atom node-name)
                       (not (symbol? (try-read node-name))))
                   )))
       (into {}))

  )


(defn get-family [ancs descs graph]
  (let [anc-parents  (->> (map graph ancs)
                                  (map :edges-in)
                                  (apply concat)
                                  (map :start)
                                  (distinct))
        desc-children  (->> graph
                                    (filter (fn [[uuid {node-name :name edges-in :edges-in}]]
                                              (some (set descs) (map :start edges-in))))
                                    (map first))
        ancs-new (distinct (concat ancs anc-parents))
        descs-new (distinct (concat descs desc-children))]
    (if (and (= ancs-new ancs)
             (= descs-new descs))
      (distinct (concat ancs descs))
      (recur ancs-new descs-new graph))))

(defn give-value [start input graph]
  (let [read-value  (try-read input)
        literal-nodes  (get-literal-nodes graph)
        invalidated-uuids (get-family [start] [start] graph)]

    (swap! given-values-atom #(apply (partial dissoc %) invalidated-uuids))
    (swap! given-values-atom #(assoc %
                                start
                                {:raw input :compiled read-value}))
    (doseq [[uuid {node-name :name edges-in :edges-in}] literal-nodes]
      (swap! given-values-atom
             #(assoc %
                uuid
                {:raw nil
                 :compiled  (or (get-in @node-defs-atom [node-name :fn]
                               (try-read node-name)))})))
    (let [
           given-values (zipmap (keys @given-values-atom)
                                (map :compiled (vals @given-values-atom)))
           activated  (get-activated graph given-values node-defs-atom)]
      (doseq [[uuid value] activated]
        (swap! given-values-atom #(assoc-in % [uuid :compiled] value))))))

(defn input-view [x y start graph graph-width graph-height]
  (let [signal (reaction (@given-values-atom start))
        value (str (or (:compiled @signal) (:raw @signal)))
        graph-view  (js/document.getElementById "graph-view")
        parent-height (.-clientHeight graph-view)
        parent-width (.-clientWidth graph-view)
        ratio-w (/ parent-width graph-width)
        ratio-h (/ parent-height graph-height)]
    [:input {:type "text"
             :key start
             :on-change
             (fn [this ]
               (let [input (-> this .-target .-value)]
                 (give-value start input graph)))
             :value  value
             :style {
                      :position "absolute"
                      :left  (* (- x (+ (* 0.5 (- node-w spacing-right)))) ratio-w)
                      :top   (* (- y (+ (* -0.05 spacing-down ) (* 0.5 (- node-h spacing-down)))) ratio-h)
                      :width (* ratio-h font-size 2.4)
                      :height (* ratio-h font-size 0.4)
                      :font-size (* ratio-h font-size 0.5)
                      }}]))


(defn if-view [model]
  (let [{:keys [height width syms-used head pred then else]} model]
    [:g
     [:text (str head)]
     pred
     then
     else]))
(defn let-view [model]
  (let [{:keys [height width syms-used head bindings]} model]
    [:g
     [:text (str head)]
     bindings]))
(defn basic-view [model]
  (let [{:keys [height width syms-used head forms]} model]
    [:g
     [:text (str head)]
     forms]))



;; (defn assoc-coords [model]
;;   (->> model
;;     (#(assoc % :x 0 :y 0)) ;; make the outermost group have coords 0 0
;;     (w/prewalk
;;     (fn [inner-model]
;;       (if (and (map? inner-model)
;;                (:model inner-model))
;;         (let [{:keys [height width syms-used head x y]} inner-model]
;;           (cond
;;             (= head if )
;;             (let [pred-height (get-in inner-model [:pred :height])
;;                   pred-width (get-in inner-model [:pred :width])
;;                   pred-x (+ x (* 0.5 width) (* -0.5 pred-width))
;;                   then-height (get-in inner-model [:then :height])
;;                   then-width (get-in inner-model [:then :width])
;;                   else-height (get-in inner-model [:else :height])
;;                   else-width (get-in inner-model [:else :width])]
;;               (-> inner-model
;;                   (assoc-in [:pred :x] (x (* 0.5 width) pred-width)) ;; pred is horizontally center
;;                   (assoc-in [:pred :y] 0)) ;; pred is top

;;             (= head let)
;;             (let-view inner-model)

;;             (ifn? head)
;;             (basic-view inner-model)))
;;         inner-model)
;;                )))))

(defn graph-view2 [current-node]
  (let [code  (get-in @node-defs-atom [@current-node :code])
        form (try-read code)
        model (parse-defn form)]
    (->>  model
          ;;       (#(assoc ))
          (w/postwalk
            (fn [inner-model]
              (if (and (map? inner-model)
                       (:connected inner-model))
                (let [{:keys [height width syms-used head x y]} inner-model]
                  (cond
                    (= head 'if )
                    (if-view inner-model)

                    (= head 'let)
                    (let-view inner-model)

                    (ifn? head)
                    (basic-view inner-model)))
                inner-model
                )))
          (conj
              [:svg {:width "100%"
                     :height "100%"
;;                      :view-box (str  0 " "
;;                                      0 " "
                     ;;                                      width " "
                     ;;                                      height)
                     :style {:position "absolute"}}
               ])
          (conj [:div {:id "graph-view"
                       :style {:height "85%"
                               :position "relative"
                               :z-index 1}}])
          (do-prn )
          )))


;; (defn graph-view [current-node]
;;   (let [graph  (reaction (get-in @node-defs-atom [@current-node :graph]))
;;         uuids (reaction (keys @graph))
;;         layers (reaction (get-best-layers @graph))
;;         graph-height (reaction (* spacing-down (count @layers)))
;;         graph-width (reaction (* spacing-right (or (apply max (map count @layers)) 0)))
;;         edges (reaction (apply concat
;;                                (map (fn [[end {node-name :name edges-in :edges-in}]]
;;                                       (map (fn [start-and-label]
;;                                              (assoc start-and-label :end end))
;;                                            edges-in))  @graph)))
;;         edge-freqs  (reaction (frequencies @edges))
;;         edge-freq-infos (reaction (map #(get-freq-info  % @layers @graph-height @graph-width) @edge-freqs))
;;         input-infos (reaction (map (fn [[x1 y1 x2 y2 freq label start]]
;;                                      [x1 y1 start]) @edge-freq-infos))
;;         literal-nodes  (get-literal-nodes @graph)]

;;     (doseq [[uuid {node-name :name edges-in :edges-in}] literal-nodes]
;;       (swap! given-values-atom
;;              #(assoc %
;;                 uuid
;;                 {:raw nil
;;                  :compiled  (or (get-in @node-defs-atom [node-name :fn])
;;                                (try-read node-name))})))

;;     [:div {:id "graph-view"
;;            :style {:height "85%"
;;                    :position "relative"}}
;;      [:svg
;;       {:width "100%"
;;        :height "100%"
;;        :view-box (str  (* 0.5 (- node-w spacing-right))  " "
;;                        (* 0.5 (- node-h spacing-down)) " "
;;                        @graph-width " "
;;                        @graph-height)
;;        :style {:position "absolute"
;;                ;;                :z-index -1
;;                }}
;;       (doall (for [[x1 y1 x2 y2 freq label start] @edge-freq-infos]
;;                (edge-freq-view x1 y1 x2 y2 freq label start)))
;;       (doall (for [uuid @uuids]
;;                (let [node-name (reaction (get-in @graph [uuid :name]))
;;                      [x y] (first (coords-from-layers uuid @layers))] ;; use first because we assume there is only 1 of each node
;;                  (node-view (* spacing-right x)
;;                             (* spacing-down y)
;;                             @node-name
;;                             uuid))))]

;;      (doall
;;        (let [literal-nodes (get-literal-nodes @graph)]
;;          (for [[x y start] @input-infos]
;;            (when-not (literal-nodes start)
;;              (input-view x y start @graph @graph-width @graph-height)
;;              ))
;;          ))]))

(defn focus-view []
  (let [current-node  (reaction (first @node-history-atom))
        ;;               code (get-in @node-defs-atom [current-node :code])
        ]
    [:div {:style {
                    ;;                       :display "block"
                    :position "absolute"
                    :right 0
                      :top 0
                      :height "100%"
                      :width "50%"}}
       [:div {:style {:float "top"
                      :height "15%"
                      :width "100%"
                      }}
        (button-view "back" #(prev-graph))

        (nodes-dropdown)
       [:p {:style {:position "absolute"
                    :top 30
                    :left 30
                     :font-size 30
                     :font-family "Ubuntu"}} @current-node]]
       [graph-view2 current-node]
       ]
      ))


(defn editor-view []
  (do (prn "rendering editor view")
    (reagent/create-class
    {:reagent-render         (fn [] @cm-atom [:div {:id "cm-wrapper"
                                                    :style {:position "absolute"
                                                            :top 0
                                                            :left 0
                                                            :height "100%"
                                                            :width "50%"
                                                            :border-right "solid grey 2px"}
                                                    }])
     :component-did-update   (update-editor "cm-wrapper")
     :component-did-mount    (update-editor "cm-wrapper")})))

;; (defn all-view []
;;   [:div
;;    [editor-view]
;;    [focus-view]
;;    ])


;; Render the root comp
;; (defn start []
;;   (render-component
;;     [all-view]
;;     (.getElementById js/document "root")))





