package com.atguigu.gulimall.search.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.constant.EsConstant;
import com.atguigu.common.to.es.SkuEsModel;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.search.config.GulimallElasticSearchConfig;
import com.atguigu.gulimall.search.feign.ProductFeignService;
import com.atguigu.gulimall.search.service.MallSearchService;
import com.atguigu.gulimall.search.vo.AttrResponseVo;
import com.atguigu.gulimall.search.vo.BrandVo;
import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.nested.NestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.ParsedNested;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedLongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedStringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MallSearchServiceImpl implements MallSearchService {

    @Autowired
    RestHighLevelClient client;

    @Autowired
    ProductFeignService productFeignService;

    @Override
    public SearchResult search(SearchParam param) {

        SearchResult result = null;

        // ???SearchRequest
        SearchRequest searchRequest = buildSearchRequest(param);

        try {
             SearchResponse response = client.search(searchRequest, GulimallElasticSearchConfig.COMMON_OPTIONS);
             result = buildSearchResult(response,param);
            // ?????????????????????????????????
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * ???SearchRequest
     * @return
     */
    private SearchRequest buildSearchRequest(SearchParam param) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();

        // ???. ???query???????????????bool??????
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // 1. ??????bool-must??????????????????
                // ??? ??????skuTitle??????
                if(!StringUtils.isEmpty(param.getKeyword())) {
                    boolQuery.must(QueryBuilders.matchQuery("skuTitle",param.getKeyword()));
                }

            // 2. ??????bool-filter??????????????????
                // ??? catalog??????
                if (param.getCatalog3Id() != null) {
                    boolQuery.filter(QueryBuilders.termQuery("catalogId", param.getCatalog3Id()));
                }

                // ??? brandId??????
                if (param.getBrandId() != null && param.getBrandId().size() > 0) {
                    boolQuery.filter(QueryBuilders.termsQuery("brandId", param.getBrandId()));
                }

                // ??? hasStock????????????????????????0/1???
                if (param.getHasStock() != null) {
                    boolQuery.filter(QueryBuilders.termsQuery("hasStock", param.getHasStock()==1));
                }


                // ??? attrs???????????????????????????"attrs=2_5???:6???"???string???
                        if(param.getAttrs() != null && param.getAttrs().size() > 0) {
                            for (String attrStr : param.getAttrs()) {
                                BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
                                String[] s = attrStr.split("_");
                                String attrId = s[0];
                                String[] attrValue = s[1].split(":");
                                boolQueryBuilder.must(QueryBuilders.termQuery("attrs.attrId", attrId));
                                boolQueryBuilder.must(QueryBuilders.termsQuery("attrs.attrValue",attrValue));
                                // ??????nested??????
                                NestedQueryBuilder attrsQuery = QueryBuilders.nestedQuery("attrs", boolQueryBuilder, ScoreMode.None);
                                boolQuery.filter(attrsQuery);
                            }
                        }

                // ??? skuPrice???????????????????????????"skuPrice=1_500/_500/500_"???string???
                if(!StringUtils.isEmpty(param.getSkuPrice())) {
                    RangeQueryBuilder rangeQuery = QueryBuilders.rangeQuery("skuPrice");
                    String[] s = param.getSkuPrice().split("_");
                    if(s.length == 2) {
                        rangeQuery.gte(s[0]).lte(s[1]);
                    } else if(s.length == 1) {
                        if(param.getSkuPrice().startsWith("_")) {
                            rangeQuery.lte(s[0]);
                        }
                        if(param.getSkuPrice().endsWith("_")) {
                            rangeQuery.gte(s[0]);
                        }
                    }
                    boolQuery.filter(rangeQuery);
                }

                sourceBuilder.query(boolQuery);

        // ???. ??????sort??????????????????
        if(!StringUtils.isEmpty(param.getSort())){
            String sort = param.getSort();
            // sort=hotScore_asc/desc
            String[] s = sort.split("_");
            SortOrder order = s[1].equalsIgnoreCase("asc") ? SortOrder.ASC : SortOrder.DESC;
            sourceBuilder.sort(s[0], order);
        }

        // ???. ??????from???size??????(??????)
        sourceBuilder.from((param.getPageNum() - 1) * EsConstant.PRODUCT_PAGESIZE);
        sourceBuilder.size(EsConstant.PRODUCT_PAGESIZE);

        // ???. ??????highlight??????????????????
        if(!StringUtils.isEmpty(param.getKeyword())){
            HighlightBuilder builder = new HighlightBuilder();
            builder.field("skuTitle");
            builder.preTags("<b style='color:red'>");
            builder.postTags("</b>");
            sourceBuilder.highlighter(builder);
        }

        // ???. ??????aggs??????????????????

            // ??? ?????????brand????????? (brand=xxx??????xxx???)
            TermsAggregationBuilder brand_agg = AggregationBuilders.terms("brand_agg");
            brand_agg.field("brandId").size(50);

            brand_agg.subAggregation(AggregationBuilders.terms("brand_name_agg").field("brandName").size(1));
            brand_agg.subAggregation(AggregationBuilders.terms("brand_img_agg").field("brandImg").size(1));

            sourceBuilder.aggregation(brand_agg);

            // ??? ?????????catalog????????? (catalog=xxx??????xxx???)
            TermsAggregationBuilder catalog_agg = AggregationBuilders.terms("catalog_agg").field("catalogId").size(20);
            catalog_agg.subAggregation(AggregationBuilders.terms("catalog_name_agg").field("catalogName").size(1));

            sourceBuilder.aggregation(catalog_agg);

            // ??? ?????????attrs?????????
            NestedAggregationBuilder attr_agg = AggregationBuilders.nested("attrs_agg", "attrs");

                // ???attr_id_agg??????
                TermsAggregationBuilder attr_id_agg = AggregationBuilders.terms("attr_id_agg").field("attrs.attrId");
                attr_id_agg.subAggregation(AggregationBuilders.terms("attr_name_agg").field("attrs.attrName").size(1));
                attr_id_agg.subAggregation(AggregationBuilders.terms("attr_value_agg").field("attrs.attrValue").size(50));
                attr_agg.subAggregation(attr_id_agg);

            sourceBuilder.aggregation(attr_agg);

        String s = sourceBuilder.toString();
        System.out.println("?????????DSL: "+s);

        SearchRequest searchRequest = new SearchRequest(new String[]{EsConstant.PRODUCT_INDEX}, sourceBuilder);
        return searchRequest;
    }

    /**
     * ???SearchResult
     * @return
     */
    private SearchResult buildSearchResult(SearchResponse response, SearchParam param) {
        SearchResult result = new SearchResult();

            // ???response??????SearchResult?????????
            SearchHits hits = response.getHits();

            // ??? ??????products??????
            List<SkuEsModel> esModels = new ArrayList<>();
            if(hits.getHits() != null && hits.getHits().length > 0) {
                for (SearchHit hit : hits.getHits()) {
                    String sourceAsString = hit.getSourceAsString();
                    SkuEsModel esModel = JSON.parseObject(sourceAsString, SkuEsModel.class);

                    // ??????highlight
                    if(!StringUtils.isEmpty(param.getKeyword())){
                        HighlightField skuTitle = hit.getHighlightFields().get("skuTitle");
                        String highlightFields = skuTitle.getFragments()[0].string();
                        esModel.setSkuTitle(highlightFields);
                    }
                    esModels.add(esModel);
                }
            }
            result.setProducts(esModels);

            // ??? ??????catalogs??????
            List<SearchResult.CatalogVo> catalogVos = new ArrayList<>();

                ParsedLongTerms catalog_agg = response.getAggregations().get("catalog_agg");
                List<? extends Terms.Bucket> buckets = catalog_agg.getBuckets();
                for (Terms.Bucket bucket : buckets) {
                    // ???CatalogVo??????catalogId??????
                    SearchResult.CatalogVo catalogVo = new SearchResult.CatalogVo();
                    catalogVo.setCatalogId(bucket.getKeyAsNumber().longValue());

                    // ???CatalogVo??????catalogName??????
                    ParsedStringTerms catalog_name_agg = bucket.getAggregations().get("catalog_name_agg");
                    String catalog_name = catalog_name_agg.getBuckets().get(0).getKeyAsString();
                    catalogVo.setCatalogName(catalog_name);
                    catalogVos.add(catalogVo);
                }
            result.setCatalogs(catalogVos);

            // ??? ??????brands??????
            List<SearchResult.BrandVo> brandVos = new ArrayList<>();

                ParsedLongTerms brand_agg = response.getAggregations().get("brand_agg");
                for (Terms.Bucket bucket : brand_agg.getBuckets()) {
                    SearchResult.BrandVo brandVo = new SearchResult.BrandVo();
                    // ???BrandVo??????brandId??????
                    brandVo.setBrandId(bucket.getKeyAsNumber().longValue());

                    // ???BrandVo??????brandImg??????
                    ParsedStringTerms brand_img_agg = bucket.getAggregations().get("brand_img_agg");
                    brandVo.setBrandImg(brand_img_agg.getBuckets().get(0).getKeyAsString());

                    // ???BrandVo??????brandName??????
                    ParsedStringTerms brand_name_agg = bucket.getAggregations().get("brand_name_agg");
                    brandVo.setBrandName(brand_name_agg.getBuckets().get(0).getKeyAsString());

                    brandVos.add(brandVo);
                }
            result.setBrands(brandVos);

            // ??? ??????attrs??????
            List<SearchResult.AttrVo> attrVos = new ArrayList<>();

                ParsedNested attrs_agg = response.getAggregations().get("attrs_agg");
                ParsedLongTerms attr_id_agg = attrs_agg.getAggregations().get("attr_id_agg");
                for (Terms.Bucket bucket : attr_id_agg.getBuckets()) {
                    SearchResult.AttrVo attrVo = new SearchResult.AttrVo();

                        // ???AttrVO??????attrId??????
                        attrVo.setAttrId(bucket.getKeyAsNumber().longValue());

                        // ???AttrVO??????attrName??????
                        ParsedStringTerms attr_name_agg = bucket.getAggregations().get("attr_name_agg");
                        attrVo.setAttrName(attr_name_agg.getBuckets().get(0).getKeyAsString());

                        // ???AttrVO??????attrValue??????
                        List<String> attrValues = new ArrayList<>();
                        ParsedStringTerms attr_value_agg = bucket.getAggregations().get("attr_value_agg");
                        for (Terms.Bucket bucket1 : attr_value_agg.getBuckets()) {
                            attrValues.add(bucket1.getKeyAsString());
                        }
                        attrVo.setAttrValue(attrValues);

                    attrVos.add(attrVo);
                }
            result.setAttrs(attrVos);


            // ??? ??????total??????
            long total = hits.getTotalHits().value;
            result.setTotal(total);

            // ??? ??????totalPages??????
            int totalPages = (int) total % EsConstant.PRODUCT_PAGESIZE == 0 ? (int) total/EsConstant.PRODUCT_PAGESIZE : (int) total/(EsConstant.PRODUCT_PAGESIZE + 1);
            result.setTotalPages(totalPages);

            // ??? ??????pageNum??????
            result.setPageNum(param.getPageNum());

            // ??? ??????pageNavs??????
            List<Integer> pageNavs = new ArrayList<>();
            for (int i = 0; i <= totalPages; i++) {
                pageNavs.add(i);
            }
            result.setPageNavs(pageNavs);

        // ??? ??????navs?????????attrIds??????  Attrs??????????????????List<String>?????????string???????????????"2_5???:6???"
        // ?????????attr?????????navVo
        if(param.getAttrs() != null && param.getAttrs().size() > 0) {
            List<SearchResult.NavVo> collect = param.getAttrs().stream().map(attr -> {
                SearchResult.NavVo navVo = new SearchResult.NavVo();

                // ???navVo???navValue??????
                String[] s = attr.split("_");
                navVo.setNavValue(s[1]);

                // ???result??????attrIds??????
                result.getAttrIds().add(Long.parseLong(s[0]));

                // ???navVo???name??????
                R r = productFeignService.attrInfo(Long.parseLong(s[0]));
                if(r.getCode() == 0) {
                    AttrResponseVo data = r.getData("attr", new TypeReference<AttrResponseVo>() {});
                    navVo.setName(data.getAttrName());
                } else {
                    navVo.setName(s[0]);
                }

                // ???navVo???link??????
                String replace = replaceQueryString(param, attr,"attrs");
                navVo.setLink("http://search.gulimall.com/list.html?" + replace);
                return navVo;
            }).collect(Collectors.toList());
            result.setNavs(collect);
        }

        // ?????????brand?????????navVo
        if (param.getBrandId() != null && param.getBrandId().size() > 0) {
            List<SearchResult.NavVo> navs = result.getNavs();
            SearchResult.NavVo navVo = new SearchResult.NavVo();
            // ???navVo??????name??????
            navVo.setName("??????");
            // ???navVo??????navValue??????
            R r = productFeignService.brandsInfo(param.getBrandId());
            if(r.getCode() == 0) {
                List<BrandVo> brand = r.getData("brand", new TypeReference<List<BrandVo>>() {
                });
                StringBuffer buffer = new StringBuffer();
                String replace = "";
                for (BrandVo brandVo : brand) {
                    buffer.append(brandVo.getName() + ";");
                    replace = replaceQueryString(param, brandVo.getBrandId()+"", "brandId");
                    param.set_queryString(replace);
                }
                navVo.setNavValue(buffer.toString());
                // ???navVo???link??????
                navVo.setLink("http://search.gulimall.com/list.html?" + replace);
            }
            navs.add(navVo);
        }

        // TODO ????????????????????????

        return result;
    }

    private String replaceQueryString(SearchParam param, String value, String key) {
        String encode = null;
        try {
            encode = URLEncoder.encode(value, "UTF-8");
            encode.replace("+", "%20"); // ?????????????????????browser???java???space???????????????
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        String replace = param.get_queryString().replace("&"+key+"=" + encode, "");
        return replace;
    }
}
