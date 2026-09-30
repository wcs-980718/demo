package com.yiwei.midplat.evaluation.target;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yiwei.midplat.model.ModelService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** OpenAI-compatible, bounded, one-shot execution port; retry policy belongs to the run orchestrator. */
@Component
public class OpenAiEvaluationTargetExecutor implements EvaluationTargetExecutor {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ModelService models;
    private final ObjectMapper json;
    private final HttpClient http;

    public OpenAiEvaluationTargetExecutor(ModelService models) {
        this.models = models;
        this.json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public EvaluationTargetResponse execute(EvaluationTargetRequest request) {
        String apiKey = currentApiKey(request.modelId());
        ObjectNode body = json.createObjectNode();
        body.put("model", request.modelName());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.promptBody());
        messages.addObject().put("role", "user").put("content", request.input());

        CompletableFuture<HttpResponse<byte[]>> future = null;
        long startedAt = System.nanoTime();
        long deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs());
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(request.chatCompletionsUri())
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            future = http.sendAsync(httpRequest, boundedBodyHandler());
            HttpResponse<byte[]> response = awaitBeforeDeadline(future, deadline);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw EvaluationTargetException.http(status, status >= 500 && status <= 599);
            }
            return parseResponse(new String(response.body(), StandardCharsets.UTF_8), status, elapsedMs(startedAt));
        } catch (TimeoutException ex) {
            cancel(future);
            throw EvaluationTargetException.timeout();
        } catch (InterruptedException ex) {
            cancel(future);
            Thread.currentThread().interrupt();
            throw EvaluationTargetException.interrupted();
        } catch (ExecutionException ex) {
            throw mapAsyncFailure(ex.getCause());
        } catch (CancellationException ex) {
            if (Thread.currentThread().isInterrupted()) throw EvaluationTargetException.interrupted();
            throw EvaluationTargetException.network();
        } catch (EvaluationTargetException ex) {
            throw ex;
        } catch (IOException ex) {
            throw EvaluationTargetException.invalidResponse();
        } catch (RuntimeException ex) {
            throw EvaluationTargetException.invalidResponse();
        }
    }

    private static HttpResponse<byte[]> awaitBeforeDeadline(CompletableFuture<HttpResponse<byte[]>> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static void cancel(CompletableFuture<?> future) {
        if (future != null) future.cancel(true);
    }

    private static BodyHandler<byte[]> boundedBodyHandler() {
        return responseInfo -> {
            if (responseInfo.statusCode() < 200 || responseInfo.statusCode() >= 300) {
                return new DiscardingBodySubscriber();
            }
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1);
            return new BoundedBodySubscriber(contentLength);
        };
    }

    private static EvaluationTargetException mapAsyncFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof HttpTimeoutException) return EvaluationTargetException.timeout();
        if (cause instanceof BodyTooLargeException) return EvaluationTargetException.invalidResponse();
        if (cause instanceof BodyReadFailure || cause instanceof IOException) return EvaluationTargetException.network();
        return EvaluationTargetException.network();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String currentApiKey(String modelId) {
        try {
            String apiKey = models.requireCurrentApiKey(modelId);
            if (apiKey == null || apiKey.isBlank()) throw EvaluationTargetException.configuration();
            return apiKey;
        } catch (EvaluationTargetException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw EvaluationTargetException.configuration();
        }
    }

    private EvaluationTargetResponse parseResponse(String payload, int status, long latencyMs) {
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject()) throw EvaluationTargetException.invalidResponse();
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) throw EvaluationTargetException.invalidResponse();
            JsonNode content = choices.get(0).path("message").get("content");
            if (content == null || !content.isTextual()) throw EvaluationTargetException.invalidResponse();
            JsonNode usage = root.get("usage");
            if (usage == null || !usage.isObject()) throw EvaluationTargetException.invalidResponse();
            long prompt = nonNegativeLong(usage.get("prompt_tokens"));
            long completion = nonNegativeLong(usage.get("completion_tokens"));
            long calculatedTotal = EvaluationTargetResponse.safeTotal(prompt, completion);
            JsonNode totalNode = usage.get("total_tokens");
            long total = totalNode == null ? calculatedTotal : nonNegativeLong(totalNode);
            if (total != calculatedTotal) throw EvaluationTargetException.invalidResponse();
            return new EvaluationTargetResponse(content.textValue(), prompt, completion, total,
                    new EvaluationTargetTraceSummary(status, latencyMs));
        } catch (EvaluationTargetException ex) {
            throw ex;
        } catch (Exception ex) {
            throw EvaluationTargetException.invalidResponse();
        }
    }

    private static long nonNegativeLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw EvaluationTargetException.invalidResponse();
        }
        return value.longValue();
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private static final class BoundedBodySubscriber implements BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final boolean oversizedHeader;
        private ByteArrayOutputStream buffer;
        private Flow.Subscription subscription;
        private int size;

        BoundedBodySubscriber(long contentLength) {
            this.oversizedHeader = contentLength > MAX_RESPONSE_BYTES;
            int initialSize = contentLength > 0 && contentLength <= MAX_RESPONSE_BYTES ? (int) contentLength : 8192;
            this.buffer = new ByteArrayOutputStream(initialSize);
        }

        @Override
        public CompletionStage<byte[]> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            synchronized (this) {
                subscription = candidate;
            }
            if (oversizedHeader) cancelAndFail(new BodyTooLargeException());
            else candidate.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            synchronized (this) {
                if (terminated.get()) return;
                long incoming = 0;
                for (ByteBuffer item : buffers) incoming += item.remaining();
                if (incoming > MAX_RESPONSE_BYTES - size) {
                    cancelAndFail(new BodyTooLargeException());
                    return;
                }
                for (ByteBuffer item : buffers) {
                    ByteBuffer copy = item.slice();
                    byte[] bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                    buffer.writeBytes(bytes);
                    size += bytes.length;
                }
            }
        }

        @Override
        public void onError(Throwable ignored) { cancelAndFail(new BodyReadFailure()); }

        @Override
        public void onComplete() {
            byte[] completed;
            synchronized (this) {
                if (!terminated.compareAndSet(false, true)) return;
                completed = buffer.toByteArray();
                buffer = null;
            }
            body.complete(completed);
        }

        private void cancelAndFail(RuntimeException failure) {
            Flow.Subscription current;
            synchronized (this) {
                if (!terminated.compareAndSet(false, true)) return;
                current = subscription;
                buffer = null;
            }
            if (current != null) current.cancel();
            body.completeExceptionally(failure);
        }
    }

    private static final class DiscardingBodySubscriber implements BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final AtomicBoolean terminated = new AtomicBoolean();

        @Override
        public CompletionStage<byte[]> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            complete();
        }

        @Override
        public void onNext(List<ByteBuffer> ignored) { complete(); }

        @Override
        public void onError(Throwable ignored) { complete(); }

        @Override
        public void onComplete() { complete(); }

        private void complete() {
            if (terminated.compareAndSet(false, true)) body.complete(new byte[0]);
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {}
    private static final class BodyReadFailure extends RuntimeException {}
}
