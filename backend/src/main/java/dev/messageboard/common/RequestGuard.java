package dev.messageboard.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuard extends OncePerRequestFilter {
    private final ObjectMapper json;
    public RequestGuard(ObjectMapper json) { this.json=json; }
    public static void error(HttpServletRequest req, HttpServletResponse res, int status, String text, ObjectMapper json) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control","no-store");
        json.writeValue(res.getOutputStream(),Map.of("message",text,"requestId",String.valueOf(req.getAttribute("requestId"))));
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id=UUID.randomUUID().toString();
        req.setAttribute("requestId",id); res.setHeader("X-Request-Id",id);
        long start=System.nanoTime();
        try {
            boolean imageUpload = req.getRequestURI().equals("/api/uploads/images") && req.getMethod().equals("POST")
                    && req.getContentType() != null && req.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
            if (imageUpload && req.getContentLengthLong() > 16L * 1024 * 1024) {
                error(req,res,413,"图片总请求不能超过 16MB",json); return;
            }
            // 仅图片上传走 Servlet multipart 的独立上限；其他写接口保持 32 KiB。
            if (!imageUpload && req.getRequestURI().startsWith("/api/") && Set.of("POST","PUT","PATCH").contains(req.getMethod())) {
                // 同时约束 Content-Length 和无长度 / chunked 请求，最多读取上限 + 1 字节。
                byte[] bytes=req.getInputStream().readNBytes(32769);
                if(bytes.length>32768) { error(req,res,413,"请求内容超过 32 KiB",json); return; }
                req=new HttpServletRequestWrapper(req) {
                    @Override public ServletInputStream getInputStream() {
                        var input=new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            public int read() { return input.read(); }
                            public boolean isFinished() { return input.available()==0; }
                            public boolean isReady() { return true; }
                            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
                };
            }
            chain.doFilter(req,res);
        } finally {
            LoggerFactory.getLogger(RequestGuard.class).info("request={} method={} status={} elapsedMs={}",id,req.getMethod(),res.getStatus(),(System.nanoTime()-start)/1_000_000);
        }
    }
}
