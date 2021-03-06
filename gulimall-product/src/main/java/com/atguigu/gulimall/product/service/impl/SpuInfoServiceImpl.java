package com.atguigu.gulimall.product.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.constant.ProductConstant;
import com.atguigu.common.to.SkuReductionTo;
import com.atguigu.common.to.SpuBoundTo;
import com.atguigu.common.to.es.SkuEsModel;
import com.atguigu.common.to.es.SkuHasStockVo;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.product.dao.SpuInfoDao;
import com.atguigu.gulimall.product.entity.*;
import com.atguigu.gulimall.product.feign.CouponFeignService;
import com.atguigu.gulimall.product.feign.SearchFeignService;
import com.atguigu.gulimall.product.feign.WareFeignService;
import com.atguigu.gulimall.product.service.*;
import com.atguigu.gulimall.product.vo.BaseAttrs;
import com.atguigu.gulimall.product.vo.Images;
import com.atguigu.gulimall.product.vo.Skus;
import com.atguigu.gulimall.product.vo.SpuSaveVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {

    @Autowired
    SearchFeignService searchFeignService;

    @Autowired
    WareFeignService wareFeignService;

    @Autowired
    ProductAttrValueService productAttrValueService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    BrandService brandService;

    @Autowired
    SpuInfoDescService spuInfoDescService;

    @Autowired
    SpuImagesService spuImagesService;

    @Autowired
    AttrService attrService;

    @Autowired
    ProductAttrValueServiceImpl attrValueService;

    @Autowired
    SkuInfoService skuInfoService;

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    SkuImagesService skuImagesService;

    @Autowired
    SkuSaleAttrValueService skuSaleAttrValueService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void saveSpuInfo(SpuSaveVo vo) {
        // 1. ??????spu_info???????????????(6??????) ??? pms_spu_info(9??????)
        // ??? spuName, SpuDescription, catelogId, brandId, weight, publishStatus
        // ??? ???????????? + id + create_time + update_time
        SpuInfoEntity infoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo,infoEntity);
        infoEntity.setCreateTime(new Date());
        infoEntity.setUpdateTime(new Date());
        this.saveBaseSpuInfo(infoEntity);

        // 2. ??????decript??????(1????????????List<String>) ??? pms_spu_info_desc(2??????)
        // ??? decript??????list????????????string???????????????????????????url
        // ??? spu_id,decript
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity spuInfoDescEntity = new SpuInfoDescEntity();
        spuInfoDescEntity.setSpuId(infoEntity.getId());
        spuInfoDescEntity.setDecript(String.join(",",decript));
        spuInfoDescService.saveSpuInfoDesc(spuInfoDescEntity);

        // 3. ??????images??????(1????????????List<String>) ??? pms_spu_images(6??????)
        // ??? images??????list????????????string??????????????????????????????????????????url
        // ??? id, spu_id, img_name, img_url, img_sort, default_img
        List<String> images = vo.getImages();
        spuImagesService.saveImages(infoEntity.getId(),images);

        // 4. ??????baseAttrs??????(??????class?????????3??????) ??? pms_product_attr_value(7??????)
        // ??? attrId, attrValues, showDesc
        // ??? id, spu_id, attr_id, attr_name, attr_value, attr_sort, quick_show
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        List<ProductAttrValueEntity> collect = baseAttrs.stream().map(attr -> {
            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
            valueEntity.setAttrId(attr.getAttrId());
            AttrEntity id = attrService.getById(attr.getAttrId());
            valueEntity.setAttrName(id.getAttrName());
            valueEntity.setAttrValue(attr.getAttrValues());
            valueEntity.setQuickShow(attr.getShowDesc());
            valueEntity.setSpuId(infoEntity.getId());

            return valueEntity;
        }).collect(Collectors.toList());
        attrValueService.saveProductAttr(collect);

        // 5. ??????bounds???????????????class?????????2???????????? gulimall_sms.sms_spu_bounds(5??????)
        // ??? growBounds, buyBounds
        // ??? id, spu_id, grow_bounds, buy_bounds, work
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        BeanUtils.copyProperties(vo.getBounds(),spuBoundTo);
        spuBoundTo.setSpuId(infoEntity.getId());
        R r = couponFeignService.saveSpuBounds(spuBoundTo);
        if(r.getCode() != 0) {
            log.error("????????????spu bounds??????");
        }

        // 6. ??????skus???????????????List<class>?????????14?????????
        List<Skus> skus = vo.getSkus();

        if(skus != null && skus.size() > 0) {
            skus.forEach( item -> {

                // ??? sku_name, price, sku_title, sku_subtitle(4??????) ??? pms_sku_info(11??????)
                //    - ???????????? + sku_id, spu_id,  sku_desc, catelog_id, brand_id, sku_default_img, sale_count
                //    ?????????spu_id, catelog_id, brand_id??????3?????????SPU???
                //    ?????????sku_id????????????????????????
                //    ????????????sku_desc, sku_default_img, sale_count
                String defaultImg = "";
                for(Images img : item.getImages()) {
                    if(img.getDefaultImg() == 1) {
                        defaultImg = img.getImgUrl();
                    }
                }

                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item,skuInfoEntity);
                skuInfoEntity.setSpuId(infoEntity.getId());
                skuInfoEntity.setCatalogId(infoEntity.getCatalogId());
                skuInfoEntity.setBrandId(infoEntity.getBrandId());
                skuInfoEntity.setSaleCount(0L);
                skuInfoEntity.setSkuDefaultImg(defaultImg);
                skuInfoService.saveSkuInfo(skuInfoEntity);

                Long skuId = skuInfoEntity.getSkuId();

                // ??? attr??????(??????List<class>?????????3??????) ??? pms_sku_sale_attr_value(6??????)
                //    - attrId, attrName, attrValue
                //    - id, sku_id, attr_id, attr_name, attr_value, attr_sort
                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = item.getAttr().stream().map(attr -> {
                    SkuSaleAttrValueEntity skuSaleAttrValueEntity = new SkuSaleAttrValueEntity();
                    BeanUtils.copyProperties(attr, skuSaleAttrValueEntity);
                    skuSaleAttrValueEntity.setSkuId(skuId);
                    return skuSaleAttrValueEntity;
                }).collect(Collectors.toList());
                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);

                // ??? images??????(??????List<class>?????????2??????) ??? pms_sku_images(5??????)
                //    - imgUrl, defaultImg
                //    - id, sku_id, img_url, img_sort, default_img
                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    BeanUtils.copyProperties(img, skuImagesEntity);
                    skuImagesEntity.setSkuId(skuId);
                    return skuImagesEntity;
                }).filter( entity -> {
                    return !StringUtils.isEmpty(entity.getImgUrl());
                }).collect(Collectors.toList());
                skuImagesService.saveBatch(imagesEntities);

                // ??? full_account, discount??????, countStatus(3??????) ??? gulimall_sms.sms_sku_ladder(6??????)
                //    - id, sku_id, full_count, discount, price, add_other
                //    - countStatus????????????add_other

                // ??? full_price, reduce_price, priceStatus(3??????) ??? gulimall_sms.sms_sku_full_reduction(5??????)
                //    - id, sku_id, full_price, reduce_price, add_other
                //    - priceStatus????????????add_other

                // ??? member_price(??????List<class>?????????3??????) ??? gulimall_sms.sms_member_price(6??????)
                //    - id, name, price
                //    - id, sku_id, member_level_id, member_level_name, member_price, add_other

                SkuReductionTo skuReductionTo = new SkuReductionTo();
                // TODO ??????????????????memberPrice??????
                BeanUtils.copyProperties(item,skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if(skuReductionTo.getFullCount() >0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1){
                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
                    if(r1.getCode() != 0){
                        log.error("????????????sku??????????????????");
                    }
                }

            });
        }

        // ??? descar??????(??????List<String>)
    }

