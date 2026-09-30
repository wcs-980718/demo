package com.yiwei.midplat.evaluation.run;

import com.yiwei.midplat.evaluation.evaluator.EvaluationEvaluatorRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
class EvaluationRunConfiguration {
    @Bean
    EvaluationEvaluatorRegistry evaluationEvaluatorRegistry() {
        return new EvaluationEvaluatorRegistry();
    }

    @Bean(name = "evaluationTaskExecutor")
    ThreadPoolTaskExecutor evaluationTaskExecutor(
            @Value("${midplat.evaluation.executor.core-pool-size:1}") int core,
            @Value("${midplat.evaluation.executor.max-pool-size:2}") int max,
            @Value("${midplat.evaluation.executor.queue-capacity:50}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("evaluation-run-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
