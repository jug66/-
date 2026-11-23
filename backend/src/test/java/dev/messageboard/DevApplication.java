package dev.messageboard;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;

/** 仅存在于测试 classpath 的本地辅助入口；生产 jar 不打包 PostgreSQL。 */
public class DevApplication {
    public static void main(String[] args) throws Exception {
        Path data=Path.of("../.runtime/postgres").toAbsolutePath().normalize();
        Files.createDirectories(data);
        Path stop=data.getParent().resolve("stop-backend");
        Path pid=data.getParent().resolve("backend.pid");
        var pg=EmbeddedPostgres.builder().setDataDirectory(data.toFile()).setCleanDataDirectory(false)
            .setPort(55432).setServerConfig("listen_addresses","127.0.0.1")
            .setServerConfig("fsync","on").setServerConfig("synchronous_commit","on").start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { pg.close(); Files.deleteIfExists(pid); } catch(Exception ignored) {}
        }));
        Files.deleteIfExists(stop);
        try(var app=SpringApplication.run(Application.class,
                "--spring.datasource.url="+pg.getJdbcUrl("postgres","postgres"),
                "--spring.datasource.username=postgres","--spring.datasource.password=")) {
            Files.writeString(pid,Long.toString(ProcessHandle.current().pid()));
            while(app.isActive() && !Files.exists(stop)) Thread.sleep(500);
        } finally {
            pg.close();
            Files.deleteIfExists(pid);
            Files.deleteIfExists(stop);
        }
    }
}
