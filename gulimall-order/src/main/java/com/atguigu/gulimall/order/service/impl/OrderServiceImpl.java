package com.atguigu.gulimall.order.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.to.es.SkuHasStockVo;
import com.atguigu.common.to.mq.OrderTo;
import com.atguigu.common.to.mq.SeckillOrderTo;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.common.utils.R;
import com.atguigu.common.vo.MemberRespVo;
import com.atguigu.gulimall.order.constant.OrderConstant;
import com.atguigu.gulimall.order.dao.OrderDao;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.entity.PaymentInfoEntity;
import com.atguigu.gulimall.order.enume.OrderStatusEnum;
import com.atguigu.gulimall.order.feign.CartFeignService;
import com.atguigu.gulimall.order.feign.MemberFeignService;
import com.atguigu.gulimall.order.feign.ProductFeignService;
import com.atguigu.gulimall.order.feign.WareFeignService;
import com.atguigu.gulimall.order.interceptor.LoginUserInterceptor;
import com.atguigu.gulimall.order.service.OrderItemService;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.service.PaymentInfoService;
import com.atguigu.gulimall.order.to.OrderCreateTo;
import com.atguigu.gulimall.order.vo.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

@Slf4j
@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    @Autowired
    PaymentInfoService paymentInfoService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    OrderItemService orderItemService;

    @Autowired
    ProductFeignService productFeignService;

    private ThreadLocal<OrderSubmitVo> submitVoThreadLocal = new ThreadLocal<>();

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    WareFeignService wareFeignService;

    @Autowired
    MemberFeignService memberFeignService;

    @Autowired
    CartFeignService cartFeignService;

    @Autowired
    ThreadPoolExecutor executor;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    // ???????????????(confirm.html)???????????????
    @Override
    public OrderConfirmVo confirmOrder() throws ExecutionException, InterruptedException {
        MemberRespVo member = LoginUserInterceptor.loginUser.get();
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();

        // ??????OrderConfirmVo
        OrderConfirmVo confirmVo = new OrderConfirmVo();

        CompletableFuture<Void> getAddressFuture = CompletableFuture.runAsync(() -> {
            // ??????????????????request context
            RequestContextHolder.setRequestAttributes(requestAttributes);
            // ???List<MemberAddressVo>??????
            List<MemberAddressVo> address = memberFeignService.getAddress(member.getId());
            confirmVo.setAddress(address);
        }, executor);


        CompletableFuture<Void> cartFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(requestAttributes);
            // ???List<OrderItemVo>??????
            List<OrderItemVo> currentUserCartItems = cartFeignService.getCurrentUserCartItems();
            confirmVo.setItems(currentUserCartItems);
        }, executor).thenRunAsync(() -> {
            // ???stocks??????
            List<Long> skuIds = confirmVo.getItems().stream().map(OrderItemVo::getSkuId).collect(Collectors.toList());
            RequestContextHolder.setRequestAttributes(requestAttributes);
            R r = wareFeignService.getSkusHasStock(skuIds);
            List<SkuHasStockVo> stockVos = r.getData(new TypeReference<List<SkuHasStockVo>>() {
            });
            Map<Long, Boolean> collect = stockVos.stream().collect(Collectors.toMap(SkuHasStockVo::getSkuId, SkuHasStockVo::getHasStock));
            confirmVo.setStocks(collect);
        }, executor);

        // ???integration??????
        Integer integration = member.getIntegration();
        confirmVo.setIntegration(integration);

        // total??????????????????
        // payPrice??????????????????

        // ???token??????
        String token = UUID.randomUUID().toString().replace("-", "");
        confirmVo.setOrderToken(token);
        redisTemplate.opsForValue().set(OrderConstant.SUBMIT_ORDER_TOKEN_PREFIX + member.getId(), token);

        CompletableFuture.allOf(getAddressFuture, cartFuture).get();

        return confirmVo;
    }

    //    @GlobalTransactional
    @Transactional
    @Override
    public SubmitOrderResponseVo submitOrder(OrderSubmitVo vo) {
        // ???OrderSubmitVo??????ThreadLocal???
        submitVoThreadLocal.set(vo);

        SubmitOrderResponseVo response = new SubmitOrderResponseVo();
        response.setCode(0);

        // 1. ????????????token
        // ??? ???Redis??????token
        MemberRespVo member = LoginUserInterceptor.loginUser.get();
        String cartKey = OrderConstant.SUBMIT_ORDER_TOKEN_PREFIX + member.getId();
        String orderToken1 = redisTemplate.opsForValue().get(cartKey);

        // ??? ?????????????????????token
        String orderToken2 = vo.getOrderToken();

        // ??? redis??????
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

        // ??? ????????????orderToken
        Long result = redisTemplate.execute(new DefaultRedisScript<>(script, Long.class), Arrays.asList(cartKey), orderToken2);

        if (result == 0L) {
            // token??????????????????????????????1
            response.setCode(1);
            return response;
        } else {
            // token????????????

            // 2. ????????????(OrderCreateTo)
            OrderCreateTo order = createOrder();

            // 3. ??????
            BigDecimal payPrice = vo.getPayPrice();
            BigDecimal payAmount = order.getOrderEntity().getPayAmount();
            if (Math.abs(payAmount.subtract(payPrice).doubleValue()) < 0.01) {
                // 3.1 ????????????

                // 4. ??????????????????
                saveOrder(order);

                // 5. ?????????
                WareSkuLockVo lockVo = new WareSkuLockVo();
                lockVo.setOrderSn(order.getOrderEntity().getOrderSn());
                List<OrderItemVo> orderItemVos = order.getEntities().stream().map(item -> {
                    OrderItemVo orderItemVo = new OrderItemVo();
                    orderItemVo.setSkuId(item.getSkuId());
                    orderItemVo.setCount(item.getSkuQuantity());
                    orderItemVo.setTitle(item.getSpuName());
                    return orderItemVo;
                }).collect(Collectors.toList());
                lockVo.setLocks(orderItemVos);
                R r = wareFeignService.orderLockStock(lockVo);

                if (r.getCode() == 0) {
                    // ????????????
                    response.setOrderEntity(order.getOrderEntity());
                    // ??????
                    // int i = 10 / 0;

                    // 6. ???RabbitMQ?????????
                    rabbitTemplate.convertAndSend("order-event-exchange", "order.create.order", order.getOrderEntity());
                    return response;
                } else {
                    // ????????????
                    response.setCode(3);
                    return response;
                }

            } else {
                // 3.2 ??????????????????????????????2
                response.setCode(2);
                return response;
            }
        }
    }

    @Override
    public OrderEntity getOrderByOrderSn(String orderSn) {
        OrderEntity orderEntity = this.getOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));
        return orderEntity;
    }

    /**
     * ????????????
     * ????????????????????????"?????????"(???OrderEntity.status=1)
     * ???????????? ???OrderEntity.status;??? ????????????order.release.order.queue
     *
     * @param orderEntity
     */
    @Override
    public void closeOrder(OrderEntity orderEntity) {
        // ?????????????????????"?????????"?????????
        Long id = orderEntity.getId();
        OrderEntity byId = this.getById(id);
        if (byId.getStatus() == OrderStatusEnum.CREATE_NEW.getCode()) {
            OrderEntity update = new OrderEntity();
            update.setId(id);
            update.setStatus(OrderStatusEnum.CANCLED.getCode());
            this.updateById(update);
        }
        // ????????????order.release.other
        OrderTo orderTo = new OrderTo();
        BeanUtils.copyProperties(byId, orderTo);
        rabbitTemplate.convertAndSend("order-event-exchange", "order.release.other", orderTo);
    }


    // ??????4:??????OrderEntity???OrderItemEntity????????????
    private void saveOrder(OrderCreateTo order) {
        // ??????OrderEntity
        OrderEntity orderEntity = order.getOrderEntity();
        orderEntity.setModifyTime(new Date());
        this.save(orderEntity);

        // ??????OrderItemEntity
        List<OrderItemEntity> entities = order.getEntities();
        orderItemService.saveBatch(entities);
    }

    // ???method?????????OrderCreateTo
    private OrderCreateTo createOrder() {
        OrderCreateTo orderCreateTo = new OrderCreateTo();
        // ???orderEntity??????
        OrderEntity orderEntity = buildOrderEntity();
        orderCreateTo.setOrderEntity(orderEntity);

        // ???List<OrderItemEntity>??????
        List<OrderItemEntity> orderItemEntities = buildOrderItemEntities(orderEntity.getOrderSn());
        orderCreateTo.setEntities(orderItemEntities);

        // ??????orderEntity
        computePrice(orderEntity, orderItemEntities);

        // ???payPrice??????

        // ???fare??????

        return orderCreateTo;
    }


    // ?????????1:???method?????????OrderEntity
    private OrderEntity buildOrderEntity() {
        OrderEntity orderEntity = new OrderEntity();

        // ???memberId??????
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        orderEntity.setMemberId(memberRespVo.getId());

        // ???orderSn??????
        String timeId = IdWorker.getTimeId();
        orderEntity.setOrderSn(timeId);

        // ???freightAmount??????
        OrderSubmitVo orderSubmitVo = submitVoThreadLocal.get();
        R fare = wareFeignService.getFare(orderSubmitVo.getAddrId());
        FareVo fareVo = fare.getData(new TypeReference<FareVo>() {
        });
        orderEntity.setFreightAmount(fareVo.getFare());

        // ???receiveName,receivePhone,receivePostCode,receiveProvince,receiveCity,receiveRegion,receiveDetailAddress??????
        MemberAddressVo address = fareVo.getAddress();
        orderEntity.setReceiverName(address.getName());
        orderEntity.setReceiverPhone(address.getPhone());
        orderEntity.setReceiverPostCode(address.getPostCode());
        orderEntity.setReceiverProvince(address.getProvince());
        orderEntity.setReceiverCity(address.getCity());
        orderEntity.setReceiverCity(address.getRegion());
        orderEntity.setReceiverDetailAddress(address.getDetailAddress());

        // ???autoConfirmDay??????
        orderEntity.setAutoConfirmDay(7);

        // ???status??????(????????????)
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());

        // ???deleteStatus??????
        orderEntity.setDeleteStatus(0);

        return orderEntity;
    }

    // ?????????2:???method?????????List<OrderItemEntity>
    private List<OrderItemEntity> buildOrderItemEntities(String orderSn) {
        List<OrderItemVo> cartItems = cartFeignService.getCurrentUserCartItems();
        if (cartItems != null && cartItems.size() > 0) {
            return cartItems.stream().map(item -> buildOrderItemEntity(item, orderSn)).collect(Collectors.toList());
        }
        return null;
    }

    // ?????????2.1:???method?????????OrderItemEntity,???????????????2
    private OrderItemEntity buildOrderItemEntity(OrderItemVo orderItemVo, String orderSn) {

        OrderItemEntity orderItemEntity = new OrderItemEntity();

        // 1. ???orderSn
        orderItemEntity.setOrderSn(orderSn);

        // 2. ???spuId,spuName,spuPic,spuBrand,categoryId
        R r = productFeignService.getSpuInfoBySkuId(orderItemVo.getSkuId());
        SpuInfoVo spuInfoVo = r.getData(new TypeReference<SpuInfoVo>() {
        });
        orderItemEntity.setSpuId(spuInfoVo.getId());
        orderItemEntity.setSpuName(spuInfoVo.getSpuName());
        orderItemEntity.setSpuPic(spuInfoVo.getSpuDescription());
        orderItemEntity.setSpuBrand(spuInfoVo.getBrandId().toString());
        orderItemEntity.setCategoryId(spuInfoVo.getCatalogId());

        // 3. ???skuId,skuName,skuPic,skuPrice,skuQuantity,skuAttrsVals
        orderItemEntity.setSkuId(orderItemVo.getSkuId());
        orderItemEntity.setSkuName(orderItemVo.getTitle());
        orderItemEntity.setSkuPic(orderItemVo.getImage());
        orderItemEntity.setSkuPrice(orderItemVo.getPrice());
        orderItemEntity.setSkuQuantity(orderItemVo.getCount());
        String s = StringUtils.collectionToDelimitedString(orderItemVo.getSkuAttr(), ";");
        orderItemEntity.setSkuAttrsVals(s);

        //  4. ???promotionAmount,couponAmount,integrationAmount,realAmount
        orderItemEntity.setPromotionAmount(new BigDecimal(0));
        orderItemEntity.setCouponAmount(new BigDecimal(0));
        orderItemEntity.setIntegrationAmount(new BigDecimal(0));
        orderItemEntity.setRealAmount(orderItemVo.getPrice()
                .subtract(orderItemEntity.getPromotionAmount())
                .subtract(orderItemEntity.getCouponAmount())
                .subtract(orderItemEntity.getIntegrationAmount()));

        // 5. ???giftIntegration,giftGrowth
        BigDecimal total = orderItemVo.getPrice().multiply(new BigDecimal(orderItemVo.getCount().toString()));
        orderItemEntity.setGiftGrowth(total.intValue());
        orderItemEntity.setGiftIntegration(total.intValue());

        return orderItemEntity;
    }

    // ?????????3:???method?????????OrderEntity?????????????????????????????????
    private void computePrice(OrderEntity orderEntity, List<OrderItemEntity> orderItemEntities) {
        BigDecimal promotionAmount = new BigDecimal(0);
        BigDecimal couponAmount = new BigDecimal(0);
        BigDecimal integrationAmount = new BigDecimal(0);
        BigDecimal totalAmount = new BigDecimal(0);

        Integer growth = 0;
        Integer integration = 0;

        if (orderItemEntities != null && orderItemEntities.size() > 0) {
            for (OrderItemEntity item : orderItemEntities) {
                promotionAmount = promotionAmount.add(item.getPromotionAmount());
                couponAmount = couponAmount.add(item.getCouponAmount());
                integrationAmount = integrationAmount.add(item.getIntegrationAmount());
                totalAmount = totalAmount.add(item.getSkuPrice().multiply(new BigDecimal(item.getSkuQuantity().toString())));

                growth += item.getGiftGrowth();
                integration += item.getGiftIntegration();
            }
        }

        // ???promotionAmount,couponAmount,integrationAmount,totalAmount??????
        orderEntity.setPromotionAmount(promotionAmount);
        orderEntity.setCouponAmount(couponAmount);
        orderEntity.setIntegrationAmount(integrationAmount);
        orderEntity.setTotalAmount(totalAmount);

        // ???payAmount??????(?????????)
        orderEntity.setPayAmount(totalAmount.add(orderEntity.getFreightAmount()));

        // ???growth,integration??????
        orderEntity.setGrowth(growth);
        orderEntity.setIntegration(integration);

    }

    // ???orderSn??????PayVo
    @Override
    public PayVo getPayOrder(String orderSn) {
        OrderEntity order = this.getOrderByOrderSn(orderSn);

        PayVo payVo = new PayVo();
        // ???out_trade_no??????
        payVo.setOut_trade_no(orderSn);
        // ???amount??????
        BigDecimal bigDecimal = order.getPayAmount().setScale(2, BigDecimal.ROUND_UP);
        payVo.setTotal_amount(bigDecimal.toString());
        // ???subject??????
        List<OrderItemEntity> items = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        OrderItemEntity item = items.get(0);
        payVo.setSubject(item.getSkuName());
        // ???body??????
        payVo.setBody(item.getSkuAttrsVals());

        return payVo;
    }

    @Override
    public PageUtils queryPageWithItem(Map<String, Object> params) {
        MemberRespVo memberRespVo = LoginUserInterceptor.loginUser.get();
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>().eq("member_id", memberRespVo.getId()).orderByDesc("id"));

        // ???OrderEntity???List<OrderItemEntity>???field
        List<OrderEntity> orderEntities = page.getRecords().stream().map(order ->
                {
                    List<OrderItemEntity> order_sn = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", order.getOrderSn()));
                    order.setItemEntities(order_sn);
                    return order;
                }
        ).collect(Collectors.toList());

        page.setRecords(orderEntities);

        return new PageUtils(page);
    }

    @Override
    @Transactional
    public String handlePayResult(PayAsyncVo vo) {
        // 1. ?????????payment_info???
        PaymentInfoEntity paymentInfo = new PaymentInfoEntity();
        paymentInfo.setOrderSn(vo.getOut_trade_no());
        paymentInfo.setAlipayTradeNo(vo.getTrade_no());
        paymentInfo.setCallbackTime(vo.getNotify_time());
        paymentInfo.setPaymentStatus(vo.getTrade_status());

        // ???payment_nfo??????orderSn???alipayTradeNo????????????unique
        paymentInfoService.save(paymentInfo);

        // 2. ??????????????????
        if (vo.getTrade_status().equals("TRADE_SUCCESS") || vo.getTrade_status().equals("TRADE_FINISHED")) {
            this.baseMapper.updateOrderStatus(vo.getOut_trade_no(), OrderStatusEnum.PAYED.getCode());
        }

        return "success";
    }

    @Override
    public void createSeckillOrder(SeckillOrderTo orderTo) {
        log.info("??????????????????");

        // ???????????????OrderEntity
        OrderEntity entity = new OrderEntity();
        entity.setOrderSn(orderTo.getOrderSn());
        entity.setMemberId(orderTo.getMemberId());
        entity.setCreateTime(new Date());
        entity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        BigDecimal price = orderTo.getSeckillPrice().multiply(new BigDecimal("" + orderTo.getNum()));
        entity.setPayAmount(price);
        this.save(entity);

        // ???????????????OrderItemEntity
        OrderItemEntity itemEntity = new OrderItemEntity();
        itemEntity.setOrderSn(orderTo.getOrderSn());
        itemEntity.setRealAmount(price);
        itemEntity.setSkuId(orderTo.getSkuId());
        itemEntity.setSkuQuantity(orderTo.getNum());
        orderItemService.save(itemEntity);
    }

}