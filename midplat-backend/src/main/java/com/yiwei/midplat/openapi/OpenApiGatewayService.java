package com.yiwei.midplat.openapi;

import com.yiwei.midplat.platform.PlatformApiService;
import com.yiwei.midplat.platform.PlatformCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class OpenApiGatewayService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final PlatformCredentialService credentials;
    private final PlatformApiService platformApis;
    private final RestClient restClient;

    OpenApiGatewayService(PlatformCredentialService credentials, PlatformApiService platformApis) {
        this.credentials = credentials;
        this.platformApis = platformApis;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(REQUEST_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public ResponseEntity<byte[]> forward(String ownerPlatformId, String apiId, String extraPath, HttpServletRequest request)
            throws IOException, InterruptedException {
        // W1: callers resolve through the unified credential service (new key or legacy digest);
        // project identity alone no longer authorizes cross-project open APIs.
        String callerId = credentials.requireCallerProjectId(request.getHeader("Authorization"));
        var resolved = platformApis.requireCallable(ownerPlatformId, apiId, callerId);
        if (!resolved.item().method().equalsIgnoreCase(request.getMethod())) {
            throw new IllegalArgumentException("该接口只支持 " + resolved.item().method());
        }
        URI target = OpenApiPathTemplate.target(
                resolved.owner().getEntryUrl(),
                OpenApiPathTemplate.fill(resolved.item().path(), extraPath),
                request.getQueryString());
        byte[] incoming;
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
        // multipart 请求不预读 inputStream：容器解析 multipart 后流已耗尽，直接走 getParts()。
        incoming = multipart ? new byte[0] : request.getInputStream().readAllBytes();
        RestClient.RequestBodySpec spec = restClient.method(HttpMethod.valueOf(resolved.item().method().toUpperCase()))
                .uri(target);
        if (multipart) {
            // Servlet 容器解析 multipart 后 inputStream 已耗尽，从 getParts() 重组转发；
            // Content-Type（含 boundary）由客户端重新生成，不透传原值。
            spec.contentType(MediaType.MULTIPART_FORM_DATA);
            spec.body(multipartBody(request));
        } else {
            if (incoming.length > 0) {
                if (contentType != null && !contentType.isBlank()) {
                    spec.contentType(MediaType.parseMediaType(contentType));
                }
                spec.body(incoming);
            }
        }
        ResponseEntity<byte[]> upstream = spec.exchange((req, response) -> {
            byte[] body = response.getBody().readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            MediaType responseType = response.getHeaders().getContentType();
            if (responseType != null) {
                headers.setContentType(responseType);
            }
            return ResponseEntity.status(response.getStatusCode()).headers(headers).body(body);
        });
        byte[] responseBody = upstream.getBody() == null ? new byte[0] : upstream.getBody();
        return ResponseEntity.status(upstream.getStatusCode()).headers(upstream.getHeaders()).body(responseBody);
    }

    private static MultiValueMap<String, Object> multipartBody(HttpServletRequest request) throws IOException {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        if (request instanceof org.springframework.web.multipart.MultipartHttpServletRequest multipartRequest) {
            // MockMvc / StandardServletMultipartResolver 把请求包成 Spring multipart，
            // 文件走 getMultiFileMap，普通字段走 getParameterMap。
            multipartRequest.getMultiFileMap().forEach((name, files) -> {
                for (org.springframework.web.multipart.MultipartFile file : files) {
                    try {
                        ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                            @Override
                            public String getFilename() {
                                return file.getOriginalFilename();
                            }
                        };
                        HttpHeaders partHeaders = new HttpHeaders();
                        String partType = file.getContentType();
                        partHeaders.setContentType(partType == null
                                ? MediaType.APPLICATION_OCTET_STREAM
                                : MediaType.parseMediaType(partType));
                        form.add(name, new HttpEntity<>(resource, partHeaders));
                    } catch (IOException e) {
                        throw new org.springframework.web.client.ResourceAccessException("读取上传文件失败", e);
                    }
                }
            });
            multipartRequest.getParameterMap().forEach((name, values) -> {
                for (String value : values) {
                    form.add(name, value);
                }
            });
            return form;
        }
        try {
            Collection<Part> parts = request.getParts();
            for (Part part : parts) {
                String filename = part.getSubmittedFileName();
                byte[] bytes = part.getInputStream().readAllBytes();
                if (filename != null) {
                    ByteArrayResource resource = new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return filename;
                        }
                    };
                    HttpHeaders partHeaders = new HttpHeaders();
                    partHeaders.setContentType(part.getContentType() == null
                            ? MediaType.APPLICATION_OCTET_STREAM
                            : MediaType.parseMediaType(part.getContentType()));
                    form.add(part.getName(), new HttpEntity<>(resource, partHeaders));
                } else {
                    form.add(part.getName(), new String(bytes, StandardCharsets.UTF_8));
                }
            }
        } catch (jakarta.servlet.ServletException e) {
            throw new IOException("解析 multipart 请求失败", e);
        }
        return form;
    }
}
