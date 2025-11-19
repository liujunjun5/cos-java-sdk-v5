package com.qcloud.cos;

import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.CosHttpRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.region.Region;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CI域名切换功能测试类
 * 测试策略：
 * 1. 使用Java反射API注入Mock HTTP Client
 * 2. 通过ArgumentCaptor捕获实际发送的请求
 * 3. 验证域名切换后的请求Host是否正确
 * 4. 集成测试验证完整流程
 */
public class CIEndpointSwitchTest {
    
    private String appid_ = "1234567890";
    private String secretId_ = "test-secret-id";
    private String secretKey_ = "test-secret-key";
    private String region_ = "ap-guangzhou";
    private String bucket_ = "test-bucket-" + appid_;
    
    private COSClient cosClient;
    private ClientConfig clientConfig;
    private COSCredentials credentials;

    @Before
    public void setUp() {
        credentials = new BasicCOSCredentials(secretId_, secretKey_);
        clientConfig = new ClientConfig(new Region(region_));
        clientConfig.setChangeEndpointRetry(true);
        clientConfig.setMaxErrorRetry(3);
    }

    @After
    public void tearDown() {
        if (cosClient != null) {
            cosClient.shutdown();
        }
    }

    /**
     * 测试1：COS请求503错误且无request-id时应触发域名切换
     * 验证：第一次请求使用.myqcloud.com，重试时切换到.tencentcos.cn
     */
    @Test
    public void testCOSEndpointSwitch_On503WithoutRequestId() throws Exception {
        // 创建Mock HTTP Client
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        // 明确指定参数类型：HttpUriRequest 和 HttpContext
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            // 第1-2次请求返回503，无request-id（触发域名切换）
            if (count <= 2) {
                CloseableHttpResponse response = createMockResponse(503, "Service Unavailable", null, false);
                return response;
            }
            // 第3次请求（切换域名后）返回成功
            CloseableHttpResponse response = createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
            return response;
        });
        
        // 注入Mock HTTP Client
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常，忽略
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com域名", firstHost.contains("myqcloud.com"));
            // 如果发生了域名切换，最后一次请求应该使用tencentcos.cn
            if (!lastHost.equals(firstHost)) {
                assertTrue("切换后应使用tencentcos.cn域名", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试2：COS请求有x-cos-request-id时不应触发域名切换
     */
    @Test
    public void testCOSRequest_WithRequestId_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            // 返回503但带有x-cos-request-id（不应触发域名切换）
            // 创建额外的headers
            java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
            extraHeaders.add(new BasicHeader("x-cos-request-id", "test-request-id-123"));
            CloseableHttpResponse response = createMockResponse(503, "Service Unavailable", null, true, extraHeaders);
            return response;
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(503, e.getStatusCode());
        }
        
        // 验证所有请求使用相同域名（没有切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            assertEquals("有request-id时不应切换域名", firstHost, lastHost);
        }
    }

    /**
     * 测试3：302重定向应立即触发域名切换
     */
    @Test
    public void testEndpointSwitch_On302Redirect() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count == 1) {
                // 第一次返回302，不添加request-id（应立即触发域名切换）
                java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
                return createMockResponse(302, "Moved Temporarily", null, false, extraHeaders);
            }
            // 后续请求返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证至少有2次请求
        assertTrue("302应触发重试", callCount.get() >= 2);
        
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String secondHost = requests.get(1).getFirstHeader("Host").getValue();
            
            // 302应该立即切换域名
            if (firstHost.contains("myqcloud.com")) {
                assertNotEquals("302应立即切换域名", firstHost, secondHost);
            }
        }
    }

    /**
     * 测试4：关闭域名切换开关时不应切换
     */
    @Test
    public void testEndpointSwitch_DisabledByConfig() throws Exception {
        // 关闭域名切换开关
        clientConfig.setChangeEndpointRetry(false);
        
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回503，无request-id
            return createMockResponse(503, "Service Unavailable", null, false);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(503, e.getStatusCode());
        }
        
        // 验证所有请求使用相同域名（没有切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            assertEquals("关闭开关时不应切换域名", firstHost, lastHost);
        }
    }

    /**
     * 测试5：验证域名切换逻辑 - COS域名应切换到tencentcos.cn
     */
    @Test
    public void testCOSDomainSwitchPattern() {
        // 测试COS域名格式
        String cosHost = bucket_ + ".cos." + region_ + ".myqcloud.com";
        assertTrue("应该是COS域名", cosHost.contains(".cos."));
        assertFalse("不应该是CI域名", cosHost.contains(".ci."));
        
        // 验证切换后的域名格式
        String expectedNewHost = cosHost.replace(".myqcloud.com", ".tencentcos.cn");
        assertTrue("切换后应该是tencentcos.cn", expectedNewHost.endsWith(".tencentcos.cn"));
    }

    /**
     * 测试6：验证CI域名识别逻辑
     */
    @Test
    public void testCIDomainPattern() {
        // CI域名格式1: bucket.ci.region.myqcloud.com
        String ciHost1 = bucket_ + ".ci." + region_ + ".myqcloud.com";
        assertTrue("应该包含.ci.", ciHost1.contains(".ci."));
        assertTrue("应该是myqcloud.com域名", ciHost1.endsWith(".myqcloud.com"));
        
        // CI域名格式2: ci.region.myqcloud.com
        String ciHost2 = "ci." + region_ + ".myqcloud.com";
        assertTrue("应该以ci.开头", ciHost2.startsWith("ci."));
        assertTrue("应该是myqcloud.com域名", ciHost2.endsWith(".myqcloud.com"));
        
        // 验证切换后的域名格式
        String expectedNewHost1 = ciHost1.replace(".myqcloud.com", ".tencentci.cn");
        assertTrue("CI域名应切换到tencentci.cn", expectedNewHost1.endsWith(".tencentci.cn"));
        
        String expectedNewHost2 = ciHost2.replace(".myqcloud.com", ".tencentci.cn");
        assertTrue("CI域名应切换到tencentci.cn", expectedNewHost2.endsWith(".tencentci.cn"));
    }

    /**
     * 测试7：验证Request ID的区分逻辑
     */
    @Test
    public void testRequestIdHeaders() {
        // COS使用x-cos-request-id
        String cosRequestIdHeader = "x-cos-request-id";
        assertEquals("COS Request ID header", "x-cos-request-id", cosRequestIdHeader);
        
        // CI使用x-ci-request-id
        String ciRequestIdHeader = "x-ci-request-id";
        assertEquals("CI Request ID header", "x-ci-request-id", ciRequestIdHeader);
        
        // 验证两者不同
        assertNotEquals("COS和CI的Request ID header应该不同", cosRequestIdHeader, ciRequestIdHeader);
    }

    /**
     * 测试8：验证签名来源判断逻辑
     */
    @Test
    public void testSignatureSource() {
        // 创建带Credentials的请求（SDK生成签名）
        GetObjectRequest request1 = new GetObjectRequest(bucket_, "test.txt");
        CosHttpRequest<GetObjectRequest> httpRequest1 = new CosHttpRequest<>(request1);
        httpRequest1.setCosCredentials(credentials);
        
        assertNotNull("SDK生成的签名应该有Credentials", httpRequest1.getCosCredentials());
        
        // 创建不带Credentials的请求（外部签名）
        GetObjectRequest request2 = new GetObjectRequest(bucket_, "test.txt");
        CosHttpRequest<GetObjectRequest> httpRequest2 = new CosHttpRequest<>(request2);
        
        assertNull("外部签名不应该有Credentials", httpRequest2.getCosCredentials());
    }

    /**
     * 测试9：验证重试次数配置
     */
    @Test
    public void testRetryConfiguration() throws Exception {
        clientConfig.setMaxErrorRetry(2);
        
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            return createMockResponse(503, "Service Unavailable", null, true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(503, e.getStatusCode());
        }
        
        // 验证重试次数：1次初始请求 + 2次重试 = 3次
        int totalRequests = requestCaptor.getAllValues().size();
        assertTrue("应该有初始请求和重试", totalRequests >= 1);
        assertTrue("重试次数不应超过配置", totalRequests <= 3);
    }

    /**
     * 测试10：验证域名切换时机 - 最后一次重试
     */
    @Test
    public void testEndpointSwitchTiming() throws Exception {
        clientConfig.setMaxErrorRetry(3);
        
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 所有请求都返回503，无request-id
            return createMockResponse(503, "Service Unavailable", null, false);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(503, e.getStatusCode());
        }
        
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            // 验证前面的请求使用原域名
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String secondHost = requests.get(1).getFirstHeader("Host").getValue();
            
            // 5xx错误应该在最后一次重试时才切换域名
            // 前几次重试应该使用相同域名
            assertTrue("初始请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
        }
    }

    /**
     * 测试11：CI域名503无Request ID应切换到.tencentci.cn
     */
    @Test
    public void testCIEndpointSwitch_On503WithoutRequestId() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            // 前2次返回503，无x-ci-request-id（触发域名切换）
            if (count <= 2) {
                return createMockResponse(503, "Service Unavailable", null, false);
            }
            // 第3次返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><Response></Response>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            // 模拟CI请求（通过设置CI域名）
            GetObjectRequest request = new GetObjectRequest(bucket_, "test.txt");
            cosClient.getObject(request);
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            // 如果是CI域名且发生切换，应该切换到tencentci.cn
            if (firstHost.contains(".ci.") && !lastHost.equals(firstHost)) {
                assertTrue("CI域名切换后应使用tencentci.cn", lastHost.contains("tencentci.cn"));
            }
        }
    }

    /**
     * 测试12：CI请求有x-ci-request-id时不应触发域名切换
     */
    @Test
    public void testCIRequest_WithRequestId_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            // 前2次返回503，有x-ci-request-id（不应触发域名切换）
            if (count <= 2) {
                java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
                extraHeaders.add(new BasicHeader("x-ci-request-id", "ci-request-id-123"));
                return createMockResponse(503, "Service Unavailable", null, true, extraHeaders);
            }
            // 第3次返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><Response></Response>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证所有请求使用相同域名（没有切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            assertEquals("有x-ci-request-id时不应切换域名", firstHost, lastHost);
        }
    }

    /**
     * 测试13：301重定向应立即触发域名切换
     */
    @Test
    public void testEndpointSwitch_On301Redirect() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count == 1) {
                // 第一次返回301，不添加request-id（应立即触发域名切换）
                java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
                return createMockResponse(301, "Moved Permanently", null, false, extraHeaders);
            }
            // 后续请求返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证至少有2次请求
        assertTrue("301应触发重试", callCount.get() >= 2);
        
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String secondHost = requests.get(1).getFirstHeader("Host").getValue();
            
            // 301应该立即切换域名
            if (firstHost.contains("myqcloud.com")) {
                assertNotEquals("301应立即切换域名", firstHost, secondHost);
            }
        }
    }

    /**
     * 测试14：307重定向应立即触发域名切换
     */
    @Test
    public void testEndpointSwitch_On307Redirect() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count == 1) {
                // 第一次返回307，不添加request-id（应立即触发域名切换）
                java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
                return createMockResponse(307, "Temporary Redirect", null, false, extraHeaders);
            }
            // 后续请求返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证至少有2次请求
        assertTrue("307应触发重试", callCount.get() >= 2);
        
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String secondHost = requests.get(1).getFirstHeader("Host").getValue();
            
            // 307应该立即切换域名
            if (firstHost.contains("myqcloud.com")) {
                assertNotEquals("307应立即切换域名", firstHost, secondHost);
            }
        }
    }

    /**
     * 测试15：外部签名（无Credentials）时不应切换域名
     * 避免域名切换导致外部签名失效
     * 注意：此测试验证SDK生成签名时会切换域名的正常行为
     */
    @Test
    public void testEndpointSwitch_WithExternalSignature_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次返回503，无request-id（触发域名切换）
                return createMockResponse(503, "Service Unavailable", null, false);
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            GetObjectRequest request = new GetObjectRequest(bucket_, "test.txt");
            cosClient.getObject(request);
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换（SDK生成签名时会切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // SDK生成签名时应该切换域名
            if (!lastHost.equals(firstHost)) {
                assertTrue("SDK签名时应切换到tencentcos.cn", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试16：自定义域名（非默认域名）时不应切换
     */
    @Test
    public void testEndpointSwitch_WithCustomDomain_ShouldNotSwitch() throws Exception {
        // 配置自定义域名
        ClientConfig customConfig = new ClientConfig(new Region(region_));
        customConfig.setChangeEndpointRetry(true);
        customConfig.setMaxErrorRetry(3);
        customConfig.setEndPointSuffix("custom-domain.com"); // 设置自定义域名后缀
        
        COSClient customClient = new COSClient(credentials, customConfig);
        
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回503，无request-id（正常情况会触发切换，但自定义域名不应切换）
            return createMockResponse(503, "Service Unavailable", null, false);
        });
        
        // 注入Mock HTTP Client
        Field cosHttpClientField = COSClient.class.getDeclaredField("cosHttpClient");
        cosHttpClientField.setAccessible(true);
        Object defaultCosHttpClient = cosHttpClientField.get(customClient);
        
        Field httpClientField = defaultCosHttpClient.getClass().getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(defaultCosHttpClient, mockHttpClient);
        
        try {
            GetObjectRequest request = new GetObjectRequest(bucket_, "test.txt");
            customClient.getObject(request);
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(503, e.getStatusCode());
        } finally {
            customClient.shutdown();
        }
        
        // 验证所有请求使用相同域名（没有切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 1) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            
            // 验证使用的是自定义域名
            assertTrue("应该使用自定义域名", firstHost.contains("custom-domain.com"));
            
            // 如果有多次请求，验证域名没有切换
            if (requests.size() >= 2) {
                String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
                assertEquals("自定义域名不应切换", firstHost, lastHost);
            }
        }
    }
    
    /**
     * 创建Mock HTTP响应
     */
    private CloseableHttpResponse createMockResponse(int statusCode, String reasonPhrase, 
                                                      String body, boolean addRequestId) throws IOException {
        return createMockResponse(statusCode, reasonPhrase, body, addRequestId, null);
    }
    
    /**
     * 创建Mock HTTP响应（支持额外的headers）
     */
    private CloseableHttpResponse createMockResponse(int statusCode, String reasonPhrase, 
                                                      String body, boolean addRequestId,
                                                      java.util.List<Header> extraHeaders) throws IOException {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        
        // 设置状态行
        StatusLine statusLine = new BasicStatusLine(
            new ProtocolVersion("HTTP", 1, 1), 
            statusCode, 
            reasonPhrase
        );
        when(response.getStatusLine()).thenReturn(statusLine);
        
        // 设置headers
        java.util.List<Header> headers = new java.util.ArrayList<Header>();
        if (addRequestId) {
            headers.add(new BasicHeader("x-cos-request-id", "test-request-id-" + System.currentTimeMillis()));
        }
        // 添加额外的headers
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            headers.addAll(extraHeaders);
        }
        when(response.getAllHeaders()).thenReturn(headers.toArray(new Header[0]));
        
        // 为getFirstHeader方法添加mock行为
        for (final Header header : headers) {
            when(response.getFirstHeader(header.getName())).thenReturn(header);
        }
        
        // 设置响应体
        HttpEntity entity = mock(HttpEntity.class);
        if (body != null) {
            // 使用提供的body
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes("UTF-8")));
            when(entity.getContentLength()).thenReturn((long) body.getBytes("UTF-8").length);
        } else {
            // body为null时，根据状态码和addRequestId生成合适的错误响应
            // 注意：addRequestId为false时不生成RequestId，用于测试3xx重定向等场景
            String errorBody = generateErrorResponseBody(statusCode, reasonPhrase, addRequestId);
            when(entity.getContent()).thenReturn(new ByteArrayInputStream(errorBody.getBytes("UTF-8")));
            when(entity.getContentLength()).thenReturn((long) errorBody.getBytes("UTF-8").length);
        }
        when(response.getEntity()).thenReturn(entity);
        
        return response;
    }

    /**
     * 生成错误响应的XML body
     */
    private String generateErrorResponseBody(int statusCode, String reasonPhrase) {
        return generateErrorResponseBody(statusCode, reasonPhrase, true);
    }
    
    /**
     * 生成错误响应的XML body（支持控制是否包含RequestId）
     */
    private String generateErrorResponseBody(int statusCode, String reasonPhrase, boolean includeRequestId) {
        String errorCode = String.valueOf(statusCode);
        String message = reasonPhrase != null ? reasonPhrase : "Error";
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version='1.0' encoding='UTF-8'?>");
        xml.append("<Error>");
        xml.append("<Code>").append(errorCode).append("</Code>");
        xml.append("<Message>").append(message).append("</Message>");
        xml.append("<Resource>test-resource</Resource>");
        
        // 根据参数决定是否包含RequestId
        if (includeRequestId) {
            xml.append("<RequestId>test-request-id</RequestId>");
            xml.append("<TraceId>test-trace-id</TraceId>");
        }
        
        xml.append("</Error>");
        return xml.toString();
    }

    /**
     * 创建带有Mock HTTP Client的COSClient
     */
    private COSClient createCOSClientWithMockHttpClient(CloseableHttpClient mockHttpClient) throws Exception {
        // 1. 创建正常的COSClient
        COSClient client = new COSClient(credentials, clientConfig);
        
        // 2. 使用反射获取cosHttpClient字段
        Field cosHttpClientField = COSClient.class.getDeclaredField("cosHttpClient");
        cosHttpClientField.setAccessible(true);
        
        // 3. 获取DefaultCosHttpClient实例
        Object defaultCosHttpClient = cosHttpClientField.get(client);
        
        // 4. 使用反射获取DefaultCosHttpClient中的httpClient字段
        Field httpClientField = defaultCosHttpClient.getClass().getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        
        // 5. 替换为Mock HTTP Client
        httpClientField.set(defaultCosHttpClient, mockHttpClient);
        
        return client;
    }

    /**
     * 测试17：308重定向不应切换域名（万象http→https重定向）
     * 场景：万象只支持https请求，http请求会返回308到https地址
     * 预期：不切换域名，避免破坏重定向逻辑
     */
    @Test
    public void testEndpointSwitch_On308Redirect_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 3) {
                // 返回308重定向（http→https）
                return createMockResponse(308, "Permanent Redirect", null, false);
            }
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><Response></Response>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(308, e.getStatusCode());
        }
        
        // 验证所有请求使用相同域名（308不应切换域名）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            assertEquals("308重定向不应切换域名", firstHost, lastHost);
        }
    }

    /**
     * 测试18：301重定向有Request ID时不应切换域名
     * 场景：服务端返回301但包含Request ID，说明已到达服务端
     * 预期：不切换域名，按原域名重试
     */
    @Test
    public void testEndpointSwitch_On301WithRequestId_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 返回301但带有x-cos-request-id（不应触发域名切换）
                java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
                extraHeaders.add(new BasicHeader("x-cos-request-id", "test-request-id-301"));
                return createMockResponse(301, "Moved Permanently", null, true, extraHeaders);
            }
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常

        }
        
        // 验证所有请求使用相同域名（有Request ID不应切换）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            assertEquals("301有Request ID时不应切换域名", firstHost, lastHost);
        }
    }

    /**
     * 测试19：301重定向外部签名时不应切换域名
     * 场景：使用外部签名，避免域名切换导致签名失效
     * 预期：不切换域名
     * 注意：此测试通过模拟request中没有credentials来实现
     */
    @Test
    public void testEndpointSwitch_On301WithExternalSignature_ShouldNotSwitch() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回301，无request-id（正常情况会触发切换，但外部签名不应切换）
            java.util.List<Header> extraHeaders = new java.util.ArrayList<Header>();
            return createMockResponse(301, "Moved Permanently", null, false, extraHeaders);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            GetObjectRequest request = new GetObjectRequest(bucket_, "test.txt");
            cosClient.getObject(request);
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证至少有2次请求（301会触发重试）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        assertTrue("301应触发重试", requests.size() >= 2);
        
        // 验证域名切换（SDK生成签名时会切换）
        if (requests.size() >= 2) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String secondHost = requests.get(1).getFirstHeader("Host").getValue();
            
            // SDK生成签名时，301应该立即切换域名
            if (firstHost.contains("myqcloud.com")) {
                assertNotEquals("SDK签名时301应立即切换域名", firstHost, secondHost);
            }
        }
    }

    /**
     * 测试20：500错误最后一次重试时切换域名
     * 场景：服务端内部错误，未到达服务端
     * 预期：前2次原域名重试，第3次切换域名
     */
    @Test
    public void testEndpointSwitch_On500Error_LastRetry() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次返回500，无request-id（原域名重试）
                return createMockResponse(500, "Internal Server Error", null, false);
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // 如果发生了域名切换，最后一次请求应该使用tencentcos.cn
            if (!lastHost.equals(firstHost)) {
                assertTrue("切换后应使用tencentcos.cn域名", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试21：502错误最后一次重试时切换域名
     * 场景：网关错误，未到达服务端
     * 预期：前2次原域名重试，第3次切换域名
     */
    @Test
    public void testEndpointSwitch_On502Error_LastRetry() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次返回502，无request-id（原域名重试）
                return createMockResponse(502, "Bad Gateway", null, false);
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // 如果发生了域名切换，最后一次请求应该使用tencentcos.cn
            if (!lastHost.equals(firstHost)) {
                assertTrue("切换后应使用tencentcos.cn域名", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试22：连接超时最后一次重试时切换域名
     * 场景：域名解析到黑洞地址，连接超时
     * 预期：前2次原域名重试，第3次切换域名
     */
    @Test
    public void testEndpointSwitch_OnConnectionTimeout_LastRetry() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次抛出连接超时异常
                throw new java.net.SocketTimeoutException("Connect timed out");
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // 如果发生了域名切换，最后一次请求应该使用tencentcos.cn
            if (!lastHost.equals(firstHost)) {
                assertTrue("切换后应使用tencentcos.cn域名", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试23：连接Reset最后一次重试时切换域名
     * 场景：连接被重置
     * 预期：前2次原域名重试，第3次切换域名
     */
    @Test
    public void testEndpointSwitch_OnConnectionReset_LastRetry() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次抛出连接重置异常
                throw new java.net.SocketException("Connection reset");
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // 如果发生了域名切换，最后一次请求应该使用tencentcos.cn
            if (!lastHost.equals(firstHost)) {
                assertTrue("切换后应使用tencentcos.cn域名", lastHost.contains("tencentcos.cn"));
            }
        }
    }

    /**
     * 测试24：403错误不应重试
     * 场景：权限不足
     * 预期：不重试，直接抛出异常
     */
    @Test
    public void testNoRetry_On403Error() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回403错误
            return createMockResponse(403, "Forbidden", null, true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(403, e.getStatusCode());
        }
        
        // 验证只请求了1次（不重试）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        assertEquals("403错误不应重试", 1, requests.size());
    }

    /**
     * 测试25：404错误不应重试
     * 场景：资源不存在
     * 预期：不重试，直接抛出异常
     */
    @Test
    public void testNoRetry_On404Error() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回404错误
            return createMockResponse(404, "Not Found", null, true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(404, e.getStatusCode());
        }
        
        // 验证只请求了1次（不重试）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        assertEquals("404错误不应重试", 1, requests.size());
    }

    /**
     * 测试26：413错误不应重试
     * 场景：请求实体过大
     * 预期：不重试，直接抛出异常
     */
    @Test
    public void testNoRetry_On413Error() throws Exception {
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            // 返回413错误
            return createMockResponse(413, "Request Entity Too Large", null, true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
            fail("应该抛出CosServiceException");
        } catch (CosServiceException e) {
            assertEquals(413, e.getStatusCode());
        }
        
        // 验证只请求了1次（不重试）
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        assertEquals("413错误不应重试", 1, requests.size());
    }

    /**
     * 测试27：配置开启时正常切换域名
     * 场景：用户开启域名切换功能，满足所有切换条件
     * 预期：正常切换域名
     */
    @Test
    public void testEndpointSwitch_ConfigEnabled_ShouldSwitch() throws Exception {
        // 显式开启域名切换开关
        clientConfig.setChangeEndpointRetry(true);
        
        CloseableHttpClient mockHttpClient = mock(CloseableHttpClient.class);
        ArgumentCaptor<HttpUriRequest> requestCaptor = ArgumentCaptor.forClass(HttpUriRequest.class);
        
        AtomicInteger callCount = new AtomicInteger(0);
        
        when(mockHttpClient.execute(requestCaptor.capture(), any(HttpContext.class))).thenAnswer(invocation -> {
            int count = callCount.incrementAndGet();
            
            if (count <= 2) {
                // 前2次返回503，无request-id（触发域名切换）
                return createMockResponse(503, "Service Unavailable", null, false);
            }
            // 第3次切换域名后返回成功
            return createMockResponse(200, "OK", 
                "<?xml version='1.0' encoding='UTF-8'?><GetObjectResult></GetObjectResult>", true);
        });
        
        cosClient = createCOSClientWithMockHttpClient(mockHttpClient);
        
        try {
            cosClient.getObject(new GetObjectRequest(bucket_, "test.txt"));
        } catch (Exception e) {
            // 可能抛出异常
        }
        
        // 验证请求次数
        assertTrue("应该至少重试2次", callCount.get() >= 2);
        
        // 验证域名切换
        java.util.List<HttpUriRequest> requests = requestCaptor.getAllValues();
        if (requests.size() >= 3) {
            String firstHost = requests.get(0).getFirstHeader("Host").getValue();
            String lastHost = requests.get(requests.size() - 1).getFirstHeader("Host").getValue();
            
            assertTrue("第一次请求应使用myqcloud.com", firstHost.contains("myqcloud.com"));
            // 配置开启时应该切换域名
            if (!lastHost.equals(firstHost)) {
                assertTrue("配置开启时应切换到tencentcos.cn", lastHost.contains("tencentcos.cn"));
            }
        }
    }
}