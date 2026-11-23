package dev.messageboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.messageboard.auth.*;
import dev.messageboard.comment.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"board.admin-user-ids=00000000-0000-0000-0000-000000000001","board.rate.read=100000","board.rate.write=100000","board.rate.auth=100000","board.upload.user-daily-bytes=100","board.upload.min-free-bytes=0","board.upload-dir=target/hardening-uploads"})
@AutoConfigureMockMvc
class HardeningIntegrationTest {
    static final UUID ADMIN=UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final EmbeddedPostgres PG;
    static { try { PG=EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start(); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres"));r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
    @AfterAll static void close() throws Exception { PG.close(); }
    @Autowired MockMvc mvc;
    @Autowired AuthController auth;
    @Autowired CommentService comments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    static final String PASSWORD="correct horse battery staple";
    User.View user(){return auth.register(new AuthController.Register("t"+UUID.randomUUID().toString().replace("-", "").substring(0,20),"讨论者",PASSWORD,PASSWORD));}
    MockHttpSession login(User.View u) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("loginName",u.loginName(),"password",PASSWORD)))).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession();
    }
    MockHttpSession admin() throws Exception {
        var source=user();
        jdbc.update("INSERT INTO board_user(id,login_name,display_name,password_hash) SELECT ?, 'operator', '管理员', password_hash FROM board_user WHERE id=? ON CONFLICT DO NOTHING",ADMIN,source.id());
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("loginName","operator","password",PASSWORD)))).andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    Comment create(User.View u,UUID p,String text){return comments.create(u.id(),new CommentService.Create(text,p,UUID.randomUUID()));}
    ResultActions write(String path,MockHttpSession session,Object body) throws Exception {return mvc.perform(post(path).session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));}
    @Test void moderationHidesAllPublicPathsAndRevokesBlockedSessions() throws Exception {
        var author=user(); var session=login(author); var operator=admin();
        mvc.perform(get("/api/moderation/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false));
        mvc.perform(get("/api/moderation/me").session(operator)).andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var upload=mvc.perform(multipart("/api/uploads/images").file(new MockMultipartFile("files","moderation.png","image/png",png)).param("batchId",UUID.randomUUID().toString()).session(session).with(csrf())).andExpect(status().isOk()).andReturn();
        UUID image=UUID.fromString(json.readTree(upload.getResponse().getContentAsString()).get(0).get("id").asText());
        var root=comments.create(author.id(),new CommentService.Create("需要管理的正文",null,UUID.randomUUID(),List.of(image))); var child=create(author,root.id(),"保留的回复");
        write("/api/moderation/comments/"+root.id(),session,Map.of("hidden",true,"reason","越权")).andExpect(status().isForbidden());
        write("/api/comments/"+root.id()+"/reports",session,Map.of("reason","广告内容")).andExpect(status().isNoContent());
        var openReports=mvc.perform(get("/api/moderation/reports").session(operator)).andExpect(status().isOk()).andExpect(jsonPath("$[0].commentId").value(root.id().toString())).andReturn();
        UUID reportId=UUID.fromString(json.readTree(openReports.getResponse().getContentAsString()).get(0).get("id").asText());
        write("/api/moderation/comments/"+root.id(),operator,Map.of("hidden",true,"reason","测试隐藏")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/attachments/"+image)).andExpect(status().isNotFound());
        mvc.perform(get("/api/comments/"+child.id()+"/context")).andExpect(status().isOk()).andExpect(jsonPath("$.ancestors[0].content").isEmpty());
        write("/api/moderation/comments/"+root.id(),operator,Map.of("hidden",false,"reason","恢复内容")).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("需要管理的正文"));
        mvc.perform(get("/api/attachments/"+image)).andExpect(status().isOk());
        write("/api/moderation/threads/"+root.id(),operator,Map.of("state","locked","reason","关闭讨论")).andExpect(status().isOk());
        write("/api/comments",session,new CommentService.Create("不能越过根锁",child.id(),UUID.randomUUID())).andExpect(status().isConflict());
        write("/api/moderation/users/"+author.id(),operator,Map.of("blocked",true,"reason","持续广告")).andExpect(status().isNoContent());
        write("/api/comments",session,new CommentService.Create("旧会话",null,UUID.randomUUID())).andExpect(status().isForbidden());
        write("/api/moderation/reports/"+reportId,operator,Map.of("status","resolved","reason","已处理")).andExpect(status().isNoContent());
        mvc.perform(get("/api/moderation/audit").session(operator)).andExpect(status().isOk()).andExpect(jsonPath("$[0].action").isNotEmpty());
    }
    @Test void uploadQuotaRejectsSecondBatchAfterDailyBudget() throws Exception {
        var author=user(); var session=login(author);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var file=new MockMultipartFile("files","quota.png","image/png",png);
        mvc.perform(multipart("/api/uploads/images").file(file).param("batchId",UUID.randomUUID().toString()).session(session).with(csrf())).andExpect(status().isOk());
        mvc.perform(multipart("/api/uploads/images").file(file).param("batchId",UUID.randomUUID().toString()).session(session).with(csrf())).andExpect(status().isTooManyRequests());
    }
}
