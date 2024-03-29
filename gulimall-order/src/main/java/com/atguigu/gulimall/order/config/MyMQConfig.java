package com.atguigu.gulimall.order.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class MyMQConfig {

    @Value("${myRabbitmq.MQConfig.eventExchange}")
    private String eventExchange;

    @Value("${myRabbitmq.MQConfig.delayQueue}")
    private String delayQueue;

    @Value("${myRabbitmq.MQConfig.releaseQueue}")
    private String releaseQueue;

    @Value("${myRabbitmq.MQConfig.createOrderKey}")
    private String createOrderKey;

    @Value("${myRabbitmq.MQConfig.releaseOrderKey}")
    private String releaseOrderKey;

    @Value("${myRabbitmq.MQConfig.releaseOtherQueue}")
    private String ReleaseOtherQueue;

    @Value("${myRabbitmq.MQConfig.releaseOtherKey}")
    private String ReleaseOtherKey;

    @Value("${myRabbitmq.MQConfig.ttl}")
    private Integer ttl;

    /**
     * String name, boolean durable, boolean exclusive, boolean autoDelete,  @Nullable Map<String, Object> arguments
     */
    @Bean
    public Queue orderDelayQueue(){
        Map<String ,Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", eventExchange);
        arguments.put("x-dead-letter-routing-key", releaseOrderKey);
        arguments.put("x-message-ttl", ttl);
        Queue queue = new Queue(delayQueue, true, false, false, arguments);
        return queue;
    }

    @Bean
    public Queue orderReleaseOrderQueue(){
        Queue queue = new Queue(releaseQueue, true, false, false);
        return queue;
    }

    /**
     * String name, boolean durable, boolean autoDelete, Map<String, Object> arguments
     * @return
     */
    @Bean
    public Exchange orderEventExchange(){

        return new TopicExchange(eventExchange, true, false);
    }

    /**
     * String destination, DestinationType destinationType, String exchange, String routingKey, @Nullable Map<String, Object> arguments
     */
    @Bean
    public Binding orderCreateOrderBinding(){

        return new Binding(delayQueue, Binding.DestinationType.QUEUE, eventExchange, createOrderKey, null);
    }

    @Bean
    public Binding orderReleaseOrderBinding(){

        return new Binding(releaseQueue, Binding.DestinationType.QUEUE, eventExchange, releaseOrderKey, null);
    }

    /**
     * 订单释放直接和库存释放进行绑定
     */
    @Bean
    public Binding orderReleaseOtherBinding(){

        return new Binding(ReleaseOtherQueue, Binding.DestinationType.QUEUE, eventExchange, ReleaseOtherKey + ".#", null);
    }

    @Bean
    public Queue orderSecKillQueue(){
        return new Queue("order.seckill.order.queue", true, false, false);
    }

    @Bean
    public Binding orderSecKillQueueBinding(){
        return new Binding("order.seckill.order.queue", Binding.DestinationType.QUEUE, "order-event-exchange", "order.seckill.order", null);
    }


}
