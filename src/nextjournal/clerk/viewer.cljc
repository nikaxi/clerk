(ns nextjournal.clerk.viewer
  (:require [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.datafy :as datafy]
            [clojure.set :as set]
            [clojure.walk :as w]
            #?@(:clj [[babashka.fs :as fs]
                      [clojure.repl :refer [demunge]]
                      [nextjournal.clerk.config :as config]
                      [nextjournal.clerk.analyzer :as analyzer]]
                :cljs [[reagent.ratom :as ratom]
                       [sci.impl.vars :as sci.vars]
                       [applied-science.js-interop :as j]])
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            [lambdaisland.uri.normalize :as uri.normalize])
  #?(:clj (:import (com.pngencoder PngEncoder)
                   (clojure.lang IDeref)
                   (java.lang Throwable)
                   (java.awt.image BufferedImage)
                   (java.util Base64)
                   (java.nio.file Files StandardOpenOption))))

(defrecord ViewerEval [form])

(defrecord ViewerFn [form #?(:cljs f)]
  #?@(:cljs [IFn
             (-invoke [this x] ((:f this) x))
             (-invoke [this x y] ((:f this) x y))]))


(defn viewer-fn? [x]
  (instance? ViewerFn x))

(defn viewer-eval? [x]
  (instance? ViewerEval x))

