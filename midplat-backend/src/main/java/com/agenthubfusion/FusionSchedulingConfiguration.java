package com.agenthubfusion;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
@Configuration
public class FusionSchedulingConfiguration {
    /** Publication network latency must not starve run leases or cancellation delivery. */
    @Bean public ThreadPoolTaskScheduler taskScheduler(){ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(4);scheduler.setThreadNamePrefix("fusion-jobs-");scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;}
}
