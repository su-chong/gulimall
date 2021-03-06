package com.atguigu.gulimall.ware.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.to.es.SkuHasStockVo;
import com.atguigu.common.to.mq.OrderTo;
import com.atguigu.common.to.mq.StockDetailTo;
import com.atguigu.common.to.mq.StockLockedTo;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.ware.dao.WareSkuDao;
import com.atguigu.gulimall.ware.entity.WareOrderTaskDetailEntity;
import com.atguigu.gulimall.ware.entity.WareOrderTaskEntity;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.atguigu.gulimall.ware.exception.NoStockException;
import com.atguigu.gulimall.ware.feign.OrderFeignService;
import com.atguigu.gulimall.ware.feign.ProductFeignService;
import com.atguigu.gulimall.ware.service.WareOrderTaskDetailService;
import com.atguigu.gulimall.ware.service.WareOrderTaskService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.atguigu.gulimall.ware.vo.OrderItemVo;
import com.atguigu.gulimall.ware.vo.OrderVo;
import com.atguigu.gulimall.ware.vo.WareSkuLockVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.Data;
import org.apache.commons.lang.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service("wareSkuService")
public class WareSkuServiceImpl extends ServiceImpl<WareSkuDao, WareSkuEntity> implements WareSkuService {

    @Autowired
    OrderFeignService orderFeignService;

    @Autowired
    WareOrderTaskDetailService wareOrderTaskDetailService;

    @Autowired
    WareOrderTaskService wareOrderTaskService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    WareOrderTaskDetailServiceImpl taskDetailService;

    @Autowired
    WareSkuDao wareSkuDao;

    @Autowired
    ProductFeignService productFeignService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<WareSkuEntity> wrapper = new QueryWrapper<>();

        String skuId = (String) params.get("skuId");
        if (!StringUtils.isEmpty(skuId)) {
            wrapper.eq("sku_id", skuId);
        }

        String wareId = (String) params.get("wareId");
        if (!StringUtils.isEmpty(wareId)) {
            wrapper.eq("ware_id", wareId);
        }

