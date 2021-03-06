package com.atguigu.gulimall.product.service.impl;

import com.atguigu.common.constant.ProductConstant;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.gulimall.product.dao.AttrAttrgroupRelationDao;
import com.atguigu.gulimall.product.dao.AttrDao;
import com.atguigu.gulimall.product.dao.AttrGroupDao;
import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.AttrAttrgroupRelationEntity;
import com.atguigu.gulimall.product.entity.AttrEntity;
import com.atguigu.gulimall.product.entity.AttrGroupEntity;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.service.AttrService;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.vo.AttrVo;
import com.atguigu.gulimall.product.vo.AttroRespVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service("attrService")
public class AttrServiceImpl extends ServiceImpl<AttrDao, AttrEntity> implements AttrService {

    @Autowired
    AttrAttrgroupRelationDao relationDao;

    @Autowired
    AttrGroupDao attrGroupDao;

    @Autowired
    CategoryDao categoryDao;

    @Autowired
    CategoryService categoryService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<AttrEntity> page = this.page(
                new Query<AttrEntity>().getPage(params),
                new QueryWrapper<AttrEntity>()
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void saveAttr(AttrVo attr) {
        // ??????attrEntity?????????
        AttrEntity attrEntity = new AttrEntity();
        BeanUtils.copyProperties(attr,attrEntity);
        this.save(attrEntity);

        // ??????attrgroup?????????
        if(attr.getAttrType() == ProductConstant.AttrEnum.ATTR_TYPE_BASE.getCode() && attr.getAttrGroupId()!=null) {
            AttrAttrgroupRelationEntity relation = new AttrAttrgroupRelationEntity();
            relation.setAttrId(attrEntity.getAttrId()); // ?????????attr.getAttrId()?????????
            relation.setAttrGroupId(attr.getAttrGroupId());
            relationDao.insert(relation);
        }

    }

    @Override
    public PageUtils queryBaseAttrPage(Map<String, Object> params, Long catelogId, String type) {
        QueryWrapper<AttrEntity> wrapper = new QueryWrapper<AttrEntity>().eq("attr_type","base".equalsIgnoreCase(type)? ProductConstant.AttrEnum.ATTR_TYPE_BASE.getCode(): ProductConstant.AttrEnum.ATTR_TYPE_SALE.getCode());

        // ?????? catelogId != 0??????wrapper???????????????
        if(catelogId != 0) {
            wrapper.eq("catelog_id",catelogId);
        }

        // ?????? key????????????wrapper??????key?????????
        String key = (String) params.get("key");
        if(!Strings.isEmpty(key)) {
            wrapper.and( (w) -> {
                w.eq("attr_id",key).or().like("attr_name",key);
            });
        }

        IPage<AttrEntity> page = this.page(
                new Query<AttrEntity>().getPage(params),
                wrapper
        );

        PageUtils pageUtils = new PageUtils(page);
        List<AttrEntity> records = page.getRecords();
        List<AttroRespVo> respVos = records.stream().map((attrEntity) -> {
            AttroRespVo attroRespVo = new AttroRespVo();
            BeanUtils.copyProperties(attrEntity, attroRespVo);

            // ???groupName
            if("base".equalsIgnoreCase(type)){
                AttrAttrgroupRelationEntity relationEntity = relationDao.selectOne(new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attrEntity.getAttrId()));
                if (relationEntity != null) {
                    AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(relationEntity.getAttrGroupId());
                    attroRespVo.setGroupName(attrGroupEntity.getAttrGroupName());
                }
            }

            // ???catelogName
            CategoryEntity categoryEntity = categoryDao.selectById(attrEntity.getCatelogId());
            if (categoryEntity != null) {
                attroRespVo.setCatelogName(categoryEntity.getName());
            }

            return attroRespVo;
        }).collect(Collectors.toList());

        pageUtils.setList(respVos);

        return pageUtils;
    }

    @Cacheable(value = {"attr"},key = "'attrinfo:'+#root.args[0]")
    @Override
    public AttroRespVo getAttrInfo(Long attrId) {
        AttroRespVo attroRespVo = new AttroRespVo();

        // ???attrEntity??????property????????????
        AttrEntity attrEntity = this.getById(attrId);
        BeanUtils.copyProperties(attrEntity,attroRespVo);

        if(attrEntity.getAttrType() == ProductConstant.AttrEnum.ATTR_TYPE_BASE.getCode()){
            // ???AttrGroupId????????????
            AttrAttrgroupRelationEntity relationEntity = relationDao.selectOne(new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attrEntity.getAttrId()));
            if(relationEntity != null) {
                Long groupId = relationEntity.getAttrGroupId();
                attroRespVo.setAttrGroupId(groupId);

                // ???attrgroupName????????????
                AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(groupId);
                if(attrGroupEntity != null) {
                    attroRespVo.setGroupName(attrGroupEntity.getAttrGroupName());
                }
            }
        }

