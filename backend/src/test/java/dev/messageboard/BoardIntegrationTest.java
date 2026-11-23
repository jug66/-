package dev.messageboard;

import com.fasterxml.jackson.databind.*;
import dev.messageboard.auth.*;
import dev.messageboard.comment.*;
import dev.messageboard.common.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"board.rate.read=100000","board.rate.auth=100000","board.rate.write=100000"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BoardIntegrationTest {
    @TestConfiguration
    static class SqlMeasurement {
        @Bean QueryCounter queryCounter() { return new QueryCounter(); }
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,org.apache.ibatis.session.ResultHandler.class}))
    static class QueryCounter implements Interceptor {
        static final ThreadLocal<Integer> COUNT=ThreadLocal.withInitial(() -> 0);
        public Object intercept(Invocation invocation) throws Throwable {
            COUNT.set(COUNT.get()+1); return invocation.proceed();
        }
    }
    static final EmbeddedPostgres PG;
    static { try { PG=EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> PG.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",() -> "postgres");
        registry.add("spring.datasource.password",() -> "");
    }
    @AfterAll static void closePg() throws Exception { PG.close(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommentService comments;
    @Autowired AuthController auth;
    @Autowired PlatformTransactionManager txManager;
    static final String PASSWORD="correct horse battery staple";
    String loginName() { return "u"+UUID.randomUUID().toString().replace("-","").substring(0,20); }
    User.View register() { return auth.register(new AuthController.Register(loginName(),"测试用户",PASSWORD,PASSWORD)); }
    Comment create(UUID user,UUID parent,String text) { return comments.create(user,new CommentService.Create(text,parent,UUID.randomUUID())); }
    MockHttpSession login(User.View user) throws Exception {
        var session=new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("loginName",user.loginName(),"password",PASSWORD)))).andExpect(status().isOk());
        return session;
    }
    @Test @Order(1) void registrationValidationNormalizationAndConcurrentUniqueness() throws Exception {
        String name=loginName();
        var data=new AuthController.Register(" "+name.toUpperCase(Locale.ROOT)+" ","中文😀昵称",PASSWORD,PASSWORD);
        var user=auth.register(data);
        assertThat(user.loginName()).isEqualTo(name);
        assertThatThrownBy(() -> auth.register(new AuthController.Register(name,"",PASSWORD,PASSWORD))).isInstanceOf(ApiException.class);
        assertThat(auth.register(new AuthController.Register(loginName(),"昵称","abcdefghi","abcdefghi"))).isNotNull();
        for(String password:List.of("short","abcdefgh","x".repeat(129),"Password1!"))
            assertThatThrownBy(() -> auth.register(new AuthController.Register(loginName(),"昵称",password,password))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> auth.register(new AuthController.Register(loginName(),"昵称",PASSWORD,"other"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> auth.register(new AuthController.Register("bad!","昵称",PASSWORD,PASSWORD))).isInstanceOf(ApiException.class);
        String concurrent=loginName();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=pool.invokeAll(List.<Callable<Boolean>>of(
                () -> tryRegister(concurrent), () -> tryRegister(concurrent.toUpperCase(Locale.ROOT))));
            assertThat(jobs.stream().filter(f -> { try{return f.get();}catch(Exception e){throw new RuntimeException(e);} }).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT password_hash FROM board_user WHERE id=?",String.class,user.id())).startsWith("$argon2id$").doesNotContain(PASSWORD);
        String longPassword="😀".repeat(128);
        assertThat(auth.register(new AuthController.Register(loginName(),"😀".repeat(30),longPassword,longPassword))).isNotNull();
    }

    @Test @Order(2) void optionalEmailVerificationAndPasswordReset() throws Exception {
        String email = "person"+UUID.randomUUID().toString().replace("-","").substring(0,8)+"@example.com";
        var user=auth.register(new AuthController.Register(loginName(),"邮箱用户","abcdefghi","abcdefghi",email));
        assertThat(user.email()).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM board_user WHERE id=?",OffsetDateTime.class,user.id())).isNull();
        assertThatThrownBy(() -> auth.register(new AuthController.Register(loginName(),"重复邮箱",PASSWORD,PASSWORD,email)))
            .isInstanceOf(ApiException.class).hasMessageContaining("邮箱");

        String verify="verify-"+UUID.randomUUID();
        jdbc.update("INSERT INTO account_token(id,user_id,purpose,token_hash,expires_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),user.id(),"EMAIL_VERIFY",tokenHash(verify),OffsetDateTime.now().plusMinutes(30));
        mvc.perform(get("/api/auth/verify-email").param("token",verify)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT email_verified_at FROM board_user WHERE id=?",OffsetDateTime.class,user.id())).isNotNull();

        String reset="reset-"+UUID.randomUUID();
        jdbc.update("INSERT INTO account_token(id,user_id,purpose,token_hash,expires_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),user.id(),"PASSWORD_RESET",tokenHash(reset),OffsetDateTime.now().plusMinutes(30));
        mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(new AuthController.Reset(reset,"newpass9!","newpass9!"))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("loginName",user.loginName(),"password","newpass9!"))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/reset-password").with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(new AuthController.Reset(reset,"newpass10!","newpass10!"))))
            .andExpect(status().isBadRequest());
    }

    private String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    boolean tryRegister(String name) {
        try { auth.register(new AuthController.Register(name,"同名",PASSWORD,PASSWORD)); return true; }
        catch(ApiException e) { assertThat(e.status).isEqualTo(409); return false; }
    }
    @Test @Order(2) void sessionRotationLogoutAbsoluteExpiryAndGenericLoginError() throws Exception {
        var user=register(); var old=new MockHttpSession(); String oldId=old.getId();
        mvc.perform(post("/api/auth/login").session(old).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("loginName",user.loginName(),"password",PASSWORD)))).andExpect(status().isOk());
        assertThat(old.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(old)).andExpect(jsonPath("$.user.id").value(user.id().toString())).andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        var second=login(user);
        mvc.perform(post("/api/auth/logout").session(old).with(csrf())).andExpect(status().isNoContent());
        assertThat(old.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(second)).andExpect(jsonPath("$.user.id").value(user.id().toString()));
        second.setAttribute("authenticatedAt",Instant.now().minusSeconds(604801).getEpochSecond());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(jsonPath("$.user").isEmpty());
        for(String name:List.of(user.loginName(),loginName()))
            mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("loginName",name,"password","wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("账号或密码不正确"));
    }
    @Test @Order(3) void realCsrfAnonymousPermissionsBodySizeAndProtectedFields() throws Exception {
        mvc.perform(get("/api/comments")).andExpect(status().isOk());
        String body=json.writeValueAsString(new CommentService.Create("hello",null,UUID.randomUUID()));
        mvc.perform(post("/api/comments").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        var session=login(register());
        mvc.perform(post("/api/comments").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var tokenResult=mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        JsonNode token=json.readTree(tokenResult.getResponse().getContentAsString());
        mvc.perform(post("/api/comments").session(session).header(token.get("headerName").asText(),token.get("token").asText()).contentType("application/json").content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/comments").session(session).with(csrf()).contentType("application/json").content("{\"content\":\"x\",\"authorId\":\"fake\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/comments").session(session).with(csrf()).contentType("application/json").content("x".repeat(32769))).andExpect(status().isPayloadTooLarge());
        for(String text:List.of(" \n\t\u3000","😀".repeat(2001)))
            mvc.perform(post("/api/comments").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(new CommentService.Create(text,null,UUID.randomUUID())))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/comments").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(new CommentService.Create("😀".repeat(2000),null,UUID.randomUUID())))).andExpect(status().isCreated());
    }
    @Test @Order(4) void trueTreeDeletionOwnershipAndActiveDescendant() {
        var a=register(); var b=register();
        var root=create(a.id(),null,"A"); var child=create(b.id(),root.id(),"B"); var grand=create(a.id(),child.id(),"C");
        assertThat(grand.parentId()).isEqualTo(child.id()); assertThat(grand.rootId()).isEqualTo(root.id());
        assertThat(comments.page(root.id(),"oldest",20,null).items()).extracting(Comment::id).containsExactly(child.id());
        assertThatThrownBy(() -> comments.delete(a.id(),child.id())).isInstanceOf(ApiException.class);
        comments.delete(b.id(),child.id()); comments.delete(b.id(),child.id());
        assertThat(comments.get(child.id()).content()).isNull();
        assertThat(comments.get(child.id()).deletedAt()).isNotNull();
        assertThat(comments.get(grand.id()).content()).isEqualTo("C");
        assertThatThrownBy(() -> create(a.id(),child.id(),"blocked")).isInstanceOf(ApiException.class).hasMessageContaining("已删除");
        assertThat(create(a.id(),grand.id(),"D").rootId()).isEqualTo(root.id());
        assertThatThrownBy(() -> create(a.id(),UUID.randomUUID(),"missing")).isInstanceOf(ApiException.class);
        comments.delete(a.id(),root.id());
        assertThat(comments.page(null,"newest",100,null).items()).extracting(Comment::id).contains(root.id());
        assertThatThrownBy(() -> jdbc.update("UPDATE comment SET parent_id=? WHERE id=?",grand.id(),child.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test @Order(5) void idempotencyReplayAfterParentAndSelfDeleteConflictAndRollback() {
        var user=register(); var root=create(user.id(),null,"root");
        var data=new CommentService.Create("sensitive original",root.id(),UUID.randomUUID());
        var result=comments.create(user.id(),data);
        comments.delete(user.id(),root.id());
        assertThat(comments.create(user.id(),data).id()).isEqualTo(result.id());
        comments.delete(user.id(),result.id());
        assertThat(comments.create(user.id(),data).content()).isNull();
        assertThatThrownBy(() -> comments.create(user.id(),new CommentService.Create("changed",root.id(),data.requestId()))).isInstanceOf(ApiException.class);
        var rollback=new CommentService.Create("rollback",null,UUID.randomUUID());
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> {
            comments.create(user.id(),rollback); throw new IllegalStateException("simulate failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creation_request WHERE request_id=?",Long.class,rollback.requestId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE author_id=? AND content='rollback'",Long.class,user.id())).isZero();
        assertThat(comments.create(user.id(),rollback)).isNotNull();
    }
    @Test @Order(6) void concurrentDuplicateCreatesExactlyOneRow() throws Exception {
        var user=register(); var data=new CommentService.Create("once",null,UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<UUID>> jobs=new ArrayList<>();
            for(int i=0;i<8;i++) jobs.add(() -> comments.create(user.id(),data).id());
            Set<UUID> ids=new HashSet<>();
            for(var result:pool.invokeAll(jobs)) ids.add(result.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment WHERE author_id=?",Long.class,user.id())).isEqualTo(1);
    }
    @Test @Order(7) void deleteCommitsBeforeWaitingReplySoReplyFails() throws Exception {
        var user=register(); var parent=create(user.id(),null,"parent");
        try(var conn=PG.getPostgresDatabase().getConnection();var pool=Executors.newSingleThreadExecutor()) {
            conn.setAutoCommit(false);
            try(var stmt=conn.prepareStatement("SELECT id FROM comment WHERE id=? FOR UPDATE")) { stmt.setObject(1,parent.id()); stmt.executeQuery().close(); }
            var started=new CountDownLatch(1);
            var future=pool.submit(() -> { started.countDown(); return create(user.id(),parent.id(),"late"); });
            started.await();
            try(var stmt=conn.prepareStatement("UPDATE comment SET content=NULL,deleted_at=now() WHERE id=?")) { stmt.setObject(1,parent.id()); stmt.executeUpdate(); }
            conn.commit();
            assertThatThrownBy(() -> future.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThat(comments.page(parent.id(),"oldest",20,null).items()).isEmpty();
        }
    }
    @Test @Order(8) void replyCommitsBeforeDeleteSoChildIsPreserved() throws Exception {
        var user=register(); var parent=create(user.id(),null,"parent");
        var inserted=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reply=pool.submit(() -> new TransactionTemplate(txManager).execute(tx -> {
                Comment child=create(user.id(),parent.id(),"before");
                inserted.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout"); } catch(InterruptedException e) { throw new RuntimeException(e); }
                return child;
            }));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=pool.submit(() -> comments.delete(user.id(),parent.id()));
            release.countDown();
            assertThat(reply.get(10,TimeUnit.SECONDS).parentId()).isEqualTo(parent.id());
            assertThat(deletion.get(10,TimeUnit.SECONDS).content()).isNull();
            assertThat(comments.page(parent.id(),"oldest",20,null).items()).hasSize(1);
        }
    }
    @Test @Order(9) void stableTimeTiesPaginationScopeAndSqlInjection() throws Exception {
        var user=register(); var root=create(user.id(),null,"paging");
        for(int i=0;i<53;i++) jdbc.update("INSERT INTO comment(id,author_id,parent_id,root_id,content,created_at) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID(),user.id(),root.id(),root.id(),"same time",OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        var ids=new ArrayList<UUID>(); String cursor=null;
        do { var page=comments.page(root.id(),"oldest",7,cursor); ids.addAll(page.items().stream().map(Comment::id).toList()); cursor=page.nextCursor(); } while(cursor!=null);
        assertThat(ids).hasSize(53).doesNotHaveDuplicates();
        assertThat(ids).isEqualTo(jdbc.queryForList("SELECT id FROM comment WHERE parent_id=? ORDER BY created_at,id",UUID.class,root.id()));
        String scoped=comments.page(root.id(),"oldest",2,null).nextCursor();
        assertThatThrownBy(() -> comments.page(null,"oldest",2,scoped)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> comments.page(root.id(),"oldest",101,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> comments.page(null,"newest",20,"invalid")).isInstanceOf(ApiException.class);
        for(int i=0;i<11;i++) {
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO comment(id,author_id,root_id,content,created_at) VALUES(?,?,?,?,?)",
                id,user.id(),id,"root tie",OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        }
        for(String sort:List.of("oldest","newest")) {
            List<UUID> got=new ArrayList<>(); cursor=null;
            do { var page=comments.page(null,sort,7,cursor); got.addAll(page.items().stream().map(Comment::id).toList()); cursor=page.nextCursor(); } while(cursor!=null);
            assertThat(got).isEqualTo(jdbc.queryForList("SELECT id FROM comment WHERE parent_id IS NULL ORDER BY created_at "+(sort.equals("newest")?"DESC":"ASC")+",id "+(sort.equals("newest")?"DESC":"ASC"),UUID.class));
        }
        mvc.perform(get("/api/comments").param("sort","newest; DROP TABLE comment")).andExpect(status().isBadRequest());
        var injected=create(user.id(),null,"<script>alert('x')</script>'; DROP TABLE comment;--");
        mvc.perform(get("/api/comments/"+injected.id())).andExpect(status().isOk()).andExpect(jsonPath("$.content").value(injected.content())).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
    }
    @Test @Order(10) void lockTimeoutIsBoundedAndDoesNotLeavePartialWrite() throws Exception {
        var user=register(); var root=create(user.id(),null,"locked");
        var session=login(user);
        try(var conn=PG.getPostgresDatabase().getConnection()) {
            conn.setAutoCommit(false);
            try(var stmt=conn.prepareStatement("SELECT id FROM comment WHERE id=? FOR UPDATE")) { stmt.setObject(1,root.id()); stmt.executeQuery().close(); }
            long start=System.nanoTime();
            mvc.perform(post("/api/comments").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(new CommentService.Create("waiting",root.id(),UUID.randomUUID()))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))));
            assertThat(Duration.ofNanos(System.nanoTime()-start).toSeconds()).isLessThan(7);
            conn.rollback();
        }
        assertThat(comments.page(root.id(),"oldest",20,null).items()).isEmpty();
    }
    @Test @Order(11) void imageUploadPublishesReadsAndCleansWithComment() throws Exception {
        var user=register(); var session=login(user); UUID batch=UUID.randomUUID();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var upload=mvc.perform(multipart("/api/uploads/images").file(new MockMultipartFile("files","hello.png","image/png",png))
                .param("batchId",batch.toString()).session(session).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].mediaType").value("image/png"))
            .andExpect(jsonPath("$[0].byteSize").value(org.hamcrest.Matchers.greaterThan(0))).andReturn();
        UUID attachment=UUID.fromString(json.readTree(upload.getResponse().getContentAsString()).get(0).get("id").asText());
        var row=comments.create(user.id(),new CommentService.Create("带图片的留言",null,UUID.randomUUID(),List.of(attachment)));
        assertThat(row.attachments()).hasSize(1).first().extracting(Attachment::id).isEqualTo(attachment);
        var reply=comments.create(user.id(),new CommentService.Create("深层回复",row.id(),UUID.randomUUID()));
        mvc.perform(get("/api/comments/"+reply.id()+"/context")).andExpect(status().isOk())
            .andExpect(jsonPath("$.target.id").value(reply.id().toString()))
            .andExpect(jsonPath("$.ancestors[0].id").value(row.id().toString()))
            .andExpect(jsonPath("$.parent.id").value(row.id().toString()))
            .andExpect(jsonPath("$.parent.replyCount").value(1))
            .andExpect(jsonPath("$.parent.lastReplyAt").isNotEmpty());
        mvc.perform(get("/api/attachments/"+attachment)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        var deleted=comments.delete(user.id(),row.id());
        assertThat(deleted.attachments()).isEmpty();
        mvc.perform(get("/api/attachments/"+attachment)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT comment_id FROM comment_attachment WHERE id=?",UUID.class,attachment)).isEqualTo(row.id());
    }
    @Test @Order(12) void deepContextContinuesAncestorsAndRecentRepliesArePaged() throws Exception {
        var user=register(); var root=create(user.id(),null,"root");
        var one=create(user.id(),root.id(),"one");
        var two=create(user.id(),one.id(),"two");
        var three=create(user.id(),two.id(),"three");
        var first=mvc.perform(get("/api/comments/"+three.id()+"/context").param("ancestorLimit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ancestors").isArray())
            .andExpect(jsonPath("$.ancestors.length()").value(1))
            .andExpect(jsonPath("$.hasMoreAncestors").value(true)).andReturn();
        String ancestorCursor=json.readTree(first.getResponse().getContentAsString()).get("ancestorCursor").asText();
        mvc.perform(get("/api/comments/"+three.id()+"/context").param("ancestorLimit","1").param("ancestorCursor",ancestorCursor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ancestors.length()").value(1))
            .andExpect(jsonPath("$.ancestors[0].content").value("root"));
        create(user.id(),root.id(),"recent-1"); create(user.id(),root.id(),"recent-2");
        var page=mvc.perform(get("/api/comments/recent-replies").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
        String cursor=json.readTree(page.getResponse().getContentAsString()).get("nextCursor").asText();
        mvc.perform(get("/api/comments/recent-replies").param("limit","1").param("cursor",cursor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test @Order(13) void authorCanEditWithinWindowAndHistoryProtectsOldContent() throws Exception {
        var user=register(); var other=register(); var row=create(user.id(),null,"original");
        var session=login(user);
        mvc.perform(put("/api/comments/"+row.id()).session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content","edited","revision",0))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("edited"))
            .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(get("/api/comments/"+row.id()+"/revisions").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].content").value("original"));
        mvc.perform(put("/api/comments/"+row.id()).session(login(other)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content","intrusion","revision",1))))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/comments/"+row.id()).session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content","stale","revision",0))))
            .andExpect(status().isConflict());
        jdbc.update("UPDATE comment SET edit_deadline=clock_timestamp()-interval '1 second' WHERE id=?",row.id());
        mvc.perform(put("/api/comments/"+row.id()).session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content","expired","revision",1))))
            .andExpect(status().isConflict());
    }
    @Test @Order(14) void replyCreatesDeduplicatedNotificationAndReadState() throws Exception {
        var owner=register(); var replier=register(); var root=create(owner.id(),null,"question");
        create(replier.id(),root.id(),"answer");
        var session=login(owner);
        var first=mvc.perform(get("/api/notifications").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].type").value("reply"))
            .andExpect(jsonPath("$.items[0].readAt").isEmpty()).andReturn();
        String notification=json.readTree(first.getResponse().getContentAsString()).get("items").get(0).get("id").asText();
        create(replier.id(),root.id(),"answer duplicate check");
        mvc.perform(get("/api/notifications").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(post("/api/notifications/"+notification+"/read").session(session).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[1].readAt").isNotEmpty());
    }
    @Test @Order(15) void searchPaginatesVisibleTextAndExcludesDeletedOrHidden() throws Exception {
        var user=register(); var first=create(user.id(),null,"alpha one"); var second=create(user.id(),null,"alpha two"); var third=create(user.id(),null,"alpha three");
        var deleted=create(user.id(),null,"alpha deleted"); comments.delete(user.id(),deleted.id());
        jdbc.update("UPDATE comment SET moderation_state='hidden' WHERE id=?",second.id());
        var page=mvc.perform(get("/api/comments/search").param("q","alpha").param("limit","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(third.id().toString()))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
        String cursor=json.readTree(page.getResponse().getContentAsString()).get("nextCursor").asText();
        mvc.perform(get("/api/comments/search").param("q","different").param("limit","1").param("cursor",cursor))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/comments/"+first.id()+"/stats")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalReplies").value(0));
    }
    @Test @Order(16) void realPostgresScaleDeepChainAndIndexPlans() throws Exception {
        var user=register(); var wide=create(user.id(),null,"wide"); var deep=create(user.id(),null,"deep");
        jdbc.update("""
            INSERT INTO comment(id,author_id,parent_id,root_id,content)
            SELECT gen_random_uuid(),?, ?, ?, 'wide' FROM generate_series(1,50000)
            """,user.id(),wide.id(),wide.id());
        jdbc.update("""
            INSERT INTO comment(id,author_id,root_id,content)
            SELECT id,?,id,'scale' FROM (SELECT gen_random_uuid() id FROM generate_series(1,100000)) q
            """,user.id());
        jdbc.execute("DO $$ DECLARE previous uuid := '"+deep.id()+"'; current uuid; BEGIN FOR i IN 1..10000 LOOP current := gen_random_uuid(); INSERT INTO comment(id,author_id,parent_id,root_id,content) VALUES(current,'"+user.id()+"',previous,'"+deep.id()+"','deep'); previous := current; END LOOP; END $$");
        UUID leaf=jdbc.queryForObject("SELECT c.id FROM comment c WHERE root_id=? AND NOT EXISTS(SELECT 1 FROM comment ch WHERE ch.parent_id=c.id)",UUID.class,deep.id());
        assertThat(create(user.id(),leaf,"depth 10001").rootId()).isEqualTo(deep.id());
        jdbc.execute("ANALYZE comment");
        QueryCounter.COUNT.set(0);
        assertThat(comments.page(wide.id(),"oldest",20,null).items()).hasSize(20);
        assertThat(QueryCounter.COUNT.get()).isEqualTo(3);
        QueryCounter.COUNT.set(0);
        assertThat(comments.page(null,"newest",20,null).items()).hasSize(20);
        assertThat(QueryCounter.COUNT.get()).isEqualTo(2);
        String rootPlan=String.join("\n",jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) "+CommentMapper.FIELDS+" WHERE c.parent_id IS NULL ORDER BY c.created_at DESC,c.id DESC LIMIT 21",String.class));
        String childPlan=String.join("\n",jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) "+CommentMapper.FIELDS+" WHERE c.parent_id='"+wide.id()+"' ORDER BY c.created_at,c.id LIMIT 21",String.class));
        assertThat(rootPlan).contains("comment_roots_time");
        assertThat(childPlan).contains("comment_children_time");
        assertThat(rootPlan).doesNotContain("Seq Scan on comment");
        assertThat(childPlan).doesNotContain("Seq Scan on comment");
        List<Long> nanos=new ArrayList<>();
        for(int i=0;i<120;i++) { long start=System.nanoTime(); comments.page(i%2==0?null:wide.id(),i%2==0?"newest":"oldest",20,null); if(i>=20)nanos.add(System.nanoTime()-start); }
        Collections.sort(nanos);
        String evidence="PostgreSQL: "+jdbc.queryForObject("SELECT version()",String.class)
            +"\nRows: "+jdbc.queryForObject("SELECT count(*) FROM comment",Long.class)
            +"\nWide children: 50000; deep chain: 10001; root fixtures: 100000"
            +"\nSingle-thread service page, 20 warmup + 100 measured; p95 ms="+nanos.get(94)/1_000_000.0
            +"\nNot a 50-reader/10-writer HTTP load acceptance.\n\nROOT:\n"+rootPlan+"\n\nCHILD:\n"+childPlan+"\n";
        Files.writeString(Path.of("target/scale-evidence.txt"),evidence);
    }
}
