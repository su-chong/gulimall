package com.atguigu.gulimall.seckill.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.to.mq.SeckillOrderTo;
import com.atguigu.common.utils.R;
import com.atguigu.common.vo.MemberRespVo;
import com.atguigu.gulimall.seckill.feign.CouponFeignService;
import com.atguigu.gulimall.seckill.feign.ProductFeignService;
import com.atguigu.gulimall.seckill.interceptor.LoginUserInterceptor;
import com.atguigu.gulimall.seckill.service.SeckillService;
import com.atguigu.gulimall.seckill.to.SeckillSkuRedisTo;
import com.atguigu.gulimall.seckill.vo.SeckillSessionWithSkusVo;
import com.atguigu.gulimall.seckill.vo.SkuInfoVo;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service("SeckillService")
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    CouponFeignService couponFeignService;

    private final String SESSIONS_CACHE_PREFIX = "seckill:session:";
    private final String SKUKILL_CACHE_PREFIX = "seckill:skus";
    private final String SKU_STOCK_SEMAPHORE = "seckill:stock:";

    @Override
    public void uploadSeckillSkuLatest3Days() {
        // ?????????3??????SeckillSessionWithSkus
        R r = couponFeignService.getLatest3DaySession();
        if (r.getCode() == 0) {
            List<SeckillSessionWithSkusVo> sessionData = r.getData(new TypeReference<List<SeckillSessionWithSkusVo>>() {
            });

            if(sessionData == null || sessionData.size() == 0) {
                System.out.println("??????3??????????????????");
            } else {
                // ??????????????????Redis
                saveSessionInfos(sessionData);

                // ?????????sku???Redis
                saveSessionSkuInfo(sessionData);

                System.out.println("??????????????????????????????");
            }
        }
    }



    /**
     * ????????????????????????
     *
     * @param sessions
     */
    private void saveSessionInfos(List<SeckillSessionWithSkusVo> sessions) {
        for (SeckillSessionWithSkusVo session : sessions) {

            // 1. ???key
            long startTime = session.getStartTime().getTime();
            long endTime = session.getEndTime().getTime();
            String key = SESSIONS_CACHE_PREFIX + startTime + "_" + endTime;

            // 2. ??????Redis??????????????????session
            Boolean hasKey = redisTemplate.hasKey(key);

            // 2.1 ????????????????????????session????????????
            // ????????????promotionSessionId?????????????????????????????????sku
            if (!hasKey) {
                List<String> skuIds = session.getRelationSkus().stream().map(item ->
                        item.getPromotionSessionId().toString()+"_"+ item.getSkuId().toString())
                        .collect(Collectors.toList());
                redisTemplate.opsForList().leftPushAll(key, skuIds);
            }

        }
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param sessions
     */
    private void saveSessionSkuInfo(List<SeckillSessionWithSkusVo> sessions) {
        sessions.stream().forEach(session -> {
            // ??????hash???????????????hash
            BoundHashOperations<String, Object, Object> ops = redisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
            session.getRelationSkus().stream().forEach(seckillSkuVo -> {

                String key = seckillSkuVo.getPromotionSessionId().toString() + "_" + seckillSkuVo.getSkuId().toString();

                // 1. ???Redis??????SeckillSku????????????????????????????????????
                if (!ops.hasKey(key)) {
                    SeckillSkuRedisTo redisTo = new SeckillSkuRedisTo();

                    // ??? ???SeckillSkuRelation????????????
                    BeanUtils.copyProperties(seckillSkuVo, redisTo);

                    // ??? ???SkuInfoEntity??????
                    R skuInfo = productFeignService.getSkuInfo(seckillSkuVo.getSkuId());
                    if (skuInfo.getCode() == 0) {
                        SkuInfoVo skuInfoVo = skuInfo.getData("skuInfo", new TypeReference<SkuInfoVo>() {
                        });
                        redisTo.setSkuInfo(skuInfoVo);
                    }

                    // ??? ???session???startTime???endTime
                    redisTo.setStartTime(session.getStartTime().getTime());
                    redisTo.setEndTime(session.getEndTime().getTime());

                    // ??? ??????????????????
                    String token = UUID.randomUUID().toString();
                    redisTo.setRandomCode(token);

                    // ??? ?????????json????????????Redis???
                    String jsonString = JSON.toJSONString(redisTo);
                    ops.put(key, jsonString);

                    // 2. ???Redis??????sku?????????????????????????????????semaphore
                    RSemaphore semaphore = redissonClient.getSemaphore(SKU_STOCK_SEMAPHORE + token);
                    semaphore.trySetPermits(seckillSkuVo.getSeckillCount());
                }
            });
        });
    }

    /**
     * ??????????????????????????????????????????
     * @return
     */
    @Override
    public List<SeckillSkuRedisTo> getCurrentSeckillSkus() {

        long time = new Date().getTime();

        try(Entry entry = SphU.entry("CurrentSeckillSkus")) {
            Set<String> keys = redisTemplate.keys(SESSIONS_CACHE_PREFIX + "*");
            for (String key : keys) {
                String replace = key.replace(SESSIONS_CACHE_PREFIX, "");
                String[] s = replace.split("_");
                long startTime = Long.parseLong(s[0]);
                long endTime = Long.parseLong(s[1]);
                if(time >= startTime && time <= endTime) {
                    // ??????session??????key
                    List<String> range = redisTemplate.opsForList().range(key, -100, 100);
                    BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
                    List<String> list = ops.multiGet(range);
                    if(list != null && list.size()>0) {
                        // ??????list??????json?????????????????????SeckillSkuRedisTo
                        List<SeckillSkuRedisTo> collect = list.stream().map(item -> {
                            SeckillSkuRedisTo redisTo = JSON.parseObject((String) item, SeckillSkuRedisTo.class);
                            return redisTo;
                        }).collect(Collectors.toList());
                        return collect;
                    }
                    break;
                }
            }
        }  catch (BlockException e) {
            log.error(e.getMessage());
        }

        return null;
    }

    @Override
    public SeckillSkuRedisTo getSkuSeckillInfo(Long skuId) {
        BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
        Set<String> keys = ops.keys();

        if(keys != null && keys.size() > 0) {
            // ??????????????????regx
            String regx = "\\d_" + skuId;
            for (String key : keys) {
                if(Pattern.matches(regx,key)) {
                    String json = ops.get(key);
                    SeckillSkuRedisTo redisTo = JSON.parseObject(json, SeckillSkuRedisTo.class);
                    // ?????????????????????????????????????????????randomCode??????
                    long time = new Date().getTime();
                    if(time >= redisTo.getStartTime() && time <= redisTo.getEndTime()) {
                    } else {
                        redisTo.setRandomCode(null);
                    }
                    return redisTo;
                }
            }
        }
        return null;
    }

    @Override
    public String kill(String killId, String key, Integer num) {

        BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(SKUKILL_CACHE_PREFIX);
        String json = ops.get(killId);

        // ??? redis???????????????id
        if(!StringUtils.isEmpty(json)) {
            SeckillSkuRedisTo redisTo = JSON.parseObject(json, SeckillSkuRedisTo.class);

            // ???  ???????????????????????????????????????
            long now = new Date().getTime();
            long start = redisTo.getStartTime();
            long end = redisTo.getEndTime();
            if(now >= start && now <= end) {

                // ??? ??????randomCode
                String skuId = redisTo.getPromotionSessionId() + "_" + redisTo.getSkuId();
                String randomCode = redisTo.getRandomCode();
                if(skuId.equals(killId) && randomCode.equals(key)) {

                    // ??? ??????num
                    if(num <= redisTo.getSeckillCount()) {

                        // ??? ?????????????????????????????????
                        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
                        String redisKey = memberRespVo.getId() + "_" + skuId;
                        long ttl = end - now;
                        Boolean aBoolean = redisTemplate.opsForValue().setIfAbsent(redisKey,num.toString(),ttl,TimeUnit.MILLISECONDS);
                        if(aBoolean) {

                            // ??????
                            RSemaphore semaphore = redissonClient.getSemaphore(SKU_STOCK_SEMAPHORE + randomCode);
                            boolean b = semaphore.tryAcquire(num);
                            if(b) {
                                String timeId = IdWorker.getTimeId();

                                // ???orderTo?????????rabbitMQ
                                SeckillOrderTo orderTo = new SeckillOrderTo();
                                orderTo.setOrderSn(timeId);
                                orderTo.setMemberId(memberRespVo.getId());
                                orderTo.setSkuId(redisTo.getSkuId());
                                orderTo.setSeckillPrice(redisTo.getSeckillPrice());
                                orderTo.setPromotionSessionId(redisTo.getPromotionSessionId());
                                orderTo.setNum(num);
                                rabbitTemplate.convertAndSend("order-event-exchange", "order.seckill.order", orderTo);

                                return timeId;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }
}
