package com.collabnotes.platform.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 通过真实 CSRF 接口获取校验码，不关闭过滤器、不额外下载测试依赖。 */
public final class CsrfTestSupport {
    private CsrfTestSupport() { }

    public static MockHttpServletRequestBuilder withCsrf(MockMvc mvc, ObjectMapper mapper,
                                                        MockHttpServletRequestBuilder request,
                                                        MockHttpSession session) throws Exception {
        var csrfRequest = get("/api/auth/csrf");
        if (session != null) { csrfRequest.session(session); }
        var result = mvc.perform(csrfRequest).andExpect(status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return request.session((MockHttpSession) result.getRequest().getSession(false))
                .header(body.get("headerName").asText(), body.get("token").asText());
    }
}