//    @Transactional
//    @Override
//    public void saveSpuInfo(SpuSaveVo vo) {
//
//        //1?????????spu???????????? pms_spu_info
//        SpuInfoEntity infoEntity = new SpuInfoEntity();
//        BeanUtils.copyProperties(vo,infoEntity);
//        infoEntity.setCreateTime(new Date());
//        infoEntity.setUpdateTime(new Date());
//        this.saveBaseSpuInfo(infoEntity);
//
//        //2?????????Spu??????????????? pms_spu_info_desc
//        List<String> decript = vo.getDecript();
//        SpuInfoDescEntity descEntity = new SpuInfoDescEntity();
//        descEntity.setSpuId(infoEntity.getId());
//        descEntity.setDecript(String.join(",",decript));
//        spuInfoDescService.saveSpuInfoDesc(descEntity);
//
//
//
//        //3?????????spu???????????? pms_spu_images
//        List<String> images = vo.getImages();
//        spuImagesService.saveImages(infoEntity.getId(),images);
//
//
//        //4?????????spu???????????????;pms_product_attr_value
//        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
//        List<ProductAttrValueEntity> collect = baseAttrs.stream().map(attr -> {
//            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
//            valueEntity.setAttrId(attr.getAttrId());
//            AttrEntity id = attrService.getById(attr.getAttrId());
//            valueEntity.setAttrName(id.getAttrName());
//            valueEntity.setAttrValue(attr.getAttrValues());
//            valueEntity.setQuickShow(attr.getShowDesc());
//            valueEntity.setSpuId(infoEntity.getId());
//
//            return valueEntity;
//        }).collect(Collectors.toList());
//        attrValueService.saveProductAttr(collect);
//
//
//        //5?????????spu??????????????????gulimall_sms->sms_spu_bounds
//        Bounds bounds = vo.getBounds();
//        SpuBoundTo spuBoundTo = new SpuBoundTo();
//        BeanUtils.copyProperties(bounds,spuBoundTo);
//        spuBoundTo.setSpuId(infoEntity.getId());
//        R r = couponFeignService.saveSpuBounds(spuBoundTo);
//        if(r.getCode() != 0){
//            log.error("????????????spu??????????????????");
//        }
//
//
//        //5???????????????spu???????????????sku?????????
//
//        List<Skus> skus = vo.getSkus();
//        if(skus!=null && skus.size()>0){
//            skus.forEach(item->{
//                String defaultImg = "";
//                for (Images image : item.getImages()) {
//                    if(image.getDefaultImg() == 1){
//                        defaultImg = image.getImgUrl();
//                    }
//                }
//                //    private String skuName;
//                //    private BigDecimal price;
//                //    private String skuTitle;
//                //    private String skuSubtitle;
//                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
//                BeanUtils.copyProperties(item,skuInfoEntity);
//                skuInfoEntity.setBrandId(infoEntity.getBrandId());
//                skuInfoEntity.setCatalogId(infoEntity.getCatalogId());
//                skuInfoEntity.setSaleCount(0L);
//                skuInfoEntity.setSpuId(infoEntity.getId());
//                skuInfoEntity.setSkuDefaultImg(defaultImg);
//                //5.1??????sku??????????????????pms_sku_info
//                skuInfoService.saveSkuInfo(skuInfoEntity);
//
//                Long skuId = skuInfoEntity.getSkuId();
//
//                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
//                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
//                    skuImagesEntity.setSkuId(skuId);
//                    skuImagesEntity.setImgUrl(img.getImgUrl());
//                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
//                    return skuImagesEntity;
//                }).filter(entity->{
//                    //??????true???????????????false????????????
//                    return !StringUtils.isEmpty(entity.getImgUrl());
//                }).collect(Collectors.toList());
//                //5.2??????sku??????????????????pms_sku_image
//                skuImagesService.saveBatch(imagesEntities);
//                //TODO ?????????????????????????????????
//
//                List<Attr> attr = item.getAttr();
//                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attr.stream().map(a -> {
//                    SkuSaleAttrValueEntity attrValueEntity = new SkuSaleAttrValueEntity();
//                    BeanUtils.copyProperties(a, attrValueEntity);
//                    attrValueEntity.setSkuId(skuId);
//
//                    return attrValueEntity;
//                }).collect(Collectors.toList());
//                //5.3??????sku????????????????????????pms_sku_sale_attr_value
//                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);
//
//                // //5.4??????sku??????????????????????????????gulimall_sms->sms_sku_ladder\sms_sku_full_reduction\sms_member_price
//                SkuReductionTo skuReductionTo = new SkuReductionTo();
//                BeanUtils.copyProperties(item,skuReductionTo);
//                skuReductionTo.setSkuId(skuId);
//                if(skuReductionTo.getFullCount() >0 || skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) == 1){
//                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
//                    if(r1.getCode() != 0){
//                        log.error("????????????sku??????????????????");
//                    }
//                }
//
//
//
//            });
//        }
//
//    }

    @Override
    public void saveBaseSpuInfo(SpuInfoEntity spuInfoEntity) {
        this.baseMapper.insert(spuInfoEntity);
    }


    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {

        QueryWrapper<SpuInfoEntity> wrapper = new QueryWrapper<>();

        String key = (String) params.get("key");
        if(!org.springframework.util.StringUtils.isEmpty(key)){
            wrapper.and((w)->{
                w.eq("id",key).or().like("spu_name",key);
            });
        }
        // status=1 and (id=1 or spu_name like xxx)
        String status = (String) params.get("status");
        if(!org.springframework.util.StringUtils.isEmpty(status)){
            wrapper.eq("publish_status",status);
        }

        String brandId = (String) params.get("brandId");
        if(!org.springframework.util.StringUtils.isEmpty(brandId)&&!"0".equalsIgnoreCase(brandId)){
            wrapper.eq("brand_id",brandId);
        }

        String catelogId = (String) params.get("catelogId");
        if(!org.springframework.util.StringUtils.isEmpty(catelogId)&&!"0".equalsIgnoreCase(catelogId)){
            wrapper.eq("catalog_id",catelogId);
        }

        /**
         * status: 2
         * key:
         * brandId: 9
         * catelogId: 225
         */

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    /**
     * ????????????
     * @param spuId
     */
    @Override
    public void up(Long spuId) {
        // ??????spuId????????????SkuEntity
        List<SkuInfoEntity> skuInfoEntities = skuInfoService.getSkuIdsBySpuId(spuId);


        // ???spuId??????List<Attrs>
        // ??? ???spuId????????????ProductAttrValueEntity
        List<ProductAttrValueEntity> baseAttrs= productAttrValueService.baseAttrListforspu(spuId);
        // ??? ?????????ProductAttrValueEntity???id????????????searchType=1???id
        List<Long> attrIds = baseAttrs.stream().map(ProductAttrValueEntity::getAttrId).collect(Collectors.toList());
        List<Long> searchAttrIds = attrService.selectSearchAttrIds(attrIds);
        HashSet<Long> idSet = new HashSet<>(searchAttrIds);
        // ??? ???searchType=1???ProductAttrValueEntity?????????ES?????????attrs
        List<SkuEsModel.Attrs> attrsList = baseAttrs.stream().filter(item -> {
            return idSet.contains(item.getAttrId());
        }).map(item -> {
            SkuEsModel.Attrs attrs1 = new SkuEsModel.Attrs();
            BeanUtils.copyProperties(item, attrs1);
            return attrs1;
        }).collect(Collectors.toList());


        // ??????????????????????????????skuId???????????????
        List<Long> skuIdList = skuInfoEntities.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());
        Map<Long, Boolean> stockMap = null;
        try {
            R skusHasStock = wareFeignService.getSkusHasStock(skuIdList);
            stockMap = skusHasStock.getData(new TypeReference<List<SkuHasStockVo>>(){}).stream().collect(Collectors.toMap(SkuHasStockVo::getSkuId, SkuHasStockVo::getHasStock));
        }catch (Exception e) {
            log.error("???????????????????????????{}",e);
        }




        // ??????SkuInfoEntity??????SkuEsModel
        Map<Long, Boolean> finalStockMap = stockMap;
        List<SkuEsModel> upProducts = skuInfoEntities.stream().map(sku -> {
            SkuEsModel esModel = new SkuEsModel();

            // ??? ???SkuInfoEntity???????????????
            BeanUtils.copyProperties(sku,esModel);
            esModel.setSkuPrice(sku.getPrice());
            esModel.setSkuImg(sku.getSkuDefaultImg());

            // ??? ???brandName???brandImg
            BrandEntity brandEntity = brandService.getById(sku.getBrandId());
            esModel.setBrandName(brandEntity.getName());
            esModel.setBrandImg(brandEntity.getLogo());

            // ??? ???catelogName
            CategoryEntity categoryEntity = categoryService.getById(sku.getCatalogId());
            esModel.setCatalogName(categoryEntity.getName());

            // ??? ???hotScore
            esModel.setHotScore(0L);

            // ??? ???attrs?????????search_type=1???attr?????????sku??????spu???attr?????????????????????
            esModel.setAttrs(attrsList);

            // ??? ???hasStock??????module???????????????module?????????????????????sku????????????????????????????????????
            // ???????????????????????????????????????"?????????"
            if(finalStockMap == null) {
                esModel.setHasStock(true);
            } else {
                esModel.setHasStock(finalStockMap.get(sku.getSkuId()));
            }

            return esModel;
        }).collect(Collectors.toList());

        // ???skuEsModel?????????ES
        R r = searchFeignService.productStatusUp(upProducts);
        if(r.getCode() == 0) {
            // ????????????????????????spu???status???"????????????"
            baseMapper.updateSpuStatus(spuId, ProductConstant.StatusEnum.SPU_UP.getCode());
        } else {
            // TODO ??????????????????????????????????????????????????????
        }

    }

    @Override
    public SpuInfoEntity getSpuInfoBySkuId(Long skuId) {
        SkuInfoEntity skuInfoEntity = skuInfoService.getById(skuId);
        Long spuId = skuInfoEntity.getSpuId();
        return this.getById(spuId);
    }

}