        // ???catelogName????????????
        Long catelogId = attrEntity.getCatelogId();
        CategoryEntity categoryEntity = categoryDao.selectById(catelogId);
        if(categoryEntity != null) {
            attroRespVo.setCatelogName(categoryEntity.getName());
        }

        // ???catelogPath????????????
        Long[] categoryPath = categoryService.findCategoryPath(catelogId);
        attroRespVo.setCatelogPath(categoryPath);

        return attroRespVo;
    }

    @Override
    public void updateAttr(AttrVo attr) {
        // ??????AttrEntity???????????????
        AttrEntity attrEntity = new AttrEntity();
        BeanUtils.copyProperties(attr,attrEntity);
        this.updateById(attrEntity);

        if(attrEntity.getAttrType() == ProductConstant.AttrEnum.ATTR_TYPE_BASE.getCode()){
            // ???attrGroupId????????????
            Long attrId = attrEntity.getAttrId();
            AttrAttrgroupRelationEntity relationEntity = new AttrAttrgroupRelationEntity();
            relationEntity.setAttrId(attrId);
            relationEntity.setAttrGroupId(attr.getAttrGroupId());

            Integer count = relationDao.selectCount(new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attrId));
            if(count > 0) {
                relationDao.update(relationEntity,new UpdateWrapper<AttrAttrgroupRelationEntity>().eq("attr_id",attrId));
            } else {
                relationDao.insert(relationEntity);
            }
        }

    }

    @Override
    public List<AttrEntity> getRelationAttr(Long attrGroupId) {
        List<AttrAttrgroupRelationEntity> entities = relationDao.selectList(new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_group_id", attrGroupId));
        List<Long> attrs = entities.stream().map((attr) -> {
            return attr.getAttrId();
        }).collect(Collectors.toList());

        if(attrs == null || attrs.size()==0) {
            return null;
        }
        List<AttrEntity> attrEntities = this.listByIds(attrs);
        return attrEntities;
    }

    @Override
    public PageUtils getNoRelationAttr(Map<String, Object> params, Long attrgroupId) {
        QueryWrapper<AttrEntity> wrapper = new QueryWrapper<AttrEntity>();

        // ??????1???catelogId????????????attrgroup???catelogId??????
        AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(attrgroupId);
        Long catelogId = attrGroupEntity.getCatelogId();
        wrapper.eq("catelog_id", catelogId);

        // ??????2:??????????????????attrgroup
        // ??? ????????????catelogId????????????attrgroupId
        List<AttrGroupEntity> attrGroupEntities = attrGroupDao.selectList(new QueryWrapper<AttrGroupEntity>().eq("catelog_id", catelogId));
        List<Long> attrGroupIds = attrGroupEntities.stream().map((item) -> {
            return item.getAttrGroupId();
        }).collect(Collectors.toList());

        // ??? ????????????attrgroupId????????????attrId
        List<AttrAttrgroupRelationEntity> relationEntities = relationDao.selectList(new QueryWrapper<AttrAttrgroupRelationEntity>().in("attr_group_id", attrGroupIds));
        List<Long> attrIds = relationEntities.stream().map((item) -> {
            return item.getAttrId();
        }).collect(Collectors.toList());

        // ??? ????????????attrId??????????????????attr
        if(attrIds != null && attrIds.size()>0) {
            wrapper.notIn("attr_id",attrIds);
        }

        // ??????3:???key???????????????????????????????????????
        String key = (String) params.get("key");
        if(!Strings.isEmpty(key)) {
            wrapper.and( (w) -> {
                w.eq("attr_id",key).or().like("attr_name",key);
            });
        }

        IPage<AttrEntity> page = this.page(new Query<AttrEntity>().getPage(params), wrapper);
        PageUtils pageUtils = new PageUtils(page);
        return pageUtils;
    }

    /**
     * ?????????attrId?????????searchType=1????????????
     * @param attrIds
     * @return
     */
    @Override
    public List<Long> selectSearchAttrIds(List<Long> attrIds) {
        return baseMapper.selectSearchAttrIds(attrIds);
    }

}