        IPage<WareSkuEntity> page = this.page(
                new Query<WareSkuEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void addStock(Long skuId, Long wareId, Integer skuNum) {
        List<WareSkuEntity> wareSkuEntities = wareSkuDao.selectList(new QueryWrapper<WareSkuEntity>().eq("sku_id", skuId).eq("ware_id", wareId));
        if (wareSkuEntities == null || wareSkuEntities.size() == 0) {
            WareSkuEntity wareSkuEntity = new WareSkuEntity();
            wareSkuEntity.setWareId(wareId);
            wareSkuEntity.setSkuId(skuId);
            wareSkuEntity.setStock(skuNum);
            wareSkuEntity.setStockLocked(0);

            try {
                R info = productFeignService.info(skuId);
                Map<String, Object> data = (Map<String, Object>) info.get("skuInfo");
                if (info.getCode() == 0) {
                    wareSkuEntity.setSkuName((String) data.get("skuName"));
                }
            } catch (Exception e) {

            }

            wareSkuDao.insert(wareSkuEntity);
        } else {
            wareSkuDao.addStock(skuId, wareId, skuNum);
        }
    }

    @Override
    public List<SkuHasStockVo> getSkusHasStock(List<Long> skuIds) {
        List<SkuHasStockVo> collect = skuIds.stream().map(item -> {
            Long count = baseMapper.getSkuStock(item);
            SkuHasStockVo vo = new SkuHasStockVo();
            vo.setSkuId(item);
            vo.setHasStock(count == null ? false : count > 0);
            return vo;
        }).collect(Collectors.toList());
        return collect;
    }

    /**
     * ???order?????????
     * @param vo
     * @return
     */
    @Transactional(rollbackFor = NoStockException.class)
    @Override
    public Boolean orderLockStock(WareSkuLockVo vo) {
        List<OrderItemVo> orderItemVos = vo.getLocks();

        // 0. [??????1] ??????WareOrderTaskEntity
        WareOrderTaskEntity taskEntity = new WareOrderTaskEntity();
        taskEntity.setOrderSn(vo.getOrderSn());
        wareOrderTaskService.save(taskEntity);


        // 1. ??????OrderItem?????????ware?????????
        List<SkuWareHasStock> stockVos = orderItemVos.stream().map(item -> {
            Long skuId = item.getSkuId();

            SkuWareHasStock skuWareHasStockVo = new SkuWareHasStock();
            // ???skuId??????
            skuWareHasStockVo.setSkuId(skuId);
            // ???num??????
            skuWareHasStockVo.setNum(item.getCount());
            // ???wareId??????
            List<Long> wareIds = wareSkuDao.listWareIdHasStock(skuId);
            skuWareHasStockVo.setWareIds(wareIds);

            return skuWareHasStockVo;
        }).collect(Collectors.toList());

        // 2. ??????OrderItem??????????????????ware???????????????
        for (SkuWareHasStock stockVo : stockVos) {
            Boolean locked = false;
            Long skuId = stockVo.getSkuId();
            List<Long> wareIds = stockVo.getWareIds();
            // ??????ware?????????????????????
            if(wareIds == null || wareIds.size() == 0) {
                throw new NoStockException(skuId);
            }

            for (Long wareId : wareIds) {
                Long count = wareSkuDao.lockSkuStock(skuId,wareId,stockVo.getNum());
                if(count == 1) {
                    // ?????????????????????????????????stockVo
                    locked = true;

                    // [??????2] ??????WareOrderTaskDetailEntity
                    WareOrderTaskDetailEntity taskDetailEntity = new WareOrderTaskDetailEntity(null, skuId, null, stockVo.getNum(), taskEntity.getId(), wareId, 1);
                    taskDetailService.save(taskDetailEntity);

                    // [??????3] ??????RabbitMQ
                    StockLockedTo stockLockedTo = new StockLockedTo();
                    // ??? ???id??????
                    stockLockedTo.setId(taskEntity.getId());
                    // ??? ???stockDetailTo??????
                    StockDetailTo detailTo = new StockDetailTo();
                    BeanUtils.copyProperties(taskDetailEntity, detailTo);
                    stockLockedTo.setDetailTo(detailTo);
                    // ??????
                    rabbitTemplate.convertAndSend("stock-event-exchange", "stock.locked", stockLockedTo);

                    break;
                } else {
                    // ?????????????????????????????????ware
                }
            }
            if(!locked) {
                // ???????????????????????????
                throw new NoStockException(skuId);
            }
        }

        // ????????????????????????????????????
        return true;
    }

    @Data
    class SkuWareHasStock {
        private Long skuId;
        private List<Long> wareIds;
        private Integer num;
    }

    /**
     * [????????????1] ?????????????????????????????????
     * @param lockedTo
     */
    @Override
    public void unlockStock(StockLockedTo lockedTo) {
        Long id = lockedTo.getId();
        StockDetailTo detailTo = lockedTo.getDetailTo();
        Long detailId = detailTo.getId();

        // 1. ??????WareOrderTaskDetailEntity???????????????
        WareOrderTaskDetailEntity detailEntity = wareOrderTaskDetailService.getById(detailId);
        if(detailEntity != null) {
            // 1.1 WareOrderTaskDetailEntity??????

            // 2. ??????OrderEntity???????????????
            WareOrderTaskEntity taskEntity = wareOrderTaskService.getById(id);
            String orderSn = taskEntity.getOrderSn();
            // ??????orderSn??????OrderEntity
            R r = orderFeignService.getOrderByOrderSn(orderSn);
            if(r.getCode() == 0) {
                OrderVo orderVo = r.getData(new TypeReference<OrderVo>() {
                });

                if(orderVo == null || orderVo.getStatus() == 4) {
                    // 2.1 OrderEntity????????????????????????
                    // >>> [???3?????????] ??????????????????????????????
                    if(detailEntity.getLockStatus() == 1) {
                        unlockStock(detailEntity.getSkuId(),detailEntity.getWareId(), detailEntity.getSkuNum(),detailId);
                    }

                } else {
                    // 2.2 OrderEntity?????????????????????
                    // >>> ?????????????????????????????????
                }
            } else {
                // ??????????????????
                // >>> [???2?????????] ???????????????
                throw new RuntimeException("???????????????,??????????????????");
            }

        } else {
            // 1.2 WareOrderTaskDetailEntity??????????????????????????????????????????????????????
            // >>> [???1?????????] ?????????????????????????????????
        }
    }


    /**
     * [????????????2] ??????????????????????????????
     * ??????????????????????????????"??????????????????"??????"??????????????????"???????????????????????????????????????
     * @param orderTo
     */
    @Override
    public void unlockStock(OrderTo orderTo) {
        String orderSn = orderTo.getOrderSn();
        WareOrderTaskEntity taskEntity = wareOrderTaskService.getByOrderSn(orderSn);
        List<WareOrderTaskDetailEntity> detailEntities = wareOrderTaskDetailService.list(new QueryWrapper<WareOrderTaskDetailEntity>()
                .eq("task_id", taskEntity.getId())
                .eq("lock_status", 1));
        for (WareOrderTaskDetailEntity entity : detailEntities) {
            unlockStock(entity.getSkuId(), entity.getWareId(), entity.getSkuNum(), entity.getId());
        }
    }


    /**
     * [?????????????????????] ??????????????????????????????????????????
     * ???????????? ???stock_locked ??? ???WareOrderTaskDetailEntity.lockStatus
     * @param skuId
     * @param wareId
     * @param num
     * @param taskDetailId
     */
    private void unlockStock(Long skuId, Long wareId, Integer num, Long taskDetailId) {
        wareSkuDao.unlockStock(skuId, wareId, num);

        WareOrderTaskDetailEntity updateEntity = new WareOrderTaskDetailEntity();
        updateEntity.setId(taskDetailId);
        updateEntity.setLockStatus(2);
        wareOrderTaskDetailService.updateById(updateEntity);
    }

}

