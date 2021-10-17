package com.atguigu.gulimall.search.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>Title: MallElasticSearchConfig</p>
 * Description：导入依赖
 * API : https://www.elastic.co/guide/en/elasticsearch/client/java-rest/7.4/java-rest-high-supported-apis.html
 * date：2020/6/8 14:50
 */
@Configuration
public class GulimallElasticSearchConfig {

//    @Value("${ipAddr}")
//    private String host;
//
    public static final RequestOptions COMMON_OPTIONS;

    static {
        RequestOptions.Builder builder = RequestOptions.DEFAULT.toBuilder();
//		builder.addHeader("Authorization", "Bearer " + TOKEN);
//		builder.setHttpAsyncResponseConsumerFactory(
//				new HttpAsyncResponseConsumerFactory
//						.HeapBufferedResponseConsumerFactory(30 * 1024 * 1024 * 1024));
        COMMON_OPTIONS = builder.build();
    }

    /**
     * final String hostname, final int port, final String scheme
     * @return
     */
    @Bean
    public RestHighLevelClient esRestClient() {
        RestHighLevelClient client = new RestHighLevelClient(
                RestClient.builder(new HttpHost("192.168.32.10", 9200, "http")));
        return client;
    }
}