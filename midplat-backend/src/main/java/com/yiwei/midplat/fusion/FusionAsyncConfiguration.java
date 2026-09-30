package com.yiwei.midplat.fusion;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;
@Configuration
public class FusionAsyncConfiguration implements WebMvcConfigurer {
    @Bean public ThreadPoolTaskExecutor fusionStreams(){ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();pool.setThreadNamePrefix("fusion-stream-");pool.setCorePoolSize(8);pool.setMaxPoolSize(32);pool.setQueueCapacity(16);pool.initialize();return pool;}
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer){configurer.setTaskExecutor(fusionStreams()).setDefaultTimeout(610000);}
}
