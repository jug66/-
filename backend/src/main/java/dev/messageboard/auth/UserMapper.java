package dev.messageboard.auth;

import org.apache.ibatis.annotations.*;
import java.util.UUID;
import java.time.OffsetDateTime;

@Mapper
public interface UserMapper {
    @Select("SELECT id, login_name, display_name, password_hash, email, email_verified_at FROM board_user WHERE login_name=#{name}")
    User find(String name);
    @Select("SELECT id, login_name, display_name, password_hash, email, email_verified_at FROM board_user WHERE lower(email)=lower(#{email})")
    User findByEmail(String email);
    @Select("SELECT id, login_name, display_name, password_hash, email, email_verified_at FROM board_user WHERE id=#{id}")
    User findById(UUID id);
    @Select("SELECT blocked_at IS NOT NULL FROM board_user WHERE id=#{id}")
    Boolean blocked(UUID id);
    @Insert("INSERT INTO board_user(id,login_name,display_name,password_hash,email) VALUES(#{id},#{loginName},#{displayName},#{passwordHash},#{email})")
    void insert(User user);
    @Update("UPDATE board_user SET email_verified_at=clock_timestamp() WHERE id=#{id} AND email IS NOT NULL AND email_verified_at IS NULL")
    int markEmailVerified(UUID id);
    @Update("UPDATE board_user SET password_hash=#{passwordHash} WHERE id=#{id}")
    int updatePassword(@Param("id") UUID id,@Param("passwordHash") String passwordHash);
    @Insert("INSERT INTO account_token(id,user_id,purpose,token_hash,expires_at) VALUES(#{id},#{userId},#{purpose},#{tokenHash},#{expiresAt})")
    void insertToken(AccountToken token);
    @Select("SELECT id,user_id,purpose,token_hash,expires_at,used_at FROM account_token WHERE token_hash=#{tokenHash} AND purpose=#{purpose}")
    AccountToken findToken(@Param("tokenHash") String tokenHash,@Param("purpose") String purpose);
    @Update("UPDATE account_token SET used_at=clock_timestamp() WHERE id=#{id} AND used_at IS NULL AND expires_at>clock_timestamp()")
    int consumeToken(UUID id);
    record AccountToken(UUID id, UUID userId, String purpose, String tokenHash, OffsetDateTime expiresAt, OffsetDateTime usedAt) {}
}
