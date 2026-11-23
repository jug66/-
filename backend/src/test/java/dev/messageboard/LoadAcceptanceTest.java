package dev.messageboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.messageboard.auth.AuthController;
import dev.messageboard.comment.CommentService;
import dev.messageboard.comment.CommentMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 显式开启：mvn -Dtest=LoadAcceptanceTest -Dboard.load=true test
 * 50 个读取客户端 + 10 个写入客户端，各客户端每 200ms 至多发起一请求。
 * 默认预热 300s、测量 60s；使用隔离的真实 PostgreSQL 和真实 HTTP。
 */
@EnabledIfSystemProperty(named="board.load",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "board.rate.read=1000000","board.rate.auth=10000","board.rate.write=1000000",
    "logging.level.dev.messageboard.common.RequestGuard=WARN"
})
class LoadAcceptanceTest {
    static final EmbeddedPostgres PG;
    static { try { PG=EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1")
        .setServerConfig("fsync","on").setServerConfig("synchronous_commit","on").start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> PG.getJdbcUrl("postgres","postgres"));
        r.add("spring.datasource.username",() -> "postgres"); r.add("spring.datasource.password",() -> "");
    }
    @AfterAll static void close() throws Exception { PG.close(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthController auth;
    @Autowired CommentService comments;
    @Autowired ObjectMapper json;
    record Client(HttpClient http,String csrf) {}
    URI url(String path) { return URI.create("http://127.0.0.1:"+port+"/api"+path); }
    Client login(String name,String password) throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        var http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        var token=json.readTree(http.send(HttpRequest.newBuilder(url("/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
        var response=http.send(HttpRequest.newBuilder(url("/auth/login")).header("X-CSRF-TOKEN",token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("loginName",name,"password",password)))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return new Client(http,token);
    }
    @Test void mixedHttpWorkload() throws Exception {
        String password="local load verification password";
        var user=auth.register(new AuthController.Register("load_user","压测用户",password,password));
        var root=comments.create(user.id(),new CommentService.Create("wide",null,UUID.randomUUID()));
        jdbc.update("INSERT INTO comment(id,author_id,parent_id,root_id,content) SELECT gen_random_uuid(),?,?,?,'load' FROM generate_series(1,50000)",user.id(),root.id(),root.id());
        jdbc.update("INSERT INTO comment(id,author_id,root_id,content) SELECT id,?,id,'load root' FROM (SELECT gen_random_uuid() id FROM generate_series(1,100000)) q",user.id());
        jdbc.execute("ANALYZE comment");
        String skewPlan=String.join("\n",jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) "+CommentMapper.FIELDS+" WHERE c.parent_id IS NULL ORDER BY c.created_at DESC,c.id DESC LIMIT 21",String.class));
        Files.writeString(Path.of("target/skew-plan.txt"),skewPlan);
        assertThat(skewPlan).doesNotContain("Seq Scan on comment");
        var reader=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<Client> writers=new ArrayList<>();
        for(int i=0;i<10;i++) writers.add(login(user.loginName(),password));
        int warmup=Integer.getInteger("board.load.warmup",300);
        int measure=Integer.getInteger("board.load.measure",60);
        long start=System.nanoTime(), measureAt=start+Duration.ofSeconds(warmup).toNanos(), end=measureAt+Duration.ofSeconds(measure).toNanos();
        Queue<Long> readTimes=new ConcurrentLinkedQueue<>(),writeTimes=new ConcurrentLinkedQueue<>();
        AtomicLong errors=new AtomicLong(),warmupErrors=new AtomicLong();
        var statuses=new ConcurrentHashMap<Integer,AtomicLong>();
        try(var pool=Executors.newFixedThreadPool(60)) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int n=0;n<60;n++) {
                final int worker=n;
                tasks.add(pool.submit(() -> {
                    while(System.nanoTime()<end) {
                        long t=System.nanoTime(); boolean measured=t>=measureAt;
                        try {
                            HttpRequest request;
                            HttpClient http;
                            if(worker<50) {
                                http=reader;
                                String path=worker%2==0?"/comments?limit=20&sort=newest":"/comments/"+root.id()+"/replies?limit=20";
                                request=HttpRequest.newBuilder(url(path)).timeout(Duration.ofSeconds(10)).GET().build();
                            } else {
                                Client client=writers.get(worker-50); http=client.http();
                                request=HttpRequest.newBuilder(url("/comments")).timeout(Duration.ofSeconds(10))
                                    .header("X-CSRF-TOKEN",client.csrf()).header("Content-Type","application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new CommentService.Create("load write",worker%2==0?root.id():null,UUID.randomUUID())))).build();
                            }
                            int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                            if(measured) statuses.computeIfAbsent(status,k -> new AtomicLong()).incrementAndGet();
                            if(status!=(worker<50?200:201)) { if(measured)errors.incrementAndGet();else warmupErrors.incrementAndGet(); }
                            else if(measured) (worker<50?readTimes:writeTimes).add(System.nanoTime()-t);
                        } catch(Exception ex) { if(measured)errors.incrementAndGet();else warmupErrors.incrementAndGet(); }
                        long remain=200_000_000-(System.nanoTime()-t);
                        if(remain>0) java.util.concurrent.locks.LockSupport.parkNanos(remain);
                    }
                }));
            }
            for(var task:tasks) task.get(warmup+measure+30L,TimeUnit.SECONDS);
        }
        double readP95=p95(readTimes),writeP95=p95(writeTimes);
        String result="Date: "+java.time.Instant.now()+"\nJava: "+System.getProperty("java.version")
            +"\nOS: "+System.getProperty("os.name")+" "+System.getProperty("os.arch")+"\nLogical processors: "+Runtime.getRuntime().availableProcessors()
            +"\nPG: "+jdbc.queryForObject("SELECT version()",String.class)
            +"\nfsync="+jdbc.queryForObject("SHOW fsync",String.class)+"; synchronous_commit="+jdbc.queryForObject("SHOW synchronous_commit",String.class)
            +"\nTransport: loopback HTTP; Hikari pool=12; rate limits read/write=1000000/min/IP"
            +"\nInitial data: 100000 roots + 50000 same-parent children"
            +"\nClients: 50 readers + 10 writers; max 5 requests/second/client"
            +"\nWarmup seconds: "+warmup+"\nMeasured seconds: "+measure+"\nWarmup errors: "+warmupErrors.get()
            +"\nRead successes: "+readTimes.size()+"\nWrite successes: "+writeTimes.size()+"\nMeasured failures: "+errors.get()
            +"\nStatus counts: "+statuses+"\nRead p95 ms: "+readP95+"\nWrite p95 ms: "+writeP95
            +"\nRead throughput/s: "+readTimes.size()/(double)measure+"\nWrite throughput/s: "+writeTimes.size()/(double)measure+"\n";
        Files.writeString(Path.of("target/load-evidence.txt"),result);
        System.out.println(result);
        assertThat(errors.get()).isZero(); assertThat(warmupErrors.get()).isZero();
        assertThat(readTimes.size()).isGreaterThan(1000);
        assertThat(writeTimes.size()).isGreaterThan(100);
        assertThat(readP95).isLessThan(300);
        reader.close(); for(var client:writers)client.http().close();
    }
    double p95(Queue<Long> samples) {
        var sorted=samples.stream().sorted().toList();
        return sorted.isEmpty()?Double.POSITIVE_INFINITY:sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1_000_000.0;
    }
}