(defn ->viewer-fn [form]
  (map->ViewerFn {:form form #?@(:cljs [:f (eval form)])}))

(defn ->viewer-eval [form]
  (map->ViewerEval {:form form}))

#?(:clj
   (defmethod print-method ViewerFn [v ^java.io.Writer w]
     (.write w (str "#viewer-fn " (pr-str `~(:form v))))))

#?(:clj
   (defmethod print-method ViewerEval [v ^java.io.Writer w]
     (.write w (str "#viewer-eval " (pr-str `~(:form v))))))

#_(binding [*data-readers* {'viewer-fn ->viewer-fn}]
    (read-string (pr-str (->viewer-fn '(fn [x] x)))))
#_(binding [*data-readers* {'viewer-fn ->viewer-fn}]
    (read-string (pr-str (->viewer-fn 'number?))))

(comment
  (def num? (form->fn+form 'number?))
  (num? 42)
  (:form num?)
  (pr-str num?))

(defn wrapped-value?
  "Tests if `x` is a map containing a `:nextjournal/value`."
  [x]
  (and (map? x) ;; can throw for `sorted-map`
       (try (contains? x :nextjournal/value)
            (catch #?(:clj Exception :cljs js/Error) _e false))))

(defn ensure-wrapped
  "Ensures `x` is wrapped in a map under a `:nextjournal/value` key."
  ([x] (if (wrapped-value? x) x {:nextjournal/value x}))
  ([x v] (-> x ensure-wrapped (assoc :nextjournal/viewer v))))

#_(ensure-wrapped 123)
#_(ensure-wrapped {:nextjournal/value 456})

(defn ->value
  "Takes `x` and returns the `:nextjournal/value` from it, or otherwise `x` unmodified."
  [x]
  (if (wrapped-value? x)
    (:nextjournal/value x)
    x))

#_(->value (with-viewer :code '(+ 1 2 3)))
#_(->value 123)

(defn ->viewer
  "Returns the `:nextjournal/viewer` for a given wrapped value `x`, `nil` otherwise."
  [x]
  (when (wrapped-value? x)
    (:nextjournal/viewer x)))


#_(->viewer (with-viewer :code '(+ 1 2 3)))
#_(->viewer "123")

(defn ->viewers
  "Returns the `:nextjournal/viewers` for a given wrapped value `x`, `nil` otherwise."
  [x]
  (when (wrapped-value? x)
    (:nextjournal/viewers x)))

(defn width
  "Returns the `:nextjournal/width` for a given wrapped value `x`, `nil` otherwise."
  [x]
  (when (wrapped-value? x)
    (:nextjournal/width x)))


(defn normalize-viewer-opts [opts]
  (when-not (map? opts)
    (throw (ex-info "normalize-viewer-opts not passed `map?` opts" {:opts opts})))
  (set/rename-keys opts {:nextjournal.clerk/viewer :nextjournal/viewer
                         :nextjournal.clerk/viewers :nextjournal/viewers
                         :nextjournal.clerk/opts :nextjournal/opts
                         :nextjournal.clerk/width :nextjournal/width}))

(defn normalize-viewer [viewer]
  (cond (keyword? viewer) viewer
        (map? viewer) viewer
        (or (symbol? viewer) (seq? viewer) #?(:cljs (fn? viewer))) {:render-fn viewer}
        #?@(:clj [(fn? viewer) {:transform-fn viewer}])
        :else (throw (ex-info "cannot normalize viewer" {:viewer viewer}))))

#_(normalize-viewer '#(v/html [:h3 "Hello " % "!"]))
#_(normalize-viewer :latex)
#_(normalize-viewer {:render-fn '#(v/html [:h3 "Hello " % "!"]) :transform-fn identity})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public api

(defn with-viewer
  "Wraps the given value `x` and associates it with the given `viewer`. Takes an optional second `viewer-opts` arg."
  ([viewer x] (with-viewer viewer nil x))
  ([viewer viewer-opts x]
   (merge (when viewer-opts (normalize-viewer-opts viewer-opts))
          (-> x
              ensure-wrapped
              (assoc :nextjournal/viewer (normalize-viewer viewer))))))

;; TODO: Think of a better name
(defn with-viewer-extracting-opts [viewer & opts+items]
  ;; TODO: maybe support sequantial & viewer-opts?
  (cond
    (and (map? (first opts+items)) (not (wrapped-value? (first opts+items))))
    (with-viewer viewer (first opts+items) (rest opts+items))

    (and (sequential? (first opts+items)) (= 1 (count opts+items)))
    (apply (partial with-viewer viewer) opts+items)

    :else
    (with-viewer viewer opts+items)))

#_(with-viewer :latex "x^2")
#_(with-viewer '#(v/html [:h3 "Hello " % "!"]) "x^2")

(defn with-viewers
  "Binds viewers to types, eg {:boolean view-fn}"
  [viewers x]
  (-> x
      ensure-wrapped
      (assoc :nextjournal/viewers viewers)))

#_(->> "x^2" (with-viewer :latex) (with-viewers [{:name :latex :render-fn :mathjax}]))

(defn get-safe
  ([key] #(get-safe % key))
  ([map key]
   (when (map? map)
     (try (get map key) ;; can throw for e.g. sorted-map
          (catch #?(:clj Exception :cljs js/Error) _e nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; table viewer normalization

(defn rpad-vec [v length padding]
  (vec (take length (concat v (repeat padding)))))

(def missing-pred
  :nextjournal/missing)

(defn normalize-seq-of-seq [s]
  (let [max-count (count (apply max-key count s))]
    {:rows (mapv #(rpad-vec (->value %) max-count missing-pred) s)}))

(defn normalize-seq-of-map [s]
  (let [ks (->> s (mapcat keys) distinct vec)]
    {:head ks
     :rows (mapv (fn [m] (mapv #(get m % missing-pred) ks)) s)}))


(defn normalize-map-of-seq [m]
  (let [ks (-> m keys vec)
        m* (if (seq? (get m (first ks)))
             (reduce (fn [acc [k s]] (assoc acc k (vec s))) {} m)
             m)]
    {:head ks
     :rows (->> (range (count (val (apply max-key (comp count val) m*))))
                (mapv (fn [i] (mapv #(get-in m* [% i] missing-pred) ks))))}))

(defn normalize-seq-to-vec [{:keys [head rows]}]
  (cond-> {:rows (vec rows)}
    head (assoc :head (vec head))))

(defn use-headers [s]
  (let [{:as table :keys [rows]} (normalize-seq-of-seq s)]
    (-> table
        (assoc :head (first rows))
        (update :rows rest))))

(defn normalize-table-data [data]
  (cond
    (and (map? data) (-> data (get-safe :rows) sequential?)) (normalize-seq-to-vec data)
    (and (map? data) (sequential? (first (vals data)))) (normalize-map-of-seq data)
    (and (sequential? data) (map? (first data))) (normalize-seq-of-map data)
    (and (sequential? data) (sequential? (first data))) (normalize-seq-of-seq data)
    :else nil))

(defn demunge-ex-data [ex-data]
  (update ex-data :trace (fn [traces] (mapv #(update % 0 (comp demunge pr-str)) traces))))

#_(demunge-ex-data (datafy/datafy (ex-info "foo" {:bar :baz})))

(declare present present* !viewers apply-viewers apply-viewers* ensure-wrapped-with-viewers process-viewer process-wrapped-value default-viewers find-named-viewer)

(defn inspect-fn []  #?(:clj (->viewer-eval 'v/inspect-presented) :cljs (eval 'v/inspect-presented)))

(defn when-wrapped [f] #(cond-> % (wrapped-value? %) f))

(defn inspect-wrapped-value [wrapped-value]
  [(inspect-fn) (-> wrapped-value apply-viewers process-wrapped-value)])

#_(w/postwalk (when-wrapped inspect-wrapped-value) [1 2 {:a [3 (with-viewer :latex "\\alpha")]} 4])

(defn mark-presented [wrapped-value]
  (assoc wrapped-value :nextjournal/presented? true))

(defn mark-preserve-keys [wrapped-value]
  (assoc wrapped-value :nextjournal/preserve-keys? true))

(defn fetch-all [_opts _xs]
  (throw (ex-info "`fetch-all` is deprecated, please use a `:transform-fn` with `mark-presented` instead." {})))

(def var-from-def?
  (get-safe :nextjournal.clerk/var-from-def))

(def datafied?
  (get-safe :nextjournal.clerk/datafied))

(defn with-md-viewer [wrapped-value]
  (let [{:as node :keys [type]} (->value wrapped-value)]
    (when-not type
      (throw (ex-info "no type given for with-md-viewer" {:wrapped-value wrapped-value})))
    (with-viewer (keyword "nextjournal.markdown" (name type)) wrapped-value)))

(defn into-markup [markup]
  (fn [{:as wrapped-value :nextjournal/keys [viewers]}]
    (-> (with-viewer {:name :html- :render-fn 'v/html} wrapped-value)
        mark-presented
        (update :nextjournal/value
                (fn [{:as node :keys [text content]}]
                  (into (cond-> markup (fn? markup) (apply [node]))
                        (cond text [text]
                              content (mapv #(-> (with-md-viewer %)
                                                 (assoc :nextjournal/viewers viewers)
                                                 (apply-viewers)
                                                 (as-> w
                                                     (if (= :html- (:name (->viewer w)))
                                                       (->value w)
                                                       [(inspect-fn) (process-wrapped-value w)])))
                                            content))))))))

#?(:clj
   (defn ->edn [x]
     (binding [*print-namespace-maps* false]
       (pr-str x))))

#_(->edn {:nextjournal/value :foo})

(defn apply-viewer-unwrapping-var-from-def [{:as result :nextjournal/keys [value viewer]}]
  (if viewer
    (let [{:keys [transform-fn]} (and (map? viewer) viewer)
          value (if (and (not transform-fn) (get value :nextjournal.clerk/var-from-def))
                  (-> value :nextjournal.clerk/var-from-def deref)
                  value)]
      (assoc result :nextjournal/value (if (or (var? viewer) (fn? viewer))
                                         (viewer value)
                                         {:nextjournal/value value
                                          :nextjournal/viewer (normalize-viewer viewer)})))
    result))

#_(apply-viewer-unwrapping-var-from-def {:nextjournal/value [:h1 "hi"] :nextjournal/viewer :html})
#_(apply-viewer-unwrapping-var-from-def {:nextjournal/value [:h1 "hi"] :nextjournal/viewer (resolve 'nextjournal.clerk/html)})

#?(:clj
   (defn base64-encode-value [{:as result :nextjournal/keys [content-type]}]
     (update result :nextjournal/value (fn [data] (str "data:" content-type ";base64,"
                                                       (.encodeToString (Base64/getEncoder) data))))))

#?(:clj
   (defn maybe-store-result-as-file [{:as _doc+blob-opts :keys [blob-id file out-path]} {:as result :nextjournal/keys [content-type value]}]
     ;; TODO: support customization via viewer api
     (if-let [image-type (second (re-matches #"image/(\w+)" content-type))]
       (let [dir (fs/path out-path "_data")
             file-path (fs/path dir (str (analyzer/valuehash value) "." image-type))
             dir-depth (get (frequencies file) \/ 0)
             relative-root (str/join (repeat dir-depth "../"))]
         ;; TODO: support absolute paths
         (fs/create-dirs dir)
         (when-not (fs/exists? file-path)
           (Files/write file-path value (into-array [StandardOpenOption/CREATE])))
         (assoc result :nextjournal/value (str relative-root "_data/" (fs/file-name file-path))))
       result)))

#_(nextjournal.clerk.builder/build-static-app! {:paths ["image.clj" "notebooks/image.clj" "notebooks/viewers/image.clj"] :bundle? false :browse? false})
#_(nextjournal.clerk.builder/build-static-app! {:paths ["image.clj" "notebooks/image.clj" "notebooks/viewers/image.clj"] :browse? false})

#?(:clj
   (defn process-blobs [{:as doc+blob-opts :keys [blob-mode blob-id]} presented-result]
     (w/postwalk #(if (get % :nextjournal/content-type)
                    (case blob-mode
                      :lazy-load (assoc % :nextjournal/value {:blob-id blob-id :path (:path %)})
                      :inline (base64-encode-value %)
                      :file (maybe-store-result-as-file doc+blob-opts %))
                    %)
                 presented-result)))

(defn get-default-viewers []
  (:default @!viewers default-viewers))

(defn get-viewers
  ([scope] (get-viewers scope nil))
  ([scope value]
   (or (when value (->viewers value))
       (when scope (@!viewers scope))
       (get-default-viewers))))

#_(get-viewers nil nil)

#?(:clj
   (defn transform-result [{cell :nextjournal/value doc :nextjournal/opts}]
     (let [{:keys [inline-results? bundle?]} doc
           {:as result :nextjournal/keys [value blob-id viewers]} (:result cell)
           blob-mode (cond
                       (and (not inline-results?) blob-id) :lazy-load
                       bundle? :inline ;; TODO: provide a separte setting for this
                       :else :file)
           blob-opts (assoc doc :blob-mode blob-mode :blob-id blob-id)
           presented-result (process-blobs blob-opts (present (ensure-wrapped-with-viewers (or viewers (get-viewers *ns*)) value)))
           opts-from-form-meta (select-keys result [:nextjournal/width :nextjournal/opts])]
       (merge {:nextjournal/viewer :clerk/result
               :nextjournal/value (cond-> (try {:nextjournal/edn (->edn (merge presented-result opts-from-form-meta))}
                                               (catch Throwable _e
                                                 {:nextjournal/string (pr-str value)}))
                                    (-> presented-result ->viewer :name)
                                    (assoc :nextjournal/viewer (select-keys (->viewer presented-result) [:name]))

                                    (= blob-mode :lazy-load)
                                    (assoc :nextjournal/fetch-opts {:blob-id blob-id}
                                           :nextjournal/hash (analyzer/->hash-str [blob-id presented-result opts-from-form-meta])))}
              (dissoc presented-result :nextjournal/value :nextjournal/viewer :nextjournal/viewers)
              ;; TODO: consider dropping this. Still needed by notebook-viewer fn to read :nextjournal/width option on result blocks
              opts-from-form-meta))))

(def hide-result-viewer
  {:name :hide-result :transform-fn (fn [_] nil)})

(defn ->display [{:as code-cell :keys [result visibility]}]
  (let [{:keys [code result]} visibility]
    {:result? (not= :hide result)
     :fold? (= code :fold)
     :code? (not= :hide code)}))

#_(->display {:result {:nextjournal.clerk/visibility {:code :show :result :show}}})
#_(->display {:result {:nextjournal.clerk/visibility {:code :fold :result :show}}})
#_(->display {:result {:nextjournal.clerk/visibility {:code :fold :result :hide}}})

(defn with-block-viewer [doc {:as cell :keys [type]}]
  (case type
    :markdown [(with-viewer :markdown (:doc cell))]
    :code (let [cell (update cell :result apply-viewer-unwrapping-var-from-def)
                {:as display-opts :keys [code? result?]} (->display cell)]
            ;; TODO: use vars instead of names
            (cond-> []
              code?
              (conj (with-viewer :clerk/code-block {:nextjournal.clerk/opts (select-keys cell [:loc])}
                      ;; TODO: display analysis could be merged into cell earlier
                      (-> cell (merge display-opts) (dissoc :result))))
              result?
              (conj (with-viewer :clerk/result-block {:nextjournal/opts doc}
                      cell))))))

(defn update-viewers [viewers select-fn->update-fn]
  (reduce (fn [viewers [pred update-fn]]
            (mapv (fn [viewer]
                    (cond-> viewer
                      (pred viewer) update-fn)) viewers))
          viewers
          select-fn->update-fn))

#_(update-viewers default-viewers {:page-size #(dissoc % :page-size)})

(defn add-viewers
  ([added-viewers] (add-viewers (get-default-viewers) added-viewers))
  ([viewers added-viewers] (into (vec added-viewers) viewers)))

(def table-missing-viewer {:pred #{:nextjournal/missing} :render-fn '(fn [x] (v/html [:<>]))})

(def table-markup-viewer
  {:name :table/markup
   :render-fn '(fn [head+body opts]
                 (v/html [:div.overflow-x-auto (into [:table.text-xs.sans-serif.text-gray-900.dark:text-white.not-prose] (v/inspect-children opts) head+body)]))})

(def table-head-viewer
  {:name :table/head
   :render-fn '(fn [header-row {:as opts :keys [path number-col?]}]
                 (v/html [:thead.border-b.border-gray-300.dark:border-slate-700
                          (into [:tr]
                                (map-indexed (fn [i {:as header-cell :nextjournal/keys [value]}]
                                               (let [title (when (or (string? value) (keyword? value) (symbol? value))
                                                             value)]
                                                 [:th.relative.pl-6.pr-2.py-1.align-bottom.font-medium
                                                  (cond-> {:class (when (number-col? i) "text-right")} title (assoc :title title))
                                                  [:div.flex.items-center (v/inspect-presented opts header-cell)]]))) header-row)]))})

(def table-body-viewer
  {:name :table/body :page-size 20
   :render-fn '(fn [rows opts] (v/html (into [:tbody] (map-indexed (fn [idx row] (v/inspect-presented (update opts :path conj idx) row))) rows)))})

(def table-row-viewer
  {:name :table/row
   :render-fn '(fn [row {:as opts :keys [path number-col?]}]
                 (v/html (into [:tr.hover:bg-gray-200.dark:hover:bg-slate-700
                                {:class (if (even? (peek path)) "bg-black/5 dark:bg-gray-800" "bg-white dark:bg-gray-900")}]
                               (map-indexed (fn [idx cell] [:td.pl-6.pr-2.py-1 (when (number-col? idx) {:class "text-right"}) (v/inspect-presented opts cell)])) row)))})

(defn update-table-viewers [viewers]
  (-> viewers
      (update-viewers {(comp #{string?} :pred) #(assoc % :render-fn (quote v/string-viewer))
                       (comp #{number?} :pred) #(assoc % :render-fn '(fn [x] (v/html [:span.tabular-nums (if (js/Number.isNaN x) "NaN" (str x))])))
                       (comp #{:elision} :name) #(assoc % :render-fn '(fn [{:as fetch-opts :keys [total offset unbounded?]} {:keys [num-cols]}]
                                                                        (v/html
                                                                         [v/consume-view-context :fetch-fn (fn [fetch-fn]
                                                                                                             [:tr.border-t.dark:border-slate-700
                                                                                                              [:td.text-center.py-1
                                                                                                               {:col-span num-cols
                                                                                                                :class (if (fn? fetch-fn)
                                                                                                                         "bg-indigo-50 hover:bg-indigo-100 dark:bg-gray-800 dark:hover:bg-slate-700 cursor-pointer"
                                                                                                                         "text-gray-400 text-slate-500")
                                                                                                                :on-click (fn [_] (when (fn? fetch-fn)
                                                                                                                                    (fetch-fn fetch-opts)))}
                                                                                                               (- total offset) (when unbounded? "+") (if (fn? fetch-fn) " more…" " more elided")]])])))})
      (add-viewers [table-missing-viewer
                    table-markup-viewer
                    table-head-viewer
                    table-body-viewer
                    table-row-viewer])))

#?(:clj (def utc-date-format ;; from `clojure.instant/thread-local-utc-date-format`
          (doto (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00")
            (.setTimeZone (java.util.TimeZone/getTimeZone "GMT")))))

#?(:clj
   (defn datafy-scope [scope]
     (cond
       (instance? clojure.lang.Namespace scope) {:namespace (-> scope str keyword)}
       (keyword? scope) scope
       :else (throw (ex-info (str "Unsupported scope " scope) {:scope scope})))))

#_(datafy-scope *ns*)
#_(datafy-scope #'datafy-scope)

(defn update-val [f & args]
  (fn [wrapped-value] (apply update wrapped-value :nextjournal/value f args)))

#_((update-val + 1) {:nextjournal/value 41})

(def markdown-viewers
  [{:name :nextjournal.markdown/doc :transform-fn (into-markup [:div.viewer-markdown])}

   ;; blocks
   {:name :nextjournal.markdown/heading
    :transform-fn (into-markup
                   (fn [{:as node :keys [heading-level]}]
                     [(str "h" heading-level) {:id (uri.normalize/normalize-fragment (md.transform/->text node))}]))}
   {:name :nextjournal.markdown/image :transform-fn #(with-viewer :html [:img.inline (-> % ->value :attrs)])}
   {:name :nextjournal.markdown/blockquote :transform-fn (into-markup [:blockquote])}
   {:name :nextjournal.markdown/paragraph :transform-fn (into-markup [:p])}
   {:name :nextjournal.markdown/plain :transform-fn (into-markup [:<>])}
   {:name :nextjournal.markdown/ruler :transform-fn (into-markup [:hr])}
   {:name :nextjournal.markdown/code
    :transform-fn (fn [wrapped-value] (with-viewer :html
                                        [:div.viewer-code (with-viewer :code
                                                            (md.transform/->text (->value wrapped-value)))]))}

   ;; marks
   {:name :nextjournal.markdown/em :transform-fn (into-markup [:em])}
   {:name :nextjournal.markdown/strong :transform-fn (into-markup [:strong])}
   {:name :nextjournal.markdown/monospace :transform-fn (into-markup [:code])}
   {:name :nextjournal.markdown/strikethrough :transform-fn (into-markup [:s])}
   {:name :nextjournal.markdown/link :transform-fn (into-markup #(vector :a (:attrs %)))}
   {:name :nextjournal.markdown/internal-link :transform-fn (into-markup #(vector :a {:href (str "#" (:text %))}))}
   {:name :nextjournal.markdown/hashtag :transform-fn (into-markup #(vector :a {:href (str "#" (:text %))}))}

   ;; inlines
   {:name :nextjournal.markdown/text :transform-fn (into-markup [:span])}
   {:name :nextjournal.markdown/softbreak :transform-fn (fn [_] (with-viewer :html [:span " "]))}
   #?(:clj {:name :nextjournal.markdown/inline :transform-fn (comp eval read-string md.transform/->text)})

   ;; formulas
   {:name :nextjournal.markdown/formula :transform-fn (comp :text ->value) :render-fn '(fn [tex] (v/katex-viewer tex {:inline? true}))}
   {:name :nextjournal.markdown/block-formula :transform-fn (comp :text ->value) :render-fn 'v/katex-viewer}

   ;; lists
   {:name :nextjournal.markdown/bullet-list :transform-fn (into-markup [:ul])}
   {:name :nextjournal.markdown/numbered-list :transform-fn (into-markup [:ol])}
   {:name :nextjournal.markdown/todo-list :transform-fn (into-markup [:ul.contains-task-list])}
   {:name :nextjournal.markdown/list-item :transform-fn (into-markup [:li])}
   {:name :nextjournal.markdown/todo-item
    :transform-fn (into-markup (fn [{:keys [attrs]}] [:li [:input {:type "checkbox" :default-checked (:checked attrs)}]]))}

   ;; tables
   {:name :nextjournal.markdown/table :transform-fn (into-markup [:table])}
   {:name :nextjournal.markdown/table-head :transform-fn (into-markup [:thead])}
   {:name :nextjournal.markdown/table-body :transform-fn (into-markup [:tbody])}
   {:name :nextjournal.markdown/table-row :transform-fn (into-markup [:tr])}
   {:name :nextjournal.markdown/table-header
    :transform-fn (into-markup #(vector :th {:style (md.transform/table-alignment (:attrs %))}))}
   {:name :nextjournal.markdown/table-data
    :transform-fn (into-markup #(vector :td {:style (md.transform/table-alignment (:attrs %))}))}

   ;; ToC via [[TOC]] placeholder ignored
   {:name :nextjournal.markdown/toc :transform-fn (into-markup [:div.toc])}

   ;; sidenotes
   {:name :nextjournal.markdown/sidenote
    :transform-fn (into-markup (fn [{:keys [attrs]}] [:span.sidenote [:sup {:style {:margin-right "3px"}} (-> attrs :ref inc)]]))}
   {:name :nextjournal.markdown/sidenote-ref
    :transform-fn (into-markup [:sup.sidenote-ref])}])

(def char-viewer
  {:pred char? :render-fn '(fn [c] (v/html [:span.cmt-string.inspected-value "\\" c]))})

(def string-viewer
  {:pred string? :render-fn (quote v/quoted-string-viewer) :page-size 80})

(def number-viewer
  {:pred number? :render-fn (quote v/number-viewer)})

(def number-hex-viewer
  {:name :number-hex :render-fn '(fn [num] (v/number-viewer (str "0x" (.toString (js/Number. num) 16))))})

(def symbol-viewer
  {:pred symbol? :render-fn '(fn [x] (v/html [:span.cmt-keyword.inspected-value (str x)]))})

(def keyword-viewer
  {:pred keyword? :render-fn '(fn [x] (v/html [:span.cmt-atom.inspected-value (str x)]))})

(def nil-viewer
  {:pred nil? :render-fn '(fn [_] (v/html [:span.cmt-default.inspected-value "nil"]))})

(def boolean-viewer
  {:pred boolean? :render-fn '(fn [x] (v/html [:span.cmt-bool.inspected-value (str x)]))})

(def map-entry-viewer
  {:pred map-entry? :name :map-entry :render-fn '(fn [xs opts] (v/html (into [:<>] (comp (v/inspect-children opts) (interpose " ")) xs))) :page-size 2})

(def var-from-def-viewer
  {:pred var-from-def? :transform-fn (update-val (comp deref :nextjournal.clerk/var-from-def))})

(def read+inspect-viewer
  {:name :read+inspect :render-fn '(fn [x] (try (v/html [v/inspect (v/read-string x)])
                                                (catch js/Error _e
                                                  (v/unreadable-edn-viewer x))))})

(def vector-viewer
  {:pred vector? :render-fn 'v/coll-viewer :opening-paren "[" :closing-paren "]" :page-size 20})

(def set-viewer
  {:pred set? :render-fn 'v/coll-viewer :opening-paren "#{" :closing-paren "}" :page-size 20})

(def sequential-viewer
  {:pred sequential? :render-fn 'v/coll-viewer :opening-paren "(" :closing-paren ")" :page-size 20})

(def map-viewer
  {:pred map? :name :map :render-fn 'v/map-viewer :opening-paren "{" :closing-paren "}" :page-size 10})

#?(:cljs (defn var->symbol [v] (if (sci.vars/var? v) (sci.vars/toSymbol v) (symbol v))))

(def var-viewer
  {:pred (some-fn var? #?(:cljs sci.vars/var?)) :transform-fn (comp #?(:cljs var->symbol :clj symbol) ->value) :render-fn '(fn [x] (v/html [:span.inspected-value [:span.cmt-meta "#'" (str x)]]))})

(def throwable-viewer
  {:pred (fn [e] (instance? #?(:clj Throwable :cljs js/Error) e))
   :name :error :render-fn (quote v/throwable-viewer) :transform-fn (comp mark-presented (update-val (comp demunge-ex-data datafy/datafy)))})

(def buffered-image-viewer #?(:clj {:pred #(instance? BufferedImage %)
                                    :transform-fn (fn [{image :nextjournal/value}]
                                                    (let [w (.getWidth image)
                                                          h (.getHeight image)
                                                          r (float (/ w h))]
                                                      (-> {:nextjournal/value (.. (PngEncoder.)
                                                                                  (withBufferedImage image)
                                                                                  (withCompressionLevel 1)
                                                                                  (toBytes))
                                                           :nextjournal/content-type "image/png"
                                                           :nextjournal/width (if (and (< 2 r) (< 900 w)) :full :wide)}
                                                          mark-presented)))
                                    :render-fn '(fn [blob] (v/html [:figure.flex.flex-col.items-center.not-prose [:img {:src (v/url-for blob)}]]))}))

(def ideref-viewer
  {:pred #(instance? IDeref %)
   :transform-fn (fn [wrapped-value] (with-viewer :tagged-value
                                       {:tag "object"
                                        :value (let [r (->value wrapped-value)]
                                                 (vector (type r)
                                                         #?(:clj (with-viewer :number-hex (System/identityHashCode r)))
                                                         (if-let [deref-as-map (resolve 'clojure.core/deref-as-map)]
                                                           (deref-as-map r)
                                                           r)))}))})

(def regex-viewer
  {:pred #?(:clj (partial instance? java.util.regex.Pattern) :cljs regexp?)
   :transform-fn (fn [wrapped-value] (with-viewer :tagged-value {:tag "" :value (let [regex (->value wrapped-value)]
                                                                                  #?(:clj (.pattern regex) :cljs (.-source regex)))}))})

(def fallback-viewer
  {:pred (constantly :true) :transform-fn (update-val #(with-viewer :read+inspect (pr-str %)))})

(def elision-viewer
  {:name :elision :render-fn (quote v/elision-viewer) :transform-fn mark-presented})

(def katex-viewer
  {:name :latex :render-fn (quote v/katex-viewer) :transform-fn mark-presented})

(def mathjax-viewer
  {:name :mathjax :render-fn (quote v/mathjax-viewer) :transform-fn mark-presented})

(def html-viewer
  {:name :html
   :render-fn (quote v/html)
   :transform-fn (comp mark-presented
                       (update-val (partial w/postwalk (when-wrapped inspect-wrapped-value))))})

(def plotly-viewer
  {:name :plotly :render-fn (quote v/plotly-viewer) :transform-fn mark-presented})

(def vega-lite-viewer
  {:name :vega-lite :render-fn (quote v/vega-lite-viewer) :transform-fn mark-presented})

(def markdown-viewer
  {:name :markdown :transform-fn (fn [wrapped-value]
                                   (-> wrapped-value
                                       mark-presented
                                       (update :nextjournal/value #(cond->> % (string? %) md/parse))
                                       (update :nextjournal/viewers add-viewers markdown-viewers)
                                       (with-md-viewer)))})

(def code-viewer
  {:name :code :render-fn (quote v/code-viewer) :transform-fn (comp mark-presented (update-val (fn [v] (if (string? v) v (str/trim (with-out-str (pprint/pprint v)))))))})

(def code-folded-viewer
  {:name :code-folded :render-fn (quote v/foldable-code-viewer) :transform-fn (comp mark-presented (update-val (fn [v] (if (string? v) v (with-out-str (pprint/pprint v))))))})

(def reagent-viewer
  {:name :reagent :render-fn (quote v/reagent-viewer) :transform-fn mark-presented})

(def row-viewer
  {:name :row :render-fn '(fn [items opts]
                            (let [item-count (count items)]
                              (v/html (into [:div {:class "md:flex md:flex-row md:gap-4 not-prose"
                                                   :style opts}]
                                            (map (fn [item]
                                                   [:div.flex.items-center.justify-center.flex-auto
                                                    (v/inspect-presented opts item)])) items))))})

(def col-viewer
  {:name :col :render-fn '(fn [items opts]
                            (v/html (into [:div {:class "md:flex md:flex-col md:gap-4 clerk-grid not-prose"
                                                 :style opts}]
                                          (map (fn [item]
                                                 [:div.flex.items-center.justify-center
                                                  (v/inspect-presented opts item)])) items)))})

(def table-viewer
  {:name :table
   :transform-fn (fn [wrapped-value]
                   (if-let [{:keys [head rows]} (normalize-table-data (->value wrapped-value))]
                     (-> wrapped-value
                         (assoc :nextjournal/viewer :table/markup)
                         (update :nextjournal/width #(or % :wide))
                         (update :nextjournal/viewers update-table-viewers)
                         (assoc :nextjournal/opts {:num-cols (count (or head (first rows)))
                                                   :number-col? (if (seq (first rows)) (mapv number? (first rows)) {})})
                         (assoc :nextjournal/value (cond->> []
                                                     (seq rows) (cons (with-viewer :table/body (map (partial with-viewer :table/row) rows)))
                                                     head (cons (with-viewer (:name table-head-viewer table-head-viewer) head)))))
                     (-> wrapped-value
                         mark-presented
                         (assoc :nextjournal/width :wide)
                         (assoc :nextjournal/value [(present wrapped-value)])
                         (assoc :nextjournal/viewer {:render-fn 'v/table-error}))))})

(def table-error-viewer
  {:name :table-error :render-fn (quote v/table-error) :page-size 1})

(def code-block-viewer
  {:name :clerk/code-block :transform-fn (fn [{:as wrapped-value :nextjournal/keys [value]}]
                                           (-> wrapped-value
                                               (assoc :nextjournal/viewer (if (:fold? value) :code-folded :code))
                                               (update :nextjournal/value :text)))})

(def tagged-value-viewer
  {:name :tagged-value :render-fn '(fn [{:keys [tag value space?]}] (v/html (v/tagged-value {:space? space?} (str "#" tag) [v/inspect value])))
   :transform-fn (fn [wrapped-value]
                   (-> wrapped-value
                       (update-in [:nextjournal/value :value] present)
                       mark-presented))})

#?(:cljs
   (def js-object-viewer
     {:name :js-array
      :pred goog/isObject
      :page-size 20
      :opening-paren "{" :closing-paren "}"
      :render-fn '(fn [v opts] (v/html (v/tagged-value {:space? true} "#js" (v/map-view v opts))))
      :transform-fn (update-val (fn [^js o]
                                  (into {}
                                        (comp (remove (fn [k] (identical? "function" (goog/typeOf (j/get o k)))))
                                              (map (fn [k]
                                                     [(symbol k)
                                                      (try (let [v (j/get o k)]
                                                             (.-constructor v) ;; test for SecurityError
                                                             ;; https://developer.mozilla.org/en-US/docs/Web/Security/Same-origin_policy
                                                             v)
                                                           (catch js/Error _ 'forbidden))]))) (js/Object.keys o))))}))

#?(:cljs
   (def js-array-viewer
     {:name :js-array
      :pred js-iterable?
      :transform-fn (update-val seq)
      :render-fn '(fn [v opts] (v/html (v/tagged-value {:space? true} "#js" (v/coll-view v opts))))
      :opening-paren "[" :closing-paren "]"
      :page-size 20}))

(def result-block-viewer
  {:name :clerk/result-block
   :transform-fn (comp mark-presented
                       #?(:clj transform-result))})

(def result-viewer
  {:name :clerk/result :render-fn (quote v/result-viewer) :transform-fn mark-presented})

(defn process-blocks [viewers {:as doc :keys [ns]}]
  (-> doc
      (update :blocks (partial into [] (comp (mapcat (partial with-block-viewer doc))
                                             (map (comp process-wrapped-value
                                                        apply-viewers*
                                                        (partial ensure-wrapped-with-viewers viewers))))))
      (select-keys [:blocks :toc :toc-visibility :title])
      #?(:clj (cond-> ns (assoc :scope (datafy-scope ns))))))

(def notebook-viewer
  {:name :clerk/notebook
   :render-fn (quote v/notebook-viewer)
   :transform-fn (fn [{:as wrapped-value :nextjournal/keys [viewers]}]
                   (-> wrapped-value
                       (update :nextjournal/value (partial process-blocks viewers))
                       mark-presented))})

(def default-viewers
  ;; maybe make this a sorted-map
  [char-viewer
   string-viewer
   number-viewer
   number-hex-viewer
   symbol-viewer
   keyword-viewer
   nil-viewer
   boolean-viewer
   map-entry-viewer
   var-from-def-viewer
   read+inspect-viewer
   vector-viewer
   set-viewer
   sequential-viewer
   map-viewer
   var-viewer
   throwable-viewer
   buffered-image-viewer
   ideref-viewer
   regex-viewer
   #?(:cljs js-array-viewer)
   #?(:cljs js-object-viewer)
   fallback-viewer
   elision-viewer
   katex-viewer
   mathjax-viewer
   html-viewer
   plotly-viewer
   vega-lite-viewer
   markdown-viewer
   code-viewer
   code-folded-viewer
   reagent-viewer
   row-viewer
   col-viewer
   table-viewer
   table-error-viewer
   code-block-viewer
   result-block-viewer
   tagged-value-viewer
   result-viewer
   notebook-viewer
   hide-result-viewer])

(defonce
  ^{:doc "atom containing a map of and per-namespace viewers or `:defaults` overridden viewers."}
  !viewers
  (#?(:clj atom :cljs ratom/atom) {}))

#_(reset! !viewers {})

;; heavily inspired by code from Thomas Heller in shadow-cljs, see
;; https://github.com/thheller/shadow-cljs/blob/1708acb21bcdae244b50293d17633ce35a78a467/src/main/shadow/remote/runtime/obj_support.cljc#L118-L144

(defn rank-val [val]
  (reduce-kv (fn [res idx pred]
               (if (and (ifn? pred) (pred val)) (reduced idx) res))
             -1
             (into [] (map :pred) default-viewers)))

(defn resilient-compare [a b]
  (try
    (compare a b)
    (catch #?(:clj Exception :cljs js/Error) _e
      (compare (rank-val a) (rank-val b)))))

(defn ensure-sorted [xs]
  (cond
    (sorted? xs) xs
    (map? xs) (sort-by first resilient-compare xs)
    (set? xs) (sort resilient-compare xs)
    :else xs))


(defn find-viewer [viewers select-fn]
  (first (filter select-fn viewers)))

#_(find-viewer default-viewers (comp #{string?} :pred))
#_(find-viewer default-viewers (comp #{:elision} :name))

(defn find-named-viewer [viewers viewer-name]
  (find-viewer viewers (comp #{viewer-name} :name)))

#_(find-named-viewer default-viewers :elision)

(defn viewer-for [viewers x]
  (or (when-let [selected-viewer (->viewer x)]
        (if (keyword? selected-viewer)
          (or (find-named-viewer viewers selected-viewer)
              (throw (ex-info (str "cannot find viewer named " selected-viewer)
                              {:selected-viewer selected-viewer :viewers viewers})))
          selected-viewer))
      (find-viewer viewers (let [v (->value x)]
                             (fn [{:keys [pred]}]
                               (and (ifn? pred) (pred v)))))
      (throw (ex-info (str "cannot find matching viewer for value")
                      {:value (->value x) :viewers viewers :x x}))))

#_(viewer-for default-viewers [1 2 3])
#_(viewer-for default-viewers {:nextjournal/value [1 2 3]})
#_(viewer-for default-viewers 42)
#_(viewer-for default-viewers (with-viewer :html [:h1 "Hello Hiccup"]))
#_(viewer-for default-viewers (with-viewer {:transform-fn identity} [:h1 "Hello Hiccup"]))

(defn ensure-wrapped-with-viewers
  ([x] (ensure-wrapped-with-viewers (get-viewers *ns*) x))
  ([viewers x]
   (-> x
       ensure-wrapped
       (update :nextjournal/viewers (fn [x-viewers] (or x-viewers viewers))))))

#_(ensure-wrapped-with-viewers 42)
#_(ensure-wrapped-with-viewers {:nextjournal/value 42 :nextjournal/viewers [:boo]})

(defn ->opts [wrapped-value]
  (select-keys wrapped-value [:nextjournal/width :nextjournal/opts :!budget :budget :path :current-path :offset]))

(defn apply-viewers* [wrapped-value]
  (when (empty? (->viewers wrapped-value))
    (throw (ex-info "cannot apply empty viewers" {:wrapped-value wrapped-value})))
  (let [viewers (->viewers wrapped-value)
        {:as viewer :keys [render-fn transform-fn]} (viewer-for viewers wrapped-value)
        transformed-value (ensure-wrapped-with-viewers viewers
                                                       (cond-> (dissoc wrapped-value :nextjournal/viewer)
                                                         transform-fn transform-fn))
        wrapped-value' (cond-> transformed-value
                         (-> transformed-value ->value wrapped-value?)
                         (merge (->value transformed-value)))]
    (if (and transform-fn (not render-fn))
      (recur wrapped-value')
      (-> wrapped-value'
          (assoc :nextjournal/viewer viewer)
          (merge (->opts wrapped-value))))))

(defn apply-viewers [x]
  (apply-viewers* (ensure-wrapped-with-viewers x)))

#_(apply-viewers 42)
#_(apply-viewers {:one :two})
#_(apply-viewers {:one :two})
#_(apply-viewers [1 2 3])
#_(apply-viewers (range 3))
#_(apply-viewers (clojure.java.io/file "notebooks"))
#_(apply-viewers (md "# Hello"))
#_(apply-viewers (html [:h1 "hi"]))
#_(apply-viewers (with-viewer :elision {:remaining 10 :count 30 :offset 19}))
#_(apply-viewers (with-viewer (->Form '(fn [name] (html [:<> "Hello " name]))) "James"))
#_(apply-viewers (with-viewers [{:pred (constantly true) :render-fn '(fn [x] [:h1 "hi"])}] 42))

(defn count-viewers
  "Helper function to walk a given `x` and replace the viewers with their counts. Useful for debugging."
  [x]
  (w/postwalk #(if (wrapped-value? %)
                 (cond-> (dissoc % :!budget)
                   (:nextjournal/viewers %)
                   (-> #_%
                       (update :nextjournal/viewers count)
                       (set/rename-keys {:nextjournal/viewers :nextjournal/viewers-count})))
                 %) x))

(defn bounded-count-opts [n xs]
  (when-not (number? n)
    (throw (ex-info "n must be a number?" {:n n :xs xs})))
  (let [limit (+ n #?(:clj config/*bounded-count-limit* :cljs 10000))
        total (try (bounded-count limit xs)
                   (catch #?(:clj Exception :cljs js/Error) _
                     nil))]
    (cond-> {}
      total (assoc :total total)
      (or (not total) (= total limit)) (assoc :unbounded? true))))

#_(bounded-count-opts 20 (range))
#_(bounded-count-opts 20 (range 3234567))

(defn drop+take-xf
  "Takes a map with optional `:n` and `:offset` and returns a transducer that drops `:offset` and takes `:n`."
  [{:keys [n offset]
    :or {offset 0}}]
  (cond-> (drop offset)
    (int? n)
    (comp (take n))))

#_(sequence (drop+take-xf {:n 10}) (range 100))
#_(sequence (drop+take-xf {:n 10 :offset 10}) (range 100))
#_(sequence (drop+take-xf {}) (range 9))


(declare assign-closing-parens)

(defn process-render-fn [{:as viewer :keys [render-fn]}]
  (cond-> viewer
    (and render-fn (not (viewer-fn? render-fn)))
    (update :render-fn ->viewer-fn)))

(defn process-viewer [viewer]
  (if-not (map? viewer)
    viewer
    (-> viewer
        (dissoc :pred :transform-fn :update-viewers-fn)
        process-render-fn)))

#_(process-viewer {:render-fn '(v/html [:h1]) :transform-fn mark-presented})

(defn process-wrapped-value [wrapped-value]
  (-> wrapped-value
      (select-keys [:nextjournal/viewer :nextjournal/value :nextjournal/width :nextjournal/content-type :nextjournal/opts :path :offset :n])
      (update :nextjournal/viewer process-viewer)))

#_(process-wrapped-value (apply-viewers 42))

(defn make-elision [viewers fetch-opts]
  (->> (with-viewer :elision fetch-opts)
       (ensure-wrapped-with-viewers viewers)
       apply-viewers
       process-wrapped-value))

#_(make-elision default-viewers {:n 20})

(defn find-elision [desc]
  (->value (first (filter (comp #{:elision} :name :nextjournal/viewer)
                          (tree-seq (some-fn map? vector?) #(cond-> % (map? %) vals) desc)))))

(defn ->fetch-opts [wrapped-value]
  (merge {:n (-> wrapped-value ->viewer :page-size)}
         (select-keys wrapped-value [:path :offset])))

(defn get-elision [wrapped-value]
  (let [{:as fetch-opts :keys [path offset n]} (->fetch-opts wrapped-value)]
    (merge fetch-opts (bounded-count-opts n (->value wrapped-value)))))

#_(get-elision (present (range)))
#_(get-elision (present "abc"))
#_(get-elision (present (str/join (repeat 1000 "abc"))))

(defn get-fetch-opts-n [wrapped-value]
  (-> wrapped-value ->fetch-opts :n))

(defn inherit-opts [{:as wrapped-value :nextjournal/keys [viewers]} value path-segment]
  (-> (ensure-wrapped-with-viewers viewers value)
      (merge (->opts wrapped-value))
      (dissoc :offset)
      (update :path (fnil conj []) path-segment)
      (update :current-path (fnil conj []) path-segment)))

(defn present+paginate-children [{:as wrapped-value :nextjournal/keys [viewers preserve-keys?] :keys [!budget budget]}]
  (let [{:as fetch-opts :keys [path offset n]} (->fetch-opts wrapped-value)
        xs (->value wrapped-value)
        paginate? (and (number? n) (not preserve-keys?))
        fetch-opts' (cond-> fetch-opts
                      (and paginate? !budget (not (map-entry? xs)))
                      (update :n min @!budget))
        children (if preserve-keys?
                   (into {} (map (fn [[k v]] [k (present* (inherit-opts wrapped-value v k))])) xs)
                   (into []
                         (comp (if paginate? (drop+take-xf fetch-opts') identity)
                               (map-indexed (fn [i x] (present* (inherit-opts wrapped-value x (+ i (or offset 0))))))
                               (remove nil?))
                         (ensure-sorted xs)))
        {:as elision :keys [total unbounded?]} (and paginate? (get-elision wrapped-value))
        new-offset (when paginate? (or (some-> children peek :path peek inc) 0))]
    (cond-> children
      (and paginate? (or unbounded? (< new-offset total)))
      (conj (let [fetch-opts (assoc elision :offset new-offset)]
              (make-elision viewers fetch-opts))))))

(defn present+paginate-string [{:as wrapped-value :nextjournal/keys [viewers viewer value]}]
  (let [{:as elision :keys [n total path offset]} (and (:page-size viewer)
                                                       (get-elision wrapped-value))]
    (if (and n (< n total))
      (let [new-offset (min (+ (or offset 0) n) total)]
        (cond-> [(subs value (or offset 0) new-offset)]
          (pos? (- total new-offset)) (conj (let [fetch-opts (-> elision
                                                                 (assoc :offset new-offset :replace-path (conj path new-offset)))]
                                              (make-elision viewers fetch-opts)))
          true ensure-wrapped))
      value)))


(defn ^:private present* [{:as wrapped-value
                           :keys [path current-path !budget]
                           :nextjournal/keys [viewers]}]
  (when (empty? viewers)
    (throw (ex-info "cannot present* with empty viewers" {:wrapped-value wrapped-value})))
  (let [{:as wrapped-value :nextjournal/keys [viewers presented?]} (apply-viewers* wrapped-value)
        descend? (< (count current-path)
                    (count path))
        xs (->value wrapped-value)]
    #_(prn :xs xs :type (type xs) :path path :current-path current-path :descend? descend?)
    (when (and !budget (not descend?) (not presented?))
      (swap! !budget #(max (dec %) 0)))
    (-> (merge (->opts wrapped-value)
               (with-viewer (->viewer wrapped-value)
                 (cond presented?
                       wrapped-value

                       descend? ;; TODO: can this be unified, simplified, or even dropped in favor of continuation?
                       (let [idx (first (drop (count current-path) path))]
                         (present* (-> (ensure-wrapped-with-viewers
                                        viewers
                                        (cond (and (map? xs) (keyword? idx)) (get xs idx)
                                              (or (map? xs) (set? xs)) (nth (seq (ensure-sorted xs)) idx)
                                              (associative? xs) (get xs idx)
                                              (sequential? xs) (nth xs idx)))
                                       (merge (->opts wrapped-value))
                                       (update :current-path (fnil conj []) idx))))

                       (string? xs)
                       (present+paginate-string wrapped-value)

                       (and xs (seqable? xs))
                       (present+paginate-children wrapped-value)

                       :else ;; leaf value
                       xs)))
        process-wrapped-value)))

(defn assign-content-lengths [wrapped-value]
  (w/postwalk
   (fn [x]
     (if-let [value (and (wrapped-value? x) (:nextjournal/value x))]
       (let [{:nextjournal/keys [viewer]} x
             {:keys [name opening-paren closing-paren]} viewer
             elision-content-length 6]
         (assoc x
                :content-length
                (cond
                  (or (nil? value) (char? value) (string? value) (keyword? value) (symbol? value) (number? value))
                  (count (pr-str value))
                  (contains? #{:elision} name)
                  elision-content-length
                  (contains? #{:map-entry} name)
                  (reduce + 1 (map #(:content-length % 0) value))
                  (vector? value)
                  (->> value
                       (map #(:content-length % 0))
                       (reduce + (+ (count opening-paren) (count closing-paren)))
                       (+ (dec (count value))))
                  :else 0)
                :type name))
       x))
   wrapped-value))

#_(defn compute-expanded-at [{:as state :keys [indents expanded-at prev-type]}
                           {:nextjournal/keys [value]
                            :keys [content-length path type]
                            :or {content-length 0}}]
  (let [max-length (- 80 (reduce + 0 indents))
        expanded? (< max-length content-length)
        state' (assoc state
                      :expanded-at (assoc expanded-at path expanded?)
                      #_(if expanded?
                          (assoc expanded-at path true)
                          expanded-at)
                      :prev-type type
                      :indents (conj
                                (->> indents (take (count path)) vec)
                                (cond
                                  (contains? #{:map-entry} prev-type) (or content-length 0)
                                  (vector? value) 2
                                  :else 1)))]
    (if (vector? value)
      (reduce compute-expanded-at state' value)
      state')))

#_(defn assign-expanded-at [wrapped-value]
  (cond-> wrapped-value
    (:content-length wrapped-value)
    (assoc :nextjournal/expanded-at
           (:expanded-at (compute-expanded-at {:expanded-at {}} wrapped-value)))))

(defn compute-expanded-at [{:as state :keys [expanded-at]}
                           {:nextjournal/keys [value] :keys [path]}]
  (let [state' (assoc state :expanded-at (assoc expanded-at path false))]
    (if (vector? value)
      (reduce compute-expanded-at state' value)
      state')))

(defn assign-expanded-at [wrapped-value]
  (assoc wrapped-value :nextjournal/expanded-at (:expanded-at (compute-expanded-at {:expanded-at {}} wrapped-value))))

(comment
  (-> (compute-expanded-at
       {:indents [] :expanded-at {}}
       (present {:a-vector [1 2 3] :a-list '(123 234 345) :a-set #{1 2 3 4}}))
      :expanded-at
      keys
      sort)
  (= (count "[1 2 [1 [2] 3] 4 5]")
     (:content-length (assign-content-lengths (present [1 2 [1 [2] 3] 4 5]))))
  (= (count "{:a-vector [1 2 3] :a-list (123 234 345) :a-set #{1 2 3 4}}")
     (:content-length (assign-content-lengths (present {:a-vector [1 2 3] :a-list '(123 234 345) :a-set #{1 2 3 4}}))))
  ;; Check for elisions as well
  (assign-content-lengths (present {:foo (vec (repeat 2 {:baz (range 30) :fooze (range 40)})) :bar (range 20)})))

(defn present
  "Returns a subset of a given `value`."
  ([x] (present x {}))
  ([x opts]
   (-> (ensure-wrapped-with-viewers x)
       (merge {:!budget (atom (:budget opts 200))
               :path (:path opts [])
               :current-path (:current-path opts [])}
              opts)
       present*
       assign-closing-parens
       assign-expanded-at)))

(comment
  (present [\a \b])
  (present [42])
  (-> (present (range 100)) ->value peek)
  (present {:hello [1 2 3]})
  (present {:one [1 2 3] 1 2 3 4})
  (present [1 2 [1 [2] 3] 4 5])
  (present (clojure.java.io/file "notebooks"))
  (present {:viewers [{:pred sequential? :render-fn pr-str}]} (range 100))
  (present (map vector (range)))
  (present (subs (slurp "/usr/share/dict/words") 0 1000))
  (present (plotly {:data [{:z [[1 2 3] [3 2 1]] :type "surface"}]}))
  (present [(with-viewer :html [:h1 "hi"])])
  (present (with-viewer :html [:ul (for [x (range 3)] [:li x])]))
  (present (range))
  (present {1 [2]})
  (present (with-viewer '(fn [name] (html [:<> "Hello " name])) "James"))
  (present {:foo (vec (repeat 2 {:baz (range 30) :fooze (range 40)})) :bar (range 20)}))

(defn desc->values
  "Takes a `description` and returns its value. Inverse of `present`. Mostly useful for debugging."
  [desc]
  (let [x (->value desc)
        viewer-name (-> desc ->viewer :name)]
    (cond (= viewer-name :elision) (with-meta '… x)
          (coll? x) (into (case viewer-name
                            ;; TODO: fix table viewer
                            (:map :table) {}
                            (or (empty x) []))
                          (map desc->values)
                          x)
          :else x)))

#_(desc->values (present [1 [2 {:a :b} 2] 3 (range 100)]))
#_(desc->values (present (table (mapv vector (range 30)))))
#_(desc->values (present (with-viewer :table (normalize-table-data (repeat 60 ["Adelie" "Biscoe" 50 30 200 5000 :female])))))

(defn path-to-value [path]
  (conj (interleave path (repeat :nextjournal/value)) :nextjournal/value))

(defn merge-presentations [root more elision]
  (update-in root
             (path-to-value (:path elision))
             (fn [value]
               (let [{:keys [offset path]} (-> value peek :nextjournal/value)
                     path-from-value (conj path offset)
                     path-from-more (or (:replace-path elision) ;; string case, TODO find a better way to unify
                                        (-> more :nextjournal/value first :path))]
                 (when (not= path-from-value path-from-more)
                   (throw (ex-info "paths mismatch" {:path-from-value path-from-value :path-from-more path-from-more :root root :more more :path-to-value (path-to-value (:path more)) :value value})))
                 (into (pop value) (:nextjournal/value more))))))


(defn assign-closing-parens
  ([node] (assign-closing-parens '() node))
  ([closing-parens node]
   (let [value (->value node)
         viewer (->viewer node)
         closing (:closing-paren viewer)
         non-leaf? (and (vector? value) (wrapped-value? (first value)))
         defer-closing? (and non-leaf?
                             (or (-> value last :nextjournal/viewer :closing-paren) ;; the last element can carry parens
                                 (and (= :map-entry (-> value last :nextjournal/viewer :name)) ;; the last element is a map entry whose value can carry parens
                                      (-> value last :nextjournal/value last :nextjournal/viewer :closing-paren))))]
     (cond-> (cond
               (not closing) node
               defer-closing? (update node :nextjournal/viewer dissoc :closing-paren)
               :else (update-in node [:nextjournal/viewer :closing-paren] cons closing-parens))
       non-leaf? (update :nextjournal/value
                         (fn [xs]
                           (into []
                                 (map-indexed (fn [i x]
                                                (assign-closing-parens (if (and defer-closing? (= (dec (count xs)) i))
                                                                         (cond->> closing-parens closing (cons closing))
                                                                         '())
                                                                       x)))
                                 xs)))))))

(defn reset-viewers!
  ([viewers] (reset-viewers! *ns* viewers))
  ([scope viewers]
   (assert (or (#{:default} scope)
               #?(:clj (instance? clojure.lang.Namespace scope))))
   (swap! !viewers assoc scope viewers)))

(defn add-viewers! [viewers]
  (reset-viewers! *ns* (add-viewers (get-default-viewers) viewers))
  viewers)



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public convenience api
(def html         (partial with-viewer html-viewer))
(def md           (partial with-viewer markdown-viewer))
(def plotly       (partial with-viewer plotly-viewer))
(def vl           (partial with-viewer vega-lite-viewer))
(def table        (partial with-viewer table-viewer))
(def row          (partial with-viewer-extracting-opts row-viewer))
(def col          (partial with-viewer-extracting-opts col-viewer))
(def tex          (partial with-viewer katex-viewer))
(def notebook     (partial with-viewer (:name notebook-viewer)))
(def code         (partial with-viewer code-viewer))
(defn doc-url [path]
  (->viewer-eval (list 'v/doc-url path)))

(defn hide-result
  "Deprecated, please put ^{:nextjournal.clerk/visibility {:result :hide}} metadata on the form instead."
  {:deprecated "0.10"}
  ([x] #?(:clj (hide-result {} x)) :cljs x)
  ([_viewer-opts x]
   #?(:clj (binding [*out* *err*]
             (prn "`hide-result` has been deprecated, please put `^{:nextjournal.clerk/visibility {:result :hide}}` metadata on the form instead.")))
   x))

(def eval-cljs-result-viewer
  {:transform-fn mark-presented
   :render-fn '(fn [x]
                 (v/html (v/inspect x)))})

(defn eval-cljs-str [code-string]
  ;; NOTE: this relies on implementation details on how SCI code is evaluated
  ;; and will change in a future version of Clerk
  (with-viewer eval-cljs-result-viewer
    (-> (->viewer-eval (list 'binding '[*ns* *ns*]
                             (list 'load-string code-string)))
        (assoc :remount? true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; examples
(def example-viewer
  {:transform-fn (fn [{:as wrapped-value :nextjournal/keys [viewers] :keys [path current-path]}]
                   (-> wrapped-value
                       mark-preserve-keys
                       (assoc :nextjournal/viewer {:render-fn '(fn [{:keys [form val]} opts]
                                                                 (v/html [:div.flex.flex-wrap
                                                                          {:class "py-[7px]"}
                                                                          [:div [:div.bg-slate-100.px-2.rounded
                                                                                 (v/inspect-presented opts form)]]
                                                                          [:div.flex.mt-1
                                                                           [:div.mx-2.font-sans.text-xs.text-slate-500 {:class "mt-[2px]"} "⇒"]
                                                                           (v/inspect-presented opts val)]]))})
                       (update-in [:nextjournal/value :form] code)))})

(def examples-viewer
  {:transform-fn (update-val (fn [examples]
                               (mapv (partial with-viewer example-viewer) examples)))
   :render-fn '(fn [examples opts]
                 (v/html (into [:div.border-l-2.border-slate-300.pl-4
                                [:div.uppercase.tracking-wider.text-xs.font-sans.text-slate-500.mt-4.mb-2 "Examples"]]
                               (v/inspect-children opts) examples)))})
